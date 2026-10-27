package com.lostf1sh.pixelplayeross.dap.admin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * B40-P2: one system dialog (the USB permission dialog) shown to a Dedicated device, as one ordered, **bounded** transaction.
 *
 * On the HONOR the platform's own dialog cannot be shown while Lock Task holds the task list (`UsbPermissionActivity` is rejected, see
 * `DapContainmentPolicy.needsLockTaskSuspensionForSystemDialog`), so a DAC that was never allowed stays without permission and without
 * Purity. The way round is the one the System page already takes to open Android Settings:
 *
 *  1. Lock Task is lifted with the same two steps (`stopLockTask()`, then Device Owner's allowlist release if that did not reach NONE);
 *  2. the dialog is asked for ([show]);
 *  3. containment is put back **whatever happens**: the dialog is answered ([Outcome.ANSWERED]), it is dismissed and the app is in front
 *     again ([Outcome.DISMISSED]), nothing happens for [timeoutMs] ([Outcome.TIMED_OUT]), or the activity is cancelled meanwhile. The old
 *     path relocked only when the app came back to the front, so a dialog that never showed left the device unlocked for good.
 *
 * If the lift fails the dialog is not asked for and the containment is put back ([Outcome.NOT_LIFTED]): never a half-unlocked device.
 *
 * Pure: every platform call is a lambda, so the order and the bound are what is tested.
 */
internal class DapSystemDialogLift(
    private val lift: suspend () -> Boolean,
    private val relock: suspend () -> Unit,
    private val timeoutMs: Long,
) {
    enum class Outcome {
        /** Lock Task was not holding anything: the dialog was asked for directly, nothing was lifted. */
        SHOWN_WITHOUT_LIFT,

        /** The listener answered (allowed or refused) and containment is back. */
        ANSWERED,

        /** The dialog went away without an answer reaching the app, and the app is in front again. */
        DISMISSED,

        /** Nobody answered in time; containment was put back anyway. */
        TIMED_OUT,

        /** Lock Task could not be lifted; the dialog was not asked for and containment was put back. */
        NOT_LIFTED,
    }

    /**
     * @param needsLift whether Lock Task is holding the task list (decided by the caller from what Android reports, with the mode).
     * @param show asks the system for its dialog; called once, after the lift.
     * @param ended completed by the host with [Outcome.ANSWERED] or [Outcome.DISMISSED] when the dialog is over.
     */
    suspend fun run(needsLift: Boolean, show: () -> Unit, ended: CompletableDeferred<Outcome>): Outcome {
        if (!needsLift) {
            show()
            return Outcome.SHOWN_WITHOUT_LIFT
        }
        val lifted = try {
            lift()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { relock() }
            throw cancelled
        }
        if (!lifted) {
            relock()
            return Outcome.NOT_LIFTED
        }
        return try {
            show()
            withTimeoutOrNull(timeoutMs) { ended.await() } ?: Outcome.TIMED_OUT
        } finally {
            // also when the activity is being recreated: the next one must not inherit an unlocked device
            withContext(NonCancellable) { relock() }
        }
    }
}
