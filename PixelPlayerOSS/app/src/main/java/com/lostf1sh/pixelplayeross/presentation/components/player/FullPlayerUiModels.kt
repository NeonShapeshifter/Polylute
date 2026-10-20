package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.runtime.Immutable
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.data.repository.LyricsSearchResult
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.utils.ValidatedLyricsImport
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

/*
 * B21: what the full player draws, and what it can do.
 *
 * `FullPlayerContent` used to be one 2,362-line function that took 40 parameters, collected from the
 * PlayerViewModel itself and handed the ViewModel to three of its own children. The surfaces are now
 * separate files that each take **one slice** of [FullPlayerUiState] and the one [FullPlayerActions]:
 *
 *   cover (`PlayerCoverSurface`)       <- [PlayerCoverState]
 *   title / artist / like / loading    <- [PlayerTrackInfoState]       (`PlayerTrackInfoSurface`)
 *   seek bar, times, technical line    <- [PlayerProgressState]        (`PlayerProgressSurface`)
 *   transport, shuffle, repeat         <- [PlayerControlsState]        (`PlayerControlsSurface`)
 *   header tabs, details, queue        <- the header and the queue drag (`FullPlayerContent`)
 *   lyrics                             <- [PlayerLyricsState]          (`PlayerLyricsEntry`)
 *
 * The slices are separate classes so a song change that leaves one of them equal (the controls, when shuffle
 * and repeat did not change) does not recompose its surface. Values that change every few hundred
 * milliseconds (the position, whether it is playing) are *providers*: a surface reads them in the phase that
 * draws them, not in composition.
 */

/** What the surfaces need from the sheet around them to place themselves and to time their content. */
@Immutable
data class PlayerSheetEnv(
    val loadingTweaks: FullPlayerLoadingTweaks,
    val isSheetDragGestureActive: Boolean,
    val currentSheetState: PlayerSheetState,
    /** False while the sheet is dragged or hidden: nothing may tick in real time. */
    val allowRealtimeUpdates: Boolean,
    /** 0 collapsed .. 1 expanded; read in the draw phase. */
    val expansionFractionProvider: () -> Float,
)

/** The artwork carousel. */
@Immutable
data class PlayerCoverState(
    val song: Song,
    val queue: ImmutableList<Song>,
    val queueSourceName: String,
    /** The queue position of [song] (resolved against the queue), or the player's own index when it cannot be. */
    val mediaItemIndex: Int,
    val carouselStyle: String,
    val albumArtQuality: AlbumArtQuality,
    /** Where the carousel should already be scrolling to: a skip was asked for, the player has not got there yet. */
    val requestedScrollIndex: Int?,
    val isPlayingProvider: () -> Boolean,
    val playWhenReadyProvider: () -> Boolean,
)

/** The title, the artist, the heart and the loading mark. */
@Immutable
data class PlayerTrackInfoState(
    val song: Song,
    val artists: ImmutableList<Artist>,
    val isFavorite: Boolean,
    val isBuffering: Boolean,
    val isPlayingProvider: () -> Boolean,
)

/** The seek bar, the two times and the technical line (format, rate, depth). */
@Immutable
data class PlayerProgressState(
    val song: Song,
    /** What the player says it is decoding right now; used only to fill gaps, and only when it is this song. */
    val runtimeMediaId: String?,
    val runtimeMimeType: String?,
    val runtimeSampleRateHz: Int?,
    val showFileInfo: Boolean,
    val currentPositionProvider: () -> Long,
    val totalDurationProvider: () -> Long,
    val isPlayingProvider: () -> Boolean,
) {
    companion object {
        fun of(
            song: Song,
            metadata: PlaybackAudioMetadata,
            showFileInfo: Boolean,
            currentPositionProvider: () -> Long,
            totalDurationProvider: () -> Long,
            isPlayingProvider: () -> Boolean,
        ) = PlayerProgressState(
            song = song,
            runtimeMediaId = metadata.mediaId,
            runtimeMimeType = metadata.mimeType,
            runtimeSampleRateHz = metadata.sampleRate,
            showFileInfo = showFileInfo,
            currentPositionProvider = currentPositionProvider,
            totalDurationProvider = totalDurationProvider,
            isPlayingProvider = isPlayingProvider,
        )
    }
}

/** Play/pause, previous, next, shuffle, repeat. Nothing here depends on which song it is. */
@Immutable
data class PlayerControlsState(
    val isShuffleEnabled: Boolean,
    val repeatMode: Int,
    val isPlayingProvider: () -> Boolean,
)

/** What the lyrics layer needs. */
@Immutable
data class PlayerLyricsState(
    val searchState: LyricsSearchUiState,
    /** The player's own streams, which the lyrics view follows itself (it ticks faster than a state value should). */
    val stablePlayerStateFlow: StateFlow<StablePlayerState>,
    val playbackPositionFlow: StateFlow<Long>,
    val externalLyricsEnabled: Boolean,
    val lyricsSyncOffset: Int,
    val immersiveLyricsEnabled: Boolean,
    val immersiveLyricsTimeout: Long,
    val isImmersiveTemporarilyDisabled: Boolean,
    val isShuffleEnabled: Boolean,
    val repeatMode: Int,
    val isFavorite: Boolean,
    val lyricsProvider: () -> Lyrics?,
    val lyricsResolveCompletedProvider: () -> Boolean,
)

/** Everything the full player shows. Built once per composition of the host; each surface reads its own slice. */
@Immutable
data class FullPlayerUiState(
    val env: PlayerSheetEnv,
    val cover: PlayerCoverState,
    val trackInfo: PlayerTrackInfoState,
    val progress: PlayerProgressState,
    val controls: PlayerControlsState,
    val lyrics: PlayerLyricsState,
    val isLandscape: Boolean,
)

/**
 * Builds the state the full player draws from what its host collected.
 *
 * Pure, so a test can build the state for two songs and compare the slices: what a song change must not touch (the
 * environment, the controls, the lyrics layer when nothing in it moved) compares equal, and a surface whose slice
 * compares equal is skipped by Compose.
 */
internal fun buildFullPlayerUiState(
    env: PlayerSheetEnv,
    song: Song,
    queue: ImmutableList<Song>,
    queueSourceName: String,
    mediaItemIndex: Int,
    carouselStyle: String,
    artists: ImmutableList<Artist>,
    albumArtQuality: AlbumArtQuality,
    audioMetadata: PlaybackAudioMetadata,
    showFileInfo: Boolean,
    isFavorite: Boolean,
    isBuffering: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    isPlayingProvider: () -> Boolean,
    playWhenReadyProvider: () -> Boolean,
    currentPositionProvider: () -> Long,
    totalDurationProvider: () -> Long,
    lyrics: PlayerLyricsState,
    isLandscape: Boolean,
): FullPlayerUiState = FullPlayerUiState(
    env = env,
    cover = PlayerCoverState(
        song = song,
        queue = queue,
        queueSourceName = queueSourceName,
        mediaItemIndex = mediaItemIndex,
        carouselStyle = carouselStyle,
        albumArtQuality = albumArtQuality,
        requestedScrollIndex = null,
        isPlayingProvider = isPlayingProvider,
        playWhenReadyProvider = playWhenReadyProvider,
    ),
    trackInfo = PlayerTrackInfoState(
        song = song,
        artists = artists,
        isFavorite = isFavorite,
        isBuffering = isBuffering,
        isPlayingProvider = isPlayingProvider,
    ),
    progress = PlayerProgressState.of(
        song = song,
        metadata = audioMetadata,
        showFileInfo = showFileInfo,
        currentPositionProvider = currentPositionProvider,
        totalDurationProvider = totalDurationProvider,
        isPlayingProvider = isPlayingProvider,
    ),
    controls = PlayerControlsState(
        isShuffleEnabled = isShuffleEnabled,
        repeatMode = repeatMode,
        isPlayingProvider = isPlayingProvider,
    ),
    lyrics = lyrics,
    isLandscape = isLandscape,
)

/**
 * Everything the full player can do. Implemented by the host (`PlayerViewModelFullPlayerActions`); the surfaces
 * never see a ViewModel.
 */
interface FullPlayerActions {
    // ── transport ──
    fun onPlayPause()
    fun onSeek(positionMs: Long)
    /** Skips with the artwork already scrolling to its target (see `PlayerSkipCoordinator`). */
    fun onSkipNext()
    fun onSkipPrevious()
    /** A plain skip, for the surfaces that have no carousel to keep in step (lyrics). */
    fun onNext()
    fun onPrevious()
    fun onShuffleToggle()
    fun onRepeatToggle()
    fun onFavoriteToggle()

    // ── the entries to the other surfaces ──
    fun onCollapse()
    /** The "Details" tab: the track-details view of the current song. */
    fun onShowDetails()
    fun onQueueDragStart()
    fun onQueueDrag(dragAmount: Float)
    fun onQueueRelease(totalDrag: Float, velocity: Float)

    // ── the track ──
    fun onPlayFromCarousel(song: Song, queue: ImmutableList<Song>, queueName: String, index: Int)
    fun onOpenAlbum(albumId: Long)
    fun onOpenArtist(artistId: Long)
    fun onAddBookmark(song: Song, title: String, positionMs: Long)

    // ── lyrics ──
    fun onEnsureLyricsResolved()
    fun onFetchLyrics(forcePick: Boolean)
    fun onAcceptLyricsResult(result: LyricsSearchResult)
    fun onSearchLyricsManually(title: String, artist: String?)
    fun onResetLyricsSearch()
    fun onResetLyrics()
    fun onLyricsSyncOffsetChange(songId: String, offsetMs: Int)
    fun onLyricsAlignmentChange(alignment: String)
    fun onShowLyricsTranslationChange(show: Boolean)
    fun onShowLyricsRomanizationChange(show: Boolean)
    fun onImmersiveTemporarilyDisabledChange(disabled: Boolean)
    fun onSaveLyricsToFile(song: Song, lyrics: Lyrics, preferSynced: Boolean)
    fun onImportLyricsFromFile(songId: Long, validated: ValidatedLyricsImport)
    fun onToast(message: String)

    /** The same position `previousSong()` decides with, not the display tick. */
    fun previousDecisionPositionMs(): Long
}
