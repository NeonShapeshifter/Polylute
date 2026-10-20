package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.StereoDownmix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StereoDownmixTest {

    @Test
    fun `every channel count from 3 to 8 cannot exceed full scale`() {
        for (channels in 3..8) {
            val frame = FloatArray(channels) { 1f }
            val out = FloatArray(2)
            StereoDownmix.apply(frame, channels, out, 1)
            assertTrue(out[0] <= 1.0001f && out[1] <= 1.0001f, "ch=$channels out=${out.toList()}")
        }
    }

    @Test
    fun `5_1 keeps left on left, right on right and drops the LFE`() {
        val out = FloatArray(2)
        // FL FR FC LFE BL BR
        StereoDownmix.apply(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f), 6, out, 1)
        assertTrue(out[0] > 0f); assertEquals(0f, out[1])
        StereoDownmix.apply(floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f), 6, out, 1)
        assertEquals(0f, out[0]); assertTrue(out[1] > 0f)
        StereoDownmix.apply(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), 6, out, 1)
        assertEquals(0f, out[0]); assertEquals(0f, out[1])
    }

    @Test
    fun `centre is shared equally`() {
        val out = FloatArray(2)
        StereoDownmix.apply(floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f), 6, out, 1)
        assertEquals(out[0], out[1], 1e-6f)
        assertTrue(out[0] > 0f)
    }

    @Test
    fun `interleaved frames stay in order`() {
        val frames = 3
        val src = FloatArray(frames * 6) { i -> if (i % 6 == 0) (i / 6 + 1) * 0.1f else 0f }
        val out = FloatArray(frames * 2)
        StereoDownmix.apply(src, 6, out, frames)
        assertTrue(out[0] < out[2] && out[2] < out[4])
    }
}
