package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DapDeviceControlPolicyTest {
    @Test
    fun `never while playing keeps screen on only during active playback`() {
        assertTrue(
            DapDeviceControlPolicy.shouldKeepScreenOn(
                DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING,
                isPlaying = true,
                playerVisible = true,
            )
        )
        assertFalse(
            DapDeviceControlPolicy.shouldKeepScreenOn(
                DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING,
                isPlaying = false,
                playerVisible = true,
            )
        )
    }

    @Test
    fun `ordinary timeout never holds screen awake`() {
        assertFalse(DapDeviceControlPolicy.shouldKeepScreenOn(60_000L, isPlaying = true, playerVisible = true))
    }

    @Test
    fun `USB Direct to a fixed output forbids volume changes`() {
        assertFalse(DapDeviceControlPolicy.canAdjustVolume(UsbDirectSinkState.USB_DIRECT_ACTIVE, backendControllable = false))
    }

    @Test
    fun `USB Direct to a DAC with hardware volume allows volume changes`() {
        assertTrue(DapDeviceControlPolicy.canAdjustVolume(UsbDirectSinkState.USB_DIRECT_ACTIVE, backendControllable = true))
    }

    @Test
    fun `Android and failed Direct paths allow Android media volume`() {
        assertTrue(DapDeviceControlPolicy.canAdjustVolume(UsbDirectSinkState.ANDROID_AUDIO, backendControllable = true))
        assertTrue(DapDeviceControlPolicy.canAdjustVolume(UsbDirectSinkState.USB_DIRECT_ERROR, backendControllable = true))
    }

    @Test
    fun `brightness is constrained to owner-safe range`() {
        assertEquals(5, DapDeviceControlPolicy.sanitizeBrightnessPercent(0))
        assertEquals(62, DapDeviceControlPolicy.sanitizeBrightnessPercent(62))
        assertEquals(100, DapDeviceControlPolicy.sanitizeBrightnessPercent(120))
    }

    @Test
    fun `follow-system timeout selects the effective platform value`() {
        assertTrue(
            DapDeviceControlPolicy.isTimeoutOptionSelected(
                DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM,
                effectiveSystemTimeoutMillis = 120_000L,
                optionMillis = 120_000L,
            )
        )
        assertFalse(
            DapDeviceControlPolicy.isTimeoutOptionSelected(
                DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM,
                effectiveSystemTimeoutMillis = 120_000L,
                optionMillis = 60_000L,
            )
        )
    }
}
