package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.service.player.HandoffVolumeVerdict
import org.junit.jupiter.api.Test

/** AUD-002: what the Safety state must say before an automatic handoff may resume. */
class HandoffResumeSafetyTest {

    private fun backend(kind: SafetyVolumeBackendKind, raw: Int = 10) = VolumeBackendSnapshot(
        kind, "dac:x", "DAC", raw, 0, 60, kind != SafetyVolumeBackendKind.FIXED_OUTPUT,
    )

    private val calibration = SafetyMaxCalibration(
        outputContextId = "dac:x",
        backendKind = SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
        ceilingRaw = 20,
        backendMinRaw = 0,
        backendMaxRaw = 60,
        calibratedAtEpochMs = 1L,
    )

    private fun state(
        backend: VolumeBackendSnapshot,
        calibration: SafetyMaxCalibration? = null,
        enabled: Boolean = calibration != null,
        decibels: Float? = null,
    ) = SafetyVolumeState(
        backend = backend,
        selectedProfileId = "p",
        safetyEnabled = enabled,
        calibration = calibration,
        hardwareDecibels = decibels,
    )

    @Test
    fun `the Android backend has not been reconciled to the DAC yet`() {
        assertThat(HandoffResumeSafety.verdict(state(backend(SafetyVolumeBackendKind.ANDROID_MEDIA))))
            .isEqualTo(HandoffVolumeVerdict.WAIT)
    }

    @Test
    fun `a fixed output is never auto resumed`() {
        assertThat(HandoffResumeSafety.verdict(state(backend(SafetyVolumeBackendKind.FIXED_OUTPUT))))
            .isEqualTo(HandoffVolumeVerdict.HOLD)
    }

    @Test
    fun `within the armed ceiling resumes`() {
        val s = state(backend(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, raw = 20), calibration, decibels = -40f)
        assertThat(HandoffResumeSafety.verdict(s)).isEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `above the armed ceiling waits for the clamp`() {
        val s = state(backend(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, raw = 21), calibration, decibels = -38f)
        assertThat(HandoffResumeSafety.verdict(s)).isEqualTo(HandoffVolumeVerdict.WAIT)
    }

    @Test
    fun `without a ceiling, a loud DAC is held paused`() {
        val s = state(backend(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, raw = 60), decibels = 0f)
        assertThat(HandoffResumeSafety.verdict(s)).isEqualTo(HandoffVolumeVerdict.HOLD)
    }

    @Test
    fun `without a ceiling, a conservative level resumes`() {
        val s = state(
            backend(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, raw = 5),
            decibels = HandoffResumeSafety.CONSERVATIVE_RESUME_DB - 1f,
        )
        assertThat(HandoffResumeSafety.verdict(s)).isEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `a disarmed ceiling does not count as one`() {
        val s = state(
            backend(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, raw = 20),
            calibration,
            enabled = false,
            decibels = 0f,
        )
        assertThat(HandoffResumeSafety.verdict(s)).isEqualTo(HandoffVolumeVerdict.HOLD)
    }
}
