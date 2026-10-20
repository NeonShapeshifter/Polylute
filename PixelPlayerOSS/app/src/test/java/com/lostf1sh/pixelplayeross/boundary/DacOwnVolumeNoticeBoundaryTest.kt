package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-119: until the DAC's front-panel volume is understood, the app must say plainly that it
 * neither reflects nor limits it — in the volume control and in Safety Max.
 */
class DacOwnVolumeNoticeBoundaryTest {
    private val main = File("src/main")

    private fun read(path: String) = File(main, path).readText()

    @Test
    fun `notice exists in both languages`() {
        for (dir in listOf("values", "values-es")) {
            assertThat(read("res/$dir/strings_dap_control_center.xml")).contains("name=\"dap_volume_dac_own_not_reflected\"")
            assertThat(read("res/$dir/strings_safety_volume.xml")).contains("name=\"safety_max_dac_own_volume_note\"")
        }
    }

    @Test
    fun `volume block and Safety Max screen show it`() {
        assertThat(read("java/com/lostf1sh/pixelplayeross/presentation/screens/DapDeviceControlComponents.kt"))
            .contains("R.string.dap_volume_dac_own_not_reflected")
        assertThat(read("java/com/lostf1sh/pixelplayeross/presentation/screens/HeadphoneProfileScreens.kt"))
            .contains("R.string.safety_max_dac_own_volume_note")
    }

    /**
     * AUD-119 conclusion (device dump + owner): the Melody's own volume is internal, not exposed over
     * USB (one FU id=10 ch1/ch2, no interrupt endpoint). Safety Max is only valid for the internal
     * level it was calibrated at, so calibration recommends the worst case first.
     */
    @Test
    fun `calibration recommends the DAC's own volume at maximum, and the DAC card says it is not readable`() {
        for (dir in listOf("values", "values-es")) {
            assertThat(read("res/$dir/strings_safety_volume.xml")).contains("name=\"safety_max_calibrate_dac_at_max\"")
            assertThat(read("res/$dir/strings_dap_control_center.xml")).contains("name=\"dac_own_volume_not_controllable\"")
        }
        val safety = read("java/com/lostf1sh/pixelplayeross/presentation/screens/HeadphoneProfileScreens.kt")
        val advice = safety.indexOf("R.string.safety_max_calibrate_dac_at_max")
        assertThat(advice).isAtLeast(0)
        assertThat(advice).isLessThan(safety.indexOf("R.string.safety_max_use_current"))
        assertThat(read("java/com/lostf1sh/pixelplayeross/presentation/screens/DacProfileScreens.kt"))
            .contains("R.string.dac_own_volume_not_controllable")
    }
}
