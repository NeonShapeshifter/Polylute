package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B12a-7: the rule behind the play/pause glyph not bouncing back (see [PlayPauseCommandGuard]). Time is the
 * argument, so a hold and its expiry are exact.
 */
class PlayPauseCommandGuardTest {

    @Test
    fun `with no command every report is believed`() {
        val guard = PlayPauseCommandGuard()
        for (current in listOf(true, false)) for (reported in listOf(true, false)) {
            val r = guard.resolvePlaying(current, reported, nowMs = 5L)
            assertThat(r.value).isEqualTo(reported)
            assertThat(r.held).isFalse()
        }
    }

    @Test
    fun `after a pause the echoes of the old state are held for the whole window, whatever agrees in between`() {
        val guard = PlayPauseCommandGuard()
        guard.onUserCommand(playbackWanted = false, nowMs = 1_000L)
        // the controller's own optimistic step agrees with the tap and is applied...
        assertThat(guard.resolvePlaying(current = false, reported = false, nowMs = 1_001L).held).isFalse()
        // ...but it is the FIRST report, not the last: the session's ramp echoes isPlaying=true right behind it
        val echo1 = guard.resolvePlaying(current = false, reported = true, nowMs = 1_030L)
        assertThat(echo1.held).isTrue()
        assertThat(echo1.value).isFalse()
        assertThat(echo1.ageMs).isEqualTo(30L)
        assertThat(guard.resolvePlaying(current = false, reported = true, nowMs = 1_070L).value).isFalse()
        // the pause really happens and agrees; it does not open the door to a late echo either
        assertThat(guard.resolvePlaying(current = false, reported = false, nowMs = 1_160L).held).isFalse()
        assertThat(guard.resolvePlaying(current = false, reported = true, nowMs = 1_200L).held).isTrue()
        // after the window the player's word is the truth
        val resume = guard.resolvePlaying(current = false, reported = true, nowMs = 1_000L + PlayPauseCommandGuard.HOLD_MS)
        assertThat(resume.value).isTrue()
        assertThat(resume.held).isFalse()
    }

    @Test
    fun `a contradiction older than the hold is real and is shown`() {
        val guard = PlayPauseCommandGuard()
        guard.onUserCommand(false, 0L)
        assertThat(guard.resolvePlaying(false, true, PlayPauseCommandGuard.HOLD_MS - 1).held).isTrue()
        val real = guard.resolvePlaying(false, true, PlayPauseCommandGuard.HOLD_MS)
        assertThat(real.value).isTrue()
        assertThat(real.held).isFalse()
        assertThat(real.expired).isTrue()
    }

    @Test
    fun `a play tap holds the paused echo of its own first ramp step the same way`() {
        val guard = PlayPauseCommandGuard()
        guard.onUserCommand(playbackWanted = true, nowMs = 0L)
        // the controller said playing (optimistic), then the session echoed the state from before the play
        assertThat(guard.resolvePlaying(current = false, reported = true, nowMs = 5L).value).isTrue()
        val echo = guard.resolvePlaying(current = true, reported = false, nowMs = 25L)
        assertThat(echo.held).isTrue()
        assertThat(echo.value).isTrue()
        assertThat(guard.resolvePlaying(current = true, reported = true, nowMs = 90L).held).isFalse()
    }

    @Test
    fun `a report that changes nothing is not held and a new command replaces the old one`() {
        val guard = PlayPauseCommandGuard()
        guard.onUserCommand(false, 0L)
        val same = guard.resolvePlaying(current = true, reported = true, nowMs = 10L)
        assertThat(same.held).isFalse()                     // nothing to hold: the glyph was not going to move
        guard.onUserCommand(true, 100L)                     // the user changed their mind
        assertThat(guard.resolvePlaying(current = false, reported = true, nowMs = 150L).value).isTrue()
        guard.onUserCommand(false, 300L)
        assertThat(guard.resolvePlaying(current = false, reported = true, nowMs = 320L).held).isTrue()
    }

    @Test
    fun `playWhenReady is tracked on its own, confirmed by its own report`() {
        val guard = PlayPauseCommandGuard()
        guard.onUserCommand(false, 0L)
        // isPlaying agreeing first changes nothing for playWhenReady: its own echo is still an echo of the old state
        assertThat(guard.resolvePlaying(false, false, 60L).held).isFalse()
        assertThat(guard.resolveIntent(current = false, reported = true, nowMs = 80L).held).isTrue()
        assertThat(guard.resolveIntent(current = false, reported = false, nowMs = 150L).held).isFalse()
        assertThat(guard.resolveIntent(current = false, reported = true, nowMs = 200L).held).isTrue()
        assertThat(guard.resolveIntent(current = false, reported = true, nowMs = PlayPauseCommandGuard.HOLD_MS).value).isTrue()
    }

    @Test
    fun `a write that is not a report supersedes the command`() {
        val guard = PlayPauseCommandGuard()
        guard.onUserCommand(false, 0L)
        assertThat(guard.hasPending(10L)).isTrue()
        guard.clear()
        assertThat(guard.hasPending(10L)).isFalse()
        assertThat(guard.resolvePlaying(false, true, 20L).value).isTrue()
    }
}
