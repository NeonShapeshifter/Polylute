package com.lostf1sh.pixelplayeross.dap.admin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * What Android says about "is Polylute the Device Owner". Three answers, because a failed read is not an answer (B34-fix1 R2): the old
 * adapter turned every exception into `false`, and `false` was read as "the role is gone".
 */
internal enum class OwnerPresence {
    /** A specific query answered: Polylute is the Device Owner. */
    PRESENT,

    /** A specific query answered: Polylute is not the Device Owner. */
    ABSENT,

    /** The query failed. Neither released nor still owner; nothing may be applied on the strength of it. */
    UNKNOWN,
}

/**
 * How "Dejar de ser propietario del dispositivo" ended (B34, B34-fix1). Only [RELEASED] says Polylute is no longer the Device Owner, and it
 * is said only after a query confirmed the absence.
 */
enum class DapReleaseOutcome {
    /** Policies cleared, `clearDeviceOwnerApp` attempted and a query after it confirmed Polylute is no longer the Device Owner. */
    RELEASED,

    /** A query confirmed Polylute was not the Device Owner: nothing was touched. */
    NOT_DEVICE_OWNER,

    /** The owner role or the device state could not be read before anything was touched: nothing was changed. */
    UNREADABLE_BEFORE,

    /** Lock Task would not stop: nothing was cleared. [DapReleaseResult.rollback] says whether the containment is confirmed back. */
    LOCK_NOT_STOPPED,

    /** An allowlist, keyguard or HOME policy could not be cleared: the owner was not cleared. [DapReleaseResult.rollback] says the rest. */
    POLICY_NOT_CLEARED,

    /** The policies are cleared but a query says Polylute is still the Device Owner. [DapReleaseResult.rollback] says whether it is back. */
    STILL_OWNER,

    /**
     * The owner was asked to go and the query that should say whether it did kept failing. Not released, not failed: unknown. Nothing was
     * re-applied (that would be on the strength of a guess) and the window was closed.
     */
    UNVERIFIED,
}

/** What a failed release did about the containment it had removed (B34-fix1 R1). Said to the listener only as it is. */
enum class DapReleaseRollback {
    /** No rollback applies: the release succeeded, ended before touching anything, or ownership was unknown so nothing was re-applied. */
    NONE,

    /** A rollback ran and had nothing to put back: the device was not contained when the release started. */
    NOT_NEEDED,

    /** The removed policies were put back and Android reads them as they were; if Lock Task was held at the start, `LOCKED` was observed. */
    RESTORED,

    /** The rollback ran and the policies or the lock could not be confirmed back. */
    NOT_RESTORED,
}

/** What the page needs to say about a release. [homeStillPolylute]: Polylute still answers HOME (Android's own default, not this app's policy). */
data class DapReleaseResult(
    val outcome: DapReleaseOutcome,
    val homeStillPolylute: Boolean = false,
    val adminRemoved: Boolean = true,
    val rollback: DapReleaseRollback = DapReleaseRollback.NONE,
)

/**
 * What the device looked like when the release started: the things the operation removes and a rollback puts back (B34-fix1 R1).
 *
 * Read once, before anything is touched. The product mode is *inferred* from these very signals (Device Owner plus HOME, the flag or the
 * allowlist), so after the operation has cleared them the mode reads "not dedicated" and a rollback asking the mode whether there is
 * anything to restore always answers no (R34-01). The rollback asks this instead.
 */
internal data class DapOwnerStart(
    val dedicatedFlag: Boolean,
    val persistentHomePreference: Boolean,
    val homeWasPolylute: Boolean,
    val lockTaskPermitted: Boolean,
    val lockHeld: Boolean,
) {
    /** Whether the device was contained at all: only then does a failed release owe a containment back. */
    val wasContained: Boolean
        get() = dedicatedFlag || homeWasPolylute || lockTaskPermitted || lockHeld
}

/**
 * "Dejar de ser propietario del dispositivo" as one ordered transaction (B34, B34-fix1): the phone is left clean or as it was, and the
 * result says which.
 *
 *  0. Ownership and the starting state are read first. If either cannot be read nothing is touched ([DapReleaseOutcome.UNREADABLE_BEFORE]).
 *  1. The Maintenance window opens, so nothing re-locks the device behind the release.
 *  2. Lock Task is stopped and the state read back (the activity's `stopLockTask()`, then the allowlist as the last resort).
 *  3. **Every policy the app set is cleared before the owner is:** the dedicated flag, the keyguard hold, the Lock Task mask and allowlist,
 *     and the persistent HOME (so Android's own HOME picker is in charge again).
 *  4. `clearDeviceOwnerApp` is attempted. Its answer, or its exception, is not trusted: **a query asks whether Polylute is still the
 *     owner** (a few bounded tries if the query fails).
 *       - absent  -> the departure is finished ([DapReleaseOutcome.RELEASED]): leftover admin removed, request back to Launcher, window closed;
 *       - present -> [DapReleaseOutcome.STILL_OWNER] and the rollback;
 *       - unknown -> [DapReleaseOutcome.UNVERIFIED]: window closed, nothing applied.
 *
 * **Rollback** (a failure with the role confirmed present): the policies are put back from the *starting state*, Android is read back,
 * the window is closed and, if Lock Task was held at the start, it is requested again and `LOCKED` observed. [DapReleaseRollback.RESTORED] is
 * said only after those reads. A permanent choice of another launcher (Polylute was not home at the start) is not undone: only what was
 * there is put back.
 *
 * **Cancellation** (the activity is destroyed, the scope cancelled): whatever phase it reaches, the window is closed and the policies are
 * settled under [NonCancellable], the same way [DapSystemSurfaceCoordinator] does for the Android-surface lift, and the cancellation
 * then goes on. The Lock Task entry itself is the next activity's: its resume enters Lock Task for a dedicated device with no window.
 *
 * Pure: every platform call is a lambda, so the order and the answers are what is tested. Never run against a real device by the batch.
 */
internal class DapReleaseOwnerCoordinator(
    private val ownerPresence: () -> OwnerPresence,
    private val readStart: () -> DapOwnerStart?,
    private val beginWindow: () -> Unit,
    private val stopLockTask: suspend () -> Boolean,
    private val clearPolicies: () -> Boolean,
    private val clearPersistentHome: () -> Boolean,
    private val clearDeviceOwner: () -> Unit,
    private val removeActiveAdmin: () -> Boolean,
    private val resetRequestToLauncher: () -> Unit,
    private val homeIsPolylute: () -> Boolean,
    private val endWindow: () -> Unit,
    /** Puts the removed policies back from the start; true only when Android reads each of them as it was. */
    private val restorePolicies: (DapOwnerStart) -> Boolean,
    /** Asks for Lock Task again and observes `LOCKED`. */
    private val relock: suspend () -> Boolean,
    private val readAttempts: Int = READ_ATTEMPTS,
    private val readRetryDelayMs: Long = READ_RETRY_DELAY_MS,
) {
    private enum class Phase { UNTOUCHED, WINDOW_OPEN, OWNER_ATTEMPTED, SETTLED }

    suspend fun release(): DapReleaseResult {
        when (readOwner()) {
            OwnerPresence.ABSENT -> return DapReleaseResult(DapReleaseOutcome.NOT_DEVICE_OWNER)
            OwnerPresence.UNKNOWN -> return DapReleaseResult(DapReleaseOutcome.UNREADABLE_BEFORE)
            OwnerPresence.PRESENT -> Unit
        }
        val start = runCatching { readStart() }.getOrNull() ?: return DapReleaseResult(DapReleaseOutcome.UNREADABLE_BEFORE)

        var phase = Phase.UNTOUCHED
        try {
            beginWindow()
            phase = Phase.WINDOW_OPEN
            if (!liftObserved()) return failed(DapReleaseOutcome.LOCK_NOT_STOPPED, start) { phase = Phase.SETTLED }

            val cleared = runCatching { clearPolicies() }.getOrDefault(false)
            val homeCleared = runCatching { clearPersistentHome() }.getOrDefault(false)
            if (!cleared || !homeCleared) return failed(DapReleaseOutcome.POLICY_NOT_CLEARED, start) { phase = Phase.SETTLED }

            phase = Phase.OWNER_ATTEMPTED
            // neither the call's return value nor its exception says whether the role is gone: the query after it does
            runCatching { clearDeviceOwner() }
            return when (readOwner()) {
                OwnerPresence.ABSENT -> finishDeparture().also { phase = Phase.SETTLED }
                OwnerPresence.PRESENT -> rollBack(DapReleaseOutcome.STILL_OWNER, start, relockNow = true) { phase = Phase.SETTLED }
                OwnerPresence.UNKNOWN -> {
                    endWindow()
                    phase = Phase.SETTLED
                    DapReleaseResult(DapReleaseOutcome.UNVERIFIED)
                }
            }
        } catch (cancelled: CancellationException) {
            if (phase != Phase.UNTOUCHED && phase != Phase.SETTLED) withContext(NonCancellable) { settleAfterCancel(start) }
            throw cancelled
        }
    }

    /** A failure before the owner was asked to go: re-read who the owner is, and only a confirmed owner gets its containment put back. */
    private suspend fun failed(
        outcome: DapReleaseOutcome,
        start: DapOwnerStart,
        settled: () -> Unit,
    ): DapReleaseResult = when (readOwner()) {
        OwnerPresence.PRESENT -> rollBack(outcome, start, relockNow = true, settled = settled)
        OwnerPresence.ABSENT -> finishDeparture().also { settled() }
        OwnerPresence.UNKNOWN -> {
            endWindow()
            settled()
            DapReleaseResult(DapReleaseOutcome.UNVERIFIED)
        }
    }

    private suspend fun rollBack(
        outcome: DapReleaseOutcome,
        start: DapOwnerStart,
        relockNow: Boolean,
        settled: () -> Unit,
    ): DapReleaseResult {
        val restored = runCatching { restorePolicies(start) }.getOrDefault(false)
        // the window closes whatever the restore said: a window nobody owns keeps the containment from ever coming back (R3)
        endWindow()
        settled()
        val rollback = when {
            !restored -> DapReleaseRollback.NOT_RESTORED
            !start.wasContained -> DapReleaseRollback.NOT_NEEDED
            start.lockHeld && relockNow -> if (relockObserved()) DapReleaseRollback.RESTORED else DapReleaseRollback.NOT_RESTORED
            else -> DapReleaseRollback.RESTORED
        }
        return DapReleaseResult(outcome, rollback = rollback)
    }

    /** A lift that throws is a lift that failed: the window is open and must be settled, not left to an uncaught exception. */
    private suspend fun liftObserved(): Boolean = try {
        stopLockTask()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        false
    }

    private suspend fun relockObserved(): Boolean = try {
        relock()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        false
    }

    private fun finishDeparture(): DapReleaseResult {
        val adminRemoved = runCatching { removeActiveAdmin() }.getOrDefault(false)
        // best-effort: the stored request is also reset on the next read once Polylute is not a Dedicated device, so a failure here must
        // not leave the window open
        runCatching { resetRequestToLauncher() }
        endWindow()
        return DapReleaseResult(
            outcome = DapReleaseOutcome.RELEASED,
            homeStillPolylute = runCatching { homeIsPolylute() }.getOrDefault(false),
            adminRemoved = adminRemoved,
        )
    }

    /**
     * The cleanup of a cancelled release, under [NonCancellable]. The same rule as everywhere else: a confirmed owner gets its policies back
     * (the Lock Task request is the next activity's), a confirmed departure is finished, and an unknown owner gets nothing applied. In every
     * case the window is closed, so the next activity inherits no `KEEP_OPEN` window that nobody is going to close.
     */
    private suspend fun settleAfterCancel(start: DapOwnerStart) {
        when (readOwner()) {
            OwnerPresence.PRESENT -> {
                runCatching { restorePolicies(start) }
                endWindow()
            }
            OwnerPresence.ABSENT -> finishDeparture()
            OwnerPresence.UNKNOWN -> endWindow()
        }
    }

    /** The specific ownership query, tried a bounded number of times while it fails. */
    private suspend fun readOwner(): OwnerPresence {
        repeat(readAttempts) { attempt ->
            val answer = runCatching { ownerPresence() }.getOrDefault(OwnerPresence.UNKNOWN)
            if (answer != OwnerPresence.UNKNOWN) return answer
            if (attempt < readAttempts - 1) delay(readRetryDelayMs)
        }
        return OwnerPresence.UNKNOWN
    }

    companion object {
        const val READ_ATTEMPTS = 3
        const val READ_RETRY_DELAY_MS = 200L
    }
}
