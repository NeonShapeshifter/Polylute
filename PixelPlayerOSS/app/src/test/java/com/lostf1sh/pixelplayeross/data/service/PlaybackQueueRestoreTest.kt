package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-023 / AUD-044 / AUD-045: the persisted and rebuilt queue is the real queue.
 *
 * - After a crossfade on a queue over 200 items, the master player holds only a 200-item window;
 *   the snapshot and every rebuild copied that window as if it were the whole queue (AUD-023).
 * - The snapshot index was taken by `indexOfFirst(mediaId)` (the wrong occurrence of a repeated
 *   track) and restored against a list that had since been filtered (the wrong track) (AUD-044).
 * - A restore did not declare the Direct start target, so a Direct-capable sink could start the
 *   native engine at 0 under a UI at the saved position (AUD-045).
 */
class PlaybackQueueRestoreTest {

    private val full = (0 until 500).map { "t$it" }

    @Test
    fun `a windowed player persists the full queue and the absolute index`() {
        val window = full.subList(300, 500)
        val (items, index) = QueueSource.select(
            windowed = true,
            fullQueue = full,
            absoluteIndex = 350,
            playerItems = window,
            playerIndex = 50,
        )
        assertThat(items).hasSize(500)
        assertThat(index).isEqualTo(350)
    }

    @Test
    fun `an unwindowed player is its own queue`() {
        val (items, index) = QueueSource.select(
            windowed = false, fullQueue = emptyList(), absoluteIndex = 0,
            playerItems = listOf("a", "b"), playerIndex = 1,
        )
        assertThat(items).containsExactly("a", "b").inOrder()
        assertThat(index).isEqualTo(1)
    }

    @Test
    fun `a repeated track keeps its own occurrence`() {
        // [a, b, a] playing the second "a": every position is kept, so the index is 2, not 0.
        assertThat(QueueIndexMapping.remap(keptOriginalIndices = listOf(0, 1, 2), originalIndex = 2)).isEqualTo(2)
    }

    @Test
    fun `filtered items before the current one shift the index to the same track`() {
        // Originals 0 and 1 were dropped (not local); the current item was original 3.
        assertThat(QueueIndexMapping.remap(keptOriginalIndices = listOf(2, 3, 4), originalIndex = 3)).isEqualTo(1)
    }

    @Test
    fun `a dropped current item falls to the next kept one, else the last`() {
        assertThat(QueueIndexMapping.remap(keptOriginalIndices = listOf(0, 4), originalIndex = 2)).isEqualTo(1)
        assertThat(QueueIndexMapping.remap(keptOriginalIndices = listOf(0, 1), originalIndex = 5)).isEqualTo(1)
        assertThat(QueueIndexMapping.remap(keptOriginalIndices = emptyList(), originalIndex = 0)).isEqualTo(0)
    }

    @Test
    fun `restoring the snapshot declares the Direct start target before setting the items`() {
        val service = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/MusicService.kt").readText()
        val restore = service.substringAfter("private suspend fun restorePlaybackQueueSnapshotIfNeeded(")
            .substringBefore("Restored playback snapshot")
        val declare = restore.indexOf("engine.declareItemStartTarget(")
        assertThat(declare).isAtLeast(0)
        assertThat(declare).isLessThan(restore.indexOf("player.setMediaItems("))
    }
}
