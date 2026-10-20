package com.lostf1sh.pixelplayeross.presentation.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.admin.DapReturnOutcome
import com.lostf1sh.pixelplayeross.dap.admin.SystemSurfaceHost
import com.lostf1sh.pixelplayeross.dap.admin.SystemSurfaceOutcome
import com.lostf1sh.pixelplayeross.presentation.B32Renders
import com.lostf1sh.pixelplayeross.presentation.B34Renders
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B32-Q6: the System page (Settings, System), where Maintenance's everyday and leave-the-DAP actions went when the PIN went.
 *
 * Page: every moved action is a row; the destructive ones and the ones that leave the DAP ask "are you sure?" in a dialog that says what
 * they do and run nothing until it is confirmed; Android's own screens open at a tap, because they lift nothing the owner has not asked
 * for. Wiring: each action reaches the owner it always reached (the library actions the view model, the openers and the HOME recovery the
 * activity through [SystemSurfaceHost]), and a failure says so instead of leaving the device half-released.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class B32SystemPageTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val language get() = context.resources.configuration.locales[0].language

    private class Recorder : SystemSettingsActions {
        val calls = mutableListOf<String>()
        override fun onOpenAndroidSettings() { calls += "settings" }
        override fun onOpenUsbSettings() { calls += "usb" }
        override fun onOpenWifiSettings() { calls += "wifi" }
        override fun onOpenBluetoothSettings() { calls += "bluetooth" }
        override fun onRebuildDatabase() { calls += "rebuild" }
        override fun onDeleteImportedLyrics() { calls += "lyrics" }
        override fun onCleanUpItemsWithoutFile() { calls += "cleanup" }
        override fun onOpenMaintenance() { calls += "maintenance" }
    }

    private val actions = Recorder()

    private fun show(state: SystemSettingsState = SystemSettingsState(purgeableDormantCount = 62)) = compose.setContent {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            Column(Modifier.fillMaxSize().background(Dap.background).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                SystemSettingsScreen(state, actions, mountInputBridge = false)
            }
        }
    }

    private fun row(id: Int) = compose.onNodeWithText(s(id)).performScrollTo()

    @Test
    fun `every action that moved off Maintenance is a row of the System page`() {
        show()
        listOf(
            R.string.maintenance_system_section, R.string.maintenance_open_settings, R.string.maintenance_open_usb, R.string.maintenance_open_wifi,
            R.string.maintenance_open_bluetooth,
            R.string.presentation_batch_f_recovery_section, R.string.presentation_batch_f_rebuild_database, R.string.setcat_reset_imported_lyrics_title,
            R.string.maintenance_dormant_cleanup_title,
            R.string.maintenance_internal_tools_section, R.string.setcat_maintenance,
        ).forEach { row(it).assertExists() }
        // the page has no PIN, in the language it is shown in
        compose.onAllNodesWithText("PIN", substring = true).assertCountEquals(0)
        B32Renders.saveAs(compose, "q6-system-page-$language")
    }

    @Test
    fun `Android's own screens open at a tap, with no question`() {
        show()
        row(R.string.maintenance_open_settings).performClick()
        row(R.string.maintenance_open_usb).performClick()
        row(R.string.maintenance_open_wifi).performClick()
        row(R.string.maintenance_open_bluetooth).performClick()
        assertEquals(listOf("settings", "usb", "wifi", "bluetooth"), actions.calls)
    }

    @Test
    fun `an item that is not here asks nothing and the USB row says so`() {
        show(SystemSettingsState(purgeableDormantCount = 0, usbSettingsAvailable = false))
        row(R.string.maintenance_open_usb).assertHasNoClickAction()
        compose.onNodeWithText(s(R.string.maintenance_not_available)).assertExists()
        row(R.string.maintenance_dormant_cleanup_title).assertHasNoClickAction()
        row(R.string.maintenance_open_wifi).assertHasClickAction()
    }

    @Test
    fun `a destructive library action asks first, says what it does, and runs nothing until it is confirmed`() {
        show()
        data class Case(val row: Int, val title: Int, val body: String, val confirm: Int, val call: String)
        val cases = listOf(
            Case(R.string.presentation_batch_f_rebuild_database, R.string.dialog_rebuild_database_title, s(R.string.dialog_rebuild_database_message), R.string.rebuild, "rebuild"),
            Case(R.string.setcat_reset_imported_lyrics_title, R.string.system_confirm_lyrics_title, s(R.string.setcat_reset_imported_lyrics_subtitle), R.string.confirm, "lyrics"),
            Case(R.string.maintenance_dormant_cleanup_title, R.string.maintenance_dormant_cleanup_confirm_title, context.getString(R.string.maintenance_dormant_cleanup_confirm_body, 62), R.string.maintenance_dormant_cleanup_action, "cleanup"),
        )
        cases.forEach { case ->
            val bodiesOnThePage = compose.onAllNodesWithText(case.body).fetchSemanticsNodes().size
            row(case.row).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(s(case.title)).assertExists()
            // the dialog says what it does: its body is one more text than the page had (some rows carry the same sentence)
            compose.onAllNodesWithText(case.body).assertCountEquals(bodiesOnThePage + 1)
            // cancelling runs nothing
            compose.onNodeWithText(s(R.string.cancel)).performClick()
            compose.waitForIdle()
            assertFalse("${case.call} ran without a yes", actions.calls.contains(case.call))
            compose.onAllNodesWithText(s(case.title)).assertCountEquals(0)
            // confirming runs it, once
            row(case.row).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(s(case.confirm)).performClick()
            compose.waitForIdle()
            assertEquals(1, actions.calls.count { it == case.call })
        }
    }

    @Test
    fun `the Maintenance row opens the technical page`() {
        show()
        row(R.string.system_maintenance_subtitle).performClick()
        assertEquals(listOf("maintenance"), actions.calls)
    }

    // ── B34 (U6): the Android shortcuts follow the product mode ───────────────────────────────────

    @Test
    fun `without the shortcuts the page keeps Recovery and Maintenance and offers no Android screen`() {
        show(SystemSettingsState(purgeableDormantCount = 62, showAndroidShortcuts = false))
        listOf(
            R.string.maintenance_open_settings, R.string.maintenance_open_usb, R.string.maintenance_open_wifi, R.string.maintenance_open_bluetooth,
            R.string.maintenance_system_section,
        ).forEach { compose.onAllNodesWithText(s(it)).assertCountEquals(0) }
        listOf(
            R.string.presentation_batch_f_recovery_section, R.string.presentation_batch_f_rebuild_database, R.string.setcat_reset_imported_lyrics_title,
            R.string.maintenance_dormant_cleanup_title, R.string.maintenance_internal_tools_section, R.string.setcat_maintenance,
        ).forEach { row(it).assertExists() }
        B34Renders.saveAs(compose, "system-page-no-shortcuts-$language")
    }

    @Test
    fun `the leave-the-DAP rows are not on the System page when the shortcuts are offered`() {
        show(SystemSettingsState(purgeableDormantCount = 62, showAndroidShortcuts = true))
        compose.onAllNodesWithText(s(R.string.home_mode_choose_home)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.maintenance_exit)).assertCountEquals(0)
    }

    // ── the wiring ───────────────────────────────────────────────────────────────────────────────────

    private val vm = mockk<SettingsViewModel>(relaxed = true)
    private val toasts = mutableListOf<Int>()
    private val removed = mutableListOf<Int>()
    private val navigated = mutableListOf<String>()

    private class FakeHost : SystemSurfaceHost {
        var outcome = SystemSurfaceOutcome.OPENED
        val opened = mutableListOf<Intent>()
        var restoreCalls = 0
        var selectorOpened = 0
        var returnOutcome = DapReturnOutcome.CONTAINED
        var returns = 0
        override fun openSystemSurface(intent: Intent, onResult: (SystemSurfaceOutcome) -> Unit) { opened += intent; onResult(outcome) }
        override fun restoreHonorLauncher(openSelector: () -> Boolean, onResult: (SystemSurfaceOutcome) -> Unit) {
            restoreCalls++
            if (outcome == SystemSurfaceOutcome.OPENED) { selectorOpened++; openSelector() }
            onResult(outcome)
        }
        override fun returnToDap(onResult: (DapReturnOutcome) -> Unit) { returns++; onResult(returnOutcome) }
    }

    private fun wired(host: SystemSurfaceHost?) = SystemSettingsActionsImpl(
        vm = vm, host = host, navigate = { navigated += it }, notify = { toasts += it }, notifyRemoved = { removed += it },
    )

    @Test
    fun `each opener asks the activity for its Android screen, and the activity is the one that lifts Lock Task`() {
        val host = FakeHost()
        val actions = wired(host)
        actions.onOpenAndroidSettings()
        actions.onOpenUsbSettings()
        actions.onOpenWifiSettings()
        actions.onOpenBluetoothSettings()
        assertEquals(
            listOf("android.settings.SETTINGS", "android.settings.USB_SETTINGS", "android.settings.WIFI_SETTINGS", "android.settings.BLUETOOTH_SETTINGS"),
            host.opened.map { it.action },
        )
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun `an opener that could not lift Lock Task says so, and one that could not start says it is not available`() {
        val host = FakeHost()
        val actions = wired(host)
        host.outcome = SystemSurfaceOutcome.NOT_LIFTED
        actions.onOpenWifiSettings()
        host.outcome = SystemSurfaceOutcome.NOT_STARTED
        actions.onOpenWifiSettings()
        assertEquals(listOf(R.string.maintenance_lock_task_exit_failed, R.string.maintenance_not_available), toasts)
        // with no activity to lift anything there is nothing to open
        toasts.clear()
        wired(null).onOpenAndroidSettings()
        assertEquals(listOf(R.string.maintenance_not_available), toasts)
    }

    @Test
    fun `the library actions reach the view model they always reached`() {
        val actions = wired(FakeHost())
        actions.onRebuildDatabase()
        actions.onDeleteImportedLyrics()
        val done = slot<(Int) -> Unit>()
        every { vm.purgeDormantItems(capture(done)) } answers { done.captured(3) }
        actions.onCleanUpItemsWithoutFile()
        verify(exactly = 1) { vm.rebuildDatabase() }
        verify(exactly = 1) { vm.resetAllLyrics() }
        verify(exactly = 1) { vm.purgeDormantItems(any()) }
        assertEquals(listOf(R.string.toast_rebuilding_database, R.string.setcat_reset_imported_lyrics_title), toasts)
        assertEquals(listOf(3), removed)
    }

    @Test
    fun `the Maintenance row navigates to its route`() {
        wired(FakeHost()).onOpenMaintenance()
        assertEquals(listOf("dap_maintenance"), navigated)
        assertNull(null)
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
class B32SystemPageSpanishTest : B32SystemPageTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xhdpi")
class B32SystemPageSwedishTest : B32SystemPageTest()
