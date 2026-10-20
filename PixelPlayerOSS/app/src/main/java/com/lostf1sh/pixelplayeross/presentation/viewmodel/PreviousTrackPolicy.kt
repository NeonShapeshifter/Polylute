package com.lostf1sh.pixelplayeross.presentation.viewmodel

/**
 * The one "previous" rule (AUD-116): past [RESTART_THRESHOLD_MS], or with nothing before, it restarts
 * the current item; otherwise it goes to the previous one. [PlaybackStateHolder.previousSong] acts on
 * it and the full player's carousel predicts with it, from the same engine position — a separate
 * 10 s threshold in the carousel animated to the previous artwork while the player restarted.
 */
object PreviousTrackPolicy {
    const val RESTART_THRESHOLD_MS = 3_000L

    fun restartsCurrent(positionMs: Long, hasPrevious: Boolean): Boolean =
        positionMs > RESTART_THRESHOLD_MS || !hasPrevious
}
