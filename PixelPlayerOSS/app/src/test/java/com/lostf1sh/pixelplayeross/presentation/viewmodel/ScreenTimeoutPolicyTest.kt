package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING
import org.junit.jupiter.api.Test

/**
 * F-05 (device, 2026-10-06): `screen_off_timeout = 2147483647`, so the screen only ever went off on
 * the power button, no lock screen came up on its own and the phone never reached Doze.
 */
class ScreenTimeoutPolicyTest {

    @Test
    fun `what the device was found with is never, and so is anything like it`() {
        assertThat(ScreenTimeoutPolicy.isNever(2_147_483_647L)).isTrue()
        assertThat(ScreenTimeoutPolicy.isNever(Int.MAX_VALUE.toLong())).isTrue()
        assertThat(ScreenTimeoutPolicy.isNever(0L)).isTrue()
        assertThat(ScreenTimeoutPolicy.isNever(-1L)).isTrue()
        assertThat(ScreenTimeoutPolicy.isNever(ScreenTimeoutPolicy.NEVER_FROM_MS)).isTrue()
    }

    @Test
    fun `every finite value Android offers is not never`() {
        // Android's own list tops out at 30 minutes.
        for (ms in listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 1_800_000L)) {
            assertThat(ScreenTimeoutPolicy.isNever(ms)).isFalse()
        }
    }

    @Test
    fun `the app writes the presets and nothing else`() {
        for (ms in ScreenTimeoutPolicy.PRESETS_MS) assertThat(ScreenTimeoutPolicy.isAppWritable(ms)).isTrue()
        // Never, in every spelling; and the app-local modes, which are not system values at all.
        for (ms in listOf(
            2_147_483_647L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE, 0L, -1L, 45_000L, 3_600_000L,
            DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM, DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING,
        )) {
            assertThat(ScreenTimeoutPolicy.isAppWritable(ms)).isFalse()
        }
    }

    @Test
    fun `no preset the app can write is itself never`() {
        for (ms in ScreenTimeoutPolicy.PRESETS_MS) assertThat(ScreenTimeoutPolicy.isNever(ms)).isFalse()
    }

    @Test
    fun `the DAP default is a preset, one minute`() {
        assertThat(ScreenTimeoutPolicy.DAP_DEFAULT_MS).isEqualTo(60_000L)
        assertThat(ScreenTimeoutPolicy.isAppWritable(ScreenTimeoutPolicy.DAP_DEFAULT_MS)).isTrue()
    }

    @Test
    fun `a finite timeout is never offered a correction`() {
        assertThat(ScreenTimeoutPolicy.offer(60_000L, dapMode = true)).isEqualTo(ScreenTimeoutPolicy.Offer.None)
        assertThat(ScreenTimeoutPolicy.offer(600_000L, dapMode = false)).isEqualTo(ScreenTimeoutPolicy.Offer.None)
    }

    @Test
    fun `never is offered a correction, prominent only on the appliance`() {
        assertThat(ScreenTimeoutPolicy.offer(2_147_483_647L, dapMode = true))
            .isEqualTo(ScreenTimeoutPolicy.Offer.CorrectNever(prominent = true))
        // App mode: the same fact, but only where the setting lives, and it changes nothing alone.
        assertThat(ScreenTimeoutPolicy.offer(2_147_483_647L, dapMode = false))
            .isEqualTo(ScreenTimeoutPolicy.Offer.CorrectNever(prominent = false))
    }
}
