package com.lostf1sh.pixelplayeross.data.service

import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot

/**
 * What a restored session does about playing (AUD-125).
 *
 * A restore follows the process or the service dying: nobody asked for sound at that moment. With a
 * saved `playWhenReady` it resumed by itself — measured with B-ux, through the phone speaker, since
 * the DAC was not connected. It always comes back paused, at the saved position; the listener
 * presses play.
 */
object RestorePlaybackPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun playWhenReadyAfterRestore(snapshot: PlaybackQueueSnapshot, allowBackgroundPlayback: Boolean): Boolean = false
}
