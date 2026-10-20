package com.lostf1sh.pixelplayeross.data.service

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Which controllers of the media session get the queue, that is the whole timeline (B19-S4).
 *
 * Every time ExoPlayer replaces its `Timeline` the session republishes it to each controller and to the platform session, and what
 * that costs grows with the number of items: the queue of the owner's library is 4,870 songs and a song change makes one or two such
 * replacements (the duration of the item that was just prepared). Only the app's own controller needs the queue (its queue screen
 * lists it); the media notification, the system's media controls and an external controller only show the song that is playing, which
 * they still get (title, artwork, duration, position, next and previous). Without `COMMAND_GET_TIMELINE` a controller is sent the
 * timeline with the current item only, and the platform session, whose commands are the media notification controller's, publishes
 * no queue.
 *
 * What an external controller loses with it: a queue view (Bluetooth AVRCP browsing of "now playing", Android Auto's queue, the
 * system output switcher's list). Skipping, play/pause, seeking and the current song's metadata stay.
 */
object SessionQueuePolicy {
    /** [controllerPackage] is the app's own and it is not the media notification controller (which is the app's too). */
    fun getsQueue(appPackage: String, controllerPackage: String, isMediaNotificationController: Boolean): Boolean =
        controllerPackage == appPackage && !isMediaNotificationController

    /** [base] (what the session would give any controller) with the queue taken out unless [getsQueue]. */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun playerCommands(base: Player.Commands, getsQueue: Boolean): Player.Commands =
        if (getsQueue) base else base.buildUpon().remove(Player.COMMAND_GET_TIMELINE).build()
}
