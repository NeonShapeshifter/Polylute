package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** AUD-119: the diagnostic dump is reachable from the debug bridge and never writes to the DAC. */
class DacVolumeDumpBoundaryTest {
    private val device = File(
        "../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioDevice.kt"
    ).readText()

    private fun dumpBody(): String {
        val start = device.indexOf("fun dumpVolumeControls(")
        val end = device.indexOf("\n    }", start)
        return device.substring(start, end)
    }

    @Test
    fun `debug bridge action is declared and dispatched`() {
        val manifest = File("src/debug/AndroidManifest.xml").readText()
        val receiver = File("src/debug/java/com/lostf1sh/pixelplayeross/dap/admin/DapDebugPolicyReceiver.kt").readText()
        assertThat(manifest).contains("com.lostf1sh.pixelplayeross.debug.DUMP_DAC_VOLUME_CONTROLS")
        assertThat(receiver).contains("ACTION_DUMP_DAC_VOLUME_CONTROLS ->")
        assertThat(receiver).contains(".dumpVolumeControls()")
    }

    @Test
    fun `dump reads every feature unit and only issues GET_CUR`() {
        val body = dumpBody()
        assertThat(body).contains("UacFeatureUnitParser.describeAll(raw)")
        assertThat(body).contains("UacFeatureUnitParser.audioControlInterruptEndpoint(raw)")
        assertThat(body).contains("0xA1, 0x01,")
        assertThat(body).doesNotContain("0x21")
        assertThat(body).doesNotContain("writeHardwareVolume")
    }
}
