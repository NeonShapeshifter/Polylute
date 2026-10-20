package com.lostf1sh.pixelplayeross.data.volume

import android.app.KeyguardManager
import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbVolumeRange
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.dac.DacProfileCatalog
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.dac.UsbDacDescriptor
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileRepository
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.RemoteDeviceVolume
import com.lostf1sh.pixelplayeross.data.service.player.RemoteVolumeState
import com.lostf1sh.pixelplayeross.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.decent.usbaudio.media3.DirectSignalMode
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

data class SafetyVolumeState(
    val backend: VolumeBackendSnapshot = VolumeBackendSnapshot(
        SafetyVolumeBackendKind.UNKNOWN, null, "Unknown output", 0, 0, 0, false,
    ),
    val selectedProfileId: String? = null,
    val selectedProfileName: String? = null,
    val safetyEnabled: Boolean = false,
    val calibration: SafetyMaxCalibration? = null,
    val calibrationNeedsRefresh: Boolean = false,
    val temporaryOverride: Boolean = false,
    val showOverrideConfirmation: Boolean = false,
    /**
     * FIX-5: the confirmation was asked for with the screen off and is shown over the lock screen. It
     * offers only the temporary override and Cancel: disabling Safety Max for a headphone is a
     * permanent change and not something to put one tap away from a locked screen.
     */
    val overrideConfirmationOverLock: Boolean = false,
    /** The DAC's level in dB when the backend is its hardware volume; null on every other backend. */
    val hardwareDecibels: Float? = null,
) {
    val protectionActive: Boolean
        get() = selectedProfileId != null && safetyEnabled && calibration != null && backend.controllable
    val displayPercent: Int
        get() = SafetyVolumePolicy.displayPercent(
            backend.rawLevel,
            backend,
            calibration?.ceilingRaw?.takeIf { safetyEnabled },
        )
}

/**
 * The fact that a hardware volume key was handled, and how it went.
 *
 * This carries no volume of its own. [interactionId] exists only so the readout knows a new press
 * happened and can restart its own timeout; the number that gets rendered comes from [SafetyVolumeState],
 * which is the same state the ceiling, the Settings control and playback read. A counter here would
 * be a second volume that could disagree with the first.
 */
data class VolumeHudEvent(
    /** Monotonic. Zero means no key has been handled since the process started. */
    val interactionId: Long = 0L,
    /** When the press that produced [interactionId] happened, on the uptime clock. */
    val interactionAtMs: Long = 0L,
    /**
     * The press that produced [interactionId] was refused by the configured Safety Max rather than
     * applied. A press refused because the output is simply at the end of its range is *not* this:
     * that is a fact about the level, which the readout can see for itself.
     */
    val refusedBySafetyMax: Boolean = false,
)

/**
 * F-07: a volume raise the Safety Max ceiling refused where nothing on screen could say so: the
 * media session's remote volume (hardware keys with the screen off or locked, the system panel's
 * slider) or a change made through the platform's own volume. The in-app key path has its own
 * readout and override gesture and does not produce this.
 *
 * Carries nothing but the moment, for the same reason [VolumeHudEvent] carries no level: whoever
 * renders it reads the volume from [SafetyVolumeState].
 */
data class SafetyMaxRefusal(val atElapsedMs: Long)

/**
 * How long the readout stays up, and what "a repeated press extends it" means as arithmetic.
 *
 * The rule is a function of the *last* press rather than of a queue of them: each press moves the
 * deadline, so a burst of presses is one readout that stays up until the reader stops pressing,
 * not a readout per press. Keeping it here rather than inside the effect that runs the timer is
 * what makes it testable without a composition.
 */
object VolumeHudPolicy {
    const val VISIBLE_MILLIS = 1_200L

    /** How much of the readout's time is left, given when the last press landed. */
    fun remainingMillis(lastInteractionAtMs: Long, nowMs: Long): Long {
        if (lastInteractionAtMs <= 0L) return 0L
        return (VISIBLE_MILLIS - (nowMs - lastInteractionAtMs)).coerceAtLeast(0L)
    }

    /** Whether the readout should be on screen at [nowMs]. */
    fun isVisible(lastInteractionAtMs: Long, nowMs: Long): Boolean =
        remainingMillis(lastInteractionAtMs, nowMs) > 0L
}

/**
 * Whether the output (profile, context or backend kind) differs from the previous reconcile. Since
 * FIX-4 this no longer decides anything about the *override* (that has its own session and its own
 * rules, [SafetyOverridePolicy], because this fires on a one-quarter-second flicker at every stream
 * rebuild); it only resets the three-press gesture and closes its dialog, which do belong to one
 * output at one instant.
 */
internal fun safetyOverrideContextChanged(
    previousProfileId: String?,
    previousOutputContextId: String?,
    previousBackendKind: SafetyVolumeBackendKind?,
    profileId: String?,
    outputContextId: String?,
    backendKind: SafetyVolumeBackendKind,
): Boolean = profileId == null ||
    (previousProfileId != null && previousProfileId != profileId) ||
    (previousOutputContextId != null && previousOutputContextId != outputContextId) ||
    (previousBackendKind != null && previousBackendKind != backendKind)

/**
 * The level the media session presents as remote playback: the DAC's Feature Unit while Direct uses
 * it, and nothing otherwise, where `STREAM_MUSIC` is the level being heard (AUD-027).
 */
internal fun remoteVolumeStateOf(state: SafetyVolumeState): RemoteVolumeState? =
    state.backend.takeIf { it.kind == SafetyVolumeBackendKind.USB_HARDWARE_VOLUME && it.controllable }
        ?.let { RemoteVolumeState(min = it.rawMin, max = it.rawMax, level = it.rawLevel) }

/** At most this many steps (~[UsbHardwareVolumePolicy.STEP_DB] dB each) per remote raise request. */
internal const val REMOTE_MAX_RAISE_STEPS = 3

/**
 * A remote volume request, paced: the system panel's slider can ask for the top of the range in one
 * gesture, which the keys never could (REV-4). Raises are limited per request; lowering is not. The
 * Safety Max ceiling still applies afterwards, in [SafetyVolumeController.setRawLevel].
 */
internal fun remoteRequestedRaw(requested: Int, current: Int, maxRaiseSteps: Int): Int =
    if (requested > current) minOf(requested, current + maxRaiseSteps) else requested

/**
 * The DAC whose Safety context applies. When Direct has a device open, that device is the one being
 * heard, so it wins even with several DACs attached (AUD-075). Otherwise only an unambiguous single
 * DAC qualifies: which of two DACs the Android mixer routes to is not knowable from here.
 */
internal fun selectSafetyDac(
    profiles: List<DacProfile>,
    connected: List<UsbDacDescriptor>,
    openedDevicePath: String?,
): DacProfile? {
    openedDevicePath
        ?.let { path -> connected.firstOrNull { it.connectionPath == path } }
        ?.let { DacProfileCatalog.findForDescriptor(profiles, it) }
        ?.let { return it }
    return connected.mapNotNull { DacProfileCatalog.findForDescriptor(profiles, it) }
        .distinctBy { it.profileId }
        .singleOrNull()
}

/**
 * Single process authority for volume policy. It never inserts PCM gain. Android media volume is the
 * backend on Android Audio; on USB Direct it is the DAC's own UAC2 Feature Unit when the DAC exposes
 * one, and a fixed output otherwise. Settings observation is event-driven; there is no poll.
 */
@Singleton
class SafetyVolumeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: HeadphoneProfileRepository,
    private val dacProfiles: DacProfileRepository,
    private val dualPlayerEngine: DualPlayerEngine,
    @AppScope private val appScope: CoroutineScope,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gesture = SafetyOverrideGesture()

    /** FIX-5: the same ask with the screen off, five presses (see [ScreenOffOverridePolicy]). */
    private val screenOffGesture = ScreenOffOverridePolicy.newGesture()
    private val overrideScreenWaker = OverrideScreenWaker(context)

    @Volatile
    private var confirmationHost: OverrideConfirmationHost? = null

    /**
     * FIX-5b: a screen-off request waiting for the app to come to the front, with what it is waiting
     * for and since when. Set *before* the screen is woken: the activity can resume the instant it is.
     */
    private class PendingOverrideRequest(
        val delivery: ScreenOffOverridePolicy.Delivery,
        val sinceMs: Long,
    )

    @Volatile
    private var pendingRequest: PendingOverrideRequest? = null
    private var pendingDropJob: Job? = null

    /**
     * The product mode (Device Owner + HOME + Lock Task allowed), asked of the *device* when the fifth
     * press arrives. It needs only the application context: it does not depend on an activity being
     * resumed, which with the screen off it is not.
     */
    private val productMode by lazy { com.lostf1sh.pixelplayeross.dap.mode.ProductModeController(context) }

    @Volatile
    private var overLockActive = false
    private var overLockJob: Job? = null
    private val _state = MutableStateFlow(SafetyVolumeState())
    val state: StateFlow<SafetyVolumeState> = _state.asStateFlow()
    private val _hudEvent = MutableStateFlow(VolumeHudEvent())

    /**
     * Fires once per handled hardware volume press. The readout layer observes this for *when* to
     * appear and reads [state] for *what* to show.
     */
    val hudEvent: StateFlow<VolumeHudEvent> = _hudEvent.asStateFlow()

    private val _refusals = MutableSharedFlow<SafetyMaxRefusal>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * F-07: fires when the ceiling refuses a raise that arrived by a path with no readout of its own
     * (see [SafetyMaxRefusal]), at most once per [REFUSAL_MIN_INTERVAL_MS]. The service turns it into
     * a notification ("Safety Max · unlock to go higher"); this class owns the decision, not the UI.
     */
    val refusals: SharedFlow<SafetyMaxRefusal> = _refusals.asSharedFlow()

    private var lastRefusalAtMs = 0L

    private fun announceRefusal() {
        val now = SystemClock.elapsedRealtime()
        if (lastRefusalAtMs != 0L && now - lastRefusalAtMs < REFUSAL_MIN_INTERVAL_MS) return
        lastRefusalAtMs = now
        _refusals.tryEmit(SafetyMaxRefusal(now))
        // The app's own readout too, when the activity is up to show it.
        announceHud(refusedBySafetyMax = true)
    }

    /**
     * The app's digital level while it is a second volume in front of the DAC (USB Direct in
     * PROCESSED), or null. Read by the readout; the keys never move it.
     */
    val digitalLevel: StateFlow<Float?> = combine(
        dualPlayerEngine.gainModel,
        dualPlayerEngine.audioRouteState,
    ) { gain, route ->
        gain.userDigitalGain.takeIf { route.isDirectActive && route.signalMode == DirectSignalMode.PROCESSED }
    }.stateIn(appScope, SharingStarted.Eagerly, null)

    /**
     * The DAC's Feature Unit as the session's remote volume, so the hardware keys reach it with the
     * screen off (AUD-027). Requests go through [setRawLevel], which applies the Safety Max ceiling.
     */
    val remoteVolume: RemoteDeviceVolume = object : RemoteDeviceVolume {
        override val state: StateFlow<RemoteVolumeState?> = this@SafetyVolumeController.state
            .map(::remoteVolumeStateOf)
            .stateIn(appScope, SharingStarted.Eagerly, null)

        override fun setLevel(level: Int) {
            // Like a key press: the DAC's own buttons may have moved it, so re-read before stepping.
            if (_state.value.backend.kind == SafetyVolumeBackendKind.USB_HARDWARE_VOLUME) {
                hardwareRefreshRequested = true
                reconcile()
            }
            refreshOverride()
            val current = _state.value
            val ceiling = current.calibration?.ceilingRaw?.takeIf { current.safetyEnabled }
            val requested = remoteRequestedRaw(
                requested = level,
                current = current.backend.rawLevel,
                maxRaiseSteps = SafetyVolumePolicy.remoteRaiseSteps(current.backend.kind, ceiling),
            )
            // F-07: at the ceiling a raise changes nothing, and on this channel nothing else would
            // say so. It is reported, never applied: the clamp in `setRawLevel` is untouched, and
            // this path never grants an override. FIX-5: it can *ask* for one (five refused presses
            // with the screen off), but only the tap on the confirmation grants it.
            if (SafetyVolumePolicy.raiseRefusedByCeiling(
                    requestedRaw = level,
                    currentRaw = current.backend.rawLevel,
                    ceilingRaw = ceiling,
                    overrideActive = current.temporaryOverride,
                )
            ) {
                announceRefusal()
                noteRefusedRemotePress(singleStep = level == current.backend.rawLevel + 1)
                return
            }
            // AUD-126: raising is paced like a held key. The remote channel is the only one that can
            // raise the level with nobody touching the device, so it may not walk it up faster than
            // a finger could. A request inside the window is refused, never queued.
            if (requested > current.backend.rawLevel) {
                val now = SystemClock.elapsedRealtime()
                if (!VolumeKeyRepeatPolicy.remoteRaiseAllowed(now, lastRemoteRaiseAtMs)) return
                lastRemoteRaiseAtMs = now
            }
            setRawLevel(requested, origin = "remote")
        }
    }
    private val calibrationNotice = SafetyCalibrationNotice()
    private var started = false
    private var lastProfileId: String? = null
    private var lastOutputContextId: String? = null
    private var lastBackendKind: SafetyVolumeBackendKind? = null
    private var selfWriteRaw: Int? = null
    private var lastKeyStepAtMs = 0L

    /** AUD-126: when the remote channel last raised the level, so it can be paced like a key. */
    private var lastRemoteRaiseAtMs = 0L
    private val usbDevice = UsbAudioDevice.getInstance(context)
    private val hardwareStore = UsbHardwareVolumeStore(context)

    /**
     * FIX-4: the temporary override, as a session with its own rules ([SafetyOverridePolicy]). It is
     * read from the thread that opens the DAC (the open guard) as well as the main one, so it is
     * only ever replaced under [sessionLock], and read through the volatile field.
     */
    private val overrideStore = SafetyOverrideStore(context)
    private val sessionLock = Any()

    @Volatile
    private var overrideSession: SafetyOverrideSession? = null

    /**
     * B12a-8 #3: the headphone profile store has delivered its first real read. Until it has, a restored override is
     * not judged (see [SafetyOverrideObservation.contextLoaded]): the profile list is the empty default after a restart.
     */
    @Volatile
    private var contextLoaded = false
    private var overrideCheckJob: Job? = null
    private var lastOverridePersistAtMs = 0L

    /** The fade that ends an override while music plays ([SafetyOverrideRamp]); null when none runs. */
    private var rampJob: Job? = null

    @Volatile
    private var rampLevel: Int? = null

    /** Range of the DAC behind the current USB_HARDWARE_VOLUME snapshot; null on any other backend. */
    @Volatile
    private var hardwareRange: UsbVolumeRange? = null

    /**
     * B15-F1: the one gate to the DAC's volume. Every listener or Safety write goes through
     * [DacVolumeArbiter.listenerWrite], a Direct duck's steps through [DacVolumeArbiter.temporaryWrite]; see there.
     *
     * B16-G1: its lock **is the USB device's monitor** (`usbDevice`, whose volume methods are `@Synchronized`), not a
     * second one: see [DacVolumeArbiter] for the order. Everything this class runs inside it (the write lambdas, the
     * ceiling read) takes no other lock, and [hardwareOpenTarget], which the driver calls under that same monitor at a
     * rate change, takes none either.
     */
    private val dacVolume = DacVolumeArbiter(usbDevice)

    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reconcile(externalVolumeChange = true)
        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) = reconcile(externalVolumeChange = true)
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = reconcile()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = reconcile()
    }

    @Synchronized
    fun start() {
        if (started || audioManager == null) return
        started = true
        usbDevice.volumeGuard = ::hardwareOpenTarget
        restoreOverride()
        // B12a-8 #3: judge a restored override only against the profile the store really holds. `profiles.currentState()`
        // is the store's own first read; the published state catches up to it a moment later.
        appScope.launch {
            try {
                val real = profiles.currentState()
                withTimeoutOrNull(CONTEXT_LOAD_WAIT_MS) { profiles.state.first { it == real } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Profile store unreadable at start; judging the restored override as it stands")
            }
            contextLoaded = true
            if (overrideSession != null) Timber.tag(TAG).i("Profiles loaded: the restored override is judged against them now")
            reconcile()
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        appScope.launch {
            combine(
                profiles.state,
                dacProfiles.profiles,
                dacProfiles.connected,
                dualPlayerEngine.audioRouteState,
            ) { _, _, _, _ -> Unit }.collect { reconcile() }
        }
        // FIX-4: the override's idle clock follows playback; a pause starts it, playing restarts it.
        appScope.launch { dualPlayerEngine.isPlayingFlow.collect(::onPlaybackChanged) }
        // The automatic Direct handoff resumes only on this verdict, never on DIRECT_ACTIVE alone.
        appScope.launch {
            state.map(HandoffResumeSafety::verdict)
                .distinctUntilChanged()
                .collect(dualPlayerEngine::onHandoffVolumeVerdict)
        }
        reconcile()
    }

    fun reconcile(externalVolumeChange: Boolean = false) {
        val manager = audioManager ?: return
        val backend = resolveBackend(manager, dacProfiles.profiles.value, dacProfiles.connected.value)
        val selected = profiles.state.value.selectedProfile
        if (safetyOverrideContextChanged(
                lastProfileId,
                lastOutputContextId,
                lastBackendKind,
                selected?.profileId,
                backend.outputContextId,
                backend.kind,
            )) {
            // Only the gesture and its dialog are per output-instant. The override is not cleared
            // here any more: it has its own session (FIX-4), which a rebuild's flicker cannot end.
            gesture.reset()
            screenOffGesture.reset()
            _state.update { it.copy(showOverrideConfirmation = false) }
        }
        // B15-F1: another DAC under the same state. A flicker through "no DAC" (a reopen) is not a swap and does not
        // end a duck; whatever the duck wrote on a different device would fail its compare-and-set on the read anyway.
        val previousContext = lastOutputContextId
        if (previousContext != null && backend.outputContextId != null && previousContext != backend.outputContextId) {
            dacVolume.supersede()
        }
        lastProfileId = selected?.profileId
        lastOutputContextId = backend.outputContextId
        lastBackendKind = backend.kind

        val stored = selected?.safetyMax?.calibrationFor(backend.outputContextId, backend.kind)
        val calibration = SafetyVolumePolicy.effectiveCalibration(stored, backend)
        val needsRefresh = stored != null && SafetyVolumePolicy.compatibleCalibration(stored, backend) == null
        val enabled = selected?.safetyMax?.enabled == true
        // AUD-126: Direct on a DAC nothing bounds is said once, in the log and on screen.
        if (calibrationNotice.shouldShow(backend, calibration)) {
            Timber.tag(TAG).w(
                "Direct on context=%s has no Safety Max ceiling: nothing limits a rise of the DAC's level",
                backend.outputContextId,
            )
            mainHandler.post {
                Toast.makeText(context, R.string.safety_max_calibrate_dac_notice, Toast.LENGTH_LONG).show()
            }
        }
        val previous = _state.value
        val leftCeiling = calibration != null && backend.rawLevel < calibration.ceilingRaw
        val volumeLowered = previous.backend.outputContextId == backend.outputContextId &&
            backend.rawLevel < previous.backend.rawLevel
        if (leftCeiling || volumeLowered) {
            gesture.reset()
            screenOffGesture.reset()
            if (previous.showOverrideConfirmation) {
                _state.update { it.copy(showOverrideConfirmation = false) }
            }
        }
        val ceilingRaw = calibration?.ceilingRaw?.takeIf { enabled }
        val outcome = evaluateOverride(SystemClock.elapsedRealtime(), backend, ceilingRaw)
        val override = outcome.active
        val clamp = SafetyVolumePolicy.mustClamp(backend.rawLevel, ceilingRaw, override)
        if (clamp != null && backend.controllable) {
            val rampedTo = rampLevel
            if (rampedTo != null && !SafetyOverrideRamp.raisedDuringRamp(backend.rawLevel, rampedTo)) {
                // The fade that ends an override is under way and the level is at or under what it
                // last wrote: let it finish rather than cut it short with a jump.
                updateState(backend, selected?.profileId, selected?.displayName, enabled, calibration, needsRefresh, override)
                return
            }
            if (rampedTo != null) cancelRamp()
            if (SafetyOverrideRamp.shouldRamp(
                    endedHere = outcome.ended != null,
                    playing = dualPlayerEngine.isPlayingFlow.value,
                    level = backend.rawLevel,
                    ceilingRaw = clamp,
                )
            ) {
                startRamp(manager, backend, clamp)
                updateState(backend, selected?.profileId, selected?.displayName, enabled, calibration, needsRefresh, override)
                return
            }
            Timber.tag(TAG).i(
                "Clamping volume context=%s raw=%d ceiling=%d external=%s",
                backend.outputContextId,
                backend.rawLevel,
                clamp,
                externalVolumeChange,
            )
            val blockedAttempts = (backend.rawLevel - clamp).coerceAtLeast(1).coerceAtMost(3)
            val shouldConfirm = externalVolumeChange && selfWriteRaw != backend.rawLevel &&
                (1..blockedAttempts).any { gesture.blockedVolumeUp(SystemClock.elapsedRealtime()) }
            // F-07: the platform's own volume (a key with the app not in front, the system panel)
            // went over the ceiling and is being put back. Say so, instead of a slider that rises
            // and snaps back with no explanation. Only for a change somebody made: a clamp after a
            // device switch is not a refused press.
            if (externalVolumeChange && selfWriteRaw != backend.rawLevel) announceRefusal()
            if (shouldConfirm) {
                Timber.tag(TAG).i("Third blocked Volume Up; requesting explicit override confirmation")
                _state.update { it.copy(showOverrideConfirmation = true) }
            }
            // B27-A3 (R-03): the level that is published is the one the DAC reported after the write. A write it refused leaves the level
            // above the ceiling and unverified, which the resume verdict reads as "wait". Not retried from here: the next event re-reads it.
            val write = setBackendRaw(manager, backend, clamp, "clamp")
            if (write.reductionFailed(backend.rawLevel)) announceVolumeWriteFailed()
            updateState(
                backend.copy(rawLevel = write.publishedStep(backend.rawLevel), levelVerified = write.verified),
                selected?.profileId, selected?.displayName, enabled, calibration, needsRefresh, override,
            )
        } else {
            selfWriteRaw = null
            // Nothing above a ceiling any more (the fade arrived, or the output is another one).
            if (rampLevel != null) cancelRamp()
            updateState(backend, selected?.profileId, selected?.displayName, enabled, calibration, needsRefresh, override)
        }
    }

    /** What the override did at one evaluation: whether it applies now, and why it ended if it did. */
    private class OverrideOutcome(val active: Boolean, val ended: SafetyOverrideEnd?)

    private fun observationFor(
        session: SafetyOverrideSession?,
        nowMs: Long,
        backend: VolumeBackendSnapshot,
        ceilingRaw: Int?,
    ): SafetyOverrideObservation {
        val selected = profiles.state.value.selectedProfile
        return SafetyOverrideObservation(
            nowMs = nowMs,
            profileId = selected?.profileId,
            safetyEnabled = selected?.safetyMax?.enabled == true,
            calibration = session?.let { selected?.safetyMax?.calibrationFor(it.contextId, it.kind) },
            contextId = backend.outputContextId,
            kind = backend.kind,
            rawLevel = backend.rawLevel,
            ceilingRaw = ceilingRaw,
            attachments = attachedTokens(),
            playing = dualPlayerEngine.isPlayingFlow.value,
            contextLoaded = contextLoaded,
        )
    }

    /** One token per USB audio device attached right now (see [SafetyOverridePolicy.attachmentOf]). */
    private fun attachedTokens(): Set<String> = dacProfiles.connected.value
        .mapTo(mutableSetOf()) { SafetyOverridePolicy.attachmentOf(it.connectionPath, it.identityKey) }

    /**
     * The attachment an override given on [contextId] is bound to: the DAC Direct has open, else the
     * one DAC that belongs to the context's profile. Null when it cannot be told (several identical
     * DACs and none open) or the output is not a DAC; such an override is held by context only, and
     * is not restored after a restart.
     */
    private fun attachmentFor(contextId: String): String? {
        if (!contextId.startsWith(SafetyOverridePolicy.DAC_CONTEXT_PREFIX)) return null
        val connected = dacProfiles.connected.value
        val opened = usbDevice.openedDevicePath
        connected.firstOrNull { opened != null && it.connectionPath == opened }
            ?.let { return SafetyOverridePolicy.attachmentOf(it.connectionPath, it.identityKey) }
        val profileId = contextId.removePrefix(SafetyOverridePolicy.DAC_CONTEXT_PREFIX)
        return connected
            .filter { DacProfileCatalog.findForDescriptor(dacProfiles.profiles.value, it)?.profileId == profileId }
            .singleOrNull()
            ?.let { SafetyOverridePolicy.attachmentOf(it.connectionPath, it.identityKey) }
    }

    /**
     * Runs the session through [SafetyOverridePolicy] against the backend as it is now, commits the
     * verdict (session kept, kept but suspended, or ended and forgotten) and re-arms the one timer.
     */
    private fun evaluateOverride(nowMs: Long, backend: VolumeBackendSnapshot, ceilingRaw: Int?): OverrideOutcome =
        synchronized(sessionLock) {
            val session = overrideSession ?: return@synchronized OverrideOutcome(false, null)
            when (val verdict = SafetyOverridePolicy.evaluate(session, observationFor(session, nowMs, backend, ceilingRaw))) {
                is SafetyOverrideVerdict.Active -> {
                    if (session.mismatchSinceMs != null) {
                        Timber.tag(TAG).i("Override resumed: output is %s again", session.contextId)
                    }
                    commitOverride(session, verdict.session, nowMs)
                    OverrideOutcome(true, null)
                }
                is SafetyOverrideVerdict.Suspended -> {
                    if (session.mismatchSinceMs == null) {
                        Timber.tag(TAG).i(
                            "Override suspended: output is %s/%s, it was given on %s/%s (waits %d ms)",
                            backend.outputContextId,
                            backend.kind,
                            session.contextId,
                            session.kind,
                            session.graceMs,
                        )
                    }
                    commitOverride(session, verdict.session, nowMs)
                    OverrideOutcome(false, null)
                }
                is SafetyOverrideVerdict.Ended -> {
                    Timber.tag(TAG).i(
                        "Temporary Safety Max override ended: %s (context=%s level=%d ceiling=%s)",
                        verdict.reason,
                        backend.outputContextId,
                        backend.rawLevel,
                        ceilingRaw,
                    )
                    forgetOverride()
                    OverrideOutcome(false, verdict.reason)
                }
            }
        }

    private fun commitOverride(previous: SafetyOverrideSession, next: SafetyOverrideSession, nowMs: Long) {
        overrideSession = next
        if (next.peaked != previous.peaked || nowMs - lastOverridePersistAtMs >= SafetyOverridePolicy.HEARTBEAT_MS) {
            persistOverride(next, nowMs)
        }
        scheduleOverrideCheck(next, nowMs)
    }

    private fun persistOverride(session: SafetyOverrideSession, nowMs: Long) {
        overrideStore.save(SafetyOverrideRecord(session, bootCount()))
        lastOverridePersistAtMs = nowMs
    }

    /** The one timer: wakes the evaluation when time alone could end the session, or to refresh it. */
    private fun scheduleOverrideCheck(session: SafetyOverrideSession, nowMs: Long) {
        overrideCheckJob?.cancel()
        val delayMs = SafetyOverridePolicy.nextCheckDelayMs(session, dualPlayerEngine.isPlayingFlow.value, nowMs)
        overrideCheckJob = appScope.launch {
            delay(delayMs)
            reconcile()
        }
    }

    private fun forgetOverride() {
        overrideSession = null
        overrideCheckJob?.cancel()
        overrideCheckJob = null
        overrideStore.clear()
    }

    /** Ends the session for a reason the caller knows (Safety Max switched off) without waiting for a reconcile. */
    private fun endOverride(reason: SafetyOverrideEnd) = synchronized(sessionLock) {
        if (overrideSession == null) return@synchronized
        Timber.tag(TAG).i("Temporary Safety Max override ended: %s", reason)
        forgetOverride()
    }

    /**
     * The timer that should have ended the override can be late (coroutine delays do not run while
     * the device sleeps), so anything that is about to read the override asks first whether time has
     * already ended it, and if so reconciles, which ends it.
     */
    private fun refreshOverride() {
        val session = overrideSession ?: return
        if (SafetyOverridePolicy.isDue(session, dualPlayerEngine.isPlayingFlow.value, SystemClock.elapsedRealtime())) {
            reconcile()
        }
    }

    private fun onPlaybackChanged(playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        synchronized(sessionLock) {
            val session = overrideSession ?: return
            // A pause is the last moment of activity; the idle window runs from here.
            val touched = SafetyOverridePolicy.touch(session, now)
            overrideSession = touched
            persistOverride(touched, now)
            scheduleOverrideCheck(touched, now)
            Timber.tag(TAG).d("Override: playback %s", if (playing) "running" else "stopped; idle window starts")
        }
    }

    private fun bootCount(): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrDefault(-1)

    /**
     * FIX-4: the override across a restart of the app. Read once; the policy decides whether it is
     * still trustworthy (same boot, inside the idle window, a DAC attachment), and even then it only
     * *applies* once the same output, profile and calibration are seen again.
     */
    private fun restoreOverride() {
        val record = overrideStore.load() ?: return
        val restored = SafetyOverridePolicy.restore(record, bootCount(), SystemClock.elapsedRealtime())
        if (restored == null) {
            Timber.tag(TAG).i("Stored Safety Max override not restored (other boot, idle too long, or no DAC attachment)")
            overrideStore.clear()
            return
        }
        Timber.tag(TAG).i(
            "Safety Max override restored after restart: context=%s kind=%s (applies when that output is seen again)",
            restored.contextId,
            restored.kind,
        )
        synchronized(sessionLock) {
            overrideSession = restored
            scheduleOverrideCheck(restored, SystemClock.elapsedRealtime())
        }
    }

    private fun cancelRamp() {
        rampJob?.cancel()
        rampJob = null
        rampLevel = null
    }

    /**
     * Brings the level from the end of an override down to the ceiling one step at a time
     * ([SafetyOverrideRamp]). Each write goes through [setBackendRaw], so the Feature Unit log shows
     * it as `origin=ramp`.
     */
    private fun startRamp(manager: AudioManager, from: VolumeBackendSnapshot, ceilingRaw: Int) {
        cancelRamp()
        rampLevel = from.rawLevel
        Timber.tag(TAG).i(
            "Override ended while playing: lowering %d -> %d over ~%d ms instead of one jump",
            from.rawLevel,
            ceilingRaw,
            SafetyOverrideRamp.durationMs(from.rawLevel, ceilingRaw),
        )
        rampJob = appScope.launch {
            var level = from.rawLevel
            while (isActive && level > ceilingRaw) {
                delay(SafetyOverrideRamp.STEP_INTERVAL_MS)
                val current = _state.value.backend
                if (current.outputContextId != from.outputContextId || current.kind != from.kind) break
                // Raised meanwhile: not ours to soften; the reconcile that saw it clamps at once. Lowered by hand
                // meanwhile: the ramp carries on from the real level, never from its own counter (B12a-8), so it
                // can never write a level above the current one.
                level = SafetyOverrideRamp.next(current.rawLevel, level, ceilingRaw) ?: break
                val write = setBackendRaw(manager, current, level, "ramp")
                // B27-A3: a step the DAC did not take ends the fade; the level published is the one it reported (or the last known one).
                val published = write.publishedStep(current.rawLevel)
                _state.update {
                    if (!sameOutput(it.backend, current)) it else {
                        val backend = it.backend.copy(rawLevel = published, levelVerified = write.verified)
                        it.copy(backend = backend, hardwareDecibels = hardwareDecibelsOf(backend))
                    }
                }
                if (write.reductionFailed(current.rawLevel)) announceVolumeWriteFailed()
                // The step is taken when the DAC reports it or something quieter; anything else ends the fade where it stands.
                if (!write.verified || published > level) break
                rampLevel = level
            }
            rampLevel = null
        }
    }

    /**
     * Handles a hardware volume key, and reports whether it was consumed.
     *
     * The app consumes the key whenever there is an Android media volume it can actually change —
     * not only when Safety Max happens to be armed. That is the whole point: the key used to fall
     * through to the platform whenever protection was off, so the *system's* volume panel appeared
     * on some outputs and nothing at all appeared on others. Whether a readout is shown is our
     * decision now, and it is the same decision on every output that has a volume to show.
     *
     * The one case that still falls through is an output this app cannot control at all — USB Direct
     * to a DAC without a hardware volume control, where the level that would change is not the level
     * being heard. There is nothing truthful to render there. A DAC that does expose one is an
     * ordinary controllable backend and takes the key like any other.
     */
    fun handleVolumeKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        if (!_state.value.backend.controllable) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // A held key repeats, at a paced rate rather than the platform's ~50 ms: raising is slower
        // than lowering, because a held Volume Up on a DAC is otherwise ~40 dB per second.
        val raising = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val now = SystemClock.uptimeMillis()
        if (!VolumeKeyRepeatPolicy.shouldStep(event.repeatCount, raising, lastKeyStepAtMs, now)) return true
        lastKeyStepAtMs = now
        // A DAC's own buttons change its level without telling us. Stepping from a stale reading
        // would turn "one step up" into a jump back to wherever it was before, so re-read first.
        if (_state.value.backend.kind == SafetyVolumeBackendKind.USB_HARDWARE_VOLUME) {
            hardwareRefreshRequested = true
            reconcile()
        }
        // An override the idle window has already ended must not be honoured by a press that arrives
        // before the timer got to run.
        refreshOverride()
        val current = _state.value
        if (!current.backend.controllable) return false

        // The ceiling is only a ceiling while Safety Max is armed *and* the stored calibration
        // still describes this output. Either one missing means the level is free to reach the top
        // of the backend's own range, which is what the platform would have allowed anyway.
        val ceiling = current.calibration?.ceilingRaw?.takeIf { current.safetyEnabled }

        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            gesture.reset()
            setRawLevel(current.backend.rawLevel - 1, origin = "key")
            announceHud(refusedBySafetyMax = false)
            return true
        }

        if (ceiling == null || current.temporaryOverride || current.backend.rawLevel < ceiling) {
            gesture.reset()
            setRawLevel(current.backend.rawLevel + 1, origin = "key")
            announceHud(refusedBySafetyMax = false)
        } else {
            // At the ceiling. The press is refused and counted; three inside the window raise the
            // confirmation. The readout says the press was refused either way. Only real presses
            // count: a key held against the ceiling must not add up to an override request.
            if (event.repeatCount == 0 && gesture.blockedVolumeUp(SystemClock.elapsedRealtime())) {
                Timber.tag(TAG).i("Third blocked Volume Up; requesting explicit override confirmation")
                _state.update { it.copy(showOverrideConfirmation = true) }
            }
            announceHud(refusedBySafetyMax = true)
        }
        return true
    }

    private fun announceHud(refusedBySafetyMax: Boolean) {
        _hudEvent.update {
            it.copy(
                interactionId = it.interactionId + 1,
                interactionAtMs = SystemClock.uptimeMillis(),
                refusedBySafetyMax = refusedBySafetyMax,
            )
        }
    }

    /**
     * @param origin who asked, for the Feature Unit log (AUD-126): `key`, `remote`, `pristine`,
     *        `clamp` or `ui`. Every write used to leave as the driver's own `host` default, so an
     *        unknown writer and this app's writes were indistinguishable in the log.
     */
    fun setRawLevel(requested: Int, origin: String = "ui") {
        refreshOverride()
        val current = _state.value
        if (!current.backend.controllable || audioManager == null) return
        val ceiling = current.calibration?.ceilingRaw?.takeIf { current.safetyEnabled }
        val raw = SafetyVolumePolicy.requestedRaw(requested, current.backend, ceiling, current.temporaryOverride)
        val generationBefore = usbDevice.openGeneration
        val write = setBackendRaw(audioManager, current.backend, raw, origin)
        // B27-A3 (R-03): from here on the level is the one the backend reported, not the one that was asked for. A write the DAC
        // refused, or could not read back, publishes the last known level, unverified; a read-back that differs publishes that.
        val published = write.publishedStep(current.backend.rawLevel)
        // FIX-5: any lowering breaks a sequence of presses at the ceiling.
        if (published < current.backend.rawLevel) screenOffGesture.reset()
        // Lowering to the ceiling ends the override; the verdict is taken on the level the DAC reports after the write.
        val lowered = current.backend.copy(rawLevel = published)
        val override = evaluateOverride(SystemClock.elapsedRealtime(), lowered, ceiling).active
        if (write.reductionFailed(current.backend.rawLevel)) announceVolumeWriteFailed()
        _state.update {
            // The output changed while the DAC was being written (another DAC, the same one reopened): this reading belongs to the
            // old one. The next reconcile reads the new.
            val dacReopened = current.backend.kind == SafetyVolumeBackendKind.USB_HARDWARE_VOLUME &&
                usbDevice.openGeneration != generationBefore
            if (!sameOutput(it.backend, current.backend) || dacReopened) it else {
                val backend = it.backend.copy(rawLevel = published, levelVerified = write.verified)
                it.copy(backend = backend, hardwareDecibels = hardwareDecibelsOf(backend), temporaryOverride = override)
            }
        }
    }

    /** The same output: what a reading taken for one is good for (B27-A3). */
    private fun sameOutput(a: VolumeBackendSnapshot, b: VolumeBackendSnapshot): Boolean =
        a.outputContextId == b.outputContextId && a.kind == b.kind

    private var lastWriteFailureNoticeAtMs = 0L

    /** B27-A3: a reduction the DAC did not take is said, once per burst, in words; the level shown stays what it last reported. */
    private fun announceVolumeWriteFailed() {
        val now = SystemClock.elapsedRealtime()
        if (lastWriteFailureNoticeAtMs != 0L && now - lastWriteFailureNoticeAtMs < REFUSAL_MIN_INTERVAL_MS) return
        lastWriteFailureNoticeAtMs = now
        Timber.tag(TAG).w("The DAC did not take the volume reduction; its level may be higher than shown")
        mainHandler.post {
            Toast.makeText(context, R.string.safety_max_volume_write_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * F-02: the DAC's hardware volume in steps while it is the active, controllable backend, with a
     * read-back of the DAC itself (a blocking control transfer: call off the main thread).
     */
    fun pristineHardwareVolume(): PristineModePolicy.HardwareVolume? {
        val current = _state.value
        val backend = current.backend
        if (backend.kind != SafetyVolumeBackendKind.USB_HARDWARE_VOLUME || !backend.controllable) return null
        val range = hardwareRange ?: return null
        val expected = UsbHardwareVolumePolicy.unitsFor(backend.rawLevel, range)
        // The read and the ticket are taken together: a write that lands after this read is a write the duck has not seen.
        val (ticket, verified) = dacVolume.observe { usbDevice.readHardwareVolume() == expected }
        return PristineModePolicy.HardwareVolume(
            currentStep = backend.rawLevel,
            minStep = backend.rawMin,
            maxStep = backend.rawMax,
            stepDb = UsbHardwareVolumePolicy.stepUnits(range) / UsbVolumeRange.UNITS_PER_DB.toFloat(),
            verified = verified && backend.levelVerified,
            ticket = ticket,
        )
    }

    /**
     * F-02: the Safety Max ceiling in backend steps, when it is in force.
     *
     * B16-G2: worked out from the profile as the store holds it **now** ([SafetyVolumePolicy.ceilingNow]) rather than from
     * the last published state, which a profile or calibration change only reaches a reconcile later; the override is
     * the published verdict. It reads flows' values and fields only (no lock), so the arbiter evaluates it inside its
     * compare-and-set, under the same lock as the write.
     */
    fun pristineCeilingStep(): Int? {
        val current = _state.value
        return SafetyVolumePolicy.ceilingNow(
            profiles.state.value.selectedProfile?.safetyMax,
            current.backend,
            current.temporaryOverride,
        )
    }

    /** F-02: sets a DAC step (through the same ceiling clamp as any request) and reads it back. */
    fun setHardwareStepVerified(step: Int): Boolean {
        setRawLevel(step, origin = "pristine")
        val range = hardwareRange ?: return false
        val held = _state.value.backend.rawLevel
        return held == step && usbDevice.readHardwareVolume() == UsbHardwareVolumePolicy.unitsFor(held, range)
    }

    /**
     * B14-3 (audit A-05), B15-F1: a temporary DAC level for a Direct duck. Written and read back like any step, but it is
     * the app's, not the listener's: it never goes into the store the DAC is opened at next time, and it does
     * not pass through the ceiling clamp or the override verdict, because a duck only goes down and comes back
     * to the level it left (the caller caps that at the ceiling, at every step). The state follows the DAC, so the
     * readout shows what is playing.
     *
     * It is a compare-and-set against everyone else who writes this volume ([DacVolumeArbiter]): [ticket] is what
     * [pristineHardwareVolume] handed the duck, [expectedStep] where the duck last left the DAC. If the listener or
     * Safety has written since, or the DAC reads anything but [expectedStep], or this is no longer the hardware
     * backend, nothing is written and the answer is [TemporaryWrite.SUPERSEDED]. B16-G2: and a step that would raise
     * the DAC above the Safety Max ceiling **as it is when the step is about to be written** is not written either
     * ([TemporaryWrite.ABOVE_CEILING]): the caller's own reading of the ceiling is only its plan.
     */
    fun setHardwareStepTemporarily(step: Int, ticket: Long, expectedStep: Int): TemporaryWrite {
        val range = hardwareRange
        return dacVolume.temporaryWrite(
            ticket = ticket,
            expectedStep = expectedStep,
            step = step,
            ceilingStep = ::pristineCeilingStep,
            readStep = {
                val current = _state.value
                if (range == null || current.backend.kind != SafetyVolumeBackendKind.USB_HARDWARE_VOLUME ||
                    !current.backend.controllable
                ) null else usbDevice.readHardwareVolume()?.let { UsbHardwareVolumePolicy.stepFor(it, range) }
            },
            writeStep = {
                val written = range?.let {
                    usbDevice.writeHardwareVolume(UsbHardwareVolumePolicy.unitsFor(step, it), "duck")
                }
                if (range == null || written == null) {
                    false
                } else {
                    hardwareUnitsCache = written
                    val held = UsbHardwareVolumePolicy.stepFor(written, range)
                    _state.update {
                        val backend = it.backend.copy(rawLevel = held)
                        it.copy(backend = backend, hardwareDecibels = hardwareDecibelsOf(backend))
                    }
                    held == step && usbDevice.readHardwareVolume() == UsbHardwareVolumePolicy.unitsFor(held, range)
                }
            },
        )
    }

    /** Sets the app's digital level from a touch on the readout; the keys never move it. */
    fun setDigitalLevel(gain: Float) = dualPlayerEngine.setUserDigitalGain(gain)

    /**
     * Saves the level the backend holds as this output's Safety Max ceiling.
     *
     * B35 (RT-01): only a level the backend **reported**. After a write the DAC refused, or whose result could not be read back, the
     * published level is just the last one that was known ([VolumeBackendSnapshot.levelVerified] is false) and used to be saved
     * as if it were a reading. Now a fresh reading is taken first (the same one a volume key takes); if it cannot be confirmed, nothing is
     * saved and the answer says so. What was read must belong to the output, the connection and the profile the request was made on
     * ([SafetyMaxCalibrationPolicy]); anything else is [SafetyMaxCalibrationResult.OUTPUT_CHANGED].
     */
    fun useCurrentAsSafetyMax(onResult: (SafetyMaxCalibrationResult) -> Unit = {}) {
        val tapped = _state.value
        val basis = SafetyMaxCalibrationPolicy.basisOf(tapped, connectionOf(tapped.backend))
        if (basis == null) {
            onResult(SafetyMaxCalibrationResult.NOT_AVAILABLE)
            return
        }
        appScope.launch {
            var current = _state.value
            if (!current.backend.levelVerified && current.backend.kind == SafetyVolumeBackendKind.USB_HARDWARE_VOLUME) {
                // A reading, not a write: the DAC is asked what it holds, and the level it answers is published like any other.
                hardwareRefreshRequested = true
                reconcile()
                current = _state.value
            }
            val refusal = SafetyMaxCalibrationPolicy.refusal(basis, current, connectionOf(current.backend))
            if (refusal != null) {
                Timber.tag(TAG).w("Safety Max not calibrated context=%s: %s", basis.outputContextId, refusal)
                mainHandler.post { onResult(refusal) }
                return@launch
            }
            val calibration = SafetyMaxCalibration(
                outputContextId = basis.outputContextId,
                backendKind = current.backend.kind,
                ceilingRaw = current.backend.rawLevel,
                backendMinRaw = current.backend.rawMin,
                backendMaxRaw = current.backend.rawMax,
                calibratedAtEpochMs = System.currentTimeMillis(),
            )
            profiles.calibrateSafetyMax(basis.profileId, calibration)
            Timber.tag(TAG).i(
                "Safety Max calibrated context=%s ceiling=%d range=%d..%d",
                basis.outputContextId,
                calibration.ceilingRaw,
                calibration.backendMinRaw,
                calibration.backendMaxRaw,
            )
            reconcile()
            mainHandler.post { onResult(SafetyMaxCalibrationResult.SAVED) }
        }
    }

    /** The connection a level was read over: the DAC's open generation, or null where there is no such thing (the platform's volume). */
    private fun connectionOf(backend: VolumeBackendSnapshot): Long? =
        if (backend.kind == SafetyVolumeBackendKind.USB_HARDWARE_VOLUME) usbDevice.openGeneration else null

    fun setSafetyEnabled(enabled: Boolean) {
        val profileId = _state.value.selectedProfileId ?: return
        if (!enabled) {
            gesture.reset()
            screenOffGesture.reset()
            closeOverLock()
            endOverride(SafetyOverrideEnd.SAFETY_OFF)
            _state.update { it.copy(temporaryOverride = false, showOverrideConfirmation = false, overrideConfirmationOverLock = false) }
        }
        appScope.launch {
            profiles.setSafetyMaxEnabled(profileId, enabled)
            reconcile()
        }
    }

    /**
     * The one place an override is granted, and only from the confirmed gesture. Gives the override a
     * session bound to the output it was confirmed on ([SafetyOverridePolicy]); the ceiling and the
     * gesture are as they always were, only how long it lasts changed.
     */
    fun allowTemporaryOverride() {
        val current = _state.value
        if (!current.protectionActive) return
        gesture.reset()
        screenOffGesture.reset()
        val profile = profiles.state.value.selectedProfile
        val contextId = current.backend.outputContextId
        val stored = contextId?.let { profile?.safetyMax?.calibrationFor(it, current.backend.kind) }
        val ceiling = current.calibration?.ceilingRaw
        if (profile == null || contextId == null || stored == null || ceiling == null) {
            closeOverLock()
            _state.update { it.copy(showOverrideConfirmation = false, overrideConfirmationOverLock = false) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val session = SafetyOverridePolicy.grant(
            profileId = profile.profileId,
            contextId = contextId,
            kind = current.backend.kind,
            calibration = stored,
            attachment = attachmentFor(contextId),
            rawLevel = current.backend.rawLevel,
            ceilingRaw = ceiling,
            nowMs = now,
        )
        synchronized(sessionLock) {
            overrideSession = session
            persistOverride(session, now)
            scheduleOverrideCheck(session, now)
        }
        Timber.tag(TAG).i(
            "Temporary Safety Max override enabled: context=%s kind=%s ceiling=%d attachment=%s; " +
                "it ends when the level is lowered to the ceiling, after %d min without playback, " +
                "or when the output, profile or calibration changes",
            contextId,
            current.backend.kind,
            ceiling,
            session.attachment ?: "none",
            SafetyOverridePolicy.IDLE_END_MS / 60_000L,
        )
        closeOverLock()
        _state.update { it.copy(temporaryOverride = true, showOverrideConfirmation = false, overrideConfirmationOverLock = false) }
    }

    fun cancelOverrideDialog() {
        gesture.reset()
        screenOffGesture.reset()
        closeOverLock()
        _state.update { it.copy(showOverrideConfirmation = false, overrideConfirmationOverLock = false) }
    }

    /**
     * FIX-5: one Volume Up press that the ceiling refused on the remote path (the media session's
     * volume: keys with the screen off or locked, the system panel). Counts toward five in three
     * seconds; the fifth asks for the confirmation. The press itself changed nothing: the refusal
     * branch returned before any write, and nothing here writes either.
     */
    private fun noteRefusedRemotePress(singleStep: Boolean) {
        val counts = ScreenOffOverridePolicy.countsAsPress(singleStep, screenIsOffOrLocked())
        if (!counts) return
        val now = SystemClock.elapsedRealtime()
        if (!screenOffGesture.blockedVolumeUp(now)) return
        requestOverrideConfirmation(now)
    }

    private fun screenIsOffOrLocked(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return power?.isInteractive == false || keyguard?.isKeyguardLocked == true
    }

    /**
     * The fifth press. Where the confirmation goes is [ScreenOffOverridePolicy.delivery]. On the
     * dedicated appliance (read from the device, not from an activity) the screen is woken and the
     * dialog the app already has is shown above the lock screen: at once if its activity is resumed,
     * otherwise as soon as it resumes, which it does when the screen turns on. Anywhere else the F-07
     * notification stands and the dialog follows if the app comes to the front within the grace.
     */
    private fun requestOverrideConfirmation(nowMs: Long) {
        val current = _state.value
        val delivery = ScreenOffOverridePolicy.delivery(
            protectionActive = current.protectionActive,
            overrideActive = current.temporaryOverride,
            dedicated = runCatching { productMode.isDedicatedActive() }.getOrDefault(false),
            hostResumed = confirmationHost != null,
        )
        Timber.tag(TAG).i(
            "Five refused Volume Up presses at the ceiling with the screen off: confirmation %s",
            delivery,
        )
        when (delivery) {
            ScreenOffOverridePolicy.Delivery.NONE -> Unit
            ScreenOffOverridePolicy.Delivery.ON_UNLOCK ->
                pendingRequest = PendingOverrideRequest(delivery, nowMs)
            ScreenOffOverridePolicy.Delivery.OVER_LOCK_SCREEN -> openOverLockConfirmation()
            ScreenOffOverridePolicy.Delivery.WAKE_THEN_SHOW -> wakeAndWaitForActivity(nowMs)
        }
    }

    /**
     * The screen is off and the activity is stopped. The request is recorded first (the activity can
     * resume the instant the screen comes on, and must find it), then the screen is woken with a timed
     * lock that holds it for the grace; the activity's own `onResume` shows the dialog. If it never
     * comes, the pending request is dropped and the wake released when the grace is over.
     */
    private fun wakeAndWaitForActivity(nowMs: Long) {
        pendingRequest = PendingOverrideRequest(ScreenOffOverridePolicy.Delivery.WAKE_THEN_SHOW, nowMs)
        overrideScreenWaker.wake(ScreenOffOverridePolicy.WAKE_GRACE_MS + WAKE_MARGIN_MS)
        pendingDropJob?.cancel()
        pendingDropJob = appScope.launch {
            delay(ScreenOffOverridePolicy.WAKE_GRACE_MS)
            if (pendingRequest?.delivery == ScreenOffOverridePolicy.Delivery.WAKE_THEN_SHOW) {
                Timber.tag(TAG).i("The activity did not come up after the wake; dropping the override request")
                pendingRequest = null
                if (!overLockActive) overrideScreenWaker.release()
            }
        }
    }

    private fun openOverLockConfirmation() {
        if (overLockActive) return
        overLockActive = true
        overrideScreenWaker.wake(ScreenOffOverridePolicy.CONFIRM_TIMEOUT_MS + WAKE_MARGIN_MS)
        mainHandler.post { confirmationHost?.setOverLockScreen(true) }
        _state.update { it.copy(showOverrideConfirmation = true, overrideConfirmationOverLock = true) }
        // No answer in time: it closes by itself and the screen is left to go off as it normally does.
        overLockJob = appScope.launch {
            delay(ScreenOffOverridePolicy.CONFIRM_TIMEOUT_MS)
            Timber.tag(TAG).i("Override confirmation over the lock screen timed out; closing")
            cancelOverrideDialog()
        }
    }

    /** Puts back what [openOverLockConfirmation] changed: the window flags and the wake. Idempotent. */
    private fun closeOverLock() {
        overLockJob?.cancel()
        overLockJob = null
        if (!overLockActive) return
        overLockActive = false
        overrideScreenWaker.release()
        mainHandler.post { confirmationHost?.setOverLockScreen(false) }
    }

    /**
     * The activity that can show the confirmation, registered while it is resumed. It also answers a
     * request that was waiting for the app to come to the front: after the wake on the appliance (the
     * dialog is shown over the lock screen), after the unlock on a phone.
     *
     * A screen that wakes can resume, pause and resume the activity again (the Keyguard handoff does
     * exactly that on the HONOR), so a confirmation that is already open is not closed by a pause: it
     * closes on its own timeout, and a resume puts the window flags back for it.
     */
    fun attachConfirmationHost(host: OverrideConfirmationHost) {
        confirmationHost = host
        if (overLockActive) {
            mainHandler.post { host.setOverLockScreen(true) }
            return
        }
        // The activity's flags follow the controller's truth, never a leftover.
        mainHandler.post { host.setOverLockScreen(false) }
        val pending = pendingRequest ?: return
        pendingRequest = null
        pendingDropJob?.cancel()
        val current = _state.value
        val show = ScreenOffOverridePolicy.showWhenAppComesUp(
            delivery = pending.delivery,
            pendingSinceMs = pending.sinceMs,
            nowMs = SystemClock.elapsedRealtime(),
            protectionActive = current.protectionActive,
            overrideActive = current.temporaryOverride,
            level = current.backend.rawLevel,
            ceilingRaw = current.calibration?.ceilingRaw?.takeIf { current.safetyEnabled },
        )
        Timber.tag(TAG).i(
            "App in front after a screen-off override request (%s): showing the confirmation=%s",
            pending.delivery,
            show,
        )
        when {
            show && pending.delivery == ScreenOffOverridePolicy.Delivery.WAKE_THEN_SHOW -> openOverLockConfirmation()
            show -> _state.update { it.copy(showOverrideConfirmation = true) }
            // Too late, or no longer the case: the screen was woken for nothing; let it go.
            pending.delivery == ScreenOffOverridePolicy.Delivery.WAKE_THEN_SHOW -> overrideScreenWaker.release()
        }
    }

    /** The activity left the front. An open confirmation is not closed by this alone (see [attachConfirmationHost]). */
    fun detachConfirmationHost(host: OverrideConfirmationHost) {
        if (confirmationHost === host) confirmationHost = null
    }

    private fun updateState(
        backend: VolumeBackendSnapshot,
        profileId: String?,
        profileName: String?,
        enabled: Boolean,
        calibration: SafetyMaxCalibration?,
        needsRefresh: Boolean,
        override: Boolean,
    ) {
        _state.update {
            it.copy(
                backend = backend,
                hardwareDecibels = hardwareDecibelsOf(backend),
                // The session's verdict, written here and in `allowTemporaryOverride` (the grant).
                temporaryOverride = override,
                selectedProfileId = profileId,
                selectedProfileName = profileName,
                safetyEnabled = enabled,
                calibration = calibration,
                // Also set while a rescaled calibration is enforced: it protects, but was not taken
                // on this range, so the listener is asked to recalibrate (AUD-074).
                calibrationNeedsRefresh = needsRefresh,
            )
        }
    }

    /**
     * Writes [raw] to [backend] and returns what the backend reported (B27-A3, review R-03). The result is never inferred from the request.
     */
    private fun setBackendRaw(manager: AudioManager, backend: VolumeBackendSnapshot, raw: Int, origin: String): VolumeWrite {
        if (backend.kind == SafetyVolumeBackendKind.USB_HARDWARE_VOLUME) {
            val range = hardwareRange ?: return VolumeWrite(raw, null)
            val units = UsbHardwareVolumePolicy.unitsFor(raw, range)
            val generation = usbDevice.openGeneration
            var readBack: Int? = null
            // B15-F1: the listener's and Safety's write: serialised with a duck's steps, and it ends any duck.
            val held = dacVolume.listenerWrite {
                readBack = usbDevice.writeHardwareVolume(units, origin)
                // A write that failed part-way (one channel refused it) leaves the DAC at some level, and the loudest channel is what
                // counts: ask it. A reading, not a second write: the level that is published is one the DAC reported.
                readBack ?: usbDevice.readHardwareVolume()
            }
            if (usbDevice.openGeneration != generation) {
                // The DAC was reopened (or swapped) during the write: nothing read belongs to the one the caller was writing.
                Timber.tag(TAG).w("DAC changed during a volume write; not credited")
                hardwareRefreshRequested = true
                return VolumeWrite(raw, null)
            }
            if (held == null) {
                // Not retried and not re-reconciled from here: reconcile's own clamp calls this, and a DAC that keeps refusing
                // would loop. The level it holds is unknown: the next event re-reads the real level, and until then it is unverified.
                Timber.tag(TAG).w("DAC hardware volume write failed; its level is unknown")
                hardwareRefreshRequested = true
                hardwareLevelUnverified = true
                return VolumeWrite(raw, null)
            }
            hardwareUnitsCache = held
            readBack?.let { written -> backend.outputContextId?.let { hardwareStore.save(it, written) } }
            // Exactly what was asked is the step asked for; anything else is taken for the louder step it could be, never the quieter.
            val step = if (held == units) raw else UsbHardwareVolumePolicy.stepCeilFor(held, range)
            if (step != raw) {
                Timber.tag(TAG).w("DAC hardware volume write asked step %d, the DAC reports step %d", raw, step)
                hardwareRefreshRequested = true
            }
            hardwareLevelUnverified = false
            return VolumeWrite(raw, step)
        }
        selfWriteRaw = raw
        return runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, raw, 0) }
            .onFailure { Timber.tag(TAG).e(it, "Unable to set Android media volume") }
            .fold(onSuccess = { VolumeWrite(raw, raw) }, onFailure = { VolumeWrite(raw, null) })
    }

    /**
     * [UsbAudioDevice.volumeGuard]: what a DAC may play at when it is opened, before any audio.
     *
     * Runs on whichever thread opens the device, so it reads only published state. Without a stable
     * DAC identity there is no saved level and no calibration to consult, and the DAC is left as is.
     *
     * B16-G1: the driver also calls it **while holding its own monitor** (`restoreLevelAfterReconfigure` is
     * `@Synchronized`, and runs on every rate, alt or clock change). So it must take no lock another thread can hold while
     * it waits for that monitor. It takes none: it reads `StateFlow` values, the volatile override session, the
     * `SharedPreferences` (a leaf lock nothing holds across a DAC call) and the clock, and tells the arbiter
     * ([DacVolumeArbiter.supersede]), whose lock is that same monitor, entered again.
     */
    private fun hardwareOpenTarget(range: UsbVolumeRange, currentUnits: Int): Int? {
        val dac = uniqueConnectedDac(dacProfiles.profiles.value, dacProfiles.connected.value) ?: return null
        val contextId = "dac:${dac.profileId}"
        val selected = profiles.state.value.selectedProfile
        val snapshot = hardwareSnapshot(contextId, dac.displayName, range, currentUnits)
        val storedCalibration = selected?.safetyMax
            ?.takeIf { it.enabled }
            ?.calibrationFor(contextId, SafetyVolumeBackendKind.USB_HARDWARE_VOLUME)
        val ceilingUnits = storedCalibration
            ?.let { SafetyVolumePolicy.effectiveCalibration(it, snapshot) }
            ?.let { UsbHardwareVolumePolicy.unitsFor(it.ceilingRaw, range) }
        // FIX-4: this runs at every open *and every rate change* (FU_RECONFIGURE), and used to lower
        // the DAC to the ceiling each time, taking an override away at the first track with another
        // sample rate (2026-10-06 11:13:20: -12 dB -> -26 dB). While an override stands for this very
        // DAC, profile and calibration, the level it has is the level the listener chose. The guard
        // still only ever lowers (to what was last chosen), never raises.
        val overrideStands = SafetyOverridePolicy.coversOpen(
            overrideSession,
            observationFor(overrideSession, SystemClock.elapsedRealtime(), snapshot, ceilingRaw = null),
        )
        val saved = hardwareStore.saved(contextId)
        val target = UsbHardwareVolumePolicy.openTarget(currentUnits, saved, ceilingUnits.takeUnless { overrideStands })
        // B15-F1: the driver lowers the DAC to this after we return: Safety's write, so a duck on it is over.
        if (target != null) dacVolume.supersede()
        // The real ceiling apart from the guard's answer: the driver logs that one as `guardTarget=`,
        // the lower of the saved level and this ceiling. `none` here means nothing bounds a rise of the
        // DAC's level (AUD-126) — it was invisible while the two shared the name `ceiling=`.
        Timber.tag(TAG).i(
            "DAC opened context=%s current=%.1fdB saved=%s safetyCeiling=%s override=%s target=%s",
            contextId,
            UsbHardwareVolumePolicy.decibels(currentUnits),
            saved?.let { "%.1fdB".format(UsbHardwareVolumePolicy.decibels(it)) } ?: "none",
            ceilingUnits?.let { "%.1fdB".format(UsbHardwareVolumePolicy.decibels(it)) } ?: "none",
            if (overrideStands) "standing" else "none",
            target?.let { "%.1fdB".format(UsbHardwareVolumePolicy.decibels(it)) } ?: "unchanged",
        )
        return target
    }

    // USB control transfers are blocking, up to 250 ms each, and reconcile runs on the main thread
    // for every route, profile or Settings.System change. Reading the DAC there on every call froze
    // the UI (measured: 35 % janky frames, p99 400 ms). The range is fixed for a connection, so it is
    // read once per open; the level is re-read only on a new open, a key press, or after our write.
    private var hardwareCacheGeneration = -1L
    private var hardwareRangeCache: UsbVolumeRange? = null
    @Volatile
    private var hardwareUnitsCache: Int? = null
    private var hardwareRefreshRequested = false

    /**
     * B27-A3: the last write to the DAC was refused or could not be read back, so [hardwareUnitsCache] is the last level that was
     * known, not a reading. Cleared by the next successful read of the DAC; the snapshot built meanwhile says so.
     */
    @Volatile
    private var hardwareLevelUnverified = false

    private fun hardwareLevel(): Pair<UsbVolumeRange?, Int?> {
        if (!usbDevice.hasHardwareVolume) {
            hardwareCacheGeneration = -1L
            hardwareRangeCache = null
            hardwareUnitsCache = null
            hardwareLevelUnverified = false
            return null to null
        }
        val generation = usbDevice.openGeneration
        if (generation != hardwareCacheGeneration) {
            hardwareCacheGeneration = generation
            hardwareRangeCache = usbDevice.readHardwareVolumeRange()
            hardwareUnitsCache = hardwareRangeCache?.let { usbDevice.readHardwareVolume() }
            hardwareLevelUnverified = false  // a DAC opened afresh is read afresh; no reading at all is FIXED_OUTPUT below
        } else if (hardwareRefreshRequested && hardwareRangeCache != null) {
            val read = usbDevice.readHardwareVolume()
            if (read != null) {
                hardwareUnitsCache = read
                hardwareLevelUnverified = false
            }
        }
        hardwareRefreshRequested = false
        return hardwareRangeCache to hardwareUnitsCache
    }

    private fun hardwareDecibelsOf(backend: VolumeBackendSnapshot): Float? {
        if (backend.kind != SafetyVolumeBackendKind.USB_HARDWARE_VOLUME) return null
        val range = hardwareRange ?: return null
        // The safety step is an upper bound, including a final partial step above the selectable range.
        return UsbHardwareVolumePolicy.decibels(range.minUnits + backend.rawLevel * UsbHardwareVolumePolicy.stepUnits(range))
    }

    private fun hardwareSnapshot(
        contextId: String?,
        label: String,
        range: UsbVolumeRange,
        currentUnits: Int,
    ) = VolumeBackendSnapshot(
        SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
        contextId,
        label,
        UsbHardwareVolumePolicy.stepCeilFor(currentUnits, range),
        0,
        UsbHardwareVolumePolicy.maxStep(range),
        true,
        levelVerified = !hardwareLevelUnverified,
    )

    private fun resolveBackend(
        manager: AudioManager,
        knownDacs: List<DacProfile>,
        connectedDacs: List<UsbDacDescriptor>,
    ): VolumeBackendSnapshot {
        val dac = uniqueConnectedDac(knownDacs, connectedDacs)
        val direct = dualPlayerEngine.audioRouteState.value.actualBackend == AudioBackend.USB_DIRECT
        if (direct) {
            val (range, currentUnits) = hardwareLevel()
            if (range != null && currentUnits != null) {
                hardwareRange = range
                return hardwareSnapshot(
                    dac?.let { "dac:${it.profileId}" },
                    dac?.displayName ?: "USB Direct",
                    range,
                    currentUnits,
                )
            }
            hardwareRange = null
            return VolumeBackendSnapshot(
                SafetyVolumeBackendKind.FIXED_OUTPUT,
                dac?.let { "dac:${it.profileId}" },
                dac?.displayName ?: "USB Direct",
                0, 0, 0, false,
            )
        }
        hardwareRange = null
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getStreamMinVolume(AudioManager.STREAM_MUSIC).coerceAtMost(max)
        } else 0
        val raw = manager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(min, max)
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { manager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES) }.getOrDefault(emptyList())
        } else emptyList()
        val output = routed.firstOrNull() ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type in OUTPUT_TYPES
        }
        val usb = output?.type in USB_TYPES
        val contextId = when {
            usb && dac != null -> "dac:${dac.profileId}"
            output?.type in BLUETOOTH_TYPES -> "android:bluetooth"
            output?.type in WIRED_TYPES -> "android:wired_headphones"
            output?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "android:built_in_speaker"
            else -> "android:media_default"
        }
        val label = when {
            usb && dac != null -> dac.displayName
            output?.type in BLUETOOTH_TYPES -> output?.productName?.toString()?.takeIf(String::isNotBlank) ?: "Bluetooth"
            output?.type in WIRED_TYPES -> "Wired headphones"
            output?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            else -> "Android Audio"
        }
        return VolumeBackendSnapshot(
            SafetyVolumeBackendKind.ANDROID_MEDIA,
            contextId,
            label,
            raw,
            min,
            max,
            !manager.isVolumeFixed,
        )
    }

    private fun uniqueConnectedDac(
        profiles: List<DacProfile>,
        connected: List<UsbDacDescriptor>,
    ): DacProfile? = selectSafetyDac(profiles, connected, usbDevice.openedDevicePath)

    companion object {
        private const val TAG = "SafetyVolume"

        /** B12a-8 #3: how long the published profile state may take to catch up with the store's first read. */
        private const val CONTEXT_LOAD_WAIT_MS = 3_000L

        /** F-07: one notice per burst of presses; the notification updates in place after that. */
        const val REFUSAL_MIN_INTERVAL_MS = 2_000L

        /** FIX-5: the wake outlives the confirmation's own timeout by this much, so it never ends first. */
        private const val WAKE_MARGIN_MS = 1_000L
        private val MEDIA_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        private val USB_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        private val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
        )
        private val OUTPUT_TYPES = USB_TYPES + BLUETOOTH_TYPES + WIRED_TYPES + AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }
}
