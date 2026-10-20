package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import com.lostf1sh.pixelplayeross.utils.QueueUtils
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * B31 P2.2: **the draw of a "Shuffle" press is the list indices it is, handed to the play order as they are**, and every press logs where its
 * time went.
 *
 * Until B31 the draw was turned into a second list of the whole library, both lists were turned into id strings, and the play order was
 * found by matching the two by id in a map of queues (`PlayOrder.fromSequence`), on a path the owner measured at ~1 s against 0.29 s for a plain
 * tap. (A tap on a list that is already the loaded queue is only a seek; "shuffle all" always rebuilds, which is most of the gap and is
 * the log's to confirm on the phone.) The draw is the same; what is built from it is not.
 */
class ShuffleAllDealTest {
    private fun library(count: Int, duplicateEvery: Int = 0): List<Song> = (0 until count).map { index ->
        val id = if (duplicateEvery > 0 && index % duplicateEvery == 1) "s%04d".format(index - 1) else "s%04d".format(index)
        Song(
            id = id, title = "Song $index", artist = "Artist", artistId = 1L, album = "Album", albumId = 1L, path = "path/$index",
            contentUriString = "content://dummy/$index", albumArtUriString = null, duration = 180_000L, mimeType = "audio/mpeg",
            bitrate = null, sampleRate = null,
        )
    }

    // ── the draw ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the draw is a permutation of the list that begins with the start song, and the original order is kept`() = runBlocking {
        val songs = library(4_870)
        val holder = QueueStateHolder()
        val deal = holder.dealShuffledQueue(songs, startAtZero = true)!!
        assertThat(deal.startIndex).isIn(songs.indices)
        assertThat(deal.startSong).isSameInstanceAs(songs[deal.startIndex])
        assertThat(deal.order).hasLength(songs.size)
        assertThat(deal.order[0]).isEqualTo(deal.startIndex)
        assertThat(deal.order.toSet()).hasSize(songs.size)
        assertThat(deal.order.all { it in songs.indices }).isTrue()
        assertThat(deal.order.toList()).isNotEqualTo(songs.indices.toList())
        assertThat(holder.originalQueueOrder).isEqualTo(songs)
    }

    @Test
    fun `without start at zero the start song keeps the place it had`() = runBlocking {
        val songs = library(500)
        val deal = QueueStateHolder().dealShuffledQueue(songs, startAtZero = false)!!
        assertThat(deal.order[deal.startIndex]).isEqualTo(deal.startIndex)
        assertThat(deal.order.toSet()).hasSize(500)
    }

    @Test
    fun `an empty list has no draw, and a list of one plays that one`() = runBlocking {
        assertThat(QueueStateHolder().dealShuffledQueue(emptyList(), startAtZero = true)).isNull()
        val deal = QueueStateHolder().dealShuffledQueue(library(1), startAtZero = true)!!
        assertThat(deal.order.toList()).containsExactly(0)
    }

    @Test
    fun `the order is the draw the song list is built from, so the two paths agree`() = runBlocking {
        val songs = library(1_000)
        val seed = 12345
        val order = QueueUtils.dealOrder(songs.size, 321, startAtZero = true, random = Random(seed))
        val sequence = QueueUtils.buildAnchoredShuffleQueueSuspending(songs, 321, startAtZero = true, random = Random(seed))
        assertThat(sequence.map { it.id }).isEqualTo(order.map { songs[it].id })
        // What the player was given until B31 (ids matched) and what it is given now (the indices) are the same play order.
        assertThat(PlayOrder.fromSequence(songs.map { it.id }, sequence.map { it.id })).isEqualTo(order)
    }

    @Test
    fun `a song that is in the list twice starts where the draw says, and the order is still a permutation of positions`() = runBlocking {
        val songs = library(400, duplicateEvery = 10)
        repeat(20) {
            val deal = QueueStateHolder().dealShuffledQueue(songs, startAtZero = true)!!
            assertThat(deal.order[0]).isEqualTo(deal.startIndex)
            assertThat(deal.order.toSet()).hasSize(400)
        }
    }

    // ── the load with the indices as they are ───────────────────────────────────────────────────────────

    @Test
    fun `a press loads the same timeline and the same final order whether the order was matched by ids or handed over as indices`() = runBlocking {
        val songs = library(4_870)
        val deal = QueueStateHolder().dealShuffledQueue(songs, startAtZero = true)!!
        val byIndices = QueueLoadHarness(size = songs.size, start = deal.startIndex, shuffle = true, idOf = { songs[it].id }, givenOrder = deal.order)
        byIndices.putFirstSongs()
        byIndices.loadRest()
        val byIds = QueueLoadHarness(
            size = songs.size, start = deal.startIndex, shuffle = true, idOf = { songs[it].id },
            givenSequence = deal.order.map { songs[it].id },
        )
        byIds.putFirstSongs()
        byIds.loadRest()

        assertThat(byIndices.fake.ids).isEqualTo(songs.map { it.id })
        assertThat(byIndices.fake.ids).isEqualTo(byIds.fake.ids)
        assertThat(byIndices.finalIds()).isEqualTo(byIds.finalIds())
        assertThat(byIndices.finalIds().first()).isEqualTo(songs[deal.startIndex].id)
        assertThat(byIndices.fake.shuffle).isTrue()
    }

    // ── the log line ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the press's line names each phase with the time since the one before it, and the total`() {
        var now = 1_000L
        val timeline = ShuffleAllTimeline("All Songs (Shuffled)") { now }
        for ((phase, took) in listOf("ids" to 120L, "resolve" to 15L, "draw" to 9L, "block" to 40L, "order" to 3L, "load" to 210L, ShuffleAllTimeline.LAST to 300L)) {
            now += took
            timeline.mark(phase)
        }
        timeline.mark("ids") // a phase is marked once
        assertThat(timeline.line()).isEqualTo(
            "ShuffleAll[All Songs (Shuffled)]: tap→first timeline 697 ms (ids 120 ms, resolve 15 ms, draw 9 ms, block 40 ms, order 3 ms, load 210 ms, timeline 300 ms)"
        )
    }

    @Test
    fun `a press whose timeline never came says so instead of inventing one`() {
        var now = 0L
        val timeline = ShuffleAllTimeline("Liked Songs (Shuffled)") { now }
        now += 50; timeline.mark("ids")
        now += 25; timeline.mark("resolve")
        assertThat(timeline.line()).isEqualTo("ShuffleAll[Liked Songs (Shuffled)]: tap→resolve (no timeline seen) 75 ms (ids 50 ms, resolve 25 ms)")
    }
}
