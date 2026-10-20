package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.common.C
import com.decent.usbaudio.media3.PipelinePositionClock
import com.decent.usbaudio.media3.RateSwitchPreroll
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Test

/**
 * B12a-4: silence ahead of an item's first frame after a sample-rate change (the DAC may mute while it relocks
 * its clock: "some songs eat their first second"). Default 0 — nothing changes. What is pinned here:
 *
 * - it is inserted **only at a rate change**, never at a same-rate join, and only for the values the bridge allows;
 * - **the item's frames are complete** afterwards: the silence is extra frames in front, never in place of any;
 * - **the position does not shift**: the clock marks the item's own first timestamp when its first frame is
 *   written, whatever part of the silence had already gone out when the first buffer arrived.
 */
class RateSwitchPrerollTest {

    // ── when ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `only 0, 250, 500 and 1000 ms exist, and anything else is no pre-roll`() {
        assertThat(RateSwitchPreroll.ALLOWED_MS).containsExactly(0, 250, 500, 1000).inOrder()
        for (ok in listOf(0, 250, 500, 1000)) assertThat(RateSwitchPreroll.normalized(ok)).isEqualTo(ok)
        for (bad in listOf(-1, 1, 100, 249, 251, 750, 1001, 5000, 60_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertThat(RateSwitchPreroll.normalized(bad)).isEqualTo(0)
            assertThat(RateSwitchPreroll.isAllowed(bad)).isFalse()
        }
    }

    @Test
    fun `a rate change owes the pre-roll and a same-rate stream never does`() {
        val rates = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
        for (previous in rates) for (new in rates) for (ms in listOf(250, 500, 1000)) {
            val owed = RateSwitchPreroll.msFor(ms, previous, new)
            if (previous == new) {
                assertThat(owed).isEqualTo(0)                // a same-rate rebuild (another depth, say) is not a rate change
                assertThat(RateSwitchPreroll.isRateSwitch(previous, new)).isFalse()
            } else {
                assertThat(owed).isEqualTo(ms)
            }
        }
    }

    @Test
    fun `the first stream after a start counts as a rate change - the DAC relocks from its idle clock`() {
        assertThat(RateSwitchPreroll.msFor(500, previousRateHz = 0, newRateHz = 44_100)).isEqualTo(500)
    }

    @Test
    fun `with the default setting nothing is ever owed, at any rate change`() {
        for (previous in listOf(0, 44_100, 96_000)) for (new in listOf(44_100, 48_000, 96_000, 192_000)) {
            assertThat(RateSwitchPreroll.msFor(0, previous, new)).isEqualTo(0)
        }
    }

    @Test
    fun `a stream with no rate is never a rate change`() {
        assertThat(RateSwitchPreroll.isRateSwitch(44_100, 0)).isFalse()
        assertThat(RateSwitchPreroll.msFor(500, 44_100, 0)).isEqualTo(0)
    }

    // ── how much ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the silence is exactly the requested time at the stream's rate, in whole buffers`() {
        for (rate in listOf(44_100, 48_000, 96_000, 192_000)) for (ms in listOf(250, 500, 1000)) {
            val frames = RateSwitchPreroll.frames(ms, rate)
            assertThat(frames).isEqualTo(rate.toLong() * ms / 1000L)
            val chunks = RateSwitchPreroll.chunks(frames)
            assertThat(chunks.sumOf { it.toLong() }).isEqualTo(frames)
            assertThat(chunks.all { it in 1..RateSwitchPreroll.CHUNK_FRAMES }).isTrue()
            assertThat(chunks.size).isAtMost(24)             // within the sink's queue and its backpressure
        }
        assertThat(RateSwitchPreroll.frames(0, 44_100)).isEqualTo(0L)
        assertThat(RateSwitchPreroll.chunks(0L)).isEmpty()
    }

    // ── the item's frames are complete ──────────────────────────────────────────────────────────

    private val encoding = C.ENCODING_PCM_24BIT
    private val channels = 2
    private val framesPerBuffer = 4096
    private val bytesPerBuffer = framesPerBuffer * channels * 3

    private class RecordingWriter : UsbStreamingThread.PcmWriter {
        class Write(val bytes: ByteArray, val encoding: Int)
        val writes = CopyOnWriteArrayList<Write>()
        override fun write(pcmBuffer: FloatArray) = Unit
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) { writes += Write(pcmBuffer, encoding) }
        override val framesWritten: Long get() = writes.sumOf { it.bytes.size.toLong() / ((if (it.encoding == C.ENCODING_PCM_16BIT) 2 else 3) * 2) }
        override fun flush() = Unit
        override fun drainEndOfStream(): Boolean = true
    }

    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(2)
    }

    @Test
    fun `the silence is queued ahead and every frame of the item follows it, none lost, none replaced`() {
        for (ms in listOf(250, 500, 1000)) {
            val writer = RecordingWriter()
            val thread = UsbStreamingThread(writer, initiallyPaused = false, channelCount = channels).also { it.start() }
            try {
                val rate = 48_000
                val silenceFrames = RateSwitchPreroll.enqueue(thread, ms, rate, channels)
                assertThat(silenceFrames).isEqualTo(rate.toLong() * ms / 1000L)
                // the item's buffers, each tagged so its place in the stream is readable
                val item = (1..6).map { tag -> ByteArray(bytesPerBuffer) { tag.toByte() } }
                item.forEach { thread.enqueueRaw(it, encoding) }
                val silenceBuffers = RateSwitchPreroll.chunks(silenceFrames).size
                waitUntil { writer.writes.size == silenceBuffers + item.size }

                val writes = writer.writes.toList()
                assertThat(writes).hasSize(silenceBuffers + item.size)
                // first: only zeros, in 16-bit, adding up to exactly the pre-roll
                val silence = writes.take(silenceBuffers)
                assertThat(silence.all { it.encoding == RateSwitchPreroll.ENCODING }).isTrue()
                assertThat(silence.all { w -> w.bytes.all { it == 0.toByte() } }).isTrue()
                assertThat(silence.sumOf { it.bytes.size.toLong() / (2 * channels) }).isEqualTo(silenceFrames)
                // then: the item, byte for byte and in order
                val after = writes.drop(silenceBuffers)
                assertThat(after.map { it.encoding }.toSet()).containsExactly(encoding)
                assertThat(after.map { it.bytes.toList() }).containsExactlyElementsIn(item.map { it.toList() }).inOrder()
                assertThat(after.sumOf { it.bytes.size.toLong() / (3 * channels) }).isEqualTo(6L * framesPerBuffer)
            } finally {
                thread.stop()
            }
        }
    }

    @Test
    fun `with no pre-roll nothing is queued and the item is exactly what it was`() {
        val writer = RecordingWriter()
        val thread = UsbStreamingThread(writer, initiallyPaused = false, channelCount = channels).also { it.start() }
        try {
            assertThat(RateSwitchPreroll.enqueue(thread, 0, 48_000, channels)).isEqualTo(0L)
            val item = (1..3).map { tag -> ByteArray(bytesPerBuffer) { tag.toByte() } }
            item.forEach { thread.enqueueRaw(it, encoding) }
            waitUntil { writer.writes.size == 3 }
            assertThat(writer.writes.map { it.bytes.toList() }).containsExactlyElementsIn(item.map { it.toList() }).inOrder()
        } finally {
            thread.stop()
        }
    }

    // ── the position does not shift ─────────────────────────────────────────────────────────────

    /**
     * The sink latches the pipeline clock on the first buffer with the queue lead (AUD-143): the clock starts that
     * much earlier and reaches the buffer's own timestamp exactly when its first frame is written. The silence is
     * queue lead like any other audio ahead of the buffer, whatever part of it had already gone out.
     */
    @Test
    fun `the clock marks the item's first timestamp when its first frame is written, whatever part of the silence is gone`() {
        val rate = 48_000
        val presentationUs = 1_000_000_000_000L          // the renderer's domain
        for (ms in listOf(250, 500, 1000)) {
            val silence = RateSwitchPreroll.frames(ms, rate)
            for (alreadyWritten in listOf(0L, silence / 3, silence / 2, silence)) {
                val clock = PipelinePositionClock()
                val framesWrittenAtAccept = 12_345L + alreadyWritten     // the stream's cumulative count
                val leadUs = (silence - alreadyWritten) * 1_000_000L / rate
                assertThat(clock.accept(presentationUs, framesWrittenAtAccept, leadUs)).isTrue()
                // the item's first frame is written once the rest of the silence has been
                val atFirstFrame = clock.positionUs(framesWrittenAtAccept + (silence - alreadyWritten), rate)
                assertThat(atFirstFrame).isWithin(1L).of(presentationUs)
                // before it the clock is behind the item's start, never ahead (nothing of the item is heard yet)
                if (silence - alreadyWritten > 0) {
                    assertThat(clock.positionUs(framesWrittenAtAccept + (silence - alreadyWritten) / 2, rate)).isLessThan(presentationUs)
                }
                // after it the position advances with the item's own frames
                assertThat(clock.positionUs(framesWrittenAtAccept + (silence - alreadyWritten) + rate, rate))
                    .isWithin(1L).of(presentationUs + 1_000_000L)
            }
        }
    }

    @Test
    fun `with no pre-roll the clock is the one it was`() {
        val clock = PipelinePositionClock()
        assertThat(clock.accept(5_000_000L, 777L, 0L)).isTrue()
        assertThat(clock.positionUs(777L + 48_000, 48_000)).isEqualTo(6_000_000L)
    }
}
