package com.lostf1sh.pixelplayeross.data.media

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Feeds a [WaveformBuilder] from the decoder's PCM, reading **one frame in every [stride]** (B31 P2.5).
 *
 * A waveform keeps 1,024 buckets however long the track is, so a 4:13 track puts about 10,900 frames into each bucket, and the peak and
 * RMS of a bucket do not need all of them. Reading every frame meant converting 22 million samples to float and comparing each one: the
 * Kotlin half of the 7.4 s the phone took (a debug build is never compiled ahead of time). With [strideFor] the same track reads about
 * 1,100 frames per bucket, a tenth of the work.
 *
 * **What it costs in the picture.** The RMS is the same to a fraction of a percent. The peak is the loudest of the frames read, not of
 * every frame: it can miss a one-sample click between two frames read, and then shows the loudest neighbour (a bar a few per cent
 * shorter at worst on a hard transient). The buckets stay equal slices of *time*: the builder is told how many frames it will be given
 * (`totalFrames / stride`), so the bucket a frame lands in is the same as when every frame is read.
 *
 * The phase of the stride runs on across buffers (the codec's buffers are not a multiple of it), so the result does not depend on how
 * the decoder happens to cut its output. With a stride of 1 every frame is read, exactly as before.
 */
internal class PcmReducer(
    private val builder: WaveformBuilder,
    private val stride: Int,
) {
    var channelCount: Int = 1
    var pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

    /** Frames to skip at the start of the next buffer before the next one that is read. */
    private var untilNext = 0
    private var scratch = FloatArray(0)

    init {
        require(stride >= 1) { "stride must be at least 1" }
    }

    fun accept(buffer: ByteBuffer, offset: Int, byteCount: Int) {
        val channels = channelCount.coerceAtLeast(1)
        val bytesPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val frameCount = byteCount / (bytesPerSample * channels)
        if (frameCount <= 0) return
        if (untilNext >= frameCount) {
            untilNext -= frameCount
            return
        }

        buffer.order(ByteOrder.nativeOrder())
        buffer.position(offset)
        buffer.limit(offset + byteCount)

        val taken = (frameCount - 1 - untilNext) / stride + 1
        val needed = taken * channels
        if (scratch.size < needed) scratch = FloatArray(needed)
        val samples = scratch

        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                for (t in 0 until taken) {
                    val from = (untilNext + t * stride) * channels
                    for (c in 0 until channels) samples[t * channels + c] = floats.get(from + c)
                }
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                // 8-bit PCM from MediaCodec is unsigned with 128 as the zero point.
                for (t in 0 until taken) {
                    val from = (untilNext + t * stride) * channels
                    for (c in 0 until channels) {
                        samples[t * channels + c] = (buffer.get(offset + from + c).toInt() and 0xFF) / 128f - 1f
                    }
                }
            }

            else -> {
                val shorts = buffer.asShortBuffer()
                for (t in 0 until taken) {
                    val from = (untilNext + t * stride) * channels
                    for (c in 0 until channels) samples[t * channels + c] = shorts.get(from + c) / 32768f
                }
            }
        }

        builder.accept(samples, taken, channels)
        untilNext = untilNext + taken * stride - frameCount
    }

    companion object {
        /** Each bucket keeps at least this many frames read, so its peak and RMS rest on a thousand samples. */
        const val MIN_FRAMES_PER_BUCKET = 1024

        /** However long and however fast the track, no more than one frame in this many is skipped. */
        const val MAX_STRIDE = 64

        /** The stride for a track of [totalFrames] frames in [bucketCount] buckets: 1 for a short track, about 10 for a 4:13 at 44.1 kHz. */
        fun strideFor(totalFrames: Long, bucketCount: Int): Int {
            if (totalFrames <= 0 || bucketCount <= 0) return 1
            return (totalFrames / bucketCount / MIN_FRAMES_PER_BUCKET).coerceIn(1L, MAX_STRIDE.toLong()).toInt()
        }

        /** What the builder is told to expect: the frames that will be read. */
        fun framesRead(totalFrames: Long, stride: Int): Long = ((totalFrames + stride - 1) / stride).coerceAtLeast(1L)
    }
}
