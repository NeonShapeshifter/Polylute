package com.lostf1sh.pixelplayeross.presentation.components.player

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioBookmarksViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The full player's host: joins what the PlayerViewModel publishes into a [FullPlayerUiState] and hands
 * [FullPlayerScreen] the actions. It is the only place the player's surfaces meet a ViewModel.
 *
 * B21: this used to be the 2,362-line player itself. The surfaces are in `PlayerCoverSurface`,
 * `PlayerTrackInfoSurface`, `PlayerProgressSurface` (+ `PlayerTechnicalLine`), `PlayerControlsSurface` and
 * `PlayerLyricsEntry`; the header, the layouts and the layers are in `FullPlayerScreen`/`PlayerLayouts`. The
 * parameters are what they were, so the sheet that hosts it did not change.
 *
 * Nothing is read here that changes faster than a song does. The position, whether it is playing, the duration
 * and the lyrics are passed as providers and read by the one surface that draws them.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun FullPlayerContent(
    currentSong: Song?,
    currentPlaybackQueue: ImmutableList<Song>,
    currentQueueSourceName: String,
    currentMediaItemIndex: Int = -1,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    allowRealtimeUpdates: Boolean = true,
    expansionFractionProvider: () -> Float,
    currentSheetState: PlayerSheetState,
    carouselStyle: String,
    loadingTweaks: FullPlayerLoadingTweaks,
    isSheetDragGestureActive: Boolean = false,
    playerViewModel: PlayerViewModel,
    currentPositionProvider: () -> Long,
    isPlayingProvider: () -> Boolean,
    playWhenReadyProvider: () -> Boolean,
    isFavoriteProvider: () -> Boolean,
    totalDurationProvider: () -> Long,
    lyricsProvider: () -> Lyrics? = { null },
    lyricsResolveCompletedProvider: () -> Boolean = { false },
    /** Whether online lyrics are enabled, so the lyrics surface never offers a refused action. */
    externalLyricsEnabled: Boolean = false,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCollapse: () -> Unit,
    onShowSongInfo: () -> Unit,
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: () -> Unit
) {
    var retainedSong by remember { mutableStateOf(currentSong) }
    LaunchedEffect(currentSong?.id) {
        if (currentSong != null) {
            retainedSong = currentSong
        }
    }

    val song = currentSong ?: retainedSong ?: return
    val bookmarksViewModel: AudioBookmarksViewModel = hiltViewModel()

    val lyricsSearchUiState by playerViewModel.lyricsSearchUiState.collectAsStateWithLifecycle()
    val fullPlayerSlice by playerViewModel.fullPlayerSlice.collectAsStateWithLifecycle()
    // Only the one flag the track info draws, and only when it flips: the whole player state ticks on far more.
    val isBufferingFlow = remember(playerViewModel) {
        playerViewModel.stablePlayerState.map { it.isBuffering }.distinctUntilChanged()
    }
    val isBuffering by isBufferingFlow.collectAsStateWithLifecycle(
        initialValue = playerViewModel.stablePlayerState.value.isBuffering
    )

    val callbacks = rememberUpdatedState(
        PlayerSheetCallbacks(
            onPlayPause = onPlayPause,
            onSeek = onSeek,
            onNext = onNext,
            onPrevious = onPrevious,
            onCollapse = onCollapse,
            onShowDetails = onShowSongInfo,
            onQueueDragStart = onQueueDragStart,
            onQueueDrag = onQueueDrag,
            onQueueRelease = onQueueRelease,
            onShuffleToggle = onShuffleToggle,
            onRepeatToggle = onRepeatToggle,
            onFavoriteToggle = onFavoriteToggle,
        )
    )
    val actions = remember(playerViewModel, bookmarksViewModel) {
        PlayerViewModelFullPlayerActions(playerViewModel, bookmarksViewModel, callbacks)
    }

    val isFavorite = isFavoriteProvider()
    val state = buildFullPlayerUiState(
        env = PlayerSheetEnv(
            loadingTweaks = loadingTweaks,
            isSheetDragGestureActive = isSheetDragGestureActive,
            currentSheetState = currentSheetState,
            allowRealtimeUpdates = allowRealtimeUpdates,
            expansionFractionProvider = expansionFractionProvider,
        ),
        song = song,
        queue = currentPlaybackQueue,
        queueSourceName = currentQueueSourceName,
        mediaItemIndex = currentMediaItemIndex,
        carouselStyle = carouselStyle,
        artists = fullPlayerSlice.currentSongArtists,
        albumArtQuality = fullPlayerSlice.albumArtQuality,
        audioMetadata = fullPlayerSlice.audioMetadata,
        showFileInfo = fullPlayerSlice.showPlayerFileInfo,
        isFavorite = isFavorite,
        isBuffering = isBuffering,
        isShuffleEnabled = isShuffleEnabled,
        repeatMode = repeatMode,
        isPlayingProvider = isPlayingProvider,
        playWhenReadyProvider = playWhenReadyProvider,
        currentPositionProvider = currentPositionProvider,
        totalDurationProvider = totalDurationProvider,
        lyrics = PlayerLyricsState(
            searchState = lyricsSearchUiState,
            stablePlayerStateFlow = playerViewModel.stablePlayerState,
            playbackPositionFlow = playerViewModel.currentPlaybackPosition,
            externalLyricsEnabled = externalLyricsEnabled,
            lyricsSyncOffset = fullPlayerSlice.lyricsSyncOffset,
            immersiveLyricsEnabled = fullPlayerSlice.immersiveLyricsEnabled,
            immersiveLyricsTimeout = fullPlayerSlice.immersiveLyricsTimeout,
            isImmersiveTemporarilyDisabled = fullPlayerSlice.isImmersiveTemporarilyDisabled,
            isShuffleEnabled = isShuffleEnabled,
            repeatMode = repeatMode,
            isFavorite = isFavorite,
            lyricsProvider = lyricsProvider,
            lyricsResolveCompletedProvider = lyricsResolveCompletedProvider,
        ),
        isLandscape = isLandscapeNow(),
    )
    FullPlayerScreen(state, actions)
}
