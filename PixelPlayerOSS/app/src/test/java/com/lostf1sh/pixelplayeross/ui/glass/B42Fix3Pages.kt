package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.data.repository.LyricsSearchResult
import com.lostf1sh.pixelplayeross.presentation.components.RecentlyPlayedSection
import com.lostf1sh.pixelplayeross.presentation.components.StatsOverviewCard
import com.lostf1sh.pixelplayeross.presentation.components.player.FullPlayerActions
import com.lostf1sh.pixelplayeross.presentation.components.player.FullPlayerPortraitContent
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerControlsSurface
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerLyricsLines
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerLyricsState
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerProgressSurface
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerSheetEnv
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerTrackInfoSurface
import com.lostf1sh.pixelplayeross.presentation.components.player.buildFullPlayerUiState
import com.lostf1sh.pixelplayeross.presentation.model.RecentlyPlayedSongUiModel
import com.lostf1sh.pixelplayeross.presentation.screens.LibrarySongsTab
import com.lostf1sh.pixelplayeross.presentation.settings.AppearanceSettingsActions
import com.lostf1sh.pixelplayeross.presentation.settings.AppearanceSettingsScreen
import com.lostf1sh.pixelplayeross.presentation.settings.AppearanceSettingsState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBottomNav
import com.lostf1sh.pixelplayeross.ui.ds.DapCollectionActions
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapMiniPlayer
import com.lostf1sh.pixelplayeross.ui.ds.DapNavItem
import com.lostf1sh.pixelplayeross.ui.ds.DapPlayerHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapRowState
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.theme.DapAppearanceChoice
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * B42-fix3: the screens the renders and the surface measurements are made of, with the app's own components (the song list is `LibrarySongsTab`, the
 * Home tiles are `RecentlyPlayedSection`, the card is `StatsOverviewCard`, the bars are `DapMiniPlayer` and `DapBottomNav`, Settings is
 * `AppearanceSettingsScreen`, the player is `FullPlayerPortraitContent`), on whatever the stage puts behind them. The same file runs in the tree
 * before this batch and after it.
 */
internal object B42Fix3Pages {
    const val TAG_STRIP = "fix3-strip"
    const val TAG_TILES = "fix3-tiles"
    const val TAG_STATS = "fix3-stats"
    const val TAG_MINI = "fix3-mini"
    const val TAG_NAV = "fix3-nav"
    const val TAG_BAR = "fix3-bar"

    fun song(i: Int) = Song(
        id = "id-$i", title = "Cancion $i", artist = listOf("Gemini Sound", "Amaia", "Nao")[i % 3], artistId = 7L, album = "Noche", albumId = 9L,
        path = "/m/$i.flac", contentUriString = "content://$i", albumArtUriString = null, duration = 245_000L, mimeType = "audio/flac", bitrate = 1000,
        sampleRate = 44100,
    )

    private val songs = (0 until 60).map(::song)

    private val libraryViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.stablePlayerState } returns MutableStateFlow(StablePlayerState(currentSong = songs[2], isPlaying = true))
        every { it.scrollToIndexEvent } returns MutableSharedFlow()
        every { it.libraryLocateRequest } returns MutableStateFlow(null)
        every { it.locatedSongId } returns MutableStateFlow(null)
    }

    private val navItems = listOf(
        DapNavItem(R.string.tab_home, DapIconKind.Home), DapNavItem(R.string.search, DapIconKind.Search), DapNavItem(R.string.tab_library, DapIconKind.Library),
    )

    @Composable
    private fun Cover(cover: B41Renders.Cover, modifier: Modifier) =
        Image(cover.bitmap.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)

    /** The docked player and the bottom bar, as the app stacks them. */
    @Composable
    private fun Shelf(cover: B41Renders.Cover, selected: Int) {
        Box(Modifier.testTag(TAG_MINI)) {
            DapMiniPlayer(
                title = "Cine", artist = "Gemini Sound", isPlaying = true, onPlayPause = {}, onNext = {}, onOpen = {},
                artwork = { Cover(cover, Modifier.size(40.dp)) },
            )
        }
        Box(Modifier.testTag(TAG_NAV)) {
            DapBottomNav(items = navItems, selectedIndex = selected, onSelect = {}, modifier = Modifier.fillMaxWidth().height(72.dp))
        }
    }

    /** The strip under the status bar, as Home draws it. */
    @Composable
    private fun StatusStrip() {
        Box(Modifier.testTag(TAG_STRIP).fillMaxWidth().height(37.dp).background(Dap.bar))
    }

    // ── Home ────────────────────────────────────────────────────────────────────────────────────────

    @Composable
    fun Home(cover: B41Renders.Cover) {
        val recent: ImmutableList<RecentlyPlayedSongUiModel> = remember {
            (0 until 6).map { RecentlyPlayedSongUiModel(song(it).copy(title = listOf("Rinkside", "Cinderella", "PERO NO TE", "Mi Presidio", "BELLAKEO", "Mi Universo")[it]), 1_000L + it) }
                .toImmutableList()
        }
        Column(Modifier.fillMaxSize()) {
            StatusStrip()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = DapSpacing.Sm)) {
                Box(Modifier.testTag(TAG_TILES)) {
                    RecentlyPlayedSection(
                        songs = recent,
                        onSongClick = {},
                        onOpenAllClick = {},
                        /*THEME_HOLDER*/
                        currentSongId = "id-2",
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DapSpacing.Gutter),
                    )
                }
                Spacer(Modifier.height(DapSpacing.SectionGap))
                Box(Modifier.testTag(TAG_STATS)) { StatsOverviewCard(summary = null, onClick = {}) }
            }
            Shelf(cover, selected = 0)
        }
    }

    @Composable
    fun Device(cover: B41Renders.Cover) {
        Column(Modifier.fillMaxSize()) {
            StatusStrip()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                com.lostf1sh.pixelplayeross.presentation.screens.DapWirelessControlContent(
                    state = com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState(),
                    onManageWifi = {}, onEnableBluetooth = {}, onManageBluetooth = {})
                com.lostf1sh.pixelplayeross.presentation.screens.DapQuickDeviceControls(
                    state = com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState(),
                    onBrightnessChanged = {}, onVolumeChanged = {})
            }
            Shelf(cover, selected = 0)
        }
    }

    // ── Library ─────────────────────────────────────────────────────────────────────────────────────

    @Composable
    fun Library(cover: B41Renders.Cover) {
        val items = remember { flowOf(PagingData.from(songs)) }.collectAsLazyPagingItems()
        Column(Modifier.fillMaxSize()) {
            StatusStrip()
            DapScreenTitle(text = "Biblioteca", modifier = Modifier.padding(horizontal = DapSpacing.Gutter))
            Box(Modifier.weight(1f)) {
                LibrarySongsTab(
                    songs = items, isLoading = false, playerViewModel = libraryViewModel, bottomBarHeight = 0.dp, onMoreOptionsClick = {},
                    isRefreshing = false, onRefresh = {}, sortOption = SortOption.SongTitleAZ, hasCurrentSong = true,
                )
            }
            Shelf(cover, selected = 2)
        }
    }

    // ── Stats ───────────────────────────────────────────────────────────────────────────────────────

    @Composable
    fun Stats(cover: B41Renders.Cover) {
        Column(Modifier.fillMaxSize()) {
            StatusStrip()
            DapScreenTitle(text = "Estadísticas", modifier = Modifier.padding(horizontal = DapSpacing.Gutter))
            Spacer(Modifier.height(DapSpacing.Md))
            Box(Modifier.testTag(TAG_STATS)) { StatsOverviewCard(summary = null, onClick = {}) }
            Spacer(Modifier.height(DapSpacing.Lg))
            listOf("Cine" to "Gemini Sound", "Ventana roja" to "Amaia", "Luces de la ciudad" to "Nao").forEachIndexed { i, (title, artist) ->
                DapMediaRow(
                    title = title, subtitle = artist, duration = "3:2$i", state = DapRowState.Normal,
                    leading = { Cover(cover, Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))) }, onClick = {},
                )
            }
            Spacer(Modifier.weight(1f))
            Shelf(cover, selected = 0)
        }
    }

    // ── an album page ───────────────────────────────────────────────────────────────────────────────

    @Composable
    fun Album(cover: B41Renders.Cover) {
        Column(Modifier.fillMaxSize()) {
            // the bar once the header has collapsed under it
            Box(Modifier.testTag(TAG_BAR).fillMaxWidth().height(96.dp).background(Dap.bar))
            Column(Modifier.padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Md)) {
                DapScreenTitle(text = "Noche")
                Spacer(Modifier.height(DapSpacing.Sm))
                DapCollectionActions(onPlay = {}, onShuffle = {}, playLabel = "Reproducir", shuffleLabel = "Aleatorio")
            }
            listOf("Cine", "Ventana roja", "Luces de la ciudad", "Madrugada", "Calle vacía", "Noche").forEachIndexed { i, title ->
                DapMediaRow(
                    title = title, subtitle = "Gemini Sound", duration = "3:${10 + i}", state = if (i == 1) DapRowState.Current else DapRowState.Normal,
                    leading = { Cover(cover, Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))) }, onClick = {},
                )
            }
            Spacer(Modifier.weight(1f))
            Shelf(cover, selected = 2)
        }
    }

    // ── Settings ────────────────────────────────────────────────────────────────────────────────────

    private val noSettingsActions = object : AppearanceSettingsActions {
        override fun onAppLanguageChange(value: String) {}
        override fun onAccentChange(value: DapAccent) {}
        override fun onAppColorSourceChange(value: String) {}
        override fun onCoverThemeChoiceChange(value: String) {}
        override fun onPlayerThemePreferenceChange(value: String) {}
        override fun onShowBookmarkButtonChange(value: Boolean) {}
        override fun onShowPlayerFileInfoChange(value: Boolean) {}
        override fun onAlbumArtQualityChange(value: AlbumArtQuality) {}
        override fun onLaunchTabChange(value: String) {}
        override fun onLibraryNavigationModeChange(value: String) {}
        override fun onThemeChoiceChange(choice: DapAppearanceChoice) {}
        override fun onOpenPaletteStyle() {}
        override fun onCoverGlassMovingChange(value: Boolean) {}
        override fun onLyricsUnderCoverChange(value: Boolean) {}
        override fun onImmersiveLyricsEnabledChange(value: Boolean) {}
        override fun onImmersiveLyricsTimeoutChange(value: Long) {}
        override fun onLyricsAlignmentChange(value: String) {}
        override fun onShowLyricsTranslationChange(value: Boolean) {}
        override fun onShowLyricsRomanizationChange(value: Boolean) {}
        override fun onUseAnimatedLyricsChange(value: Boolean) {}
        override fun onAnimatedLyricsBlurEnabledChange(value: Boolean) {}
        override fun onAnimatedLyricsBlurStrengthChange(value: Float) {}
        override fun onDapLockScreenEnabledChange(value: Boolean) {}
    }

    @Composable
    fun Settings(source: String) {
        Column(Modifier.fillMaxSize().padding(top = 37.dp + DapSpacing.Sm, start = 16.dp, end = 16.dp).verticalScroll(rememberScrollState())) {
            DapScreenTitle(text = "Appearance")
            AppearanceSettingsScreen(AppearanceSettingsState.from(SettingsUiState(appColorSource = source)), noSettingsActions)
        }
    }

    // ── the full player ─────────────────────────────────────────────────────────────────────────────

    private class NoopActions : FullPlayerActions {
        override fun onPlayPause() {}
        override fun onSeek(positionMs: Long) {}
        override fun onSkipNext() {}
        override fun onSkipPrevious() {}
        override fun onNext() {}
        override fun onPrevious() {}
        override fun onShuffleToggle() {}
        override fun onRepeatToggle() {}
        override fun onFavoriteToggle() {}
        override fun onCollapse() {}
        override fun onQueueDragStart() {}
        override fun onQueueDrag(dragAmount: Float) {}
        override fun onQueueRelease(totalDrag: Float, velocity: Float) {}
        override fun onPlayFromCarousel(song: Song, queue: ImmutableList<Song>, queueName: String, index: Int) {}
        override fun onOpenAlbum(albumId: Long) {}
        override fun onOpenArtist(artistId: Long) {}
        override fun onAddBookmark(song: Song, title: String, positionMs: Long) {}
        override fun onEnsureLyricsResolved() {}
        override fun onFetchLyrics(forcePick: Boolean) {}
        override fun onAcceptLyricsResult(result: LyricsSearchResult) {}
        override fun onSearchLyricsManually(title: String, artist: String?) {}
        override fun onResetLyricsSearch() {}
        override fun onResetLyrics() {}
        override fun onLyricsSyncOffsetChange(songId: String, offsetMs: Int) {}
        override fun onLyricsAlignmentChange(alignment: String) {}
        override fun onShowLyricsTranslationChange(show: Boolean) {}
        override fun onShowLyricsRomanizationChange(show: Boolean) {}
        override fun onImmersiveTemporarilyDisabledChange(disabled: Boolean) {}
        override fun onSaveLyricsToFile(song: Song, lyrics: Lyrics, preferSynced: Boolean) {}
        override fun onImportLyricsFromFile(songId: Long, validated: com.lostf1sh.pixelplayeross.utils.ValidatedLyricsImport) {}
        override fun onToast(message: String) {}
        override fun previousDecisionPositionMs(): Long = 0L
    }

    private val lyrics = Lyrics(
        synced = listOf(
            SyncedLine(1_000, "Abre la puerta, que estoy afuera"),
            SyncedLine(20_000, "Estás grande ya, pero tú eres mi nena"),
            SyncedLine(40_000, "Mira cómo brilla todo por la noche"),
        )
    )

    @Composable
    fun Player(cover: B41Renders.Cover) {
        val actions = remember { NoopActions() }
        val state = remember(cover) {
            buildFullPlayerUiState(
                env = PlayerSheetEnv(FullPlayerLoadingTweaks(delayAll = false), false, PlayerSheetState.EXPANDED, false, { 1f }),
                song = song(1).copy(title = "Cine", artist = "Gemini Sound"), queue = persistentListOf(), queueSourceName = "All Songs", mediaItemIndex = 0,
                carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
                audioMetadata = PlaybackAudioMetadata(mediaId = "1", mimeType = "audio/flac", sampleRate = 44100), showFileInfo = true,
                isFavorite = false, isBuffering = false, isShuffleEnabled = true, repeatMode = 0,
                isPlayingProvider = { true }, playWhenReadyProvider = { true }, currentPositionProvider = { 22_000L }, totalDurationProvider = { 245_000L },
                lyrics = PlayerLyricsState(
                    searchState = LyricsSearchUiState.Idle, stablePlayerStateFlow = MutableStateFlow(StablePlayerState()),
                    playbackPositionFlow = MutableStateFlow(0L), externalLyricsEnabled = false, lyricsSyncOffset = 0, immersiveLyricsEnabled = false,
                    immersiveLyricsTimeout = 4000L, isImmersiveTemporarilyDisabled = false, isShuffleEnabled = true, repeatMode = 0, isFavorite = false,
                    lyricsProvider = { lyrics }, lyricsResolveCompletedProvider = { true },
                ),
                isLandscape = false,
            )
        }
        // the player draws in the sheet's own scheme, built from the palette and the accent in force, as `UnifiedPlayerMiniAndFullLayers` provides it
        androidx.compose.runtime.CompositionLocalProvider(
            com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme provides
                com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme(
                    com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent.current, com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette.current,
                )
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = { Box(Modifier.padding(top = 24.dp)) { DapPlayerHeader(lyricsActive = false, onCollapse = {}, onLyrics = {}, onDetails = {}) } },
            ) { padding ->
                FullPlayerPortraitContent(
                    paddingValues = padding,
                    albumCoverSection = { m ->
                        Box(m.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
                            Image(cover.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    },
                    songMetadataSection = { PlayerTrackInfoSurface(state.trackInfo, state.env, actions, false, {}, {}) },
                    playerProgressSection = { PlayerProgressSurface(state.progress, state.env, actions) },
                    controlsSection = { PlayerControlsSurface(state.controls, state.env, actions) },
                    lyricsLinesSection = { PlayerLyricsLines(state.lyrics, state.progress, onOpen = {}) },
                )
            }
        }
    }

    @Suppress("unused")
    private val keepImport = AppColorSource.THEME
}
