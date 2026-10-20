package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.TransitionBarrier.Start
import org.junit.jupiter.api.Test

/**
 * B27-A1 (review R-01): the ticket and the permission to play that a pending crossfade shows after every wait.
 */
class TransitionBarrierTest {

    @Test
    fun `a ticket is valid until something invalidates it`() {
        val barrier = TransitionBarrier()
        val ticket = barrier.begin()
        assertThat(barrier.isCurrent(ticket)).isTrue()

        barrier.invalidate()

        assertThat(barrier.isCurrent(ticket)).isFalse()
    }

    @Test
    fun `a new transition makes the old one's ticket stale`() {
        val barrier = TransitionBarrier()
        val first = barrier.begin()
        val second = barrier.begin()

        assertThat(barrier.isCurrent(first)).isFalse()
        assertThat(barrier.isCurrent(second)).isTrue()
    }

    @Test
    fun `an invalidation after the new transition began does not revive the old ticket`() {
        val barrier = TransitionBarrier()
        val first = barrier.begin()
        barrier.invalidate()
        val second = barrier.begin()
        barrier.invalidate()

        assertThat(barrier.isCurrent(first)).isFalse()
        assertThat(barrier.isCurrent(second)).isFalse()
    }

    @Test
    fun `everything intact and playback asked for plays`() {
        assertThat(decide()).isEqualTo(Start.PLAY)
    }

    @Test
    fun `a master that is not asked to play never starts a crossfade, whatever paused it`() {
        // manual pause, focus loss, output loss and handoff all end in playWhenReady = false
        assertThat(decide(masterPlayWhenReady = false)).isEqualTo(Start.NOT_PLAYING)
    }

    @Test
    fun `a stale ticket is reported as stale even when the pause that staled it also stopped playback`() {
        assertThat(decide(ticketCurrent = false, masterPlayWhenReady = false)).isEqualTo(Start.STALE)
    }

    @Test
    fun `replaced players are not the ones the crossfade was built for`() {
        assertThat(decide(playersUnchanged = false)).isEqualTo(Start.PLAYERS_CHANGED)
    }

    @Test
    fun `an incoming player with nothing queued has nothing to fade to`() {
        assertThat(decide(incomingHasItems = false)).isEqualTo(Start.NOTHING_TO_FADE_TO)
    }

    @Test
    fun `every combination other than all-true refuses`() {
        for (mask in 0 until 16) {
            val ticket = mask and 1 != 0
            val players = mask and 2 != 0
            val items = mask and 4 != 0
            val playing = mask and 8 != 0
            val verdict = decide(ticket, players, items, playing)
            if (ticket && players && items && playing) {
                assertThat(verdict).isEqualTo(Start.PLAY)
            } else {
                assertThat(verdict).isNotEqualTo(Start.PLAY)
            }
        }
    }

    private fun decide(
        ticketCurrent: Boolean = true,
        playersUnchanged: Boolean = true,
        incomingHasItems: Boolean = true,
        masterPlayWhenReady: Boolean = true,
    ) = TransitionBarrier.decide(ticketCurrent, playersUnchanged, incomingHasItems, masterPlayWhenReady)
}
