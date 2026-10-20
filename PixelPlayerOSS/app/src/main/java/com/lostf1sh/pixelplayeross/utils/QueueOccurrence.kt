package com.lostf1sh.pixelplayeross.utils

/**
 * Which occurrence of a song a queue operation means (B18-R5).
 *
 * A queue can hold the same song more than once, so a song id does not say which row an operation is about. The queue
 * screen lists the songs in the order they play (a **row** is a position in the play order); every Media3 call speaks in
 * the order the queue was loaded in (a **timeline index**; see [PlayOrder]). Removing "song X" by looking the id up twice,
 * once in each, picked the first occurrence in the timeline for the player and the first one on screen for the undo: with
 * X twice and shuffle on those are two different songs, the undo put the song back somewhere else, and neither was the one
 * the listener had swiped.
 *
 * So the row is what travels: it names the occurrence, [timelineIndexOfRow] maps it to the player, and the same row is
 * kept for the undo. Pure.
 */
object QueueOccurrence {

    /**
     * The row an operation on [songId] means.
     *
     * - [requestedRow] when it still holds that song (what the swipe or the More menu was opened on).
     * - Otherwise, when the queue changed under the finger so that row no longer holds it, the nearest row that does
     *   (a unique song that moved is found; one of several is the closest one, never a far-away occurrence).
     * - With no row at all (an entry point that only knows the song), the one that is playing if it is this song, else the
     *   first in play order.
     *
     * Null when [songId] is not in the queue.
     */
    fun row(queueIds: List<String>, songId: String, requestedRow: Int?, playingRow: Int): Int? {
        if (requestedRow != null) {
            if (queueIds.getOrNull(requestedRow) == songId) return requestedRow
            var best = -1
            for (row in queueIds.indices) {
                if (queueIds[row] != songId) continue
                if (best < 0 || kotlin.math.abs(row - requestedRow) < kotlin.math.abs(best - requestedRow)) best = row
            }
            return best.takeIf { it >= 0 }
        }
        if (queueIds.getOrNull(playingRow) == songId) return playingRow
        return queueIds.indexOf(songId).takeIf { it >= 0 }
    }

    /**
     * The timeline index that holds the song listed at [row]: [order] (positions to timeline indices) maps it with shuffle
     * on, the row itself is the index with shuffle off ([order] null). [mediaIdAt] reads the player; the answer is checked
     * against [songId].
     *
     * The row is only an index of the player when the player holds as many songs as the screen lists. It may not: a queue that
     * is still being attached is a block of the list in the player and the whole list on the screen, and the row of a song
     * says nothing about its index in the block. Then the song is looked for by id, and only a song that is listed once can be
     * found that way: for a repeated one the occurrence meant cannot be told from the others, and removing another one is the
     * flaw this exists to prevent, so the answer is null (nothing is removed) until the whole queue is in.
     */
    fun timelineIndexOfRow(
        order: IntArray?,
        row: Int,
        queueIds: List<String>,
        songId: String,
        count: Int,
        mediaIdAt: (Int) -> String?,
    ): Int? {
        if (count == queueIds.size) {
            val direct = if (order != null) order.getOrNull(row) else row.takeIf { it in 0 until count }
            return direct?.takeIf { mediaIdAt(it) == songId }
        }
        if (queueIds.count { it == songId } != 1) return null
        for (index in 0 until count) if (mediaIdAt(index) == songId) return index
        return null
    }
}
