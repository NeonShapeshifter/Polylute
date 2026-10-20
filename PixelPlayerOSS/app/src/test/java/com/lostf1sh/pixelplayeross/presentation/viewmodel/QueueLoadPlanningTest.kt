package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * B13-4. Tapping a song in the library used to rebuild the whole ~4,870-song queue every time: a full
 * database read to find out the queue was the same, then 26 `addMediaItems` calls of 200, each one a
 * timeline publication to the session (29 .. 492 ms of main thread apiece on the HONOR).
 *
 * The device numbers (tap -> `handleBuffer #1`, `Timeline changed` per action) are in B13-resultado.md;
 * these tests pin the decisions behind them.
 */
class QueueLoadPlanningTest {

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    // ---- the queue that is already loaded is the list that was tapped ------------------------------

    @Test
    fun `the queue in the app is the tapped list when every id is at the same position`() {
        val queue = listOf("11", "12", "13", "14")
        assertThat(LoadedQueueReuse.sameList(queue, listOf(11L, 12L, 13L, 14L))).isTrue()
    }

    @Test
    fun `a shuffled queue is not the tapped list, even with the same songs`() {
        val shuffled = listOf("13", "11", "14", "12")
        assertThat(LoadedQueueReuse.sameList(shuffled, listOf(11L, 12L, 13L, 14L))).isFalse()
    }

    @Test
    fun `a queue that lost a song is not the tapped list`() {
        // The list resolves to fewer songs than its ids when one is missing from the database; the old path
        // rebuilt then, and so does this one.
        assertThat(LoadedQueueReuse.sameList(listOf("11", "13"), listOf(11L, 12L, 13L))).isFalse()
    }

    @Test
    fun `an empty queue is never reused`() {
        assertThat(LoadedQueueReuse.sameList(emptyList(), emptyList())).isFalse()
    }

    @Test
    fun `a song inside a window the player holds is found by its id`() {
        val window = listOf("40", "41", "42", "43")
        assertThat(LoadedQueueReuse.indexInWindow(window.size, { window.getOrNull(it) }, "42")).isEqualTo(2)
    }

    @Test
    fun `a song outside the window is not found, and the caller rebuilds`() {
        val window = listOf("40", "41", "42", "43")
        assertThat(LoadedQueueReuse.indexInWindow(window.size, { window.getOrNull(it) }, "3919")).isNull()
    }

    // ---- loading a queue: two publications, not twenty-six ------------------------------------------

    private fun playerHoldingOnly(id: String): Player {
        val player = mockk<Player>(relaxed = true)
        every { player.currentMediaItem } returns item(id)
        every { player.mediaItemCount } returns 1
        every { player.getMediaItemAt(0) } returns item(id)
        return player
    }

    @Test
    fun `a big queue is attached with exactly two addMediaItems calls`() = runBlocking<Unit> {
        val player = playerHoldingOnly("start")
        val before = List(2400) { item("b$it") }
        val after = List(2469) { item("a$it") }
        // After the first call the player really holds before + start.
        every { player.addMediaItems(0, before) } answers {
            every { player.mediaItemCount } returns before.size + 1
        }

        val attached = attachQueueSegments(player, "start", before, after)

        assertThat(attached).isTrue()
        verifyOrder {
            player.addMediaItems(0, before)
            player.addMediaItems(before.size + 1, after)
        }
        verify(exactly = 2) { player.addMediaItems(any<Int>(), any<List<MediaItem>>()) }
    }

    @Test
    fun `a song tapped first in the list needs only the call behind it`() = runBlocking<Unit> {
        val player = playerHoldingOnly("start")
        val after = List(4869) { item("a$it") }

        assertThat(attachQueueSegments(player, "start", emptyList(), after)).isTrue()

        verify(exactly = 1) { player.addMediaItems(any<Int>(), any<List<MediaItem>>()) }
        verify { player.addMediaItems(1, after) }
    }

    @Test
    fun `nothing is attached when the start song is no longer the one playing`() = runBlocking<Unit> {
        val player = playerHoldingOnly("somebody else")

        assertThat(attachQueueSegments(player, "start", listOf(item("b")), listOf(item("a")))).isFalse()

        verify(exactly = 0) { player.addMediaItems(any<Int>(), any<List<MediaItem>>()) }
    }

    @Test
    fun `nothing is attached to a timeline that already holds more than the start song`() = runBlocking<Unit> {
        val player = playerHoldingOnly("start")
        every { player.mediaItemCount } returns 3

        assertThat(attachQueueSegments(player, "start", listOf(item("b")), listOf(item("a")))).isFalse()

        verify(exactly = 0) { player.addMediaItems(any<Int>(), any<List<MediaItem>>()) }
    }

    @Test
    fun `the second call is not made when the user moved on after the first`() = runBlocking<Unit> {
        val player = playerHoldingOnly("start")
        val before = listOf(item("b0"), item("b1"))
        every { player.addMediaItems(0, before) } answers {
            // The user skipped to another song while the main thread was free.
            every { player.currentMediaItem } returns item("elsewhere")
            every { player.mediaItemCount } returns 3
        }

        val attached = attachQueueSegments(player, "start", before, listOf(item("a0")))

        assertThat(attached).isFalse()
        verify(exactly = 1) { player.addMediaItems(any<Int>(), any<List<MediaItem>>()) }
    }
}
