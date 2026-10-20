package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-146: the position reaches the UI at 1 Hz, or 4 Hz where a bar is being watched, and never
 * faster. These are the ceilings the progress loop, the full player's sampler and the lock surface
 * all read from.
 */
class PlaybackProgressRatesTest {

    @Test
    fun `the position never reaches the UI faster than 4 Hz`() {
        for (interactive in listOf(true, false)) {
            for (mounted in listOf(true, false)) {
                val hz = PlaybackProgressRates.hertz(PlaybackProgressRates.tickMs(interactive, mounted))
                assertThat(hz).isAtMost(4.0)
            }
        }
    }

    @Test
    fun `only the expanded full player bar gets the fast tick`() {
        assertThat(PlaybackProgressRates.tickMs(screenInteractive = true, sliderMounted = true))
            .isEqualTo(PlaybackProgressRates.SLIDER_TICK_MS)
        // Mini player, labels, anything that shows whole seconds: 1 Hz.
        assertThat(PlaybackProgressRates.tickMs(screenInteractive = true, sliderMounted = false))
            .isEqualTo(1_000L)
    }

    @Test
    fun `with the screen off the loop is at 1 Hz even if a slider is still mounted`() {
        assertThat(PlaybackProgressRates.tickMs(screenInteractive = false, sliderMounted = true))
            .isEqualTo(1_000L)
        assertThat(PlaybackProgressRates.tickMs(screenInteractive = false, sliderMounted = false))
            .isEqualTo(1_000L)
    }

    @Test
    fun `the lock surface and the background tick are 1 Hz`() {
        assertThat(PlaybackProgressRates.LOCK_SURFACE_TICK_MS).isEqualTo(1_000L)
        assertThat(PlaybackProgressRates.BACKGROUND_TICK_MS).isEqualTo(1_000L)
    }

    @Test
    fun `the full player does not sample faster than the source changes`() {
        assertThat(PlaybackProgressRates.EXPANDED_BAR_SAMPLE_MS)
            .isAtLeast(PlaybackProgressRates.SLIDER_TICK_MS)
        assertThat(PlaybackProgressRates.COLLAPSED_BAR_SAMPLE_MS)
            .isAtLeast(PlaybackProgressRates.EXPANDED_BAR_SAMPLE_MS)
    }

    @Test
    fun `a 4 Hz tick moves a thin bar by about a pixel or less, which is why it reads as smooth`() {
        // 330 dp bar, a 3-minute track is the quick case: dp moved per tick.
        val barDp = 330.0
        val trackSeconds = 180.0
        val dpPerTick = barDp / trackSeconds * (PlaybackProgressRates.SLIDER_TICK_MS / 1000.0)
        assertThat(dpPerTick).isLessThan(0.6)
    }
}
