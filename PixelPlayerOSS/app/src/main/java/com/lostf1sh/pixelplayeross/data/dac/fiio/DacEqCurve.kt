package com.lostf1sh.pixelplayeross.data.dac.fiio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * B30-P2: what a set of PEQ filters does to the level, and the pre-gain that keeps the net boost at or below 0 dB.
 *
 * The rule (B30 rule 6): **a DAC EQ must never make the output louder than Safety calibrated.** The DAC does not manage its own
 * headroom ("deviceHandlesPregain": false in DevicePEQ's constraints for the Melody), so the app writes the global gain:
 *
 *     attenuation = (highest positive peak of the evaluated curve), rounded up to the tenth the DAC stores
 *
 * so that `peak − attenuation ≤ 0` (a small guard is added to the peak, so the rounding can only go one way). **Which register value
 * is that attenuation** depends on what the global gain means (B31 P1.2, [PreGainSense]): under the signed reading it is `−attenuation`,
 * under the other `+attenuation`, and while the sense is unconfirmed no register but 0 is safe under both. The functions here that take
 * a [PreGainSense] are the ones a write is judged with; the two that do not are the signed reading, kept for the tests and for the reader
 * of the first model.
 *
 * **How the curve is evaluated.** RBJ biquads (peaking and the two shelves, the three types the app writes), summed in dB, over
 * 20 Hz–20 kHz at 1/96 octave plus every band's own centre. The DAC's firmware is not ours, so the evaluation takes the worst of
 * several readings of it, and the peak is the highest of them:
 * - sample rates 44.1, 48, 96 and 192 kHz (a biquad's shape near Nyquist depends on the rate it runs at);
 * - the filter as asked, and a **peaking filter widened** by the RBJ amplitude (`Q / A`, `A = 10^(|gain|/40)`): DevicePEQ
 *   measured that FiiO DACs realise a peaking filter's bandwidth that way (QX13 and KA17, REW; `fiioUsbHidHandler.js`,
 *   0BSD), which makes neighbouring boosts overlap more than the nominal Q says. Shelves use the peaking alpha, as the same notes
 *   say the device does.
 *
 * The DAC's real response is **not measured here**; this is a bound with a margin, not a measurement.
 */
object DacEqCurve {
    private const val MIN_HZ = 20.0
    private const val MAX_HZ = 20_000.0
    private const val POINTS_PER_OCTAVE = 96
    private val SAMPLE_RATES = doubleArrayOf(44_100.0, 48_000.0, 96_000.0, 192_000.0)

    /** Added to the peak before the pre-gain is rounded down: a sampled maximum between two grid points can only be lower. */
    const val GUARD_DB = 0.02

    private val grid: DoubleArray by lazy {
        val octaves = ln(MAX_HZ / MIN_HZ) / ln(2.0)
        DoubleArray((octaves * POINTS_PER_OCTAVE).toInt() + 1) { MIN_HZ * 2.0.pow(it.toDouble() / POINTS_PER_OCTAVE) }
    }

    /** The response of [bands], in dB, at [frequenciesHz], read as a plain biquad bank at [sampleRate] (nominal Q). */
    fun responseDb(bands: List<DacEqBand>, frequenciesHz: DoubleArray, sampleRate: Double = 48_000.0): DoubleArray =
        DoubleArray(frequenciesHz.size) { i -> bandsDb(bands, frequenciesHz[i], sampleRate, widened = false) }

    /**
     * A peak is expensive (four rates, two readings, a 665-point grid) and the same curve is asked for again and again: by the planner
     * for each order it tries, by the gate's replay of every step, by the writer's log, by the screen. The answer depends on the bands
     * alone, so it is remembered (a small least-recently-used map).
     */
    private val peaks = object : LinkedHashMap<List<DacEqBand>, Double>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<DacEqBand>, Double>?) = size > PEAK_CACHE_SIZE
    }
    private const val PEAK_CACHE_SIZE = 256

    /**
     * The highest level change the filters can produce anywhere in 20 Hz–20 kHz, in dB, on the worst reading described above.
     * Positive means "louder than the input" somewhere; the pre-gain exists to take it back to 0.
     */
    fun peakBoostDb(bands: List<DacEqBand>): Double {
        synchronized(peaks) { peaks[bands] }?.let { return it }
        return computePeakBoostDb(bands).also { synchronized(peaks) { peaks[bands.toList()] = it } }
    }

    private fun computePeakBoostDb(bands: List<DacEqBand>): Double {
        val active = bands.filter { it.type?.isGainFilter == true && it.gainTenthsDb != 0 }
        if (active.isEmpty()) return 0.0
        val frequencies = grid + active.map { it.frequencyHz.toDouble().coerceIn(MIN_HZ, MAX_HZ) }
        var peak = Double.NEGATIVE_INFINITY
        for (rate in SAMPLE_RATES) {
            for (widened in booleanArrayOf(false, true)) {
                for (f in frequencies) {
                    if (f >= rate / 2.0) continue
                    peak = max(peak, bandsDb(active, f, rate, widened))
                }
            }
        }
        return peak
    }

    /** The attenuation, in tenths of a dB (≥ 0), that brings [peakBoostDb] to 0 or below. 0 for a curve that never boosts. */
    fun attenuationTenthsFor(bands: List<DacEqBand>): Int {
        val peak = peakBoostDb(bands)
        // A curve that never rises above 0 dB (cuts only) needs nothing; the guard is for a peak that is there but sampled low.
        if (peak <= 0.0) return 0
        return ceil((peak + GUARD_DB) * 10.0 - 1e-9).toInt()
    }

    /**
     * The register (`SET 17`), in tenths of a dB, that holds [bands] at or below 0 dB under [sense]; **null when [sense] cannot say**:
     * a curve that rises above 0 dB cannot be protected while the sense is [PreGainSense.UNCONFIRMED], because the register that
     * attenuates under one reading amplifies under the other.
     */
    fun preGainTenthsFor(bands: List<DacEqBand>, sense: PreGainSense): Int? =
        sense.registerForAttenuation(attenuationTenthsFor(bands))

    /** The signed reading (positive = louder): a curve's pre-gain, ≤ 0. For the tests; a write is judged with the overload that takes a [PreGainSense]. */
    fun preGainTenthsFor(bands: List<DacEqBand>): Int = preGainTenthsFor(bands, PreGainSense.BOOST)!!

    /** The net peak: the curve's highest point after the DAC's global gain, the worse of the readings [sense] allows. At or below 0 is the rule. */
    fun netPeakDb(bands: List<DacEqBand>, preGainTenthsDb: Int, sense: PreGainSense): Double =
        peakBoostDb(bands) + sense.offsetDb(preGainTenthsDb)

    /** The net peak under each single reading [sense] allows (one for a confirmed sense, two while it is not). */
    fun netPeaksDb(bands: List<DacEqBand>, preGainTenthsDb: Int, sense: PreGainSense): List<Double> {
        val peak = peakBoostDb(bands)
        return sense.readings.map { peak + it.offsetDb(preGainTenthsDb) }
    }

    /** The signed reading of [netPeakDb]. For the tests. */
    fun netPeakDb(bands: List<DacEqBand>, preGainTenthsDb: Int): Double = netPeakDb(bands, preGainTenthsDb, PreGainSense.BOOST)

    /**
     * The curve the screen draws, nominal at 48 kHz, with the pre-gain applied, at [frequenciesHz]. [sense]: how the register moves the
     * curve (while it is unconfirmed the screen draws the signed reading, the one the sources describe).
     */
    fun curveDb(bands: List<DacEqBand>, preGainTenthsDb: Int, frequenciesHz: DoubleArray, sense: PreGainSense = PreGainSense.BOOST): DoubleArray {
        val offset = if (sense == PreGainSense.UNCONFIRMED) PreGainSense.BOOST.offsetDb(preGainTenthsDb) else sense.offsetDb(preGainTenthsDb)
        return responseDb(bands, frequenciesHz).also { r -> for (i in r.indices) r[i] += offset }
    }

    private fun bandsDb(bands: List<DacEqBand>, frequencyHz: Double, sampleRate: Double, widened: Boolean): Double {
        var db = 0.0
        for (band in bands) db += bandDb(band, frequencyHz, sampleRate, widened)
        return db
    }

    /** One filter's magnitude at [frequencyHz], in dB (RBJ cookbook). Types the app does not evaluate contribute nothing. */
    private fun bandDb(band: DacEqBand, frequencyHz: Double, sampleRate: Double, widened: Boolean): Double {
        val type = band.type ?: return 0.0
        if (!type.isGainFilter || band.gainTenthsDb == 0 || band.frequencyHz <= 0 || band.qHundredths <= 0) return 0.0
        val gainDb = band.gainDb
        val a = 10.0.pow(gainDb / 40.0)
        var q = band.q
        if (widened && type == FiioFilterType.PEAK) q /= 10.0.pow(abs(gainDb) / 40.0)
        val w0 = 2.0 * PI * band.frequencyHz.toDouble().coerceAtMost(sampleRate / 2.0 - 1.0) / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        when (type) {
            FiioFilterType.PEAK -> {
                b0 = 1.0 + alpha * a; b1 = -2.0 * cosW0; b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a; a1 = -2.0 * cosW0; a2 = 1.0 - alpha / a
            }
            FiioFilterType.LOW_SHELF -> {
                val s = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + s)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - s)
                a0 = (a + 1.0) + (a - 1.0) * cosW0 + s
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                a2 = (a + 1.0) + (a - 1.0) * cosW0 - s
            }
            FiioFilterType.HIGH_SHELF -> {
                val s = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + s)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - s)
                a0 = (a + 1.0) - (a - 1.0) * cosW0 + s
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                a2 = (a + 1.0) - (a - 1.0) * cosW0 - s
            }
            else -> return 0.0
        }
        val w = 2.0 * PI * frequencyHz / sampleRate
        val cw = cos(w)
        val sw = sin(w)
        val c2w = cos(2.0 * w)
        val s2w = sin(2.0 * w)
        val numRe = b0 + b1 * cw + b2 * c2w
        val numIm = -(b1 * sw + b2 * s2w)
        val denRe = a0 + a1 * cw + a2 * c2w
        val denIm = -(a1 * sw + a2 * s2w)
        val magnitude = hypot(numRe, numIm) / hypot(denRe, denIm)
        return 20.0 * log10(magnitude)
    }
}
