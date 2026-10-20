package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * B34-fix3 at source level. R1: Android's home picker is started in a task of its own, from one place, and nothing alters that on the way to
 * `startActivity`; the intents' flags are `B34HomePlatformTest`'s, on Robolectric. R2: the restore that re-contains a Device Owner whose
 * listener made Polylute the home app again also puts the persistent HOME back; the behaviour is `DapHomeExitTest`'s, on a stateful phone.
 * `B34Fix2BoundaryTest` still pins the order the new flags must not change: the picker is launched after `openSelector()`, the activity's
 * pause arms the flow, and the next start or resume settles it.
 */
class B34Fix3BoundaryTest {
    private val java = "java/com/lostf1sh/pixelplayeross"

    private fun file(relative: String): File = listOf(File(relative), File("app/$relative")).first { it.exists() }
    private fun source(path: String) = file("src/main/$java/$path").readText()
    private fun codeOnly(text: String) = text.lines().joinToString("\n") { it.substringBefore("//") }
    private fun body(text: String, start: String, end: String) = text.substringAfter(start).substringBefore(end)

    private val picker get() = codeOnly(source("dap/mode/HomeApp.kt"))
    private val host get() = codeOnly(source("presentation/settings/HomeModeHost.kt"))
    private val reconciler get() = codeOnly(source("dap/admin/DapContainmentReconciler.kt"))

    // ── R1: the picker is a task of its own ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `both intents of the home picker are given the same task flags, in one place`() {
        val home = body(picker, "object HomePicker {", "/** Reads the current home app")
        assertThat(home).contains("Intent.FLAG_ACTIVITY_NEW_TASK")
        assertThat(home).contains("Intent.FLAG_ACTIVITY_NO_HISTORY")
        assertThat(home).contains("Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS")
        assertThat(Regex("const val TASK_FLAGS").findAll(home).count()).isEqualTo(1)

        val settings = body(home, "fun settingsIntent()", "fun chooserIntent()")
        assertThat(settings).contains("addFlags(TASK_FLAGS)")
        val chooser = body(home, "fun chooserIntent()", "fun resolveIntent(")
        assertThat(chooser).contains("addFlags(TASK_FLAGS)")
        // still no brand: the flags are the only thing added
        assertThat(home).doesNotContain("setPackage")
        assertThat(home).doesNotContain("setComponent")
        assertThat(home).doesNotContain("hihonor")
    }

    @Test
    fun `nothing else starts Android's home settings, and the host starts what HomePicker resolves, unchanged`() {
        val users = file("src/main/$java").walkTopDown()
            .filter { it.extension == "kt" && "ACTION_HOME_SETTINGS" in codeOnly(it.readText()) }
            .map { it.name }
            .toList()
        assertThat(users).containsExactly("HomeApp.kt")

        val start = body(host, "openHomePicker =", "openSecuritySettings =")
        assertThat(start).contains("HomePicker.resolveIntent(context)")
        assertThat(start).contains("context.startSafely(it)")
        // no flag is removed or replaced between the intent and `startActivity`
        assertThat(host).doesNotContain("removeFlags(")
        assertThat(host).doesNotContain("setFlags(")
        assertThat(host).doesNotContain(".flags")
        // a plain start: a start for a result would settle the picker from a different place than fix2's pause and resume
        assertThat(host).doesNotContain("startActivityForResult")
        assertThat(host).doesNotContain("registerForActivityResult")
    }

    // ── R2: Polylute picked again completes Dedicated ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the restore counts a Device Owner with Polylute as home and the persistent HOME recorded off as half-provisioned, behind the recorded-exit check`() {
        val restore = body(reconciler, "fun restore(trigger", "private fun reconcileContainmentFlag(")
        assertThat(restore).contains("val persistentHomeRecorded = persistentHomeRequired()")
        assertThat(restore).contains("persistentHomeRequired = persistentHomeRecorded || state.isPixelPlayerHome")
        assertThat(restore).contains("persistentHomeReady = persistentHomeRecorded && state.isPixelPlayerHome")
        // the exit that waits still wins: nothing is decided before that check
        assertThat(restore.indexOf("state.isHomeExitChosen")).isAtLeast(0)
        assertThat(restore.indexOf("state.isHomeExitChosen")).isLessThan(restore.indexOf("persistentHomeRecorded"))
    }

    @Test
    fun `Volver al DAP's own words no longer call the HONOR choice permanent, and say what ends it`() {
        val coordinator = source("dap/admin/DapReturnToDapCoordinator.kt")
        assertThat(coordinator).doesNotContain("is permanent")
        assertThat(coordinator).contains("makes Polylute the home app again")
        assertThat(coordinator).contains("re-contains")
    }
}
