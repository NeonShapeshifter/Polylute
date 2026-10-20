package com.lostf1sh.pixelplayeross.dap.admin

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * F-08: the appliance policy was reconciled at activity creation, boot and package replacement only.
 * Changing the default HOME app does none of those, so the reconciliation never ran again until a
 * reboot. The tracker is what says "look again".
 */
class DapHomeChangeTrackerTest {

    private val dedicated = DapHomeChangeTracker.Evidence(
        isDeviceOwner = true,
        isPixelPlayerHome = true,
        isDedicatedModeEnabled = true,
        isLockTaskPermitted = true,
    )

    @Test
    fun `the first look only records, the activity's own start has reconciled`() {
        assertThat(DapHomeChangeTracker().changed(dedicated)).isFalse()
    }

    @Test
    fun `an unchanged look is not a change, so resume stays cheap`() {
        val tracker = DapHomeChangeTracker()
        tracker.changed(dedicated)
        repeat(5) { assertThat(tracker.changed(dedicated)).isFalse() }
    }

    @Test
    fun `HOME going away and coming back is two changes, and the second is the one that was missed`() {
        val tracker = DapHomeChangeTracker()
        tracker.changed(dedicated)
        // The default HOME is changed to the OEM launcher (and the recovery cleared the policy).
        val released = DapHomeChangeTracker.Evidence(true, false, false, false)
        assertThat(tracker.changed(released)).isTrue()
        // The owner sets the app back as HOME: only HOME is back, flag and allowlist are not.
        val reselected = DapHomeChangeTracker.Evidence(true, true, false, false)
        assertThat(tracker.changed(reselected)).isTrue()
        // Once reconciled, the evidence settles and the tracker goes quiet again.
        assertThat(tracker.changed(dedicated)).isTrue()
        assertThat(tracker.changed(dedicated)).isFalse()
    }

    @Test
    fun `each fact the policy reads is watched`() {
        for (changed in listOf(
            dedicated.copy(isDeviceOwner = false),
            dedicated.copy(isPixelPlayerHome = false),
            dedicated.copy(isDedicatedModeEnabled = false),
            dedicated.copy(isLockTaskPermitted = false),
        )) {
            val tracker = DapHomeChangeTracker()
            tracker.changed(dedicated)
            assertThat(tracker.changed(changed)).isTrue()
        }
    }

    // ── B34: an app pin is a change; the Maintenance lift of a Device Owner device is not ───────────────────────────────────────────

    private fun owner(lockTaskModeState: Int, deviceOwner: Boolean = true) = DapDeviceOwnerState(
        isDeviceOwner = deviceOwner,
        isAdminActive = deviceOwner,
        isLockTaskPermitted = deviceOwner,
        isPixelPlayerHome = true,
        isDedicatedModeEnabled = deviceOwner,
        lockTaskModeState = lockTaskModeState,
        lockTaskFeatures = 0,
    )

    @Test
    fun `Lock Task going LOCKED to NONE and back is not a change, so the System page's lift is not reconciled behind its back`() {
        val tracker = DapHomeChangeTracker()
        tracker.changed(owner(android.app.ActivityManager.LOCK_TASK_MODE_LOCKED))
        assertThat(tracker.changed(owner(android.app.ActivityManager.LOCK_TASK_MODE_NONE))).isFalse()
        assertThat(tracker.changed(owner(android.app.ActivityManager.LOCK_TASK_MODE_LOCKED))).isFalse()
    }

    @Test
    fun `a pin starting and ending is a change each time`() {
        val tracker = DapHomeChangeTracker()
        tracker.changed(owner(android.app.ActivityManager.LOCK_TASK_MODE_NONE, deviceOwner = false))
        assertThat(tracker.changed(owner(android.app.ActivityManager.LOCK_TASK_MODE_PINNED, deviceOwner = false))).isTrue()
        assertThat(tracker.changed(owner(android.app.ActivityManager.LOCK_TASK_MODE_PINNED, deviceOwner = false))).isFalse()
        assertThat(tracker.changed(owner(android.app.ActivityManager.LOCK_TASK_MODE_NONE, deviceOwner = false))).isTrue()
    }
}
