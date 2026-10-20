package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap

@Immutable
internal data class LibrarySongPlaybackUiState(
    val isCurrentSong: Boolean = false,
    val isPlaying: Boolean = false
)

/**
 * The player state a whole list of songs shares, collected once for the list.
 *
 * Every row used to collect its own `stablePlayerState.map { ... }.distinctUntilChanged()` through
 * `collectAsStateWithLifecycle`: a flow chain, a lifecycle observer and two coroutines per row, built
 * when the row scrolled in and cancelled when it scrolled out. A fling brings in several rows per
 * frame, and on the owner's phone each new row cost the main thread ~15 ms (B13 trace), so the rows
 * now only read this holder.
 */
@Stable
internal class LibraryPlaybackStates(private val playerState: State<StablePlayerState>) {
    /** Which row this is: current song and playing, current but not playing, or neither. */
    fun uiStateFor(songId: String): LibrarySongPlaybackUiState {
        val state = playerState.value
        val isCurrent = state.currentSong?.id == songId
        return if (!isCurrent) NotCurrent else if (state.isPlaying) CurrentPlaying else CurrentPaused
    }

    private companion object {
        val NotCurrent = LibrarySongPlaybackUiState()
        val CurrentPlaying = LibrarySongPlaybackUiState(isCurrentSong = true, isPlaying = true)
        val CurrentPaused = LibrarySongPlaybackUiState(isCurrentSong = true, isPlaying = false)
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun rememberLibraryPlaybackStates(playerViewModel: PlayerViewModel): LibraryPlaybackStates {
    val playerState = playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    return remember(playerState) { LibraryPlaybackStates(playerState) }
}

@OptIn(UnstableApi::class)
@Composable
internal fun LibraryPlaybackAwareSongItem(
    song: Song,
    playback: LibraryPlaybackStates,
    albumArtSize: Dp = 50.dp,
    showAlbumArt: Boolean = true,
    /** B32-Q2: the song Stats or a collection asked the Library to show: its row is raised for a few seconds, whatever is playing. */
    isLocated: Boolean = false,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onMoreOptionsClick: (Song) -> Unit,
    onClick: () -> Unit
) {
    // derivedStateOf: only the rows whose own answer changes recompose when the track changes,
    // not every visible row.
    val playbackUiState by remember(song.id, playback) {
        derivedStateOf { playback.uiStateFor(song.id) }
    }

    EnhancedSongListItem(
        song = song,
        isPlaying = playbackUiState.isPlaying,
        isCurrentSong = playbackUiState.isCurrentSong,
        isLoading = false,
        albumArtSize = albumArtSize,
        showAlbumArt = showAlbumArt,
        containerColorOverride = if (isLocated) Dap.surfaceRaised else null,
        isSelected = isSelected,
        selectionIndex = selectionIndex,
        isSelectionMode = isSelectionMode,
        onLongPress = onLongPress,
        onMoreOptionsClick = onMoreOptionsClick,
        onClick = onClick
    )
}
