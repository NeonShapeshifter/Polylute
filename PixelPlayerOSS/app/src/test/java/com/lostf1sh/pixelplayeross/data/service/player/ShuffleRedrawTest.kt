package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * B18-R1: classic mode deals the queue again every time shuffle is switched on, as ONE publication, with the song that is playing
 * first. A queue of 4,870 songs is the size that matters: B16's rebuild of it cost ~5 s of main thread.
 */
class ShuffleRedrawTest {

    private class FakeTarget(
        override val itemCount: Int,
        var current: Int,
        override var shuffleOn: Boolean = true,
    ) : ShuffleRedraw.Target {
        override var generation: Long = 0L
        override val currentIndex: Int get() = current
        val published = ArrayList<IntArray>()
        override fun publishOrder(order: IntArray) {
            published += order
        }
    }

    @Test
    fun `switching on twice deals two different orders, each leading with the song that is playing`() = runBlocking<Unit> {
        val random = Random(2026L)
        val target = FakeTarget(itemCount = 4_870, current = 1_234)

        assertThat(ShuffleRedraw.redraw(target, random)).isTrue()
        val first = target.published.single()
        // Off and on again: the same target, the same random source, a new deal.
        assertThat(ShuffleRedraw.redraw(target, random)).isTrue()
        val second = target.published[1]

        assertThat(first).isNotEqualTo(second)
        assertThat(PlayOrder.isPermutation(first, 4_870)).isTrue()
        assertThat(PlayOrder.isPermutation(second, 4_870)).isTrue()
        assertThat(first[0]).isEqualTo(1_234)
        assertThat(second[0]).isEqualTo(1_234)
        // Not a rotation of each other or a few swaps: the two deals agree on almost nothing (~1 position in 4,870 by chance).
        val sharedPositions = first.indices.count { first[it] == second[it] }
        assertThat(sharedPositions).isLessThan(60)
    }

    @Test
    fun `one switch is one publication and nothing else`() = runBlocking<Unit> {
        val target = FakeTarget(itemCount = 4_870, current = 0)

        ShuffleRedraw.redraw(target, Random(1L))

        // The target has no way to replace, add or move an item: the only thing a redraw can do to the queue is publish an order.
        assertThat(target.published).hasSize(1)
    }

    @Test
    fun `a song's place in the new order is uniform over the others`() = runBlocking<Unit> {
        val random = Random(7L)
        val size = 8
        val current = 2
        val draws = 6_000
        val counts = Array(size) { IntArray(size) }
        repeat(draws) {
            val target = FakeTarget(itemCount = size, current = current)
            ShuffleRedraw.redraw(target, random)
            target.published.single().forEachIndexed { position, index -> counts[index][position]++ }
        }

        // The playing song is always first; every other song lands in each of the 7 other places about 1 time in 7.
        assertThat(counts[current][0]).isEqualTo(draws)
        for (index in 0 until size) {
            if (index == current) continue
            assertThat(counts[index][0]).isEqualTo(0)
            for (position in 1 until size) {
                val share = counts[index][position].toDouble() / draws
                assertThat(share).isWithin(0.03).of(1.0 / (size - 1))
            }
        }
    }

    @Test
    fun `a skip made while the order is being dealt does not leave the old song first`() = runBlocking<Unit> {
        val target = FakeTarget(itemCount = 100, current = 10)

        ShuffleRedraw.redraw(target, Random(3L), draw = { count, source ->
            target.current = 11 // the listener skipped while the permutation was being built off the main thread
            PlayOrder.randomPermutation(count, source)
        })

        assertThat(target.published.single()[0]).isEqualTo(11)
    }

    @Test
    fun `a queue replaced while the order is being dealt gets nothing`() = runBlocking<Unit> {
        val target = FakeTarget(itemCount = 100, current = 10)

        val applied = ShuffleRedraw.redraw(target, Random(3L), draw = { count, source ->
            target.generation++ // another load, a removal, a drag: the queue is not the one this was dealt for
            PlayOrder.randomPermutation(count, source)
        })

        assertThat(applied).isFalse()
        assertThat(target.published).isEmpty()
    }

    @Test
    fun `shuffle switched off again while the order is being dealt gets nothing`() = runBlocking<Unit> {
        val target = FakeTarget(itemCount = 100, current = 10)

        val applied = ShuffleRedraw.redraw(target, Random(3L), draw = { count, source ->
            target.shuffleOn = false
            PlayOrder.randomPermutation(count, source)
        })

        assertThat(applied).isFalse()
        assertThat(target.published).isEmpty()
    }

    @Test
    fun `fewer than two songs have nothing to deal, and nothing is built`() = runBlocking<Unit> {
        var built = false
        for (count in 0..1) {
            val target = FakeTarget(itemCount = count, current = 0)
            val applied = ShuffleRedraw.redraw(target, Random(3L), draw = { n, source ->
                built = true
                PlayOrder.randomPermutation(n, source)
            })
            assertThat(applied).isFalse()
            assertThat(target.published).isEmpty()
        }
        assertThat(built).isFalse()
    }

    @Test
    fun `nothing is published when no song is playing`() = runBlocking<Unit> {
        val target = FakeTarget(itemCount = 50, current = -1)

        assertThat(ShuffleRedraw.redraw(target, Random(3L))).isFalse()
        assertThat(target.published).isEmpty()
    }

    @Test
    fun `the permutation is built off the thread that publishes it`() = runBlocking<Unit> {
        val callerThread = Thread.currentThread()
        var builtOn: Thread? = null
        var publishedOn: Thread? = null
        val target = object : ShuffleRedraw.Target {
            override val generation = 0L
            override val itemCount = 4_870
            override val currentIndex = 5
            override val shuffleOn = true
            override fun publishOrder(order: IntArray) {
                publishedOn = Thread.currentThread()
            }
        }

        ShuffleRedraw.redraw(target, Random(1L), draw = { count, source ->
            // The default draw hops to Dispatchers.Default; a custom one is the caller's business, so this one hops itself.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                builtOn = Thread.currentThread()
                PlayOrder.randomPermutation(count, source)
            }
        })

        assertThat(builtOn).isNotSameInstanceAs(callerThread)
        assertThat(publishedOn).isSameInstanceAs(callerThread)
    }
}
