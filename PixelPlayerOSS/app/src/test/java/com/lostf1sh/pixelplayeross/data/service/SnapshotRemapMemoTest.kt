package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import java.util.Random
import org.junit.jupiter.api.Test

/**
 * B23-P4: the snapshot capture ran the whole-queue remaps (a permutation check, boxed survivor lists, the saved order boxed again,
 * a scan for the cursor) on every capture for the same queue: 16-70 ms on the main thread, 1.5 s after every song change.
 * They are now held per queue revision, with the same values.
 */
class SnapshotRemapMemoTest {

    private fun shuffled(n: Int, seed: Long): IntArray {
        val list = (0 until n).toMutableList()
        list.shuffle(Random(seed))
        return list.toIntArray()
    }

    @Test
    fun `capture with shuffle on and an unchanged queue does no remap after the first`() {
        val memo = SnapshotRemapMemo()
        val order = shuffled(4870, 7)
        val kept = (0 until 4870).toList()
        val first = memo.savedOrder(order, 4870, kept)
        repeat(50) {
            // every capture of the same queue, a song change or not: the same object back, nothing recomputed
            assertThat(memo.savedOrder(order, 4870, kept)).isSameInstanceAs(first)
            memo.currentIndex(kept, it * 90)
        }
        assertThat(memo.orderRemaps).isEqualTo(1L)
        assertThat(memo.keptScans).isEqualTo(1L)
        assertThat(first).isEqualTo(ShuffleOrderPersistence.orderToSave(order, 4870, kept))
    }

    @Test
    fun `a new order, a new kept list or a new count is a new remap`() {
        val memo = SnapshotRemapMemo()
        val kept = (0 until 100).toList()
        val a = shuffled(100, 1)
        val b = shuffled(100, 2)
        memo.savedOrder(a, 100, kept)
        assertThat(memo.savedOrder(b, 100, kept)).isEqualTo(ShuffleOrderPersistence.orderToSave(b, 100, kept))
        assertThat(memo.orderRemaps).isEqualTo(2L)
        val keptOther = (0 until 100).toList()           // equal content, another list: the queue was rebuilt
        memo.savedOrder(b, 100, keptOther)
        assertThat(memo.orderRemaps).isEqualTo(3L)
        // the order no longer describes the list: null, and held as null
        assertThat(memo.savedOrder(a, 99, kept)).isNull()
        assertThat(memo.savedOrder(a, 99, kept)).isNull()
        assertThat(memo.orderRemaps).isEqualTo(4L)
    }

    @Test
    fun `dropped items keep their order and their duplicates, exactly as before`() {
        val memo = SnapshotRemapMemo()
        // 10 items, two of them dropped (no uri): kept = 0,1,3,4,6,7,8,9
        val kept = listOf(0, 1, 3, 4, 6, 7, 8, 9)
        val order = intArrayOf(5, 3, 9, 0, 2, 7, 1, 8, 4, 6)
        val saved = memo.savedOrder(order, 10, kept)
        assertThat(saved).isEqualTo(ShuffleOrderPersistence.orderToSave(order, 10, kept))
        assertThat(saved).containsExactly(2, 7, 0, 5, 1, 6, 3, 4).inOrder()   // 5 and 2 are gone, the rest renumbered
        // the cursor is positional: the original index 5 was dropped, so the next kept one (6, at position 4) is the cursor
        assertThat(memo.currentIndex(kept, 5)).isEqualTo(4)
        assertThat(memo.currentIndex(kept, 5)).isEqualTo(QueueIndexMapping.remap(kept, 5))
    }

    @Test
    fun `the cursor is the same value as QueueIndexMapping for any queue and any index`() {
        val random = Random(42)
        repeat(300) {
            val size = 1 + random.nextInt(60)
            // an ascending kept list: identity, a prefix, or a random subset
            val kept = when (it % 3) {
                0 -> (0 until size).toList()
                1 -> (0 until 1 + random.nextInt(size)).toList()
                else -> (0 until size).filter { random.nextInt(3) != 0 }
            }
            val memo = SnapshotRemapMemo()
            for (index in -2..size + 2) {
                assertThat(memo.currentIndex(kept, index)).isEqualTo(QueueIndexMapping.remap(kept, index))
            }
        }
    }

    @Test
    fun `an empty kept list maps to zero and a repeated track keeps its own occurrence`() {
        val memo = SnapshotRemapMemo()
        assertThat(memo.currentIndex(emptyList(), 17)).isEqualTo(0)
        // the same song three times (positions 2, 5, 8): the cursor on the second occurrence stays on the second
        val kept = listOf(0, 2, 5, 8, 9)
        assertThat(memo.currentIndex(kept, 5)).isEqualTo(2)
        assertThat(memo.currentIndex(kept, 8)).isEqualTo(3)
        assertThat(memo.currentIndex(kept, 2)).isEqualTo(1)
    }
}
