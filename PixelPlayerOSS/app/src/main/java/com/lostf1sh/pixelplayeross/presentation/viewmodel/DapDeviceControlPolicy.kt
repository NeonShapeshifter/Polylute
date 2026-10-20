package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING

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
     * The lyrics sheet's own "keep the screen on" toggle (a per-session choice of the listener) is
     * held only while a song is playing. Left open on a paused track it used to hold the screen on
     * until the sheet was closed.
     */
    fun shouldKeepScreenOnForLyrics(userToggle: Boolean, isPlaying: Boolean): Boolean =
        userToggle && isPlaying

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
