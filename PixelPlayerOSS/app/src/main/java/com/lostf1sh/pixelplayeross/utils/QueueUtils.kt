package com.lostf1sh.pixelplayeross.utils

import com.lostf1sh.pixelplayeross.data.model.Song
import kotlin.random.Random

object QueueUtils {

    /**
     * Suspendable shuffle that yields periodically for large queues (10,000+),
     * delegated to [Shuffler] so all queue draws share one central source.
     */
    suspend fun buildAnchoredShuffleQueueSuspending(
        currentQueue: List<Song>,
        anchorIndex: Int,
        startAtZero: Boolean = false,
        random: Random = Shuffler.defaultKotlinRandom()
    ): List<Song> {
        if (currentQueue.size <= 1) return currentQueue.toList()
        val order = dealOrder(currentQueue.size, anchorIndex, startAtZero, random)
        return List(order.size) { idx -> currentQueue[order[idx]] }
    }

    /**
     * The draw on its own (B31 P2.2): the sequence a list of [size] songs plays in, as **list indices** (`order[0]` is the first song to
     * play, and with [startAtZero] it is [anchorIndex]; without it the anchor keeps its own place), dealt by [Shuffler].
     */
    suspend fun dealOrder(
        size: Int,
        anchorIndex: Int,
        startAtZero: Boolean = false,
        random: Random = Shuffler.defaultKotlinRandom(),
    ): IntArray = Shuffler.dealSuspending(
        size = size,
        anchorIndex = anchorIndex,
        startAtZero = startAtZero,
        random = random,
    )
}
