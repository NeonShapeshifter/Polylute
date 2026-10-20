package com.lostf1sh.pixelplayeross.data.audio.dsp

/**
 * Glides the Graphic EQ's band gains to a new curve over [rampMs] instead of jumping (AUD-111).
 *
 * With the stage applied at write time, a slider move is heard tens of milliseconds later; switching
 * every biquad's coefficients in one step at that point is audible as a click on a large move. The
 * stage asks for the gains of each sub-block through [advance], which interpolates linearly from the
 * gains in effect when the target changed. A different band count is a new curve and is taken as is:
 * there is nothing meaningful to interpolate between a ten- and a fifteen-band table.
 *
 * Not thread-safe: owned by the stage, which runs on one thread at a time.
 */
internal class GraphicEqGainRamp(private val rampMs: Int = DEFAULT_RAMP_MS) {
    var current: FloatArray = FloatArray(0)
        private set

    private var from: FloatArray = FloatArray(0)
    private var target: FloatArray = FloatArray(0)
    private var elapsedFrames = 0
    private var totalFrames = 0

    val isRamping: Boolean
        get() = totalFrames < 0 || elapsedFrames < totalFrames

    fun retarget(newTarget: FloatArray) {
        if (newTarget.contentEquals(target)) return
        if (current.size != newTarget.size) {
            current = newTarget.copyOf()
            from = current.copyOf()
            target = newTarget.copyOf()
            elapsedFrames = 0
            totalFrames = 0
            return
        }
        from = current.copyOf()
        target = newTarget.copyOf()
        elapsedFrames = 0
        totalFrames = -1   // sized on the first advance, which knows the sample rate
    }

    /** Takes [gains] at once, with no glide: a new band table or the first curve after bypass. */
    fun snap(gains: FloatArray) {
        current = gains.copyOf()
        from = current.copyOf()
        target = current.copyOf()
        elapsedFrames = 0
        totalFrames = 0
    }

    /** The gains for the next [frames]-frame sub-block. */
    fun advance(frames: Int, sampleRateHz: Int): FloatArray {
        if (totalFrames < 0) totalFrames = (sampleRateHz.toLong() * rampMs / 1000).toInt().coerceAtLeast(1)
        if (!isRamping) return current
        elapsedFrames = (elapsedFrames + frames).coerceAtMost(totalFrames)
        val t = elapsedFrames.toFloat() / totalFrames
        current = FloatArray(target.size) { i -> if (t >= 1f) target[i] else from[i] + (target[i] - from[i]) * t }
        return current
    }

    companion object {
        const val DEFAULT_RAMP_MS = 15
    }
}
