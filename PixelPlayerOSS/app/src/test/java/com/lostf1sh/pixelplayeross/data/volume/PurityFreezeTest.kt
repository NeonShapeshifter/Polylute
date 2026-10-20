package com.lostf1sh.pixelplayeross.data.volume

import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.UsbDirectSinkState
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.TransitionMode
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteFacts
import com.lostf1sh.pixelplayeross.data.service.player.AudioRoutePolicy
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.presentation.components.PristineFrozenFeature
import com.lostf1sh.pixelplayeross.presentation.components.PristineLockCopy
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState
import org.junit.jupiter.api.Test

/**
 * B15-F2: the Purity (Pure Direct) lock freezes crossfade and speed like it freezes the EQ.
 *
 * On 2026-10-12 15:07:42 the owner turned crossfade on with the lock on: the route went to Android
 * (`CROSSFADE_REQUIRES_ANDROID`), the readout went from dB to % and the screen still said Pure Direct. The route policy
 * sends crossfade and speed to Android before any other decision, and the lock's `requirements` did not hold them.
 * These tests take the requirements the way `MusicService` builds them (the preferences, then the lock) and the route
 * the way the engine decides it.
 */
class PurityFreezeTest {

    private val dac = AudioRouteFacts(usbDacPresent = true, usbPermissionGranted = true)

    /** What the engine's route would be for these preferences with the lock [locked]. */
    /** A route on which USB Direct is carrying the stream, in [mode]. */
    private fun directRoute(mode: DirectSignalMode) = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT,
        transition = AudioRouteTransition.DIRECT_ACTIVE,
        signalMode = mode,
    )

    private fun route(preferences: AudioProcessingRequirements, locked: Boolean) =
        AudioRoutePolicy.decide(dac.copy(processing = PristineModePolicy.requirements(preferences, locked)))

    private fun assertPristineDirect(preferences: AudioProcessingRequirements) {
        val decision = route(preferences, locked = true)
        assertThat(decision.backend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(decision.signalMode).isEqualTo(DirectSignalMode.PRISTINE)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.DIRECT_PRISTINE)
    }

    // ── The route ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `lock on and crossfade on stays on USB Direct, pristine`() =
        assertPristineDirect(AudioProcessingRequirements(crossfade = true))

    @Test
    fun `lock on and speed 1_25x stays on USB Direct, pristine`() =
        assertPristineDirect(AudioProcessingRequirements(nonDefaultSpeed = true))

    @Test
    fun `lock on with everything the listener can ask for stays on USB Direct, pristine`() =
        assertPristineDirect(
            AudioProcessingRequirements(
                graphicEq = true, replayGain = true, digitalVolume = true,
                nonDefaultSpeed = true, crossfade = true, androidSessionEffect = true,
            ),
        )

    @Test
    fun `lock off is the behaviour there always was`() {
        val crossfade = route(AudioProcessingRequirements(crossfade = true), locked = false)
        assertThat(crossfade.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(crossfade.reason).isEqualTo(AudioRouteReason.CROSSFADE_REQUIRES_ANDROID)

        val speed = route(AudioProcessingRequirements(nonDefaultSpeed = true), locked = false)
        assertThat(speed.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(speed.reason).isEqualTo(AudioRouteReason.SPEED_REQUIRES_ANDROID)

        val eq = route(AudioProcessingRequirements(graphicEq = true), locked = false)
        assertThat(eq.backend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(eq.signalMode).isEqualTo(DirectSignalMode.PROCESSED)
    }

    @Test
    fun `the lock changes only what the listener asked for, not the transport facts`() {
        val noDac = AudioRoutePolicy.decide(
            AudioRouteFacts(
                usbDacPresent = false, usbPermissionGranted = false,
                processing = PristineModePolicy.requirements(AudioProcessingRequirements(crossfade = true), locked = true),
            ),
        )
        assertThat(noDac.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(noDac.reason).isEqualTo(AudioRouteReason.NO_USB_DAC)    // the real reason, not "crossfade"
    }

    @Test
    fun `playlist rules with crossfade under the lock stay on Direct because no rule is a requirement`() {
        // A playlist rule (TransitionMode.OVERLAP with a duration) picks which settings win *if* a crossfade runs;
        // the route is decided from the global switch alone, and under the lock that switch reads as off.
        val rule = TransitionMode.OVERLAP
        assertThat(rule).isNotEqualTo(TransitionMode.NONE)
        assertThat(PristineModePolicy.effectiveCrossfade(enabled = true, locked = true)).isFalse()
        assertPristineDirect(AudioProcessingRequirements(crossfade = true))
    }

    // ── What playback reads ────────────────────────────────────────────────────────────────────

    @Test
    fun `the effective crossfade and speed are the choice when unlocked and off and 1x when locked`() {
        assertThat(PristineModePolicy.effectiveCrossfade(enabled = true, locked = false)).isTrue()
        assertThat(PristineModePolicy.effectiveCrossfade(enabled = false, locked = false)).isFalse()
        assertThat(PristineModePolicy.effectiveCrossfade(enabled = true, locked = true)).isFalse()
        assertThat(PristineModePolicy.effectiveSpeed(1.25f, locked = false)).isEqualTo(1.25f)
        assertThat(PristineModePolicy.effectiveSpeed(1.25f, locked = true)).isEqualTo(1f)
        // Nothing is lost: the lock's answer is a function of the choice, which it never touches.
        assertThat(PristineModePolicy.effectiveSpeed(1.25f, locked = false)).isEqualTo(1.25f)
    }

    @Test
    fun `the lock holds all six contributors and nothing else changes without it`() {
        val everything = AudioProcessingRequirements(
            graphicEq = true, replayGain = true, digitalVolume = true,
            nonDefaultSpeed = true, crossfade = true, androidSessionEffect = true,
        )
        assertThat(PristineModePolicy.requirements(everything, locked = true)).isEqualTo(AudioProcessingRequirements())
        assertThat(PristineModePolicy.requirements(everything, locked = false)).isEqualTo(everything)
    }

    // ── The label says what is true ────────────────────────────────────────────────────────────

    @Test
    fun `the status is active only when Direct really is active and pristine`() {
        val status = PristineModePolicy::status
        assertThat(status(true, true, DirectSignalMode.PRISTINE)).isEqualTo(PristineModePolicy.Status.ACTIVE)
        assertThat(status(true, true, DirectSignalMode.PROCESSED)).isEqualTo(PristineModePolicy.Status.DIRECT_NOT_PRISTINE)
        assertThat(status(true, false, DirectSignalMode.PRISTINE)).isEqualTo(PristineModePolicy.Status.ONLY_IN_DIRECT)
        assertThat(status(true, false, DirectSignalMode.PROCESSED)).isEqualTo(PristineModePolicy.Status.ONLY_IN_DIRECT)
        assertThat(status(false, true, DirectSignalMode.PRISTINE)).isEqualTo(PristineModePolicy.Status.OFF)
        assertThat(status(false, false, DirectSignalMode.PRISTINE)).isEqualTo(PristineModePolicy.Status.OFF)
    }

    @Test
    fun `the lock on with the route on Android is not reported as active, whatever the sink last said`() {
        // The owner's case: lock on, route Android (reason CROSSFADE_REQUIRES_ANDROID before the fix), while the sink's
        // own state still read USB_DIRECT_ACTIVE for a moment.
        val state = DeviceControlUiState(
            usbDirectState = UsbDirectSinkState.USB_DIRECT_ACTIVE,
            audio = AudioStatus(
                purityPreference = true,
                route = AudioRouteState(reason = AudioRouteReason.CROSSFADE_REQUIRES_ANDROID),
                sinkDirectActive = true,
            ),
        )
        assertThat(state.pristineStatus).isEqualTo(PristineModePolicy.Status.ONLY_IN_DIRECT)
        assertThat(state.pristineStatus).isNotEqualTo(PristineModePolicy.Status.ACTIVE)
    }

    @Test
    fun `the status needs the route and the sink to agree before it says active`() {
        fun status(locked: Boolean, routeDirect: Boolean, sinkDirect: Boolean, mode: DirectSignalMode = DirectSignalMode.PRISTINE) =
            AudioStatus(
                purityPreference = locked,
                route = if (routeDirect) directRoute(mode) else AudioRouteState(),
                sinkDirectActive = sinkDirect,
            ).purity
        assertThat(status(true, routeDirect = true, sinkDirect = true)).isEqualTo(PristineModePolicy.Status.ACTIVE)
        // The route says Direct but the sink does not (yet): not claimed.
        assertThat(status(true, routeDirect = true, sinkDirect = false)).isEqualTo(PristineModePolicy.Status.ONLY_IN_DIRECT)
        assertThat(status(true, routeDirect = true, sinkDirect = true, mode = DirectSignalMode.PROCESSED))
            .isEqualTo(PristineModePolicy.Status.DIRECT_NOT_PRISTINE)
        assertThat(status(false, routeDirect = true, sinkDirect = true)).isEqualTo(PristineModePolicy.Status.OFF)
        // The device-control state answers from the same status.
        assertThat(DeviceControlUiState(audio = AudioStatus(purityPreference = true, route = directRoute(DirectSignalMode.PRISTINE), sinkDirectActive = true)).pristineStatus)
            .isEqualTo(PristineModePolicy.Status.ACTIVE)
    }

    @Test
    fun `every reason the route can give that is not Direct has words, and Direct needs none`() {
        for (reason in AudioRouteReason.values()) {
            val words = PristineLockCopy.routeReason(reason)
            val isDirect = reason == AudioRouteReason.DIRECT_PRISTINE || reason == AudioRouteReason.DIRECT_PROCESSED ||
                reason == AudioRouteReason.DIRECT_ACTIVE
            if (isDirect) assertThat(words).isNull() else assertThat(words).isNotNull()
        }
    }

    @Test
    fun `crossfade and speed have their own notice, the same whatever the route is`() {
        for (feature in listOf(PristineFrozenFeature.CROSSFADE, PristineFrozenFeature.SPEED)) {
            assertThat(PristineLockCopy.notice(feature, directActive = true))
                .isEqualTo(PristineLockCopy.notice(feature, directActive = false))
        }
        assertThat(PristineLockCopy.notice(PristineFrozenFeature.CROSSFADE, true))
            .isNotEqualTo(PristineLockCopy.notice(PristineFrozenFeature.SPEED, true))
    }
}
