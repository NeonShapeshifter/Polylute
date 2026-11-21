package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.ReplayGainRuntimeState
import org.junit.jupiter.api.Test

class B62TrackGainIdentityTest {
    private val android = AudioRouteState()
    private val applied = ReplayGainRuntimeState(requested = true, applied = true, mediaId = "a", multiplier = 0.5f)

    @Test fun `the state of another track cannot label this one as applied or failed`() {
        assertThat(resolveReplayGain(applied, android, "b")).isEqualTo(ReplayGainState.WAITING_FOR_TRACK)
        assertThat(resolveReplayGain(applied.copy(applied = false), android, "b")).isEqualTo(ReplayGainState.WAITING_FOR_TRACK)
        assertThat(resolveReplayGain(applied, android, "a")).isEqualTo(ReplayGainState.TRACK_APPLIED)
    }

    @Test fun `pending tags and a pending crossfade are not a failed gain application`() {
        assertThat(resolveReplayGain(applied.copy(applied = false, pending = true), android, "a"))
            .isEqualTo(ReplayGainState.PENDING)
        assertThat(resolveReplayGain(applied.copy(multiplier = 1f), android, "a"))
            .isEqualTo(ReplayGainState.UNITY_APPLIED)
    }

    @Test fun `Direct continues to report the bypass rather than an Android correction`() {
        assertThat(resolveReplayGain(applied, android.copy(actualBackend = AudioBackend.USB_DIRECT), "a"))
            .isEqualTo(ReplayGainState.REQUESTED_NOT_APPLIED)
    }
}
