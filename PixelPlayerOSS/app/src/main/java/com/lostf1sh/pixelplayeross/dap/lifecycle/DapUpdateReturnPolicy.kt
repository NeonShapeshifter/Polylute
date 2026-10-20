package com.lostf1sh.pixelplayeross.dap.lifecycle

import android.app.ActivityManager
import com.lostf1sh.pixelplayeross.dap.admin.DapPolicyRestoreResult

/**
 * Whether the DAP comes back by itself after its own package was replaced (B34-fix2 R3).
 *
 * Replacing the package (a Play update, `adb install -r`) kills the process and with it the task Lock Task was holding. The restore the
 * receiver runs puts the policy back, but nothing started an activity: what showed was whatever the system had in front of it (the
 * HONOR's Internet panel, then its Recents) with Lock Task at NONE, until the HOME activity was started by hand. A Device Owner may start
 * its own activity from the broadcast; this says when it should.
 *
 * Only for a device that is Dedicated *and* contained again: the restore found a Device Owner whose policy is in force, Polylute is the
 * home app, and Android reads nothing locked. It never starts anything on a phone (no Device Owner, so the restore reports none), nor on
 * a boot (the system starts the home app itself), nor for a device whose listener chose another launcher: that choice leaves no
 * Dedicated evidence ([com.lostf1sh.pixelplayeross.dap.admin.DapContainmentReconciler]), so the restore reports no owner and Polylute is not
 * the home app, and an exit still waiting for the picker reports the policy as not in force.
 */
object DapUpdateReturnPolicy {
    fun shouldStartHome(
        trigger: DapRecoveryTrigger,
        restore: DapPolicyRestoreResult,
        isPolyluteHome: Boolean,
        lockTaskModeState: Int,
    ): Boolean = trigger == DapRecoveryTrigger.MY_PACKAGE_REPLACED &&
        restore.isDeviceOwner &&
        restore.containmentConfigured &&
        isPolyluteHome &&
        lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE
}
