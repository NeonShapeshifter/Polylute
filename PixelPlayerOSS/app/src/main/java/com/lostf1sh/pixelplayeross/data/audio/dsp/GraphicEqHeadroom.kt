package com.lostf1sh.pixelplayeross.data.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * How far the Graphic EQ cascade lifts its loudest frequency, measured on the sections the bank actually
 * designed for the stream's own sample rate (B14-2, audit A-01).
 *
 * The bands are applied in cascade and boosts that overlap add up in dB, so the loudest point of a curve is not
 * the highest slider: all ten bands at +15 dB lift the cascade by +23.7 dB and all fifteen by +25.2 dB. The
 * headroom a boost needs is that peak, taken over the whole band up to Nyquist. The caller takes it off *before*
 * the cascade, ahead of every point where the signal can saturate, so a sinusoid at the loudest frequency of the
 * curve leaves the EQ at the level it entered with, never above it.
 *
 * What this is, and is not: it is the steady-state bound of the cascade, the standard preamp of a graphic EQ.
 * It holds for every stationary sinusoid; broadband programme can exceed it for a few samples (the peak of the
 * impulse response's absolute sum is well above the peak of its transfer), and the final saturation of the two
 * paths stays as the safety net for that.
 *
 * The estimate is the highest of [POINTS] log-spaced samples of the cascade's response, from [MIN_HZ] to Nyquist,
 * with every local maximum of that grid refined by the parabola through its two neighbours (a peak between two
 * samples is otherwise under-read by a few hundredths of a dB when boosts of opposite sign sit close together),
 * and [SAFETY_MARGIN_DB] added on top. A curve that only cuts has its supremum at DC and Nyquist, where a peaking
 * section is exactly unity, so it costs no headroom.
 *
 * The trigonometry depends on the rate alone and is tabulated once; a recompute is multiplications.
 */
internal class GraphicEqHeadroom(sampleRateHz: Int) {

    private val cosW = DoubleArray(POINTS)
    private val sinW = DoubleArray(POINTS)
    private val cos2W = DoubleArray(POINTS)
    private val sin2W = DoubleArray(POINTS)

    /** |H|² of the cascade at each grid point; reused by every [peakGainDb], which only ever runs on the audio thread. */
    private val magnitudeSquared = DoubleArray(POINTS)

    init {
        val nyquist = sampleRateHz / 2.0
        val minHz = minOf(MIN_HZ, nyquist / 2.0)
        for (i in 0 until POINTS) {
            val hz = minHz * (nyquist / minHz).pow(i.toDouble() / (POINTS - 1))
            val w = 2.0 * PI * hz / sampleRateHz
            cosW[i] = cos(w)
            sinW[i] = sin(w)
            cos2W[i] = cos(2.0 * w)
            sin2W[i] = sin(2.0 * w)
        }
    }

    /**
     * The loudest point of the cascade of [activeBands] of [sections], in dB: 0 when the curve only cuts, else the
     * peak plus [SAFETY_MARGIN_DB].
     */
    fun peakGainDb(sections: Array<PeakingEqBiquad>, activeBands: IntArray): Float {
        if (activeBands.isEmpty()) return 0f
        for (i in 0 until POINTS) {
            var product = 1.0
            for (band in activeBands) {
                product *= sections[band].magnitudeSquared(cosW[i], sinW[i], cos2W[i], sin2W[i])
            }
            magnitudeSquared[i] = product
        }
        var peakDb = Double.NEGATIVE_INFINITY
        for (i in 0 until POINTS) {
            val here = magnitudeSquared[i]
            val left = if (i > 0) magnitudeSquared[i - 1] else Double.NEGATIVE_INFINITY
            val right = if (i < POINTS - 1) magnitudeSquared[i + 1] else Double.NEGATIVE_INFINITY
            if (here < left || here < right) continue  // not a local maximum of the grid
            var db = TEN_OVER_LN10 * ln(here)
            if (left > 0.0 && right > 0.0) {
                val y0 = TEN_OVER_LN10 * ln(left)
                val y2 = TEN_OVER_LN10 * ln(right)
                val curvature = y0 - 2.0 * db + y2
                // A true local peak bends down; the vertex of the parabola is never below the sample.
                if (curvature < 0.0) db -= (y2 - y0) * (y2 - y0) / (8.0 * curvature)
            }
            if (db > peakDb) peakDb = db
        }
        // A cut-only curve peaks at exactly 0 dB (DC, Nyquist); a hair above is float rounding of the coefficients.
        return if (peakDb <= FLAT_TOLERANCE_DB) 0f else (peakDb + SAFETY_MARGIN_DB).toFloat()
    }

    companion object {
        const val POINTS = 512
        const val MIN_HZ = 5.0

        /**
         * Above what the analytic response can miss, so the bound is a bound and not an estimate. The grid is
         * not the main reason: the running filter is float32 arithmetic, and at 96 kHz the measured response of a
         * 63 Hz boost sat 0.044 dB above the one its own coefficients predict (the low bands have poles very
         * close to the unit circle there). A tenth of a dB covers that with room, and costs nothing audible.
         */
        const val SAFETY_MARGIN_DB = 0.1

        /** Below this a curve counts as not boosting anything. */
        const val FLAT_TOLERANCE_DB = 0.005

        private const val TEN_OVER_LN10 = 4.342944819032518  // 10 / ln(10)
    }
}
