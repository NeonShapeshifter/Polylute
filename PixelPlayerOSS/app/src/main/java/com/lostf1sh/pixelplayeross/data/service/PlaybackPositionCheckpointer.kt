package com.lostf1sh.pixelplayeross.data.service

/**
 * Persists only the playing position (a few bytes, never the queue) every [intervalMs] while
 * playback runs (AUD-121).
 *
 * The snapshot is written on state changes only (and, since AUD-113, as little as possible), so a
 * process death lost everything played since the last one — measured: killed at 39.9 s, restored
 * at 2.4 s. At most [intervalMs] is lost now.
 */
class PlaybackPositionCheckpointer(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val write: suspend (mediaId: String, positionMs: Long) -> Unit,
) {
    /** Runs until cancelled. [current] returns the playing item and position, or null when idle. */
    suspend fun run(current: suspend () -> Pair<String, Long>?) {
        while (true) {
            kotlinx.coroutines.delay(intervalMs)
            val (mediaId, positionMs) = current() ?: continue
            write(mediaId, positionMs)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
