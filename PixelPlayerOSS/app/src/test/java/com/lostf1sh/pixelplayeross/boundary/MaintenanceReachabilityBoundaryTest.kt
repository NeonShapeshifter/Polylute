package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * F-08: Maintenance is the owner's way out and must never become unreachable. The Device Owner and
 * Lock Task calls are Android-bound, so the wiring is asserted at source level; the rules are in
 * `DapMaintenanceEntryPolicyTest` and `DapHomeChangeTrackerTest`.
 */
class MaintenanceReachabilityBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private fun source(relative: String): String =
        File("src/main", "$java/$relative").readText().replace("\r\n", "\n")

    private val activity = source("MainActivity.kt")

    private val entry = activity.substringAfter("fun enterAuthenticatedMaintenance(")
        .substringBefore("/** Gets Lock Task to NONE")

    @Test
    fun `entering Maintenance is decided by the Lock Task state, not by the inferred product mode`() {
        assertThat(entry).contains("DapMaintenanceEntryPolicy.plan(lockState)")
        // The gate that stranded the owner: "not dedicated, so nothing to release".
        assertThat(entry).doesNotContain("if (!productModeController.isDedicatedActive())")
        assertThat(entry).contains("dapDeviceOwnerController.lockTaskModeState()")
    }

    @Test
    fun `a Lock Task that will not release is escalated to Device Owner's allowlist`() {
        val release = activity.substringAfter("private suspend fun releaseLockTaskForMaintenance()")
            .substringBefore("fun returnToDedicatedMode")
        assertThat(release).contains("stopLockTask()")
        assertThat(release).contains("DapMaintenanceEntryPolicy.escalation(")
        assertThat(release).contains("dapDeviceOwnerController.releaseLockTaskForMaintenance()")
        // The caller treats a failed release as a failed entry and restores the containment.
        assertThat(entry).contains("restoreDedicatedModeAfterTransitionFailure()")
    }

    @Test
    fun `the Device Owner release touches the allowlist and nothing else`() {
        val controller = source("dap/admin/DapDeviceOwnerController.kt")
        val release = controller.substringAfter("fun releaseLockTaskForMaintenance(): Boolean {")
            .substringBefore("\n    }\n")
        assertThat(release).contains("setLockTaskPackages(adminComponent, emptyArray())")
        assertThat(release).doesNotContain("setDedicatedModeEnabled")
        assertThat(release).doesNotContain("clearPersistentHome")
        assertThat(release).doesNotContain("setLockTaskFeatures")
        // The way back: the ordinary exit re-applies the allowlist when it is not in force.
        val ready = controller.substringAfter("fun ensureDedicatedModeReady(): Boolean {").substringBefore("\n    }\n")
        assertThat(ready).contains("enableDedicatedMode()")
        assertThat(controller).contains("fun enableDedicatedMode(): Boolean")
    }

    @Test
    fun `a HOME change the activity lives through is noticed on resume and on a new intent`() {
        val onResume = activity.substringAfter("override fun onResume() {").substringBefore("override fun onDestroy")
        assertThat(onResume).contains("reconcileOwnerPolicyIfHomeChanged(\"onResume\")")
        // Before Lock Task is requested again, so the flag and the allowlist are right when it is.
        assertThat(onResume.indexOf("reconcileOwnerPolicyIfHomeChanged"))
            .isLessThan(onResume.indexOf("maybeEnterDedicatedMode()"))
        val onNewIntent = activity.substringAfter("override fun onNewIntent(intent: Intent) {").substringBefore("private fun observeDapDisplayPolicy")
        assertThat(onNewIntent).contains("reconcileOwnerPolicyIfHomeChanged(\"onNewIntent\")")
    }

    @Test
    fun `the reconciliation is the existing idempotent one, and it refreshes what composition derived`() {
        val reconcile = activity.substringAfter("private fun reconcileOwnerPolicyIfHomeChanged(reason: String) {")
            .substringBefore("private fun restoreDeviceOwnerPolicy()")
        assertThat(reconcile).contains("if (!homeChangeTracker.changed(state)) return")
        assertThat(reconcile).contains("restoreDeviceOwnerPolicy()")
        assertThat(reconcile).contains("ownerEpoch.intValue++")
        // The shell reads the epoch, so a change recomposes the mode-derived state.
        assertThat(activity).contains("val ownerFactsEpoch = ownerEpoch.intValue")
    }

    @Test
    fun `the exit from Maintenance and the activity restore still re-arm the containment`() {
        // B34-fix2: the restore body moved from the controller into `DapContainmentReconciler` (the same calls, the same order); the
        // controller still routes every restore trigger to it
        val restore = source("dap/admin/DapContainmentReconciler.kt")
            .substringAfter("fun restore(").substringBefore("private fun reconcileContainmentFlag(")
        assertThat(restore).contains("reconcileContainmentFlag(state)")
        assertThat(restore).contains("configureContainmentPolicy()")
        assertThat(restore).contains("applyKeyguardDisabled()")
        val controller = source("dap/admin/DapDeviceOwnerController.kt")
            .substringAfter("fun restoreOwnerPolicies(").substringBefore("fun leaveDedicatedByChoice")
        assertThat(controller).contains("reconciler.restore(trigger)")
    }
}
