package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-120 (device: Digital at 99 % kept the route DIRECT_PROCESSED — "no quiere volver a Pristine").
 * Anything but exactly 100 % forces PROCESSED, and a drag rarely lands on the end of the track.
 */
class DigitalGainSliderTest {

    @Test
    fun `the last 3 percent of the track snaps to 100`() {
        assertThat(DigitalGainSlider.snapPercent(1.0f)).isEqualTo(100)
        assertThat(DigitalGainSlider.snapPercent(0.99f)).isEqualTo(100)
        assertThat(DigitalGainSlider.snapPercent(0.97f)).isEqualTo(100)
        assertThat(DigitalGainSlider.snapPercent(0.96f)).isEqualTo(96)
        assertThat(DigitalGainSlider.snapPercent(0.5f)).isEqualTo(50)
        assertThat(DigitalGainSlider.snapPercent(0f)).isEqualTo(0)
    }

    @Test
    fun `the notice shows only when the digital level is what keeps Direct off PRISTINE`() {
        assertThat(DigitalGainSlider.pristineBlockedByDigital(true, 99, pristineLocked = false)).isTrue()
        assertThat(DigitalGainSlider.pristineBlockedByDigital(true, 100, pristineLocked = false)).isFalse()
        assertThat(DigitalGainSlider.pristineBlockedByDigital(false, 50, pristineLocked = false)).isFalse()
        // The Pristine lock freezes it: nothing is blocked.
        assertThat(DigitalGainSlider.pristineBlockedByDigital(true, 50, pristineLocked = true)).isFalse()
    }
}
