package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * B34-fix1 at source level: what `MainActivity` (a Hilt activity, not buildable on the JVM) must keep doing for the release of the Device
 * Owner role, and the reads the controller must never turn into an answer. The behaviour is `DapReleaseOwnerCoordinatorTest`'s, on a
 * stateful phone; this is only the wiring that connects it to the platform.
 */
class B34Fix1BoundaryTest {
    private val java = "java/com/lostf1sh/pixelplayeross"

    private fun file(relative: String): File = listOf(File(relative), File("app/$relative")).first { it.exists() }
    private fun source(path: String) = file("src/main/$java/$path").readText()
    private fun codeOnly(text: String) = text.lines().joinToString("\n") { it.substringBefore("//") }

    private fun release(): String {
        val activity = codeOnly(source("MainActivity.kt"))
        return activity.substringAfter("override fun releaseDeviceOwner(").substringBefore("private fun openThroughLiftedLockTask")
    }

    @Test
    fun `the release asks ownership with a query that can say unknown, and never turns a failed read into false`() {
        val release = release()
        assertThat(release).contains("ownerPresence = dapDeviceOwnerController::ownerPresence")
        // the old adapter: every exception of `currentState()` became `false`, which the coordinator read as "the role is gone"
        assertThat(release).doesNotContain("getOrDefault(false)")
        assertThat(release).doesNotContain("currentState()")
        val controller = codeOnly(source("dap/admin/DapDeviceOwnerController.kt"))
        val query = controller.substringAfter("internal fun ownerPresence()").substringBefore("internal fun ownerStart()")
        assertThat(query).contains("OwnerPresence.UNKNOWN")
        assertThat(query).contains("catch")
        assertThat(query).contains("isDeviceOwnerApp(")
    }

    @Test
    fun `the rollback is built from the starting state and never from the product mode the release has just erased`() {
        val release = release()
        assertThat(release).contains("readStart = dapDeviceOwnerController::ownerStart")
        assertThat(release).contains("restorePolicies = dapDeviceOwnerController::restoreOwnerStart")
        // the gate that made R34-01: `restoreDedicatedModeAfterTransitionFailure` returns at once when the inferred mode is not dedicated
        assertThat(release).doesNotContain("restoreDedicatedModeAfterTransitionFailure")
        assertThat(release).doesNotContain("isDedicatedActive")
        assertThat(release).doesNotContain("maybeEnterDedicatedMode")
        // the lock is requested directly and observed; it does not ask the inferred mode either
        assertThat(release).contains("startLockTask()")
        assertThat(release).contains("awaitLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED)")

        val coordinator = codeOnly(source("dap/admin/DapReleaseOwnerCoordinator.kt"))
        assertThat(coordinator).doesNotContain("isDedicatedActive")
        assertThat(coordinator).doesNotContain("ProductMode")
    }

    @Test
    fun `the restore writes only what the starting state had, and reads each piece back`() {
        val controller = codeOnly(source("dap/admin/DapDeviceOwnerController.kt"))
        val restore = controller.substringAfter("internal fun restoreOwnerStart(").substringBefore("@Suppress(\"DEPRECATION\")")
        assertThat(restore).contains("start.dedicatedFlag")
        assertThat(restore).contains("start.homeWasPolylute")
        assertThat(restore).contains("start.lockTaskPermitted")
        assertThat(restore).contains("start.persistentHomePreference")
        assertThat(restore).contains("applyPersistentHome()")
        assertThat(restore).contains("configureContainmentPolicy()")
        // it never forces a home or a flag the device did not have
        assertThat(restore).doesNotContain("enableDedicatedMode")
    }

    @Test
    fun `a cancelled release settles under NonCancellable like the Android-surface opener, and goes on`() {
        val coordinator = codeOnly(source("dap/admin/DapReleaseOwnerCoordinator.kt"))
        assertThat(coordinator).contains("catch (cancelled: CancellationException)")
        assertThat(coordinator).contains("withContext(NonCancellable)")
        assertThat(coordinator).contains("throw cancelled")
        val surface = codeOnly(source("dap/admin/DapSystemSurfaceCoordinator.kt"))
        assertThat(surface).contains("withContext(NonCancellable)")
        // the release closes its window through the same end() the surface opener's abort uses
        val release = release()
        assertThat(release).contains("DapMaintenanceSession.end()")
        assertThat(release).contains("cancelMaintenanceExpiry()")
    }

    @Test
    fun `a second release or a surface opener cannot share the lift of a release already running`() {
        val release = release()
        assertThat(release).contains("if (systemSurfaceOpening) return")
        assertThat(release).contains("systemSurfaceOpening = true")
        // cleared in a finally, so a cancelled or failed release does not block the next one
        assertThat(release.substringAfter("systemSurfaceOpening = true")).contains("finally")
    }

    @Test
    fun `the Stats budgets are read from CPU time and the wall clock is only printed`() {
        val test = file("src/test/$java/data/stats/StatsPerformanceTest.kt").readText()
        val code = codeOnly(test)
        assertThat(code).contains("getThreadMXBean()")
        assertThat(code).contains("assertThat(load.cpuMs + oneReport.cpuMs).isLessThan(1_500L)")
        assertThat(code).contains("assertThat(refreshes.map { it.cpuMs }.sorted()[refreshes.size / 2]).isLessThan(150L)")
        // the budgets did not move: 1.5 s first load, 150 ms refresh
        assertThat(code).doesNotContain("wallMs).isLessThan")
    }

    @Test
    fun `the page says unverified and released with different texts in every language`() {
        listOf("values", "values-es", "values-sv").forEach { dir ->
            val strings = file("src/main/res/$dir/strings_home_mode.xml").readText()
            fun text(name: String) = Regex("<string name=\"$name\">([^<]*)</string>").find(strings)!!.groupValues[1]
            val done = text("home_mode_release_done")
            val unverified = text("home_mode_release_unverified")
            val unreadable = text("home_mode_release_unreadable")
            assertThat(unverified).isNotEqualTo(done)
            assertThat(unreadable).isNotEqualTo(done)
            // the recovery path names the labels the listener will read: Settings > System > Maintenance > Device Owner, and the home row
            fun label(resource: String, name: String) =
                Regex("<string name=\"$name\">([^<]*)</string>").find(file("src/main/res/$dir/$resource").readText())!!.groupValues[1]
            assertThat(unverified).contains(label("strings_settings.xml", "settings_category_system_title"))
            assertThat(unverified).contains(label("strings_settings.xml", "setcat_maintenance"))
            assertThat(unverified).contains(label("strings.xml", "maintenance_device_owner"))
            assertThat(unverified).contains(label("strings_home_mode.xml", "home_mode_choose_home"))
            listOf("lock_not_stopped", "policy_failed", "still_owner").forEach { key ->
                assertThat(text("home_mode_release_${key}_unrestored")).contains(label("strings_home_mode.xml", "home_mode_choose_home"))
            }
            // the failure texts claim a restore only in the RESTORED variant
            listOf("lock_not_stopped", "policy_failed", "still_owner").forEach { key ->
                assertThat(text("home_mode_release_${key}_plain")).isNotEqualTo(text("home_mode_release_$key"))
                assertThat(text("home_mode_release_${key}_unrestored")).isNotEqualTo(text("home_mode_release_$key"))
            }
        }
    }
}
