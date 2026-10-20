package com.lostf1sh.pixelplayeross.dap.mode

import android.app.ActivityManager

/**
 * What the listener asked for. A preference: it never makes anything true on its own ([ProductModeResolver] decides what is).
 *
 * B34: the three levels Polylute can run at when it is the home app. [APP] is "nothing locked" (what the listener gets by choosing Polylute
 * as home, and the default); [PINNED] is Android's app pinning; [DEDICATED] is Device Owner + Lock Task. The stored names are unchanged,
 * so a preference written by an earlier build still reads.
 */
enum class ProductModeRequest {
    APP,
    PINNED,
    DEDICATED,
}

/**
 * What Android confirms, resolved from evidence (B34). One model: it was `APP | DEDICATED_SETUP_REQUIRED | DEDICATED_ACTIVE` and
 * gained the two levels in between.
 *
 *  - [APP]: Polylute is not the home app. A normal app on a phone with its own launcher.
 *  - [LAUNCHER]: Polylute is the home app and nothing is locked.
 *  - [PINNED]: Polylute is the home app and Android's app pinning holds it (`LOCK_TASK_MODE_PINNED`).
 *  - [DEDICATED_SETUP_REQUIRED]: the listener asked for Dedicated and Android has not confirmed it (the existing partial state).
 *  - [DEDICATED_ACTIVE]: Device Owner evidence (see [DedicatedProvisioningEvidence]); [ProductModeState.isLockHeld] says whether Lock Task
 *    is in force at this moment.
 */
enum class EffectiveProductMode {
    APP,
    LAUNCHER,
    PINNED,
    DEDICATED_SETUP_REQUIRED,
    DEDICATED_ACTIVE,
}

/** The three levels the "Inicio y modo DAP" page offers when Polylute is the home app. */
enum class ProductLevel {
    LAUNCHER,
    PINNED,
    DEDICATED,
}

data class DedicatedProvisioningEvidence(
    val isDeviceOwner: Boolean,
    val isPixelPlayerHome: Boolean,
    val isDedicatedModeEnabled: Boolean,
    val isLockTaskPermitted: Boolean,
    /** `ActivityManager.getLockTaskModeState()`: what Android says is held right now (B34: this is the evidence for Pinned). */
    val lockTaskModeState: Int = ActivityManager.LOCK_TASK_MODE_NONE,
    /**
     * B34-fix2 R1: the listener asked to leave Dedicated through the home picker and the picker has not settled it. A recorded fact that
     * outranks the inference below: Polylute is still the home app until something else is picked, and "Device Owner + home" would read
     * that as a policy the app lost and must heal.
     */
    val homeExitChosen: Boolean = false,
) {
    val provesExistingDedicatedProvisioning: Boolean
        get() = isDeviceOwner && !homeExitChosen && (
            isPixelPlayerHome ||
                isDedicatedModeEnabled ||
                isLockTaskPermitted
            )
}

data class ProductModeState(
    val requestedMode: ProductModeRequest,
    val effectiveMode: EffectiveProductMode,
    val isDeviceOwner: Boolean,
    val hasDedicatedProvisioningEvidence: Boolean,
    /** Whether Polylute is the default home app right now (a fact, not a preference). */
    val isPolyluteHome: Boolean = false,
    val lockTaskModeState: Int = ActivityManager.LOCK_TASK_MODE_NONE,
) {
    val isDedicatedActive: Boolean
        get() = effectiveMode == EffectiveProductMode.DEDICATED_ACTIVE

    /**
     * The level the evidence places Polylute at while it plays the home role, or null when it does not (a normal app). It follows the
     * Device Owner evidence, which says the device is provisioned as Dedicated whether or not Android is holding the lock this second:
     * what the lock surface and the root Back guard follow, so they do not flicker with Lock Task. [level] is what the page says.
     */
    private val homeRoleLevel: ProductLevel?
        get() = when (effectiveMode) {
            EffectiveProductMode.DEDICATED_ACTIVE -> ProductLevel.DEDICATED
            EffectiveProductMode.PINNED -> ProductLevel.PINNED
            EffectiveProductMode.LAUNCHER -> ProductLevel.LAUNCHER
            EffectiveProductMode.DEDICATED_SETUP_REQUIRED -> if (isPolyluteHome) ProductLevel.LAUNCHER else null
            EffectiveProductMode.APP -> null
        }

    /**
     * The level Android confirms, or null when Polylute is not the home app (then there are no levels: it is a normal app).
     *
     * B34-fix2 R2: **Dedicated needs [isLockHeld]**. Device Owner + home is the evidence that the device is provisioned (it is what heals a
     * lost policy), not that it is locked; with Polylute home and Android reading no Lock Task the level in force is Launcher, and the
     * page said "Dedicado" beside "Sin bloqueo ahora". A Dedicated request Android has not confirmed is [ProductLevel.LAUNCHER] here too.
     */
    val level: ProductLevel?
        get() = when (val level = homeRoleLevel) {
            ProductLevel.DEDICATED -> when {
                isLockHeld -> ProductLevel.DEDICATED
                isPolyluteHome -> ProductLevel.LAUNCHER
                else -> null
            }
            else -> level
        }

    /** What the listener asked for, as a level. Shown beside [level], never instead of it. */
    val requestedLevel: ProductLevel
        get() = when (requestedMode) {
            ProductModeRequest.APP -> ProductLevel.LAUNCHER
            ProductModeRequest.PINNED -> ProductLevel.PINNED
            ProductModeRequest.DEDICATED -> ProductLevel.DEDICATED
        }

    /** Lock Task in force (Android reads LOCKED). In Dedicated this is "contained"; false during a Maintenance window or a failed lock. */
    val isLockHeld: Boolean
        get() = lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED

    /** Whether the DAP lock surface may take a wake: Polylute is the home app, at any level (B34). */
    val allowsLockSurface: Boolean
        get() = homeRoleLevel != null

    /**
     * Whether Android's own shortcuts (Android Settings, USB, Wi-Fi, Bluetooth) are offered on the System page (B34, U6).
     *
     * Only Lock Task blocks them, so they matter only where Lock Task is Polylute's to manage: while the Dedicated level is active, or
     * while the device is a Device Owner (that covers the HONOR after it left the DAP for another launcher: still owned, not dedicated).
     * On a phone at Launcher or Pinned level, or in App Mode, the listener reaches Android normally (in Pinned, after unpinning).
     */
    val offersAndroidShortcuts: Boolean
        get() = isDedicatedActive || isDeviceOwner

    /**
     * Whether the protected Maintenance entry exists at all.
     *
     * **Not gated on Dedicated Mode.** It used to be `isDedicatedActive`, which made recovery
     * depend on the very thing most likely to be broken: a device whose Dedicated provisioning is
     * half-applied, whose Device Owner is gone, or whose lock task will not release is exactly the
     * device where a user needs Maintenance, and the old rule offered it only after that device had
     * already succeeded. Setup-required resolves to APP, so that device had no entry at all.
     *
     * Maintenance is not a public surface because of this flag — it is not a Settings category, it
     * is reached by a deliberate gesture, and the PIN is the authorization boundary. The mode only
     * ever decided *which containment to suspend*, which is a property of the transitions
     * (see `MainActivity`), not of whether the entry exists.
     */
    val isMaintenanceAvailable: Boolean
        get() = true
}

/** Pure source-of-truth rules. Android state outranks a stale or absent preference. */
object ProductModeResolver {
    fun resolve(
        requestedMode: ProductModeRequest?,
        evidence: DedicatedProvisioningEvidence,
    ): ProductModeState {
        if (evidence.provesExistingDedicatedProvisioning) {
            return ProductModeState(
                requestedMode = ProductModeRequest.DEDICATED,
                effectiveMode = EffectiveProductMode.DEDICATED_ACTIVE,
                isDeviceOwner = true,
                hasDedicatedProvisioningEvidence = true,
                isPolyluteHome = evidence.isPixelPlayerHome,
                lockTaskModeState = evidence.lockTaskModeState,
            )
        }

        val requested = requestedMode ?: ProductModeRequest.APP
        val home = evidence.isPixelPlayerHome
        val effective = when {
            // pinned is what Android says it is, whatever was asked for; and only Polylute-as-home is a level at all
            home && evidence.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_PINNED -> EffectiveProductMode.PINNED
            requested == ProductModeRequest.DEDICATED -> EffectiveProductMode.DEDICATED_SETUP_REQUIRED
            home -> EffectiveProductMode.LAUNCHER
            else -> EffectiveProductMode.APP
        }
        return ProductModeState(
            requestedMode = requested,
            effectiveMode = effective,
            isDeviceOwner = evidence.isDeviceOwner,
            hasDedicatedProvisioningEvidence = false,
            isPolyluteHome = home,
            lockTaskModeState = evidence.lockTaskModeState,
        )
    }

    /**
     * What the stored request becomes when Polylute is not the home app (B34): a Pinned or Dedicated request does not outlive the home
     * role, so choosing Polylute as home again later starts at Launcher and nothing surprising happens (a pin the listener forgot about, a
     * lock). Null means "leave it". Device Owner evidence keeps its request: that device is Dedicated whatever the preference says (the
     * HONOR's policy re-asserts HOME), and the resolver reads the evidence, not this.
     */
    fun requestAfterLosingHome(
        requestedMode: ProductModeRequest?,
        evidence: DedicatedProvisioningEvidence,
    ): ProductModeRequest? = when {
        evidence.isPixelPlayerHome -> null
        evidence.provesExistingDedicatedProvisioning -> null
        requestedMode == null || requestedMode == ProductModeRequest.APP -> null
        else -> ProductModeRequest.APP
    }

    /** Whether a pin Polylute holds should be released because Polylute is no longer the home app. */
    fun shouldReleasePin(evidence: DedicatedProvisioningEvidence): Boolean =
        !evidence.isPixelPlayerHome && evidence.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_PINNED
}
