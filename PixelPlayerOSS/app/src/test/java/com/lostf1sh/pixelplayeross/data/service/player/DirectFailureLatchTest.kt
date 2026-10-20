package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-011: after a Direct claim failed, the policy kept deciding USB. With playback audible through
 * the delegate, every track change re-evaluated to SAFETY_TRANSITION_DEFERRED and re-armed the
 * handoff: pause, two rebuilds, another failed claim, ~2 s of silence — on every track.
 *
 * A failure is now latched for the attachment and source rate that produced it, and the policy
 * reports it as a transport fact, so neither the rebuild nor the handoff is attempted again until a
 * relevant fact changes.
 */
class DirectFailureLatchTest {

    private val device = "/dev/bus/usb/001/002"

    @Test
    fun `a failure blocks Direct for that device and rate`() {
        val latch = DirectFailureLatch()
        latch.onFailure(device, sampleRateHz = 32_000)

        assertThat(latch.blocks(device, 32_000)).isTrue()
        // Rate not known yet (the next track has not decoded): stay put rather than retry blindly.
        assertThat(latch.blocks(device, null)).isTrue()
    }

    @Test
    fun `another rate, another device, a detach or a new permission lifts it`() {
        val latch = DirectFailureLatch()
        latch.onFailure(device, sampleRateHz = 32_000)

        assertThat(latch.blocks(device, 44_100)).isFalse()
        assertThat(latch.blocks("/dev/bus/usb/001/009", 32_000)).isFalse()

        latch.clear()
        assertThat(latch.blocks(device, 32_000)).isFalse()
    }

    @Test
    fun `a failure with an unknown rate blocks every rate on that device`() {
        val latch = DirectFailureLatch()
        latch.onFailure(device, sampleRateHz = null)
        assertThat(latch.blocks(device, 44_100)).isTrue()
    }

    @Test
    fun `the policy reports a latched failure as a transport fact, not a safety deferral`() {
        val decision = AudioRoutePolicy.decide(
            AudioRouteFacts(
                usbDacPresent = true,
                usbPermissionGranted = true,
                directTransitionAllowed = false,
                directFailedForSource = true,
            )
        )
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.DIRECT_FAILED_FALLBACK)
    }

    @Test
    fun `N track changes after a failed claim start no handoff and change no backend`() {
        val latch = DirectFailureLatch()
        val handoff = AutoDirectHandoff()
        latch.onFailure(device, sampleRateHz = 32_000)
        var previousBackend = AudioBackend.ANDROID_AUDIO
        var handoffsStarted = 0
        var backendChanges = 0

        repeat(10) { track ->
            // Playing through the Android delegate: audible, so a Direct transition is "unsafe".
            val decision = AudioRoutePolicy.decide(
                AudioRouteFacts(
                    usbDacPresent = true,
                    usbPermissionGranted = true,
                    directTransitionAllowed = false,
                    directFailedForSource = latch.blocks(device, 32_000),
                )
            )
            if (decision.backend != previousBackend) backendChanges++
            previousBackend = decision.backend
            if (handoff.begin(decision.reason, isPlaying = true, directIsActive = false, itemId = track, nowMs = track * 1_000L)) {
                handoffsStarted++
                handoff.conclude()
            }
        }

        assertThat(handoffsStarted).isEqualTo(0)
        assertThat(backendChanges).isEqualTo(0)
    }

    // ── R4-2: a format rejection must not block other formats at the same rate ──────────────

    private val pcm16 = 2
    private val pcm24 = 0x15

    @Test
    fun `a rejected 24-bit source does not block a 16-bit one at the same rate`() {
        val latch = DirectFailureLatch()
        latch.onFailure(device, sampleRateHz = 44_100, pcmEncoding = pcm24, channels = 2)

        assertThat(latch.blocks(device, 44_100, pcmEncoding = pcm24, channels = 2)).isTrue()
        assertThat(latch.blocks(device, 44_100, pcmEncoding = pcm16, channels = 2)).isFalse()
    }

    @Test
    fun `a different channel count is a different format`() {
        val latch = DirectFailureLatch()
        latch.onFailure(device, sampleRateHz = 44_100, pcmEncoding = pcm16, channels = 6)
        assertThat(latch.blocks(device, 44_100, pcmEncoding = pcm16, channels = 2)).isFalse()
    }

    @Test
    fun `an unknown field on either side still blocks, as for the rate`() {
        val latch = DirectFailureLatch()
        latch.onFailure(device, sampleRateHz = 44_100, pcmEncoding = pcm24, channels = 2)
        assertThat(latch.blocks(device, 44_100, pcmEncoding = null, channels = 2)).isTrue()
    }
}
