package com.lostf1sh.pixelplayeross.data.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A time-domain summary of one audio track, stored as a fixed number of time buckets.
 *
 * This is deliberately NOT a spectrogram, an FFT, a spectrum analyser or an oscilloscope. It is
 * a downsampled envelope: for every bucket we keep the loudest single sample and the RMS energy.
 * Both are absolute magnitudes in the 0f..1f range where 1f is digital full scale, so a quiet
 * master renders as a quiet waveform and silence renders as silence. Nothing is normalised per
 * render — normalising to the loudest bucket would make a fade-out and a wall of noise look
 * identical.
 */
data class Waveform(
    /** Number of buckets. Fixed at generation time so a cached waveform is resolution-stable. */
    val bucketCount: Int,
    /** Absolute peak magnitude per bucket, 0f..1f. */
    val peaks: FloatArray,
    /** RMS energy per bucket, 0f..1f. */
    val rms: FloatArray,
    /** Sample rate of the PCM that produced this summary, for the cache header only. */
    val sampleRate: Int = 0,
    /** Track duration used to size the buckets, in milliseconds. */
    val durationMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Waveform) return false
        return bucketCount == other.bucketCount &&
            peaks.contentEquals(other.peaks) &&
            rms.contentEquals(other.rms) &&
            sampleRate == other.sampleRate &&
            durationMs == other.durationMs
    }

    override fun hashCode(): Int {
        var result = bucketCount
        result = 31 * result + peaks.contentHashCode()
        result = 31 * result + rms.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + durationMs.hashCode()
        return result
    }
}

/**
 * Builds a [Waveform] from decoded PCM, incrementally.
 *
 * Determinism is the whole point of this class, so every choice is spelled out:
 *
 *  - **Channel reduction.** Each frame is collapsed to one mono value by taking the channel with
 *    the largest absolute magnitude. Averaging would cancel out a signal that only exists in one
 *    channel; taking the loudest keeps it visible. This makes stereo and mono files comparable.
 *  - **Bucket boundaries.** Frame *n* belongs to bucket `n * bucketCount / totalFrames`. Buckets
 *    therefore represent equal slices of *time*, not equal counts of samples, and bucket 0 always
 *    starts at the first decoded frame.
 *  - **Peak.** The maximum `|mono|` seen in the bucket.
 *  - **RMS.** `sqrt(mean(mono^2))` over the bucket, so it is always <= the peak.
 *  - **No normalisation.** Values stay relative to full scale.
 *  - **Clamping.** If the decoder produces more frames than [totalFrames] estimated, the surplus
 *    keeps accumulating into the final bucket rather than being dropped or wrapped.
 *
 * Not thread-safe: feed it from a single decoding thread.
 */
class WaveformBuilder(
    private val totalFrames: Long,
    val bucketCount: Int = WAVEFORM_BUCKET_COUNT
) {
    init {
        require(bucketCount > 0) { "bucketCount must be positive" }
        require(totalFrames > 0) { "totalFrames must be positive" }
    }

    private val peaks = FloatArray(bucketCount)
    private val sumSquares = DoubleArray(bucketCount)
    private val framesPerBucket = LongArray(bucketCount)
    private var framesSeen = 0L
    private var bucket = 0
    /** First frame index that belongs to [bucket] + 1, i.e. where the bucket ends. */
    private var bucketEnd = boundaryAfter(0)

    private fun boundaryAfter(index: Int): Long = ((index + 1).toLong() * totalFrames) / bucketCount

    /**
     * Consumes [frameCount] frames of interleaved PCM from [samples].
     *
     * [samples] holds `frameCount * channelCount` values in the -1f..1f range, in the layout the
     * platform decoders use (interleaved, channel-minor).
     */
    fun accept(samples: FloatArray, frameCount: Int, channelCount: Int) {
        if (frameCount <= 0 || channelCount <= 0) return
        var index = 0
        for (frame in 0 until frameCount) {
            var mono = 0f
            for (channel in 0 until channelCount) {
                val value = samples[index + channel]
                val magnitude = if (value < 0f) -value else value
                if (magnitude > mono) mono = magnitude
            }
            index += channelCount

            // Advance the bucket pointer rather than dividing per frame: one comparison in the
            // common case, a divide only when a boundary is crossed.
            while (framesSeen >= bucketEnd && bucket < bucketCount - 1) {
                bucket++
                bucketEnd = boundaryAfter(bucket)
            }

            if (mono > peaks[bucket]) peaks[bucket] = mono
            sumSquares[bucket] += mono.toDouble() * mono.toDouble()
            framesPerBucket[bucket]++
            framesSeen++
        }
    }

    /** Convenience overload for callers that already know the whole array is one chunk. */
    fun accept(samples: FloatArray, channelCount: Int) =
        accept(samples, samples.size / channelCount, channelCount)

    fun build(sampleRate: Int = 0, durationMs: Long = 0L): Waveform {
        val outPeaks = FloatArray(bucketCount)
        val outRms = FloatArray(bucketCount)
        for (i in 0 until bucketCount) {
            outPeaks[i] = peaks[i].coerceIn(0f, 1f)
            outRms[i] = if (framesPerBucket[i] == 0L) {
                0f
            } else {
                sqrt(sumSquares[i] / framesPerBucket[i].toDouble()).toFloat().coerceIn(0f, 1f)
            }
        }
        return Waveform(
            bucketCount = bucketCount,
            peaks = outPeaks,
            rms = outRms,
            sampleRate = sampleRate,
            durationMs = durationMs
        )
    }

    val framesAccepted: Long get() = framesSeen

    companion object {
        /**
         * 1024 buckets.
         *
         * The number is fixed rather than derived from the render width so that a cached waveform
         * is stable across devices, rotations and window resizes, and so the cache key does not
         * depend on layout. 1024 is comfortably inside the "hundreds to a few thousand" range: it
         * is finer than any phone screen can resolve for a full-width waveform (a 1080 px strip
         * gets roughly one bucket per pixel) while staying tiny in memory — 2 FloatArrays of 1024
         * is 8 KB per track, so even a few thousand cached tracks are a rounding error.
         */
        const val WAVEFORM_BUCKET_COUNT = 1024

        /** Largest absolute sample across channels, used for every frame. */
        fun monoMagnitude(samples: FloatArray, offset: Int, channelCount: Int): Float {
            var mono = 0f
            for (channel in 0 until channelCount) {
                val magnitude = abs(samples[offset + channel])
                mono = max(mono, magnitude)
            }
            return mono
        }
    }
}
