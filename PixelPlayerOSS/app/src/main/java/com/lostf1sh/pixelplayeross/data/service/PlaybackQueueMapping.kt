package com.lostf1sh.pixelplayeross.data.service

import com.lostf1sh.pixelplayeross.utils.PlayOrder

/**
 * Where the real queue lives (AUD-023).
 *
 * After a crossfade on a queue longer than the auxiliary window, the master player holds only a
 * window of it; the engine keeps the whole queue and the absolute index. Persisting or rebuilding
 * from the player's items then saved the window as if it were the queue.
 */
internal object QueueSource {
    fun <T> select(
        windowed: Boolean,
        fullQueue: List<T>,
        absoluteIndex: Int,
        playerItems: List<T>,
        playerIndex: Int,
    ): Pair<List<T>, Int> =
        if (windowed && fullQueue.isNotEmpty()) fullQueue to absoluteIndex.coerceIn(0, fullQueue.size - 1)
        else playerItems to playerIndex
}

/**
 * Carries a queue position across a filter, by position rather than by media id (AUD-044).
 *
 * [keptOriginalIndices] lists, in order, the original positions that survived the filter. The result
 * is the position in the kept list of the item that was at [originalIndex] — so a repeated track
 * keeps its own occurrence — or of the next kept item if that one was dropped, else the last one.
 */
internal object QueueIndexMapping {
    fun remap(keptOriginalIndices: List<Int>, originalIndex: Int): Int {
        if (keptOriginalIndices.isEmpty()) return 0
        val next = keptOriginalIndices.indexOfFirst { it >= originalIndex }
        return if (next >= 0) next else keptOriginalIndices.size - 1
    }
}

/**
 * The last value built for a key, the key compared by identity (AUD-108). A Media3 Timeline, or the
 * engine's full queue, is replaced by a new object whenever the queue changes, so identity is both
 * the cheapest and the correct test. Not thread-safe: the player's thread only.
 */
internal class IdentityMemo<V> {
    private var lastKey: Array<out Any?>? = null
    private var lastValue: V? = null

    fun get(vararg key: Any?, build: () -> V): V {
        val previous = lastKey
        @Suppress("UNCHECKED_CAST")
        if (previous != null && previous.size == key.size && previous.indices.all { previous[it] === key[it] }) {
            return lastValue as V
        }
        val value = build()
        lastKey = key.copyOf()
        lastValue = value
        return value
    }
}

/**
 * The two remaps a snapshot capture does over the whole queue, kept per queue revision (B23-P4).
 *
 * The capture runs on the main thread after every state change and 1.5 s after every song change, and with shuffle on it used
 * to validate the permutation, build boxed survivor lists and box the saved order again (~0.2–0.4 MB of garbage for 4,870
 * items), then scan the kept indices for the cursor, each time, for the same queue. None of it changes until the queue does:
 *
 *  - [savedOrder] is `ShuffleOrderPersistence.orderToSave`, held for the same `order` object over the same kept list. The
 *    engine hands out one order array per queue revision (and one for a windowed shuffle), so identity is the revision.
 *  - [currentIndex] is `QueueIndexMapping.remap`, constant time when nothing was dropped (the kept list is 0, 1, 2 … — checked
 *    once per kept list) and a binary search otherwise, instead of a scan from the front. The result is the same value.
 *
 * Restore stays exact: a duplicated track keeps its own occurrence because both are positional, and a different order or
 * kept list is a different key. Main thread only, like the capture.
 */
internal class SnapshotRemapMemo {
    /** Remaps actually computed (not served from what is held): a seam for the tests and a counter for the reviewer. */
    var orderRemaps = 0L
        private set
    var keptScans = 0L
        private set

    private var orderKey: IntArray? = null
    private var orderLiveCount = -1
    private var orderKept: List<Int>? = null
    private var orderValue: List<Int>? = null

    fun savedOrder(order: IntArray, liveCount: Int, keptIndices: List<Int>): List<Int>? {
        if (order === orderKey && liveCount == orderLiveCount && keptIndices === orderKept) return orderValue
        orderRemaps++
        val value = ShuffleOrderPersistence.orderToSave(order, liveCount, keptIndices)
        orderKey = order
        orderLiveCount = liveCount
        orderKept = keptIndices
        orderValue = value
        return value
    }

    private var keptKey: List<Int>? = null
    private var keptIsIdentity = false

    /** The same value as [QueueIndexMapping.remap]; [keptIndices] ascending, as the filter that made it leaves it. */
    fun currentIndex(keptIndices: List<Int>, originalIndex: Int): Int {
        if (keptIndices.isEmpty()) return 0
        if (keptIndices !== keptKey) {
            keptScans++
            keptKey = keptIndices
            keptIsIdentity = keptIndices is RandomAccess && keptIndices.indices.all { keptIndices[it] == it }
        }
        if (keptIsIdentity) return originalIndex.coerceIn(0, keptIndices.size - 1)
        if (keptIndices !is RandomAccess) return QueueIndexMapping.remap(keptIndices, originalIndex)
        // The first kept position whose original index is >= originalIndex, or the last one.
        var low = 0
        var high = keptIndices.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (keptIndices[mid] >= originalIndex) high = mid else low = mid + 1
        }
        return if (low < keptIndices.size) low else keptIndices.size - 1
    }
}

/**
 * The shuffle order across a save and a restore (B17-H1).
 *
 * Shuffle is the player's own mode and the saved queue is in the order it was loaded in, so a restart has to bring the order
 * back with it or it deals a new one. The order is saved over the saved list and restored over what could be restored: items
 * that cannot be played (no uri) are not saved, and the restore drops the ones whose file is gone (twice: the local-media
 * filter, then the probe), each time keeping the sequence of the survivors.
 */
internal object ShuffleOrderPersistence {
    /** The order to store next to [keptIndices] of the [liveCount] items of the queue, or null when it does not describe them. */
    fun orderToSave(order: IntArray, liveCount: Int, keptIndices: List<Int>): List<Int>? =
        PlayOrder.remap(order, liveCount, keptIndices)?.toList()

    /**
     * The play order over the restored items. [saved] null is a snapshot from before the order was stored: its items already
     * are the sequence that was playing, so it plays front to back. An order that no longer fits (a different count) does the same
     * rather than being trusted.
     */
    fun orderToRestore(
        saved: List<Int>?,
        savedCount: Int,
        keptByLocalFilter: List<Int>,
        afterLocalFilterCount: Int,
        keptByProbe: List<Int>,
        restoredCount: Int,
    ): IntArray {
        val carried = saved
            ?.toIntArray()
            ?.let { PlayOrder.remap(it, savedCount, keptByLocalFilter) }
            ?.let { PlayOrder.remap(it, afterLocalFilterCount, keptByProbe) }
        return carried?.takeIf { PlayOrder.isPermutation(it, restoredCount) } ?: PlayOrder.identity(restoredCount)
    }
}
