package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B32-fix1 R2: the restore after Android Settings (Wi-Fi, Bluetooth, USB) survives the activity that opened it.
 *
 * The obligation to put the containment back used to be a field of the activity instance. An activity destroyed and recreated while
 * the surface was out (a configuration change, the system reclaiming it) came back with the field at false, the resume found the window
 * in `AUTHENTICATED_ACTIVE` and left Lock Task off until the window expired (R32-02). It now lives in the window, which is
 * process-wide: [Activity] below is everything `MainActivity` keeps about it, which is nothing; it asks the window what the app coming
 * back owes ([DapSystemSurfaceCoordinator.reconcileOnResume]) and acts on the answer.
 *
 * `MainActivity` itself is a Hilt activity and cannot be built on the JVM (the auditor's probe instantiated it with reflection and no
 * `onCreate`); that it keeps no state of its own about the window, and that the resume asks the window, is pinned at source level in
 * `B32Fix1BoundaryTest`.
 */
class DapWindowReturnTest {

    private val minute = 60_000L

    /** One activity instance: it holds no window state, so two of them are the same thing over the same window. */
    private class Activity(private val window: MaintenanceSessionController) {
        val exits = mutableListOf<WindowReturn>()
        fun resume(nowElapsedMs: Long): WindowReturn =
            DapSystemSurfaceCoordinator.reconcileOnResume(window, nowElapsedMs).also {
                if (it == WindowReturn.RESTORE_AFTER_SURFACE || it == WindowReturn.RESTORE_AFTER_EXPIRY) {
                    exits += it
                    window.invalidate() // the Maintenance exit ends the window
                }
            }
    }

    private fun openedForASurface(): MaintenanceSessionController = MaintenanceSessionController().apply {
        begin(0L)
        assertThat(beginExternalFlow(1_000L, restoreOnReturn = true)).isTrue()
    }

    // --- open, recreate, return ----------------------------------------------------------------------------------------------

    @Test
    fun `the activity that opened the surface restores on return`() {
        val window = openedForASurface()
        val original = Activity(window)
        assertThat(original.resume(2 * minute)).isEqualTo(WindowReturn.RESTORE_AFTER_SURFACE)
        assertThat(window.snapshot.value.isAuthenticated).isFalse()
    }

    @Test
    fun `an activity recreated while the surface is out restores on return exactly as the first would have`() {
        val window = openedForASurface()
        val original = Activity(window)
        // the surface is out; the original is destroyed and a new one is created over the same process-wide window
        val replacement = Activity(window)
        assertThat(window.snapshot.value.restoreOnReturn).isTrue()
        assertThat(replacement.resume(2 * minute)).isEqualTo(WindowReturn.RESTORE_AFTER_SURFACE)
        assertThat(replacement.exits).containsExactly(WindowReturn.RESTORE_AFTER_SURFACE)
        // the original is gone; if it were somehow resumed too it would find nothing owed
        assertThat(original.resume(3 * minute)).isEqualTo(WindowReturn.NONE)
        assertThat(original.exits).isEmpty()
    }

    @Test
    fun `a configuration change and a second recreation lose nothing either`() {
        val window = openedForASurface()
        val before = window.snapshot.value
        // two recreations (a rotation and a locale change, say) while the surface is out: the window is not theirs to change
        Activity(window)
        Activity(window)
        assertThat(window.snapshot.value).isEqualTo(before)
        assertThat(Activity(window).resume(2 * minute)).isEqualTo(WindowReturn.RESTORE_AFTER_SURFACE)
    }

    @Test
    fun `the restore is asked once per return, not left standing for the next resume`() {
        val window = openedForASurface()
        // the first resume reads the obligation and the window moves on; the exit that follows can fail or take time, and a second
        // resume in the meantime must not run it again
        assertThat(DapSystemSurfaceCoordinator.reconcileOnResume(window, 2 * minute)).isEqualTo(WindowReturn.RESTORE_AFTER_SURFACE)
        assertThat(window.snapshot.value.restoreOnReturn).isFalse()
        assertThat(DapSystemSurfaceCoordinator.reconcileOnResume(window, 2 * minute + 100L)).isEqualTo(WindowReturn.KEEP_OPEN)
    }

    // --- expiry --------------------------------------------------------------------------------------------------------------

    @Test
    fun `a surface left out past the absolute limit is restored on return as an expiry`() {
        val window = openedForASurface()
        val activity = Activity(window)
        assertThat(activity.resume(MaintenanceSessionController.ABSOLUTE_TIMEOUT_MS + minute)).isEqualTo(WindowReturn.RESTORE_AFTER_EXPIRY)
    }

    @Test
    fun `a window that ran out of idle time before any surface was started is restored on the next resume`() {
        val window = MaintenanceSessionController().apply { begin(0L) }
        assertThat(Activity(window).resume(MaintenanceSessionController.IDLE_TIMEOUT_MS + minute)).isEqualTo(WindowReturn.RESTORE_AFTER_EXPIRY)
    }

    // --- what is never restored by mistake -----------------------------------------------------------------------------------

    @Test
    fun `a window that was ended owes nothing, so the HOME recovery is never put back by a resume`() {
        // the HONOR recovery ends its window as soon as it has cleared the policy; the owner is then in another launcher or back in the app
        val window = MaintenanceSessionController().apply { begin(0L) }
        window.invalidate()
        assertThat(Activity(window).resume(2 * minute)).isEqualTo(WindowReturn.NONE)
        assertThat(window.snapshot.value.restoreOnReturn).isFalse()
    }

    @Test
    fun `an external flow that no one asked a restore for returns to an open window and restores nothing`() {
        val window = MaintenanceSessionController().apply {
            begin(0L)
            beginExternalFlow(1_000L) // the default: no obligation
        }
        assertThat(Activity(window).resume(2 * minute)).isEqualTo(WindowReturn.KEEP_OPEN)
    }

    @Test
    fun `an ordinary resume with no window does nothing`() {
        assertThat(Activity(MaintenanceSessionController()).resume(minute)).isEqualTo(WindowReturn.NONE)
    }

    @Test
    fun `a window that is open and not out on a surface is kept, not restored`() {
        val window = MaintenanceSessionController().apply { begin(0L) }
        assertThat(Activity(window).resume(minute)).isEqualTo(WindowReturn.KEEP_OPEN)
    }

    // --- process death, on its own -------------------------------------------------------------------------------------------

    @Test
    fun `process death is a separate case - the new process has no window and no obligation, and the ordinary rule locks again`() {
        val oldProcess = openedForASurface()
        assertThat(oldProcess.snapshot.value.isAuthenticated).isTrue()

        // the process is gone with the window in it; the new one starts from nothing
        val newProcess = MaintenanceSessionController()
        assertThat(newProcess.snapshot.value).isEqualTo(MaintenanceSessionSnapshot())
        assertThat(Activity(newProcess).resume(10 * minute)).isEqualTo(WindowReturn.NONE)
        // nothing keeps Lock Task off any more: the lifecycle's own rule (a Device Owner, dedicated, permitted, not locked) enters it
        assertThat(
            DapContainmentPolicy.shouldEnterLockTask(
                dedicatedModeEnabled = true,
                maintenanceSessionActive = newProcess.snapshot.value.isAuthenticated,
                isDeviceOwner = true,
                isLockTaskPermitted = true,
                lockTaskModeState = ActivityManager.LOCK_TASK_MODE_NONE,
            ),
        ).isTrue()
    }

    @Test
    fun `while the window is open, nothing re-locks the device behind the surface`() {
        val window = openedForASurface()
        assertThat(
            DapContainmentPolicy.shouldEnterLockTask(
                dedicatedModeEnabled = true,
                maintenanceSessionActive = window.snapshot.value.isAuthenticated,
                isDeviceOwner = true,
                isLockTaskPermitted = true,
                lockTaskModeState = ActivityManager.LOCK_TASK_MODE_NONE,
            ),
        ).isFalse()
    }
}
