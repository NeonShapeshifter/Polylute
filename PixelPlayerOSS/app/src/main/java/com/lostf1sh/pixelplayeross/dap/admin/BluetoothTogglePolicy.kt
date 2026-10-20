package com.lostf1sh.pixelplayeross.dap.admin

import android.Manifest
import android.os.Build

/**
 * Which permission the device-owner Bluetooth toggle needs on a given Android version (B15-F5).
 *
 * `BluetoothAdapter.enable()/disable()` are guarded by `BLUETOOTH_ADMIN` up to API 30 and by `BLUETOOTH_CONNECT`
 * from API 31, which is when the latter was introduced. B14 asked for `BLUETOOTH_CONNECT` everywhere, and on Android 11
 * (this app's minSdk 30) `checkSelfPermission` of a permission the system has never heard of is DENIED, so the toggle
 * refused every time. The manifest declares `BLUETOOTH_ADMIN` up to API 30 for the other half.
 */
internal object BluetoothTogglePolicy {

    /**
     * The permission the toggle needs on [sdkInt]. `BLUETOOTH_CONNECT` is a string constant the compiler inlines; it is
     * only returned (and only asked about) from API 31, which is what the check on [sdkInt] guarantees and lint cannot
     * see through a parameter.
     */
    @android.annotation.SuppressLint("InlinedApi")
    fun requiredPermission(sdkInt: Int): String =
        if (sdkInt >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH_ADMIN

    /** Whether the toggle may be tried on [sdkInt], given what the app holds. [holds] answers a permission name. */
    fun mayToggle(sdkInt: Int, holds: (String) -> Boolean): Boolean = holds(requiredPermission(sdkInt))
}
