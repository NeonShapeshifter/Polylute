package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.mode.DedicatedProvisioningEvidence
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeRequest
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeResolver
import org.junit.jupiter.api.Test

/**
 * F-08 (owner, 2026-10-07): after changing the default HOME app and setting the app back as HOME,
 * Maintenance could not be entered until the phone was rebooted. Maintenance is the owner's way out:
 * it must not be possible for it to become unreachable.
 */
class DapMaintenanceEntryPolicyTest {

    private val none = ActivityManager.LOCK_TASK_MODE_NONE
    private val locked = ActivityManager.LOCK_TASK_MODE_LOCKED
    private val pinned = ActivityManager.LOCK_TASK_MODE_PINNED

    @Test
    fun `nothing locked means there is nothing to release`() {
        assertThat(DapMaintenanceEntryPolicy.plan(none)).isEqualTo(DapMaintenanceEntryPolicy.Plan.BEGIN_SESSION)
    }

    @Test
    fun `any Lock Task state is released first`() {
        assertThat(DapMaintenanceEntryPolicy.plan(locked)).isEqualTo(DapMaintenanceEntryPolicy.Plan.RELEASE_LOCK_TASK)
        assertThat(DapMaintenanceEntryPolicy.plan(pinned)).isEqualTo(DapMaintenanceEntryPolicy.Plan.RELEASE_LOCK_TASK)
    }

    @Test
    fun `the plan reads the lock, never the product mode`() {
        // The sequence that stranded the owner: Restore HONOR HOME clears the containment flag, the Lock
        // Task allowlist and the persistent HOME, so the resolver reads "App"...
        val afterRecovery = ProductModeResolver.resolve(
            requestedMode = ProductModeRequest.DEDICATED,
            evidence = DedicatedProvisioningEvidence(
                isDeviceOwner = true,
                isPixelPlayerHome = false,
                isDedicatedModeEnabled = false,
                isLockTaskPermitted = false,
            ),
        )
        assertThat(afterRecovery.effectiveMode).isNotEqualTo(EffectiveProductMode.DEDICATED_ACTIVE)
        // ...yet if the activity is still held by Lock Task, Maintenance has to release it. The old gate
        // ("not dedicated, so nothing to suspend") began the session and released nothing.
        assertThat(DapMaintenanceEntryPolicy.plan(locked)).isEqualTo(DapMaintenanceEntryPolicy.Plan.RELEASE_LOCK_TASK)
    }

    @Test
    fun `selecting the app as HOME again looks dedicated while the containment is still cleared`() {
        // "Select this app as HOME" sets only the HOME (and persistent HOME). The resolver counts HOME
        // as proof of provisioning, so the mode says dedicated while the flag and the allowlist are
        // still the ones the recovery cleared: the half-state a HOME change leaves until something
        // reconciles it (DapHomeChangeTracker is what makes that happen without a reboot).
        val half = DedicatedProvisioningEvidence(
            isDeviceOwner = true,
            isPixelPlayerHome = true,
            isDedicatedModeEnabled = false,
            isLockTaskPermitted = false,
        )
        val state = ProductModeResolver.resolve(ProductModeRequest.DEDICATED, half)
        assertThat(state.effectiveMode).isEqualTo(EffectiveProductMode.DEDICATED_ACTIVE)
        assertThat(DapContainmentPolicy.shouldEnterLockTask(
            dedicatedModeEnabled = half.isDedicatedModeEnabled,
            maintenanceSessionActive = false,
            isDeviceOwner = true,
            isLockTaskPermitted = half.isLockTaskPermitted,
            lockTaskModeState = none,
        )).isFalse()
    }

    @Test
    fun `a stop that worked needs no escalation`() {
        assertThat(DapMaintenanceEntryPolicy.escalation(none, isDeviceOwner = true))
            .isEqualTo(DapMaintenanceEntryPolicy.Escalation.NONE_NEEDED)
        assertThat(DapMaintenanceEntryPolicy.escalation(none, isDeviceOwner = false))
            .isEqualTo(DapMaintenanceEntryPolicy.Escalation.NONE_NEEDED)
    }

    @Test
    fun `a stop that did not work falls back to Device Owner's allowlist`() {
        assertThat(DapMaintenanceEntryPolicy.escalation(locked, isDeviceOwner = true))
            .isEqualTo(DapMaintenanceEntryPolicy.Escalation.DEVICE_OWNER_RELEASE)
        assertThat(DapMaintenanceEntryPolicy.escalation(pinned, isDeviceOwner = true))
            .isEqualTo(DapMaintenanceEntryPolicy.Escalation.DEVICE_OWNER_RELEASE)
    }

    @Test
    fun `without Device Owner there is nothing to fall back to`() {
        assertThat(DapMaintenanceEntryPolicy.escalation(locked, isDeviceOwner = false))
            .isEqualTo(DapMaintenanceEntryPolicy.Escalation.GIVE_UP)
    }
}
