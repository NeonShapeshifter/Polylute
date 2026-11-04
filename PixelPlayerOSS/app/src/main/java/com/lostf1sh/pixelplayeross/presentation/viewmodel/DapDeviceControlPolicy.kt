package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

object DapDeviceControlPolicy {
    fun sanitizeBrightnessPercent(percent: Int): Int = percent.coerceIn(5, 100)

    /** DAP pages stay on during playback; App mode and the DAP lock surface always release the flag. */
    fun shouldKeepScreenOn(timeoutMode: Long, isPlaying: Boolean, dapMode: Boolean, lockSurfaceShowing: Boolean = false): Boolean =
        dapMode && timeoutMode == DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING && isPlaying && !lockSurfaceShowing

    fun keepScreenOnFlow(
        timeoutMode: Flow<Long>,
        isPlaying: Flow<Boolean>,
        dapMode: Flow<Boolean>,
        lockSurfaceShowing: Flow<Boolean>,
    ): Flow<Boolean> = combine(timeoutMode, isPlaying, dapMode, lockSurfaceShowing) { mode, playing, dap, lockShowing ->
        shouldKeepScreenOn(mode, playing, dap, lockShowing)
    }.distinctUntilChanged()

    /**
     * The lyrics sheet's own "keep the screen on" toggle (a per-session choice of the listener) is
     * held only in DAP mode while a song is playing. Left open on a paused track it used to hold the screen on
     * until the sheet was closed. B42-fix1: and not under the DAP lock screen, by the same rule as
     * the window flag.
     */
    fun shouldKeepScreenOnForLyrics(userToggle: Boolean, isPlaying: Boolean, lockSurfaceShowing: Boolean = false, dapMode: Boolean = true): Boolean =
        dapMode && userToggle && isPlaying && !lockSurfaceShowing

    /**
     * On Android Audio the media volume is always adjustable. On USB Direct only when the backend is
     * the DAC's own hardware volume; a fixed direct output has no level to move.
     */
    fun canAdjustVolume(directState: UsbDirectSinkState, backendControllable: Boolean): Boolean =
        directState != UsbDirectSinkState.USB_DIRECT_ACTIVE || backendControllable

    /**
     * Which option the screen-timeout list marks as selected.
     *
     * B11-fix (FIX-2): a finite option is selected when it is **the system's real value**, whatever
     * the app has stored. It used to follow the stored choice, which is how the list showed "30 s"
     * over a system timeout of "never". Only the app-local mode ("never while playing") is read from
     * the stored preference, because it owns no system value; while it is chosen it owns the radio.
     */
    fun isTimeoutOptionSelected(
        storedMode: Long,
        effectiveSystemTimeoutMillis: Long,
        optionMillis: Long,
    ): Boolean = when {
        optionMillis == DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING -> storedMode == DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING
        storedMode == DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING -> false
        else -> effectiveSystemTimeoutMillis == optionMillis
    }
}
