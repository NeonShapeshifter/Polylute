package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.DirectStreamLoss
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B25-S1: unplugging the DAC while music plays must pause it, with the reason, before the route can fall back to the
 * speaker; plugging one in while playing, or unplugging while paused, must not change what plays.
 */
class OutputLossGuardTest {

    @Test
    fun `a DAC leaving while Direct plays pauses, and the reason says Direct`() {
        val guard = OutputLossGuard()
        assertThat(guard.onUsbPresence(present = true, playWhenReady = true, directActive = true)).isNull()

        val loss = guard.onUsbPresence(present = false, playWhenReady = true, directActive = true)

        assertThat(loss).isEqualTo(OutputLossReason.DIRECT_DAC_DETACHED)
    }

    @Test
    fun `a DAC leaving while Android plays to it pauses too, with its own reason`() {
        val guard = OutputLossGuard()
        guard.onUsbPresence(present = true, playWhenReady = true, directActive = false)

        val loss = guard.onUsbPresence(present = false, playWhenReady = true, directActive = false)

        assertThat(loss).isEqualTo(OutputLossReason.ANDROID_USB_DAC_DETACHED)
    }

    @Test
    fun `a DAC leaving while paused leaves it paused and asks for nothing`() {
        val guard = OutputLossGuard()
        guard.onUsbPresence(present = true, playWhenReady = false, directActive = true)

        assertThat(guard.onUsbPresence(present = false, playWhenReady = false, directActive = true)).isNull()
        assertThat(guard.isDacPresent).isFalse()
    }

    @Test
    fun `plugging a DAC in while playing on the speaker changes nothing`() {
        val guard = OutputLossGuard()
        // no DAC at first, playing on the speaker
        assertThat(guard.onUsbPresence(present = false, playWhenReady = true, directActive = false)).isNull()

        assertThat(guard.onUsbPresence(present = true, playWhenReady = true, directActive = false)).isNull()
        assertThat(guard.isDacPresent).isTrue()
    }

    @Test
    fun `no DAC, still no DAC, is not a loss`() {
        val guard = OutputLossGuard()
        repeat(3) {
            assertThat(guard.onUsbPresence(present = false, playWhenReady = true, directActive = false)).isNull()
        }
    }

    @Test
    fun `every observation of the same loss after the first is silent, so one detach is one pause`() {
        val guard = OutputLossGuard()
        guard.onUsbPresence(present = true, playWhenReady = true, directActive = true)

        // the broadcast, the sink's report and the route evaluation all see the DAC gone, in any order
        assertThat(guard.onUsbPresence(present = false, playWhenReady = true, directActive = true)).isNotNull()
        assertThat(guard.onUsbPresence(present = false, playWhenReady = true, directActive = true)).isNull()
        assertThat(guard.onUsbPresence(present = false, playWhenReady = true, directActive = true)).isNull()
    }

    @Test
    fun `a replug and a second unplug is a second pause`() {
        val guard = OutputLossGuard()
        guard.onUsbPresence(present = true, playWhenReady = true, directActive = true)
        assertThat(guard.onUsbPresence(present = false, playWhenReady = true, directActive = true)).isNotNull()
        guard.onUsbPresence(present = true, playWhenReady = true, directActive = true)

        assertThat(guard.onUsbPresence(present = false, playWhenReady = true, directActive = true)).isNotNull()
    }

    @Test
    fun `the sink reports a DAC that left with its own reason, and that is not the generic stopped stream`() {
        assertThat(DirectStreamLoss.isDeviceGone(DirectStreamLoss.reasonFor(deviceGone = true))).isTrue()
        assertThat(DirectStreamLoss.isDeviceGone(DirectStreamLoss.reasonFor(deviceGone = false))).isFalse()
        assertThat(DirectStreamLoss.isDeviceGone(null)).isFalse()
        assertThat(DirectStreamLoss.isDeviceGone("Direct refused")).isFalse()
    }
}
