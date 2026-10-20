package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Trace
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.Animatable
import androidx.core.content.ContextCompat
import com.lostf1sh.pixelplayeross.presentation.library.resolveVisibleTabs
import com.lostf1sh.pixelplayeross.data.model.DEFAULT_LIBRARY_TAB_KEYS
import com.lostf1sh.pixelplayeross.data.model.LibraryTabId
import com.lostf1sh.pixelplayeross.data.model.reconcileLibraryTabOrder
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.Timeline
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.EotStateHolder
import com.lostf1sh.pixelplayeross.data.database.AlbumArtThemeDao
import com.lostf1sh.pixelplayeross.data.media.CoverArtUpdate
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.FolderSource
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.model.toLibraryTabIdOrNull
import com.lostf1sh.pixelplayeross.data.provider.SharedArtworkContentProvider
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.LibraryNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.NavBarStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtPaletteStyle
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreference
import com.lostf1sh.pixelplayeross.data.repository.LyricsSearchResult
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.service.MusicNotificationProvider
import com.lostf1sh.pixelplayeross.data.service.MusicService
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.data.stats.TrackEndReason
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.worker.SyncManager
import com.lostf1sh.pixelplayeross.utils.AppShortcutManager
import com.lostf1sh.pixelplayeross.utils.ValidatedLyricsImport
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import com.lostf1sh.pixelplayeross.utils.QueueUtils
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import com.lostf1sh.pixelplayeross.utils.LyricsUtils
import com.lostf1sh.pixelplayeross.utils.StorageType
import com.lostf1sh.pixelplayeross.utils.StorageUtils
import com.lostf1sh.pixelplayeross.utils.timedSection
import com.lostf1sh.pixelplayeross.utils.traceSection
import com.lostf1sh.pixelplayeross.utils.ZipShareHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import com.lostf1sh.pixelplayeross.data.service.QueueFileProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import androidx.paging.PagingData
import androidx.paging.cachedIn
import coil.memory.MemoryCache

private const val ENABLE_FOLDERS_SOURCE_SWITCHING = true
private const val MAX_ALBUM_BATCH_SELECTION = 6
private const val SONG_ID_QUERY_CHUNK_SIZE = 900
private const val HOME_MIX_PREVIEW_LIMIT = 48
private const val EXTERNAL_SONG_ID_PREFIX = "external:"

/** F-09: how long an external load holds the overlay while the media controller connects. */
private const val EXTERNAL_LOAD_GRACE_MS = 5_000L

/**
 * FIX-1c: how long an external VIEW's play waits for its own load, which waits for the MediaController.
 * Measured on the device: 0.7 s to 5.8 s on a freshly started process. The wait costs nothing (a
 * suspended coroutine) and the VIEW must not give up on a slow connection, so the bound only exists
 * for a controller that never connects.
 */
private const val EXTERNAL_CONTROLLER_WAIT_MS = 30_000L

/** How long the Quick Settings tile waits for the saved sort and filter to be read (B24-Q1); it goes on with the defaults after that. */
private const val SELECTION_LOAD_WAIT_MS = 5_000L

/** B31 P2.2: how long a "Shuffle" press's log waits for the first timeline before it is written without it. */
private const val FIRST_TIMELINE_WAIT_MS = 5_000L

/** The sources the Songs list and the Liked list record for what they play (B28). */
private val LibrarySource = ListenSource(ListenSourceKind.LIBRARY)
private val LikedSource = ListenSource(ListenSourceKind.LIBRARY, "liked")

/** FIX-1c: how long the timeline waits for the first audible-state confirmation before it is written anyway. */
private const val EXTERNAL_PLAYING_WAIT_MS = 5_000L

private val LOCAL_PLAYBACK_SCHEMES = setOf("content", "file", "android.resource")

private fun List<Song>.toPlaybackQueue(): ImmutableList<Song> = when (this) {
    is PersistentList<Song> -> this
    is ImmutableList<Song> -> this
    else -> this.toPersistentList()
}

private fun ImmutableList<Song>.asPersistentPlaybackQueue(): PersistentList<Song> =
    this as? PersistentList<Song> ?: this.toPersistentList()

private fun ImmutableList<Song>.replaceSong(updatedSong: Song): ImmutableList<Song> {
    val index = indexOfFirst { it.id == updatedSong.id }
    if (index == -1) return this
    return asPersistentPlaybackQueue().replacingAt(index, updatedSong)
}

private fun ImmutableList<Song>.removeSongById(songId: String): ImmutableList<Song> {
    val index = indexOfFirst { it.id == songId }
    if (index == -1) return this
    return asPersistentPlaybackQueue().removingAt(index)
}

private fun ImmutableList<Song>.moveSong(fromIndex: Int, toIndex: Int): ImmutableList<Song> {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) return this
    val movedSong = this[fromIndex]
    return asPersistentPlaybackQueue()
        .removingAt(fromIndex)
        .addingAt(toIndex, movedSong)
}

private fun moveQueueIndex(index: Int, fromIndex: Int, toIndex: Int): Int {
    if (index == C.INDEX_UNSET || fromIndex == toIndex) return index
    return when {
        index == fromIndex -> toIndex
        fromIndex < toIndex && index in (fromIndex + 1)..toIndex -> index - 1
        toIndex < fromIndex && index in toIndex until fromIndex -> index + 1
        else -> index
    }
}

private data class QueueTimelineSignature(
    val count: Int,
    val orderHash: Long,
    val firstMediaId: String?,
    val lastMediaId: String?
)

data class PlaybackAudioMetadata(
    val mediaId: String? = null,
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val bitDepth: Int? = null
)

private data class SortOptionsSnapshot(
    val songSort: SortOption,
    val albumSort: SortOption,
    val artistSort: SortOption,
    val folderSort: SortOption,
    val favoriteSort: SortOption,
)

private data class PreparedPlaybackQueueSegments(
    val beforeCurrent: List<MediaItem>,
    val afterCurrent: List<MediaItem>,
    val currentIndex: Int
)

private data class PendingMetadataEdit(
    val song: Song,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val composer: String,
    val genre: String,
    val lyrics: String,
    val trackNumber: Int,
    val discNumber: Int?,
    val replayGainTrackGainDb: String?,
    val replayGainAlbumGainDb: String?,
    val coverArtUpdate: CoverArtUpdate?
)

private data class PendingBatchMetadataEdit(
    val songs: List<Song>,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val composer: String?,
    val genre: String?,
    val lyrics: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val replayGainTrackGainDb: String?,
    val replayGainAlbumGainDb: String?,
    val coverArtUpdate: CoverArtUpdate?
)

private data class PendingLyricsSave(
    val song: Song,
    val lyrics: Lyrics,
    val preferSynced: Boolean
)

private data class ResolvedAlbumSelection(
    val albums: List<Album>,
    val songs: List<Song>,
    val wasTrimmed: Boolean
)

/**
 * Every Library tab the product ships, in the default order.
 *
 * Derived from [LibraryTabId] rather than written out, so a tab cannot exist in the enum without
 * also existing in the strip. The hand-written copy this replaces is exactly how the generated
 * views ended up unreachable.
 */
internal val DEFAULT_LIBRARY_TABS = DEFAULT_LIBRARY_TAB_KEYS

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("LogNotTimber")
@OptIn(coil.annotation.ExperimentalCoilApi::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val albumArtThemeDao: AlbumArtThemeDao,
    val syncManager: SyncManager,

    private val dualPlayerEngine: DualPlayerEngine,
    private val appShortcutManager: AppShortcutManager,
    private val listeningStatsTracker: ListeningStatsTracker,
    private val dailyMixStateHolder: DailyMixStateHolder,
    private val lyricsStateHolder: LyricsStateHolder,
    private val queueStateHolder: QueueStateHolder,
    private val queueUndoStateHolder: QueueUndoStateHolder,
    private val playbackStateHolder: PlaybackStateHolder,
    private val connectivityStateHolder: ConnectivityStateHolder,
    private val sleepTimerStateHolder: SleepTimerStateHolder,
    private val searchStateHolder: SearchStateHolder,
    private val libraryStateHolder: LibraryStateHolder,
    private val folderNavigationStateHolder: FolderNavigationStateHolder,
    private val libraryTabsStateHolder: LibraryTabsStateHolder,
    private val metadataEditStateHolder: MetadataEditStateHolder,
    private val songRemovalStateHolder: SongRemovalStateHolder,
    private val externalMediaStateHolder: ExternalMediaStateHolder,
    val themeStateHolder: ThemeStateHolder,
    val multiSelectionStateHolder: MultiSelectionStateHolder,
    val playlistSelectionStateHolder: PlaylistSelectionStateHolder,
    private val sessionToken: SessionToken,
    private val mediaControllerFactory: com.lostf1sh.pixelplayeross.data.media.MediaControllerFactory
) : ViewModel() {

    private val _playerUiState = MutableStateFlow(PlayerUiState())
    val playerUiState: StateFlow<PlayerUiState> = _playerUiState.asStateFlow()

    val queueFlow: StateFlow<ImmutableList<Song>> = _playerUiState
        .map { it.currentPlaybackQueue }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = persistentListOf()
        )

    val stablePlayerState: StateFlow<StablePlayerState> = playbackStateHolder.stablePlayerState
    val albumArtPaletteStyle: StateFlow<AlbumArtPaletteStyle> = themePreferencesRepository
        .albumArtPaletteStyleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AlbumArtPaletteStyle.default
        )
    /**
     * High-frequency playback position should not force global UI recomposition.
     * Keep a dedicated position flow for real-time UI elements (seek bars, lyrics timing).
     */
    val currentPlaybackPosition: StateFlow<Long> = playbackStateHolder.currentPosition

    /** B25-S3: the position read from the player now, for a surface that has no collector of [currentPlaybackPosition]. */
    fun samplePlaybackPositionNow(): Long = playbackStateHolder.samplePlaybackPositionNow()
    val playbackHistory = listeningStatsTracker.playbackHistory

    private val lyricsLoadCallback = object : LyricsLoadCallback {
        override fun onLoadingStarted(requestId: Long, songId: String) {
            playbackStateHolder.updateStablePlayerState { state ->
                if (state.currentSong?.id != songId) state
                else state.copy(
                    isLoadingLyrics = true,
                    lyrics = null,
                    lyricsResolveCompleted = false,
                    lyricsError = null
                )
            }
        }

        override fun onLyricsSettled(requestId: Long, songId: String, outcome: LyricsOutcome) {
            playbackStateHolder.updateStablePlayerState { state ->
                if (state.currentSong?.id != songId) state
                else when (outcome) {
                    is LyricsOutcome.Resolved -> state.copy(
                        isLoadingLyrics = false,
                        lyrics = outcome.lyrics,
                        lyricsResolveCompleted = true,
                        lyricsError = null
                    )

                    is LyricsOutcome.Failed -> state.copy(
                        isLoadingLyrics = false,
                        lyrics = null,
                        lyricsResolveCompleted = true,
                        lyricsError = outcome.message
                    )

                    // Cancelled means the owner stopped asking, not that the song has no lyrics,
                    // so the lyrics already in state are left alone. It still has to be terminal:
                    // clearing the loading flag here is what makes a permanent spinner impossible.
                    LyricsOutcome.Cancelled -> state.copy(
                        isLoadingLyrics = false,
                        lyricsResolveCompleted = true
                    )
                }
            }
        }
    }



    private val _playlistPickerStorageFilter = MutableStateFlow(com.lostf1sh.pixelplayeross.data.model.StorageFilter.OFFLINE)
    val playlistPickerStorageFilter: StateFlow<com.lostf1sh.pixelplayeross.data.model.StorageFilter> = _playlistPickerStorageFilter.asStateFlow()

    /**
     * Paginated songs for efficient display in LibraryScreen.
     * Uses Paging 3 for memory-efficient loading of large libraries.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val paginatedSongs: Flow<PagingData<Song>> = libraryStateHolder.songsPagingFlow
        .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val playlistPickerFavoriteSongs: Flow<PagingData<Song>> = combine(
        libraryStateHolder.currentSongSortOption,
        _playlistPickerStorageFilter
    ) { sortOption, storageFilter ->
        sortOption to storageFilter
    }
        .flatMapLatest { (sortOption, storageFilter) ->
            musicRepository.getPaginatedFavoriteSongs(
                sortOption = sortOption,
                storageFilter = storageFilter
            )
        }
        .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val playlistPickerSongs: Flow<PagingData<Song>> = combine(
        libraryStateHolder.currentSongSortOption,
        _playlistPickerStorageFilter
    ) { sortOption, storageFilter ->
        sortOption to storageFilter
    }
        .flatMapLatest { (sortOption, storageFilter) ->
            musicRepository.getPaginatedSongs(
                sortOption = sortOption,
                storageFilter = storageFilter
            )
        }
        .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentSongArtists: StateFlow<ImmutableList<Artist>> = stablePlayerState
        .map { it.currentSong?.id }
        .distinctUntilChanged()
        .flatMapLatest { songId ->
            val idLong = songId?.toLongOrNull()
            if (idLong == null) flowOf<ImmutableList<Artist>>(persistentListOf())
            else musicRepository.getArtistsForSong(idLong).map { it.toImmutableList() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentListOf())

    private val _sheetState = MutableStateFlow(PlayerSheetState.COLLAPSED)
    val sheetState: StateFlow<PlayerSheetState> = _sheetState.asStateFlow()

    private val _externalLoadInFlight = MutableStateFlow(false)

    /**
     * F-09: an external VIEW/SEND is being turned into playback (the file is read, the queue replaced,
     * and the replacement possibly waiting for the media controller to connect). The external
     * player's overlay must not dismiss itself while this is true: a restored queue that no longer
     * has a song to show makes `currentSong` null in the middle of the replacement, which is exactly
     * when the overlay used to conclude there was nothing to show and finish the activity.
     */
    val externalLoadInFlight: StateFlow<Boolean> = _externalLoadInFlight.asStateFlow()
    private val _isSheetVisible = MutableStateFlow(false)
    private val _bottomBarHeight = MutableStateFlow(0)
    val bottomBarHeight: StateFlow<Int> = _bottomBarHeight.asStateFlow()
    private val _predictiveBackCollapseFraction = MutableStateFlow(0f)
    val predictiveBackCollapseFraction: StateFlow<Float> = _predictiveBackCollapseFraction.asStateFlow()
    private val _predictiveBackSwipeEdge = MutableStateFlow<Int?>(null)
    val predictiveBackSwipeEdge: StateFlow<Int?> = _predictiveBackSwipeEdge.asStateFlow()
    private val _isQueueSheetVisible = MutableStateFlow(false)
    val isQueueSheetVisible: StateFlow<Boolean> = _isQueueSheetVisible.asStateFlow()

    val playerContentExpansionFraction = Animatable(0f)

    private val _selectedSongForInfo = MutableStateFlow<Song?>(null)
    val selectedSongForInfo: StateFlow<Song?> = _selectedSongForInfo.asStateFlow()

    val currentAlbumArtColorSchemePair: StateFlow<ColorSchemePair?> = themeStateHolder.currentAlbumArtColorSchemePair
    val activePlayerColorSchemePair: StateFlow<ColorSchemePair?> = themeStateHolder.activePlayerColorSchemePair
    val currentThemedAlbumArtUri: StateFlow<String?> = themeStateHolder.currentAlbumArtUri

    val playerThemePreference: StateFlow<String> = themePreferencesRepository.playerThemePreferenceFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemePreference.ALBUM_ART
        )

    val navBarCornerRadius: StateFlow<Int> = userPreferencesRepository.navBarCornerRadiusFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 32)

    val navBarStyle: StateFlow<String> = userPreferencesRepository.navBarStyleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = NavBarStyle.DEFAULT
        )

    val navBarCompactMode: StateFlow<Boolean> = userPreferencesRepository.navBarCompactModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val libraryNavigationMode: StateFlow<String> = userPreferencesRepository.libraryNavigationModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LibraryNavigationMode.TAB_ROW
        )

    val carouselStyle: StateFlow<String> = userPreferencesRepository.carouselStyleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CarouselStyle.NO_PEEK
        )

    val fullPlayerLoadingTweaks: StateFlow<FullPlayerLoadingTweaks> = userPreferencesRepository.fullPlayerLoadingTweaksFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FullPlayerLoadingTweaks()
        )

    val showPlayerFileInfo: StateFlow<Boolean> = userPreferencesRepository.showPlayerFileInfoFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    /**
     * Whether tapping the background of the player sheet toggles its state.
     * When disabled, users must use gestures or buttons to expand/collapse.
     */
    val tapBackgroundClosesPlayer: StateFlow<Boolean> = userPreferencesRepository.tapBackgroundClosesPlayerFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val hapticsEnabled: StateFlow<Boolean> = userPreferencesRepository.hapticsEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val currentSongLyricsSyncOffset: StateFlow<Int> = lyricsStateHolder.currentSongSyncOffset

    val lyricsSourcePreference: StateFlow<LyricsSourcePreference> = userPreferencesRepository.lyricsSourcePreferenceFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LyricsSourcePreference.EMBEDDED_FIRST
        )

    /**
     * Whether online lyrics are enabled at all. The lyrics surface must not offer a search that
     * the repository will refuse: offering it and then failing is a dead end the product creates
     * for itself.
     */
    val externalLyricsEnabled: StateFlow<Boolean> = userPreferencesRepository.externalLyricsEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    val immersiveLyricsEnabled: StateFlow<Boolean> = userPreferencesRepository.immersiveLyricsEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val immersiveLyricsTimeout: StateFlow<Long> = userPreferencesRepository.immersiveLyricsTimeoutFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 4000L
        )

    private val _isImmersiveTemporarilyDisabled = MutableStateFlow(false)
    val isImmersiveTemporarilyDisabled: StateFlow<Boolean> = _isImmersiveTemporarilyDisabled.asStateFlow()

    fun setImmersiveTemporarilyDisabled(disabled: Boolean) {
        _isImmersiveTemporarilyDisabled.value = disabled
    }

    val albumArtQuality: StateFlow<AlbumArtQuality> = userPreferencesRepository.albumArtQualityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlbumArtQuality.MEDIUM)

    fun setLyricsSyncOffset(songId: String, offsetMs: Int) {
        lyricsStateHolder.setSyncOffset(songId, offsetMs)
    }

    /**
     * The three display preferences the Lyrics surface can change.
     *
     * They go through the repository rather than writing DataStore directly, because the repository
     * is the declared owner of these keys and it validates them — `setLyricsAlignment` refuses a
     * value outside `LYRICS_ALIGNMENTS`. The lyrics sheet used to write the same keys itself with
     * raw `context.dataStore.edit` calls, which meant the key had two writers with different rules
     * and the validation could be bypassed from inside the player.
     */
    fun setLyricsAlignment(alignment: String) {
        viewModelScope.launch { userPreferencesRepository.setLyricsAlignment(alignment) }
    }

    fun setShowLyricsTranslation(show: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowLyricsTranslation(show) }
    }

    fun setShowLyricsRomanization(show: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowLyricsRomanization(show) }
    }

    val useSmoothCorners: StateFlow<Boolean> = userPreferencesRepository.useSmoothCornersFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )



    private val _isInitialThemePreloadComplete = MutableStateFlow(false)

    val isEndOfTrackTimerActive: StateFlow<Boolean> = sleepTimerStateHolder.isEndOfTrackTimerActive
    val activeTimerValueDisplay: StateFlow<String?> = sleepTimerStateHolder.activeTimerValueDisplay
    val activeTimerDurationMinutes: StateFlow<Int?> = sleepTimerStateHolder.activeTimerDurationMinutes
    val playCount: StateFlow<Float> = sleepTimerStateHolder.playCount

    val lyricsSearchUiState: StateFlow<LyricsSearchUiState> = lyricsStateHolder.searchUiState

    private var bufferingDebounceJob: Job? = null



    private val _toastEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val toastEvents = _toastEvents.asSharedFlow()

    private val _writePermissionRequest = MutableSharedFlow<android.content.IntentSender>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val writePermissionRequest: SharedFlow<android.content.IntentSender> = _writePermissionRequest.asSharedFlow()

    private val _deletePermissionRequest = MutableSharedFlow<android.content.IntentSender>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val deletePermissionRequest: SharedFlow<android.content.IntentSender> = _deletePermissionRequest.asSharedFlow()

    private var pendingMetadataEdit: PendingMetadataEdit? = null
    private var pendingBatchMetadataEdit: PendingBatchMetadataEdit? = null
    private var pendingLyricsSave: PendingLyricsSave? = null
    private var pendingDeleteSong: Song? = null
    private var pendingDeleteCallback: ((Boolean) -> Unit)? = null

    private val _albumNavigationRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val albumNavigationRequests = _albumNavigationRequests.asSharedFlow()
    private val _artistNavigationRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val artistNavigationRequests = _artistNavigationRequests.asSharedFlow()
    private val _searchNavDoubleTapEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val searchNavDoubleTapEvents = _searchNavDoubleTapEvents.asSharedFlow()
    
    private val _scrollToIndexEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val scrollToIndexEvent = _scrollToIndexEvent.asSharedFlow()
    
    private var albumNavigationJob: Job? = null
    private var artistNavigationJob: Job? = null
    private var fullQueuePlaybackJob: Job? = null
    private var fullQueuePlaybackToken: Long = 0L
    private var directPlaybackJob: Job? = null
    private var directPlaybackToken: Long = 0L
    private var pendingQueueSegmentsJob: Job? = null

    fun requestLocateCurrentSong() {
        val currentSong = stablePlayerState.value.currentSong ?: return

        viewModelScope.launch {
            try {
                val index = libraryIndexOf(currentSong)

                if (index != -1) {
                    _scrollToIndexEvent.emit(index)
                } else {
                    sendToast(context.getString(R.string.player_song_not_found_in_list))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to locate current song")
                sendToast(context.getString(R.string.player_could_not_locate_song))
            }
        }
    }

    /** Where [song] is in the Songs list as the Library shows it (its sort and storage filter), or -1. The one rule both locates use. */
    private suspend fun libraryIndexOf(song: Song): Int {
        val sortedIds = songIdsInLibraryOrder()
        val unifiedId = song.id.toLongOrNull()
            ?: song.contentUriString
                .takeIf { it.isNotBlank() }
                ?.let { musicRepository.getSongIdByContentUri(it) }
        return unifiedId?.let { sortedIds.indexOf(it) } ?: -1
    }

    /**
     * B32-Q2: a song another page (Stats, a collection) wants shown in the Library: held by [LibraryLocator] until the Songs list takes it,
     * and located by the same rule the "locate current song" button uses ([libraryIndexOf]). Nothing here plays anything.
     */
    private val libraryLocator = LibraryLocator(
        scope = viewModelScope,
        resolveIndex = { songId -> musicRepository.getSong(songId).first()?.let { libraryIndexOf(it) } ?: -1 },
        onNotFound = { sendToast(context.getString(R.string.player_song_not_found_in_list)) },
    )

    val libraryLocateRequest: StateFlow<LibraryLocator.Request?> = libraryLocator.request

    /** The song the Library last scrolled to for another page, while it is marked (a few seconds, or until the next locate). */
    val locatedSongId: StateFlow<String?> = libraryLocator.located

    fun requestLibraryLocate(songId: String) = libraryLocator.request(songId)

    /** The Songs list asks where the requested song is: its index in the list as shown (the song is marked), or -1. */
    suspend fun takeLibraryLocate(request: LibraryLocator.Request): Int = libraryLocator.take(request)

    /** The Songs list has scrolled to the requested song: the request is spent (it stays published, as the effect's key, until now). */
    fun completeLibraryLocate(request: LibraryLocator.Request) = libraryLocator.complete(request)

    /** The page that asked is gone (Back): whatever was pending or marked for it goes too. */
    fun clearLibraryLocate() = libraryLocator.clear()

    fun showAndPlaySongFromLibrary(
        song: Song,
        queueName: String = context.getString(R.string.tab_library),
        updatesLastPlaylistShortcut: Boolean = true,
        source: ListenSource = LibrarySource,
    ) {
        launchLatestFullQueuePlayback(
            song = song,
            queueName = queueName,
            updatesLastPlaylistShortcut = updatesLastPlaylistShortcut,
            failureMessage = "Failed to build full library queue for songId=%s",
            source = source,
        ) {
            songIdsInLibraryOrder()
        }
    }

    /**
     * The Songs list as the Library shows it (B24-Q1): the library's current sort and storage filter, through the repository's one
     * query for that list (which also applies the allowed/blocked folders). Every button that plays "the library" asks here, so a
     * tap on a row, Play, Shuffle, the tile and the locate button all name the same list in the same order.
     */
    private suspend fun songIdsInLibraryOrder(): List<Long> = musicRepository.getSongIdsSorted(
        libraryStateHolder.currentSongSortOption.value,
        libraryStateHolder.currentStorageFilter.value,
    )

    /** The Liked list as the Library shows it: the same rule for the favourites' own sort. */
    private suspend fun songIdsInLikedOrder(): List<Long> = musicRepository.getFavoriteSongIdsSorted(
        libraryStateHolder.currentFavoriteSortOption.value,
        libraryStateHolder.currentStorageFilter.value,
    )

    /**
     * Library > Songs "Play": the whole list from its first song, in the order it is sorted —
     * the same queue a tap on a row builds, started at the top instead of at the tapped row.
     */
    fun playLibraryFromStart() = playSortedFromStart(
        queueName = context.getString(R.string.tab_library),
        failureMessage = "Failed to build full library queue for songId=%s",
        source = LibrarySource,
    ) {
        songIdsInLibraryOrder()
    }

    /** Library > Favorites "Play": the same, for liked songs in their sort order. */
    fun playFavoritesFromStart() = playSortedFromStart(
        queueName = "Liked Songs",
        failureMessage = "Failed to build favorites queue for songId=%s",
        source = LikedSource,
    ) {
        songIdsInLikedOrder()
    }

    private fun playSortedFromStart(
        queueName: String,
        failureMessage: String,
        source: ListenSource,
        sortedIdsProvider: suspend () -> List<Long>,
    ) {
        viewModelScope.launch {
            val sortedIds = sortedIdsProvider()
            val firstId = sortedIds.firstOrNull() ?: return@launch
            val firstSong = musicRepository.getSong(firstId.toString()).first() ?: return@launch
            launchLatestFullQueuePlayback(
                song = firstSong,
                queueName = queueName,
                updatesLastPlaylistShortcut = true,
                failureMessage = failureMessage,
                source = source,
            ) { sortedIds }
        }
    }

    fun showAndPlaySongFromFavorites(
        song: Song,
        queueName: String = "Liked Songs",
        updatesLastPlaylistShortcut: Boolean = true
    ) {
        launchLatestFullQueuePlayback(
            song = song,
            queueName = queueName,
            updatesLastPlaylistShortcut = updatesLastPlaylistShortcut,
            failureMessage = "Failed to build favorites queue for songId=%s",
            source = LikedSource,
        ) {
            songIdsInLikedOrder()
        }
    }

    suspend fun getSongsForCurrentLibrarySelection(): List<Song> {
        return resolvePlaybackQueueFromSortedIds(songIdsInLibraryOrder())
    }

    private fun launchLatestFullQueuePlayback(
        song: Song,
        queueName: String,
        updatesLastPlaylistShortcut: Boolean,
        failureMessage: String,
        source: ListenSource? = null,
        sortedIdsProvider: suspend () -> List<Long>
    ) {
        cancelPendingFullQueuePlayback()
        cancelPendingDirectPlayback()
        val requestToken = fullQueuePlaybackToken

        fullQueuePlaybackJob = viewModelScope.launch {
            try {
                val sortedIds = sortedIdsProvider()
                throwIfFullQueuePlaybackRequestIsStale(requestToken)

                // The list the user tapped in may already be the queue that is playing: then the tap is
                // a seek, and resolving 4,870 songs from the database only to find that out is waste.
                if (reuseLoadedQueueIfSameList(song, sortedIds, requestToken)) {
                    resetPredictiveBackState()
                    return@launch
                }

                val fullQueue = resolvePlaybackQueueFromSortedIds(sortedIds)
                throwIfFullQueuePlaybackRequestIsStale(requestToken)

                showAndPlaySong(
                    song = song,
                    contextSongs = fullQueue.ifEmpty { listOf(song) },
                    queueName = queueName,
                    updatesLastPlaylistShortcut = updatesLastPlaylistShortcut,
                    cancelPendingQueueBuild = false,
                    source = source,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestToken != fullQueuePlaybackToken) {
                    return@launch
                }

                Timber.e(error, failureMessage, song.id)
                val fallbackQueue = libraryStateHolder.allSongs.value.takeIf { songs ->
                    songs.isNotEmpty() && songs.any { it.id == song.id }
                } ?: listOf(song)
                showAndPlaySong(
                    song = song,
                    contextSongs = fallbackQueue,
                    queueName = queueName,
                    updatesLastPlaylistShortcut = updatesLastPlaylistShortcut,
                    cancelPendingQueueBuild = false,
                    source = source,
                )
            }
        }
    }

    /**
     * A tap on [song] in a list whose songs are, in order, [sortedIds]: when that is the queue already
     * loaded and the player holds the song, seek to it and report true, building nothing.
     *
     * Decided from ids alone (the same comparison [showAndPlaySong] makes on resolved songs, without
     * resolving them). A queue the engine is holding as a window around the current song is searched
     * inside that window; a song outside it is not in the player, so the caller rebuilds as before.
     */
    private suspend fun reuseLoadedQueueIfSameList(song: Song, sortedIds: List<Long>, requestToken: Long): Boolean {
        val controller = mediaController ?: return false
        if (!controller.isConnected || dualPlayerEngine.isTransitionRunning()) return false
        val queue = _playerUiState.value.currentPlaybackQueue
        // B24-Q2: comparing 4,870 ids is work a media-next never does, and it ran on the main thread in the tap's frame. The queue is
        // immutable, so the comparison and the position of the song in it go off the main thread; what touches the controller stays.
        val indexInQueue = withContext(Dispatchers.Default) {
            if (LoadedQueueReuse.sameList(queue.map { it.id }, sortedIds)) queue.indexOfFirst { it.id == song.id } else -1
        }
        throwIfFullQueuePlaybackRequestIsStale(requestToken)
        if (indexInQueue == -1) return false
        // What was compared must still be what the app holds, and the player must still be in a state to be asked.
        if (queue !== _playerUiState.value.currentPlaybackQueue) return false
        if (!controller.isConnected || dualPlayerEngine.isTransitionRunning()) return false

        // Named in the trace and logged (`SongChange: PlayerViewModel.tapReuseSeek took N ms`) when it is slow: this is the part of the
        // tap that asks the controller, after the comparison above.
        return timedSection("PlayerViewModel.tapReuseSeek") {
            val directIndex = controller.resolveReusablePlaybackTargetIndex(
                songIndexInQueue = indexInQueue,
                songId = song.id
            )
            val windowIndex = if (directIndex == null && dualPlayerEngine.isUsingWindowedQueue()) {
                LoadedQueueReuse.indexInWindow(
                    count = controller.mediaItemCount,
                    mediaIdAt = { index -> runCatching { controller.getMediaItemAt(index).mediaId }.getOrNull() },
                    songId = song.id
                )
            } else {
                null
            }
            val targetIndex = directIndex ?: windowIndex ?: return@timedSection false

            Timber.tag("QueueLoad").i(
                "Tap on %s reuses the loaded queue (%d songs): seek to index %d, no rebuild",
                song.id, queue.size, targetIndex
            )
            cancelPendingDirectPlaybackBuild()
            playLoadedControllerItem(controller, targetIndex)
            true
        }
    }

    private fun cancelPendingFullQueuePlayback() {
        fullQueuePlaybackToken += 1L
        fullQueuePlaybackJob?.cancel()
        fullQueuePlaybackJob = null
    }

    private fun throwIfFullQueuePlaybackRequestIsStale(requestToken: Long) {
        if (requestToken != fullQueuePlaybackToken) {
            throw CancellationException("Stale full-queue playback request")
        }
    }

    private fun beginDirectPlaybackRequest(): Long {
        directPlaybackToken += 1L
        directPlaybackJob?.cancel()
        directPlaybackJob = null
        pendingQueueSegmentsJob?.cancel()
        pendingQueueSegmentsJob = null
        return directPlaybackToken
    }

    private fun cancelPendingDirectPlayback() {
        cancelPendingDirectPlaybackBuild()
        pendingQueueSegmentsJob?.cancel()
        pendingQueueSegmentsJob = null
    }

    private fun cancelPendingDirectPlaybackBuild() {
        directPlaybackToken += 1L
        directPlaybackJob?.cancel()
        directPlaybackJob = null
    }

    private fun throwIfDirectPlaybackRequestIsStale(requestToken: Long) {
        if (requestToken != directPlaybackToken) {
            throw CancellationException("Stale direct playback request")
        }
    }

    private suspend fun resolvePlaybackQueueFromSortedIds(sortedIds: List<Long>): List<Song> {
        if (sortedIds.isEmpty()) return emptyList()

        val cachedSongsById = libraryStateHolder.allSongsById.value
        val missingIds = ArrayList<String>()
        val cachedQueue = ArrayList<Song>(sortedIds.size)
        var orderedIds: List<String> = emptyList()

        withContext(Dispatchers.Default) {
            // B24-Q1: "shuffle all" names the whole library here; 4,870 `toString`s are not the main thread's to do.
            orderedIds = sortedIds.map(Long::toString)
            orderedIds.forEach { songId ->
                val cachedSong = cachedSongsById[songId]
                if (cachedSong != null) {
                    cachedQueue.add(cachedSong)
                } else {
                    missingIds.add(songId)
                }
            }
        }

        if (missingIds.isEmpty()) {
            return cachedQueue
        }

        val missingSongsById = getSongsByIdsChunked(missingIds).associateBy { it.id }
        return withContext(Dispatchers.Default) {
            val finalQueue = ArrayList<Song>(orderedIds.size)
            orderedIds.forEach { songId ->
                val resolvedSong = cachedSongsById[songId] ?: missingSongsById[songId]
                if (resolvedSong != null) {
                    finalQueue.add(resolvedSong)
                }
            }
            finalQueue
        }
    }

    private suspend fun getSongsByIdsChunked(songIds: List<String>): List<Song> {
        if (songIds.isEmpty()) return emptyList()
        if (songIds.size <= SONG_ID_QUERY_CHUNK_SIZE) {
            return musicRepository.getSongsByIds(songIds).first()
        }

        return withContext(Dispatchers.IO) {
            buildList(songIds.size) {
                songIds.chunked(SONG_ID_QUERY_CHUNK_SIZE).forEach { chunk ->
                    addAll(musicRepository.getSongsByIds(chunk).first())
                }
            }
        }
    }

    val isWifiEnabled: StateFlow<Boolean> = connectivityStateHolder.isWifiEnabled
    val isWifiRadioOn: StateFlow<Boolean> = connectivityStateHolder.isWifiRadioOn
    val wifiName: StateFlow<String?> = connectivityStateHolder.wifiName
    val isBluetoothEnabled: StateFlow<Boolean> = connectivityStateHolder.isBluetoothEnabled
    val bluetoothName: StateFlow<String?> = connectivityStateHolder.bluetoothName
    val bluetoothAudioDeviceStates: StateFlow<List<BluetoothAudioDeviceState>> = connectivityStateHolder.bluetoothAudioDeviceStates
    val bluetoothAudioDevices: StateFlow<List<String>> = connectivityStateHolder.bluetoothAudioDevices



    @Inject
    lateinit var mediaMapper: com.lostf1sh.pixelplayeross.data.media.MediaMapper

    @Inject
    lateinit var imageCacheManager: com.lostf1sh.pixelplayeross.data.media.ImageCacheManager

    init {
        listeningStatsTracker.initialize(viewModelScope)
        lyricsStateHolder.initialize(lyricsLoadCallback, playbackStateHolder.stablePlayerState)
        playbackStateHolder.initialize()

        // Lyrics resolution belongs to the track becoming known, not to the user opening the
        // Lyrics tab. Starting it here means the Lyrics surface consumes a result that is already
        // running or already finished, instead of being the thing that kicks off file, sidecar,
        // cache and network work while the user watches a spinner.
        //
        // Resolution is local-first and unchanged: this only decides *when* it starts.
        viewModelScope.launch {
            playbackStateHolder.stablePlayerState
                .map { it.currentSong?.id }
                .distinctUntilChanged()
                .collect { songId ->
                    if (songId == null) return@collect
                    loadLyricsForCurrentSong()
                }
        }
        themeStateHolder.initialize(viewModelScope)

        viewModelScope.launch {
            val snapshot = runCatching {
                userPreferencesRepository.getPlaybackQueueSnapshotOnce()
            }.getOrNull() ?: return@launch

            val currentItem = if (snapshot.currentMediaId != null) {
                snapshot.items.find { it.mediaId == snapshot.currentMediaId }
            } else {
                snapshot.items.getOrNull(snapshot.currentIndex)
            } ?: return@launch

            val artworkUri = currentItem.artworkUri?.takeIf { it.isNotBlank() } ?: return@launch

            themeStateHolder.extractAndGenerateColorScheme(
                albumArtUriAsUri = artworkUri.toUri(),
                currentSongUriString = artworkUri,
                isPreload = false
            )
        }

        stablePlayerState
            .map { it.currentSong?.albumArtUriString?.takeIf { uri -> uri.isNotBlank() } }
            .distinctUntilChanged()
            .onEach { artworkUri ->
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = artworkUri?.toUri(),
                    currentSongUriString = artworkUri,
                    isPreload = false
                )
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            lyricsStateHolder.songUpdates.collect { update: Pair<com.lostf1sh.pixelplayeross.data.model.Song, com.lostf1sh.pixelplayeross.data.model.Lyrics?> ->
                val song = update.first
                val lyrics = update.second
                if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                    val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
                    val safeSong = if (song.title.isEmpty() && currentSong != null) {
                        currentSong.copy(lyrics = "")
                    } else {
                        song
                    }
                    updateSongInStates(safeSong, lyrics)
                }
                if (_selectedSongForInfo.value?.id == song.id) {
                    val currentSelected = _selectedSongForInfo.value
                    if (song.title.isEmpty() && currentSelected != null) {
                        _selectedSongForInfo.value = currentSelected.copy(lyrics = "")
                    } else {
                        _selectedSongForInfo.value = song
                    }
                }
            }
        }

        lyricsStateHolder.messageEvents
            .onEach { msg: String -> _toastEvents.emit(msg) }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            stablePlayerState
                .map { it.currentSong?.id }
                .distinctUntilChanged()
                .flatMapLatest { songId ->
                    if (songId.isNullOrBlank()) flowOf(null)
                    else musicRepository.getSong(songId)
                }
                .collect { repositorySong ->
                    val currentState = playbackStateHolder.stablePlayerState.value
                    val currentSong = currentState.currentSong ?: return@collect
                    if (repositorySong == null || repositorySong.id != currentSong.id) {
                        return@collect
                    }

                    val hydratedSong = currentSong.withRepositoryHydration(repositorySong)
                    val persistedLyrics = parsePersistedLyrics(hydratedSong.lyrics)
                    val shouldApplyPersistedLyrics = currentState.lyrics == null && persistedLyrics != null
                    val shouldRefreshSong = hydratedSong != currentSong
                    val shouldReloadLyrics =
                        !shouldApplyPersistedLyrics &&
                            currentState.lyrics == null &&
                            hydratedSong.improvesLyricsLookupComparedTo(currentSong)

                    if (shouldApplyPersistedLyrics || shouldReloadLyrics) {
                        lyricsStateHolder.cancelLoading()
                    }

                    if (shouldRefreshSong || shouldApplyPersistedLyrics) {
                        updateSongInStates(
                            updatedSong = hydratedSong,
                            newLyrics = if (shouldApplyPersistedLyrics) persistedLyrics else null,
                            isLoadingLyrics = if (shouldApplyPersistedLyrics) false else null
                        )

                        if (_selectedSongForInfo.value?.id == hydratedSong.id) {
                            _selectedSongForInfo.value = hydratedSong
                        }
                    }

                    if (shouldReloadLyrics) {
                        lyricsStateHolder.loadLyricsForSong(hydratedSong, lyricsSourcePreference.value)
                    }
                }
        }
    }

    fun sendToast(message: String) {
        viewModelScope.launch {
            _toastEvents.emit(message)
        }
    }

    fun onSearchNavIconDoubleTapped() {
        _searchNavDoubleTapEvents.tryEmit(Unit)
    }


    val lastLibraryTabIndexFlow: StateFlow<Int> =
        userPreferencesRepository.lastLibraryTabIndexFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    /**
     * Every tab the user has, in the user's order. This is the complete record: it always contains
     * all tabs whether or not they are currently shown. The tab editor reads this.
     */
    val libraryAllTabsFlow: StateFlow<ImmutableList<String>> = userPreferencesRepository.libraryTabsOrderFlow
        .map { orderJson -> decodeTabOrder(orderJson) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            DEFAULT_LIBRARY_TABS.toImmutableList()
        )

    /** Tab ids the user has hidden. Empty means the Library shows everything it has. */
    val libraryHiddenTabsFlow: StateFlow<ImmutableList<String>> = userPreferencesRepository.libraryHiddenTabsFlow
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentListOf())

    /**
     * The tabs the Library actually shows: the order minus anything hidden.
     *
     * Hiding is a presentation filter, never a deletion. The tab stays in [libraryAllTabsFlow] and
     * remains listed in the editor, so it can be brought back at any time.
     *
     * The strip is never allowed to become empty. If a stored hidden list would hide every tab
     * (a hand-edited preference, or a build that once knew a different tab set), the first tab in
     * the order is shown instead of an empty Library.
     */
    val libraryTabsFlow: StateFlow<ImmutableList<String>> =
        combine(libraryAllTabsFlow, libraryHiddenTabsFlow) { all, hidden ->
            resolveVisibleTabs(all, hidden).toImmutableList()
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            DEFAULT_LIBRARY_TABS.toImmutableList()
        )

    private val _loadedTabs = MutableStateFlow(emptySet<String>())
    private var lastBlockedDirectories: Set<String>? = null

    private val _currentLibraryTabId = MutableStateFlow(LibraryTabId.SONGS)
    val currentLibraryTabId: StateFlow<LibraryTabId> = _currentLibraryTabId.asStateFlow()

    private val _isSortingSheetVisible = MutableStateFlow(false)
    val isSortingSheetVisible: StateFlow<Boolean> = _isSortingSheetVisible.asStateFlow()

    val availableSortOptions: StateFlow<List<SortOption>> =
        currentLibraryTabId.map { tabId ->
            Trace.beginSection("PlayerViewModel.availableSortOptionsMapping")
            try {
                when (tabId) {
                    LibraryTabId.SONGS -> SortOption.SONGS
                    LibraryTabId.ALBUMS -> SortOption.ALBUMS
                    LibraryTabId.ARTISTS -> SortOption.ARTISTS
                    LibraryTabId.PLAYLISTS -> SortOption.PLAYLISTS
                    LibraryTabId.FOLDERS -> SortOption.FOLDERS
                    LibraryTabId.LIKED -> SortOption.LIKED
                    // The genre list is read straight from the repository, which returns it in
                    // its own order and has no sort parameter. Offering a sort control here would
                    // offer one that changes nothing, so the Library draws none.
                    LibraryTabId.GENRES,
                    LibraryTabId.COLLECTIONS -> emptyList()
                }
            } finally {
                Trace.endSection()
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SortOption.SONGS
        )

    val isSyncingStateFlow: StateFlow<Boolean> = syncManager.isSyncing
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    private val _isInitialDataLoaded = MutableStateFlow(false)

    val allSongsFlow: StateFlow<ImmutableList<Song>> = libraryStateHolder.allSongs

    val genres: StateFlow<ImmutableList<Genre>> = libraryStateHolder.genres
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = persistentListOf()
        )

    val paletteRegenerationTargets: StateFlow<List<Song>> = musicRepository.getDistinctAlbumArtSongs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val homeMixPreviewSongs: StateFlow<ImmutableList<Song>> = musicRepository.getHomeMixPreviewSongs(
        limit = HOME_MIX_PREVIEW_LIMIT
    ).map { it.toImmutableList() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = persistentListOf()
        )

    val songCountFlow: StateFlow<Int> = musicRepository.getSongCountFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val albumsFlow: StateFlow<ImmutableList<Album>> = libraryStateHolder.albums
    val artistsFlow: StateFlow<ImmutableList<Artist>> = libraryStateHolder.artists

    var searchQuery by mutableStateOf("")
        private set

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    private var mediaController: MediaController? = null
    private var mediaControllerPlaybackListener: Player.Listener? = null
    private val _isMediaControllerReady = MutableStateFlow(false)
    val isMediaControllerReady: StateFlow<Boolean> = _isMediaControllerReady.asStateFlow()
    private val mediaControllerListener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (command.customAction == MusicNotificationProvider.CUSTOM_COMMAND_SET_SHUFFLE_STATE) {
                // B17-H1: shuffle is the player's own mode and the player reports it (`onShuffleModeEnabledChanged`), so
                // there is nothing left for the app to rebuild when the service says it changed. The service no longer
                // sends this; an older service that still does is acknowledged and ignored.
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }
    private val mediaControllerFuture: ListenableFuture<MediaController> =
        mediaControllerFactory.create(context, sessionToken, mediaControllerListener)
    private var pendingRepeatMode: Int? = null

    private var pendingPlaybackAction: (() -> Unit)? = null

    /**
     * FIX-1c: the load of the external VIEW that is waiting in [pendingPlaybackAction], if any. It is
     * told (`false`) when another request takes its place, so its play never waits for a load that
     * will not happen, nor plays over what replaced it.
     */
    private var pendingExternalLoad: CompletableDeferred<Boolean>? = null

    /** The one place a playback request waits for the MediaController; a request already waiting is dropped by it. */
    private fun queuePlaybackAction(action: () -> Unit) {
        pendingExternalLoad?.complete(false)
        pendingExternalLoad = null
        pendingPlaybackAction = action
    }

    /** The timeline of the external VIEW in flight (FIX-1c); null when none. */
    @Volatile
    private var externalTimeline: ExternalViewTimeline? = null
    private var metadataProbeJob: Job? = null
    private var metadataProbeMediaId: String? = null

    private val _playbackAudioMetadata = MutableStateFlow(PlaybackAudioMetadata())
    val playbackAudioMetadata: StateFlow<PlaybackAudioMetadata> = _playbackAudioMetadata.asStateFlow()

    val favoriteSongIds: StateFlow<Set<String>> = musicRepository
        .getFavoriteSongIdsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val isCurrentSongFavorite: StateFlow<Boolean> = combine(
        stablePlayerState
            .map { it.currentSong }
            .distinctUntilChanged { old, new ->
                old?.id == new?.id &&
                    old?.contentUriString == new?.contentUriString &&
                    old?.path == new?.path
            }
            .flatMapLatest { song ->
                kotlinx.coroutines.flow.flow {
                    emit(resolveFavoriteSongId(song))
                }
            },
        favoriteSongIds
    ) { favoriteSongId, ids ->
        favoriteSongId?.let { ids.contains(it) } ?: false
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    data class FullPlayerSlice(
        val currentSongArtists: ImmutableList<Artist> = persistentListOf(),
        val lyricsSyncOffset: Int = 0,
        val albumArtQuality: AlbumArtQuality = AlbumArtQuality.MEDIUM,
        val audioMetadata: PlaybackAudioMetadata = PlaybackAudioMetadata(),
        val showPlayerFileInfo: Boolean = true,
        val immersiveLyricsEnabled: Boolean = false,
        val immersiveLyricsTimeout: Long = 4000L,
        val isImmersiveTemporarilyDisabled: Boolean = false,
        val isBluetoothEnabled: Boolean = false,
        val bluetoothName: String? = null
    )

    private val fullPlayerSlicePart1 = combine(
        currentSongArtists,
        currentSongLyricsSyncOffset,
        albumArtQuality,
        playbackAudioMetadata,
        showPlayerFileInfo
    ) { artists: ImmutableList<Artist>, syncOffset: Int, artQuality: AlbumArtQuality,
        audioMeta: PlaybackAudioMetadata, showFileInfo: Boolean ->
        FullPlayerSlicePart1(artists, syncOffset, artQuality, audioMeta, showFileInfo)
    }

    private data class BluetoothSlice(val enabled: Boolean, val name: String?)

    private val bluetoothSlice = combine(isBluetoothEnabled, bluetoothName) { bt, btName ->
        BluetoothSlice(bt, btName)
    }

    private val fullPlayerSlicePart2 = combine(
        immersiveLyricsEnabled,
        immersiveLyricsTimeout,
        isImmersiveTemporarilyDisabled,
        bluetoothSlice
    ) { immersive: Boolean, immersiveTimeout: Long, immersiveDisabled: Boolean, bt: BluetoothSlice ->
        FullPlayerSlicePart2(immersive, immersiveTimeout, immersiveDisabled, bt.enabled, bt.name)
    }

    private data class FullPlayerSlicePart1(
        val currentSongArtists: ImmutableList<Artist>,
        val lyricsSyncOffset: Int,
        val albumArtQuality: AlbumArtQuality,
        val audioMetadata: PlaybackAudioMetadata,
        val showPlayerFileInfo: Boolean
    )

    private data class FullPlayerSlicePart2(
        val immersiveLyricsEnabled: Boolean,
        val immersiveLyricsTimeout: Long,
        val isImmersiveTemporarilyDisabled: Boolean,
        val isBluetoothEnabled: Boolean,
        val bluetoothName: String?
    )

    val fullPlayerSlice: StateFlow<FullPlayerSlice> = combine(
        fullPlayerSlicePart1,
        fullPlayerSlicePart2
    ) { p1, p2 ->
        FullPlayerSlice(
            currentSongArtists = p1.currentSongArtists,
            lyricsSyncOffset = p1.lyricsSyncOffset,
            albumArtQuality = p1.albumArtQuality,
            audioMetadata = p1.audioMetadata,
            showPlayerFileInfo = p1.showPlayerFileInfo,
            immersiveLyricsEnabled = p2.immersiveLyricsEnabled,
            immersiveLyricsTimeout = p2.immersiveLyricsTimeout,
            isImmersiveTemporarilyDisabled = p2.isImmersiveTemporarilyDisabled,
            isBluetoothEnabled = p2.isBluetoothEnabled,
            bluetoothName = p2.bluetoothName
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FullPlayerSlice())

    data class PlayerConfigSlice(
        val navBarCornerRadius: Int = 32,
        val navBarStyle: String = NavBarStyle.DEFAULT,
        val carouselStyle: String = CarouselStyle.NO_PEEK,
        val fullPlayerLoadingTweaks: FullPlayerLoadingTweaks = FullPlayerLoadingTweaks(),
        val tapBackgroundClosesPlayer: Boolean = false,
        val useSmoothCorners: Boolean = true,
        val playerThemePreference: String = ThemePreference.ALBUM_ART
    )

    private val playerConfigSlicePart1 = combine(
        navBarCornerRadius,
        navBarStyle,
        carouselStyle,
        fullPlayerLoadingTweaks,
        tapBackgroundClosesPlayer
    ) { radius, style, carousel, tweaks, tapClose ->
        PlayerConfigSlicePart1(radius, style, carousel, tweaks, tapClose)
    }

    private data class PlayerConfigSlicePart1(
        val navBarCornerRadius: Int,
        val navBarStyle: String,
        val carouselStyle: String,
        val fullPlayerLoadingTweaks: FullPlayerLoadingTweaks,
        val tapBackgroundClosesPlayer: Boolean
    )

    val playerConfigSlice: StateFlow<PlayerConfigSlice> = combine(
        playerConfigSlicePart1,
        useSmoothCorners,
        playerThemePreference
    ) { p1, smoothCorners, themePref ->
        PlayerConfigSlice(
            navBarCornerRadius = p1.navBarCornerRadius,
            navBarStyle = p1.navBarStyle,
            carouselStyle = p1.carouselStyle,
            fullPlayerLoadingTweaks = p1.fullPlayerLoadingTweaks,
            tapBackgroundClosesPlayer = p1.tapBackgroundClosesPlayer,
            useSmoothCorners = smoothCorners,
            playerThemePreference = themePref
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerConfigSlice())

    val favoritesPagingFlow = libraryStateHolder.favoritesPagingFlow

    val dailyMixSongs: StateFlow<ImmutableList<Song>> = dailyMixStateHolder.dailyMixSongs
    val yourMixSongs: StateFlow<ImmutableList<Song>> = dailyMixStateHolder.yourMixSongs

    fun removeFromDailyMix(songId: String) {
        dailyMixStateHolder.removeFromDailyMix(songId)
    }

    /**
     * Observes a song by ID from Room DB, combined with the latest favorite status.
     * Uses direct Room query instead of scanning the full in-memory list.
     */
    fun observeSong(songId: String?): Flow<Song?> {
        if (songId == null) return flowOf(null)
        return combine(
            musicRepository.getSong(songId),
            favoriteSongIds
        ) { song, favorites ->
            song?.copy(isFavorite = favorites.contains(songId))
        }.distinctUntilChanged()
    }



    private fun updateDailyMix() {
        dailyMixStateHolder.updateDailyMix()
    }

    /**
     * "Shuffle all": the whole Songs list, in the order the Library lists it (its sort, its storage filter, the allowed folders),
     * with random on (B24-Q1). The base order of the queue is that list, so switching random off goes on from the current song to
     * the next one of the list, and random is only Media3's shuffle order over it; the first song is drawn at random.
     * It used to load a sample of 500 songs that the database had already put in random order (`ORDER BY RANDOM() LIMIT 500`): a
     * tenth of the library, with no list order to go back to. Loading all of it costs the staged load's ~100 ms.
     */
    fun shuffleAllSongs(queueName: String = "All Songs (Shuffled)") {
        Timber.tag("ShuffleDebug").d("shuffleAllSongs called.")
        shuffleSortedList(queueName, failureMessage = "Failed to build the shuffle-all queue") { songIdsInLibraryOrder() }
    }

    /**
     * Called from Quick Settings tile. Unlike shuffleAllSongs(), this always starts
     * fresh playback regardless of current state, and correctly handles the case
     * where the MediaController isn't ready yet (cold start from tile).
     *
     * The same list as [shuffleAllSongs] (B24-Q1). On a cold start no screen has shown the Library yet, so it first waits for the
     * saved sort and filter to be read, and an empty database waits for the first sync instead of failing.
     */
    fun triggerShuffleAllFromTile() {
        Timber.d("[TileDebug] triggerShuffleAllFromTile called. mediaController=${mediaController != null}")
        val action: () -> Unit = {
            Timber.d("[TileDebug] action() invoked")
            shuffleSortedList(
                "All Songs (Shuffled)",
                failureMessage = "Failed to build the shuffle-all queue from the tile",
                onEmpty = {
                    Timber.w("[TileDebug] No songs found even after sync - library may be empty")
                    sendToast(context.getString(R.string.player_no_songs_in_library_toast))
                },
            ) {
                withTimeoutOrNull(SELECTION_LOAD_WAIT_MS) { libraryStateHolder.awaitSelectionLoaded() }
                var ids = songIdsInLibraryOrder()
                Timber.d("[TileDebug] Repository returned ${ids.size} songs in library order")

                if (ids.isEmpty()) {
                    Timber.d("[TileDebug] No songs available yet, triggering sync and retrying the list")
                    syncManager.sync()
                    ids = withTimeoutOrNull(30_000L) {
                        var refreshed = emptyList<Long>()
                        while (refreshed.isEmpty()) {
                            refreshed = songIdsInLibraryOrder()
                            if (refreshed.isEmpty()) {
                                delay(500L)
                            }
                        }
                        refreshed
                    }
                        ?: emptyList()
                    Timber.d("[TileDebug] After retry, the list has ${ids.size} songs")
                }
                ids
            }
        }

        if (mediaController == null) {
            Timber.d("[TileDebug] mediaController null, queuing as pendingPlaybackAction")
            queuePlaybackAction(action)
        } else {
            Timber.d("[TileDebug] mediaController ready, calling action immediately")
            action()
        }
    }

    /**
     * Shuffle the list [sortedIdsProvider] names, as it is listed (B24-Q1): resolve every id in that order (the library's own
     * cache, the database for the rest, as a tap does) and hand the whole list to [playSongsShuffled], which loads it as it is,
     * turns random on and plays it in a fresh draw that starts at a random song. Never a sample, never a list built for the
     * button. A newer play or shuffle request replaces this one wherever it is in the way.
     */
    private fun shuffleSortedList(
        queueName: String,
        failureMessage: String,
        source: ListenSource = LibrarySource,
        onEmpty: () -> Unit = {},
        sortedIdsProvider: suspend () -> List<Long>,
    ) {
        cancelPendingFullQueuePlayback()
        val requestToken = fullQueuePlaybackToken
        // B31 P2.2: the phases of this press, logged once when the first timeline arrives.
        val timeline = ShuffleAllTimeline(queueName) { android.os.SystemClock.elapsedRealtime() }

        fullQueuePlaybackJob = viewModelScope.launch {
            try {
                val sortedIds = sortedIdsProvider()
                timeline.mark("ids")
                throwIfFullQueuePlaybackRequestIsStale(requestToken)
                val songs = resolvePlaybackQueueFromSortedIds(sortedIds)
                timeline.mark("resolve")
                throwIfFullQueuePlaybackRequestIsStale(requestToken)
                if (songs.isEmpty()) {
                    onEmpty()
                    return@launch
                }
                Timber.tag("QueueLoad").i("Shuffle \"%s\": %d songs, the base order is the list as the Library shows it", queueName, songs.size)
                playSongsShuffled(songs, queueName, startAtZero = true, source = source, timeline = timeline)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestToken != fullQueuePlaybackToken) return@launch
                Timber.e(error, failureMessage)
                sendToast(context.getString(R.string.player_no_songs_to_shuffle))
            }
        }
    }

    /** Liked > Shuffle: the liked songs in the order the Liked list shows them (its sort and filter), random on top. */
    fun shuffleFavoriteSongs() {
        Timber.tag("ShuffleDebug").d("shuffleFavoriteSongs called.")
        shuffleSortedList("Liked Songs (Shuffled)", failureMessage = "Failed to build the shuffled favorites queue", source = LikedSource) {
            songIdsInLikedOrder()
        }
    }

    /** Albums > Shuffle picks an album and shuffles it: the album as its page lists it (disc, track), random on top. */
    fun shuffleRandomAlbum() {
        viewModelScope.launch {
            val allAlbums = libraryStateHolder.albums.value
            if (allAlbums.isNotEmpty()) {
                val randomAlbum = allAlbums.random()
                val albumSongs = SourceOrder.albumPage(musicRepository.getSongsForAlbum(randomAlbum.id).first())
                if (albumSongs.isNotEmpty()) {
                    playSongsShuffled(
                        albumSongs, randomAlbum.title, startAtZero = true,
                        source = ListenSource(ListenSourceKind.ALBUM, randomAlbum.id.toString()),
                    )
                }
            }
        }
    }

    /** Artists > Shuffle picks an artist and shuffles it: the artist as its page lists it (album by album), random on top. */
    fun shuffleRandomArtist() {
        viewModelScope.launch {
            val allArtists = libraryStateHolder.artists.value
            if (allArtists.isNotEmpty()) {
                val randomArtist = allArtists.random()
                val artistSongs = SourceOrder.artistPage(musicRepository.getSongsForArtist(randomArtist.id).first())
                if (artistSongs.isNotEmpty()) {
                    playSongsShuffled(
                        artistSongs, randomArtist.name, startAtZero = true,
                        source = ListenSource(ListenSourceKind.ARTIST, randomArtist.id.toString()),
                    )
                }
            }
        }
    }


    private fun loadPersistedDailyMix() {
        dailyMixStateHolder.loadPersistedDailyMix()
    }

    fun forceUpdateDailyMix() {
        dailyMixStateHolder.forceUpdate()
    }

    private var transitionSchedulerJob: Job? = null

    fun updatePredictiveBackCollapseFraction(fraction: Float) {
        _predictiveBackCollapseFraction.value = fraction.coerceIn(0f, 1f)
    }

    fun updatePredictiveBackSwipeEdge(edge: Int?) {
        _predictiveBackSwipeEdge.value = edge
    }

    fun resetPredictiveBackState() {
        _predictiveBackCollapseFraction.value = 0f
        _predictiveBackSwipeEdge.value = null
    }

    fun updateQueueSheetVisibility(visible: Boolean) {
        _isQueueSheetVisible.value = visible
    }

    private fun resolveSortOption(
        optionKey: String?,
        allowed: Collection<SortOption>,
        fallback: SortOption
    ): SortOption {
        return SortOption.fromStorageKey(optionKey, allowed, fallback)
    }

    private data class FolderSourceState(
        val source: FolderSource,
        val rootPath: String,
        val isSdCardAvailable: Boolean
    )

    private fun resolveFolderSourceState(preferredSource: FolderSource): FolderSourceState {
        val storages = StorageUtils.getAvailableStorages(context)
        val internalPath = storages
            .firstOrNull { it.storageType == StorageType.INTERNAL }
            ?.path
            ?.path
            ?: android.os.Environment.getExternalStorageDirectory().path
        val sdPath = StorageUtils.getSdCardStorage(context)
            ?.path
            ?.path

        val effectiveSource = if (!ENABLE_FOLDERS_SOURCE_SWITCHING) {
            FolderSource.INTERNAL
        } else if (preferredSource == FolderSource.SD_CARD && sdPath == null) {
            FolderSource.INTERNAL
        } else {
            preferredSource
        }

        val resolvedRootPath = if (effectiveSource == FolderSource.SD_CARD) sdPath!! else internalPath
        return FolderSourceState(
            source = effectiveSource,
            rootPath = resolvedRootPath,
            isSdCardAvailable = sdPath != null
        )
    }

    init {
        traceSection("PlayerViewModel.init") {
            Timber.tag("PlayerViewModel").i("init started.")


            viewModelScope.launch {
                userPreferencesRepository.migrateTabOrder()
            }

            viewModelScope.launch {
                userPreferencesRepository.ensureLibrarySortDefaults()
            }

            viewModelScope.launch {
                val legacyFavoriteIds = userPreferencesRepository.favoriteSongIdsFlow.first()
                if (legacyFavoriteIds.isNotEmpty()) {
                    val roomFavoriteIds = musicRepository.getFavoriteSongIdsOnce()
                    if (roomFavoriteIds.isEmpty()) {
                        legacyFavoriteIds.forEach { songId ->
                            musicRepository.setFavoriteStatus(songId, true)
                        }
                    }
                    userPreferencesRepository.clearFavoriteSongIds()
                }
            }

            viewModelScope.launch {
                userPreferencesRepository.isFoldersPlaylistViewFlow.collect { isPlaylistView ->
                    folderNavigationStateHolder.setFoldersPlaylistViewState(
                        isPlaylistView = isPlaylistView,
                        updateUiState = { mutation -> _playerUiState.update(mutation) }
                    )
                }
            }

            viewModelScope.launch {
                userPreferencesRepository.foldersSourceFlow.collect { preferredSource ->
                    val resolved = resolveFolderSourceState(preferredSource)
                    if (resolved.source != preferredSource) {
                        userPreferencesRepository.setFoldersSource(resolved.source)
                    }

                    _playerUiState.update { currentState ->
                        val sourceChanged = currentState.folderSource != resolved.source ||
                                currentState.folderSourceRootPath != resolved.rootPath
                        currentState.copy(
                            folderSource = resolved.source,
                            folderSourceRootPath = resolved.rootPath,
                            isSdCardAvailable = resolved.isSdCardAvailable,
                            currentFolderPath = if (sourceChanged) null else currentState.currentFolderPath,
                            currentFolder = if (sourceChanged) null else currentState.currentFolder
                        )
                    }
                }
            }

            viewModelScope.launch {
                combine(
                    userPreferencesRepository.folderBackGestureNavigationFlow,
                    userPreferencesRepository.isAlbumsListViewFlow,
                ) { gestureNav, albumsList ->
                    Pair(gestureNav, albumsList)
                }.collect { (gestureNav, albumsList) ->
                    _playerUiState.update {
                        it.copy(
                            folderBackGestureNavigationEnabled = gestureNav,
                            isAlbumsListView = albumsList,
                        )
                    }
                }
            }

            viewModelScope.launch {
                userPreferencesRepository.blockedDirectoriesFlow
                    .distinctUntilChanged()
                    .collect { blocked ->
                        if (lastBlockedDirectories == null) {
                            lastBlockedDirectories = blocked
                            return@collect
                        }

                        if (blocked != lastBlockedDirectories) {
                            lastBlockedDirectories = blocked
                            onBlockedDirectoriesChanged()
                        }
                    }
            }

            viewModelScope.launch {
                combine(libraryTabsFlow, lastLibraryTabIndexFlow) { tabs, index ->
                    tabs.getOrNull(index)?.toLibraryTabIdOrNull() ?: LibraryTabId.SONGS
                }.collect { tabId ->
                    _currentLibraryTabId.value = tabId
                }
            }

            viewModelScope.launch {
                val initialSongSort = resolveSortOption(
                    userPreferencesRepository.songsSortOptionFlow.first(),
                    SortOption.SONGS,
                    SortOption.SongTitleAZ
                )
                val initialAlbumSort = resolveSortOption(
                    userPreferencesRepository.albumsSortOptionFlow.first(),
                    SortOption.ALBUMS,
                    SortOption.AlbumTitleAZ
                )
                val initialArtistSort = resolveSortOption(
                    userPreferencesRepository.artistsSortOptionFlow.first(),
                    SortOption.ARTISTS,
                    SortOption.ArtistNameAZ
                )
                val initialFolderSort = resolveSortOption(
                    userPreferencesRepository.foldersSortOptionFlow.first(),
                    SortOption.FOLDERS,
                    SortOption.FolderNameAZ
                )
                val initialLikedSort = resolveSortOption(
                    userPreferencesRepository.likedSongsSortOptionFlow.first(),
                    SortOption.LIKED,
                    SortOption.LikedSongDateLiked
                )

                _playerUiState.update {
                    it.copy(
                        currentSongSortOption = initialSongSort,
                        currentAlbumSortOption = initialAlbumSort,
                        currentArtistSortOption = initialArtistSort,
                        currentFolderSortOption = initialFolderSort,
                        currentFavoriteSortOption = initialLikedSort
                    )
                }

                sortSongs(initialSongSort, persist = false)
                sortAlbums(initialAlbumSort, persist = false)
                sortArtists(initialArtistSort, persist = false)
                sortFolders(initialFolderSort, persist = false)
                sortFavoriteSongs(initialLikedSort, persist = false)
            }

            viewModelScope.launch {
                val isPersistent = userPreferencesRepository.persistentShuffleEnabledFlow.first()
                if (isPersistent) {
                    val savedShuffle = userPreferencesRepository.isShuffleOnFlow.first()
                    playbackStateHolder.updateStablePlayerState { it.copy(isShuffleEnabled = savedShuffle) }
                }
            }

            loadPersistedDailyMix()
            loadSearchHistory()

            viewModelScope.launch {
                isSyncingStateFlow.collect { isSyncing ->
                    val oldSyncingLibraryState = _playerUiState.value.isSyncingLibrary
                    _playerUiState.update { it.copy(isSyncingLibrary = isSyncing) }

                    if (oldSyncingLibraryState && !isSyncing) {
                        Timber.tag("PlayerViewModel").i("Sync completed. Calling resetAndLoadInitialData from isSyncingStateFlow observer.")
                        resetAndLoadInitialData("isSyncingStateFlow observer")
                    }
                }
            }

            viewModelScope.launch {
                if (!isSyncingStateFlow.value && !_isInitialDataLoaded.value && libraryStateHolder.allSongs.value.isEmpty()) {
                    Timber.tag("PlayerViewModel").i("Initial check: Sync not active and initial data not loaded. Calling resetAndLoadInitialData.")
                    resetAndLoadInitialData("Initial Check")
                }
            }

            mediaControllerFuture.addListener({
                try {
                    mediaController = mediaControllerFuture.get()
                    playbackStateHolder.setMediaController(mediaController)
                    externalTimeline?.mark(ExternalViewTimeline.CONTROLLER, android.os.SystemClock.elapsedRealtime())
                    _isMediaControllerReady.value = true


                    setupMediaControllerListeners()
                    flushPendingRepeatMode()
                    // B17-H1: the player is the truth about shuffle (the service restored it with the queue); the app
                    // used to push its own idea of it into the session here and undo whatever had been restored.
                    mediaController?.let { controller ->
                        playbackStateHolder.updateStablePlayerState("controller.connect") {
                            it.copy(isShuffleEnabled = controller.shuffleModeEnabled)
                        }
                    }
                    pendingPlaybackAction?.invoke()
                    pendingPlaybackAction = null
                } catch (e: Exception) {
                    // The waiting load will never run: its play must not wait for it.
                    pendingExternalLoad?.complete(false)
                    _playerUiState.update { it.copy(isLoadingInitialSongs = false, isLoadingLibraryCategories = false) }
                    Timber.tag("PlayerViewModel").e(e, "Error setting up MediaController")
                }
            }, ContextCompat.getMainExecutor(context))


            connectivityStateHolder.initialize()

            val currentSongIdFlow = stablePlayerState.map { it.currentSong?.id }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)
            sleepTimerStateHolder.initialize(
                scope = viewModelScope,
                toastEmitter = { msg -> _toastEvents.emit(msg) },
                mediaControllerProvider = { mediaController },
                currentSongIdProvider = { currentSongIdFlow },
                songTitleResolver = { songId -> libraryStateHolder.allSongsById.value[songId]?.title ?: "Unknown" }
            )

            searchStateHolder.initialize()

            viewModelScope.launch {
                combine(
                    searchStateHolder.searchResults,
                    searchStateHolder.selectedSearchFilter,
                    searchStateHolder.searchHistory,
                ) { results, filter, history ->
                    Triple(results, filter, history)
                }.collect { (results, filter, history) ->
                    _playerUiState.update {
                        it.copy(
                            searchResults = results,
                            selectedSearchFilter = filter,
                            searchHistory = history,
                        )
                    }
                }
            }

            libraryStateHolder.initialize(viewModelScope)

            viewModelScope.launch {
                combine(
                    libraryStateHolder.musicFolders,
                    libraryStateHolder.isLoadingLibrary,
                    libraryStateHolder.isLoadingCategories,
                ) { folders, loadingLibrary, loadingCategories ->
                    Triple(folders, loadingLibrary, loadingCategories)
                }.collect { (folders, loadingLibrary, loadingCategories) ->
                    _playerUiState.update {
                        it.copy(
                            musicFolders = folders,
                            isLoadingInitialSongs = loadingLibrary,
                            isLoadingLibraryCategories = loadingCategories,
                        )
                    }
                }
            }

            viewModelScope.launch {
                combine(
                    libraryStateHolder.currentSongSortOption,
                    libraryStateHolder.currentAlbumSortOption,
                    libraryStateHolder.currentArtistSortOption,
                    libraryStateHolder.currentFolderSortOption,
                    libraryStateHolder.currentFavoriteSortOption,
                ) { songSort, albumSort, artistSort, folderSort, favoriteSort ->
                    SortOptionsSnapshot(songSort, albumSort, artistSort, folderSort, favoriteSort)
                }.collect { snapshot ->
                    _playerUiState.update {
                        it.copy(
                            currentSongSortOption = snapshot.songSort,
                            currentAlbumSortOption = snapshot.albumSort,
                            currentArtistSortOption = snapshot.artistSort,
                            currentFolderSortOption = snapshot.folderSort,
                            currentFavoriteSortOption = snapshot.favoriteSort,
                        )
                    }
                }
            }
            viewModelScope.launch {
                libraryStateHolder.currentStorageFilter.collect { filter ->
                    _playerUiState.update { it.copy(currentStorageFilter = filter) }
                }
            }

            viewModelScope.launch {
                val savedRepeatMode = userPreferencesRepository.repeatModeFlow.first()
                applyPreferredRepeatMode(savedRepeatMode)
            }
        }
    }

    fun onMainActivityStart() {
        Trace.beginSection("PlayerViewModel.onMainActivityStart")
        try {
            preloadThemesAndInitialData()
            activityDayCheck.onActivityStart()
            // Idempotent; here rather than in init, which runs before favoriteSongIds exists.
            dailyMixStateHolder.startDayBoundaryWatch()
        } finally {
            Trace.endSection()
        }
    }


    /** First use of the day (AUD-132): a HOME app under Lock Task resumes far more than it starts. */
    fun onMainActivityResume() {
        activityDayCheck.onActivityResume()
        // B29-L2: what the screen comes back to is the song the engine is on, whatever the events said while it was away.
        reconcileDisplayedItem("resume")
    }

    /** B23-P1: the day check of a wake waits out the burst of the wake; the first start of the process does not. */
    private val activityDayCheck = ActivityDayCheck(viewModelScope) { checkAndUpdateDailyMixIfNeeded() }

    private fun checkAndUpdateDailyMixIfNeeded() {
        dailyMixStateHolder.checkAndUpdateIfNeeded()
    }

    private fun preloadThemesAndInitialData() {
        Trace.beginSection("PlayerViewModel.preloadThemesAndInitialData")
        try {
            viewModelScope.launch {
                _isInitialThemePreloadComplete.value = false
                if (isSyncingStateFlow.value && !_isInitialDataLoaded.value) {
                } else if (!_isInitialDataLoaded.value && libraryStateHolder.allSongs.value.isEmpty()) {
                    resetAndLoadInitialData("preloadThemesAndInitialData")
                }
                _isInitialThemePreloadComplete.value = true
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun loadInitialLibraryDataParallel() {
        libraryStateHolder.loadSongsFromRepository()
        libraryStateHolder.loadAlbumsFromRepository()
        libraryStateHolder.loadArtistsFromRepository()
        libraryStateHolder.loadFoldersFromRepository()
    }

    private fun resetAndLoadInitialData(caller: String = "Unknown") {
        Trace.beginSection("PlayerViewModel.resetAndLoadInitialData")
        try {
            Timber.tag("PlayerViewModel").d("resetAndLoadInitialData called by $caller")
            loadInitialLibraryDataParallel()
            updateDailyMix()
        } finally {
            Trace.endSection()
        }
    }

    fun loadSongsIfNeeded() = libraryStateHolder.loadSongsIfNeeded()
    fun loadAlbumsIfNeeded() = libraryStateHolder.loadAlbumsIfNeeded()
    fun loadArtistsIfNeeded() = libraryStateHolder.loadArtistsIfNeeded()
    fun loadFoldersFromRepository() = libraryStateHolder.loadFoldersFromRepository()

    fun setStorageFilter(filter: com.lostf1sh.pixelplayeross.data.model.StorageFilter) {
        libraryStateHolder.setStorageFilter(filter)
    }

    fun toggleStorageFilter() {
        val current = _playerUiState.value.currentStorageFilter
        val next = when (current) {
            com.lostf1sh.pixelplayeross.data.model.StorageFilter.ALL -> com.lostf1sh.pixelplayeross.data.model.StorageFilter.OFFLINE
            else -> com.lostf1sh.pixelplayeross.data.model.StorageFilter.ALL
        }
        setStorageFilter(next)
    }

    /**
     * Starts playback of [song] inside [contextSongs].
     *
     * [updatesLastPlaylistShortcut] is the whole of what used to be called `isVoluntaryPlay`. That
     * flag also fed an `isVoluntary` field on the listening session, which **nothing ever read**: it
     * was written from a pending-selection id, carried through the session, and discarded at
     * finalization without reaching the outcome classifier or any counter. The playback outcome is
     * decided by `TrackEndReason` and measured listening time, and a second, silently unpopulated
     * notion of "voluntary" alongside it was a trap for anyone who later trusted it.
     *
     * So the dead half is gone. The half that did something — a play started from a library surface
     * is a library-context play and may update the "last playlist" shortcut, while one started from
     * a generated mix may not — is kept, under a name that says what it does.
     */
    fun showAndPlaySong(
        song: Song,
        contextSongs: List<Song>,
        queueName: String = "Current Context",
        updatesLastPlaylistShortcut: Boolean = true,
        cancelPendingQueueBuild: Boolean = true,
        playlistId: String? = null,
        indexInQueue: Int? = null,
        /** Where the list comes from, for the listening history; null records none. A tap that only seeks inside the queue that is playing keeps the queue's. */
        source: ListenSource? = null,
    ) {
        if (cancelPendingQueueBuild) {
            cancelPendingFullQueuePlayback()
        }
        val playbackContext =
            if (contextSongs.any { it.id == song.id }) contextSongs else listOf(song)
        val controller = mediaController
        val currentQueue = _playerUiState.value.currentPlaybackQueue
        val songIndexInQueue = indexInQueue ?: currentQueue.indexOfFirst { it.id == song.id }
        val queueMatchesContext = currentQueue.matchesSongOrder(playbackContext)
        val reusableTargetIndex = if (
            controller != null &&
            controller.isConnected &&
            !dualPlayerEngine.isTransitionRunning() &&
            songIndexInQueue != -1 &&
            queueMatchesContext
        ) {
            controller.resolveReusablePlaybackTargetIndex(
                songIndexInQueue = songIndexInQueue,
                songId = song.id,
                isExplicitQueueTarget = indexInQueue != null
            )
        } else {
            null
        }

        if (controller != null && reusableTargetIndex != null) {
            cancelPendingDirectPlaybackBuild()
            playLoadedControllerItem(controller, reusableTargetIndex)
            if (updatesLastPlaylistShortcut && playlistId != null && queueName != "None") {
                appShortcutManager.updateLastPlaylistShortcut(playlistId, queueName)
            }
        } else {
            playSongs(playbackContext, song, queueName, playlistId, source)
        }
        resetPredictiveBackState()
    }

    fun showAndPlaySong(song: Song) {
        Timber.tag("ShuffleDebug").d("showAndPlaySong (single song overload) called for '${song.title}'")
        showAndPlaySong(song, listOf(song), context.getString(R.string.tab_library))
    }

    fun playSongAtPosition(song: Song, startPositionMs: Long) {
        viewModelScope.launch {
            val controller = mediaController ?: return@launch
            val bookmarkStartPositionMs = startPositionMs.coerceAtLeast(0L)
            if (controller.currentMediaItem?.mediaId == song.id) {
                controller.seekTo(bookmarkStartPositionMs)
                controller.play()
            } else {
                val mediaItem = buildResolvedPlaybackMediaItem(song)
                controller.setMediaItem(mediaItem, bookmarkStartPositionMs)
                controller.prepare()
                controller.play()
            }
            _isSheetVisible.value = true
            _sheetState.value = PlayerSheetState.EXPANDED
        }
    }

    private fun List<Song>.matchesSongOrder(contextSongs: List<Song>): Boolean {
        if (size != contextSongs.size) return false
        return indices.all { this[it].id == contextSongs[it].id }
    }

    private fun MediaController.resolveReusablePlaybackTargetIndex(
        songIndexInQueue: Int,
        songId: String,
        isExplicitQueueTarget: Boolean = false
    ): Int? {
        if (!isExplicitQueueTarget) {
            currentMediaItem?.takeIf { it.mediaId == songId }?.let {
                return currentMediaItemIndex.takeIf { index -> index != C.INDEX_UNSET } ?: 0
            }
        }

        if (songIndexInQueue !in 0 until mediaItemCount) return null

        // B17-H1: the queue the app lists is in play order, the player's indices are in the order it was loaded in.
        val playerIndex = if (shuffleModeEnabled && !dualPlayerEngine.isUsingWindowedQueue()) {
            playbackStateHolder.playOrderOf(this).getOrNull(songIndexInQueue) ?: return null
        } else {
            songIndexInQueue
        }
        val mediaIdAtTarget = runCatching { getMediaItemAt(playerIndex).mediaId }.getOrNull()
        return playerIndex.takeIf { mediaIdAtTarget == songId }
    }

    private fun playLoadedControllerItem(controller: MediaController, targetIndex: Int) {
        val shouldSeekToStart =
            controller.currentMediaItemIndex != targetIndex ||
                controller.playbackState == Player.STATE_ENDED

        if (shouldSeekToStart) {
            // An explicit track selection is START-FRESH, and the Direct path is told so explicitly
            // rather than by omission. A declaration left over from a previous resume would
            // otherwise still be standing, and the native engine would start this item at the
            // restored position while Media3 started it at 0 — the same policy disagreement, in the
            // other direction.
            playbackStateHolder.declareItemStartTarget(0L)
            controller.seekTo(targetIndex, 0L)
        }
        if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
            controller.prepare()
        }
        controller.play()
    }

    private fun Song.requiresHydration(): Boolean {
        return contentUriString.isBlank()
    }

    private suspend fun hydrateSongsIfNeeded(songs: List<Song>): List<Song> {
        if (songs.isEmpty() || songs.none { it.requiresHydration() }) return songs
        val hydratedSongs = getSongsByIdsChunked(songs.map { it.id })
        if (hydratedSongs.isEmpty()) return songs
        val hydratedById = hydratedSongs.associateBy { it.id }
        return songs.mapNotNull { original ->
            hydratedById[original.id] ?: original.takeIf { !original.requiresHydration() }
        }
    }

    fun playAlbum(album: Album) {
        Timber.tag("ShuffleDebug").d("playAlbum called for album: ${album.title}")
        viewModelScope.launch {
            try {
                val songsList: List<Song> = withContext(Dispatchers.IO) {
                    musicRepository.getSongsForAlbum(album.id).first()
                }

                if (songsList.isNotEmpty()) {
                    val sortedSongs = SourceOrder.albumPage(songsList)

                    playSongs(sortedSongs, sortedSongs.first(), album.title, null, ListenSource(ListenSourceKind.ALBUM, album.id.toString()))
                    _isSheetVisible.value = true
                } else {
                    Timber.tag("PlayerViewModel").w("Album '${album.title}' has no playable songs.")
                }
            } catch (e: Exception) {
                Timber.tag("PlayerViewModel").e(e, "Error playing album ${album.title}")
            }
        }
    }

    fun playArtist(artist: Artist) {
        Timber.tag("ShuffleDebug").d("playArtist called for artist: ${artist.name}")
        viewModelScope.launch {
            try {
                val songsList: List<Song> = withContext(Dispatchers.IO) {
                    musicRepository.getSongsForArtist(artist.id).first()
                }

                if (songsList.isNotEmpty()) {
                    // B24-Q1: the artist as the artist page lists it (album by album), not the query's title order.
                    val inPageOrder = SourceOrder.artistPage(songsList)
                    playSongs(inPageOrder, inPageOrder.first(), artist.name, null, ListenSource(ListenSourceKind.ARTIST, artist.id.toString()))
                    _isSheetVisible.value = true
                } else {
                    Timber.tag("PlayerViewModel").w("Artist '${artist.name}' has no playable songs.")
                }
            } catch (e: Exception) {
                Timber.tag("PlayerViewModel").e(e, "Error playing artist ${artist.name}")
            }
        }
    }

    /**
     * The queue row the song-actions menu was opened from (B18-R5): the song and the row of this occurrence. A row only means
     * something for the queue it was read from, so it is dropped when the queue is listed again and when the menu closes.
     */
    private var queueRowForSongActions: Pair<String, Int>? = null

    fun noteQueueRowForSongActions(songId: String, row: Int) {
        queueRowForSongActions = songId to row
    }

    fun clearQueueRowForSongActions() {
        queueRowForSongActions = null
    }

    /**
     * Removes one occurrence of a song from the queue. [queueRow] is the row the listener acted on (a swipe, the remove button);
     * without it the row the song-actions menu was opened from, else the song that is playing, else the first occurrence in
     * play order (B18-R5). The queue can hold a song more than once, so the song id alone does not say which.
     */
    fun removeSongFromQueue(songId: String, queueRow: Int? = null) {
        val controller = mediaController
        val row = queueRow ?: queueRowForSongActions?.takeIf { it.first == songId }?.second
        queueRowForSongActions = null
        // The rows are in play order; the player's indices are in the order the queue was loaded in.
        val order = if (controller != null && controller.shuffleModeEnabled && !dualPlayerEngine.isUsingWindowedQueue()) {
            playbackStateHolder.playOrderOf(controller)
        } else {
            null
        }
        queueUndoStateHolder.removeSongFromQueue(
            scope = viewModelScope,
            mediaController = controller,
            songId = songId,
            position = row,
            playOrder = order,
            playingRow = playbackStateHolder.stablePlayerState.value.currentMediaItemIndex,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) }
        )
    }

    fun undoRemoveSongFromQueue() {
        queueUndoStateHolder.undoRemoveSongFromQueue(
            mediaController = mediaController,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) },
            reinsertIntoShuffleOrder = ::reinsertIntoShuffleOrder,
        )
    }

    /** An undone removal with shuffle on: the song is back in the timeline and in its old row of the play order. */
    private fun reinsertIntoShuffleOrder(insertAt: Int, mediaItem: MediaItem, position: Int): Boolean {
        if (!editsShuffleOrder() || position < 0) return false
        val master = dualPlayerEngine.masterPlayer
        val at = insertAt.coerceIn(0, master.mediaItemCount)
        master.addMediaItem(at, mediaItem)
        val order = dualPlayerEngine.currentPlayOrder() ?: return true
        dualPlayerEngine.setPlayOrder(PlayOrder.placeAt(order, timelineIndex = at, position = position))
        return true
    }

    fun hideQueueItemUndoBar() {
        queueUndoStateHolder.hideQueueItemUndoBar { mutation ->
            _playerUiState.update(mutation)
        }
    }

    fun reorderQueueItem(fromIndex: Int, toIndex: Int) {
        mediaController?.let { controller ->
            if (fromIndex >= 0 && fromIndex < controller.mediaItemCount &&
                toIndex >= 0 && toIndex < controller.mediaItemCount) {
                val shuffled = controller.shuffleModeEnabled && !dualPlayerEngine.isUsingWindowedQueue()
                val currentIndexBeforeMove = if (shuffled) {
                    // The rows are in play order, so the song that is playing is the row it sits in, not its player index.
                    playbackStateHolder.queuePositionOf(controller)
                } else {
                    controller.currentMediaItemIndex
                        .takeIf { it != C.INDEX_UNSET }
                        ?: playbackStateHolder.stablePlayerState.value.currentMediaItemIndex
                }
                val updatedCurrentIndex = moveQueueIndex(currentIndexBeforeMove, fromIndex, toIndex)

                if (shuffled) {
                    // Moving a row changes the play order, not the queue: nothing in the timeline moves.
                    val reordered = PlayOrder.move(playbackStateHolder.playOrderOf(controller), fromIndex, toIndex)
                    if (!dualPlayerEngine.setPlayOrder(reordered)) return@let
                } else {
                    controller.moveMediaItem(fromIndex, toIndex)
                }

                _playerUiState.update { state ->
                    val updatedQueue = state.currentPlaybackQueue.moveSong(fromIndex, toIndex)
                    if (updatedQueue === state.currentPlaybackQueue) {
                        state
                    } else {
                        state.copy(currentPlaybackQueue = updatedQueue)
                    }
                }

                playbackStateHolder.updateStablePlayerState { state ->
                    if (updatedCurrentIndex == C.INDEX_UNSET ||
                        state.currentMediaItemIndex == updatedCurrentIndex
                    ) {
                        state
                    } else {
                        state.copy(currentMediaItemIndex = updatedCurrentIndex)
                    }
                }
            }
        }
    }

    fun togglePlayerSheetState(resetPredictiveState: Boolean = true) {
        _sheetState.value = if (_sheetState.value == PlayerSheetState.COLLAPSED) {
            PlayerSheetState.EXPANDED
        } else {
            PlayerSheetState.COLLAPSED
        }
        if (resetPredictiveState) {
            resetPredictiveBackState()
        }
    }

    fun expandPlayerSheet(resetPredictiveState: Boolean = true) {
        _sheetState.value = PlayerSheetState.EXPANDED
        if (resetPredictiveState) {
            resetPredictiveBackState()
        }
    }

    fun collapsePlayerSheet(resetPredictiveState: Boolean = true) {
        _sheetState.value = PlayerSheetState.COLLAPSED
        if (resetPredictiveState) {
            resetPredictiveBackState()
        }
    }

    fun triggerAlbumNavigationFromPlayer(albumId: Long) {
        if (albumId == -1L) {
            Timber.tag("AlbumDebug").d("triggerAlbumNavigationFromPlayer ignored invalid albumId=$albumId")
            return
        }

        val existingJob = albumNavigationJob
        if (existingJob != null && existingJob.isActive) {
            Timber.tag("AlbumDebug").d("triggerAlbumNavigationFromPlayer ignored; navigation already in progress for albumId=$albumId")
            return
        }

        albumNavigationJob?.cancel()
        albumNavigationJob = viewModelScope.launch {
            val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
            Timber.tag("AlbumDebug").d(
                "triggerAlbumNavigationFromPlayer: albumId=$albumId, songId=${currentSong?.id}, title=${currentSong?.title}"
            )
            collapsePlayerSheet()

            withTimeoutOrNull(900) {
                awaitSheetState(PlayerSheetState.COLLAPSED)
                awaitPlayerCollapse()
            }

            _albumNavigationRequests.emit(albumId)
        }
    }

    fun triggerArtistNavigationFromPlayer(artistId: Long) {
        if (artistId == 0L) {
            Timber.tag("ArtistDebug").d("triggerArtistNavigationFromPlayer ignored invalid artistId=$artistId")
            return
        }

        val existingJob = artistNavigationJob
        if (existingJob != null && existingJob.isActive) {
            Timber.tag("ArtistDebug").d("triggerArtistNavigationFromPlayer ignored; navigation already in progress for artistId=$artistId")
            return
        }

        artistNavigationJob?.cancel()
        artistNavigationJob = viewModelScope.launch {
            var resolvedId = artistId
            val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
            
            if (resolvedId == -1L && currentSong != null) {
                val idFromName = musicRepository.getArtistIdByName(currentSong.artist)
                if (idFromName != null) {
                    resolvedId = idFromName
                }
            }

            if (resolvedId == 0L || resolvedId == -1L) {
                Timber.tag("ArtistDebug").d("triggerArtistNavigationFromPlayer: could not resolve artistId for name=${currentSong?.artist}")
                return@launch
            }

            Timber.tag("ArtistDebug").d(
                "triggerArtistNavigationFromPlayer: artistId=$resolvedId, songId=${currentSong?.id}, title=${currentSong?.title}"
            )
            collapsePlayerSheet()

            withTimeoutOrNull(900) {
                awaitSheetState(PlayerSheetState.COLLAPSED)
                awaitPlayerCollapse()
            }

            _artistNavigationRequests.emit(resolvedId)
        }
    }

    suspend fun awaitSheetState(target: PlayerSheetState) {
        sheetState.first { it == target }
    }

    suspend fun awaitPlayerCollapse(threshold: Float = 0.1f, timeoutMillis: Long = 800L) {
        withTimeoutOrNull(timeoutMillis) {
            snapshotFlow { playerContentExpansionFraction.value }
                .first { it <= threshold }
        }
    }

    private fun resolveSongFromMediaItem(
        mediaItem: MediaItem,
        allSongsById: Map<String, Song>? = null
    ): Song? {
        val resolvedSong =
            allSongsById?.get(mediaItem.mediaId)
                ?: libraryStateHolder.allSongsById.value[mediaItem.mediaId]
                ?: _playerUiState.value.currentPlaybackQueue.find { it.id == mediaItem.mediaId }
                ?: mediaMapper.resolveSongFromMediaItem(mediaItem)

        return resolvedSong?.let { normalizeArtworkForResolvedSong(it, mediaItem) }
    }

    private fun normalizeArtworkForResolvedSong(song: Song, mediaItem: MediaItem): Song {
        val metadataArtwork =
            mediaItem.mediaMetadata.artworkUri?.toString()?.takeIf { it.isNotBlank() }
                ?: mediaItem.mediaMetadata.extras
                    ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART)
                    ?.takeIf { it.isNotBlank() }

        return when {
            metadataArtwork == null && song.albumArtUriString != null -> song.copy(albumArtUriString = null)
            metadataArtwork != null && song.albumArtUriString != metadataArtwork ->
                song.copy(albumArtUriString = metadataArtwork)
            else -> song
        }
    }

    private var lastQueueUpdateRequestId = 0L
    private var lastQueueSignature: QueueTimelineSignature? = null
    private var lastQueueUpdateJob: Job? = null

    private fun updateCurrentPlaybackQueueFromPlayer(playerCtrl: MediaController?) {
        val currentMediaController = playerCtrl ?: mediaController ?: return
        val requestId = ++lastQueueUpdateRequestId
        lastQueueUpdateJob?.cancel()
        lastQueueUpdateJob = viewModelScope.launch {
            delay(100)

            val isWindowed = dualPlayerEngine.isUsingWindowedQueue()
            val mediaItems = if (isWindowed) {
                // Whole, and in play order when shuffle is on (the engine cut its window from the play order).
                dualPlayerEngine.getFullQueue()
            } else {
                val timeline = currentMediaController.currentTimeline
                val windowCount = timeline.windowCount
                // B17-H1: the timeline is in the order the queue was loaded in; the queue screen lists the songs in the
                // order they will play. The order is read off the timeline the session published, so this costs the session
                // nothing and runs here, off the toggle.
                val playOrder = if (currentMediaController.shuffleModeEnabled) PlayOrder.of(timeline, true) else null
                val list = ArrayList<MediaItem>(windowCount)
                val window = Timeline.Window()
                for (position in 0 until windowCount) {
                    val index = playOrder?.get(position) ?: position
                    list.add(timeline.getWindow(index, window).mediaItem)
                    if (position % 500 == 0) yield()
                }
                list
            }

            val count = mediaItems.size
            if (count == 0) {
                if (requestId != lastQueueUpdateRequestId) return@launch
                val emptySignature = QueueTimelineSignature(
                    count = 0,
                    orderHash = 0L,
                    firstMediaId = null,
                    lastMediaId = null
                )
                if (lastQueueSignature != emptySignature) {
                    lastQueueSignature = emptySignature
                    _playerUiState.update { it.copy(currentPlaybackQueue = persistentListOf()) }
                }
                return@launch
            }

            var orderHash = 1125899906842597L
            var firstMediaId: String? = null
            var lastMediaId: String? = null

            for (i in 0 until count) {
                val mediaItem = mediaItems[i]
                val mediaId = mediaItem.mediaId
                if (i == 0) firstMediaId = mediaId
                if (i == count - 1) lastMediaId = mediaId
                orderHash = (orderHash * 31) + mediaId.hashCode()
                if (i % 500 == 0) yield()
            }

            val signature = QueueTimelineSignature(
                count = count,
                orderHash = orderHash,
                firstMediaId = firstMediaId,
                lastMediaId = lastMediaId
            )
            if (requestId != lastQueueUpdateRequestId) return@launch
            if (signature == lastQueueSignature) return@launch

            val allSongsById = libraryStateHolder.allSongsById.value
            
            val queue = withContext(Dispatchers.Default) {
                mediaItems.mapNotNull { mediaItem ->
                    resolveSongFromMediaItem(mediaItem, allSongsById)
                }
            }

            if (requestId != lastQueueUpdateRequestId) return@launch

            lastQueueSignature = signature
            queueRowForSongActions = null // a row belongs to the queue it was read from
            _playerUiState.update { it.copy(currentPlaybackQueue = queue.toPlaybackQueue()) }
            if (queue.isNotEmpty()) {
                _isSheetVisible.value = true
            }
        }
    }

    private fun applyPreferredRepeatMode(@Player.RepeatMode mode: Int) {
        playbackStateHolder.updateStablePlayerState { it.copy(repeatMode = mode) }

        val controller = mediaController
        if (controller == null) {
            pendingRepeatMode = mode
            return
        }

        if (controller.repeatMode != mode) {
            controller.repeatMode = mode
        }
        pendingRepeatMode = null
    }

    private fun flushPendingRepeatMode() {
        pendingRepeatMode?.let { applyPreferredRepeatMode(it) }
    }

    private fun resetPlaybackAudioMetadata() {
        metadataProbeJob?.cancel()
        metadataProbeJob = null
        metadataProbeMediaId = null
        _playbackAudioMetadata.value = PlaybackAudioMetadata()
    }

    private fun preparePlaybackAudioMetadataForMedia(mediaId: String?) {
        metadataProbeJob?.cancel()
        metadataProbeJob = null
        metadataProbeMediaId = null
        _playbackAudioMetadata.value = PlaybackAudioMetadata(mediaId = mediaId)
    }

    private fun extractBitDepthFromPcmEncoding(pcmEncoding: Int): Int? {
        return when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
    }

    private fun refreshPlaybackAudioMetadata(player: Player, tracks: Tracks = player.currentTracks) {
        runCatching {
            val mediaId = player.currentMediaItem?.mediaId
            if (mediaId == null) {
                resetPlaybackAudioMetadata()
                return@runCatching
            }

            val selectedAudioFormat = tracks.groups
                .asSequence()
                .filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group ->
                    (0 until group.length)
                        .asSequence()
                        .filter { index -> group.isTrackSelected(index) }
                        .map { index -> group.getTrackFormat(index) }
                }
                .firstOrNull()

            val current = _playbackAudioMetadata.value.takeIf { it.mediaId == mediaId }
            val metadata = PlaybackAudioMetadata(
                mediaId = mediaId,
                mimeType = selectedAudioFormat?.sampleMimeType
                    ?: selectedAudioFormat?.containerMimeType
                    ?: current?.mimeType,
                bitrate = selectedAudioFormat?.bitrate?.takeIf { it > 0 }
                    ?: current?.bitrate,
                sampleRate = selectedAudioFormat?.sampleRate?.takeIf { it > 0 }
                    ?: current?.sampleRate,
                channelCount = selectedAudioFormat?.channelCount?.takeIf { it > 0 } ?: current?.channelCount,
                bitDepth = selectedAudioFormat?.pcmEncoding?.let(::extractBitDepthFromPcmEncoding) ?: current?.bitDepth
            )

            _playbackAudioMetadata.value = metadata
            maybeProbeMissingPlaybackAudioMetadata(player, metadata)
        }.onFailure { throwable ->
            Timber.w(throwable, "Failed to refresh playback audio metadata")
        }
    }

    private fun maybeProbeMissingPlaybackAudioMetadata(
        player: Player,
        metadata: PlaybackAudioMetadata
    ) {
        val shouldProbe = metadata.mimeType.isNullOrBlank() || metadata.bitrate == null || metadata.sampleRate == null
        if (!shouldProbe) return

        val mediaItem = player.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId
        val uri = mediaItem.localConfiguration?.uri ?: return

        if (metadataProbeMediaId == mediaId && metadataProbeJob?.isActive == true) return

        metadataProbeJob?.cancel()
        metadataProbeMediaId = mediaId
        metadataProbeJob = viewModelScope.launch(Dispatchers.IO) {
            val probedMetadata = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val mimeType = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        ?.takeIf { it.isNotBlank() }
                        ?: context.contentResolver.getType(uri)
                    val bitrate = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                    val sampleRate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                            ?.toIntOrNull()
                            ?.takeIf { it > 0 }
                    } else null
                    PlaybackAudioMetadata(
                        mediaId = mediaId,
                        mimeType = mimeType,
                        bitrate = bitrate,
                        sampleRate = sampleRate
                    )
                } finally {
                    retriever.release()
                }
            }.getOrNull() ?: return@launch

            _playbackAudioMetadata.update { current ->
                val isSameMediaItem = current.mediaId == mediaId
                if (!isSameMediaItem) return@update current
                current.copy(
                    mimeType = current.mimeType ?: probedMetadata.mimeType,
                    bitrate = current.bitrate ?: probedMetadata.bitrate,
                    sampleRate = current.sampleRate ?: probedMetadata.sampleRate
                )
            }
        }
    }

    private fun syncPlaybackPositionFromPlayer(
        mediaId: String?,
        reportedPositionMs: Long
    ): Long {
        playbackStateHolder.syncCurrentPositionFromPlayer(mediaId, reportedPositionMs)
        return playbackStateHolder.currentPosition.value
    }

    /** The song and player index the displayed state was last brought in line with (B17-H1, see [syncDisplayedMediaItemIfChanged]). */
    private var lastSeenPlayerMediaId: String? = null
    private var lastSeenPlayerIndex: Int = C.INDEX_UNSET

    /**
     * B29-L2: the song shown follows the engine's current item (see [DisplayedItemReconcile]). Run when the screen comes
     * back and when the player starts playing, not per frame; with nothing to correct it is three reads and two string
     * compares. Skipped while a crossfade runs: then the controller is deliberately ahead of the engine's master.
     */
    private fun reconcileDisplayedItem(why: String) {
        val controller = mediaController ?: return
        if (dualPlayerEngine.isTransitionRunning()) return
        val engine = runCatching { dualPlayerEngine.masterPlayer }.getOrNull() ?: return
        val displayedId = playbackStateHolder.stablePlayerState.value.currentSong?.id
        val engineId = engine.currentMediaItem?.mediaId
        val controllerId = controller.currentMediaItem?.mediaId
        when (DisplayedItemReconcile.followFrom(displayedId, controllerId, engineId)) {
            DisplayedItemReconcile.Source.NONE -> Unit
            DisplayedItemReconcile.Source.CONTROLLER -> syncDisplayedMediaItemIfChanged(controller)
            DisplayedItemReconcile.Source.ENGINE -> {
                Timber.tag("DisplayLag").w(
                    "Shown %s, controller %s, engine %s (%s): showing the engine's item",
                    displayedId, controllerId, engineId, why,
                )
                syncDisplayedMediaItemIfChanged(engine)
            }
        }
    }

    private fun syncDisplayedMediaItemIfChanged(player: Player) {

        val mediaItem = player.currentMediaItem ?: return
        val currentSongId = playbackStateHolder.stablePlayerState.value.currentSong?.id
        val currentIndex = playbackStateHolder.stablePlayerState.value.currentMediaItemIndex
        val expectedIndex = playbackStateHolder.queuePositionOf(player)
        val playerIndex = player.currentMediaItemIndex
        if (currentSongId == mediaItem.mediaId && currentIndex == expectedIndex) {
            lastSeenPlayerMediaId = mediaItem.mediaId
            lastSeenPlayerIndex = playerIndex
            return
        }
        // B17-H1: with shuffle on, the row a song is listed in changes without the song changing (the order was edited, the
        // queue was listed again): the same song at the same player index is not a new occurrence of it, so only the row is
        // updated. Without shuffle a different row for the same song is a repeat of that track, as it always was.
        if (player.shuffleModeEnabled && currentSongId == mediaItem.mediaId &&
            lastSeenPlayerMediaId == mediaItem.mediaId && lastSeenPlayerIndex == playerIndex
        ) {
            playbackStateHolder.updateStablePlayerState("controller.queuePosition") {
                it.copy(currentMediaItemIndex = expectedIndex)
            }
            return
        }
        lastSeenPlayerMediaId = mediaItem.mediaId
        lastSeenPlayerIndex = playerIndex

        playbackStateHolder.onPlaybackOccurrenceTransition(mediaItem.mediaId)
        preparePlaybackAudioMetadataForMedia(mediaItem.mediaId)
        transitionSchedulerJob?.cancel()
        lyricsStateHolder.cancelLoading()
        resetLyricsSearchState()

        val song = resolveSongFromMediaItem(mediaItem)
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        val resolvedDuration = if (song != null) {
            playbackStateHolder.resolveDurationForPlaybackState(
                reportedDurationMs = player.duration,
                songDurationHintMs = song.duration.coerceAtLeast(0L),
                currentPositionMs = currentPosition
            )
        } else {
            0L
        }

        playbackStateHolder.updateStablePlayerState("controller.mediaItemSync") {
            it.copy(
                currentSong = song,
                currentMediaItemIndex = expectedIndex,
                totalDuration = resolvedDuration,
                lyrics = null,
                lyricsResolveCompleted = false,
                isLoadingLyrics = song != null,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady
            )
        }
        syncPlaybackPositionFromPlayer(mediaItem.mediaId, currentPosition)

        song?.let { currentSongValue ->
            viewModelScope.launch {
                val uri = currentSongValue.albumArtUriString?.toUri()
                val currentUri = playbackStateHolder.stablePlayerState.value.currentSong?.albumArtUriString
                themeStateHolder.extractAndGenerateColorScheme(uri, currentUri)
            }
            loadLyricsForCurrentSong()
        }
    }

    private fun setupMediaControllerListeners(): Unit = traceSection("PlayerViewModel.setupMediaControllerListeners") {
        val playerCtrl = mediaController ?: return
        playbackStateHolder.updateStablePlayerState {
            it.copy(
                isShuffleEnabled = it.isShuffleEnabled,
                repeatMode = playerCtrl.repeatMode,
                isPlaying = playerCtrl.isPlaying,
                playWhenReady = playerCtrl.playWhenReady
            )
        }
        preparePlaybackAudioMetadataForMedia(playerCtrl.currentMediaItem?.mediaId)
        refreshPlaybackAudioMetadata(playerCtrl)

        updateCurrentPlaybackQueueFromPlayer(playerCtrl)

        playerCtrl.currentMediaItem?.let { mediaItem ->
            playbackStateHolder.ensureCurrentPlaybackOccurrence(mediaItem.mediaId)
            val song = resolveSongFromMediaItem(mediaItem)

            if (song != null) {
                val initialPosition = playerCtrl.currentPosition.coerceAtLeast(0L)
                val resolvedDuration = playbackStateHolder.resolveDurationForPlaybackState(
                    reportedDurationMs = playerCtrl.duration,
                    songDurationHintMs = song.duration.coerceAtLeast(0L),
                    currentPositionMs = initialPosition
                )
                playbackStateHolder.updateStablePlayerState {
                    it.copy(
                        currentSong = song,
                        totalDuration = resolvedDuration
                    )
                }
                syncPlaybackPositionFromPlayer(mediaItem.mediaId, initialPosition)
                viewModelScope.launch {
                    val uri = song.albumArtUriString?.toUri()
                    val currentUri = playbackStateHolder.stablePlayerState.value.currentSong?.albumArtUriString
                    themeStateHolder.extractAndGenerateColorScheme(uri, currentUri)
                }
                loadLyricsForCurrentSong()
                if (playerCtrl.isPlaying) {
                    _isSheetVisible.value = true
                    startProgressUpdates()
                }
            } else {
                playbackStateHolder.updateStablePlayerState {
                    it.copy(
                        currentSong = null,
                        isPlaying = false,
                        playWhenReady = false
                    )
                }
                playbackStateHolder.clearCurrentPositionHints()
                playbackStateHolder.setCurrentPosition(0L)
                resetPlaybackAudioMetadata()
            }
        }

        mediaControllerPlaybackListener?.let(playerCtrl::removeListener)
        mediaControllerPlaybackListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                viewModelScope.launch {
                    _toastEvents.emit(context.getString(R.string.player_error_skipping))
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playbackStateHolder.updateStablePlayerState("controller.onIsPlayingChanged") {
                    it.copy(
                        isPlaying = isPlaying,
                        playWhenReady = playerCtrl.playWhenReady
                    )
                }
                val shouldKeepSampling = playerCtrl.playWhenReady &&
                    playerCtrl.playbackState != Player.STATE_IDLE &&
                    playerCtrl.playbackState != Player.STATE_ENDED
                if (isPlaying || shouldKeepSampling) {
                    _isSheetVisible.value = true
                    if (isPlaying) {
                        clearPreparingSongIfMatching(playerCtrl.currentMediaItem?.mediaId)
                        reconcileDisplayedItem("playing")
                    }
                    startProgressUpdates()
                } else {
                    stopProgressUpdates()
                    val pausedPosition = playerCtrl.currentPosition.coerceAtLeast(0L)
                    syncPlaybackPositionFromPlayer(playerCtrl.currentMediaItem?.mediaId, pausedPosition)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playbackStateHolder.updateStablePlayerState("controller.onPlayWhenReadyChanged") { it.copy(playWhenReady = playWhenReady) }
                // B12a-8 #5: a pause the engine's focus refusal did not make (it arrives as USER_REQUEST): someone decided.
                if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                ) {
                    playbackStateHolder.noteExternalPause()
                }
                if (
                    playWhenReady &&
                    playerCtrl.playbackState != Player.STATE_IDLE &&
                    playerCtrl.playbackState != Player.STATE_ENDED
                ) {
                    startProgressUpdates()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = timedSection("PlayerViewModel.onMediaItemTransition") {
                playbackStateHolder.onPlaybackOccurrenceTransition(mediaItem?.mediaId)
                preparePlaybackAudioMetadataForMedia(mediaItem?.mediaId)
                transitionSchedulerJob?.cancel()
                lyricsStateHolder.cancelLoading()
                transitionSchedulerJob = viewModelScope.launch {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        val activeEotSongId = EotStateHolder.eotTargetSongId.value
                        val previousSongId = playerCtrl.run { if (previousMediaItemIndex != C.INDEX_UNSET) getMediaItemAt(previousMediaItemIndex).mediaId else null }

                        if (isEndOfTrackTimerActive.value && activeEotSongId != null && previousSongId != null && previousSongId == activeEotSongId) {
                            // The service's timer pauses (AUD-017, one implementation); this only reports.

                            val finishedSongTitle = libraryStateHolder.allSongsById.value[previousSongId]?.title
                                ?: context.getString(R.string.player_default_track_title)

                            viewModelScope.launch {
                                _toastEvents.emit(
                                    context.getString(R.string.player_playback_stopped_eot, finishedSongTitle),
                                )
                            }
                            cancelSleepTimer(suppressDefaultToast = true)
                        }
                    }

                    mediaItem?.let { transitionedItem ->
                        val song = resolveSongFromMediaItem(transitionedItem)
                        
                        val resolvedDuration = if (song != null) {
                            playbackStateHolder.resolveDurationForPlaybackState(
                                reportedDurationMs = playerCtrl.duration,
                                songDurationHintMs = song.duration.coerceAtLeast(0L),
                                currentPositionMs = playerCtrl.currentPosition.coerceAtLeast(0L)
                            )
                        } else {
                            0L
                        }
                        resetLyricsSearchState()
                        lastSeenPlayerMediaId = transitionedItem.mediaId
                        lastSeenPlayerIndex = playerCtrl.currentMediaItemIndex
                        playbackStateHolder.updateStablePlayerState {
                            it.copy(
                                currentSong = song,
                                currentMediaItemIndex = playbackStateHolder.queuePositionOf(playerCtrl),
                                totalDuration = resolvedDuration,
                                lyrics = null,
                                lyricsResolveCompleted = false,
                                isLoadingLyrics = song != null,
                                playWhenReady = playerCtrl.playWhenReady
                            )
                        }
                        val transitionPosition = syncPlaybackPositionFromPlayer(
                            transitionedItem.mediaId,
                            playerCtrl.currentPosition.coerceAtLeast(0L)
                        )

                        song?.let { currentSongValue ->
                            launch {
                                val uri = currentSongValue.albumArtUriString?.toUri()
                                val currentUri = playbackStateHolder.stablePlayerState.value.currentSong?.albumArtUriString
                                themeStateHolder.extractAndGenerateColorScheme(uri, currentUri)
                            }
                            loadLyricsForCurrentSong()
                        }
                    } ?: run {
                        lyricsStateHolder.cancelLoading()
                        playbackStateHolder.updateStablePlayerState {
                            it.copy(
                                currentSong = null,
                                isPlaying = false,
                                playWhenReady = false,
                                lyrics = null,
                                lyricsResolveCompleted = false,
                                isLoadingLyrics = false,
                                totalDuration = 0L
                            )
                        }
                        playbackStateHolder.clearCurrentPositionHints()
                        resetPlaybackAudioMetadata()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                refreshPlaybackAudioMetadata(playerCtrl)
                syncDisplayedMediaItemIfChanged(playerCtrl)

                bufferingDebounceJob?.cancel()
                if (playbackState == Player.STATE_BUFFERING) {
                    bufferingDebounceJob = viewModelScope.launch {
                        delay(500)
                        playbackStateHolder.updateStablePlayerState { state ->
                            state.copy(isBuffering = true)
                        }
                    }
                } else {
                    playbackStateHolder.updateStablePlayerState { state ->
                        state.copy(isBuffering = false)
                    }
                }

                if (playbackState == Player.STATE_READY) {
                    clearPreparingSongIfMatching(playerCtrl.currentMediaItem?.mediaId)
                    val readyPosition = playerCtrl.currentPosition.coerceAtLeast(0L)
                    val songDurationHint = playbackStateHolder.stablePlayerState.value.currentSong?.duration ?: 0L
                    val resolvedDuration = playbackStateHolder.resolveDurationForPlaybackState(
                        reportedDurationMs = playerCtrl.duration,
                        songDurationHintMs = songDurationHint,
                        currentPositionMs = readyPosition
                    )
                    syncPlaybackPositionFromPlayer(playerCtrl.currentMediaItem?.mediaId, readyPosition)
                    playbackStateHolder.updateStablePlayerState { it.copy(totalDuration = resolvedDuration) }
                    startProgressUpdates()
                }
                if (playbackState == Player.STATE_IDLE && playerCtrl.mediaItemCount == 0) {
                    clearPreparingSongIfMatching()
                    lyricsStateHolder.cancelLoading()
                    playbackStateHolder.updateStablePlayerState {
                        it.copy(
                            currentSong = null,
                            isPlaying = false,
                            playWhenReady = false,
                            lyrics = null,
                            lyricsResolveCompleted = false,
                            isLoadingLyrics = false,
                            totalDuration = 0L
                        )
                    }
                    playbackStateHolder.clearCurrentPositionHints()
                    playbackStateHolder.setCurrentPosition(0L)
                    resetPlaybackAudioMetadata()
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                refreshPlaybackAudioMetadata(playerCtrl, tracks)
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                syncDisplayedMediaItemIfChanged(playerCtrl)
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                // B17-H1: the player's flag is the truth; this used to force it back to false and rebuild the queue.
                // The queue screen is listed in play order, so it is listed again (off the main thread, the session is not
                // touched) and the current song's place in it follows.
                playbackStateHolder.updateStablePlayerState("controller.shuffleMode") {
                    it.copy(
                        isShuffleEnabled = shuffleModeEnabled,
                        currentMediaItemIndex = playbackStateHolder.queuePositionOf(playerCtrl),
                    )
                }
                updateCurrentPlaybackQueueFromPlayer(playerCtrl)
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                playbackStateHolder.updateStablePlayerState { it.copy(repeatMode = repeatMode) }
                viewModelScope.launch { userPreferencesRepository.setRepeatMode(repeatMode) }
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = timedSection("PlayerViewModel.onTimelineChanged") {
                timedSection("PlayerViewModel.syncDisplayedMediaItem") { syncDisplayedMediaItemIfChanged(playerCtrl) }
                if (dualPlayerEngine.isTransitionRunning()) return@timedSection

                transitionSchedulerJob?.cancel()

                // B18-R3: a source update (the duration of the song that was just prepared, one or two per song change) cannot add,
                // remove or move a song, so when the queue is still the size the screen lists there is nothing to read again; the
                // walk over ~4,870 windows and their ids that it used to start was main-thread work for nothing.
                val queueChanged = reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED ||
                    (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE && timeline.windowCount != lastQueueSignature?.count)
                if (queueChanged) {
                    updateCurrentPlaybackQueueFromPlayer(mediaController)
                }
            }
        }
        playerCtrl.addListener(checkNotNull(mediaControllerPlaybackListener))
    }


    /**
     * The product rule for the two playback verbs, stated once here because this is where the
     * state is actually changed:
     *
     *  - **Play** starts the requested context in normal ordered playback and establishes shuffle
     *    OFF. That is what "Play" means on a playlist, album or mix, and it is why every Play
     *    control in the app clears the flag rather than leaving it to chance.
     *  - **Shuffle** ([playSongsShuffled]) starts shuffled playback and establishes shuffle ON.
     *
     * The one deliberate exception is [persistentShuffleEnabled]: a user who has asked for shuffle
     * to survive across playbacks keeps it, and Play honours both the flag and the shuffled order.
     *
     * Both verbs write the same global shuffle state the rest of the UI reads, so the shuffle
     * indicators on Now Playing, the Queue and the Lyrics sheet always show the result — turning
     * shuffle off here is not silent, it is visible wherever shuffle is displayed.
     */
    fun playSongs(
        songsToPlay: List<Song>,
        startSong: Song,
        queueName: String = "None",
        playlistId: String? = null,
        source: ListenSource? = null,
    ) {
        cancelPendingFullQueuePlayback()
        val requestToken = beginDirectPlaybackRequest()
        directPlaybackJob = viewModelScope.launch {
            transitionSchedulerJob?.cancel()

            val validSongs = hydrateSongsIfNeeded(songsToPlay)
            throwIfDirectPlaybackRequestIsStale(requestToken)

            if (validSongs.isEmpty()) {
                _toastEvents.emit(context.getString(R.string.no_valid_songs))
                return@launch
            }

            val validStartSong =
                validSongs.firstOrNull { it.id == startSong.id } ?: validSongs.first()

            val isPersistent = userPreferencesRepository.persistentShuffleEnabledFlow.first()
            throwIfDirectPlaybackRequestIsStale(requestToken)
            val isShuffleOn = playbackStateHolder.stablePlayerState.value.isShuffleEnabled

            // B17-H1: the queue is loaded in the order it was asked for, whatever shuffle says; a persistent shuffle plays
            // it in the order it deals (the start song keeps its place, as before) through the player's shuffle order.
            val keepShuffle = isPersistent && isShuffleOn
            val playSequence = if (keepShuffle) {
                withContext(Dispatchers.Default) {
                    QueueUtils.buildAnchoredShuffleQueueSuspending(
                        validSongs,
                        validSongs.indexOfFirst { it.id == validStartSong.id }.coerceAtLeast(0)
                    )
                }
            } else {
                null
            }
            throwIfDirectPlaybackRequestIsStale(requestToken)

            internalPlaySongs(
                validSongs, validStartSong, queueName, playlistId,
                // Play establishes shuffle OFF (see the product rule above) unless the listener asked for it to persist.
                shuffle = if (keepShuffle) null else false,
                playSequence = playSequence,
                source = source,
            )
            if (requestToken == directPlaybackToken) {
                directPlaybackJob = null
            }
        }
    }

    fun playSongsShuffled(
        songsToPlay: List<Song>, 
        queueName: String = "None", 
        playlistId: String? = null,
        startAtZero: Boolean = false,
        source: ListenSource? = null,
        timeline: ShuffleAllTimeline? = null,
    ) {
        cancelPendingFullQueuePlayback()
        val requestToken = beginDirectPlaybackRequest()
        directPlaybackJob = viewModelScope.launch {
            // B31 P2.2: the draw is kept as the list indices it is (no second list of the whole library, no ids to match) and handed to
            // the player's play order as they are: the same draw, nothing built from it until it is needed.
            val deal = queueStateHolder.dealShuffledQueue(songsToPlay, startAtZero)
            throwIfDirectPlaybackRequestIsStale(requestToken)
            if (deal == null) {
                sendToast(context.getString(R.string.player_no_songs_to_shuffle))
                return@launch
            }
            timeline?.mark("draw")

            transitionSchedulerJob?.cancel()

            launch { userPreferencesRepository.setShuffleOn(true) }

            // B17-H1: the list is loaded as it was (so turning shuffle off is the original order and costs nothing) and
            // plays in the shuffled sequence through the player's shuffle order.
            internalPlaySongs(
                songsToPlay, deal.startSong, queueName, playlistId,
                shuffle = true,
                playOrder = deal.order,
                source = source,
                timeline = timeline,
            )
            if (requestToken == directPlaybackToken) {
                directPlaybackJob = null
            }
        }
    }

    /**
     * An external VIEW/SEND always replaces the queue and plays, whatever state the player is in
     * (F-09): it goes through [internalPlaySongs], whose action is `setMediaItem` + `prepare()` +
     * `play()`, and `prepare()` is what takes a player out of a failed state.
     */
    fun playExternalUri(uri: Uri) {
        viewModelScope.launch {
            _externalLoadInFlight.value = true
            try {
                playExternalUriNow(uri)
            } finally {
                _externalLoadInFlight.value = false
            }
        }
    }

    private suspend fun playExternalUriNow(uri: Uri) {
        run {
            val requestedAt = android.os.SystemClock.elapsedRealtime()
            val timeline = ExternalViewTimeline(
                processAgeAtRequestMs = requestedAt - android.os.Process.getStartElapsedRealtime(),
                requestedAtMs = requestedAt,
            )
            externalTimeline = timeline
            val externalResult = externalMediaStateHolder.buildExternalSongFromUri(uri)
            timeline.mark(ExternalViewTimeline.BUILT, android.os.SystemClock.elapsedRealtime())
            if (externalResult == null) {
                sendToast(context.getString(R.string.external_playback_error))
                return
            }

            transitionSchedulerJob?.cancel()
            // A tail still being attached for the queue this one replaces must not outlive it.
            pendingQueueSegmentsJob?.cancel()

            // FIX-1 (B11-fix): the song that was opened starts ALONE. The rest of its folder is built
            // from the index afterwards and attached behind it, so the start never waits for the size
            // of the folder (2855 files took 10-50 s when the queue was built first).
            val queueSongs = listOf(externalResult.song)
            val immutableQueue = queueSongs.toPlaybackQueue()

            _playerUiState.update { state ->
                state.copy(
                    currentPlaybackQueue = immutableQueue,
                    currentQueueSourceName = context.getString(R.string.external_queue_label),
                )
            }
            playbackStateHolder.setCurrentPosition(0L)

            playbackStateHolder.updateStablePlayerState { state ->
                state.copy(
                    currentSong = externalResult.song,
                    isPlaying = true,
                    playWhenReady = true,
                    totalDuration = externalResult.song.duration,
                    lyrics = null,
                    // Loading, not the blank triple this used to write. `lyrics == null` with
                    // neither Loading nor a completed resolve matched no branch of the lyrics
                    // surface and rendered an empty pane. The handoff genuinely has not resolved
                    // lyrics for this song yet, and the playback observer starts that resolve, so
                    // Loading is both truthful and guaranteed to settle.
                    lyricsResolveCompleted = false,
                    isLoadingLyrics = true,
                    lyricsError = null
                )
            }

            _sheetState.value = PlayerSheetState.COLLAPSED
            _isSheetVisible.value = true

            Timber.tag("PlayerViewModel").i(
                "External VIEW/SEND replaces the queue: player state=%d error=%s items=%d",
                dualPlayerEngine.masterPlayer.playbackState,
                dualPlayerEngine.masterPlayer.playerError?.errorCodeName,
                dualPlayerEngine.masterPlayer.mediaItemCount
            )
            val queueLabel = context.getString(R.string.external_queue_label)
            val chosen = externalResult.song
            // FIX-1c: completes when this song is really in the player (`true`), or when the load that
            // was waiting for the MediaController is dropped (`false`). Everything that depends on
            // "my song is loaded" (the play, the tail of the folder) waits on this and not on the
            // player, which still holds the restored queue until the load runs.
            val loaded = CompletableDeferred<Boolean>()
            pendingQueueSegmentsJob = ExternalPlayback(
                start = {
                    // Loaded and prepared at full speed, but NOT played yet: see [ExternalPlayStart].
                    internalPlaySongs(
                        queueSongs, chosen, queueLabel, null,
                        source = ListenSource(ListenSourceKind.EXTERNAL_VIEW),
                        autoPlay = false,
                        onLoaded = {
                            timeline.mark(ExternalViewTimeline.LOADED, android.os.SystemClock.elapsedRealtime())
                            loaded.complete(true)
                        },
                    )
                    // Queued behind the controller (process freshly started): a later request that
                    // takes its place tells this load, so its play never waits for nothing.
                    if (!loaded.isCompleted) pendingExternalLoad = loaded
                    showPlayer()
                    timeline.mark(ExternalViewTimeline.HANDED, android.os.SystemClock.elapsedRealtime())
                    Timber.tag("PlayerViewModel").i(
                        "External VIEW/SEND handed to the player in %d ms",
                        android.os.SystemClock.elapsedRealtime() - requestedAt
                    )
                    startExternalPlayback(chosen, requestedAt, loaded, timeline)
                },
                loadSiblings = {
                    externalMediaStateHolder.buildFolderContinuation(
                        reference = externalResult,
                        originalUri = uri,
                        libraryLookup = { id -> libraryStateHolder.allSongsById.value[id] },
                    )
                },
                // Only the head of the tail is ever asked (ExternalQueueContinuation); off the main thread.
                probe = { song ->
                    withContext(Dispatchers.IO) {
                        QueueFileProbe.presence(context, MediaItemBuilder.playbackUri(song), song.path)
                    }
                },
                attach = { tail ->
                    // The replacement may be waiting for the controller; the tail follows it, once it
                    // is really loaded (the player holds the restored queue until then, and would
                    // make the check below drop a tail that belongs to this queue).
                    if (kotlinx.coroutines.withTimeoutOrNull(EXTERNAL_CONTROLLER_WAIT_MS) { loaded.await() } != true) {
                        return@ExternalPlayback
                    }
                    withContext(Dispatchers.Main.immediate) {
                        val player = dualPlayerEngine.masterPlayer
                        // Replaced by something else in the meantime: the tail is not for this queue.
                        if (player.currentMediaItem?.mediaId != chosen.id || player.mediaItemCount != 1) {
                            return@withContext
                        }
                        val full = listOf(chosen) + tail
                        _playerUiState.update { it.copy(currentPlaybackQueue = full.toPlaybackQueue()) }
                        val prepared = preparePlaybackQueueSegments(full, chosen.id, null)
                        attachPreparedQueueSegmentsIfCurrent(player, chosen.id, prepared)
                        Timber.tag("PlayerViewModel").i(
                            "External VIEW/SEND queue completed: %d songs, %d ms after the request",
                            full.size,
                            android.os.SystemClock.elapsedRealtime() - requestedAt
                        )
                    }
                },
            ).run(viewModelScope)
            // The replacement may be waiting for the controller; hold the overlay until it has run.
            kotlinx.coroutines.withTimeoutOrNull(EXTERNAL_LOAD_GRACE_MS) { loaded.await() }
        }
    }

    private var externalPlayJob: Job? = null

    /**
     * FIX-1b: plays the song of an external VIEW once the app is in the foreground, and retries if
     * Android 15 still refuses audio focus at that instant, so a VIEW always ends up playing. See
     * [ExternalPlayStart] for the rule; this is its wiring to the player and the process lifecycle.
     */
    private fun startExternalPlayback(
        chosen: Song,
        requestedAt: Long,
        loaded: CompletableDeferred<Boolean>,
        timeline: ExternalViewTimeline,
    ) {
        externalPlayJob?.cancel()
        externalPlayJob = viewModelScope.launch {
            val outcome = ExternalPlayStart(
                // The load may be waiting for the controller (0.7-5.8 s measured on a cold process).
                awaitLoaded = {
                    kotlinx.coroutines.withTimeoutOrNull(EXTERNAL_CONTROLLER_WAIT_MS) { loaded.await() } == true
                },
                awaitForeground = {
                    awaitAppForeground().also {
                        timeline.mark(ExternalViewTimeline.FOREGROUND, android.os.SystemClock.elapsedRealtime())
                    }
                },
                stillCurrent = { dualPlayerEngine.masterPlayer.currentMediaItem?.mediaId == chosen.id },
                focusDenials = { dualPlayerEngine.focusDenials.value },
                play = {
                    timeline.mark(ExternalViewTimeline.PLAY, android.os.SystemClock.elapsedRealtime())
                    dualPlayerEngine.masterPlayer.play()
                },
                pause = { kotlinx.coroutines.delay(it) },
                userCommands = { playbackStateHolder.userPlaybackCommandCount },
            ).run()
            Timber.tag("PlayerViewModel").i(
                "External VIEW/SEND play: %s, %d ms after the request (state=%d playWhenReady=%s)",
                outcome,
                android.os.SystemClock.elapsedRealtime() - requestedAt,
                dualPlayerEngine.masterPlayer.playbackState,
                dualPlayerEngine.masterPlayer.playWhenReady
            )
            if (outcome == ExternalPlayStart.Outcome.PLAYING) {
                kotlinx.coroutines.withTimeoutOrNull(EXTERNAL_PLAYING_WAIT_MS) { dualPlayerEngine.isPlayingFlow.first { it } }
                    ?.let { timeline.mark(ExternalViewTimeline.PLAYING, android.os.SystemClock.elapsedRealtime()) }
            }
            Timber.tag("PlayerViewModel").i("External VIEW timeline: %s", timeline.describe())
            if (externalTimeline === timeline) externalTimeline = null
        }
    }

    /**
     * Suspends until the app is in the foreground, at most [ExternalPlayStart.FOREGROUND_WAIT_MS]; true when it waited.
     * B12a-5: the process lifecycle being RESUMED **or** the activity manager saying the process is the top app,
     * whichever comes first (see [ForegroundWait]); the line says which, and what both read, so the cold-VIEW
     * timeline explains the wait instead of only measuring it.
     */
    private suspend fun awaitAppForeground(): Boolean {
        val lifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
        val result = ForegroundWait(
            lifecycleResumed = { lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) },
            processInForeground = {
                myProcessImportance() <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            },
            pause = { kotlinx.coroutines.delay(it) },
        ).await(ExternalPlayStart.FOREGROUND_WAIT_MS)
        if (result.waited) {
            Timber.tag("PlayerViewModel").i(
                "External VIEW foreground: via=%s after %d ms (lifecycle=%s importance=%d)",
                result.via, result.waitedMs, lifecycle.currentState, myProcessImportance(),
            )
        }
        return result.waited
    }

    /** The importance the activity manager holds for this process (`IMPORTANCE_FOREGROUND` = top app); GONE on failure. */
    private fun myProcessImportance(): Int = try {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance
    } catch (e: Exception) {
        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
    }

    fun showPlayer() {
        if (stablePlayerState.value.currentSong != null) {
            _isSheetVisible.value = true
        }
    }

    private fun setPreparingSong(songId: String?) {
        _playerUiState.update { state ->
            if (state.preparingSongId == songId) state else state.copy(preparingSongId = songId)
        }
    }

    private fun beginPreparingSong(song: Song) {
        if (!isLocalPlaybackSong(song)) {
            setPreparingSong(song.id)
        } else {
            setPreparingSong(null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val albumArtUri = song.albumArtUriString
            if (albumArtUri.isNullOrBlank()) {
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = null,
                    currentSongUriString = null,
                    isPreload = false
                )
            } else {
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = albumArtUri.toUri(),
                    currentSongUriString = albumArtUri,
                    isPreload = false
                )
            }
        }
    }

    private fun isLocalPlaybackSong(song: Song): Boolean {
        val scheme = MediaItemBuilder.playbackUri(song).scheme?.lowercase()
        return scheme == null || scheme in LOCAL_PLAYBACK_SCHEMES
    }

    private fun clearPreparingSongIfMatching(mediaId: String? = null) {
        val preparingSongId = _playerUiState.value.preparingSongId ?: return
        if (mediaId == null || preparingSongId == mediaId) {
            setPreparingSong(null)
        }
    }

    private suspend fun preparePlaybackQueueSegments(
        songsToPlay: List<Song>,
        startSongId: String,
        playlistId: String?
    ): PreparedPlaybackQueueSegments = withContext(Dispatchers.Default) {
        val currentIndex = songsToPlay
            .indexOfFirst { it.id == startSongId }
            .takeIf { it >= 0 }
            ?: 0

        val beforeCurrent = List(currentIndex) { index ->
            buildPlaybackMediaItem(songsToPlay[index], playlistId)
        }
        val afterStartIndex = currentIndex + 1
        val afterCurrent = List((songsToPlay.size - afterStartIndex).coerceAtLeast(0)) { offset ->
            buildPlaybackMediaItem(songsToPlay[afterStartIndex + offset], playlistId)
        }

        PreparedPlaybackQueueSegments(
            beforeCurrent = beforeCurrent,
            afterCurrent = afterCurrent,
            currentIndex = currentIndex
        )
    }

    /**
     * Makes the player play a queue of [songCount] songs in [order] (B17-H1): the same songs, dealt in another order (positions to
     * list indices), which the player takes as its shuffle order. One timeline publication, after the queue is attached. Nothing
     * happens if the player no longer holds this queue.
     *
     * [order] is [QueueLoadPlan.orderAfterAttach]: the sequence, led by the [playedCount] occurrences the listener really went
     * through while the rest of the queue was still loading (B18-R2, B19-S1), so none of them comes round again, the song that is
     * playing follows them, and every song nobody heard is still ahead. Nothing played keeps the sequence exactly as it was dealt.
     */
    private fun applyPlayOrder(songCount: Int, order: IntArray, playedCount: Int) {
        val master = dualPlayerEngine.masterPlayer
        if (master.mediaItemCount != songCount) return
        if (!dualPlayerEngine.setPlayOrder(order)) return
        // The row the playing song sits in changed with the order; the song and the player did not.
        playbackStateHolder.updateStablePlayerState {
            it.copy(currentMediaItemIndex = playbackStateHolder.queuePositionOf(master))
        }
        Timber.tag("QueueLoad").i(
            "Play order applied: %d songs dealt in the requested sequence, %d played in the first block lead it",
            order.size, playedCount,
        )
    }

    private suspend fun attachPreparedQueueSegmentsIfCurrent(
        player: Player,
        startSongId: String,
        preparedSegments: PreparedPlaybackQueueSegments
    ): Boolean {
        val attachStartedAt = android.os.SystemClock.elapsedRealtime()
        // Two calls, not 26 batches of 200 (B13-4): see [attachQueueSegments].
        val attached = attachQueueSegments(
            player = player,
            startSongId = startSongId,
            beforeCurrent = preparedSegments.beforeCurrent,
            afterCurrent = preparedSegments.afterCurrent
        )
        if (!attached) return false
        Timber.tag("QueueLoad").i(
            "Queue attached: %d before + %d after the start song, 2 timeline publications, %d ms on the main thread",
            preparedSegments.beforeCurrent.size,
            preparedSegments.afterCurrent.size,
            android.os.SystemClock.elapsedRealtime() - attachStartedAt
        )

        playbackStateHolder.updateStablePlayerState {
            // With shuffle on the start song's row is where the play order puts it, not where it sits in the loaded list.
            it.copy(
                currentMediaItemIndex = if (player.shuffleModeEnabled) {
                    playbackStateHolder.queuePositionOf(player)
                } else {
                    preparedSegments.currentIndex
                }
            )
        }
        return true
    }



    private suspend fun internalPlaySongs(
        songsToPlay: List<Song>,
        startSong: Song,
        queueName: String = "None",
        playlistId: String? = null,
        /**
         * False loads and prepares the song but leaves `play()` to the caller. Only the external VIEW
         * uses it (FIX-1b): it must not ask for audio focus until the app is in the foreground.
         */
        autoPlay: Boolean = true,
        /** Called on the main thread once the song is in the player and prepared; null for every caller but the VIEW. */
        onLoaded: (() -> Unit)? = null,
        /** Shuffle on (true) or off (false) for the queue being loaded; null leaves it as it is. */
        shuffle: Boolean? = null,
        /**
         * The order the songs of [songsToPlay] should play in, when it is not the player's own (a "Shuffle" button, a persistent
         * shuffle): the queue is loaded in its own order and this becomes the player's shuffle order (B17-H1).
         */
        playSequence: List<Song>? = null,
        /** Where the queue comes from, for the listening history (B28). */
        source: ListenSource? = null,
        /** False for a caller that re-plays the queue that is already there, which keeps the source it has. */
        recordsSource: Boolean = true,
        /**
         * B31 P2.2: the same sequence as [playSequence], already as indices into [songsToPlay] (what the draw produces): the play order is
         * taken from it directly instead of being matched to the songs by id. Not both.
         */
        playOrder: IntArray? = null,
        /** B31 P2.2: the phases of a "Shuffle" press, marked as this goes through them; null for everything else. */
        timeline: ShuffleAllTimeline? = null,
    ) {
        if (songsToPlay.isEmpty()) {
            clearPreparingSongIfMatching()
            return
        }
        val effectiveStartSong = songsToPlay.firstOrNull { it.id == startSong.id } ?: songsToPlay.first()
        if (recordsSource) {
            // Loading a queue is the listener leaving what was playing (if anything), and every listen of the new queue is
            // recorded as coming from where it was loaded from, never from the queue it replaces.
            listeningStatsTracker.noteUserDeparture(TrackEndReason.USER_SELECTED_OTHER)
            listeningStatsTracker.setQueueSource(source)
        }

        if (playlistId != null && queueName != "None") {
            appShortcutManager.updateLastPlaylistShortcut(playlistId, queueName)
        }

        beginPreparingSong(effectiveStartSong)
        // B18-R2: from here until the first songs of this queue are in the player, a next or previous is held instead of being
        // applied to the queue that is about to be replaced; once they are, only a skip with nowhere to go is held, until the
        // whole queue is attached. Every exit below ends it.
        val loadHold = dualPlayerEngine.queueLoadHold
        val loadToken = loadHold.begin()
        val startIndex = songsToPlay.indexOfFirst { it.id == effectiveStartSong.id }.coerceAtLeast(0)
        val geometry = QueueLoadPlan.of(size = songsToPlay.size, startIndex = startIndex)
        _playerUiState.update {
            it.copy(
                currentPlaybackQueue = songsToPlay.toPlaybackQueue(),
                currentQueueSourceName = queueName
            )
        }
        playbackStateHolder.updateStablePlayerState {
            it.copy(
                currentSong = effectiveStartSong,
                // The row inside the block the player starts with; the whole queue's row is set once the rest is attached.
                currentMediaItemIndex = geometry.startLocalIndex,
                isPlaying = true,
                playWhenReady = true,
                totalDuration = effectiveStartSong.duration.coerceAtLeast(0L)
            )
        }
        _isSheetVisible.value = true

        // The first load is the start song and its neighbours (5 behind, 20 ahead), so next and previous have somewhere to go the
        // moment it plays. They are cheap to build; the thousands behind them are built afterwards, off the main thread.
        val startMediaItem: MediaItem
        val firstItems: List<MediaItem>
        val plan: QueueLoadPlan
        try {
            startMediaItem = buildResolvedPlaybackMediaItem(effectiveStartSong)
            firstItems = if (geometry.windowSize == 1) {
                listOf(startMediaItem)
            } else {
                withContext(Dispatchers.Default) {
                    geometry.window.map { index ->
                        if (index == geometry.startIndex) startMediaItem else buildPlaybackMediaItem(songsToPlay[index], playlistId)
                    }
                }
            }
            // B19-S1: the sequence the list will play in, as list indices (null when the two lists are not the same songs, or no
            // sequence): the first block is anchored to it and what is played in the block leads it once the rest is attached.
            timeline?.mark("block")
            plan = when {
                playOrder != null -> geometry.withOrder(playOrder)
                playSequence == null -> geometry
                else -> geometry.withOrder(
                    withContext(Dispatchers.Default) {
                        PlayOrder.fromSequence(songsToPlay.map { it.id }, playSequence.map { it.id })
                    }
                )
            }
            timeline?.mark("order")
        } catch (failure: Throwable) {
            loadHold.end(loadToken)
            throw failure
        }

        val playSongsAction = {
            dualPlayerEngine.cancelNext()
            val enginePlayer = dualPlayerEngine.masterPlayer
            timeline?.let { watchFirstTimeline(enginePlayer, it) }

            val visits = putFirstSongsInPlayer(
                enginePlayer, plan, startMediaItem, firstItems, loadHold, loadToken,
                setBlockOrder = dualPlayerEngine::setPlayOrder,
            ) {
                // After the load, which replaced any window the engine held, so turning shuffle on or off here is the flag alone.
                shuffle?.let { setShuffleEnabled(it) }
                enginePlayer.prepare()
                if (autoPlay) enginePlayer.play()
                onLoaded?.invoke()
                _playerUiState.update { it.copy(isLoadingInitialSongs = false) }
            }

            timeline?.mark("load")
            if (plan.hasRest) {
                pendingQueueSegmentsJob?.cancel()
                pendingQueueSegmentsJob = viewModelScope.launch {
                    val builtAt = android.os.SystemClock.elapsedRealtime()
                    loadRestOfQueue(
                        player = dualPlayerEngine.masterPlayer,
                        plan = plan,
                        windowFirstId = firstItems.first().mediaId,
                        windowLastId = firstItems.last().mediaId,
                        buildItems = { range ->
                            withContext(Dispatchers.Default) {
                                List(range.count()) { offset -> buildPlaybackMediaItem(songsToPlay[range.first + offset], playlistId) }
                            }
                        },
                        hold = loadHold,
                        token = loadToken,
                        mainContext = Dispatchers.Main.immediate,
                        visits = visits,
                        clockMs = { android.os.SystemClock.elapsedRealtime() },
                        onAttached = { played, attachMs ->
                            val player = dualPlayerEngine.masterPlayer
                            Timber.tag("QueueLoad").i(
                                "Queue attached: %d before + %d after the block of %d songs around the start song, 2 timeline publications, %d ms on the main thread (items built in %d ms off it)",
                                plan.restBefore.count(), plan.restAfter.count(), plan.windowSize, attachMs,
                                android.os.SystemClock.elapsedRealtime() - builtAt - attachMs
                            )
                            // With shuffle on the row is where the play order puts the song, not where it sits in the loaded list.
                            playbackStateHolder.updateStablePlayerState {
                                it.copy(currentMediaItemIndex = playbackStateHolder.queuePositionOf(player))
                            }
                            plan.orderAfterAttach(played)?.let { applyPlayOrder(songsToPlay.size, it, played.size) }
                        },
                    )
                }
            } else {
                loadHold.end(loadToken)
            }
        }

        if (mediaController == null) {
            Timber.w("MediaController not available. Queuing playback action.")
            queuePlaybackAction(playSongsAction)
        } else {
            playSongsAction()
        }
    }

    /**
     * B31 P2.2: the player's next timeline change ends a "Shuffle" press's last phase and logs the line (once; the listener removes itself,
     * and a timeline that never comes is logged without it after [FIRST_TIMELINE_WAIT_MS]). Main thread, like the player.
     */
    private fun watchFirstTimeline(player: Player, timeline: ShuffleAllTimeline) {
        var done = false
        val listener = object : Player.Listener {
            override fun onTimelineChanged(newTimeline: androidx.media3.common.Timeline, reason: Int) {
                if (done) return
                done = true
                player.removeListener(this)
                timeline.mark(ShuffleAllTimeline.LAST)
                Timber.tag("ShuffleAll").i(timeline.line())
            }
        }
        player.addListener(listener)
        viewModelScope.launch {
            delay(FIRST_TIMELINE_WAIT_MS)
            if (!done) {
                done = true
                player.removeListener(listener)
                Timber.tag("ShuffleAll").i(timeline.line())
            }
        }
    }

    private suspend fun buildResolvedPlaybackMediaItem(song: Song): MediaItem {
        return MediaItemBuilder.build(song)
    }


    private fun loadAndPlaySong(song: Song) {
        cancelPendingFullQueuePlayback()
        beginPreparingSong(song)
        playbackStateHolder.updateStablePlayerState {
            it.copy(
                currentSong = song,
                isPlaying = true,
                playWhenReady = true
            )
        }
        _isSheetVisible.value = true

        val controller = mediaController
        if (controller == null) {
            queuePlaybackAction {
                loadAndPlaySong(song)
            }
            return
        }

        viewModelScope.launch {
            val mediaItem = buildResolvedPlaybackMediaItem(song)
            if (controller.currentMediaItem?.mediaId == song.id) {
                if (!controller.isPlaying) controller.play()
            } else {
                controller.setMediaItem(mediaItem)
                controller.prepare()
                controller.play()
            }
        }
    }

    /**
     * Shuffle on or off (B17-H1): Media3's own shuffle mode, so nothing is rebuilt and nothing is republished to the session;
     * the song that is playing carries on and what comes next follows the new order. See [PlaybackStateHolder.setShuffleEnabled].
     */
    fun toggleShuffle() {
        cancelPendingFullQueuePlayback()
        playbackStateHolder.toggleShuffle()
    }

    /**
     * Shuffle off, whatever it was: what every Play control means after it starts a list (see [playSongs]). An explicit request, not
     * a toggle, so a screen that read a stale "on" can never turn shuffle on by flipping it (B19-S2).
     */
    fun turnShuffleOff() {
        cancelPendingFullQueuePlayback()
        playbackStateHolder.setShuffleEnabled(false)
    }

    /** Shuffle to a given state for a queue that is being loaded; a no-op when it already is. The load deals its own order. */
    private fun setShuffleEnabled(enabled: Boolean) {
        if (dualPlayerEngine.isShuffleEnabled != enabled) playbackStateHolder.setShuffleForQueueLoad(enabled)
    }

    fun cycleRepeatMode() {
        playbackStateHolder.cycleRepeatMode()
    }

    private suspend fun setFavoriteStatusEverywhere(songId: String, isFavorite: Boolean) {
        musicRepository.setFavoriteStatus(songId, isFavorite)
    }

    fun toggleFavorite() {
        val currentSong = playbackStateHolder.stablePlayerState.value.currentSong ?: return
        viewModelScope.launch {
            val favoriteSongId = resolveFavoriteSongId(currentSong) ?: return@launch
            val currentlyFavorite = favoriteSongIds.value.contains(favoriteSongId)
            setFavoriteStatusEverywhere(favoriteSongId, !currentlyFavorite)
        }
    }

    fun toggleFavoriteSpecificSong(song: Song, removing: Boolean = false) {
        viewModelScope.launch {
            val favoriteSongId = resolveFavoriteSongId(song) ?: return@launch
            val currentlyFavorite = favoriteSongIds.value.contains(favoriteSongId)
            val targetFavoriteState = if (removing) false else !currentlyFavorite
            setFavoriteStatusEverywhere(favoriteSongId, targetFavoriteState)
        }
    }

    private suspend fun resolveFavoriteSongId(song: Song?): String? {
        song ?: return null
        if (song.id.toLongOrNull() != null) {
            return song.id
        }

        val contentUriCandidates = buildList {
            if (song.id.startsWith(EXTERNAL_SONG_ID_PREFIX)) {
                add(song.id.removePrefix(EXTERNAL_SONG_ID_PREFIX))
            }
            add(song.contentUriString)
        }.filter { it.isNotBlank() }.distinct()

        for (candidate in contentUriCandidates) {
            musicRepository.getSongIdByContentUri(candidate)?.let { return it.toString() }
            parseMediaStoreAudioId(candidate)?.let { return it.toString() }
        }

        val pathCandidates = buildList {
            add(song.path)
            contentUriCandidates.forEach { candidate ->
                parseFileUriPath(candidate)?.let(::add)
            }
        }.filter { it.isNotBlank() }.distinct()

        for (candidate in pathCandidates) {
            musicRepository.getSongByPath(candidate)?.id?.takeIf { it.toLongOrNull() != null }?.let {
                return it
            }
        }

        return null
    }

    private fun parseMediaStoreAudioId(uriString: String): Long? {
        val normalizedUri = uriString.substringBefore('?').substringBefore('#')
        if (
            !normalizedUri.startsWith("content://media/", ignoreCase = true) ||
            !normalizedUri.contains("/audio/media/", ignoreCase = true)
        ) {
            return null
        }

        return normalizedUri.substringAfterLast('/').toLongOrNull()?.takeIf { it > 0L }
    }

    private fun parseFileUriPath(uriString: String): String? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return uri.takeIf { it.scheme == "file" }?.path?.takeIf { it.isNotBlank() }
    }

    /** Whether queue edits go through the player's shuffle order: shuffle on and the player holds the whole queue. */
    private fun editsShuffleOrder(): Boolean =
        dualPlayerEngine.isShuffleEnabled && !dualPlayerEngine.isUsingWindowedQueue() &&
            dualPlayerEngine.masterPlayer.mediaItemCount > 0

    fun addSongToQueue(song: Song) {
        if (editsShuffleOrder()) {
            addSongsToShuffledQueue(listOf(song))
            return
        }
        mediaController?.let { controller ->
            val mediaItem = buildPlaybackMediaItem(song)
            controller.addMediaItem(mediaItem)
        }
    }

    fun addSongNextToQueue(song: Song) {
        if (editsShuffleOrder()) {
            // Media3 deals a new item a random place in the shuffle order; "next" is right after the current song.
            val master = dualPlayerEngine.masterPlayer
            val currentIndex = master.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET }
            val insertionIndex = if (currentIndex != null) (currentIndex + 1).coerceAtMost(master.mediaItemCount) else master.mediaItemCount
            master.addMediaItem(insertionIndex, buildPlaybackMediaItem(song))
            val order = dualPlayerEngine.currentPlayOrder()
            if (order != null && currentIndex != null) {
                dualPlayerEngine.setPlayOrder(PlayOrder.placeAfter(order, timelineIndex = insertionIndex, afterTimelineIndex = currentIndex))
            }
            return
        }
        mediaController?.let { controller ->
            val mediaItem = buildPlaybackMediaItem(song)

            val insertionIndex = if (controller.currentMediaItemIndex != C.INDEX_UNSET) {
                (controller.currentMediaItemIndex + 1).coerceAtMost(controller.mediaItemCount)
            } else {
                controller.mediaItemCount
            }

            controller.addMediaItem(insertionIndex, mediaItem)
        }
    }

    /**
     * "Add to queue" with shuffle on: the songs go on the end of the queue and, in the shuffle order, after everything that is
     * already lined up (Media3 would deal each a random place). One extra publication for the whole batch (B17-H1).
     */
    private fun addSongsToShuffledQueue(songs: List<Song>) {
        val master = dualPlayerEngine.masterPlayer
        val firstNew = master.mediaItemCount
        master.addMediaItems(songs.map { buildPlaybackMediaItem(it) })
        var order = dualPlayerEngine.currentPlayOrder() ?: return
        for (offset in songs.indices) order = PlayOrder.placeLast(order, firstNew + offset)
        dualPlayerEngine.setPlayOrder(order)
    }

    private fun buildPlaybackMediaItem(song: Song, playlistId: String? = null): MediaItem {
        val baseItem = MediaItemBuilder.build(song)
        if (playlistId == null) {
            return baseItem
        }

        val mergedExtras = Bundle(baseItem.mediaMetadata.extras ?: Bundle()).apply {
            putString("playlistId", playlistId)
        }

        return baseItem.buildUpon()
            .setMediaMetadata(
                baseItem.mediaMetadata.buildUpon()
                    .setExtras(mergedExtras)
                    .build()
            )
            .build()
    }

    /**
     * Plays all selected songs, preserving their selection order.
     * Clears selection after starting playback.
     */
    fun playSelectedSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        playSongs(songs, songs.first(), "Selected Songs")
        multiSelectionStateHolder.clearSelection()
    }

    /**
     * Adds all selected songs to the end of the queue.
     * Clears selection after adding.
     */
    fun addSelectedToQueue(songs: List<Song>) {
        if (songs.isNotEmpty() && editsShuffleOrder()) addSongsToShuffledQueue(songs) else songs.forEach { addSongToQueue(it) }
        viewModelScope.launch {
            val n = songs.size
            _toastEvents.emit(
                context.resources.getQuantityString(R.plurals.n_songs_added_to_queue, n, n),
            )
        }
        multiSelectionStateHolder.clearSelection()
    }

    /**
     * Adds all selected songs to play next, preserving selection order.
     * Songs are inserted in reverse order so they play in the correct sequence.
     * Clears selection after adding.
     */
    fun addSelectedAsNext(songs: List<Song>) {
        songs.reversed().forEach { addSongNextToQueue(it) }
        viewModelScope.launch {
            val n = songs.size
            _toastEvents.emit(
                context.resources.getQuantityString(R.plurals.n_songs_will_play_next, n, n),
            )
        }
        multiSelectionStateHolder.clearSelection()
    }

    fun playSelectedAlbums(albums: List<Album>) {
        if (albums.isEmpty()) return
        viewModelScope.launch {
            try {
                val resolvedSelection = resolveSelectedAlbumSongs(albums)
                if (resolvedSelection.songs.isEmpty()) {
                    _toastEvents.emit(context.getString(R.string.player_no_playable_songs_in_albums))
                    return@launch
                }

                val queueName = if (resolvedSelection.albums.size == 1) {
                    resolvedSelection.albums.first().title
                } else {
                    context.getString(R.string.player_queue_name_selected_albums)
                }

                playSongs(resolvedSelection.songs, resolvedSelection.songs.first(), queueName, null)
                _isSheetVisible.value = true

                if (resolvedSelection.wasTrimmed) {
                    _toastEvents.emit(
                        context.getString(R.string.player_only_first_n_albums_queued, MAX_ALBUM_BATCH_SELECTION),
                    )
                } else {
                    _toastEvents.emit(
                        context.getString(
                            R.string.player_albums_queued_format,
                            resolvedSelection.albums.size,
                            resolvedSelection.songs.size,
                        ),
                    )
                }
            } catch (e: Exception) {
                Timber.tag("PlayerViewModel").e(e, "Error playing selected albums")
                _toastEvents.emit(context.getString(R.string.player_could_not_queue_albums))
            }
        }
    }

    fun addSelectedAlbumsAsNext(albums: List<Album>) {
        if (albums.isEmpty()) return

        viewModelScope.launch {
            try {
                val resolvedSelection = resolveSelectedAlbumSongs(albums)
                if (resolvedSelection.songs.isEmpty()) {
                    _toastEvents.emit(context.getString(R.string.player_no_playable_songs_in_albums))
                    return@launch
                }

                resolvedSelection.songs
                    .asReversed()
                    .forEach(::addSongNextToQueue)

                if (resolvedSelection.wasTrimmed) {
                    _toastEvents.emit(context.getString(R.string.player_only_first_n_albums_next, MAX_ALBUM_BATCH_SELECTION))
                } else {
                    _toastEvents.emit(context.getString(R.string.player_albums_will_play_next_format, resolvedSelection.albums.size))
                }
            } catch (e: Exception) {
                Timber.tag("PlayerViewModel").e(e, "Error adding selected albums as next")
                _toastEvents.emit(context.getString(R.string.player_could_not_add_albums_next))
            }
        }
    }

    fun addSelectedAlbumsToQueue(albums: List<Album>) {
        if (albums.isEmpty()) return

        viewModelScope.launch {
            try {
                val resolvedSelection = resolveSelectedAlbumSongs(albums)
                if (resolvedSelection.songs.isEmpty()) {
                    _toastEvents.emit(context.getString(R.string.player_no_playable_songs_in_albums))
                    return@launch
                }

                resolvedSelection.songs.forEach(::addSongToQueue)

                if (resolvedSelection.wasTrimmed) {
                    _toastEvents.emit(context.getString(R.string.player_only_first_n_albums_added_queue, MAX_ALBUM_BATCH_SELECTION))
                } else {
                    _toastEvents.emit(context.getString(R.string.player_albums_added_to_queue_format, resolvedSelection.albums.size))
                }
            } catch (e: Exception) {
                Timber.tag("PlayerViewModel").e(e, "Error adding selected albums to queue")
                _toastEvents.emit(context.getString(R.string.player_could_not_add_albums_queue))
            }
        }
    }

    /**
     * Adds all selected songs to favorites.
     * Clears selection after liking.
     */
    fun likeSelectedSongs(songs: List<Song>) {
        viewModelScope.launch {
            val favIds = favoriteSongIds.value.toMutableSet()
            var likedCount = 0
            songs.forEach { song ->
                if (!favIds.contains(song.id)) {
                    setFavoriteStatusEverywhere(song.id, true)
                    favIds.add(song.id)
                    likedCount++
                }
            }
            if (likedCount > 0) {
                _toastEvents.emit(
                    context.resources.getQuantityString(R.plurals.n_songs_added_to_favorites, likedCount, likedCount),
                )
            } else {
                _toastEvents.emit(context.getString(R.string.player_all_songs_already_in_favorites))
            }
            multiSelectionStateHolder.clearSelection()
        }
    }

    /**
     * Removes all selected songs from favorites.
     * Clears selection after unliking.
     */
    fun unlikeSelectedSongs(songs: List<Song>) {
        viewModelScope.launch {
            val favIds = favoriteSongIds.value.toMutableSet()
            var unlikedCount = 0
            songs.forEach { song ->
                if (favIds.contains(song.id)) {
                    setFavoriteStatusEverywhere(song.id, false)
                    favIds.remove(song.id)
                    unlikedCount++
                }
            }
            if (unlikedCount > 0) {
                _toastEvents.emit(
                    context.resources.getQuantityString(
                        R.plurals.n_songs_removed_from_favorites,
                        unlikedCount,
                        unlikedCount,
                    ),
                )
            } else {
                _toastEvents.emit(context.getString(R.string.player_no_songs_were_in_favorites))
            }
            multiSelectionStateHolder.clearSelection()
        }
    }

    /**
     * Shares all selected songs as a ZIP file.
     * Clears selection after initiating share.
     */
    fun shareSelectedAsZip(songs: List<Song>) {
        viewModelScope.launch {
            _toastEvents.emit(context.getString(R.string.player_creating_zip))

            val result = ZipShareHelper.createAndShareZip(context, songs)

            result.onSuccess {
                multiSelectionStateHolder.clearSelection()
            }.onFailure { error ->
                _toastEvents.emit(
                    context.getString(R.string.player_share_zip_failed_format, error.localizedMessage ?: ""),
                )
                Timber.tag("PlayerViewModel").e(error, "Failed to share selection as zip")
            }
        }
    }

    private suspend fun resolveSelectedAlbumSongs(albums: List<Album>): ResolvedAlbumSelection {
        val albumsToProcess = albums.take(MAX_ALBUM_BATCH_SELECTION)
        val wasTrimmed = albums.size > albumsToProcess.size

        val songs = withContext(Dispatchers.IO) {
            buildList {
                albumsToProcess.forEach { album ->
                    val albumSongs = musicRepository.getSongsForAlbum(album.id).first()
                    if (albumSongs.isNotEmpty()) {
                        addAll(sortSongsForAlbumSelection(albumSongs))
                    }
                }
            }
        }

        return ResolvedAlbumSelection(
            albums = albumsToProcess,
            songs = songs,
            wasTrimmed = wasTrimmed
        )
    }

    private fun sortSongsForAlbumSelection(songs: List<Song>): List<Song> {
        return songs.sortedWith(
            compareBy<Song> { it.discNumber ?: 1 }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        )
    }

    /**
     * Deletes all selected songs from device with confirmation.
     * Shows a single confirmation dialog for all songs.
     */
    private var pendingBatchDeleteSongs: List<Song>? = null
    private var pendingBatchDeleteSkippedCount: Int = 0
    private var pendingBatchDeleteOnComplete: (() -> Unit)? = null

    fun deleteSelectedFromDevice(activity: Activity, songs: List<Song>, onComplete: () -> Unit) {
        viewModelScope.launch {
            // The song being played is deletable like any other. Refusing it was what made a
            // one-song library impossible to empty from inside the app: `currentSong` only moves
            // when playback moves, so with one song left the refusal could never stop applying.
            // Taking playback off a deleted song is `detachDeletedSongFromPlayback`'s job, and it
            // runs after the file is gone so a failed delete cannot stop the music.
            val deletableSongs = songs
            val skippedCount = 0

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val deleteRequests = withContext(Dispatchers.IO) {
                    deletableSongs.mapNotNull { song ->
                        com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper
                            .resolveDeleteRequestUri(
                                context = activity,
                                songId = song.id.toLongOrNull(),
                                contentUriString = song.contentUriString,
                                filePath = song.path,
                            )?.let { uri -> song to uri }
                    }
                }
                if (deleteRequests.size == deletableSongs.size) {
                    val uris = deleteRequests.map { it.second }.distinctBy { it.toString() }
                    val deleteRequest = com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper
                        .createDeleteRequest(activity, uris)
                    if (deleteRequest != null) {
                        val acceptedUriStrings = deleteRequest.acceptedUris
                            .mapTo(mutableSetOf()) { it.toString() }
                        val acceptedSongs = deleteRequests
                            .filter { (_, uri) -> uri.toString() in acceptedUriStrings }
                            .map { it.first }
                        val invalidRequestCount = deletableSongs.size - acceptedSongs.size

                        pendingBatchDeleteSongs = acceptedSongs
                        pendingBatchDeleteSkippedCount = skippedCount + invalidRequestCount
                        pendingBatchDeleteOnComplete = onComplete
                        _deletePermissionRequest.emit(deleteRequest.intentSender)
                        return@launch
                    }
                }
            }

            val confirmed = showMultiDeleteConfirmation(activity, deletableSongs.size)
            if (!confirmed) {
                onComplete()
                return@launch
            }

            var successCount = 0
            deletableSongs.forEach { song ->
                val success = metadataEditStateHolder.deleteSong(song)
                if (success) {
                    detachDeletedSongFromPlayback(song)
                    removeSong(song)
                    successCount++
                }
            }

            when {
                successCount == deletableSongs.size && skippedCount == 0 ->
                    _toastEvents.emit(
                        context.resources.getQuantityString(R.plurals.n_files_deleted, successCount, successCount),
                    )
                successCount == deletableSongs.size && skippedCount > 0 ->
                    _toastEvents.emit(
                        context.getString(
                            R.string.player_batch_delete_files_deleted_skipped_format,
                            successCount,
                            skippedCount,
                        ),
                    )
                successCount > 0 ->
                    _toastEvents.emit(
                        context.getString(
                            R.string.player_batch_delete_partial_format,
                            successCount,
                            deletableSongs.size,
                        ),
                    )
                else ->
                    _toastEvents.emit(context.getString(R.string.player_delete_files_failed))
            }

            multiSelectionStateHolder.clearSelection()
            onComplete()
        }
    }

    private suspend fun showMultiDeleteConfirmation(activity: Activity, count: Int): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    return@withContext false
                }

                val userChoice = CompletableDeferred<Boolean>()

                val dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(
                        context.resources.getQuantityString(
                            R.plurals.delete_songs_confirmation_title,
                            count,
                            count,
                        ),
                    )
                    .setMessage(context.getString(R.string.delete_songs_permanent_message))
                    .setPositiveButton(context.getString(R.string.delete_action)) { _, _ ->
                        userChoice.complete(true)
                    }
                    .setNegativeButton(context.getString(R.string.cancel)) { _, _ ->
                        userChoice.complete(false)
                    }
                    .setOnCancelListener {
                        userChoice.complete(false)
                    }
                    .setCancelable(true)
                    .create()

                dialog.show()
                userChoice.await()
            } catch (e: Exception) {
                false
            }
        }
    }

    fun deleteFromDevice(activity: Activity, song: Song, onResult: (Boolean) -> Unit = {}){
        viewModelScope.launch {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val intentSender = withContext(Dispatchers.IO) {
                    com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper
                        .resolveDeleteRequestUri(
                            context = activity,
                            songId = song.id.toLongOrNull(),
                            contentUriString = song.contentUriString,
                            filePath = song.path,
                        )?.let { uri ->
                            com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper
                                .createDeleteRequestIntentSender(activity, listOf(uri))
                        }
                }
                if (intentSender != null) {
                    pendingDeleteSong = song
                    pendingDeleteCallback = onResult
                    _deletePermissionRequest.emit(intentSender)
                    return@launch
                }
            }

            val userConfirmed = songRemovalStateHolder.showDeleteConfirmation(activity, song)
            if (!userConfirmed) {
                onResult(false)
                return@launch
            }

            val success = songRemovalStateHolder.deleteSongFile(song)
            if (success) {
                _toastEvents.emit(context.getString(R.string.player_file_deleted))
                detachDeletedSongFromPlayback(song)
                removeSong(song)
                onResult(true)
            } else {
                _toastEvents.emit(context.getString(R.string.player_delete_file_not_found))
                onResult(false)
            }
        }
    }

    /** Called from the UI after the user approves or denies the MediaStore delete request. */
    fun onDeletePermissionResult(granted: Boolean) {
        val batchSongs = pendingBatchDeleteSongs
        if (batchSongs != null) {
            val skippedCount = pendingBatchDeleteSkippedCount
            val onComplete = pendingBatchDeleteOnComplete
            pendingBatchDeleteSongs = null
            pendingBatchDeleteSkippedCount = 0
            pendingBatchDeleteOnComplete = null
            viewModelScope.launch {
                if (granted) {
                    batchSongs.forEach { song ->
                        detachDeletedSongFromPlayback(song)
                        removeSong(song)
                    }
                    val count = batchSongs.size
                    if (skippedCount > 0) {
                        _toastEvents.emit(
                            context.getString(
                                R.string.player_batch_delete_files_deleted_skipped_format,
                                count,
                                skippedCount,
                            ),
                        )
                    } else {
                        _toastEvents.emit(
                            context.resources.getQuantityString(R.plurals.n_files_deleted, count, count),
                        )
                    }
                } else {
                    _toastEvents.emit(context.getString(R.string.player_deletion_cancelled))
                }
                multiSelectionStateHolder.clearSelection()
                onComplete?.invoke()
            }
            return
        }

        val song = pendingDeleteSong ?: return
        val callback = pendingDeleteCallback
        pendingDeleteSong = null
        pendingDeleteCallback = null
        viewModelScope.launch {
            if (granted) {
                _toastEvents.emit(context.getString(R.string.player_file_deleted))
                detachDeletedSongFromPlayback(song)
                removeSong(song)
                callback?.invoke(true)
            } else {
                callback?.invoke(false)
            }
        }
    }

    suspend fun removeSong(song: Song) {
        toggleFavoriteSpecificSong(song, true)
        playbackStateHolder.setCurrentPosition(0L)
        _playerUiState.update { currentState ->
            currentState.copy(
                currentPlaybackQueue = currentState.currentPlaybackQueue.removeSongById(song.id),
                currentQueueSourceName = ""
            )
        }
        _isSheetVisible.value = false
        songRemovalStateHolder.removeSongFromLibrary(song)
    }

    /**
     * Takes a song that has just been deleted from the device out of playback.
     *
     * Deleting a song other than the one playing is a queue edit and nothing more. Deleting the
     * song that *is* playing cannot be left to [removeFromMediaControllerQueue] alone: when it is
     * the last item, Media3 empties the timeline but the UI mirror keeps rendering a song whose row
     * and file are both gone — a Now Playing surface describing something nobody can play, which is
     * exactly the "one song left that will not go away" the library kept ending up with.
     *
     * So the last item is stopped and cleared outright. When other items remain, removing the
     * current one is enough: Media3 moves to the item that takes its place, so playback continues
     * on something that still exists rather than being interrupted.
     */
    private fun detachDeletedSongFromPlayback(song: Song) {
        val isCurrentSong = playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id
        // Both sources still contain the song being deleted. Two, because neither is trustworthy on
        // its own at this instant: the controller's timeline is authoritative but can read empty
        // while it reconnects, and the UI mirror is derived but always populated. The larger count
        // wins a disagreement, so an ambiguous queue continues playback rather than wrongly stopping
        // it — continuing on a song that turns out to be deleted too is corrected by that song's own
        // pass, while stopping is the outcome the user notices immediately and cannot undo.
        val queueItems = maxOf(
            mediaController?.currentTimeline?.windowCount ?: 0,
            _playerUiState.value.currentPlaybackQueue.size,
        )

        if (DeletedSongPlaybackPolicy.decide(isCurrentSong, queueItems) ==
            DeletedSongPlaybackAction.REMOVE_FROM_QUEUE_ONLY
        ) {
            removeFromMediaControllerQueue(song.id)
            return
        }

        mediaController?.stop()
        mediaController?.clearMediaItems()
        playbackStateHolder.setCurrentPosition(0L)
        playbackStateHolder.updateStablePlayerState {
            it.copy(
                currentSong = null,
                isPlaying = false,
                playWhenReady = false,
                totalDuration = 0L,
            )
        }
    }

    private fun removeFromMediaControllerQueue(songId: String) {
        val controller = mediaController ?: return

        try {
            val timeline = controller.currentTimeline
            val mediaItemCount = timeline.windowCount

            for (i in 0 until mediaItemCount) {
                val window = timeline.getWindow(i, Timeline.Window())
                if (window.mediaItem.mediaId == songId) {
                    controller.removeMediaItem(i)
                    break
                }
            }
        } catch (e: Exception) {
            Timber.tag("MediaController").e("Error removing from queue: ${e.message}")
        }
    }

    /**
     * Signal from the player sheet whether the slider-bearing UI is currently
     * rendered. Drives the position-ticker's resolution (250 ms vs 1 s).
     */
    fun setSliderUiMounted(mounted: Boolean) {
        playbackStateHolder.setSliderUiMounted(mounted)
    }

    fun playPause() {
        val controller = mediaController
        if (controller == null || !controller.isConnected) {
            playbackStateHolder.playPause()
            return
        }

        if (controller.isPlaying) {
            // B12a-7: the glyph answers the tap, and the controller's echoes of the state from before it cannot undo that.
            playbackStateHolder.noteUserPlayPause(playbackWanted = false)
            controller.pause()
        } else {
            if (controller.currentMediaItem == null) {
                val currentQueue = _playerUiState.value.currentPlaybackQueue
                val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
                when {
                    currentQueue.isNotEmpty() && currentSong != null -> {
                        viewModelScope.launch {
                            transitionSchedulerJob?.cancel()
                            internalPlaySongs(
                                currentQueue.toList(),
                                currentSong,
                                _playerUiState.value.currentQueueSourceName,
                                // The rows are in play order: with shuffle on, the queue plays in the order it is listed in.
                                playSequence = currentQueue.toList().takeIf { dualPlayerEngine.isShuffleEnabled },
                                recordsSource = false,
                            )
                        }
                    }
                    currentSong != null -> loadAndPlaySong(currentSong)
                    else -> {
                        viewModelScope.launch {
                            val fallbackSong = musicRepository.getFirstPlayableSong()
                            if (fallbackSong != null) {
                                loadAndPlaySong(fallbackSong)
                            } else {
                                controller.play()
                            }
                        }
                    }
                }
            } else {
                if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
                    controller.prepare()
                }
                playbackStateHolder.noteUserPlayPause(playbackWanted = true)
                controller.play()
            }
        }
    }

    fun seekTo(position: Long) {
        playbackStateHolder.seekTo(position)
    }

    fun nextSong() {
        playbackStateHolder.nextSong()
    }

    fun previousSong() {
        playbackStateHolder.previousSong()
    }

    /** The engine position "previous" decides with, for the carousel's prediction (AUD-116). */
    fun previousDecisionPositionMs(): Long = playbackStateHolder.previousDecisionPositionMs()

    private fun startProgressUpdates() {
        playbackStateHolder.startProgressUpdates()
    }

    private fun stopProgressUpdates() {
        playbackStateHolder.stopProgressUpdates()
    }

    fun observeSongs(songIds: List<String>): Flow<List<Song>> {
        return musicRepository.getSongsByIds(songIds)
    }

    fun searchSongs(query: String): Flow<List<Song>> {
        return musicRepository.searchSongs(query)
    }

    suspend fun getSongs(songIds: List<String>) : List<Song>{
        return musicRepository.getSongsByIds(songIds).first()
    }

    fun sortSongs(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortSongs(sortOption, persist)
    }

    fun sortAlbums(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortAlbums(sortOption, persist)
    }

    fun sortArtists(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortArtists(sortOption, persist)
    }

    fun sortFavoriteSongs(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortFavoriteSongs(sortOption, persist)
    }

    fun sortFolders(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortFolders(sortOption, persist)
    }

    fun setFoldersPlaylistView(isPlaylistView: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setFoldersPlaylistView(isPlaylistView)
            folderNavigationStateHolder.setFoldersPlaylistViewState(
                isPlaylistView = isPlaylistView,
                updateUiState = { mutation -> _playerUiState.update(mutation) }
            )
        }
    }

    fun setFoldersSource(source: FolderSource) {
        if (!ENABLE_FOLDERS_SOURCE_SWITCHING) return
        viewModelScope.launch {
            userPreferencesRepository.setFoldersSource(source)
        }
    }

    fun navigateToFolder(path: String) {
        folderNavigationStateHolder.navigateToFolder(
            path = path,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) },
            onFolderChanged = { folderPath ->
                folderNavigationStateHolder.hydrateCurrentFolderSongsIfNeeded(
                    scope = viewModelScope,
                    folderPath = folderPath,
                    getUiState = { _playerUiState.value },
                    updateUiState = { mutation -> _playerUiState.update(mutation) },
                    requiresHydration = { song -> song.requiresHydration() },
                    hydrateSongs = { songs -> hydrateSongsIfNeeded(songs) }
                )
            }
        )
    }

    fun navigateBackFolder() {
        folderNavigationStateHolder.navigateBackFolder(
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) },
            onFolderChanged = { folderPath ->
                folderNavigationStateHolder.hydrateCurrentFolderSongsIfNeeded(
                    scope = viewModelScope,
                    folderPath = folderPath,
                    getUiState = { _playerUiState.value },
                    updateUiState = { mutation -> _playerUiState.update(mutation) },
                    requiresHydration = { song -> song.requiresHydration() },
                    hydrateSongs = { songs -> hydrateSongsIfNeeded(songs) }
                )
            }
        )
    }

    fun setAlbumsListView(isList: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAlbumsListView(isList)
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        searchStateHolder.updateSearchFilter(filterType)
    }

    fun loadSearchHistory(limit: Int = 15) {
        searchStateHolder.loadSearchHistory(limit)
    }

    fun onSearchQuerySubmitted(query: String) {
        searchStateHolder.onSearchQuerySubmitted(query)
    }

    fun performSearch(query: String) {
        searchStateHolder.performSearch(query)
    }

    fun deleteSearchHistoryItem(query: String) {
        searchStateHolder.deleteSearchHistoryItem(query)
    }

    fun clearSearchHistory() {
        searchStateHolder.clearSearchHistory()
    }

    fun clearQueueExceptCurrent() {
        mediaController?.let { controller ->
            val currentSongIndex = controller.currentMediaItemIndex
            if (currentSongIndex == C.INDEX_UNSET) return@let
            val indicesToRemove = (0 until controller.mediaItemCount)
                .filter { it != currentSongIndex }
                .sortedDescending()

            for (index in indicesToRemove) {
                controller.removeMediaItem(index)
            }
        }
    }


    override fun onCleared() {
        val controllerToRelease = mediaController
        mediaControllerPlaybackListener?.let { listener ->
            controllerToRelease?.removeListener(listener)
            mediaControllerPlaybackListener = null
        }
        playbackStateHolder.clearMediaController(controllerToRelease)
        controllerToRelease?.release()
        mediaController = null
        mediaControllerFuture.cancel(true)
        super.onCleared()
        stopProgressUpdates()
        listeningStatsTracker.onCleared()
        dailyMixStateHolder.onCleared()
        themeStateHolder.onCleared()
        searchStateHolder.onCleared()
        // SearchStateHolder is an application-scoped singleton whose subscription must outlive
        // this ViewModel: its onCleared() is deliberately a no-op for that reason, and it is
        // still called here so the contract stays visible at the call site.
        libraryStateHolder.onCleared()
        sleepTimerStateHolder.onCleared()
        // ConnectivityStateHolder is an application-scoped singleton shared with the DAP
        // control-center ViewModel. Clearing it here unregisters radio observers while another
        // live consumer still owns its StateFlows, leaving Bluetooth/Wi-Fi UI permanently stale.
        // Process teardown releases these application-context registrations.
        queueUndoStateHolder.onCleared()
    }

    fun setSleepTimer(durationMinutes: Int) {
        sleepTimerStateHolder.setSleepTimer(durationMinutes)
    }

    fun playCounted(count: Int) {
        sleepTimerStateHolder.playCounted(count)
    }

    fun cancelCountedPlay() {
        sleepTimerStateHolder.cancelCountedPlay()
    }

    fun setEndOfTrackTimer(enable: Boolean) {
        val currentSongId = stablePlayerState.value.currentSong?.id
        sleepTimerStateHolder.setEndOfTrackTimer(enable, currentSongId)
    }

    fun cancelSleepTimer(overrideToastMessage: String? = null, suppressDefaultToast: Boolean = false) {
        sleepTimerStateHolder.cancelSleepTimer(overrideToastMessage, suppressDefaultToast)
    }

    /**
     * The songs of one genre, read from the library **once** (B33-U5): what Search's genre section loads when the listener unfolds it. Not
     * observed, and never called while the section is folded.
     */
    suspend fun songsOfGenreOnce(genreName: String): List<Song> = musicRepository.getMusicByGenre(genreName).first()

    fun getSongUrisForGenre(genreId: String): Flow<List<String>> {
        return musicRepository.getMusicByGenre(genreId).map { songs ->
            songs.take(4).mapNotNull { it.albumArtUriString?.takeIf { uri -> uri.isNotBlank() } }
        }
    }

    fun showSortingSheet() {
        libraryTabsStateHolder.showSortingSheet(_isSortingSheetVisible)
    }

    fun hideSortingSheet() {
        libraryTabsStateHolder.hideSortingSheet(_isSortingSheetVisible)
    }

    fun onLibraryTabSelected(tabIndex: Int) {
        libraryTabsStateHolder.onLibraryTabSelected(
            tabIndex = tabIndex,
            libraryTabs = libraryTabsFlow.value,
            loadedTabs = _loadedTabs,
            currentLibraryTabId = _currentLibraryTabId,
            saveLastTabIndex = { index -> userPreferencesRepository.saveLastLibraryTabIndex(index) },
            scope = viewModelScope,
            loadSongs = { loadSongsIfNeeded() },
            loadAlbums = { loadAlbumsIfNeeded() },
            loadArtists = { loadArtistsIfNeeded() },
            loadFolders = { loadFoldersFromRepository() }
        )
    }

    /**
     * Stores the tab editor's result in one go: the full order with hidden tabs included, and
     * which of them are hidden. Nothing is ever removed from the order.
     */
    fun saveLibraryTabConfiguration(order: List<String>, hidden: Set<String>) {
        viewModelScope.launch {
            userPreferencesRepository.saveLibraryTabsOrder(Json.encodeToString(order))
            userPreferencesRepository.saveLibraryHiddenTabs(hidden.toList())
        }
    }

    private fun decodeTabOrder(orderJson: String?): ImmutableList<String> {
        if (orderJson == null) return DEFAULT_LIBRARY_TABS.toImmutableList()
        return try {
            reconcileLibraryTabOrder(Json.decodeFromString<List<String>>(orderJson)).toImmutableList()
        } catch (e: Exception) {
            DEFAULT_LIBRARY_TABS.toImmutableList()
        }
    }

    fun saveLibraryTabsOrder(tabs: List<String>) {
        viewModelScope.launch {
            val orderJson = Json.encodeToString(tabs)
            userPreferencesRepository.saveLibraryTabsOrder(orderJson)
        }
    }

    fun resetLibraryTabsOrder() {
        viewModelScope.launch {
            userPreferencesRepository.resetLibraryTabsOrder()
        }
    }

    fun selectSongForInfo(song: Song) {
        _selectedSongForInfo.value = song
        viewModelScope.launch {
            val hydrated = withContext(Dispatchers.IO) {
                musicRepository.getSong(song.id).first()
            } ?: return@launch
            if (_selectedSongForInfo.value?.id == song.id) {
                _selectedSongForInfo.value = hydrated
            }
        }
    }

    private fun loadLyricsForCurrentSong() {
        val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
        if (currentSong == null) {
            // Nothing to resolve for. Publishing a terminal state here is what stops a Loading
            // state outliving the request that set it.
            playbackStateHolder.updateStablePlayerState { state ->
                if (state.currentSong != null) state
                else state.copy(
                    isLoadingLyrics = false,
                    lyrics = null,
                    lyricsResolveCompleted = true
                )
            }
            return
        }
        lyricsStateHolder.loadLyricsForSong(currentSong, lyricsSourcePreference.value)
    }

    /**
     * Makes sure local lyrics have actually been looked for before the UI offers an online
     * search. Safe to call repeatedly: a resolve already in flight or already finished is left
     * alone.
     */
    fun ensureLyricsResolvedForCurrentSong() {
        // Deliberately keyed on `lyricsResolveCompleted` alone. It used to return early when
        // `isLoadingLyrics` was already true, which meant a Loading state that had been set but
        // never followed by a live request could never be retried — the guard disabled the only
        // recovery path. Re-requesting is cheap and safe: the holder cancels any in-flight job.
        val state = playbackStateHolder.stablePlayerState.value
        if (state.lyricsResolveCompleted) return
        loadLyricsForCurrentSong()
    }

    fun saveBatchMetadata(
        songs: List<Song>,
        title: String?,
        artist: String?,
        album: String?,
        albumArtist: String?,
        composer: String?,
        genre: String?,
        lyrics: String?,
        trackNumber: Int?,
        discNumber: Int?,
        replayGainTrackGainDb: String?,
        replayGainAlbumGainDb: String?,
        coverArtUpdate: CoverArtUpdate?
    ) {
        viewModelScope.launch {
            val localSongsNeedingPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                songs.mapNotNull { song ->
                    song.id.toLongOrNull()?.takeIf { it > 0 }?.let { song to it }
                }
            } else {
                emptyList()
            }

            if (localSongsNeedingPermission.isNotEmpty()) {
                val uris = localSongsNeedingPermission.mapNotNull { (_, songId) ->
                    android.provider.MediaStore.Audio.Media.getContentUri(
                        android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY,
                        songId
                    )
                }

                if (uris.isNotEmpty()) {
                    val intentSender = com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper
                        .createWriteRequestIntentSender(context, uris)

                    if (intentSender != null) {
                        pendingBatchMetadataEdit = PendingBatchMetadataEdit(
                            songs = songs,
                            title = title,
                            artist = artist,
                            album = album,
                            albumArtist = albumArtist,
                            composer = composer,
                            genre = genre,
                            lyrics = lyrics,
                            trackNumber = trackNumber,
                            discNumber = discNumber,
                            replayGainTrackGainDb = replayGainTrackGainDb,
                            replayGainAlbumGainDb = replayGainAlbumGainDb,
                            coverArtUpdate = coverArtUpdate
                        )
                        _writePermissionRequest.emit(intentSender)
                        return@launch
                    }
                }
            }

            performBatchMetadataEdit(
                songs, title, artist, album, albumArtist, composer, genre, lyrics,
                trackNumber, discNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate
            )
        }
    }

    private suspend fun performBatchMetadataEdit(
        songs: List<Song>,
        title: String?,
        artist: String?,
        album: String?,
        albumArtist: String?,
        composer: String?,
        genre: String?,
        lyrics: String?,
        trackNumber: Int?,
        discNumber: Int?,
        replayGainTrackGainDb: String?,
        replayGainAlbumGainDb: String?,
        coverArtUpdate: CoverArtUpdate?
    ) {
        var successCount = 0
        var failureCount = 0
        val previousAlbumArts = mutableSetOf<String?>()

        songs.forEach { song ->
            previousAlbumArts.add(song.albumArtUriString)

            val result = metadataEditStateHolder.saveMetadata(
                song = song,
                newTitle = title ?: song.title,
                newArtist = artist ?: song.displayArtist,
                newAlbum = album ?: song.album,
                newAlbumArtist = albumArtist ?: (song.albumArtist ?: ""),
                newComposer = composer ?: "",
                newGenre = genre ?: (song.genre ?: ""),
                newLyrics = lyrics ?: (song.lyrics ?: ""),
                newTrackNumber = trackNumber ?: song.trackNumber,
                newDiscNumber = discNumber ?: song.discNumber,
                newReplayGainTrackGainDb = replayGainTrackGainDb,
                newReplayGainAlbumGainDb = replayGainAlbumGainDb,
                coverArtUpdate = coverArtUpdate
            )

            if (result.success && result.updatedSong != null) {
                successCount++
                val updatedSong = result.updatedSong
                val refreshedAlbumArtUri = result.updatedAlbumArtUri

                invalidateCoverArtCaches(song.albumArtUriString, refreshedAlbumArtUri)

                _playerUiState.update { state ->
                    val updatedQueue = state.currentPlaybackQueue.replaceSong(updatedSong)
                    if (updatedQueue === state.currentPlaybackQueue) {
                        state
                    } else {
                        state.copy(currentPlaybackQueue = updatedQueue)
                    }
                }

                libraryStateHolder.updateSong(updatedSong)

                if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                    playbackStateHolder.updateStablePlayerState {
                        it.copy(
                            currentSong = updatedSong,
                            lyrics = result.parsedLyrics
                        )
                    }

                    val controller = playbackStateHolder.mediaController
                    if (controller != null) {
                        val currentIndex = controller.currentMediaItemIndex
                        if (currentIndex >= 0 && currentIndex < controller.mediaItemCount) {
                            val currentPosition = controller.currentPosition
                            val newMediaItem = MediaItemBuilder.build(updatedSong)
                            controller.replaceMediaItem(currentIndex, newMediaItem)
                            controller.seekTo(currentIndex, currentPosition)
                        }
                    }
                }

                if (_selectedSongForInfo.value?.id == song.id) {
                    _selectedSongForInfo.value = updatedSong
                }
            } else {
                failureCount++
            }
        }

        if (coverArtUpdate != null) {
            previousAlbumArts.forEach { previousArt ->
                purgeAlbumArtThemes(previousArt, null)
            }

            val currentSongId = playbackStateHolder.stablePlayerState.value.currentSong?.id
            if (currentSongId != null && songs.any { it.id == currentSongId }) {
                val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
                val paletteTargetUri = currentSong?.albumArtUriString
                if (paletteTargetUri != null) {
                    themeStateHolder.getAlbumColorSchemeFlow(paletteTargetUri)
                    themeStateHolder.extractAndGenerateColorScheme(
                        paletteTargetUri.toUri(),
                        paletteTargetUri,
                        isPreload = false
                    )
                } else {
                    themeStateHolder.extractAndGenerateColorScheme(null, null, isPreload = false)
                }
            }
        }

        multiSelectionStateHolder.clearSelection()

        val message = when {
            failureCount == 0 -> context.getString(R.string.batch_edit_success, successCount)
            successCount == 0 -> context.getString(R.string.batch_edit_failed)
            else -> context.getString(R.string.batch_edit_partial_success, successCount, songs.size)
        }
        _toastEvents.emit(message)
    }

    fun editSongMetadata(
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String,
        newComposer: String,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        newReplayGainTrackGainDb: String? = null,
        newReplayGainAlbumGainDb: String? = null,
        coverArtUpdate: CoverArtUpdate?,
    ) {
        viewModelScope.launch {
            Timber.tag("PlayerViewModel").e("METADATA_EDIT_VM: Starting editSongMetadata via Holder")

            val songId = song.id.toLongOrNull()
            if (songId != null && songId > 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val intentSender = com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper
                    .createWriteRequestForSong(context, songId)
                if (intentSender != null) {
                    pendingMetadataEdit = PendingMetadataEdit(
                        song = song,
                        title = newTitle,
                        artist = newArtist,
                        album = newAlbum,
                        albumArtist = newAlbumArtist,
                        composer = newComposer,
                        genre = newGenre,
                        lyrics = newLyrics,
                        trackNumber = newTrackNumber,
                        discNumber = newDiscNumber,
                        replayGainTrackGainDb = newReplayGainTrackGainDb,
                        replayGainAlbumGainDb = newReplayGainAlbumGainDb,
                        coverArtUpdate = coverArtUpdate
                    )
                    _writePermissionRequest.emit(intentSender)
                    return@launch
                }
            }

            performMetadataEdit(song, newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics,
                newTrackNumber, newDiscNumber, newReplayGainTrackGainDb, newReplayGainAlbumGainDb, coverArtUpdate)
        }
    }

    /** Called from the UI after the user approves or denies the MediaStore write permission. */
    fun onWritePermissionResult(granted: Boolean) {
        val batchMetadata = pendingBatchMetadataEdit
        if (batchMetadata != null) {
            pendingBatchMetadataEdit = null
            if (!granted) {
                viewModelScope.launch {
                    _toastEvents.emit(context.getString(R.string.player_permission_denied_edit_files))
                }
                return
            }
            viewModelScope.launch {
                performBatchMetadataEdit(
                    batchMetadata.songs,
                    batchMetadata.title,
                    batchMetadata.artist,
                    batchMetadata.album,
                    batchMetadata.albumArtist,
                    batchMetadata.composer,
                    batchMetadata.genre,
                    batchMetadata.lyrics,
                    batchMetadata.trackNumber,
                    batchMetadata.discNumber,
                    batchMetadata.replayGainTrackGainDb,
                    batchMetadata.replayGainAlbumGainDb,
                    batchMetadata.coverArtUpdate
                )
            }
            return
        }

        val batchGenre = pendingBatchGenreEdit
        if (batchGenre != null) {
            pendingBatchGenreEdit = null
            if (!granted) {
                viewModelScope.launch {
                    _toastEvents.emit(context.getString(R.string.player_permission_denied_edit_files))
                }
                return
            }
            viewModelScope.launch { performBatchEditGenre(batchGenre.first, batchGenre.second) }
            return
        }

        val pendingLyrics = pendingLyricsSave
        if (pendingLyrics != null) {
            pendingLyricsSave = null
            if (!granted) {
                viewModelScope.launch {
                    _toastEvents.emit(context.getString(R.string.player_permission_denied_save_lyrics))
                }
                return
            }
            performLyricsSave(pendingLyrics.song, pendingLyrics.lyrics, pendingLyrics.preferSynced)
            return
        }

        val pending = pendingMetadataEdit ?: return
        pendingMetadataEdit = null
        if (!granted) {
            viewModelScope.launch {
                _toastEvents.emit(context.getString(R.string.player_permission_denied_edit_this_file))
            }
            return
        }
        viewModelScope.launch {
            performMetadataEdit(
                pending.song, pending.title, pending.artist, pending.album,
                pending.albumArtist, pending.composer, pending.genre, pending.lyrics,
                pending.trackNumber, pending.discNumber,
                pending.replayGainTrackGainDb, pending.replayGainAlbumGainDb, pending.coverArtUpdate
            )
        }
    }

    fun saveLyricsToFile(song: Song, lyrics: Lyrics, preferSynced: Boolean) {
        val lrcContent = LyricsUtils.toLrcString(lyrics, preferSynced)
        if (lrcContent.isEmpty()) {
            viewModelScope.launch { _toastEvents.emit(context.getString(R.string.no_lyrics_to_save)) }
            return
        }

        val songFile = java.io.File(song.path)
        val lrcFile = java.io.File(songFile.parentFile, "${songFile.nameWithoutExtension}.lrc")

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && lrcFile.exists() && !lrcFile.canWrite()) {
            val uri = com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper.getMediaStoreUri(context, lrcFile.absolutePath)
            if (uri != null) {
                val intentSender = com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper.createWriteRequestIntentSender(context, listOf(uri))
                if (intentSender != null) {
                    pendingLyricsSave = PendingLyricsSave(song, lyrics, preferSynced)
                    viewModelScope.launch { _writePermissionRequest.emit(intentSender) }
                    return
                }
            }
        }

        performLyricsSave(song, lyrics, preferSynced)
    }

    private fun performLyricsSave(song: Song, lyrics: Lyrics, preferSynced: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val songFile = java.io.File(song.path)
                val lrcFile = java.io.File(songFile.parentFile, "${songFile.nameWithoutExtension}.lrc")
                val lrcContent = LyricsUtils.toLrcString(lyrics, preferSynced)

                lrcFile.writeText(lrcContent, Charsets.UTF_8)
                _toastEvents.emit(context.getString(R.string.lyrics_saved_successfully))
                
                if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                    loadLyricsForCurrentSong()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save lyrics to file")
                _toastEvents.emit(context.getString(R.string.lyrics_save_failed))
            }
        }
    }

    private suspend fun performMetadataEdit(
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String,
        newComposer: String,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        newReplayGainTrackGainDb: String?,
        newReplayGainAlbumGainDb: String?,
        coverArtUpdate: CoverArtUpdate?,
    ) {
        val previousAlbumArt = song.albumArtUriString

        val result = metadataEditStateHolder.saveMetadata(
            song = song,
            newTitle = newTitle,
            newArtist = newArtist,
            newAlbum = newAlbum,
            newAlbumArtist = newAlbumArtist,
            newComposer = newComposer,
            newGenre = newGenre,
            newLyrics = newLyrics,
            newTrackNumber = newTrackNumber,
            newDiscNumber = newDiscNumber,
            newReplayGainTrackGainDb = newReplayGainTrackGainDb,
            newReplayGainAlbumGainDb = newReplayGainAlbumGainDb,
            coverArtUpdate = coverArtUpdate
        )

        Timber.tag("PlayerViewModel").e("METADATA_EDIT_VM: Result success=${result.success}")

        if (result.success && result.updatedSong != null) {
            val updatedSong = result.updatedSong
            val refreshedAlbumArtUri = result.updatedAlbumArtUri

            invalidateCoverArtCaches(previousAlbumArt, refreshedAlbumArtUri)

            _playerUiState.update { state ->
                val updatedQueue = state.currentPlaybackQueue.replaceSong(updatedSong)
                if (updatedQueue === state.currentPlaybackQueue) {
                    state
                } else {
                    state.copy(currentPlaybackQueue = updatedQueue)
                }
            }

            libraryStateHolder.updateSong(updatedSong)

            if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                playbackStateHolder.updateStablePlayerState {
                    it.copy(
                        currentSong = updatedSong,
                        lyrics = result.parsedLyrics
                    )
                }

                val controller = playbackStateHolder.mediaController
                if (controller != null) {
                    val currentIndex = controller.currentMediaItemIndex
                    if (currentIndex >= 0 && currentIndex < controller.mediaItemCount) {
                        val currentPosition = controller.currentPosition
                        val newMediaItem = MediaItemBuilder.build(updatedSong)
                        controller.replaceMediaItem(currentIndex, newMediaItem)
                        controller.seekTo(currentIndex, currentPosition)
                    }
                }
            }

            if (_selectedSongForInfo.value?.id == song.id) {
                _selectedSongForInfo.value = updatedSong
            }

            if (coverArtUpdate != null) {
                purgeAlbumArtThemes(previousAlbumArt, updatedSong.albumArtUriString)
                val paletteTargetUri = updatedSong.albumArtUriString
                if (paletteTargetUri != null) {
                    themeStateHolder.getAlbumColorSchemeFlow(paletteTargetUri)
                    val currentUri = playbackStateHolder.stablePlayerState.value.currentSong?.albumArtUriString
                    themeStateHolder.extractAndGenerateColorScheme(paletteTargetUri.toUri(), currentUri, isPreload = false)
                } else {
                    val currentUri = playbackStateHolder.stablePlayerState.value.currentSong?.albumArtUriString
                    themeStateHolder.extractAndGenerateColorScheme(null, currentUri, isPreload = false)
                }
            }

            _toastEvents.emit(context.getString(R.string.metadata_updated_successfully))
        } else {
            val errorMessage = result.getUserFriendlyErrorMessage()
            Timber.tag("PlayerViewModel").e("METADATA_EDIT_VM: Failed - ${result.error}: $errorMessage")
            _toastEvents.emit(errorMessage)
        }
    }

    private fun invalidateCoverArtCaches(vararg uriStrings: String?) {
        imageCacheManager.invalidateCoverArtCaches(*uriStrings)
    }

    private suspend fun purgeAlbumArtThemes(vararg uriStrings: String?) {
        val uris = uriStrings.mapNotNull { it?.takeIf(String::isNotBlank) }.distinct()
        if (uris.isEmpty()) return

        withContext(Dispatchers.IO) {
            albumArtThemeDao.deleteThemesByUris(uris)
        }
    }

    suspend fun forceRegenerateAlbumPaletteForSong(song: Song): Boolean {
        val albumArtUri = song.albumArtUriString?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            themeStateHolder.forceRegenerateColorScheme(
                uriString = albumArtUri,
                regenerateAllStyles = true
            )
            true
        }.getOrDefault(false)
    }



    private fun updateSongInStates(
        updatedSong: Song,
        newLyrics: Lyrics? = null,
        isLoadingLyrics: Boolean? = null
    ) {
        val currentQueue = _playerUiState.value.currentPlaybackQueue
        val updatedQueue = currentQueue.replaceSong(updatedSong)

        if (updatedQueue !== currentQueue) {
            _playerUiState.update { it.copy(currentPlaybackQueue = updatedQueue) }
        }

        playbackStateHolder.updateStablePlayerState { state ->
            val finalLyrics = newLyrics ?: state.lyrics
            state.copy(
                currentSong = updatedSong,
                lyrics = if (state.currentSong?.id == updatedSong.id) finalLyrics else state.lyrics,
                isLoadingLyrics = isLoadingLyrics ?: state.isLoadingLyrics
            )
        }
    }

    /**
     * Fetches the lyrics for the current song from the remote service.
     */
    /**
     * Fetches the lyrics for the current song from the remote service.
     */
    fun fetchLyricsForCurrentSong(forcePickResults: Boolean = false) {
        val currentSong = stablePlayerState.value.currentSong ?: return
        lyricsStateHolder.fetchLyricsForSong(currentSong, forcePickResults, lyricsSourcePreference.value) { resId ->
            context.getString(resId)
        }
    }

    /**
     * Manual search lyrics using query provided by user (title and artist)
     */
    fun searchLyricsManually(title: String, artist: String? = null) {
        lyricsStateHolder.searchLyricsManually(title, artist)
    }

    fun acceptLyricsSearchResultForCurrentSong(result: LyricsSearchResult) {
        val currentSong = stablePlayerState.value.currentSong ?: return
        lyricsStateHolder.acceptLyricsSearchResult(result, currentSong)
    }

    fun resetLyricsForCurrentSong() {
        val songId = stablePlayerState.value.currentSong?.id?.toLongOrNull() ?: return
        lyricsStateHolder.resetLyrics(songId)
        playbackStateHolder.updateStablePlayerState { state -> state.copy(lyrics = null) }
    }

    fun resetAllLyrics() {
        lyricsStateHolder.resetAllLyrics()
        playbackStateHolder.updateStablePlayerState { state -> state.copy(lyrics = null) }
    }

    /**
     * Processes the lyrics imported from a file, saves them, and updates the UI.
     * @param songId The ID of the song the lyrics are being imported for.
     * @param lyricsContent The lyrics content as a String.
     */
    fun importLyricsFromFile(songId: Long, validatedImport: ValidatedLyricsImport) {
        val currentSong = stablePlayerState.value.currentSong
        lyricsStateHolder.importLyricsFromFile(songId, validatedImport, currentSong)
    }



    /**
     * Resets the lyrics search state to Idle.
     */
    fun resetLyricsSearchState() {
        lyricsStateHolder.resetSearchState()
    }

    private fun onBlockedDirectoriesChanged() {
        viewModelScope.launch {
            musicRepository.invalidateCachesDependentOnAllowedDirectories()
            resetAndLoadInitialData("Blocked directories changed")
        }
    }

    fun playSong(song: Song) {
        viewModelScope.launch {
            val controller = mediaController ?: return@launch
            val mediaItem = buildResolvedPlaybackMediaItem(song)

            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()

            _isSheetVisible.value = true
            _sheetState.value = PlayerSheetState.EXPANDED
        }
    }

    fun prepareBenchmarkPlayerFromLibrary() {
        viewModelScope.launch {
            repeat(90) { attempt ->
                val controllerReady = mediaController != null
                val songs = withContext(Dispatchers.IO) {
                    musicRepository.getAllSongsOnce()
                }
                Timber.tag("PixelPlayerBenchmark").i(
                    "prepare player attempt=$attempt controllerReady=$controllerReady songs=${songs.size}"
                )
                if (controllerReady && songs.isNotEmpty()) {
                    playSongs(songs, songs.first(), "Benchmark Player")
                    delay(700L)
                    collapsePlayerSheet()
                    Timber.tag("PixelPlayerBenchmark").i("Benchmark player prepared with ${songs.first().title}")
                    return@launch
                }
                delay(500L)
            }
            Timber.tag("PixelPlayerBenchmark").w("Unable to prepare benchmark player from library")
        }
    }

    private var pendingBatchGenreEdit: Pair<List<Song>, String>? = null

    fun batchEditGenre(songs: List<Song>, newGenre: String) {
        if (songs.isEmpty()) return

        viewModelScope.launch {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val uris = songs.mapNotNull { song ->
                    song.id.toLongOrNull()?.takeIf { it > 0 }?.let { id ->
                        com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper.getMediaStoreUri(context, id)
                    }
                }
                if (uris.isNotEmpty()) {
                    val intentSender = com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper
                        .createWriteRequestIntentSender(context, uris)
                    if (intentSender != null) {
                        pendingBatchGenreEdit = songs to newGenre
                        _writePermissionRequest.emit(intentSender)
                        return@launch
                    }
                }
            }

            performBatchEditGenre(songs, newGenre)
        }
    }

    private suspend fun performBatchEditGenre(songs: List<Song>, newGenre: String) {
            Timber.tag("PlayerViewModel").d("Starting batch genre update for ${songs.size} songs to '$newGenre'")
            _toastEvents.emit(context.getString(R.string.player_updating_n_songs, songs.size))

            var successCount = 0
            var failCount = 0

            songs.forEach { song ->
                val sourceSong = if (song.lyrics != null) {
                    song
                } else {
                    withContext(Dispatchers.IO) {
                        musicRepository.getSong(song.id).first()
                    } ?: song
                }

                val result = metadataEditStateHolder.saveMetadata(
                    song = sourceSong,
                    newTitle = sourceSong.title,
                    newArtist = sourceSong.artist,
                    newAlbum = sourceSong.album,
                    newAlbumArtist = sourceSong.albumArtist ?: "",
                    newComposer = "",
                    newGenre = newGenre,
                    newLyrics = sourceSong.lyrics ?: "",
                    newTrackNumber = sourceSong.trackNumber,
                    newDiscNumber =  sourceSong.discNumber,
                    coverArtUpdate = null
                )

                if (result.success && result.updatedSong != null) {
                    successCount++
                    val updatedSong = result.updatedSong

                    libraryStateHolder.updateSong(updatedSong)

                    if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                        playbackStateHolder.updateStablePlayerState { it.copy(currentSong = updatedSong) }
                        val controller = playbackStateHolder.mediaController
                        if (controller != null) {
                            val idx = controller.currentMediaItemIndex
                            if (idx != C.INDEX_UNSET) {
                                controller.replaceMediaItem(idx, MediaItemBuilder.build(updatedSong))
                            }
                        }
                    }
                } else {
                    failCount++
                }
            }

            if (failCount == 0) {
                _toastEvents.emit(context.getString(R.string.player_batch_genre_updated_all, successCount))
            } else {
                _toastEvents.emit(
                    context.getString(R.string.player_batch_genre_updated_partial, successCount, failCount),
                )
            }
    }

    val customGenres: StateFlow<Set<String>> = userPreferencesRepository.customGenresFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    val customGenreIcons: StateFlow<Map<String, Int>> = userPreferencesRepository.customGenreIconsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val isGenreGridView: StateFlow<Boolean> = userPreferencesRepository.isGenreGridViewFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    fun toggleGenreViewMode() {
        viewModelScope.launch {
            userPreferencesRepository.setGenreGridView(!isGenreGridView.value)
        }
    }

    fun addCustomGenre(genre: String, iconResId: Int? = null) {
        viewModelScope.launch {
            userPreferencesRepository.addCustomGenre(genre, iconResId)
        }
    }
}

internal fun Song.withRepositoryHydration(repositorySong: Song): Song {
    if (id != repositorySong.id) return this

    val hydratedArtworkUri = when {
        repositorySong.albumArtUriString.isNullOrBlank() -> albumArtUriString
        albumArtUriString.isNullOrBlank() -> repositorySong.albumArtUriString
        areEquivalentArtworkUrisForSong(id, albumArtUriString, repositorySong.albumArtUriString) ->
            albumArtUriString
        else -> repositorySong.albumArtUriString
    }

    return repositorySong.copy(
        contentUriString = repositorySong.contentUriString.ifBlank { contentUriString },
        albumArtUriString = hydratedArtworkUri,
        duration = repositorySong.duration.takeIf { it > 0L } ?: duration,
        lyrics = repositorySong.lyrics ?: lyrics
    )
}

internal fun areEquivalentArtworkUrisForSong(
    songId: String,
    firstUri: String?,
    secondUri: String?
): Boolean {
    if (firstUri == secondUri) return true
    if (firstUri.isNullOrBlank() || secondUri.isNullOrBlank()) return false

    val targetSongId = songId.toLongOrNull() ?: return false

    fun resolveUriSongId(uri: String): Long? {
        return LocalArtworkUri.parseSongId(uri)
            ?: SharedArtworkContentProvider.parseSongId(uri)
    }

    val firstSongId = resolveUriSongId(firstUri)
    val secondSongId = resolveUriSongId(secondUri)
    return firstSongId == targetSongId && secondSongId == targetSongId
}

internal fun Song.improvesLyricsLookupComparedTo(previousSong: Song): Boolean {
    return (previousSong.lyrics.isNullOrBlank() && !lyrics.isNullOrBlank()) ||
        (previousSong.path.isBlank() && path.isNotBlank()) ||
        (previousSong.contentUriString.isBlank() && contentUriString.isNotBlank())
}

internal fun parsePersistedLyrics(rawLyrics: String?): Lyrics? {
    val normalizedLyrics = rawLyrics?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val parsedLyrics = LyricsUtils.parseLyrics(normalizedLyrics)
    return parsedLyrics.takeIf {
        !it.synced.isNullOrEmpty() || !it.plain.isNullOrEmpty()
    }
}
