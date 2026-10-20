package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-013 / AUD-056 wiring: the decode loop records why it stopped, a failed submit stops it (it
 * used to keep decoding to EOS at CPU speed), a read timeout is an error rather than end of file,
 * and the sink resolves a stopped engine through [NativeEngineEndPolicy].
 */
class NativeEngineExitBoundaryTest {
    private val libs = "../../decent-player/libs"
    private val engine = File("$libs/decent-usb-audio-driver/src/main/jni/native-audio-engine.cpp")
        .readText().replace("\r\n", "\n")
    private val sink = File("$libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt")
        .readText().replace("\r\n", "\n")
    private val wrapper = File("$libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/NativeAudioEngine.kt")
        .readText().replace("\r\n", "\n")

    private fun decodeLoop(): String {
        val start = engine.indexOf("static void *decodeThreadFunc(")
        return engine.substring(start, engine.indexOf("\n}\n", start))
    }

    @Test
    fun `a failed submit stops the loop as a USB error`() {
        val loop = decodeLoop()
        assertThat(loop).contains("if (!submitPcmToUrbs(engine->usbCtx, usbData, usbBytes)) {")
        assertThat(loop).contains("EngineExit::USB_ERROR")
        assertThat(loop).contains("EngineExit::END_OF_STREAM")
        assertThat(loop).contains("EngineExit::DECODE_ERROR")
        assertThat(engine).contains("nativeGetExitReason")
        assertThat(wrapper).contains("val exitReason: NativeEngineExit")
    }

    @Test
    fun `a read timeout is an error, not end of file`() {
        val readAt = engine.substring(engine.indexOf("ssize_t readAt("), engine.indexOf("off64_t getLength()"))
        val timeout = readAt.substring(readAt.indexOf("readAt: timeout waiting for data"))
        assertThat(timeout.substring(0, timeout.indexOf("}"))).contains("return -1;")
    }

    @Test
    fun `the sink resolves a stopped engine through the end policy`() {
        assertThat(sink).contains("val exit = engine.exitReason")
        assertThat(sink).contains("NativeEngineEndPolicy.resolve(exit)")
        val isEnded = sink.substring(sink.indexOf("override fun isEnded()"), sink.indexOf("override fun hasPendingData()"))
        assertThat(isEnded).contains("NativeEngineEndPolicy.reportsEnded(engine.exitReason)")
    }
}
