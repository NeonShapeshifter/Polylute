package com.lostf1sh.pixelplayeross.dap.compat

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class CapabilityStatus {
    SUPPORTED,
    SUPPORTED_WITH_CONFIRMATION,
    SYSTEM_UI_REQUIRED,
    NOT_SUPPORTED,
    NOT_APPLICABLE,
    UNVERIFIED,
}

enum class DeviceCapability {
    MEDIA_STORE,
    MEDIA_LIBRARY_PERMISSION,
    NOTIFICATION_PERMISSION,
    MEDIA_PLAYBACK_FOREGROUND_SERVICE,
    SHARED_MUSIC_STORAGE,
    USB_HOST,
    USB_MANAGER,
    USB_AUDIO_CANDIDATE_ATTACHED,
    USB_PERMISSION,
    USB_DIRECT_IMPLEMENTATION,
    USB_DIRECT_PHYSICAL_VALIDATION,
    BLUETOOTH_ADAPTER,
    BLUETOOTH_STATE_OBSERVATION,
    BLUETOOTH_DIRECT_TOGGLE,
    BLUETOOTH_SYSTEM_MANAGEMENT,
    WIFI_MANAGER,
    WIFI_STATE_OBSERVATION,
    WIFI_DIRECT_TOGGLE,
    WIFI_SYSTEM_MANAGEMENT,
    DEVICE_OWNER,
    LOCK_TASK,
    LOCK_TASK_ALLOWLISTED,
    PERSISTENT_HOME,
    PIXELPLAYER_HOME,
    DEVICE_ADMIN_COMPONENT,
    MAINTENANCE,
    RECOVERY_PATH,
    STANDARD_MEDIA_KEYS,
    SPECIAL_HARDWARE_KEYS,
    PORTRAIT_ORIENTATION,
}

data class CapabilityFinding(
    val status: CapabilityStatus,
    val detail: String? = null,
)

data class DeviceCompatibilitySnapshot(
    val androidApi: Int,
    val productMode: EffectiveProductMode,
    val findings: Map<DeviceCapability, CapabilityFinding>,
) {
    operator fun get(capability: DeviceCapability): CapabilityFinding =
        findings[capability] ?: CapabilityFinding(CapabilityStatus.UNVERIFIED)

    companion object {
        fun empty() = DeviceCompatibilitySnapshot(
            androidApi = 0,
            productMode = EffectiveProductMode.APP,
            findings = DeviceCapability.entries.associateWith {
                CapabilityFinding(CapabilityStatus.UNVERIFIED)
            },
        )
    }
}

/** Platform facts only. Keeping resolution pure makes missing hardware and API levels testable. */
data class DeviceCapabilityFacts(
    val androidApi: Int,
    val productMode: EffectiveProductMode,
    val hasMediaStore: Boolean,
    val hasMediaPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val hasUsbHostFeature: Boolean,
    val hasUsbManager: Boolean,
    val hasAttachedUsbAudioCandidate: Boolean,
    val hasBluetoothAdapter: Boolean,
    val hasBluetoothConnectPermission: Boolean,
    val hasBluetoothManagementIntent: Boolean,
    val hasWifiManager: Boolean,
    val hasWifiFeature: Boolean,
    val hasWifiManagementIntent: Boolean,
    val hasDeviceAdminFeature: Boolean,
    val isDeviceOwner: Boolean,
    val isLockTaskPermitted: Boolean,
    val isPixelPlayerHome: Boolean,
    val hasDeviceAdminComponent: Boolean,
    val hasRecoveryPath: Boolean,
)

object DeviceCapabilityResolver {
    fun resolve(facts: DeviceCapabilityFacts): DeviceCompatibilitySnapshot {
        val dedicated = facts.productMode == EffectiveProductMode.DEDICATED_ACTIVE
        val bluetoothPermissionRequired = facts.androidApi >= Build.VERSION_CODES.S
        val notificationPermissionRequired = facts.androidApi >= Build.VERSION_CODES.TIRAMISU
        val mediaPermission = if (facts.hasMediaPermission) {
            CapabilityStatus.SUPPORTED
        } else {
            CapabilityStatus.SUPPORTED_WITH_CONFIRMATION
        }
        val bluetoothObservation = when {
            !facts.hasBluetoothAdapter -> CapabilityStatus.NOT_SUPPORTED
            !bluetoothPermissionRequired || facts.hasBluetoothConnectPermission -> CapabilityStatus.SUPPORTED
            else -> CapabilityStatus.SUPPORTED_WITH_CONFIRMATION
        }
        val wifiAvailable = facts.hasWifiFeature && facts.hasWifiManager
        val usbAvailable = facts.hasUsbHostFeature && facts.hasUsbManager

        fun supported(value: Boolean) = if (value) {
            CapabilityStatus.SUPPORTED
        } else {
            CapabilityStatus.NOT_SUPPORTED
        }

        val findings = mapOf(
            DeviceCapability.MEDIA_STORE to CapabilityFinding(supported(facts.hasMediaStore)),
            DeviceCapability.MEDIA_LIBRARY_PERMISSION to CapabilityFinding(mediaPermission),
            DeviceCapability.NOTIFICATION_PERMISSION to CapabilityFinding(
                when {
                    !notificationPermissionRequired -> CapabilityStatus.NOT_APPLICABLE
                    facts.hasNotificationPermission -> CapabilityStatus.SUPPORTED
                    else -> CapabilityStatus.SUPPORTED_WITH_CONFIRMATION
                },
            ),
            DeviceCapability.MEDIA_PLAYBACK_FOREGROUND_SERVICE to CapabilityFinding(CapabilityStatus.SUPPORTED),
            DeviceCapability.SHARED_MUSIC_STORAGE to CapabilityFinding(CapabilityStatus.SUPPORTED_WITH_CONFIRMATION),
            DeviceCapability.USB_HOST to CapabilityFinding(supported(facts.hasUsbHostFeature)),
            DeviceCapability.USB_MANAGER to CapabilityFinding(supported(facts.hasUsbManager)),
            DeviceCapability.USB_AUDIO_CANDIDATE_ATTACHED to CapabilityFinding(
                if (!usbAvailable) CapabilityStatus.NOT_SUPPORTED
                else if (facts.hasAttachedUsbAudioCandidate) CapabilityStatus.SUPPORTED
                else CapabilityStatus.NOT_APPLICABLE,
            ),
            DeviceCapability.USB_PERMISSION to CapabilityFinding(
                if (usbAvailable) CapabilityStatus.SUPPORTED_WITH_CONFIRMATION
                else CapabilityStatus.NOT_SUPPORTED,
            ),
            DeviceCapability.USB_DIRECT_IMPLEMENTATION to CapabilityFinding(
                if (usbAvailable) CapabilityStatus.SUPPORTED_WITH_CONFIRMATION
                else CapabilityStatus.NOT_SUPPORTED,
                "Available means prerequisites exist; it is not a physical verification claim.",
            ),
            DeviceCapability.USB_DIRECT_PHYSICAL_VALIDATION to CapabilityFinding(CapabilityStatus.UNVERIFIED),
            DeviceCapability.BLUETOOTH_ADAPTER to CapabilityFinding(supported(facts.hasBluetoothAdapter)),
            DeviceCapability.BLUETOOTH_STATE_OBSERVATION to CapabilityFinding(bluetoothObservation),
            DeviceCapability.BLUETOOTH_DIRECT_TOGGLE to CapabilityFinding(
                when {
                    !facts.hasBluetoothAdapter -> CapabilityStatus.NOT_SUPPORTED
                    facts.isDeviceOwner && bluetoothObservation == CapabilityStatus.SUPPORTED -> CapabilityStatus.SUPPORTED
                    facts.hasBluetoothManagementIntent -> CapabilityStatus.SYSTEM_UI_REQUIRED
                    else -> CapabilityStatus.NOT_SUPPORTED
                },
            ),
            DeviceCapability.BLUETOOTH_SYSTEM_MANAGEMENT to CapabilityFinding(
                supported(facts.hasBluetoothManagementIntent),
            ),
            DeviceCapability.WIFI_MANAGER to CapabilityFinding(supported(wifiAvailable)),
            DeviceCapability.WIFI_STATE_OBSERVATION to CapabilityFinding(supported(wifiAvailable)),
            DeviceCapability.WIFI_DIRECT_TOGGLE to CapabilityFinding(
                when {
                    !wifiAvailable -> CapabilityStatus.NOT_SUPPORTED
                    facts.isDeviceOwner -> CapabilityStatus.SUPPORTED
                    facts.hasWifiManagementIntent -> CapabilityStatus.SYSTEM_UI_REQUIRED
                    else -> CapabilityStatus.NOT_SUPPORTED
                },
            ),
            DeviceCapability.WIFI_SYSTEM_MANAGEMENT to CapabilityFinding(
                supported(facts.hasWifiManagementIntent),
            ),
            DeviceCapability.DEVICE_OWNER to CapabilityFinding(
                when {
                    facts.isDeviceOwner -> CapabilityStatus.SUPPORTED
                    facts.hasDeviceAdminFeature -> CapabilityStatus.SYSTEM_UI_REQUIRED
                    else -> CapabilityStatus.NOT_SUPPORTED
                },
            ),
            DeviceCapability.LOCK_TASK to CapabilityFinding(
                if (facts.hasDeviceAdminFeature) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED,
            ),
            DeviceCapability.LOCK_TASK_ALLOWLISTED to CapabilityFinding(
                when {
                    !facts.isDeviceOwner -> CapabilityStatus.NOT_APPLICABLE
                    facts.isLockTaskPermitted -> CapabilityStatus.SUPPORTED
                    else -> CapabilityStatus.NOT_SUPPORTED
                },
            ),
            DeviceCapability.PERSISTENT_HOME to CapabilityFinding(
                if (facts.isDeviceOwner) CapabilityStatus.SUPPORTED
                else if (facts.hasDeviceAdminFeature) CapabilityStatus.SYSTEM_UI_REQUIRED
                else CapabilityStatus.NOT_SUPPORTED,
            ),
            DeviceCapability.PIXELPLAYER_HOME to CapabilityFinding(
                if (facts.isPixelPlayerHome) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_APPLICABLE,
            ),
            DeviceCapability.DEVICE_ADMIN_COMPONENT to CapabilityFinding(supported(facts.hasDeviceAdminComponent)),
            DeviceCapability.MAINTENANCE to CapabilityFinding(
                if (dedicated && facts.isDeviceOwner) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_APPLICABLE,
            ),
            DeviceCapability.RECOVERY_PATH to CapabilityFinding(
                if (dedicated) supported(facts.hasRecoveryPath) else CapabilityStatus.NOT_APPLICABLE,
            ),
            DeviceCapability.STANDARD_MEDIA_KEYS to CapabilityFinding(CapabilityStatus.SUPPORTED),
            DeviceCapability.SPECIAL_HARDWARE_KEYS to CapabilityFinding(CapabilityStatus.UNVERIFIED),
            DeviceCapability.PORTRAIT_ORIENTATION to CapabilityFinding(CapabilityStatus.SUPPORTED),
        )
        return DeviceCompatibilitySnapshot(facts.androidApi, facts.productMode, findings)
    }
}

@Singleton
class DeviceCapabilityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun snapshot(): DeviceCompatibilitySnapshot {
        val packageManager = context.packageManager
        val usbManager = context.getSystemService(UsbManager::class.java)
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val devicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val mode = ProductModeController(context).currentState()
        val ownerState = runCatching {
            com.lostf1sh.pixelplayeross.dap.admin.DapDeviceOwnerController(context).currentState()
        }.getOrNull()

        fun hasPermission(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        fun resolves(action: String) = Intent(action).resolveActivity(packageManager) != null

        val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val hasUsbAudioCandidate = usbManager?.deviceList?.values.orEmpty().any { device ->
            (0 until device.interfaceCount).any { index ->
                device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        }
        val adminComponent = com.lostf1sh.pixelplayeross.dap.admin.DapDeviceOwnerController(context)
            .adminComponent
        @Suppress("DEPRECATION")
        val adminComponentAvailable = runCatching {
            packageManager.getReceiverInfo(adminComponent, 0)
            true
        }.getOrDefault(false)
        val hasIndependentHomeRecovery = runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY).any { match ->
                match.activityInfo.packageName != context.packageName && match.activityInfo.enabled
            }
        }.getOrDefault(false)

        return DeviceCapabilityResolver.resolve(
            DeviceCapabilityFacts(
                androidApi = Build.VERSION.SDK_INT,
                productMode = mode.effectiveMode,
                hasMediaStore = runCatching { MediaStore.Audio.Media.EXTERNAL_CONTENT_URI != null }.getOrDefault(false),
                hasMediaPermission = hasPermission(mediaPermission),
                hasNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    hasPermission(Manifest.permission.POST_NOTIFICATIONS),
                hasUsbHostFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
                hasUsbManager = usbManager != null,
                hasAttachedUsbAudioCandidate = hasUsbAudioCandidate,
                hasBluetoothAdapter = bluetoothManager?.adapter != null,
                hasBluetoothConnectPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT),
                hasBluetoothManagementIntent = resolves(Settings.ACTION_BLUETOOTH_SETTINGS) &&
                    resolves(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE),
                hasWifiManager = wifiManager != null,
                hasWifiFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
                hasWifiManagementIntent = resolves(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                    } else {
                        Settings.ACTION_WIFI_SETTINGS
                    },
                ),
                hasDeviceAdminFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN) &&
                    devicePolicyManager != null && activityManager != null,
                isDeviceOwner = ownerState?.isDeviceOwner == true,
                isLockTaskPermitted = ownerState?.isLockTaskPermitted == true,
                isPixelPlayerHome = ownerState?.isPixelPlayerHome == true,
                hasDeviceAdminComponent = adminComponentAvailable,
                // This capability means an independent enabled HOME still exists. ADB recovery is
                // deliberately not inferred from application APIs and remains a separate QA check.
                hasRecoveryPath = ownerState?.isDeviceOwner == true &&
                    ownerState.isPixelPlayerHome &&
                    hasIndependentHomeRecovery,
            ),
        )
    }
}
