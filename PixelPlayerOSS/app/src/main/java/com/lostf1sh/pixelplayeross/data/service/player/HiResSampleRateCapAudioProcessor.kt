package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Caps ultra-high-rate PCM to a rate Android devices handle more reliably.
 *
 * Media3/AudioTrack can end up in unstable device-specific paths for sample rates above 192 kHz.
 * Reducing 352.8/384 kHz streams before they reach the sink avoids the "loading audio" hang while
 * preserving normal playback for standard and regular hi-res files.
 *
 * **How it reduces the rate (B40-F8).** Not by averaging groups of samples, which is a two-tap filter and let a 180 kHz tone in a
 * 384 kHz stream fold to 12 kHz only 20 dB down, but by a linear-phase windowed-sinc FIR decimator ([DecimationFilter], [FirDecimator]):
 * at least 100 dB of rejection from the new Nyquist frequency up, flat to better than 0.001 dB up to 80 % of it, the filter's delay
 * taken out and its tail flushed at the end of the stream, the same output whatever the block sizes. 16-bit streams are converted to
 * float for the arithmetic and rounded back once; float streams stay float and are clamped to full scale.
 *
 * **Where it runs.** In the Android route's processor chain only: `UsbAudioSink` diverts the PCM of a USB Direct stream before
 * `DefaultAudioSink` runs its processors, so this class never touches Purity (nor the DSP stage of Resonance on Direct). A stream at
 * or under [maxOutputSampleRateHz] makes the processor inactive and untouched.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class HiResSampleRateCapAudioProcessor(
    private val maxOutputSampleRateHz: Int = 192_000
) : AudioProcessor {

    private companion object {
        private val NATIVE_ORDER = ByteOrder.nativeOrder()
        const val SHORT_SCALE = 32768f
    }

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputFormat: AudioFormat = AudioFormat.NOT_SET
    private var decimator: FirDecimator? = null
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    /** The long-lived allocation [outputBuffer] is made of: the pipeline has taken the last one before it queues more. */
    private var outputStorage: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    /** The bytes of a frame that was cut by a buffer boundary, until the rest arrives. */
    private var stage = ByteArray(0)
    private var leftoverBytes = 0
    private var floatIn = FloatArray(0)
    private var floatOut = FloatArray(0)

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val shouldDownsample = (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) &&
            inputAudioFormat.channelCount > 0 &&
            inputAudioFormat.sampleRate > maxOutputSampleRateHz

        if (!shouldDownsample) {
            inputFormat = AudioFormat.NOT_SET
            outputFormat = AudioFormat.NOT_SET
            decimator = null
            leftoverBytes = 0
            return inputAudioFormat
        }

        val factor = ceil(
            inputAudioFormat.sampleRate.toDouble() / maxOutputSampleRateHz.toDouble()
        ).toInt().coerceAtLeast(2)

        inputFormat = inputAudioFormat
        outputFormat = AudioFormat(
            inputAudioFormat.sampleRate / factor,
            inputAudioFormat.channelCount,
            inputAudioFormat.encoding
        )
        decimator = FirDecimator(inputAudioFormat.channelCount, factor, DecimationFilter.taps(inputAudioFormat.sampleRate, factor))
        leftoverBytes = 0
        return outputFormat
    }

    override fun isActive(): Boolean = outputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val filter = decimator ?: return
        val channels = inputFormat.channelCount
        val isFloat = inputFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerFrame = channels * if (isFloat) Float.SIZE_BYTES else Short.SIZE_BYTES

        val incoming = inputBuffer.remaining()
        val total = leftoverBytes + incoming
        val frames = total / bytesPerFrame
        if (stage.size < total) stage = stage.copyOf(maxOf(total, stage.size * 2))
        inputBuffer.get(stage, leftoverBytes, incoming)
        if (frames == 0) {
            leftoverBytes = total
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }

        // bytes -> float, once
        val samples = frames * channels
        if (floatIn.size < samples) floatIn = FloatArray(maxOf(samples, floatIn.size * 2))
        val bytes = ByteBuffer.wrap(stage, 0, frames * bytesPerFrame).order(NATIVE_ORDER)
        if (isFloat) {
            for (i in 0 until samples) floatIn[i] = bytes.float
        } else {
            for (i in 0 until samples) floatIn[i] = bytes.short / SHORT_SCALE
        }
        val kept = total - frames * bytesPerFrame
        System.arraycopy(stage, frames * bytesPerFrame, stage, 0, kept)
        leftoverBytes = kept

        val maxOut = filter.maxOutputFrames(frames)
        if (floatOut.size < maxOut * channels) floatOut = FloatArray(maxOf(maxOut * channels, floatOut.size * 2))
        val produced = filter.process(floatIn, 0, frames, floatOut, 0)
        outputBuffer = fill(produced, channels, isFloat)
    }

    /** [frames] float frames from [floatOut] as the stream's own encoding, in a buffer the pipeline can take. */
    private fun fill(frames: Int, channels: Int, isFloat: Boolean): ByteBuffer {
        if (frames == 0) return AudioProcessor.EMPTY_BUFFER
        val bytes = frames * channels * if (isFloat) Float.SIZE_BYTES else Short.SIZE_BYTES
        if (outputStorage.capacity() < bytes) outputStorage = ByteBuffer.allocateDirect(maxOf(bytes, outputStorage.capacity() * 2)).order(NATIVE_ORDER)
        val buffer = outputStorage
        buffer.clear()
        val samples = frames * channels
        if (isFloat) {
            for (i in 0 until samples) buffer.putFloat(floatOut[i].coerceIn(-1f, 1f))
        } else {
            for (i in 0 until samples) {
                val scaled = Math.round(floatOut[i] * SHORT_SCALE)
                buffer.putShort(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        buffer.flip()
        return buffer
    }

    override fun getOutput(): ByteBuffer {
        val pendingOutput = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return pendingOutput
    }

    override fun isEnded(): Boolean = inputEnded && !outputBuffer.hasRemaining()

    override fun queueEndOfStream() {
        val filter = decimator
        if (filter != null) {
            val channels = inputFormat.channelCount
            val maxOut = filter.maxOutputFrames(0) + filter.groupDelayFrames / filter.factor + 1
            if (floatOut.size < maxOut * channels) floatOut = FloatArray(maxOut * channels)
            val produced = filter.finish(floatOut, 0)
            // a frame cut by the end of the stream is dropped, as the rest of a group of frames always was
            leftoverBytes = 0
            outputBuffer = fill(produced, channels, inputFormat.encoding == C.ENCODING_PCM_FLOAT)
        }
        inputEnded = true
    }

    @Deprecated("Media3 AudioProcessor now prefers flush(StreamMetadata); kept for interface compatibility")
    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        leftoverBytes = 0
        inputEnded = false
        decimator?.reset()
    }

    override fun reset() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        leftoverBytes = 0
        inputEnded = false
        inputFormat = AudioFormat.NOT_SET
        outputFormat = AudioFormat.NOT_SET
        decimator = null
    }
}
