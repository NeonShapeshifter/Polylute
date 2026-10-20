package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import org.junit.jupiter.api.Test

/**
 * B17-H1: "restoring the queue after a restart keeps the shuffle mode and order".
 *
 * Shuffle no longer reorders the queue, so what a restart must bring back is the order. The saved queue is a filtered copy of the live
 * one (items without a uri are not saved), and the restore filters it twice more (not local media; file gone). Each of those has to
 * carry the order across by keeping the sequence of the survivors.
 */
class ShuffleOrderPersistenceTest {

    private val songs = listOf("a", "b", "c", "d", "e", "f")

    private fun sequence(order: IntArray, items: List<String>) = order.map { items[it] }

    @Test
    fun `nothing dropped, the same sequence comes back`() {
        val live = intArrayOf(4, 2, 0, 5, 1, 3)
        val saved = ShuffleOrderPersistence.orderToSave(live, liveCount = 6, keptIndices = (0 until 6).toList())!!
        val restored = ShuffleOrderPersistence.orderToRestore(
            saved = saved, savedCount = 6,
            keptByLocalFilter = (0 until 6).toList(), afterLocalFilterCount = 6,
            keptByProbe = (0 until 6).toList(), restoredCount = 6,
        )
        assertThat(sequence(restored, songs)).isEqualTo(sequence(live, songs))
    }

    @Test
    fun `songs that cannot be saved are left out of the order and the rest keep their sequence`() {
        // Live queue of 6; the song at index 1 has no uri and is not saved.
        val live = intArrayOf(4, 2, 0, 5, 1, 3)
        val keptIndices = listOf(0, 2, 3, 4, 5)
        val saved = ShuffleOrderPersistence.orderToSave(live, 6, keptIndices)!!
        val savedSongs = keptIndices.map { songs[it] }
        // 4 2 0 5 3 over the saved list a c d e f -> e c a f d
        assertThat(sequence(saved.toIntArray(), savedSongs)).containsExactly("e", "c", "a", "f", "d").inOrder()
    }

    @Test
    fun `a file that is gone at the restore is skipped and the others still play in the saved sequence`() {
        val saved = listOf(4, 2, 0, 5, 1, 3) // e c a f b d
        // Not local media: index 5 ("f"). Probe: the saved current and the ones after it are probed; "c" (after the filter, index 2) is gone.
        val afterLocal = listOf("a", "b", "c", "d", "e")
        val restored = ShuffleOrderPersistence.orderToRestore(
            saved = saved, savedCount = 6,
            keptByLocalFilter = listOf(0, 1, 2, 3, 4), afterLocalFilterCount = 5,
            keptByProbe = listOf(0, 1, 3, 4), restoredCount = 4,
        )
        val playable = listOf("a", "b", "d", "e")
        assertThat(PlayOrder.isPermutation(restored, 4)).isTrue()
        assertThat(sequence(restored, playable)).containsExactly("e", "a", "b", "d").inOrder()
        assertThat(afterLocal).hasSize(5)
    }

    @Test
    fun `a snapshot from before the order was saved plays the saved list front to back`() {
        // Its items already were the sequence that was playing (the old physical shuffle), so identity is the right order.
        val restored = ShuffleOrderPersistence.orderToRestore(
            saved = null, savedCount = 6,
            keptByLocalFilter = (0 until 6).toList(), afterLocalFilterCount = 6,
            keptByProbe = (0 until 6).toList(), restoredCount = 6,
        )
        assertThat(restored.toList()).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
    }

    @Test
    fun `an order that does not fit the list is not trusted`() {
        val restored = ShuffleOrderPersistence.orderToRestore(
            saved = listOf(0, 0, 1), savedCount = 3,
            keptByLocalFilter = listOf(0, 1, 2), afterLocalFilterCount = 3,
            keptByProbe = listOf(0, 1, 2), restoredCount = 3,
        )
        assertThat(restored.toList()).containsExactly(0, 1, 2).inOrder()
        assertThat(ShuffleOrderPersistence.orderToSave(intArrayOf(0, 0, 1), 3, listOf(0, 1, 2))).isNull()
    }

    @Test
    fun `a five thousand song order survives a restore that drops a hundred files`() {
        val count = 5_000
        val live = (0 until count).toMutableList().also { it.shuffle(java.util.Random(3)) }.toIntArray()
        val saved = ShuffleOrderPersistence.orderToSave(live, count, (0 until count).toList())!!
        val gone = (0 until count step 50).toSet() // 100 files
        val kept = (0 until count).filter { it !in gone }

        val restored = ShuffleOrderPersistence.orderToRestore(
            saved = saved, savedCount = count,
            keptByLocalFilter = (0 until count).toList(), afterLocalFilterCount = count,
            keptByProbe = kept, restoredCount = kept.size,
        )

        assertThat(PlayOrder.isPermutation(restored, kept.size)).isTrue()
        // The survivors play in the order they had.
        val expected = live.filter { it !in gone }
        assertThat(restored.map { kept[it] }).isEqualTo(expected)
    }
}
