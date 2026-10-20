package com.lostf1sh.pixelplayeross.data.service.player

import android.app.ActivityManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.flac.FlacExtractor
import com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqController
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.diagnostics.HardwareEventLog
import com.lostf1sh.pixelplayeross.data.diagnostics.HardwareEventType
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.di.AppScope
import com.lostf1sh.pixelplayeross.data.model.TransitionSettings
import com.lostf1sh.pixelplayeross.data.preferences.RandomMode
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import android.hardware.usb.UsbDevice
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.DirectStreamLoss
import com.decent.usbaudio.media3.UsbAudioSink
import com.decent.usbaudio.media3.UsbAudioSinkConfig
import com.decent.usbaudio.media3.UsbAudioSinkDiagnostics
import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.utils.crossfadeGains
import com.lostf1sh.pixelplayeross.utils.timedSection
import com.lostf1sh.pixelplayeross.utils.traceSection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume


data class ActiveDecoderInfo(
    val name: String,
    val isHardware: Boolean
)

internal fun shouldResumeAfterTransientAudioFocusLoss(
    masterPlayWhenReady: Boolean,
    masterIsPlaying: Boolean,
    transitionRunning: Boolean,
    auxiliaryPlayWhenReady: Boolean,
    auxiliaryIsPlaying: Boolean
): Boolean {
    return masterPlayWhenReady ||
        masterIsPlaying ||
        (transitionRunning && (auxiliaryPlayWhenReady || auxiliaryIsPlaying))
}

internal fun shouldDisableAudioOffloadByDefaultForDevice(
    manufacturer: String,
    brand: String,
    model: String,
    hardware: String,
    sdkInt: Int
): Boolean {
    val manufacturerName = manufacturer.trim().lowercase()
    val brandName = brand.trim().lowercase()
    val modelName = model.trim().lowercase()
    val hardwareName = hardware.trim().lowercase()

    val isXiaomiFamilyDevice = manufacturerName == "xiaomi" ||
        brandName == "xiaomi" ||
        brandName == "redmi" ||
        brandName == "poco"
    if (isXiaomiFamilyDevice && sdkInt >= 36) return true

    val isGooglePixelDevice = manufacturerName == "google" || brandName == "google"
    if (isGooglePixelDevice && sdkInt >= 37) return true

    val isLavaDevice =
        manufacturerName == "lava" ||
            brandName == "lava"
    val looksLikeMtkHardware =
        hardwareName.startsWith("mt") ||
            hardwareName.contains("mediatek") ||
            hardwareName.contains("mtk")
    val isReportedLxxFamily = modelName.startsWith("lxx") && isLavaDevice
    val isMtkLavaVariant = isLavaDevice && looksLikeMtkHardware

    return sdkInt >= 35 && (isReportedLxxFamily || isMtkLavaVariant)
}

/**
 * Whether the transport may be switched right now — the Direct-transition permission.
 *
 * The rule is "do not interrupt Android Audio that is audibly playing", and nothing else.
 * `isPlaying` is Media3's own answer to "is this player producing sound", which is the only
 * question that rule asks; `directIsActive` appears only so that a session already running on
 * Direct is not treated as "playing through another backend".
 *
 * It is deliberately **not** a function of the play intent. An earlier version required
 * `!playWhenReady`, which meant that a play intent arriving with Direct eligible could never be
 * granted Direct — the decision fell back to Android, the rebuild it triggered destroyed the
 * Direct-active state, and the player's own callbacks re-evaluated into the same refusal. That
 * cycle is why this is a named function with a test rather than an inline expression: the
 * property that matters is that the composed decision reaches a fixed point, and it cannot be
 * asserted from inside the engine.
 */
internal fun isDirectTransitionAllowed(
    masterInitialised: Boolean,
    directIsActive: Boolean,
    isPlaying: Boolean,
): Boolean = !masterInitialised || directIsActive || !isPlaying

internal fun shouldTriggerAudioOffloadStallFallback(
    audioOffloadEnabled: Boolean,
    transitionRunning: Boolean,
    isCurrentMasterPlayer: Boolean,
    mediaIdMatches: Boolean,
    playbackState: Int,
    isPlaying: Boolean,
    playWhenReady: Boolean,
    playbackSuppressionReason: Int
): Boolean {
    return audioOffloadEnabled &&
        !transitionRunning &&
        isCurrentMasterPlayer &&
        mediaIdMatches &&
        playWhenReady &&
        !isPlaying &&
        playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
        playbackState != Player.STATE_IDLE &&
        playbackState != Player.STATE_ENDED
}

/**
 * Decides whether an early STATE_BUFFERING (within ~500ms of audio playing) should be read
 * as a HAL offload reset and trigger disabling offload for the session.
 *
 * The buffering is NOT treated as a HAL reset when it is explained by a recent user seek
 * ([isPostSeekBuffering]) or by a just-finished crossfade ([isPostTransitionBuffering]) —
 * in those cases the buffering is expected, and disabling offload would needlessly drop the
 * battery saving and rebuild the player (an audible glitch).
 */
internal fun shouldDisableAudioOffloadOnEarlyBuffering(
    audioOffloadEnabled: Boolean,
    transitionRunning: Boolean,
    lastPlayingAtMs: Long,
    timeSincePlayingMs: Long,
    isPostSeekBuffering: Boolean,
    isPostTransitionBuffering: Boolean,
    isPostMediaItemTransition: Boolean
): Boolean {
    return audioOffloadEnabled &&
        !transitionRunning &&
        lastPlayingAtMs > 0L &&
        timeSincePlayingMs < 500L &&
        !isPostSeekBuffering &&
        !isPostTransitionBuffering &&
        !isPostMediaItemTransition
}

/**
 * Manages two ExoPlayer instances (A and B) to enable seamless transitions.
 *
 * Player A is the designated "master" player. During a crossfade the MediaSession can
 * expose Player B early for UI continuity, while Player A remains alive to fade out.
 * Player B is the auxiliary player used to pre-buffer and fade in the next track.
 * After a transition, Player A adopts the state of Player B, ensuring continuity.
 */
@OptIn(UnstableApi::class)
@Singleton
class DualPlayerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val headphoneGraphicEqController: HeadphoneGraphicEqController,
    private val dacProfiles: DacProfileRepository,
    private val maintenanceOverrides: MaintenanceOverrideStore,
    @AppScope appScope: CoroutineScope,
) {
    /**
     * The Direct transport's live answer to "pristine or processed, and what does that mean for
     * the samples".
     *
     * One instance for the whole engine rather than one per player build, because the answer is a
     * property of the listening state, not of the player. A rebuild therefore cannot produce a sink
     * that disagrees with the route the policy chose — both read the same object.
     */
    /**
     * The master player's volume, mirrored so the audio thread can read it.
     *
     * ExoPlayer enforces thread affinity on every accessor, including `getVolume`, and the Direct
     * path reads the gain from the *playback* thread — `publishSignalTruth` asks the stage which
     * processors are active, and naming the gain stage means reading this value. Reading the player
     * directly from there throws `IllegalStateException: Player is accessed on the wrong thread`,
     * which surfaces as an `ExoPlaybackException` and stops playback entirely.
     *
     * So the value is mirrored on the main thread — the only thread allowed to ask — and the audio
     * path reads the mirror. [Player.Listener.onVolumeChanged] is the complete set of changes,
     * because every path that alters this value (a user gesture, ReplayGain, a crossfade) does so
     * through the player.
     */
    @Volatile
    private var cachedMasterVolume: Float = 1f

    /**
     * The three gain values, kept distinct.
     *
     * `player.volume` holds their product and is an *output*; deriving a requirement from it would
     * attribute ReplayGain's attenuation to the user's own volume control, and the two have to be
     * nameable separately. The routing policy reads this instead, so each requirement is owned by
     * the contributor that actually causes it.
     */
    data class GainModel(
        /** The user's own digital level. */
        val userDigitalGain: Float,
        /** The ReplayGain factor, or null when ReplayGain is contributing nothing. */
        val replayGainFactor: Float?,
        /** `userDigitalGain * replayGainFactor * profileCompensation`, clamped — what `player.volume` holds. */
        val combinedGain: Float,
        /** The active headphone profile's boost compensation, ≤ 1 (AUD-025). */
        val profileCompensation: Float = 1f,
    )

    private val _gainModel = MutableStateFlow(GainModel(1f, null, 1f))
    val gainModel: StateFlow<GainModel> = _gainModel.asStateFlow()

    /** Published by the ReplayGain processor whenever either contributor changes. */
    fun publishGainModel(
        userDigitalGain: Float,
        replayGainFactor: Float?,
        combinedGain: Float,
        profileCompensation: Float = 1f,
    ) {
        _gainModel.value = GainModel(
            userDigitalGain = userDigitalGain.coerceIn(0f, 1f),
            replayGainFactor = replayGainFactor,
            combinedGain = combinedGain.coerceIn(0f, 1f),
            profileCompensation = profileCompensation.coerceIn(0f, 1f),
        )
    }

    /**
     * Sets the master player's volume from the debug bridge.
     *
     * Deliberately the same assignment a user gesture makes, so the resulting state — including the
     * `onVolumeChanged` echo ReplayGain uses to distinguish a programmatic change from a real one —
     * is exactly what a volume keypress would produce. Diagnostic only.
     */
    /**
     * Diagnostic only: forwards a synthetic ReplayGain factor.
     *
     * The processor lives in the service, not here, so this is a registered callback rather than a
     * direct reference — the engine must not grow a dependency on the service.
     */
    @Volatile
    private var replayGainFactorSetter: ((Float?) -> Unit)? = null

    fun registerDiagnosticReplayGainSetter(setter: (Float?) -> Unit) {
        replayGainFactorSetter = setter
    }

    /** Diagnostic only: sets the user's gain *contributor*, not the composed player volume. */
    @Volatile
    private var userGainSetter: ((Float) -> Unit)? = null

    fun registerDiagnosticUserGainSetter(setter: (Float) -> Unit) {
        userGainSetter = setter
    }

    fun setDiagnosticUserGain(gain: Float) {
        userGainSetter?.invoke(gain)
    }

    /**
     * The user's own digital level, in PROCESSED the second volume beside the DAC's hardware one.
     *
     * Writes the contributor, not the composed `player.volume`, for the same reason the diagnostic
     * path does; see `ReplayGainProcessor.setDiagnosticUserGain`. Main-thread affine like the
     * processor it reaches.
     */
    fun setUserDigitalGain(gain: Float) {
        mainHandler.post { userGainSetter?.invoke(gain.coerceIn(0f, 1f)) }
    }

    /**
     * Diagnostic only. True when the processor received [factor]: the setter is registered when the
     * service builds its processor, so before that (or without a service) the call is delivered to nobody,
     * and the bridge says so instead of reporting a factor that never arrived (B9-4).
     */
    fun setDiagnosticReplayGainFactor(factor: Float?): Boolean {
        val setter = replayGainFactorSetter ?: return false
        setter(factor)
        return true
    }

    fun setDiagnosticVolume(volume: Float) {
        mainHandler.post {
            if (::playerA.isInitialized) {
                playerA.volume = volume.coerceIn(0f, 1f)
            }
        }
    }

    private val directSignalController = DirectSignalController(
        requirements = { processingRequirements },
        graphicEq = { headphoneGraphicEqController.settings() },
        // The player's volume is where the user's digital level and ReplayGain both land, so
        // reading it here is what makes that control real on the Direct path instead of a value
        // that only ever reached a muted delegate.
        masterVolume = { cachedMasterVolume },
        // A "may duck" request while the signal is PROCESSED (B14-3): the stage ramps toward this.
        duckGain = { directDuckGainTarget },
        // For attribution only. The arithmetic still uses the product; these let the truth surface
        // name each contributor separately, which a product cannot.
        userDigitalGainProvider = { _gainModel.value.userDigitalGain },
        replayGainFactorProvider = { _gainModel.value.replayGainFactor },
        // Diagnostics only: a renderer-domain anchor is only interpretable next to the player
        // state that produced it. Never an anchor itself (AUD-139).
        handoffContextProvider = { diagnosticHandoffContext },
        // DEBUG ONLY: plan B (B12a) — the persisted switch that brings the native engine back, loaded once
        // here (not on the audio thread) and read from the cache. Off unless the debug bridge turned it
        // on, and inert in a release build: the ExoPlayer pipeline carries PRISTINE and no engine exists.
        debugForceNativeEngineProvider = NativeEngineDebugSwitch.shared(context)::isForced,
        // DEBUG ONLY (B12a-4): the persisted pre-roll after a rate change, 0 by default and in release.
        rateSwitchPrerollMsProvider = RateSwitchPrerollSetting.shared(context)::ms,
    )

    private companion object {
        private const val AUDIO_OFFLOAD_STALL_FALLBACK_MS = 4_000L
        /** How often a held Android-USB decision re-reads `/proc/asound` (AUD-003). */
        private const val USB_ROUTE_PROBE_INTERVAL_MS = 250L
        private const val POST_TRANSITION_OFFLOAD_GUARD_MS = 2_000L
        private const val MAX_AUXILIARY_TIMELINE_ITEMS = 200
        /** TEMPORARY measurement instrumentation — see [traceRoute]. */
        private const val TRACE_MAX_LINES = 1500
        private val LOCAL_MEDIA_SCHEMES = setOf("content", "file", "android.resource")
        private val REMOTE_MEDIA_SCHEMES = setOf("http", "https")
    }

    data class TransitionTarget(
        val mediaItem: MediaItem,
        val absoluteIndex: Int,
        val queueSize: Int
    )

    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    /** The listening state as the app published it, before the Purity lock is applied to it. Read by the signal path's thread. */
    @Volatile
    private var rawProcessingRequirements = AudioProcessingRequirements()

    /** The Purity lock *preference* (B16-G4): whether it is in force depends on the DAC, which only the router knows. */
    @Volatile
    private var pristineLockPreference = false

    /** Whether the router found a USB DAC the last time it decided; null until it has (the lock is then taken as in force). */
    @Volatile
    private var usbDacPresentFact: Boolean? = null

    /**
     * What the route is decided from, and what the Direct signal path reads: the listening state with the Purity lock
     * applied **only while a DAC is connected** ([PristineModePolicy.effectiveRequirements], B16-G4). Computed from its three
     * inputs every time it is read, so a DAC appearing or going away changes it without anyone having to remember to.
     */
    private val processingRequirements: AudioProcessingRequirements
        get() = PristineModePolicy.effectiveRequirements(rawProcessingRequirements, pristineLockPreference, usbDacPresentFact)
    private var processingRequirementsKnown = false
    private var desiredBackend = AudioBackend.ANDROID_AUDIO

    /**
     * Whether the master player currently in hand was constructed with the USB Direct sink
     * enabled. Desired backend alone is not enough to know this: [publishUsbDiagnostics] writes
     * [desiredBackend] when a DAC becomes available, which can happen *after* the running player
     * was already built Android-only. Without this flag that order leaves a player that can never
     * claim the exclusive route, and nothing rebuilds it, because the backend never "changed".
     */
    private var playerBuiltWithDirectAccess = false

    /**
     * Whether the Direct-capable master player in hand has already been given its claim opportunity
     * against a claimable source.
     *
     * Capability is not claim. The Direct claim lives inside `UsbAudioSink.configure()`, which the
     * audio renderer only reaches once the player has been prepared from a media item — so a
     * Direct-capable player built against an empty queue has never had the chance to claim, and
     * must not have that chance permanently consumed. This latch records the difference: it is set
     * only when a Direct-capable player is built *while there was something to configure the sink
     * with*, and is cleared by building a new player. [MasterRebuildFacts.directClaimAttempted] is
     * how it reaches the rebuild decision.
     */
    private var directClaimAttempted = false

    /**
     * Keeps the rebuild from being re-entered by the route reevaluation it triggers itself.
     *
     * See [MasterRebuildCycle] for the failure this exists to prevent; it is not a defensive nicety,
     * it is the fix for a measured reboot storm that killed the process.
     */
    private val masterRebuildCycle = MasterRebuildCycle()

    /**
     * The single automatic Direct handoff: pauses briefly so the route machinery can claim Direct
     * on attach, then restores playback. Purely reactive — it never decides a route. See
     * [AutoDirectHandoff].
     */
    private val autoDirectHandoff = AutoDirectHandoff()

    /** A failed Direct claim, per attachment and source rate, so it is not retried every track. */
    private val directFailureLatch = DirectFailureLatch()

    /**
     * Holds playback while the Android path has no Android USB output to play to (AUD-003); see
     * [AndroidUsbRouteGuard]. The route appearing is observed through [audioOutputsCallback].
     */
    private val androidUsbRouteGuard = AndroidUsbRouteGuard()

    /**
     * B25-S1: pauses when the USB DAC the music is playing to goes away, before the route can fall back to the speaker.
     * Fed by every observation of the bus (the attach/detach broadcast, the sink's own report of a dead stream, each route
     * evaluation); see [OutputLossGuard].
     */
    private val outputLossGuard = OutputLossGuard()

    /** The service's side of the pause (it arms the reconnect rule); null in tests and before the service starts. */
    @Volatile
    var outputLossListener: OutputLossListener? = null

    private val _lastOutputLoss = MutableStateFlow<OutputLossReason?>(null)

    /** Why the last output-loss pause happened (null until one has). A diagnostic; nothing reads it to decide. */
    val lastOutputLoss: StateFlow<OutputLossReason?> = _lastOutputLoss.asStateFlow()

    /**
     * One observation of whether a USB audio device is there. A DAC that was there and is not any more, while the player
     * is asked to play, pauses it **here, first**: the rebuild that follows a lost DAC carries the play intent over to the
     * Android path, and the sink's fall-back plays to whatever Android routes to, which is the speaker.
     *
     * The pause also ends what could restart playback by itself on a route that no longer exists: an owed focus resume, a
     * hold waiting for an Android USB output ([AndroidUsbRouteGuard.onDeviceLost]). A DAC that leaves while the player is
     * paused therefore leaves it paused.
     */
    private fun observeUsbPresence(present: Boolean, trigger: String, directActive: Boolean = _audioRouteState.value.isDirectActive) {
        val wasPresent = outputLossGuard.isDacPresent
        val playing = ::playerA.isInitialized && (playerA.playWhenReady || transitionEndPause)
        val loss = outputLossGuard.onUsbPresence(present, playWhenReady = playing, directActive = directActive)
        if (wasPresent && !present) {
            isFocusLossPause = false
            pausedByDuck = false
            if (androidUsbRouteGuard.isHolding) {
                androidUsbRouteGuard.onDeviceLost()
                mainHandler.removeCallbacks(androidUsbRouteTimeout)
            }
        }
        if (loss != null) pauseForOutputLoss(loss, trigger)
    }

    private fun pauseForOutputLoss(reason: OutputLossReason, trigger: String) {
        traceRoute("OUTPUT_LOST", "reason=$reason trigger=\"$trigger\" ${playerFacts()}; pausing before the route falls back")
        Timber.tag("DualPlayerEngine").w("Output lost (%s, %s): pausing playback", reason, trigger)
        _lastOutputLoss.value = reason
        // B27-A1 (R-01): first, so that no crossfade that was waiting for its incoming player can play again once it is ready.
        invalidatePendingTransition("output lost ($reason)")
        // The service first: it arms the reconnect rule for the pause that follows, which the player reports as an ordinary one.
        outputLossListener?.onPlaybackPausedForOutputLoss(reason)
        playerA.playWhenReady = false
        playerB?.playWhenReady = false
    }

    private val androidUsbRouteTimeout = Runnable {
        if (!androidUsbRouteGuard.expire(SystemClock.elapsedRealtime())) return@Runnable
        traceRoute("USB_ROUTE", "Android USB output never appeared; leaving playback paused")
        _audioRouteState.value = _audioRouteState.value.copy(
            directError = "Android USB route unavailable; replug the DAC to play through it",
        )
    }

    private fun readProcText(path: String): String? =
        runCatching { java.io.File(path).takeIf { it.canRead() }?.readText() }.getOrNull()

    /** While the guard holds, re-check the USB output: a card's stream appears without any Android event. */
    private val usbRouteProbe = object : Runnable {
        override fun run() {
            if (!androidUsbRouteGuard.isHolding) return
            reevaluateAutomaticRoute("USB playback probe")
            if (androidUsbRouteGuard.isHolding) mainHandler.postDelayed(this, USB_ROUTE_PROBE_INTERVAL_MS)
        }
    }

    private var audioOutputsCallbackRegistered = false
    /** A new USB output after the hand-back is the signal when `/proc/asound` is unreadable (AUD-003). */
    private val usbRouteSignal = UsbRouteSignal()

    private val audioOutputsCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
            val usbOutputAdded = addedDevices.orEmpty().any {
                it.isSink && (it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE)
            }
            usbRouteSignal.onOutputDeviceAdded(usbOutputAdded)
            if (androidUsbRouteGuard.isHolding) reevaluateAutomaticRoute("Audio outputs changed")
        }
    }

    /**
     * Whether the platform can currently play to the USB DAC. Read from `/proc/asound` (a USB-Audio
     * card with a playback stream), because Android's listing keeps the original attach's
     * `usb_headset` even when the card came back without a stream (AUD-003, measured). Only when
     * `/proc/asound` is unreadable does it fall back to that listing.
     */
    private fun androidUsbRouteAvailable(): Boolean {
        val probe = UsbPlaybackProbe.evaluate(readProcText("/proc/asound/cards")) { card ->
            readProcText("/proc/asound/card$card/stream0")
        }
        val manager = context.getSystemService(AudioManager::class.java)
        val platformListsUsb = manager == null || manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
        // Readable probe decides; otherwise, after a hand-back only a new USB output event counts.
        return usbRouteSignal.available(probe, platformListsUsb)
    }

    /**
     * Bounded wait for a handoff to reach Direct before falling back to Android.
     *
     * A timeout, not a delay: it fires only if [AudioRouteState.transition] has not become
     * [AudioRouteTransition.DIRECT_ACTIVE], and its whole purpose is that a failed Direct claim
     * cannot leave the listener in silence.
     */
    private val handoffTimeout = Runnable {
        if (!autoDirectHandoff.isInProgress) return@Runnable
        if (!autoDirectHandoff.hasTimedOut(SystemClock.elapsedRealtime())) return@Runnable
        val resume = autoDirectHandoff.timeoutResumes()
        traceRoute(
            "HANDOFF",
            if (resume) "timeout waiting for DIRECT_ACTIVE; falling back"
            else "timeout waiting for a safe DAC level; leaving playback paused"
        )
        concludeHandoff(resumeIfOwed = resume)
    }

    /**
     * The Safety controller's latest answer to "may the handoff resume at this DAC level", pushed
     * from its state collector (AUD-002). WAIT until it has reconciled the DAC; the engine cannot
     * depend on the controller directly, which itself depends on the engine.
     */
    private var handoffVolumeVerdict = HandoffVolumeVerdict.WAIT

    fun onHandoffVolumeVerdict(verdict: HandoffVolumeVerdict) {
        mainHandler.post {
            handoffVolumeVerdict = verdict
            onRouteStatePublished(audioRouteState.value)
        }
    }

    /** The single owner of USB permission requests (AUD-031); main thread only. */
    private val permissionCoordinator = UsbPermissionCoordinator()

    /** Unregisters the receiver of each pending request, so an unanswered one does not leak. */
    private val pendingPermissionCancels = mutableMapOf<String, () -> Unit>()

    private val permissionTimeout = Runnable {
        permissionCoordinator.expire(SystemClock.elapsedRealtime()).forEach { device ->
            pendingPermissionCancels.remove(device)?.invoke()
            traceRoute("PERMISSION", "no answer for $device; request expired and may be asked again")
        }
    }

    /**
     * Container-provided entry point that suspends Lock Task for one interactive system dialog.
     * Null until the activity installs it, so the engine never depends on it being present.
     */
    @Volatile private var usbPermissionRequester: ((() -> Unit) -> Boolean)? = null
    private var audioOffloadEnabled = !shouldDisableAudioOffloadByDefault()
    private var transitionJob: Job? = null
    private var bufferingFallbackJob: Job? = null
    private var transitionRunning = false

    /**
     * B27-A1 (review R-01): the ticket a pending crossfade shows after every wait, and whether its fade has begun. A crossfade
     * that has not played anything yet is dropped by anything that pauses or replaces what it was built on ([invalidatePendingTransition]);
     * one that has begun is left to finish (its players are paused, and nothing after the fade starts a player).
     */
    private val transitionBarrier = TransitionBarrier()
    private var transitionFadeStarted = false
    // The crossfade's own end-of-item pause preserves play intent for this ticket only.
    private var transitionEndPause = false
    private var queueSnapshot: List<MediaItem> = emptyList()
    private var activeWindowStartIndex = 0
    private var activePlayerUsesWindowedQueue = false
    private var preparedWindowStartIndex = 0
    private var preparedPlayerUsesWindowedQueue = false

    /**
     * B17-H1: while the master holds a window cut from the **play order** (a long shuffled queue after a crossfade),
     * [queueSnapshot] is that play-ordered list, so the window arithmetic below stays what it always was. These two keep
     * what it was cut from: the queue in its own (base) order, and the play order over it (positions to base indices).
     * They are what a rebuild, a snapshot to disk and turning shuffle off need to get the real queue back. Both are null in
     * every other state, and cleared whenever the snapshot is refreshed from a master that holds the whole queue.
     */
    private var windowedBaseQueue: List<MediaItem>? = null
    private var windowedPlayOrder: IntArray? = null
    private var preparedWindowedBaseQueue: List<MediaItem>? = null
    private var preparedWindowedPlayOrder: IntArray? = null

    private lateinit var playerA: ExoPlayer
    private var playerB: ExoPlayer? = null
    private val playerUsbSinks = IdentityHashMap<ExoPlayer, UsbAudioSink>()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Collecting from `init` is an ordering trap, and this class has already been bitten by it
        // once: a collector here observed `audioRouteState` — a *field*, declared further down —
        // and read `null` before its initializer had run, which killed the process about a second
        // after every start. `logRouteTransition` documents that incident from the other side.
        //
        // Two rules keep these two collectors on the right side of it. `DualPlayerEngineBoundaryTest`
        // fails if either is broken.
        //
        //  1. The receiver must be a **constructor parameter** (`dacProfiles`, `maintenanceOverrides`)
        //     or something derived from one. Constructor parameters are assigned before any
        //     initializer runs, so they are the only references guaranteed live here. A class field
        //     named here would be read before its own initializer — that is the crash.
        //
        //  2. The body must not touch engine state until it has hopped to Main. That hop is not
        //     decoration: `appScope` is IO, and the engine is constructed on the main thread, so
        //     the hop cannot run until the constructor has returned and every field exists. Neither
        //     the emission, nor any `map`/`distinctUntilChanged` in front of it, may read engine
        //     state — those run on IO, immediately, possibly mid-construction.
        appScope.launch {
            dacProfiles.connected.collect {
                withContext(Dispatchers.Main) { refreshUsbAudioDiagnostics() }
            }
        }
        appScope.launch {
            // A Maintenance override changes the route decision the same way a listening choice
            // does, so it must re-evaluate rather than wait for the next unrelated trigger.
            maintenanceOverrides.state.collect {
                withContext(Dispatchers.Main) { reevaluateAutomaticRoute("Maintenance override changed") }
            }
        }
    }

    private val onPlayerSwappedListeners = mutableListOf<(Player) -> Unit>()
    private val onTransitionDisplayPlayerListeners = mutableListOf<(Player) -> Unit>()
    private val onTransitionFinishedListeners = mutableListOf<() -> Unit>()

    private var onPlayerAboutToBeReleasedListener: ((Player) -> Unit)? = null

    fun setOnPlayerAboutToBeReleasedListener(listener: (Player) -> Unit) {
        onPlayerAboutToBeReleasedListener = listener
    }
    
    private val _activeAudioSessionId = MutableStateFlow(0)
    val activeAudioSessionId: StateFlow<Int> = _activeAudioSessionId.asStateFlow()

    private val _activeDecoderInfo = MutableStateFlow<ActiveDecoderInfo?>(null)
    val activeDecoderInfo: StateFlow<ActiveDecoderInfo?> = _activeDecoderInfo.asStateFlow()

    private val _usbAudioDiagnostics = MutableStateFlow(UsbDirectAudioDiagnostics())
    val usbAudioDiagnostics: StateFlow<UsbDirectAudioDiagnostics> =
        _usbAudioDiagnostics.asStateFlow()

    /**
     * Records route movement into the bounded hardware log.
     *
     * Called from the two write sites rather than from a collector started in `init`. A collector
     * here would have to observe `audioRouteState`, which is a *field* — and field initializers run
     * after `init`, so launching from there reads an uninitialised reference. Publishing from the
     * writes keeps the log exactly as accurate (every state change goes through one of them) and
     * avoids that ordering trap entirely.
     *
     * Consecutive writes with an unchanged transition are not re-logged: the log is a record of
     * transitions, not of how often the policy was asked.
     */
    private var lastLoggedTransition: AudioRouteTransition? = null

    private fun logRouteTransition(state: AudioRouteState) {
        if (state.transition == lastLoggedTransition) return
        lastLoggedTransition = state.transition
        when (state.transition) {
            AudioRouteTransition.DIRECT_ACTIVE -> HardwareEventLog.record(
                HardwareEventType.DIRECT_ACTIVE,
                state.confirmedOutputRateHz?.let { "$it Hz" } ?: state.deviceName,
            )
            AudioRouteTransition.DIRECT_FAILED_FALLBACK -> HardwareEventLog.record(
                HardwareEventType.DIRECT_FALLBACK,
                state.directError ?: state.deviceName,
            )
            AudioRouteTransition.DIRECT_ELIGIBLE -> HardwareEventLog.record(
                HardwareEventType.DIRECT_CANDIDATE,
                state.deviceName,
            )
            AudioRouteTransition.DIRECT_NEGOTIATING -> HardwareEventLog.record(
                HardwareEventType.DIRECT_CANDIDATE,
                "negotiating",
            )
            AudioRouteTransition.ANDROID_ACTIVE -> HardwareEventLog.record(
                HardwareEventType.ROUTE_ANDROID,
                state.reason.name,
            )
        }
    }

    private val _audioRouteState = MutableStateFlow(AudioRouteState())
    val audioRouteState: StateFlow<AudioRouteState> = _audioRouteState.asStateFlow()

    private val _replayGainRuntimeState = MutableStateFlow(ReplayGainRuntimeState())
    val replayGainRuntimeState: StateFlow<ReplayGainRuntimeState> =
        _replayGainRuntimeState.asStateFlow()

    fun publishReplayGainState(state: ReplayGainRuntimeState) {
        _replayGainRuntimeState.value = state.copy(
            applied = state.applied && _audioRouteState.value.actualBackend == AudioBackend.ANDROID_AUDIO
        )
    }

    /**
     * Overlap (crossfade) and the exclusive USB Direct route cannot coexist: Direct needs one
     * stream, a crossfade needs two. Suppression is therefore only correct while Direct is
     * actually the route the engine is trying to reach.
     *
     * [UsbDirectAudioDiagnostics.reservesExclusiveUsbPath] alone is not that question. It reports
     * a property of the *sink* — "a permitted DAC is attached" — and stays true while a listening
     * choice (EQ, ReplayGain, speed, crossfade) is pinning the route to Android and Direct can
     * never activate. Suppressing on that would kill crossfade and gapless playback for every
     * user who attaches a DAC, permanently and for no benefit.
     */
    fun shouldSuppressOverlapTransitions(): Boolean =
        _usbAudioDiagnostics.value.reservesExclusiveUsbPathFor(desiredBackend)

    fun refreshUsbAudioDiagnostics() {
        // B23-P4: a refresh is what an attach, a detach and a permission answer end in (and every resume): it reads the bus
        // itself, and what a new song's evaluation held from before is no longer to be trusted.
        usbRouteFacts.invalidate()
        val usbAudioDevice = UsbAudioDevice.getInstance(context)
        val usbDevice = usbAudioDevice.findUsbAudioDevice()
        // B25-S1: first, before anything below can rebuild the player on the Android path with the play intent.
        observeUsbPresence(usbDevice != null, "USB availability changed")
        val masterSink = if (::playerA.isInitialized) playerUsbSinks[playerA] else null
        val sinkDiagnostics = masterSink?.diagnostics
        if (usbDevice != null && (
                sinkDiagnostics?.state == UsbDirectSinkState.USB_DIRECT_ACTIVE ||
                    sinkDiagnostics?.state == UsbDirectSinkState.USB_DIRECT_ERROR
                )
        ) {
            publishUsbDiagnostics(sinkDiagnostics)
            return
        }

        val permissionGranted = usbDevice?.let(usbAudioDevice::hasPermission)
        // A DAC can be present with no permission and no attach intent on its way. This app is the
        // HOME app under Lock Task, so a DAC plugging in resumes it from Lock Task rather than
        // launching it with USB_DEVICE_ATTACHED — and the activity's intent handler is the only
        // other place permission is ever requested. Without this the DAC is enumerated, visible,
        // selected as the output device by the platform, and permanently unusable for Direct.
        if (usbDevice != null && permissionGranted == false) {
            requestUsbPermissionOnce(usbAudioDevice, usbDevice)
        }
        if (usbDevice == null) {
            permissionCoordinator.onAllDetached()
            pendingPermissionCancels.values.forEach { it() }
            pendingPermissionCancels.clear()
            directFailureLatch.clear()
        }
        val detectedState = when {
            usbDevice == null -> UsbDirectSinkState.ANDROID_AUDIO
            permissionGranted == true -> UsbDirectSinkState.USB_DIRECT_AVAILABLE
            else -> UsbDirectSinkState.USB_PERMISSION_REQUIRED
        }
        publishUsbDiagnostics(
            (sinkDiagnostics ?: UsbAudioSinkDiagnostics()).copy(
                state = detectedState,
                deviceName = usbDevice?.productName ?: usbDevice?.deviceName,
                permissionGranted = permissionGranted,
                errorReason = null,
            )
        )
        reevaluateAutomaticRoute("USB availability changed")
    }

    /**
     * Asks for USB permission for [device] at most once per attachment.
     *
     * The guard is keyed on the device name and cleared when the device list empties, so a refused
     * or dismissed dialog is not re-raised on every diagnostic refresh, while a genuine unplug and
     * replug does ask again.
     */
    /**
     * Asks for USB permission for [device], at most once per attachment.
     *
     * The request is handed to [usbPermissionRequester] when one is installed, because on a
     * dedicated appliance the dialog cannot simply be raised: AOSP's `ActivityStarter` refuses to
     * launch the system's `UsbPermissionActivity` while Lock Task is holding the task list, unless
     * that activity carries `ATTR_IS_USB_PERMISSION_ACTIVITY`. Measured on the HONOR LGN-NX3, whose
     * `SystemUIOversea` variant does not carry it — the launch is issued and immediately rejected
     * (`dumpsys activity starter` → `mLastStartActivityResult=101`, the record left at
     * `launchCount=0 ... windows=[]`), so no dialog is ever drawn and the callback never fires.
     *
     * The container solves this the same way it already solves it for Bluetooth enablement: suspend
     * Lock Task for the one interactive system dialog, then restore it on return. That logic lives
     * in `MainActivity.beginTemporarySystemConfirmation`, and this callback is how the engine asks
     * for it — containment authority stays in the activity rather than being duplicated here.
     */
    private fun requestUsbPermissionOnce(usbAudioDevice: UsbAudioDevice, device: UsbDevice) {
        val key = device.deviceName
        // Main thread only, like the coordinator; the diagnostics refresh can arrive from anywhere.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestUsbPermissionOnce(usbAudioDevice, device) }
            return
        }
        val requester = usbPermissionRequester
        // No container entry point (activity not there): nothing can be shown, and the request is
        // not recorded, so installing the requester — or the next refresh — asks again (AUD-030).
        if (requester == null) return
        if (!permissionCoordinator.tryBegin(key, SystemClock.elapsedRealtime())) return
        HardwareEventLog.record(HardwareEventType.USB_PERMISSION_REQUESTED, key)
        mainHandler.removeCallbacks(permissionTimeout)
        mainHandler.postDelayed(permissionTimeout, UsbPermissionCoordinator.DEFAULT_TIMEOUT_MS)
        val request = {
            pendingPermissionCancels[key] = usbAudioDevice.requestPermission(device) { granted ->
                mainHandler.post {
                    pendingPermissionCancels.remove(key)
                    permissionCoordinator.onResult(key, granted)
                }
                HardwareEventLog.record(
                    if (granted) HardwareEventType.USB_PERMISSION_GRANTED
                    else HardwareEventType.USB_PERMISSION_DENIED,
                    device.deviceName,
                )
                mainHandler.post {
                    if (granted) {
                        // The serial is only readable now; without a re-scan a DAC whose profile was
                        // promoted to its serial stays unidentified (AUD-026).
                        dacProfiles.reconcile()
                        onUsbAudioDeviceReady()
                    } else {
                        refreshUsbAudioDiagnostics()
                    }
                }
            }
        }
        // A refused request (no resumed activity, suspension refused) showed nothing and is
        // forgotten, so the next scan in the foreground asks again. A request that was accepted but
        // never produces an answer is dropped by the timeout above.
        if (!requester(request)) {
            permissionCoordinator.abandon(key)
        }
    }

    /**
     * Installs the container's "suspend Lock Task for one system dialog" entry point. Returns false
     * when the request could not be started, in which case nothing was shown.
     */
    fun setUsbPermissionRequester(requester: ((() -> Unit) -> Boolean)?) {
        usbPermissionRequester = requester
        // A request skipped for want of a requester is asked now that one exists.
        if (requester != null) mainHandler.post { refreshUsbAudioDiagnostics() }
    }

    /** Removes [requester] if it is still the installed one; a newer activity's stays (AUD-030). */
    fun clearUsbPermissionRequester(requester: (() -> Unit) -> Boolean) {
        if (usbPermissionRequester === requester) usbPermissionRequester = null
    }

    private fun publishUsbDiagnostics(diagnostics: UsbAudioSinkDiagnostics) {
        // B25-S1: the Direct stream died because its DAC is gone. The sink has already kept its speaker delegate silent; the
        // pause is decided here, before the failure below re-routes (and a gone DAC is not a failed claim to latch).
        val dacGone = DirectStreamLoss.isDeviceGone(diagnostics.errorReason)
        if (dacGone) observeUsbPresence(false, "Direct stream lost its DAC", directActive = true)
        val source = currentAudioFormatSnapshot()
        _usbAudioDiagnostics.value = UsbDirectAudioDiagnostics(
            state = diagnostics.state,
            deviceName = diagnostics.deviceName,
            permissionGranted = diagnostics.permissionGranted,
            sourceSampleRate = diagnostics.sourceSampleRate
                ?: source?.sampleRate?.takeIf { it > 0 },
            sourceBitDepth = diagnostics.sourceBitDepth
                ?: source?.pcmEncoding?.let(::pcmEncodingBitDepth),
            sourceCodec = source?.sampleMimeType,
            dacBestBitDepth = diagnostics.dacBestBitDepth,
            dacSupportedRates = diagnostics.dacSupportedRates,
            errorReason = diagnostics.errorReason,
            signalMode = diagnostics.signalMode,
            bitPerfect = diagnostics.bitPerfect,
            transportPcmEncoding = diagnostics.transportPcmEncoding,
            transportBitDepth = diagnostics.transportBitDepth,
        )
        val signalMode = diagnostics.signalMode ?: DirectSignalMode.PRISTINE
        val newlyFailed = diagnostics.state == UsbDirectSinkState.USB_DIRECT_ERROR && !dacGone &&
            _audioRouteState.value.transition != AudioRouteTransition.DIRECT_FAILED_FALLBACK
        if (diagnostics.state == UsbDirectSinkState.USB_DIRECT_ERROR && !dacGone) {
            // The whole source format is the key (R4-2): a depth or channel rejection at 44.1 kHz
            // must not block other formats at 44.1 kHz.
            directFailureLatch.onFailure(
                UsbAudioDevice.getInstance(context).findUsbAudioDevice()?.deviceName,
                diagnostics.sourceSampleRate?.takeIf { it > 0 },
                diagnostics.sourcePcmEncoding?.takeIf { it > 0 },
                diagnostics.sourceChannelCount?.takeIf { it > 0 },
            )
        }
        // A DAC that left is not a failed claim: it is the Android route with no DAC, which is what the evaluation below decides.
        _audioRouteState.value = when (if (dacGone) UsbDirectSinkState.ANDROID_AUDIO else diagnostics.state) {
            UsbDirectSinkState.USB_DIRECT_ACTIVE -> AudioRouteState(
                desiredBackend = desiredBackend,
                actualBackend = AudioBackend.USB_DIRECT,
                transition = AudioRouteTransition.DIRECT_ACTIVE,
                // The reason names the signal mode the sink is actually running, not the mode the
                // policy asked for. Between a request and its effect the two can differ by one
                // buffer, and a truth surface that reports the request is not reporting the truth.
                reason = if (signalMode == DirectSignalMode.PROCESSED) {
                    AudioRouteReason.DIRECT_PROCESSED
                } else {
                    AudioRouteReason.DIRECT_PRISTINE
                },
                deviceName = diagnostics.deviceName,
                permissionGranted = diagnostics.permissionGranted,
                confirmedOutputRateHz = diagnostics.sourceSampleRate?.takeIf { it > 0 },
                signalMode = signalMode,
                bitPerfect = diagnostics.bitPerfect,
            )
            UsbDirectSinkState.USB_DIRECT_ERROR -> directFailureFallback(
                previous = _audioRouteState.value,
                desiredBackend = desiredBackend,
                deviceName = diagnostics.deviceName,
                permissionGranted = diagnostics.permissionGranted,
                error = diagnostics.errorReason,
            )
            UsbDirectSinkState.USB_DIRECT_AVAILABLE -> AudioRouteState(
                desiredBackend = desiredBackend,
                actualBackend = AudioBackend.ANDROID_AUDIO,
                transition = if (desiredBackend == AudioBackend.USB_DIRECT) {
                    AudioRouteTransition.DIRECT_NEGOTIATING
                } else AudioRouteTransition.ANDROID_ACTIVE,
                reason = _audioRouteState.value.reason,
                deviceName = diagnostics.deviceName,
                permissionGranted = diagnostics.permissionGranted,
            )
            UsbDirectSinkState.USB_PERMISSION_REQUIRED,
            UsbDirectSinkState.ANDROID_AUDIO -> _audioRouteState.value.copy(
                desiredBackend = desiredBackend,
                actualBackend = AudioBackend.ANDROID_AUDIO,
                transition = AudioRouteTransition.ANDROID_ACTIVE,
                deviceName = diagnostics.deviceName,
                permissionGranted = diagnostics.permissionGranted,
                confirmedOutputRateHz = null,
                directError = null,
            )
        }
        logRouteTransition(_audioRouteState.value)
        // The failed claim is now a policy fact; re-decide so the route leaves the Direct-capable
        // player (whose delegate stays muted after a failure, AUD-004) for the Android path.
        if (newlyFailed) mainHandler.post { reevaluateAutomaticRoute("Direct claim failed") }
        else if (dacGone) mainHandler.post { reevaluateAutomaticRoute("Direct stream lost its DAC") }
        // Direct becoming active is published here, not by the rebuild — the sink reports back after
        // the rebuild has already returned. This is therefore where an automatic handoff learns it
        // can restore playback.
        onRouteStatePublished(_audioRouteState.value)
    }

    /**
     * @param pristineLock the Purity lock *preference*, delivered together with the listening state so a decision is never
     *        taken from one without the other. Whether it holds anything off is the router's to say (a DAC must be there).
     */
    fun updateProcessingRequirements(requirements: AudioProcessingRequirements, pristineLock: Boolean = false) {
        if (processingRequirementsKnown && rawProcessingRequirements == requirements && pristineLockPreference == pristineLock) return
        processingRequirementsKnown = true
        rawProcessingRequirements = requirements
        pristineLockPreference = pristineLock
        // Push the change into the Direct pipeline before anything else looks at it.
        //
        // This is the authoritative trigger for a signal-mode transition, and it is deliberately
        // not the route reevaluation below. A mode change is not a backend change and does not
        // rebuild the player, and it cannot be discovered by the sink's PCM path: while the native
        // engine owns a local FLAC, `NativeEngineAwareLoadControl` stops ExoPlayer loading and
        // `handleBuffer` may never be called again for that track. Waiting for it produced a
        // `DIRECT_PROCESSED` route, an untouched native engine, and a `StuckPlayerException`.
        if (::playerA.isInitialized) {
            playerUsbSinks[playerA]?.requestSignalMode()
        }
        reevaluateAutomaticRoute("Listening requirements changed")
    }

    /**
     * The listening state the route is currently being decided from. Exposed read-only for the
     * device diagnostic, so the surface names the same blockers the policy acted on rather than
     * re-deriving them and risking a different answer.
     */
    fun currentProcessingRequirements(): AudioProcessingRequirements = processingRequirements

    /** Master player reconstructions since process start. Diagnostic only; nothing reads it to decide. */
    private val masterRebuildCount = java.util.concurrent.atomic.AtomicLong()

    /** Read-only accessor for the device diagnostic. */
    fun rebuildCount(): Long = masterRebuildCount.get()

    /**
     * Last position read on the main thread; see [diagnosticPositionMs].
     *
     * ExoPlayer enforces thread affinity — `getCurrentPosition` throws
     * `IllegalStateException: Player is accessed on the wrong thread` from anywhere but the thread
     * it was built on. A diagnostic reader is by definition on another thread, so the read is
     * posted to the main looper and this cached value is what a non-main caller gets.
     */
    @Volatile
    private var diagnosticPositionCacheMs: Long = -1L

    /**
     * The master player's current position in milliseconds, or -1 when there is no player.
     *
     * Diagnostic only: it reads and changes nothing about playback. It exists because a transition
     * record whose "after" position was inferred rather than read would be a claim about continuity
     * rather than a measurement of it.
     *
     * Safe to call from any thread. From a non-main thread the value returned is the most recent
     * main-thread reading and the refresh is posted, so a caller may observe one sample of lag —
     * invisible against a 1 Hz capture and far preferable to the alternative, which is an
     * exception that kills the calling thread.
     */
    fun diagnosticPositionMs(): Long {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            diagnosticPositionCacheMs = currentPositionOrUnknown()
            diagnosticHandoffContext = currentHandoffContextOrNull()
        } else {
            mainHandler.post {
                diagnosticPositionCacheMs = currentPositionOrUnknown()
                diagnosticHandoffContext = currentHandoffContextOrNull()
            }
        }
        return diagnosticPositionCacheMs
    }

    private fun currentPositionOrUnknown(): Long =
        if (::playerA.isInitialized) playerA.currentPosition else -1L

    /**
     * Diagnostics only: the player's own state, mirrored beside the position and refreshed with it.
     *
     * A renderer-domain value reaching a handoff anchor means one thing at EOF after a process
     * restart and another during ordinary mid-track playback. The anchor value alone cannot tell
     * those apart; this is the context that can. Refreshed in the same main-thread block as the
     * position, so the pair a caller reads is always from one generation — one sample stale when
     * read off-main, which is the same lag the position already has.
     */
    @Volatile
    private var diagnosticHandoffContext: String? = null

    private fun currentHandoffContextOrNull(): String? =
        if (!::playerA.isInitialized) {
            null
        } else {
            runCatching {
                "state=${playerA.playbackState} playing=${playerA.isPlaying} " +
                    "item=${playerA.currentMediaItemIndex}/${playerA.mediaItemCount} " +
                    "posMs=${playerA.currentPosition} durMs=${playerA.duration} " +
                    "ended=${playerA.playbackState == Player.STATE_ENDED}"
            }.getOrNull()
        }

    /** Track duration in milliseconds, or -1. Diagnostic only; main-thread guarded like position. */
    fun diagnosticDurationMs(): Long = if (::playerA.isInitialized) playerA.duration else -1L

    /**
     * TEMPORARY measurement instrumentation.
     *
     * Writes a per-invocation trace of the automatic route decision to a file the host can read
     * back — this device's OEM logging stack does not surface app logs through `adb logcat`. It
     * changes nothing about the decision; it records the inputs it was made from. Remove once the
     * trace has been captured (see analysis/dogfood-prelibrary/bug2-usb-direct/).
     */
    /**
     * Route trace (AUD-035): an in-memory append here; the file is written on its own thread. The
     * previous session's lines are read once, on that thread, and kept ahead of this one's.
     */
    private val routeTrace by lazy { com.lostf1sh.pixelplayeross.data.diagnostics.RouteTraceLog(
        maxLines = TRACE_MAX_LINES,
        executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RouteTrace").apply { isDaemon = true }
        },
        clock = { SystemClock.elapsedRealtime() },
    ) { lines ->
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = java.io.File(base, "diagnostics").apply { mkdirs() }
        val file = java.io.File(dir, "route-trace.txt")
        val previous = previousRouteTrace ?: (if (file.isFile) file.readLines() else emptyList()).also {
            previousRouteTrace = it
        }
        file.writeText((previous + lines).takeLast(TRACE_MAX_LINES).joinToString("\n"))
    } }

    /** The trace file as the previous session left it; RouteTrace thread only. */
    private var previousRouteTrace: List<String>? = null

    private fun traceRoute(tag: String, detail: String) = routeTrace.record(tag, detail)

    private fun playerFacts(): String = if (::playerA.isInitialized) {
        "initialized=true playWhenReady=${playerA.playWhenReady} isPlaying=${playerA.isPlaying} " +
            "playbackState=${playerA.playbackState} mediaItems=${playerA.mediaItemCount}"
    } else {
        "initialized=false"
    }

    /** The DAC facts of the last live probe (B23-P4); see [UsbRouteFactsCache] for when they may stand in for a probe. */
    private val usbRouteFacts = UsbRouteFactsCache {
        val usb = UsbAudioDevice.getInstance(context)
        val found = usb.findUsbAudioDevice()
        UsbRouteFacts<UsbDevice>(found, found != null && usb.hasPermission(found))
    }

    /** What the last evaluation decided from a **live** probe: the only route held facts are allowed to confirm. */
    private var lastLiveRouteDecision: AudioRouteDecision? = null

    private class RouteEvaluation(
        val device: UsbDevice?,
        val permission: Boolean,
        val sourceCompatible: Boolean,
        val directTransitionAllowed: Boolean,
        override val decision: AudioRouteDecision,
        val directClaimable: Boolean,
        val rebuild: MasterRebuildFacts,
    ) : RouteStep {
        override val rebuildNeeded: Boolean get() = rebuild.shouldRebuild || rebuild.backendChanged
    }

    /** Decides the route from [facts] and what the engine already knows. Acts on nothing; running it twice is harmless. */
    private fun evaluateRoute(facts: UsbRouteFacts<UsbDevice>, previousRoute: AudioRouteState): RouteEvaluation {
        val device = facts.device
        val permission = facts.permission
        // B16-G4: the lock is in force only with a DAC, and this is where the engine learns whether there is one. Recorded
        // before the decision is taken from the requirements, and a signal path already running is told if what it must
        // honour changed (a DAC plugged in with the lock on, or taken away).
        val requirementsBefore = processingRequirements
        usbDacPresentFact = device != null
        if (processingRequirements != requirementsBefore && ::playerA.isInitialized) {
            playerUsbSinks[playerA]?.requestSignalMode()
        }
        val scheme = if (::playerA.isInitialized) {
            playerA.currentMediaItem?.localConfiguration?.uri?.scheme?.lowercase()
        } else null
        val sourceCompatible = scheme == null || scheme in LOCAL_MEDIA_SCHEMES || scheme in REMOTE_MEDIA_SCHEMES
        // The permission to enter Direct comes from stable facts, never from Direct already being
        // in effect — see `isDirectTransitionAllowed`. Short-circuiting on `isInitialized` keeps
        // `playerA` from being touched before it exists.
        val directTransitionAllowed = !::playerA.isInitialized ||
            isDirectTransitionAllowed(
                masterInitialised = true,
                directIsActive = _audioRouteState.value.isDirectActive,
                isPlaying = playerA.isPlaying,
            )
        val decision = if (!processingRequirementsKnown) {
            AudioRouteDecision(AudioBackend.ANDROID_AUDIO, AudioRouteReason.STARTING)
        } else AudioRoutePolicy.decide(
            AudioRouteFacts(
                usbDacPresent = device != null,
                usbPermissionGranted = permission,
                sourceDirectCompatible = sourceCompatible,
                processing = processingRequirements,
                directTransitionAllowed = directTransitionAllowed,
                maintenanceForceAndroid = maintenanceOverrides.current().forcedAndroidAudio(),
                maintenanceDirectDisabled = maintenanceOverrides.current().directTransitionDisabled(),
                directFailedForSource = currentAudioFormatSnapshot().let { source ->
                    directFailureLatch.blocks(
                        device?.deviceName,
                        source?.sampleRate?.takeIf { it > 0 },
                        source?.pcmEncoding?.takeIf { it > 0 },
                        source?.channelCount?.takeIf { it > 0 },
                    )
                },
            ),
            // B23-P4: read only for an Android decision with a DAC attached (`/proc/asound` and the platform's outputs).
            androidUsbRouteAvailable = ::androidUsbRouteAvailable,
        )
        // Claimability is read from the player, never assumed: the Direct claim is made inside
        // UsbAudioSink.configure(), which needs a media item to configure the sink from.
        val directClaimable = ::playerA.isInitialized && playerA.mediaItemCount > 0
        val rebuild = MasterRebuildFacts(
            decidedBackend = decision.backend,
            previousDesiredBackend = previousRoute.desiredBackend,
            playerBuiltWithDirectAccess = playerBuiltWithDirectAccess,
            directIsActive = previousRoute.isDirectActive,
            masterInitialised = ::playerA.isInitialized,
            directClaimable = directClaimable,
            directClaimAttempted = directClaimAttempted,
        )
        return RouteEvaluation(device, permission, sourceCompatible, directTransitionAllowed, decision, directClaimable, rebuild)
    }

    private fun reevaluateAutomaticRoute(logMessage: String) = timedSection("DualPlayerEngine.reevaluateAutomaticRoute") {
        reevaluateAutomaticRouteNow(logMessage)
    }

    private fun reevaluateAutomaticRouteNow(logMessage: String) {
        // A rebuild in flight has not yet restored the queue or written `recordDirectCapability`, so
        // the capability facts this evaluation would read describe the player that rebuild just
        // released. Acting on them authorises the same rebuild again, before the state that clears
        // it can be written — an unbounded recursion, not a slow convergence. Remember the request
        // instead; `rebuildPlayersPreservingMasterState` replays it once the engine is whole.
        if (masterRebuildCycle.isRebuilding) {
            masterRebuildCycle.deferReevaluation()
            traceRoute(
                "DEFER",
                "trigger=\"$logMessage\" deferred: master rebuild in progress"
            )
            return
        }
        traceRoute(
            "ENTER",
            "trigger=\"$logMessage\" ${playerFacts()} " +
                "directActive=${_audioRouteState.value.isDirectActive} " +
                "desired=${_audioRouteState.value.desiredBackend} " +
                "actual=${_audioRouteState.value.actualBackend} " +
                "processingKnown=$processingRequirementsKnown"
        )
        val previousRoute = _audioRouteState.value
        // B23-P4: the DAC facts of the last live probe stand in for a new song's evaluation, and only to confirm the route the
        // last live evaluation chose. Anything that would change something is decided again from a live probe first.
        val evaluated = usbRouteFacts.evaluate(
            trigger = logMessage,
            lastLiveDecision = lastLiveRouteDecision,
            handoffInFlight = autoDirectHandoff.isInProgress,
            androidRouteGuardHolding = androidUsbRouteGuard.isHolding,
        ) { facts -> evaluateRoute(facts, previousRoute) }
        val evaluation = evaluated.step
        val factsReused = evaluated.reused
        if (!factsReused) lastLiveRouteDecision = evaluation.decision
        val device = evaluation.device
        // B25-S1: whatever noticed the DAC leaving, nothing below (a handoff, the Android guard, a rebuild) may carry a play
        // intent over to a route with no DAC. A no-op when an earlier observation already paused.
        observeUsbPresence(device != null, logMessage)
        val permission = evaluation.permission
        val sourceCompatible = evaluation.sourceCompatible
        val directTransitionAllowed = evaluation.directTransitionAllowed
        val decision = evaluation.decision
        val directClaimable = evaluation.directClaimable
        val rebuild = evaluation.rebuild
        traceRoute(
            "DECIDE",
            "trigger=\"$logMessage\" usbDacPresent=${device != null} permission=$permission " +
                "sourceCompatible=$sourceCompatible allowed=$directTransitionAllowed " +
                "decision=${decision.backend}/${decision.reason} " +
                "prevDesired=${previousRoute.desiredBackend} prevActual=${previousRoute.actualBackend} " +
                "backendChanged=${rebuild.backendChanged} shouldRebuild=${rebuild.shouldRebuild} " +
                "builtWithDirectAccess=$playerBuiltWithDirectAccess claimable=$directClaimable " +
                "claimAttempted=$directClaimAttempted usbFacts=${if (factsReused) "reused" else "live"}"
        )
        desiredBackend = decision.backend
        val actualBackend = when {
            rebuild.backendChanged -> previousRoute.actualBackend
            previousRoute.isDirectActive && decision.backend == AudioBackend.USB_DIRECT ->
                AudioBackend.USB_DIRECT
            else -> AudioBackend.ANDROID_AUDIO
        }
        _audioRouteState.value = previousRoute.copy(
            desiredBackend = decision.backend,
            actualBackend = actualBackend,
            transition = when {
                actualBackend == AudioBackend.USB_DIRECT ->
                    AudioRouteTransition.DIRECT_ACTIVE
                decision.backend == AudioBackend.USB_DIRECT -> AudioRouteTransition.DIRECT_ELIGIBLE
                else -> AudioRouteTransition.ANDROID_ACTIVE
            },
            reason = decision.reason,
            // The policy's intent, used only until the sink reports what it is actually doing.
            // On the Android path the application sink is not carrying the samples, so there is no
            // Direct signal mode to report and pristine is the only honest answer.
            signalMode = if (actualBackend == AudioBackend.USB_DIRECT) {
                decision.signalMode
            } else {
                DirectSignalMode.PRISTINE
            },
            deviceName = device?.productName ?: device?.deviceName,
            usbDacPresent = device != null,
            permissionGranted = device?.let { permission },
            confirmedOutputRateHz = previousRoute.confirmedOutputRateHz
                .takeIf { actualBackend == AudioBackend.USB_DIRECT },
            directError = null,
        )
        logRouteTransition(_audioRouteState.value)
        onRouteStatePublished(_audioRouteState.value)
        // A handoff in flight is only ever waiting on Direct, so this decision is what tells it
        // whether the wait is still meaningful. Checked before a new one can start, so a DAC that
        // left during the last handoff cannot have one opened against it.
        reconcileHandoffWithDecision(decision, devicePresent = device != null)
        maybeStartDirectHandoff(decision, previousRoute)
        applyAndroidUsbRouteGuard(decision)
        if (rebuild.backendChanged && ::playerA.isInitialized) {
            traceRoute("REBUILD", "REBUILDING (backendChanged) reason=${decision.reason}")
            rebuildPlayersPreservingMasterState("$logMessage: ${decision.reason}")
        } else if (rebuild.shouldRebuild) {
            traceRoute(
                "REBUILD",
                "REBUILDING (capability) reason=${decision.reason} " +
                    "capability=${rebuild.needsCapabilityRebuild} claim=${rebuild.needsClaimAttempt}"
            )
            // Two distinct windows can open here. Either the running player cannot serve the
            // backend it is being asked for — publishUsbDiagnostics assigning the desired backend
            // from a DAC becoming available, with the player in hand built Android-only — or it
            // already holds the Direct sink but was built before there was anything to configure it
            // with, so the claim it was built for was never actually made. Both are rebuilt, but
            // only the second is spent by the rebuild: `recordDirectCapability` latches the claim
            // opportunity when this build has a source in front of the sink.
            rebuildPlayersPreservingMasterState(
                if (rebuild.needsClaimAttempt) {
                    "$logMessage: claiming ${decision.backend} with a source now available"
                } else {
                    "$logMessage: rebuilding for ${decision.backend} capability"
                }
            )
        }
    }

    /**
     * Holds or restores playback for an Android decision that has no Android USB output (AUD-003).
     * Runs before any rebuild, so the rebuild carries the held play intent rather than restoring it.
     */
    private fun applyAndroidUsbRouteGuard(decision: AudioRouteDecision) {
        if (!::playerA.isInitialized) return
        when (androidUsbRouteGuard.step(
            blocked = decision.androidOutputBlocked,
            playing = playerA.playWhenReady,
            nowMs = SystemClock.elapsedRealtime(),
        )) {
            AndroidUsbRouteGuard.Step.NONE -> Unit
            AndroidUsbRouteGuard.Step.PAUSE -> {
                traceRoute("USB_ROUTE", "Android path chosen with the DAC attached but no USB output; holding playback")
                mainHandler.removeCallbacks(androidUsbRouteTimeout)
                mainHandler.postDelayed(androidUsbRouteTimeout, AndroidUsbRouteGuard.DEFAULT_TIMEOUT_MS)
                mainHandler.removeCallbacks(usbRouteProbe)
                mainHandler.postDelayed(usbRouteProbe, USB_ROUTE_PROBE_INTERVAL_MS)
                playerA.playWhenReady = false
            }
            AndroidUsbRouteGuard.Step.RESUME -> {
                traceRoute("USB_ROUTE", "Android USB output available; restoring playback")
                mainHandler.removeCallbacks(androidUsbRouteTimeout)
                playerA.playWhenReady = true
            }
        }
    }

    /**
     * Considers starting an automatic Direct handoff for this evaluation, and pauses if it does.
     *
     * The decision to start lives entirely in [AutoDirectHandoff.begin]; this only supplies the
     * facts and performs the pause. Issuing the pause is what makes the next evaluation see
     * `isPlaying == false` and let the ordinary route machinery claim Direct — nothing here reaches
     * past [AudioRoutePolicy] to force a route.
     */
    private fun maybeStartDirectHandoff(decision: AudioRouteDecision, previousRoute: AudioRouteState) {
        if (!::playerA.isInitialized) return
        val started = autoDirectHandoff.begin(
            reason = decision.reason,
            isPlaying = playerA.isPlaying,
            directIsActive = previousRoute.isDirectActive,
            itemId = playerA.currentMediaItem?.mediaId,
            nowMs = SystemClock.elapsedRealtime(),
        )
        if (started) {
            traceRoute(
                "HANDOFF",
                "starting: ${decision.reason} while audible; pausing to let Direct claim"
            )
            mainHandler.postDelayed(handoffTimeout, AutoDirectHandoff.DEFAULT_TIMEOUT_MS)
            playerA.playWhenReady = false
        }
    }

    /**
     * Called after every write to the route authority.
     *
     * Resuming hangs off this rather than off the rebuild, because Direct becomes active when the
     * sink reports back — which is after the rebuild returns, and is published through
     * [publishUsbDiagnostics]. The rebuild is where Direct is *built*; this is where it is known to
     * be *active*, which is the only thing worth resuming on.
     */
    private fun onRouteStatePublished(state: AudioRouteState) {
        if (!autoDirectHandoff.isInProgress) return
        val itemId = if (::playerA.isInitialized) playerA.currentMediaItem?.mediaId else null
        // DIRECT_ACTIVE alone is not enough: the DAC's level must be reconciled and bounded first.
        when (autoDirectHandoff.resumeStep(state, itemId, handoffVolumeVerdict)) {
            AutoDirectHandoff.ResumeStep.WAIT -> Unit
            AutoDirectHandoff.ResumeStep.RESUME -> {
                traceRoute("HANDOFF", "DIRECT_ACTIVE reached, DAC level safe; restoring playback")
                concludeHandoff(resumeIfOwed = true)
            }
            AutoDirectHandoff.ResumeStep.CONCLUDE_WITHOUT_RESUME -> {
                traceRoute("HANDOFF", "DIRECT_ACTIVE reached, DAC level unbounded; leaving playback paused")
                concludeHandoff(resumeIfOwed = false)
            }
        }
    }

    /**
     * Ends a handoff, restoring playback only if the handoff still owes it.
     *
     * The state is cleared before the resume so the `playWhenReady = true` this issues cannot be
     * mistaken for a user action by the listener.
     */
    private fun concludeHandoff(resumeIfOwed: Boolean) {
        val resume = autoDirectHandoff.conclude()
        mainHandler.removeCallbacks(handoffTimeout)
        if (resume && resumeIfOwed && ::playerA.isInitialized) {
            playerA.playWhenReady = true
        }
    }

    /**
     * Applies a route decision to any handoff in flight.
     *
     * A handoff is only ever waiting for Direct. If the DAC leaves, or Direct becomes impossible for
     * a reason other than audibility, the wait is over — and the two outcomes differ: a detached DAC
     * must never be auto-resumed through (the audio would come out of the speaker), whereas a device
     * that is still there but no longer Direct-capable should simply give the user their playback
     * back on Android.
     */
    private fun reconcileHandoffWithDecision(decision: AudioRouteDecision, devicePresent: Boolean) {
        if (!autoDirectHandoff.isInProgress) return
        when {
            !devicePresent -> {
                traceRoute("HANDOFF", "DAC removed during handoff; abandoning without resume")
                autoDirectHandoff.onDeviceLost()
                mainHandler.removeCallbacks(handoffTimeout)
            }
            decision.backend == AudioBackend.ANDROID_AUDIO &&
                decision.reason != AudioRouteReason.SAFETY_TRANSITION_DEFERRED -> {
                // Direct is impossible for a reason that is not audibility, but the device is still
                // here. The user asked to listen, so hand the playback back on Android rather than
                // leaving them paused over a route that is no longer coming.
                traceRoute(
                    "HANDOFF",
                    "Direct no longer eligible (${decision.reason}); restoring Android playback"
                )
                concludeHandoff(resumeIfOwed = true)
            }
        }
    }

    private fun pcmEncodingBitDepth(encoding: Int): Int? = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    /**
     * Whether ExoPlayer audio offload is currently enabled for this session. Exposed
     * read-only for the diagnostic performance report.
     */
    val isAudioOffloadEnabled: Boolean
        get() = audioOffloadEnabled

    /** Lightweight, allocation-cheap snapshot of the live audio format, for diagnostics. */
    data class AudioFormatSnapshot(
        val sampleMimeType: String?,
        val sampleRate: Int,
        val channelCount: Int,
        val pcmEncoding: Int,
        val bitrate: Int
    )

    /** Returns the current master-player audio format, or null when nothing is decoding. */
    fun currentAudioFormatSnapshot(): AudioFormatSnapshot? {
        if (!::playerA.isInitialized) return null
        val format = playerA.audioFormat ?: return null
        fun Int.orZero() = if (this == Format.NO_VALUE) 0 else this
        val bitrate = when {
            format.averageBitrate != Format.NO_VALUE -> format.averageBitrate
            format.peakBitrate != Format.NO_VALUE -> format.peakBitrate
            else -> 0
        }
        return AudioFormatSnapshot(
            sampleMimeType = format.sampleMimeType,
            sampleRate = format.sampleRate.orZero(),
            channelCount = format.channelCount.orZero(),
            pcmEncoding = format.pcmEncoding.orZero(),
            bitrate = bitrate
        )
    }

    private var sharedAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isFocusLossPause = false

    /** Whether the held request asked the system to hand over "may duck" instead of ducking itself (B14-3). */
    private var focusRequestPausesWhenDucked = false

    /**
     * True while this app holds focus outright: granted, not delayed, not lost, not ducked. A request is only
     * replaced then; re-requesting while another app has focus would take it back from them.
     */
    private var focusHeldOutright = false

    /**
     * B14-3 (audit A-05): attenuates USB Direct while another app asks for "may duck" focus. Set by the service;
     * null in tests and before it starts, when the engine behaves as it did.
     */
    @Volatile
    var directDuckListener: DirectDuckController? = null

    /**
     * The PROCESSED Direct stage's duck target, linear (1 = none). The stage ramps toward it and nothing else
     * reads it: a PRISTINE signal has no stage and is ducked on the DAC instead.
     */
    @Volatile
    private var directDuckGainTarget = 1f

    fun setDirectDuckGain(gain: Float) {
        directDuckGainTarget = gain.coerceIn(0f, 1f)
    }

    /**
     * B15-F4: the number of the focus event being handled, taken at the top of [focusChangeListener] (main thread).
     * A pause the Direct duck decided for an event is dropped when it runs if a newer event has arrived.
     */
    private val focusSequence = FocusSequence()

    /**
     * Pauses as a transient focus loss does, so the next focus gain resumes (a Direct duck that cannot attenuate).
     *
     * [generation] is the focus event the duck decided this for. The pause is posted to the main thread, where the
     * focus callbacks run too, so a gain can be handled before it lands: with nothing paused yet it resumed nothing,
     * and the pause that then arrived would leave playback stopped with no gain left to end it. Both run on the main
     * thread, so the comparison is exact: a pause for an event that a newer one has overtaken is dropped.
     */
    fun pauseForFocusDuck(generation: Long) {
        mainHandler.post {
            if (!focusSequence.isCurrent(generation)) {
                Timber.tag("TransitionDebug").d("Duck pause for focus event %d dropped: a newer event has arrived", generation)
                return@post
            }
            pauseForTransientFocusLoss()
            pausedByDuck = isFocusLossPause
        }
    }

    /** A Direct duck that could not attenuate paused playback: the resume it owes survives a loss that follows it. */
    private var pausedByDuck = false

    private fun pauseForTransientFocusLoss() {
        val auxiliaryPlayer = playerB
        // Capture the end-of-item play intent before cancelling the crossfade. Repeated losses
        // keep a resume already owed; GAIN, explicit pause, detach or permanent loss consume it.
        isFocusLossPause = shouldResumeAfterTransientAudioFocusLoss(
            masterPlayWhenReady = playerA.playWhenReady || transitionEndPause,
            masterIsPlaying = playerA.isPlaying,
            transitionRunning = transitionRunning,
            auxiliaryPlayWhenReady = auxiliaryPlayer?.playWhenReady == true,
            auxiliaryIsPlaying = auxiliaryPlayer?.isPlaying == true
        ) || isFocusLossPause || pausedByDuck
        // B27-A1 (R-01): the owed resume above is the focus gain's; a crossfade still waiting to start is not carried over it.
        invalidatePendingTransition("transient audio focus loss")
        playerA.playWhenReady = false
        auxiliaryPlayer?.playWhenReady = false
    }

    private val _focusDenials = MutableStateFlow(0)

    /**
     * How many audio-focus requests the platform has refused outright (`AUDIOFOCUS_REQUEST_FAILED`).
     *
     * Android 15's audio hardening refuses focus to an app that is not in the foreground at that
     * instant, and a refused request ends in `playWhenReady = false`, which looks exactly like the
     * user pausing. A counter, not an event: the refusal happens synchronously inside `play()`, so a
     * caller reads it before and after to learn that *this* play was refused (B11-fix FIX-1b).
     */
    val focusDenials: StateFlow<Int> = _focusDenials.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)

    /** The master player's own answer to "is this producing sound", as a flow (B11-fix FIX-4). */
    val isPlayingFlow: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private var lastPlayWhenReadyAtMs: Long = 0L
    private var lastPlayingAtMs: Long = 0L
    private var lastSeekAtMs: Long = 0L
    private var lastTransitionFinishedAtMs: Long = 0L
    private var lastMediaItemTransitionAtMs: Long = 0L

    /**
     * The **effective gain** the incoming track must land on when a crossfade completes:
     * `userDigitalGain * replayGainFactor`, not the ReplayGain factor alone.
     *
     * The name is load-bearing. This field previously held the bare ReplayGain multiplier, which
     * was correct only under the old model where ReplayGain *replaced* the user's volume. Now that
     * ReplayGain modifies it, passing the factor alone would make the fade land at a level that
     * ignores the user's own setting — audible as a jump at the instant the fade ends, and only at
     * that instant.
     *
     * Set by MusicService once ReplayGain for the incoming track is known; the crossfade loop reads
     * it at the end instead of hard-coding 1f. Reset to null after each transition.
     */
    var incomingTrackEffectiveVolume: Float? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val focusEvent = focusSequence.next()
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Timber.tag("TransitionDebug").d("AudioFocus LOSS. Pausing.")
                focusHeldOutright = false
                pausedByDuck = false
                isFocusLossPause = false
                invalidatePendingTransition("audio focus lost")
                playerA.playWhenReady = false
                playerB?.playWhenReady = false
                abandonAudioFocus()
                directDuckListener?.onFocusEvent(DuckFocusEvent.LOSS, focusEvent)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Timber.tag("TransitionDebug").d("AudioFocus LOSS_TRANSIENT. Pausing.")
                focusHeldOutright = false
                pauseForTransientFocusLoss()
                directDuckListener?.onFocusEvent(DuckFocusEvent.LOSS_TRANSIENT, focusEvent)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // The system hands this over only when it is not ducking for us: the request asks for it on USB
                // Direct, where the music is not in an AudioTrack the system can attenuate (A-05). On the Android
                // route nothing arrives here, and if something does the current behaviour stands.
                Timber.tag("TransitionDebug").d("AudioFocus LOSS_TRANSIENT_CAN_DUCK. Ducking Direct.")
                focusHeldOutright = false
                directDuckListener?.onFocusEvent(DuckFocusEvent.CAN_DUCK, focusEvent)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Timber.tag("TransitionDebug").d("AudioFocus GAIN. Resuming if paused by loss.")
                focusHeldOutright = true
                pausedByDuck = false
                if (isFocusLossPause) {
                    isFocusLossPause = false
                    playerA.playWhenReady = true
                    if (transitionRunning) playerB?.playWhenReady = true
                }
                directDuckListener?.onFocusEvent(DuckFocusEvent.GAIN, focusEvent)
            }
        }
    }

    private val masterPlayerListener = object : Player.Listener, AnalyticsListener, ExoPlayer.AudioOffloadListener {
        /**
         * Delivered on the main thread, which is what makes it usable as the mirror's update point;
         * see [cachedMasterVolume].
         */
        override fun onVolumeChanged(volume: Float) {
            cachedMasterVolume = volume.coerceIn(0f, 1f)
            _gainModel.value = _gainModel.value.copy(combinedGain = cachedMasterVolume)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            traceRoute(
                "CB_playWhenReady",
                "playWhenReady=$playWhenReady reason=$reason ${playerFacts()}"
            )
            if (playWhenReady) {
                // A play intent while a handoff is waiting means the user resumed on their own. The
                // handoff gives up rather than restoring playback over the top of them. When the
                // handoff's own resume lands, it has already concluded, so this is a no-op.
                autoDirectHandoff.onUserPlay()
                androidUsbRouteGuard.onUserTookControl()
                lastPlayWhenReadyAtMs = SystemClock.elapsedRealtime()
                requestAudioFocus()
                scheduleAudioOffloadFallbackIfNeeded(playerA)
            } else {
                // B27-A1 (R-01): a pause (the user's, a handoff's, a hold's) ends a crossfade that has not started to play. The
                // pause at the end of an item is the one the crossfade itself arms, and is not a reason to drop it.
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    invalidatePendingTransition("playback paused (reason $reason)")
                } else if (transitionRunning && !transitionFadeStarted && playerA.pauseAtEndOfMediaItems) {
                    transitionEndPause = true
                }
                // Media3 reports PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST for a programmatic pause
                // just as it does for a real one, so the reason cannot tell these apart. The handoff
                // is asked first whether this is the pause it issued; anything else is the user.
                if (!autoDirectHandoff.consumeOwnPause()) {
                    autoDirectHandoff.onUserPause()
                }
                if (!androidUsbRouteGuard.consumeOwnPause()) {
                    androidUsbRouteGuard.onUserTookControl()
                }
                cancelAudioOffloadFallback()
                mainHandler.post { reevaluateAutomaticRoute("Playback paused") }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            traceRoute("CB_isPlaying", "isPlaying=$isPlaying ${playerFacts()}")
            if (isPlaying) {
                lastPlayingAtMs = SystemClock.elapsedRealtime()
                cancelAudioOffloadFallback()
            }
        }

        /**
         * Fires when ExoPlayer believes the audio HAL is producing output via
         * offload and the renderer thread can stop polling — at that point the
         * CPU genuinely doesn't need a wake lock to keep playing audio. When
         * [sleepingForOffload] flips back to false (track change, format
         * mismatch, fallback path), restore [C.WAKE_MODE_LOCAL] so the
         * non-offload PCM path keeps the CPU awake correctly.
         *
         * Battery: this is what actually lets the SoC race-to-sleep during
         * music playback. The static [C.WAKE_MODE_LOCAL] we set at build time
         * is the safe default; this callback is the dynamic optimisation.
         */
        @Suppress("UnsafeOptInUsageError")
        override fun onSleepingForOffloadChanged(sleepingForOffload: Boolean) {
            if (!::playerA.isInitialized) return
            val baseMode = wakeModeFor(playerA.currentMediaItem)
            val desiredMode = if (sleepingForOffload && baseMode == C.WAKE_MODE_LOCAL) {
                C.WAKE_MODE_NONE
            } else {
                baseMode
            }
            if (currentWakeMode == desiredMode) return

            try {
                playerA.setWakeMode(desiredMode)
                playerB?.setWakeMode(desiredMode)
                currentWakeMode = desiredMode
                Timber.tag("DualPlayerEngine").d(
                    "Wake mode -> %d (sleepingForOffload=%b)",
                    desiredMode,
                    sleepingForOffload
                )
            } catch (e: Exception) {
                Timber.tag("DualPlayerEngine").w(e, "Failed to apply offload-aware wake mode")
            }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            val isHardware = AudioDecoderPolicy.isLikelyHardwareDecoder(decoderName)
            _activeDecoderInfo.value = ActiveDecoderInfo(decoderName, isHardware)
            Timber.tag("DualPlayerEngine").d("Audio decoder initialized: %s (Hardware: %b)", decoderName, isHardware)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            // A latched Direct failure is keyed by source rate, which is only known once the new
            // track decodes; re-evaluate then so a rate Direct can carry gets its one retry.
            if (directFailureLatch.isLatched) {
                mainHandler.post { reevaluateAutomaticRoute("Source format changed") }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId != 0 && _activeAudioSessionId.value != audioSessionId) {
                _activeAudioSessionId.value = audioSessionId
                Timber.tag("TransitionDebug").d("Master audio session changed: %d", audioSessionId)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A handoff is only allowed to restore the item the user was on. Next/previous/seeks are
            // the user moving on, and abort it; a natural end-of-track advance is not a user action
            // and re-targets instead. The Direct rebuild restores the same queue and reports
            // PLAYLIST_CHANGED, which is deliberately ignored — otherwise the handoff would cancel
            // itself on the very rebuild it exists to wait for.
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ->
                    autoDirectHandoff.onItemChanged(mediaItem?.mediaId, userInitiated = true)
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ->
                    autoDirectHandoff.onItemChanged(mediaItem?.mediaId, userInitiated = false)
            }
            lastMediaItemTransitionAtMs = SystemClock.elapsedRealtime()
            cancelAudioOffloadFallback()
            mainHandler.post { reevaluateAutomaticRoute("Source changed") }
            
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                cancelNext()
            }

            timedSection("DualPlayerEngine.applyWakeMode") { applyWakeModeForCurrentItem() }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                playlistGeneration++
                queueRevision = Any()
            }
            if (transitionRunning) return
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || queueSnapshot.isEmpty()) {
                refreshQueueSnapshotFromMaster(windowStartIndex = 0, usesWindowedQueue = false)
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // B17-H1: [setShuffleEnabled] widens a window before it flips the flag, so it never gets here with one. A flip
            // that did not come through it (a head unit's or Android Auto's standard `setShuffleModeEnabled`) landed on a
            // master whose window was cut for the other order; it is widened, which restores the whole queue in its own
            // order and leaves the flag as it was asked. B18-R4: during a fade that waits for the fade to end (before, it was
            // dropped), and it is newer than any request of ours that was waiting for the same fade.
            shuffleGate.onFlagChanged()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    val now = SystemClock.elapsedRealtime()
                    val timeSincePlayingMs = now - lastPlayingAtMs
                    val timeSinceSeekMs = now - lastSeekAtMs
                    val timeSinceTransitionMs = now - lastTransitionFinishedAtMs
                    val timeSinceMediaItemTransitionMs = now - lastMediaItemTransitionAtMs
                    val isPostSeekBuffering = lastSeekAtMs > 0L && timeSinceSeekMs < 1_500L
                    val isPostTransitionBuffering = lastTransitionFinishedAtMs > 0L &&
                        timeSinceTransitionMs < POST_TRANSITION_OFFLOAD_GUARD_MS
                    val isPostMediaItemTransition = lastMediaItemTransitionAtMs > 0L &&
                        timeSinceMediaItemTransitionMs < 2_000L
                    if (shouldDisableAudioOffloadOnEarlyBuffering(
                            audioOffloadEnabled = audioOffloadEnabled,
                            transitionRunning = transitionRunning,
                            lastPlayingAtMs = lastPlayingAtMs,
                            timeSincePlayingMs = timeSincePlayingMs,
                            isPostSeekBuffering = isPostSeekBuffering,
                            isPostTransitionBuffering = isPostTransitionBuffering,
                            isPostMediaItemTransition = isPostMediaItemTransition
                        )
                    ) {
                        disableAudioOffloadForSession(
                            reason = "HAL offload reset detected: STATE_BUFFERING after ${timeSincePlayingMs}ms of playback"
                        )
                    } else {
                        scheduleAudioOffloadFallbackIfNeeded(playerA)
                    }
                }
                Player.STATE_READY -> scheduleAudioOffloadFallbackIfNeeded(playerA)
                Player.STATE_IDLE, Player.STATE_ENDED -> cancelAudioOffloadFallback()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                lastSeekAtMs = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun addMasterPlayerListeners(player: ExoPlayer) {
        // Seed the mirror from the freshly built player before anything can read it. A listener
        // only reports *changes*, so without this a Direct stream configured before the first
        // volume change would apply the initial 1f and silently ignore a player that was built at
        // some other level.
        cachedMasterVolume = player.volume.coerceIn(0f, 1f)
        _gainModel.value = _gainModel.value.copy(combinedGain = cachedMasterVolume)
        player.addListener(masterPlayerListener)
        player.addAnalyticsListener(masterPlayerListener)
        player.addAudioOffloadListener(masterPlayerListener)
    }

    private fun removeMasterPlayerListeners(player: ExoPlayer) {
        player.removeListener(masterPlayerListener)
        player.removeAnalyticsListener(masterPlayerListener)
        player.removeAudioOffloadListener(masterPlayerListener)
    }

    fun addPlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.add(listener)
    }

    fun removePlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.remove(listener)
    }

    fun addTransitionDisplayPlayerListener(listener: (Player) -> Unit) {
        onTransitionDisplayPlayerListeners.add(listener)
    }

    fun removeTransitionDisplayPlayerListener(listener: (Player) -> Unit) {
        onTransitionDisplayPlayerListeners.remove(listener)
    }

    fun addTransitionFinishedListener(listener: () -> Unit) {
        onTransitionFinishedListeners.add(listener)
    }

    /**
     * Notifies the engine that an external caller (UI seek, etc.) is about to issue a
     * seek through the MediaController. Used to mark the upcoming STATE_BUFFERING as
     * seek-driven so the HAL-reset heuristic does not trigger a player rebuild that
     * would race with the in-flight seek command.
     *
     * Setting this here (synchronously, before the seek dispatches) is more reliable
     * than waiting for onPositionDiscontinuity, which is delivered on the next event
     * batch and can race with onPlaybackStateChanged on some Media3 versions.
     */
    /**
     * Declares the media position at which the next item start will begin, in milliseconds.
     *
     * The single authority for an item's start position. Whoever starts or resumes the player —
     * session restore, queue replacement, an explicit track selection — calls this with the same
     * number it hands Media3, so the renderer mapping and the native producer are initialised from
     * one intent. The declaration applies to exactly one item start and then expires.
     *
     * `0` means START-FRESH, which is what an explicit "play this track" means. A resume must pass
     * its real position: omitting it is not a neutral default, it is a different policy from the one
     * Media3 is being given.
     */
    fun declareItemStartTarget(positionMs: Long) {
        val sink = if (::playerA.isInitialized) playerUsbSinks[playerA] else null
        sink?.requestStartTargetUs(positionMs.coerceAtLeast(0L) * 1_000L)
    }

    /**
     * Diagnostic only: records a Player operation with the live pending-ACK count.
     *
     * Called immediately before and after forwarding one operation, so an observed count
     * transition inside that window can be attributed to that operation exactly rather than by
     * timestamp proximity.
     */
    fun notePlayerOperation(phase: String, operation: String) {
        val sink = (if (::playerA.isInitialized) playerUsbSinks[playerA] else null) ?: return
        sink.notePlayerOperation(phase, operation, sink.diagnosticPendingAcks())
    }

    /**
     * Diagnostic only: the Direct sink's control-precondition reading, or a marker when no sink
     * exists. Never read by playback logic.
     */
    /** Diagnostic only: the compact state the readiness probe is evaluating. */
    fun diagnosticProvocationBrief(): String {
        val sink = if (::playerA.isInitialized) playerUsbSinks[playerA] else null
        val liveState = if (::playerA.isInitialized) playerA.playbackState else -1
        return sink?.provocationStateBrief(liveState) ?: "sink=null"
    }

    /** Diagnostic only: whether the sink is inside the natural STATE_ENDED window. */
    /**
     * Diagnostic only: whether the Player is in STATE_ENDED, read live on its own looper.
     *
     * Engine state is deliberately NOT part of readiness — see [UsbAudioSink.provokeMappingUnlatch].
     * This runs on the main looper, which is the player's application looper, so the getter is legal
     * and the value is not a 250 ms-stale mirror sample.
     */
    fun diagnosticProvocationReady(): Boolean =
        if (::playerA.isInitialized) playerA.playbackState == Player.STATE_ENDED else false

    /**
     * Diagnostic only: apply the mapping-state provocation and stop.
     *
     * Deliberately does NOT trigger playback. The provocation's effect persists — `windowOffsetUs`
     * stays unlatched until something latches it — so the trigger can be a real UI action performed
     * afterwards, which is what lets the differential use the genuine `shouldSeekToStart` path
     * instead of a substitute.
     */
    fun diagnosticProvokeOnly(): String {
        val sink = if (::playerA.isInitialized) playerUsbSinks[playerA] else null
        val liveState = if (::playerA.isInitialized) playerA.playbackState else -1
        return sink?.provokeMappingUnlatch(liveState) ?: "PROVOCATION_INVALID reason=no-sink"
    }

    /**
     * Diagnostic only: provocation, then the same Play entry the UI's transport control reaches.
     *
     * The Play call mirrors `PlaybackStateHolder.playPause()`'s play branch exactly — prepare when
     * idle, then `play()`. Nothing seeks directly and nothing touches the sink's internals after the
     * provocation: `shouldSeekToStart`, `seekTo`, `flush`, `configure` and the latch are all decided
     * by unmodified production code.
     */
    fun diagnosticProvokeAndPlay(): String {
        val sink = if (::playerA.isInitialized) playerUsbSinks[playerA] else null
        val liveState = if (::playerA.isInitialized) playerA.playbackState else -1
        val provocation = sink?.provokeMappingUnlatch(liveState) ?: "PROVOCATION_INVALID reason=no-sink"
        if (!provocation.contains("valid=true")) return "$provocation | PLAY_SKIPPED"
        val p = masterPlayer
        val stateAtTrigger = p.playbackState
        val pwrBefore = p.playWhenReady
        if (p.playbackState == Player.STATE_IDLE && p.mediaItemCount > 0) p.prepare()
        p.play()
        return "$provocation | PLAY_TRIGGER playbackStateAtTrigger=$stateAtTrigger" +
            " playWhenReadyBefore=$pwrBefore playWhenReadyAfter=${p.playWhenReady}"
    }

    fun diagnosticControlPrecondition(): String {
        val sink = if (::playerA.isInitialized) playerUsbSinks[playerA] else null
        return sink?.controlPreconditionReport() ?: "sink=null"
    }

    /** The UI's own notification and the session's echo of the same seek are forwarded once. */
    private val explicitSeekDeduper = ExplicitSeekDeduper()

    fun notifyExternalSeekInitiated(targetPositionMs: Long) {
        lastSeekAtMs = SystemClock.elapsedRealtime()
        if (!explicitSeekDeduper.shouldForward(targetPositionMs, lastSeekAtMs)) return

        // Push the requested media target straight to the native producer.
        //
        // This is the only place the user's target still exists in the form they asked for.
        // Everything downstream used to reconstruct it — from presentationTimeUs, windowOffsetUs
        // or player.currentPosition — and every one of those is either renderer-domain or
        // delivered by a callback that PRISTINE is allowed to starve, so the seek was accepted
        // by the player and never performed by the engine. Handing it over here means the seek
        // does not depend on ExoPlayer delivering PCM.
        //
        // `targetPositionMs` is already sanitised (coerceAtLeast(0)) by the caller and is in
        // media-item time, which is the only domain the engine's seek accepts.
        val sink = if (::playerA.isInitialized) playerUsbSinks[playerA] else null
        sink?.requestMediaSeek(targetPositionMs * 1_000L)
    }

    fun removeTransitionFinishedListener(listener: () -> Unit) {
        onTransitionFinishedListeners.remove(listener)
    }

    val masterPlayer: Player
        get() {
            initialize()
            return playerA
        }

    fun isTransitionRunning(): Boolean = transitionRunning

    fun isUsingWindowedQueue(): Boolean = activePlayerUsesWindowedQueue

    fun getFullQueue(): List<MediaItem> = ensureQueueSnapshot()

    fun getCurrentAbsoluteIndex(): Int {
        if (!::playerA.isInitialized) return 0
        val mediaItem = playerA.currentMediaItem ?: return playerA.currentMediaItemIndex.coerceAtLeast(0)
        val snapshot = ensureQueueSnapshot()
        val index = resolveCurrentAbsoluteIndex(mediaItem, snapshot)
        return if (index == C.INDEX_UNSET) {
            if (activePlayerUsesWindowedQueue) {
                (activeWindowStartIndex + playerA.currentMediaItemIndex).coerceIn(0, (snapshot.size - 1).coerceAtLeast(0))
            } else {
                playerA.currentMediaItemIndex.coerceAtLeast(0)
            }
        } else {
            index
        }
    }

    /** The queue as the engine would rebuild or persist it: whole, in its base order, with the shuffle order if any. */
    class CapturedQueue(
        val items: List<MediaItem>,
        /** Where the current song is in [items] (base order). */
        val currentIndex: Int,
        /** Positions to indices into [items] while shuffle is on; null with shuffle off. */
        val playOrder: IntArray?,
    )

    /**
     * The whole queue in the order it was loaded in (never the crossfade window), the current song's place in it, and the
     * shuffle order. B17-H1: shuffle no longer reorders the timeline, so this is also what a snapshot to disk and a rebuild
     * of the master player need to bring shuffle back as it was.
     */
    fun captureQueue(): CapturedQueue {
        if (!::playerA.isInitialized) return CapturedQueue(emptyList(), 0, null)
        val windowed = activePlayerUsesWindowedQueue
        val shuffleOn = playerA.shuffleModeEnabled
        val base = windowedBaseQueue
        val order = windowedPlayOrder
        if (windowed && base != null && order != null) {
            // The master holds a window of the play order; the real queue is what it was cut from. (Decided by the state,
            // not by the flag: the flag may have just been flipped under the window by someone else.)
            return CapturedQueue(base, order.getOrNull(getCurrentAbsoluteIndex()) ?: 0, order.takeIf { shuffleOn })
        }
        val (items, index) = com.lostf1sh.pixelplayeross.data.service.QueueSource.select(
            windowed = windowed,
            fullQueue = if (windowed) ensureQueueSnapshot() else emptyList(),
            absoluteIndex = if (windowed) getCurrentAbsoluteIndex() else 0,
            playerItems = List(playerA.mediaItemCount, playerA::getMediaItemAt),
            playerIndex = playerA.currentMediaItemIndex,
        )
        val playOrder = when {
            !shuffleOn -> null
            // A window of a play-ordered list whose base is gone: keep the sequence it has.
            windowed -> PlayOrder.identity(items.size)
            else -> PlayOrder.of(playerA.currentTimeline, shuffleEnabled = true)
                .takeIf { PlayOrder.isPermutation(it, items.size) }
        }
        return CapturedQueue(items, index, playOrder)
    }

    /**
     * While the master holds a window cut from the play order: the queue it was cut from, in its own order, and the play
     * order over it (positions to indices into that queue). Null in every other state.
     */
    fun windowedShuffleQueue(): Pair<List<MediaItem>, IntArray>? {
        val base = windowedBaseQueue ?: return null
        val order = windowedPlayOrder ?: return null
        return base to order
    }

    /** Whether the master plays its queue in shuffle order. */
    val isShuffleEnabled: Boolean
        get() = ::playerA.isInitialized && playerA.shuffleModeEnabled

    /**
     * Whether shuffle is on **once everything that was asked for has happened**: the request that waits for a crossfade to end if
     * there is one, else the master's flag. Every toggle (the app's button, the notification and lock-screen action) flips this,
     * not [isShuffleEnabled], which a pending request has not moved yet (B19-S2).
     */
    val shuffleIntent: Boolean
        get() = ::playerA.isInitialized && shuffleGate.intent

    /**
     * Turns shuffle on or off. **No media item moves and nothing is republished** (B17-H1): the flag changes, the
     * player keeps playing the current song and picks the next one from its shuffle order, or in the order the queue was
     * loaded in when it is off. That is Media3's own shuffle mode; the timeline is the base order whatever the flag.
     *
     * One state is not a flag flip: a master that holds a *window* of the queue (after a crossfade on a queue longer than
     * [MAX_AUXILIARY_TIMELINE_ITEMS]) cannot change its order by flag, because the window was cut for the order it had. It
     * is first widened back to the whole queue (two replacements around the song that is playing, which is what turning
     * shuffle on or off used to cost every time), then the flag is set. A fade in progress finishes first.
     */
    fun setShuffleEnabled(enabled: Boolean) {
        if (masterPlayer !is ExoPlayer) return
        shuffleGate.request(enabled, freshOrder = true)
    }

    /**
     * The flag for a queue that is being loaded (B18-R1): the same as [setShuffleEnabled] except that classic mode does not deal
     * the queue again, because a load brings its own order (a "Shuffle" button's sequence, a persistent shuffle's draw) and is
     * attached after this call.
     */
    fun setShuffleForQueueLoad(enabled: Boolean) {
        if (masterPlayer !is ExoPlayer) return
        shuffleGate.request(enabled, freshOrder = false)
    }

    /**
     * Skips (next / previous) that arrive while a queue is still being loaded and have nowhere to go yet; see [QueueLoadSkipHold].
     * Shared by the app's load and the session's player, which consults it for every skip from every controller (B18-R2).
     */
    val queueLoadHold = QueueLoadSkipHold()

    /**
     * What switching shuffle on does to the order (B18-R1): classic deals the queue again, fast leaves the order the player
     * holds. The service keeps this in step with the stored preference; the default is the preference's.
     */
    @Volatile
    var randomMode: RandomMode = RandomMode.DEFAULT

    /** Counts the replacements and re-orderings of the master's queue (main thread): a draw made for one queue is never applied to another. */
    private var playlistGeneration = 0L

    /**
     * A token that is replaced whenever the master's queue changes (an item added, removed, moved or replaced, or the order
     * changed) and **not** when only the timeline object is: ExoPlayer hands out a new `Timeline` for every source update, which is
     * one or two per song change (the duration of the item that was just prepared). Things that are derived from the whole queue
     * and only depend on its content, such as the playback snapshot's 4,870 items, key on this and not on the timeline (B18-R3).
     */
    @Volatile
    var queueRevision: Any = Any()
        private set
    private var redrawJob: Job? = null

    /**
     * Classic mode: deals the queue again after shuffle was switched on. The permutation is built off the main thread, then ONE
     * `setShuffleOrder` over the timeline the player holds (see [ShuffleRedraw]); the song that is playing goes first and is
     * neither cut nor re-prepared. Logs the milliseconds that publication takes on the main thread.
     */
    private fun dealQueueAgain() {
        val player = playerA
        if (player.mediaItemCount < 2 || activePlayerUsesWindowedQueue) return
        redrawJob?.cancel()
        redrawJob = scope.launch {
            ShuffleRedraw.redraw(object : ShuffleRedraw.Target {
                override val generation: Long get() = if (playerA === player) playlistGeneration else -1L
                override val itemCount: Int get() = player.mediaItemCount
                override val currentIndex: Int get() = player.currentMediaItemIndex.let { if (it == C.INDEX_UNSET) -1 else it }
                override val shuffleOn: Boolean get() = player.shuffleModeEnabled

                override fun publishOrder(order: IntArray) {
                    val startedAt = SystemClock.elapsedRealtime()
                    val applied = traceSection("DualPlayerEngine.dealQueueAgain") { setPlayOrder(order) }
                    Timber.tag("DualPlayerEngine").i(
                        "Shuffle on (classic): new order of %d items, 1 timeline publication, %d ms on the main thread, applied=%s",
                        order.size, SystemClock.elapsedRealtime() - startedAt, applied,
                    )
                }
            })
        }
    }

    /**
     * Shuffle requests that cannot be applied yet (B18-R4): one intent, the last one wins, applied when the fade ends. See
     * [ShuffleRequestGate]; this is only the player behind it.
     */
    private val shuffleGate: ShuffleRequestGate = ShuffleRequestGate(object : ShuffleRequestGate.Host {
        /** The listener that is registered for as long as something is deferred. */
        private val onFadeEnded: () -> Unit = { mainHandler.post { shuffleGate.settle() } }

        override val shuffleOn: Boolean get() = ::playerA.isInitialized && playerA.shuffleModeEnabled
        override val windowed: Boolean get() = activePlayerUsesWindowedQueue
        override val transitionRunning: Boolean get() = this@DualPlayerEngine.transitionRunning

        override fun widen(): Boolean {
            val widened = unwindowQueue()
            if (!widened) {
                Timber.tag("DualPlayerEngine").w("Shuffle: could not widen the windowed queue; only the flag changes")
            }
            return widened
        }

        override fun setFlag(enabled: Boolean, freshOrder: Boolean) {
            val player = playerA
            player.shuffleModeEnabled = enabled
            if (enabled && freshOrder && randomMode == RandomMode.CLASSIC) {
                Timber.tag("DualPlayerEngine").i("Shuffle on: flag set, %d items; classic mode deals them again", player.mediaItemCount)
                dealQueueAgain()
            } else {
                Timber.tag("DualPlayerEngine").i(
                    "Shuffle %s: flag only, %d items, no timeline change", if (enabled) "on" else "off", player.mediaItemCount,
                )
            }
        }

        override fun watchTransitionEnd() = addTransitionFinishedListener(onFadeEnded)
        override fun unwatchTransitionEnd() = removeTransitionFinishedListener(onFadeEnded)
        override fun later(action: () -> Unit) {
            mainHandler.post(action)
        }
    })

    /**
     * Replaces the shuffle order (positions to timeline indices). One timeline change: the order is part of the timeline
     * the session publishes. For an edit of the order that is not a toggle (a drag in the queue, "play next", a restore).
     * False when it cannot apply: the master holds a window, or [order] is not a permutation of its queue.
     */
    fun setPlayOrder(order: IntArray): Boolean {
        val player = masterPlayer as? ExoPlayer ?: return false
        if (activePlayerUsesWindowedQueue) return false
        if (!PlayOrder.isPermutation(order, player.mediaItemCount)) return false
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
        return true
    }

    /** The order the master plays in over its own timeline, or null while it holds a window of the queue. */
    fun currentPlayOrder(): IntArray? {
        val player = masterPlayer as? ExoPlayer ?: return null
        if (activePlayerUsesWindowedQueue) return null
        return PlayOrder.of(player.currentTimeline, player.shuffleModeEnabled)
    }

    /**
     * Gives the master the whole queue in its base order again, around the song that is playing and without stopping it.
     * Used only by [setShuffleEnabled] on a windowed master; `onTimelineChanged` then refreshes the snapshot as for any
     * other queue change.
     */
    private fun unwindowQueue(): Boolean {
        val player = playerA
        val captured = captureQueue()
        val baseIndex = captured.currentIndex
        val base = captured.items
        val playing = player.currentMediaItem ?: return false
        if (baseIndex !in base.indices || base[baseIndex].mediaId != playing.mediaId) return false
        val windowIndex = player.currentMediaItemIndex
        if (windowIndex !in 0 until player.mediaItemCount) return false

        val before = base.subList(0, baseIndex)
        val after = base.subList(baseIndex + 1, base.size)
        if (windowIndex > 0 || before.isNotEmpty()) {
            player.replaceMediaItems(0, windowIndex, before)
        }
        if (player.currentMediaItemIndex != baseIndex) return false
        player.replaceMediaItems(baseIndex + 1, player.mediaItemCount, after)
        refreshQueueSnapshotFromMaster(windowStartIndex = 0, usesWindowedQueue = false)
        return true
    }

    private var isReleased = false

    fun initialize() {
        if (!isReleased && ::playerA.isInitialized && playerA.applicationLooper.thread.isAlive) return
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        abandonTransitionForRebuild()

        if (::playerA.isInitialized) {
            removeMasterPlayerListeners(playerA)
            onPlayerAboutToBeReleasedListener?.invoke(playerA)
            try { releasePlayer(playerA) } catch (e: Exception) { }
        }
        playerB?.let { try { releasePlayer(it) } catch (e: Exception) { } }
        playerB = null

        playerA = buildPlayer(allowDirect = desiredBackend == AudioBackend.USB_DIRECT)
        recordDirectCapability()
        refreshUsbAudioDiagnostics()

        addMasterPlayerListeners(playerA)
        if (!audioOutputsCallbackRegistered) {
            context.getSystemService(AudioManager::class.java)
                ?.registerAudioDeviceCallback(audioOutputsCallback, mainHandler)
            audioOutputsCallbackRegistered = true
            // Any hand-back — this engine's rebuild or the sink's failure path — arms the signal.
            // Posted ahead of the device callback, which Android delivers only after the kernel
            // has rebound snd-usb-audio and the framework re-added the ALSA device.
            UsbAudioDevice.getInstance(context).releaseListener = {
                mainHandler.post { usbRouteSignal.onReleasedToAndroid() }
            }
            // Posted after the one above: the hand-back's own GETDRIVER result (fix2 regression).
            UsbAudioDevice.getInstance(context).releasedDriversListener = { control, streaming ->
                mainHandler.post {
                    usbRouteSignal.onKernelDriversAfterRelease(control, streaming)
                    if (androidUsbRouteGuard.isHolding) reevaluateAutomaticRoute("USB drivers rebound")
                }
            }
        }

        _activeAudioSessionId.value = playerA.audioSessionId
        isReleased = false
        queueSnapshot = emptyList()
        activeWindowStartIndex = 0
        activePlayerUsesWindowedQueue = false
        windowedBaseQueue = null
        windowedPlayOrder = null
        resetPreparedWindowState()
    }

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return

        val request = buildAudioFocusRequest(pauseWhenDucked = wantsDuckCallback())

        val result = audioManager.requestAudioFocus(request)
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                audioFocusRequest = request
                focusRequestPausesWhenDucked = request.willPauseWhenDucked()
                focusHeldOutright = true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                audioFocusRequest = request
                focusRequestPausesWhenDucked = request.willPauseWhenDucked()
                focusHeldOutright = false
                isFocusLossPause = true
                playerA.playWhenReady = false
                if (transitionRunning) playerB?.playWhenReady = false
            }
            else -> {
                Timber.tag("TransitionDebug").w("AudioFocus Request Failed: $result")
                _focusDenials.value = _focusDenials.value + 1
                playerA.playWhenReady = false
            }
        }
    }

    private fun buildAudioFocusRequest(pauseWhenDucked: Boolean): AudioFocusRequest {
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .setAcceptsDelayedFocusGain(true)
            // The system ducks an app's AudioTracks by itself and does not tell it. On USB Direct that ducks only
            // the silent presence track while the music goes over usbfs untouched, so on Direct the request asks
            // for the callback instead (B14-3). On the Android route it does not: the system's ducking stands.
            .setWillPauseWhenDucked(pauseWhenDucked)
            .build()
    }

    /** Whether the signal is on USB Direct right now, i.e. whether the system's ducking would miss the music. */
    private fun wantsDuckCallback(): Boolean = _audioRouteState.value.isDirectActive

    /**
     * Keeps the held focus request in step with the route: the flag that makes the system hand over "may duck"
     * belongs to Direct only. Called from the service when the route's backend or signal mode changes.
     *
     * Only an outright holder replaces its request (same listener, so the system updates it in place). Anyone
     * else has lost focus, or been ducked, to an app that is using it: asking again would take it back.
     */
    fun syncFocusRequestToRoute() {
        mainHandler.post {
            val current = audioFocusRequest ?: return@post
            val wanted = wantsDuckCallback()
            if (!focusHeldOutright || wanted == focusRequestPausesWhenDucked) return@post
            val replacement = buildAudioFocusRequest(pauseWhenDucked = wanted)
            if (audioManager.requestAudioFocus(replacement) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFocusRequest = replacement
                focusRequestPausesWhenDucked = wanted
            } else {
                // Keep the request that works; the route and the flag disagree until the next change.
                Timber.tag("TransitionDebug").w("AudioFocus re-request for the route was not granted; keeping %s", current)
            }
        }
    }

    private fun abandonAudioFocus() {
        focusHeldOutright = false
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    private fun scheduleAudioOffloadFallbackIfNeeded(player: ExoPlayer) {
        cancelAudioOffloadFallback()
        if (!audioOffloadEnabled || transitionRunning || !player.playWhenReady || player.isPlaying) return
        // On USB Direct the PCM never reaches the platform AudioTrack that offload is about, and the
        // player can sit in BUFFERING for seconds while the native engine and the USB stream come up.
        // Arming this there read that as "offload stalled" and rebuilt the whole player mid-track,
        // which reopened the DAC and restarted the song. Measured: a rebuild 4.4 s after every
        // library tap, with the stream reopened and the start position re-declared.
        val route = _audioRouteState.value
        if (route.actualBackend == AudioBackend.USB_DIRECT || route.desiredBackend == AudioBackend.USB_DIRECT) return
        if (!isLikelyLocalMedia(player.currentMediaItem)) return

        val watchedMediaId = player.currentMediaItem?.mediaId ?: return
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return
        bufferingFallbackJob = scope.launch {
            delay(AUDIO_OFFLOAD_STALL_FALLBACK_MS)

            val currentMediaId = player.currentMediaItem?.mediaId
            val shouldFallback = shouldTriggerAudioOffloadStallFallback(
                audioOffloadEnabled = audioOffloadEnabled,
                transitionRunning = transitionRunning,
                isCurrentMasterPlayer = player === playerA,
                mediaIdMatches = currentMediaId == watchedMediaId,
                playbackState = player.playbackState,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                playbackSuppressionReason = player.playbackSuppressionReason
            )
            if (!shouldFallback) return@launch

            disableAudioOffloadForSession(
                reason = "Local media did not produce audio for " +
                    "${AUDIO_OFFLOAD_STALL_FALLBACK_MS}ms (state=${player.playbackState})"
            )
        }
    }

    private fun cancelAudioOffloadFallback() {
        bufferingFallbackJob?.cancel()
        bufferingFallbackJob = null
    }

    private fun isLikelyLocalMedia(mediaItem: MediaItem?): Boolean {
        val scheme = mediaItem?.localConfiguration?.uri?.scheme?.lowercase()
        return scheme == null || scheme in LOCAL_MEDIA_SCHEMES
    }

    private fun wakeModeFor(mediaItem: MediaItem?): Int {
        val scheme = mediaItem?.localConfiguration?.uri?.scheme?.lowercase()
        return if (scheme != null && scheme in REMOTE_MEDIA_SCHEMES) {
            C.WAKE_MODE_NETWORK
        } else {
            C.WAKE_MODE_LOCAL
        }
    }

    private var currentWakeMode: Int = C.WAKE_MODE_LOCAL

    private fun applyWakeModeForCurrentItem() {
        if (!::playerA.isInitialized) return
        val mode = wakeModeFor(playerA.currentMediaItem)
        if (currentWakeMode == mode) return
        
        try {
            playerA.setWakeMode(mode)
            playerB?.setWakeMode(mode)
            currentWakeMode = mode
            Timber.tag("DualPlayerEngine").d("Wake mode updated to %d", mode)
        } catch (e: Exception) {
            Timber.tag("DualPlayerEngine").w(e, "Failed to update wake mode")
        }
    }

    private fun shouldDisableAudioOffloadByDefault(): Boolean {
        return shouldDisableAudioOffloadByDefaultForDevice(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            hardware = Build.HARDWARE,
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    private fun disableAudioOffloadForSession(reason: String) {
        if (!audioOffloadEnabled) return
        if (transitionRunning) {
            Timber.tag("DualPlayerEngine").w("Skipping offload fallback during active transition. %s", reason)
            return
        }

        audioOffloadEnabled = false
        rebuildPlayersPreservingMasterState(
            logMessage = "Audio offload disabled for current session. $reason"
        )
    }

    /**
     * Rebuilds the master player with the route loop held off for the duration.
     *
     * The rebuild asks for a route reevaluation part-way through: [refreshUsbAudioDiagnostics] ends
     * by requesting one, and it is called before the queue is restored and before
     * [recordDirectCapability] runs. This wrapper exists to remember that request rather than run it
     * against a half-built engine, and to replay it once. See [MasterRebuildCycle] for why running
     * it inline is fatal rather than merely untidy.
     */
    private fun rebuildPlayersPreservingMasterState(logMessage: String) {
        if (!masterRebuildCycle.beginRebuild()) {
            // The reevaluation that authorises a rebuild is deferred while one is in flight, so
            // nothing reaches this today. Refusing keeps a future call site from releasing the
            // player the in-flight rebuild is still assembling.
            Timber.tag("DualPlayerEngine").w("Ignoring nested master rebuild: $logMessage")
            return
        }
        // Counted, not acted on. A rebuild count is how a session record distinguishes "converged
        // once" from "oscillating", and it has to be counted where the rebuild is authorised rather
        // than inferred from route traces after the fact.
        masterRebuildCount.incrementAndGet()
        com.decent.usbaudio.media3.DirectPathTelemetry.recordIfCapturing("REBUILD") { logMessage }
        var replayDeferredReevaluation = false
        try {
            reassembleMasterPlayer(logMessage)
        } finally {
            replayDeferredReevaluation = masterRebuildCycle.endRebuild()
        }
        if (replayDeferredReevaluation) {
            // The engine is whole again — queue restored, capability latch written — so this
            // evaluation reads the facts the rebuild produced rather than the ones that authorised
            // it. Those facts are a fixed point, which is what keeps the replay from rebuilding.
            reevaluateAutomaticRoute("$logMessage: deferred route reevaluation")
        }
    }

    /** The rebuild itself. Reach it through [rebuildPlayersPreservingMasterState], never directly. */
    private fun reassembleMasterPlayer(logMessage: String) {
        cancelAudioOffloadFallback()
        // B27-A1 (R-01): a crossfade holds the players about to be released; it must not touch their successors.
        abandonTransitionForRebuild()

        val desiredPlayWhenReady = playerA.playWhenReady
        val positionMs = playerA.currentPosition.coerceAtLeast(0L)
        // A windowed master holds only part of the queue; rebuild from the whole of it, at the
        // absolute index, or the rebuilt player (and its snapshot) keeps just the window (AUD-023).
        // B17-H1: and with the shuffle order it had, or the rebuild would deal the shuffle again.
        val captured = captureQueue()
        val currentIndex = captured.currentIndex.coerceAtLeast(0)
        val mediaItems = ArrayList<MediaItem>(captured.items)
        val repeatMode = playerA.repeatMode
        val shuffleMode = playerA.shuffleModeEnabled
        val volume = playerA.volume
        val pauseAtEnd = playerA.pauseAtEndOfMediaItems
        val playbackParameters: PlaybackParameters = playerA.playbackParameters

        removeMasterPlayerListeners(playerA)
        onPlayerAboutToBeReleasedListener?.invoke(playerA)
        releasePlayer(playerA)
        playerB?.let(::releasePlayer)
        playerB = null
        // Leaving Direct: hand the DAC back to Android (release both interfaces, reattach
        // snd-usb-audio, close) instead of keeping it claimed, which left the Android path playing
        // to the speaker or a dead route (AUD-003). No-op when nothing is open.
        if (desiredBackend != AudioBackend.USB_DIRECT) {
            UsbAudioDevice.getInstance(context).releaseToAndroid()
        }

        playerA = buildPlayer(allowDirect = desiredBackend == AudioBackend.USB_DIRECT)
        refreshUsbAudioDiagnostics()

        addMasterPlayerListeners(playerA)
        playerA.volume = volume
        playerA.pauseAtEndOfMediaItems = pauseAtEnd
        playerA.playbackParameters = playbackParameters

        if (mediaItems.isNotEmpty()) {
            // This is a RESUME at `positionMs`, so the Direct path is told the same number before
            // the item starts. Without it Media3 resumes here while the native engine starts at its
            // START-FRESH default of 0, and the first mapping latch bakes the difference into
            // windowOffsetUs — measured on the HONOR as a player position running 149s ahead of the
            // native engine for the whole item.
            declareItemStartTarget(positionMs)
            playerA.setMediaItems(mediaItems, currentIndex, positionMs)
            playerA.repeatMode = repeatMode
            playerA.shuffleModeEnabled = shuffleMode
            captured.playOrder
                ?.takeIf { shuffleMode && PlayOrder.isPermutation(it, mediaItems.size) }
                ?.let { playerA.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(it, System.nanoTime())) }
            playerA.prepare()
            playerA.playWhenReady = desiredPlayWhenReady
            applyWakeModeForCurrentItem()
        }

        // After the queue is restored, never before: whether this player can claim Direct is a
        // statement about the player that now exists, and the restore above is what puts a source
        // in front of the sink. A Direct-capable player rebuilt against an empty queue stays
        // eligible for its one claim attempt, so the next source to arrive can still reach Direct.
        recordDirectCapability()

        _activeAudioSessionId.value = playerA.audioSessionId
        onPlayerSwappedListeners.forEach { it(playerA) }

        Timber.tag("DualPlayerEngine").d(logMessage)
    }

    /**
     * Records the Direct capability of the player just built, and whether that build spent the
     * claim opportunity.
     *
     * The two are not the same question. Building the Direct sink is capability; the sink claiming
     * the route only happens once `UsbAudioSink.configure()` runs, which needs a media item to
     * configure from. A build with an empty queue therefore establishes the former without ever
     * having exercised the latter, and must leave the claim still owed.
     */
    private fun recordDirectCapability() {
        playerBuiltWithDirectAccess = desiredBackend == AudioBackend.USB_DIRECT
        directClaimAttempted = playerBuiltWithDirectAccess && playerA.mediaItemCount > 0
    }

    /**
     * Returns a [DefaultLoadControl] tuned to the device's RAM tier.
     *
     * Low-RAM devices ([ActivityManager.isLowRamDevice]) receive halved buffer ceilings
     * to prevent memory pressure when both players co-exist during a crossfade.
     * [bufferForPlaybackMs] is set to ExoPlayer's documented default of 2 500 ms on both
     * tiers — the previous value of 5 000 ms doubled first-audio latency with no benefit.
     */
    private fun buildAdaptiveLoadControl(): DefaultLoadControl {
        val isLowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .isLowRamDevice
        return if (isLowRam) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    15_000,
                    30_000,
                    2_500,
                    5_000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    30_000,
                    60_000,
                    2_500,
                    5_000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
    }

    /**
     * Returns the process-wide audio session id every player is pinned to, generating it on first
     * use, or `null` when the platform refuses to allocate one (then ExoPlayer keeps its
     * per-instance behaviour).
     */
    private fun sharedAudioSessionIdOrNull(): Int? {
        if (sharedAudioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            sharedAudioSessionId = try {
                audioManager.generateAudioSessionId()
            } catch (e: Exception) {
                Timber.tag("DualPlayerEngine").w(e, "Failed to generate a shared audio session id")
                AudioManager.ERROR
            }
        }
        return sharedAudioSessionId.takeIf {
            it != AudioManager.ERROR && it != C.AUDIO_SESSION_ID_UNSET
        }
    }

    private fun buildPlayer(allowDirect: Boolean): ExoPlayer {
        var builtUsbSink: UsbAudioSink? = null
        val mediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoderInfos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )

            AudioDecoderPolicy.selectPlatformDecoders(mimeType, decoderInfos)
        }
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                val hasLibFlac = try {
                    Class.forName("androidx.media3.decoder.flac.LibflacAudioRenderer")
                    true
                } catch (_: ClassNotFoundException) {
                    false
                }
                val delegate = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(!hasLibFlac)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            HiResSampleRateCapAudioProcessor(),
                            SurroundDownmixProcessor(),
                            // Phase 8.5 headphone Graphic EQ (software DSP). Last in the chain, so
                            // a multichannel source is downmixed to stereo first and every filter
                            // sees the same two channels the user's headphones receive.
                            HeadphoneGraphicEqProcessor { headphoneGraphicEqController.settings() }
                        )
                    )
                    .build()
                return UsbAudioSink(
                    delegate = delegate,
                    context = context,
                    config = UsbAudioSinkConfig(
                        directEnabled = allowDirect,
                        forceRouteToSpeaker = true,
                        signalSource = directSignalController,
                    ),
                ).also { builtUsbSink = it }
            }

            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
            }

            override fun buildTextRenderers(
                context: Context,
                eventListener: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
            }

            override fun buildCameraMotionRenderers(
                context: Context,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
            }
        }.setEnableAudioFloatOutput(false)
         .setMediaCodecSelector(mediaCodecSelector)
         .setEnableDecoderFallback(true)
         .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val audioAttributes = Media3AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
            
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val extractorsFactory = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            .setFlacExtractorFlags(FlacExtractor.FLAG_DISABLE_ID3_METADATA)

        val loadControl = UsbAudioSink.wrapLoadControl(
            buildAdaptiveLoadControl(),
            // READY follows the native engine, not Media3's buffer, once the engine exists (AUD-114).
            isEngineReady = { builtUsbSink?.isNativeEngineReadyForCurrentItem == true },
            // Media3 may load the next item in the engine's last seconds (AUD-130).
            isEnginePreloadWindow = { builtUsbSink?.isNativeEngineNearEnd == true },
        ) {
            builtUsbSink?.isNativeEngineActive == true
        }

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .build().apply {
            sharedAudioSessionIdOrNull()?.let { setAudioSessionId(it) }
            setAudioAttributes(audioAttributes, false)
            // Gapless support is required, not optional: on a HAL that only advertises plain
            // offload, the data written ahead for the next item is dropped at the automatic
            // transition and the track starts seconds in. Devices without gapless offload fall
            // back to the regular PCM path instead.
            val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(
                    if (audioOffloadEnabled) {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    } else {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                    }
                )
                .setIsGaplessSupportRequired(true)
                .build()
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setAudioOffloadPreferences(offloadPreferences)
                .build()
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            playWhenReady = false
        }
        builtUsbSink?.let { sink -> registerUsbSink(player, sink) }
        return player
    }

    private fun registerUsbSink(player: ExoPlayer, sink: UsbAudioSink) {
        playerUsbSinks[player] = sink
        sink.attachToPlayer(player)
        sink.setDiagnosticsListener { diagnostics ->
            mainHandler.post {
                if (::playerA.isInitialized && player === playerA) {
                    publishUsbDiagnostics(diagnostics)
                    applyAudioOffloadPreference(
                        player = player,
                        enabled = audioOffloadEnabled &&
                            diagnostics.state != UsbDirectSinkState.USB_DIRECT_ACTIVE,
                    )
                }
            }
        }
    }

    private fun releasePlayer(player: ExoPlayer) {
        playerUsbSinks.remove(player)?.let { sink ->
            sink.setDiagnosticsListener(null)
            sink.detachFromPlayer()
        }
        player.release()
    }

    private fun applyAudioOffloadPreference(player: ExoPlayer, enabled: Boolean) {
        val preferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                if (enabled) {
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                } else {
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                }
            )
            .setIsGaplessSupportRequired(true)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(preferences)
            .build()
    }

    private fun getOrCreateAuxiliaryPlayer(): ExoPlayer {
        playerB?.let { return it }
        // The auxiliary player is never allowed to reserve Decent's exclusive USB backend.
        // Crossfade itself makes the master route Android, and this is the structural backstop.
        return buildPlayer(allowDirect = false).also { player ->
            player.setWakeMode(currentWakeMode)
            playerB = player
        }
    }

    fun setPauseAtEndOfMediaItems(shouldPause: Boolean) {
        if (::playerA.isInitialized) {
            playerA.pauseAtEndOfMediaItems = shouldPause
        }
    }

    fun getNextTransitionTarget(currentMediaItem: MediaItem, repeatMode: Int): TransitionTarget? {
        val snapshot = ensureQueueSnapshot()
        if (snapshot.isEmpty()) return null

        val currentAbsoluteIndex = resolveCurrentAbsoluteIndex(currentMediaItem, snapshot)
        if (currentAbsoluteIndex == C.INDEX_UNSET) return null

        val targetIndex = when {
            repeatMode == Player.REPEAT_MODE_ONE -> currentAbsoluteIndex
            // B17-H1: with shuffle on, the master holds the whole queue in its own order and "next" is what its shuffle
            // order says (the snapshot is that same base order). A play-ordered window (shuffle on, queue windowed) is
            // already in play order, so the plain "next index" below is right for it.
            !activePlayerUsesWindowedQueue && playerA.shuffleModeEnabled ->
                playerA.currentTimeline.getNextWindowIndex(
                    playerA.currentMediaItemIndex, Player.REPEAT_MODE_OFF, true,
                ).takeIf { it != C.INDEX_UNSET } ?: return null
            else -> currentAbsoluteIndex + 1
        }

        val targetItem = snapshot.getOrNull(targetIndex) ?: return null
        return TransitionTarget(
            mediaItem = targetItem,
            absoluteIndex = targetIndex,
            queueSize = snapshot.size
        )
    }

    fun onUsbAudioDeviceReady() {
        mainHandler.post {
            // A newly granted permission is a new fact: the latched failure may not hold any more.
            directFailureLatch.clear()
            val retryFailedDirect = desiredBackend == AudioBackend.USB_DIRECT &&
                _audioRouteState.value.transition == AudioRouteTransition.DIRECT_FAILED_FALLBACK
            refreshUsbAudioDiagnostics()
            if (retryFailedDirect &&
                ::playerA.isInitialized &&
                desiredBackend == AudioBackend.USB_DIRECT &&
                !_audioRouteState.value.isDirectActive &&
                !playerA.playWhenReady &&
                !playerA.isPlaying
            ) {
                rebuildPlayersPreservingMasterState("USB Audio device ready; attempting automatic Direct route")
            }
        }
    }

    suspend fun prepareNext(target: TransitionTarget, startPositionMs: Long = 0L) {
        prepareNext(target.mediaItem, target.absoluteIndex, startPositionMs)
    }

    suspend fun prepareNext(mediaItem: MediaItem, startPositionMs: Long = 0L) {
        val preferredIndex = findMediaItemIndex(
            items = ensureQueueSnapshot(),
            mediaId = mediaItem.mediaId,
            preferAfterExclusive = resolveCurrentAbsoluteIndex(playerA.currentMediaItem ?: mediaItem, queueSnapshot)
        )
        prepareNext(mediaItem, preferredIndex, startPositionMs)
    }

    private suspend fun prepareNext(mediaItem: MediaItem, preferredAbsoluteIndex: Int, startPositionMs: Long = 0L) {
        try {
            val snapshot = ensureQueueSnapshot()
            val currentAbsoluteIndex = resolveCurrentAbsoluteIndex(playerA.currentMediaItem ?: mediaItem, snapshot)
            val targetIndex = when {
                preferredAbsoluteIndex in snapshot.indices &&
                    snapshot[preferredAbsoluteIndex].mediaId == mediaItem.mediaId -> preferredAbsoluteIndex
                else -> findMediaItemIndex(snapshot, mediaItem.mediaId, currentAbsoluteIndex)
            }
            val resolvedItem = mediaItem
            val auxiliaryPlayer = getOrCreateAuxiliaryPlayer()

            auxiliaryPlayer.stop()
            auxiliaryPlayer.clearMediaItems()

            if (targetIndex != C.INDEX_UNSET && snapshot.isNotEmpty()) {
                val count = snapshot.size
                // B17-H1: the master's play order, so the auxiliary player continues from the song the master had next.
                // A snapshot that is already a play-ordered window carries it in its own order (identity).
                val shuffleOn = playerA.shuffleModeEnabled
                val masterPlayOrder = if (shuffleOn && !activePlayerUsesWindowedQueue) {
                    PlayOrder.of(playerA.currentTimeline, shuffleEnabled = true)
                        .takeIf { PlayOrder.isPermutation(it, count) }
                } else {
                    null
                }
                val plan = AuxiliaryQueuePlan.build(
                    count = count,
                    targetIndex = targetIndex,
                    playOrder = masterPlayOrder,
                    maxItems = MAX_AUXILIARY_TIMELINE_ITEMS,
                    sourceIsPlayOrder = shuffleOn && activePlayerUsesWindowedQueue,
                )
                val windowItems = ArrayList<MediaItem>(plan.sourceIndices.size)
                for (i in plan.sourceIndices) {
                    windowItems.add(if (i == targetIndex) resolvedItem else snapshot[i])
                }
                preparedWindowStartIndex = plan.windowStart
                preparedPlayerUsesWindowedQueue = plan.windowed
                preparedWindowedPlayOrder = plan.windowedPlayOrder
                preparedWindowedBaseQueue = if (plan.windowedPlayOrder != null) snapshot else null
                auxiliaryPlayer.setMediaItems(windowItems, plan.targetIndex, startPositionMs)
                // After setMediaItems, which gives the player a fresh random order of its own.
                plan.auxOrder?.takeIf { PlayOrder.isPermutation(it, windowItems.size) }?.let {
                    auxiliaryPlayer.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(it, System.nanoTime()))
                }
            } else {
                resetPreparedWindowState()
                auxiliaryPlayer.setMediaItem(resolvedItem)
                auxiliaryPlayer.seekTo(startPositionMs)
            }

            auxiliaryPlayer.prepare()
            auxiliaryPlayer.volume = 0f
            auxiliaryPlayer.pause()
        } catch (e: Exception) {
            resetPreparedWindowState()
            Timber.tag("TransitionDebug").e(e, "Failed to prepare next player")
        }
    }

    fun cancelNext() {
        val shouldPublishMasterPlayer = transitionRunning
        transitionEndPause = false
        transitionBarrier.invalidate()
        transitionJob?.cancel()
        transitionRunning = false
        // A fade that is cancelled never tells the finished listeners: a shuffle request that waited for it is applied now.
        if (shuffleGate.hasPending) mainHandler.post { shuffleGate.settle() }
        resetPreparedWindowState()
        playerB?.takeIf { it.mediaItemCount > 0 }?.let { auxiliaryPlayer ->
            try {
                auxiliaryPlayer.stop()
                auxiliaryPlayer.clearMediaItems()
            } catch (e: Exception) { }
        }
        if (::playerA.isInitialized) {
            TransitionVolumePolicy.volumeAfterCancel(
                transitionWasRunning = shouldPublishMasterPlayer,
                composedGain = _gainModel.value.combinedGain,
            )?.let { playerA.volume = it }
            if (shouldPublishMasterPlayer) {
                onPlayerSwappedListeners.forEach { it(playerA) }
            }
        }
        incomingTrackEffectiveVolume = null
        setPauseAtEndOfMediaItems(false)
    }

    private fun gainWithoutReplayGain(): Float =
        _gainModel.value.let { (it.userDigitalGain * it.profileCompensation).coerceIn(0f, 1f) }

    /**
     * B27-A1 (R-01): what pauses, loses the output or replaces the players calls. A crossfade that has been asked for but has
     * not started to play (it is waiting for the incoming player, or for its turn) is dropped with the same clean-up as a
     * cancel: it must not issue a `play()` after the pause that ended it. A crossfade that is already fading is left alone.
     * Safe to call at any time and from inside a player callback (main thread).
     */
    private fun invalidatePendingTransition(why: String) {
        transitionEndPause = false
        transitionBarrier.invalidate()
        if (transitionRunning && !transitionFadeStarted) {
            Timber.tag("TransitionDebug").w("Pending crossfade dropped before it started: %s", why)
            cancelNext()
        }
    }

    /** The players are being replaced: whatever a transition holds is the old ones. Any phase; the players are cleaned by their release. */
    private fun abandonTransitionForRebuild() {
        transitionEndPause = false
        transitionBarrier.invalidate()
        transitionJob?.cancel()
        transitionRunning = false
        transitionFadeStarted = false
        resetPreparedWindowState()
    }

    fun performTransition(settings: TransitionSettings) {
        transitionJob?.cancel()
        transitionRunning = true
        transitionFadeStarted = false
        transitionEndPause = false
        val ticket = transitionBarrier.begin()
        transitionJob = scope.launch {
            try {
                performOverlapTransition(settings, ticket)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Timber.tag("TransitionDebug").e(e, "Error performing transition")
                }
                // The composed gain, not unity: see TransitionVolumePolicy.
                playerA.volume = _gainModel.value.combinedGain
                setPauseAtEndOfMediaItems(false)
                playerB?.stop()
            } finally {
                transitionRunning = false
                transitionFadeStarted = false
                transitionEndPause = false
                lastTransitionFinishedAtMs = SystemClock.elapsedRealtime()
                // A copy: a listener may add or remove listeners (the shuffle gate takes its own off).
                onTransitionFinishedListeners.toList().forEach { it() }
            }
        }
    }

    private suspend fun performOverlapTransition(settings: TransitionSettings, ticket: Long) {
        if (shouldSuppressOverlapTransitions()) {
            Timber.tag("UsbDirectAudio").d(
                "Skipping overlap transition while the exclusive USB route is reserved"
            )
            cancelNext()
            setPauseAtEndOfMediaItems(false)
            return
        }

        val auxiliaryPlayer = playerB
        if (auxiliaryPlayer == null || auxiliaryPlayer.mediaItemCount == 0) {
            playerA.volume = _gainModel.value.combinedGain
            continueAfterTransitionEndPause(ticket, playerA)
            return
        }

        val outgoingPlayer = playerA
        // B27-A1 (R-01): nothing here may start the players unless playback is still asked for. Checked before the wait, after
        // it, and once more right before the first play(), with nothing that can suspend in between.
        if (!overlapMayStart(ticket, outgoingPlayer, auxiliaryPlayer, "before waiting for the incoming player")) return

        if (auxiliaryPlayer.playbackState == Player.STATE_IDLE) auxiliaryPlayer.prepare()
        if (auxiliaryPlayer.playbackState == Player.STATE_BUFFERING) {
            if (!awaitPlayerReady(auxiliaryPlayer, 3000L)) {
                // Not a pause: the incoming player never became ready, or the wait was cut short by one (then it is the barrier's).
                if (!overlapMayStart(ticket, outgoingPlayer, auxiliaryPlayer, "incoming player not ready")) return
                playerA.volume = _gainModel.value.combinedGain
                continueAfterTransitionEndPause(ticket, outgoingPlayer)
                return
            }
        }
        if (!overlapMayStart(ticket, outgoingPlayer, auxiliaryPlayer, "after waiting for the incoming player")) return

        val outgoingStartVolume = playerA.volume.coerceIn(0f, 1f)
        auxiliaryPlayer.volume = 0f
        transitionFadeStarted = true
        if (!transitionEndPause && !playerA.isPlaying && playerA.playbackState == Player.STATE_READY) playerA.play()
        auxiliaryPlayer.playWhenReady = true
        auxiliaryPlayer.play()

        val incomingPlayer = auxiliaryPlayer

        incomingPlayer.repeatMode = outgoingPlayer.repeatMode
        incomingPlayer.shuffleModeEnabled = outgoingPlayer.shuffleModeEnabled
        outgoingPlayer.pauseAtEndOfMediaItems = true
        incomingPlayer.pauseAtEndOfMediaItems = false
        onTransitionDisplayPlayerListeners.forEach { it(incomingPlayer) }

        val duration = settings.durationMs.toLong().coerceAtLeast(500L)
        val stepMs = 32L
        val startedAtMs = SystemClock.elapsedRealtime()

        while (true) {
            val elapsed = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtMost(duration)
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            // Without a precomputed ReplayGain target, fade to the user's own level (with the
            // profile compensation), not unity.
            val incomingTarget = incomingTrackEffectiveVolume ?: gainWithoutReplayGain()
            // Equal power (AUD-122), each side scaled by its own composed gain.
            val gains = crossfadeGains(
                progress, settings.curveIn, settings.curveOut, incomingTarget, outgoingStartVolume,
            )
            incomingPlayer.volume = gains.incoming
            outgoingPlayer.volume = gains.outgoing

            if (elapsed >= duration) break
            delay(stepMs)
        }

        outgoingPlayer.volume = 0f
        incomingPlayer.volume = incomingTrackEffectiveVolume ?: gainWithoutReplayGain()
        incomingTrackEffectiveVolume = null

        removeMasterPlayerListeners(outgoingPlayer)

        playerA = incomingPlayer
        playerB = outgoingPlayer
        refreshUsbAudioDiagnostics()
        activeWindowStartIndex = preparedWindowStartIndex
        activePlayerUsesWindowedQueue = preparedPlayerUsesWindowedQueue
        // B17-H1: a window cut from the play order makes the play-ordered list the snapshot; what it was cut from is kept.
        val windowedOrder = preparedWindowedPlayOrder
        val windowedBase = preparedWindowedBaseQueue
        if (windowedOrder != null && windowedBase != null) {
            windowedBaseQueue = windowedBase
            windowedPlayOrder = windowedOrder
            queueSnapshot = List(windowedOrder.size) { windowedBase[windowedOrder[it]] }
        } else if (!activePlayerUsesWindowedQueue) {
            windowedBaseQueue = null
            windowedPlayOrder = null
        }
        resetPreparedWindowState()

        playerA.pauseAtEndOfMediaItems = false
        playerB?.pauseAtEndOfMediaItems = false
        addMasterPlayerListeners(playerA)
        if (playerA.playWhenReady) requestAudioFocus()

        onPlayerSwappedListeners.forEach { it(playerA) }
        _activeAudioSessionId.value = playerA.audioSessionId

        playerB?.pause()
        playerB?.stop()
        playerB?.clearMediaItems()

        setPauseAtEndOfMediaItems(false)
    }

    /**
     * B27-A1 (R-01): whether the crossfade holding [ticket] may go on to start its players ([TransitionBarrier.decide]), read
     * from the engine as it is **now**. When it may not, the crossfade is dropped with a cancel's clean-up and the song plays on
     * (or stays paused) as the pause that stopped it left it; the next song schedules its own.
     */
    private fun overlapMayStart(ticket: Long, outgoing: ExoPlayer, incoming: ExoPlayer, where: String): Boolean {
        val verdict = TransitionBarrier.decide(
            ticketCurrent = transitionBarrier.isCurrent(ticket),
            playersUnchanged = ::playerA.isInitialized && !isReleased && playerA === outgoing && playerB === incoming,
            incomingHasItems = incoming.mediaItemCount > 0,
            masterPlayWhenReady = ::playerA.isInitialized && (playerA.playWhenReady || transitionEndPause),
        )
        if (verdict == TransitionBarrier.Start.PLAY) return true
        Timber.tag("TransitionDebug").w("Crossfade not started ($where): %s", verdict)
        traceRoute("TRANSITION_DROPPED", "verdict=$verdict where=\"$where\" ${playerFacts()}")
        if (verdict == TransitionBarrier.Start.NOTHING_TO_FADE_TO) continueAfterTransitionEndPause(ticket, outgoing)
        // A stale ticket was already cancelled by whoever made it stale; the others are cleaned up here.
        if (verdict != TransitionBarrier.Start.STALE && verdict != TransitionBarrier.Start.PLAYERS_CHANGED) cancelNext()
        return false
    }

    /** A session pause is intent even if Media3 was already paused at the item's end and emits no callback. */
    fun notifyExternalPauseInitiated() {
        isFocusLossPause = false
        pausedByDuck = false
        invalidatePendingTransition("explicit pause command")
    }

    /** Release the crossfade's stop-at-end and let the master's queue advance when overlap is unavailable. */
    private fun continueAfterTransitionEndPause(ticket: Long, outgoing: ExoPlayer) {
        setPauseAtEndOfMediaItems(false)
        if (transitionBarrier.isCurrent(ticket) && !isReleased && playerA === outgoing && transitionEndPause) {
            transitionEndPause = false
            outgoing.play()
        }
    }

    private fun ensureQueueSnapshot(): List<MediaItem> {
        if (queueSnapshot.isEmpty() ||
            (!activePlayerUsesWindowedQueue && queueSnapshot.size != playerA.mediaItemCount)
        ) {
            refreshQueueSnapshotFromMaster(windowStartIndex = 0, usesWindowedQueue = false)
        }
        return queueSnapshot
    }

    private fun refreshQueueSnapshotFromMaster(windowStartIndex: Int, usesWindowedQueue: Boolean) {
        if (!::playerA.isInitialized) return

        val count = playerA.mediaItemCount
        if (count <= 0) {
            queueSnapshot = emptyList()
            activeWindowStartIndex = 0
            activePlayerUsesWindowedQueue = false
            windowedBaseQueue = null
            windowedPlayOrder = null
            return
        }

        val items = ArrayList<MediaItem>(count)
        for (i in 0 until count) {
            items.add(playerA.getMediaItemAt(i))
        }

        queueSnapshot = items
        activeWindowStartIndex = windowStartIndex
        activePlayerUsesWindowedQueue = usesWindowedQueue
        if (!usesWindowedQueue) {
            windowedBaseQueue = null
            windowedPlayOrder = null
        }
    }

    private fun resolveCurrentAbsoluteIndex(mediaItem: MediaItem, snapshot: List<MediaItem>): Int {
        if (snapshot.isEmpty()) return C.INDEX_UNSET

        val playerIndex = playerA.currentMediaItemIndex
        if (activePlayerUsesWindowedQueue) {
            val absoluteIndex = activeWindowStartIndex + playerIndex
            if (absoluteIndex in snapshot.indices &&
                snapshot[absoluteIndex].mediaId == mediaItem.mediaId
            ) {
                return absoluteIndex
            }
        } else if (playerIndex in snapshot.indices &&
            snapshot[playerIndex].mediaId == mediaItem.mediaId
        ) {
            return playerIndex
        }

        return findMediaItemIndex(snapshot, mediaItem.mediaId, preferAfterExclusive = C.INDEX_UNSET)
    }

    private fun findMediaItemIndex(
        items: List<MediaItem>,
        mediaId: String,
        preferAfterExclusive: Int
    ): Int {
        var fallback = C.INDEX_UNSET
        for (i in items.indices) {
            if (items[i].mediaId == mediaId) {
                if (preferAfterExclusive != C.INDEX_UNSET && i > preferAfterExclusive) return i
                if (fallback == C.INDEX_UNSET) fallback = i
            }
        }
        return fallback
    }

    private fun resetPreparedWindowState() {
        preparedWindowStartIndex = 0
        preparedPlayerUsesWindowedQueue = false
        preparedWindowedBaseQueue = null
        preparedWindowedPlayOrder = null
    }

    private suspend fun awaitPlayerReady(player: ExoPlayer, timeoutMs: Long): Boolean {
        if (player.playbackState == Player.STATE_READY) return true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState != Player.STATE_BUFFERING) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(playbackState == Player.STATE_READY)
                        }
                    }
                }
                player.addListener(listener)
                cont.invokeOnCancellation { player.removeListener(listener) }
            }
        } ?: false
    }

    fun release() {
        transitionEndPause = false
        transitionBarrier.invalidate()
        transitionJob?.cancel()
        cancelAudioOffloadFallback()
        scope.coroutineContext[Job]?.cancel()
        abandonAudioFocus()
        if (::playerA.isInitialized) {
            removeMasterPlayerListeners(playerA)
            onPlayerAboutToBeReleasedListener?.invoke(playerA)
            releasePlayer(playerA)
        }
        playerB?.let(::releasePlayer)
        playerB = null
        playerUsbSinks.clear()
        if (audioOutputsCallbackRegistered) {
            context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(audioOutputsCallback)
            audioOutputsCallbackRegistered = false
        }
        mainHandler.removeCallbacks(androidUsbRouteTimeout)
        mainHandler.removeCallbacks(usbRouteProbe)
        isReleased = true
    }
}
