package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LiveAudioFormat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TrackDetailsViewModel
import kotlinx.coroutines.flow.map

/**
 * Hosts the canonical [TrackDetailsSheet] for a song opened from a list's action menu.
 *
 * This exists so that "Track details" on the song-actions sheet means the *same* surface no matter
 * which list opened it. It composes [TrackDetailsSheet] unchanged and binds it through the same
 * [TrackDetailsViewModel] the player uses — there is no second details implementation, and no
 * technical values are recomputed here.
 *
 * **How this differs from the player's copy of the same wiring.** The player mounts its details as a
 * sibling of the More menu inside `UnifiedPlayerOverlaysLayer`, because there the details and the
 * action menu are two entries onto the same selection and need to share one `secondaryView` state.
 * A list is not that: it owns its own action sheet, so it owns the details overlay above it, and
 * closing the details returns to the action sheet the user actually opened.
 *
 * **The live reading is self-filtering.** [LiveAudioFormat.from] returns null unless the metadata
 * belongs to *this* song, so opening details for something that is not playing simply yields no
 * live reading — the sheet falls back to the stored file facts and, correctly, reports no confirmed
 * output rate rather than borrowing the playing track's.
 */
@Composable
fun TrackDetailsHost(
    song: Song,
    playerViewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val trackDetailsViewModel: TrackDetailsViewModel = hiltViewModel()
    val trackDetailsState by trackDetailsViewModel.uiState.collectAsStateWithLifecycle()

    // The player's own account of what it is decoding. Only ever consulted while this overlay is
    // on screen, and only ever accepted for the song it actually describes.
    val liveMetadata by remember(playerViewModel) {
        playerViewModel.playbackAudioMetadata
    }.collectAsStateWithLifecycle()
    val liveDurationMs by remember(playerViewModel) {
        playerViewModel.stablePlayerState.map { it.totalDuration }
    }.collectAsStateWithLifecycle(initialValue = 0L)
    val liveReading = remember(song.id, liveMetadata, liveDurationMs) {
        LiveAudioFormat.from(
            metadata = liveMetadata,
            durationMs = liveDurationMs,
            songId = song.id
        )
    }
    LaunchedEffect(song.id, liveReading) {
        trackDetailsViewModel.bind(song = song, live = liveReading)
    }

    // Raw providers over the player's own authorities — deliberately not collected as state, so a
    // playing track does not recompose this overlay on every position tick.
    val positionProvider = remember(playerViewModel) {
        { playerViewModel.currentPlaybackPosition.value }
    }
    val onSeekFromWaveform = remember(playerViewModel) { { ms: Long ->
        playerViewModel.seekTo(ms)
    } }

    // A dialog rather than a sheet: the action menu behind this is a ModalBottomSheet and owns its
    // own window, so the details have to be drawn in a window above it.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // This window is its own surface, so the activity's volume-key path does not
        // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
        ModalInputBridge()
        // The marker and the seek mapping use the duration the waveform was actually built from, so
        // 100% lands on the end of the drawn envelope. The stored duration is only a fallback while
        // the waveform is still loading.
        val waveformDurationMs = trackDetailsState.waveform?.durationMs ?: 0L
        TrackDetailsSheet(
            state = trackDetailsState,
            currentPositionProvider = positionProvider,
            totalDurationMs = waveformDurationMs.takeIf { it > 0L } ?: song.duration,
            onSeek = onSeekFromWaveform,
            onBackClick = onDismiss,
        )
    }
}
