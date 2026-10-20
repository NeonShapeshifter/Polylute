package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.lostf1sh.pixelplayeross.MainActivity
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapProductionLifecycle
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapRecoveryPlan
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapRecoverySnapshot
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapRecoveryTrigger
import timber.log.Timber

data class DapDeviceOwnerState(
    val isDeviceOwner: Boolean,
    val isAdminActive: Boolean,
    val isLockTaskPermitted: Boolean,
    val isPixelPlayerHome: Boolean,
    val isDedicatedModeEnabled: Boolean,
    val lockTaskModeState: Int,
    val lockTaskFeatures: Int,
    /** B34-fix2 R1: the listener asked to leave through the home picker and it is not settled yet ([DapContainmentStore.isHomeExitChosen]). */
    val isHomeExitChosen: Boolean = false,
)

data class DapPolicyRestoreResult(
    val isDeviceOwner: Boolean,
    val persistentHomeApplied: Boolean,
    val containmentConfigured: Boolean,
    val persistentHomeWritten: Boolean = false,
    val containmentPolicyWritten: Boolean = false,
    val recoveryPlan: DapRecoveryPlan? = null,
)

/**
 * Narrow entry point for supported DevicePolicyManager operations.
 *
 * Activity entry/exit remains owned by MainActivity; this class only configures supported DPM
 * policy and reports actual platform state.
 */
class DapDeviceOwnerController(context: Context) {
    private val appContext = context.applicationContext
    private val devicePolicyManager =
        appContext.getSystemService(DevicePolicyManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val containmentStore = DapContainmentStore(appContext)

    /**
     * Read directly rather than injected: this controller is constructed from `Context` alone at
     * several call sites (Activity, boot receiver, product-mode controller), and the store it needs
     * is itself a `Context`-only SharedPreferences reader. Keeping the constructor unchanged avoids
     * threading a new dependency through every containment entry point for one flag.
     */
    private val maintenanceOverrideStore = MaintenanceOverrideStore(appContext)

    private val keyguardManager: KeyguardManager? =
        appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    val adminComponent: ComponentName
        get() = ComponentName(appContext, DapDeviceAdminReceiver::class.java)

    fun currentState(): DapDeviceOwnerState = DapDeviceOwnerState(
        isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(appContext.packageName),
        isAdminActive = devicePolicyManager.isAdminActive(adminComponent),
        isLockTaskPermitted = devicePolicyManager.isLockTaskPermitted(appContext.packageName),
        isPixelPlayerHome = resolveCurrentHome() == homeComponent,
        isDedicatedModeEnabled = containmentStore.isDedicatedModeEnabled(),
        lockTaskModeState = activityManager.lockTaskModeState,
        lockTaskFeatures = if (devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) {
            devicePolicyManager.getLockTaskFeatures(adminComponent)
        } else {
            DevicePolicyManager.LOCK_TASK_FEATURE_NONE
        },
        isHomeExitChosen = containmentStore.isHomeExitChosen(),
    )

    /**
     * B34-fix2 R1: who may write the Dedicated policy and when lives in [DapContainmentReconciler]; this controller hands it the platform.
     * Declared after the members its lambdas read.
     */
    private val reconciler = DapContainmentReconciler(
        readState = ::currentState,
        persistentHomeRequired = containmentStore::isPersistentHomeEnabled,
        containmentRequired = containmentStore::isDedicatedModeEnabled,
        requiredLockTaskFeatures = {
            DapContainmentPolicy.requiredLockTaskFeatures(containmentStore.isDapLockScreenEnabled())
        },
        markHomeExit = containmentStore::setHomeExitChosen,
        setDedicatedFlag = containmentStore::setDedicatedModeEnabled,
        applyKeyguardDisabled = ::applyKeyguardDisabled,
        applyPersistentHome = ::applyPersistentHome,
        configureContainmentPolicy = ::configureContainmentPolicy,
        clearPolicies = ::disableDedicatedMode,
        clearPersistentHome = ::clearPersistentHome,
        enableDedicatedMode = ::enableDedicatedMode,
    )

    fun restoreOwnerPolicies(
        trigger: DapRecoveryTrigger = DapRecoveryTrigger.ACTIVITY_START,
    ): DapPolicyRestoreResult {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) {
            return DapPolicyRestoreResult(false, false, false)
        }
        return reconciler.restore(trigger)
    }

    /**
     * B34-fix2 R1: "Choose home app" on a Dedicated Device Owner. Records that the listener is leaving, then clears every policy and the
     * persistent HOME ([DapContainmentReconciler.leave]). True when all of it is cleared; false leaves the mark off and the policy for the
     * caller's usual recovery to put back.
     */
    fun leaveDedicatedByChoice(): Boolean = reconciler.leave()

    /**
     * B34-fix2 R1: the picker is over (an activity start or resume): another launcher is home and the exit stands, or Polylute is still home
     * (cancelled, or picked again) and the DAP policy is put back. [HomeExitSettlement.NONE] when nothing was waiting.
     */
    internal fun settleHomeExit(): HomeExitSettlement = reconciler.settle()

    fun enableDedicatedMode(): Boolean {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return false
        // asking for the DAP back is the listener's own word on it: whatever exit was waiting to be settled is over
        containmentStore.setHomeExitChosen(false)
        val homeApplied = applyPersistentHome()
        val configured = configureContainmentPolicy()
        if (homeApplied && configured) containmentStore.setDedicatedModeEnabled(true)
        return homeApplied && configured
    }

    /** Avoids rewriting stable Device Owner policy during the normal Maintenance exit path. */
    fun ensureDedicatedModeReady(): Boolean {
        val state = currentState()
        return if (!state.isHomeExitChosen &&
            DapContainmentPolicy.isRelockPolicyReady(
                state,
                containmentStore.isDapLockScreenEnabled(),
                persistentHomeEnabled = containmentStore.isPersistentHomeEnabled(),
            )
        ) {
            true
        } else {
            enableDedicatedMode()
        }
    }

    /**
     * B35-fix1 R2: the policy "Volver al DAP" puts back: [ensureDedicatedModeReady], and then the persistent HOME again whatever the record says.
     * The record only says this app wrote the entry once; Android cannot be asked whether it is in force (an OEM reset of preferred activities
     * leaves the record true and the entry gone), and this is the listener's explicit request. Adding the same entry again changes nothing:
     * the policy engine keeps it as one keyed policy and enforces only a change. The Maintenance exit keeps [ensureDedicatedModeReady]'s cheaper
     * contract (nothing is rewritten while it is all in place).
     */
    fun reassertDedicatedPolicy(): Boolean {
        if (!ensureDedicatedModeReady()) return false
        return applyPersistentHome()
    }

    /**
     * F-08, last resort for Maintenance: takes this app out of the Lock Task allowlist so the platform
     * ends the lock itself, for the case where the activity's own `stopLockTask()` did not.
     *
     * Only the allowlist is touched: the containment flag, the persistent HOME and the Lock Task mask
     * stay as they are, so the ordinary Maintenance exit (`ensureDedicatedModeReady` ->
     * `enableDedicatedMode`) and every restore trigger put the allowlist back. Returns whether the app
     * is no longer permitted (and so no longer held).
     */
    fun releaseLockTaskForMaintenance(): Boolean {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return false
        devicePolicyManager.setLockTaskPackages(adminComponent, emptyArray())
        return !devicePolicyManager.isLockTaskPermitted(appContext.packageName)
    }

    fun disableDedicatedMode(): Boolean {
        containmentStore.setDedicatedModeEnabled(false)
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return true
        // Releasing the wake surface belongs to leaving Dedicated Mode. `shouldDisableKeyguard`
        // reads the flag cleared above, so this restores the OEM lock behaviour instead of leaving
        // the appliance holding a surface it no longer manages.
        applyKeyguardDisabled()
        devicePolicyManager.setLockTaskFeatures(
            adminComponent,
            DevicePolicyManager.LOCK_TASK_FEATURE_NONE,
        )
        devicePolicyManager.setLockTaskPackages(adminComponent, emptyArray())
        return !devicePolicyManager.isLockTaskPermitted(appContext.packageName)
    }

    fun configureContainmentPolicy(): Boolean {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return false
        devicePolicyManager.setLockTaskPackages(
            adminComponent,
            arrayOf(appContext.packageName),
        )
        val desired = DapContainmentPolicy.requiredLockTaskFeatures(
            dapLockScreenEnabled = containmentStore.isDapLockScreenEnabled(),
            lockScreenIntegrationKilled = maintenanceOverrideStore
                .current().lockScreenIntegrationDisabled(),
        )
        devicePolicyManager.setLockTaskFeatures(adminComponent, desired)
        return devicePolicyManager.isLockTaskPermitted(appContext.packageName) &&
            devicePolicyManager.getLockTaskFeatures(adminComponent) == desired
    }

    fun applyPersistentHome(): Boolean {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return false
        containmentStore.setPersistentHomeEnabled(true)
        devicePolicyManager.addPersistentPreferredActivity(
            adminComponent,
            homeIntentFilter(),
            homeComponent,
        )
        return resolveCurrentHome() == homeComponent
    }

    fun clearPersistentHome(): Boolean {
        containmentStore.setPersistentHomeEnabled(false)
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return false
        devicePolicyManager.clearPackagePersistentPreferredActivities(
            adminComponent,
            appContext.packageName,
        )
        return true
    }

    fun isDedicatedModeEnabled(): Boolean = containmentStore.isDedicatedModeEnabled()

    /**
     * B34: clears every Device Owner policy this app sets, and says whether Android agrees each is gone: the dedicated flag, the keyguard
     * hold (which reads the flag cleared first), the Lock Task mask and the allowlist. The persistent HOME is [clearPersistentHome]'s.
     * Unlike [disableDedicatedMode] it does not trust the calls: it reads the keyguard hold and the allowlist back.
     *
     * B35-fix1 R1: it no longer reads the mask back. On Android 14 and 15 the policy engine keeps the packages and the mask in one
     * `LockTaskPolicy`, an empty allowlist removes that whole policy, and `getLockTaskFeatures` then answers `GLOBAL_ACTIONS` ("consistent with
     * pre-P behaviour"), never the `NONE` just written: the release ended `POLICY_NOT_CLEARED` every time. The mask is still set to `NONE` first,
     * which is what scrubs it on the legacy store. What is judged is what every API answers alike ([DapContainmentPolicy.unclearedOwnerPolicies]),
     * and a refusal logs which check failed.
     */
    fun clearAllOwnerPolicies(): Boolean {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return false
        containmentStore.setDedicatedModeEnabled(false)
        val keyguardReleased = applyKeyguardDisabled()
        devicePolicyManager.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        devicePolicyManager.setLockTaskPackages(adminComponent, emptyArray())
        val uncleared = DapContainmentPolicy.unclearedOwnerPolicies(
            keyguardReleased = keyguardReleased,
            lockTaskPermitted = devicePolicyManager.isLockTaskPermitted(appContext.packageName),
            lockTaskPackages = devicePolicyManager.getLockTaskPackages(adminComponent).toList(),
        )
        if (uncleared.isNotEmpty()) Timber.tag(TAG).w("Owner policies not cleared; failed: %s", uncleared.joinToString(", "))
        return uncleared.isEmpty()
    }

    /**
     * B34-fix1 R2: whether this app is the Device Owner, as one specific query with three answers. A query that throws is
     * [OwnerPresence.UNKNOWN], never "absent": the release is announced as done only on a confirmed absence.
     */
    internal fun ownerPresence(): OwnerPresence = try {
        if (devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) OwnerPresence.PRESENT else OwnerPresence.ABSENT
    } catch (error: Exception) {
        Timber.tag(TAG).w(error, "Unable to read whether this app is the Device Owner")
        OwnerPresence.UNKNOWN
    }

    /**
     * B34-fix1 R1: what the device looks like right now, for the release to put back if it fails. Throws if Android cannot be read; the
     * release then touches nothing.
     */
    internal fun ownerStart(): DapOwnerStart {
        val state = currentState()
        return DapOwnerStart(
            dedicatedFlag = state.isDedicatedModeEnabled,
            persistentHomePreference = containmentStore.isPersistentHomeEnabled(),
            homeWasPolylute = state.isPixelPlayerHome,
            lockTaskPermitted = state.isLockTaskPermitted,
            lockHeld = state.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED,
        )
    }

    /**
     * B34-fix1 R1: puts back what the release removed, from [start] and not from the product mode (the mode is inferred from the very
     * signals the release cleared). Only what was in place is written: a device that was not contained gets nothing applied, and a
     * launcher the owner chose for good stays chosen. Each piece is read back; true only when Android agrees with every one.
     */
    internal fun restoreOwnerStart(start: DapOwnerStart): Boolean {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return false
        if (start.dedicatedFlag) containmentStore.setDedicatedModeEnabled(true)
        val homeBack = !start.homeWasPolylute || applyPersistentHome()
        val allowlistBack = !start.lockTaskPermitted || configureContainmentPolicy()
        // the keyguard hold reads the flag put back above; it is the same decision the activity-start restore takes
        val keyguardBack = !start.dedicatedFlag || applyKeyguardDisabled()
        // `applyPersistentHome` writes the preference true; the start value is what stays
        containmentStore.setPersistentHomeEnabled(start.persistentHomePreference)
        return containmentStore.isDedicatedModeEnabled() == start.dedicatedFlag &&
            homeBack && allowlistBack && keyguardBack
    }

    /**
     * B34: gives up the Device Owner role (`clearDeviceOwnerApp`, the supported call for exactly this; it takes the owner's policies with
     * it). The caller clears the app's own policies first ([clearAllOwnerPolicies], [clearPersistentHome]). True when Android no longer
     * lists this app as the Device Owner. The release does not trust this answer: [ownerPresence] is asked after it.
     */
    @Suppress("DEPRECATION")
    fun clearDeviceOwner(): Boolean {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return true
        devicePolicyManager.clearDeviceOwnerApp(appContext.packageName)
        return !devicePolicyManager.isDeviceOwnerApp(appContext.packageName)
    }

    /** B34: once the owner is gone the receiver can stay an ordinary active admin; take that away too. True when it is not active. */
    fun removeActiveAdminIfAny(): Boolean {
        if (!devicePolicyManager.isAdminActive(adminComponent)) return true
        devicePolicyManager.removeActiveAdmin(adminComponent)
        return !devicePolicyManager.isAdminActive(adminComponent)
    }

    /** B34: the HOME activity Android resolves right now (null when nothing does). */
    fun currentHome(): ComponentName? = resolveCurrentHome()

    /** B34: whether Polylute is the HOME Android resolves. */
    fun isPolyluteHome(): Boolean = resolveCurrentHome() == homeComponent

    fun lockTaskModeState(): Int = activityManager.lockTaskModeState

    @Suppress("DEPRECATION")
    fun setWifiEnabled(enabled: Boolean): Boolean {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return false
        return wifiManager.setWifiEnabled(enabled)
    }

    /**
     * Turns Bluetooth on or off as device owner. False whenever it did not happen, for any reason, and never throws.
     *
     * Being device owner is not holding the permission: from Android 12 `BluetoothAdapter.enable()/disable()` need the
     * runtime permission `BLUETOOTH_CONNECT` and throw `SecurityException` without it (lint MissingPermission, audit
     * §5.2); up to Android 11 (minSdk 30) the permission is `BLUETOOTH_ADMIN`, which did not need asking for and which
     * `BLUETOOTH_CONNECT` does not exist for ([BluetoothTogglePolicy], B15-F5). Both are answered as "not done", which the
     * caller already turns into the confirmation path or a rejection notice. Where the platform has made the call a
     * no-op for non-privileged apps (it returns false from Android 13), the answer is the same.
     */
    @Suppress("DEPRECATION")
    fun setBluetoothEnabled(enabled: Boolean): Boolean {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return false
        val needed = BluetoothTogglePolicy.requiredPermission(Build.VERSION.SDK_INT)
        if (!BluetoothTogglePolicy.mayToggle(Build.VERSION.SDK_INT) {
                appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            Log.w(TAG, "Bluetooth toggle refused: $needed is not granted")
            return false
        }
        val adapter = bluetoothManager.adapter ?: return false
        return try {
            if (enabled) adapter.enable() else adapter.disable()
        } catch (denied: SecurityException) {
            Log.w(TAG, "Bluetooth toggle denied by the platform", denied)
            false
        }
    }

    private val homeComponent: ComponentName
        get() = ComponentName(appContext, MainActivity::class.java)

    private fun homeIntentFilter() = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    private fun resolveCurrentHome(): ComponentName? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = appContext.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
        return ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
    }

    // --- Appliance wake-surface policy -----------------------------------------------------------

    /**
     * What is truthfully true about the system Keyguard right now.
     *
     * Reported rather than assumed: a device with a real credential is a *refusal*, not a silent
     * fallback, and the log has to distinguish a policy the app applied from one it was denied.
     */
    /** Mirrors the DAP Lock Screen setting into the store the policy is reconciled from. */
    fun setDapLockScreenEnabled(enabled: Boolean) {
        containmentStore.setDapLockScreenEnabled(enabled)
    }

    fun keyguardState(): DapKeyguardState {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) {
            return DapKeyguardState.BLOCKED_NOT_DEVICE_OWNER
        }
        if (keyguardManager?.isDeviceSecure == true) {
            return DapKeyguardState.BLOCKED_SECURE_CREDENTIAL
        }
        val features = devicePolicyManager.getLockTaskFeatures(adminComponent)
        return if (features and DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD == 0) {
            DapKeyguardState.DISABLED_BY_LOCK_TASK_POLICY
        } else {
            DapKeyguardState.SYSTEM_KEYGUARD_NOT_MANAGED
        }
    }

    /**
     * Re-derives the desired Lock Task feature mask from persistent product state and applies it.
     *
     * Device Owner policy outlives the process, so this deliberately consults no in-memory flag: it
     * reads Dedicated Mode and the DAP Lock Screen setting and reconciles. Running it twice in a row
     * is a no-op the second time, which is what makes it safe to call on every activity start.
     *
     * Returns the state actually in force afterwards.
     */
    fun reconcileKeyguardPolicy(): DapKeyguardState {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) {
            return DapKeyguardState.BLOCKED_NOT_DEVICE_OWNER
        }
        if (!containmentStore.isDedicatedModeEnabled()) {
            // App Mode: no appliance wake surface, and the OEM's lock behaviour is left alone.
            applyKeyguardDisabled()
            return DapKeyguardState.SYSTEM_KEYGUARD_NOT_MANAGED
        }
        return try {
            val containmentConfigured = configureContainmentPolicy()
            val keyguardHeld = applyKeyguardDisabled()
            if (!containmentConfigured || !keyguardHeld) DapKeyguardState.FAILED else keyguardState()
        } catch (security: SecurityException) {
            DapKeyguardState.FAILED
        }
    }

    /**
     * Holds the platform Keyguard down, or releases it, according to [DapKeyguardPolicy].
     *
     * Lock Task suppresses the Keyguard only while Lock Task is held, and Maintenance deliberately
     * suspends it — so relying on the Lock Task mask alone left the OEM lock screen in charge for
     * the whole Maintenance session. This is the second, Lock-Task-independent half of the same
     * decision, and it is idempotent: the same state always writes the same value.
     *
     * Reads no in-memory flag, for the same reason [reconcileKeyguardPolicy] does not: Device Owner
     * policy outlives this process, so it has to be re-derivable from persistent product state.
     *
     * Returns whether the platform now agrees with the decision. A device with a real credential
     * resolves to "do not disable" and reports success for *that* outcome — the refusal is the
     * intended result, not a failure.
     */
    private fun applyKeyguardDisabled(): Boolean {
        if (!devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) return false
        val disable = DapKeyguardPolicy.shouldDisableKeyguard(
            isDeviceOwner = true,
            dedicatedModeEnabled = containmentStore.isDedicatedModeEnabled(),
            dapLockScreenEnabled = containmentStore.isDapLockScreenEnabled(),
            lockScreenIntegrationKilled = maintenanceOverrideStore
                .current().lockScreenIntegrationDisabled(),
            isDeviceSecure = keyguardManager?.isDeviceSecure == true,
        )
        return runCatching {
            devicePolicyManager.setKeyguardDisabled(adminComponent, disable)
        }.getOrElse { error ->
            Log.w(TAG, "Unable to set keyguard disabled=$disable", error)
            false
        }
    }

    private companion object {
        const val TAG = "DapDeviceOwner"
    }
}
