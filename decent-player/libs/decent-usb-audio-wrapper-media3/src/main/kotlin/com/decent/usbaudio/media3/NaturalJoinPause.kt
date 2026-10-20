package com.decent.usbaudio.media3

/**
 * AUD-144: what a `pause()` from Media3 does to the native engine that has just taken over at a
 * natural track join.
 *
 * Measured on the HONOR (B8a, device payload harness, 2026-09-29): two same-rate FLACs played back to
 * back reached the DAC with the right byte count but the wrong hash, differing in exactly one 1 MiB
 * block; a 96 → 44.1 kHz pair did the same. The block hash was reproduced to the bit on the host by
 * one modification of the expected stream: an 8 ms fade-out of the next decoded block's first frames
 * (the engine's *pause* fade, AUD-123/140), then a 15 ms fade-in over the rest of that block (the
 * *resume* fade), starting at the second decoded block of the new track. Nothing else changes.
 *
 * Why a pause at all: right after the transition Media3 rebuffers the new item (log: `BUFFERING`,
 * `onIsPlayingChanged: false`, then `play()` 35 ms later) and stops its renderers for that time —
 * `sink.pause()` — while the user still wants playback (`playWhenReady`). The engine is the producer
 * and has nothing to wait for, but it treated the stop like a user's pause: it queued the faded
 * continuation, kept the rest of the block and replayed it faded in on the `play()`.
 *
 * So a stop that arrives soon after a natural join, while the user still wants playback, is held back
 * from the engine: [MAX_HOLD_MS] at most, cancelled by the `play()` that ends the rebuffer, applied
 * at once when the user pauses. Everything else — a pause by the user, a pause long after the join, a
 * stop after a seek or a manual skip — reaches the engine exactly as before.
 */
object NaturalJoinPause {

    enum class Action { PAUSE_NOW, HOLD }

    /**
     * A join counts as "just happened" for this long: Media3's rebuffer of the new item starts within
     * tens of ms of the transition, and a user's seek or pause comes after a human reaction time.
     */
    const val JOIN_WINDOW_MS = 1_000L

    /**
     * Longest the engine keeps playing through a stop that Media3 has not ended. Past it the pause is
     * applied as it would have been at once, so a player that never calls `play()` again is not left
     * with an engine running under a paused UI.
     */
    const val MAX_HOLD_MS = 1_500L

    /**
     * @param userWantsPlayback `Player.playWhenReady`, as last reported by the player's own listener
     * @param msSinceNaturalJoin time since Media3's own transition after an engine's end of stream, or
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

    /**
     * B9-1: the ExoPlayer pipeline's thread now fades at a pause (the ring ends in an 8 ms fade-out and the
     * rest of that block comes back faded in), so a stop from Media3 at a natural join would modify the join's
     * samples exactly as it did the engine's. The same policy therefore covers it. An engine marks its own
     * join where it hands over; the pipeline has no engine, so Media3's own transition ([NaturalEndCoordinator.isSequential])
     * marks it, when the pipeline's thread is the producer. On the device Media3 sends no stop at a pipeline
     * join (B8b `bp-exo-gap`: no `pause()`, no `flush()`), so this costs nothing there; it is what keeps the
     * join bit-exact if one ever does.
     */
    fun marksPipelineJoin(engineExists: Boolean, threadRunning: Boolean): Boolean = !engineExists && threadRunning
}
