package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * B32-fix1 R2 and R3 at source level: what `MainActivity` cannot be built to show on the JVM (it is a Hilt activity), pinned where it
 * would break.
 */
class B32Fix1BoundaryTest {
    private val java = "java/com/lostf1sh/pixelplayeross"

    private fun source(path: String): String {
        val relative = "src/main/$java/$path"
        return listOf(File(relative), File("app/$relative")).first { it.exists() }.readText()
    }

    private val activity get() = source("MainActivity.kt")

    private fun codeOnly(text: String) = text.lines().joinToString("\n") { it.substringBefore("//") }

    // --- R2: the window outlives the activity ------------------------------------------------------------------------------------

    @Test
    fun `the activity keeps no state about a window the System page opened`() {
        val code = codeOnly(activity)
        // it was an instance field: a recreated activity started it at false and the restore was lost
        assertThat(code).doesNotContain("systemSurfaceReturnPending")
        assertThat(code).doesNotContain("returnOnResume")
    }

    @Test
    fun `the window carries its exit kind and the resume reads it from the window`() {
        val store = source("dap/admin/DapContainmentStore.kt")
        assertThat(store).contains("val restoreOnReturn: Boolean = false")
        assertThat(store).contains("restoreOnReturn = restoreOnReturn")
        assertThat(store).contains("fun beginExternalFlow(restoreOnReturn: Boolean = false)")
        assertThat(store).contains("DapSystemSurfaceCoordinator.reconcileOnResume(controller, SystemClock.elapsedRealtime())")
        val launch = activity.substringAfter("fun launchAuthenticatedMaintenanceSystemFlow(").substringBefore("private fun scheduleMaintenanceExpiry()")
        assertThat(launch).contains("DapMaintenanceSession.beginExternalFlow(restoreOnReturn)")
    }

    // --- R3: "Volver al DAP" is its own request ----------------------------------------------------------------------------------

    @Test
    fun `the System page's return goes through returnToDap and not through the temporary-window exit`() {
        val wiring = source("presentation/settings/SettingsActionsWiring.kt").substringAfter("override fun onReturnToDap()").substringBefore("override fun onOpenMaintenance()")
        assertThat(wiring).contains("surfaces.returnToDap")
        assertThat(wiring).doesNotContain("exitAuthenticatedMaintenance")
        val host = source("dap/admin/SystemSurfaceHost.kt")
        assertThat(host).contains("fun returnToDap(onResult: (DapReturnOutcome) -> Unit)")
        assertThat(host).doesNotContain("exitAuthenticatedMaintenance")
    }

    @Test
    fun `the explicit return puts the policy back and verifies Lock Task`() {
        val returns = activity.substringAfter("override fun returnToDap(").substringBefore("private suspend fun exitAuthenticatedMaintenanceNow()")
        assertThat(returns).contains("DapReturnToDapCoordinator(")
        assertThat(returns).contains("dapDeviceOwnerController.reassertDedicatedPolicy()")
        assertThat(returns).contains("productModeController.isDedicatedActive()")
        assertThat(returns).contains("requestLockTask = ::maybeEnterDedicatedMode")
        assertThat(returns).contains("awaitLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED)")
        assertThat(returns).contains("recoverContainment = ::restoreDedicatedModeAfterTransitionFailure")
    }

    @Test
    fun `nothing returns to the DAP by itself - not the resume, not the expiry, not the HOME recovery`() {
        val code = codeOnly(activity)
        // the one caller of the explicit return is the System page, through the host interface: the activity only implements it
        assertThat(Regex("\\.returnToDap\\(").findAll(code).count()).isEqualTo(1) // the coordinator's own method, inside the override
        val reconcile = code.substringAfter("private fun reconcileMaintenanceSessionOnResume() {").substringBefore("fun beginTemporarySystemConfirmation(")
        assertThat(reconcile).doesNotContain("returnToDap")
        val expiry = code.substringAfter("private fun scheduleMaintenanceExpiry()").substringBefore("private fun cancelMaintenanceExpiry()")
        assertThat(expiry).doesNotContain("returnToDap")
        val recovery = code.substringAfter("fun prepareHonorHomeRecovery()").substringBefore("private suspend fun awaitLockTaskModeState(")
        assertThat(recovery).doesNotContain("returnToDap")
        assertThat(recovery).contains("DapMaintenanceSession.end()")
    }
}
