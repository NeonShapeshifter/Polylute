package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import io.mockk.mockk
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B18-R5: removing a song from the queue, and undoing it, act on the occurrence the listener chose.
 *
 * The queue screen lists the songs in play order; the player's indices are in the order the queue was loaded in. The removal used
 * to look the song up twice by its id, once in each: with X in the queue three times the player dropped the first X of its
 * timeline, the undo remembered the first X on screen, and neither was the row that had been swiped.
 *
 * The queue here is `a x b x c x d`, X at timeline indices 1, 3 and 5.
 */
class QueueUndoStateHolderTest {

    private val timeline = listOf("a", "x", "b", "x", "c", "x", "d")

    private fun song(id: String): Song = Song.emptySong().copy(id = id, title = "Track $id")
    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    /** A player holding [timeline], played in [order] when shuffle is on, and the app's idea of the queue (the rows, in play order). */
    private inner class Scene(order: IntArray?) {
        val fake = FakeQueuePlayer()
        val controller: MediaController = fake.stub(mockk<MediaController>(relaxed = true))
        val playOrder: IntArray? = order
        val holder = QueueUndoStateHolder()
        var ui = PlayerUiState()

        init {
            fake.player.setMediaItems(timeline.map(::item), 0, 0L)
            if (order != null) {
                fake.shuffle = true
                fake.order.clear()
                fake.order.addAll(order.toList())
            }
            ui = ui.copy(currentPlaybackQueue = rows().map(::song).toImmutableList())
        }

        /** The ids as the queue screen lists them. */
        fun rows(): List<String> = if (playOrder != null) playOrder.map { timeline[it] } else timeline

        fun remove(scope: kotlinx.coroutines.CoroutineScope, songId: String, row: Int?) = holder.removeSongFromQueue(
            scope = scope,
            mediaController = controller,
            songId = songId,
            position = row,
            playOrder = playOrder,
            playingRow = 0,
            getUiState = { ui },
            updateUiState = { mutation -> ui = mutation(ui) },
        )

        /** What the view model's `reinsertIntoShuffleOrder` does: the song goes back into the timeline, then to its row of the order. */
        fun undo() = holder.undoRemoveSongFromQueue(
            mediaController = controller,
            getUiState = { ui },
            updateUiState = { mutation -> ui = mutation(ui) },
            reinsertIntoShuffleOrder = { insertAt, mediaItem, position ->
                if (playOrder == null || position < 0) return@undoRemoveSongFromQueue false
                fake.player.addMediaItem(insertAt, mediaItem)
                val current = PlayOrder.of(fake.player.currentTimeline, shuffleEnabled = true)
                val placed = PlayOrder.placeAt(current, timelineIndex = insertAt, position = position)
                fake.order.clear()
                fake.order.addAll(placed.toList())
                true
            },
            buildMediaItem = { song -> item(song.id) },
        )
    }

    @Test
    fun `without shuffle each row of a repeated song removes that occurrence and undo puts it back in the same row`() = runTest {
        for (row in listOf(1, 3, 5)) {
            val scene = Scene(order = null)

            scene.remove(backgroundScope, "x", row)

            // Exactly that occurrence went, nothing else.
            assertThat(scene.fake.removed).isEqualTo(1)
            val expected = timeline.toMutableList().also { it.removeAt(row) }
            assertThat(scene.fake.ids).isEqualTo(expected)
            assertThat(scene.ui.lastRemovedQueueIndex).isEqualTo(row)
            assertThat(scene.ui.lastRemovedQueuePosition).isEqualTo(row)
            assertThat(scene.ui.lastRemovedQueueSong?.id).isEqualTo("x")

            scene.undo()

            assertThat(scene.fake.ids).isEqualTo(timeline)
            assertThat(scene.ui.showQueueItemUndoBar).isFalse()
        }
    }

    @Test
    fun `with shuffle each row of a repeated song removes that occurrence and undo puts it back in the same row`() = runTest {
        // Play order: x(5) a x(3) d x(1) b c. Rows 0, 2 and 4 are x; their timeline indices are 5, 3 and 1.
        val order = intArrayOf(5, 0, 3, 6, 1, 2, 4)
        val expectedIndexOfRow = mapOf(0 to 5, 2 to 3, 4 to 1)

        for ((row, timelineIndex) in expectedIndexOfRow) {
            val scene = Scene(order)
            val playedBefore = scene.fake.playOrderIds()

            scene.remove(backgroundScope, "x", row)

            val expected = timeline.toMutableList().also { it.removeAt(timelineIndex) }
            assertThat(scene.fake.ids).isEqualTo(expected)
            assertThat(scene.ui.lastRemovedQueueIndex).isEqualTo(timelineIndex)
            // The row the undo keeps is the row that was swiped, not the first x on screen (row 0) and not the timeline's.
            assertThat(scene.ui.lastRemovedQueuePosition).isEqualTo(row)
            // What is left plays in the order it had, minus that one occurrence.
            val afterRemoval = scene.fake.playOrderIds()
            assertThat(afterRemoval).isEqualTo(playedBefore.toMutableList().also { it.removeAt(row) })

            scene.undo()

            assertThat(scene.fake.ids).isEqualTo(timeline)
            assertThat(scene.fake.playOrderIds()).isEqualTo(playedBefore)
        }
    }

    @Test
    fun `with shuffle the occurrence that goes is not the first x of the timeline`() = runTest {
        // The flaw in one line: row 2 is the x at timeline index 3; B17 removed index 1 (the first x of the timeline).
        val scene = Scene(intArrayOf(5, 0, 3, 6, 1, 2, 4))

        scene.remove(backgroundScope, "x", row = 2)

        assertThat(scene.fake.ids).isEqualTo(listOf("a", "x", "b", "c", "x", "d"))
        assertThat(scene.ui.lastRemovedQueueIndex).isEqualTo(3)
    }

    @Test
    fun `a song that is in the queue once behaves as it always did`() = runTest {
        for (order in listOf(null, intArrayOf(5, 0, 3, 6, 1, 2, 4))) {
            val scene = Scene(order)
            val rows = scene.rows()
            val row = rows.indexOf("d")
            val playedBefore = scene.fake.playOrderIds()

            scene.remove(backgroundScope, "d", row)
            assertThat(scene.fake.ids).isEqualTo(listOf("a", "x", "b", "x", "c", "x"))
            assertThat(scene.ui.lastRemovedQueuePosition).isEqualTo(row)

            scene.undo()
            assertThat(scene.fake.ids).isEqualTo(timeline)
            assertThat(scene.fake.playOrderIds()).isEqualTo(playedBefore)
        }
    }

    @Test
    fun `an entry point that only knows the song removes the playing occurrence, else the first in play order`() = runTest {
        val order = intArrayOf(5, 0, 3, 6, 1, 2, 4)
        val scene = Scene(order)

        // The entry point (the song menu opened from the player) has no row: the playing row, 2, holds x.
        scene.holder.removeSongFromQueue(
            scope = backgroundScope,
            mediaController = scene.controller,
            songId = "x",
            position = null,
            playOrder = order,
            playingRow = 2,
            getUiState = { scene.ui },
            updateUiState = { mutation -> scene.ui = mutation(scene.ui) },
        )

        // Row 2 is the playing one and holds x: that one goes (timeline index 3).
        assertThat(scene.ui.lastRemovedQueuePosition).isEqualTo(2)
        assertThat(scene.ui.lastRemovedQueueIndex).isEqualTo(3)
    }

    @Test
    fun `a song that is not in the queue removes nothing and leaves no undo`() = runTest {
        val scene = Scene(order = null)

        scene.remove(backgroundScope, "zzz", row = 3)

        assertThat(scene.fake.removed).isEqualTo(0)
        assertThat(scene.ui.showQueueItemUndoBar).isFalse()
    }

    @Test
    fun `a stale row that no longer holds the song removes the nearest occurrence of it`() = runTest {
        val scene = Scene(order = null)

        // Row 4 holds "c" now (the queue changed under the swipe): the x nearest to it is row 3.
        scene.remove(backgroundScope, "x", row = 4)

        assertThat(scene.ui.lastRemovedQueuePosition).isEqualTo(3)
        assertThat(scene.fake.ids).isEqualTo(timeline.toMutableList().also { it.removeAt(3) })
    }

    @Test
    fun `while the queue is still being attached a repeated song is not removed by guesswork`() = runTest {
        val scene = Scene(order = null)
        // The screen lists the whole queue; the player was started with a block of it and holds three songs.
        scene.fake.player.setMediaItems(listOf("b", "x", "c").map(::item), 1, 0L)

        scene.remove(backgroundScope, "x", row = 3)

        assertThat(scene.fake.removed).isEqualTo(0)
        assertThat(scene.fake.ids).isEqualTo(listOf("b", "x", "c"))
        assertThat(scene.ui.showQueueItemUndoBar).isFalse()
    }
}
