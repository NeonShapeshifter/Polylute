package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-003: leaving Direct for the Android path with the DAC still attached — crossfade, a speed
 * change, an Android effect, a maintenance override, the handoff giving up — played through the
 * speaker or a dead route, because the app still held the DAC and `snd-usb-audio` was detached.
 *
 * The app now returns the DAC to Android on the way out, but the platform needs a moment to
 * re-register it. Until an Android USB output exists the Android decision is *blocked*: playback is
 * held rather than sent wherever the platform falls back to, and resumed if the route appears.
 */
class AndroidUsbRouteGuardTest {

    private fun facts(androidUsbRouteAvailable: Boolean, crossfade: Boolean = true) = AudioRouteFacts(
        usbDacPresent = true,
        usbPermissionGranted = true,
        processing = AudioProcessingRequirements(crossfade = crossfade),
        androidUsbRouteAvailable = androidUsbRouteAvailable,
    )

    // ── The policy fact ────────────────────────────────────────────────────────────────────

    @Test
    fun `Android with the DAC attached but no Android USB output is blocked`() {
        val decision = AudioRoutePolicy.decide(facts(androidUsbRouteAvailable = false))
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(decision.androidOutputBlocked).isTrue()
    }

    @Test
    fun `an available Android USB output, Direct, or no DAC is not blocked`() {
        assertThat(AudioRoutePolicy.decide(facts(androidUsbRouteAvailable = true)).androidOutputBlocked).isFalse()
        assertThat(
            AudioRoutePolicy.decide(facts(androidUsbRouteAvailable = false, crossfade = false)).androidOutputBlocked
        ).isFalse()
        assertThat(
            AudioRoutePolicy.decide(
                AudioRouteFacts(usbDacPresent = false, usbPermissionGranted = false, androidUsbRouteAvailable = false)
            ).androidOutputBlocked
        ).isFalse()
    }

    // ── The hold ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a blocked decision while playing holds playback, and the route appearing resumes it`() {
        val guard = AndroidUsbRouteGuard(timeoutMs = 3_000L)

        assertThat(guard.step(blocked = true, playing = true, nowMs = 0)).isEqualTo(AndroidUsbRouteGuard.Step.PAUSE)
        assertThat(guard.consumeOwnPause()).isTrue()
        assertThat(guard.step(blocked = true, playing = false, nowMs = 500)).isEqualTo(AndroidUsbRouteGuard.Step.NONE)
        assertThat(guard.step(blocked = false, playing = false, nowMs = 900)).isEqualTo(AndroidUsbRouteGuard.Step.RESUME)
        assertThat(guard.isHolding).isFalse()
    }

    @Test
    fun `a route that never appears leaves playback paused`() {
        val guard = AndroidUsbRouteGuard(timeoutMs = 3_000L)
        guard.step(blocked = true, playing = true, nowMs = 0)
        guard.consumeOwnPause()

        assertThat(guard.expire(nowMs = 3_000)).isTrue()
        assertThat(guard.step(blocked = false, playing = false, nowMs = 4_000)).isEqualTo(AndroidUsbRouteGuard.Step.NONE)
    }

    @Test
    fun `the user taking control cancels the resume`() {
        val guard = AndroidUsbRouteGuard(timeoutMs = 3_000L)
        guard.step(blocked = true, playing = true, nowMs = 0)
        guard.consumeOwnPause()

        guard.onUserTookControl()
        assertThat(guard.step(blocked = false, playing = false, nowMs = 500)).isEqualTo(AndroidUsbRouteGuard.Step.NONE)
    }

    @Test
    fun `nothing is held when not playing`() {
        val guard = AndroidUsbRouteGuard()
        assertThat(guard.step(blocked = true, playing = false, nowMs = 0)).isEqualTo(AndroidUsbRouteGuard.Step.NONE)
        assertThat(guard.isHolding).isFalse()
    }

    // ── B25-S1: the DAC itself leaves ──────────────────────────────────────────────────────────

    @Test
    fun `a hold does not resume when the DAC is gone, the pause it issued stays a pause`() {
        val guard = AndroidUsbRouteGuard(timeoutMs = 3_000L)
        assertThat(guard.step(blocked = true, playing = true, nowMs = 0)).isEqualTo(AndroidUsbRouteGuard.Step.PAUSE)
        assertThat(guard.consumeOwnPause()).isTrue()

        guard.onDeviceLost()
        // with no DAC the decision is no longer "blocked" (nothing to wait for): that used to resume, onto the speaker
        assertThat(guard.step(blocked = false, playing = false, nowMs = 900)).isEqualTo(AndroidUsbRouteGuard.Step.NONE)
        assertThat(guard.isHolding).isFalse()
    }

    @Test
    fun `losing the DAC with no hold in force is a no-op`() {
        val guard = AndroidUsbRouteGuard()
        guard.onDeviceLost()
        assertThat(guard.isHolding).isFalse()
        assertThat(guard.step(blocked = false, playing = true, nowMs = 0)).isEqualTo(AndroidUsbRouteGuard.Step.NONE)
    }
}
