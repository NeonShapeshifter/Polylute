package com.lostf1sh.pixelplayeross.data.headphones

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * AUD-024: the same DAC is reached through two backends — Android media volume (USB through the
 * mixer) and its own Feature Unit (Direct). Their raw scales differ, so each needs its own
 * calibration; keyed by `outputContextId` alone, calibrating one route overwrote the other.
 */
class SafetyMaxCalibrationKeyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun calibration(kind: SafetyVolumeBackendKind, ceiling: Int) = SafetyMaxCalibration(
        outputContextId = "dac:x",
        backendKind = kind,
        ceilingRaw = ceiling,
        backendMinRaw = 0,
        backendMaxRaw = 60,
        calibratedAtEpochMs = 1L,
    )

    private val created = HeadphoneProfileCatalog.createAndSelect(
        HeadphoneProfileState(), "A", HeadphoneEqState(), 10L, "hp"
    )

    @Test
    fun `calibrating both routes of one DAC keeps both`() {
        val android = calibration(SafetyVolumeBackendKind.ANDROID_MEDIA, 12)
        val hardware = calibration(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, 30)

        val state = HeadphoneProfileCatalog.calibrateSafetyMax(
            HeadphoneProfileCatalog.calibrateSafetyMax(created, "hp", android, 20L),
            "hp", hardware, 30L,
        )
        val reloaded = HeadphoneProfilesCodec.decode(json, HeadphoneProfilesCodec.encode(json, state))
        val policy = reloaded.selectedProfile!!.safetyMax

        assertThat(policy.calibrationFor("dac:x", SafetyVolumeBackendKind.ANDROID_MEDIA)).isEqualTo(android)
        assertThat(policy.calibrationFor("dac:x", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME)).isEqualTo(hardware)
    }

    @Test
    fun `a calibration stored under the legacy context-only key is still found`() {
        val hardware = calibration(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, 30)
        val legacy = created.copy(
            profiles = created.profiles.map {
                it.copy(safetyMax = HeadphoneSafetyPolicy(calibrations = mapOf("dac:x" to hardware)))
            }
        )

        val reloaded = HeadphoneProfilesCodec.decode(json, HeadphoneProfilesCodec.encode(json, legacy))
        val policy = reloaded.selectedProfile!!.safetyMax

        assertThat(policy.calibrationFor("dac:x", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME)).isEqualTo(hardware)
        assertThat(policy.calibrationFor("dac:x", SafetyVolumeBackendKind.ANDROID_MEDIA)).isNull()
    }
}
