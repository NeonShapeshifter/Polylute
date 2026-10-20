package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-003: the forced claim detaches `snd-usb-audio`, and the app never gave the DAC back — a normal
 * end of Direct kept the device open and claimed ("device kept open"), and `closeDevice` released
 * only the streaming interface. Every policy exit to the Android path then played to the speaker or a
 * dead route. Leaving Direct now releases both interfaces, re-binds the kernel driver and closes.
 *
 * Source-level because neither the engine nor the driver can run on the JVM (ExoPlayer, usbfs).
 */
class UsbClaimReleaseBoundaryTest {

    private val engine = File(
        "src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt"
    ).readText()
    private val driver = File(
        "../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioDevice.kt"
    ).readText()

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
    fun `rebuilding for the Android path hands the DAC back before building the player`() {
        val rebuild = body(engine, "private fun reassembleMasterPlayer(")
        val release = rebuild.indexOf("releaseToAndroid()")
        assertThat(release).isAtLeast(0)
        assertThat(release).isLessThan(rebuild.indexOf("playerA = buildPlayer("))
        assertThat(rebuild).containsMatch("""if \(desiredBackend != AudioBackend\.USB_DIRECT\)""")
    }

    @Test
    fun `releasing to Android frees both interfaces and re-binds the kernel driver before closing`() {
        val release = body(driver, "fun releaseToAndroid(")
        assertThat(release).contains("claimedControlInterface")
        assertThat(release).contains("nativeReattachKernelDriver")
        assertThat(release.indexOf("releaseInterface")).isLessThan(release.indexOf("nativeReattachKernelDriver"))
        assertThat(release.indexOf("nativeReattachKernelDriver")).isLessThan(release.indexOf("closeDevice()"))
    }

    @Test
    fun `closing also releases the AudioControl interface`() {
        assertThat(body(driver, "fun closeDevice(")).contains("claimedControlInterface")
    }
}
