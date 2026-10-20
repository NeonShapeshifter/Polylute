package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.utils.PlayOrder

/**
 * What the crossfade's auxiliary player is loaded with, as a function of the queue, the song it starts on and the shuffle
 * order (B17-H1). Pure, so the three shapes it can take are testable without a player.
 *
 * The auxiliary player becomes the master when the fade ends, and the session then publishes *its* timeline. A queue
 * longer than [maxItems] is therefore never copied whole (a 4,870-item `setMediaItems` per fade is a stall): the auxiliary
 * player holds a window of it, and the engine remembers where in the real queue the window sits.
 *
 *  1. **Short queue** ([count] <= [maxItems]): the whole queue in its own order, with the master's shuffle order when
 *     there is one, so the next songs after the fade are the ones the master had lined up.
 *  2. **Long queue, shuffle off**: a window of the queue around the target, as before.
 *  3. **Long queue, shuffle on**: a window of the **play order** starting around the target, loaded in play order with an
 *     identity shuffle order. A window cut from the base order would hold the target's neighbours in the base order, none of
 *     which the shuffle order visits next; with it the fade ends on a timeline whose "next" is the song that was next.
 *
 * `sourceIndices` are indices into the list the plan was made for (the engine's queue snapshot, in the base order, or already
 * in the play order when [sourceIsPlayOrder]); `windowStart` is where the window begins in the list it was cut from.
 */
internal class AuxiliaryQueuePlan private constructor(
    /** Indices into the snapshot of the items the auxiliary player holds, in the order of its timeline. */
    val sourceIndices: IntArray,
    /** Where the target is inside the auxiliary timeline. */
    val targetIndex: Int,
    /** The auxiliary player's own play order (positions to its timeline indices); null when shuffle is off. */
    val auxOrder: IntArray?,
    val windowed: Boolean,
    /** Start of the window in the list it was cut from (the play order in shape 3). */
    val windowStart: Int,
    /** In shape 3: the whole play order (positions to snapshot indices) the window was cut from; otherwise null. */
    val windowedPlayOrder: IntArray?,
) {
    companion object {
        /**
         * @param count the size of the queue (the snapshot).
         * @param targetIndex the target's index in the snapshot.
         * @param playOrder the master's play order over the snapshot (positions to snapshot indices), or null with shuffle off.
         * @param sourceIsPlayOrder the snapshot is itself already in play order (the engine holds a play-ordered window state
         *   and shuffle is on): windows are cut from it as they are, loaded in order, with an identity shuffle order.
         */
        fun build(
            count: Int,
            targetIndex: Int,
            playOrder: IntArray?,
            maxItems: Int,
            sourceIsPlayOrder: Boolean = false,
        ): AuxiliaryQueuePlan {
            require(count > 0 && targetIndex in 0 until count) { "target $targetIndex outside a queue of $count" }
            if (count <= maxItems) {
                return AuxiliaryQueuePlan(
                    sourceIndices = PlayOrder.identity(count),
                    targetIndex = targetIndex,
                    auxOrder = if (sourceIsPlayOrder) PlayOrder.identity(count) else playOrder,
                    windowed = false,
                    windowStart = 0,
                    windowedPlayOrder = null,
                )
            }
            if (sourceIsPlayOrder) {
                val (start, end) = windowBounds(targetIndex, count, maxItems)
                return AuxiliaryQueuePlan(
                    sourceIndices = IntArray(end - start) { start + it },
                    targetIndex = targetIndex - start,
                    auxOrder = PlayOrder.identity(end - start),
                    windowed = true,
                    windowStart = start,
                    windowedPlayOrder = null,
                )
            }
            if (playOrder == null) {
                val (start, end) = windowBounds(targetIndex, count, maxItems)
                return AuxiliaryQueuePlan(
                    sourceIndices = IntArray(end - start) { start + it },
                    targetIndex = targetIndex - start,
                    auxOrder = null,
                    windowed = true,
                    windowStart = start,
                    windowedPlayOrder = null,
                )
            }
            val targetPosition = PlayOrder.positionOf(playOrder, targetIndex).takeIf { it >= 0 } ?: 0
            val (start, end) = windowBounds(targetPosition, count, maxItems)
            return AuxiliaryQueuePlan(
                sourceIndices = IntArray(end - start) { playOrder[start + it] },
                targetIndex = targetPosition - start,
                auxOrder = PlayOrder.identity(end - start),
                windowed = true,
                windowStart = start,
                windowedPlayOrder = playOrder,
            )
        }

        /** The window of [maxItems] around [target], clamped to the queue: the engine's long-standing rule. */
        fun windowBounds(target: Int, count: Int, maxItems: Int): Pair<Int, Int> {
            if (count <= maxItems) return 0 to count
            val half = maxItems / 2
            var start = (target - half).coerceAtLeast(0)
            val end = (start + maxItems).coerceAtMost(count)
            start = (end - maxItems).coerceAtLeast(0)
            return start to end
        }
    }
}
