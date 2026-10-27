package com.lostf1sh.pixelplayeross.data.service.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * B40-F8: a look-ahead peak limiter for Resonance on the Android route: after the equalizer, so a boost never clips.
 *
 * **What it promises.** No output sample is above the ceiling ([ceilingDb], -0.5 dBFS by default), whatever comes in. A sample that
 * would be is brought down by a gain that has been falling for the [lookaheadFrames] before it, so there is no corner in the signal and
 * no click; the gain comes back up slowly ([releaseMs]), so the signal is not modulated at audio rate. **What it does not do:** nothing
 * to a signal that stays under the ceiling. Then the gain is exactly 1.0 and every sample is the input's, bit for bit (it only arrives
 * [delayFrames] frames later, which [process] and [finish] take out so that the stream keeps its length and its place).
 *
 * **How.** Each frame's peak over all channels gives the gain it would need, `ceiling / peak` (1 when under). The smallest need over
 * the last [lookaheadFrames] frames is averaged over the last [lookaheadFrames] of those: a gain that starts falling a whole window
 * before the peak and arrives at the needed value as the peak does (the window of the minimum and the window of the average are equal,
 * which is what makes the average no higher than any one need inside it). The channels share one gain, so the stereo image does not
 * move. The audio is delayed by `lookaheadFrames - 1` frames so that gain meets its sample. The gain is finally held to its own sample's
 * need, which is what makes the ceiling a property of the code and not of the arithmetic's rounding.
 *
 * Not thread-safe; one stream per instance. No allocation per frame.
 */
internal class PeakLimiter(
    sampleRateHz: Int,
    private val channels: Int,
    ceilingDb: Double = DEFAULT_CEILING_DB,
    lookaheadMs: Double = DEFAULT_LOOKAHEAD_MS,
    releaseMs: Double = DEFAULT_RELEASE_MS,
) {
    init {
        require(sampleRateHz > 0 && channels > 0) { "a limiter needs a rate and channels" }
    }

    val ceiling: Float = 10.0.pow(ceilingDb / 20.0).toFloat()
    val lookaheadFrames: Int = (lookaheadMs * sampleRateHz / 1000.0).roundToInt().coerceAtLeast(2)

    /** How far behind the input the output is, in frames; taken out of the stream's length by priming and flushing. */
    val delayFrames: Int = lookaheadFrames - 1

    /** What is left of a gain reduction one frame later: the recovery is exponential, held as a double so it cannot stall in float32. */
    private val releaseDecay: Double = exp(-1.0 / (releaseMs * sampleRateHz / 1000.0))

    // the audio, delayed: delayFrames + 1 frames in a ring
    private val audioSlots = delayFrames + 1
    private val audio = FloatArray(audioSlots * channels)

    // the gain each of the last lookaheadFrames frames needed, in a ring
    private val need = FloatArray(lookaheadFrames)

    // the smallest need over the last lookaheadFrames, as a monotonic deque of (frame, need)
    private val dequeFrame = LongArray(lookaheadFrames + 1)
    private val dequeNeed = FloatArray(lookaheadFrames + 1)
    private var dequeHead = 0
    private var dequeSize = 0

    // the last lookaheadFrames of that smallest need, in a ring, and their sum
    private val smallest = FloatArray(lookaheadFrames)
    private var smallestSum = 0.0

    private var frame = 0L

    /** How far under unity the (slowly recovering) gain is: 0 for no reduction. */
    private var deficit = 0.0

    /** The deepest gain reduction since the last [reset], in dB (0 when the limiter has done nothing). */
    var deepestReductionDb: Double = 0.0
        private set

    init {
        reset()
    }

    fun reset() {
        java.util.Arrays.fill(audio, 0f)
        java.util.Arrays.fill(need, 1f)
        java.util.Arrays.fill(smallest, 1f)
        smallestSum = lookaheadFrames.toDouble()
        dequeHead = 0
        dequeSize = 0
        frame = 0L
        deficit = 0.0
        deepestReductionDb = 0.0
    }

    /**
     * Takes [frames] interleaved frames from [input] and writes the frames that are ready to [output] (never more than [frames]; fewer
     * while the delay fills at the start of a stream); returns how many.
     */
    fun process(input: FloatArray, inputOffset: Int, frames: Int, output: FloatArray, outputOffset: Int): Int {
        var written = 0
        for (f in 0 until frames) {
            val base = inputOffset + f * channels
            if (step(input, base, output, outputOffset + written * channels)) written++
        }
        return written
    }

    /** The end of the stream: pushes silence through to bring out the frames still held; returns how many were written. */
    fun finish(output: FloatArray, outputOffset: Int): Int {
        val silence = FloatArray(channels)
        var written = 0
        repeat(delayFrames) {
            if (step(silence, 0, output, outputOffset + written * channels)) written++
        }
        return written
    }

    /** One frame in; true when one frame was written to [output] at [outputBase]. */
    private fun step(input: FloatArray, inputBase: Int, output: FloatArray, outputBase: Int): Boolean {
        var peak = 0f
        for (ch in 0 until channels) {
            val a = abs(input[inputBase + ch])
            if (a > peak) peak = a
        }
        val needed = if (peak > ceiling) ceiling / peak else 1f

        // the frame that comes out now went in delayFrames frames ago: it sits in the slot the next frame will take
        val slot = (frame % audioSlots).toInt()
        val oldest = ((frame + 1) % audioSlots).toInt() * channels
        val outSlot = ((frame - delayFrames) % lookaheadFrames + lookaheadFrames).toInt() % lookaheadFrames

        // the smallest need over the last lookaheadFrames frames (this one included)
        while (dequeSize > 0 && dequeNeed[(dequeHead + dequeSize - 1) % dequeNeed.size] >= needed) dequeSize--
        val tail = (dequeHead + dequeSize) % dequeNeed.size
        dequeFrame[tail] = frame
        dequeNeed[tail] = needed
        dequeSize++
        while (dequeFrame[dequeHead] <= frame - lookaheadFrames) {
            dequeHead = (dequeHead + 1) % dequeNeed.size
            dequeSize--
        }
        val smallestNow = dequeNeed[dequeHead]

        // average of the last lookaheadFrames values of the smallest need
        val ringSlot = (frame % lookaheadFrames).toInt()
        smallestSum += smallestNow - smallest[ringSlot]
        smallest[ringSlot] = smallestNow
        val averaged = (smallestSum / lookaheadFrames).toFloat().coerceAtMost(1f)

        // the need of the frame that is coming out, read before this frame's own overwrites its slot
        val needOfOutput = need[outSlot]
        need[(frame % lookaheadFrames).toInt()] = needed

        // the gain recovers slowly, and never above what the average allows (the larger deficit is the lower gain)
        val allowed = 1.0 - averaged
        val recovering = deficit * releaseDecay
        deficit = if (allowed > recovering) allowed else recovering
        // an exponential recovery never quite arrives; within two millionths of unity (0.00002 dB) it has, and the signal is the input's again
        if (deficit < SNAP_TO_UNITY_DEFICIT) deficit = 0.0
        var gain = (1.0 - deficit).toFloat()
        val ready = frame >= delayFrames
        if (ready && needOfOutput < gain) gain = needOfOutput

        var wrote = false
        if (ready) {
            for (ch in 0 until channels) output[outputBase + ch] = audio[oldest + ch] * gain
            if (gain < 1f) {
                val db = 20.0 * kotlin.math.log10(gain.toDouble())
                if (db < deepestReductionDb) deepestReductionDb = db
            }
            wrote = true
        }
        for (ch in 0 until channels) audio[slot * channels + ch] = input[inputBase + ch]
        frame++
        return wrote
    }

    companion object {
        private const val SNAP_TO_UNITY_DEFICIT = 2e-6
        const val DEFAULT_CEILING_DB = -0.5
        const val DEFAULT_LOOKAHEAD_MS = 1.5
        const val DEFAULT_RELEASE_MS = 80.0
    }
}
