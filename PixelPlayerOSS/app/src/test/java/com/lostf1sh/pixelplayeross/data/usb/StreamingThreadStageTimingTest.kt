package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Test

/**
 * AUD-111: the EQ took ~1.5-2 s to be heard. The stage ran when a buffer was *queued*, and the
 * queue holds up to ~16 buffers of 4096 frames (~1.5 s at 44.1 kHz), so everything already queued had
 * been equalised with the old settings. It now runs when the buffer is *taken* to be written, so a
 * change is heard from the next buffer that leaves the queue.
 */
class StreamingThreadStageTimingTest {

    private class FakeWriter : UsbStreamingThread.PcmWriter {
        val written = CopyOnWriteArrayList<FloatArray>()
        override fun write(pcmBuffer: FloatArray) { written += pcmBuffer.copyOf() }
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) = Unit
        override val framesWritten: Long get() = written.size.toLong()
        override fun flush() = Unit
        override fun drainEndOfStream(): Boolean = true
    }

    @Test
    fun `a settings change applies to buffers already queued, not only to new ones`() {
        val writer = FakeWriter()
        val thread = UsbStreamingThread(writer, initiallyPaused = true)
        thread.start()
        val gain = java.util.concurrent.atomic.AtomicReference(1f)
        val stage: (FloatArray) -> Unit = { data -> for (i in data.indices) data[i] *= gain.get() }
        try {
            repeat(3) { thread.enqueue(FloatArray(4) { 0.5f }, processAtWrite = stage) }
            gain.set(2f)                     // the listener moves the slider while they wait
            thread.resumeStreaming()
            val deadline = System.currentTimeMillis() + 2_000
            while (writer.written.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        } finally {
            thread.stop()
        }
        assertThat(writer.written).hasSize(3)
        writer.written.forEach { buffer -> buffer.forEach { assertThat(it).isEqualTo(1.0f) } }
    }
}
