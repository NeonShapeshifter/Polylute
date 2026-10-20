package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.utils.PlayOrder
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The new random order of classic mode (B18-R1): switching shuffle on deals the queue again, the song that is playing first.
 *
 * It is **one** timeline publication, a `setShuffleOrder` over the timeline the player already holds: no media item moves, is
 * replaced or is re-prepared, which is what B16's rebuild did (two replacements, ~5 s on 4,870 songs). The permutation is
 * built off the main thread; the queue is only touched on the caller's thread at the end, and only if it is still the queue the
 * draw was made for. The song that is playing is read at that moment, not when the draw began, so a skip made while it was
 * being dealt does not leave the order starting with a song that is no longer the current one.
 *
 * Pure: the player is a [Target], so the sequence runs on the JVM.
 */
internal object ShuffleRedraw {

    interface Target {
        /** Changes whenever the queue is replaced, edited or re-ordered by anyone else; equal values mean "the same queue". */
        val generation: Long

        val itemCount: Int

        /** Timeline index of the song that is playing, or -1. */
        val currentIndex: Int

        val shuffleOn: Boolean

        /** The one publication: the new order (positions to timeline indices), already known to be a permutation. */
        fun publishOrder(order: IntArray)
    }

    /**
     * Deals the queue again. Returns whether an order was published (false: fewer than two songs, the queue changed while it
     * was being drawn, shuffle was switched off again, or nothing is playing).
     *
     * [draw] builds the permutation and is expected to leave the caller's thread; [random] is injectable so a test can fix the
     * seed.
     */
    suspend fun redraw(
        target: Target,
        random: Random = Random(),
        draw: suspend (Int, Random) -> IntArray = { count, source ->
            withContext(Dispatchers.Default) { PlayOrder.randomPermutation(count, source) }
        },
    ): Boolean {
        val generation = target.generation
        val count = target.itemCount
        if (count < 2) return false
        val dealt = draw(count, random)
        // Back on the caller's thread: is it still the queue this was dealt for, and still shuffled?
        if (target.generation != generation || target.itemCount != count || !target.shuffleOn) return false
        val current = target.currentIndex
        if (current !in 0 until count) return false
        target.publishOrder(PlayOrder.currentFirst(dealt, current))
        return true
    }
}
