package com.lostf1sh.pixelplayeross.presentation.maintenance

import android.app.ActivityManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.admin.DapDeviceOwnerState
import com.lostf1sh.pixelplayeross.data.diagnostics.DeviceDiagnosticSnapshot
import com.lostf1sh.pixelplayeross.data.diagnostics.HardwareDiagnosticGroup
import com.lostf1sh.pixelplayeross.data.diagnostics.HardwareDiagnosticRow
import com.lostf1sh.pixelplayeross.data.diagnostics.HardwareEvent
import com.lostf1sh.pixelplayeross.data.diagnostics.HardwareEventType
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverride
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics
import com.lostf1sh.pixelplayeross.presentation.B32Renders
import com.lostf1sh.pixelplayeross.presentation.screens.MaintenanceContent
import com.lostf1sh.pixelplayeross.presentation.screens.MaintenanceState
import com.lostf1sh.pixelplayeross.presentation.settings.MaintenanceToolsActions
import com.lostf1sh.pixelplayeross.presentation.settings.MaintenanceToolsState
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrides
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B32-Q6: Maintenance with no PIN: the page opens on what it holds (the readouts, the overrides, the per-track detail, the device
 * diagnostics and the three regenerations) and holds nothing that changes the device or the library: no Android screens, no database
 * rebuild, no HOME or "Return to DAP", no PIN to set or change. The test crash is a debug build's and no other's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B32MaintenancePageTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    private val calls = mutableListOf<String>()

    private val tools = object : MaintenanceToolsActions {
        override suspend fun onRegeneratePalette(song: Song): Boolean = true
        override fun onRegenerateDailyMix() { calls += "mix" }
        override fun onRegenerateStats() { calls += "stats" }
        override fun onTriggerTestCrash() { calls += "crash" }
    }

    private val state = MaintenanceState(
        currentHome = "com.lostf1sh.pixelplayeross.debug/com.lostf1sh.pixelplayeross.MainActivity",
        deviceOwner = DapDeviceOwnerState(
            isDeviceOwner = true, isAdminActive = true, isLockTaskPermitted = true, isPixelPlayerHome = true,
            isDedicatedModeEnabled = true, lockTaskModeState = ActivityManager.LOCK_TASK_MODE_NONE, lockTaskFeatures = 0,
        ),
        audio = UsbDirectAudioDiagnostics(),
        route = AudioRouteState(),
        hardware = DeviceDiagnosticSnapshot(
            device = HardwareDiagnosticGroup("Device", listOf(HardwareDiagnosticRow("Manufacturer", "HONOR"), HardwareDiagnosticRow("Model", "LGN-NX3"))),
            events = listOf(HardwareEvent(HardwareEventType.DIRECT_ACTIVE, 3_534_270L, "44100 Hz")),
        ),
        overrides = MaintenanceOverrides(),
        track = null,
        metadata = null,
        collections = null,
        tools = MaintenanceToolsState(),
    )

    private fun show(offersTestCrash: Boolean) = compose.setContent {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            MaintenanceContent(
                state = state, toolsActions = tools, onSetOverride = { _: MaintenanceOverride, _: Boolean -> },
                onClearAllOverrides = {}, onExit = { calls += "exit" }, onOpenDiagnostics = { calls += "diagnostics" },
                offersTestCrash = offersTestCrash,
            )
        }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun `Maintenance opens with no PIN and shows the readouts, the package as Polylute, and the technical tools`() {
        show(offersTestCrash = false)
        // no PIN, no unlock, no setup, in any word of the page
        compose.onAllNodesWithText("PIN", substring = true).assertCountEquals(0)
        compose.onNodeWithText(s(R.string.maintenance_title)).assertExists()
        compose.onNodeWithText(s(R.string.maintenance_status_section)).assertExists()
        compose.onNodeWithText(s(R.string.maintenance_package_version)).assertExists()
        assertEquals("Polylute package / version", s(R.string.maintenance_package_version))
        compose.onNodeWithText(s(R.string.maintenance_current_home)).assertExists()
        compose.onNodeWithText(s(R.string.maintenance_device_owner)).assertExists()
        compose.onNodeWithText(s(R.string.maintenance_lock_task_state)).assertExists()
        B32Renders.saveAs(compose, "q6-maintenance-after-top")
        scrollTo(s(R.string.maintenance_overrides_section))
        compose.onNodeWithText(s(R.string.maintenance_overrides_section)).assertExists()
        scrollTo(s(R.string.maintenance_device_diagnostics))
        compose.onNodeWithText(s(R.string.maintenance_device_diagnostics)).performClick()
        assertEquals(listOf("diagnostics"), calls)
    }

    @Test
    fun `nothing that changes the device or the library is on the page`() {
        show(offersTestCrash = false)
        scrollTo(s(R.string.setcat_force_palette_title))
        listOf(
            R.string.maintenance_open_settings, R.string.maintenance_open_usb, R.string.maintenance_open_wifi, R.string.maintenance_open_bluetooth,
            R.string.presentation_batch_f_rebuild_database, R.string.setcat_reset_imported_lyrics_title, R.string.maintenance_dormant_cleanup_title,
            R.string.home_mode_choose_home, R.string.maintenance_exit,
        ).forEach { compose.onAllNodesWithText(s(it)).assertCountEquals(0) }
        B32Renders.saveAs(compose, "q6-maintenance-after-tools")
    }

    @Test
    fun `the three regenerations of the Developer page are Maintenance's, and the test crash is only a debug build's`() {
        show(offersTestCrash = false)
        scrollTo(s(R.string.setcat_force_palette_title))
        listOf(R.string.setcat_force_daily_mix_title, R.string.setcat_force_stats_title, R.string.setcat_force_palette_title)
            .forEach { compose.onNodeWithText(s(it)).assertExists() }
        compose.onAllNodesWithText(s(R.string.setcat_trigger_crash_title)).assertCountEquals(0)
    }

    @Test
    fun `a debug build adds the test crash and its action runs`() {
        show(offersTestCrash = true)
        scrollTo(s(R.string.setcat_trigger_crash_title))
        compose.onNodeWithText(s(R.string.setcat_trigger_crash_title)).performClick()
        assertEquals(listOf("crash"), calls)
    }
}

/** The whole page in one image, in a window tall enough to hold it: the debug build's (with the test crash) and the release build's. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h2700dp-xhdpi")
class B32MaintenancePageWholeRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val tools = object : MaintenanceToolsActions {
        override suspend fun onRegeneratePalette(song: Song): Boolean = true
        override fun onRegenerateDailyMix() {}
        override fun onRegenerateStats() {}
        override fun onTriggerTestCrash() {}
    }

    private fun show(offersTestCrash: Boolean) = compose.setContent {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            MaintenanceContent(
                state = MaintenanceState(
                    currentHome = "com.lostf1sh.pixelplayeross.debug/com.lostf1sh.pixelplayeross.MainActivity",
                    deviceOwner = DapDeviceOwnerState(
                        isDeviceOwner = true, isAdminActive = true, isLockTaskPermitted = true, isPixelPlayerHome = true,
                        isDedicatedModeEnabled = true, lockTaskModeState = ActivityManager.LOCK_TASK_MODE_NONE, lockTaskFeatures = 0,
                    ),
                    audio = UsbDirectAudioDiagnostics(),
                    route = AudioRouteState(),
                    hardware = DeviceDiagnosticSnapshot(
                        device = HardwareDiagnosticGroup("Device", listOf(HardwareDiagnosticRow("Manufacturer", "HONOR"), HardwareDiagnosticRow("Model", "LGN-NX3"))),
                        events = listOf(HardwareEvent(HardwareEventType.DIRECT_ACTIVE, 3_534_270L, "44100 Hz")),
                    ),
                    overrides = MaintenanceOverrides(),
                    track = null, metadata = null, collections = null,
                    tools = MaintenanceToolsState(),
                ),
                toolsActions = tools, onSetOverride = { _: MaintenanceOverride, _: Boolean -> }, onClearAllOverrides = {},
                onExit = {}, onOpenDiagnostics = {}, offersTestCrash = offersTestCrash,
            )
        }
    }

    @Test
    fun `the debug build's page`() {
        show(offersTestCrash = true)
        B32Renders.saveAs(compose, "q6-maintenance-after-debug-whole")
    }

    @Test
    fun `the release build's page`() {
        show(offersTestCrash = false)
        B32Renders.saveAs(compose, "q6-maintenance-after-release-whole")
    }
}
