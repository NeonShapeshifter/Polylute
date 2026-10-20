package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-096: paused, the crossfade countdown kept polling the position (every 1 s, 250 ms, or 50 ms
 * within the last second) for as long as the pause lasted, although nothing could change it.
 */
class TransitionCountdownTest {
    @Test
    fun `paused, the countdown waits instead of polling`() {
        assertThat(TransitionCountdown.sleepMs(remainingMs = 400, isPlaying = false)).isNull()
        assertThat(TransitionCountdown.sleepMs(remainingMs = 60_000, isPlaying = false)).isNull()
    }

    @Test
    fun `playing keeps the existing cadence`() {
        assertThat(TransitionCountdown.sleepMs(60_000, true)).isEqualTo(1000L)
        assertThat(TransitionCountdown.sleepMs(3_000, true)).isEqualTo(250L)
        assertThat(TransitionCountdown.sleepMs(400, true)).isEqualTo(50L)
        assertThat(TransitionCountdown.sleepMs(20, true)).isEqualTo(20L)
        assertThat(TransitionCountdown.sleepMs(0, true)).isEqualTo(1L)
    }
}
