package com.lostf1sh.pixelplayeross.data.service

import androidx.media3.common.Player
import java.util.concurrent.atomic.AtomicInteger

/**
 * Play/pause commands that came from outside the app's own screens, counted where they enter.
 *
 * B13-5. [com.lostf1sh.pixelplayeross.presentation.viewmodel.ExternalPlayStart] retries a `play()`
 * the platform refused (Android 15 audio focus) and must give up as soon as the user has decided
 * otherwise. The player cannot say who decided: the engine answers a refused focus request with
 * `playWhenReady = false`, and a pause from the notification, a headset or a widget reaches the
 * player as the same `USER_REQUEST`, so the reason on the player's callback separates neither. Only
 * the entry point does: the session sees every controller's command (notification, Bluetooth,
 * system UI, media buttons) before it runs, and the widget's service intent is handled in one place.
 * A pause the app makes itself, the focus refusal, never passes through either.
 *
 * Process-wide because the service that sees the commands and the view model that retries are
 * different objects of the same process.
 */
object ExternalPlaybackCommands {
    private val count = AtomicInteger()

    /** Commands noted since the process started; only differences are meaningful. */
    val total: Int get() = count.get()

    fun note() {
        count.incrementAndGet()
    }

    /**
     * Whether [playerCommand] from this controller is a user's play/pause/stop decision that no other
     * path of the app records.
     *
     * The app's own controller (its screens and lock-screen UI) is excluded: those taps are recorded
     * by `PlaybackStateHolder.noteUserPlayPause` and counting them twice would only make the retry
     * more cautious. The media notification is the app's own package too, but it is somebody else's
     * tap, so it counts.
     */
    fun countsAsUserCommand(
        playerCommand: Int,
        controllerPackage: String,
        appPackage: String,
        isMediaNotificationController: Boolean,
    ): Boolean {
        if (playerCommand != Player.COMMAND_PLAY_PAUSE && playerCommand != Player.COMMAND_STOP) return false
        val fromOwnScreens = controllerPackage == appPackage && !isMediaNotificationController
        return !fromOwnScreens
    }
}
