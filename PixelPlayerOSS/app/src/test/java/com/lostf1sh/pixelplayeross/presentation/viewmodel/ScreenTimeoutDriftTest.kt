package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ScreenTimeoutPolicy.DriftAction
import org.junit.jupiter.api.Test

/**
 * FIX-2 (B11-fix, device 2026-10-07): the app showed "30 s" (its stored choice) while the system was
 * at 2147483647. Something overwrote the value and nothing noticed. On the appliance a drift is put
 * back; on a phone the app never fights the system.
 */
class ScreenTimeoutDriftTest {

    private val never = 2_147_483_647L

    private fun action(stored: Long, system: Long, dap: Boolean, canWrite: Boolean) =
        ScreenTimeoutPolicy.drift(stored, system, dap, canWrite).action

    @Test
    fun `what happened on the device is a drift, and "never" counts as one`() {
        val drift = ScreenTimeoutPolicy.drift(storedMode = 30_000L, systemTimeoutMs = never, dapMode = true, canWrite = true)
        assertThat(drift.drifted).isTrue()
        assertThat(drift.storedMs).isEqualTo(30_000L)
        assertThat(drift.systemMs).isEqualTo(never)
    }

    @Test
    fun `the appliance puts a drifted value back, if Android lets it`() {
        assertThat(action(30_000L, never, dap = true, canWrite = true)).isEqualTo(DriftAction.REAPPLY)
        assertThat(action(60_000L, 30_000L, dap = true, canWrite = true)).isEqualTo(DriftAction.REAPPLY)
    }

    @Test
    fun `without WRITE_SETTINGS even the appliance only shows it`() {
        assertThat(action(30_000L, never, dap = true, canWrite = false)).isEqualTo(DriftAction.SHOW_ONLY)
    }

    @Test
    fun `on a phone the app never fights the system`() {
        // Whatever the permission: app mode shows the real value and writes nothing.
        assertThat(action(30_000L, never, dap = false, canWrite = true)).isEqualTo(DriftAction.SHOW_ONLY)
        assertThat(action(30_000L, never, dap = false, canWrite = false)).isEqualTo(DriftAction.SHOW_ONLY)
    }

    @Test
    fun `a value that still matches is no drift in either mode`() {
        for (dap in listOf(true, false)) {
            for (canWrite in listOf(true, false)) {
                assertThat(action(30_000L, 30_000L, dap, canWrite)).isEqualTo(DriftAction.NONE)
            }
        }
    }

    @Test
    fun `a choice that owns no system value cannot drift`() {
        // "Follow the system" and "never while playing" are not values the app wrote.
        for (stored in listOf(DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM, DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING)) {
            for (system in listOf(30_000L, never)) {
                for (dap in listOf(true, false)) {
                    assertThat(action(stored, system, dap, canWrite = true)).isEqualTo(DriftAction.NONE)
                }
            }
        }
    }

    @Test
    fun `a stored value the app would not write is never re-applied`() {
        // Defence in depth: whatever the preference holds, REAPPLY only ever means a preset.
        for (stored in listOf(45_000L, never, 3_600_000L)) {
            assertThat(action(stored, 30_000L, dap = true, canWrite = true)).isEqualTo(DriftAction.NONE)
        }
    }

    // --- what the list marks as selected (the system's real value, not the stored choice)

    @Test
    fun `the list marks the real value, not the stored one`() {
        // Stored 30 s, system never: nothing finite is selected (it used to say 30 s).
        for (option in ScreenTimeoutPolicy.PRESETS_MS) {
            assertThat(DapDeviceControlPolicy.isTimeoutOptionSelected(30_000L, never, option)).isFalse()
        }
        // Stored 30 s, system 1 min: 1 min is the one.
        assertThat(DapDeviceControlPolicy.isTimeoutOptionSelected(30_000L, 60_000L, 60_000L)).isTrue()
        assertThat(DapDeviceControlPolicy.isTimeoutOptionSelected(30_000L, 60_000L, 30_000L)).isFalse()
        // Following the system: also the real value.
        assertThat(DapDeviceControlPolicy.isTimeoutOptionSelected(DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM, 120_000L, 120_000L)).isTrue()
    }

    @Test
    fun `never while playing owns the radio only while it is chosen`() {
        val mode = DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING
        assertThat(DapDeviceControlPolicy.isTimeoutOptionSelected(mode, 60_000L, mode)).isTrue()
        assertThat(DapDeviceControlPolicy.isTimeoutOptionSelected(mode, 60_000L, 60_000L)).isFalse()
        assertThat(DapDeviceControlPolicy.isTimeoutOptionSelected(60_000L, 60_000L, mode)).isFalse()
    }
}
