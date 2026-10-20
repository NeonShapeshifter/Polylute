package com.lostf1sh.pixelplayeross.dap.compat

import android.bluetooth.BluetoothAdapter

/** Resolves Android's state broadcast without re-reading an adapter during a transitional state. */
object BluetoothStateSignal {
    fun resolve(current: Boolean, broadcastState: Int): Boolean? = when (broadcastState) {
        BluetoothAdapter.STATE_ON -> true
        BluetoothAdapter.STATE_OFF -> false
        BluetoothAdapter.STATE_TURNING_ON,
        BluetoothAdapter.STATE_TURNING_OFF -> current
        else -> null
    }
}

enum class RadioActionRoute {
    DIRECT,
    SYSTEM_UI,
    UNAVAILABLE,
}

/** Pure user-action routing; evaluating this policy never requests a permission or changes a radio. */
object WirelessActionPolicy {
    fun route(status: CapabilityStatus): RadioActionRoute = when (status) {
        CapabilityStatus.SUPPORTED -> RadioActionRoute.DIRECT
        CapabilityStatus.SUPPORTED_WITH_CONFIRMATION,
        CapabilityStatus.SYSTEM_UI_REQUIRED -> RadioActionRoute.SYSTEM_UI
        CapabilityStatus.NOT_SUPPORTED,
        CapabilityStatus.NOT_APPLICABLE,
        CapabilityStatus.UNVERIFIED -> RadioActionRoute.UNAVAILABLE
    }
}

enum class OptionalPermissionTrigger {
    APP_LAUNCH,
    ENABLE_BLUETOOTH,
    MANAGE_BLUETOOTH,
    MANAGE_WIFI,
}

/** Optional radio permissions are requested only by the explicit feature action that needs them. */
object OnDemandPermissionPolicy {
    fun requestBluetoothConnect(
        androidApi: Int,
        alreadyGranted: Boolean,
        trigger: OptionalPermissionTrigger,
    ): Boolean = androidApi >= 31 &&
        !alreadyGranted &&
        trigger == OptionalPermissionTrigger.ENABLE_BLUETOOTH

    fun requestWifiPermission(trigger: OptionalPermissionTrigger): Boolean = false
}
