package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.DirectPathTelemetry
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

class B635UnderrunBurstTest {
    private class Writer(override val diagnosticSequence: Long) : UsbStreamingThread.PcmWriter {
        val twoTimeouts = CountDownLatch(2)
        val thirdTimeout = CountDownLatch(3)
        var afterSecond: (() -> Unit)? = null
        val notes = CopyOnWriteArrayList<Triple<Long, Int, Long>>()
        val summaries = CopyOnWriteArrayList<String>()
        override fun write(pcmBuffer: FloatArray) = Unit
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) = Unit
        override val framesWritten: Long get() = 32L
        override fun flush() = Unit
        override fun drainEndOfStream(): Boolean = true
        override fun noteQueueTimeout(total: Long, depth: Int, burst: Long) {
            notes += Triple(total, depth, burst)
            summaries += DirectPathTelemetry.streamUnderrunSummary
            if (notes.size == 2) afterSecond?.invoke()
            twoTimeouts.countDown()
            thirdTimeout.countDown()
        }
    }
    @Test
    fun `each stream starts its own delta and a new write ends an underrun burst`() {
        for (seq in listOf(101L, 102L)) {
            val writer = Writer(seq)
            val thread = UsbStreamingThread(writer)
            writer.afterSecond = { thread.enqueue(FloatArray(64)) }
            thread.enqueue(FloatArray(64))
            thread.start()
            try {
                assertThat(writer.twoTimeouts.await(3, TimeUnit.SECONDS)).isTrue()
                assertThat(writer.notes.take(2)).containsExactly(Triple(1L, 0, 1L), Triple(2L, 0, 2L)).inOrder()
                assertThat(writer.summaries.first()).contains("stream=$seq delta=1 total=1 burstCount=1 lastMonoNs=")
                assertThat(writer.summaries.first()).contains("depth=0 OUTPUT_GAP=unknown-until-submit")
                assertThat(writer.thirdTimeout.await(3, TimeUnit.SECONDS)).isTrue()
                assertThat(writer.notes[2]).isEqualTo(Triple(3L, 0, 1L))
            } finally { thread.stop() }
        }
    }
}
