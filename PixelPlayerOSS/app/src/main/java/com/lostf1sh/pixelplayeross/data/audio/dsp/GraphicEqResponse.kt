package com.lostf1sh.pixelplayeross.data.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * The magnitude response of the graphic equalizer's own filter bank.
 *
 * This computes the curve from the *same* peaking biquads [GraphicEqFilterBank] designs for
 * playback, rather than interpolating between band gains. A drawn curve that does not match the
 * signal path is decoration: it would show a smooth shape where the engine produces overlapping
 * peaking filters, and it would be wrong everywhere the filters interact.
 *
 * The reference rate is 48 kHz because the response of a digital peaking filter is a function of
 * frequency relative to the sample rate. The shape in the audio band is what matters here, and
 * 48 kHz is the rate the Android path runs at in the common case.
 */
object GraphicEqResponse {

    /** Frequencies the curve is sampled at, log-spaced across the audible band. */
    private const val POINT_COUNT = 96
    private const val MIN_HZ = 20.0
    private const val MAX_HZ = 20_000.0
    private const val REFERENCE_SAMPLE_RATE_HZ = 48_000

    /**
     * The response, in dB, at [POINT_COUNT] log-spaced frequencies from 20 Hz to 20 kHz.
     *
     * Summing in dB is exact here: the filters are in series and each is minimum-phase, so the
     * total magnitude is the product of the individual magnitudes.
     */
    fun curveDb(frequenciesHz: IntArray, gainsDb: List<Int>): FloatArray {
        val q = GraphicEqBands.qualityFactor(frequenciesHz)
        val pointHz = DoubleArray(POINT_COUNT) { index ->
            val t = index.toDouble() / (POINT_COUNT - 1)
            MIN_HZ * (MAX_HZ / MIN_HZ).pow(t)
        }

        val sections = frequenciesHz.mapIndexed { index, frequency ->
            val gain = gainsDb.getOrNull(index)?.toFloat() ?: 0f
            BiquadResponse(gain, frequency, q)
        }

        return FloatArray(POINT_COUNT) { point ->
            var totalDb = 0.0
            sections.forEach { section -> totalDb += section.magnitudeDb(pointHz[point]) }
            totalDb.toFloat()
        }
    }

    /** The frequency each sample of [curveDb] sits at, so an axis can be labelled honestly. */
    fun curveFrequenciesHz(): DoubleArray = DoubleArray(POINT_COUNT) { index ->
        val t = index.toDouble() / (POINT_COUNT - 1)
        MIN_HZ * (MAX_HZ / MIN_HZ).pow(t)
    }

    /**
     * One peaking section, evaluated at a frequency.
     *
     * The coefficients are the same arithmetic [PeakingEqBiquad.design] performs; they are computed
     * here rather than read back from an instance because the curve is drawn for gains the engine
     * may not be holding yet — the editor has to respond to the finger, not to the next commit.
     */
    private class BiquadResponse(gainDb: Float, frequencyHz: Int, q: Float) {
        private val identity = gainDb == 0f
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double

        init {
            if (identity) {
                b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
            } else {
                val nyquistLimit = 0.45 * REFERENCE_SAMPLE_RATE_HZ
                val f0 = minOf(frequencyHz.toDouble(), nyquistLimit).coerceAtLeast(1.0)
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * f0 / REFERENCE_SAMPLE_RATE_HZ
                val alpha = sin(w0) / (2.0 * q.toDouble())
                val cosW0 = cos(w0)
                val a0 = 1.0 + alpha / a

                b0 = (1.0 + alpha * a) / a0
                b1 = (-2.0 * cosW0) / a0
                b2 = (1.0 - alpha * a) / a0
                a1 = (-2.0 * cosW0) / a0
                a2 = (1.0 - alpha / a) / a0
            }
        }

        /** |H(e^jw)| in dB. */
        fun magnitudeDb(frequencyHz: Double): Double {
            if (identity) return 0.0
            val w = 2.0 * PI * frequencyHz / REFERENCE_SAMPLE_RATE_HZ
            val cosW = cos(w)
            val sinW = sin(w)
            val cos2W = cos(2 * w)
            val sin2W = sin(2 * w)

            val numeratorReal = b0 + b1 * cosW + b2 * cos2W
            val numeratorImag = -(b1 * sinW + b2 * sin2W)
            val denominatorReal = 1.0 + a1 * cosW + a2 * cos2W
            val denominatorImag = -(a1 * sinW + a2 * sin2W)

            val numerator = numeratorReal * numeratorReal + numeratorImag * numeratorImag
            val denominator = denominatorReal * denominatorReal + denominatorImag * denominatorImag
            if (denominator <= 0.0) return 0.0

            // |H| in dB is 20*log10(|H|), and |H|^2 is what the coefficient ratio gives, so the
            // factor is 10 rather than 20. Halving it here under-reported every band by 6 dB.
            return 10.0 * ln(numerator / denominator) / ln(10.0)
        }
    }
}
