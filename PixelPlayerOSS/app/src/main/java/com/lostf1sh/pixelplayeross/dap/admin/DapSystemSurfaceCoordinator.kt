package com.lostf1sh.pixelplayeross.dap.admin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What the app coming back to the front does about the Maintenance window (B32-fix1 R2). */
internal enum class WindowReturn {
    /** No window, or nothing owed: leave the containment alone. */
    NONE,

    /** A window that is still open and owes nothing yet: keep its expiry running. */
    KEEP_OPEN,

    /** A window the System page opened for an Android surface, and the surface is back: close it and put the containment back. */
    RESTORE_AFTER_SURFACE,

    /** The window ran out while the surface was out (or before it started): put the containment back. */
    RESTORE_AFTER_EXPIRY,
}

/**
 * Opening an Android surface (Android Settings, Wi-Fi, Bluetooth, USB preferences, the HOME selector) from the System page, as one
 * ordered transaction (B32-Q6).
 *
 * Android refuses to start another app's screen while Lock Task holds the task list, so a device in dedicated mode has to let go
 * first. That is exactly what Maintenance did after its PIN, and this is the same thing without the PIN, in the same order:
 *
 *  1. the window opens (`DapMaintenanceSession.begin()`), which is what keeps the containment from re-asserting itself while the
 *     surface is in front (`DapContainmentPolicy.shouldEnterLockTask` is false for as long as it is open);
 *  2. Lock Task is lifted, decided from what Android says the state *is* ([DapMaintenanceEntryPolicy.plan], F-08) and not from the
 *     product mode: nothing locked means nothing to lift (App Mode, a half-provisioned device); otherwise `stopLockTask()` first and,
 *     if that does not reach NONE, Device Owner's allowlist release ([DapMaintenanceEntryPolicy.escalation]);
 *  3. the surface is started.
 *
 * If the lift fails the surface is not started and the containment is put back ([abort]); if the start fails after a lift the way back
 * is the Maintenance exit ([putContainmentBack]). If the activity is cancelled in the middle of the lift (it is being recreated), the
 * window is closed and the containment put back the same way before the cancellation goes on: the next activity must not inherit a
 * window nobody is going to close. The restore on a successful open is not here: it is the app coming back to the front
 * (`MainActivity.onResume`), which runs the same exit, from the obligation the window carries ([reconcileOnResume]).
 *
 * Pure: every platform call is a lambda, so the order is what is tested.
 */
internal class DapSystemSurfaceCoordinator(
    private val lockTaskModeState: () -> Int,
    private val beginWindow: () -> Unit,
    private val liftLockTask: suspend () -> Boolean,
    private val start: () -> Boolean,
    private val abort: suspend () -> Unit,
    private val putContainmentBack: suspend () -> Boolean,
) {
    suspend fun open(): SystemSurfaceOutcome {
        beginWindow()
        val plan = DapMaintenanceEntryPolicy.plan(lockTaskModeState())
        if (plan == DapMaintenanceEntryPolicy.Plan.RELEASE_LOCK_TASK) {
            val lifted = try {
                liftLockTask()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { abort() }
                throw cancelled
            }
            if (!lifted) {
                abort()
                return SystemSurfaceOutcome.NOT_LIFTED
            }
        }
        if (!start()) {
            putContainmentBack()
            return SystemSurfaceOutcome.NOT_STARTED
        }
        return SystemSurfaceOutcome.OPENED
    }

    companion object {
        /**
         * Whether the app coming back to the front closes the window with the Maintenance exit: only for a window that owes a restore
         * ([restoreOnReturn]: the System page opened it for an Android surface) and only when the surface was really out
         * ([externalFlowWasOpen]), so an ordinary resume (a screen-on, a dialog closing) never runs an exit nobody asked for.
         */
        fun restoresOnReturn(externalFlowWasOpen: Boolean, restoreOnReturn: Boolean): Boolean =
            externalFlowWasOpen && restoreOnReturn

        /**
         * What the app coming back to the front does about the window, from the window alone (B32-fix1 R2): `MainActivity` keeps no
         * state of its own about it, so a recreated activity (a configuration change, the system reclaiming it while a surface is out)
         * decides exactly what the original would have. A window that was ended (the HOME recovery ends its own; an explicit return
         * does) owes nothing, so a device the owner left for another launcher is never put back by a resume.
         */
        fun reconcileOnResume(controller: MaintenanceSessionController, nowElapsedMs: Long): WindowReturn {
            val before = controller.snapshot.value
            val externalFlowWasOpen = before.state == MaintenanceSessionState.AUTHENTICATED_EXTERNAL_FLOW
            val valid = if (externalFlowWasOpen) {
                controller.returnFromExternalFlow(nowElapsedMs)
            } else {
                !controller.expireIfNeeded(nowElapsedMs)
            }
            if (!valid && controller.snapshot.value.state == MaintenanceSessionState.EXPIRED) return WindowReturn.RESTORE_AFTER_EXPIRY
            if (restoresOnReturn(externalFlowWasOpen, before.restoreOnReturn)) return WindowReturn.RESTORE_AFTER_SURFACE
            return if (controller.snapshot.value.isAuthenticated) WindowReturn.KEEP_OPEN else WindowReturn.NONE
        }
    }
}
