package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import timber.log.Timber

/**
 * What the platform keeps of this app's Lock Task policy, in the two shapes Android has had (B35-fix1 R1).
 *
 * [policyEngine] true is Android 14 and 15 (`DevicePolicyManagerService` of `android-14.0.0_r1` and `android-15.0.0_r1`, which both keep it in
 * the policy engine): one `LockTaskPolicy` per admin holding the packages **and** the mask. Clearing the packages removes the whole policy
 * (`setLockTaskPackages`: `if (policy.getPackages().isEmpty()) removeLocalPolicy(...)`), and with no policy `getLockTaskFeatures` answers
 * `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` ("consistent with pre-P behaviour"), never the `NONE` the admin wrote. false is the legacy per-user store
 * of the older releases, where the mask is kept apart from the packages and reads back as written.
 */
object OwnerDpmModel {
    var policyEngine = true
    var owner = true
    var adminActive = true

    /** The platform ignores `setLockTaskPackages(empty)`: the app stays permitted (a real refusal). */
    var keepsAllowlist = false

    /** `clearDeviceOwnerApp` is accepted and changes nothing. */
    var clearOwnerIgnored = false

    private var hasPolicy = false
    private var packages: Set<String> = emptySet()
    private var flags = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
    private var legacyFlags = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS

    var keyguardDisabled = false
    var persistentEntry = false
    val calls = mutableListOf<String>()

    fun reset(engine: Boolean) {
        policyEngine = engine
        owner = true
        adminActive = true
        keepsAllowlist = false
        clearOwnerIgnored = false
        hasPolicy = false
        packages = emptySet()
        flags = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        legacyFlags = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        keyguardDisabled = false
        persistentEntry = false
        calls.clear()
    }

    fun setPackages(list: Set<String>) {
        calls += "setLockTaskPackages${list.toList()}"
        if (keepsAllowlist && list.isEmpty()) return
        if (!policyEngine) {
            packages = list
            return
        }
        if (list.isEmpty()) {
            hasPolicy = false
            packages = emptySet()
            flags = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        } else {
            if (!hasPolicy) flags = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
            hasPolicy = true
            packages = list
        }
    }

    fun setFeatures(value: Int) {
        calls += "setLockTaskFeatures($value)"
        if (!policyEngine) {
            legacyFlags = value
            return
        }
        if (packages.isEmpty() && value == DevicePolicyManager.LOCK_TASK_FEATURE_NONE) {
            hasPolicy = false
            flags = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        } else {
            hasPolicy = true
            flags = value
        }
    }

    fun features(): Int {
        calls += "getLockTaskFeatures"
        return when {
            !policyEngine -> legacyFlags
            hasPolicy -> flags
            else -> DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        }
    }

    fun packageList(): Set<String> = packages

    fun permitted(pkg: String): Boolean = pkg in packages
}

@Implements(DevicePolicyManager::class)
class OwnerDpmShadow {
    @Implementation
    fun isDeviceOwnerApp(packageName: String?): Boolean = OwnerDpmModel.owner

    @Implementation
    fun isAdminActive(admin: ComponentName): Boolean = OwnerDpmModel.adminActive

    @Implementation
    fun setLockTaskPackages(admin: ComponentName, packages: Array<String>) = OwnerDpmModel.setPackages(packages.toSet())

    @Implementation
    fun getLockTaskPackages(admin: ComponentName): Array<String> {
        OwnerDpmModel.calls += "getLockTaskPackages"
        return OwnerDpmModel.packageList().toTypedArray()
    }

    @Implementation
    fun isLockTaskPermitted(pkg: String): Boolean = OwnerDpmModel.permitted(pkg)

    @Implementation
    fun setLockTaskFeatures(admin: ComponentName, flags: Int) = OwnerDpmModel.setFeatures(flags)

    @Implementation
    fun getLockTaskFeatures(admin: ComponentName): Int = OwnerDpmModel.features()

    @Implementation
    fun setKeyguardDisabled(admin: ComponentName, disabled: Boolean): Boolean {
        OwnerDpmModel.keyguardDisabled = disabled
        return true
    }

    @Implementation
    fun addPersistentPreferredActivity(admin: ComponentName, filter: IntentFilter, activity: ComponentName) {
        OwnerDpmModel.persistentEntry = true
    }

    @Implementation
    fun clearPackagePersistentPreferredActivities(admin: ComponentName, packageName: String) {
        OwnerDpmModel.persistentEntry = false
    }

    @Implementation
    fun clearDeviceOwnerApp(packageName: String) {
        OwnerDpmModel.calls += "clearDeviceOwnerApp"
        if (!OwnerDpmModel.clearOwnerIgnored) {
            OwnerDpmModel.owner = false
            OwnerDpmModel.adminActive = false
            // the platform takes the admin's policies with it
            OwnerDpmModel.setPackages(emptySet())
            OwnerDpmModel.setFeatures(DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
    }

    @Implementation
    fun removeActiveAdmin(admin: ComponentName) {
        OwnerDpmModel.adminActive = false
    }
}

/**
 * B35-fix1 R1: "Dejar de ser propietario del dispositivo" never succeeded on Android 14+ (the reviewer's HONOR, SDK 35: `POLICY_NOT_CLEARED`
 * with `rollback=RESTORED`, twice). [DapDeviceOwnerController.clearAllOwnerPolicies] cleared the allowlist and then demanded that
 * `getLockTaskFeatures` read back `LOCK_TASK_FEATURE_NONE`; on the policy engine the empty allowlist removes the whole policy and the mask reads
 * `GLOBAL_ACTIONS`, so the check could not pass.
 *
 * The release runs here exactly as `MainActivity.newReleaseCoordinator` wires it: the coordinator with the controller's own functions. Only
 * the platform is a model ([OwnerDpmModel], from the AOSP sources), and the Lock Task window and the lift are plain fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, shadows = [OwnerDpmShadow::class])
class OwnerReleaseOnPolicyEngineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val activityManager: ActivityManager get() = context.getSystemService(ActivityManager::class.java)

    private lateinit var controller: DapDeviceOwnerController
    private lateinit var store: DapContainmentStore
    private val window = MaintenanceSessionController()
    private var requestReset = 0

    @Before
    fun fresh() {
        OwnerDpmModel.reset(engine = true)
        ShadowLog.clear()
        // the application plants a tree in onCreate; this test runs on the plain Application, so the controller's Timber lines reach Log through one here
        Timber.plant(Timber.DebugTree())
        requestReset = 0
        context.getSharedPreferences("dap_containment", Context.MODE_PRIVATE).edit().clear().commit()
        controller = DapDeviceOwnerController(context)
        store = DapContainmentStore(context)
    }

    @After
    fun uproot() {
        Timber.uprootAll()
    }

    /** A Dedicated Device Owner as the app leaves it: the flag, the allowlist and the mask written by the real code, Lock Task held. */
    private fun dedicated(engine: Boolean) {
        OwnerDpmModel.reset(engine)
        store.setDedicatedModeEnabled(true)
        assertThat(controller.configureContainmentPolicy()).isTrue()
        shadowOf(activityManager).setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED)
    }

    private fun coordinator() = DapReleaseOwnerCoordinator(
        ownerPresence = controller::ownerPresence,
        readStart = controller::ownerStart,
        beginWindow = { window.begin(0L) },
        stopLockTask = {
            shadowOf(activityManager).setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_NONE)
            true
        },
        clearPolicies = controller::clearAllOwnerPolicies,
        clearPersistentHome = controller::clearPersistentHome,
        clearDeviceOwner = { controller.clearDeviceOwner() },
        removeActiveAdmin = controller::removeActiveAdminIfAny,
        resetRequestToLauncher = { requestReset++ },
        homeIsPolylute = controller::isPolyluteHome,
        endWindow = { window.invalidate() },
        restorePolicies = controller::restoreOwnerStart,
        relock = {
            if (OwnerDpmModel.permitted(context.packageName)) {
                shadowOf(activityManager).setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED)
            }
            activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
        },
        readRetryDelayMs = 0L,
    )

    // ── the model is the platform's, not a rubber stamp ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the model reads the mask as AOSP does, GLOBAL_ACTIONS once the allowlist is gone on the engine and as written on the legacy store`() {
        dedicated(engine = true)
        controller.clearAllOwnerPolicies()
        assertThat(OwnerDpmModel.features()).isEqualTo(DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)

        dedicated(engine = false)
        controller.clearAllOwnerPolicies()
        assertThat(OwnerDpmModel.features()).isEqualTo(DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
    }

    // ── R1: the release succeeds on Android 14+ ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `on the policy engine the release reaches RELEASED and leaves nothing of the policy behind`() = runTest {
        dedicated(engine = true)

        val result = coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.RELEASED)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.NONE)
        assertThat(OwnerDpmModel.owner).isFalse()
        assertThat(OwnerDpmModel.packageList()).isEmpty()
        assertThat(OwnerDpmModel.permitted(context.packageName)).isFalse()
        assertThat(OwnerDpmModel.keyguardDisabled).isFalse()
        assertThat(OwnerDpmModel.persistentEntry).isFalse()
        assertThat(store.isDedicatedModeEnabled()).isFalse()
        assertThat(requestReset).isEqualTo(1)
        assertThat(window.snapshot.value.isAuthenticated).isFalse()
    }

    @Test
    fun `on the legacy store the release reaches RELEASED as it always did`() = runTest {
        dedicated(engine = false)

        val result = coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.RELEASED)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.NONE)
        assertThat(OwnerDpmModel.owner).isFalse()
        assertThat(OwnerDpmModel.permitted(context.packageName)).isFalse()
        assertThat(store.isDedicatedModeEnabled()).isFalse()
    }

    @Test
    fun `a real refusal, the app still permitted, still rolls back on either store`() = runTest {
        for (engine in listOf(true, false)) {
            ShadowLog.clear()
            dedicated(engine)
            OwnerDpmModel.keepsAllowlist = true
            val required = DapContainmentPolicy.requiredLockTaskFeatures(dapLockScreenEnabled = false)

            val result = coordinator().release()

            assertWithMessage("engine=$engine").that(result.outcome).isEqualTo(DapReleaseOutcome.POLICY_NOT_CLEARED)
            assertWithMessage("engine=$engine").that(result.rollback).isEqualTo(DapReleaseRollback.RESTORED)
            assertThat(OwnerDpmModel.owner).isTrue()
            assertThat(OwnerDpmModel.calls).doesNotContain("clearDeviceOwnerApp")
            assertThat(OwnerDpmModel.permitted(context.packageName)).isTrue()
            assertThat(OwnerDpmModel.features()).isEqualTo(required)
            assertThat(store.isDedicatedModeEnabled()).isTrue()
            assertThat(window.snapshot.value.isAuthenticated).isFalse()
            assertThat(activityManager.lockTaskModeState).isEqualTo(ActivityManager.LOCK_TASK_MODE_LOCKED)
        }
    }

    @Test
    fun `an owner the platform will not release is rolled back with the policy it had`() = runTest {
        // the policies clear on the engine now; the role itself is refused (the part of the flow after the clear)
        dedicated(engine = true)
        OwnerDpmModel.clearOwnerIgnored = true

        val result = coordinator().release()

        assertThat(result.outcome).isEqualTo(DapReleaseOutcome.STILL_OWNER)
        assertThat(result.rollback).isEqualTo(DapReleaseRollback.RESTORED)
        assertThat(OwnerDpmModel.permitted(context.packageName)).isTrue()
        assertThat(OwnerDpmModel.features()).isEqualTo(DapContainmentPolicy.requiredLockTaskFeatures(dapLockScreenEnabled = false))
    }

    // ── R1: the failed check names itself ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a refused clear logs which check failed, in one line`() = runTest {
        dedicated(engine = true)
        OwnerDpmModel.keepsAllowlist = true

        assertThat(controller.clearAllOwnerPolicies()).isFalse()

        val lines = ShadowLog.getLogsForTag("DapDeviceOwner").filter { it.msg.orEmpty().contains("not cleared") }
        assertThat(lines).hasSize(1)
        val line = lines.single().msg.orEmpty()
        assertThat(line).contains("isLockTaskPermitted")
        assertThat(line).contains("getLockTaskPackages")
        assertThat(line).doesNotContain("keyguard")
    }

    @Test
    fun `a clear that works logs nothing and answers true on either store`() {
        for (engine in listOf(true, false)) {
            dedicated(engine)
            ShadowLog.clear()
            assertWithMessage("engine=$engine").that(controller.clearAllOwnerPolicies()).isTrue()
            assertThat(ShadowLog.getLogsForTag("DapDeviceOwner").filter { it.msg.orEmpty().contains("not cleared") }).isEmpty()
        }
    }

    @Test
    fun `the clear reads back the allowlist and never the mask`() {
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/dap/admin/DapDeviceOwnerController.kt").readText()
        val clear = source.substringAfter("fun clearAllOwnerPolicies(): Boolean {").substringBefore("\n    /**")
        val code = clear.lines().joinToString("\n") { it.substringBefore("//") }
        assertThat(code).contains("isLockTaskPermitted")
        assertThat(code).contains("getLockTaskPackages")
        assertThat(code).doesNotContain("getLockTaskFeatures")
    }
}
