package com.lostf1sh.pixelplayeross.dap.admin

import android.app.admin.DevicePolicyManager

/**
 * Who owns the wake surface of the dedicated appliance.
 *
 * This is the ONE authority for that question. Nothing else in the app decides it, and no
 * `DevicePolicyManager` keyguard call is made outside [DapDeviceOwnerController], which consults
 * this policy first.
 *
 * ## The mechanism
 *
 * Android's Lock Task mode disables the system Keyguard **unless** the admin asks for it back with
 * `LOCK_TASK_FEATURE_KEYGUARD`. This app was asking for it back — the flag was part of the Lock
 * Task feature mask — which is exactly why every wake showed the OEM swipe Keyguard first and the
 * DAP surface only after it was cleared.
 *
 * Removing that one bit is the smallest official change that makes the app's own surface the first
 * thing seen after wake. It is not a Keyguard bypass: it is declining to opt back in to a lock
 * screen that Lock Task removes by default.
 *
 * ## What this is not
 *
 * The DAP Lock Screen is an **appliance lock screen**. It is not authentication, it does not
 * protect secrets, and it does not replace a PIN, pattern, password or biometric. On a device with
 * a real credential the system Keyguard stays authoritative and this policy does not apply.
 */
object DapKeyguardPolicy {

    /**
     * Lock Task features for a dedicated appliance whose own surface owns the wake.
     *
     * Every bit here was already intended; only `LOCK_TASK_FEATURE_KEYGUARD` is absent.
     */
    val APPLIANCE_WAKE_FEATURES: Int =
        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO

    /**
     * The previous mask, which opted the system Keyguard back into the wake flow.
     *
     * Kept as a named constant because it is what the app falls back to whenever the DAP Lock
     * Screen is not enabled — the OEM lock behaviour is restored, not merely abandoned.
     */
    val SYSTEM_KEYGUARD_IN_WAKE_FEATURES: Int =
        APPLIANCE_WAKE_FEATURES or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD

    /**
     * The Lock Task feature mask Dedicated Mode should be running.
     *
     * A pure function of product state so it can be tested without a device, and so reconciliation
     * can be idempotent: the same inputs always produce the same mask.
     *
     * [lockScreenIntegrationKilled] is the Maintenance feature kill. When set, the mask reverts to
     * the OEM-keyguard-inclusive form — which is precisely the "feature not enabled" behaviour — so
     * killing the integration cannot leave the appliance holding a wake surface it no longer manages.
     */
    fun desiredLockTaskFeatures(
        dapLockScreenEnabled: Boolean,
        lockScreenIntegrationKilled: Boolean = false,
    ): Int =
        if (dapLockScreenEnabled && !lockScreenIntegrationKilled) {
            APPLIANCE_WAKE_FEATURES
        } else {
            SYSTEM_KEYGUARD_IN_WAKE_FEATURES
        }

    /**
     * Whether the appliance may take over the wake surface at all.
     *
     * Device Owner is required because the feature is implemented through Device Owner policy. A
     * secure credential blocks it outright: replacing a real lock screen with a playback surface
     * that performs no authentication would weaken the device, and that is never done here.
     */
    fun canOwnWakeSurface(
        isDeviceOwner: Boolean,
        dedicatedModeEnabled: Boolean,
        dapLockScreenEnabled: Boolean,
        isDeviceSecure: Boolean,
    ): Boolean = isDeviceOwner &&
        dedicatedModeEnabled &&
        dapLockScreenEnabled &&
        !isDeviceSecure

    /**
     * Whether the platform Keyguard should be held disabled right now.
     *
     * [canOwnWakeSurface] answers *whether* the appliance owns the wake surface. This answers
     * whether that ownership has to be held by the Device Owner Keyguard API as well as by the Lock
     * Task mask, and it is the same question with the Maintenance feature kill folded in — so a
     * killed integration cannot be honoured by one caller and forgotten by another.
     *
     * ## Why Lock Task alone was not enough
     *
     * Omitting `LOCK_TASK_FEATURE_KEYGUARD` suppresses the Keyguard only while Lock Task is *held*.
     * Maintenance must suspend Lock Task so Android Settings, USB preferences and the HOME selector
     * can be launched at all, and on a device whose Keyguard is present but not secure that hands
     * the wake surface back to the OEM for the entire Maintenance session. Measured on the HONOR
     * LGN-NX3: with Lock Task `LOCKED`, screen-off/wake never showed the Keyguard; in Maintenance
     * (Lock Task `NONE`) every wake showed the HONOR lock screen first, which is the defect this
     * exists to remove.
     *
     * The credential refusal is unchanged and absolute: a device with a real credential never has
     * its Keyguard disabled, because replacing an authenticated lock screen with a playback surface
     * that performs no authentication would weaken the device.
     */
    fun shouldDisableKeyguard(
        isDeviceOwner: Boolean,
        dedicatedModeEnabled: Boolean,
        dapLockScreenEnabled: Boolean,
        lockScreenIntegrationKilled: Boolean = false,
        isDeviceSecure: Boolean,
    ): Boolean = canOwnWakeSurface(
        isDeviceOwner = isDeviceOwner,
        dedicatedModeEnabled = dedicatedModeEnabled,
        // Inlined rather than reached for through `DapLockScreenPolicy.effectiveEnabled`: this
        // package must not depend on the presentation layer, and it is the same fold
        // `desiredLockTaskFeatures` already applies to the mask.
        dapLockScreenEnabled = dapLockScreenEnabled && !lockScreenIntegrationKilled,
        isDeviceSecure = isDeviceSecure,
    )
}

/**
 * What is truthfully true about the system Keyguard, as far as this app can tell.
 *
 * The distinction that matters: [DISABLED_BY_LOCK_TASK_POLICY] is the app having declined to opt
 * the Keyguard back in, which is benign and reversible. The `BLOCKED_*` states are refusals — the
 * app may not do this, and says so rather than pretending otherwise.
 */
enum class DapKeyguardState {
    /** No Dedicated Mode, or the DAP Lock Screen is off: the OEM owns the wake flow, unchanged. */
    SYSTEM_KEYGUARD_NOT_MANAGED,

    /** The appliance owns the wake surface because Lock Task does not re-enable the Keyguard. */
    DISABLED_BY_LOCK_TASK_POLICY,

    /** A real credential is configured, so the system Keyguard stays authoritative. */
    BLOCKED_SECURE_CREDENTIAL,

    /** Not Device Owner, so no Device Owner policy can be applied. */
    BLOCKED_NOT_DEVICE_OWNER,

    /** Policy was requested and the platform refused, or threw. */
    FAILED,
}
