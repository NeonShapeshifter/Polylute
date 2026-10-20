package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.data.headphones.*
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumePolicy
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.data.volume.VolumeBackendSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProfileAudioPresentationTest {
    private val android = VolumeBackendSnapshot(SafetyVolumeBackendKind.ANDROID_MEDIA, "dac:a", "A", 4, 0, 15, true)
    private val calibration = SafetyMaxCalibration("dac:a", android.kind, 8, 0, 15, 1L)
    private val eq = HeadphoneEqState(enabled = true, bands10 = List(10) { 3 }, bands15 = List(15) { -2 })
    private val profile = HeadphoneProfile("a", "A", eq, 1L, 1L,
        HeadphoneSafetyPolicy(enabled = true, calibrations = mapOf(calibration.storageKey to calibration)))
    private val volume = SafetyVolumeState(backend = android, selectedProfileId = "a", safetyEnabled = true, calibration = calibration)
    private val profiles = HeadphoneProfileState(listOf(profile, profile.copy(profileId = "b", equalizer = eq.copy(enabled = false))), "a")
    private val direct = AudioRouteState(actualBackend = AudioBackend.USB_DIRECT,
        transition = AudioRouteTransition.DIRECT_ACTIVE, signalMode = DirectSignalMode.PRISTINE)

    @Test
    fun `Android to fixed USB and back changes only the displayed effect`() {
        assertEquals(SafetyMaxOutputState.ACTIVE, safetyMaxOutputState(profile, volume))
        val fixed = android.copy(kind = SafetyVolumeBackendKind.FIXED_OUTPUT, controllable = false, rawMax = 0)
        assertFalse(safetyMaxOutputCompatible(fixed))
        assertEquals(SafetyMaxOutputState.UNAVAILABLE, safetyMaxOutputState(profile, volume.copy(backend = fixed, calibration = null)))
        assertEquals(SafetyMaxOutputState.ACTIVE, safetyMaxOutputState(profile, volume))
        assertTrue(profile.safetyMax.enabled)
        assertEquals(calibration, profile.safetyMax.calibrationFor("dac:a", android.kind))
    }

    @Test
    fun `USB hardware volume is compatible and uses its own calibration`() {
        val hardware = android.copy(kind = SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, rawMax = 60)
        assertTrue(safetyMaxOutputCompatible(hardware))
        assertNull(profile.safetyMax.calibrationFor(hardware.outputContextId, hardware.kind))
        assertEquals(SafetyMaxOutputState.NOT_CALIBRATED, safetyMaxOutputState(profile, volume.copy(backend = hardware, calibration = null)))
        val hardwareCalibration = calibration.copy(backendKind = hardware.kind, ceilingRaw = 32, backendMaxRaw = 60)
        assertEquals(SafetyMaxOutputState.ACTIVE, safetyMaxOutputState(profile, volume.copy(backend = hardware, calibration = hardwareCalibration)))
    }

    @Test
    fun `unknown or uncontrollable outputs cannot calibrate`() {
        assertFalse(safetyMaxOutputCompatible(android.copy(kind = SafetyVolumeBackendKind.UNKNOWN)))
        assertFalse(safetyMaxOutputCompatible(android.copy(controllable = false)))
        assertFalse(safetyMaxOutputCompatible(android.copy(outputContextId = null)))
        assertFalse(safetyMaxOutputCompatible(android.copy(rawMax = 0)))
    }

    @Test
    fun `DAC and selected profile changes do not claim another calibration is active`() {
        assertEquals(SafetyMaxOutputState.NOT_CALIBRATED, safetyMaxOutputState(profile,
            volume.copy(backend = android.copy(outputContextId = "dac:b"), calibration = null)))
        assertEquals(SafetyMaxOutputState.PROFILE_NOT_SELECTED, safetyMaxOutputState(profile, volume.copy(selectedProfileId = "b")))
        assertEquals(SafetyMaxOutputState.OFF, safetyMaxOutputState(profile.copy(safetyMax = profile.safetyMax.copy(enabled = false)), volume))
    }

    @Test
    fun `rescaled limits still apply and a temporary override is explicit`() {
        val changed = android.copy(rawMax = 30)
        val rescaled = SafetyVolumePolicy.effectiveCalibration(calibration, changed)
        assertEquals(16, rescaled?.ceilingRaw)
        val refreshed = volume.copy(backend = changed, calibration = rescaled, calibrationNeedsRefresh = true)
        assertEquals(SafetyMaxOutputState.ACTIVE_RESCALED, safetyMaxOutputState(profile, refreshed))
        assertEquals(SafetyMaxOutputState.TEMPORARY_OVERRIDE, safetyMaxOutputState(profile, refreshed.copy(temporaryOverride = true)))
        assertEquals(8, profile.safetyMax.calibrations.getValue(calibration.storageKey).ceilingRaw)
    }

    @Test
    fun `Pure Direct bypass requires an actual pristine Direct output`() {
        fun state(route: AudioRouteState, locked: Boolean = true) =
            equalizerOutputContext(profiles, "a", route, locked, false).state(eq.enabled)
        assertEquals(EqualizerOutputState.APPLIED, state(AudioRouteState()))
        assertEquals(EqualizerOutputState.APPLIED, state(AudioRouteState(desiredBackend = AudioBackend.USB_DIRECT)))
        assertEquals(EqualizerOutputState.BYPASSED_PURE_DIRECT, state(direct))
        assertEquals(EqualizerOutputState.BYPASSED_USB_DIRECT, state(direct, false))
        assertEquals(EqualizerOutputState.APPLIED, state(direct.copy(signalMode = DirectSignalMode.PROCESSED), false))
        assertEquals(EqualizerOutputState.APPLIED, state(AudioRouteState()))
        assertEquals(List(10) { 3 }, editedEqualizer(profiles, "a").bands10)
        assertTrue(editedEqualizer(profiles, "a").enabled)
    }

    @Test
    fun `named editor keeps both saved curves when the DAC selects another profile`() {
        val changedSelection = profiles.copy(selectedProfileId = "b")
        assertEquals(eq, editedEqualizer(changedSelection, "a"))
        assertEquals(List(15) { -2 }, editedEqualizer(changedSelection, "a").bands15)
        assertEquals(EqualizerOutputState.PROFILE_NOT_SELECTED,
            equalizerOutputContext(changedSelection, "a", direct, true, false).state(true))
        assertFalse(editedEqualizer(changedSelection, null).enabled)
        assertEquals(HeadphoneEqState(), editedEqualizer(changedSelection, "missing"))
        assertEquals(EqualizerOutputState.BYPASSED_PURE_DIRECT,
            equalizerOutputContext(profiles, "a", direct, true, false).state(true))
    }

    @Test
    fun `saved off and maintenance bypass have distinct output descriptions`() {
        val context = equalizerOutputContext(profiles, "a", direct, true, true)
        assertEquals(EqualizerOutputState.OFF, context.state(false))
        assertEquals(EqualizerOutputState.BYPASSED_MAINTENANCE, context.state(true))
        assertEquals(eq, editedEqualizer(profiles, "a"))
    }
}
