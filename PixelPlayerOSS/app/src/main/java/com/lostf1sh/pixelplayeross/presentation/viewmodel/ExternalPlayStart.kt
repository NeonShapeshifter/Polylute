package com.lostf1sh.pixelplayeross.presentation.viewmodel

/**
 * FIX-1b (B11-fix, device 2026-10-07): an external VIEW is now handed to the player in ~340 ms, and it
 * ended up paused at position 0.
 *
 * Android 15's audio hardening refuses `requestAudioFocus` to an app that is not in the foreground at
 * that instant. Until FIX-1 the VIEW took 10-50 s to load, by which time `ExternalPlayerActivity` was
 * long since resumed; now `play()` and its focus request happen ~300 ms after the intent, before the
 * activity counts as the foreground. The engine answers a refused request by setting
 * `playWhenReady = false` (`AudioFocus Request Failed`), which the rest of the app reads as the user
 * pausing.
 *
 * Loading stays fast; only the moment of `play()` changes:
 *
 * 1. the song is loaded **paused**, as fast as ever;
 * 2. `play()` waits until the app is in the foreground (the caller bounds the wait);
 * 3. if the platform still refuses (the process state lags the activity's resume by a few frames), the
 *    refusal is detected (the engine counts them) and `play()` is retried a few times, each after the app
 *    is in the foreground, instead of leaving the song paused.
 *
 * It never forces playback the user stopped: a retry only follows a refusal of **this** `play()`, and it
 * gives up once the song is no longer the current one.
 *
 * B12a-8 #5: and it gives up once the user has given a play/pause command since the request ([Outcome.USER_INTERVENED]).
 * The retries used to check only that the song was still current, so a user who paused while the engine's refusals
 * were being retried had the next `play()` override the pause. The engine answers a refusal with `playWhenReady = false`,
 * which looks exactly like the user pausing, so the signal is not the player's state but the user's own command: a
 * counter of play/pause taps (and of pauses made from another controller — the notification, a headset button), compared
 * with its value when the request was made.
 *
 * FIX-1c (device 2026-10-07, process freshly started): a VIEW whose process has just started is handed
 * to the player *before* the MediaController is connected, so its load is queued
 * (`pendingPlaybackAction`) and runs a moment later, when the controller connects. The queue restored
 * at start-up is what the player still holds in between. The first version of the guard read the
 * player: the restored song is not the VIEW's song, so it concluded "replaced" and gave up, 145 ms
 * before the load ran, and the VIEW ended paused on its own song. "My song is not in the player yet"
 * and "somebody replaced my song" are different facts, so the load now reports itself
 * ([awaitLoaded]) and the guard only means something after it:
 *
 * - [Outcome.SUPERSEDED]: the load never ran (another request took its place, or the controller never
 *   connected). Nothing is played.
 * - [Outcome.REPLACED]: the song **was** loaded and something else became current afterwards (another
 *   VIEW, a queue chosen in the app). Nothing is forced.
 *
 * B12a-5 (device 2026-10-07, FIX-1c timeline): `loaded=2179 foreground=5184 play=5334` — the song was in the player
 * at 2.2 s and `play()` waited the full 3 s cap for "foreground" (see [ForegroundWait]).
 *
 * Pure: the player, the foreground and the clock are arguments, so it runs in virtual time on the JVM.
 */
internal class ExternalPlayStart(
    /**
     * Suspends until this VIEW's song has been loaded into the player (true), or its load was dropped
     * before it could run (false). It may take seconds on a cold process: the load waits for the
     * MediaController.
     */
    private val awaitLoaded: suspend () -> Boolean,
    /** Suspends until the app is in the foreground, or a bounded time; true when it had to wait. */
    private val awaitForeground: suspend () -> Boolean,
    /** Whether the song that was opened is still the player's current item. */
    private val stillCurrent: () -> Boolean,
    /** The engine's count of audio-focus requests the platform refused. */
    private val focusDenials: () -> Int,
    private val play: () -> Unit,
    private val pause: suspend (millis: Long) -> Unit,
    /**
     * How many play/pause commands the user (or another controller) has given so far. Read when this is built, which is
     * when the request is made, and before every `play()`: a different number means the user has since decided.
     */
    private val userCommands: () -> Int = { 0 },
) {
    private val commandsAtRequest = userCommands()

    enum class Outcome {
        /** `play()` went through without a refusal. */
        PLAYING,

        /** The song was loaded, and then something else became the current item; nothing is forced. */
        REPLACED,

        /** Its load was dropped before it ran (another request took its place); nothing is played. */
        SUPERSEDED,

        /** Every attempt was refused; the song is left loaded and paused. */
        GAVE_UP,

        /** The user gave a play/pause command after the request (a pause during the retries, say); nothing is forced. */
        USER_INTERVENED,
    }

    suspend fun run(): Outcome {
        if (!awaitLoaded()) return Outcome.SUPERSEDED
        // The process state is a few frames behind the activity's resume: after having to wait for the
        // foreground, a short settle makes the first request the one that is granted.
        if (awaitForeground()) pause(SETTLE_AFTER_WAIT_MS)
        var retries = 0
        while (true) {
            if (!stillCurrent()) return Outcome.REPLACED
            // Before every request, the first included: the user's command is newer than anything this started.
            if (userCommands() != commandsAtRequest) return Outcome.USER_INTERVENED
            val refusedBefore = focusDenials()
            play()
            if (focusDenials() == refusedBefore) return Outcome.PLAYING
            if (retries >= MAX_RETRIES) return Outcome.GAVE_UP
            retries++
            pause(RETRY_SPACING_MS)
            awaitForeground()
        }
    }

    companion object {
        /** How long to wait for the foreground before asking anyway; the retries cover the rest. */
        const val FOREGROUND_WAIT_MS = 3_000L

        /** After having waited for the foreground, before the first request. */
        const val SETTLE_AFTER_WAIT_MS = 150L

        /** Between a refusal and the next request. */
        const val RETRY_SPACING_MS = 300L

        /**
         * Four retries at 300 ms is 1.2 s: longer than an activity's resume plus the process-state
         * update (measured in tens of milliseconds to a few hundred), short enough that a refusal that
         * is real (no foreground at all) is reported as paused rather than retried for ever.
         */
        const val MAX_RETRIES = 4
    }
}

/**
 * B12a-5: when is the app "in the foreground" for the purpose of asking for audio focus?
 *
 * The first version asked one question — is the process lifecycle RESUMED — and waited for it, capped at 3 s. On a
 * cold VIEW the song was loaded at 2.2 s and the wait then ate the whole cap (`foreground=5184`) although the
 * overlay was on screen. What Android 15's hardening actually looks at is the process state the platform holds for
 * the app (TOP while an activity is in front), not our lifecycle bookkeeping, and the two can disagree for a while: the
 * lifecycle follows `onResume` on the main thread, the process state follows the activity manager.
 *
 * So there are now two signals, and the wait ends on whichever says "foreground" first:
 * - the process lifecycle is RESUMED (what it always was), or
 * - the process's own importance is `IMPORTANCE_FOREGROUND` (the activity manager says it is the top app) — the
 *   state the focus check reads, so a `play()` made then is not refused for being in the background.
 *
 * It does not lower the bar: neither signal fires for an app that is not in front (a dialog on top, the screen off, a
 * background start), so FIX-1b's rule — never ask for focus before the app is foreground — still holds, and
 * [ExternalPlayStart] still retries a refusal. If neither fires the wait ends at the cap as before, and the caller logs
 * which signal ended it and what both read, so the next cold-VIEW timeline says what the 3 s really was.
 */
internal class ForegroundWait(
    private val lifecycleResumed: () -> Boolean,
    private val processInForeground: () -> Boolean,
    private val pause: suspend (millis: Long) -> Unit,
) {
    enum class Via {
        /** Already in front when asked: no wait. */
        ALREADY,

        /** Became so while waiting, by the lifecycle. */
        LIFECYCLE,

        /** Became so while waiting, by the process state (the lifecycle had not caught up). */
        PROCESS_STATE,

        /** Neither signal fired within the cap; the caller asks anyway and the retries cover the rest. */
        TIMEOUT,
    }

    class Result(val via: Via, val waitedMs: Long) {
        /** True when the caller had to wait (so a short settle precedes its first request). */
        val waited: Boolean get() = via != Via.ALREADY
    }

    suspend fun await(capMs: Long = ExternalPlayStart.FOREGROUND_WAIT_MS, pollMs: Long = POLL_MS): Result {
        if (lifecycleResumed() || processInForeground()) return Result(Via.ALREADY, 0L)
        var waitedMs = 0L
        while (waitedMs < capMs) {
            val step = minOf(pollMs, capMs - waitedMs)
            pause(step)
            waitedMs += step
            // The lifecycle first: it is the signal the original rule and its settle were designed around.
            if (lifecycleResumed()) return Result(Via.LIFECYCLE, waitedMs)
            if (processInForeground()) return Result(Via.PROCESS_STATE, waitedMs)
        }
        return Result(Via.TIMEOUT, waitedMs)
    }

    companion object {
        /** A binder call to the activity manager per poll: cheap, and bounded by the cap (~75 polls). */
        const val POLL_MS = 40L
    }
}
