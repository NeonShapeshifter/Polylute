package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-036: paused, the streaming thread woke every 50 ms (`Thread.sleep(50)`) for as long as the
 * pause lasted — hours with the screen off. It now waits until it is resumed or stopped.
 */
class StreamingThreadPauseTest {

    private class NullWriter : UsbStreamingThread.PcmWriter {
        override fun write(pcmBuffer: FloatArray) = Unit
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) = Unit
        override val framesWritten: Long get() = 0
        override fun flush() = Unit
        override fun drainEndOfStream(): Boolean = true
    }

    private fun streamingThread(): Thread? =
        Thread.getAllStackTraces().keys.firstOrNull { it.name == "UsbStreamingThread" && it.isAlive }

    @Test
    fun `a paused thread waits instead of polling, and resume and stop wake it`() {
        val thread = UsbStreamingThread(NullWriter(), initiallyPaused = true)
        thread.start()
        try {
            var worker: Thread? = null
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline) {
                worker = streamingThread()
                if (worker?.state == Thread.State.WAITING) break
                Thread.sleep(5)
            }
            // A timed sleep shows as TIMED_WAITING; an untimed wait for resume/stop as WAITING.
            assertThat(worker?.state).isEqualTo(Thread.State.WAITING)
            thread.resumeStreaming()
            val resumed = System.currentTimeMillis() + 2_000
            while (worker?.state == Thread.State.WAITING && System.currentTimeMillis() < resumed) Thread.sleep(2)
            assertThat(worker?.state).isNotEqualTo(Thread.State.WAITING)
            thread.pauseStreaming()
        } finally {
            thread.stop()
        }
        val stopped = System.currentTimeMillis() + 2_000
        while (streamingThread() != null && System.currentTimeMillis() < stopped) Thread.sleep(5)
        assertThat(streamingThread()).isNull()
    }
}
