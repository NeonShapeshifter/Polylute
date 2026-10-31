package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.common.C
import com.decent.usbaudio.UsbTransportPlanner
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B46-B2: the producer queue's limits are durations, not numbers of buffers.
 *
 * Until B45 the sink told Media3 to retry at 16 queued buffers and the thread dropped at 128. A buffer is a FLAC block, so those were 1.5 s
 * and 12 s at 44.1 kHz with 4096-frame blocks but 0.17 s and 1.4 s at 384 kHz: nine times less headroom exactly where the data rate is highest.
 * The plan (usb-pcm-packetizer.h) now says 1440 ms and 11 900 ms and gives them in frames of the stream's rate; the thread counts frames.
 * The thread here is never started, so nothing consumes the queue: what it accepts before it says "full" is the headroom Media3 sees.
 */
class UsbStreamingQueueHeadroomTest {

    private class NullWriter : UsbStreamingThread.PcmWriter {
        override fun write(pcmBuffer: FloatArray) = Unit
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) = Unit
        override val framesWritten: Long get() = 0
        override fun flush() = Unit
        override fun drainEndOfStream(): Boolean = true
    }

    private val rates = listOf(44_100, 48_000, 96_000, 192_000, 352_800, 384_000)
    private val bufferLengths = listOf(1024, 4096, 16_384)   // frames: a short block, the common FLAC block, a long one

    private fun threadFor(rate: Int): UsbStreamingThread {
        val plan = UsbTransportPlanner.plan(
            sampleRateHz = rate, channels = 2, containerBytes = 4, sourceBits = null, maxPacketBytes = 3072, bInterval = 1,
            hasFeedback = true, feedbackBytes = 4, busSpeed = 3,
        )
        return UsbStreamingThread(
            NullWriter(), initiallyPaused = true, channelCount = 2,
            queueSoftFrames = plan.queueSoftFrames, queueHardFrames = plan.queueHardFrames,
        )
    }

    /** How many [bufferFrames]-frame buffers a Media3 that stops at the soft limit gets in. */
    private fun buffersAcceptedBeforeSoftLimit(rate: Int, bufferFrames: Int): Int {
        val thread = threadFor(rate)
        val buffer = ByteArray(bufferFrames * 2 * 4)   // stereo, 32 bit
        var accepted = 0
        while (!thread.atSoftLimit()) {
            thread.enqueueRaw(buffer, C.ENCODING_PCM_32BIT)
            accepted++
        }
        return accepted
    }

    @Test
    fun `the soft limit is the same headroom in milliseconds at every rate and for every buffer length`() {
        for (rate in rates) for (bufferFrames in bufferLengths) {
            val accepted = buffersAcceptedBeforeSoftLimit(rate, bufferFrames)
            val headroomMs = accepted.toDouble() * bufferFrames * 1000 / rate
            val bufferMs = bufferFrames * 1000.0 / rate
            // at least the limit, and no more than the one buffer that crossed it
            assertThat(headroomMs).isAtLeast(1440.0 - 0.001)
            assertThat(headroomMs).isLessThan(1440.0 + bufferMs)
        }
    }

    @Test
    fun `the Melody at 44_1 and 48 kHz queues the buffers it queued before, to within one`() {
        // 4096-frame blocks, the reference: 16 buffers before (count rule), 16 at 44.1 kHz and 17 at 48 kHz now
        assertThat(buffersAcceptedBeforeSoftLimit(44_100, 4096)).isEqualTo(16)
        assertThat(buffersAcceptedBeforeSoftLimit(48_000, 4096)).isEqualTo(17)
    }

    @Test
    fun `where the count rule left 171 ms at 384 kHz the duration rule leaves the same 1_4 s as at 44_1`() {
        val countRuleMs = 16 * 4096 * 1000.0 / 384_000                      // the old limit at 384 kHz with 4096-frame blocks
        assertThat(countRuleMs).isWithin(0.5).of(170.7)
        val now = buffersAcceptedBeforeSoftLimit(384_000, 4096) * 4096 * 1000.0 / 384_000
        assertThat(now).isAtLeast(1440.0)
        assertThat(now / countRuleMs).isGreaterThan(8.0)                    // the nine-fold headroom the count rule lacked
    }

    @Test
    fun `the hard limit bounds the queue in time and so in bytes, and drops the oldest`() {
        val rate = 384_000
        val bufferFrames = 16_384
        val thread = threadFor(rate)
        val hardFrames = rate * 11_900L / 1000
        val buffer = ByteArray(bufferFrames * 2 * 4)
        repeat(400) { thread.enqueueRaw(buffer, C.ENCODING_PCM_32BIT) }       // a producer that ignores the soft limit
        val queued = thread.queuedFrames()
        assertThat(queued).isAtMost(hardFrames)
        assertThat(queued).isGreaterThan(hardFrames - bufferFrames)           // it dropped only as many as it had to
        // the worst case in bytes at the highest rate: 11.9 s x 384 000 frames/s x 8 bytes (stereo, 32 bit) = 36.5 MB
        assertThat(queued * 8).isAtMost(36_556_800L)
        assertThat(hardFrames * 8).isEqualTo(36_556_800L)
    }

    @Test
    fun `a pre-roll queued past the soft limit is never refused, only the hard limit applies`() {
        val rate = 192_000
        val thread = threadFor(rate)
        val silence = ByteArray(8192 * 2 * 2)                                // the pre-roll's blocks: 16-bit zeros
        repeat(24) { thread.enqueueRaw(silence, C.ENCODING_PCM_16BIT) }     // 1 s at 192 kHz
        assertThat(thread.atSoftLimit()).isFalse()                           // 1 s is under the 1.44 s soft limit: Media3 still gets in
        assertThat(thread.queuedFrames()).isEqualTo(24L * 8192)
    }
}
