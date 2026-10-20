package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-118 at the driver and sink boundaries. Source-level: usbfs and Media3 cannot run on the JVM;
 * the decision itself is [com.decent.usbaudio.DirectOpenLevelPolicy], tested directly.
 */
class DirectOpenLevelBoundaryTest {

    private val libs = "../../decent-player/libs/"
    private val device = File("${libs}decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioDevice.kt").readText()
    private val sink = File("${libs}decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt").readText()

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced $signature")
    }

    @Test
    fun `every claim restores the saved Direct level before the device is handed out`() {
        val open = body(device, "fun openDevice(")
        assertThat(open).contains("restoreDirectLevelOnOpen(")
        assertThat(open.indexOf("restoreDirectLevelOnOpen(")).isLessThan(open.lastIndexOf("return info"))
    }

    @Test
    fun `the restore is keyed by the opening device and always logs current and target`() {
        val restore = body(device, "private fun restoreDirectLevelOnOpen(")
        assertThat(restore).contains("DirectOpenLevelPolicy.target(")
        assertThat(restore).contains("\"DAC opened")
        assertThat(restore).doesNotContain("uniqueConnectedDac")
        assertThat(body(device, "fun openDevice(")).contains("DirectOpenLevelPolicy.deviceKey(device.vendorId, device.productId")
    }

    @Test
    fun `a level that cannot be read or restored refuses Direct`() {
        assertThat(body(sink, "private fun configureUsbBitPerfect(")).contains("OpenLevelCheck.UNVERIFIED")
    }

    @Test
    fun `reads before the restore are not remembered as the Direct level`() {
        assertThat(body(device, "fun readHardwareVolume(")).contains("if (openLevelSettled)")
    }
}
