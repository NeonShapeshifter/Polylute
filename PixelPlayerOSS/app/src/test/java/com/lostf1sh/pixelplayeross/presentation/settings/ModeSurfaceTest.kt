package com.lostf1sh.pixelplayeross.presentation.settings

import android.app.ActivityManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeRequest
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeState
import com.lostf1sh.pixelplayeross.presentation.settings.ModeSurfacePolicy.Entry
import com.lostf1sh.pixelplayeross.presentation.settings.ModeSurfacePolicy.Scope
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B39-A5: App mode shows only what App mode needs. The audit (the [Entry] table, with a reason each) is checked against every level the
 * product can be at, and the Device and System pages are drawn in App mode and in each DAP level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h2400dp-xxhdpi")
class ModeSurfaceTest {
    @get:Rule val compose = createComposeRule()
    private fun s(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private fun state(
        effective: EffectiveProductMode,
        requested: ProductModeRequest = ProductModeRequest.APP,
        owner: Boolean = false,
        home: Boolean = false,
        lockTask: Int = ActivityManager.LOCK_TASK_MODE_NONE,
    ) = ProductModeState(requested, effective, isDeviceOwner = owner, hasDedicatedProvisioningEvidence = owner, isPolyluteHome = home, lockTaskModeState = lockTask)

    private val app = state(EffectiveProductMode.APP)
    private val appOwnedByDap = state(EffectiveProductMode.APP, owner = true)   // the HONOR after it left the DAP for another launcher
    private val setupRequired = state(EffectiveProductMode.DEDICATED_SETUP_REQUIRED, ProductModeRequest.DEDICATED)
    private val launcher = state(EffectiveProductMode.LAUNCHER, home = true)
    private val pinned = state(EffectiveProductMode.PINNED, ProductModeRequest.PINNED, home = true, lockTask = ActivityManager.LOCK_TASK_MODE_PINNED)
    private val dedicated = state(EffectiveProductMode.DEDICATED_ACTIVE, ProductModeRequest.DEDICATED, owner = true, home = true, lockTask = ActivityManager.LOCK_TASK_MODE_LOCKED)

    private val dapLevels = listOf(launcher, pinned, dedicated)

    @Test
    fun `the screen-off setting is hidden in App mode and stays at every DAP level`() {
        for (appLike in listOf(app, appOwnedByDap, setupRequired)) assertThat(ModeSurfacePolicy.showScreenTimeout(appLike)).isFalse()
        for (dap in dapLevels) assertThat(ModeSurfacePolicy.showScreenTimeout(dap)).isTrue()
        for (appLike in listOf(app, appOwnedByDap, setupRequired)) assertThat(ModeSurfacePolicy.isVisible(Entry.DEVICE_SCREEN_TIMEOUT, appLike)).isFalse()
        for (dap in dapLevels) assertThat(ModeSurfacePolicy.isVisible(Entry.DEVICE_SCREEN_TIMEOUT, dap)).isTrue()
    }

    @Test
    fun `an entry that is for every mode is on its page in every mode`() {
        val everywhere = Entry.entries.filter { it.scope == Scope.APP_AND_DAP }
        assertThat(everywhere).containsExactly(
            Entry.SYSTEM_REBUILD_DATABASE, Entry.SYSTEM_DELETE_IMPORTED_LYRICS, Entry.SYSTEM_CLEAN_UP_WITHOUT_FILE, Entry.SYSTEM_MAINTENANCE,
        )
        for (entry in everywhere) for (mode in listOf(app, appOwnedByDap, setupRequired) + dapLevels) {
            assertThat(ModeSurfacePolicy.isVisible(entry, mode)).isTrue()
        }
    }

    @Test
    fun `Android's own shortcuts are offered only where Lock Task is Polylute's to manage, as before`() {
        for (entry in listOf(Entry.SYSTEM_ANDROID_SETTINGS, Entry.SYSTEM_USB_SETTINGS, Entry.SYSTEM_WIFI_SETTINGS, Entry.SYSTEM_BLUETOOTH_SETTINGS)) {
            assertThat(entry.scope).isEqualTo(Scope.DEDICATED_ONLY)
            assertThat(ModeSurfacePolicy.isVisible(entry, app)).isFalse()
            assertThat(ModeSurfacePolicy.isVisible(entry, setupRequired)).isFalse()
            assertThat(ModeSurfacePolicy.isVisible(entry, launcher)).isFalse()
            assertThat(ModeSurfacePolicy.isVisible(entry, pinned)).isFalse()
            assertThat(ModeSurfacePolicy.isVisible(entry, dedicated)).isTrue()
            // B34 U6, unchanged: a Device Owner that is not home is still offered them
            assertThat(ModeSurfacePolicy.isVisible(entry, appOwnedByDap)).isEqualTo(appOwnedByDap.offersAndroidShortcuts)
        }
    }

    @Test
    fun `Maintenance's home and Device Owner readouts are the DAP's, and a Device Owner that is not home still reads them`() {
        for (entry in listOf(Entry.MAINTENANCE_CURRENT_HOME, Entry.MAINTENANCE_DEVICE_OWNER)) {
            assertThat(entry.scope).isEqualTo(Scope.DAP_OR_OWNER)
            assertThat(ModeSurfacePolicy.isVisible(entry, app)).isFalse()
            assertThat(ModeSurfacePolicy.isVisible(entry, setupRequired)).isFalse()
            for (dap in dapLevels) assertThat(ModeSurfacePolicy.isVisible(entry, dap)).isTrue()
            assertThat(ModeSurfacePolicy.isVisible(entry, appOwnedByDap)).isTrue()
        }
        assertThat(ModeSurfacePolicy.showMaintenanceDapStatus(isDeviceOwner = false, isPolyluteHome = false)).isFalse()
        assertThat(ModeSurfacePolicy.showMaintenanceDapStatus(isDeviceOwner = false, isPolyluteHome = true)).isTrue()
        assertThat(ModeSurfacePolicy.showMaintenanceDapStatus(isDeviceOwner = true, isPolyluteHome = false)).isTrue()
    }

    @Test
    fun `every entry says why it is where it is`() {
        for (entry in Entry.entries) assertThat(entry.reason.length).isAtLeast(20)
    }

    private val noDeviceActions = object : DeviceSettingsActions {
        override fun onManageWifi() = Unit
        override fun onEnableBluetooth() = Unit
        override fun onManageBluetooth() = Unit
        override fun onWifiEnabledChange(enabled: Boolean) = Unit
        override fun onBluetoothEnabledChange(enabled: Boolean) = Unit
        override fun onScreenTimeoutSelected(mode: Long) = Unit
    }

    @Test
    fun `the Device page has no Screen section in App mode and has it in a DAP level`() {
        compose.setContent {
            DeviceSettingsScreen(DeviceSettingsState(DeviceControlUiState(), showScreenTimeout = ModeSurfacePolicy.showScreenTimeout(app)), noDeviceActions)
        }
        compose.onAllNodesWithText(s(R.string.setcat_device_screen)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.dap_screen_timeout_title)).assertCountEquals(0)
        // the rest of the page is untouched
        compose.onNodeWithText(s(R.string.setcat_device_connectivity)).assertExists()
        compose.onNodeWithText(s(R.string.setcat_device_storage)).assertExists()
    }

    @Test
    fun `the Device page draws the Screen section at the Dedicated level`() {
        compose.setContent {
            DeviceSettingsScreen(DeviceSettingsState(DeviceControlUiState(), showScreenTimeout = ModeSurfacePolicy.showScreenTimeout(dedicated)), noDeviceActions)
        }
        compose.onNodeWithText(s(R.string.setcat_device_screen)).assertExists()
        compose.onNodeWithText(s(R.string.dap_screen_timeout_title)).assertExists()
    }

    private val noSystemActions = object : SystemSettingsActions {
        override fun onOpenAndroidSettings() = Unit
        override fun onOpenUsbSettings() = Unit
        override fun onOpenWifiSettings() = Unit
        override fun onOpenBluetoothSettings() = Unit
        override fun onRebuildDatabase() = Unit
        override fun onDeleteImportedLyrics() = Unit
        override fun onCleanUpItemsWithoutFile() = Unit
        override fun onOpenMaintenance() = Unit
    }

    @Test
    fun `the System page in App mode keeps the library repairs and Maintenance, and has no Android shortcuts`() {
        compose.setContent {
            SystemSettingsScreen(
                SystemSettingsState(showAndroidShortcuts = ModeSurfacePolicy.isVisible(Entry.SYSTEM_ANDROID_SETTINGS, app)),
                noSystemActions,
                mountInputBridge = false,
            )
        }
        for (gone in listOf(R.string.maintenance_open_settings, R.string.maintenance_open_usb, R.string.maintenance_open_wifi, R.string.maintenance_open_bluetooth)) {
            compose.onAllNodesWithText(s(gone)).assertCountEquals(0)
        }
        for (kept in listOf(R.string.presentation_batch_f_rebuild_database, R.string.setcat_reset_imported_lyrics_title, R.string.maintenance_dormant_cleanup_title, R.string.setcat_maintenance)) {
            compose.onNodeWithText(s(kept)).assertExists()
        }
    }

    @Test
    fun `the System page at the Dedicated level has the Android shortcuts too`() {
        compose.setContent {
            SystemSettingsScreen(
                SystemSettingsState(showAndroidShortcuts = ModeSurfacePolicy.isVisible(Entry.SYSTEM_ANDROID_SETTINGS, dedicated)),
                noSystemActions,
                mountInputBridge = false,
            )
        }
        for (shown in listOf(R.string.maintenance_open_settings, R.string.maintenance_open_wifi, R.string.maintenance_open_bluetooth)) {
            compose.onNodeWithText(s(shown)).assertExists()
        }
    }
}
