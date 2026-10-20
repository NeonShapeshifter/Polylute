package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import androidx.media3.common.MediaItem
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.MappingPlayer
import com.lostf1sh.pixelplayeross.data.service.player.QueueLoadSkipHold
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

/**
 * B18-R2: a next or previous made while a queue is still being loaded is never lost.
 *
 * The owner's log (B17, 2026-10-13): tap a song from another list at 07:45:59.458 -> `Shuffle off: flag only, 1 items`; the rest of
 * the list was attached 2.1 s later (`Queue attached: 4092 before + 777 after`, 1017 ms on the main thread). For those two seconds
 * the player held one song, so a skip had nowhere to go.
 *
 * Everything here walks the real functions the view model uses (the plan, the first stage, the second stage, the hold, the
 * session's player) against [FakeQueuePlayer], a queue that behaves like ExoPlayer's on the points that matter.
 */
class QueueLoadStagesTest {

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()
    private fun songId(index: Int) = QueueLoadHarness.songId(index)

    // ---- what goes in first ------------------------------------------------------------------------

    @Test
    fun `the first load is the start song with five songs behind it and twenty ahead`() {
        val plan = QueueLoadPlan.of(size = 5_000, startIndex = 2_000)

        assertThat(plan.window).isEqualTo(1_995..2_020)
        assertThat(plan.windowSize).isEqualTo(26)
        assertThat(plan.startLocalIndex).isEqualTo(5)
        assertThat(plan.restBefore).isEqualTo(0 until 1_995)
        assertThat(plan.restAfter).isEqualTo(2_021 until 5_000)
        assertThat(plan.restBefore.count() + plan.windowSize + plan.restAfter.count()).isEqualTo(5_000)
    }

    @Test
    fun `at the edges of the list the block is cut, not shifted`() {
        val first = QueueLoadPlan.of(5_000, 0)
        assertThat(first.window).isEqualTo(0..20)
        assertThat(first.startLocalIndex).isEqualTo(0)
        assertThat(first.restBefore.count()).isEqualTo(0)

        val last = QueueLoadPlan.of(5_000, 4_999)
        assertThat(last.window).isEqualTo(4_994..4_999)
        assertThat(last.restAfter.count()).isEqualTo(0)
    }

    @Test
    fun `a short list is loaded whole and has no second stage`() {
        val plan = QueueLoadPlan.of(size = 12, startIndex = 3)
        assertThat(plan.window).isEqualTo(0..11)
        assertThat(plan.hasRest).isFalse()

        val single = QueueLoadPlan.of(size = 1, startIndex = 0)
        assertThat(single.windowSize).isEqualTo(1)
        assertThat(single.hasRest).isFalse()
    }

    @Test
    fun `a start outside the list is refused`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { QueueLoadPlan.of(10, 10) }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { QueueLoadPlan.of(0, 0) }
    }

    // ---- next and previous right after the tap -------------------------------------------------------

    @Test
    fun `a list of 5000 loads as one small timeline and a next in the first moments goes to the right song`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000)
        load.putFirstSongs()

        // Nothing but the block is in the player, in one publication; the rest is still being built.
        assertThat(load.fake.ids).hasSize(26)
        assertThat(load.fake.publications).isEqualTo(1)
        assertThat(load.fake.playing).isEqualTo("s2000")

        // The listener (or the headset) skips straight away, through the session's player as every controller does.
        load.mapping.seekToNext()

        assertThat(load.fake.playing).isEqualTo("s2001")
        assertThat(load.hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `the rest is attached around the block without moving the song that is playing`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000)
        load.putFirstSongs()
        load.mapping.seekToNext()

        assertThat(load.loadRest()).isTrue()

        assertThat(load.fake.ids).hasSize(5_000)
        assertThat(load.fake.ids).isEqualTo((0 until 5_000).map(::songId))
        assertThat(load.fake.playing).isEqualTo("s2001")
        assertThat(load.fake.current).isEqualTo(2_001)
        // 1 for the first songs, then exactly two for everything else (B13-4): not twenty-six.
        assertThat(load.fake.publications).isEqualTo(3)
        assertThat(load.fake.addCalls).containsExactly(1_995, 2_979).inOrder()
        assertThat(load.hold.isLoading).isFalse()
    }

    @Test
    fun `several fast nexts end on the right song, without skipping or repeating`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000)
        load.putFirstSongs()
        val visited = mutableListOf(load.fake.playing)

        repeat(7) {
            load.mapping.seekToNext()
            visited += load.fake.playing
        }
        load.loadRest()
        repeat(3) {
            load.mapping.seekToNext()
            visited += load.fake.playing
        }

        assertThat(visited).isEqualTo((2_000..2_010).map(::songId))
        assertThat(load.fake.playing).isEqualTo("s2010")
    }

    @Test
    fun `previous in the window goes to the previous song and the queue keeps it`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000)
        load.putFirstSongs()

        load.mapping.seekToPrevious()
        load.mapping.seekToPrevious()
        assertThat(load.fake.playing).isEqualTo("s1998")

        load.loadRest()

        assertThat(load.fake.playing).isEqualTo("s1998")
        assertThat(load.fake.current).isEqualTo(1_998)
        assertThat(load.fake.upcoming().first()).isEqualTo("s1999")
    }

    // ---- skips that have nowhere to go yet -------------------------------------------------------------

    @Test
    fun `skips made before the new queue is in the player wait for it, and the old queue does not move`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000)
        // The queue that is about to be replaced: ten songs, playing the fifth.
        load.fake.player.setMediaItems(List(10) { item("old$it") }, 4, 0L)
        val before = load.fake.current

        load.mapping.seekToNext()
        load.mapping.seekToNext()
        load.mapping.seekToNext()
        load.mapping.seekToPreviousMediaItem()
        assertThat(load.fake.current).isEqualTo(before)
        // Held as a sequence, in the order asked (B19-S3): next, next, next, previous.
        assertThat(load.hold.held).containsExactly(+1, +1, +1, -1).inOrder()

        load.putFirstSongs()

        // Played back in that order, once, before the start song made a sound: s2001, s2002, s2003, s2002.
        assertThat(load.fake.playing).isEqualTo("s2002")
        assertThat(load.fake.path).containsExactly(5 + 1, 5 + 2, 5 + 3, 5 + 2).inOrder()
        assertThat(load.hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `more nexts than the block holds are not lost - they happen when the rest arrives`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000)
        load.putFirstSongs()

        repeat(25) { load.mapping.seekToNext() }
        // Twenty songs were ahead of the start song; the other five wait.
        assertThat(load.fake.playing).isEqualTo("s2020")
        assertThat(load.hold.heldCount).isEqualTo(5)

        load.loadRest()

        assertThat(load.fake.playing).isEqualTo("s2025")
        assertThat(load.hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `a skip that could move still waits behind the ones that could not, so they happen in the order asked`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000)
        load.putFirstSongs()
        repeat(21) { load.mapping.seekToNext() } // the 21st has nowhere to go
        assertThat(load.hold.held).containsExactly(+1)

        // A previous now could move (there is a song behind), but a next is still waiting: it does not overtake it.
        load.mapping.seekToPreviousMediaItem()

        assertThat(load.hold.held).containsExactly(+1, -1).inOrder()
        assertThat(load.fake.playing).isEqualTo("s2020")

        // When the rest is in they happen in the order asked: next (s2021), then previous (s2020).
        load.loadRest()

        assertThat(load.fake.path.takeLast(2)).containsExactly(2_021, 2_020).inOrder()
        assertThat(load.fake.playing).isEqualTo("s2020")
        assertThat(load.hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `previous with nothing before the first song of the list restarts it at once, it is the real start`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2)
        load.putFirstSongs()

        repeat(3) { load.mapping.seekToPrevious() }
        // Two songs were behind the start song; the third previous is the list's real start (nothing more is coming before it): the
        // restart every player does, now, not after the rest of the queue has been built (B19-S3).
        assertThat(load.fake.playing).isEqualTo("s0")
        assertThat(load.fake.restarts).isEqualTo(1)
        assertThat(load.hold.heldCount).isEqualTo(0)

        load.loadRest()

        assertThat(load.fake.playing).isEqualTo("s0")
        assertThat(load.fake.restarts).isEqualTo(1)
    }

    @Test
    fun `previous at the head of the block waits while songs are still to come before it`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000, behind = 1)
        load.putFirstSongs()

        repeat(3) { load.mapping.seekToPrevious() }
        // One song was behind the start song; the other two previous have nowhere to go *yet*.
        assertThat(load.fake.playing).isEqualTo("s1999")
        assertThat(load.hold.held).containsExactly(-1, -1).inOrder()

        load.loadRest()

        assertThat(load.fake.playing).isEqualTo("s1997")
        assertThat(load.fake.restarts).isEqualTo(0)
    }

    @Test
    fun `choosing a song by index drops the skips that were waiting`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000)
        load.putFirstSongs()
        repeat(23) { load.mapping.seekToNext() }
        assertThat(load.hold.heldCount).isEqualTo(3)

        load.mapping.seekTo(0, 0L)

        assertThat(load.hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `without a load in flight a skip is nobody's business but the player's`() {
        val hold = QueueLoadSkipHold { 0L }
        assertThat(hold.hold(+1, hasNeighbour = false)).isFalse()
        assertThat(hold.heldCount).isEqualTo(0)

        val fake = FakeQueuePlayer()
        fake.player.setMediaItems(List(3) { item("x$it") }, 0, 0L)
        val mapping = MappingPlayer(fake.player, mockk<Context>(relaxed = true), queueLoadHold = hold)
        repeat(5) { mapping.seekToNext() }
        assertThat(fake.playing).isEqualTo("x2")
    }

    @Test
    fun `a load that never reports back stops holding skips after a while`() {
        var now = 0L
        val hold = QueueLoadSkipHold { now }
        hold.begin()
        assertThat(hold.hold(+1, hasNeighbour = false)).isTrue()

        now += QueueLoadSkipHold.MAX_LOAD_MS + 1

        assertThat(hold.hold(+1, hasNeighbour = false)).isFalse()
        assertThat(hold.isLoading).isFalse()
    }

    // ---- shuffle on --------------------------------------------------------------------------------

    @Test
    fun `with shuffle on a skip in the block follows the player's order and nothing it played comes round again`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000, shuffle = true)
        load.putFirstSongs()
        assertThat(load.fake.ids).hasSize(26)

        val upcoming = load.fake.upcoming()
        load.mapping.seekToNext()
        load.mapping.seekToNext()
        val played = listOf("s2000", upcoming[0], upcoming[1])
        assertThat(load.fake.playing).isEqualTo(upcoming[1])

        var passedToDealer: List<Int> = emptyList()
        load.loadRest(onAttachedExtra = { passedToDealer = it })

        // The songs went through lead the order, in the order they played (list indices, B19-S1); the rest follows the dealt sequence.
        assertThat(passedToDealer.map(::songId)).isEqualTo(played)
        val order = load.fake.playOrderIds()
        assertThat(order).hasSize(5_000)
        assertThat(order.toSet()).hasSize(5_000)
        assertThat(order.take(3)).isEqualTo(played)
        assertThat(order.drop(3)).isEqualTo(load.sequence.filter { it !in played.toSet() })
        assertThat(load.fake.playing).isEqualTo(upcoming[1])
        assertThat(load.fake.upcoming().first()).isEqualTo(load.sequence.first { it !in played.toSet() })
    }

    @Test
    fun `with shuffle on and nobody skipping, the dealt sequence is applied exactly as dealt`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000, shuffle = true)
        load.putFirstSongs()

        load.loadRest()

        assertThat(load.fake.playOrderIds()).isEqualTo(load.sequence)
        assertThat(load.fake.playing).isEqualTo("s2000")
    }

    @Test
    fun `with shuffle on, nexts that outrun the block still end on the right song`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000, shuffle = true)
        load.putFirstSongs()

        // 30 nexts: the block holds 25 songs after the start one in the player's order (the sequence's, B19-S1); the other five wait.
        repeat(30) { load.mapping.seekToNext() }
        val heldWhileBuilding = load.hold.heldCount
        val visited = load.fake.playOrderIds().take(load.fake.playOrderIds().indexOf(load.fake.playing) + 1)
        assertThat(heldWhileBuilding).isEqualTo(5)
        assertThat(visited).hasSize(26)

        load.loadRest()

        val order = load.fake.playOrderIds()
        assertThat(order.toSet()).hasSize(5_000)
        // 25 songs were played in the block, five skips were held: the song that is playing is the fifth after those.
        assertThat(order.take(26)).isEqualTo(visited)
        assertThat(load.fake.playing).isEqualTo(order[30])
    }

    // ---- the second stage's own rules --------------------------------------------------------------

    @Test
    fun `nothing is attached to a queue that is not the block that was loaded`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000)
        load.putFirstSongs()
        // Someone else replaced the queue while the rest was being built.
        load.fake.player.setMediaItems(List(8) { item("other$it") }, 0, 0L)

        val attached = load.loadRest()

        assertThat(attached).isFalse()
        assertThat(load.fake.ids).hasSize(8)
        assertThat(load.fake.addCalls).isEmpty()
        assertThat(load.hold.isLoading).isFalse()
    }

    @Test
    fun `the items are built before anything touches the player, and in two pieces`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000)
        load.putFirstSongs()

        load.loadRest()

        assertThat(load.buildCalls).containsExactly(0 until 1_995, 2_021 until 5_000).inOrder()
    }

    @Test
    fun `a load that was replaced neither attaches nor ends the one that replaced it`() = runBlocking<Unit> {
        val first = QueueLoadHarness(size = 5_000, start = 2_000)
        first.putFirstSongs()
        val gate = CompletableDeferred<Unit>()
        var attachedByFirst = true
        val job = launch(Dispatchers.Unconfined) {
            attachedByFirst = loadRestOfQueue(
                player = first.fake.player,
                plan = first.plan,
                windowFirstId = first.firstItems.first().mediaId,
                windowLastId = first.firstItems.last().mediaId,
                buildItems = { range ->
                    gate.await()
                    range.map { item(songId(it)) }
                },
                hold = first.hold,
                token = first.token,
                mainContext = Dispatchers.Unconfined,
                visits = first.visits,
                onAttached = { _, _ -> },
            )
        }
        yield()

        // The listener taps another list: a new load begins on the same hold.
        val secondToken = first.hold.begin()
        gate.complete(Unit)
        job.join()

        assertThat(attachedByFirst).isFalse()
        assertThat(first.fake.addCalls).isEmpty()
        assertThat(first.hold.isCurrent(secondToken)).isTrue()
        assertThat(first.hold.isLoading).isTrue()
    }

    @Test
    fun `a skip held for the first load is not applied to the second`() {
        val hold = QueueLoadSkipHold { 0L }
        hold.begin()
        hold.hold(+1, hasNeighbour = false)
        hold.hold(+1, hasNeighbour = false)

        hold.begin()

        assertThat(hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `the session's player holds next, next media item and previous the same way`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 5_000, start = 2_000)
        load.fake.player.setMediaItems(List(4) { item("old$it") }, 1, 0L)

        load.mapping.seekToNext()
        load.mapping.seekToNextMediaItem()
        load.mapping.seekToPrevious()
        load.mapping.seekToPreviousMediaItem()

        assertThat(load.fake.current).isEqualTo(1)
        // +1 +1 -1 -1: held in the order asked, none of them lost to a sum.
        assertThat(load.hold.held).containsExactly(+1, +1, -1, -1).inOrder()
    }
}
