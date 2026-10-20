package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.Lyrics

@Immutable
data class StablePlayerState(
    val currentSong: Song? = null,
    val currentMediaItemIndex: Int = -1,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val totalDuration: Long = 0L,
    val isShuffleEnabled: Boolean = false,
    val isShuffleTransitionInProgress: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val isLoadingLyrics: Boolean = false,
    val lyrics: Lyrics? = null,
    /**
     * True once a lyrics resolve has completed for the current song, whether or not it found
     * anything. Distinguishes "local sources not tried yet" from "local sources are empty", so
     * the UI never offers an online search before local lyrics have actually been looked for.
     */
    val lyricsResolveCompleted: Boolean = false,
    /**
     * Set when the lyrics resolve for the current song ended in a failure rather than in
     * "there are no lyrics". Lets the surface say what went wrong instead of showing an empty
     * pane, and is cleared the moment a new resolve starts or succeeds.
     */
    val lyricsError: String? = null,
    val isBuffering: Boolean = false
)
