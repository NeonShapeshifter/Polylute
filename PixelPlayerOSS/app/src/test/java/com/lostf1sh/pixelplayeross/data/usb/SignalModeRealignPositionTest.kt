package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.PipelinePositionClock
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Frame baselines are scoped to a renderer period, including seeks and cached USB streams. */
class SignalModeRealignPositionTest {

    private val offsetUs = 1_000_000_000_000L          // Media3's renderer offset for the first period
    private val rate = 44_100
    private val bufferFrames = 4096
    private val bufferUs = bufferFrames * 1_000_000L / rate

    @Test
    fun `frames written before the latch are not counted`() {
        val clock = PipelinePositionClock()
        clock.accept(offsetUs + 20_000_000L, framesWritten = 5_000_000L)
        assertThat(clock.positionUs(framesWritten = 5_000_000L + rate, sampleRate = rate))
            .isEqualTo(offsetUs + 21_000_000L)
    }
}
