package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import androidx.media3.common.Player
import com.decent.usbaudio.media3.BootstrapSeekPolicy
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * AUD-117 (confirmed on the device): "previous" between ~3 and 10 s did not restart the audio. The
 * session's seekToPrevious reached the player without the explicit authority, and the sink's
 * bootstrap compared the target (0) with a stale explicit target left at 0 by the item start —
 * "Bootstrap seek skipped: engine already at 0ms" with the engine at 5.4 s.
 *
 * - Every position seek arriving at the session player now goes through the explicit authority
 *   ([MappingPlayer] → `notifyExternalSeekInitiated`), including seekToPrevious from any controller.
 * - The bootstrap trusts the explicit target only when it was issued for this very flush; otherwise it
 *   compares with the engine's live position.
 */
class SeekAuthorityTest {

    private val seeks = mutableListOf<Long>()
    private var position = 0L
    private var hasPrevious = true

    private val inner = mockk<Player>(relaxed = true).also {
        every { it.currentPosition } answers { position }
        every { it.hasPreviousMediaItem() } answers { hasPrevious }
        every { it.maxSeekToPreviousPosition } returns 3_000L
        every { it.isCurrentMediaItemLive } returns false
    }

    private fun player() = MappingPlayer(inner, mockk<Context>(relaxed = true), onPositionSeek = { seeks += it })

    @Test
    fun `seekToPrevious past the threshold is an explicit restart`() {
        position = 5_400L
        player().seekToPrevious()
        assertThat(seeks).containsExactly(0L)
        verify { inner.seekTo(0L) }
        verify(exactly = 0) { inner.seekToPrevious() }
    }

    @Test
    fun `seekToPrevious near the start moves to the previous item without a position seek`() {
        position = 2_000L
        player().seekToPrevious()
        assertThat(seeks).isEmpty()
        verify { inner.seekToPreviousMediaItem() }
    }

    @Test
    fun `with no previous item it restarts, explicitly`() {
        position = 1_000L
        hasPrevious = false
        player().seekToPrevious()
        assertThat(seeks).containsExactly(0L)
    }

    @Test
    fun `a position seek from any controller reaches the authority`() {
        player().seekTo(12_345L)
        assertThat(seeks).containsExactly(12_345L)
        verify { inner.seekTo(12_345L) }
    }

    @Test
    fun `the bootstrap does not trust a stale explicit target`() {
        // The device case: engine at 5.4 s, target 0, explicit target 0 left by the item start.
        assertThat(BootstrapSeekPolicy.alreadyThere(0L, 5_400_000L, explicitTargetUs = 0L, explicitIsFresh = false))
            .isFalse()
        // A fresh explicit seek to the same target already moved the engine: skipping is right.
        assertThat(BootstrapSeekPolicy.alreadyThere(0L, 300_000L, explicitTargetUs = 0L, explicitIsFresh = true))
            .isTrue()
        // The engine genuinely at the target.
        assertThat(BootstrapSeekPolicy.alreadyThere(0L, 40_000L, explicitTargetUs = -1L, explicitIsFresh = false))
            .isTrue()
    }

    @Test
    fun `the UI's own notification and the session's echo of it seek once`() {
        val deduper = ExplicitSeekDeduper(windowMs = 500L)
        assertThat(deduper.shouldForward(0L, nowMs = 1_000L)).isTrue()
        assertThat(deduper.shouldForward(0L, nowMs = 1_120L)).isFalse()
        assertThat(deduper.shouldForward(0L, nowMs = 1_700L)).isTrue()
        assertThat(deduper.shouldForward(9_000L, nowMs = 1_710L)).isTrue()
    }
}
