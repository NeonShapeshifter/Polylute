package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * B40-F8: Resonance's optional peak limiter ([PeakLimiter]) as the last processor of the Android route's chain, after the headphone
 * graphic EQ, so a boost never clips.
 *
 * ```
 * decoder -> ToInt16 -> HiResSampleRateCap -> SurroundDownmix -> HeadphoneGraphicEq -> PeakLimiter -> silence skip -> sonic -> AudioTrack
 * ```
 *
 * **Off unless asked for, and then out of the chain altogether.** [enabled] is read when a stream is configured and again at every
 * flush (a seek, a new song): the processor is [isActive] only if it said yes then, so with the switch off it adds no latency, no copy
 * and no arithmetic, and a switch turned on during a song takes effect at the next seek or song. [onRunning] says what the processor
 * decided each time, which is what the Audio page's status line reads: it never claims a limiter that is not in the signal.
 *
 * **Never on Purity, by position.** `UsbAudioSink` diverts the PCM of a USB Direct stream before `DefaultAudioSink` runs its
 * processors, so nothing in this chain ever sees a Direct stream (the same fact that keeps the headphone EQ and the sample-rate cap off
 * it); the Direct path's Resonance stage is not touched by this class. The format is never changed, only the level of what is over the
 * ceiling.
 *
 * Only 16-bit and float PCM are limited; anything else is left out of the chain.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PeakLimiterAudioProcessor(
    private val enabled: () -> Boolean,
    private val onRunning: (Boolean) -> Unit = {},
) : AudioProcessor {

    private companion object {
        val NATIVE_ORDER: ByteOrder = ByteOrder.nativeOrder()
        const val SHORT_SCALE = 32768f
        const val MAX_CHANNELS = 8
    }

    /** What the stream is, whether or not the limiter is wanted for it. */
    private var configured: AudioFormat = AudioFormat.NOT_SET
    private var outputFormat: AudioFormat = AudioFormat.NOT_SET
    private var limiter: PeakLimiter? = null
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputStorage: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var stage = ByteArray(0)
    private var leftoverBytes = 0
    private var floatIn = FloatArray(0)
    private var floatOut = FloatArray(0)

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        configured = inputAudioFormat
        inputEnded = false
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        decide()
        return inputAudioFormat
    }

    /** Asks the switch and builds or drops the limiter to match. */
    private fun decide() {
        val eligible = (configured.encoding == C.ENCODING_PCM_16BIT || configured.encoding == C.ENCODING_PCM_FLOAT) &&
            configured.channelCount in 1..MAX_CHANNELS && configured.sampleRate > 0
        val wanted = eligible && enabled()
        limiter = if (wanted) PeakLimiter(configured.sampleRate, configured.channelCount) else null
        outputFormat = if (wanted) configured else AudioFormat.NOT_SET
        leftoverBytes = 0
        onRunning(wanted)
    }

    override fun isActive(): Boolean = outputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val filter = limiter ?: return
        val channels = configured.channelCount
        val isFloat = configured.encoding == C.ENCODING_PCM_FLOAT
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
        val samples = frames * channels
        if (floatIn.size < samples) floatIn = FloatArray(maxOf(samples, floatIn.size * 2))
        if (floatOut.size < samples) floatOut = FloatArray(maxOf(samples, floatOut.size * 2))
        val bytes = ByteBuffer.wrap(stage, 0, frames * bytesPerFrame).order(NATIVE_ORDER)
        if (isFloat) {
            for (i in 0 until samples) floatIn[i] = bytes.float
        } else {
            for (i in 0 until samples) floatIn[i] = bytes.short / SHORT_SCALE
        }
        val kept = total - frames * bytesPerFrame
        System.arraycopy(stage, frames * bytesPerFrame, stage, 0, kept)
        leftoverBytes = kept

        outputBuffer = fill(filter.process(floatIn, 0, frames, floatOut, 0), channels, isFloat)
    }

    private fun fill(frames: Int, channels: Int, isFloat: Boolean): ByteBuffer {
        if (frames == 0) return AudioProcessor.EMPTY_BUFFER
        val bytes = frames * channels * if (isFloat) Float.SIZE_BYTES else Short.SIZE_BYTES
        if (outputStorage.capacity() < bytes) outputStorage = ByteBuffer.allocateDirect(maxOf(bytes, outputStorage.capacity() * 2)).order(NATIVE_ORDER)
        val buffer = outputStorage
        buffer.clear()
        val samples = frames * channels
        if (isFloat) {
            for (i in 0 until samples) buffer.putFloat(floatOut[i])
        } else {
            for (i in 0 until samples) {
                buffer.putShort(Math.round(floatOut[i] * SHORT_SCALE).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        buffer.flip()
        return buffer
    }

    override fun getOutput(): ByteBuffer {
        val pending = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return pending
    }

    override fun isEnded(): Boolean = inputEnded && !outputBuffer.hasRemaining()

    override fun queueEndOfStream() {
        val filter = limiter
        if (filter != null) {
            val channels = configured.channelCount
            if (floatOut.size < filter.delayFrames * channels) floatOut = FloatArray(filter.delayFrames * channels)
            outputBuffer = fill(filter.finish(floatOut, 0), channels, configured.encoding == C.ENCODING_PCM_FLOAT)
        }
        leftoverBytes = 0
        inputEnded = true
    }

    @Deprecated("Media3 AudioProcessor now prefers flush(StreamMetadata); kept for interface compatibility")
    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        // a seek or a new song: the switch is asked again, and the stream starts from silence
        if (configured != AudioFormat.NOT_SET) decide()
    }

    override fun reset() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        configured = AudioFormat.NOT_SET
        outputFormat = AudioFormat.NOT_SET
        limiter = null
        leftoverBytes = 0
        onRunning(false)
    }
}
