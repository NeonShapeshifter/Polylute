package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * B34-fix2 at source level: what `MainActivity` (a Hilt activity, not buildable on the JVM), the controller, the receiver and the store must
 * keep doing so that choosing another launcher ends Dedicated and stays ended, and so that the DAP comes back after an update. The
 * behaviour is `DapHomeExitTest`'s, on a stateful phone; this is the wiring that connects the production pieces to the platform, in the
 * order that test replays.
 */
class B34Fix2BoundaryTest {
    private val java = "java/com/lostf1sh/pixelplayeross"

    private fun file(relative: String): File = listOf(File(relative), File("app/$relative")).first { it.exists() }
    private fun source(path: String) = file("src/main/$java/$path").readText()
    private fun codeOnly(text: String) = text.lines().joinToString("\n") { it.substringBefore("//") }

    private val activity get() = codeOnly(source("MainActivity.kt"))
    private val controller get() = codeOnly(source("dap/admin/DapDeviceOwnerController.kt"))
    private val reconciler get() = codeOnly(source("dap/admin/DapContainmentReconciler.kt"))

    private fun body(text: String, start: String, end: String) = text.substringAfter(start).substringBefore(end)

    // ── R1: the exit is a recorded fact ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the HOME exit goes through the recorded exit, not through the two bare clears`() {
        val recovery = body(activity, "fun prepareHonorHomeRecovery()", "private suspend fun awaitLockTaskModeState(")
        assertThat(recovery).contains("dapDeviceOwnerController.leaveDedicatedByChoice()")
        // the bare clears, called directly, are what left a window with nothing recorded
        assertThat(recovery).doesNotContain("disableDedicatedMode()")
        assertThat(recovery).doesNotContain("clearPersistentHome()")
        assertThat(recovery).contains("DapMaintenanceSession.end()")
    }

    @Test
    fun `the picker is settled at an activity start and at a resume, before any reconcile and before the lock is asked, and never on a focus change`() {
        val create = body(activity, "override fun onCreate(", "observeDapDisplayPolicy()")
        assertThat(create.indexOf("settleHomeExit()")).isAtLeast(0)
        assertThat(create.indexOf("settleHomeExit()")).isLessThan(create.indexOf("restoreDeviceOwnerPolicy()"))

        val resume = body(activity, "override fun onResume() {", "override fun onPause")
        assertThat(resume.indexOf("settleHomeExit()")).isAtLeast(0)
        assertThat(resume.indexOf("settleHomeExit()")).isLessThan(resume.indexOf("reconcileOwnerPolicyIfHomeChanged(\"onResume\")"))
        assertThat(resume.indexOf("settleHomeExit()")).isLessThan(resume.indexOf("maybeEnterDedicatedMode()"))

        // the focus change is what used to re-arm the policy during the exit: it reconciles, it does not settle
        val focus = body(activity, "override fun onWindowFocusChanged(", "/**")
        assertThat(focus).doesNotContain("settleHomeExit")
        val reconcile = body(activity, "private fun reconcileOwnerPolicyIfHomeChanged(", "override fun onWindowFocusChanged(")
        assertThat(reconcile).doesNotContain("settleHomeExit")
        val newIntent = body(activity, "override fun onNewIntent(", "private fun observeDapDisplayPolicy()")
        assertThat(newIntent).doesNotContain("settleHomeExit")
    }

    @Test
    fun `a resume that is not the picker coming back does not settle it, and a picker that did not open is settled at once`() {
        val settle = body(activity, "private fun settleHomeExit() {", "override fun requestAppPinning(")
        assertThat(settle).contains("DapHomePickerSession.shouldSettleNow()")
        assertThat(settle).contains("dapDeviceOwnerController.settleHomeExit()")
        assertThat(settle).contains("DapHomePickerSession.settled()")

        val restore = body(activity, "override fun restoreHonorLauncher(", "private fun settleHomeExit()")
        assertThat(restore).contains("prepareHonorHomeRecovery()")
        assertThat(restore).contains("if (openSelector())")
        assertThat(restore).contains("DapHomePickerSession.launched()")
        assertThat(restore).contains("settleHomeExit()")
        assertThat(restore).contains("maybeEnterDedicatedMode()")

        val pause = body(activity, "override fun onPause() {", "super.onPause()")
        assertThat(pause).contains("DapHomePickerSession.activityPaused()")
    }

    @Test
    fun `every restore trigger goes through the reconciler, which writes nothing while an exit waits`() {
        val restore = body(controller, "fun restoreOwnerPolicies(", "fun leaveDedicatedByChoice()")
        assertThat(restore).contains("reconciler.restore(trigger)")
        // the flag reconciliation lives in the reconciler now, and respects the recorded exit
        assertThat(controller).doesNotContain("reconcileContainmentFlag")
        val restoreBody = body(reconciler, "fun restore(trigger", "private fun reconcileContainmentFlag(")
        assertThat(restoreBody.indexOf("state.isHomeExitChosen")).isAtLeast(0)
        // nothing is written before the check
        assertThat(restoreBody.indexOf("state.isHomeExitChosen")).isLessThan(restoreBody.indexOf("reconcileContainmentFlag(state)"))
        assertThat(restoreBody.indexOf("state.isHomeExitChosen")).isLessThan(restoreBody.indexOf("applyKeyguardDisabled()"))
        val flag = body(reconciler, "private fun reconcileContainmentFlag(", "fun leave()")
        assertThat(flag).contains("state.isHomeExitChosen")
    }

    @Test
    fun `the exit is recorded before anything is cleared, taken back when the clear fails, and settled from Android's state`() {
        val leave = body(reconciler, "fun leave(): Boolean {", "fun settle(): HomeExitSettlement {")
        assertThat(leave.indexOf("markHomeExit(true)")).isAtLeast(0)
        assertThat(leave.indexOf("markHomeExit(true)")).isLessThan(leave.indexOf("clearPolicies()"))
        assertThat(leave.indexOf("clearPolicies()")).isLessThan(leave.indexOf("clearPersistentHome()"))
        assertThat(leave).contains("finally")
        assertThat(leave).contains("markHomeExit(false)")

        val settle = body(reconciler, "fun settle(): HomeExitSettlement {", "\n}")
        // the mark is taken first, so what follows is decided from Android's state
        assertThat(settle.indexOf("markHomeExit(false)")).isLessThan(settle.indexOf("state.isPixelPlayerHome"))
        assertThat(settle).contains("enableDedicatedMode()")
    }

    @Test
    fun `the controller reads the recorded exit into the owner state and hands the platform to the reconciler`() {
        val state = body(controller, "fun currentState(): DapDeviceOwnerState", "private val reconciler")
        assertThat(state).contains("isHomeExitChosen = containmentStore.isHomeExitChosen()")
        assertThat(controller).contains("markHomeExit = containmentStore::setHomeExitChosen")
        assertThat(controller).contains("clearPolicies = ::disableDedicatedMode")
        assertThat(controller).contains("clearPersistentHome = ::clearPersistentHome")
        // the explicit way back is the listener's own word: it ends any exit waiting, and is not skipped as "already ready"
        val enable = body(controller, "fun enableDedicatedMode(): Boolean {", "/** Avoids rewriting")
        assertThat(enable).contains("containmentStore.setHomeExitChosen(false)")
        val ensure = body(controller, "fun ensureDedicatedModeReady(): Boolean {", "/**")
        assertThat(ensure).contains("!state.isHomeExitChosen")
        // and a persistent HOME the exit cleared is not "ready": the way back would answer "nothing to do" and leave HOME unpinned
        assertThat(ensure).contains("persistentHomeEnabled = containmentStore.isPersistentHomeEnabled()")
    }

    // ── B35-fix1 R2: the record means "this app wrote the entry" ───────────────────────────────────────────────────────────────

    @Test
    fun `the record is false until the app writes the entry, and the entry is only ever added by the call that records it first`() {
        val store = codeOnly(source("dap/admin/DapContainmentStore.kt"))
        assertThat(body(store, "fun isPersistentHomeEnabled()", "fun setPersistentHomeEnabled")).contains("KEY_PERSISTENT_HOME_ENABLED, false")
        // the one place the entry is added records it first, so every install that ever held an entry also holds the key
        val apply = body(controller, "fun applyPersistentHome(): Boolean {", "fun clearPersistentHome")
        assertThat(apply.indexOf("setPersistentHomeEnabled(true)")).isAtLeast(0)
        assertThat(apply.indexOf("setPersistentHomeEnabled(true)")).isLessThan(apply.indexOf("addPersistentPreferredActivity("))
        val adders = file("src/main/$java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { codeOnly(it.readText()).contains("addPersistentPreferredActivity(") }.map { it.name }.toList()
        assertThat(adders).containsExactly("DapDeviceOwnerController.kt")
        assertThat(Regex("addPersistentPreferredActivity\\(").findAll(controller).count()).isEqualTo(1)
    }

    @Test
    fun `Volver al DAP asserts the persistent HOME again whatever the record says, and the Maintenance exit keeps its cheaper contract`() {
        val returns = activity.substringAfter("override fun returnToDap(").substringBefore("private suspend fun exitAuthenticatedMaintenanceNow()")
        assertThat(returns).contains("dapDeviceOwnerController.reassertDedicatedPolicy()")
        val reassert = body(controller, "fun reassertDedicatedPolicy(): Boolean {", "\n    }\n")
        assertThat(reassert).contains("ensureDedicatedModeReady()")
        assertThat(reassert).contains("applyPersistentHome()")
        val maintenance = body(activity, "private suspend fun returnToDedicatedModeNow()", "fun exitAuthenticatedMaintenance(")
        assertThat(maintenance).contains("ensureDedicatedModeReady()")
        assertThat(maintenance).doesNotContain("reassertDedicatedPolicy")
    }

    @Test
    fun `the mode controller puts the recorded exit into every evidence it builds`() {
        val mode = codeOnly(source("dap/mode/ProductModeController.kt"))
        assertThat(Regex("homeExitChosen = ownerState.isHomeExitChosen").findAll(mode).count()).isEqualTo(2)
        val resolver = codeOnly(source("dap/mode/ProductMode.kt"))
        assertThat(resolver).contains("isDeviceOwner && !homeExitChosen")
    }

    @Test
    fun `the recorded exit is written synchronously, because a process that dies right after the tap must find it`() {
        val store = codeOnly(source("dap/admin/DapContainmentStore.kt"))
        val setter = body(store, "fun setHomeExitChosen(", "private companion object")
        // KTX `edit(commit = true)`: the synchronous write without a bare `commit()` (lint's ApplySharedPref / UseKtx, and the baseline stays as it was)
        assertThat(setter).contains("edit(commit = true)")
        assertThat(setter).doesNotContain(".apply()")
        assertThat(setter).doesNotContain("commit = false")
    }

    @Test
    fun `no other code writes the Dedicated policy outside the reconciler, the explicit way back and the failed-transition recovery`() {
        // the writers of the flag in the controller: enableDedicatedMode (the way back and the recovery), disableDedicatedMode (the HOME exit's
        // clear), clearAllOwnerPolicies (the release's clear) and restoreOwnerStart (the release's rollback). The reconciler's restore writes it
        // only through `setDedicatedFlag(true)`, behind the recorded-exit check.
        val writers = Regex("setDedicatedModeEnabled\\(").findAll(controller).count()
        assertThat(writers).isEqualTo(4)
        assertThat(reconciler).contains("setDedicatedFlag(true)")
        assertThat(Regex("setDedicatedFlag\\(").findAll(reconciler).count()).isEqualTo(1)
    }

    // ── R3: the DAP after an update ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the receiver starts the home activity after a package replacement only through the policy, and only for this package`() {
        val receiver = codeOnly(source("dap/admin/DapPolicyRestoreReceiver.kt"))
        val start = body(receiver, "private fun bringHomeBackAfterUpdate(", "private companion object")
        assertThat(start).contains("DapUpdateReturnPolicy.shouldStartHome(")
        assertThat(start.indexOf("DapUpdateReturnPolicy.shouldStartHome(")).isLessThan(start.indexOf("startActivity"))
        assertThat(start).contains("Intent.CATEGORY_HOME")
        assertThat(start).contains(".setPackage(context.packageName)")
        assertThat(start).contains("Intent.FLAG_ACTIVITY_NEW_TASK")
        // a refusal is logged and is not fatal
        assertThat(start).contains("runCatching { context.startActivity(home) }")
        // no component or package name of any other app
        assertThat(start).doesNotContain("hihonor")
        assertThat(start).doesNotContain("ComponentName(\"")
        // it runs after the restore, with the restore's own result
        val onReceive = body(receiver, "override fun onReceive(", "/**")
        assertThat(onReceive.indexOf("restoreDedicatedPoliciesIfActive(trigger)")).isLessThan(onReceive.indexOf("bringHomeBackAfterUpdate("))
    }

    @Test
    fun `the manifest still delivers the boot and the package replacement to the same receiver, and the receiver is not exported`() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        val receiver = manifest.substringAfter("android:name=\".dap.admin.DapPolicyRestoreReceiver\"").substringBefore("</receiver>")
        assertThat(receiver).contains("android.intent.action.BOOT_COMPLETED")
        assertThat(receiver).contains("android.intent.action.MY_PACKAGE_REPLACED")
        assertThat(receiver).contains("android:exported=\"false\"")
    }

    @Test
    fun `the update policy is pure and never names a device`() {
        val policy = codeOnly(source("dap/lifecycle/DapUpdateReturnPolicy.kt"))
        assertThat(policy).contains("trigger == DapRecoveryTrigger.MY_PACKAGE_REPLACED")
        assertThat(policy).doesNotContain("Context")
        assertThat(policy).doesNotContain("hihonor")
    }
}
