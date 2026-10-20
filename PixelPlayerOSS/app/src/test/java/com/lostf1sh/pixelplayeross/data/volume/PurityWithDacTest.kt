package com.lostf1sh.pixelplayeross.data.volume

import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.UsbDirectSinkState
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteFacts
import com.lostf1sh.pixelplayeross.data.service.player.AudioRoutePolicy
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.HandoffVolumeVerdict
import com.lostf1sh.pixelplayeross.presentation.components.PristineFrozenFeature
import com.lostf1sh.pixelplayeross.presentation.components.PristineLockCopy
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B16-G4 and G5: Purity (the Pure Direct lock) only means something with a DAC.
 *
 * The lock is a preference. It is *in force* only while a USB DAC is connected ([PristineModePolicy.lockInForce]); without
 * one everything it would freeze applies on the Android route as if it were off, the preference is kept, and it takes hold
 * again by itself when a DAC arrives. These tests build the requirements the way the engine does
 * ([PristineModePolicy.effectiveRequirements] over what `MusicService` publishes) and decide the route the way it does.
 */
class PurityWithDacTest {

    private enum class Choice { EQ, CROSSFADE, SPEED }

    private fun requirementsFor(choice: Choice) = when (choice) {
        Choice.EQ -> AudioProcessingRequirements(graphicEq = true)
        Choice.CROSSFADE -> AudioProcessingRequirements(crossfade = true)
        Choice.SPEED -> AudioProcessingRequirements(nonDefaultSpeed = true)
    }

    private fun route(choice: Choice, lock: Boolean, dac: Boolean) = AudioRoutePolicy.decide(
        AudioRouteFacts(
            usbDacPresent = dac,
            usbPermissionGranted = dac,
            processing = PristineModePolicy.effectiveRequirements(requirementsFor(choice), lock, dac),
        ),
    )

    // ── when the lock is in force ──────────────────────────────────────────────────────────────

    @Test
    fun `the lock is in force with the preference on and a DAC, and while the router has not looked`() {
        assertThat(PristineModePolicy.lockInForce(lockPreference = true, usbDacPresent = true)).isTrue()
        assertThat(PristineModePolicy.lockInForce(lockPreference = true, usbDacPresent = false)).isFalse()
        // Before the router has looked the lock holds: a start-up with the DAC plugged in never lets a frozen contributor through.
        assertThat(PristineModePolicy.lockInForce(lockPreference = true, usbDacPresent = null)).isTrue()
        for (dac in listOf(true, false, null)) assertThat(PristineModePolicy.lockInForce(false, dac)).isFalse()
    }

    // ── the matrix: lock × DAC × {EQ, crossfade, speed} → route, and what applies ───────────────

    @Test
    fun `lock on with a DAC holds every one of them, on USB Direct and pristine, so nothing applies`() {
        for (choice in Choice.values()) {
            val decision = route(choice, lock = true, dac = true)
            assertThat(decision.backend).isEqualTo(AudioBackend.USB_DIRECT)
            assertThat(decision.signalMode).isEqualTo(DirectSignalMode.PRISTINE)
            assertThat(decision.reason).isEqualTo(AudioRouteReason.DIRECT_PRISTINE)
            assertThat(PristineModePolicy.effectiveRequirements(requirementsFor(choice), true, true))
                .isEqualTo(AudioProcessingRequirements())
        }
        assertThat(PristineModePolicy.effectiveCrossfade(enabled = true, locked = PristineModePolicy.lockInForce(true, true))).isFalse()
        assertThat(PristineModePolicy.effectiveSpeed(1.25f, locked = PristineModePolicy.lockInForce(true, true))).isEqualTo(1f)
    }

    @Test
    fun `lock on without a DAC is as if the lock were off, everything applies on the Android route`() {
        val expected = mapOf(
            Choice.EQ to AudioRouteReason.NO_USB_DAC,
            Choice.CROSSFADE to AudioRouteReason.CROSSFADE_REQUIRES_ANDROID,
            Choice.SPEED to AudioRouteReason.SPEED_REQUIRES_ANDROID,
        )
        for (choice in Choice.values()) {
            val locked = route(choice, lock = true, dac = false)
            val off = route(choice, lock = false, dac = false)
            assertThat(locked).isEqualTo(off)                                     // the preference changes nothing without a DAC
            assertThat(locked.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
            assertThat(locked.reason).isEqualTo(expected.getValue(choice))
            // Nothing is held back: the requirements are the listener's own.
            assertThat(PristineModePolicy.effectiveRequirements(requirementsFor(choice), true, false)).isEqualTo(requirementsFor(choice))
        }
        val inForce = PristineModePolicy.lockInForce(true, false)
        assertThat(PristineModePolicy.effectiveCrossfade(enabled = true, locked = inForce)).isTrue()
        assertThat(PristineModePolicy.effectiveSpeed(1.25f, locked = inForce)).isEqualTo(1.25f)
    }

    @Test
    fun `lock off with a DAC is the behaviour there always was`() {
        assertThat(route(Choice.EQ, lock = false, dac = true).reason).isEqualTo(AudioRouteReason.DIRECT_PROCESSED)
        assertThat(route(Choice.EQ, lock = false, dac = true).signalMode).isEqualTo(DirectSignalMode.PROCESSED)
        assertThat(route(Choice.CROSSFADE, lock = false, dac = true).reason).isEqualTo(AudioRouteReason.CROSSFADE_REQUIRES_ANDROID)
        assertThat(route(Choice.SPEED, lock = false, dac = true).reason).isEqualTo(AudioRouteReason.SPEED_REQUIRES_ANDROID)
    }

    @Test
    fun `the preference is never switched off by any of this, it only stops holding anything`() {
        // The function that says whether it holds takes the preference and answers; there is nothing here to write it back.
        val preference = true
        PristineModePolicy.lockInForce(preference, false)
        PristineModePolicy.effectiveRequirements(requirementsFor(Choice.CROSSFADE), preference, false)
        assertThat(preference).isTrue()
        // And the lock comes back by itself when the DAC does.
        assertThat(PristineModePolicy.lockInForce(preference, true)).isTrue()
    }

    // ── what the screens say ───────────────────────────────────────────────────────────────────

    @Test
    fun `without a DAC the lock is not reported as active and the crossfade and speed controls are available`() {
        val noDac = DeviceControlUiState(audio = AudioStatus(purityPreference = true, route = AudioRouteState(usbDacPresent = false)))
        assertThat(noDac.pristineInForce).isFalse()
        assertThat(noDac.pristineStatus).isEqualTo(PristineModePolicy.Status.ONLY_IN_DIRECT)   // "Pristine (USB Direct only) · no USB DAC connected"
        assertThat(PristineLockCopy.routeReason(AudioRouteReason.NO_USB_DAC)).isNotNull()

        val withDac = DeviceControlUiState(
            usbDirectState = UsbDirectSinkState.USB_DIRECT_ACTIVE,
            audio = AudioStatus(
                purityPreference = true,
                route = AudioRouteState(
                    usbDacPresent = true,
                    actualBackend = AudioBackend.USB_DIRECT,
                    transition = AudioRouteTransition.DIRECT_ACTIVE,
                ),
                sinkDirectActive = true,
            ),
        )
        assertThat(withDac.pristineInForce).isTrue()
        assertThat(withDac.pristineStatus).isEqualTo(PristineModePolicy.Status.ACTIVE)

        assertThat(DeviceControlUiState(audio = AudioStatus(purityPreference = false, route = AudioRouteState(usbDacPresent = true))).pristineInForce).isFalse()
        assertThat(DeviceControlUiState(audio = AudioStatus(purityPreference = true)).pristineInForce).isTrue()        // not looked yet: held
    }

    @Test
    fun `with the lock on and no DAC the notice says crossfade and speed will not apply once a DAC is connected`() {
        for (feature in listOf(PristineFrozenFeature.CROSSFADE, PristineFrozenFeature.SPEED)) {
            val pending = PristineLockCopy.notice(feature, directActive = false, dacPresent = false)
            val inForce = PristineLockCopy.notice(feature, directActive = false, dacPresent = true)
            assertThat(pending).isNotEqualTo(inForce)
            // The route is not what decides it, the DAC is.
            assertThat(PristineLockCopy.notice(feature, directActive = true, dacPresent = false)).isEqualTo(pending)
        }
        // The EQ-like ones still depend on whether Direct carries the stream, as before.
        assertThat(PristineLockCopy.notice(PristineFrozenFeature.EQ, directActive = true, dacPresent = false))
            .isEqualTo(PristineLockCopy.notice(PristineFrozenFeature.EQ, directActive = true))
    }

    // ── B16-G5: honest crossfade and speed with a DAC and the lock off ──────────────────────────

    @Test
    fun `with a DAC and the lock off, crossfade and speed say they play through Android audio`() {
        assertThat(PristineLockCopy.androidRouteNote(PristineFrozenFeature.CROSSFADE)).isNotNull()
        assertThat(PristineLockCopy.androidRouteNote(PristineFrozenFeature.SPEED)).isNotNull()
        assertThat(PristineLockCopy.androidRouteNote(PristineFrozenFeature.CROSSFADE))
            .isNotEqualTo(PristineLockCopy.androidRouteNote(PristineFrozenFeature.SPEED))
        for (feature in listOf(PristineFrozenFeature.EQ, PristineFrozenFeature.REPLAY_GAIN, PristineFrozenFeature.DIGITAL_VOLUME)) {
            assertThat(PristineLockCopy.androidRouteNote(feature)).isNull()           // they stay on Direct: nothing to warn of
        }
        assertThat(DeviceControlUiState(audio = AudioStatus(route = AudioRouteState(usbDacPresent = true))).usbDacConnected).isTrue()
        assertThat(DeviceControlUiState(audio = AudioStatus(route = AudioRouteState(usbDacPresent = false))).usbDacConnected).isFalse()
        assertThat(DeviceControlUiState().usbDacConnected).isFalse()
    }

    @Test
    fun `the device status names a listening choice as the reason for Android, and only those`() {
        val signal = listOf(
            AudioRouteReason.CROSSFADE_REQUIRES_ANDROID, AudioRouteReason.SPEED_REQUIRES_ANDROID,
            AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID,
        )
        for (reason in AudioRouteReason.values()) {
            val words = PristineLockCopy.signalBlockerReason(reason)
            if (reason in signal) assertThat(words).isEqualTo(PristineLockCopy.routeReason(reason)) else assertThat(words).isNull()
        }
        // Each has its own words: "a setting needs the Android route" told nobody which.
        assertThat(signal.map { PristineLockCopy.routeReason(it) }.toSet()).hasSize(3)
    }

    // ── the transitions never raise the level (B1) ─────────────────────────────────────────────

    private class FakeHost(var step: Int? = 150, var direct: Boolean = true) : PristineModeHost {
        var locked = false
        var lowered = 0
        val dacWrites = mutableListOf<Int>()
        override fun gainContributors() = Triple(0.5f, null, 1f)
        override fun isDirectActive() = direct
        override fun hardwareVolume() = step?.let { PristineModePolicy.HardwareVolume(it, 0, 200, 0.5f, true) }
        override fun ceilingStep(): Int? = null
        override fun setHardwareStep(step: Int): Boolean { dacWrites += step; this.step = step; return true }
        override suspend fun awaitContributorsApplied() = true
        override suspend fun lockState() = locked to lowered
        override suspend fun persist(locked: Boolean, loweredSteps: Int) { this.locked = locked; lowered = loweredSteps }
    }

    @Test
    fun `unplugging with the lock on touches no DAC level and keeps the preference and what was lowered`() = runTest {
        val host = FakeHost()
        PristineModeController(host).enable()
        assertThat(host.locked).isTrue()
        val loweredBefore = host.lowered
        val writesBefore = host.dacWrites.toList()
        assertThat(loweredBefore).isGreaterThan(0)

        // The DAC goes away. Nothing in the app reacts to that by writing a DAC level or the lock: the lock simply stops
        // being in force (a function of the DAC fact), and the frozen attenuation applies again on the Android route.
        host.direct = false
        host.step = null
        assertThat(PristineModePolicy.lockInForce(host.locked, usbDacPresent = false)).isFalse()

        assertThat(host.locked).isTrue()
        assertThat(host.lowered).isEqualTo(loweredBefore)
        assertThat(host.dacWrites).isEqualTo(writesBefore)
    }

    @Test
    fun `turning the lock off with no DAC stays quieter, with no DAC write, the lock off and nothing owed`() = runTest {
        val host = FakeHost()
        val controller = PristineModeController(host)
        controller.enable()
        host.direct = false
        host.step = null
        val writesBefore = host.dacWrites.toList()

        assertThat(controller.disable()).isEqualTo(PristineToggleResult.Disabled(0f))

        assertThat(host.dacWrites).isEqualTo(writesBefore)
        assertThat(host.locked).isFalse()
        assertThat(host.lowered).isEqualTo(0)
    }

    @Test
    fun `what comes back on the Android route when the DAC is unplugged only ever attenuates`() {
        // The frozen contributors return as the listener set them. The gain model clamps each of them to unity and the
        // player's volume is their product, so on the Android route the level can only be at or below the untouched one.
        val users = listOf(0.1f, 0.5f, 1f)
        val replayGains = listOf(null, 0.3f, 1f, 1.6f)
        val compensations = listOf(0.5f, 1f)
        for (user in users) for (rg in replayGains) for (compensation in compensations) {
            val product = (user * (rg ?: 1f) * compensation).coerceIn(0f, 1f)
            assertThat(product).isAtMost(1f)
            assertThat(PristineModePolicy.frozenAttenuationDb(user, rg, compensation)).isAtMost(0f)
        }
    }

    @Test
    fun `plugging a DAC in with the lock on resumes only at a level the handoff can bound`() {
        // The lock coming into force on a plug is the same event as the automatic Android-to-Direct handoff, and that
        // resumes only on this verdict: under an armed ceiling, or under the conservative level.
        fun state(raw: Int, ceiling: Int?, decibels: Float?) = SafetyVolumeState(
            backend = VolumeBackendSnapshot(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, "dac:x", "DAC", raw, 0, 60, true),
            selectedProfileId = "p",
            safetyEnabled = ceiling != null,
            calibration = ceiling?.let {
                SafetyMaxCalibration("dac:x", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, it, 0, 60, 1L)
            },
            hardwareDecibels = decibels,
        )
        assertThat(HandoffResumeSafety.verdict(state(raw = 10, ceiling = 20, decibels = -40f))).isEqualTo(HandoffVolumeVerdict.RESUME)
        assertThat(HandoffResumeSafety.verdict(state(raw = 30, ceiling = 20, decibels = -10f))).isEqualTo(HandoffVolumeVerdict.WAIT)
        assertThat(HandoffResumeSafety.verdict(state(raw = 50, ceiling = null, decibels = -12f))).isEqualTo(HandoffVolumeVerdict.HOLD)
        assertThat(HandoffResumeSafety.verdict(state(raw = 5, ceiling = null, decibels = -45f))).isEqualTo(HandoffVolumeVerdict.RESUME)
    }
}
