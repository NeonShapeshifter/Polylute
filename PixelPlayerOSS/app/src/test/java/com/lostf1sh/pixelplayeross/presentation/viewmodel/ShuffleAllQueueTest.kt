package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * B24-Q1, the owner's rule: a queue is always its source list.
 *
 * What the owner saw on 2026-10-14 16:14: "Uptight" playing with random off, and next "Hoe Cakes" (MF DOOM) instead of "Uptown
 * Girl", the next song of the Songs list sorted by title. The round shuffle button had loaded 500 songs that the database had
 * already put in random order, so with random off there was no list order to go back to.
 *
 * These tests run the real pieces after the repository: [QueueStateHolder.prepareShuffledQueueSuspending] (the draw), the plan, the
 * first and the second stage of the load and the session's player ([QueueLoadHarness] over [FakeQueuePlayer]). What the view
 * model hands them is pinned in `PlayerViewModelTest` (the full list, in the Library's order). Together: the timeline's base order
 * is the list as listed, random is Media3's shuffle order on top, and turning random off goes on from the current song in list order.
 */
class ShuffleAllQueueTest {

    private fun library(count: Int): List<Song> = (0 until count).map { index ->
        Song(
            id = "s%04d".format(index), title = "Song %04d".format(index), artist = "Artist", artistId = 1L, album = "Album",
            albumId = 1L, path = "path/$index", contentUriString = "content://dummy/$index", albumArtUriString = null,
            duration = 180_000L, mimeType = "audio/mpeg", bitrate = null, sampleRate = null,
        )
    }

    /** One "Shuffle" press on [list]: the draw the view model makes (a random start, a fresh order) and the staged load of the list. */
    private fun shufflePress(list: List<Song>): Pair<QueueLoadHarness, Int> = runBlocking {
        val (sequence, startSong) = QueueStateHolder().prepareShuffledQueueSuspending(list, startAtZero = true)!!
        val start = list.indexOfFirst { it.id == startSong.id }
        val load = QueueLoadHarness(
            size = list.size, start = start, shuffle = true, idOf = { list[it].id },
            givenSequence = sequence.map { it.id },
        )
        load.putFirstSongs()
        load.loadRest()
        load to start
    }

    @Test
    fun `shuffle all on 4,870 songs loads the whole list in the list's order, random on, from a random song`() {
        val songs = library(4_870)

        val (load, start) = shufflePress(songs)

        // The base timeline is the whole list, as listed: no sample, no second list, nothing pre-shuffled.
        assertThat(load.fake.ids).hasSize(4_870)
        assertThat(load.fake.ids).isEqualTo(songs.map { it.id })
        // Random is the player's own shuffle mode over it, and the song that plays is the drawn one.
        assertThat(load.fake.shuffle).isTrue()
        assertThat(load.fake.playing).isEqualTo(songs[start].id)
        // A fresh draw starting at that song, and not the list's own order.
        assertThat(load.finalIds().first()).isEqualTo(songs[start].id)
        assertThat(load.finalIds()).isNotEqualTo(songs.map { it.id })
        assertThat(load.finalIds().toSet()).hasSize(4_870)
        // Only the staged load: a block around the start song, then the rest attached in one step for each side of the block that has
        // songs outside it. The start is random, so near either end of the list one side has none and there is one step, not two; the
        // expectation is read from the block, not from the draw (B28-fix2 E5, which this test failed once in about two hundred runs).
        assertThat(load.fake.addCalls).hasSize(stepsFor(load.plan))
        assertThat(load.fake.addCalls.sum() + load.plan.windowSize).isEqualTo(4_870)
    }

    private fun stepsFor(plan: QueueLoadPlan): Int = listOf(plan.restBefore, plan.restAfter).count { !it.isEmpty() }

    /** One staged load of [size] songs started on [start], with no draw: the start is given. */
    private fun stagedLoad(size: Int, start: Int): QueueLoadHarness = runBlocking {
        QueueLoadHarness(size = size, start = start, shuffle = true).also {
            it.putFirstSongs()
            it.loadRest()
        }
    }

    @Test
    fun `the rest of the list is attached in one step for each side of the block that has songs, wherever the start is`() {
        val size = 4_870
        val behind = QueueLoadPlan.BEHIND
        val ahead = QueueLoadPlan.AHEAD

        // In the middle both sides have songs. At the first song nothing is in front; at the last song nothing is behind.
        assertThat(stagedLoad(size, size / 2).fake.addCalls).hasSize(2)
        assertThat(stagedLoad(size, 0).fake.addCalls).hasSize(1)
        assertThat(stagedLoad(size, size - 1).fake.addCalls).hasSize(1)
        // The edge of the rule: the first start with a song in front of the block, and the last start with a song behind it.
        assertThat(stagedLoad(size, behind).fake.addCalls).hasSize(1)
        assertThat(stagedLoad(size, behind + 1).fake.addCalls).hasSize(2)
        assertThat(stagedLoad(size, size - 1 - ahead).fake.addCalls).hasSize(1)
        assertThat(stagedLoad(size, size - 2 - ahead).fake.addCalls).hasSize(2)
        // Whichever it is, the whole list ends up in the player.
        listOf(0, behind, size / 2, size - 1).forEach { start ->
            val load = stagedLoad(size, start)
            assertThat(load.fake.addCalls.sum() + load.plan.windowSize).isEqualTo(size)
            assertThat(load.fake.addCalls).hasSize(stepsFor(load.plan))
        }
    }

    @Test
    fun `switching random off goes on from the current song in list order - Uptight then Uptown Girl`() {
        val songs = library(4_870)

        repeat(8) {
            val (load, start) = shufflePress(songs)
            load.fake.shuffle = false

            load.mapping.seekToNext()

            if (start == songs.lastIndex) {
                assertThat(load.fake.playing).isEqualTo(songs[start].id)
            } else {
                assertThat(load.fake.playing).isEqualTo(songs[start + 1].id)
            }
        }
    }

    @Test
    fun `after a few songs of the draw, random off continues from the song that plays now, not from the start`() {
        val songs = library(4_870)
        val (load, _) = shufflePress(songs)

        load.mapping.seekToNext()
        load.mapping.seekToNext()
        load.mapping.seekToNext()
        val now = load.fake.ids.indexOf(load.fake.playing)
        load.fake.shuffle = false

        load.mapping.seekToNext()
        assertThat(load.fake.playing).isEqualTo(songs[(now + 1).coerceAtMost(songs.lastIndex)].id)
        load.mapping.seekToPreviousMediaItem()
        assertThat(load.fake.playing).isEqualTo(songs[now].id)
    }

    @Test
    fun `each press starts at a different random song`() {
        val songs = library(4_870)

        val starts = (0 until 12).map { shufflePress(songs).second }

        assertThat(starts.toSet().size).isGreaterThan(1)
    }

    @Test
    fun `with a folder filter the base is the filtered list in its order and random off follows that list`() {
        val everything = library(4_870)
        // The Library filtered to one folder's worth of songs, listed by title: every 7th song, in the list's own order.
        val filtered = everything.filterIndexed { index, _ -> index % 7 == 3 }
        assertThat(filtered).hasSize(696)

        repeat(6) {
            val (load, start) = shufflePress(filtered)

            assertThat(load.fake.ids).isEqualTo(filtered.map { it.id })
            assertThat(load.fake.shuffle).isTrue()
            load.fake.shuffle = false
            load.mapping.seekToNext()
            val expected = if (start == filtered.lastIndex) filtered[start] else filtered[start + 1]
            // The filtered list's next song, never the next song of the whole library.
            assertThat(load.fake.playing).isEqualTo(expected.id)
            if (start != filtered.lastIndex) {
                assertThat(load.fake.playing).isNotEqualTo(everything[everything.indexOfFirst { it.id == filtered[start].id } + 1].id)
            }
        }
    }

    @Test
    fun `favourites are the liked songs in the Liked list's order, and random off follows it`() {
        val everything = library(4_870)
        // Date liked, newest first: not the title order and not the id order.
        val liked = listOf(31, 4_001, 12, 2_999, 7, 4_869, 100, 2_000, 55, 3_333).map { everything[it] }

        repeat(10) {
            val (load, start) = shufflePress(liked)

            assertThat(load.fake.ids).isEqualTo(liked.map { it.id })
            load.fake.shuffle = false
            load.mapping.seekToNext()
            val expected = if (start == liked.lastIndex) liked[start] else liked[start + 1]
            assertThat(load.fake.playing).isEqualTo(expected.id)
        }
    }

    @Test
    fun `a short list is loaded whole too`() {
        val songs = library(12)

        val (load, start) = shufflePress(songs)

        assertThat(load.fake.ids).isEqualTo(songs.map { it.id })
        assertThat(load.fake.playing).isEqualTo(songs[start].id)
    }
}
