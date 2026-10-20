package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.media3.exoplayer.source.ShuffleOrder
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * B19-S1: a shuffled load counted songs nobody heard as "played", and they dropped out of the round.
 *
 * B18's `songsPlayedInBlock` took the whole prefix of the block's shuffle order up to the song that was playing. The block goes in with
 * `setMediaItems(..., startLocalIndex, 0)` and the shuffle flag on, so Media3 gives it a random order of its own in which the start
 * song can be anywhere, and nothing in production put it first. The reviewer's reproduction (50 songs, start `s20`, Media3's real
 * `DefaultShuffleOrder(41, 42L)`, no skips): 17 songs nobody heard ended before the current one, and the final order was not the
 * sequence that was asked for. On the owner's 4,870 songs the block is 26 songs, so ~12 songs dropped out of every persistent-random load.
 *
 * Both halves of the fix are pinned here, with the real stage functions against [FakeQueuePlayer]:
 * the block's order is **anchored** to the sequence in the first stage (one `setShuffleOrder`), and "played" is what the player
 * really went through, by occurrence.
 */
class QueueLoadAnchoredOrderTest {

    private fun songId(index: Int) = QueueLoadHarness.songId(index)

    // ---- the reviewer's probe, adapted to the fixed API ----------------------------------------------------

    @Test
    fun `nobody skipped so the full shuffle must remain exactly the requested sequence`() = runBlocking<Unit> {
        // The probe's list: 50 songs, start s20, a sequence that leads with the start song.
        val load = QueueLoadHarness(size = 50, start = 20, shuffle = true)
        load.putFirstSongs()

        load.loadRest()

        assertThat(load.played).containsExactly(emptyList<Int>())
        assertThat(load.finalIds()).isEqualTo(load.sequence)
        assertThat(load.fake.playing).isEqualTo("s20")
    }

    @Test
    fun `the same with Media3's own random order over the block, when the engine cannot anchor it`() = runBlocking<Unit> {
        // The probe's literal setup: no anchoring, Media3's real order for the block (start song anywhere in it).
        val load = QueueLoadHarness(size = 50, start = 20, shuffle = true, anchor = false)
        load.putFirstSongs()
        val generated = ShuffleOrder.DefaultShuffleOrder(load.plan.windowSize, 42L)
        load.fake.order.clear()
        var i = generated.firstIndex
        while (i >= 0) {
            load.fake.order.add(i)
            i = generated.getNextIndex(i)
        }

        load.loadRest()

        // B18 claimed the block's order up to the song that was playing as played: 17 songs nobody had heard led the final order.
        assertThat(load.played).containsExactly(emptyList<Int>())
        assertThat(load.finalIds()).isEqualTo(load.sequence)
    }

    // ---- part 1: the first stage anchors the block's order -------------------------------------------------

    @Test
    fun `the block is put in the sequence's order with one setShuffleOrder, before anything plays`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000, shuffle = true)

        load.putFirstSongs()

        // One load, one order: 2 publications of 26 songs (the second costs ~4 ms on the device, nothing like the whole queue).
        assertThat(load.fake.ids).hasSize(26)
        assertThat(load.fake.orderCalls).isEqualTo(1)
        assertThat(load.fake.publications).isEqualTo(2)
        // The start song goes first and the rest follow the order those songs have in the sequence.
        val blockSequence = load.sequence.filter { id -> load.fake.ids.contains(id) }
        assertThat(load.fake.playOrderIds()).isEqualTo(blockSequence)
        assertThat(load.fake.playOrderIds().first()).isEqualTo("s2000")
        assertThat(load.fake.playing).isEqualTo("s2000")
    }

    @Test
    fun `a sequence that keeps the start song where the list had it gives the block that order and ends exactly as dealt`() = runBlocking<Unit> {
        // A persistent random anchors the start song in place (QueueUtils.generateShuffleOrderSuspending): it is not first.
        val load = QueueLoadHarness(size = 50, start = 20, shuffle = true, startFirst = false)
        assertThat(load.sequence.first()).isNotEqualTo("s20")
        load.putFirstSongs()

        // The block's order is the sequence restricted to the block, whoever leads it.
        val blockSequence = load.sequence.filter { id -> load.fake.ids.contains(id) }
        assertThat(load.fake.playOrderIds()).isEqualTo(blockSequence)

        load.loadRest()

        assertThat(load.finalIds()).isEqualTo(load.sequence)
        assertThat(load.fake.playing).isEqualTo("s20")
    }

    @Test
    fun `a next in the first moments goes to the song the sequence gives, not to a random one`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 20, shuffle = true)
        load.putFirstSongs()
        val expected = load.sequence.filter { id -> load.fake.ids.contains(id) }[1]

        load.mapping.seekToNext()

        assertThat(load.fake.playing).isEqualTo(expected)
    }

    @Test
    fun `without a sequence nothing is anchored`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 20)
        load.putFirstSongs()

        assertThat(load.plan.blockOrder).isNull()
        assertThat(load.fake.orderCalls).isEqualTo(0)
        assertThat(load.fake.publications).isEqualTo(1)
    }

    @Test
    fun `a whole list in one block is anchored too and has no second stage`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 12, start = 3, shuffle = true)
        load.putFirstSongs()

        assertThat(load.plan.hasRest).isFalse()
        assertThat(load.finalIds()).isEqualTo(load.sequence)
        assertThat(load.fake.listenerCount).isEqualTo(0)
    }

    // ---- part 2: "played" is what was really played -------------------------------------------------------

    @Test
    fun `two nexts in the window lead the order with exactly those two songs and the current one, and every other song is still ahead`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 20, shuffle = true)
        load.putFirstSongs()
        val block = load.sequence.filter { id -> load.fake.ids.contains(id) }

        load.mapping.seekToNext()
        load.mapping.seekToNext()
        val played = listOf("s20", block[1], block[2])
        assertThat(load.fake.playing).isEqualTo(block[2])

        load.loadRest()

        val final = load.finalIds()
        assertThat(final).hasSize(50)
        assertThat(final.toSet()).hasSize(50)
        assertThat(final.take(3)).isEqualTo(played)
        // Everything else is ahead of the current song, in the order the sequence gave.
        assertThat(final.drop(3)).isEqualTo(load.sequence.filter { it !in played })
        assertThat(load.fake.upcoming()).isEqualTo(load.sequence.filter { it !in played })
        assertThat(load.fake.playing).isEqualTo(block[2])
    }

    @Test
    fun `a tap on a row of the block during the load counts only that song, and the start song that was playing`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 20, shuffle = true)
        load.putFirstSongs()
        // The queue screen lists the block while the rest loads; the listener taps the song at its row 12.
        val tapped = load.fake.playOrderIds()[12]
        val timelineIndex = load.fake.ids.indexOf(tapped)

        load.mapping.seekTo(timelineIndex, 0L)
        assertThat(load.fake.playing).isEqualTo(tapped)

        load.loadRest()

        val final = load.finalIds()
        assertThat(final.take(2)).isEqualTo(listOf("s20", tapped))
        // The eleven songs between them in the block were not played: they are ahead, where the sequence put them.
        assertThat(load.fake.upcoming()).isEqualTo(load.sequence.filter { it != "s20" && it != tapped })
        assertThat(load.fake.playing).isEqualTo(tapped)
        assertThat(load.played.single()).hasSize(2)
    }

    @Test
    fun `a song that ends on its own while the rest is loading counts as played`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 20, shuffle = true)
        load.putFirstSongs()
        val block = load.sequence.filter { id -> load.fake.ids.contains(id) }

        load.fake.songEnds()

        load.loadRest()

        assertThat(load.finalIds().take(2)).isEqualTo(listOf("s20", block[1]))
        assertThat(load.fake.playing).isEqualTo(block[1])
    }

    @Test
    fun `with a song twice in the list the occurrence that was played is the one that leads`() = runBlocking<Unit> {
        // Songs 18 and 30 are the same song; both are inside the block 15..40 that starts on s20.
        val idOf = { index: Int -> if (index == 18 || index == 30) "dup" else "s$index" }
        val second = QueueLoadHarness(size = 50, start = 20, shuffle = true, idOf = idOf)
        second.putFirstSongs()

        // The listener taps the SECOND occurrence (list index 30, block row 15 in the list's own order).
        second.mapping.seekTo(30 - second.plan.windowStart, 0L)
        second.loadRest()

        assertThat(second.fake.order.take(2)).containsExactly(20, 30).inOrder()
        assertThat(second.fake.current).isEqualTo(30)
        // The first occurrence was not heard: it is still ahead.
        assertThat(second.fake.order.indexOf(18)).isGreaterThan(1)
        assertThat(second.fake.upcoming()).contains("dup")

        // And the other way round: the first occurrence is the one that was heard.
        val first = QueueLoadHarness(size = 50, start = 20, shuffle = true, idOf = idOf)
        first.putFirstSongs()
        first.mapping.seekTo(18 - first.plan.windowStart, 0L)
        first.loadRest()

        assertThat(first.fake.order.take(2)).containsExactly(20, 18).inOrder()
        assertThat(first.fake.order.indexOf(30)).isGreaterThan(1)
    }

    @Test
    fun `a transition after the songs in front are in but before the rest is, is recorded at its list index`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 20, shuffle = true)
        load.putFirstSongs()

        // The main thread is free between the two attach calls: a next lands there, when timeline indices already are list indices
        // (the songs that went in front are in, and Media3 gave them places in the order, so which song is next is not the point).
        load.loadRest(betweenAttachCalls = { load.mapping.seekToNext() })

        val landed = load.fake.current
        assertThat(landed).isNotEqualTo(20)
        assertThat(load.played.single()).containsExactly(20, landed).inOrder()
        assertThat(load.finalIds().take(2)).isEqualTo(listOf("s20", songId(landed)))
        assertThat(load.fake.playing).isEqualTo(songId(landed))
    }

    @Test
    fun `the record stops when the load is over or replaced, and listens to nothing after`() = runBlocking<Unit> {
        val done = QueueLoadHarness(size = 50, start = 20, shuffle = true)
        done.putFirstSongs()
        assertThat(done.fake.listenerCount).isEqualTo(1)
        done.loadRest()
        assertThat(done.fake.listenerCount).isEqualTo(0)

        val replaced = QueueLoadHarness(size = 50, start = 20, shuffle = true)
        replaced.putFirstSongs()
        assertThat(replaced.fake.listenerCount).isEqualTo(1)
        // Another list is tapped: a newer load takes over the hold, and the first one's record leaves at its next event.
        replaced.hold.begin()
        replaced.fake.songEnds()
        assertThat(replaced.fake.listenerCount).isEqualTo(0)
    }

    // ---- shuffle off: unchanged ----------------------------------------------------------------------------

    @Test
    fun `with shuffle off the list keeps its own order and nothing is recorded or reordered`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 20)
        load.putFirstSongs()
        assertThat(load.fake.listenerCount).isEqualTo(0)

        repeat(3) { load.mapping.seekToNext() }
        load.loadRest()

        assertThat(load.played.single()).isEmpty()
        assertThat(load.fake.orderCalls).isEqualTo(0)
        assertThat(load.fake.ids).isEqualTo((0 until 50).map(::songId))
        assertThat(load.fake.playOrderIds()).isEqualTo((0 until 50).map(::songId))
        assertThat(load.fake.playing).isEqualTo("s23")
    }

    // ---- the pure parts ----------------------------------------------------------------------------------

    @Test
    fun `the block's order is the whole sequence restricted to it, with indices inside the block`() {
        // Six songs, a block of three from index 2: the sequence plays 4, 0, 3, 5, 2, 1.
        val order = intArrayOf(4, 0, 3, 5, 2, 1)

        assertThat(PlayOrder.restrictTo(order, start = 2, size = 3)).isEqualTo(intArrayOf(2, 1, 0))
        assertThat(PlayOrder.restrictTo(order, start = 0, size = 6)).isEqualTo(order)
        // An order that does not hold the whole block is not guessed at.
        assertThat(PlayOrder.restrictTo(intArrayOf(0, 1), start = 0, size = 3)).isEqualTo(intArrayOf(0, 1, 2))
    }

    @Test
    fun `the plan knows what is still to come with a sequence and with the list's own order`() {
        val own = QueueLoadPlan.of(size = 100, startIndex = 50)
        assertThat(own.blockOrder).isNull()
        assertThat(own.songsBefore).isTrue()
        assertThat(own.songsAfter).isTrue()
        assertThat(QueueLoadPlan.of(100, 0).songsBefore).isFalse()
        assertThat(QueueLoadPlan.of(100, 99).songsAfter).isFalse()

        val order = PlayOrder.randomPermutation(100, java.util.Random(3))
        val dealt = QueueLoadPlan.of(size = 100, startIndex = 50, order = order)
        assertThat(dealt.blockOrder).hasLength(26)
        assertThat(PlayOrder.isPermutation(dealt.blockOrder!!, 26)).isTrue()
        // Nothing played is the sequence as it was; what was played leads it.
        assertThat(dealt.orderAfterAttach(emptyList())).isEqualTo(order)
        assertThat(dealt.orderAfterAttach(listOf(50, 51))!!.take(2)).containsExactly(50, 51).inOrder()
        // An order that is not a permutation of the list is ignored, like no order.
        assertThat(QueueLoadPlan.of(100, 50, order = intArrayOf(1, 2, 3)).order).isNull()
        assertThat(own.withOrder(intArrayOf(0, 0)).order).isNull()
    }

    @Test
    fun `dealing a list with occurrences played puts them first and never repeats one`() {
        val base = listOf("a", "b", "a", "c")
        val sequence = listOf("c", "a", "b", "a")

        // The SECOND a (timeline index 2) was the one that was heard.
        val order = PlayOrder.dealt(base, sequence, played = listOf(1, 2))!!

        assertThat(order.toList()).containsExactly(1, 2, 3, 0).inOrder()
        assertThat(PlayOrder.isPermutation(order, 4)).isTrue()
        // Nothing played is the sequence as it was dealt, and a different list is refused.
        assertThat(PlayOrder.dealt(base, sequence)).isEqualTo(intArrayOf(3, 0, 1, 2))
        assertThat(PlayOrder.dealt(base, listOf("c", "a", "b"))).isNull()
    }
}
