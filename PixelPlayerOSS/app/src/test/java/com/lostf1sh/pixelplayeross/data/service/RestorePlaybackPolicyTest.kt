package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import org.junit.jupiter.api.Test

/**
 * AUD-125 (measured with B-ux): after the process died the app resumed playing by itself — through
 * the phone speaker when the DAC was not connected. A restored session always comes back paused.
 */
class RestorePlaybackPolicyTest {
    @Test
    fun `a snapshot saved while playing restores paused`() {
        val playing = PlaybackQueueSnapshot(items = emptyList(), playWhenReady = true)
        assertThat(RestorePlaybackPolicy.playWhenReadyAfterRestore(playing, allowBackgroundPlayback = true)).isFalse()
        assertThat(RestorePlaybackPolicy.playWhenReadyAfterRestore(playing, allowBackgroundPlayback = false)).isFalse()
    }
}
