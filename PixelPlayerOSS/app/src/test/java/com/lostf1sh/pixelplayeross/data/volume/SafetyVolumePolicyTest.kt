package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneSafetyPolicy
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import org.junit.jupiter.api.Test

class SafetyVolumePolicyTest {
    private val backend = VolumeBackendSnapshot(
        SafetyVolumeBackendKind.ANDROID_MEDIA,
        "android:built_in_speaker",
        "Speaker",
        rawLevel = 5,
        rawMin = 0,
        rawMax = 15,
        controllable = true,
    )

    @Test
    fun `cap ten maps discrete raw steps to normalized over-limit percentages`() {
        assertThat(SafetyVolumePolicy.displayPercent(0, backend, 10)).isEqualTo(0)
        assertThat(SafetyVolumePolicy.displayPercent(5, backend, 10)).isEqualTo(50)
        assertThat(SafetyVolumePolicy.displayPercent(10, backend, 10)).isEqualTo(100)
        assertThat(SafetyVolumePolicy.displayPercent(11, backend, 10)).isEqualTo(110)
        assertThat(SafetyVolumePolicy.displayPercent(15, backend, 10)).isEqualTo(150)
    }

    @Test
    fun `ordinary mapping uses backend range when no cap exists`() {
        assertThat(SafetyVolumePolicy.displayPercent(0, backend, null)).isEqualTo(0)
        assertThat(SafetyVolumePolicy.displayPercent(7, backend, null)).isEqualTo(47)
        assertThat(SafetyVolumePolicy.displayPercent(15, backend, null)).isEqualTo(100)
    }

    @Test
    fun `mapping respects nonzero backend minimum and rounding`() {
        val nonzero = backend.copy(rawMin = 2, rawMax = 12)
        assertThat(SafetyVolumePolicy.displayPercent(2, nonzero, 8)).isEqualTo(0)
        assertThat(SafetyVolumePolicy.displayPercent(5, nonzero, 8)).isEqualTo(50)
        assertThat(SafetyVolumePolicy.displayPercent(6, nonzero, 9)).isEqualTo(57)
    }

    @Test
    fun `normal request cannot exceed cap but override can reach backend max`() {
        assertThat(SafetyVolumePolicy.requestedRaw(11, backend, 10, false)).isEqualTo(10)
        assertThat(SafetyVolumePolicy.requestedRaw(11, backend, 10, true)).isEqualTo(11)
        assertThat(SafetyVolumePolicy.requestedRaw(99, backend, 10, true)).isEqualTo(15)
    }

    @Test
    fun `external over-cap level requests downward clamp only without override`() {
        assertThat(SafetyVolumePolicy.mustClamp(12, 10, false)).isEqualTo(10)
        assertThat(SafetyVolumePolicy.mustClamp(12, 10, true)).isNull()
        assertThat(SafetyVolumePolicy.mustClamp(8, 10, false)).isNull()
    }

    @Test
    fun `calibration is accepted only for exact stable context backend and range`() {
        val calibration = calibration()
        assertThat(SafetyVolumePolicy.compatibleCalibration(calibration, backend)).isEqualTo(calibration)
        assertThat(SafetyVolumePolicy.compatibleCalibration(
            calibration, backend.copy(outputContextId = "dac:other")
        )).isNull()
        assertThat(SafetyVolumePolicy.compatibleCalibration(
            calibration, backend.copy(rawMax = 16)
        )).isNull()
        assertThat(SafetyVolumePolicy.compatibleCalibration(
            calibration, backend.copy(kind = SafetyVolumeBackendKind.FIXED_OUTPUT)
        )).isNull()
    }

    @Test
    fun `triple blocked press triggers once and timeout resets`() {
        val gesture = SafetyOverrideGesture()
        assertThat(gesture.blockedVolumeUp(1_000)).isFalse()
        assertThat(gesture.blockedVolumeUp(2_000)).isFalse()
        assertThat(gesture.blockedVolumeUp(3_000)).isTrue()
        assertThat(gesture.blockedVolumeUp(3_100)).isFalse()
        assertThat(gesture.blockedVolumeUp(7_000)).isFalse()
        assertThat(gesture.blockedVolumeUp(7_100)).isFalse()
        assertThat(gesture.blockedVolumeUp(7_200)).isTrue()
    }

    @Test
    fun `gesture explicit reset clears pending presses`() {
        val gesture = SafetyOverrideGesture()
        gesture.blockedVolumeUp(1_000)
        gesture.blockedVolumeUp(1_100)
        gesture.reset()
        assertThat(gesture.blockedVolumeUp(1_200)).isFalse()
        assertThat(gesture.blockedVolumeUp(1_300)).isFalse()
        assertThat(gesture.blockedVolumeUp(1_400)).isTrue()
    }

    @Test
    fun `temporary override resets across backend kind even with the same DAC context`() {
        assertThat(
            safetyOverrideContextChanged(
                previousProfileId = "headphone-1",
                previousOutputContextId = "dac:fosi",
                previousBackendKind = SafetyVolumeBackendKind.ANDROID_MEDIA,
                profileId = "headphone-1",
                outputContextId = "dac:fosi",
                backendKind = SafetyVolumeBackendKind.FIXED_OUTPUT,
            )
        ).isTrue()
    }

    @Test
    fun `temporary override remains valid only for the exact same profile context and backend`() {
        assertThat(
            safetyOverrideContextChanged(
                "headphone-1", "dac:fosi", SafetyVolumeBackendKind.ANDROID_MEDIA,
                "headphone-1", "dac:fosi", SafetyVolumeBackendKind.ANDROID_MEDIA,
            )
        ).isFalse()
    }

    private fun calibration() = SafetyMaxCalibration(
        "android:built_in_speaker",
        SafetyVolumeBackendKind.ANDROID_MEDIA,
        10, 0, 15, 1L,
    )

    // ── B16-G2: the ceiling as it is right now ─────────────────────────────────────────────────

    private val dac = VolumeBackendSnapshot(
        SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, "dac:melody", "Melody", rawLevel = 31, rawMin = 0, rawMax = 40, controllable = true,
    )

    private fun policy(ceiling: Int?, enabled: Boolean = true, min: Int = 0, max: Int = 40, context: String = "dac:melody") =
        HeadphoneSafetyPolicy(
            enabled = enabled,
            calibrations = ceiling?.let {
                val calibration = SafetyMaxCalibration(context, SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, it, min, max, 1L)
                mapOf(calibration.storageKey to calibration)
            } ?: emptyMap(),
        )

    @Test
    fun `the ceiling now is the stored one while Safety Max is on and no override stands`() {
        assertThat(SafetyVolumePolicy.ceilingNow(policy(31), dac, overrideActive = false)).isEqualTo(31)
    }

    @Test
    fun `no ceiling while an override stands, Safety Max is off, there is no profile or no calibration for the output`() {
        assertThat(SafetyVolumePolicy.ceilingNow(policy(31), dac, overrideActive = true)).isNull()
        assertThat(SafetyVolumePolicy.ceilingNow(policy(31, enabled = false), dac, overrideActive = false)).isNull()
        assertThat(SafetyVolumePolicy.ceilingNow(null, dac, overrideActive = false)).isNull()
        assertThat(SafetyVolumePolicy.ceilingNow(policy(null), dac, overrideActive = false)).isNull()
        assertThat(SafetyVolumePolicy.ceilingNow(policy(31, context = "dac:other"), dac, overrideActive = false)).isNull()
    }

    @Test
    fun `a calibration taken on another range is rescaled down, as the published state does`() {
        // 20 of 0..30 is two thirds; on 0..40 that is 26 (rounded down), never louder than the proportional point.
        assertThat(SafetyVolumePolicy.ceilingNow(policy(20, max = 30), dac, overrideActive = false)).isEqualTo(26)
    }
}
