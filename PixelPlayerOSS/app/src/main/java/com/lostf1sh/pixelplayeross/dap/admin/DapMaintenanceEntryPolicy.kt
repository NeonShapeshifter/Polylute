package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager

/**
 * F-08: how Maintenance gets the device out of Lock Task, decided from what Android says the Lock
 * Task state *is*, never from what the app infers the product mode to be.
 *
 * Maintenance is the owner's way out. After the owner changed the default HOME app and set the app
 * back, they could not enter it until the phone was rebooted. The entry used to ask
 * `ProductModeController.isDedicatedActive()` first and skip releasing Lock Task when the answer was
 * "no": an inference built from Device Owner + HOME + a persisted flag + the Lock Task allowlist.
 * Those are exactly the things a HOME change moves around (the recovery flow clears the flag and the
 * allowlist, "select the app as HOME" sets only HOME), so the mode could say "App" while the activity
 * was still held by Lock Task. The session then began, nothing was released, and Android Settings
 * stayed blocked by the very containment Maintenance exists to leave.
 *
 * Pure: no Android calls, only the constants of [ActivityManager].
 */
object DapMaintenanceEntryPolicy {

    enum class Plan {
        /** Nothing is locked: begin the session. True in App Mode and in a half-provisioned device alike. */
        BEGIN_SESSION,

        /** Lock Task holds the device: release it first, whatever the product mode says. */
        RELEASE_LOCK_TASK,
    }

    enum class Escalation {
        /** The activity's own `stopLockTask()` got the state to NONE (or there was nothing to stop). */
        NONE_NEEDED,

        /** It did not, and this app is Device Owner: take the app out of the Lock Task allowlist. */
        DEVICE_OWNER_RELEASE,

        /** It did not and nothing else can: report failure, keep the containment, say so. */
        GIVE_UP,
    }

    fun plan(lockTaskModeState: Int): Plan =
        if (lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) Plan.BEGIN_SESSION else Plan.RELEASE_LOCK_TASK

    /**
     * What to do once `stopLockTask()` has been asked for and the transition waited out.
     *
     * Device Owner can always end Lock Task for its own app by removing it from the allowlist, which
     * is what makes "Maintenance is never unreachable" a guarantee instead of a hope: a refusal or an
     * exception from `stopLockTask()` (it throws when the activity is not the one that is locked)
     * no longer leaves the owner stuck. The allowlist is put back by the normal Maintenance exit
     * (`ensureDedicatedModeReady`) and by every restore trigger.
     */
    fun escalation(lockTaskModeStateAfterStop: Int, isDeviceOwner: Boolean): Escalation = when {
        lockTaskModeStateAfterStop == ActivityManager.LOCK_TASK_MODE_NONE -> Escalation.NONE_NEEDED
        isDeviceOwner -> Escalation.DEVICE_OWNER_RELEASE
        else -> Escalation.GIVE_UP
    }
}
