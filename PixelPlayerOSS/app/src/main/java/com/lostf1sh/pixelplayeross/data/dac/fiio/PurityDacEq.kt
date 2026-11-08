package com.lostf1sh.pixelplayeross.data.dac.fiio

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * B61 item 3 (owner 11-07: "the point of Purity is that it locks everything"). **Purity closes the DAC's own EQ.**
 *
 * Before, Purity froze the phone's processing and left the Melody's EQ on, and said so ("On — but the DAC's EQ is shaping the sound"). Now turning Purity on selects the model's Close EQ
 * preset (`F0` on the Melody) through the one gate and the one writer (`WriteKind.CLOSE_EQ`: a pointer move, nothing is written to a slot, band or save), and turning it off puts back
 * exactly what was selected. What is put back is remembered in [PurityDacEqMemoryStore]: **a file beside that DAC's EQ backup** (`dac-eq-backups/purity-<key>.json`, the backup's own folder
 * and key), because it must survive an app restart and a re-plug of the DAC, it belongs to one DAC and not to the app's preferences, and the backup folder is where this app already keeps the
 * DAC's EQ state that outlives a session.
 *
 * Put back = the remembered **slot of the app** selected again, with the curve it held read back equal (`APPLY_CURVE` of the remembered bands: against a slot that still holds them it is a plan of
 * zero steps, which selects the slot and compares; no band is written). Anything that is not the app's slot is never selected: the gate refuses it ([GateRefusal.NOT_USER_SLOT]) and B60's
 * `backTo` rule holds for the pointer. If the DAC cannot be reached at that moment, the memory stays and [reconcile] tries again at the next attach.
 */
@Serializable
data class PurityDacEqMemory(
    /** The preset the DAC had selected when Purity closed its EQ. */
    val slotId: Int,
    /** The curve that slot held then, when it was the app's own slot (empty otherwise: what is not the app's is not put back). */
    val bands: List<DacEqBand> = emptyList(),
    val preGainTenthsDb: Int = 0,
    val takenAtEpochMs: Long = 0L,
)

/** The memory, one file per DAC next to its EQ backup. A file that cannot be read is no memory. */
class PurityDacEqMemoryStore(private val directory: File, private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }) {
    private fun file(key: String) = File(directory, "purity-${key.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    fun load(key: String): PurityDacEqMemory? = runCatching {
        file(key).takeIf { it.isFile }?.let { json.decodeFromString<PurityDacEqMemory>(it.readText()) }
    }.getOrNull()

    /** Writes and reads back; false when it could not be stored exactly. */
    fun save(key: String, memory: PurityDacEqMemory): Boolean = runCatching {
        directory.mkdirs()
        file(key).writeText(json.encodeToString(PurityDacEqMemory.serializer(), memory))
        load(key) == memory
    }.getOrDefault(false)

    fun clear(key: String) {
        runCatching { file(key).delete() }
    }
}

/** What the Audio page says in one short line about Purity and the DAC's EQ, when something did not go as asked. */
enum class PurityDacEqNote {
    /** Purity was turned off but the DAC could not be reached: its EQ comes back the next time it is connected. */
    REOPEN_WHEN_CONNECTED,

    /** What the DAC had selected is not a slot Polylute writes: its EQ stays closed. */
    NOT_OURS_TO_REOPEN,

    /** The DAC refused or lost the put-back: its EQ stays closed. */
    REOPEN_FAILED,

    /** Purity is on but the DAC's EQ could not be closed. */
    CLOSE_FAILED,
}

/** The note the Audio page reads; written by [PurityDacEq]. A singleton so that the writer of the note and the screen share it. */
@Singleton
class PurityDacEqNotes @Inject constructor() {
    private val _note = MutableStateFlow<PurityDacEqNote?>(null)
    val note: StateFlow<PurityDacEqNote?> = _note.asStateFlow()
    fun set(note: PurityDacEqNote?) {
        _note.value = note
    }
}

enum class PurityDacEqOutcome {
    /** Nothing to do (no DAC EQ this build can write, or nothing was closed by Purity). */
    NOTHING,

    /** The EQ is closed now (Purity closed it, and what was selected is remembered). */
    CLOSED,

    /** The EQ was closed already: nothing sent. */
    ALREADY_CLOSED,

    /** The remembered slot is selected again and its curve read back equal. */
    REOPENED,

    /** What was selected is not the app's slot: never selected; the EQ stays closed. */
    NOT_OURS,

    /** The DAC could not be reached; the memory is kept for the next attach. */
    UNREACHABLE,

    /** The gate refused, or the DAC did not hold it. */
    FAILED,
}

class PurityDacEq(
    private val service: DacEqService,
    private val memories: PurityDacEqMemoryStore,
    private val notes: PurityDacEqNotes = PurityDacEqNotes(),
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    /**
     * What to do for [purityOn] with [att] attached (null: none), on every change of either and at every attach. [lastKey] is the backup key of the DAC that was attached last, so that
     * "Purity went off while the DAC was away" can still say that something waits for the next attach.
     */
    suspend fun reconcile(purityOn: Boolean, att: AttachedFiio?, lastKey: String? = null): PurityDacEqOutcome = mutex.withLock {
        if (att == null) {
            if (!purityOn && lastKey != null && memories.load(lastKey) != null) notes.set(PurityDacEqNote.REOPEN_WHEN_CONNECTED)
            return@withLock PurityDacEqOutcome.NOTHING
        }
        if (purityOn) engage(att) else release(att)
    }

    /** Purity is on and [att] is attached: close the DAC's EQ, remembering what was selected. */
    private suspend fun engage(att: AttachedFiio): PurityDacEqOutcome {
        val close = att.profile.disabledPresetId
        if (close == null || !att.profile.canBeWritten) return PurityDacEqOutcome.NOTHING
        if (!service.availability(att).canWrite) {
            // the listener has not allowed writes to this DAC: Purity cannot close its EQ, and the page says so (the DAC's EQ status keeps its honest word)
            notes.set(PurityDacEqNote.CLOSE_FAILED)
            log("DAC_HID|PURITY on: the DAC's EQ is not closed, writes to ${att.displayName} are not allowed (nothing sent)")
            return PurityDacEqOutcome.FAILED
        }
        val state = service.read(att) ?: return PurityDacEqOutcome.UNREACHABLE.also { log("DAC_HID|PURITY on: ${att.displayName} could not be read; Purity tries again at the next attach") }
        val preset = state.activePreset.valueOrNull ?: return PurityDacEqOutcome.UNREACHABLE
        notes.set(null)
        if (preset == close) {
            log("DAC_HID|PURITY on: the DAC's EQ is closed already (0x%02X): nothing sent".format(close))
            return PurityDacEqOutcome.ALREADY_CLOSED
        }
        // what is on goes into the memory BEFORE anything is sent, so an unplug between the two cannot lose it
        val backup = service.backups.load(att.backupKey)
        val ours = backup?.appSlotFor(att.profile) ?: att.profile.appSlotId
        val own = preset == ours
        val memory = PurityDacEqMemory(
            slotId = preset,
            bands = if (own) state.bands.mapNotNull { it.valueOrNull } else emptyList(),
            preGainTenthsDb = if (own) state.preGainTenthsDb.valueOrNull ?: 0 else 0,
            takenAtEpochMs = wallClockMs(),
        )
        if (!memories.save(att.backupKey, memory)) {
            notes.set(PurityDacEqNote.CLOSE_FAILED)
            log("DAC_HID|PURITY on: the memory of what was selected (0x%02X) could not be stored; the EQ is left as it is".format(preset))
            return PurityDacEqOutcome.FAILED
        }
        log("DAC_HID|PURITY on: closing the DAC's EQ (selected 0x%02X, %s)".format(preset, if (own) "the app's slot, ${memory.bands.size} band(s) remembered" else "not the app's slot"))
        return when (val result = service.screenWriter.closeEq(att, WriteInitiator.USER_ACTION)) {
            is DacEqWriteResult.Done -> PurityDacEqOutcome.CLOSED.also { log("DAC_HID|PURITY on: done, ${result.note}") }
            is DacEqWriteResult.Unavailable -> {
                // nothing was sent: forget what was not changed, and try again at the next attach
                memories.clear(att.backupKey)
                PurityDacEqOutcome.UNREACHABLE.also { log("DAC_HID|PURITY on: the HID interface was not available (${result.why}); tried again at the next attach") }
            }
            else -> {
                memories.clear(att.backupKey)
                notes.set(PurityDacEqNote.CLOSE_FAILED)
                PurityDacEqOutcome.FAILED.also { log("DAC_HID|PURITY on: the DAC's EQ was not closed: ${describeWriteResult(result)}") }
            }
        }
    }

    /** Purity is off and [att] is attached: put back what Purity closed, if it closed something. */
    private suspend fun release(att: AttachedFiio): PurityDacEqOutcome {
        val memory = memories.load(att.backupKey) ?: return PurityDacEqOutcome.NOTHING.also { notes.set(null) }
        val backup = service.backups.load(att.backupKey)
        val ours = backup?.appSlotFor(att.profile) ?: att.profile.appSlotId
        if (memory.slotId != ours || memory.bands.isEmpty()) {
            // not a slot Polylute writes: it is never selected (the gate would refuse it, and B60's backTo rule keeps the pointer from naming it)
            memories.clear(att.backupKey)
            notes.set(PurityDacEqNote.NOT_OURS_TO_REOPEN)
            log("DAC_HID|PURITY off: 0x%02X was selected before Purity and is not the app's slot (0x%s): not selected, the DAC's EQ stays closed".format(memory.slotId, ours?.let { "%02X".format(it) } ?: "--"))
            return PurityDacEqOutcome.NOT_OURS
        }
        log("DAC_HID|PURITY off: putting back slot 0x%02X (%d band(s) remembered)".format(memory.slotId, memory.bands.size))
        return when (val result = service.screenWriter.applyCurve(att, memory.bands, WriteInitiator.USER_ACTION)) {
            is DacEqWriteResult.Done -> {
                memories.clear(att.backupKey)
                notes.set(null)
                PurityDacEqOutcome.REOPENED.also { log("DAC_HID|PURITY off: done, ${result.note}") }
            }
            is DacEqWriteResult.Unavailable -> {
                notes.set(PurityDacEqNote.REOPEN_WHEN_CONNECTED)
                PurityDacEqOutcome.UNREACHABLE.also { log("DAC_HID|PURITY off: the HID interface was not available (${result.why}); the memory is kept for the next attach") }
            }
            else -> {
                memories.clear(att.backupKey)
                notes.set(PurityDacEqNote.REOPEN_FAILED)
                PurityDacEqOutcome.FAILED.also { log("DAC_HID|PURITY off: the DAC's EQ was not put back: ${describeWriteResult(result)}") }
            }
        }
    }
}

/**
 * Keeps [PurityDacEq] in step with the two things it depends on: the Purity switch (as the engine reads it, `pristineLockFlow`) and the DAC that is attached. It acts on **every change of
 * either, and at every attach** (a new attachment is a new [AttachmentToken], also for the same DAC replugged), one at a time, so the one entry point covers the switch on the Audio page, on
 * the lock screen, a DAC plugged after Purity was turned on, and "Purity went off while the DAC was away".
 */
class PurityDacEqObserver(
    private val coordinator: PurityDacEq,
    private val purity: kotlinx.coroutines.flow.Flow<Boolean>,
    private val controller: DacEqController,
) {
    private var lastKey: String? = null

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            kotlinx.coroutines.flow.combine(purity.distinctUntilChanged(), controller.readout) { on, _ -> on to controller.attachment() }
                .distinctUntilChanged { a, b -> a.first == b.first && a.second?.second == b.second?.second }
                .collect { (on, attachment) ->
                    val att = attachment?.first
                    if (att != null) lastKey = att.backupKey
                    try {
                        coordinator.reconcile(on, att, lastKey)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // one failed attempt must not end the observer: the next change or attach tries again
                        timber.log.Timber.tag("DAC_HID").w(error, "PURITY could not be reconciled with the DAC's EQ")
                    }
                }
        }
    }
}
