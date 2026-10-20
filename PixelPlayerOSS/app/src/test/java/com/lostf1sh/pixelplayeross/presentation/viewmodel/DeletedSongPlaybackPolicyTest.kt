package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * What deleting a song does to playback.
 *
 * These are the contracts behind "the last song cannot be deleted". The rule used to be a refusal
 * for the song being played, which with one song left could never stop applying — so the library
 * could not be emptied from inside the app and the final song had to be removed from a PC.
 */
class DeletedSongPlaybackPolicyTest {

    @Test
    fun `deleting a song that is not playing is only a queue edit`() {
        assertThat(
            DeletedSongPlaybackPolicy.decide(isCurrentSong = false, queueItemsIncludingDeleted = 3),
        ).isEqualTo(DeletedSongPlaybackAction.REMOVE_FROM_QUEUE_ONLY)
        assertThat(
            DeletedSongPlaybackPolicy.decide(isCurrentSong = false, queueItemsIncludingDeleted = 1),
        ).isEqualTo(DeletedSongPlaybackAction.REMOVE_FROM_QUEUE_ONLY)
    }

    @Test
    fun `deleting the playing song while others remain lets playback move on`() {
        // The requirement is "removes it and advances safely", not "stops".
        assertThat(
            DeletedSongPlaybackPolicy.decide(isCurrentSong = true, queueItemsIncludingDeleted = 2),
        ).isEqualTo(DeletedSongPlaybackAction.REMOVE_FROM_QUEUE_ONLY)
    }

    @Test
    fun `deleting the last song stops and clears rather than leaving a stale item`() {
        // Queue as it stands before the removal: the deleted song is the only item in it.
        assertThat(
            DeletedSongPlaybackPolicy.decide(isCurrentSong = true, queueItemsIncludingDeleted = 1),
        ).isEqualTo(DeletedSongPlaybackAction.STOP_AND_CLEAR)
    }

    @Test
    fun `an empty or unknown queue never leaves the deleted song as current`() {
        // Both counts can read zero before the controller has published a timeline. That is not a
        // reason to keep a deleted row on screen, so it resolves to the clearing path.
        assertThat(
            DeletedSongPlaybackPolicy.decide(isCurrentSong = true, queueItemsIncludingDeleted = 0),
        ).isEqualTo(DeletedSongPlaybackAction.STOP_AND_CLEAR)
    }

    @Test
    fun `the playing song is never left in the queue once decided`() {
        // No input may resolve to "keep it": either it is removed, or the queue is cleared. This is
        // the property that makes Library = 0 songs reachable.
        for (items in 0..5) {
            for (isCurrent in listOf(true, false)) {
                val action = DeletedSongPlaybackPolicy.decide(isCurrent, items)
                assertThat(action).isAnyOf(
                    DeletedSongPlaybackAction.REMOVE_FROM_QUEUE_ONLY,
                    DeletedSongPlaybackAction.STOP_AND_CLEAR,
                )
            }
        }
    }
}
