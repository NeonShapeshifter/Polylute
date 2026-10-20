package com.lostf1sh.pixelplayeross.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B18-R5: which occurrence of a repeated song an operation on the queue means.
 *
 * A queue can hold a song more than once. The screen lists rows in play order; the player's indices are in the order the queue was
 * loaded in. Naming the song (its id) says nothing about which of its occurrences, so the row travels instead.
 */
class QueueOccurrenceTest {

    // --- the row ------------------------------------------------------------------------------------

    @Test
    fun `the row asked for is the row used when it holds the song`() {
        val rows = listOf("x", "a", "x", "b", "x")
        assertThat(QueueOccurrence.row(rows, "x", requestedRow = 2, playingRow = 0)).isEqualTo(2)
        assertThat(QueueOccurrence.row(rows, "x", requestedRow = 4, playingRow = 0)).isEqualTo(4)
        assertThat(QueueOccurrence.row(rows, "x", requestedRow = 0, playingRow = 4)).isEqualTo(0)
    }

    @Test
    fun `a row that moved under the finger resolves to the nearest occurrence, never a far one`() {
        // The queue was re-listed between the tap and the removal: row 3 now holds "b".
        val rows = listOf("x", "a", "x", "b", "x", "c", "x")
        assertThat(QueueOccurrence.row(rows, "x", requestedRow = 3, playingRow = 0)).isEqualTo(2)
        assertThat(QueueOccurrence.row(rows, "x", requestedRow = 5, playingRow = 0)).isEqualTo(4)
        // A unique song that moved is found wherever it went.
        assertThat(QueueOccurrence.row(rows, "c", requestedRow = 0, playingRow = 0)).isEqualTo(5)
    }

    @Test
    fun `with no row, the song that is playing if it is this one, else the first in play order`() {
        val rows = listOf("a", "x", "b", "x", "c")
        assertThat(QueueOccurrence.row(rows, "x", requestedRow = null, playingRow = 3)).isEqualTo(3)
        assertThat(QueueOccurrence.row(rows, "x", requestedRow = null, playingRow = 0)).isEqualTo(1)
        assertThat(QueueOccurrence.row(rows, "x", requestedRow = null, playingRow = -1)).isEqualTo(1)
    }

    @Test
    fun `a song that is not in the queue has no row`() {
        assertThat(QueueOccurrence.row(listOf("a", "b"), "x", requestedRow = 1, playingRow = 0)).isNull()
        assertThat(QueueOccurrence.row(listOf("a", "b"), "x", requestedRow = null, playingRow = 0)).isNull()
        assertThat(QueueOccurrence.row(emptyList(), "x", requestedRow = 0, playingRow = -1)).isNull()
    }

    // --- the player's index -------------------------------------------------------------------------

    /** A whole queue of [timeline] ids, played in [order] when it is not null. */
    private fun index(order: IntArray?, rows: List<String>, row: Int, song: String, timeline: List<String>) =
        QueueOccurrence.timelineIndexOfRow(order, row, rows, song, timeline.size) { timeline.getOrNull(it) }

    @Test
    fun `without shuffle the row is the player's index`() {
        val timeline = listOf("a", "x", "b", "x", "c", "x")
        for (row in listOf(1, 3, 5)) assertThat(index(null, timeline, row, "x", timeline)).isEqualTo(row)
    }

    @Test
    fun `with shuffle the row maps through the order, and each occurrence of a repeated song is its own index`() {
        val timeline = listOf("a", "x", "b", "x", "c", "x", "d")
        val order = intArrayOf(5, 0, 3, 6, 1, 2, 4) // play order: x(5) a x(3) d x(1) b c
        val rows = order.map { timeline[it] }

        assertThat(index(order, rows, 0, "x", timeline)).isEqualTo(5)
        assertThat(index(order, rows, 2, "x", timeline)).isEqualTo(3)
        assertThat(index(order, rows, 4, "x", timeline)).isEqualTo(1)
        // The first x of the TIMELINE (index 1) is the third one on screen: asking for it by id was the flaw.
        assertThat(rows.indexOf("x")).isEqualTo(0)
        assertThat(order[rows.indexOf("x")]).isNotEqualTo(timeline.indexOf("x"))
    }

    @Test
    fun `a unique song maps the same either way`() {
        val timeline = listOf("a", "x", "b", "x", "c", "x", "d")
        val order = intArrayOf(5, 0, 3, 6, 1, 2, 4)
        val rows = order.map { timeline[it] }

        assertThat(index(order, rows, rows.indexOf("d"), "d", timeline)).isEqualTo(6)
        assertThat(index(null, timeline, 6, "d", timeline)).isEqualTo(6)
    }

    @Test
    fun `a player that holds only part of the queue finds a unique song by id and never guesses among repeats`() {
        // The screen lists 12 rows; the player was started with the block around row 6 and holds rows 4..8.
        val rows = listOf("a", "x", "b", "c", "x", "d", "x", "e", "x", "f", "g", "x")
        val timeline = rows.subList(4, 9) // x d x e x

        // A unique song is found wherever the player has it (row 5 is index 1 of the block).
        assertThat(QueueOccurrence.timelineIndexOfRow(null, 5, rows, "d", timeline.size) { timeline.getOrNull(it) }).isEqualTo(1)
        // x is listed five times: which one row 8 means cannot be told from the block, so nothing is removed, not "some x".
        assertThat(QueueOccurrence.timelineIndexOfRow(null, 8, rows, "x", timeline.size) { timeline.getOrNull(it) }).isNull()
        // A unique song the player does not hold yet is not found either.
        assertThat(QueueOccurrence.timelineIndexOfRow(null, 10, rows, "g", timeline.size) { timeline.getOrNull(it) }).isNull()
    }

    @Test
    fun `a whole player whose index does not hold the song is not guessed`() {
        val rows = listOf("a", "x", "b")
        // The player says index 1 holds "b", not "x": the queue changed under the screen; nothing is removed.
        assertThat(QueueOccurrence.timelineIndexOfRow(null, 1, rows, "x", 3) { listOf("a", "b", "x").getOrNull(it) }).isNull()
    }

    @Test
    fun `a row outside the player is not guessed`() {
        val rows = listOf("a", "b", "c")
        assertThat(QueueOccurrence.timelineIndexOfRow(null, 7, rows, "a", 3) { listOf("a", "b", "c").getOrNull(it) }).isNull()
    }
}
