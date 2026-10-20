package com.lostf1sh.pixelplayeross.presentation.components

import com.lostf1sh.pixelplayeross.presentation.components.scoped.SongInfoEntry
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lostf1sh.pixelplayeross.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.player.CreditsSheet
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsSheet
import com.lostf1sh.pixelplayeross.presentation.viewmodel.CreditsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LiveAudioFormat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TrackDetailsViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber
import kotlinx.collections.immutable.persistentListOf

/**
 * Which song an overlay describes, given the entry point it was opened from.
 *
 * The player's DETAILS sibling describes **what is playing**, so it follows playback: skipping to
 * the next track with the details open shows the next track, without closing and reopening. A
 * song's action menu — opened from the player's overflow or from a list — describes **the song it
 * was opened for**, so it stays on that song even while something else plays. List-hosted details
 * are pinned for the same reason and never jump to whatever starts playing in the background.
 *
 * Both cases used to take the song captured when the overlay opened, which is why the details went
 * stale: the id never changed, every re-bind was a no-op, and the sheet went on describing a track
 * that had long stopped playing.
 *
 * The fallback is the selected song for a player with nothing loaded, so opening the details on an
 * empty queue still describes something rather than nothing.
 */
internal fun describedSongFor(
    entry: SongInfoEntry,
    playingSong: Song?,
    selectedSong: Song,
): Song = when (entry) {
    SongInfoEntry.DETAILS -> playingSong ?: selectedSong
    SongInfoEntry.ACTIONS -> selectedSong
}

internal data class SaveQueueOverlayData(
    val songs: ImmutableList<Song>,
    val defaultName: String,
    val onConfirm: (String, Set<String>) -> Unit
)

@Composable
internal fun UnifiedPlayerQueueLayer(
    playerViewModel: PlayerViewModel,
    shouldRenderLayer: Boolean,
    albumColorScheme: ColorScheme,
    albumAccent: androidx.compose.ui.graphics.Color? = null,
    queueScrimAlpha: Float,
    showQueueSheet: Boolean,
    queueHiddenOffsetPx: Float,
    queueSheetOffset: Animatable<Float, AnimationVector1D>,
    queueSheetHeightPx: Float,
    onQueueSheetHeightPxChange: (Float) -> Unit,
    foldedStripPx: Float,
    onOpenQueue: () -> Unit,
    configurationResetKey: Any,
    currentPlaybackQueue: ImmutableList<Song>,
    currentQueueSourceName: String,
    currentMediaItemIndex: Int,
    infrequentPlayerState: StablePlayerState,
    activeTimerValueDisplay: State<String?>,
    activeTimerDurationMinutes: State<Int?>,
    playCount: State<Float>,
    isEndOfTrackTimerActive: State<Boolean>,
    onDismissQueue: () -> Unit,
    onSongInfoClick: (Song, Int) -> Unit,
    onPlaySong: (Song, Int) -> Unit,
    onRemoveSong: (songId: String, queueIndex: Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    onSetPredefinedTimer: (Int) -> Unit,
    onSetEndOfTrackTimer: (Boolean) -> Unit,
    onOpenCustomTimePicker: () -> Unit,
    onCancelTimer: () -> Unit,
    onCancelCountedPlay: () -> Unit,
    onPlayCounter: (Int) -> Unit,
    onRequestSaveAsPlaylist: (List<Song>, String, (String, Set<String>) -> Unit) -> Unit,
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit
) {
    if (!shouldRenderLayer) return

    // The queue is a CORE RETAINED surface (see the retention note on [QueueSurface]).
    //
    // One mount once a playback session exists, retained across collapse, expand, open, close and
    // track changes; disposed when the session empties. Composition is deliberately NOT tied to
    // visibility and NOT tied to *which* song.
    //
    // This replaces three clauses that used to decide it — `showQueueSheet || keepQueueSheetWarm ||
    // queueSheetHeightPx == 0f`. `keepQueueSheetWarm` is gone because retention no longer depends on
    // the sheet being expanded, and the height clause is gone because a retained sheet measures
    // itself during its one boot composition instead of being force-composed to be measured and
    // thrown away. Phase 14.0X2–X4 measured the trade; §21 records the closeout.
    val shouldRenderQueueSheet = infrequentPlayerState.currentSong != null
    QueueSurface(
        composed = shouldRenderQueueSheet,
        visible = showQueueSheet,
        playerViewModel = playerViewModel,
        albumColorScheme = albumColorScheme,
        albumAccent = albumAccent,
        queueScrimAlpha = queueScrimAlpha,
        queueSheetOffset = queueSheetOffset,
        queueHiddenOffsetPx = queueHiddenOffsetPx,
        queueSheetHeightPx = queueSheetHeightPx,
        onQueueSheetHeightPxChange = onQueueSheetHeightPxChange,
        foldedStripPx = foldedStripPx,
        onOpenQueue = onOpenQueue,
        configurationResetKey = configurationResetKey,
        currentPlaybackQueue = currentPlaybackQueue,
        currentQueueSourceName = currentQueueSourceName,
        currentMediaItemIndex = currentMediaItemIndex,
        infrequentPlayerState = infrequentPlayerState,
        activeTimerValueDisplay = activeTimerValueDisplay,
        activeTimerDurationMinutes = activeTimerDurationMinutes,
        playCount = playCount,
        isEndOfTrackTimerActive = isEndOfTrackTimerActive,
        onDismissQueue = onDismissQueue,
        onSongInfoClick = onSongInfoClick,
        onPlaySong = onPlaySong,
        onRemoveSong = onRemoveSong,
        onReorder = onReorder,
        onToggleRepeat = onToggleRepeat,
        onToggleShuffle = onToggleShuffle,
        onClearQueue = onClearQueue,
        onSetPredefinedTimer = onSetPredefinedTimer,
        onSetEndOfTrackTimer = onSetEndOfTrackTimer,
        onOpenCustomTimePicker = onOpenCustomTimePicker,
        onCancelTimer = onCancelTimer,
        onCancelCountedPlay = onCancelCountedPlay,
        onPlayCounter = onPlayCounter,
        onRequestSaveAsPlaylist = onRequestSaveAsPlaylist,
        onQueueDragStart = onQueueDragStart,
        onQueueDrag = onQueueDrag,
        onQueueRelease = onQueueRelease
    )
}

@OptIn(UnstableApi::class)
@Composable
internal fun UnifiedPlayerSongInfoLayer(
    selectedSongForInfo: Song?,
    /** Which surface the entry point asked for: the details, or the action menu. */
    songInfoEntry: SongInfoEntry,
    albumColorScheme: ColorScheme,
    playerViewModel: PlayerViewModel,
    currentPlaybackQueueProvider: () -> ImmutableList<Song>,
    currentQueueSourceNameProvider: () -> String,
    onDismissSongInfo: () -> Unit,
    onNavigateToAlbum: (Song) -> Unit,
    onNavigateToArtist: (Song) -> Unit,
    onNavigateToGenre: (Song) -> Unit
) {
    selectedSongForInfo?.let { staticSong ->
        val context = LocalContext.current
        var showPlaylistBottomSheet by remember(staticSong.id) { mutableStateOf(false) }
        // Which sibling view the More menu has opened, if any. Null means the menu itself is the
        // visible surface. These are informational views: opening or closing one changes no
        // playback state.
        var secondaryView by remember(staticSong.id, songInfoEntry) {
            mutableStateOf(
                if (songInfoEntry == SongInfoEntry.DETAILS) SecondaryView.TRACK_DETAILS else null
            )
        }
        val playlistViewModel: PlaylistViewModel = hiltViewModel()
        val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

        // What the player is playing right now, as the player itself holds it.
        //
        // Reduced to the song so that nothing below recomposes on a position tick or a play/pause,
        // and so that only an actual track change moves the value.
        val playingSong by remember(playerViewModel) {
            playerViewModel.stablePlayerState.map { it.currentSong }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = null)

        // Which song this overlay describes. See describedSongFor for the rule and for why the
        // captured song alone was not enough.
        val describedSong = describedSongFor(
            entry = songInfoEntry,
            playingSong = playingSong,
            selectedSong = staticSong,
        )

        // Kept fresh from the library, so an edit made while the sheet is open lands on it. The
        // fallback is [describedSong] itself rather than the originally captured song: a row that
        // cannot be read must degrade to the same track described from the player's own copy, never
        // to a different track. Keyed on the id so a track change re-collects rather than restarts.
        val liveSongState by remember(playerViewModel, describedSong.id) {
            playerViewModel.observeSong(describedSong.id).map { it ?: describedSong }
        }.collectAsStateWithLifecycle(initialValue = describedSong)

        val liveSong = liveSongState

        val trackDetailsViewModel: TrackDetailsViewModel = hiltViewModel()
        val trackDetailsState by trackDetailsViewModel.uiState.collectAsStateWithLifecycle()
        val creditsViewModel: CreditsViewModel = hiltViewModel()
        val creditsState by creditsViewModel.uiState.collectAsStateWithLifecycle()

        // Raw provider over the player's own position authority — deliberately not collected as
        // state here so that a playing track does not recompose this layer every tick. The Track
        // Details sheet samples it on its own schedule.
        val positionProvider = remember(playerViewModel) {
            { playerViewModel.currentPlaybackPosition.value }
        }
        val onSeekFromWaveform = remember(playerViewModel) { { ms: Long ->
            playerViewModel.seekTo(ms)
        } }

        // Which surface this overlay *is*.
        //
        // The player's DETAILS sibling opens the details directly, so the song-options menu must not
        // be mounted underneath it. It used to be: the details were drawn in a dialog above the
        // menu, so backing out of the details revealed an action menu the user never asked for.
        // The two entries are mutually exclusive surfaces, not a base and a layer over it.
        val showsSongActions = songInfoEntry == SongInfoEntry.ACTIONS

        MaterialTheme(
            colorScheme = albumColorScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes
        ) {
            if (showsSongActions) {
            SongInfoBottomSheet(
                song = liveSong,
                isFavorite = liveSong.isFavorite,
                onToggleFavorite = { playerViewModel.toggleFavoriteSpecificSong(liveSong) },
                onDismiss = {
                    showPlaylistBottomSheet = false
                    onDismissSongInfo()
                },
                onPlaySong = {
                    playerViewModel.showAndPlaySong(
                        song = liveSong,
                        contextSongs = currentPlaybackQueueProvider(),
                        queueName = currentQueueSourceNameProvider()
                    )
                    onDismissSongInfo()
                },
                onAddToQueue = {
                    playerViewModel.addSongToQueue(liveSong)
                    onDismissSongInfo()
                    Toast.makeText(context, context.getString(R.string.toast_added_to_queue), Toast.LENGTH_SHORT).show()
                },
                onAddNextToQueue = {
                    playerViewModel.addSongNextToQueue(liveSong)
                    onDismissSongInfo()
                    Toast.makeText(context, context.getString(R.string.toast_playing_next), Toast.LENGTH_SHORT).show()
                },
                onAddToPlayList = {
                    showPlaylistBottomSheet = true
                },
                onDeleteFromDevice = { activity, songToDelete, onResult ->
                    playerViewModel.deleteFromDevice(activity, songToDelete, onResult)
                    onDismissSongInfo()
                },
                onNavigateToAlbum = { onNavigateToAlbum(liveSong) },
                onNavigateToArtist = { onNavigateToArtist(liveSong) },
                onNavigateToGenre = { onNavigateToGenre(liveSong) },
                onViewTrackDetails = { secondaryView = SecondaryView.TRACK_DETAILS },
                onViewCredits = { secondaryView = SecondaryView.CREDITS },
                onEditSong = { title, artist, album, albumArtist, composer, genre, lyrics, trackNumber, discNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                    playerViewModel.editSongMetadata(
                        liveSong,
                        title,
                        artist,
                        album,
                        albumArtist,
                        composer,
                        genre,
                        lyrics,
                        trackNumber,
                        discNumber,
                        replayGainTrackGainDb,
                        replayGainAlbumGainDb,
                        coverArtUpdate
                    )
                    onDismissSongInfo()
                },
                removeFromListTrigger = {
                    playerViewModel.removeSongFromQueue(liveSong.id)
                    onDismissSongInfo()
                }
            )

            if (showPlaylistBottomSheet) {
                PlaylistBottomSheet(
                    playlistUiState = playlistUiState,
                    songs = persistentListOf(liveSong),
                    onDismiss = { showPlaylistBottomSheet = false },
                    bottomBarHeight = 0.dp,
                    playerViewModel = playerViewModel,
                )
            }

            }

            // The secondary views are siblings of the More menu. That menu is a ModalBottomSheet
            // and therefore lives in a window of its own, so these are drawn in a dialog window to
            // sit above it — and, when the menu is the surface, closing one hands the user back to
            // the menu they came from.
            when (secondaryView) {
                SecondaryView.TRACK_DETAILS -> {
                    // The player's own account of what it is decoding, watched only while this
                    // sibling view is on screen. The duration is reduced to a Long here so that an
                    // unchanged value cannot recompose anything.
                    val liveMetadata by remember(playerViewModel) {
                        playerViewModel.playbackAudioMetadata
                    }.collectAsStateWithLifecycle()
                    val liveDurationMs by remember(playerViewModel) {
                        playerViewModel.stablePlayerState.map { it.totalDuration }
                    }.collectAsStateWithLifecycle(initialValue = 0L)
                    val liveReading = remember(liveSong.id, liveMetadata, liveDurationMs) {
                        LiveAudioFormat.from(
                            metadata = liveMetadata,
                            durationMs = liveDurationMs,
                            songId = liveSong.id
                        )
                    }
                    LaunchedEffect(liveSong.id, liveReading) {
                        trackDetailsViewModel.bind(
                            song = liveSong,
                            live = liveReading
                        )
                    }
                    // With the menu mounted, closing returns to it. Without it — the DETAILS
                    // entry — there is nothing behind, so closing leaves the overlay entirely
                    // rather than dropping the user onto a surface they did not open.
                    val closeDetails = {
                        if (showsSongActions) secondaryView = null else onDismissSongInfo()
                    }
                    SecondaryViewWindow(onDismiss = closeDetails) {
                        // The marker and the seek mapping use the duration the waveform was
                        // actually built from, so 100% lands on the end of the drawn envelope. The
                        // library duration is only a fallback while the waveform is still loading.
                        val waveformDurationMs = trackDetailsState.waveform?.durationMs ?: 0L
                        TrackDetailsSheet(
                            state = trackDetailsState,
                            currentPositionProvider = positionProvider,
                            totalDurationMs = waveformDurationMs.takeIf { it > 0L } ?: liveSong.duration,
                            onSeek = onSeekFromWaveform,
                            onBackClick = closeDetails
                        )
                    }
                }

                SecondaryView.CREDITS -> {
                    LaunchedEffect(liveSong.id) {
                        creditsViewModel.bind(liveSong)
                    }
                    SecondaryViewWindow(onDismiss = { secondaryView = null }) {
                        CreditsSheet(
                            state = creditsState,
                            onBackClick = { secondaryView = null }
                        )
                    }
                }

                null -> Unit
            }
        }
    }
}

/** The informational sibling views reachable from the Now Playing More menu. */
private enum class SecondaryView { TRACK_DETAILS, CREDITS }

/**
 * Hosts a secondary view in a full-bleed dialog window so it can cover the More menu's own modal
 * window, and routes the system back gesture to the same dismissal as the view's back button.
 */
@Composable
private fun SecondaryViewWindow(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
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
        content()
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun UnifiedPlayerQueueAndSongInfoHost(
    shouldRenderHost: Boolean,
    isQueueTelemetryActive: Boolean,
    albumColorScheme: ColorScheme,
    albumAccent: androidx.compose.ui.graphics.Color? = null,
    queueScrimAlpha: Float,
    showQueueSheet: Boolean,
    queueHiddenOffsetPx: Float,
    queueSheetOffset: Animatable<Float, AnimationVector1D>,
    queueSheetHeightPx: Float,
    onQueueSheetHeightPxChange: (Float) -> Unit,
    /** B33-U1: how much of the queue sheet's top shows at rest (its folded state), in px; 0 when it has none. */
    foldedStripPx: Float,
    /** Opens the sheet (the folded state's tap and its accessibility action). */
    onOpenQueue: () -> Unit,
    configurationResetKey: Any,
    currentQueueSourceName: String,
    infrequentPlayerState: StablePlayerState,
    playerViewModel: PlayerViewModel,
    selectedSongForInfo: Song?,
    songInfoEntry: SongInfoEntry,
    onSelectedSongForInfoChange: (Song?, SongInfoEntry) -> Unit,
    onAnimateQueueSheet: (Boolean) -> Unit,
    onBeginQueueDrag: () -> Unit,
    onDragQueueBy: (Float) -> Unit,
    onEndQueueDrag: (Float, Float) -> Unit,
    onLaunchSaveQueueOverlay: (List<Song>, String, (String, Set<String>) -> Unit) -> Unit,
    onNavigateToAlbum: (Song) -> Unit,
    onNavigateToArtist: (Song) -> Unit,
    onNavigateToGenre: (Song) -> Unit
) {
    if (!shouldRenderHost) return

    val currentPlaybackQueue by playerViewModel.queueFlow.collectAsStateWithLifecycle()
    val latestPlaybackQueue = rememberUpdatedState(currentPlaybackQueue)
    val latestQueueSourceName = rememberUpdatedState(currentQueueSourceName)
    val inactiveTimerValueDisplayState = rememberUpdatedState<String?>(null)
    val inactiveTimerDurationMinutesState = rememberUpdatedState<Int?>(null)
    val inactivePlayCountState = rememberUpdatedState(0f)
    val inactiveEndOfTrackTimerActiveState = rememberUpdatedState(false)
    val activeTimerValueDisplay: State<String?> =
        if (isQueueTelemetryActive) {
            playerViewModel.activeTimerValueDisplay.collectAsStateWithLifecycle()
        } else {
            inactiveTimerValueDisplayState
        }
    val activeTimerDurationMinutes: State<Int?> =
        if (isQueueTelemetryActive) {
            playerViewModel.activeTimerDurationMinutes.collectAsStateWithLifecycle()
        } else {
            inactiveTimerDurationMinutesState
        }
    val playCount: State<Float> =
        if (isQueueTelemetryActive) {
            playerViewModel.playCount.collectAsStateWithLifecycle()
        } else {
            inactivePlayCountState
        }
    val isEndOfTrackTimerActive: State<Boolean> =
        if (isQueueTelemetryActive) {
            playerViewModel.isEndOfTrackTimerActive.collectAsStateWithLifecycle()
        } else {
            inactiveEndOfTrackTimerActiveState
        }

    CompositionLocalProvider(
        LocalMaterialTheme provides albumColorScheme
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val onDismissQueueRequest = remember(onAnimateQueueSheet) { { onAnimateQueueSheet(false) } }
            val onQueueSongInfoClick = remember(onSelectedSongForInfoChange, playerViewModel) {
                // The queue's More affordance is an action menu, not the details view. It also remembers the row it was opened
                // from: "remove from list" in that menu acts on this occurrence of the song, not on the first one (B18-R5).
                { song: Song, queueIndex: Int ->
                    onSelectedSongForInfoChange(song, SongInfoEntry.ACTIONS)
                    playerViewModel.noteQueueRowForSongActions(song.id, queueIndex)
                }
            }
            val onPlayQueueSong = remember(playerViewModel) {
                { song: Song, index: Int ->
                    playerViewModel.showAndPlaySong(
                        song = song,
                        contextSongs = latestPlaybackQueue.value,
                        queueName = latestQueueSourceName.value,
                        indexInQueue = index
                    )
                }
            }
            val onRemoveQueueSong = remember(playerViewModel) {
                { id: String, queueIndex: Int -> playerViewModel.removeSongFromQueue(id, queueIndex) }
            }
            val onReorderQueue = remember(playerViewModel) {
                { from: Int, to: Int -> playerViewModel.reorderQueueItem(from, to) }
            }
            val onToggleRepeat = remember(playerViewModel) { { playerViewModel.cycleRepeatMode() } }
            val onToggleShuffle = remember(playerViewModel) { { playerViewModel.toggleShuffle() } }
            val onClearQueue = remember(playerViewModel) { { playerViewModel.clearQueueExceptCurrent() } }
            val onSetPredefinedTimer = remember(playerViewModel) {
                { minutes: Int -> playerViewModel.setSleepTimer(minutes) }
            }
            val onSetEndOfTrackTimer = remember(playerViewModel) {
                { enable: Boolean -> playerViewModel.setEndOfTrackTimer(enable) }
            }
            val onOpenCustomTimePicker: () -> Unit = remember {
                { Timber.tag("TimerOptions").d("OpenCustomTimePicker clicked") }
            }
            val onCancelTimer = remember(playerViewModel) { { playerViewModel.cancelSleepTimer() } }
            val onCancelCountedPlay = remember(playerViewModel) { playerViewModel::cancelCountedPlay }
            val onPlayCounter = remember(playerViewModel) { playerViewModel::playCounted }
            val onRequestSavePlaylist = remember(onLaunchSaveQueueOverlay) {
                { songs: List<Song>, defName: String, onConf: (String, Set<String>) -> Unit ->
                    onLaunchSaveQueueOverlay(songs, defName, onConf)
                }
            }
            val onQueueStartDrag = remember(onBeginQueueDrag) { { onBeginQueueDrag() } }
            val onQueueDrag = remember(onDragQueueBy) { { drag: Float -> onDragQueueBy(drag) } }
            val onQueueRelease = remember(onEndQueueDrag) {
                { drag: Float, vel: Float -> onEndQueueDrag(drag, vel) }
            }
            val playbackQueueProvider = remember {
                { latestPlaybackQueue.value }
            }
            val queueSourceNameProvider = remember {
                { latestQueueSourceName.value }
            }

            UnifiedPlayerQueueLayer(
                playerViewModel = playerViewModel,
                shouldRenderLayer = true,
                albumColorScheme = albumColorScheme,
                albumAccent = albumAccent,
                queueScrimAlpha = queueScrimAlpha,
                showQueueSheet = showQueueSheet,
                queueHiddenOffsetPx = queueHiddenOffsetPx,
                queueSheetOffset = queueSheetOffset,
                queueSheetHeightPx = queueSheetHeightPx,
                onQueueSheetHeightPxChange = onQueueSheetHeightPxChange,
                foldedStripPx = foldedStripPx,
                onOpenQueue = onOpenQueue,
                configurationResetKey = configurationResetKey,
                currentPlaybackQueue = currentPlaybackQueue,
                currentQueueSourceName = currentQueueSourceName,
                currentMediaItemIndex = infrequentPlayerState.currentMediaItemIndex,
                infrequentPlayerState = infrequentPlayerState,
                activeTimerValueDisplay = activeTimerValueDisplay,
                activeTimerDurationMinutes = activeTimerDurationMinutes,
                playCount = playCount,
                isEndOfTrackTimerActive = isEndOfTrackTimerActive,
                onDismissQueue = onDismissQueueRequest,
                onSongInfoClick = onQueueSongInfoClick,
                onPlaySong = onPlayQueueSong,
                onRemoveSong = onRemoveQueueSong,
                onReorder = onReorderQueue,
                onToggleRepeat = onToggleRepeat,
                onToggleShuffle = onToggleShuffle,
                onClearQueue = onClearQueue,
                onSetPredefinedTimer = onSetPredefinedTimer,
                onSetEndOfTrackTimer = onSetEndOfTrackTimer,
                onOpenCustomTimePicker = onOpenCustomTimePicker,
                onCancelTimer = onCancelTimer,
                onCancelCountedPlay = onCancelCountedPlay,
                onPlayCounter = onPlayCounter,
                onRequestSaveAsPlaylist = onRequestSavePlaylist,
                onQueueDragStart = onQueueStartDrag,
                onQueueDrag = onQueueDrag,
                onQueueRelease = onQueueRelease
            )

            UnifiedPlayerSongInfoLayer(
                selectedSongForInfo = selectedSongForInfo,
                songInfoEntry = songInfoEntry,
                albumColorScheme = albumColorScheme,
                playerViewModel = playerViewModel,
                currentPlaybackQueueProvider = playbackQueueProvider,
                currentQueueSourceNameProvider = queueSourceNameProvider,
                onDismissSongInfo = {
                    playerViewModel.clearQueueRowForSongActions()
                    onSelectedSongForInfoChange(null, SongInfoEntry.ACTIONS)
                },
                onNavigateToAlbum = onNavigateToAlbum,
                onNavigateToArtist = onNavigateToArtist,
                onNavigateToGenre = onNavigateToGenre
            )
        }
    }
}

@Composable
internal fun UnifiedPlayerSaveQueueLayer(
    pendingOverlay: SaveQueueOverlayData?,
    onDismissOverlay: () -> Unit
) {
    pendingOverlay?.let { overlay ->
        SaveQueueAsPlaylistSheet(
            songs = overlay.songs,
            defaultName = overlay.defaultName,
            onDismiss = onDismissOverlay,
            onConfirm = { name, selectedIds ->
                overlay.onConfirm(name, selectedIds)
                onDismissOverlay()
            }
        )
    }
}
