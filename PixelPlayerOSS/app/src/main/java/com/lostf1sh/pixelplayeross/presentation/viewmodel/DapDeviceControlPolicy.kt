package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

object DapDeviceControlPolicy {
    fun sanitizeBrightnessPercent(percent: Int): Int = percent.coerceIn(5, 100)

    /**
     * F-05: "Never while playing" is a window flag, held for exactly as long as a song is playing
     * **and the player is what is in front**. It disappears on pause, and it is not held for a
     * library page that happens to be on screen while music plays in the background: that page
     * follows the system timeout like any other.
     *
     * It never touches `Settings.System.SCREEN_OFF_TIMEOUT` (see [ScreenTimeoutPolicy]); when the
     * flag is released the system's own timeout is what puts the screen out.
     */
    fun shouldKeepScreenOn(timeoutMode: Long, isPlaying: Boolean, playerVisible: Boolean): Boolean =
        timeoutMode == DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING && isPlaying && playerVisible

    /**
     * B42-fix1: "the player is in front" is what the **listener** sees, not what the player's sheet says. Listening with the player open
     * and pressing the power button leaves the sheet EXPANDED under the DAP lock screen, which is drawn over it in the same window: the
     * flag stayed, and the lock screen never went out. With [lockSurfaceShowing] (the lock screen is up, or armed to be, from the screen
     * going off until the listener has dismissed it) the player is not what is in front, and the screen follows the system timeout.
     * Leaving the lock screen back to the open player puts the flag back.
     */
    fun playerVisibleToListener(sheetExpanded: Boolean, lockSurfaceShowing: Boolean): Boolean =
        sheetExpanded && !lockSurfaceShowing

    /** The window flag as a stream of its own value: what the activity applies, and what a test can drive without an activity. */
    fun keepScreenOnFlow(
        timeoutMode: Flow<Long>,
        isPlaying: Flow<Boolean>,
        sheetExpanded: Flow<Boolean>,
        lockSurfaceShowing: Flow<Boolean>,
    ): Flow<Boolean> = combine(timeoutMode, isPlaying, sheetExpanded, lockSurfaceShowing) { mode, playing, expanded, lockShowing ->
        shouldKeepScreenOn(mode, playing, playerVisibleToListener(expanded, lockShowing))
    }.distinctUntilChanged()

    /**
     * The lyrics sheet's own "keep the screen on" toggle (a per-session choice of the listener) is
     * held only while a song is playing. Left open on a paused track it used to hold the screen on
     * until the sheet was closed. B42-fix1: and not under the DAP lock screen, by the same rule as
     * [playerVisibleToListener].
     */
    fun shouldKeepScreenOnForLyrics(userToggle: Boolean, isPlaying: Boolean, lockSurfaceShowing: Boolean = false): Boolean =
        userToggle && isPlaying && !lockSurfaceShowing

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
