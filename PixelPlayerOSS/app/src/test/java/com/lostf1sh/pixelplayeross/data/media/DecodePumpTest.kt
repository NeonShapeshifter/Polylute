package com.lostf1sh.pixelplayeross.data.media

import android.media.MediaCodec
import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B31 P2.5: the extractor → codec loop of the waveform decode, run against a fake codec that behaves like a real one in the one way that
 * matters here: it takes a few input buffers, and an output only appears after the caller has *waited* for the codec. The old loop
 * queued one input and waited for one output, every round; the count of waits is what the new loop must cut.
 */
class DecodePumpTest {
    private class FakeSource(val chunks: Int) : SamplePort {
        var next = 0
        override fun readSampleData(buffer: ByteBuffer): Int = if (next >= chunks) -1 else 4
        override val sampleTime: Long get() = next * 1000L
        override fun advance() { next++ }
    }

    /** [slots] input buffers; a queued input becomes an output only after a *waiting* poll of the output (the codec's turn). */
    private class FakeCodec(val slots: Int, val wedged: Boolean = false, val configFirst: Boolean = false, val formatFirst: Boolean = false) : DecoderPort {
        private val free = ArrayDeque((0 until slots).toList())
        private val pending = ArrayDeque<Triple<Int, Int, Int>>() // (sequence, flags, the input buffer it sits in)
        private val ready = ArrayDeque<Triple<Int, Int, Int>>() // (output index, sequence, flags)
        private var nextOutput = 100
        private var formatSent = !formatFirst
        private var configSent = !configFirst
        var waitingPolls = 0
        val released = ArrayList<Int>()
        private val bySlot = HashMap<Int, Int>()

        override fun dequeueInputBuffer(timeoutUs: Long): Int = free.removeFirstOrNull() ?: -1
        override fun inputBuffer(index: Int): ByteBuffer? = ByteBuffer.allocate(16)
        override fun queueInputBuffer(index: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            // The buffer stays taken until the codec has had its turn (a waiting poll of the output), as in a real codec.
            pending.addLast(Triple((presentationTimeUs / 1000L).toInt(), flags, index))
        }

        override fun dequeueOutputBuffer(info: PumpInfo, timeoutUs: Long): Int {
            if (!formatSent && timeoutUs > 0) { formatSent = true; waitingPolls++; return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED }
            if (ready.isEmpty() && timeoutUs > 0) {
                waitingPolls++
                if (!wedged) while (pending.isNotEmpty()) {
                    val (seq, flags, slot) = pending.removeFirst()
                    free.addLast(slot)
                    val index = nextOutput++
                    ready.addLast(Triple(index, seq, flags))
                    bySlot[index] = seq
                }
            }
            if (!configSent && ready.isNotEmpty()) {
                configSent = true
                val index = nextOutput++
                info.offset = 0; info.size = 4; info.flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                bySlot[index] = -1
                return index
            }
            val (index, seq, flags) = ready.removeFirstOrNull() ?: return MediaCodec.INFO_TRY_AGAIN_LATER
            info.offset = 0
            info.size = if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) 0 else 4
            info.flags = flags
            bySlot[index] = seq
            return index
        }

        override fun outputBuffer(index: Int): ByteBuffer? = ByteBuffer.allocate(4).also { it.putInt(0, bySlot[index] ?: -2) }
        override fun releaseOutputBuffer(index: Int) { released += index }
    }

    private fun run(chunks: Int, codec: FakeCodec): Pair<List<Int>, DecodePump> {
        val pump = DecodePump(codec, FakeSource(chunks))
        val seen = ArrayList<Int>()
        pump.run(ensureActive = {}, onFormatChanged = { seen += -100 }, onOutput = { buffer, _, _ -> seen += buffer.getInt(0) })
        return seen to pump
    }

    @Test
    fun `every chunk arrives once and in order, and the end of the stream ends the loop`() {
        val codec = FakeCodec(slots = 4)
        val (seen, pump) = run(chunks = 37, codec = codec)
        assertEquals((0 until 37).toList(), seen)
        assertEquals(38, pump.stats.inputs) // 37 samples and the end-of-stream marker
        assertEquals(codec.released.size, pump.stats.outputs)
    }

    @Test
    fun `the loop waits for the codec about once per few chunks, not once per chunk`() {
        val chunks = 400
        val codec = FakeCodec(slots = 4)
        val (seen, pump) = run(chunks, codec)
        assertEquals(chunks, seen.size)
        // One round trip per container frame was the old loop's cost; with four input buffers the new one needs about a quarter of them.
        assertTrue(pump.stats.waits <= chunks / 4 + 4, "waited ${pump.stats.waits} times for $chunks chunks")
        assertEquals(codec.waitingPolls, pump.stats.waits)
    }

    @Test
    fun `with one input buffer it still finishes, waiting once per chunk as the old loop did`() {
        val (seen, pump) = run(chunks = 50, codec = FakeCodec(slots = 1))
        assertEquals((0 until 50).toList(), seen)
        assertTrue(pump.stats.waits <= 52)
    }

    @Test
    fun `the format change is reported before the first buffer, and codec config buffers are released and not delivered`() {
        val codec = FakeCodec(slots = 2, configFirst = true, formatFirst = true)
        val (seen, pump) = run(chunks = 5, codec = codec)
        assertEquals(listOf(-100, 0, 1, 2, 3, 4), seen)
        // the config buffer and the end-of-stream buffer were handed back too
        assertEquals(pump.stats.outputs, codec.released.size)
    }

    @Test
    fun `a codec that never answers ends the decode instead of holding the thread`() {
        val codec = FakeCodec(slots = 2, wedged = true)
        val pump = DecodePump(codec, FakeSource(10))
        val failure = assertFailsWith<IllegalStateException> {
            pump.run(ensureActive = {}, onFormatChanged = {}, onOutput = { _, _, _ -> })
        }
        assertTrue(failure.message!!.contains("stalled"))
        assertTrue(codec.waitingPolls <= DecodePump.STALL_ROUNDS + 2)
    }

    @Test
    fun `cancellation is checked every round and stops the loop`() {
        val codec = FakeCodec(slots = 2)
        val pump = DecodePump(codec, FakeSource(1000))
        var rounds = 0
        assertFailsWith<java.util.concurrent.CancellationException> {
            pump.run(ensureActive = { if (++rounds > 3) throw java.util.concurrent.CancellationException("closed") }, onFormatChanged = {}, onOutput = { _, _, _ -> })
        }
        assertTrue(pump.stats.inputs < 20)
    }
}
