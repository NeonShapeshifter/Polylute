package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * B39-A7 (privacy): "you press play and your aunt's voice note plays". A play from a headset, the DAC's own button or the lock screen
 * arrives at the session's player like any other, so the one place that has to hold it while the DAC is handed back is the player the
 * session was built on ([MappingPlayer], which every controller reaches). Android sends the media buttons to the last active session;
 * what Polylute does about being that session is to keep its own active and paused through a hand-back (nothing here stops it) and
 * to be the one that plays when asked, after taking the floor and the DAC. It never calls anyone else's session.
 */
class MediaButtonRoutingTest {
    private val inner = mockk<Player>(relaxed = true)

    private fun player(gate: () -> Boolean) = MappingPlayer(
        innerPlayer = inner,
        context = mockk(relaxed = true),
        playGate = gate,
    )

    @Test
    fun `a play held by the gate does not reach the player, from play() or from playWhenReady`() {
        val held = player { false }
        held.play()
        held.playWhenReady = true
        verify(exactly = 0) { inner.play() }
        verify(exactly = 0) { inner.playWhenReady = true }
    }

    @Test
    fun `a play the gate lets through reaches the player`() {
        val open = player { true }
        open.play()
        open.playWhenReady = true
        verify(exactly = 1) { inner.play() }
        verify(exactly = 1) { inner.playWhenReady = true }
    }

    @Test
    fun `a pause is never held, so the DAC can always be given up and the session stays paused, not stopped`() {
        val held = player { false }
        held.pause()
        held.playWhenReady = false
        verify(atLeast = 1) { inner.pause() }
        verify(exactly = 1) { inner.playWhenReady = false }
        verify(exactly = 0) { inner.stop() }
        verify(exactly = 0) { inner.release() }
    }

    @Test
    fun `the gate is asked once per play, and its answer is the player's`() {
        var asked = 0
        val counting = player { asked++; asked > 1 }
        counting.play()      // the first is held (the claim begins)
        counting.play()      // the second, after the claim, goes through
        assertThat(asked).isEqualTo(2)
        verify(exactly = 1) { inner.play() }
    }
}
