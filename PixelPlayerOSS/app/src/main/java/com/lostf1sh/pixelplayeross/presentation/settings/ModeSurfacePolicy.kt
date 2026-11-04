package com.lostf1sh.pixelplayeross.presentation.settings

import com.lostf1sh.pixelplayeross.dap.mode.ProductModeState

/**
 * B39-A5: which of the settings entries exist in **App mode** (Polylute is an ordinary app on a phone with its own launcher, a status
 * bar and Android's own screens a swipe away) and which only make sense once Polylute plays the home role (Launcher, Pinned, Dedicated).
 *
 * The audit is the table in [Scope] below, one entry per row, each with the reason. It is code, not a comment, so that a page cannot grow
 * a DAP-only row in App mode without a test saying so.
 */
object ModeSurfacePolicy {
    /** Where an entry is useful. */
    enum class Scope {
        /** App mode and every DAP level: the library, the data, the diagnostics. */
        APP_AND_DAP,

        /** Only while Polylute is the home app, at any level (Launcher, Pinned, Dedicated). */
        DAP_ONLY,

        /**
         * The DAP's readouts: at any level, and also on a device that is Polylute's Device Owner without Polylute being home (the HONOR
         * after it left the DAP), because that is where its state is looked at.
         */
        DAP_OR_OWNER,

        /** Only where Lock Task is Polylute's to manage: the Dedicated level, or a Device Owner. */
        DEDICATED_ONLY,
    }

    enum class Entry(val scope: Scope, val reason: String) {
        ROOT_BATTERY(Scope.DAP_ONLY, "battery is a device readout for the DAP; an ordinary phone already owns its battery UI"),
        ROOT_BRIGHTNESS(Scope.DAP_ONLY, "only the DAP controls its own display; App mode follows the system brightness"),
        APPEARANCE_DAP_LOCK_SCREEN(Scope.DAP_ONLY, "the playback lock surface exists only while Polylute has a DAP home role"),

        // The Device page.
        DEVICE_SCREEN_TIMEOUT(Scope.DAP_ONLY, "needs WRITE_SETTINGS to change the phone's own screen-off time: on a phone with its own launcher that is Android's setting, and the app asking for a system-wide permission to do it is the wrong ask"),

        // The System page.
        SYSTEM_ANDROID_SETTINGS(Scope.DEDICATED_ONLY, "Lock Task blocks Android's screens; anywhere else they are one swipe away"),
        SYSTEM_USB_SETTINGS(Scope.DEDICATED_ONLY, "same: a shortcut past Lock Task"),
        SYSTEM_WIFI_SETTINGS(Scope.DEDICATED_ONLY, "same: a shortcut past Lock Task"),
        SYSTEM_BLUETOOTH_SETTINGS(Scope.DEDICATED_ONLY, "same: a shortcut past Lock Task"),
        SYSTEM_REBUILD_DATABASE(Scope.APP_AND_DAP, "repairs the library, which every mode has"),
        SYSTEM_DELETE_IMPORTED_LYRICS(Scope.APP_AND_DAP, "the lyrics are the app's data in every mode"),
        SYSTEM_CLEAN_UP_WITHOUT_FILE(Scope.APP_AND_DAP, "items whose file is gone exist in every mode"),
        SYSTEM_MAINTENANCE(Scope.APP_AND_DAP, "the logs, the diagnostics and the noise mark are how any install is looked at"),

        // Maintenance.
        MAINTENANCE_CURRENT_HOME(Scope.DAP_OR_OWNER, "says which app answers the HOME button, which is about Polylute being one"),
        MAINTENANCE_DEVICE_OWNER(Scope.DAP_OR_OWNER, "Device Owner, Lock Task and persistent HOME readouts: the DAP's provisioning"),
    }

    /**
     * Whether [entry] is on its page now. [DEDICATED_ONLY] is ProductModeState.offersAndroidShortcuts (B34 U6), unchanged: the
     * Dedicated level, or a Device Owner (a HONOR that left the DAP for another launcher is still owned).
     */
    fun isVisible(entry: Entry, product: ProductModeState): Boolean = when (entry.scope) {
        Scope.APP_AND_DAP -> true
        Scope.DAP_ONLY -> product.level != null
        Scope.DAP_OR_OWNER -> inDapMode(product)
        Scope.DEDICATED_ONLY -> product.offersAndroidShortcuts
    }

    /**
     * Polylute plays the home role at some level, or the device is its Device Owner: not App mode. A Device Owner that is not home (the
     * HONOR that left the DAP) is still readable in Maintenance, because that is where its state is looked at.
     */
    fun inDapMode(product: ProductModeState): Boolean = product.level != null || product.isDeviceOwner

    /** The whole Device category: at every DAP level, never in App mode. A Device Owner that is not home is App mode here. */
    fun showDeviceCategory(product: ProductModeState): Boolean = product.level != null

    /** Maintenance's two DAP readouts, from what its own device-owner reading says. */
    fun showMaintenanceDapStatus(isDeviceOwner: Boolean, isPolyluteHome: Boolean): Boolean = isDeviceOwner || isPolyluteHome
}
