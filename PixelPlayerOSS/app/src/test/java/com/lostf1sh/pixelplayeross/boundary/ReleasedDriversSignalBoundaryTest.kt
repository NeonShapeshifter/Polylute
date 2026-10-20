package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-003 follow-up (fix2 regression): the hand-back reads the kernel driver of both interfaces and
 * that result reaches the route guard, which otherwise waits for a device event Android never sends.
 */
class ReleasedDriversSignalBoundaryTest {
    private val device = File(
        "../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioDevice.kt"
    ).readText()
    private val engine = File(
        "src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt"
    ).readText()

    @Test
    fun `releaseToAndroid reports both drivers after releasing`() {
        val start = device.indexOf("fun releaseToAndroid(")
        val body = device.substring(start, device.indexOf("\n    fun ", start + 1))
        assertThat(body).contains("nativeKernelDriverName(fd, ifno)")
        val reattach = body.indexOf("nativeReattachKernelDriverIfUnbound")
        val report = body.indexOf("releasedDriversListener?.invoke(controlDriver, streamingDriver)")
        assertThat(reattach).isAtLeast(0)
        assertThat(report).isGreaterThan(reattach)
    }

    @Test
    fun `engine feeds the drivers to the route signal and re-evaluates the guard`() {
        assertThat(engine).contains("releasedDriversListener = { control, streaming ->")
        assertThat(engine).contains("usbRouteSignal.onKernelDriversAfterRelease(control, streaming)")
    }
}
