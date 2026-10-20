package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.QueueUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages queue shuffle state.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * This class handles the original queue order for shuffle/unshuffle operations.
 */
@Singleton
class QueueStateHolder @Inject constructor() {

    private var _originalQueueOrder: List<Song> = emptyList()
    val originalQueueOrder: List<Song> get() = _originalQueueOrder

    /**
     * Set original queue order (for updates during playback).
     */
    fun setOriginalQueueOrder(queue: List<Song>) {
        _originalQueueOrder = queue.toList()
    }

    /**
     * Check if original queue is empty.
     */
    fun hasOriginalQueue(): Boolean = _originalQueueOrder.isNotEmpty()

    /**
     * Prepares a list for shuffled playback.
     * 1. Saves original queue.
     * 2. Picks a random start song.
     * 3. Creates a shuffled list starting with that song.
     * Runs the heavy shuffle computation on Default dispatcher to avoid UI stalls.
     */
    /**
     * One draw of the "Shuffle" buttons (B31 P2.2): the song it starts on, where that song sits in the list, and the order the whole list
     * plays in as list indices. Nothing is built from it until it is needed: no second list of 4,870 songs, no id strings to match.
     */
    class ShuffleDeal(val startSong: Song, val startIndex: Int, val order: IntArray)

    /**
     * The draw for [songs] without materialising the shuffled list: a random start (an index, so a song that is in the list twice starts
     * where the draw says, not at its first occurrence), the original order kept for turning shuffle off, and the order as indices.
     * The same distribution as [prepareShuffledQueueSuspending] (it is the same draw); only what is built from it differs.
     */
    suspend fun dealShuffledQueue(songs: List<Song>, startAtZero: Boolean = false): ShuffleDeal? {
        if (songs.isEmpty()) return null
        val startIndex = kotlin.random.Random.Default.nextInt(songs.size)
        setOriginalQueueOrder(songs)
        val order = withContext(Dispatchers.Default) { QueueUtils.dealOrder(songs.size, startIndex, startAtZero) }
        return ShuffleDeal(songs[startIndex], startIndex, order)
    }

    suspend fun prepareShuffledQueueSuspending(
        songs: List<Song>,
        startAtZero: Boolean = false
    ): Pair<List<Song>, Song>? {
        if (songs.isEmpty()) return null

        val startSong = songs.random()
        setOriginalQueueOrder(songs)

        val startIndex = songs.indexOfFirst { it.id == startSong.id }.coerceAtLeast(0)
        val shuffledQueue = withContext(Dispatchers.Default) {
            QueueUtils.buildAnchoredShuffleQueueSuspending(songs, startIndex, startAtZero)
        }
        return Pair(shuffledQueue, startSong)
    }
}
