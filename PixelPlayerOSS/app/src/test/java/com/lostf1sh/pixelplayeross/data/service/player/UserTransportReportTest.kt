package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.TrackEndReason
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * B28 D0: every controller reaches the player through [MappingPlayer], so it is the one place that knows what the listener asked
 * (Next, Previous, start over, pick another track) and says it to the listening history before the player moves. A position seek
 * inside the item is not a departure and says nothing.
 */
class UserTransportReportTest {

    private val told = mutableListOf<TrackEndReason>()
    private var position = 0L
    private var index = 3
    private var hasPrevious = true

    private val inner = mockk<Player>(relaxed = true).also {
        every { it.currentPosition } answers { position }
        every { it.currentMediaItemIndex } answers { index }
        every { it.hasPreviousMediaItem() } answers { hasPrevious }
        every { it.maxSeekToPreviousPosition } returns 3_000L
        every { it.isCurrentMediaItemLive } returns false
    }

    private fun player() = MappingPlayer(inner, mockk<Context>(relaxed = true), onUserTransport = { told += it })

    @Test
    fun `Next from any controller is told as the listener's Next`() {
        val p = player()
        p.seekToNext()
        p.seekToNextMediaItem()
        assertThat(told).containsExactly(TrackEndReason.USER_NEXT, TrackEndReason.USER_NEXT)
    }

    @Test
    fun `Previous near the start is the listener's Previous`() {
        position = 1_500L
        player().seekToPrevious()
        assertThat(told).containsExactly(TrackEndReason.USER_PREVIOUS)

        told.clear()
        player().seekToPreviousMediaItem()
        assertThat(told).containsExactly(TrackEndReason.USER_PREVIOUS)
    }

    @Test
    fun `Previous late in the track, or with nothing before it, starts it over and is not a departure`() {
        position = 40_000L
        player().seekToPrevious()
        assertThat(told).containsExactly(TrackEndReason.USER_REPLAY)

        told.clear()
        position = 1_000L
        hasPrevious = false
        player().seekToPrevious()
        assertThat(told).containsExactly(TrackEndReason.USER_REPLAY)
    }

    @Test
    fun `picking another item is told, and a seek to a position inside the current one is not`() {
        val p = player()
        p.seekTo(7, 0L)
        assertThat(told).containsExactly(TrackEndReason.USER_SELECTED_OTHER)

        told.clear()
        p.seekTo(index, 30_000L)
        p.seekTo(45_000L)
        assertThat(told).isEmpty()
    }

    @Test
    fun `a player built without the hook still moves`() {
        val p = MappingPlayer(inner, mockk<Context>(relaxed = true))
        p.seekToNext()
        p.seekTo(9, 0L)
        assertThat(told).isEmpty()
    }
}
