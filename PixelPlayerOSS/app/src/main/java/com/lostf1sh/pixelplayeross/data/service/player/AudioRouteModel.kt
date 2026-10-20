package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.BitPerfectTruth
import com.decent.usbaudio.media3.DirectSignalMode

/** The two playback backends PixelPlayer can actually own. */
enum class AudioBackend { ANDROID_AUDIO, USB_DIRECT }

/**
 * Why the authoritative router selected its current desired route.
 *
 * The values are separated by *what kind of blocker* they name, and the separation is load
 * bearing. A transport blocker says the USB path cannot be used at all; a processing blocker says
 * the path could be used but one of its two signal modes cannot carry what was asked for. Merging
 * them — which is what the previous `*_REQUIRES_ANDROID` values did — is how a graphic equalizer
 * came to be reported in the same breath as a missing DAC, and how Direct became unreachable for
 * every listener who had ever touched an EQ slider.
 */
enum class AudioRouteReason {
    STARTING,

    // ── Transport blockers: USB Direct is not available, whatever the signal is ──
    NO_USB_DAC,
    USB_PERMISSION_REQUIRED,
    SOURCE_NOT_DIRECT_COMPATIBLE,
    SAFETY_TRANSITION_DEFERRED,

    // ── Signal blockers: USB Direct is available, but the requested processing has no
    //    implementation on the Direct path in this pass, so the Android path must carry it. ──
    CROSSFADE_REQUIRES_ANDROID,
    ANDROID_EFFECT_REQUIRES_ANDROID,
    SPEED_REQUIRES_ANDROID,

    // ── Direct, named by its signal mode ──
    /** Direct transport, no application PCM modification. */
    DIRECT_PRISTINE,
    /** Direct transport, application DSP active. */
    DIRECT_PROCESSED,

    DIRECT_ACTIVE,
    DIRECT_FAILED_FALLBACK,

    /** A Maintenance diagnostic override, not a listening choice. */
    MAINTENANCE_FORCE_ANDROID,
    MAINTENANCE_DIRECT_DISABLED,
}

enum class AudioRouteTransition {
    ANDROID_ACTIVE,
    DIRECT_ELIGIBLE,
    DIRECT_NEGOTIATING,
    DIRECT_ACTIVE,
    DIRECT_FAILED_FALLBACK,
}

/**
 * Listening choices, split by whether the Direct path can carry them.
 *
 * These are statements about the *signal*, and nothing here is a statement about whether a DAC is
 * attached. That question is asked separately, by [AudioRoutePolicy], against the transport facts —
 * which is what stops a processing reason from being reported when the real blocker is that no
 * DAC is plugged in.
 */
data class AudioProcessingRequirements(
    /** Graphic EQ with at least one non-zero band. A flat enabled EQ is not a requirement. */
    val graphicEq: Boolean = false,
    val replayGain: Boolean = false,
    /** A digital gain other than unity is engaged. */
    val digitalVolume: Boolean = false,
    /** Speed other than 1.0×. Direct has no time-stretch in this pass; see the policy. */
    val nonDefaultSpeed: Boolean = false,
    val crossfade: Boolean = false,
    val androidSessionEffect: Boolean = false,
) {
    /**
     * Whether any requirement needs the application to modify PCM.
     *
     * This is the question that decides pristine versus processed, and it is deliberately narrower
     * than "any requirement at all": crossfade and Android session effects are not PCM-domain
     * requirements, they are reasons the Direct path cannot be used at all.
     */
    val requiresPcmProcessing: Boolean
        get() = graphicEq || replayGain || digitalVolume
}

data class AudioRouteFacts(
    val usbDacPresent: Boolean,
    val usbPermissionGranted: Boolean,
    val sourceDirectCompatible: Boolean = true,
    val processing: AudioProcessingRequirements = AudioProcessingRequirements(),
    /** False while audible Android playback makes a fixed-output transition unsafe. */
    val directTransitionAllowed: Boolean = true,
    /**
     * A Maintenance diagnostic override is pinning the Android path. Reported with its own reason
     * so the diagnostic surface can never mistake a deliberately forced route for a real blocker.
     */
    val maintenanceForceAndroid: Boolean = false,
    /** A Maintenance diagnostic override is holding the automatic Direct transition off. */
    val maintenanceDirectDisabled: Boolean = false,
    /**
     * A Direct claim already failed for this attachment and source rate ([DirectFailureLatch]).
     * A transport fact: it must win over the safety deferral, or every track change re-arms the
     * handoff against a claim that will fail again (AUD-011).
     */
    val directFailedForSource: Boolean = false,
    /**
     * The platform currently offers a USB audio output. False while the app holds the DAC, and for
     * a moment after it hands the DAC back; also false when `snd-usb-audio` never rebinds (AUD-003).
     */
    val androidUsbRouteAvailable: Boolean = true,
)

/**
 * The route the policy selected, in two parts that are never allowed to stand for each other.
 *
 * [backend] answers "which transport"; [signalMode] answers "what is being done to the signal".
 * A caller that wants to know whether the stream is unmodified must read [signalMode], not
 * [backend] — `backend == USB_DIRECT` has never implied bit-perfect and no longer pretends to.
 */
data class AudioRouteDecision(
    val backend: AudioBackend,
    val reason: AudioRouteReason,
    /**
     * Only meaningful for [AudioBackend.USB_DIRECT]. Reported as [DirectSignalMode.PRISTINE] on the
     * Android path, where the application's sink is not the thing carrying the samples at all.
     */
    val signalMode: DirectSignalMode = DirectSignalMode.PRISTINE,
    /**
     * The Android path was chosen with the DAC attached but no Android USB output exists, so the
     * platform would play to the speaker or a dead route. The engine holds playback instead
     * ([AndroidUsbRouteGuard]) — never the speaker without the listener choosing it (AUD-003).
     */
    val androidOutputBlocked: Boolean = false,
)

/**
 * Pure policy: user intent selects the signal mode, transport capability selects the backend, and
 * the two questions are answered in that order but never merged.
 *
 * ## Why the order is transport-last
 *
 * Every processing requirement is now negotiated *against* the transport rather than *before* it.
 * A listener with the EQ on and a DAC attached gets Direct with DSP; the same listener with no DAC
 * gets Android, reported as `NO_USB_DAC` — because that is the fact that actually decided it. The
 * previous ordering returned `EQ_REQUIRES_ANDROID` in both cases, which named a blocker that was
 * not the operative one and made the diagnostic surface misleading exactly when it mattered.
 */
object AudioRoutePolicy {
    fun decide(facts: AudioRouteFacts): AudioRouteDecision = decide(facts) { facts.androidUsbRouteAvailable }

    /**
     * The same decision, with the Android-route fact **read only when the decision needs it** (B23-P4). It is consulted for one
     * thing: an Android decision with a DAC attached, to say the platform has no USB output to play to. A Direct decision, the
     * owner's daily route, never asks, so the engine no longer reads `/proc/asound` and the platform's output list on the main
     * thread for every song just to throw the answer away.
     */
    fun decide(facts: AudioRouteFacts, androidUsbRouteAvailable: () -> Boolean): AudioRouteDecision =
        decideRoute(facts).let { decision ->
            if (decision.backend == AudioBackend.ANDROID_AUDIO && facts.usbDacPresent &&
                !androidUsbRouteAvailable()
            ) decision.copy(androidOutputBlocked = true) else decision
        }

    private fun decideRoute(facts: AudioRouteFacts): AudioRouteDecision = when {
        // Requirements the Direct path genuinely cannot carry in this pass. These stay ahead of the
        // transport gates because they are true regardless of what is plugged in: with crossfade on
        // the answer is Android whether or not a DAC is present, and saying so is honest.
        facts.processing.crossfade -> android(AudioRouteReason.CROSSFADE_REQUIRES_ANDROID)
        facts.processing.androidSessionEffect -> android(AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID)
        facts.processing.nonDefaultSpeed -> android(AudioRouteReason.SPEED_REQUIRES_ANDROID)
        // Maintenance overrides sit after the listening choices so that a real blocker is still
        // named honestly, and before the capability gates so a forced route cannot be reported as
        // a missing DAC or a refused permission.
        facts.maintenanceForceAndroid -> android(AudioRouteReason.MAINTENANCE_FORCE_ANDROID)
        facts.maintenanceDirectDisabled -> android(AudioRouteReason.MAINTENANCE_DIRECT_DISABLED)
        !facts.usbDacPresent -> android(AudioRouteReason.NO_USB_DAC)
        !facts.usbPermissionGranted -> android(AudioRouteReason.USB_PERMISSION_REQUIRED)
        !facts.sourceDirectCompatible -> android(AudioRouteReason.SOURCE_NOT_DIRECT_COMPATIBLE)
        facts.directFailedForSource -> android(AudioRouteReason.DIRECT_FAILED_FALLBACK)
        !facts.directTransitionAllowed -> android(AudioRouteReason.SAFETY_TRANSITION_DEFERRED)
        facts.processing.requiresPcmProcessing -> AudioRouteDecision(
            backend = AudioBackend.USB_DIRECT,
            reason = AudioRouteReason.DIRECT_PROCESSED,
            signalMode = DirectSignalMode.PROCESSED,
        )
        else -> AudioRouteDecision(
            backend = AudioBackend.USB_DIRECT,
            reason = AudioRouteReason.DIRECT_PRISTINE,
            signalMode = DirectSignalMode.PRISTINE,
        )
    }

    private fun android(reason: AudioRouteReason) =
        AudioRouteDecision(AudioBackend.ANDROID_AUDIO, reason)
}

/** Desired and observed route are deliberately separate: eligible is not active. */
data class AudioRouteState(
    val desiredBackend: AudioBackend = AudioBackend.ANDROID_AUDIO,
    val actualBackend: AudioBackend = AudioBackend.ANDROID_AUDIO,
    val transition: AudioRouteTransition = AudioRouteTransition.ANDROID_ACTIVE,
    val reason: AudioRouteReason = AudioRouteReason.STARTING,
    val deviceName: String? = null,
    /**
     * Whether the router found a USB audio DAC when it last decided (B16-G4); null before it has looked. Published with the
     * route, so everything that reads "is the Purity lock in force" (the crossfade scheduler, the speed, the screens)
     * sees the same fact the route was decided from.
     */
    val usbDacPresent: Boolean? = null,
    val permissionGranted: Boolean? = null,
    val confirmedOutputRateHz: Int? = null,
    val directError: String? = null,
    /**
     * The signal mode the route is running in. Read from the sink once a Direct stream is actually
     * up, and from the policy before that — because "what we asked for" and "what the transport is
     * doing" are different claims and only the second one belongs on a truth surface.
     */
    val signalMode: DirectSignalMode = DirectSignalMode.PRISTINE,
    /** The sink's own verdict on whether this stream is unmodified, with its reason. */
    val bitPerfect: BitPerfectTruth = BitPerfectTruth.PRISTINE_UNVERIFIED,
) {
    val isDirectActive: Boolean
        get() = actualBackend == AudioBackend.USB_DIRECT &&
            transition == AudioRouteTransition.DIRECT_ACTIVE

    /** Direct transport carrying an application-modified signal. */
    val isProcessedDirect: Boolean
        get() = isDirectActive && signalMode == DirectSignalMode.PROCESSED
}

/** A failed exclusive claim is observed as Android fallback, never as Direct-active optimism. */
internal fun directFailureFallback(
    previous: AudioRouteState,
    desiredBackend: AudioBackend,
    deviceName: String?,
    permissionGranted: Boolean?,
    error: String?,
): AudioRouteState = previous.copy(
    desiredBackend = desiredBackend,
    actualBackend = AudioBackend.ANDROID_AUDIO,
    transition = AudioRouteTransition.DIRECT_FAILED_FALLBACK,
    reason = AudioRouteReason.DIRECT_FAILED_FALLBACK,
    deviceName = deviceName,
    permissionGranted = permissionGranted,
    confirmedOutputRateHz = null,
    directError = error,
    signalMode = DirectSignalMode.PRISTINE,
    bitPerfect = BitPerfectTruth.NOT_DIRECT,
)

data class ReplayGainRuntimeState(
    val requested: Boolean = false,
    val useAlbumGain: Boolean = false,
    val applied: Boolean = false,
    val mediaId: String? = null,
    val multiplier: Float? = null,
)
