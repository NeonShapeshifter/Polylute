package com.lostf1sh.pixelplayeross.utils

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * The order songs play in, and the arithmetic around it (B17-H1).
 *
 * Shuffle is **Media3's shuffle mode**: the timeline the session publishes stays in the order the queue was loaded
 * in (the *base* order) and the player visits it through a `ShuffleOrder`. Turning shuffle on or off flips one flag;
 * no media item moves and nothing is republished, which is what a 4,870-song queue needs (the physical reorder it
 * replaces was two or three full timeline publications, ~5 s of main thread).
 *
 * That makes two orders worth naming. A **timeline index** is a song's place in the base order: what every Media3
 * call (`seekTo`, `moveMediaItem`, `removeMediaItem`, `getMediaItemAt`) speaks. A **position** is its place in the play
 * order: what the queue screen lists, and what "next" walks. With shuffle off they are the same number.
 *
 * An *order* here is an `IntArray` of timeline indices, one per position (`order[position] == timelineIndex`), always
 * a permutation of `0 until count`. Pure: no player, no Android beyond Media3's own `Timeline` type.
 */
object PlayOrder {

    fun identity(count: Int): IntArray = IntArray(count) { it }

    /**
     * The play order a [timeline] gives with [shuffleEnabled]. For a `MediaController` the timeline carries the
     * session player's order (`RemotableTimeline.shuffledWindowIndices`), so this reads it without asking the player
     * anything; for the engine's own ExoPlayer it is the live `ShuffleOrder`. Identity with shuffle off or when the
     * timeline does not describe a permutation (a timeline that ignores shuffle, e.g. a wrapper that forgot to forward it).
     */
    fun of(timeline: Timeline, shuffleEnabled: Boolean): IntArray {
        val count = timeline.windowCount
        if (count == 0) return IntArray(0)
        if (!shuffleEnabled) return identity(count)
        val order = IntArray(count)
        var index = timeline.getFirstWindowIndex(true)
        for (position in 0 until count) {
            if (index == C.INDEX_UNSET) return identity(count)
            order[position] = index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return if (isPermutation(order, count)) order else identity(count)
    }

    fun isPermutation(order: IntArray, count: Int): Boolean {
        if (order.size != count) return false
        val seen = BooleanArray(count)
        for (index in order) {
            if (index !in 0 until count || seen[index]) return false
            seen[index] = true
        }
        return true
    }

    /** A uniformly random permutation of `0 until count` (Fisher-Yates): a fresh shuffle order (B18-R1). */
    fun randomPermutation(count: Int, random: java.util.Random): IntArray {
        val order = identity(count)
        for (i in count - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val swap = order[i]
            order[i] = order[j]
            order[j] = swap
        }
        return order
    }

    /**
     * [order] with the song at [first] moved to position 0, the others keeping their relative order. A new random order made
     * while a song is playing starts with that song, so it is neither cut nor played again soon (B18-R1). The same array when
     * it already leads or does not hold [first].
     */
    fun currentFirst(order: IntArray, first: Int): IntArray {
        val at = positionOf(order, first)
        if (at <= 0) return order
        val result = IntArray(order.size)
        result[0] = first
        var next = 1
        for (index in order.indices) if (index != at) result[next++] = order[index]
        return result
    }

    /** `result[timelineIndex] == position`. */
    fun inverse(order: IntArray): IntArray {
        val inverse = IntArray(order.size)
        for (position in order.indices) inverse[order[position]] = position
        return inverse
    }

    fun isIdentity(order: IntArray): Boolean = order.indices.all { order[it] == it }

    /**
     * The order that makes the songs of [playSequence] play in exactly that sequence over a timeline loaded as
     * [baseIds], or null when it cannot be (the two lists do not hold the same songs).
     *
     * Duplicates are matched first-come: the second `x` of the sequence is the second `x` of the base order, so a
     * queue holding a track twice keeps both occurrences. Used where the app already produced a shuffled list (the
     * "Shuffle" buttons, a persistent shuffle) and the timeline is loaded in the original order.
     */
    fun fromSequence(baseIds: List<String>, playSequence: List<String>): IntArray? {
        if (baseIds.size != playSequence.size) return null
        val slots = HashMap<String, ArrayDeque<Int>>(baseIds.size * 2)
        for ((index, id) in baseIds.withIndex()) slots.getOrPut(id) { ArrayDeque() }.addLast(index)
        val order = IntArray(playSequence.size)
        for ((position, id) in playSequence.withIndex()) {
            order[position] = slots[id]?.removeFirstOrNull() ?: return null
        }
        return order
    }

    /**
     * [order] with the songs of [lead] first, in that order, and every other song after them in the order it had. What was
     * already played while the rest of a queue was still loading goes in front of the sequence the queue was dealt in, so it is
     * neither repeated nor skipped over (B18-R2). Indices of [lead] that are not in [order], or repeated, are ignored.
     */
    fun leadWith(order: IntArray, lead: List<Int>): IntArray {
        val inOrder = BooleanArray(order.size)
        for (index in order) if (index in inOrder.indices) inOrder[index] = true
        val seen = BooleanArray(order.size)
        val result = ArrayList<Int>(order.size)
        for (index in lead) {
            if (index !in inOrder.indices || !inOrder[index] || seen[index]) continue
            seen[index] = true
            result.add(index)
        }
        for (index in order) if (!seen[index]) result.add(index)
        return result.toIntArray()
    }

    /**
     * The order a list loaded as [baseIds] plays in when it was dealt as [sequence], with the [played] occurrences (what the
     * listener went through while the rest of the list was still loading, as **timeline indices** in play order) leading it.
     * Null when [sequence] is not the same songs as [baseIds]. With nothing played this is [fromSequence].
     *
     * The played songs are indices, not ids (B19-S1): a list can hold the same song twice, and an id would be matched to the first
     * free occurrence whichever one was heard.
     */
    fun dealt(baseIds: List<String>, sequence: List<String>, played: List<Int> = emptyList()): IntArray? {
        val order = fromSequence(baseIds, sequence) ?: return null
        return if (played.isEmpty()) order else leadWith(order, played)
    }

    /**
     * What [order] (positions to timeline indices over the whole list) says about the `size` songs from [start] on, as an order
     * over just that block: positions to indices inside the block (`0 until size`), in the order the whole list plays them.
     *
     * The block a load starts the player with is a piece of the list, and the player's own order over it is this, so the neighbours a
     * skip finds in the first moments are the ones the dealt sequence gives them, and the order applied once the rest is attached
     * (which is [order]) agrees with it for every song it holds (B19-S1).
     */
    fun restrictTo(order: IntArray, start: Int, size: Int): IntArray {
        val result = IntArray(size)
        var next = 0
        for (index in order) if (index in start until start + size) result[next++] = index - start
        return if (next == size) result else identity(size)
    }

    /** Where the song at [timelineIndex] sits in [order]; -1 when it is not there. */
    fun positionOf(order: IntArray, timelineIndex: Int): Int {
        for (position in order.indices) if (order[position] == timelineIndex) return position
        return -1
    }

    /**
     * Drag-and-drop in the queue screen: the song at [fromPosition] goes to [toPosition], the rest keep their relative
     * order. The timeline is not touched; only the order changes.
     */
    fun move(order: IntArray, fromPosition: Int, toPosition: Int): IntArray {
        if (fromPosition !in order.indices || toPosition !in order.indices || fromPosition == toPosition) return order.copyOf()
        val list = order.toMutableList()
        list.add(toPosition, list.removeAt(fromPosition))
        return list.toIntArray()
    }

    /** "Play next": the song at [timelineIndex] plays right after the song at [afterTimelineIndex]. */
    fun placeAfter(order: IntArray, timelineIndex: Int, afterTimelineIndex: Int): IntArray {
        if (timelineIndex == afterTimelineIndex) return order.copyOf()
        val list = order.toMutableList()
        if (!list.remove(timelineIndex)) return order.copyOf()
        val anchor = list.indexOf(afterTimelineIndex)
        if (anchor < 0) return order.copyOf()
        list.add(anchor + 1, timelineIndex)
        return list.toIntArray()
    }

    /** Puts the song at [timelineIndex] at [position] of the order (an undone removal goes back to the row it left). */
    fun placeAt(order: IntArray, timelineIndex: Int, position: Int): IntArray {
        val list = order.toMutableList()
        if (!list.remove(timelineIndex)) return order.copyOf()
        list.add(position.coerceIn(0, list.size), timelineIndex)
        return list.toIntArray()
    }

    /** "Add to queue": the song at [timelineIndex] plays after everything else. */
    fun placeLast(order: IntArray, timelineIndex: Int): IntArray {
        val list = order.toMutableList()
        if (!list.remove(timelineIndex)) return order.copyOf()
        list.add(timelineIndex)
        return list.toIntArray()
    }

    /**
     * An [order] over a list that was then filtered to [keptIndices] (the old index of each kept item, ascending):
     * the same sequence over the survivors, renumbered. Null if [order] is not a permutation of the old list.
     * A snapshot's order refers to the saved list, the restore plays a subset of it.
     */
    fun remap(order: IntArray, oldCount: Int, keptIndices: List<Int>): IntArray? {
        if (!isPermutation(order, oldCount)) return null
        val newIndexOfOld = IntArray(oldCount) { -1 }
        for ((newIndex, old) in keptIndices.withIndex()) {
            if (old !in 0 until oldCount) return null
            newIndexOfOld[old] = newIndex
        }
        val survivors = ArrayList<Int>(keptIndices.size)
        for (old in order) {
            val mapped = newIndexOfOld[old]
            if (mapped >= 0) survivors.add(mapped)
        }
        return survivors.toIntArray()
    }

    /**
     * The up-to-[count] songs that follow [currentIndex] in [order], as timeline indices (the widget's queue preview, the
     * "up next" lists). Stops at the end of the order; never contains [currentIndex].
     */
    fun upcoming(order: IntArray, currentIndex: Int, count: Int): List<Int> {
        val position = positionOf(order, currentIndex)
        if (position < 0) return emptyList()
        val end = minOf(order.size, position + 1 + count)
        return (position + 1 until end).map { order[it] }
    }
}
