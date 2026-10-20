package com.lostf1sh.pixelplayeross.dap.admin

/**
 * "Volver al DAP", as one ordered transaction (B32-fix1 R3).
 *
 * The listener asked, explicitly, for the device to be the DAP again. That is not the same thing as closing a temporary window
 * (`MainActivity.exitAuthenticatedMaintenance`, which an expiry or the app coming back from Android Settings runs): that one asks the
 * *product mode* whether there is a dedicated mode to go back to, and after the HONOR recovery there is none — it cleared the HOME
 * policy, the dedicated flag and the Lock Task allowlist, which is every piece of evidence the mode is inferred from — so it answered
 * "nothing to do" and reported success while HOME, the flag and Lock Task stayed off (R32-03).
 *
 * Here the question asked is the one that matters: is this a Device Owner? If it is not (App Mode, the S22), there is nothing to contain:
 * the window, if one is open, is closed, Lock Task is **not** requested, and the answer says so. If it is, the DAP policy is put back
 * ([reassertPolicy]: HOME, the dedicated flag and the allowlist, writing nothing when they are all in place), the product mode is read
 * again to confirm it, the window is closed, Lock Task is requested and **observed**. Success is reported only for that: containment
 * confirmed. Anything short of it is said as what it is.
 *
 * It never runs on its own, and the app coming back does not run it either: a choice of another launcher stands while that launcher is the
 * home app, and nothing re-arms the policy behind it (B34-fix2). The choice ends in one of two ways.
 *  - The listener makes Polylute the home app again, and the next resume re-contains the device by itself, persistent HOME included (B34's
 *    table row 6, `DapContainmentReconciler.restore`). That is a device that is Dedicated again, and this transaction has nothing left to write.
 *  - The listener asks for it here: the explicit way back and the confirmation. It also completes a device that was locked without its
 *    persistent HOME.
 *
 * Pure: every platform call is a lambda, so the order and the answers are what is tested.
 */
internal class DapReturnToDapCoordinator(
    private val isDeviceOwner: () -> Boolean,
    private val reassertPolicy: () -> Boolean,
    private val isDedicatedActive: () -> Boolean,
    private val endWindow: () -> Unit,
    private val prepareLockTaskTransition: () -> Unit,
    private val requestLockTask: () -> Unit,
    private val awaitLocked: suspend () -> Boolean,
    private val recoverContainment: suspend () -> Unit,
) {
    suspend fun returnToDap(): DapReturnOutcome {
        if (!isDeviceOwner()) {
            endWindow()
            return DapReturnOutcome.NOTHING_TO_CONTAIN
        }
        if (!reassertPolicy() || !isDedicatedActive()) return DapReturnOutcome.POLICY_NOT_APPLIED

        endWindow()
        prepareLockTaskTransition()
        requestLockTask()
        if (awaitLocked()) return DapReturnOutcome.CONTAINED
        recoverContainment()
        return DapReturnOutcome.NOT_LOCKED
    }
}
