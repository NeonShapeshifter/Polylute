package com.decent.usbaudio.media3

/**
 * Folds a multichannel PCM frame into stereo for a two-channel DAC.
 *
 * Channel order is Android's (and Media3's) canonical order for each count:
 * 3 = FL FR FC · 4 = FL FR BL BR · 5 = FL FR FC BL BR · 6 = FL FR FC LFE BL BR ·
 * 7 = FL FR FC LFE BC SL SR · 8 = FL FR FC LFE BL BR SL SR.
 *
 * Coefficients follow the usual ITU-R BS.775 fold-down: centre and surrounds at −3 dB (0.7071)
 * into their side, a back-centre at −3 dB into both, and the LFE dropped (it is band-limited
 * effect content, and summing it into small drivers is what makes naïve downmixes boom and clip).
 * Each output is then normalised by the sum of the gains feeding it, so a full-scale signal on
 * every input channel cannot exceed full scale: this runs on the path that has no limiter.
 */
object StereoDownmix {
    private const val MINUS_3_DB = 0.70710677f

    /** Left and right gain for every input channel of a [channels]-channel frame. */
    fun gains(channels: Int): Array<FloatArray> {
        val left = FloatArray(channels)
        val right = FloatArray(channels)
        fun side(index: Int, l: Float, r: Float) {
            if (index < channels) { left[index] = l; right[index] = r }
        }
        side(0, 1f, 0f)
        side(1, 0f, 1f)
        when (channels) {
            3 -> side(2, MINUS_3_DB, MINUS_3_DB)
            4 -> { side(2, MINUS_3_DB, 0f); side(3, 0f, MINUS_3_DB) }
            5 -> { side(2, MINUS_3_DB, MINUS_3_DB); side(3, MINUS_3_DB, 0f); side(4, 0f, MINUS_3_DB) }
            6 -> { side(2, MINUS_3_DB, MINUS_3_DB); side(4, MINUS_3_DB, 0f); side(5, 0f, MINUS_3_DB) }
            7 -> {
                side(2, MINUS_3_DB, MINUS_3_DB); side(4, MINUS_3_DB, MINUS_3_DB)
                side(5, MINUS_3_DB, 0f); side(6, 0f, MINUS_3_DB)
            }
            8 -> {
                side(2, MINUS_3_DB, MINUS_3_DB)
                side(4, MINUS_3_DB, 0f); side(5, 0f, MINUS_3_DB)
                side(6, MINUS_3_DB, 0f); side(7, 0f, MINUS_3_DB)
            }
            else -> for (index in 2 until channels) {
                // Unknown layout: split the extra channels evenly rather than guess their roles.
                if (index % 2 == 0) side(index, MINUS_3_DB, 0f) else side(index, 0f, MINUS_3_DB)
            }
        }
        val leftSum = left.sum().coerceAtLeast(1f)
        val rightSum = right.sum().coerceAtLeast(1f)
        for (index in 0 until channels) {
            left[index] /= leftSum
            right[index] /= rightSum
        }
        return arrayOf(left, right)
    }

    /** Downmixes [frames] interleaved frames of [channels] channels from [source] into [stereo]. */
    fun apply(source: FloatArray, channels: Int, stereo: FloatArray, frames: Int) {
        val (left, right) = gains(channels)
        for (frame in 0 until frames) {
            val base = frame * channels
            var l = 0f
            var r = 0f
            for (channel in 0 until channels) {
                val sample = source[base + channel]
                l += sample * left[channel]
                r += sample * right[channel]
            }
            stereo[frame * 2] = l
            stereo[frame * 2 + 1] = r
        }
    }
}
