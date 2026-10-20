package com.lostf1sh.pixelplayeross.dap.admin

import android.app.admin.DevicePolicyManager
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Who owns the wake surface of the dedicated appliance.
 *
 * These are the contracts behind the double-unlock fix. On the HONOR the wake flow used to be
 * *OEM swipe Keyguard → DAP surface*; it is now the DAP surface alone, because the Lock Task
 * feature mask stopped opting the system Keyguard back in.
 */
class DapKeyguardPolicyTest {

    private val keyguardBit = DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD

    // --- The mask ---------------------------------------------------------------------------------

    @Test
    fun `the appliance mask does not opt the system keyguard back in`() {
        val mask = DapKeyguardPolicy.desiredLockTaskFeatures(dapLockScreenEnabled = true)
        assertThat(mask and keyguardBit).isEqualTo(0)
    }

    @Test
    fun `the fallback mask keeps the system keyguard, as before this pass`() {
        val mask = DapKeyguardPolicy.desiredLockTaskFeatures(dapLockScreenEnabled = false)
        assertThat(mask and keyguardBit).isEqualTo(keyguardBit)
    }

    @Test
    fun `unrelated lock task feature bits are preserved, never cleared wholesale`() {
        // The fix removes ONE bit. Every other feature the mode intended must survive it.
        val on = DapKeyguardPolicy.desiredLockTaskFeatures(dapLockScreenEnabled = true)
        val off = DapKeyguardPolicy.desiredLockTaskFeatures(dapLockScreenEnabled = false)

        assertThat(on).isEqualTo(off and keyguardBit.inv())
        assertThat(on and DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
            .isEqualTo(DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
        assertThat(on and DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
            .isEqualTo(DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
    }

    @Test
    fun `the desired mask is a pure function of product state, so reconciliation is idempotent`() {
        repeat(3) {
            assertThat(DapKeyguardPolicy.desiredLockTaskFeatures(true)).isEqualTo(
                DapKeyguardPolicy.desiredLockTaskFeatures(true),
            )
        }
    }

    @Test
    fun `containment defers to the single authority rather than keeping its own mask`() {
        assertThat(DapContainmentPolicy.requiredLockTaskFeatures(true))
            .isEqualTo(DapKeyguardPolicy.desiredLockTaskFeatures(true))
        assertThat(DapContainmentPolicy.requiredLockTaskFeatures(false))
            .isEqualTo(DapKeyguardPolicy.desiredLockTaskFeatures(false))
    }

    // --- Eligibility ------------------------------------------------------------------------------

    @Test
    fun `the appliance owns the wake surface only in Dedicated Mode with the feature on`() {
        assertThat(eligible()).isTrue()
    }

    @Test
    fun `App Mode never takes the wake surface`() {
        assertThat(eligible(dedicatedModeEnabled = false)).isFalse()
    }

    @Test
    fun `the feature off never takes the wake surface`() {
        assertThat(eligible(dapLockScreenEnabled = false)).isFalse()
    }

    @Test
    fun `without Device Owner nothing is attempted`() {
        assertThat(eligible(isDeviceOwner = false)).isFalse()
        assertThat(DapKeyguardPolicy.desiredLockTaskFeatures(true) and keyguardBit).isEqualTo(0)
    }

    // --- Secure credential ------------------------------------------------------------------------

    @Test
    fun `a real credential blocks the appliance from owning the wake surface`() {
        // A playback surface performs no authentication. Letting it stand in for a real lock screen
        // would weaken the device, so it is refused outright rather than quietly substituted.
        assertThat(eligible(isDeviceSecure = true)).isFalse()
    }

    @Test
    fun `the blocked state is named, not silently degraded`() {
        assertThat(DapKeyguardState.entries).contains(DapKeyguardState.BLOCKED_SECURE_CREDENTIAL)
        assertThat(DapKeyguardState.entries).contains(DapKeyguardState.BLOCKED_NOT_DEVICE_OWNER)
        assertThat(DapKeyguardState.entries).contains(DapKeyguardState.FAILED)
    }

    private fun eligible(
        isDeviceOwner: Boolean = true,
        dedicatedModeEnabled: Boolean = true,
        dapLockScreenEnabled: Boolean = true,
        isDeviceSecure: Boolean = false,
    ) = DapKeyguardPolicy.canOwnWakeSurface(
        isDeviceOwner = isDeviceOwner,
        dedicatedModeEnabled = dedicatedModeEnabled,
        dapLockScreenEnabled = dapLockScreenEnabled,
        isDeviceSecure = isDeviceSecure,
    )

    // --- Security boundary ------------------------------------------------------------------------

    @Test
    fun `no keyguard bypass or overlay API appears anywhere in the policy path`() {
        val files = listOf(
            "dap/admin/DapKeyguardPolicy.kt",
            "dap/admin/DapContainmentPolicy.kt",
            "dap/admin/DapDeviceOwnerController.kt",
        )
        files.forEach { relative ->
            val candidates = listOf(
                File("src/main/java/com/lostf1sh/pixelplayeross/$relative"),
                File("app/src/main/java/com/lostf1sh/pixelplayeross/$relative"),
            )
            val text = candidates.firstOrNull { it.isFile }?.readText()
                ?: error("Could not locate $relative")

            listOf(
                "SHOW_WHEN_LOCKED",
                "requestDismissKeyguard",
                "KeyguardManager.KeyguardLock",
                "disableKeyguard",
                "newKeyguardLock",
                "WindowManager",
                "TYPE_APPLICATION_OVERLAY",
            ).forEach { forbidden ->
                assertThat(text).doesNotContain(forbidden)
            }
        }
    }

    @Test
    fun `setKeyguardDisabled is used, because Lock Task alone stops at Maintenance`() {
        // This used to assert the opposite, and the comment said why: "removing one Lock Task bit
        // made the DAP surface the first wake surface, so the stronger Device Owner API was never
        // needed". That verification held with Lock Task ACTIVE. Maintenance is the one state that
        // deliberately releases Lock Task, and there the mask stops suppressing anything: measured
        // on the HONOR LGN-NX3, every wake in Maintenance showed the OEM lock screen first. The
        // Device Owner API is what makes ownership independent of Lock Task's transient state.
        val path = listOf(
            File("src/main/java/com/lostf1sh/pixelplayeross/dap/admin/DapDeviceOwnerController.kt"),
            File("app/src/main/java/com/lostf1sh/pixelplayeross/dap/admin/DapDeviceOwnerController.kt"),
        ).first { it.isFile }.readText()
        assertThat(path).contains("setKeyguardDisabled")
    }

    // --- Holding the Keyguard down ----------------------------------------------------------------

    @Test
    fun `the keyguard is held down exactly when the appliance owns the wake surface`() {
        assertThat(holdsKeyguardDown()).isTrue()
        assertThat(holdsKeyguardDown(dedicatedModeEnabled = false)).isFalse()
        assertThat(holdsKeyguardDown(dapLockScreenEnabled = false)).isFalse()
        assertThat(holdsKeyguardDown(isDeviceOwner = false)).isFalse()
    }

    @Test
    fun `a real credential is never disabled, whatever else is true`() {
        // The refusal is the intended outcome, not a failure: a playback surface performs no
        // authentication, so it must never stand in for a real lock screen.
        assertThat(holdsKeyguardDown(isDeviceSecure = true)).isFalse()
        assertThat(holdsKeyguardDown(isDeviceSecure = true, dapLockScreenEnabled = true)).isFalse()
    }

    @Test
    fun `killing the lock screen integration releases the keyguard, matching the mask`() {
        // The kill already reverts the Lock Task mask to the OEM-keyguard-inclusive form. If it did
        // not also release the Device Owner hold, a killed integration would leave the appliance
        // holding a wake surface it no longer manages — and the diagnostic would report a behaviour
        // the device is not running.
        assertThat(holdsKeyguardDown(lockScreenIntegrationKilled = true)).isFalse()
        assertThat(DapKeyguardPolicy.shouldDisableKeyguard(
            isDeviceOwner = true,
            dedicatedModeEnabled = true,
            dapLockScreenEnabled = true,
            lockScreenIntegrationKilled = true,
            isDeviceSecure = false,
        )).isFalse()
    }

    @Test
    fun `holding the keyguard down is a pure function of product state, so it is idempotent`() {
        repeat(3) {
            assertThat(holdsKeyguardDown()).isEqualTo(holdsKeyguardDown())
        }
    }

    private fun holdsKeyguardDown(
        isDeviceOwner: Boolean = true,
        dedicatedModeEnabled: Boolean = true,
        dapLockScreenEnabled: Boolean = true,
        lockScreenIntegrationKilled: Boolean = false,
        isDeviceSecure: Boolean = false,
    ) = DapKeyguardPolicy.shouldDisableKeyguard(
        isDeviceOwner = isDeviceOwner,
        dedicatedModeEnabled = dedicatedModeEnabled,
        dapLockScreenEnabled = dapLockScreenEnabled,
        lockScreenIntegrationKilled = lockScreenIntegrationKilled,
        isDeviceSecure = isDeviceSecure,
    )

    @Test
    fun `the wake surface state machine is untouched by this policy work`() {
        // WakeCycleMachine still guards the case where Android unexpectedly presents a Keyguard:
        // the policy hopes the Keyguard is gone, and the machine handles it if it is not.
        val path = listOf(
            File("src/main/java/com/lostf1sh/pixelplayeross/presentation/lockscreen/WakeCycle.kt"),
            File("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/lockscreen/WakeCycle.kt"),
        ).first { it.isFile }.readText()
        assertThat(path).contains("WAITING_FOR_KEYGUARD")
        assertThat(path).contains("DapLockScreenPolicy.shouldShow(")
    }
}
