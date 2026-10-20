package com.lostf1sh.pixelplayeross.dap.admin

import android.app.admin.DevicePolicyManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverride
import com.lostf1sh.pixelplayeross.presentation.lockscreen.DapLockScreenPolicy
import org.junit.jupiter.api.Test

/**
 * Phase 13 — the Maintenance feature kill for DAP Lock Screen integration.
 *
 * A feature kill is a *supported configuration*, not a diagnostic instrument: it exists for OEM
 * builds whose Keyguard/Lock Task interaction misbehaves, and turning it off is a legitimate end
 * state a user may keep. What it must not do is half-apply — leaving the appliance holding a wake
 * surface it no longer manages is worse than either state on its own.
 */
class DapLockScreenFeatureKillTest {

    private val keyguardBit = DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD

    @Test
    fun `the kill reconciles the mask back to OEM keyguard behaviour`() {
        val killed = DapKeyguardPolicy.desiredLockTaskFeatures(
            dapLockScreenEnabled = true,
            lockScreenIntegrationKilled = true,
        )
        assertThat(killed and keyguardBit).isEqualTo(keyguardBit)
    }

    /** The kill and "the setting is off" must land on exactly the same mask. */
    @Test
    fun `killed with the setting on equals the setting being off`() {
        assertThat(
            DapKeyguardPolicy.desiredLockTaskFeatures(
                dapLockScreenEnabled = true,
                lockScreenIntegrationKilled = true,
            )
        ).isEqualTo(DapKeyguardPolicy.desiredLockTaskFeatures(dapLockScreenEnabled = false))
    }

    @Test
    fun `without the kill the appliance mask is unchanged`() {
        assertThat(
            DapKeyguardPolicy.desiredLockTaskFeatures(
                dapLockScreenEnabled = true,
                lockScreenIntegrationKilled = false,
            )
        ).isEqualTo(DapKeyguardPolicy.desiredLockTaskFeatures(dapLockScreenEnabled = true))
        assertThat(
            DapKeyguardPolicy.desiredLockTaskFeatures(
                dapLockScreenEnabled = true,
                lockScreenIntegrationKilled = false,
            ) and keyguardBit
        ).isEqualTo(0)
    }

    @Test
    fun `removing the kill restores the appliance mask exactly`() {
        val withKill = DapKeyguardPolicy.desiredLockTaskFeatures(true, lockScreenIntegrationKilled = true)
        val withoutKill = DapKeyguardPolicy.desiredLockTaskFeatures(true, lockScreenIntegrationKilled = false)
        assertThat(withKill).isNotEqualTo(withoutKill)
        assertThat(withoutKill and keyguardBit).isEqualTo(0)
    }

    @Test
    fun `containment carries the kill through rather than deciding for itself`() {
        assertThat(DapContainmentPolicy.requiredLockTaskFeatures(true, lockScreenIntegrationKilled = true))
            .isEqualTo(
                DapKeyguardPolicy.desiredLockTaskFeatures(
                    dapLockScreenEnabled = true,
                    lockScreenIntegrationKilled = true,
                )
            )
    }

    // --- The surface ------------------------------------------------------------------------------

    /** Killed means the platform keeps its lock screen — the same answer as "feature off". */
    @Test
    fun `the kill makes the surface behave exactly as if it were disabled`() {
        assertThat(DapLockScreenPolicy.effectiveEnabled(true, lockScreenIntegrationKilled = true))
            .isFalse()
        assertThat(DapLockScreenPolicy.effectiveEnabled(false, lockScreenIntegrationKilled = false))
            .isFalse()
    }

    @Test
    fun `without the kill an enabled setting stays enabled`() {
        assertThat(DapLockScreenPolicy.effectiveEnabled(true, lockScreenIntegrationKilled = false))
            .isTrue()
    }

    @Test
    fun `the kill cannot enable a setting the user never turned on`() {
        assertThat(DapLockScreenPolicy.effectiveEnabled(false, lockScreenIntegrationKilled = true))
            .isFalse()
    }

    @Test
    fun `a killed integration never raises the surface`() {
        val effective = DapLockScreenPolicy.effectiveEnabled(true, lockScreenIntegrationKilled = true)
        assertThat(
            DapLockScreenPolicy.shouldShow(
                enabled = effective,
                dedicated = true,
                keyguardLocked = false,
                wokeFromScreenOff = true,
                hasSong = true,
            )
        ).isFalse()
    }

    @Test
    fun `a killed integration never arms`() {
        val effective = DapLockScreenPolicy.effectiveEnabled(true, lockScreenIntegrationKilled = true)
        assertThat(DapLockScreenPolicy.shouldArm(enabled = effective, dedicated = true)).isFalse()
    }

    @Test
    fun `the override that drives this is classified as a feature kill not a diagnostic`() {
        assertThat(MaintenanceOverride.DAP_LOCK_SCREEN_INTEGRATION.kind)
            .isEqualTo(com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideKind.FEATURE_KILL)
    }
}
