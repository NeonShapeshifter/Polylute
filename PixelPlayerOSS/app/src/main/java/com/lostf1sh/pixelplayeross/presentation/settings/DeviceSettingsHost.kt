package com.lostf1sh.pixelplayeross.presentation.settings

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.MainActivity
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.compat.OnDemandPermissionPolicy
import com.lostf1sh.pixelplayeross.dap.compat.OptionalPermissionTrigger
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapManagedRadio
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlEvent
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The Device page, with the platform round trips it needs: the system Wi-Fi / Bluetooth panels, the
 * Bluetooth-connect permission, the write-settings grant. They are Activity-result plumbing, so they live here
 * and not in [DeviceSettingsScreen], which only says *what* the listener asked for.
 */
@Composable
fun DeviceSettingsHost(deviceControlViewModel: DeviceControlViewModel) {
    val deviceControlState by deviceControlViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingBluetoothOwnerState by remember { mutableStateOf<Boolean?>(null) }
    val writeSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        deviceControlViewModel.onWriteSettingsAccessResult()
    }

    val bluetoothEnableConfirmationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        context.findMainActivity()?.finishTemporarySystemConfirmation()
        deviceControlViewModel.refresh()
    }

    val wirelessSystemSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        context.findMainActivity()?.finishTemporarySystemConfirmation()
        deviceControlViewModel.refresh()
    }

    val launchWirelessSystemSettings: (Intent) -> Unit = { intent ->
        val activity = context.findMainActivity()
        val launch: (Boolean) -> Unit = { flowReady ->
            if (flowReady) {
                runCatching { wirelessSystemSettingsLauncher.launch(intent) }
                    .onFailure {
                        activity?.finishTemporarySystemConfirmation()
                        deviceControlViewModel.refresh()
                    }
            }
        }
        if (activity != null) activity.beginTemporarySystemConfirmation(launch) else launch(true)
    }
    val launchBluetoothEnableConfirmation = {
        val activity = context.findMainActivity()
        val launchConfirmation: (Boolean) -> Unit = { flowReady ->
            if (flowReady) {
                runCatching {
                    bluetoothEnableConfirmationLauncher.launch(
                        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    )
                }.onFailure {
                    activity?.finishTemporarySystemConfirmation()
                    Toast.makeText(
                        context,
                        R.string.dap_bluetooth_confirmation_unavailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                Toast.makeText(
                    context,
                    R.string.dap_bluetooth_confirmation_unavailable,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        if (activity != null) {
            activity.beginTemporarySystemConfirmation(launchConfirmation)
        } else {
            launchConfirmation(true)
        }
    }

    val bluetoothConnectPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val ownerState = pendingBluetoothOwnerState
            pendingBluetoothOwnerState = null
            if (ownerState != null) {
                deviceControlViewModel.setBluetoothEnabledByOwner(ownerState)
            } else {
                launchBluetoothEnableConfirmation()
            }
        } else {
            pendingBluetoothOwnerState = null
            Toast.makeText(
                context,
                context.getString(R.string.dap_bluetooth_permission_required),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    LaunchedEffect(deviceControlViewModel) {
        deviceControlViewModel.events.collectLatest { event ->
            when (event) {
                DeviceControlEvent.RequestWriteSettingsAccess -> {
                    writeSettingsLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }
                DeviceControlEvent.RequestBluetoothEnableConfirmation -> {
                    launchBluetoothEnableConfirmation()
                }
                is DeviceControlEvent.ManagedRadioRejected -> {
                    val label = context.getString(
                        when (event.radio) {
                            DapManagedRadio.WIFI -> R.string.dap_wireless_wifi_title
                            DapManagedRadio.BLUETOOTH -> R.string.dap_wireless_bluetooth_title
                        }
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.dap_managed_radio_rejected, label),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    val actions = remember(deviceControlViewModel) {
        object : DeviceSettingsActions {
            override fun onManageWifi() {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                } else {
                    Settings.ACTION_WIFI_SETTINGS
                }
                launchWirelessSystemSettings(Intent(action))
            }

            override fun onEnableBluetooth() {
                val bluetoothGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
                if (OnDemandPermissionPolicy.requestBluetoothConnect(
                        androidApi = Build.VERSION.SDK_INT,
                        alreadyGranted = bluetoothGranted,
                        trigger = OptionalPermissionTrigger.ENABLE_BLUETOOTH,
                    )) {
                    bluetoothConnectPermissionLauncher.launch(
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                } else {
                    launchBluetoothEnableConfirmation()
                }
            }

            override fun onManageBluetooth() {
                launchWirelessSystemSettings(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                )
            }

            override fun onWifiEnabledChange(enabled: Boolean) =
                deviceControlViewModel.setWifiEnabledByOwner(enabled)

            override fun onBluetoothEnabledChange(enabled: Boolean) {
                val bluetoothGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
                if (OnDemandPermissionPolicy.requestBluetoothConnect(
                        androidApi = Build.VERSION.SDK_INT,
                        alreadyGranted = bluetoothGranted,
                        trigger = OptionalPermissionTrigger.ENABLE_BLUETOOTH,
                    )) {
                    pendingBluetoothOwnerState = enabled
                    bluetoothConnectPermissionLauncher.launch(
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                } else {
                    deviceControlViewModel.setBluetoothEnabledByOwner(enabled)
                }
            }

            override fun onScreenTimeoutSelected(mode: Long) = deviceControlViewModel.selectScreenTimeout(mode)
        }
    }

    DeviceSettingsScreen(state = DeviceSettingsState(deviceControlState), actions = actions)
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
