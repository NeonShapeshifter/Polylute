package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING
import com.lostf1sh.pixelplayeross.presentation.lockscreen.CoverSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * F-05: "never while playing" is a window flag held only while a song plays and the player is in
 * front. It must disappear on pause, and a finite mode or "follow system" never holds it at all.
 */
class DapDeviceControlPolicyKeepScreenOnTest {

    private val never = DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING

    @Test
    fun `held while playing with the player in front`() {
        assertThat(DapDeviceControlPolicy.shouldKeepScreenOn(never, isPlaying = true, playerVisible = true)).isTrue()
    }

    @Test
    fun `released on pause`() {
        assertThat(DapDeviceControlPolicy.shouldKeepScreenOn(never, isPlaying = false, playerVisible = true)).isFalse()
    }

    @Test
    fun `not held for a page that is not the player, even while music plays`() {
        assertThat(DapDeviceControlPolicy.shouldKeepScreenOn(never, isPlaying = true, playerVisible = false)).isFalse()
    }

    @Test
    fun `no other mode ever holds it`() {
        for (mode in listOf(DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM, 30_000L, 60_000L, 600_000L)) {
            for (playing in listOf(true, false)) {
                for (visible in listOf(true, false)) {
                    assertThat(DapDeviceControlPolicy.shouldKeepScreenOn(mode, playing, visible)).isFalse()
                }
            }
        }
    }

    @Test
    fun `lyrics hold the screen only while playing`() {
        assertThat(DapDeviceControlPolicy.shouldKeepScreenOnForLyrics(userToggle = true, isPlaying = true)).isTrue()
        // Paused with the sheet open: this used to keep the screen (and the phone) awake indefinitely.
        assertThat(DapDeviceControlPolicy.shouldKeepScreenOnForLyrics(userToggle = true, isPlaying = false)).isFalse()
        assertThat(DapDeviceControlPolicy.shouldKeepScreenOnForLyrics(userToggle = false, isPlaying = true)).isFalse()
        assertThat(DapDeviceControlPolicy.shouldKeepScreenOnForLyrics(userToggle = false, isPlaying = false)).isFalse()
    }

    // ── B42-fix1: the DAP lock screen is drawn over a player that stays EXPANDED ─────────────────────

    /** Drives the flag's stream the way the activity does, and returns every value it applied, in order. */
    private fun appliedFlag(steps: (
        timeoutMode: MutableStateFlow<Long>,
        isPlaying: MutableStateFlow<Boolean>,
        sheetExpanded: MutableStateFlow<Boolean>,
        lockShowing: MutableStateFlow<Boolean>,
    ) -> Unit): List<Boolean> = runBlocking {
        val mode = MutableStateFlow(never)
        val playing = MutableStateFlow(false)
        val expanded = MutableStateFlow(false)
        val lock = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch(Dispatchers.Unconfined) {
            DapDeviceControlPolicy.keepScreenOnFlow(mode, playing, expanded, lock).toList(seen)
        }
        steps(mode, playing, expanded, lock)
        job.cancel()
        seen
    }

    @Test
    fun `playing with the player open holds the flag`() {
        val seen = appliedFlag { _, playing, expanded, _ -> expanded.value = true; playing.value = true }
        assertThat(seen.last()).isTrue()
    }

    @Test
    fun `the DAP lock screen over the open player releases the flag, and unlocking holds it again`() {
        val seen = appliedFlag { _, playing, expanded, lock ->
            expanded.value = true
            playing.value = true
            lock.value = true   // the listener pressed the power button: the lock screen is drawn over the player
            lock.value = false  // and swiped it away: the open player is in front again
        }
        // held, released under the lock screen, held again (the stream only says when the flag changes)
        assertThat(seen.dropWhile { !it }).containsExactly(true, false, true).inOrder()
    }

    @Test
    fun `a pause releases the flag whether or not the lock screen is up`() {
        val seen = appliedFlag { _, playing, expanded, lock ->
            expanded.value = true
            playing.value = true
            playing.value = false
            lock.value = true
            lock.value = false
        }
        assertThat(seen.dropWhile { !it }).containsExactly(true, false).inOrder()
    }

    @Test
    fun `playing starts under the lock screen without ever holding the flag`() {
        val seen = appliedFlag { _, playing, expanded, lock ->
            lock.value = true
            expanded.value = true
            playing.value = true
        }
        assertThat(seen.any { it }).isFalse()
    }

    @Test
    fun `another timeout mode holds nothing with or without the lock screen`() {
        val seen = appliedFlag { mode, playing, expanded, lock ->
            mode.value = 60_000L
            expanded.value = true
            playing.value = true
            lock.value = true
            lock.value = false
        }
        assertThat(seen.any { it }).isFalse()
    }

    @Test
    fun `the player is in front only when its sheet is open and no lock screen is over it`() {
        assertThat(DapDeviceControlPolicy.playerVisibleToListener(sheetExpanded = true, lockSurfaceShowing = false)).isTrue()
        assertThat(DapDeviceControlPolicy.playerVisibleToListener(sheetExpanded = true, lockSurfaceShowing = true)).isFalse()
        assertThat(DapDeviceControlPolicy.playerVisibleToListener(sheetExpanded = false, lockSurfaceShowing = false)).isFalse()
        assertThat(DapDeviceControlPolicy.playerVisibleToListener(sheetExpanded = false, lockSurfaceShowing = true)).isFalse()
    }

    @Test
    fun `the lyrics toggle is released under the lock screen and held again after it`() {
        assertThat(DapDeviceControlPolicy.shouldKeepScreenOnForLyrics(userToggle = true, isPlaying = true, lockSurfaceShowing = false)).isTrue()
        assertThat(DapDeviceControlPolicy.shouldKeepScreenOnForLyrics(userToggle = true, isPlaying = true, lockSurfaceShowing = true)).isFalse()
        assertThat(DapDeviceControlPolicy.shouldKeepScreenOnForLyrics(userToggle = true, isPlaying = false, lockSurfaceShowing = false)).isFalse()
    }

    @Test
    fun `the wake cycle's cover reaches the flag's stream as soon as it is set`() {
        // SCREEN_OFF arms the cycle: the flow carries it in the same call, with no frame in between
        val cover = CoverSignal()
        assertThat(cover.coveredFlow.value).isFalse()
        cover.covered = true
        assertThat(cover.coveredFlow.value).isTrue()
        cover.covered = false
        assertThat(cover.coveredFlow.value).isFalse()
    }
}
