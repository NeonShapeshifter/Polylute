package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.media3.session.MediaController
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import com.lostf1sh.pixelplayeross.utils.QueueOccurrence
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@ViewModelScoped
class QueueUndoStateHolder @Inject constructor() {
    private var queueItemUndoTimerJob: Job? = null

    /**
     * Removes one occurrence of [songId] from the queue, and remembers where it was for the undo.
     *
     * The occurrence is named by its **row** in the queue screen ([position]: a place in the play order), not by the song
     * id, which a queue can hold several times (B18-R5). The row is mapped to the player's timeline index through
     * [playOrder] (positions to timeline indices, null with shuffle off), the same row is what the undo keeps. [position]
     * null means the caller only knows the song: the occurrence that is playing, else the first in play order
     * ([QueueOccurrence.row]).
     */
    fun removeSongFromQueue(
        scope: CoroutineScope,
        mediaController: MediaController?,
        songId: String,
        position: Int?,
        playOrder: IntArray?,
        playingRow: Int,
        getUiState: () -> PlayerUiState,
        updateUiState: (((PlayerUiState) -> PlayerUiState) -> Unit),
    ) {
        val controller = mediaController ?: return

        val currentQueue = getUiState().currentPlaybackQueue
        val queueIds = currentQueue.map { it.id }
        val removedPosition = QueueOccurrence.row(queueIds, songId, position, playingRow) ?: return
        val removedSong = currentQueue[removedPosition]
        val indexToRemove = QueueOccurrence.timelineIndexOfRow(
            order = playOrder,
            row = removedPosition,
            queueIds = queueIds,
            songId = songId,
            count = controller.mediaItemCount,
            mediaIdAt = { index -> runCatching { controller.getMediaItemAt(index).mediaId }.getOrNull() },
        ) ?: return

        controller.removeMediaItem(indexToRemove)

        updateUiState {
            it.copy(
                showQueueItemUndoBar = true,
                lastRemovedQueueSong = removedSong,
                lastRemovedQueueIndex = indexToRemove,
                lastRemovedQueuePosition = removedPosition
            )
        }

        queueItemUndoTimerJob?.cancel()
        queueItemUndoTimerJob = scope.launch {
            delay(4000L)
            if (getUiState().showQueueItemUndoBar) {
                updateUiState {
                    it.copy(
                        showQueueItemUndoBar = false,
                        lastRemovedQueueSong = null,
                        lastRemovedQueueIndex = -1,
                        lastRemovedQueuePosition = -1
                    )
                }
            }
        }
    }

    fun undoRemoveSongFromQueue(
        mediaController: MediaController?,
        getUiState: () -> PlayerUiState,
        updateUiState: (((PlayerUiState) -> PlayerUiState) -> Unit),
        /**
         * With shuffle on the song goes back to the row it left, which is a place in the play order, not an index of the
         * timeline: asked first with the timeline index, the song and the row, it answers whether it did it (B17-H1).
         */
        reinsertIntoShuffleOrder: (insertAt: Int, mediaItem: androidx.media3.common.MediaItem, position: Int) -> Boolean =
            { _, _, _ -> false },
        buildMediaItem: (Song) -> androidx.media3.common.MediaItem = MediaItemBuilder::build,
    ) {
        val uiState = getUiState()
        val song = uiState.lastRemovedQueueSong ?: return
        val index = uiState.lastRemovedQueueIndex
        if (index < 0) return

        mediaController?.let { controller ->
            val mediaItem = buildMediaItem(song)
            val insertAt = index.coerceAtMost(controller.mediaItemCount)
            if (!reinsertIntoShuffleOrder(insertAt, mediaItem, uiState.lastRemovedQueuePosition)) {
                controller.addMediaItem(insertAt, mediaItem)
            }
        }

        hideQueueItemUndoBar(updateUiState)
    }

    fun hideQueueItemUndoBar(
        updateUiState: (((PlayerUiState) -> PlayerUiState) -> Unit),
    ) {
        queueItemUndoTimerJob?.cancel()
        updateUiState {
            it.copy(
                showQueueItemUndoBar = false,
                lastRemovedQueueSong = null,
                lastRemovedQueueIndex = -1,
                lastRemovedQueuePosition = -1
            )
        }
    }

    fun onCleared() {
        queueItemUndoTimerJob?.cancel()
    }
}
