package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.Player

/**
 * One play/pause rule for the app and the widget (AUD-066/067).
 *
 * Decided on `playWhenReady` — the intent — never on `isPlaying`, which is false while buffering:
 * pressing pause during a buffer used to call play() instead. An ended queue restarts its current
 * item, which the widget did not do; since AUD-016 a natural end in Direct reaches ENDED too.
 */
object PlayPauseDecision {
    enum class Action { PAUSE, PLAY, PREPARE_AND_PLAY, RESTART_AND_PLAY }

    fun decide(playWhenReady: Boolean, playbackState: Int, mediaItemCount: Int): Action = when {
        playbackState == Player.STATE_ENDED -> Action.RESTART_AND_PLAY
        playWhenReady -> Action.PAUSE
        playbackState == Player.STATE_IDLE && mediaItemCount > 0 -> Action.PREPARE_AND_PLAY
        else -> Action.PLAY
    }

    fun apply(player: Player) {
        when (decide(player.playWhenReady, player.playbackState, player.mediaItemCount)) {
            Action.PAUSE -> player.pause()
            Action.PLAY -> player.play()
            Action.PREPARE_AND_PLAY -> {
                player.prepare()
                player.play()
            }
            Action.RESTART_AND_PLAY -> {
                player.seekTo(player.currentMediaItemIndex, 0L)
                player.play()
            }
        }
    }
}
