package com.decent.usbaudio.media3

/** Holds a bounded Media3 rebuffer stop after a natural join while the user still wants playback. */
object NaturalJoinPause {

    enum class Action { PAUSE_NOW, HOLD }

    /**
     * A join counts as "just happened" for this long: Media3's rebuffer of the new item starts within
     * tens of ms of the transition, and a user's seek or pause comes after a human reaction time.
     */
    const val JOIN_WINDOW_MS = 1_000L

    /**
     * Longest the pipeline keeps playing through a stop that Media3 has not ended. Past it the pause is
     * applied as it would have been at once, so a player that never calls `play()` again is not left
     * with the writer running under a paused UI.
     */
    const val MAX_HOLD_MS = 1_500L

    /**
     * @param userWantsPlayback `Player.playWhenReady`, as last reported by the player's own listener
     * @param msSinceNaturalJoin time since Media3's own transition at the end of a stream, or
     *        -1 when the last item change was not one (a manual skip, a seek, a first start)
     */
    fun onMedia3Stop(userWantsPlayback: Boolean, msSinceNaturalJoin: Long): Action = when {
        !userWantsPlayback -> Action.PAUSE_NOW
        msSinceNaturalJoin < 0L || msSinceNaturalJoin > JOIN_WINDOW_MS -> Action.PAUSE_NOW
        else -> Action.HOLD
    }

    /**
     * Why [onMedia3Stop] answered what it did, for the log line at a `pause()` that was not held (B10-4, AUD-144:
     * the native `rate` run of B9 faded a join with `joinPausesHeld=0`, and `pause()` said nothing about why).
     * `no-join-mark` is the one to look for: Media3 stopped right after a join that the sink never marked.
     */
    fun reason(userWantsPlayback: Boolean, msSinceNaturalJoin: Long): String = when {
        !userWantsPlayback -> "user-paused"
        msSinceNaturalJoin < 0L -> "no-join-mark"
        msSinceNaturalJoin > JOIN_WINDOW_MS -> "outside-window"
        else -> "held"
    }

}
