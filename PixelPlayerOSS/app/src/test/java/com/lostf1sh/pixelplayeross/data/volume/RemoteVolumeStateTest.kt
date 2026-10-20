package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.service.player.RemoteVolumeState
import org.junit.jupiter.api.Test

/** AUD-027: which Safety states hand the volume keys to the DAC through the media session. */
class RemoteVolumeStateTest {

    private fun state(kind: SafetyVolumeBackendKind, controllable: Boolean = true) = SafetyVolumeState(
        backend = VolumeBackendSnapshot(kind, "dac:x", "DAC", 20, 0, 60, controllable),
    )

    @Test
    fun `the DAC Feature Unit in Direct is exposed with its range and level`() {
        assertThat(remoteVolumeStateOf(state(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME)))
            .isEqualTo(RemoteVolumeState(min = 0, max = 60, level = 20))
    }

    @Test
    fun `Android media volume and fixed outputs stay with the platform`() {
        assertThat(remoteVolumeStateOf(state(SafetyVolumeBackendKind.ANDROID_MEDIA))).isNull()
        assertThat(remoteVolumeStateOf(state(SafetyVolumeBackendKind.FIXED_OUTPUT, controllable = false))).isNull()
    }
}

/**
 * REV-4: the system volume panel's slider reaches the DAC through the remote volume in one gesture.
 * A raise is limited per request, the way held keys are paced; lowering is never limited.
 *
 * AUD-126: with no calibrated Safety Max ceiling the ceiling is not the bound, so the per-request
 * allowance is the only thing standing between a remote channel that asks repeatedly and a DAC at
 * 0 dB. That case gets one step per request, at the keys' cadence.
 */
class RemoteVolumeRaiseLimitTest {

    @Test
    fun `a large raise is limited to a few steps per request`() {
        assertThat(remoteRequestedRaw(requested = 60, current = 10, maxRaiseSteps = REMOTE_MAX_RAISE_STEPS))
            .isEqualTo(10 + REMOTE_MAX_RAISE_STEPS)
    }

    @Test
    fun `a single step raise and any lowering pass through`() {
        assertThat(remoteRequestedRaw(requested = 11, current = 10, maxRaiseSteps = REMOTE_MAX_RAISE_STEPS))
            .isEqualTo(11)
        assertThat(remoteRequestedRaw(requested = 0, current = 40, maxRaiseSteps = REMOTE_MAX_RAISE_STEPS))
            .isEqualTo(0)
    }

    @Test
    fun `without a ceiling on the DAC a remote raise is one step at a time`() {
        assertThat(
            SafetyVolumePolicy.remoteRaiseSteps(
                SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
                ceilingRaw = null,
            )
        ).isEqualTo(1)
        assertThat(remoteRequestedRaw(requested = 60, current = 10, maxRaiseSteps = 1)).isEqualTo(11)
    }

    @Test
    fun `a calibrated ceiling keeps the allowance and Android media is never capped`() {
        assertThat(
            SafetyVolumePolicy.remoteRaiseSteps(
                SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
                ceilingRaw = -1536,
            )
        ).isEqualTo(REMOTE_MAX_RAISE_STEPS)
        assertThat(
            SafetyVolumePolicy.remoteRaiseSteps(SafetyVolumeBackendKind.ANDROID_MEDIA, ceilingRaw = null)
        ).isEqualTo(REMOTE_MAX_RAISE_STEPS)
    }

    @Test
    fun `a remote raise is paced like a held key`() {
        assertThat(VolumeKeyRepeatPolicy.remoteRaiseAllowed(nowMs = 1_000, lastRemoteRaiseAtMs = 900)).isFalse()
        assertThat(
            VolumeKeyRepeatPolicy.remoteRaiseAllowed(
                nowMs = 1_000,
                lastRemoteRaiseAtMs = 1_000 - VolumeKeyRepeatPolicy.RAISE_INTERVAL_MS,
            )
        ).isTrue()
    }
}
