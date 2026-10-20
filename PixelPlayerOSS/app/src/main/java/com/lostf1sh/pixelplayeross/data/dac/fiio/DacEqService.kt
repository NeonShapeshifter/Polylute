package com.lostf1sh.pixelplayeross.data.dac.fiio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The attached DAC's backup, keyed by what it was filed under so one DAC's presets are never shown for another. */
data class DacEqBackupState(val backupKey: String?, val backup: DacEqBackup?)

/** Whether this build may write the attached DAC's EQ at all, and why (or why not). */
enum class DacEqWriteAvailability {
    /** The model has no slot of ours: read-only whatever else is on. */
    READ_ONLY_MODEL,

    /** The profile is not verified on this app's hardware and the debug override is off: read-only until a reviewer's run passes. */
    NEEDS_VERIFICATION,

    /** The profile is verified. */
    VERIFIED,

    /** Only the debug override lets the gate through (a debug build, a reviewer verifying on this phone). */
    DEBUG_OVERRIDE,
    ;

    val canWrite: Boolean get() = this == VERIFIED || this == DEBUG_OVERRIDE
}

/**
 * B30: the DAC's EQ as the rest of the app (the Resonance screen's view model, the reviewer's debug commands) uses it: what is
 * attached, whether it can be written, the writer, and a read on demand. Everything it hands out is the controller's own
 * [DacEqHidAccess] (one HID session at a time) and the one gate.
 *
 * Plain Kotlin like [DacEqController]; the Hilt module builds it.
 */
class DacEqService(
    val controller: DacEqController,
    val backups: DacEqBackupStore,
    val writeSwitch: DacEqWriteSwitch,
    private val elapsedMs: () -> Long,
    private val wallClockMs: () -> Long,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
    /** B31 P1.2: the reviewer's answer on this phone to "what does a positive global gain do"; null where there is none (the tests). */
    val senseSwitch: DacEqPreGainSenseSwitch? = null,
    /** B31 P1.3: whether music is playing, from the player's own state (readable from any thread). The real one is wired in `DacEqModule`. */
    private val playbackActive: () -> Boolean = { false },
    /** Where the backup file is read: never the main thread. The tests hand in theirs. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _backup = MutableStateFlow(DacEqBackupState(null, null))

    /**
     * The backup of the attached DAC as it is on disk, kept here so a screen never reads the file on the main thread (B31 P1.4: the
     * Resonance screen lists the DAC's presets from it). It follows what the writer changes and [refreshBackup].
     */
    val backupState: StateFlow<DacEqBackupState> = _backup.asStateFlow()

    /** What a positive global gain does to the level on [att]'s model: the reviewer's answer on this phone, else the profile row's. */
    fun sense(att: AttachedFiio?): PreGainSense =
        att?.let { senseSwitch?.effective(it.profile) ?: it.profile.preGainSense } ?: PreGainSense.UNCONFIRMED

    /** Whether music is playing (the steps that select a preset nobody has vetted run only while it is not). */
    fun isPlaying(): Boolean = playbackActive()

    /** Loads [att]'s backup from disk (off the main thread) and publishes it on [backupState]. */
    suspend fun refreshBackup(att: AttachedFiio?) {
        val loaded = withContext(ioDispatcher) { att?.let { backups.load(it.backupKey) } }
        _backup.value = if (att == null) DacEqBackupState(null, null) else DacEqBackupState(att.backupKey, loaded)
    }

    private fun backupChanged(att: AttachedFiio, backup: DacEqBackup) {
        _backup.value = DacEqBackupState(att.backupKey, backup)
    }

    /** A writer through the one gate; [options] and [sink] are what a debug command may vary (the check byte, the save, where the log goes). */
    fun writer(options: FiioWriteOptions = FiioWriteOptions(), sink: (String) -> Unit = log): DacEqWriter = DacEqWriter(
        access = controller.access,
        gate = DacEqWriteGate(options),
        backups = backups,
        overrideAllowsWrites = writeSwitch::isAllowed,
        elapsedMs = elapsedMs,
        wallClockMs = wallClockMs,
        publish = controller::publish,
        pause = pause,
        log = sink,
        invalidate = controller::invalidate,
        playbackActive = playbackActive,
        senseOf = { profile -> senseSwitch?.effective(profile) ?: profile.preGainSense },
        onBackupChanged = ::backupChanged,
    )

    /** The writer the screen uses. */
    val screenWriter: DacEqWriter by lazy { writer() }

    /** The DAC the EQ is about right now: the one Direct opened (else the only one), as the controller sees it. */
    fun attached(): AttachedFiio? = controller.readout.value.attachedOrNull

    fun availability(att: AttachedFiio? = attached()): DacEqWriteAvailability = when {
        att == null || !att.profile.canBeWritten -> DacEqWriteAvailability.READ_ONLY_MODEL
        att.profile.verified -> DacEqWriteAvailability.VERIFIED
        writeSwitch.isAllowed() -> DacEqWriteAvailability.DEBUG_OVERRIDE
        else -> DacEqWriteAvailability.NEEDS_VERIFICATION
    }

    /** Reads the whole state now (whatever the route), logs it through [sink], and hands it to the labels. Null when it could not be read at all. */
    suspend fun read(att: AttachedFiio, sink: (String) -> Unit = log): DacEqState? {
        val use = try {
            controller.access.withSession(att, sink) { session -> FiioEqReader.read(session).also { DacEqStateFormat.lines(it).forEach(sink) } }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // B30-fix1 H3: a read that failed is not "the same as before": the old reading is dropped, so nothing keeps saying Purity.
            sink("DAC_HID|ERROR read: ${error.javaClass.simpleName}: ${error.message}")
            controller.invalidate(att, "the read failed: ${error.javaClass.simpleName}: ${error.message}")
            return null
        }
        return when (use) {
            is HidUse.Ran -> use.value.also { controller.publish(att, it) }
            is HidUse.Unavailable -> null.also {
                sink("DAC_HID|unavailable: ${use.why}")
                controller.invalidate(att, use.why)
            }
        }
    }
}
