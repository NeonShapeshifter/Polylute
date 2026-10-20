package com.lostf1sh.pixelplayeross.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackQueueItemSnapshot(
    val mediaId: String,
    val uri: String,
    val title: String? = null,
    val artist: String? = null,
    val albumTitle: String? = null,
    val artworkUri: String? = null,
    val durationMs: Long? = null,
)

@Serializable
data class PlaybackQueueSnapshot(
    val items: List<PlaybackQueueItemSnapshot>,
    val currentMediaId: String? = null,
    val currentIndex: Int = 0,
    val currentPositionMs: Long = 0L,
    val playWhenReady: Boolean = false,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false,
    /**
     * The order the songs play in while [shuffleEnabled] (B17-H1): `shuffleOrder[position]` is the index in [items] of the
     * song at that position. Shuffle no longer reorders the queue, so without this a restart would deal a new shuffle. Null
     * for a snapshot saved before it existed, whose [items] are already in the order that was playing.
     */
    val shuffleOrder: List<Int>? = null,
    val savedAtEpochMs: Long = System.currentTimeMillis(),
)
