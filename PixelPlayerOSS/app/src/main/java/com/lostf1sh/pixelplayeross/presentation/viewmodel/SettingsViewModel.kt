package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.intelligence.TrackIntelligenceDiagnostic
import com.lostf1sh.pixelplayeross.data.intelligence.TrackIntelligenceDiagnosticProvider
import com.lostf1sh.pixelplayeross.data.metadata.MetadataDiagnostic
import com.lostf1sh.pixelplayeross.data.collections.CollectionDiagnostic
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionRepository
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.data.preferences.DEFAULT_MUSIC_INFO_CACHE_MB
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.theme.CoverThemeChoice
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.preferences.AppLanguage
import com.lostf1sh.pixelplayeross.presentation.settings.AppearanceSettingsState
import com.lostf1sh.pixelplayeross.presentation.settings.BehaviorSettingsState
import com.lostf1sh.pixelplayeross.presentation.settings.LibrarySettingsState
import com.lostf1sh.pixelplayeross.presentation.settings.libraryStateFlow
import com.lostf1sh.pixelplayeross.presentation.settings.LyricsSettingsState
import com.lostf1sh.pixelplayeross.data.preferences.AppThemeMode
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.DEFAULT_COVER_GLASS_MOVING
import com.lostf1sh.pixelplayeross.data.preferences.DEFAULT_DAC_SHARE_ENABLED
import com.lostf1sh.pixelplayeross.data.preferences.DEFAULT_LYRICS_MODE
import com.lostf1sh.pixelplayeross.data.preferences.LyricsMode
import com.lostf1sh.pixelplayeross.data.preferences.DEFAULT_DAP_LOCK_SCREEN_ENABLED
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.DEFAULT_SHOW_QUEUE_HISTORY
import com.lostf1sh.pixelplayeross.data.preferences.LYRICS_ALIGNMENT_DEFAULT
import com.lostf1sh.pixelplayeross.data.preferences.LibraryNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.RandomMode
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreference
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtColorAccuracy
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtPaletteStyle
import com.lostf1sh.pixelplayeross.data.preferences.CollagePattern
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.worker.SyncManager
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import timber.log.Timber

import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.NavBarStyle
import com.lostf1sh.pixelplayeross.data.preferences.LaunchTab
import com.lostf1sh.pixelplayeross.utils.AppLocaleManager
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.diagnostics.DeviceDiagnosticSnapshot
import com.lostf1sh.pixelplayeross.data.diagnostics.DeviceDiagnosticsController
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverride
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrides
import java.io.File

data class SettingsUiState(
    val isLoadingDirectories: Boolean = false,
    val appLanguageTag: String = AppLanguage.SYSTEM.tag,
    val appThemeMode: String = AppThemeMode.FOLLOW_SYSTEM,
    val playerThemePreference: String = ThemePreference.ALBUM_ART,
    val albumArtPaletteStyle: AlbumArtPaletteStyle = AlbumArtPaletteStyle.default,
    val albumArtColorAccuracy: Int = AlbumArtColorAccuracy.DEFAULT,
    val mockGenresEnabled: Boolean = false,
    val navBarCornerRadius: Int = 32,
    val navBarStyle: String = NavBarStyle.DEFAULT,
    val navBarCompactMode: Boolean = false,
    val carouselStyle: String = CarouselStyle.NO_PEEK,
    val libraryNavigationMode: String = LibraryNavigationMode.TAB_ROW,
    val launchTab: String = LaunchTab.HOME,
    val keepPlayingInBackground: Boolean = true,
    val resumeOnHeadsetReconnect: Boolean = false,
    val showQueueHistory: Boolean = DEFAULT_SHOW_QUEUE_HISTORY,
    val isCrossfadeEnabled: Boolean = false,
    val smartCrossfadeEnabled: Boolean = false,
    val persistentShuffleEnabled: Boolean = false,
    /** What switching shuffle on does to the order; defaulted from the mode's own default so the row cannot show a value the store does not hold. */
    val randomMode: RandomMode = RandomMode.DEFAULT,
    val folderBackGestureNavigation: Boolean = true,
    /**
     * App-level Back navigation. Defaulted from the mode's own default rather than duplicated here,
     * so the switch cannot render a value the store does not hold.
     */
    val backNavigationMode: BackNavigationMode = BackNavigationMode.DEFAULT,
    val lyricsSourcePreference: LyricsSourcePreference = LyricsSourcePreference.LOCAL_FIRST,
    val autoScanLrcFiles: Boolean = true,
    val externalLyricsEnabled: Boolean = false,
    /** B40-F4: the three music-info switches (off by default), the limit of what is kept, and how much is kept now. */
    val musicInfoCoversEnabled: Boolean = false,
    val musicInfoArtistPhotosEnabled: Boolean = false,
    val musicInfoDescriptionsEnabled: Boolean = false,
    val musicInfoCacheLimitMb: Int = DEFAULT_MUSIC_INFO_CACHE_MB,
    val musicInfoUsedBytes: Long = 0L,
    val blockedDirectories: Set<String> = emptySet(),
    val appRebrandDialogShown: Boolean = false,
    val fullPlayerLoadingTweaks: FullPlayerLoadingTweaks = FullPlayerLoadingTweaks(),
    val showBookmarkButton: Boolean = true,
    val repeatSharedSongsInShuffle: Boolean = false,
    val showPlayerFileInfo: Boolean = true,
    /** Curated DAP accent. Defaults to the appearance every existing install already has. */
    val accent: DapAccent = DapAccent.DEFAULT,
    /** Curated base appearance. Defaults to the one every existing install already has. */
    val baseTheme: DapBaseTheme = DapBaseTheme.DEFAULT,
    /** B40-F1: where the app's colours come from ([AppColorSource]). */
    val appColorSource: String = AppColorSource.THEME,
    /** B41-fix1 (B1): light, dark or system while the cover decides the colours ([CoverThemeChoice]). */
    val coverThemeChoice: String = CoverThemeChoice.DARK,
    /** B41: the cover glass drifts in the full player and on the lock screen. */
    val coverGlassMoving: Boolean = DEFAULT_COVER_GLASS_MOVING,
    /** B56-3.4: how the lyrics show in the full player (over the cover, full page, off). */
    val lyricsMode: LyricsMode = DEFAULT_LYRICS_MODE,
    /** B56-7.1: "Background motion" is Lively (with [coverGlassMoving] on) instead of Gentle. */
    val coverGlassLively: Boolean = false,
    /** B56-7.2: "Animated covers" as the listener set it, or null while they have not (the mode's default stands: see `AnimatedCovers.effective`). */
    val animatedCoversStored: Boolean? = null,
    /** B57-fix1 4: the folder the animated covers are read through (Android 13+): its name, or null while there is none. */
    val animatedCoversFolder: String? = null,
    /** Whether waking the dedicated player raises the DAP lock surface. Off unless asked for. */
    val dapLockScreenEnabled: Boolean = DEFAULT_DAP_LOCK_SCREEN_ENABLED,
    /** B39-A7: Resonance shares the DAC with other apps (the Audio page's switch). */
    val dacShareEnabled: Boolean = DEFAULT_DAC_SHARE_ENABLED,
    /** B40-F8: Resonance's peak limiter on the Android route. */
    val resonanceLimiterEnabled: Boolean = false,
    val albumArtQuality: AlbumArtQuality = AlbumArtQuality.MEDIUM,
    val albumArtCacheLimitMb: Int = 200,
    val folderCoversEnabled: Boolean = true,
    val tapBackgroundClosesPlayer: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val immersiveLyricsEnabled: Boolean = false,
    val immersiveLyricsTimeout: Long = 4000L,
    val lyricsAlignment: String = LYRICS_ALIGNMENT_DEFAULT,
    val showLyricsTranslation: Boolean = true,
    val showLyricsRomanization: Boolean = true,
    val useAnimatedLyrics: Boolean = false,
    val animatedLyricsBlurEnabled: Boolean = true,
    val animatedLyricsBlurStrength: Float = 2.5f,
    val collagePattern: CollagePattern = CollagePattern.default,
    val collageAutoRotate: Boolean = false,
    val minSongDuration: Int = 10000,
    val minTracksPerAlbum: Int = 1,
    val replayGainEnabled: Boolean = false,
    val replayGainUseAlbumGain: Boolean = false
)

data class FailedSongInfo(
    val id: String,
    val title: String,
    val artist: String
)


private sealed interface SettingsUiUpdate {
    data class Group1(
        val appRebrandDialogShown: Boolean,
        val appThemeMode: String,
        val playerThemePreference: String,
        val albumArtPaletteStyle: AlbumArtPaletteStyle,
        val albumArtColorAccuracy: Int,
        val mockGenresEnabled: Boolean,
        val navBarCornerRadius: Int,
        val navBarStyle: String,
        val navBarCompactMode: Boolean,
        val libraryNavigationMode: String,
        val carouselStyle: String,
        val launchTab: String,
        val showPlayerFileInfo: Boolean,
        val accent: DapAccent,
        val baseTheme: DapBaseTheme,
        val dapLockScreenEnabled: Boolean,
        val appColorSource: String,
        val coverGlassMoving: Boolean,
        val lyricsMode: LyricsMode,
        val coverThemeChoice: String,
    ) : SettingsUiUpdate
    
    data class Group2(
        val keepPlayingInBackground: Boolean,
        val resumeOnHeadsetReconnect: Boolean,
        val showQueueHistory: Boolean,
        val isCrossfadeEnabled: Boolean,
        val persistentShuffleEnabled: Boolean,
        val folderBackGestureNavigation: Boolean,
        val lyricsSourcePreference: LyricsSourcePreference,
        val autoScanLrcFiles: Boolean,
        val blockedDirectories: Set<String>,
        val hapticsEnabled: Boolean,
        val immersiveLyricsEnabled: Boolean,
        val immersiveLyricsTimeout: Long,
        val animatedLyricsBlurEnabled: Boolean,
        val animatedLyricsBlurStrength: Float,
        val backNavigationMode: BackNavigationMode,
        val randomMode: RandomMode
    ) : SettingsUiUpdate
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val colorSchemeProcessor: ColorSchemeProcessor,
    private val syncManager: SyncManager,
    private val lyricsRepository: LyricsRepository,
    private val musicRepository: MusicRepository,
    private val dualPlayerEngine: DualPlayerEngine,
    private val trackIntelligenceDiagnosticProvider: TrackIntelligenceDiagnosticProvider,
    private val smartCollectionRepository: SmartCollectionRepository,
    private val deviceDiagnosticsController: DeviceDiagnosticsController,
    private val maintenanceOverrideStore: MaintenanceOverrideStore,
    audioStatusSource: AudioStatusSource,
    private val dormantItemCleanup: com.lostf1sh.pixelplayeross.data.identity.DormantItemCleanup,
    private val libraryFolders: com.lostf1sh.pixelplayeross.data.library.LibraryFolderService,
    private val musicInfo: com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository,
    private val musicInfoDownloads: com.lostf1sh.pixelplayeross.data.offline.MusicInfoDownloads,
    private val animatedCoverFolderStore: com.lostf1sh.pixelplayeross.data.preferences.AnimatedCoverFolderStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    /** Engineering diagnostics consumed only by authenticated Maintenance. */
    val usbAudioDiagnostics = dualPlayerEngine.usbAudioDiagnostics
    val audioRouteState = dualPlayerEngine.audioRouteState

    fun refreshUsbAudioDiagnostics() = dualPlayerEngine.refreshUsbAudioDiagnostics()

    /** B39-A8: the Audio page's "Allow USB access": asks again for a DAC whose permission was denied or never given. */
    fun askForUsbPermissionAgain() = dualPlayerEngine.askForUsbPermissionAgain()

    /**
     * The read-only hardware picture for the Maintenance device diagnostic. Started here rather
     * than at application start because it exists to answer a question asked in Maintenance, and
     * registering its battery receiver only when that surface exists keeps the idle path free of
     * work the appliance would otherwise do forever.
     */
    val deviceDiagnostics: StateFlow<DeviceDiagnosticSnapshot>
        get() {
            deviceDiagnosticsController.start()
            return deviceDiagnosticsController.snapshot
        }

    fun refreshDeviceDiagnostics() {
        deviceDiagnosticsController.start()
        deviceDiagnosticsController.refresh()
    }

    /** Active Maintenance overrides, observed so the panel reflects a change immediately. */
    val maintenanceOverrides: StateFlow<MaintenanceOverrides> = maintenanceOverrideStore.state

    fun setMaintenanceOverride(override: MaintenanceOverride, active: Boolean) {
        maintenanceOverrideStore.setActive(override, active)
        deviceDiagnosticsController.refresh()
    }

    fun clearAllMaintenanceOverrides() {
        maintenanceOverrideStore.clearAll()
        deviceDiagnosticsController.refresh()
    }

    /**
     * The Maintenance identity/engagement diagnostic for the current track.
     *
     * Read-only by construction: [TrackIntelligenceDiagnosticProvider] issues only `SELECT`s, and
     * nothing in playback or recommendation consults this. It lives here rather than in a screen
     * because it needs the database and the listening session, and it is refreshed on demand rather
     * than observed so that opening Maintenance never puts a query on a playback hot path.
     */
    private val _trackDiagnostic = MutableStateFlow<TrackIntelligenceDiagnostic?>(null)
    val trackDiagnostic: StateFlow<TrackIntelligenceDiagnostic?> = _trackDiagnostic.asStateFlow()

    fun refreshTrackDiagnostic() {
        viewModelScope.launch {
            _trackDiagnostic.value = runCatching {
                trackIntelligenceDiagnosticProvider.forCurrentTrack()
            }.getOrNull()
            _metadataDiagnostic.value = runCatching {
                _trackDiagnostic.value
                    ?.takeIf { it.libraryItemId.isNotEmpty() }
                    ?.let { trackIntelligenceDiagnosticProvider.metadataFor(it.libraryItemId) }
            }.getOrNull()
            _collectionDiagnostic.value = runCatching {
                _trackDiagnostic.value
                    ?.takeIf { it.libraryItemId.isNotEmpty() }
                    ?.let { smartCollectionRepository.diagnose(it.libraryItemId) }
            }.getOrNull()
        }
    }

    /** What the app believes about the current track's metadata. Read-only and Maintenance-only. */
    private val _metadataDiagnostic = MutableStateFlow<MetadataDiagnostic?>(null)
    val metadataDiagnostic: StateFlow<MetadataDiagnostic?> = _metadataDiagnostic.asStateFlow()

    /**
     * Why the current track is, or is not, in each saved collection.
     *
     * Same guarantees as the diagnostics above: read-only, on demand, and answered
     * by the engine that actually decides membership rather than by a second implementation here.
     */
    private val _collectionDiagnostic = MutableStateFlow<CollectionDiagnostic?>(null)
    val collectionDiagnostic: StateFlow<CollectionDiagnostic?> = _collectionDiagnostic.asStateFlow()

    private val fileExplorerStateHolder = FileExplorerStateHolder(userPreferencesRepository, viewModelScope, context)

    val currentPath = fileExplorerStateHolder.currentPath
    val currentDirectoryChildren = fileExplorerStateHolder.currentDirectoryChildren
    val blockedDirectories = fileExplorerStateHolder.blockedDirectories
    val availableStorages = fileExplorerStateHolder.availableStorages
    val selectedStorageIndex = fileExplorerStateHolder.selectedStorageIndex
    val isLoadingDirectories = fileExplorerStateHolder.isLoading
    val isExplorerPriming = fileExplorerStateHolder.isPrimingExplorer
    val isExplorerReady = fileExplorerStateHolder.isExplorerReady
    val isCurrentDirectoryResolved = fileExplorerStateHolder.isCurrentDirectoryResolved
    private var hasPendingDirectoryRuleChanges = false
    private var latestDirectoryRuleUpdateJob: Job? = null

    // B39-A1: held only while listened to: with nobody listening for a while they go back to "idle" instead of keeping the last thing they
    // were told, which `refreshLibrary` and `fullSyncLibrary` below read as "a sync is running" and would silently do nothing.
    private val whileListened = SharingStarted.WhileSubscribed(SyncManager.SYNC_STOP_TIMEOUT_MS, replayExpirationMillis = 0)

    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing
        .stateIn(
            scope = viewModelScope,
            started = whileListened,
            initialValue = false
        )

    val syncProgress: StateFlow<SyncProgress> = syncManager.syncProgress
        .stateIn(
            scope = viewModelScope,
            started = whileListened,
            initialValue = SyncProgress()
        )

    // ── B21: one state per page ───────────────────────────────────────────────────────────────────────
    //
    // Each page draws a state built from this one's preferences, and only when *its* slice changed
    // (`distinctUntilChanged`): a haptics switch no longer re-emits what the Lyrics page reads. The Audio
    // page also needs the DAC and headphone lists and [AudioStatus], which other owners publish, so the host
    // joins those with `AudioSettingsState.from`; the Device and Developer pages are drawn from theirs.
    private fun <T> derived(build: (SettingsUiState) -> T): StateFlow<T> =
        _uiState.map(build).distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), build(_uiState.value))

    val appearanceState: StateFlow<AppearanceSettingsState> = derived(AppearanceSettingsState::from)
    val behaviorState: StateFlow<BehaviorSettingsState> = derived(BehaviorSettingsState::from)
    val lyricsState: StateFlow<LyricsSettingsState> = derived(LyricsSettingsState::from)
    val libraryState: StateFlow<LibrarySettingsState> = libraryStateFlow(_uiState, syncManager.syncState, viewModelScope, _uiState.value)

    /** What is applied right now (B21). The Audio page's [AudioSettingsState.audio]. */
    val audioStatus: StateFlow<AudioStatus> = audioStatusSource.status

    init {
        viewModelScope.launch {
            userPreferencesRepository.dacShareEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(dacShareEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.resonanceLimiterEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(resonanceLimiterEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.collagePatternFlow.collect { pattern ->
                _uiState.update { it.copy(collagePattern = pattern) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.collageAutoRotateFlow.collect { autoRotate ->
                _uiState.update { it.copy(collageAutoRotate = autoRotate) }
            }
        }

    }

    init {
        _uiState.update {
            it.copy(
                appLanguageTag = AppLocaleManager.currentLanguageTag(context)
            )
        }

        viewModelScope.launch {
            themePreferencesRepository.coverGlassLivelyFlow.collect { lively -> _uiState.update { it.copy(coverGlassLively = lively) } }
        }
        viewModelScope.launch {
            themePreferencesRepository.animatedCoversStoredFlow.collect { stored -> _uiState.update { it.copy(animatedCoversStored = stored) } }
        }
        viewModelScope.launch {
            animatedCoverFolderStore.folder.collect { tree -> _uiState.update { it.copy(animatedCoversFolder = tree?.let(animatedCoverFolderStore::nameOf)) } }
        }
        viewModelScope.launch {
            userPreferencesRepository.repeatSharedSongsInShuffleFlow.collect { repeat ->
                _uiState.update { it.copy(repeatSharedSongsInShuffle = repeat) }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.showBookmarkButtonFlow.collect { show ->
                _uiState.update { it.copy(showBookmarkButton = show) }
            }
        }
        viewModelScope.launch {
            combine<Any?, SettingsUiUpdate.Group1>(
                userPreferencesRepository.appRebrandDialogShownFlow,
                themePreferencesRepository.appThemeModeFlow,
                themePreferencesRepository.playerThemePreferenceFlow,
                themePreferencesRepository.albumArtPaletteStyleFlow,
                themePreferencesRepository.albumArtColorAccuracyFlow,
                userPreferencesRepository.mockGenresEnabledFlow,
                userPreferencesRepository.navBarCornerRadiusFlow,
                userPreferencesRepository.navBarStyleFlow,
                userPreferencesRepository.navBarCompactModeFlow,
                userPreferencesRepository.libraryNavigationModeFlow,
                userPreferencesRepository.carouselStyleFlow,
                userPreferencesRepository.launchTabFlow,
                userPreferencesRepository.showPlayerFileInfoFlow,
                themePreferencesRepository.accentFlow,
                themePreferencesRepository.baseThemeFlow,
                userPreferencesRepository.dapLockScreenEnabledFlow,
                themePreferencesRepository.appColorSourceFlow,
                // Appended: the indices are literals.
                themePreferencesRepository.coverGlassMovingFlow,
                userPreferencesRepository.lyricsModeFlow,
                themePreferencesRepository.coverThemeChoiceFlow,
            ) { values ->
                SettingsUiUpdate.Group1(
                    appRebrandDialogShown = values[0] as Boolean,
                    appThemeMode = values[1] as String,
                    playerThemePreference = values[2] as String,
                    albumArtPaletteStyle = values[3] as AlbumArtPaletteStyle,
                    albumArtColorAccuracy = values[4] as Int,
                    mockGenresEnabled = values[5] as Boolean,
                    navBarCornerRadius = values[6] as Int,
                    navBarStyle = values[7] as String,
                    navBarCompactMode = values[8] as Boolean,
                    libraryNavigationMode = values[9] as String,
                    carouselStyle = values[10] as String,
                    launchTab = values[11] as String,
                    showPlayerFileInfo = values[12] as Boolean,
                    accent = values[13] as DapAccent,
                    baseTheme = values[14] as DapBaseTheme,
                    dapLockScreenEnabled = values[15] as Boolean,
                    appColorSource = values[16] as String,
                    coverGlassMoving = values[17] as Boolean,
                    lyricsMode = values[18] as LyricsMode,
                    coverThemeChoice = values[19] as String,
                )
            }.collect { update ->
                _uiState.update { state ->
                    state.copy(
                        appRebrandDialogShown = update.appRebrandDialogShown,
                        appThemeMode = update.appThemeMode,
                        playerThemePreference = update.playerThemePreference,
                        albumArtPaletteStyle = update.albumArtPaletteStyle,
                        albumArtColorAccuracy = update.albumArtColorAccuracy,
                        mockGenresEnabled = update.mockGenresEnabled,
                        navBarCornerRadius = update.navBarCornerRadius,
                        navBarStyle = update.navBarStyle,
                        navBarCompactMode = update.navBarCompactMode,
                        libraryNavigationMode = update.libraryNavigationMode,
                        carouselStyle = update.carouselStyle,
                        launchTab = update.launchTab,
                        showPlayerFileInfo = update.showPlayerFileInfo,
                        accent = update.accent,
                        baseTheme = update.baseTheme,
                        dapLockScreenEnabled = update.dapLockScreenEnabled,
                        appColorSource = update.appColorSource,
                        coverGlassMoving = update.coverGlassMoving,
                        lyricsMode = update.lyricsMode,
                        coverThemeChoice = update.coverThemeChoice,
                    )
                }
            }
        }
        
        viewModelScope.launch {
            combine<Any?, SettingsUiUpdate.Group2>(
                userPreferencesRepository.keepPlayingInBackgroundFlow,
                userPreferencesRepository.resumeOnHeadsetReconnectFlow,
                userPreferencesRepository.showQueueHistoryFlow,
                userPreferencesRepository.isCrossfadeEnabledFlow,
                userPreferencesRepository.persistentShuffleEnabledFlow,
                userPreferencesRepository.folderBackGestureNavigationFlow,
                userPreferencesRepository.lyricsSourcePreferenceFlow,
                userPreferencesRepository.autoScanLrcFilesFlow,
                userPreferencesRepository.blockedDirectoriesFlow,
                userPreferencesRepository.hapticsEnabledFlow,
                userPreferencesRepository.immersiveLyricsEnabledFlow,
                userPreferencesRepository.immersiveLyricsTimeoutFlow,
                userPreferencesRepository.animatedLyricsBlurEnabledFlow,
                userPreferencesRepository.animatedLyricsBlurStrengthFlow,
                // Appended rather than inserted: the indices below are literals and every existing
                // one of them has to keep pointing at the flow it points at today.
                userPreferencesRepository.backNavigationModeFlow,
                userPreferencesRepository.randomModeFlow
            ) { values ->
                // Positional by necessity: `combine` has typed overloads only up to five flows,
                // so this reads an Array. Every index is a literal next to the flow it reads,
                // in the same order, and each cast is a distinct type so a shift fails loudly on
                // the first emission rather than silently.
                SettingsUiUpdate.Group2(
                    keepPlayingInBackground = values[0] as Boolean,
                    resumeOnHeadsetReconnect = values[1] as Boolean,
                    showQueueHistory = values[2] as Boolean,
                    isCrossfadeEnabled = values[3] as Boolean,
                    persistentShuffleEnabled = values[4] as Boolean,
                    folderBackGestureNavigation = values[5] as Boolean,
                    lyricsSourcePreference = values[6] as LyricsSourcePreference,
                    autoScanLrcFiles = values[7] as Boolean,
                    blockedDirectories = @Suppress("UNCHECKED_CAST") (values[8] as Set<String>),
                    hapticsEnabled = values[9] as Boolean,
                    immersiveLyricsEnabled = values[10] as Boolean,
                    immersiveLyricsTimeout = values[11] as Long,
                    animatedLyricsBlurEnabled = values[12] as Boolean,
                    animatedLyricsBlurStrength = values[13] as Float,
                    backNavigationMode = values[14] as BackNavigationMode,
                    randomMode = values[15] as RandomMode
                )
            }.collect { update ->
                _uiState.update { state ->
                    state.copy(
                        keepPlayingInBackground = update.keepPlayingInBackground,
                        resumeOnHeadsetReconnect = update.resumeOnHeadsetReconnect,
                        showQueueHistory = update.showQueueHistory,
                        isCrossfadeEnabled = update.isCrossfadeEnabled,
                        persistentShuffleEnabled = update.persistentShuffleEnabled,
                        folderBackGestureNavigation = update.folderBackGestureNavigation,
                        lyricsSourcePreference = update.lyricsSourcePreference,
                        autoScanLrcFiles = update.autoScanLrcFiles,
                        blockedDirectories = update.blockedDirectories,
                        hapticsEnabled = update.hapticsEnabled,
                        immersiveLyricsEnabled = update.immersiveLyricsEnabled,
                        immersiveLyricsTimeout = update.immersiveLyricsTimeout,
                        animatedLyricsBlurEnabled = update.animatedLyricsBlurEnabled,
                        animatedLyricsBlurStrength = update.animatedLyricsBlurStrength,
                        backNavigationMode = update.backNavigationMode,
                        randomMode = update.randomMode
                    )
                }
            }
        }
        
        viewModelScope.launch {
            userPreferencesRepository.smartCrossfadeEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(smartCrossfadeEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.fullPlayerLoadingTweaksFlow.collect { tweaks ->
                _uiState.update { it.copy(fullPlayerLoadingTweaks = tweaks) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.lyricsAlignmentFlow.collect { alignment ->
                _uiState.update { it.copy(lyricsAlignment = alignment) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.showLyricsTranslationFlow.collect { show ->
                _uiState.update { it.copy(showLyricsTranslation = show) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.showLyricsRomanizationFlow.collect { show ->
                _uiState.update { it.copy(showLyricsRomanization = show) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.useAnimatedLyricsFlow.collect { enabled ->
                _uiState.update { it.copy(useAnimatedLyrics = enabled) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.externalLyricsEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(externalLyricsEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.folderCoversEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(folderCoversEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.musicInfoCoversEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(musicInfoCoversEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.musicInfoArtistPhotosEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(musicInfoArtistPhotosEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.musicInfoDescriptionsEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(musicInfoDescriptionsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.musicInfoCacheLimitMbFlow.collect { mb ->
                _uiState.update { it.copy(musicInfoCacheLimitMb = mb) }
            }
        }
        // how much is kept: read when the page is opened and whenever a cover arrives or the data is deleted
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.flow.combine(musicInfo.coverVersion, musicInfoDownloads.dao.downloadProgress()) { _, _ -> Unit }.collect {
                val used = musicInfoDownloads.downloadedBytes()
                _uiState.update { it.copy(musicInfoUsedBytes = used) }
            }
        }

        viewModelScope.launch {
            fileExplorerStateHolder.isLoading.collect { loading ->
                _uiState.update { it.copy(isLoadingDirectories = loading) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.albumArtQualityFlow.collect { quality ->
                _uiState.update { it.copy(albumArtQuality = quality) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.albumArtCacheLimitMbFlow.collect { limitMb ->
                _uiState.update { it.copy(albumArtCacheLimitMb = limitMb) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.tapBackgroundClosesPlayerFlow.collect { enabled ->
                _uiState.update { it.copy(tapBackgroundClosesPlayer = enabled) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.minSongDurationFlow.collect { duration ->
                _uiState.update { it.copy(minSongDuration = duration) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.minTracksPerAlbumFlow.collect { minTracks ->
                _uiState.update { it.copy(minTracksPerAlbum = minTracks) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.replayGainEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(replayGainEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.replayGainUseAlbumGainFlow.collect { useAlbum ->
                _uiState.update { it.copy(replayGainUseAlbumGain = useAlbum) }
            }
        }
    }

    fun setAppRebrandDialogShown(wasShown: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAppRebrandDialogShown(wasShown)
        }
    }

    fun toggleDirectoryAllowed(file: File) {
        hasPendingDirectoryRuleChanges = true
        latestDirectoryRuleUpdateJob = viewModelScope.launch {
            fileExplorerStateHolder.toggleDirectoryAllowed(file)
        }
    }

    private val _folderProposal = MutableStateFlow<FolderProposalState?>(null)

    /** B39-A9: the folders "Find folders with music" found, checked, waiting for the listener's answer. Null when nothing is being offered. */
    val folderProposal: StateFlow<FolderProposalState?> = _folderProposal.asStateFlow()

    private val _folderSearchNotices = MutableSharedFlow<FolderSearchNotice>(extraBufferCapacity = 4)
    val folderSearchNotices: SharedFlow<FolderSearchNotice> = _folderSearchNotices.asSharedFlow()

    private var folderSearchJob: Job? = null

    /** B39-A9: the same scan as the first run, at any time: the folders that hold music and are not in the library, offered checked. */
    fun findFolders() {
        if (folderSearchJob?.isActive == true) return
        folderSearchJob = viewModelScope.launch {
            _folderSearchNotices.tryEmit(FolderSearchNotice.SEARCHING)
            val found = libraryFolders.finder.scan()
            if (found.isEmpty()) _folderSearchNotices.tryEmit(FolderSearchNotice.NONE_FOUND)
            else _folderProposal.value = FolderProposalState(found)
        }
    }

    fun toggleProposedFolder(path: String) {
        _folderProposal.value = _folderProposal.value?.toggle(path)
    }

    fun dismissProposedFolders() {
        _folderProposal.value = null
    }

    /** "Add": the chosen folders go in the library and the library is scanned again (the same refresh as any change of the folders). */
    fun addProposedFolders() {
        val proposal = _folderProposal.value ?: return
        _folderProposal.value = null
        viewModelScope.launch {
            when (libraryFolders.finder.add(proposal.chosen)) {
                com.lostf1sh.pixelplayeross.data.library.LibraryFolderFinder.AddResult.ADDED -> syncManager.forceRefresh()
                com.lostf1sh.pixelplayeross.data.library.LibraryFolderFinder.AddResult.NOT_READY ->
                    _folderSearchNotices.tryEmit(FolderSearchNotice.NOT_READY)
                com.lostf1sh.pixelplayeross.data.library.LibraryFolderFinder.AddResult.NOTHING -> Unit
            }
        }
    }

    fun applyPendingDirectoryRuleChanges() {
        if (!hasPendingDirectoryRuleChanges) return
        hasPendingDirectoryRuleChanges = false
        viewModelScope.launch {
            latestDirectoryRuleUpdateJob?.join()
            syncManager.forceRefresh()
        }
    }

    fun loadDirectory(file: File) {
        fileExplorerStateHolder.loadDirectory(file)
    }

    fun primeExplorer() {
        fileExplorerStateHolder.primeExplorerRoot()
    }

    fun openExplorer() {
        fileExplorerStateHolder.openExplorerRoot()
    }

    fun navigateUp() {
        fileExplorerStateHolder.navigateUp()
    }

    fun refreshExplorer() {
        fileExplorerStateHolder.refreshCurrentDirectory()
    }

    fun selectStorage(index: Int) {
        fileExplorerStateHolder.selectStorage(index)
    }

    fun refreshAvailableStorages() {
        fileExplorerStateHolder.refreshAvailableStorages()
    }

    fun isAtRoot(): Boolean = fileExplorerStateHolder.isAtRoot()

    fun explorerRoot(): File = fileExplorerStateHolder.rootDirectory()

    fun setPlayerThemePreference(preference: String) {
        viewModelScope.launch {
            themePreferencesRepository.setPlayerThemePreference(preference)
        }
    }

    fun setAlbumArtPaletteStyle(style: AlbumArtPaletteStyle) {
        viewModelScope.launch {
            themePreferencesRepository.setAlbumArtPaletteStyle(style)
        }
    }

    fun setAlbumArtPaletteSettings(
        style: AlbumArtPaletteStyle,
        accuracyLevel: Int
    ) {
        viewModelScope.launch {
            themePreferencesRepository.setAlbumArtPaletteSettings(style, accuracyLevel)
        }
    }

    suspend fun getAlbumArtPalettePreview(
        uriString: String,
        style: AlbumArtPaletteStyle,
        accuracyLevel: Int
    ): ColorSchemePair? {
        return colorSchemeProcessor.getPreviewColorScheme(
            albumArtUri = uriString,
            paletteStyle = style,
            colorAccuracyLevel = accuracyLevel
        )
    }

    fun setCollagePattern(pattern: CollagePattern) {
        viewModelScope.launch {
            userPreferencesRepository.setCollagePattern(pattern)
        }
    }

    fun setCollageAutoRotate(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setCollageAutoRotate(enabled)
        }
    }

    /** Stores the language; the activity recreates itself once because of it (B16-G3), so the caller does not. */
    fun setAppLanguage(languageTag: String) {
        val normalized = AppLanguage.normalize(languageTag)
        AppLocaleManager.applyLanguage(context, normalized)
        _uiState.update { it.copy(appLanguageTag = normalized) }
    }


    fun setDacShareEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDacShareEnabled(enabled)
        }
    }

    fun setResonanceLimiterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setResonanceLimiterEnabled(enabled)
        }
    }

    fun setDapLockScreenEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDapLockScreenEnabled(enabled)
        }
    }

    fun setBaseTheme(baseTheme: DapBaseTheme) {
        viewModelScope.launch {
            themePreferencesRepository.setBaseTheme(baseTheme)
        }
    }

    fun setAppColorSource(source: String) {
        viewModelScope.launch {
            themePreferencesRepository.setAppColorSource(source)
        }
    }

    /** B41-fix1 (B1): "Theme" while the cover decides the colours: dark, light or system. The explicit theme is not touched. */
    fun setCoverThemeChoice(choice: String) {
        viewModelScope.launch {
            themePreferencesRepository.setCoverThemeChoice(choice)
        }
    }

    /** B41: "Moving background". */
    fun setCoverGlassMoving(moving: Boolean) {
        viewModelScope.launch {
            themePreferencesRepository.setCoverGlassMoving(moving)
        }
    }

    /** B56-7.2: "Animated covers". */
    fun setAnimatedCovers(enabled: Boolean) {
        viewModelScope.launch { themePreferencesRepository.setAnimatedCovers(enabled) }
    }

    /**
     * B57-fix1 4: the system folder picker's answer, when the listener turned "Animated covers" on (or chose Change). A folder that is kept turns the feature on; cancelling, or a folder the
     * system or the app cannot use, changes nothing: the feature stays as it was (off, when it was the first time).
     */
    fun onAnimatedCoverFolderPicked(uri: android.net.Uri?, flags: Int) {
        viewModelScope.launch {
            // a cancelled picker (no uri) keeps nothing, and then nothing is turned on
            val kept = uri != null && withContext(Dispatchers.IO) { animatedCoverFolderStore.choose(uri, flags) }
            if (com.lostf1sh.pixelplayeross.data.preferences.AnimatedCovers.enabledAfterPicker(kept)) themePreferencesRepository.setAnimatedCovers(true)
        }
    }

    /** B57-fix1 4: "Remove": the permission is released and the feature goes off. */
    fun removeAnimatedCoverFolder() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { animatedCoverFolderStore.remove() }
            themePreferencesRepository.setAnimatedCovers(false)
        }
    }

    /** B56-7.1: "Background motion": still, gentle or lively. */
    fun setBackgroundMotion(motion: com.lostf1sh.pixelplayeross.data.preferences.BackgroundMotion) {
        viewModelScope.launch { themePreferencesRepository.setBackgroundMotion(motion) }
    }

    /** B56-3.4: how the lyrics show in the full player. */
    fun setLyricsMode(mode: LyricsMode) {
        viewModelScope.launch {
            userPreferencesRepository.setLyricsMode(mode)
        }
    }

    fun setAccent(accent: DapAccent) {
        viewModelScope.launch {
            themePreferencesRepository.setAccent(accent)
        }
    }

    fun setAppThemeMode(mode: String) {
        viewModelScope.launch {
            themePreferencesRepository.setAppThemeMode(mode)
        }
    }

    fun setNavBarStyle(style: String) {
        viewModelScope.launch {
            userPreferencesRepository.setNavBarStyle(style)
        }
    }

    fun setNavBarCompactMode(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setNavBarCompactMode(enabled)
        }
    }

    fun setLibraryNavigationMode(mode: String) {
        viewModelScope.launch {
            userPreferencesRepository.setLibraryNavigationMode(mode)
        }
    }

    fun setCarouselStyle(style: String) {
        viewModelScope.launch {
            userPreferencesRepository.setCarouselStyle(style)
        }
    }

    fun setRepeatSharedSongsInShuffle(repeat: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setRepeatSharedSongsInShuffle(repeat) }
    }

    fun setShowBookmarkButton(show: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowBookmarkButton(show) }
    }

    fun setShowPlayerFileInfo(show: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setShowPlayerFileInfo(show)
        }
    }

    fun setLaunchTab(tab: String) {
        viewModelScope.launch {
            userPreferencesRepository.setLaunchTab(tab)
        }
    }

    fun setKeepPlayingInBackground(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setKeepPlayingInBackground(enabled)
        }
    }

    fun setResumeOnHeadsetReconnect(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setResumeOnHeadsetReconnect(enabled)
        }
    }

    fun setShowQueueHistory(show: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setShowQueueHistory(show)
        }
    }

    fun setCrossfadeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setCrossfadeEnabled(enabled)
        }
    }

    fun setSmartCrossfadeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setSmartCrossfadeEnabled(enabled)
        }
    }

    val playbackSpeed: StateFlow<Float> = userPreferencesRepository.playbackSpeedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    fun setPlaybackSpeed(speed: Float) {
        viewModelScope.launch {
            userPreferencesRepository.setPlaybackSpeed(speed)
        }
    }

    fun setPersistentShuffleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setPersistentShuffleEnabled(enabled)
        }
    }

    /** The one writer for the random mode; the service reads the same repository flow, so the change reaches the engine on its own. */
    fun setRandomMode(mode: RandomMode) {
        viewModelScope.launch {
            userPreferencesRepository.setRandomMode(mode)
        }
    }

    fun setFolderBackGestureNavigation(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setFolderBackGestureNavigation(enabled)
        }
    }

    /**
     * The one writer for the app's Back mode. The shell reads the same repository flow, so a change
     * here re-arms the gesture and the affordances together rather than each surface caching it.
     */
    fun setBackNavigationMode(mode: BackNavigationMode) {
        viewModelScope.launch {
            userPreferencesRepository.setBackNavigationMode(mode)
        }
    }

    fun setLyricsSourcePreference(preference: LyricsSourcePreference) {
        viewModelScope.launch {
            userPreferencesRepository.setLyricsSourcePreference(preference)
        }
    }

    fun setAutoScanLrcFiles(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAutoScanLrcFiles(enabled)
        }
    }

    fun setExternalLyricsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setExternalLyricsEnabled(enabled)
        }
    }

    fun setFolderCoversEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setFolderCoversEnabled(enabled) }
    }

    fun setMusicInfoCoversEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setMusicInfoCoversEnabled(enabled) }
    }

    fun setMusicInfoArtistPhotosEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setMusicInfoArtistPhotosEnabled(enabled) }
    }

    fun setMusicInfoDescriptionsEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setMusicInfoDescriptionsEnabled(enabled) }
    }

    fun setMusicInfoCacheLimitMb(megabytes: Int) {
        viewModelScope.launch { userPreferencesRepository.setMusicInfoCacheLimitMb(megabytes) }
    }

    private var pendingMusicInfoDelete: com.lostf1sh.pixelplayeross.data.offline.InfoUndo? = null

    /** Settings uses the same durable marks, lyrics cleanup and delayed deletion as chosen items. */
    fun deleteMusicInfo(onDone: (freedBytes: Long) -> Unit = {}) {
        deleteMusicInfoResult { it.onSuccess(onDone) }
    }

    fun deleteMusicInfoResult(onDone: (Result<Long>) -> Unit) {
        viewModelScope.launch {
            try {
                val undo = pendingMusicInfoDelete ?: musicInfoDownloads.deleteAllDownloaded().also { pendingMusicInfoDelete = it }
                if (musicInfoDownloads.dao.undoAction(undo.token)?.failed == true) musicInfoDownloads.retryDelete(undo.token)
                kotlinx.coroutines.delay((undo.untilAt - System.currentTimeMillis()).coerceAtLeast(0) + 1)
                musicInfoDownloads.finishExpiredUndo()
                check(musicInfoDownloads.dao.undoAction(undo.token) == null) { "Downloaded info cleanup failed" }
                _uiState.update { it.copy(musicInfoUsedBytes = musicInfoDownloads.downloadedBytes()) }
                pendingMusicInfoDelete = null
                onDone(Result.success(undo.knownBytes))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                timber.log.Timber.tag("MusicInfo").w(failure, "Settings delete failed")
                onDone(Result.failure(failure))
            }
        }
    }


    fun setUseAnimatedLyrics(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setUseAnimatedLyrics(enabled)
        }
    }

    fun setAnimatedLyricsBlurEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAnimatedLyricsBlurEnabled(enabled)
        }
    }

    fun setAnimatedLyricsBlurStrength(strength: Float) {
        viewModelScope.launch {
            userPreferencesRepository.setAnimatedLyricsBlurStrength(strength)
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            if (isSyncing.value) return@launch
            syncManager.forceRefresh()
        }
    }


    /**
     * Performs a full library rescan - rescans all files from scratch.
     * Use when songs are missing or metadata is incorrect.
     */
    fun fullSyncLibrary() {
        viewModelScope.launch {
            if (isSyncing.value) return@launch
            syncManager.fullSync()
        }
    }

    fun setMinSongDuration(durationMs: Int) {
        viewModelScope.launch {
            if (durationMs == _uiState.value.minSongDuration) return@launch
            userPreferencesRepository.setMinSongDuration(durationMs)
            syncManager.fullSync(deepScan = false)
        }
    }

    fun setMinTracksPerAlbum(minTracks: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setMinTracksPerAlbum(minTracks)
        }
    }

    fun setReplayGainEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setReplayGainEnabled(enabled)
        }
    }

    fun setReplayGainUseAlbumGain(useAlbumGain: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setReplayGainUseAlbumGain(useAlbumGain)
        }
    }

    fun setImmersiveLyricsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setImmersiveLyricsEnabled(enabled)
        }
    }

    fun setLyricsAlignment(alignment: String) {
        viewModelScope.launch { userPreferencesRepository.setLyricsAlignment(alignment) }
    }

    fun setShowLyricsTranslation(show: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowLyricsTranslation(show) }
    }

    fun setShowLyricsRomanization(show: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowLyricsRomanization(show) }
    }

    fun setImmersiveLyricsTimeout(timeout: Long) {
        viewModelScope.launch {
            userPreferencesRepository.setImmersiveLyricsTimeout(timeout)
        }
    }

    /**
     * Deletes every imported lyric, for every song.
     *
     * Global and irreversible: this empties the lyrics table and the per-song sidecar cache. It is
     * a recovery action, not a lyric preference, so it lives behind the Maintenance PIN rather than
     * on the Lyrics page, and the UI that triggers it says so before it runs.
     */
    fun resetAllLyrics() {
        viewModelScope.launch {
            runCatching { lyricsRepository.resetAllLyrics() }
                .onFailure { error ->
                    Timber.tag("Maintenance").e(error, "Unable to delete imported lyrics")
                }
        }
    }

    // ── Items without a file (AUD-106) ─────────────────────────────────
    //
    // Dormant items are never purged automatically: absent is not deleted. Maintenance offers a
    // manual clean-up instead, and the count it shows comes from the same selection as the delete
    // (an item the listening history names is kept: DormantItemCleanup).

    private val _purgeableDormantCount = MutableStateFlow<Int?>(null)

    /** Dormant items with no user state that the clean-up would remove; null until counted. */
    val purgeableDormantCount: StateFlow<Int?> = _purgeableDormantCount.asStateFlow()

    fun refreshPurgeableDormantCount() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _purgeableDormantCount.value = runCatching { dormantItemCleanup.count() }
                .onFailure { Timber.tag("Maintenance").e(it, "Unable to count items without a file") }
                .getOrNull()
        }
    }

    /** Removes the counted items and reports how many went. Only ever called from a confirmation. */
    fun purgeDormantItems(onDone: (removed: Int) -> Unit) {
        viewModelScope.launch {
            val removed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { dormantItemCleanup.remove() }
                    .onFailure { Timber.tag("Maintenance").e(it, "Unable to remove items without a file") }
                    .getOrDefault(0)
            }
            refreshPurgeableDormantCount()
            onDone(removed)
        }
    }

    /**
     * Rebuilds local MediaStore-backed songs from scratch.
     * Favorites, imported lyrics and metadata edits are owned by the item identity and survive it
     * (AUD-009). Use when local library data is corrupted or as a last resort.
     */
    fun rebuildDatabase() {
        viewModelScope.launch {
            if (isSyncing.value) return@launch
            syncManager.rebuildDatabase()
        }
    }

    fun setNavBarCornerRadius(radius: Int) {
        viewModelScope.launch { userPreferencesRepository.setNavBarCornerRadius(radius) }
    }
    /**
     * Triggers a test crash to verify the crash handler is working correctly.
     * This should only be used for testing in Developer Options.
     */
    fun triggerTestCrash() {
        throw RuntimeException(context.getString(R.string.dev_test_crash_message))
    }

    val albumArtQuality: StateFlow<AlbumArtQuality> = userPreferencesRepository.albumArtQualityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlbumArtQuality.MEDIUM)

    val useSmoothCorners: StateFlow<Boolean> = userPreferencesRepository.useSmoothCornersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val tapBackgroundClosesPlayer: StateFlow<Boolean> = userPreferencesRepository.tapBackgroundClosesPlayerFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setAlbumArtQuality(quality: AlbumArtQuality) {
        viewModelScope.launch {
            userPreferencesRepository.setAlbumArtQuality(quality)
        }
    }

    fun setAlbumArtCacheLimitMb(limitMb: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setAlbumArtCacheLimitMb(limitMb)
            com.lostf1sh.pixelplayeross.utils.AlbumArtCacheManager.configuredCacheLimitMb = limitMb.toLong()
        }
    }

    fun setUseSmoothCorners(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setUseSmoothCorners(enabled)
        }
    }

    fun setTapBackgroundClosesPlayer(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setTapBackgroundClosesPlayer(enabled)
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setHapticsEnabled(enabled)
        }
    }


}
