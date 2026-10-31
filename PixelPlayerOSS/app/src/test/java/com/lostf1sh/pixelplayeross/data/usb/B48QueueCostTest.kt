package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.common.C
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B48-3: what the producer pays per buffer does not grow with how much is queued. `atSoftLimit()` and the hard-limit check of every offer used to add the whole queue up under its
 * locks (O(n) per buffer); now they read a running count. Written against the API that exists at HEAD, so the same file runs there: a ratio of the cost at 20 000 queued buffers to
 * the cost at 100 (about 1 for a running count, a couple of hundred for a walk of the queue), which does not depend on how fast the machine is.
 */
class B48QueueCostTest {

    private val encoding = C.ENCODING_PCM_16BIT
    private val buffer = ByteArray(64 * 2 * 2)

    private fun medianOf(runs: Int, block: () -> Double): Double = (1..runs).map { block() }.sorted()[runs / 2]

    private fun newThread() = UsbStreamingThread(
        object : UsbStreamingThread.PcmWriter {
            override fun write(pcmBuffer: FloatArray) {}
            override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) {}
            override val framesWritten: Long get() = 0L
            override fun flush() {}
            override fun drainEndOfStream(): Boolean = true
        },
        initiallyPaused = true,
        channelCount = 2,
        queueSoftFrames = Long.MAX_VALUE,
        queueHardFrames = Long.MAX_VALUE,
    )

    /** Nanoseconds per call of [work], with [queued] buffers waiting in a thread that never writes them. */
    private fun costWithQueue(queued: Int, calls: Int, work: (UsbStreamingThread) -> Unit): Double {
        val thread = newThread()
        thread.start()
        try {
            repeat(queued) { thread.enqueueRaw(buffer, encoding) }
            repeat(300) { work(thread) }                                    // warm-up
            val startedNs = System.nanoTime()
            repeat(calls) { work(thread) }
            return (System.nanoTime() - startedNs).toDouble() / calls
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `asking the soft limit costs the same with a long queue as with a short one`() {
        val short = medianOf(5) { costWithQueue(100, 4_000) { it.atSoftLimit() } }
        val long = medianOf(5) { costWithQueue(20_000, 4_000) { it.atSoftLimit() } }
        println("B48 cost atSoftLimit: ${short.toLong()} ns at 100 queued, ${long.toLong()} ns at 20000 queued, ratio ${"%.1f".format(long / short)}")
        assertThat(long / short).isLessThan(8.0)
    }

    @Test
    fun `offering a buffer costs the same with a long queue as with a short one`() {
        val short = medianOf(5) { costWithQueue(100, 1_000) { it.enqueueRaw(buffer, encoding) } }
        val long = medianOf(5) { costWithQueue(20_000, 1_000) { it.enqueueRaw(buffer, encoding) } }
        println("B48 cost enqueueRaw: ${short.toLong()} ns at 100 queued, ${long.toLong()} ns at 20000 queued, ratio ${"%.1f".format(long / short)}")
        assertThat(long / short).isLessThan(8.0)
    }

    @Test
    fun `the frames still queued are asked for at the same cost`() {
        val short = medianOf(5) { costWithQueue(100, 4_000) { it.queuedFrames() } }
        val long = medianOf(5) { costWithQueue(20_000, 4_000) { it.queuedFrames() } }
        println("B48 cost queuedFrames: ${short.toLong()} ns at 100 queued, ${long.toLong()} ns at 20000 queued, ratio ${"%.1f".format(long / short)}")
        assertThat(long / short).isLessThan(8.0)
    }
}
