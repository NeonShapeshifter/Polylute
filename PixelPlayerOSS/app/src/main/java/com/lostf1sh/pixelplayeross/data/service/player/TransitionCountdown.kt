package com.lostf1sh.pixelplayeross.data.service.player

/** AUD-096: how long the crossfade countdown sleeps before looking at the position again. */
object TransitionCountdown {
    /**
     * Null while paused: the position cannot move, so the countdown waits for playback to resume
     * instead of polling it (every 1 s, or 50 ms within the last second, for as long as the pause
     * lasted). Playing, the cadence is the one it always had.
     */
    fun sleepMs(remainingMs: Long, isPlaying: Boolean): Long? {
        if (!isPlaying) return null
        return when {
            remainingMs > 5000 -> 1000L
            remainingMs > 1000 -> 250L
            else -> 50L
        }.coerceAtMost(remainingMs).coerceAtLeast(1L)
    }
}
