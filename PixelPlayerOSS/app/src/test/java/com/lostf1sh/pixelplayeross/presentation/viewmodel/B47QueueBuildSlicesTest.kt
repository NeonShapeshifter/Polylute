package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * B47-A1: the rest of a big queue is built in slices with a pause between them, not in one block.
 *
 * "Before" is the one call the view model made (`buildItems(range)` as a single `List(range.count()) { ... }`): on 2026-10-29 it was 4,616 + 322 items and ~1.4 s of work without a
 * single suspension point. The test measures it on a fake clock where an item costs 0.3 ms (the owner's log: 1,423 ms for 4,938 items), so the numbers are the model's, not a device's.
 */
class B47QueueBuildSlicesTest {

    private val itemCostNanos = 300_000L
    private val rest = 0..4937

    /** A clock that moves only when work or a pause says so. */
    private class FakeClock {
        var nanos = 0L
        val runs = ArrayList<Long>()     // the length of each uninterrupted run, in nanoseconds
        val pauses = ArrayList<Long>()
        fun now() = nanos
    }

    @Test
    fun `the slices are the same items in the same order as one block`() = runBlocking<Unit> {
        val clock = FakeClock()
        val oneBlock = rest.map { "song-$it" }
        val sliced = buildInSlices(rest, nowNanos = clock::now, pauseAfter = { clock.pauses += it }) { slice ->
            clock.nanos += itemCostNanos * slice.count()
            clock.runs += itemCostNanos * slice.count()
            slice.map { "song-$it" }
        }
        assertThat(sliced).isEqualTo(oneBlock)   // content and order: the queue follows the source order, whatever the slices
    }

    @Test
    fun `the burst falls from 1481 ms in one block to 77 ms and the builder pauses between slices`() = runBlocking<Unit> {
        // before: one call, no suspension point
        val beforeRun = itemCostNanos * rest.count()
        // after
        val clock = FakeClock()
        buildInSlices(rest, nowNanos = clock::now, pauseAfter = { worked ->
            clock.pauses += slicePauseMs(worked) * 1_000_000L
            clock.nanos += slicePauseMs(worked) * 1_000_000L
        }) { slice ->
            clock.nanos += itemCostNanos * slice.count()
            clock.runs += itemCostNanos * slice.count()
            slice.toList()
        }
        val longestRun = clock.runs.max()
        val work = clock.runs.sum()
        val pause = clock.pauses.sum()
        println("B47-A1 queue build (model, 0.3 ms an item, 4,938 items): before one run of ${beforeRun / 1_000_000} ms; after ${clock.runs.size} runs, " +
            "the longest ${longestRun / 1_000_000} ms, work ${work / 1_000_000} ms, paused ${pause / 1_000_000} ms between them, " +
            "the build takes ${(work + pause) / 1_000_000} ms in all (${work * 100 / (work + pause)} % of one core)")
        assertThat(beforeRun).isEqualTo(1_481_400_000L)
        assertThat(clock.runs.size).isEqualTo(20)                           // 19 full slices of 256 and one of 74
        assertThat(longestRun).isEqualTo(itemCostNanos * QUEUE_BUILD_SLICE_ITEMS)
        assertThat(longestRun).isAtMost(80_000_000L)                         // under the ring's 128 ms and a few frames
        assertThat(clock.pauses).hasSize(19)                                 // none after the last slice: the attach follows
        assertThat(work * 100 / (work + pause)).isAtLeast(60L)               // about two thirds of one core while it works, not all of it
        assertThat(work * 100 / (work + pause)).isAtMost(70L)
    }

    @Test
    fun `the pause is half of what the slice took, between 1 and 100 ms`() {
        assertThat(slicePauseMs(76_800_000L)).isEqualTo(38L)
        assertThat(slicePauseMs(0L)).isEqualTo(1L)
        assertThat(slicePauseMs(10_000_000_000L)).isEqualTo(100L)
    }

    @Test
    fun `a range that is not a multiple of the slice, a short one and an empty one`() = runBlocking<Unit> {
        val seen = ArrayList<IntRange>()
        val all = buildInSlices(0..600, sliceSize = 256, pauseAfter = {}) { slice -> seen += slice; slice.toList() }
        assertThat(all).isEqualTo((0..600).toList())
        assertThat(seen).containsExactly(0..255, 256..511, 512..600).inOrder()
        assertThat(buildInSlices(5..9, pauseAfter = {}) { it.toList() }).isEqualTo(listOf(5, 6, 7, 8, 9))
        assertThat(buildInSlices(IntRange.EMPTY, pauseAfter = {}) { it.toList() }).isEmpty()
    }

    @Test
    fun `a cancelled load stops at the next slice instead of building the rest`() = runBlocking<Unit> {
        val built = ArrayList<IntRange>()
        try {
            buildInSlices(rest, pauseAfter = { if (built.size == 3) throw CancellationException("a newer load replaced this one") }) { slice ->
                built += slice
                slice.toList()
            }
        } catch (_: CancellationException) {
        }
        assertThat(built).hasSize(3)   // 768 of the 4,938 items were built, not all of them
    }
}
