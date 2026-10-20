package com.lostf1sh.pixelplayeross.presentation.settings

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.screens.DapScreenTimeoutControlContent
import com.lostf1sh.pixelplayeross.presentation.screens.DapStorageControlContent
import com.lostf1sh.pixelplayeross.presentation.screens.DapWirelessControlContent
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState

/** What the Device page draws: the device's own live state. */
@Immutable
data class DeviceSettingsState(val device: DeviceControlUiState)

/** Everything the Device page can do. The platform round trips (permissions, system panels) are the host's. */
interface DeviceSettingsActions {
    fun onManageWifi()
    fun onEnableBluetooth()
    fun onManageBluetooth()
    /** Only offered by an appliance whose Device Owner may switch the radio itself. */
    fun onWifiEnabledChange(enabled: Boolean)
    fun onBluetoothEnabledChange(enabled: Boolean)
    fun onScreenTimeoutSelected(mode: Long)
}

@Composable
fun DeviceSettingsScreen(state: DeviceSettingsState, actions: DeviceSettingsActions) {
    // Device integration: the radios, the screen, and the storage the
    // library is read from. Brightness is deliberately absent — it is a live
    // device control and lives once, on the Settings root.
    SettingsSubsection(
        title = stringResource(R.string.setcat_device_connectivity),
        addBottomSpace = false,
    ) {
        DapWirelessControlContent(
            state = state.device,
            onManageWifi = actions::onManageWifi,
            onEnableBluetooth = actions::onEnableBluetooth,
            onManageBluetooth = actions::onManageBluetooth,
            onWifiEnabledChanged = if (state.device.isDeviceOwner) actions::onWifiEnabledChange else null,
            onBluetoothEnabledChanged = if (state.device.isDeviceOwner) actions::onBluetoothEnabledChange else null,
        )
    }

    SettingsSubsection(
        title = stringResource(R.string.setcat_device_screen),
        addBottomSpace = false,
    ) {
        DapScreenTimeoutControlContent(
            state = state.device,
            onTimeoutSelected = actions::onScreenTimeoutSelected,
        )
    }

    SettingsSubsection(
        title = stringResource(R.string.setcat_device_storage),
        addBottomSpace = false,
    ) {
        // Capacity and mounted sources only. Scanning is a Music Management
        // action and offering it twice made two screens look like the
        // authority for the same behaviour.
        DapStorageControlContent(state = state.device)
    }
}
