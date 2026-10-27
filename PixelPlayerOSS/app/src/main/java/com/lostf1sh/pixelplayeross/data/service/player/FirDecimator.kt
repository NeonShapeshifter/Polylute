package com.lostf1sh.pixelplayeross.data.service.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * B40-F8: the design of the low-pass that stands in front of every integer sample-rate reduction in the Android route.
 *
 * The first `HiResSampleRateCapAudioProcessor` averaged groups of samples. That is a two-tap low-pass for a factor of two, and it
 * lets what is above the new Nyquist frequency through: a 180 kHz tone in a 384 kHz stream came out folded to 12 kHz only 20 dB down
 * (`competitor-study-2026-10-23/notes/backend-downsampling-check.json`), which is in the music. This is a windowed-sinc FIR instead:
 *
 *  - **Stopband.** At least [STOPBAND_ATTENUATION_DB] dB (Kaiser window) from the new Nyquist frequency up: everything that can fold
 *    is down 100 dB, measured and pinned in the tests. The requirement was 80.
 *  - **Passband.** Flat to better than 0.001 dB (the Kaiser design's ripple is about 1e-5 of the signal) from 0 Hz to
 *    [PASSBAND_FRACTION] of the new Nyquist frequency: 76.8 kHz of a 192 kHz stream, 70.6 kHz of 176.4 kHz, which is more than three
 *    times what a person hears. The transition band between that and the Nyquist frequency is where nothing of music lives in a file
 *    this fast.
 *  - **Linear phase.** The taps are symmetric, so every frequency is delayed the same: the delay is a whole number of input samples
 *    ([FirDecimator.groupDelayFrames]) and [FirDecimator] takes it out, so the output is aligned with the input.
 *
 * Pure: a rate and a factor in, taps out.
 */
internal object DecimationFilter {
    /** The design's stopband attenuation. The requirement is 80 dB; the margin is for the arithmetic being float32. */
    const val STOPBAND_ATTENUATION_DB = 100.0

    /** Where the flat part ends, as a fraction of the output stream's Nyquist frequency. */
    const val PASSBAND_FRACTION = 0.8

    /** Odd, symmetric, summing to one: the DC gain is exactly unity and the delay a whole number of samples. */
    fun taps(inputRateHz: Int, factor: Int): FloatArray {
        require(factor >= 2 && inputRateHz > 0) { "a decimator needs a factor of at least 2 and a rate" }
        val nyquistHz = inputRateHz.toDouble() / (2.0 * factor)
        val passEdgeHz = PASSBAND_FRACTION * nyquistHz
        // the middle of the transition band, as a fraction of the input rate (cycles per input sample)
        val cutoff = (passEdgeHz + nyquistHz) / 2.0 / inputRateHz.toDouble()
        val transition = (nyquistHz - passEdgeHz) / inputRateHz.toDouble()

        // Kaiser's formulas for a lowpass with attenuation A and a transition of that normalised width
        val a = STOPBAND_ATTENUATION_DB
        val beta = 0.1102 * (a - 8.7)
        var length = ceil((a - 7.95) / (14.36 * transition)).toInt() + 1
        if (length % 2 == 0) length++
        val centre = (length - 1) / 2
        val i0Beta = besselI0(beta)

        val h = DoubleArray(length)
        var sum = 0.0
        for (n in 0 until length) {
            val m = (n - centre).toDouble()
            val sinc = if (n == centre) 2.0 * cutoff else sin(2.0 * PI * cutoff * m) / (PI * m)
            val r = m / centre
            val window = besselI0(beta * sqrt((1.0 - r * r).coerceAtLeast(0.0))) / i0Beta
            h[n] = sinc * window
            sum += h[n]
        }
        return FloatArray(length) { (h[it] / sum).toFloat() }
    }

    /** The response of [taps] at [frequencyHz] for a stream at [rateHz], as a gain (not decibels). */
    fun gainAt(taps: FloatArray, frequencyHz: Double, rateHz: Int): Double {
        val centre = (taps.size - 1) / 2
        val w = 2.0 * PI * frequencyHz / rateHz
        // symmetric: the response is real after taking out the delay
        var re = taps[centre].toDouble()
        for (j in 1..centre) re += 2.0 * taps[centre - j] * kotlin.math.cos(w * j)
        return abs(re)
    }

    /** Zeroth-order modified Bessel function of the first kind, by its power series. */
    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4.0
        var k = 1
        while (term > 1e-20 * sum) {
            term *= q / (k.toDouble() * k)
            sum += term
            k++
        }
        return sum
    }
}

/**
 * A decimator by an integer [factor]: the low-pass of [DecimationFilter] and then every [factor]th sample, with the filter's delay
 * taken out and its tail flushed, so what comes out is the input's length divided by the factor and in step with it.
 *
 * It works on interleaved float frames in any block size, and **the output does not depend on the block sizes**: each output frame is
 * computed from the same window of the same input in the same order of operations whether the input arrived in one piece or in one
 * frame at a time. The first window is padded with silence before the first sample, and [finish] pads the tail the same way.
 *
 * Not thread-safe; one stream per instance.
 */
internal class FirDecimator(
    private val channels: Int,
    val factor: Int,
    private val taps: FloatArray,
) {
    init {
        require(channels > 0 && factor >= 2 && taps.size % 2 == 1) { "channels, a factor of at least 2 and odd taps" }
    }

    /** Input frames from the first tap to the one the output is centred on. */
    val groupDelayFrames: Int = (taps.size - 1) / 2

    // frames of input held: [window start .. end), starting with groupDelayFrames of silence so the first output is centred on frame 0
    private var buffer = FloatArray((groupDelayFrames + FIRST_CAPACITY_FRAMES) * channels)
    private var bufferedFrames = 0
    private var nextCentre = 0
    private var received = 0L
    private var emitted = 0L

    init {
        reset()
    }

    /** Drops the history: the next frame is the start of a stream. */
    fun reset() {
        java.util.Arrays.fill(buffer, 0, groupDelayFrames * channels, 0f)
        bufferedFrames = groupDelayFrames
        nextCentre = groupDelayFrames
        received = 0L
        emitted = 0L
    }

    /** The most output frames that [inputFrames] more input frames can produce. */
    fun maxOutputFrames(inputFrames: Int): Int = ((bufferedFrames + inputFrames) / factor) + 2

    /**
     * Takes [frames] interleaved frames from [input] (from [inputOffset]) and writes the output frames they complete to [output] (from
     * [outputOffset]); returns how many frames that was. [output] must hold [maxOutputFrames] frames.
     */
    fun process(input: FloatArray, inputOffset: Int, frames: Int, output: FloatArray, outputOffset: Int): Int {
        if (frames <= 0) return 0
        ensureCapacity(bufferedFrames + frames)
        System.arraycopy(input, inputOffset, buffer, bufferedFrames * channels, frames * channels)
        bufferedFrames += frames
        received += frames
        return drain(output, outputOffset)
    }

    /** The end of the stream: pads the tail with silence and writes the output frames still owed. */
    fun finish(output: FloatArray, outputOffset: Int): Int {
        ensureCapacity(bufferedFrames + groupDelayFrames)
        java.util.Arrays.fill(buffer, bufferedFrames * channels, (bufferedFrames + groupDelayFrames) * channels, 0f)
        bufferedFrames += groupDelayFrames
        return drain(output, outputOffset)
    }

    private fun drain(output: FloatArray, outputOffset: Int): Int {
        val owed = received / factor
        var written = 0
        val centre = groupDelayFrames
        val length = taps.size
        while (nextCentre + centre < bufferedFrames && emitted < owed) {
            val start = nextCentre - centre
            val out = outputOffset + written * channels
            for (ch in 0 until channels) {
                // symmetric taps: the pairs (j, length-1-j) share a tap, the middle one stands alone
                var acc = taps[centre] * buffer[(start + centre) * channels + ch]
                for (j in 0 until centre) {
                    acc += taps[j] * (buffer[(start + j) * channels + ch] + buffer[(start + length - 1 - j) * channels + ch])
                }
                output[out + ch] = acc
            }
            nextCentre += factor
            emitted++
            written++
        }
        // forget what no later window needs
        val consumed = (nextCentre - centre).coerceAtLeast(0)
        if (consumed > 0) {
            System.arraycopy(buffer, consumed * channels, buffer, 0, (bufferedFrames - consumed) * channels)
            bufferedFrames -= consumed
            nextCentre -= consumed
        }
        return written
    }

    private fun ensureCapacity(frames: Int) {
        val needed = frames * channels
        if (buffer.size < needed) buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
    }

    private companion object {
        const val FIRST_CAPACITY_FRAMES = 4096
    }
}
