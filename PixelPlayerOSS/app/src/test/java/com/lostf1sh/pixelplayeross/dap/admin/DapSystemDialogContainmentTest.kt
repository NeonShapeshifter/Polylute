package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Phase 13.1 — the Lock Task / system-dialog interaction.
 *
 * The defect this pins was measured on the HONOR LGN-NX3, not reasoned about: with Lock Task
 * holding the task list, the system's `UsbPermissionActivity` launch was issued and immediately
 * rejected (`dumpsys activity starter` → `mLastStartActivityResult=101`, the record left at
 * `launchCount=0 ... windows=[]`). No dialog was drawn, the permission callback never fired, and
 * `UsbManager.hasPermission` stayed false — so the Direct route was unreachable, permanently.
 *
 * Releasing Lock Task by hand made the dialog appear at once and the grant landed. The fix is
 * therefore not to defeat the guard but to ask for the suspension the container already knows how
 * to take for required system UI.
 */
class DapSystemDialogContainmentTest {

    private val none = ActivityManager.LOCK_TASK_MODE_NONE
    private val locked = ActivityManager.LOCK_TASK_MODE_LOCKED
    private val pinned = ActivityManager.LOCK_TASK_MODE_PINNED

    @Test
    fun `a required system dialog suspends lock task while the appliance is contained`() {
        assertThat(
            DapContainmentPolicy.needsLockTaskSuspensionForSystemDialog(
                dedicatedModeEnabled = true,
                lockTaskModeState = locked,
            )
        ).isTrue()
    }

    @Test
    fun `the pinned state needs suspension too`() {
        assertThat(
            DapContainmentPolicy.needsLockTaskSuspensionForSystemDialog(
                dedicatedModeEnabled = true,
                lockTaskModeState = pinned,
            )
        ).isTrue()
    }

    /** With no Lock Task active there is nothing to suspend — the dialog can be raised directly. */
    @Test
    fun `outside lock task nothing needs suspending`() {
        assertThat(
            DapContainmentPolicy.needsLockTaskSuspensionForSystemDialog(
                dedicatedModeEnabled = true,
                lockTaskModeState = none,
            )
        ).isFalse()
    }

    @Test
    fun `app mode never suspends containment for a dialog`() {
        assertThat(
            DapContainmentPolicy.needsLockTaskSuspensionForSystemDialog(
                dedicatedModeEnabled = false,
                lockTaskModeState = none,
            )
        ).isFalse()
    }

    /**
     * Dedicated mode is the product setting; the lock state is the runtime fact. A stale dedicated
     * flag with no lock held must not cause a pointless suspension cycle.
     */
    @Test
    fun `a stale dedicated flag with no lock held does not suspend`() {
        assertThat(
            DapContainmentPolicy.needsLockTaskSuspensionForSystemDialog(
                dedicatedModeEnabled = true,
                lockTaskModeState = none,
            )
        ).isFalse()
    }

    @Test
    fun `the decision is pure and repeatable`() {
        repeat(3) {
            assertThat(
                DapContainmentPolicy.needsLockTaskSuspensionForSystemDialog(true, locked)
            ).isEqualTo(
                DapContainmentPolicy.needsLockTaskSuspensionForSystemDialog(true, locked)
            )
        }
    }

    /**
     * Lock Task entry is unaffected: the dialog path must not have loosened when containment is
     * (re)asserted. These are the standing conditions and they must still hold exactly.
     */
    @Test
    fun `lock task is still entered only under the established conditions`() {
        assertThat(
            DapContainmentPolicy.shouldEnterLockTask(
                dedicatedModeEnabled = true,
                maintenanceSessionActive = false,
                isDeviceOwner = true,
                isLockTaskPermitted = true,
                lockTaskModeState = none,
            )
        ).isTrue()
        assertThat(
            DapContainmentPolicy.shouldEnterLockTask(
                dedicatedModeEnabled = true,
                maintenanceSessionActive = true,
                isDeviceOwner = true,
                isLockTaskPermitted = true,
                lockTaskModeState = none,
            )
        ).isFalse()
        assertThat(
            DapContainmentPolicy.shouldEnterLockTask(
                dedicatedModeEnabled = true,
                maintenanceSessionActive = false,
                isDeviceOwner = true,
                isLockTaskPermitted = true,
                lockTaskModeState = locked,
            )
        ).isFalse()
    }
}
