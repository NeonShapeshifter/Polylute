package com.lostf1sh.pixelplayeross.dap.compat

import android.bluetooth.BluetoothAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WirelessStatePolicyTest {
    @Test
    fun `external Bluetooth OFF and ON signals replace stale state`() {
        assertEquals(false, BluetoothStateSignal.resolve(true, BluetoothAdapter.STATE_OFF))
        assertEquals(true, BluetoothStateSignal.resolve(false, BluetoothAdapter.STATE_ON))
    }

    @Test
    fun `transitional Bluetooth signals do not invent final state`() {
        assertEquals(true, BluetoothStateSignal.resolve(true, BluetoothAdapter.STATE_TURNING_OFF))
        assertEquals(false, BluetoothStateSignal.resolve(false, BluetoothAdapter.STATE_TURNING_ON))
    }

    @Test
    fun `unknown Bluetooth signal requests later platform reconciliation`() {
        assertEquals(null, BluetoothStateSignal.resolve(true, BluetoothAdapter.ERROR))
    }

    @Test
    fun `no optional radio permission is requested at app launch`() {
        assertFalse(
            OnDemandPermissionPolicy.requestBluetoothConnect(
                androidApi = 35,
                alreadyGranted = false,
                trigger = OptionalPermissionTrigger.APP_LAUNCH,
            ),
        )
        assertFalse(OnDemandPermissionPolicy.requestWifiPermission(OptionalPermissionTrigger.APP_LAUNCH))
    }

    @Test
    fun `Bluetooth permission is requested only by explicit enable action`() {
        assertTrue(
            OnDemandPermissionPolicy.requestBluetoothConnect(
                androidApi = 35,
                alreadyGranted = false,
                trigger = OptionalPermissionTrigger.ENABLE_BLUETOOTH,
            ),
        )
        assertFalse(
            OnDemandPermissionPolicy.requestBluetoothConnect(
                androidApi = 35,
                alreadyGranted = true,
                trigger = OptionalPermissionTrigger.ENABLE_BLUETOOTH,
            ),
        )
        assertFalse(
            OnDemandPermissionPolicy.requestBluetoothConnect(
                androidApi = 30,
                alreadyGranted = false,
                trigger = OptionalPermissionTrigger.ENABLE_BLUETOOTH,
            ),
        )
    }

    @Test
    fun `denied or unavailable capability never routes as direct`() {
        assertEquals(RadioActionRoute.UNAVAILABLE, WirelessActionPolicy.route(CapabilityStatus.NOT_SUPPORTED))
        assertEquals(RadioActionRoute.UNAVAILABLE, WirelessActionPolicy.route(CapabilityStatus.UNVERIFIED))
        assertEquals(RadioActionRoute.SYSTEM_UI, WirelessActionPolicy.route(CapabilityStatus.SYSTEM_UI_REQUIRED))
        assertEquals(RadioActionRoute.DIRECT, WirelessActionPolicy.route(CapabilityStatus.SUPPORTED))
    }
}
