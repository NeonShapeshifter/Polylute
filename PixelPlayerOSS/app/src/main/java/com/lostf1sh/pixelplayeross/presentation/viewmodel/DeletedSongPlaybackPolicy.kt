package com.lostf1sh.pixelplayeross.presentation.viewmodel

/** What deleting a song has to do to playback, given what is playing and what is left. */
enum class DeletedSongPlaybackAction {
    /**
     * Drop it from the queue and let Media3 decide what plays next. Correct when the song is not
     * the one playing, and when something else remains to take over.
     */
    REMOVE_FROM_QUEUE_ONLY,

    /**
     * Stop, clear the media items and null the current song. Required when the deleted song is the
     * last item: removing it from the timeline alone leaves the UI mirror rendering a song whose row
     * and file are both gone.
     */
    STOP_AND_CLEAR,
}

/**
 * The deletion/playback contract, as a pure function so it can be stated and tested without a
 * player, a controller or a device.
 *
 * This exists because the previous rule was a refusal: the song being played could not be deleted at
 * all. `currentSong` only moves when playback moves, so with one song left the refusal could never
 * stop applying and the library could not be emptied from inside the app — the one remaining song
 * had to be removed from a PC.
 *
 * [queueItemsIncludingDeleted] is the queue as it stands *before* the removal, so it still counts
 * the song being deleted. Anything above one means something survives it.
 */
object DeletedSongPlaybackPolicy {
    fun decide(
        isCurrentSong: Boolean,
        queueItemsIncludingDeleted: Int,
    ): DeletedSongPlaybackAction = when {
        !isCurrentSong -> DeletedSongPlaybackAction.REMOVE_FROM_QUEUE_ONLY
        queueItemsIncludingDeleted > 1 -> DeletedSongPlaybackAction.REMOVE_FROM_QUEUE_ONLY
        else -> DeletedSongPlaybackAction.STOP_AND_CLEAR
    }
}
