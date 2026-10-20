package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import org.junit.jupiter.api.Test

/**
 * AUD-074: a backend whose range changed (an OTA, DAC firmware) used to drop Safety Max silently,
 * because a calibration was only honoured for the exact min/max it was taken on.
 */
class SafetyCalibrationRescaleTest {

    private val calibration = SafetyMaxCalibration(
        outputContextId = "dac:x",
        backendKind = SafetyVolumeBackendKind.ANDROID_MEDIA,
        ceilingRaw = 10,
        backendMinRaw = 0,
        backendMaxRaw = 15,
        calibratedAtEpochMs = 1L,
    )

    private fun backend(min: Int, max: Int) = VolumeBackendSnapshot(
        SafetyVolumeBackendKind.ANDROID_MEDIA, "dac:x", "DAC", 0, min, max, true,
    )

    @Test
    fun `an exact range keeps the calibration as stored`() {
        assertThat(SafetyVolumePolicy.effectiveCalibration(calibration, backend(0, 15))).isEqualTo(calibration)
    }

    @Test
    fun `a changed range still protects, rescaled proportionally and rounded down`() {
        val rescaled = SafetyVolumePolicy.effectiveCalibration(calibration, backend(0, 25))!!

        // 10/15 of 25 = 16.67 → 16: never louder than the proportional point.
        assertThat(rescaled.ceilingRaw).isEqualTo(16)
        assertThat(rescaled.backendMaxRaw).isEqualTo(25)
        assertThat(rescaled.isUsable).isTrue()
    }

    @Test
    fun `a rescaled ceiling is never at or below the backend minimum`() {
        val tiny = calibration.copy(ceilingRaw = 1)
        val rescaled = SafetyVolumePolicy.effectiveCalibration(tiny, backend(0, 5))!!
        assertThat(rescaled.ceilingRaw).isEqualTo(1)
        assertThat(rescaled.isUsable).isTrue()
    }

    @Test
    fun `another context or backend is still not a match`() {
        assertThat(SafetyVolumePolicy.effectiveCalibration(
            calibration, backend(0, 25).copy(outputContextId = "dac:other")
        )).isNull()
        assertThat(SafetyVolumePolicy.effectiveCalibration(
            calibration, backend(0, 25).copy(kind = SafetyVolumeBackendKind.USB_HARDWARE_VOLUME)
        )).isNull()
    }
}
