package com.lostf1sh.pixelplayeross.dap.admin

/**
 * F-08: notices that the facts the appliance's mode is inferred from have changed while the process
 * was alive.
 *
 * The appliance policy (the containment flag, the Lock Task allowlist and mask, the persistent HOME,
 * the keyguard hold) is reconciled from Android's state by `restoreOwnerPolicies`, which ran only at
 * activity creation, at boot and after a package replacement. Changing the default HOME app does none
 * of those: the same process and the same activity keep running, so a HOME that went away and came
 * back left the product mode reading "dedicated" (it counts HOME as proof) while the flag and the
 * allowlist the HOME recovery had cleared stayed cleared. Only a reboot ran the reconciliation again.
 *
 * This is the signal that says "look again": it compares the evidence the resolver and the policies
 * read, between two looks, and says whether anything moved. It decides nothing about *what* to do;
 * the caller re-runs the reconciliation, which is idempotent.
 *
 * Pure and not thread-safe: both callers (`onResume`, `onNewIntent`) run on the main thread.
 */
class DapHomeChangeTracker {

    /** The evidence of one look. */
    data class Evidence(
        val isDeviceOwner: Boolean,
        val isPixelPlayerHome: Boolean,
        val isDedicatedModeEnabled: Boolean,
        val isLockTaskPermitted: Boolean,
        /** B34: an app pin starting or ending (only [android.app.ActivityManager.LOCK_TASK_MODE_PINNED] is ever recorded here) is a change the product mode must be derived from again. */
        val lockTaskModeState: Int = android.app.ActivityManager.LOCK_TASK_MODE_NONE,
    )

    private var last: Evidence? = null

    /**
     * Records [evidence] and returns whether it differs from the previous look. The first look only
     * records: the activity's own start has already reconciled once.
     */
    fun changed(evidence: Evidence): Boolean {
        val previous = last
        last = evidence
        return previous != null && previous != evidence
    }

    fun changed(state: DapDeviceOwnerState): Boolean = changed(
        Evidence(
            isDeviceOwner = state.isDeviceOwner,
            isPixelPlayerHome = state.isPixelPlayerHome,
            isDedicatedModeEnabled = state.isDedicatedModeEnabled,
            isLockTaskPermitted = state.isLockTaskPermitted,
            // B34: only an app pin counts. Lock Task going LOCKED -> NONE and back is the Maintenance lift the System page's openers do on a
            // Device Owner device, and it must not make this look like a change (a reconcile there would re-apply the allowlist the lift
            // released); a pin exists only without Device Owner.
            lockTaskModeState = state.lockTaskModeState.takeIf { it == android.app.ActivityManager.LOCK_TASK_MODE_PINNED }
                ?: android.app.ActivityManager.LOCK_TASK_MODE_NONE,
        )
    )
}
