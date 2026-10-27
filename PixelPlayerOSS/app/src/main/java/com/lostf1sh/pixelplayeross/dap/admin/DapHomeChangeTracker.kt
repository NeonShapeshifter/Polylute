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
 *
 * **B40-F9b: two things this used to get wrong.**
 *  - It was one per activity instance. MainActivity can have two (see [DapActivityInstancePolicy]), each remembering a different "last
 *    look", so whether a change was noticed depended on which instance resumed. The activity now uses [process], the one tracker of the
 *    process, which every instance shares.
 *  - It only noticed a *difference between two looks*. A home change leaves no trace between them when the listener goes away and
 *    comes back to the same facts: on the device an exit left "owner, Polylute home, flag off, allowlist off" as the last look, HONOR was
 *    home for a while (nobody looked), and picking Polylute again produced the same facts, so nothing was reconciled and the device was left
 *    half-provisioned. [shouldReconcile] therefore also says yes for the state that is wrong by itself ([isUnfinished]), at every look.
 */
class DapHomeChangeTracker {

    companion object {
        /** The tracker of the process: every MainActivity instance looks through this one. */
        val process = DapHomeChangeTracker()

        /**
         * A Device Owner whose home app is Polylute, no exit pending, and the Dedicated policy not all there (the flag or the Lock Task
         * allowlist missing). That is what a half-provisioned device looks like, and the restore (which is idempotent and applies nothing
         * while an exit is chosen) is what completes it.
         */
        fun isUnfinished(state: DapDeviceOwnerState): Boolean =
            state.isDeviceOwner && state.isPixelPlayerHome && !state.isHomeExitChosen &&
                (!state.isDedicatedModeEnabled || !state.isLockTaskPermitted)
    }

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

    /**
     * Whether the reconciliation should run for this look: the facts moved since the last look ([changed], which always records the look), or
     * the device is [isUnfinished] and nothing else (a Maintenance session, a system surface lifted for a moment) is deliberately holding it
     * that way ([holding]).
     */
    fun shouldReconcile(state: DapDeviceOwnerState, holding: Boolean): Boolean {
        val moved = changed(state)
        return moved || (!holding && isUnfinished(state))
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
