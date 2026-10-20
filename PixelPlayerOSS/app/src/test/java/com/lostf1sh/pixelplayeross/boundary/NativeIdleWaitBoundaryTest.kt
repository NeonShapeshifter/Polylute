package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-036: paused, the native decode thread looped on usleep(20000) (50 Hz) and the file I/O thread
 * on usleep(5000) (200 Hz) whenever its buffer was full or it had reached the end of the file — for
 * as long as the engine existed. Both now block on a condition until there is work.
 */
class NativeIdleWaitBoundaryTest {
    private val engine = File(
        "../../decent-player/libs/decent-usb-audio-driver/src/main/jni/native-audio-engine.cpp"
    ).readText().replace("\r\n", "\n")

    private fun section(from: String, to: String) = engine.substring(engine.indexOf(from), engine.indexOf(to))

    @Test
    fun `the paused decode loop waits on a condition, woken by resume, seek and stop`() {
        val pause = engine.substringAfter("// Handle pause").substringBefore("// Decode one FLAC frame")
        assertThat(pause).doesNotContain("usleep(20000)")
        assertThat(pause).contains("waitWhilePaused(engine)")
        for (jni in listOf("nativeResume(", "nativeSeek(", "nativeStop(", "nativeDestroy(")) {
            val body = engine.substringAfter("NativeAudioEngine_$jni").substringBefore("\n}\n")
            assertThat(body).contains("wakeDecodeThread(engine)")
        }
    }

    @Test
    fun `the I O thread sleeps on a condition when full or at end of file`() {
        val io = section("static void *ioLoop(void *arg)", "pthread_t ioThread_;")
        assertThat(io).doesNotContain("usleep(5000)")
        assertThat(io).contains("pthread_cond_timedwait(&ds->ioCond_")
        val readAt = section("ssize_t readAt(", "off64_t getLength()")
        assertThat(readAt).contains("pthread_cond_signal(&ioCond_)")
    }
}
