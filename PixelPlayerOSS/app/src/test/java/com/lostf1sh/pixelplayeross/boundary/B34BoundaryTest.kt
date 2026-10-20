package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import org.junit.jupiter.api.Test
import java.io.File

/**
 * B34 at source level: what the Hilt activity cannot show on the JVM, and what must never be in the app.
 */
class B34BoundaryTest {
    private val java = "java/com/lostf1sh/pixelplayeross"

    private fun file(relative: String): File = listOf(File(relative), File("app/$relative")).first { it.exists() }
    private fun source(path: String) = file("src/main/$java/$path").readText()
    private fun codeOnly(text: String) = text.lines().joinToString("\n") { it.substringBefore("//") }

    private fun allMain(): List<File> = file("src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()

    // --- the app never runs dpm; the command is text for the listener's computer -----------------------------------------------

    @Test
    fun `no code executes a shell command, and the dpm command is text in one place`() {
        val kotlin = allMain().filter { it.extension == "kt" }
        kotlin.forEach { f ->
            val code = codeOnly(f.readText())
            assertThat(code).doesNotContain("Runtime.getRuntime().exec")
            assertThat(code).doesNotContain("ProcessBuilder")
        }
        val mentions = kotlin.filter { codeOnly(it.readText()).contains("dpm set-device-owner") }.map { it.name }
        assertThat(mentions).containsExactly("HomeModeSettings.kt")
    }

    @Test
    fun `no resource and no manifest offers dpm or a provisioning entry point`() {
        allMain().filter { it.extension == "xml" }.forEach { f ->
            val text = f.readText()
            assertThat(text).doesNotContain("dpm ")
            assertThat(text).doesNotContain("set-device-owner")
            assertThat(text).doesNotContain("PROVISION_MANAGED")
            assertThat(text).doesNotContain("ACTION_PROVISION")
        }
        // the main manifest (which the release build is) has no provisioning entry point and is not test-only
        val main = file("src/main/AndroidManifest.xml").readText()
        assertThat(main).doesNotContain("PROVISION")
        assertThat(main).doesNotContain("dpm")
        assertThat(main).doesNotContain("testOnly")
        // the release flavor adds no manifest at all
        assertThat(File(file("src/main").parentFile, "release/AndroidManifest.xml").exists()).isFalse()
        // the debug build's manifest (testOnly, so adb can remove the admin) offers no provisioning entry point either
        assertThat(file("src/debug/AndroidManifest.xml").readText()).doesNotContain("PROVISION")
    }

    @Test
    fun `clearDeviceOwnerApp is called in one place and only after the policies are cleared`() {
        val callers = allMain().filter { it.extension == "kt" && codeOnly(it.readText()).contains("clearDeviceOwnerApp(") }.map { it.name }
        assertThat(callers).containsExactly("DapDeviceOwnerController.kt")
        // its only caller is the release coordinator's lambda, and the activity builds the coordinator from the controller's methods
        val activity = codeOnly(source("MainActivity.kt"))
        assertThat(activity).doesNotContain("clearDeviceOwnerApp")
        val release = activity.substringAfter("override fun releaseDeviceOwner(").substringBefore("private fun openThroughLiftedLockTask")
        assertThat(release).contains("clearPolicies = dapDeviceOwnerController::clearAllOwnerPolicies")
        assertThat(release).contains("clearPersistentHome = dapDeviceOwnerController::clearPersistentHome")
        assertThat(release).contains("clearDeviceOwner = { dapDeviceOwnerController.clearDeviceOwner() }")
    }

    @Test
    fun `the release is reached only from the confirmed dialog of the page`() {
        val page = codeOnly(source("presentation/settings/HomeModeSettings.kt"))
        // the row only raises the dialog; the action is the dialog's confirm
        assertThat(page).contains("onClick = { confirm(HomeConfirmation.RELEASE_OWNER) }")
        assertThat(page).contains("onConfirm = { run(actions::onReleaseDeviceOwner) }")
        assertThat(page.split("onReleaseDeviceOwner").size - 1).isEqualTo(2) // the interface and the dialog, nothing else
        // nothing else in the app calls the host's release: only the page's action does
        val callers = allMain().filter { it.extension == "kt" && codeOnly(it.readText()).contains(".releaseDeviceOwner") }.map { it.name }.sorted()
        assertThat(callers).containsExactly("SettingsActionsWiring.kt")
    }

    // --- no brand in the picker --------------------------------------------------------------------------------------------------

    @Test
    fun `no home code names a launcher package`() {
        listOf(
            "dap/mode/HomeApp.kt",
            "presentation/settings/HomeModeSettings.kt",
            "presentation/settings/HomeModeHost.kt",
            "presentation/settings/SystemSettingsHost.kt",
            "presentation/settings/SettingsActionsWiring.kt",
        ).forEach { path ->
            val code = codeOnly(source(path))
            assertThat(code).doesNotContain("hihonor")
            assertThat(code).doesNotContain("HONOR_HOME_PACKAGE")
            assertThat(code).doesNotContain("com.google.android.apps.nexuslauncher")
        }
    }

    // --- the page's place and the System page ------------------------------------------------------------------------------------

    @Test
    fun `the page is a Settings category right before System`() {
        val ids = SettingsCategory.entries.map { it.id }
        assertThat(ids.indexOf("home_mode")).isEqualTo(ids.indexOf("system") - 1)
        assertThat(SettingsCategory.HOME_MODE.visibility.name).isEqualTo("PUBLIC")
        assertThat(codeOnly(source("presentation/screens/SettingsCategoryScreen.kt"))).contains("SettingsCategory.HOME_MODE -> HomeModeHost()")
    }

    @Test
    fun `the old Modo dedicado card, page and route are gone, not duplicated`() {
        assertThat(file("src/main/$java/presentation/screens").listFiles()!!.map { it.name }).doesNotContain("DedicatedModeSetupScreen.kt")
        val all = allMain().filter { it.extension == "kt" }.joinToString("\n") { codeOnly(it.readText()) }
        assertThat(all).doesNotContain("DedicatedModeSetup")
        assertThat(all).doesNotContain("ProductModeSettingsCard")
    }

    @Test
    fun `the System page keeps Recovery and Maintenance and has no leave-the-DAP group`() {
        val page = codeOnly(source("presentation/settings/SystemSettings.kt"))
        assertThat(page).doesNotContain("leave_dap")
        assertThat(page).doesNotContain("onRestoreHonorLauncher")
        assertThat(page).doesNotContain("onReturnToDap")
        assertThat(page).contains("if (state.showAndroidShortcuts)")
        assertThat(page).contains("onOpenMaintenance")
        // the host reads the shortcuts from the live product mode
        val host = codeOnly(source("presentation/settings/SystemSettingsHost.kt"))
        assertThat(host).contains("showAndroidShortcuts = mode.product.offersAndroidShortcuts")
        assertThat(host).contains("ProductModeLiveEffect(productModeViewModel)")
    }

    @Test
    fun `the page follows Android live, not a value read once`() {
        val effect = codeOnly(source("presentation/settings/ProductModeLiveEffect.kt"))
        assertThat(effect).contains("repeatOnLifecycle(Lifecycle.State.STARTED)")
        assertThat(effect).contains("viewModel.refresh()")
        assertThat(codeOnly(source("presentation/settings/HomeModeHost.kt"))).contains("ProductModeLiveEffect(productModeViewModel)")
        val vm = codeOnly(source("presentation/viewmodel/ProductModeViewModel.kt"))
        assertThat(vm).contains("withContext(Dispatchers.IO)")
    }

    // --- the activity ------------------------------------------------------------------------------------------------------------

    @Test
    fun `the activity feeds the lock surface from the home role and the keyguard from Device Owner policy`() {
        val activity = codeOnly(source("MainActivity.kt"))
        assertThat(activity).contains("val lockSurfaceAllowed = productState.allowsLockSurface")
        assertThat(activity).contains("dedicated = lockSurfaceAllowed,")
        // the keyguard reconciliation is still keyed on Dedicated
        assertThat(activity).contains("LaunchedEffect(lockScreenEnabled, dedicatedActive)")
        // Back at the root is swallowed by a home app at every level
        assertThat(activity).contains("productModeController.currentState().level != null")
    }

    @Test
    fun `a pin ends with the home role, and a pin the listener makes or drops is noticed without a resume`() {
        val activity = codeOnly(source("MainActivity.kt"))
        assertThat(activity).contains("productModeController.shouldReleasePin()")
        assertThat(activity).contains("override fun onWindowFocusChanged(hasFocus: Boolean)")
        val tracker = codeOnly(source("dap/admin/DapHomeChangeTracker.kt"))
        assertThat(tracker).contains("val lockTaskModeState: Int")
        // the app asks for a pin only as the home app and never as a Device Owner
        val pin = activity.substringAfter("override fun requestAppPinning()").substringBefore("override fun releaseAppPinning()")
        assertThat(pin).contains("!state.isPolyluteHome || state.isDeviceOwner")
    }

    @Test
    fun `the request does not outlive the home role, where every consumer reads the mode`() {
        val controller = codeOnly(source("dap/mode/ProductModeController.kt"))
        assertThat(controller).contains("ProductModeResolver.requestAfterLosingHome(store.requestedMode(), evidence)")
    }

    @Test
    fun `the manifest asks to see home apps only, not every package`() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains("android.intent.category.HOME")
        assertThat(manifest).contains("<queries>")
        assertThat(manifest).doesNotContain("QUERY_ALL_PACKAGES")
    }
}
