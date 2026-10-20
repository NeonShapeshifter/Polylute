package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-003, measured on the HONOR: releasing the AudioControl interface first let `snd-usb-audio`
 * rebind to it at once — while the streaming interface was still claimed by usbfs — so the card was
 * created without a playback stream (`controlC1`, no `pcmC1D*p`). The follow-up CONNECT on the control
 * interface then failed with EBUSY. The streaming interface is released first, and CONNECT is only
 * issued for an interface that has no driver. Source-level: usbfs cannot run on the JVM.
 */
class UsbReleaseOrderBoundaryTest {

    private val driver = File(
        "../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioDevice.kt"
    ).readText()
    private val jni = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni/usb-audio-output.cpp").readText()
    private val engine = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt").readText()

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
    fun `the streaming interface is released before the control interface`() {
        val release = body(driver, "fun releaseToAndroid(")
        val streaming = release.indexOf("releaseInterface(streaming)")
        val control = release.indexOf("releaseInterface(control)")
        assertThat(streaming).isAtLeast(0)
        assertThat(control).isAtLeast(0)
        assertThat(streaming).isLessThan(control)
    }

    @Test
    fun `CONNECT is only issued to an interface without a driver`() {
        assertThat(body(driver, "fun releaseToAndroid(")).contains("nativeReattachKernelDriverIfUnbound")
        val native = jni.substringAfter("Java_com_decent_usbaudio_UsbAudioStream_nativeReattachKernelDriverIfUnbound(")
        assertThat(native).isNotEqualTo(jni)
        assertThat(native.indexOf("USBDEVFS_GETDRIVER")).isLessThan(native.indexOf("USBDEVFS_CONNECT"))
    }

    @Test
    fun `the guard asks for a real USB playback output, not Android's stale listing`() {
        val available = body(engine, "private fun androidUsbRouteAvailable(")
        assertThat(available).contains("UsbPlaybackProbe.evaluate(")
        // /proc/asound is SELinux-denied on the device: after a hand-back only a new USB output counts.
        assertThat(available).contains("usbRouteSignal.available(")
    }
}
