package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING
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
}
