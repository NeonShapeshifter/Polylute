package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import org.junit.jupiter.api.Test

/** B35 (RT-01): which level a calibration may save, as a rule, apart from the controller that applies it. */
class SafetyMaxCalibrationPolicyTest {

    private fun backend(
        raw: Int = 20,
        verified: Boolean = true,
        controllable: Boolean = true,
        kind: SafetyVolumeBackendKind = SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
        context: String? = "dac:a",
    ) = VolumeBackendSnapshot(kind, context, "DAC", raw, 0, 60, controllable, levelVerified = verified)

    private fun state(backend: VolumeBackendSnapshot = backend(), profile: String? = "p") =
        SafetyVolumeState(backend = backend, selectedProfileId = profile)

    private val basis = SafetyMaxCalibrationBasis("p", "dac:a", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, connection = 7L)

    @Test
    fun `a confirmed level above the minimum on the same output may be saved`() {
        assertThat(SafetyMaxCalibrationPolicy.refusal(basis, state(), 7L)).isNull()
    }

    @Test
    fun `a level that was not confirmed is refused whatever it says`() {
        assertThat(SafetyMaxCalibrationPolicy.refusal(basis, state(backend(raw = 40, verified = false)), 7L))
            .isEqualTo(SafetyMaxCalibrationResult.LEVEL_UNCONFIRMED)
    }

    @Test
    fun `an unconfirmed zero is unconfirmed, not zero`() {
        assertThat(SafetyMaxCalibrationPolicy.refusal(basis, state(backend(raw = 0, verified = false)), 7L))
            .isEqualTo(SafetyMaxCalibrationResult.LEVEL_UNCONFIRMED)
    }

    @Test
    fun `a confirmed zero is zero`() {
        assertThat(SafetyMaxCalibrationPolicy.refusal(basis, state(backend(raw = 0)), 7L))
            .isEqualTo(SafetyMaxCalibrationResult.LEVEL_ZERO)
    }

    @Test
    fun `another output, profile, kind or connection is a change, before anything about the level`() {
        // every one of them with a level that would otherwise be refused as unconfirmed: the change is said first
        val unconfirmed = backend(verified = false)
        assertThat(SafetyMaxCalibrationPolicy.refusal(basis, state(unconfirmed.copy(outputContextId = "dac:b")), 7L))
            .isEqualTo(SafetyMaxCalibrationResult.OUTPUT_CHANGED)
        assertThat(SafetyMaxCalibrationPolicy.refusal(basis, state(unconfirmed, profile = "other"), 7L))
            .isEqualTo(SafetyMaxCalibrationResult.OUTPUT_CHANGED)
        assertThat(SafetyMaxCalibrationPolicy.refusal(basis, state(unconfirmed.copy(kind = SafetyVolumeBackendKind.ANDROID_MEDIA)), 7L))
            .isEqualTo(SafetyMaxCalibrationResult.OUTPUT_CHANGED)
        assertThat(SafetyMaxCalibrationPolicy.refusal(basis, state(unconfirmed), 8L))
            .isEqualTo(SafetyMaxCalibrationResult.OUTPUT_CHANGED)
        assertThat(SafetyMaxCalibrationPolicy.refusal(basis, state(unconfirmed), null))
            .isEqualTo(SafetyMaxCalibrationResult.OUTPUT_CHANGED)
    }

    @Test
    fun `an output that became uncontrollable is not available`() {
        assertThat(SafetyMaxCalibrationPolicy.refusal(basis, state(backend(controllable = false)), 7L))
            .isEqualTo(SafetyMaxCalibrationResult.NOT_AVAILABLE)
    }

    @Test
    fun `a request needs a profile, an output and something to control`() {
        assertThat(SafetyMaxCalibrationPolicy.basisOf(state(), 7L)).isEqualTo(basis)
        assertThat(SafetyMaxCalibrationPolicy.basisOf(state(profile = null), 7L)).isNull()
        assertThat(SafetyMaxCalibrationPolicy.basisOf(state(backend(context = null)), 7L)).isNull()
        assertThat(SafetyMaxCalibrationPolicy.basisOf(state(backend(controllable = false)), 7L)).isNull()
    }

    @Test
    fun `the platform volume has no connection to compare`() {
        val android = SafetyMaxCalibrationBasis("p", "android", SafetyVolumeBackendKind.ANDROID_MEDIA, connection = null)
        val state = state(backend(kind = SafetyVolumeBackendKind.ANDROID_MEDIA, context = "android"))
        assertThat(SafetyMaxCalibrationPolicy.refusal(android, state, null)).isNull()
    }
}
