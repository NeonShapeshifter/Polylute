package com.lostf1sh.pixelplayeross.presentation.visualizer

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Turns the platform's raw FFT into bars that mean something.
 *
 * Two jobs, both of which are the difference between a visualizer and decoration:
 *
 * 1. **Band mapping.** The platform hands back linearly-spaced FFT bins. Drawn linearly they pile
 *    almost everything musical into the leftmost few bars and spend the rest of the width on
 *    near-silent treble. The mapping here is logarithmic across the audible range, so each bar
 *    covers a comparable musical interval rather than a comparable number of hertz.
 *
 * 2. **Smoothing.** Raw FFT frames jitter on every capture. Bars driven straight from them flicker
 *    rather than move. Attack is fast and release is slower, which is what makes a bar follow a
 *    note in and fall away naturally instead of strobing.
 *
 * Pure, so the shaping can be tested without an audio session or a device.
 */
object VisualizerBands {

    /** Bands drawn. Within the 24–40 the design calls for. */
    const val BAND_COUNT = 32

    /** The audible window the bands cover. Below and above this the platform's bins are noise. */
    const val MIN_HZ = 40.0
    const val MAX_HZ = 16_000.0

    /** How fast a bar rises toward a louder value. */
    const val ATTACK = 0.55f

    /** How fast it falls away. Slower, so a note leaves a trail instead of blinking out. */
    const val RELEASE = 0.18f

    /**
     * Compresses the platform's 0–255 magnitude. A straight linear draw spends most of its height
     * on the loudest few bands and flattens everything else against the floor.
     */
    private const val AMPLITUDE_EXPONENT = 0.6f

    /** Sample rate the FFT was taken at; the platform documents this as fixed. */
    private const val SAMPLE_RATE = 44_100.0

    /**
     * Normalises a captured frame of magnitudes into 0..1 bands.
     *
     * [fft] is the platform's magnitude array. The API returns **decibels**, where `0` is full
     * scale and large negative values are silence — so a frame of zeros is the loudest input there
     * is, not the quietest. Writing that the wrong way round would invert the entire display.
     */
    fun normalise(fft: ByteArray, bandCount: Int = BAND_COUNT): FloatArray {
        val out = FloatArray(bandCount)
        if (fft.isEmpty() || bandCount <= 0) return out

        val binCount = fft.size
        val nyquist = SAMPLE_RATE / 2.0

        for (band in 0 until bandCount) {
            val lowHz = bandEdge(band, bandCount, MIN_HZ, MAX_HZ)
            val highHz = bandEdge(band + 1, bandCount, MIN_HZ, MAX_HZ)

            val lowBin = ((lowHz / nyquist) * binCount).toInt().coerceIn(0, binCount - 1)
            val highBin = ((highHz / nyquist) * binCount).toInt().coerceIn(lowBin + 1, binCount)

            var sum = 0f
            var n = 0
            for (bin in lowBin until highBin) {
                // Undo the decibel scale before averaging, so a quiet band is not averaged away by
                // a loud neighbour in log space.
                val linear = 10.0.pow(fft[bin].toDouble() / 20.0).toFloat()
                sum += linear
                n++
            }
            val mean = if (n == 0) 0f else sum / n
            out[band] = mean.coerceIn(0f, 1f).pow(AMPLITUDE_EXPONENT)
        }
        return out
    }

    /**
     * The frequency at the edge of a band, spaced logarithmically.
     *
     * Equivalent intervals, not equivalent hertz: the jump from 40 Hz to 80 Hz is as wide a bar as
     * the jump from 8 kHz to 16 kHz, which is roughly how the ear divides the same range.
     */
    internal fun bandEdge(index: Int, bandCount: Int, minHz: Double, maxHz: Double): Double {
        val t = index.toDouble() / bandCount.toDouble()
        return minHz * (maxHz / minHz).pow(t)
    }

    /**
     * Moves [previous] toward [next] with a fast attack and a slower release.
     *
     * Asymmetric on purpose: rising immediately is what makes a bar feel connected to the note,
     * and falling slowly is what stops it strobing. Symmetric smoothing either lags the attack or
     * flickers on the release — both were audible in the first version of this.
     */
    fun smooth(previous: FloatArray, next: FloatArray): FloatArray {
        if (previous.size != next.size) return next.copyOf()
        val out = FloatArray(next.size)
        for (i in next.indices) {
            val target = next[i]
            val current = previous[i]
            val rate = if (target > current) ATTACK else RELEASE
            out[i] = current + (target - current) * rate
        }
        return out
    }

    /**
     * Overall level, 0..1.
     *
     * Deliberately NOT an automatic gain control. Normalising every frame to its own peak would
     * make a quiet passage and a loud one look identical, which destroys the one thing a visualizer
     * can honestly convey. This reports the level; it does not equalise it.
     */
    fun level(bands: FloatArray): Float {
        if (bands.isEmpty()) return 0f
        var sum = 0f
        for (b in bands) sum += b * b
        return sqrt(sum / bands.size).coerceIn(0f, 1f)
    }

    /** Whether two frames are close enough to skip a redraw. */
    fun isVisuallyIdentical(a: FloatArray, b: FloatArray, epsilon: Float = 0.004f): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (abs(a[i] - b[i]) > epsilon) return false
        return true
    }

    /** Decay applied to every band once observation stops. */
    fun decay(bands: FloatArray, rate: Float = RELEASE): FloatArray =
        FloatArray(bands.size) { (bands[it] - rate).coerceAtLeast(0f) }

    /** A convenience for the logarithm used by the mapping, kept for readability at call sites. */
    internal fun logRatio(minHz: Double, maxHz: Double): Double = ln(maxHz / minHz)
}
