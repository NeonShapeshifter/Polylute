package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test

/**
 * The processor's contract with the media3 pipeline: what it does to bytes, what it must not do to
 * them, and how it behaves at the boundaries where a buffer does not line up with a frame.
 *
 * These tests drive the processor directly rather than through a player, so each assertion is about
 * the DSP itself and not about `DefaultAudioSink`'s scheduling. Where an assertion would be vacuous
 * without a control case — a flush that clears state, say — the control case is run alongside it.
 */
class HeadphoneGraphicEqProcessorTest {

    private val stereo16 = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    private fun flat10() = FloatArray(GraphicEqBands.TEN_BAND_COUNT)

    private fun settings10(gains: FloatArray = flat10(), enabled: Boolean = true) =
        GraphicEqSettings(enabled, GraphicEqBands.TEN_BAND_HZ, gains)

    private fun AudioFormat.processorWith(settings: GraphicEqSettings): HeadphoneGraphicEqProcessor =
        HeadphoneGraphicEqProcessor { settings }.apply { configure(this@processorWith) }

    // --- Buffer helpers -------------------------------------------------------------------

    private fun pcm16Buffer(samples: List<Int>): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    private fun floatBuffer(samples: List<Float>): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putFloat(it) }
        buffer.flip()
        return buffer
    }

    private fun byteBuffer(bytes: List<Int>): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        bytes.forEach { buffer.put(it.toByte()) }
        buffer.flip()
        return buffer
    }

    private fun ByteBuffer.readShorts(): List<Int> {
        val view = duplicate().order(ByteOrder.nativeOrder())
        val out = ArrayList<Int>(view.remaining() / Short.SIZE_BYTES)
        while (view.remaining() >= Short.SIZE_BYTES) out.add(view.getShort().toInt())
        return out
    }

    private fun ByteBuffer.readFloats(): List<Float> {
        val view = duplicate().order(ByteOrder.nativeOrder())
        val out = ArrayList<Float>(view.remaining() / Float.SIZE_BYTES)
        while (view.remaining() >= Float.SIZE_BYTES) out.add(view.getFloat())
        return out
    }

    private fun ByteBuffer.readBytes(): List<Int> {
        val view = duplicate()
        val out = ArrayList<Int>(view.remaining())
        while (view.hasRemaining()) out.add(view.get().toInt())
        return out
    }

    /** Total energy of a buffer's samples, as a scalar for boost-versus-cut comparisons. */
    private fun ByteBuffer.energy(): Long {
        var total = 0L
        readShorts().forEach { val v = it.toLong(); total += v * v }
        return total
    }

    private val impulse = (0 until 512).map { if (it == 0) 16_384 else 0 }

    // --- Format negotiation ---------------------------------------------------------------

    @Test
    fun `the declared output format is the input format, untouched`() {
        // If this ever fails, the processor has changed the stream's shape — a resample, a channel
        // change or an encoding change — which the phase forbids outright.
        listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT).forEach { encoding ->
            listOf(44_100, 48_000, 96_000, 192_000).forEach { sampleRate ->
                listOf(1, 2, 6, 8).forEach { channelCount ->
                    val format = AudioFormat(sampleRate, channelCount, encoding)
                    val processor = HeadphoneGraphicEqProcessor { settings10() }

                    assertThat(processor.configure(format)).isEqualTo(format)
                    assertThat(processor.isActive()).isTrue()
                }
            }
        }
    }

    @Test
    fun `an inactive processor consumes nothing and produces nothing`() {
        val processor = HeadphoneGraphicEqProcessor { settings10() }
        val source = pcm16Buffer(listOf(1, 2, 3, 4))

        assertThat(processor.isActive()).isFalse()
        processor.queueInput(source)

        assertThat(processor.getOutput().remaining()).isEqualTo(0)
        assertThat(source.remaining()).isEqualTo(8)
    }

    // --- Bypass ---------------------------------------------------------------------------

    @Test
    fun `eq off copies the bytes through without interpreting them`() {
        val samples = listOf(0, 1, -1, 32_767, -32_768, 12_345, -12_345, 2_000)
        val processor = stereo16.processorWith(GraphicEqSettings.BYPASS)
        val source = pcm16Buffer(samples)

        processor.queueInput(source)

        // Nobody's EQ: every sample, including both full-scale rails, arrives unchanged.
        assertThat(processor.getOutput().readShorts()).isEqualTo(samples)
        assertThat(source.remaining()).isEqualTo(0)
    }

    @Test
    fun `eq on with every band flat is the same byte-for-byte pass-through`() {
        val samples = listOf(0, 1, -1, 32_767, -32_768, 12_345, -12_345, 2_000)
        val processor = stereo16.processorWith(settings10(gains = flat10(), enabled = true))
        val source = pcm16Buffer(samples)

        processor.queueInput(source)

        // A flat curve is a mathematical identity, so an enabled EQ with nothing dialled in cannot
        // colour the sound even in principle.
        assertThat(processor.getOutput().readShorts()).isEqualTo(samples)
    }

    // --- Processing -----------------------------------------------------------------------

    /** Level of a steady [hz] sine, in dB re its input, after [processor] has settled (mono, 48 kHz, PCM16). */
    private fun toneLevelDb(settings: GraphicEqSettings, hz: Double): Double {
        val processor = AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT).processorWith(settings)
        val amplitude = 0.25
        val input = (0 until 48_000).map {
            (amplitude * 32768.0 * kotlin.math.sin(2.0 * Math.PI * hz * it / 48_000)).toInt()
        }
        processor.queueInput(pcm16Buffer(input))
        val tail = processor.getOutput().readShorts().takeLast(12_000)
        val rms = kotlin.math.sqrt(tail.sumOf { (it / 32768.0) * (it / 32768.0) } / tail.size)
        return 20.0 * kotlin.math.log10(rms * kotlin.math.sqrt(2.0) / amplitude)
    }

    @Test
    fun `a boost raises its band above the rest by the slider and an equal cut lowers it by as much`() {
        // B14-2: a boost no longer lifts the whole signal past the level it came in at. The headroom the cascade
        // needs comes off ahead of it, so the boosted band stays where a flat EQ puts it and everything else
        // drops: the slider's meaning, "this band relative to the rest", is unchanged, and measured here against
        // a flat control on the same two tones.
        val flat = settings10()
        val boost = settings10(flat10().also { it[5] = 12f })
        val cut = settings10(flat10().also { it[5] = -12f })

        val flatBand = toneLevelDb(flat, 1_000.0)
        val flatFar = toneLevelDb(flat, 8_000.0)
        assertThat(flatBand).isWithin(0.1).of(0.0)
        assertThat(flatFar).isWithin(0.1).of(0.0)

        val boostBand = toneLevelDb(boost, 1_000.0)
        val boostFar = toneLevelDb(boost, 8_000.0)
        assertThat(boostBand - boostFar).isWithin(0.5).of(12.0)
        assertThat(boostBand).isWithin(0.3).of(0.0)       // held at the flat reference, not above it
        assertThat(boostFar).isWithin(0.5).of(-12.0)

        val cutBand = toneLevelDb(cut, 1_000.0)
        val cutFar = toneLevelDb(cut, 8_000.0)
        assertThat(cutBand - cutFar).isWithin(0.5).of(-12.0)
        assertThat(cutBand).isWithin(0.3).of(-12.0)       // a cut needs no headroom
        assertThat(cutFar).isWithin(0.3).of(0.0)
    }

    @Test
    fun `a boosted band changes the samples that come out`() {
        val processor = stereo16.processorWith(settings10(flat10().also { it[5] = 12f }))
        processor.queueInput(pcm16Buffer(impulse))

        val output = processor.getOutput().readShorts()

        assertThat(output).hasSize(impulse.size)
        assertThat(output).isNotEqualTo(impulse)
    }

    @Test
    fun `two processors given the same input produce bit-identical output`() {
        val gains = flat10().also { it[2] = 7.5f; it[9] = -4.5f }
        val first = stereo16.processorWith(settings10(gains))
        val second = stereo16.processorWith(settings10(gains))

        first.queueInput(pcm16Buffer(impulse))
        second.queueInput(pcm16Buffer(impulse))

        assertThat(first.getOutput().readShorts()).isEqualTo(second.getOutput().readShorts())
    }

    @Test
    fun `float samples are filtered and confined to full scale`() {
        val gains = flat10().also { it[5] = 15f }
        val processor = AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT).processorWith(settings10(gains))
        val samples = (0 until 256).flatMap { listOf(if (it == 0) 0.25f else 0f, 0f) }

        processor.queueInput(floatBuffer(samples))
        val output = processor.getOutput().readFloats()

        assertThat(output).hasSize(samples.size)
        // Real filtering, not a pass-through that happens to stay in range: the first sample is not the input
        // (the +15 dB headroom of B14-2 comes off ahead of the section, so it is lower), and the section rings
        // on the same channel after the impulse has gone.
        assertThat(output[0]).isNotEqualTo(0.25f)
        assertThat(output[0]).isLessThan(0.25f)
        assertThat((1 until 256).any { output[it * 2] != 0f }).isTrue()
        output.forEach { sample ->
            assertThat(sample).isAtMost(1f)
            assertThat(sample).isAtLeast(-1f)
        }
    }

    // --- Channels -------------------------------------------------------------------------

    @Test
    fun `filtering the left channel leaves the right channel at exactly digital silence`() {
        val processor = stereo16.processorWith(settings10(flat10().also { it[4] = 12f }))
        val interleaved = (0 until 256).flatMap { listOf(if (it == 0) 16_384 else 0, 0) }

        processor.queueInput(pcm16Buffer(interleaved))
        val output = processor.getOutput().readShorts()

        val left = output.filterIndexed { index, _ -> index % 2 == 0 }
        val right = output.filterIndexed { index, _ -> index % 2 == 1 }
        assertThat(left.any { it != 0 }).isTrue()
        assertThat(right).containsExactlyElementsIn(List(256) { 0 })
    }

    @Test
    fun `the channels are not swapped`() {
        val processor = stereo16.processorWith(settings10(flat10().also { it[4] = 12f }))
        // The mirror of the previous case: now only the right channel carries signal.
        val interleaved = (0 until 256).flatMap { listOf(0, if (it == 0) 16_384 else 0) }

        processor.queueInput(pcm16Buffer(interleaved))
        val output = processor.getOutput().readShorts()

        val left = output.filterIndexed { index, _ -> index % 2 == 0 }
        val right = output.filterIndexed { index, _ -> index % 2 == 1 }
        assertThat(right.any { it != 0 }).isTrue()
        assertThat(left).containsExactlyElementsIn(List(256) { 0 })
    }

    // --- Frame boundaries -----------------------------------------------------------------

    @Test
    fun `a buffer shorter than one whole frame is left entirely with the caller`() {
        // Stereo 16-bit frames are four bytes; three bytes is not a frame.
        val processor = stereo16.processorWith(settings10(flat10().also { it[3] = 9f }))
        val source = byteBuffer(listOf(0x11, 0x22, 0x33))

        processor.queueInput(source)

        assertThat(processor.getOutput().remaining()).isEqualTo(0)
        assertThat(source.remaining()).isEqualTo(3)
    }

    @Test
    fun `whole frames are consumed and a trailing partial frame is left`() {
        val processor = stereo16.processorWith(settings10(flat10().also { it[3] = 9f }))
        // Three whole frames plus two stray bytes: 14 bytes.
        val source = byteBuffer((0 until 14).toList())

        processor.queueInput(source)

        assertThat(processor.getOutput().remaining()).isEqualTo(12)
        assertThat(source.remaining()).isEqualTo(2)
    }

    @Test
    fun `an encoding this processor cannot filter still reaches the sink byte-for-byte`() {
        // 24-bit never reaches this chain in this application, but a frame is a frame: the samples
        // must pass through unchanged rather than be dropped, and the frame arithmetic must still
        // be right so the stream is not left misaligned.
        val processor = AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT)
            .processorWith(settings10(flat10().also { it[5] = 15f }))
        val source = byteBuffer((0 until 21).toList()) // three 6-byte frames plus three bytes

        processor.queueInput(source)

        assertThat(processor.getOutput().readBytes()).isEqualTo((0 until 18).toList())
        assertThat(source.remaining()).isEqualTo(3)
    }

    // --- Buffer lifecycle -----------------------------------------------------------------

    @Test
    fun `output is handed over once and then drained`() {
        val processor = stereo16.processorWith(settings10(flat10().also { it[5] = 12f }))
        processor.queueInput(pcm16Buffer(listOf(1, 2, 3, 4)))

        assertThat(processor.getOutput().remaining()).isGreaterThan(0)
        assertThat(processor.getOutput().remaining()).isEqualTo(0)
    }

    @Test
    fun `the output buffer is reused between buffers rather than reallocated`() {
        val processor = stereo16.processorWith(settings10(flat10().also { it[5] = 12f }))
        processor.queueInput(pcm16Buffer(listOf(1, 2, 3, 4)))
        val first = processor.getOutput()

        processor.queueInput(pcm16Buffer(listOf(5, 6, 7, 8)))
        val second = processor.getOutput()

        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun `end of stream is reported only once the queued output has been drained`() {
        val processor = stereo16.processorWith(settings10())
        processor.queueInput(pcm16Buffer(listOf(1, 2, 3, 4)))
        processor.queueEndOfStream()

        assertThat(processor.isEnded()).isFalse()

        processor.getOutput()

        assertThat(processor.isEnded()).isTrue()
    }

    // --- Flush and reset ------------------------------------------------------------------

    @Test
    fun `flush discards filter memory so a seek cannot ring`() {
        val gains = flat10().also { it[1] = 12f }

        // Control: without a flush the tail of the previous buffer is still audible, which is what
        // makes the flushed assertion meaningful rather than a statement about silence.
        val unflushed = stereo16.processorWith(settings10(gains))
        unflushed.queueInput(pcm16Buffer(listOf(32_767, 32_767)))
        assertThat(unflushed.getOutput().readShorts().any { it != 0 }).isTrue()
        unflushed.queueInput(pcm16Buffer(listOf(0, 0)))
        assertThat(unflushed.getOutput().readShorts().any { it != 0 }).isTrue()

        val flushed = stereo16.processorWith(settings10(gains))
        flushed.queueInput(pcm16Buffer(listOf(32_767, 32_767)))
        assertThat(flushed.getOutput().readShorts().any { it != 0 }).isTrue()

        flushed.flush(StreamMetadata.DEFAULT)

        flushed.queueInput(pcm16Buffer(listOf(0, 0)))
        assertThat(flushed.getOutput().readShorts()).containsExactly(0, 0)
        // Flushing is not a teardown: the stream keeps playing and the EQ keeps working.
        assertThat(flushed.isActive()).isTrue()
    }

    @Test
    fun `reset returns the processor to a state where it ignores input`() {
        val processor = stereo16.processorWith(settings10(flat10().also { it[5] = 12f }))
        processor.queueInput(pcm16Buffer(listOf(1, 2, 3, 4)))
        processor.getOutput()

        processor.reset()

        assertThat(processor.isActive()).isFalse()
        val source = pcm16Buffer(listOf(1, 2, 3, 4))
        processor.queueInput(source)
        assertThat(processor.getOutput().remaining()).isEqualTo(0)
        assertThat(source.remaining()).isEqualTo(8)
    }

    // --- 16-bit conversion ----------------------------------------------------------------

    @Test
    fun `the 16-bit conversion saturates instead of wrapping`() {
        val processor = stereo16.processorWith(GraphicEqSettings.BYPASS)

        assertThat(processor.toPcm16(0f)).isEqualTo(0.toShort())
        assertThat(processor.toPcm16(0.5f)).isEqualTo(16_384.toShort())
        assertThat(processor.toPcm16(1f)).isEqualTo(Short.MAX_VALUE)
        assertThat(processor.toPcm16(-1f)).isEqualTo(Short.MIN_VALUE)

        // Past full scale the value clamps to the rail. A naive truncation would fold +2.0 to zero
        // and +1.5 to a negative number, turning a loud peak into a loud trough.
        assertThat(processor.toPcm16(1.0001f)).isEqualTo(Short.MAX_VALUE)
        assertThat(processor.toPcm16(1.5f)).isEqualTo(Short.MAX_VALUE)
        assertThat(processor.toPcm16(2f)).isEqualTo(Short.MAX_VALUE)
        assertThat(processor.toPcm16(100f)).isEqualTo(Short.MAX_VALUE)
        assertThat(processor.toPcm16(Float.MAX_VALUE)).isEqualTo(Short.MAX_VALUE)
        assertThat(processor.toPcm16(-1.5f)).isEqualTo(Short.MIN_VALUE)
        assertThat(processor.toPcm16(-2f)).isEqualTo(Short.MIN_VALUE)
        assertThat(processor.toPcm16(-100f)).isEqualTo(Short.MIN_VALUE)
        assertThat(processor.toPcm16(-Float.MAX_VALUE)).isEqualTo(Short.MIN_VALUE)
    }

    @Test
    fun `the 16-bit conversion never inverts a sample's sign`() {
        val processor = stereo16.processorWith(GraphicEqSettings.BYPASS)

        ((-400..400).map { it / 100f }).forEach { sample ->
            val converted = processor.toPcm16(sample).toInt()
            if (sample > 0f) {
                assertThat(converted).isAtLeast(0)
            } else if (sample < 0f) {
                assertThat(converted).isAtMost(0)
            } else {
                assertThat(converted).isEqualTo(0)
            }
        }
    }
}
