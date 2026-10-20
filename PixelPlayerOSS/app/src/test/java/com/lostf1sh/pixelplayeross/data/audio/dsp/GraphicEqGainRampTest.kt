package com.lostf1sh.pixelplayeross.data.audio.dsp

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * AUD-111: a slider move changed the biquad coefficients in one step. With the stage now applied at
 * write time (tens of ms after the move instead of ~1.5 s), a large step would be heard as a click,
 * so the gains glide to the new curve over ~15 ms in small sub-block steps.
 */
class GraphicEqGainRampTest {

    private val rate = 44_100
    private val block = 64

    @Test
    fun `a new target is reached in about fifteen milliseconds, in small steps`() {
        val ramp = GraphicEqGainRamp(rampMs = 15)
        ramp.retarget(floatArrayOf(0f, 0f))
        ramp.advance(block, rate)
        ramp.retarget(floatArrayOf(12f, -6f))

        var previous = floatArrayOf(0f, 0f)
        var elapsedFrames = 0
        var maxStep = 0f
        while (ramp.isRamping) {
            val gains = ramp.advance(block, rate)
            elapsedFrames += block
            for (i in gains.indices) maxStep = maxOf(maxStep, abs(gains[i] - previous[i]))
            previous = gains
            assertThat(elapsedFrames).isAtMost(rate * 20 / 1000 + block)
        }
        assertThat(previous.toList()).containsExactly(12f, -6f).inOrder()
        // 12 dB over ~660 frames in 64-frame blocks: ~1.2 dB per block, never the whole step at once.
        assertThat(maxStep).isLessThan(2f)
        assertThat(elapsedFrames).isAtLeast(rate * 10 / 1000)
    }

    @Test
    fun `the first target is taken as is, with nothing to glide from`() {
        val ramp = GraphicEqGainRamp()
        ramp.retarget(floatArrayOf(3f))
        assertThat(ramp.isRamping).isFalse()
        assertThat(ramp.advance(block, rate).toList()).containsExactly(3f)
    }

    @Test
    fun `a band-count change is a new curve, not a glide`() {
        val ramp = GraphicEqGainRamp()
        ramp.retarget(FloatArray(10))
        ramp.retarget(FloatArray(15) { 1f })
        assertThat(ramp.isRamping).isFalse()
        assertThat(ramp.current.size).isEqualTo(15)
    }
}
