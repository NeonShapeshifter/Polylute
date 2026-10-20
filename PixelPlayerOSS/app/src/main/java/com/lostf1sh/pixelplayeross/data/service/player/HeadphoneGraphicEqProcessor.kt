package com.lostf1sh.pixelplayeross.data.service.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqFilterBank
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real software headphone Graphic EQ for the Android Audio path (Phase 8.5).
 *
 * ## Where it sits
 *
 * It is the last custom processor in the `DefaultAudioSink` chain, immediately after
 * [SurroundDownmixProcessor]:
 *
 * ```
 * decoder → media3 ToInt16 → HiResSampleRateCap → SurroundDownmix → HeadphoneGraphicEq
 *         → silence skip → sonic → AudioTrack
 * ```
 *
 * That position is what makes the routing truthful. `UsbAudioSink` intercepts PCM in
 * `handleBuffer` and, while USB Direct is active, diverts it to the DAC transport without ever
 * calling `DefaultAudioSink.handleBuffer`. An `AudioProcessor` is only ever invoked from inside
 * `DefaultAudioSink`, so the Direct path cannot run this DSP — the separation is structural rather
 * than a runtime check that could be forgotten or mis-set.
 *
 * ## What it may and may not do
 *
 * The processor never changes sample rate, channel count, channel order or PCM encoding: it
 * returns [AudioFormat] unchanged from [configure]. It applies only the per-band peaking gains the
 * user asked for, and the headroom those gains need. It performs no content-dependent gain, no
 * normalisation and no loudness compensation.
 *
 * ## Headroom (B14-2, audit A-01/A-02)
 *
 * The bands run in cascade and overlapping boosts add up, so the loudest frequency of a curve sits
 * well above its highest slider. The bank measures that peak on the coefficients it is running, at
 * this stream's rate ([GraphicEqFilterBank.headroomDb]), and this processor takes it off *before*
 * the filter, so a boost never lifts the signal past the level it came in at. The take-off has to be
 * here: the saturation to PCM16 below sits inside this processor, and the volume the player applies
 * afterwards (user level, ReplayGain) cannot restore a peak that was already flattened.
 *
 * ## Bypass
 *
 * When the EQ is off, or on with every band at exactly 0 dB, [GraphicEqSettings.isBypass] is set
 * and the processor performs a verbatim byte copy of the input buffer. No sample is interpreted,
 * converted or scaled, no coefficient is consulted and no filter memory is touched, so "PixelPlayer
 * EQ DSP introduces no PCM modification when bypassed" holds by construction rather than by
 * argument.
 *
 * The copy exists because a media3 processor must consume its input and expose its own output
 * buffer; handing the caller's buffer straight back would alias memory the sink is still draining.
 * Copying bytes is not sample modification and costs nothing measurable at audio rates.
 *
 * ## Negotiation between the audio thread and the UI
 *
 * Playback runs on a dedicated thread while sliders move on the main thread. [settingsProvider] is
 * read once per buffer, yielding an immutable snapshot; coefficients are recomputed only when the
 * snapshot instance changes. Nothing is allocated per sample, and after the first buffer nothing is
 * allocated per buffer either — the output buffer is reused.
 *
 * ## Unsupported input
 *
 * Only 16-bit and float PCM are filtered. Anything else is passed through verbatim rather than
 * rejected, so an unexpected encoding can never break playback. In this application the preceding
 * media3 `ToInt16PcmAudioProcessor` guarantees the chain only ever sees 16-bit PCM, because
 * `DefaultAudioSink` is built with float output disabled.
 */
@OptIn(UnstableApi::class)
class HeadphoneGraphicEqProcessor(
    private val settingsProvider: () -> GraphicEqSettings,
) : AudioProcessor {

    private companion object {
        /** 16-bit full scale: the divisor/multiplier between raw PCM and normalised audio. */
        const val SHORT_SCALE = 32768f

        /** Guard against a header claiming an implausible channel count. */
        const val MAX_CHANNELS = 8
    }

    private val filterBank = GraphicEqFilterBank()

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputFormat: AudioFormat = AudioFormat.NOT_SET

    private var bytesPerFrame = 0
    private var channelCount = 0

    /** Whether the configured encoding/channel count is one this processor can filter. */
    private var canFilter = false

    /** Buffer currently exposed to the pipeline, or [AudioProcessor.EMPTY_BUFFER]. */
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    /** Long-lived allocation reused for every buffer; never handed out while still in use. */
    private var scratchBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    private var inputEnded = false

    private var currentSettings: GraphicEqSettings = GraphicEqSettings.BYPASS

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        channelCount = inputAudioFormat.channelCount
        inputEnded = false
        outputBuffer = AudioProcessor.EMPTY_BUFFER

        bytesPerFrame = if (channelCount > 0) {
            channelCount * bytesPerSample(inputAudioFormat.encoding)
        } else {
            0
        }

        canFilter = channelCount in 1..MAX_CHANNELS &&
            inputAudioFormat.sampleRate > 0 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)

        if (canFilter) {
            // New stream shape: coefficients and filter memory are rebuilt on the next snapshot.
            filterBank.prepare(inputAudioFormat.sampleRate, channelCount)
        }

        currentSettings = GraphicEqSettings.BYPASS

        return outputFormat
    }

    override fun isActive(): Boolean = outputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive() || bytesPerFrame == 0) return

        val available = inputBuffer.remaining()
        if (available <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }

        val frames = available / bytesPerFrame
        if (frames == 0) {
            // Less than one whole frame; leave it for the caller to complete.
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }

        currentSettings = settingsProvider()

        if (!canFilter || currentSettings.isBypass) {
            copyVerbatim(inputBuffer, frames * bytesPerFrame)
            return
        }

        filterBank.apply(currentSettings)

        if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
            processFloat(inputBuffer, frames)
        } else {
            processInt16(inputBuffer, frames)
        }
    }

    /**
     * Byte-for-byte pass-through. This is the entire bypass implementation: the samples are never
     * read as numbers, so they cannot be altered.
     */
    private fun copyVerbatim(inputBuffer: ByteBuffer, byteCount: Int) {
        val output = obtainOutputBuffer(byteCount)
        val source = inputBuffer.duplicate()
        source.limit(source.position() + byteCount)
        output.put(source)
        output.flip()
        outputBuffer = output
        // Only whole frames are consumed; a trailing partial frame stays for the next call.
        inputBuffer.position(inputBuffer.position() + byteCount)
    }

    private fun processInt16(inputBuffer: ByteBuffer, frames: Int) {
        val byteCount = frames * bytesPerFrame
        val output = obtainOutputBuffer(byteCount)
        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        // The cascade's headroom comes off with the conversion to float, ahead of the filter and so ahead of the
        // saturation below (B14-2): attenuating after a clamp cannot give back the flattened peak.
        val toNormalised = filterBank.headroomGain / SHORT_SCALE

        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                val filtered = filterBank.processSample(channel, input.get().toFloat() * toNormalised)
                output.putShort(toPcm16(filtered))
            }
        }

        output.flip()
        outputBuffer = output
        inputBuffer.position(inputBuffer.position() + byteCount)
    }

    private fun processFloat(inputBuffer: ByteBuffer, frames: Int) {
        val byteCount = frames * bytesPerFrame
        val output = obtainOutputBuffer(byteCount)
        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val headroom = filterBank.headroomGain

        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                val filtered = filterBank.processSample(channel, input.get() * headroom)
                // Clamp only where a sample genuinely exceeds full scale; NaN cannot reach here
                // because the filter bank turns any non-finite result into digital silence.
                output.putFloat(filtered.coerceIn(-1f, 1f))
            }
        }

        output.flip()
        outputBuffer = output
        inputBuffer.position(inputBuffer.position() + byteCount)
    }

    /**
     * Converts a normalised sample back to 16-bit PCM with saturation.
     *
     * A graphic EQ with positive gain can legitimately drive a sample past full scale. The
     * saturating conversion is deterministic and content-independent: it limits the value to the
     * representable range instead of wrapping, so no amount of boost can turn a loud positive
     * sample into a loud negative one.
     */
    internal fun toPcm16(sample: Float): Short {
        val scaled = sample * SHORT_SCALE
        return when {
            scaled >= Short.MAX_VALUE.toFloat() -> Short.MAX_VALUE
            scaled <= Short.MIN_VALUE.toFloat() -> Short.MIN_VALUE
            else -> scaled.toInt().toShort()
        }
    }

    /**
     * Returns the scratch buffer sized for [requiredCapacity], growing it when needed.
     *
     * Reuse is safe because media3 never queues new input while it still holds un-drained output
     * from this processor: `AudioProcessingPipeline.processData` skips a processor whose output
     * buffer has remaining bytes.
     */
    private fun obtainOutputBuffer(requiredCapacity: Int): ByteBuffer {
        var buffer = scratchBuffer
        if (buffer.capacity() < requiredCapacity || buffer === AudioProcessor.EMPTY_BUFFER) {
            buffer = ByteBuffer.allocateDirect(requiredCapacity).order(ByteOrder.nativeOrder())
            scratchBuffer = buffer
        }
        buffer.clear()
        return buffer
    }

    override fun getOutput(): ByteBuffer {
        val pending = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return pending
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun flush(streamMetadata: StreamMetadata) {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        currentSettings = GraphicEqSettings.BYPASS
        // Seek/flush semantics: discard filter history so the new stream starts from silence.
        // Coefficients are kept; only the delay lines are cleared.
        filterBank.resetState()
    }

    override fun reset() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        scratchBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        inputFormat = AudioFormat.NOT_SET
        outputFormat = AudioFormat.NOT_SET
        channelCount = 0
        bytesPerFrame = 0
        canFilter = false
        currentSettings = GraphicEqSettings.BYPASS
        filterBank.prepare(0, 0)
    }

    /**
     * Width of one sample of [encoding], or `0` for an encoding this processor does not recognise.
     *
     * Only 16-bit and float reach the filter, but every PCM width is described here because
     * [bytesPerFrame] gates consumption: a wrong width would slice frames at the wrong byte offsets
     * and leave the stream misaligned, whereas an unrecognised encoding simply yields a frame size
     * of zero and the buffer is left untouched.
     */
    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
        C.ENCODING_PCM_DOUBLE -> 8
        else -> 0
    }
}
