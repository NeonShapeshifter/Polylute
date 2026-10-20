package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager

/** Pure containment decisions kept separate from Android lifecycle plumbing. */
object DapContainmentPolicy {
    /**
     * The Lock Task feature mask for the CURRENT product state.
     *
     * Delegates to [DapKeyguardPolicy], which is the single authority on whether the appliance or
     * the OEM owns the wake surface. Previously this was a fixed constant that always opted the
     * system Keyguard back in — the cause of the double-unlock wake flow.
     *
     * [lockScreenIntegrationKilled] carries the Maintenance feature kill through to the mask, so a
     * killed integration reconciles the device back to OEM-keyguard behaviour instead of leaving
     * the appliance holding a wake surface it no longer manages.
     */
    fun requiredLockTaskFeatures(
        dapLockScreenEnabled: Boolean,
        lockScreenIntegrationKilled: Boolean = false,
    ): Int = DapKeyguardPolicy.desiredLockTaskFeatures(
        dapLockScreenEnabled = dapLockScreenEnabled,
        lockScreenIntegrationKilled = lockScreenIntegrationKilled,
    )

    fun shouldEnterLockTask(
        dedicatedModeEnabled: Boolean,
        maintenanceSessionActive: Boolean,
        isDeviceOwner: Boolean,
        isLockTaskPermitted: Boolean,
        lockTaskModeState: Int,
    ): Boolean = dedicatedModeEnabled &&
        !maintenanceSessionActive &&
        isDeviceOwner &&
        isLockTaskPermitted &&
        lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE

    /**
     * Whether raising a required system dialog needs Lock Task suspended first.
     *
     * AOSP's `ActivityStarter` refuses to launch an activity while Lock Task holds the task list
     * unless the target is exempt — the system's `UsbPermissionActivity` is exempt only when it
     * carries `ATTR_IS_USB_PERMISSION_ACTIVITY`. Measured on the HONOR LGN-NX3, whose
     * `SystemUIOversea` variant does not, the USB permission launch is issued and immediately
     * rejected (`mLastStartActivityResult=101`, record left at `launchCount=0 ... windows=[]`):
     * the dialog is never drawn and the permission callback never fires.
     *
     * When Lock Task is not active there is nothing to suspend and the dialog can be raised
     * directly. This is a pure decision so the containment question can be tested without a device.
     */
    fun needsLockTaskSuspensionForSystemDialog(
        dedicatedModeEnabled: Boolean,
        lockTaskModeState: Int,
    ): Boolean = dedicatedModeEnabled &&
        lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    /**
     * B35-fix1 R1: which of the checks that say "this app's Lock Task policy is gone" failed after the release cleared it; empty when all passed.
     *
     * The three are what every API answers alike (Android 11 to 15): the keyguard hold was released, the app is no longer permitted, and the
     * admin's allowlist is empty. The Lock Task *mask* is deliberately not one of them: with no allowlist it means nothing, and the policy engine
     * of Android 14 and 15 keeps the mask in the same policy as the packages, so clearing the packages removes the mask too and
     * `getLockTaskFeatures` answers `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` where the legacy store answers the `NONE` that was written.
     */
    fun unclearedOwnerPolicies(
        keyguardReleased: Boolean,
        lockTaskPermitted: Boolean,
        lockTaskPackages: List<String>,
    ): List<String> = listOfNotNull(
        "keyguard hold".takeUnless { keyguardReleased },
        "isLockTaskPermitted".takeIf { lockTaskPermitted },
        "getLockTaskPackages".takeIf { lockTaskPackages.isNotEmpty() },
    )

    /**
     * Whether the DAP policy is all in place, so "Volver al DAP" and the Maintenance exit need not rewrite it.
     *
     * [persistentHomeEnabled] (B34-fix2): the persistent HOME the listener's own exit cleared. Polylute can be home through the listener's
     * default, with the flag and the allowlist back (that is what the reconcile restores when Polylute becomes home again), and still have
     * no persistent HOME; that is not "ready", or the way back would answer "nothing to do" and leave HOME to whoever the next picker names.
     */
    fun isRelockPolicyReady(
        state: DapDeviceOwnerState,
        dapLockScreenEnabled: Boolean = false,
        lockScreenIntegrationKilled: Boolean = false,
        persistentHomeEnabled: Boolean = true,
    ): Boolean = state.isDeviceOwner &&
        state.isPixelPlayerHome &&
        persistentHomeEnabled &&
        state.isDedicatedModeEnabled &&
        state.isLockTaskPermitted &&
        state.lockTaskFeatures == requiredLockTaskFeatures(
            dapLockScreenEnabled = dapLockScreenEnabled,
            lockScreenIntegrationKilled = lockScreenIntegrationKilled,
        )
}
