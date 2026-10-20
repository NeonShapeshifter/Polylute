package com.lostf1sh.pixelplayeross.boundary

import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.NativeEnginePolicy
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-006 / 007 / 008 / 012 / 058 at the driver and sink boundaries, where the pure pieces
 * (`UacStreamingDescriptors`, `UsbPcmConversion`, `MonoUpmix`, the native conversion table) are
 * actually used. Source-level because `UsbAudioDevice` and `UsbAudioSink` need usbfs and Media3.
 */
class DirectFormatNegotiationBoundaryTest {

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
    fun `openDevice takes endpoint, maxPacket, alt and container from the selected alt`() {
        val open = body(device, "fun openDevice(")
        assertThat(open).contains("UacStreamingDescriptors.selectForPlayback(")
        assertThat(open).contains("maxPacketSize = selected.maxPacketBytes")
        assertThat(open).contains("endpointOut = selected.endpointOutAddress")
        assertThat(open).contains("selected.containerBits")
        // Chosen before anything is claimed.
        assertThat(open.indexOf("selectForPlayback(")).isLessThan(open.indexOf("claimInterface("))
    }

    @Test
    fun `alt settings are applied on the selected interface number`() {
        assertThat(body(device, "fun setAltSetting(")).contains("iface.id == selectedStreamingInterfaceNumber")
    }

    @Test
    fun `the sink decides the track's alt from descriptors before claiming the DAC`() {
        // R4-1: a format the DAC cannot carry must never touch ownership of the device. The rate
        // ranges are the one check that stays after the claim (a class GET RANGE on the AudioControl
        // interface, which usbfs only allows once the interface is claimed).
        val configure = body(sink, "private fun configureUsbBitPerfect(")
        val preflight = configure.indexOf("UacStreamingDescriptors.selectForFormat(")
        assertThat(preflight).isAtLeast(0)
        assertThat(preflight).isLessThan(configure.indexOf("usbAudioDevice.openDevice("))
        assertThat(configure).contains("usbAudioDevice.readPlaybackAlts(")
        // R4-3: the stream is built on the track's alt, not on the device-level one.
        assertThat(configure).contains("maxPacketSize = formatAlt.maxPacketBytes")
        assertThat(configure).contains("val altSetting = formatAlt.altSetting")
        assertThat(configure).contains("val bitDepth = formatAlt.containerBits")
        // The cached-stream shortcut also requires the source to fit the stream's container.
        assertThat(configure.substringBefore("UacStreamingDescriptors.selectForFormat("))
            .contains("&& cachedContainerFits")
    }

    @Test
    fun `reading the alts for the preflight never claims an interface`() {
        val read = body(device, "fun readPlaybackAlts(")
        assertThat(read).doesNotContain("claimInterface")
        assertThat(read).contains("UacStreamingDescriptors.parse(")
    }

    @Test
    fun `the engine latches a failure with the full source format`() {
        val engine = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt").readText()
        assertThat(engine).containsMatch(
            """(?s)directFailureLatch\.onFailure\(.{0,300}?diagnostics\.sourcePcmEncoding.{0,120}?diagnostics\.sourceChannelCount"""
        )
    }

    @Test
    fun `a mono source is streamed as stereo, duplicated, and never through the native engine`() {
        assertThat(sink).contains("upmixMonoSource = ch == 1")
        // The decision moved into NativeEnginePolicy (debug switch): the source now delegates, and the
        // invariant is pinned on the policy itself — for plan B on as well as off (B12a).
        assertThat(body(sink, "private fun nativeEngineAllowed(")).contains("upmixMonoSource")
        for (planB in listOf(false, true)) {
            assertThat(NativeEnginePolicy.allowed(DirectSignalMode.PRISTINE, 0, upmixMonoSource = true, debugForceNativeEngine = planB))
                .isFalse()
        }
        assertThat(body(sink, "override fun handleBuffer(")).contains("MonoUpmix.duplicate(")
    }
}
