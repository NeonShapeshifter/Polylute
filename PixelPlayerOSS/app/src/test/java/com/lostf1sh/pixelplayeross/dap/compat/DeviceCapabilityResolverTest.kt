package com.lostf1sh.pixelplayeross.dap.compat

import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceCapabilityResolverTest {
    @Test
    fun `supported hardware resolves independently of manufacturer`() {
        val snapshot = resolve()

        assertEquals(CapabilityStatus.SUPPORTED, snapshot[DeviceCapability.MEDIA_STORE].status)
        assertEquals(CapabilityStatus.SUPPORTED, snapshot[DeviceCapability.USB_HOST].status)
        assertEquals(CapabilityStatus.SUPPORTED, snapshot[DeviceCapability.BLUETOOTH_ADAPTER].status)
        assertEquals(CapabilityStatus.SUPPORTED, snapshot[DeviceCapability.WIFI_MANAGER].status)
    }

    @Test
    fun `missing optional hardware is unsupported without affecting core`() {
        val snapshot = resolve(
            hasUsbHostFeature = false,
            hasUsbManager = false,
            hasBluetoothAdapter = false,
            hasWifiManager = false,
            hasWifiFeature = false,
        )

        assertEquals(CapabilityStatus.NOT_SUPPORTED, snapshot[DeviceCapability.USB_HOST].status)
        assertEquals(CapabilityStatus.NOT_SUPPORTED, snapshot[DeviceCapability.BLUETOOTH_ADAPTER].status)
        assertEquals(CapabilityStatus.NOT_SUPPORTED, snapshot[DeviceCapability.WIFI_MANAGER].status)
        assertEquals(CapabilityStatus.SUPPORTED, snapshot[DeviceCapability.MEDIA_STORE].status)
    }

    @Test
    fun `USB availability never becomes physical verification`() {
        val snapshot = resolve(hasAttachedUsbAudioCandidate = true)

        assertEquals(
            CapabilityStatus.SUPPORTED_WITH_CONFIRMATION,
            snapshot[DeviceCapability.USB_DIRECT_IMPLEMENTATION].status,
        )
        assertEquals(
            CapabilityStatus.UNVERIFIED,
            snapshot[DeviceCapability.USB_DIRECT_PHYSICAL_VALIDATION].status,
        )
    }

    @Test
    fun `ordinary App Mode routes radio toggles through system UI`() {
        val snapshot = resolve(isDeviceOwner = false)

        assertEquals(CapabilityStatus.SYSTEM_UI_REQUIRED, snapshot[DeviceCapability.WIFI_DIRECT_TOGGLE].status)
        assertEquals(CapabilityStatus.SYSTEM_UI_REQUIRED, snapshot[DeviceCapability.BLUETOOTH_DIRECT_TOGGLE].status)
        assertEquals(CapabilityStatus.NOT_APPLICABLE, snapshot[DeviceCapability.MAINTENANCE].status)
    }

    @Test
    fun `Device Owner enables managed radio and dedicated capabilities`() {
        val snapshot = resolve(
            productMode = EffectiveProductMode.DEDICATED_ACTIVE,
            isDeviceOwner = true,
            isLockTaskPermitted = true,
            isPixelPlayerHome = true,
            hasRecoveryPath = true,
        )

        assertEquals(CapabilityStatus.SUPPORTED, snapshot[DeviceCapability.WIFI_DIRECT_TOGGLE].status)
        assertEquals(CapabilityStatus.SUPPORTED, snapshot[DeviceCapability.BLUETOOTH_DIRECT_TOGGLE].status)
        assertEquals(CapabilityStatus.SUPPORTED, snapshot[DeviceCapability.LOCK_TASK_ALLOWLISTED].status)
        assertEquals(CapabilityStatus.SUPPORTED, snapshot[DeviceCapability.MAINTENANCE].status)
        assertEquals(CapabilityStatus.SUPPORTED, snapshot[DeviceCapability.RECOVERY_PATH].status)
    }

    @Test
    fun `missing modern Bluetooth authorization is confirmation not unsupported`() {
        val snapshot = resolve(androidApi = 35, hasBluetoothConnectPermission = false)

        assertEquals(
            CapabilityStatus.SUPPORTED_WITH_CONFIRMATION,
            snapshot[DeviceCapability.BLUETOOTH_STATE_OBSERVATION].status,
        )
        assertEquals(
            CapabilityStatus.SYSTEM_UI_REQUIRED,
            snapshot[DeviceCapability.BLUETOOTH_DIRECT_TOGGLE].status,
        )
    }

    @Test
    fun `older Android does not invent modern runtime permission requirements`() {
        val snapshot = resolve(
            androidApi = 30,
            hasBluetoothConnectPermission = false,
            hasNotificationPermission = false,
        )

        assertEquals(CapabilityStatus.SUPPORTED, snapshot[DeviceCapability.BLUETOOTH_STATE_OBSERVATION].status)
        assertEquals(CapabilityStatus.NOT_APPLICABLE, snapshot[DeviceCapability.NOTIFICATION_PERMISSION].status)
    }

    @Test
    fun `requested dedicated without owner never exposes maintenance`() {
        val snapshot = resolve(productMode = EffectiveProductMode.DEDICATED_SETUP_REQUIRED)

        assertEquals(CapabilityStatus.NOT_APPLICABLE, snapshot[DeviceCapability.MAINTENANCE].status)
        assertEquals(CapabilityStatus.NOT_APPLICABLE, snapshot[DeviceCapability.LOCK_TASK_ALLOWLISTED].status)
    }

    private fun resolve(
        androidApi: Int = 35,
        productMode: EffectiveProductMode = EffectiveProductMode.APP,
        hasUsbHostFeature: Boolean = true,
        hasUsbManager: Boolean = true,
        hasAttachedUsbAudioCandidate: Boolean = false,
        hasBluetoothAdapter: Boolean = true,
        hasBluetoothConnectPermission: Boolean = true,
        hasNotificationPermission: Boolean = true,
        hasWifiManager: Boolean = true,
        hasWifiFeature: Boolean = true,
        isDeviceOwner: Boolean = false,
        isLockTaskPermitted: Boolean = false,
        isPixelPlayerHome: Boolean = false,
        hasRecoveryPath: Boolean = false,
    ) = DeviceCapabilityResolver.resolve(
        DeviceCapabilityFacts(
            androidApi = androidApi,
            productMode = productMode,
            hasMediaStore = true,
            hasMediaPermission = true,
            hasNotificationPermission = hasNotificationPermission,
            hasUsbHostFeature = hasUsbHostFeature,
            hasUsbManager = hasUsbManager,
            hasAttachedUsbAudioCandidate = hasAttachedUsbAudioCandidate,
            hasBluetoothAdapter = hasBluetoothAdapter,
            hasBluetoothConnectPermission = hasBluetoothConnectPermission,
            hasBluetoothManagementIntent = true,
            hasWifiManager = hasWifiManager,
            hasWifiFeature = hasWifiFeature,
            hasWifiManagementIntent = true,
            hasDeviceAdminFeature = true,
            isDeviceOwner = isDeviceOwner,
            isLockTaskPermitted = isLockTaskPermitted,
            isPixelPlayerHome = isPixelPlayerHome,
            hasDeviceAdminComponent = true,
            hasRecoveryPath = hasRecoveryPath,
        ),
    )
}
