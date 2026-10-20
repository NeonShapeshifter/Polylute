package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-015 (what fix2's AUD-115 left): the item transition, on the main thread, still destroyed a
 * finished engine and could re-open the USB stream (deferred config) while the playback thread
 * configured; and the JNI wrappers read non-volatile handles with no guard against a concurrent
 * destroy — a seek or diagnostic read from the main thread could hand native code a freed struct.
 */
class NativeLifecycleOwnershipBoundaryTest {
    private val libs = "../../decent-player/libs"
    private val engine = File("$libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/NativeAudioEngine.kt")
        .readText().replace("\r\n", "\n")
    private val stream = File("$libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioStream.kt")
        .readText().replace("\r\n", "\n")
    private val sink = File("$libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt")
        .readText().replace("\r\n", "\n")

    private fun fn(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        return source.substring(start, source.indexOf("\n    }\n", start))
    }

    @Test
    fun `the engine handle is volatile and never used while destroy frees it`() {
        assertThat(engine).contains("@Volatile\n    private var handle: Long = 0L")
        assertThat(fn(engine, "fun seek(")).contains("synchronized(handleLock)")
        assertThat(fn(engine, "fun getPositionUs(")).contains("synchronized(handleLock)")
        assertThat(engine).contains("val isRunning: Boolean get() = synchronized(handleLock)")
        val destroy = fn(engine, "fun destroy(")
        val swap = destroy.indexOf("synchronized(handleLock)")
        assertThat(swap).isAtLeast(0)
        assertThat(destroy.indexOf("nativeDestroy(")).isGreaterThan(swap)
    }

    @Test
    fun `the stream handle is volatile and cross-thread reads are fenced from release`() {
        assertThat(stream).contains("@Volatile\n    var nativeHandle: Long = 0L")
        assertThat(fn(stream, "val urbDiag: String")).contains("synchronized(handleLock)")
        val release = fn(stream, "fun release(")
        assertThat(release).contains("synchronized(handleLock)")
    }

    @Test
    fun `the item transition no longer destroys the engine on the main thread`() {
        val listener = fn(sink, "override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int)")
        assertThat(listener).doesNotContain("val engineFinished = cleanupFinishedEngine()")
        assertThat(listener).contains("post { cleanUpFinishedEngineForItem() }")
        assertThat(sink).contains("@Volatile\n    private var nativeEngine: NativeAudioEngine? = null")
    }
}
