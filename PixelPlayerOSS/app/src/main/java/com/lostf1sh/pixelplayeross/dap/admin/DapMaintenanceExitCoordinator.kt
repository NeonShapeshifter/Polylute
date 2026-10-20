package com.lostf1sh.pixelplayeross.dap.admin

/**
 * Runs the authenticated Maintenance exit as one ordered transaction.
 *
 * The normal DAP route is not popped until this transaction reports that Lock Task is active.
 * Generic lifecycle recovery remains the fallback when the immediate request is not observed.
 */
internal class DapMaintenanceExitCoordinator(
    private val ensurePolicyReady: () -> Boolean,
    private val endMaintenanceSession: () -> Unit,
    private val prepareLockTaskTransition: () -> Unit,
    private val requestLockTask: () -> Unit,
    private val awaitLocked: suspend () -> Boolean,
    private val recoverContainment: suspend () -> Unit,
) {
    suspend fun exit(): Boolean {
        if (!ensurePolicyReady()) return false

        endMaintenanceSession()
        prepareLockTaskTransition()
        requestLockTask()

        val locked = awaitLocked()
        if (!locked) recoverContainment()
        return locked
    }
}
