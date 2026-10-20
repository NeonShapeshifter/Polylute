package com.lostf1sh.pixelplayeross.data.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * One peaking-EQ biquad section: coefficients plus a single channel's filter memory.
 *
 * ## Design
 *
 * Coefficients follow the Robert Bristow-Johnson "Audio EQ Cookbook" peaking EQ, the standard
 * reference design for a constant-Q graphic EQ:
 *
 * ```
 * A     = 10^(gainDb / 40)
 * w0    = 2*pi*f0/Fs
 * alpha = sin(w0) / (2*Q)
 *
 * b0 = 1 + alpha*A      b1 = -2*cos(w0)      b2 = 1 - alpha*A
 * a0 = 1 + alpha/A      a1 = -2*cos(w0)      a2 = 1 - alpha/A
 * ```
 *
 * all divided by `a0` before use. The response is exactly `gainDb` at `f0`, approaches 0 dB at DC
 * and at Nyquist, and is monotonic in between, so a band can only ever affect the neighbourhood
 * its Q describes.
 *
 * ## Gain convention
 *
 * `gainDb` is **dB of boost or cut**, not a linear multiplier: `+6` doubles amplitude at the band
 * centre, `-6` halves it, `0` is exactly unity. A 0 dB section produces `b == a` and therefore
 * `H(z) = 1` identically — flat bands are transparent by construction, not by a special case.
 *
 * ## State
 *
 * Transposed Direct Form II, two state variables per channel:
 *
 * ```
 * y  = b0*x + s1
 * s1 = b1*x - a1*y + s2
 * s2 = b2*x - a2*y
 * ```
 *
 * Transposed DF-II is used because it minimises the state that can accumulate magnitude error and
 * behaves well under the coefficient changes a live slider drag produces. Inactive (0 dB) sections
 * are skipped by the caller, so flat bands cost nothing.
 *
 * ## Numerics
 *
 * Coefficients are generated in `Double` and stored as `Float`. Audio and state stay `Float`,
 * matching the rest of the media3 processing chain. Denormals are flushed to zero because
 * recursive filters naturally decay into the denormal range, where some ARM cores take a large
 * per-operation penalty.
 */
internal class PeakingEqBiquad {

    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    /** True when this section is an exact identity and can be skipped entirely. */
    var isIdentity: Boolean = true
        private set

    private var s1 = 0f
    private var s2 = 0f

    fun clearState() {
        s1 = 0f
        s2 = 0f
    }

    /**
     * Recomputes coefficients for [gainDb] at [frequencyHz] for a stream running at [sampleRateHz].
     *
     * The centre frequency is clamped to 45 % of the sample rate so the section always stays
     * well-conditioned: a band at or above Nyquist would otherwise collapse to an exact identity
     * and silently stop working.
     */
    fun design(gainDb: Float, frequencyHz: Int, sampleRateHz: Int, q: Float) {
        if (gainDb == 0f) {
            b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            isIdentity = true
            return
        }

        val nyquistLimit = 0.45 * sampleRateHz
        val f0 = minOf(frequencyHz.toDouble(), nyquistLimit).coerceAtLeast(1.0)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / sampleRateHz
        val alpha = sin(w0) / (2.0 * q.toDouble())

        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha / a

        b0 = ((1.0 + alpha * a) / a0).toFloat()
        b1 = ((-2.0 * cosW0) / a0).toFloat()
        b2 = ((1.0 - alpha * a) / a0).toFloat()
        a1 = ((-2.0 * cosW0) / a0).toFloat()
        a2 = ((1.0 - alpha / a) / a0).toFloat()

        isIdentity = false
    }

    /** Processes one sample through this section. */
    fun process(x: Float): Float {
        val y = b0 * x + s1
        s1 = flushDenormal(b1 * x - a1 * y + s2)
        s2 = flushDenormal(b2 * x - a2 * y)
        return y
    }

    private fun flushDenormal(value: Float): Float =
        if (value > -DENORMAL_FLOOR && value < DENORMAL_FLOOR) 0f else value

    /**
     * |H|² of the coefficients this section is running, at a frequency given as the cosine and sine of its angle and of
     * twice it (so a sweep over many sections tabulates them once). Read from the stored `Float` coefficients, not
     * from a second design, so it describes what the filter does.
     */
    fun magnitudeSquared(cosW: Double, sinW: Double, cos2W: Double, sin2W: Double): Double {
        if (isIdentity) return 1.0
        val numeratorReal = b0 + b1 * cosW + b2 * cos2W
        val numeratorImag = b1 * sinW + b2 * sin2W
        val denominatorReal = 1.0 + a1 * cosW + a2 * cos2W
        val denominatorImag = a1 * sinW + a2 * sin2W
        val denominator = denominatorReal * denominatorReal + denominatorImag * denominatorImag
        if (denominator <= 0.0) return 1.0
        return (numeratorReal * numeratorReal + numeratorImag * numeratorImag) / denominator
    }

    /** `[b0, b1, b2, a1, a2]`, for tests that need to prove one band cannot alter another. */
    internal fun coefficientsForTest(): FloatArray = floatArrayOf(b0, b1, b2, a1, a2)

    private companion object {
        /**
         * ~1e-25, far below the 16-bit noise floor (−90 dBFS ≈ 3e-3 in normalised units) yet far
         * above the IEEE-754 single-precision denormal threshold (~1.2e-38), so flushing here is
         * inaudible and cannot affect a bit-exact comparison of the processed signal.
         */
        const val DENORMAL_FLOOR = 1e-25f
    }
}
