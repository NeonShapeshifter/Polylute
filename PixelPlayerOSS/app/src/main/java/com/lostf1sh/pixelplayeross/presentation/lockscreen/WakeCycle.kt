package com.lostf1sh.pixelplayeross.presentation.lockscreen

import kotlinx.coroutines.delay

/** The states of a single wake cycle, from the screen going dark to a surface being raised. */
enum class WakeCycleState { IDLE, ARMED, WAITING_FOR_KEYGUARD, VISIBLE }

/**
 * One wake cycle, as an explicit state machine.
 *
 * This exists because the device proved that the trigger is not the one the code assumed.
 * `ACTION_USER_PRESENT` — the broadcast that is *documented* to mean "the user has cleared the
 * Keyguard" — never arrives on this unit at all. Instrumentation recorded a full screen-off →
 * wake → swipe → app sequence containing `SCREEN_OFF`, `SCREEN_ON`, `ON_START`, `ON_RESUME` and a
 * window-focus gain, and **no** `USER_PRESENT` line anywhere in it.
 *
 * What the same trace *did* show is the signal that works:
 *
 * ```
 * 444716569 | ON_RESUME  | kgLocked=true    <- too early: the Keyguard is still up
 * 444716826 | FOCUS      | kgLocked=false   <- 257 ms later, the device is actually usable
 * ```
 *
 * So a wake cycle cannot be a single event handler. It is a state that survives from the screen
 * going off until something authoritative proves Android is done with the Keyguard, and it must
 * *not* be spent by an event that arrives too early.
 *
 * The old code consumed the cycle at `USER_PRESENT` regardless of the answer. On this device that
 * branch never ran, so the cycle was never consumed — and never used either. Either way the surface
 * never appeared; this machine is what makes the decision explicit and testable.
 *
 * ## Time (B17-H2)
 *
 * A cycle is **armed for as long as the screen stays off** — a player in a pocket is dark for an hour — and
 * only the *act of waking* is bounded: the window of [WAKE_CYCLE_TIMEOUT_MS] starts at `SCREEN_ON`
 * ([onScreenOn]), not at `SCREEN_OFF`. B16 started its 60 s at `SCREEN_OFF`, so a screen that had been off for
 * more than a minute woke into a cycle that had already been reset and showed nothing; "off and on again
 * straight away" worked. The guarantee the cap exists for is unchanged: an ordinary resume with no cycle behind
 * it, or long after a wake, raises nothing (an armed cycle is consumed by the very next `SCREEN_ON`, and a
 * waiting one is dead [WAKE_CYCLE_TIMEOUT_MS] after it).
 *
 * Pure: no Android, no Compose, no clock. Time arrives as an argument ([nowMs], a monotonic reading such as
 * `SystemClock.elapsedRealtime()`); every transition is a function of the arguments.
 */
class WakeCycleMachine {

    var state: WakeCycleState = WakeCycleState.IDLE
        private set

    /** The monotonic reading of the `SCREEN_ON` (or first evidence) the wake window counts from; [NOT_WOKEN] while armed. */
    private var wokeAtMs: Long = NOT_WOKEN

    /** The monotonic reading of the `SCREEN_OFF` that armed the cycle, or [NOT_WOKEN]; for the log only. */
    var armedAtMs: Long = NOT_WOKEN
        private set

    /** Whether a cycle is live and may still be resolved into a surface. */
    val isLive: Boolean
        get() = state == WakeCycleState.ARMED || state == WakeCycleState.WAITING_FOR_KEYGUARD

    val shouldShowSurface: Boolean
        get() = state == WakeCycleState.VISIBLE

    private fun eligible(enabled: Boolean, dedicated: Boolean, hasSong: Boolean): Boolean =
        enabled && dedicated && hasSong

    /**
     * The screen went dark. A fresh cycle replaces any previous one — never stacks on it. It is armed with
     * no deadline: the screen may stay off as long as it likes.
     */
    fun onScreenOff(enabled: Boolean, dedicated: Boolean, nowMs: Long = NOT_WOKEN): WakeCycleState {
        wokeAtMs = NOT_WOKEN
        state = if (DapLockScreenPolicy.shouldArm(enabled, dedicated)) {
            armedAtMs = nowMs
            WakeCycleState.ARMED
        } else {
            armedAtMs = NOT_WOKEN
            WakeCycleState.IDLE
        }
        return state
    }

    /**
     * The surface is being attached (the composition began, or the receiver was registered again after a
     * language/Lock Task/Maintenance change) and the screen is **already dark**: nobody saw the `SCREEN_OFF`
     * this host would have armed on, so arm now. A host that comes up with the screen on arms nothing; a live
     * cycle is never replaced.
     */
    fun onHostAttached(
        enabled: Boolean,
        dedicated: Boolean,
        screenInteractive: Boolean,
        nowMs: Long = NOT_WOKEN,
    ): WakeCycleState {
        if (!screenInteractive && !isLive && state != WakeCycleState.VISIBLE) {
            onScreenOff(enabled = enabled, dedicated = dedicated, nowMs = nowMs)
        }
        return state
    }

    /**
     * The screen came back — **the start of the wake window**. Resolves immediately only if the Keyguard is
     * already out of the way; otherwise the cycle waits for it, for at most [WAKE_CYCLE_TIMEOUT_MS] from [nowMs].
     * Any live cycle is anchored here, however long it was armed.
     */
    fun onScreenOn(
        enabled: Boolean,
        dedicated: Boolean,
        keyguardLocked: Boolean,
        hasSong: Boolean,
        nowMs: Long,
    ): WakeCycleState {
        if (isLive) wokeAtMs = nowMs
        return evaluate(
            enabled = enabled,
            dedicated = dedicated,
            keyguardLocked = keyguardLocked,
            hasSong = hasSong,
            canResolveFrom = isLive,
            nowMs = nowMs,
        )
    }

    /**
     * Something happened that may mean the Keyguard is gone — a window-focus gain, a resume, or a
     * `USER_PRESENT` on a device that sends one.
     *
     * Deliberately *not* resolving on the event itself: it re-reads the Keyguard and decides. An
     * event that arrives early leaves the cycle `WAITING_FOR_KEYGUARD` rather than spending it.
     */
    fun onUnlockEvidence(
        enabled: Boolean,
        dedicated: Boolean,
        keyguardLocked: Boolean,
        hasSong: Boolean,
        nowMs: Long,
    ): WakeCycleState {
        // A waiting cycle whose window has run out is dead, whether or not anything noticed yet: an evidence
        // event (a focus gain while opening Settings) that arrives after it must not be what revives it.
        expireIfStale(nowMs)
        return evaluate(
            enabled = enabled,
            dedicated = dedicated,
            keyguardLocked = keyguardLocked,
            hasSong = hasSong,
            canResolveFrom = isLive,
            nowMs = nowMs,
        )
    }

    private fun evaluate(
        enabled: Boolean,
        dedicated: Boolean,
        keyguardLocked: Boolean,
        hasSong: Boolean,
        canResolveFrom: Boolean,
        nowMs: Long,
    ): WakeCycleState {
        // An ordinary resume — opening Settings, closing a dialog, navigating home — arrives with
        // no cycle behind it and must be ignored entirely. This single guard is what keeps the
        // surface from appearing during normal use.
        if (!canResolveFrom) return state

        state = when {
            DapLockScreenPolicy.shouldShow(
                enabled = enabled,
                dedicated = dedicated,
                keyguardLocked = keyguardLocked,
                wokeFromScreenOff = true,
                hasSong = hasSong,
            ) -> WakeCycleState.VISIBLE

            // Still eligible, just not usable yet. Keep waiting rather than consuming the cycle:
            // this is the case the device actually produces, and the old code got it wrong.
            eligible(enabled, dedicated, hasSong) -> {
                // A cycle that starts waiting on evidence alone (no `SCREEN_ON` seen) is anchored now.
                if (wokeAtMs == NOT_WOKEN) wokeAtMs = nowMs
                WakeCycleState.WAITING_FOR_KEYGUARD
            }

            // Eligibility itself is gone (setting off, Dedicated Mode left, song cleared).
            else -> WakeCycleState.IDLE
        }
        return state
    }

    /**
     * Whether the wake window of a **waiting** cycle has run out at [nowMs]; if so the cycle is over (`IDLE`).
     * An armed cycle never expires — the screen is simply still off — and a shown surface is not a cycle.
     */
    fun expireIfStale(nowMs: Long): Boolean {
        if (state != WakeCycleState.WAITING_FOR_KEYGUARD) return false
        if (wokeAtMs == NOT_WOKEN) return false
        if (nowMs - wokeAtMs < WAKE_CYCLE_TIMEOUT_MS) return false
        reset()
        return true
    }

    /**
     * How much of the wake window is left at [nowMs] for a waiting cycle (`0` when it has run out); `null` when
     * there is no window to wait out (armed, shown or idle) — the host has nothing to schedule.
     */
    fun wakeWindowRemainingMs(nowMs: Long): Long? {
        if (state != WakeCycleState.WAITING_FOR_KEYGUARD || wokeAtMs == NOT_WOKEN) return null
        return (wokeAtMs + WAKE_CYCLE_TIMEOUT_MS - nowMs).coerceAtLeast(0L)
    }

    /** The surface was dismissed, or the cycle was abandoned (the window ran out, eligibility was lost). */
    fun reset(): WakeCycleState {
        state = WakeCycleState.IDLE
        wokeAtMs = NOT_WOKEN
        armedAtMs = NOT_WOKEN
        return state
    }

    companion object {
        /**
         * Delay before each bounded re-check of the Keyguard, in milliseconds.
         *
         * The measured lag on this device was **257 ms** from the too-early `ON_RESUME` to the
         * authoritative focus gain, so the ladder is dense where the evidence is and coarse beyond
         * it. It is a backstop for lag, not the primary mechanism: the focus and resume events are.
         *
         * Bounded by construction — eight probes totalling ~3.9 s. There is no loop here and no
         * perpetual polling.
         */
        val KEYGUARD_RECHECK_MS = longArrayOf(16L, 50L, 100L, 250L, 500L, 1000L, 2000L)

        /**
         * How long a woken cycle may wait for the Keyguard before it is abandoned, **counted from `SCREEN_ON`**.
         *
         * A wake cycle is only meaningful while the user is still in the act of waking the device.
         * Bounding it is what stops a waiting cycle from lingering and firing at some unrelated
         * moment much later — an ordinary resume must never be able to raise this surface. It does not bound
         * how long the screen may be off (B17-H2): that is not part of the act of waking.
         */
        const val WAKE_CYCLE_TIMEOUT_MS = 60_000L

        /** "No reading": a cycle that has not been woken (or armed) yet. */
        const val NOT_WOKEN = Long.MIN_VALUE
    }
}

/**
 * The bounded re-check of a cycle that is waiting for the Keyguard, as a function of a clock and two callbacks so
 * the host's coroutine and the tests run the same code (B17-H2; the host used to hold this inline).
 *
 * Not a poll: the ladder is finite, then it waits out what is left of the wake window — counted from the
 * machine's own anchor (`SCREEN_ON`), not from when this coroutine happened to start, because a recomposition
 * can arrive late (the activity is stopped while the screen is off) — and gives the cycle up.
 *
 * Does nothing for any state but `WAITING_FOR_KEYGUARD`: an armed cycle has no timer at all.
 */
suspend fun superviseWaitingCycle(
    machine: WakeCycleMachine,
    nowMs: () -> Long,
    recheck: () -> Unit,
    onExpired: () -> Unit,
) {
    if (machine.state != WakeCycleState.WAITING_FOR_KEYGUARD) return
    for (delayMs in WakeCycleMachine.KEYGUARD_RECHECK_MS) {
        delay(delayMs)
        if (!machine.isLive) return
        recheck()
        if (machine.state != WakeCycleState.WAITING_FOR_KEYGUARD) return
    }
    val remaining = machine.wakeWindowRemainingMs(nowMs()) ?: return
    if (remaining > 0) delay(remaining)
    if (machine.expireIfStale(nowMs())) onExpired()
}
