package com.lostf1sh.pixelplayeross.data.service

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The resume-on-reconnect rule (existing before B25; B25-S1 moved it here to test it and let a DAC unplug arm it).
 * Off by default; only the output going away arms it; an output of a reconnectable kind within 15 s resumes once.
 */
class HeadsetReconnectRuleTest {

    private fun rule(enabled: Boolean = true) = HeadsetReconnectRule().apply { this.enabled = enabled }

    private fun HeadsetReconnectRule.reconnect(
        at: Long,
        output: Boolean = true,
        item: Boolean = true,
        playing: Boolean = false,
        idle: Boolean = false,
    ) = onOutputAdded(at, output, item, playing, idle)

    @Test
    fun `it is off by default, and then nothing arms it`() {
        val rule = HeadsetReconnectRule()
        rule.onOutputLostPause(nowMs = 0)
        rule.onPlayWhenReadyChanged(playWhenReady = false, noisyPause = true, nowMs = 0)

        assertThat(rule.isArmed).isFalse()
        assertThat(rule.reconnect(at = 1_000)).isFalse()
    }

    @Test
    fun `a becoming-noisy pause arms it and a reconnect inside the window resumes, once`() {
        val rule = rule()
        rule.onPlayWhenReadyChanged(playWhenReady = false, noisyPause = true, nowMs = 100)

        assertThat(rule.reconnect(at = 5_000)).isTrue()
        assertThat(rule.reconnect(at = 5_100)).isFalse()
    }

    @Test
    fun `the engine's own pause when the DAC leaves arms it the same way, whichever of the two comes first`() {
        // the engine tells the service just before it pauses; the player then reports an ordinary pause
        val before = rule()
        before.onOutputLostPause(nowMs = 100)
        before.onPlayWhenReadyChanged(playWhenReady = false, noisyPause = false, nowMs = 101)
        assertThat(before.isArmed).isTrue()
        assertThat(before.reconnect(at = 3_000)).isTrue()

        // or the report is delivered first and the engine's message after it
        val after = rule()
        after.onPlayWhenReadyChanged(playWhenReady = false, noisyPause = false, nowMs = 100)
        after.onOutputLostPause(nowMs = 101)
        assertThat(after.reconnect(at = 3_000)).isTrue()
    }

    @Test
    fun `a DAC replug after the window does not resume, and ends the rule`() {
        val rule = rule()
        rule.onOutputLostPause(nowMs = 0)

        assertThat(rule.reconnect(at = HeadsetReconnectRule.DEFAULT_WINDOW_MS + 1)).isFalse()
        assertThat(rule.isArmed).isFalse()
        assertThat(rule.reconnect(at = 1_000)).isFalse()
    }

    @Test
    fun `an output that is not a headset or a DAC does not resume and does not end the wait`() {
        val rule = rule()
        rule.onOutputLostPause(nowMs = 0)

        assertThat(rule.reconnect(at = 1_000, output = false)).isFalse()
        assertThat(rule.isArmed).isTrue()
        assertThat(rule.reconnect(at = 2_000)).isTrue()
    }

    @Test
    fun `the user pressing play, or pausing again later, ends it`() {
        val play = rule()
        play.onOutputLostPause(nowMs = 0)
        play.onPlayWhenReadyChanged(playWhenReady = true, noisyPause = false, nowMs = 2_000)
        assertThat(play.isArmed).isFalse()

        val pausedAgain = rule()
        pausedAgain.onOutputLostPause(nowMs = 0)
        // a pause a second and more later is somebody's, not the engine's own
        pausedAgain.onPlayWhenReadyChanged(playWhenReady = false, noisyPause = false, nowMs = 5_000)
        assertThat(pausedAgain.isArmed).isFalse()
    }

    @Test
    fun `nothing resumes when the player already plays, has nothing loaded, or is idle or ended`() {
        for ((item, playing, idle) in listOf(Triple(false, false, false), Triple(true, true, false), Triple(true, false, true))) {
            val rule = rule()
            rule.onOutputLostPause(nowMs = 0)
            assertThat(rule.reconnect(at = 1_000, item = item, playing = playing, idle = idle)).isFalse()
            assertThat(rule.isArmed).isFalse()
        }
    }

    @Test
    fun `turning the setting off disarms it`() {
        val rule = rule()
        rule.onOutputLostPause(nowMs = 0)
        rule.enabled = false

        assertThat(rule.isArmed).isFalse()
        assertThat(rule.reconnect(at = 100)).isFalse()
    }

    @Test
    fun `the kinds of output that count are headsets, USB DACs of both listings, and Bluetooth, not the speaker`() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )) {
            assertThat(HeadsetReconnectRule.isReconnectableOutputType(type)).isTrue()
        }
        for (type in listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_HDMI)) {
            assertThat(HeadsetReconnectRule.isReconnectableOutputType(type)).isFalse()
        }
    }
}
