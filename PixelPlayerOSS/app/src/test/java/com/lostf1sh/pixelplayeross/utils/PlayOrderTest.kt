package com.lostf1sh.pixelplayeross.utils

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B17-H1: the play order and the arithmetic around it. No player: a `Timeline` that behaves like ExoPlayer's playlist
 * timeline (it answers shuffle questions from a `ShuffleOrder`) is all the helper reads.
 */
class PlayOrderTest {

    /** A timeline that ignores shuffle entirely: what a wrapper that forgot to forward the shuffle methods looks like. */
    private class OblivousTimeline(private val count: Int) : Timeline() {
        override fun getWindowCount(): Int = count
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window = window
        override fun getPeriodCount(): Int = count
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period = period
        override fun getIndexOfPeriod(uid: Any): Int = C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }

    private fun timeline(vararg order: Int) = OrderedTestTimeline.of(*order)

    // --- reading the order from a timeline -------------------------------------------------------

    @Test
    fun `without shuffle the order is the base order`() {
        assertThat(PlayOrder.of(timeline(2, 0, 3, 1), shuffleEnabled = false)).isEqualTo(intArrayOf(0, 1, 2, 3))
    }

    @Test
    fun `with shuffle the order is the one the timeline carries`() {
        assertThat(PlayOrder.of(timeline(2, 0, 3, 1), shuffleEnabled = true)).isEqualTo(intArrayOf(2, 0, 3, 1))
    }

    @Test
    fun `an empty timeline has an empty order`() {
        assertThat(PlayOrder.of(OrderedTestTimeline(0, ShuffleOrder.DefaultShuffleOrder(0, 1L)), true)).isEmpty()
    }

    @Test
    fun `a timeline that ignores shuffle reads as the base order, not as garbage`() {
        // The session's wrapper timeline did exactly this before B17: it dropped the shuffle questions.
        assertThat(PlayOrder.of(OblivousTimeline(5), shuffleEnabled = true)).isEqualTo(intArrayOf(0, 1, 2, 3, 4))
    }

    @Test
    fun `a five thousand song order is read in one pass and is a permutation`() {
        val count = 5_000
        val random = java.util.Random(7)
        val order = (0 until count).toMutableList().also { it.shuffle(random) }.toIntArray()
        val read = PlayOrder.of(timeline(*order), shuffleEnabled = true)
        assertThat(read).isEqualTo(order)
        assertThat(PlayOrder.isPermutation(read, count)).isTrue()
    }

    // --- permutations ---------------------------------------------------------------------------------

    @Test
    fun `permutation checks reject repeats, gaps and wrong sizes`() {
        assertThat(PlayOrder.isPermutation(intArrayOf(1, 0, 2), 3)).isTrue()
        assertThat(PlayOrder.isPermutation(intArrayOf(1, 1, 2), 3)).isFalse()
        assertThat(PlayOrder.isPermutation(intArrayOf(0, 1, 3), 3)).isFalse()
        assertThat(PlayOrder.isPermutation(intArrayOf(0, 1), 3)).isFalse()
    }

    @Test
    fun `inverse maps a timeline index to its position`() {
        val order = intArrayOf(2, 0, 3, 1)
        val inverse = PlayOrder.inverse(order)
        assertThat(inverse).isEqualTo(intArrayOf(1, 3, 0, 2))
        for (position in order.indices) assertThat(inverse[order[position]]).isEqualTo(position)
    }

    // --- an order from a list the app already shuffled ------------------------------------------------

    @Test
    fun `a shuffled list becomes the order that plays it`() {
        val base = listOf("a", "b", "c", "d")
        val order = PlayOrder.fromSequence(base, listOf("c", "a", "d", "b"))!!
        assertThat(order.map { base[it] }).containsExactly("c", "a", "d", "b").inOrder()
    }

    @Test
    fun `a track queued twice keeps both occurrences`() {
        val base = listOf("a", "x", "b", "x")
        val order = PlayOrder.fromSequence(base, listOf("x", "b", "x", "a"))!!
        assertThat(order.toList()).containsExactly(1, 2, 3, 0).inOrder()
        assertThat(PlayOrder.isPermutation(order, 4)).isTrue()
    }

    @Test
    fun `lists that do not hold the same songs give no order`() {
        assertThat(PlayOrder.fromSequence(listOf("a", "b"), listOf("a", "c"))).isNull()
        assertThat(PlayOrder.fromSequence(listOf("a", "b"), listOf("a"))).isNull()
        assertThat(PlayOrder.fromSequence(listOf("a", "a"), listOf("a", "b"))).isNull()
    }

    // --- editing the order ---------------------------------------------------------------------------

    @Test
    fun `dragging a song in the queue moves it in the order and nothing else`() {
        assertThat(PlayOrder.move(intArrayOf(4, 2, 0, 3, 1), fromPosition = 3, toPosition = 1))
            .isEqualTo(intArrayOf(4, 3, 2, 0, 1))
        assertThat(PlayOrder.move(intArrayOf(4, 2, 0), fromPosition = 0, toPosition = 2)).isEqualTo(intArrayOf(2, 0, 4))
        assertThat(PlayOrder.move(intArrayOf(4, 2, 0), fromPosition = 1, toPosition = 1)).isEqualTo(intArrayOf(4, 2, 0))
        assertThat(PlayOrder.move(intArrayOf(4, 2, 0), fromPosition = 5, toPosition = 0)).isEqualTo(intArrayOf(4, 2, 0))
    }

    @Test
    fun `play next puts the song right behind the current one`() {
        // Timeline index 5 was just added (random place in the order); the current song is index 2.
        val order = intArrayOf(3, 0, 2, 4, 1, 5)
        assertThat(PlayOrder.placeAfter(order, timelineIndex = 5, afterTimelineIndex = 2))
            .isEqualTo(intArrayOf(3, 0, 2, 5, 4, 1))
        // Already there: unchanged.
        assertThat(PlayOrder.placeAfter(intArrayOf(3, 0, 2, 5, 4, 1), 5, 2)).isEqualTo(intArrayOf(3, 0, 2, 5, 4, 1))
    }

    @Test
    fun `add to queue puts the song after everything else`() {
        assertThat(PlayOrder.placeLast(intArrayOf(3, 5, 0, 2, 4, 1), timelineIndex = 5))
            .isEqualTo(intArrayOf(3, 0, 2, 4, 1, 5))
    }

    @Test
    fun `an undone removal goes back to the row it left`() {
        // Row 2 (timeline index 4) was removed and then re-added; Media3 dealt it the last place.
        assertThat(PlayOrder.placeAt(intArrayOf(3, 0, 2, 1, 4), timelineIndex = 4, position = 2))
            .isEqualTo(intArrayOf(3, 0, 4, 2, 1))
        assertThat(PlayOrder.placeAt(intArrayOf(3, 0, 2, 1, 4), timelineIndex = 4, position = 99))
            .isEqualTo(intArrayOf(3, 0, 2, 1, 4))
        assertThat(PlayOrder.placeAt(intArrayOf(3, 0, 2), timelineIndex = 9, position = 0)).isEqualTo(intArrayOf(3, 0, 2))
    }

    @Test
    fun `edits that name a song that is not in the order change nothing`() {
        val order = intArrayOf(1, 0, 2)
        assertThat(PlayOrder.placeAfter(order, 9, 0)).isEqualTo(order)
        assertThat(PlayOrder.placeAfter(order, 1, 9)).isEqualTo(order)
        assertThat(PlayOrder.placeLast(order, 9)).isEqualTo(order)
    }

    // --- an order over a list that lost songs ---------------------------------------------------------

    @Test
    fun `an order survives a filter as the same sequence over the survivors`() {
        // Saved list of 6; items 1 and 4 were dropped (files gone). Sequence 4 2 0 5 1 3 -> 2 0 5 3, renumbered.
        val remapped = PlayOrder.remap(intArrayOf(4, 2, 0, 5, 1, 3), oldCount = 6, keptIndices = listOf(0, 2, 3, 5))!!
        // survivors old 0,2,3,5 become new 0,1,2,3: the sequence 2 0 5 3 becomes 1 0 3 2.
        assertThat(remapped.toList()).containsExactly(1, 0, 3, 2).inOrder()
        assertThat(PlayOrder.isPermutation(remapped, 4)).isTrue()
    }

    @Test
    fun `an order that is not a permutation of the saved list is refused`() {
        assertThat(PlayOrder.remap(intArrayOf(0, 0, 1), oldCount = 3, keptIndices = listOf(0, 1, 2))).isNull()
        assertThat(PlayOrder.remap(intArrayOf(0, 1), oldCount = 3, keptIndices = listOf(0, 1, 2))).isNull()
    }

    @Test
    fun `filters can be chained, which is how a restore drops unplayable files twice`() {
        val first = PlayOrder.remap(intArrayOf(3, 1, 0, 4, 2), 5, keptIndices = listOf(0, 1, 3, 4))!!
        val second = PlayOrder.remap(first, 4, keptIndices = listOf(1, 2, 3))!!
        assertThat(PlayOrder.isPermutation(second, 3)).isTrue()
    }

    // --- what comes next ------------------------------------------------------------------------------

    @Test
    fun `the next songs are the ones after the current one in the order`() {
        val order = intArrayOf(3, 0, 2, 4, 1)
        assertThat(PlayOrder.upcoming(order, currentIndex = 2, count = 2)).containsExactly(4, 1).inOrder()
        assertThat(PlayOrder.upcoming(order, currentIndex = 2, count = 10)).containsExactly(4, 1).inOrder()
        assertThat(PlayOrder.upcoming(order, currentIndex = 1, count = 4)).isEmpty() // last of the order
        assertThat(PlayOrder.upcoming(order, currentIndex = 9, count = 4)).isEmpty() // not in the order
    }

    // --- B18-R1: a new random order ---------------------------------------------------------------

    @Test
    fun `a random permutation holds every index once, and the same seed gives the same one`() {
        val a = PlayOrder.randomPermutation(4_870, java.util.Random(11L))
        val b = PlayOrder.randomPermutation(4_870, java.util.Random(11L))
        val c = PlayOrder.randomPermutation(4_870, java.util.Random(12L))

        assertThat(PlayOrder.isPermutation(a, 4_870)).isTrue()
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(PlayOrder.randomPermutation(0, java.util.Random(1L))).isEmpty()
        assertThat(PlayOrder.randomPermutation(1, java.util.Random(1L))).isEqualTo(intArrayOf(0))
    }

    @Test
    fun `the playing song moves to the front and the others keep their relative order`() {
        assertThat(PlayOrder.currentFirst(intArrayOf(4, 2, 0, 3, 1), first = 3)).isEqualTo(intArrayOf(3, 4, 2, 0, 1))
    }

    @Test
    fun `a song that already leads, or is not in the order, leaves the order as it was`() {
        val order = intArrayOf(4, 2, 0, 3, 1)
        assertThat(PlayOrder.currentFirst(order, first = 4)).isEqualTo(order)
        assertThat(PlayOrder.currentFirst(order, first = 9)).isEqualTo(order)
    }

    // --- B18-R2: what was played while the rest of the queue was loading leads the order ----------

    @Test
    fun `songs that were played lead the order, in the order they played, and the rest keeps the dealt sequence`() {
        val order = intArrayOf(7, 3, 5, 0, 2, 6, 1, 4)

        val led = PlayOrder.leadWith(order, listOf(2, 5, 6))

        assertThat(led).isEqualTo(intArrayOf(2, 5, 6, 7, 3, 0, 1, 4))
        assertThat(PlayOrder.isPermutation(led, 8)).isTrue()
    }

    @Test
    fun `a lead with indices outside the order or repeated is ignored where it does not apply`() {
        val order = intArrayOf(1, 0, 2)

        assertThat(PlayOrder.leadWith(order, listOf(9, 2, 2, -1))).isEqualTo(intArrayOf(2, 1, 0))
        assertThat(PlayOrder.leadWith(order, emptyList())).isEqualTo(order)
    }

    @Test
    fun `dealing a list with nothing played is the dealt sequence`() {
        val base = listOf("a", "b", "c", "d")

        assertThat(PlayOrder.dealt(base, listOf("c", "a", "d", "b"))).isEqualTo(intArrayOf(2, 0, 3, 1))
        assertThat(PlayOrder.dealt(base, listOf("c", "a", "d"))).isNull() // not the same songs
    }

    @Test
    fun `dealing a list with songs played puts them first and never repeats one`() {
        val base = listOf("a", "b", "c", "d", "e")

        // Played, as timeline indices in the order they were heard: c (2), d (3), b (1).
        val order = PlayOrder.dealt(base, sequence = listOf("c", "a", "e", "b", "d"), played = listOf(2, 3, 1))!!

        assertThat(order.map { base[it] }).containsExactly("c", "d", "b", "a", "e").inOrder()
        assertThat(PlayOrder.isPermutation(order, 5)).isTrue()
    }
}
