package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.DirectPathTelemetry
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

class B635UnderrunTest {
    private class Writer(private val beforeEos: () -> Unit = {}) : UsbStreamingThread.PcmWriter {
        val written = CountDownLatch(1)
        val drained = CountDownLatch(1)
        override fun write(pcmBuffer: FloatArray) { written.countDown() }
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) { written.countDown() }
        override val framesWritten: Long get() = 32L
        override fun flush() = Unit
        override fun drainEndOfStream(): Boolean { drained.countDown(); beforeEos(); return true }
    }
    @Test
    fun `polls after EOS do not count as underruns`() {
        val writer = Writer()
        val thread = UsbStreamingThread(writer)
        thread.enqueue(FloatArray(64))
        thread.queueEndOfStream()
        thread.start()
        try {
            assertThat(writer.drained.await(3, TimeUnit.SECONDS)).isTrue()
            val before = DirectPathTelemetry.underruns.get()
            Thread.sleep(350)
            assertThat(DirectPathTelemetry.underruns.get()).isEqualTo(before)
        } finally { thread.stop() }
    }
    @Test
    fun `poll before any audio is startup rather than an underrun`() {
        val before = DirectPathTelemetry.underruns.get()
        val thread = UsbStreamingThread(Writer())
        thread.start()
        try {
            Thread.sleep(350)
            assertThat(DirectPathTelemetry.underruns.get()).isEqualTo(before)
        } finally { thread.stop() }
    }
    @Test
    fun `pause during a poll does not count its timeout`() {
        val writer = Writer()
        val thread = UsbStreamingThread(writer)
        thread.enqueue(FloatArray(64))
        thread.start()
        try {
            assertThat(writer.written.await(3, TimeUnit.SECONDS)).isTrue()
            thread.pauseStreaming()
            val before = DirectPathTelemetry.underruns.get()
            Thread.sleep(350)
            assertThat(DirectPathTelemetry.underruns.get()).isEqualTo(before)
        } finally { thread.stop() }
    }
    @Test
    fun `an old EOS in flight does not suppress underruns after a seek`() {
        val releaseOldEos = CountDownLatch(1)
        val newWrite = CountDownLatch(1)
        val writer = Writer { releaseOldEos.await(3, TimeUnit.SECONDS) }
        val thread = UsbStreamingThread(writer)
        thread.enqueue(FloatArray(64))
        thread.queueEndOfStream()
        thread.start()
        try {
            assertThat(writer.drained.await(3, TimeUnit.SECONDS)).isTrue()
            thread.flush()
            thread.enqueue(FloatArray(64)) { newWrite.countDown() }
            val before = DirectPathTelemetry.underruns.get()
            releaseOldEos.countDown()
            assertThat(newWrite.await(3, TimeUnit.SECONDS)).isTrue()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (DirectPathTelemetry.underruns.get() == before && System.nanoTime() < deadline) Thread.sleep(5)
            assertThat(DirectPathTelemetry.underruns.get()).isGreaterThan(before)
        } finally { releaseOldEos.countDown(); thread.stop() }
    }

}
