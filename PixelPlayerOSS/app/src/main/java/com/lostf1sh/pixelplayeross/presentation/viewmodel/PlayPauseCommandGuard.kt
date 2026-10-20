package com.lostf1sh.pixelplayeross.presentation.viewmodel

/**
 * B12a-7: the play/pause button must not bounce back while a tap is still on its way to the player.
 *
 * Owner, 2026-10-07: tapping pause, the glyph goes **pause → play → pause** and then settles on play, with the
 * player already paused. What the code shows:
 *
 * - the UI reads its state from a `MediaController`. A tap calls `controller.pause()`, and Media3's controller
 *   answers at once with its own optimistic state (`playWhenReady=false`, so `isPlaying=false`): the glyph
 *   goes to *play*;
 * - the session's player is a [com.lostf1sh.pixelplayeross.data.service.player.FadingPlayer]: its `pause()` ramps the
 *   volume down over ~72 ms and only then pauses. Every ramp step is a volume change, and the session reports each
 *   one to the controller together with its **current** state — which has not been paused yet:
 *   `isPlaying=true`, `playWhenReady=true`. That stale echo overwrites the optimistic state and the glyph goes back
 *   to *pause*;
 * - ~150 ms later the pause really happens, the session reports it, and the glyph goes to *play* for good.
 *
 * (The log shows a single `onIsPlayingChanged: false` per pause because that is the player's own listener: the
 * bounce happens in the controller's view of it, which has the optimistic step and the echoes.) The same happens
 * the other way: a play tap whose first ramp step (`setVolume(0)`) is echoed while the session is still paused.
 *
 * The rule: after a user command, **the state the user asked for stands for [HOLD_MS]**. A report that
 * contradicts the command inside that window is held back; one that agrees with it is applied (and does not end the
 * window: the controller's own optimistic step agrees with the command too, and it is the *first* of the reports, not
 * the last — ending the hold on it would let the session's echoes through right behind it). After the window
 * everything is believed, so a player that really did resume (a focus gain, another controller's `play()`) is shown,
 * at most [HOLD_MS] late. `isPlaying` and `playWhenReady` are tracked apart, because the echo carries both.
 *
 * Pure: the clock is an argument.
 */
internal class PlayPauseCommandGuard(private val holdMs: Long = HOLD_MS) {

    /** What a writer's report is turned into. [value] is what to store; [held] it was a contradicting echo. */
    class Resolution(val value: Boolean, val held: Boolean, val expired: Boolean, val ageMs: Long, val target: Boolean?)

    private class Hold(val target: Boolean, val issuedAtMs: Long)

    private var playing: Hold? = null
    private var intent: Hold? = null

    /** The user asked for playback ([playbackWanted] true) or for a pause (false) at [nowMs]. */
    @Synchronized
    fun onUserCommand(playbackWanted: Boolean, nowMs: Long) {
        playing = Hold(playbackWanted, nowMs)
        intent = Hold(playbackWanted, nowMs)
    }

    /** A write that is not a controller report (a new queue, the external VIEW, a reset) supersedes any command. */
    @Synchronized
    fun clear() {
        playing = null
        intent = null
    }

    /** True while a command is waiting for its confirmation. */
    @Synchronized
    fun hasPending(nowMs: Long): Boolean =
        listOfNotNull(playing, intent).any { nowMs - it.issuedAtMs < holdMs }

    /** `isPlaying` is [current]; a writer wants it to become [reported]. */
    @Synchronized
    fun resolvePlaying(current: Boolean, reported: Boolean, nowMs: Long): Resolution {
        val result = resolve(playing, current, reported, nowMs)
        if (result.second) playing = null
        return result.first
    }

    /** `playWhenReady` is [current]; a writer wants it to become [reported]. */
    @Synchronized
    fun resolveIntent(current: Boolean, reported: Boolean, nowMs: Long): Resolution {
        val result = resolve(intent, current, reported, nowMs)
        if (result.second) intent = null
        return result.first
    }

    /** The resolution, and whether the hold it was made under is over (only its age ends it). */
    private fun resolve(hold: Hold?, current: Boolean, reported: Boolean, nowMs: Long): Pair<Resolution, Boolean> {
        if (hold == null) return Resolution(reported, held = false, expired = false, ageMs = 0L, target = null) to false
        val age = nowMs - hold.issuedAtMs
        return when {
            // Too late to be an echo: the player really is doing something else (or nothing disagreed after all).
            age >= holdMs -> Resolution(reported, held = false, expired = reported != hold.target, ageMs = age, target = hold.target) to true
            // Agrees with what the user asked for (the controller's optimistic step, the session's confirmation).
            reported == hold.target -> Resolution(reported, false, false, age, hold.target) to false
            // A report that changes nothing needs no holding.
            reported == current -> Resolution(reported, false, false, age, hold.target) to false
            // The echo of a state from before the command: keep what the user is looking at.
            else -> Resolution(current, held = true, expired = false, ageMs = age, target = hold.target) to false
        }
    }

    companion object {
        /**
         * A pause is 72 ms of fade, the session's own report and two binder hops: ~150-250 ms on the HONOR, and the echoes
         * fall inside the first of those. 700 ms is the margin for a busy main thread; a contradiction older than that is real.
         */
        const val HOLD_MS = 700L
    }
}
