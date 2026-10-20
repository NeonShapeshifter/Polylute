package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.QueueLoadSkipHold
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * B19-S3: held skips are a sequence, not a sum.
 *
 * B18 kept `held += direction`, so a previous and a next that were asked in that order before the new queue was in the player
 * cancelled out and the listener stayed on the first song, where in order a previous at the first song is a restart and the next
 * after it goes to the second. The same happened at the tail (next at the last song is a no-op, then previous goes to the one before
 * it). The class promised "in the order it was asked". What waits is now the list of actions, and they are played back in order against
 * the real queue with Media3's own rule at each step; an action that cannot be applied *yet* (songs are still to be attached that way)
 * stops the replay there so the order is kept, and one that can never move is consumed, never kept for ever.
 */
class QueueLoadSkipSequenceTest {

    private fun item(id: String) = androidx.media3.common.MediaItem.Builder().setMediaId(id).build()

    /** The queue that is about to be replaced: both media commands are available on it, as on the real session. */
    private fun QueueLoadHarness.oldQueue() {
        fake.player.setMediaItems(List(10) { item("old$it") }, 4, 0L)
    }

    // ---- the reviewer's probe ----------------------------------------------------------------------------

    @Test
    fun `previous then next before loading the first song must end at the second`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 0, ahead = 20)
        load.oldQueue()

        load.mapping.seekToPreviousMediaItem()
        load.mapping.seekToNextMediaItem()
        assertThat(load.hold.held).containsExactly(-1, +1).inOrder()
        load.putFirstSongs()

        // In order: a previous at the first song is a restart, then a next goes to the second.
        assertThat(load.fake.playing).isEqualTo("s1")
        assertThat(load.fake.restarts).isEqualTo(1)
        assertThat(load.hold.heldCount).isEqualTo(0)
    }

    // ---- both ends of the list -----------------------------------------------------------------------------

    @Test
    fun `next then previous at the last song of the list ends at the second to last`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 49)
        load.oldQueue()

        load.mapping.seekToNextMediaItem()
        load.mapping.seekToPreviousMediaItem()
        load.putFirstSongs()

        // Next at the real end with repeat off does nothing; the previous after it goes back one. A sum would have stayed on the last.
        assertThat(load.fake.playing).isEqualTo("s48")
        assertThat(load.fake.restarts).isEqualTo(0)
        assertThat(load.hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `the same at the tail with repeat all wraps around the whole list, never inside the block`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 49)
        load.oldQueue()
        load.setRepeat(Player.REPEAT_MODE_ALL)

        load.mapping.seekToNextMediaItem()
        load.mapping.seekToPreviousMediaItem()
        load.putFirstSongs()

        // The block is s44..s49 and songs are still to come in front of it: the wrap of a next at s49 goes to s0, which is not in the
        // player yet, so both wait (they must not wrap to the block's own first song, s44).
        assertThat(load.fake.playing).isEqualTo("s49")
        assertThat(load.hold.held).containsExactly(+1, -1).inOrder()
        assertThat(load.fake.path).isEmpty()

        load.loadRest()

        // Whole queue in: the next wraps to s0, the previous wraps back to s49.
        assertThat(load.fake.path).containsExactly(0, 49).inOrder()
        assertThat(load.fake.playing).isEqualTo("s49")
        assertThat(load.hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `previous then next at the head with repeat all waits too, and then goes round`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 0)
        load.oldQueue()
        load.setRepeat(Player.REPEAT_MODE_ALL)

        load.mapping.seekToPreviousMediaItem()
        load.mapping.seekToNextMediaItem()
        load.putFirstSongs()

        // Songs are still to come after the block (s0..s20) and the previous at s0 wraps to the LAST song of the list: it waits.
        assertThat(load.fake.playing).isEqualTo("s0")
        assertThat(load.hold.held).containsExactly(-1, +1).inOrder()

        load.loadRest()

        assertThat(load.fake.path).containsExactly(49, 0).inOrder()
        assertThat(load.fake.playing).isEqualTo("s0")
    }

    // ---- skips that outrun the block -------------------------------------------------------------------------

    @Test
    fun `three nexts with one neighbour in the block are one now and two after the attach, without a skip or a repeat`() = runBlocking<Unit> {
        // The block is s15..s21: the start song s20 has one neighbour ahead.
        val load = QueueLoadHarness(size = 50, start = 20, ahead = 1)
        load.putFirstSongs()

        repeat(3) { load.mapping.seekToNext() }

        assertThat(load.fake.path).containsExactly(6)
        assertThat(load.fake.playing).isEqualTo("s21")
        assertThat(load.hold.held).containsExactly(+1, +1).inOrder()

        load.loadRest()

        assertThat(load.fake.playing).isEqualTo("s23")
        // 1 now (s21), 2 after the attach (s22, s23): each song once, in order.
        assertThat(load.fake.path).containsExactly(6, 22, 23).inOrder()
        assertThat(load.hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `a previous waiting at the head of the block, then a next, both wait and run in order after the attach`() = runBlocking<Unit> {
        // The block is s20..s40: the start song is its head and songs are still to come in front of it.
        val load = QueueLoadHarness(size = 50, start = 20, behind = 0)
        load.putFirstSongs()

        load.mapping.seekToPrevious()
        // The next could move (s21 is there), but it must not overtake the previous that waits.
        load.mapping.seekToNext()

        assertThat(load.hold.held).containsExactly(-1, +1).inOrder()
        assertThat(load.fake.playing).isEqualTo("s20")
        assertThat(load.fake.path).isEmpty()

        load.loadRest()

        assertThat(load.fake.path).containsExactly(19, 20).inOrder()
        assertThat(load.fake.playing).isEqualTo("s20")
        assertThat(load.fake.restarts).isEqualTo(0)
    }

    @Test
    fun `an action that can never move is consumed at once, not kept for the attach`() = runBlocking<Unit> {
        // The last song of the list, repeat off: a next is a no-op everywhere, so it is not worth waiting for anything.
        val load = QueueLoadHarness(size = 50, start = 49)
        load.putFirstSongs()

        load.mapping.seekToNext()

        assertThat(load.hold.heldCount).isEqualTo(0)
        assertThat(load.fake.playing).isEqualTo("s49")
        assertThat(load.fake.path).isEmpty()
    }

    @Test
    fun `with shuffle on a skip that finds nothing waits for the attach and then follows the final order`() = runBlocking<Unit> {
        val load = QueueLoadHarness(size = 50, start = 20, shuffle = true)
        load.putFirstSongs()

        // 26 songs in the block, the start song first in its order: 25 nexts use it up, the next two wait.
        repeat(27) { load.mapping.seekToNext() }
        assertThat(load.hold.held).containsExactly(+1, +1).inOrder()

        load.loadRest()

        // The 26 that were played lead the order; the two held nexts go on from there.
        val final = load.finalIds()
        assertThat(load.fake.playing).isEqualTo(final[27])
        assertThat(final.take(26).toSet()).hasSize(26)
        assertThat(load.hold.heldCount).isEqualTo(0)
    }

    // ---- the hold's own rules ------------------------------------------------------------------------------

    @Test
    fun `only the latest 32 actions are kept`() {
        val hold = QueueLoadSkipHold { 0L }
        hold.begin()

        repeat(10) { hold.hold(-1, hasNeighbour = false) }
        repeat(32) { hold.hold(+1, hasNeighbour = false) }

        assertThat(hold.heldCount).isEqualTo(QueueLoadSkipHold.MAX_WAITING)
        assertThat(hold.held.toSet()).containsExactly(+1)
    }

    @Test
    fun `the fifteen second expiry and the tap on a row still clear what waits`() {
        var now = 0L
        val hold = QueueLoadSkipHold { now }
        hold.begin()
        hold.hold(+1, hasNeighbour = false)
        hold.dropHeld()
        assertThat(hold.heldCount).isEqualTo(0)

        hold.hold(+1, hasNeighbour = false)
        now += QueueLoadSkipHold.MAX_LOAD_MS + 1
        assertThat(hold.hold(+1, hasNeighbour = false)).isFalse()
        assertThat(hold.isLoading).isFalse()
        assertThat(hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `settle plays the sequence back in order and stops at the first action that must still wait`() {
        val hold = QueueLoadSkipHold { 0L }
        val token = hold.begin()
        hold.hold(+1, hasNeighbour = false) // held: the queue is not in the player
        hold.hold(+1, hasNeighbour = false)
        hold.hold(-1, hasNeighbour = false)
        // The block has one neighbour ahead and songs are still to come after it; nothing before.
        hold.queueInPlayer(token, songsBefore = false, songsAfter = true)
        val log = ArrayList<String>()
        var neighboursAhead = 1
        val steps = object : QueueLoadSkipHold.Steps {
            override fun hasNeighbour(direction: Int) = if (direction > 0) neighboursAhead > 0 else true
            override fun canMove(direction: Int) = hasNeighbour(direction)
            override val repeatsAll = false
            override fun move(direction: Int) {
                log += if (direction > 0) "next" else "previous"
                if (direction > 0) neighboursAhead-- else neighboursAhead++
            }
            override fun restart() {
                log += "restart"
            }
        }

        val consumed = hold.settle(steps)

        // The first next moves; the second finds nothing ahead and songs are coming, so it waits and the previous behind it waits too.
        assertThat(consumed).isEqualTo(1)
        assertThat(log).containsExactly("next")
        assertThat(hold.held).containsExactly(+1, -1).inOrder()

        // The rest is attached: nothing more is coming, there are two songs ahead.
        hold.queueInPlayer(token, songsBefore = false, songsAfter = false)
        neighboursAhead = 2
        assertThat(hold.settle(steps)).isEqualTo(2)
        assertThat(log).containsExactly("next", "next", "previous").inOrder()
        assertThat(hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `a previous with nothing before it is a restart and a next with nothing after it does nothing, as in Media3`() {
        val hold = QueueLoadSkipHold { 0L }
        val token = hold.begin()
        hold.hold(-1, hasNeighbour = false)
        hold.hold(+1, hasNeighbour = false)
        hold.queueInPlayer(token, songsBefore = false, songsAfter = false)
        val log = ArrayList<String>()
        val steps = object : QueueLoadSkipHold.Steps {
            override fun hasNeighbour(direction: Int) = false
            override fun canMove(direction: Int) = false
            override val repeatsAll = false
            override fun move(direction: Int) {
                log += "move"
            }
            override fun restart() {
                log += "restart"
            }
        }

        assertThat(hold.settle(steps)).isEqualTo(2)

        assertThat(log).containsExactly("restart")
        assertThat(hold.heldCount).isEqualTo(0)
    }

    @Test
    fun `the app's previous knows whether there may still be somewhere to go`() {
        val hold = QueueLoadSkipHold { 0L }
        assertThat(hold.previousMayStillHaveSomewhereToGo).isFalse()

        val token = hold.begin()
        assertThat(hold.previousMayStillHaveSomewhereToGo).isTrue() // the new queue is not in the player yet

        hold.queueInPlayer(token, songsBefore = true, songsAfter = false)
        assertThat(hold.previousMayStillHaveSomewhereToGo).isTrue()

        hold.queueInPlayer(token, songsBefore = false, songsAfter = true)
        assertThat(hold.previousMayStillHaveSomewhereToGo).isFalse() // the real start of the list: a restart, at once

        hold.end(token)
        assertThat(hold.previousMayStillHaveSomewhereToGo).isFalse()
    }
}
