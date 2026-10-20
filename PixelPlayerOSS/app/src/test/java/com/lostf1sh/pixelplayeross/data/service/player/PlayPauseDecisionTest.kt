package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.PlayPauseDecision.Action
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test

/**
 * AUD-066: the in-app play/pause decided with `isPlaying`, so while buffering with playWhenReady
 * true a press called play() instead of pausing. AUD-067: the widget toggled playWhenReady with
 * different rules (no restart when ENDED). One decision now, made on playWhenReady.
 */
class PlayPauseDecisionTest {

    @Test
    fun `buffering with playWhenReady pauses`() {
        assertThat(PlayPauseDecision.decide(true, Player.STATE_BUFFERING, 5)).isEqualTo(Action.PAUSE)
        assertThat(PlayPauseDecision.decide(true, Player.STATE_READY, 5)).isEqualTo(Action.PAUSE)
    }

    @Test
    fun `paused plays, idle prepares first, ended restarts the item`() {
        assertThat(PlayPauseDecision.decide(false, Player.STATE_READY, 5)).isEqualTo(Action.PLAY)
        assertThat(PlayPauseDecision.decide(false, Player.STATE_IDLE, 5)).isEqualTo(Action.PREPARE_AND_PLAY)
        assertThat(PlayPauseDecision.decide(false, Player.STATE_IDLE, 0)).isEqualTo(Action.PLAY)
        // Natural end of the queue (reachable in Direct since AUD-016): playWhenReady may still be true.
        assertThat(PlayPauseDecision.decide(true, Player.STATE_ENDED, 5)).isEqualTo(Action.RESTART_AND_PLAY)
        assertThat(PlayPauseDecision.decide(false, Player.STATE_ENDED, 5)).isEqualTo(Action.RESTART_AND_PLAY)
    }

    @Test
    fun `apply drives the player accordingly`() {
        val buffering = mockk<Player>(relaxed = true)
        every { buffering.playWhenReady } returns true
        every { buffering.playbackState } returns Player.STATE_BUFFERING
        every { buffering.isPlaying } returns false
        PlayPauseDecision.apply(buffering)
        verify { buffering.pause() }
        verify(exactly = 0) { buffering.play() }

        val ended = mockk<Player>(relaxed = true)
        every { ended.playWhenReady } returns true
        every { ended.playbackState } returns Player.STATE_ENDED
        every { ended.mediaItemCount } returns 3
        every { ended.currentMediaItemIndex } returns 2
        PlayPauseDecision.apply(ended)
        verifyOrder {
            ended.seekTo(2, 0L)
            ended.play()
        }
    }
}
