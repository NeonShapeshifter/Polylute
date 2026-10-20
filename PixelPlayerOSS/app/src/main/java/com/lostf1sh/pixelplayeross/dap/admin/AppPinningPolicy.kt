package com.lostf1sh.pixelplayeross.dap.admin

import android.content.Intent
import android.provider.Settings
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeState

/** Whether Android's "App pinning" switch lets an app ask to be pinned (B34). */
enum class PinningReadiness {
    /** The switch is on: `startLockTask()` asks the listener to confirm. */
    READY,

    /** The switch is off: Android will not pin. The page says so and offers the right Settings screen. */
    DISABLED_IN_SETTINGS,

    /** The setting could not be read on this device: ask anyway and let Android answer. */
    UNKNOWN,
}

/**
 * The Pinned level (B34): Android's own app pinning, with no Device Owner, no allowlist and no adb.
 *
 * `startLockTask()` from an app that is not on a Device Owner's allowlist does not lock anything by itself: Android asks the listener to
 * confirm and, if they do, pins the task (`LOCK_TASK_MODE_PINNED`). The listener unpins with the system gesture. Whether it happened is
 * what Android reads afterwards ([ProductModeState.level]), never what the app asked for.
 *
 * Pure: the platform reads are arguments.
 */
object AppPinningPolicy {
    /** `Settings.System.LOCK_TO_APP_ENABLED` (hidden constant, stable key): 1 = the "App pinning" switch is on. */
    const val SETTING_KEY = "lock_to_app_enabled"

    fun readiness(lockToAppEnabled: Int?): PinningReadiness = when (lockToAppEnabled) {
        1 -> PinningReadiness.READY
        0 -> PinningReadiness.DISABLED_IN_SETTINGS
        else -> PinningReadiness.UNKNOWN
    }

    /**
     * Whether "Fijar Polylute" does anything: Polylute is the home app, it is not a Device Owner (that device is Dedicated, whatever is asked),
     * it is not pinned already, and Android's switch is not known to be off.
     */
    fun canRequest(readiness: PinningReadiness, state: ProductModeState): Boolean =
        state.isPolyluteHome &&
            !state.isDeviceOwner &&
            state.level != ProductLevel.PINNED &&
            readiness != PinningReadiness.DISABLED_IN_SETTINGS

    /** The screen that holds Android's "App pinning" switch: the Security settings (there is no portable deep link to the switch itself). */
    fun securitySettingsIntent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
}
