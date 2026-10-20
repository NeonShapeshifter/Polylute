package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DapContainmentPolicyTest {
    @Test
    fun `required features retain keyguard global actions and read-only system info`() {
        // The mask is now a function of product state rather than a fixed constant: with the DAP
        // Lock Screen enabled the appliance owns the wake surface, so the Keyguard bit is absent.
        // Global actions and read-only system info are retained in BOTH cases — this pass removes
        // exactly one bit and preserves every other intended feature.
        val withKeyguard = DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
        val appliance = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO

        assertEquals(withKeyguard, DapContainmentPolicy.requiredLockTaskFeatures(false))
        assertEquals(appliance, DapContainmentPolicy.requiredLockTaskFeatures(true))

        listOf(withKeyguard, appliance).forEach { mask ->
            assertEquals(
                0,
                mask and (DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS),
            )
        }
    }

    @Test
    fun `enters only when every containment precondition is true`() {
        assertTrue(
            DapContainmentPolicy.shouldEnterLockTask(
                dedicatedModeEnabled = true,
                maintenanceSessionActive = false,
                isDeviceOwner = true,
                isLockTaskPermitted = true,
                lockTaskModeState = ActivityManager.LOCK_TASK_MODE_NONE,
            ),
        )
    }

    @Test
    fun `authenticated maintenance prevents lock task entry`() {
        assertFalse(shouldEnter(maintenanceSessionActive = true))
    }

    @Test
    fun `inactive dedicated mode prevents lock task entry`() {
        assertFalse(shouldEnter(dedicatedModeEnabled = false))
    }

    @Test
    fun `already locked activity does not request lock task again`() {
        assertFalse(shouldEnter(lockTaskModeState = ActivityManager.LOCK_TASK_MODE_LOCKED))
    }

    @Test
    fun `stable owner policy is ready for immediate Maintenance relock`() {
        assertTrue(DapContainmentPolicy.isRelockPolicyReady(ownerState()))
    }

    @Test
    fun `relock policy requires every recovery invariant`() {
        assertFalse(DapContainmentPolicy.isRelockPolicyReady(ownerState(isDeviceOwner = false)))
        assertFalse(DapContainmentPolicy.isRelockPolicyReady(ownerState(isPixelPlayerHome = false)))
        assertFalse(DapContainmentPolicy.isRelockPolicyReady(ownerState(isDedicatedModeEnabled = false)))
        assertFalse(DapContainmentPolicy.isRelockPolicyReady(ownerState(isLockTaskPermitted = false)))
        assertFalse(DapContainmentPolicy.isRelockPolicyReady(ownerState(lockTaskFeatures = 0)))
    }

    @Test
    fun `a persistent HOME the listener's exit cleared is not ready, even with the flag and the allowlist back`() {
        assertTrue(DapContainmentPolicy.isRelockPolicyReady(ownerState(), persistentHomeEnabled = true))
        assertFalse(DapContainmentPolicy.isRelockPolicyReady(ownerState(), persistentHomeEnabled = false))
    }

    private fun shouldEnter(
        dedicatedModeEnabled: Boolean = true,
        maintenanceSessionActive: Boolean = false,
        isDeviceOwner: Boolean = true,
        isLockTaskPermitted: Boolean = true,
        lockTaskModeState: Int = ActivityManager.LOCK_TASK_MODE_NONE,
    ) = DapContainmentPolicy.shouldEnterLockTask(
        dedicatedModeEnabled,
        maintenanceSessionActive,
        isDeviceOwner,
        isLockTaskPermitted,
        lockTaskModeState,
    )

    private fun ownerState(
        isDeviceOwner: Boolean = true,
        isPixelPlayerHome: Boolean = true,
        isDedicatedModeEnabled: Boolean = true,
        isLockTaskPermitted: Boolean = true,
        lockTaskFeatures: Int = DapContainmentPolicy.requiredLockTaskFeatures(false),
    ) = DapDeviceOwnerState(
        isDeviceOwner = isDeviceOwner,
        isAdminActive = true,
        isLockTaskPermitted = isLockTaskPermitted,
        isPixelPlayerHome = isPixelPlayerHome,
        isDedicatedModeEnabled = isDedicatedModeEnabled,
        lockTaskModeState = ActivityManager.LOCK_TASK_MODE_NONE,
        lockTaskFeatures = lockTaskFeatures,
    )
}
