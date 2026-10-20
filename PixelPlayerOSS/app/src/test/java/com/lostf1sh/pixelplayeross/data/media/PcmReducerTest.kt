package com.lostf1sh.pixelplayeross.data.media

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B31 P2.5: the waveform reads one frame in `stride` instead of every frame. What that must not change: the bucket a frame belongs to,
 * the result when the decoder cuts its output differently, the RMS, and the fact that the peak is never louder than the real one.
 */
class PcmReducerTest {
    private val channels = 2
    private val frames = 2_000_000
    private val buckets = 64

    /** A tone whose level swells and fades, louder in the left channel, with a hard transient in the middle. */
    private fun sample(frame: Int, channel: Int): Float {
        val envelope = 0.15f + 0.7f * abs(sin(frame.toDouble() / frames * 3 * PI)).toFloat()
        val tone = sin(frame * 0.05 * (channel + 1)).toFloat()
        val click = if (frame == frames / 2 + 1) 0.97f else 0f
        return (envelope * tone * (if (channel == 0) 1f else 0.7f) + click).coerceIn(-1f, 1f)
    }

    private fun pcm16(from: Int, count: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(count * channels * 2).order(ByteOrder.nativeOrder())
        for (f in from until from + count) for (c in 0 until channels) buffer.putShort(((sample(f, c) * 32767f)).toInt().toShort())
        buffer.flip()
        return buffer
    }

    private fun waveformOf(stride: Int, chunkFrames: Int): Waveform {
        val builder = WaveformBuilder(PcmReducer.framesRead(frames.toLong(), stride), buckets)
        val reducer = PcmReducer(builder, stride).also { it.channelCount = channels }
        var at = 0
        while (at < frames) {
            val count = minOf(chunkFrames, frames - at)
            val buffer = pcm16(at, count)
            // The offset is not zero: the codec's buffers have one.
            val padded = ByteBuffer.allocateDirect(buffer.remaining() + 8).order(ByteOrder.nativeOrder())
            padded.position(8)
            padded.put(buffer)
            reducer.accept(padded, 8, count * channels * 2)
            at += count
        }
        return builder.build()
    }

    @Test
    fun `the strided waveform does not depend on how the decoder cut its output`() {
        val a = waveformOf(stride = 10, chunkFrames = 4096)
        val b = waveformOf(stride = 10, chunkFrames = 1777)
        val c = waveformOf(stride = 10, chunkFrames = 3) // buffers smaller than the stride
        assertContentEquals(a.peaks, b.peaks)
        assertContentEquals(a.rms, b.rms)
        assertContentEquals(a.peaks, c.peaks)
        assertContentEquals(a.rms, c.rms)
    }

    @Test
    fun `a stride of one reads every frame, as before`() {
        val one = waveformOf(stride = 1, chunkFrames = 4096)
        val chunked = waveformOf(stride = 1, chunkFrames = 999)
        assertContentEquals(one.peaks, chunked.peaks)
        assertContentEquals(one.rms, chunked.rms)
        assertTrue(one.peaks.all { it in 0f..1f })
    }

    @Test
    fun `reading a tenth of the frames keeps the rms and the shape, and the peak is never louder than the real one`() {
        val full = waveformOf(stride = 1, chunkFrames = 4096)
        val tenth = waveformOf(stride = 10, chunkFrames = 4096)
        assertEquals(full.bucketCount, tenth.bucketCount)
        for (i in 0 until buckets) {
            assertTrue(tenth.peaks[i] <= full.peaks[i] + 1e-6f, "bucket $i: sampled peak ${tenth.peaks[i]} above the real ${full.peaks[i]}")
            assertTrue(tenth.peaks[i] >= full.peaks[i] * 0.9f || full.peaks[i] > 0.9f && tenth.peaks[i] > 0.6f, "bucket $i: peak ${tenth.peaks[i]} against ${full.peaks[i]}")
            val rms = full.rms[i]
            if (rms > 0.01f) assertTrue(abs(tenth.rms[i] - rms) / rms < 0.03f, "bucket $i: rms ${tenth.rms[i]} against $rms")
        }
        // The swell is in the same buckets: the loudest bucket is the same one, and the quietest is the same one.
        assertEquals(full.rms.indices.maxByOrNull { full.rms[it] }, tenth.rms.indices.maxByOrNull { tenth.rms[it] })
        assertEquals(full.rms.indices.minByOrNull { full.rms[it] }, tenth.rms.indices.minByOrNull { tenth.rms[it] })
    }

    @Test
    fun `a stride reads the frames it should, one in stride across buffer boundaries`() {
        val builder = WaveformBuilder(PcmReducer.framesRead(100, 7), 1)
        val reducer = PcmReducer(builder, 7).also { it.channelCount = 1 }
        val ramp = ByteBuffer.allocateDirect(100 * 2).order(ByteOrder.nativeOrder())
        for (f in 0 until 100) ramp.putShort((f * 100).toShort())
        // 100 frames in buffers of 13, 3, 40 and 44: the frames read are 0, 7, 14, ... 98 whatever the cut.
        var at = 0
        for (size in listOf(13, 3, 40, 44)) {
            val chunk = ramp.duplicate().order(ByteOrder.nativeOrder())
            reducer.accept(chunk, at * 2, size * 2)
            at += size
        }
        assertEquals(15L, builder.framesAccepted) // ceil(100 / 7)
        val w = builder.build()
        assertEquals(98 * 100 / 32768f, w.peaks[0], 1e-6f) // the last frame read is 98
    }

    @Test
    fun `the float and the 8 bit encodings read the same frames`() {
        val floats = ByteBuffer.allocateDirect(30 * 4).order(ByteOrder.nativeOrder())
        for (f in 0 until 30) floats.putFloat(if (f % 3 == 0) 0.5f else 0.1f)
        val b1 = WaveformBuilder(10, 1)
        PcmReducer(b1, 3).also { it.channelCount = 1; it.pcmEncoding = AudioFormat.ENCODING_PCM_FLOAT }.accept(floats, 0, 30 * 4)
        assertEquals(10L, b1.framesAccepted)
        assertEquals(0.5f, b1.build().peaks[0], 1e-6f)
        assertEquals(0.5f, b1.build().rms[0], 1e-6f) // every frame read was a 0.5

        val bytes = ByteBuffer.allocateDirect(30).order(ByteOrder.nativeOrder())
        for (f in 0 until 30) bytes.put(if (f % 3 == 0) 192.toByte() else 128.toByte()) // 0.5 and 0
        val b2 = WaveformBuilder(10, 1)
        PcmReducer(b2, 3).also { it.channelCount = 1; it.pcmEncoding = AudioFormat.ENCODING_PCM_8BIT }.accept(bytes, 0, 30)
        assertEquals(0.5f, b2.build().peaks[0], 1e-6f)
    }

    @Test
    fun `the stride is one for a short track, ten for a 4 minute 13 second track at 44100 and capped for a very long one`() {
        assertEquals(1, PcmReducer.strideFor(30L * 44_100, 1024))
        assertEquals(10, PcmReducer.strideFor(253L * 44_100, 1024))
        assertEquals(23, PcmReducer.strideFor(253L * 96_000, 1024))
        assertEquals(PcmReducer.MAX_STRIDE, PcmReducer.strideFor(10L * 3600 * 192_000, 1024))
        assertEquals(1, PcmReducer.strideFor(0, 1024))
        // Every bucket keeps at least the frames the stride promises.
        for (seconds in listOf(20L, 90L, 253L, 1800L)) {
            val total = seconds * 96_000
            val stride = PcmReducer.strideFor(total, 1024)
            assertTrue(total / 1024 / stride >= PcmReducer.MIN_FRAMES_PER_BUCKET || stride == PcmReducer.MAX_STRIDE || stride == 1)
        }
    }
}
