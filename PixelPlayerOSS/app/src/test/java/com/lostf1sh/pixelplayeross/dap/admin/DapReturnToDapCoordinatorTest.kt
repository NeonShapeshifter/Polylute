package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.mode.DedicatedProvisioningEvidence
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeRequest
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeResolver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B32-fix1 R3: "Volver al DAP" after the HONOR launcher was chosen really returns to dedicated mode (R32-03).
 *
 * [Device] is a Device Owner device in the respects that matter: the three pieces of evidence the product mode is inferred from (the
 * persistent HOME, the dedicated flag, the Lock Task allowlist), a Lock Task state, and a `startLockTask()` that works or does not. The
 * mode is read by the real [ProductModeResolver] and Lock Task is entered by the rule the activity uses ([DapContainmentPolicy.shouldEnterLockTask]),
 * so what is under test is the sequence and the answers, not a restatement of them. `enableDedicatedMode()` is written with the same three
 * steps `DapDeviceOwnerController.enableDedicatedMode` takes (HOME, allowlist, the flag only if both held).
 */
class DapReturnToDapCoordinatorTest {

    private val none = ActivityManager.LOCK_TASK_MODE_NONE
    private val locked = ActivityManager.LOCK_TASK_MODE_LOCKED

    private class Device(
        val isDeviceOwner: Boolean = true,
        var home: Boolean = true,
        var flag: Boolean = true,
        var permitted: Boolean = true,
        var lockState: Int = ActivityManager.LOCK_TASK_MODE_LOCKED,
        val homeCanBeApplied: Boolean = true,
        val lockTaskStarts: Boolean = true,
        var requestedMode: ProductModeRequest = ProductModeRequest.DEDICATED,
        var windowOpen: Boolean = false,
    ) {
        val events = mutableListOf<String>()

        /** The state after "Restaurar HONOR Launcher como HOME" was confirmed and HONOR was chosen: the whole DAP policy is cleared. */
        fun afterHonorRecovery() = apply {
            home = false
            flag = false
            permitted = false
            lockState = ActivityManager.LOCK_TASK_MODE_NONE
            windowOpen = false // prepareHonorHomeRecovery ends its window
        }

        fun mode() = ProductModeResolver.resolve(
            requestedMode,
            DedicatedProvisioningEvidence(isDeviceOwner, isPixelPlayerHome = home, isDedicatedModeEnabled = flag, isLockTaskPermitted = permitted),
        )

        val ready get() = isDeviceOwner && home && flag && permitted

        /** `DapDeviceOwnerController.ensureDedicatedModeReady()`: nothing is rewritten when it is all in place. */
        fun ensureReady(): Boolean {
            if (ready) return true
            events += "enableDedicatedMode"
            if (!isDeviceOwner) return false
            val homeApplied = homeCanBeApplied.also { home = it }
            permitted = true
            if (homeApplied && permitted) flag = true
            return homeApplied && permitted
        }

        /** `MainActivity.maybeEnterDedicatedMode()`: only if the product mode says dedicated and the policy rule allows it. */
        fun enterLockTask() {
            events += "requestLockTask"
            if (!mode().isDedicatedActive) return
            val should = DapContainmentPolicy.shouldEnterLockTask(
                dedicatedModeEnabled = flag,
                maintenanceSessionActive = windowOpen,
                isDeviceOwner = isDeviceOwner,
                isLockTaskPermitted = permitted,
                lockTaskModeState = lockState,
            )
            if (should && lockTaskStarts) lockState = ActivityManager.LOCK_TASK_MODE_LOCKED
        }

        fun coordinator() = DapReturnToDapCoordinator(
            isDeviceOwner = { isDeviceOwner },
            reassertPolicy = { ensureReady() },
            isDedicatedActive = { mode().isDedicatedActive },
            endWindow = { events += "endWindow"; windowOpen = false },
            prepareLockTaskTransition = { events += "prepareTransition" },
            requestLockTask = { enterLockTask() },
            awaitLocked = { events += "awaitLocked"; lockState == ActivityManager.LOCK_TASK_MODE_LOCKED },
            recoverContainment = { events += "recoverContainment" },
        )
    }

    // --- HONOR, then an explicit return ----------------------------------------------------------------------------------------

    @Test
    fun `after the HONOR recovery the device reads as not dedicated, which is why the old exit had nothing to do`() {
        val device = Device().afterHonorRecovery()
        assertThat(device.mode().isDedicatedActive).isFalse()
        assertThat(device.home).isFalse()
        assertThat(device.flag).isFalse()
        assertThat(device.permitted).isFalse()
    }

    @Test
    fun `HONOR then an explicit return puts HOME, the flag and the allowlist back, locks the device, and only then says contained`() = runTest {
        val device = Device().afterHonorRecovery()

        assertThat(device.coordinator().returnToDap()).isEqualTo(DapReturnOutcome.CONTAINED)

        // the dedicated evidence is back, read by the resolver the whole app reads
        assertThat(device.home).isTrue()
        assertThat(device.flag).isTrue()
        assertThat(device.permitted).isTrue()
        assertThat(device.mode().isDedicatedActive).isTrue()
        assertThat(device.lockState).isEqualTo(locked)
        // the order: policy, then the window, then Lock Task, then the check; nothing is claimed before the check
        assertThat(device.events).containsExactly(
            "enableDedicatedMode", "endWindow", "prepareTransition", "requestLockTask", "awaitLocked",
        ).inOrder()
    }

    @Test
    fun `success is not reported when Lock Task was not observed, and the usual recovery is tried`() = runTest {
        val device = Device(lockTaskStarts = false).afterHonorRecovery()

        assertThat(device.coordinator().returnToDap()).isEqualTo(DapReturnOutcome.NOT_LOCKED)

        assertThat(device.lockState).isEqualTo(none)
        assertThat(device.events.last()).isEqualTo("recoverContainment")
        // the policy is back: a retry does not have to rewrite it
        assertThat(device.ready).isTrue()
    }

    @Test
    fun `a policy that cannot be applied is said, locks nothing, and leaves the window alone`() = runTest {
        val device = Device(homeCanBeApplied = false).afterHonorRecovery()
        device.windowOpen = true

        assertThat(device.coordinator().returnToDap()).isEqualTo(DapReturnOutcome.POLICY_NOT_APPLIED)

        assertThat(device.lockState).isEqualTo(none)
        assertThat(device.events).doesNotContain("requestLockTask")
        assertThat(device.events).doesNotContain("endWindow")
    }

    @Test
    fun `a policy that was written but is still not dedicated is not success either`() = runTest {
        // the allowlist and the flag are back but HOME did not take (another launcher still resolves as HOME)
        val device = Device().afterHonorRecovery()
        val coordinator = DapReturnToDapCoordinator(
            isDeviceOwner = { true },
            reassertPolicy = { true },
            isDedicatedActive = { device.mode().isDedicatedActive },
            endWindow = { device.events += "endWindow" },
            prepareLockTaskTransition = { device.events += "prepareTransition" },
            requestLockTask = { device.enterLockTask() },
            awaitLocked = { device.lockState == locked },
            recoverContainment = { device.events += "recoverContainment" },
        )

        assertThat(coordinator.returnToDap()).isEqualTo(DapReturnOutcome.POLICY_NOT_APPLIED)
        assertThat(device.events).doesNotContain("requestLockTask")
    }

    // --- the App Mode control ------------------------------------------------------------------------------------------------

    @Test
    fun `App Mode has nothing to contain - no policy is written and Lock Task is not requested`() = runTest {
        val device = Device(isDeviceOwner = false, home = false, flag = false, permitted = false, lockState = none, requestedMode = ProductModeRequest.APP)
        device.windowOpen = true

        assertThat(device.coordinator().returnToDap()).isEqualTo(DapReturnOutcome.NOTHING_TO_CONTAIN)

        assertThat(device.events).containsExactly("endWindow")
        assertThat(device.lockState).isEqualTo(none)
        assertThat(device.windowOpen).isFalse()
    }

    // --- an ordinary dedicated device ----------------------------------------------------------------------------------------

    @Test
    fun `a device that is already dedicated and locked is contained without rewriting anything`() = runTest {
        val device = Device()

        assertThat(device.coordinator().returnToDap()).isEqualTo(DapReturnOutcome.CONTAINED)

        assertThat(device.events).doesNotContain("enableDedicatedMode")
        assertThat(device.lockState).isEqualTo(locked)
    }

    @Test
    fun `a dedicated device whose Lock Task was lifted is locked again`() = runTest {
        val device = Device(lockState = none)
        device.windowOpen = true

        assertThat(device.coordinator().returnToDap()).isEqualTo(DapReturnOutcome.CONTAINED)

        assertThat(device.windowOpen).isFalse()
        assertThat(device.lockState).isEqualTo(locked)
    }

    // --- choosing HONOR for good ---------------------------------------------------------------------------------------------

    @Test
    fun `choosing HONOR permanently is not undone by the app coming back, by an expiry or by time`() {
        val device = Device().afterHonorRecovery()
        // the app is resumed (the window was ended by the recovery) and nothing else runs
        val window = MaintenanceSessionController()
        assertThat(DapSystemSurfaceCoordinator.reconcileOnResume(window, 1_000L)).isEqualTo(WindowReturn.NONE)
        assertThat(
            DapContainmentPolicy.shouldEnterLockTask(
                dedicatedModeEnabled = device.flag,
                maintenanceSessionActive = false,
                isDeviceOwner = device.isDeviceOwner,
                isLockTaskPermitted = device.permitted,
                lockTaskModeState = device.lockState,
            ),
        ).isFalse()
        assertThat(device.mode().isDedicatedActive).isFalse()
        assertThat(device.events).isEmpty()
    }
}
