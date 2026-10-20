package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

/**
 * B32-Q6: the System page's openers (Android Settings, Wi-Fi, Bluetooth, USB, the HOME selector) lift Lock Task the way Maintenance
 * did, in the same order, and give it back the way Maintenance's exit did.
 *
 * [FakeDevice] is a Device Owner device in the one respect that matters here: a Lock Task state, an allowlist, and a `stopLockTask()`
 * that works or does not. Its `lift()` is the activity's own sequence written with the same policy calls (`DapMaintenanceEntryPolicy`),
 * so the ordering under test is the real one; the activity's wiring of it is pinned at source level in `B32MaintenanceBoundaryTest`.
 */
class DapSystemSurfaceCoordinatorTest {

    private val none = ActivityManager.LOCK_TASK_MODE_NONE
    private val locked = ActivityManager.LOCK_TASK_MODE_LOCKED

    private class FakeDevice(
        var lockState: Int,
        val isDeviceOwner: Boolean = true,
        val stopLockTaskWorks: Boolean = true,
        val surfaceStarts: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        var allowlistCleared = false

        /** `MainActivity.releaseLockTaskForMaintenance()`, with the policy it asks. */
        fun lift(): Boolean {
            if (lockState != ActivityManager.LOCK_TASK_MODE_NONE) {
                events += "stopLockTask"
                if (stopLockTaskWorks) lockState = ActivityManager.LOCK_TASK_MODE_NONE
            }
            if (lockState == ActivityManager.LOCK_TASK_MODE_NONE) return true
            return when (DapMaintenanceEntryPolicy.escalation(lockState, isDeviceOwner)) {
                DapMaintenanceEntryPolicy.Escalation.NONE_NEEDED -> true
                DapMaintenanceEntryPolicy.Escalation.GIVE_UP -> false
                DapMaintenanceEntryPolicy.Escalation.DEVICE_OWNER_RELEASE -> {
                    events += "releaseAllowlist"
                    allowlistCleared = true
                    lockState = ActivityManager.LOCK_TASK_MODE_NONE
                    true
                }
            }
        }

        fun coordinator() = DapSystemSurfaceCoordinator(
            lockTaskModeState = { lockState },
            beginWindow = { events += "window" },
            liftLockTask = { lift() },
            start = { events += "start"; surfaceStarts },
            abort = { events += "abort" },
            putContainmentBack = { events += "exit"; true },
        )
    }

    @Test
    fun `a locked device is lifted before the surface starts, and the window is open first`() = runTest {
        val device = FakeDevice(lockState = locked)

        assertThat(device.coordinator().open()).isEqualTo(SystemSurfaceOutcome.OPENED)

        assertThat(device.events).containsExactly("window", "stopLockTask", "start").inOrder()
        assertThat(device.lockState).isEqualTo(none)
        assertThat(device.allowlistCleared).isFalse()
    }

    @Test
    fun `a Lock Task that will not stop is taken out of the Device Owner allowlist, as Maintenance does`() = runTest {
        val device = FakeDevice(lockState = locked, stopLockTaskWorks = false)

        assertThat(device.coordinator().open()).isEqualTo(SystemSurfaceOutcome.OPENED)

        assertThat(device.events).containsExactly("window", "stopLockTask", "releaseAllowlist", "start").inOrder()
        assertThat(device.allowlistCleared).isTrue()
    }

    @Test
    fun `without Device Owner and without a lock that will stop, nothing is started and the containment is put back`() = runTest {
        val device = FakeDevice(lockState = locked, isDeviceOwner = false, stopLockTaskWorks = false)

        assertThat(device.coordinator().open()).isEqualTo(SystemSurfaceOutcome.NOT_LIFTED)

        assertThat(device.events).containsExactly("window", "stopLockTask", "abort").inOrder()
        assertThat(device.events).doesNotContain("start")
    }

    @Test
    fun `an unlocked device has nothing to lift, App Mode and a half-provisioned device alike`() = runTest {
        val device = FakeDevice(lockState = none, isDeviceOwner = false)

        assertThat(device.coordinator().open()).isEqualTo(SystemSurfaceOutcome.OPENED)

        // read from the Lock Task state Android reports, never from the product mode (F-08)
        assertThat(device.events).containsExactly("window", "start").inOrder()
    }

    @Test
    fun `a surface that will not start after the lift goes back through the Maintenance exit`() = runTest {
        val device = FakeDevice(lockState = locked, surfaceStarts = false)

        assertThat(device.coordinator().open()).isEqualTo(SystemSurfaceOutcome.NOT_STARTED)

        assertThat(device.events).containsExactly("window", "stopLockTask", "start", "exit").inOrder()
    }

    @Test
    fun `an activity cancelled in the middle of the lift closes the window and puts the containment back before it goes`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = DapSystemSurfaceCoordinator(
            lockTaskModeState = { locked },
            beginWindow = { events += "window" },
            liftLockTask = { events += "lifting"; awaitCancellation() },
            start = { events += "start"; true },
            abort = { events += "abort" },
            putContainmentBack = { events += "exit"; true },
        )

        val job = launch { coordinator.open() }
        runCurrent()
        job.cancelAndJoin()

        // the activity is being recreated: the window it opened is closed, not inherited by the next one, and no surface was started
        assertThat(events).containsExactly("window", "lifting", "abort").inOrder()
    }

    @Test
    fun `an activity cancelled in the middle of the lift while the abort itself suspends still finishes the abort`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = DapSystemSurfaceCoordinator(
            lockTaskModeState = { locked },
            beginWindow = { events += "window" },
            liftLockTask = { awaitCancellation() },
            start = { true },
            abort = { yield(); events += "abort-finished" },
            putContainmentBack = { true },
        )

        val job = launch { coordinator.open() }
        runCurrent()
        job.cancelAndJoin()

        assertThat(events).containsExactly("window", "abort-finished").inOrder()
    }

    @Test
    fun `the app coming back closes only a window the System page opened, and only when a surface was out`() {
        assertThat(DapSystemSurfaceCoordinator.restoresOnReturn(externalFlowWasOpen = true, restoreOnReturn = true)).isTrue()
        // an ordinary resume (screen on, a dialog closing): no exit nobody asked for
        assertThat(DapSystemSurfaceCoordinator.restoresOnReturn(externalFlowWasOpen = false, restoreOnReturn = true)).isFalse()
        // the HOME recovery leaves the policy the owner asked for and puts nothing back
        assertThat(DapSystemSurfaceCoordinator.restoresOnReturn(externalFlowWasOpen = true, restoreOnReturn = false)).isFalse()
    }

    @Test
    fun `the window is the existing one - no state asks for a PIN, and a surface may stay out past the idle limit`() {
        val window = MaintenanceSessionController()
        window.begin(0L)
        assertThat(window.beginExternalFlow(1_000L)).isTrue()
        // Android Settings left open for longer than the idle limit is still a valid window on return (the absolute limit decides)
        assertThat(window.returnFromExternalFlow(MaintenanceSessionController.IDLE_TIMEOUT_MS + 1_000L)).isTrue()
    }
}
