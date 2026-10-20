package com.lostf1sh.pixelplayeross.dap.admin

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Phase 13.1 — containment-flag reconciliation.
 *
 * The defect this pins was observed on the HONOR LGN-NX3: the appliance reported itself as being
 * in Dedicated Mode while Lock Task was not engaged at all.
 *
 * `ProductModeResolver` treats Device Owner + PixelPlayer-as-HOME as proof of provisioning and
 * reports `DEDICATED_ACTIVE`. But `DapContainmentPolicy.shouldEnterLockTask` gates on
 * `isDedicatedModeEnabled` — a separate persisted flag — and nothing reconciled the two. They
 * diverge whenever HOME ownership survives while the flag does not: a package replacement, an
 * uninstall/reinstall, or a HOME set through Android's settings rather than this app's setup.
 *
 * The decision is pure so the four cases can be enumerated without a device.
 *
 * B34-fix2: this file pins a private copy of the decision ([shouldReconcile] below), not the production code, and the copy has no notion of a
 * HOME exit the listener chose. The production decision is `DapContainmentReconciler`, tested on a stateful phone by `DapHomeExitTest`;
 * "Device Owner + home re-asserts a cleared flag" is still the rule for a policy that was lost, and is no longer applied while an exit is
 * waiting for Android's picker.
 */
class ContainmentFlagReconciliationTest {

    /** The condition under which the appliance may re-assert its own containment. */
    private fun shouldReconcile(
        isDeviceOwner: Boolean,
        isPixelPlayerHome: Boolean,
        flagAlreadySet: Boolean,
    ): Boolean = isDeviceOwner && isPixelPlayerHome && !flagAlreadySet

    @Test
    fun `home ownership on a device owner re-asserts a cleared flag`() {
        assertThat(
            shouldReconcile(isDeviceOwner = true, isPixelPlayerHome = true, flagAlreadySet = false)
        ).isTrue()
    }

    @Test
    fun `an already-set flag is left alone`() {
        assertThat(
            shouldReconcile(isDeviceOwner = true, isPixelPlayerHome = true, flagAlreadySet = true)
        ).isFalse()
    }

    /**
     * The release path. A deliberate exit clears HOME ownership first, so once the user's chosen
     * launcher owns HOME this must write nothing — otherwise recovery would fight the user and
     * re-contain a device they had released.
     */
    @Test
    fun `releasing home ownership prevents re-containment`() {
        assertThat(
            shouldReconcile(isDeviceOwner = true, isPixelPlayerHome = false, flagAlreadySet = false)
        ).isFalse()
    }

    @Test
    fun `without device owner nothing is asserted`() {
        assertThat(
            shouldReconcile(isDeviceOwner = false, isPixelPlayerHome = true, flagAlreadySet = false)
        ).isFalse()
    }

    @Test
    fun `without both conditions nothing is asserted`() {
        assertThat(
            shouldReconcile(isDeviceOwner = false, isPixelPlayerHome = false, flagAlreadySet = false)
        ).isFalse()
    }

    /**
     * Reconciliation must not loosen the Lock Task entry gate: the flag it writes is an input to
     * `shouldEnterLockTask`, and that rule is unchanged.
     */
    @Test
    fun `a reconciled flag still enters lock task only under the standing conditions`() {
        assertThat(
            DapContainmentPolicy.shouldEnterLockTask(
                dedicatedModeEnabled = true,
                maintenanceSessionActive = false,
                isDeviceOwner = true,
                isLockTaskPermitted = true,
                lockTaskModeState = android.app.ActivityManager.LOCK_TASK_MODE_NONE,
            )
        ).isTrue()
        assertThat(
            DapContainmentPolicy.shouldEnterLockTask(
                dedicatedModeEnabled = true,
                maintenanceSessionActive = false,
                isDeviceOwner = true,
                isLockTaskPermitted = true,
                lockTaskModeState = android.app.ActivityManager.LOCK_TASK_MODE_LOCKED,
            )
        ).isFalse()
    }

    @Test
    fun `the decision is pure and repeatable`() {
        repeat(3) {
            assertThat(shouldReconcile(true, true, false))
                .isEqualTo(shouldReconcile(true, true, false))
        }
    }
}
