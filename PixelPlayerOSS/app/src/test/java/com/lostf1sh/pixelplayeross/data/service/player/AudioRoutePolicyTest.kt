package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AudioRoutePolicyTest {
    private val transparentUsb = AudioRouteFacts(
        usbDacPresent = true,
        usbPermissionGranted = true,
    )

    @Test fun `compatible DAC and transparent listening state select Direct`() {
        assertThat(AudioRoutePolicy.decide(transparentUsb).backend).isEqualTo(AudioBackend.USB_DIRECT)
    }

    @Test fun `disconnect falls back and reattach reevaluates to Direct`() {
        assertThat(AudioRoutePolicy.decide(transparentUsb.copy(usbDacPresent = false)).backend)
            .isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(AudioRoutePolicy.decide(transparentUsb).backend).isEqualTo(AudioBackend.USB_DIRECT)
    }

    @Test fun `clearing the last processing requirement returns the route to Direct`() {
        // The requirement no longer *blocks* Direct, it selects the processed signal mode — so the
        // assertion is about which mode, and about the route coming back to pristine once the
        // requirement is gone. Written this way rather than merely asserting "not Android" because
        // a policy that quietly kept processing switched on would still pass that weaker form.
        val processed = AudioRoutePolicy.decide(
            transparentUsb.copy(processing = AudioProcessingRequirements(graphicEq = true))
        )
        assertThat(processed.backend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(processed.signalMode).isEqualTo(DirectSignalMode.PROCESSED)

        val pristine = AudioRoutePolicy.decide(transparentUsb)
        assertThat(pristine.backend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(pristine.signalMode).isEqualTo(DirectSignalMode.PRISTINE)
    }

    @Test fun `no DAC selects Android`() = assertAndroid(transparentUsb.copy(usbDacPresent = false))

    @Test fun `missing permission selects Android`() =
        assertAndroid(transparentUsb.copy(usbPermissionGranted = false))

    @Test fun `unsupported source selects Android`() =
        assertAndroid(transparentUsb.copy(sourceDirectCompatible = false))

    // ── PCM-domain requirements now ride the Direct transport ───────────

    @Test fun `EQ request selects processed Direct`() = assertProcessedDirect(
        AudioProcessingRequirements(graphicEq = true),
    )

    @Test fun `ReplayGain request selects processed Direct`() = assertProcessedDirect(
        AudioProcessingRequirements(replayGain = true),
    )

    @Test fun `digital volume request selects processed Direct`() = assertProcessedDirect(
        AudioProcessingRequirements(digitalVolume = true),
    )

    @Test fun `every PCM requirement together still selects processed Direct`() =
        assertProcessedDirect(
            AudioProcessingRequirements(
                graphicEq = true,
                replayGain = true,
                digitalVolume = true,
            )
        )

    // ── Requirements the Direct path genuinely cannot carry yet ─────────

    @Test fun `non-default speed selects Android`() = assertProcessing(
        AudioProcessingRequirements(nonDefaultSpeed = true), AudioRouteReason.SPEED_REQUIRES_ANDROID,
    )

    @Test fun `crossfade selects Android`() = assertProcessing(
        AudioProcessingRequirements(crossfade = true), AudioRouteReason.CROSSFADE_REQUIRES_ANDROID,
    )

    @Test fun `Android session effect selects Android`() = assertProcessing(
        AudioProcessingRequirements(androidSessionEffect = true),
        AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID,
    )

    // ── Transport and signal are separate questions ─────────────────────

    @Test fun `a processing requirement without a DAC is reported as a transport blocker`() {
        // The requirement is real, but it is not what decided the route. Naming it here is how the
        // previous model made an equalizer look like the reason Direct was unavailable on a device
        // with nothing plugged in.
        val decision = AudioRoutePolicy.decide(
            transparentUsb.copy(
                usbDacPresent = false,
                processing = AudioProcessingRequirements(graphicEq = true),
            )
        )
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.NO_USB_DAC)
    }

    @Test fun `a refused permission outranks a processing requirement in the reported reason`() {
        val decision = AudioRoutePolicy.decide(
            transparentUsb.copy(
                usbPermissionGranted = false,
                processing = AudioProcessingRequirements(replayGain = true),
            )
        )
        assertThat(decision.reason).isEqualTo(AudioRouteReason.USB_PERMISSION_REQUIRED)
    }

    @Test fun `Direct on the Android path never claims a signal mode`() {
        // `signalMode` describes what the Direct transport is doing; on Android the application's
        // sink is not carrying the samples at all, so reporting PROCESSED there would be a claim
        // about a path that is not in use.
        val decision = AudioRoutePolicy.decide(transparentUsb.copy(usbDacPresent = false))
        assertThat(decision.signalMode).isEqualTo(DirectSignalMode.PRISTINE)
    }

    @Test fun `pristine Direct is not evidence of bit-perfect`() {
        // The whole point of the separation: choosing Direct says nothing about purity, and the
        // state a pristine route starts in must not assert that nothing was measured.
        val state = AudioRouteState(
            desiredBackend = AudioBackend.USB_DIRECT,
            actualBackend = AudioBackend.USB_DIRECT,
            transition = AudioRouteTransition.DIRECT_ACTIVE,
            signalMode = DirectSignalMode.PRISTINE,
        )
        assertThat(state.isDirectActive).isTrue()
        assertThat(state.bitPerfect.verdict)
            .isEqualTo(com.decent.usbaudio.media3.BitPerfectVerdict.UNKNOWN)
    }

    @Test fun `processed Direct is recognisable as such`() {
        val state = AudioRouteState(
            desiredBackend = AudioBackend.USB_DIRECT,
            actualBackend = AudioBackend.USB_DIRECT,
            transition = AudioRouteTransition.DIRECT_ACTIVE,
            signalMode = DirectSignalMode.PROCESSED,
        )
        assertThat(state.isDirectActive).isTrue()
        assertThat(state.isProcessedDirect).isTrue()
    }

    @Test fun `audible Android to fixed Direct transition is deferred`() {
        val decision = AudioRoutePolicy.decide(transparentUsb.copy(directTransitionAllowed = false))
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.SAFETY_TRANSITION_DEFERRED)
    }

    @Test fun `Direct negotiation failure records actual Android fallback and clears rate`() {
        val failed = directFailureFallback(
            previous = AudioRouteState(
                desiredBackend = AudioBackend.USB_DIRECT,
                actualBackend = AudioBackend.USB_DIRECT,
                transition = AudioRouteTransition.DIRECT_ACTIVE,
                confirmedOutputRateHz = 96_000,
                signalMode = DirectSignalMode.PROCESSED,
            ),
            desiredBackend = AudioBackend.USB_DIRECT,
            deviceName = "DS2",
            permissionGranted = true,
            error = "clock did not lock",
        )
        assertThat(failed.actualBackend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(failed.transition).isEqualTo(AudioRouteTransition.DIRECT_FAILED_FALLBACK)
        assertThat(failed.confirmedOutputRateHz).isNull()
        assertThat(failed.directError).isEqualTo("clock did not lock")
        // A fallback is not a Direct stream, so it cannot still be advertising a Direct signal mode.
        assertThat(failed.signalMode).isEqualTo(DirectSignalMode.PRISTINE)
    }

    private fun assertProcessedDirect(requirements: AudioProcessingRequirements) {
        val decision = AudioRoutePolicy.decide(transparentUsb.copy(processing = requirements))
        assertThat(decision.backend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.DIRECT_PROCESSED)
        assertThat(decision.signalMode).isEqualTo(DirectSignalMode.PROCESSED)
    }

    private fun assertProcessing(requirements: AudioProcessingRequirements, reason: AudioRouteReason) {
        val decision = AudioRoutePolicy.decide(transparentUsb.copy(processing = requirements))
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(decision.reason).isEqualTo(reason)
    }

    private fun assertAndroid(facts: AudioRouteFacts) {
        assertThat(AudioRoutePolicy.decide(facts).backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
    }
}
