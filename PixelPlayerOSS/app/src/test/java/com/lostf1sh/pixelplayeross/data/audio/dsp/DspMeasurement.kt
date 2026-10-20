package com.lostf1sh.pixelplayeross.data.audio.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic measurement helpers shared by the Graphic EQ tests.
 *
 * Everything here is closed-form arithmetic over a fixed number of samples: no random input, no
 * timing, no floating-point environment dependence. The same call produces the same number on
 * every machine, which is what lets the tests assert tolerances instead of "roughly".
 */
internal object DspMeasurement {

    /**
     * Measured steady-state gain, in dB, of a single-input single-output sample function at
     * [frequencyHz].
     *
     * The signal is a unit-amplitude sine at exactly [frequencyHz]. The first second is discarded so
     * the recursive filter has fully settled — the slowest band here (25 Hz, Q ≈ 2.15) has a time
     * constant around 27 ms, so one second is more than thirty of them — and the RMS of the second
     * second is compared with the 1/√2 RMS of the input sine.
     *
     * Every band frequency in both tables is a whole number of hertz, so one second contains a whole
     * number of cycles and the steady-state RMS is well defined without windowing. Continuous sine
     * rather than an impulse response is used deliberately: it measures what a listener would hear
     * at that frequency, and it does not depend on the implementation being a biquad.
     */
    fun steadyStateGainDb(
        sampleRateHz: Int,
        frequencyHz: Double,
        process: (Float) -> Float,
    ): Double {
        val settleSamples = sampleRateHz
        val measureSamples = sampleRateHz
        val omega = 2.0 * PI * frequencyHz / sampleRateHz

        var sumOfSquares = 0.0
        for (index in 0 until settleSamples + measureSamples) {
            val input = sin(omega * index).toFloat()
            val output = process(input)
            if (index >= settleSamples) {
                sumOfSquares += output.toDouble() * output.toDouble()
            }
        }

        val outputRms = sqrt(sumOfSquares / measureSamples)
        val amplitude = outputRms * sqrt(2.0)
        return 20.0 * log10(amplitude)
    }

    /** Steady-state gain of one channel of a prepared and applied filter bank. */
    fun steadyStateGainDb(
        bank: GraphicEqFilterBank,
        sampleRateHz: Int,
        frequencyHz: Double,
        channel: Int = 0,
    ): Double = steadyStateGainDb(sampleRateHz, frequencyHz) { bank.processSample(channel, it) }

    /**
     * The measured impulse response of channel 0 of an applied [bank], [seconds] long (B14-2).
     *
     * The slowest boosted section of this EQ (25 Hz, Q ≈ 2.15, +15 dB) rings with a time constant of about
     * 0.15 s whatever the sample rate, so two seconds is more than thirteen of them. The bank is cleared first and
     * left cleared.
     */
    fun impulseResponse(bank: GraphicEqFilterBank, sampleRateHz: Int, seconds: Double = 2.0): DoubleArray {
        bank.resetState()
        val response = DoubleArray((sampleRateHz * seconds).toInt())
        for (index in response.indices) {
            response[index] = bank.processSample(0, if (index == 0) 1f else 0f).toDouble()
        }
        bank.resetState()
        return response
    }

    /**
     * The loudest point of the transfer of a measured [impulse] across [frequenciesHz], in dB, with where it is.
     *
     * Evaluated as the discrete-time Fourier transform of the samples the real recursion produced, so it knows
     * nothing about the biquad design, the band table or any estimate of the loudest point: it is what the filter
     * does, measured.
     */
    fun loudestPoint(impulse: DoubleArray, sampleRateHz: Int, frequenciesHz: DoubleArray): Pair<Double, Double> {
        var bestDb = Double.NEGATIVE_INFINITY
        var bestHz = 0.0
        for (hz in frequenciesHz) {
            val omega = 2.0 * PI * hz / sampleRateHz
            val stepRe = kotlin.math.cos(omega)
            val stepIm = -kotlin.math.sin(omega)
            var re = 1.0
            var im = 0.0
            var sumRe = 0.0
            var sumIm = 0.0
            for (sample in impulse) {
                sumRe += sample * re
                sumIm += sample * im
                val nextRe = re * stepRe - im * stepIm
                im = re * stepIm + im * stepRe
                re = nextRe
            }
            val db = 10.0 * log10(sumRe * sumRe + sumIm * sumIm)
            if (db > bestDb) {
                bestDb = db
                bestHz = hz
            }
        }
        return bestDb to bestHz
    }

    /** Log-spaced frequencies from [fromHz] to [toHz], plus [extraHz] (the band centres, where the peaks are). */
    fun sweepHz(fromHz: Double, toHz: Double, points: Int, extraHz: DoubleArray = DoubleArray(0)): DoubleArray {
        val grid = DoubleArray(points) { fromHz * Math.pow(toHz / fromHz, it.toDouble() / (points - 1)) }
        return (grid + extraHz.filter { it in fromHz..toHz }).sortedArray()
    }

    /**
     * The largest |output| of a sine of [amplitude] at [frequencyHz], once settled: [settleSeconds] discarded, then
     * at least 50 ms and three periods measured. [process] is one sample in, one sample out.
     */
    fun settledPeak(
        sampleRateHz: Int,
        frequencyHz: Double,
        amplitude: Double,
        settleSeconds: Double = 2.0,
        process: (Float) -> Float,
    ): Double {
        val settle = (sampleRateHz * settleSeconds).toInt()
        val measure = maxOf(sampleRateHz / 20, kotlin.math.ceil(3.0 * sampleRateHz / frequencyHz).toInt())
        val omega = 2.0 * PI * frequencyHz / sampleRateHz
        var peak = 0.0
        for (index in 0 until settle + measure) {
            val output = process((amplitude * sin(omega * index)).toFloat())
            if (index >= settle) peak = maxOf(peak, kotlin.math.abs(output.toDouble()))
        }
        return peak
    }

    /**
     * Largest and smallest pole magnitude of `z^2 + a1*z + a2`.
     *
     * A stable biquad has both roots strictly inside the unit circle. For a real-coefficient
     * quadratic the product of the roots is `a2` and their sum is `-a1`, so this is computed from
     * the discriminant rather than by an iterative root finder.
     */
    fun poleMagnitudes(a1: Float, a2: Float): Pair<Double, Double> {
        val discriminant = a1.toDouble() * a1.toDouble() - 4.0 * a2.toDouble()
        return if (discriminant >= 0.0) {
            val root = sqrt(discriminant)
            val first = kotlin.math.abs((-a1.toDouble() + root) / 2.0)
            val second = kotlin.math.abs((-a1.toDouble() - root) / 2.0)
            first to second
        } else {
            val magnitude = sqrt(a2.toDouble())
            magnitude to magnitude
        }
    }

    /** True when both poles of `z^2 + a1*z + a2` lie strictly inside the unit circle. */
    fun isStable(a1: Float, a2: Float): Boolean {
        val (first, second) = poleMagnitudes(a1, a2)
        return first < 1.0 && second < 1.0
    }
}
