package com.lostf1sh.pixelplayeross.data.worker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long the app waits after coming to the foreground before it starts bookkeeping nobody is waiting for (B23-P1).
 *
 * On the DAP every wake is an activity start, and the burst of a wake (SCREEN_ON, the lock surface mounting, the
 * session republishing, the first frames) is exactly when the USB writer and the decoder need the CPU. The lock
 * surface is up well inside a second on a warm wake and a little over one on the first one after an install
 * (`Davey 1331 ms`), so eight seconds is past all of it and short enough that nobody notices a library that is eight
 * seconds late.
 */
const val FOREGROUND_SETTLE_MS = 8_000L

/**
 * The longest a request can be put off by later ones. Without it a screen that keeps waking every few seconds would
 * push the work back for ever; a minute is the cooldown the catch-up already has.
 */
const val FOREGROUND_SETTLE_MAX_MS = 60_000L

/**
 * Runs one piece of foreground bookkeeping **after** [settleMs] of quiet, not in the burst that asked for it.
 *
 * Every request **restarts the wait** (B23 review): a second wake at 7.9 s of the first one's wait used to find the
 * action firing 100 ms after it, inside the very burst this protects. One action is ever pending, the latest one, so a
 * burst (a start, its resume, a quick toggle of the screen) runs it once, [settleMs] after the last of the burst. The
 * deferral is bounded: it never goes past [maxDeferMs] from the first request of the burst.
 *
 * The wait is not cancelled when the app goes back to the background: the work is bookkeeping that is owed anyway, and
 * running it while the screen is off is the best place for it. A request that arrives while the action is running
 * starts a new wait.
 *
 * [scope] decides where the action runs; the delay is virtual time under a test scope, and [now] (milliseconds, any
 * origin, only differences are used) is the clock the cap is measured on, so the whole thing is testable with a fake
 * clock.
 */
class ForegroundSettleGate(
    private val scope: CoroutineScope,
    private val settleMs: Long = FOREGROUND_SETTLE_MS,
    private val maxDeferMs: Long = FOREGROUND_SETTLE_MAX_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var pending: Job? = null
    private var waiting = false
    private var generation = 0L
    private var firstRequestAt = 0L
    private var latest: (suspend () -> Unit)? = null

    /** True while a request is waiting out its settle time. */
    val isPending: Boolean
        @Synchronized get() = waiting

    @Synchronized
    fun request(action: suspend () -> Unit) {
        val at = now()
        if (waiting) pending?.cancel() else firstRequestAt = at
        waiting = true
        latest = action
        val wait = minOf(settleMs, maxOf(0L, firstRequestAt + maxDeferMs - at))
        val mine = ++generation
        pending = scope.launch {
            delay(wait)
            val toRun = synchronized(this@ForegroundSettleGate) {
                if (mine != generation) return@launch
                waiting = false
                latest
            }
            toRun?.invoke()
        }
    }

    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
        waiting = false
        generation++
    }
}

/**
 * The foreground catch-up sync's decision, split out of `SyncManager` so it can be tested with a fake clock.
 *
 * It catches files MediaStore indexed while the app was backgrounded (the content observer only runs in the
 * foreground). Three rules, in this order:
 *  1. it waits [FOREGROUND_SETTLE_MS] of quiet after the app comes to the foreground, restarted by every new wake (B23-P1);
 *  2. it stays quiet for [cooldownMs] after the last one it scheduled, so quick minimise/restore cycles do not pile
 *     up work (the cooldown is measured when the sync would run, not when the app came forward);
 *  3. it does nothing until the initial setup is finished.
 */
class ForegroundCatchUp(
    scope: CoroutineScope,
    settleMs: Long = FOREGROUND_SETTLE_MS,
    private val cooldownMs: Long,
    private val now: () -> Long,
    private val initialSetupDone: suspend () -> Boolean,
    private val enqueueIncrementalSync: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val gate = ForegroundSettleGate(scope, settleMs, now = now)

    @Volatile
    private var lastScheduledAt = Long.MIN_VALUE

    /** The app is in the foreground (process `onStart`). */
    fun onForeground() {
        gate.request { runNow() }
    }

    private suspend fun runNow() {
        val at = now()
        if (lastScheduledAt != Long.MIN_VALUE && at - lastScheduledAt < cooldownMs) {
            log("Skipping foreground catch-up sync (cooldown active)")
            return
        }
        if (!initialSetupDone()) {
            log("Skipping foreground catch-up sync: initial setup not finished")
            return
        }
        lastScheduledAt = at
        log("Foreground catch-up - scheduling local incremental sync")
        enqueueIncrementalSync()
    }
}
