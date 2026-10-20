package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-126, the diagnosis of B7-fix4: the DataStore had no Safety Max calibration for the owner's DAC
 * (only the built-in speaker), so nothing limited a rise of the DAC's Feature Unit, and the log did
 * not say so: the `ceiling=` it printed was the guard's answer — the lower of the saved level and the
 * ceiling — not the ceiling. The 0 dB of 28/09 12:12 is still unexplained, but "there was no ceiling"
 * was invisible for as long as it went on.
 */
class SafetyCalibrationNoticeTest {
    private fun backend(
        kind: SafetyVolumeBackendKind = SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
        context: String? = "dac:profile-5ce5",
        controllable: Boolean = true,
    ) = VolumeBackendSnapshot(kind, context, "SNOWSKY Melody", 12, 0, 50, controllable)

    private val calibration = SafetyMaxCalibration(
        outputContextId = "dac:profile-5ce5",
        backendKind = SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
        ceilingRaw = 30,
        backendMinRaw = 0,
        backendMaxRaw = 50,
        calibratedAtEpochMs = 1L,
    )

    @Test
    fun `Direct on a DAC with no calibrated ceiling asks for one, once`() {
        val notice = SafetyCalibrationNotice()

        assertThat(notice.shouldShow(backend(), calibration = null)).isTrue()
        // reconcile runs on every route, profile and settings event: once, not on each.
        assertThat(notice.shouldShow(backend(), calibration = null)).isFalse()
        assertThat(notice.shouldShow(backend(), calibration = null)).isFalse()
    }

    @Test
    fun `each DAC is told once`() {
        val notice = SafetyCalibrationNotice()

        assertThat(notice.shouldShow(backend(context = "dac:a"), null)).isTrue()
        assertThat(notice.shouldShow(backend(context = "dac:b"), null)).isTrue()
        assertThat(notice.shouldShow(backend(context = "dac:a"), null)).isFalse()
    }

    @Test
    fun `a calibrated DAC, another backend or a DAC that cannot be moved is left alone`() {
        val notice = SafetyCalibrationNotice()

        assertThat(notice.shouldShow(backend(), calibration)).isFalse()
        assertThat(notice.shouldShow(backend(kind = SafetyVolumeBackendKind.ANDROID_MEDIA), null)).isFalse()
        assertThat(notice.shouldShow(backend(controllable = false), null)).isFalse()
        assertThat(notice.shouldShow(backend(context = null), null)).isFalse()
        // None of those spent the one notice: the DAC is still told when it qualifies.
        assertThat(notice.shouldShow(backend(), null)).isTrue()
    }

    @Test
    fun `the DAC opened log names the guard's answer and Safety's real ceiling apart`() {
        val device = File("../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioDevice.kt")
            .readText().replace("\r\n", "\n")
        val opened = device.substringAfter("Log.i(TAG, \"DAC opened key=\$openDeviceKey current=").substringBefore("if (target != null")
        assertThat(opened).contains("guardTarget=")
        assertThat(opened).doesNotContain("ceiling=")

        val guard = File("../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/DacLevelGuard.kt")
            .readText().replace("\r\n", "\n")
        assertThat(guard).contains("guardTarget=")
        assertThat(guard).doesNotContain("ceiling=")

        val controller = File("src/main/java/com/lostf1sh/pixelplayeross/data/volume/SafetyVolumeController.kt")
            .readText().replace("\r\n", "\n")
        val log = controller.substringAfter("\"DAC opened context=").substringBefore("return target")
        assertThat(log).contains("safetyCeiling=")
        assertThat(log).contains("saved=")
    }

    @Test
    fun `the controller shows the notice in Direct, in both languages`() {
        val controller = File("src/main/java/com/lostf1sh/pixelplayeross/data/volume/SafetyVolumeController.kt")
            .readText().replace("\r\n", "\n")
        val reconcile = controller.substringAfter("fun reconcile(").substringBefore("fun handleVolumeKey(")
        assertThat(reconcile).contains("calibrationNotice.shouldShow(backend, calibration)")
        assertThat(reconcile).contains("R.string.safety_max_calibrate_dac_notice")
        for (dir in listOf("values", "values-es")) {
            val strings = File("src/main/res/$dir/strings_safety_volume.xml").readText()
            assertThat(strings).contains("name=\"safety_max_calibrate_dac_notice\"")
        }
    }
}
