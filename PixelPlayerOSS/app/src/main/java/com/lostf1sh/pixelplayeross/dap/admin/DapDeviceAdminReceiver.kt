package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/** Minimal standard DeviceAdminReceiver used only for the dedicated-DAP Device Owner role. */
class DapDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        DapLockTaskStateEvents.onStateChanged(ActivityManager.LOCK_TASK_MODE_LOCKED)
        Timber.tag("DapContainment").i("Device Admin observed Lock Task entering for %s", pkg)
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        DapLockTaskStateEvents.onStateChanged(ActivityManager.LOCK_TASK_MODE_NONE)
        Timber.tag("DapContainment").i("Device Admin observed Lock Task exiting")
    }
}
