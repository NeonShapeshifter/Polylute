package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The cap's contract with Media3 (what it declares, when it is active, what a cut frame does). What it does to the signal, and how well,
 * is in [HiResDownsamplingTest] (B40-F8 replaced the averaging these tests used to pin with a FIR decimator).
 */
class HiResSampleRateCapAudioProcessorTest {

    @Test
    fun configure_keepsSupportedSampleRatesUntouched() {
        val processor = HiResSampleRateCapAudioProcessor()
        val inputFormat = AudioFormat(192_000, 2, C.ENCODING_PCM_16BIT)

        val outputFormat = processor.configure(inputFormat)

        assertThat(outputFormat).isEqualTo(inputFormat)
        assertThat(processor.isActive()).isFalse()
    }

    @Test
    fun queueInput_downsamples384KhzStereoTo192KhzAndHalvesTheFrames() {
        val processor = HiResSampleRateCapAudioProcessor()
        val outputFormat = processor.configure(AudioFormat(384_000, 2, C.ENCODING_PCM_16BIT))

        // 4,000 frames of a steady level on both channels
        processor.queueInput(shortBufferOf(IntArray(8_000) { 1_000 }))
        val body = readShorts(processor.getOutput())
        processor.queueEndOfStream()
        val samples = body + readShorts(processor.getOutput())

        assertThat(processor.isActive()).isTrue()
        assertThat(outputFormat.sampleRate).isEqualTo(192_000)
        assertThat(outputFormat.channelCount).isEqualTo(2)
        // 2,000 frames of two channels (the end of the stream gives back the frames the filter was still holding), and a steady level
        // stays that level once the filter has filled
        assertThat(samples).hasSize(4_000)
        assertThat(samples.subList(1_000, 3_000).all { it == 1_000 }).isTrue()
        assertThat(processor.isEnded()).isTrue()
    }

    @Test
    fun queueInput_carriesAFrameCutByABufferBoundary() {
        val whole = HiResSampleRateCapAudioProcessor()
        whole.configure(AudioFormat(384_000, 2, C.ENCODING_PCM_16BIT))
        val data = IntArray(3_000) { (it * 7) % 2_000 - 1_000 }
        whole.queueInput(shortBufferOf(data))
        val wholeBody = readShorts(whole.getOutput())
        whole.queueEndOfStream()
        val expected = wholeBody + readShorts(whole.getOutput())

        // the same samples, cut in the middle of a frame (odd number of samples), then the rest
        val cut = HiResSampleRateCapAudioProcessor()
        cut.configure(AudioFormat(384_000, 2, C.ENCODING_PCM_16BIT))
        cut.queueInput(shortBufferOf(data.copyOfRange(0, 1_001)))
        val first = readShorts(cut.getOutput())
        cut.queueInput(shortBufferOf(data.copyOfRange(1_001, data.size)))
        val second = readShorts(cut.getOutput())
        cut.queueEndOfStream()
        val tail = readShorts(cut.getOutput())

        assertThat(first + second + tail).isEqualTo(expected)
    }

    @Test
    fun queueInput_carriesPartialFloatFramesAcrossCalls() {
        val processor = HiResSampleRateCapAudioProcessor()
        processor.configure(AudioFormat(384_000, 2, C.ENCODING_PCM_FLOAT))

        processor.queueInput(floatBufferOf(0.1f, 0.2f))
        assertThat(processor.getOutput().remaining()).isEqualTo(0)

        processor.queueInput(floatBufferOf(0.3f, 0.4f))
        // two frames are in: not enough yet for a filter that looks ahead, but nothing is lost
        processor.queueEndOfStream()
        assertThat(readFloats(processor.getOutput())).hasSize(2)
    }

    private fun shortBufferOf(samples: IntArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        for (sample in samples) buffer.putShort(sample.toShort())
        buffer.flip()
        return buffer
    }

    private fun readShorts(buffer: ByteBuffer): List<Int> {
        val duplicate = buffer.duplicate().order(ByteOrder.nativeOrder())
        val samples = mutableListOf<Int>()
        while (duplicate.remaining() >= Short.SIZE_BYTES) samples += duplicate.short.toInt()
        return samples
    }

    private fun floatBufferOf(vararg samples: Float): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        for (sample in samples) buffer.putFloat(sample)
        buffer.flip()
        return buffer
    }

    private fun readFloats(buffer: ByteBuffer): List<Float> {
        val duplicate = buffer.duplicate().order(ByteOrder.nativeOrder())
        val samples = mutableListOf<Float>()
        while (duplicate.remaining() >= Float.SIZE_BYTES) samples += duplicate.float
        return samples
    }
}
