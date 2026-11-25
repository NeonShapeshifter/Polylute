package com.lostf1sh.pixelplayeross.ui.glass

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.FolderSource
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtPaletteStyle
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.data.repository.LyricsSearchResult
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemePair
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapTonalRoundButton
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.theme.AppPainting
import com.lostf1sh.pixelplayeross.ui.theme.ColorExtractionConfig
import com.lostf1sh.pixelplayeross.ui.theme.PixelPlayerTheme
import com.lostf1sh.pixelplayeross.ui.theme.rememberAppPainting
import com.lostf1sh.pixelplayeross.ui.theme.selectSeedColorArgbFromPixels
import com.lostf1sh.pixelplayeross.presentation.components.LyricsSheet
import com.lostf1sh.pixelplayeross.presentation.components.MultiSelectionBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.QueueSurface
import com.lostf1sh.pixelplayeross.presentation.components.RecentArtistsSection
import com.lostf1sh.pixelplayeross.presentation.components.RecentlyPlayedSection
import com.lostf1sh.pixelplayeross.presentation.components.StatsOverviewCard
import com.lostf1sh.pixelplayeross.presentation.components.player.AddBookmarkDialog
import com.lostf1sh.pixelplayeross.presentation.components.player.FullPlayerActions
import com.lostf1sh.pixelplayeross.presentation.components.player.FullPlayerPortraitContent
import com.lostf1sh.pixelplayeross.presentation.components.player.PLAYER_LYRICS_LINES_TAG
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerArtistPickerBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerControlsSurface
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerCoverSurface
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerLyricsLines
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerLyricsState
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerPagesTags
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerProgressSurface
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerSheetEnv
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerTrackInfoSurface
import com.lostf1sh.pixelplayeross.presentation.components.player.QueuePeekBottomGap
import com.lostf1sh.pixelplayeross.presentation.components.player.QueuePeekHeight
import com.lostf1sh.pixelplayeross.presentation.components.player.buildFullPlayerUiState
import com.lostf1sh.pixelplayeross.presentation.components.scoped.QueueSheetRuntimeEffects
import com.lostf1sh.pixelplayeross.presentation.components.scoped.rememberQueueSheetState
import com.lostf1sh.pixelplayeross.presentation.model.RecentlyPlayedSongUiModel
import com.lostf1sh.pixelplayeross.presentation.screens.FolderTabControls
import com.lostf1sh.pixelplayeross.presentation.screens.LibraryFloatingActions
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapBottomNav
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapMenuItem
import com.lostf1sh.pixelplayeross.ui.ds.DapMenuSurface
import com.lostf1sh.pixelplayeross.ui.ds.DapMiniPlayer
import com.lostf1sh.pixelplayeross.ui.ds.DapNavItem
import com.lostf1sh.pixelplayeross.ui.ds.DapPlayerHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapRowState
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette
import com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme
import com.lostf1sh.pixelplayeross.ui.theme.generateColorSchemeFromSeed
import com.lostf1sh.pixelplayeross.R
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B76 color regression renders across the three synthetic covers from B75 (warm light, dark saturated, near gray)
 * in both cover modes (`ALBUM_ART` = `art`, `COVER_GLASS` = `glass`) and `THEME` (`theme`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B76ColorRenderTest {
    @get:Rule(order = 0)
    val hilt = B42HiltShim()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val outDir: File
        get() = File(System.getenv("B76_RENDERS") ?: "build/test-artifacts/B76/renders").apply { mkdirs() }

    private val phase: String
        get() = System.getenv("B76_PHASE") ?: System.getProperty("b76.phase") ?: "after"

    private enum class Scene {
        ARTIST_PICKER,
        FOLDERS,
        LYRICS,
        PLAYER360,
        MENU,
        MULTISEL,
        BOOKMARK,
        HOME,
        QUEUE,
        SETTINGS,
        THEME_HOME,
        THEME_SETTINGS,
    }

    private data class Spec(val name: String, val scene: Scene, val cover: B41Renders.Cover, val mode: Fix3Mode)

    private fun paint(block: Paint.() -> Unit = {}) = Paint(Paint.ANTI_ALIAS_FLAG).apply(block)

    private fun warmLightCover(): B41Renders.Cover {
        val size = 320
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint {
            shader = LinearGradient(0f, 0f, 0f, size.toFloat(), 0xFFFFF4DE.toInt(), 0xFFF5C78E.toInt(), Shader.TileMode.CLAMP)
        })
        c.drawCircle(116f, 126f, 88f, paint { color = 0xFFFFE49E.toInt() })
        c.drawCircle(216f, 204f, 76f, paint { color = 0xFFE48952.toInt() })
        return B41Renders.Cover("warmlight", bmp)
    }

    private fun darkSatCover(): B41Renders.Cover {
        val size = 320
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF140409.toInt(), 0xFF5A061A.toInt(), Shader.TileMode.CLAMP)
        })
        c.drawCircle(160f, 160f, 118f, paint {
            shader = RadialGradient(160f, 160f, 118f, 0xFFE01A3B.toInt(), 0xFF700922.toInt(), Shader.TileMode.CLAMP)
        })
        c.drawRect(24f, 232f, 296f, 286f, paint { color = 0xFF4C0B36.toInt() })
        return B41Renders.Cover("darksat", bmp)
    }

    private fun nearGrayCover(): B41Renders.Cover {
        val size = 320
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFFD2D6DC.toInt(), 0xFF686C72.toInt(), Shader.TileMode.CLAMP)
        })
        c.drawOval(60f, 56f, 240f, 236f, paint { color = 0xFF414449.toInt() })
        c.drawOval(118f, 118f, 260f, 260f, paint { color = 0xFFE3E6EA.toInt() })
        return B41Renders.Cover("neargray", bmp)
    }

    private val warmLight by lazy { warmLightCover() }
    private val darkSat by lazy { darkSatCover() }
    private val nearGray by lazy { nearGrayCover() }

    private fun sampleSong(i: Int, artUri: String? = null) = Song(
        id = "song-$i",
        title = listOf("Aurora", "Obsidian", "Monochrome", "Solstice", "Velvet", "Ember")[i % 6],
        artist = "Artist Alpha",
        artistId = 10L + i,
        album = "Nocturne",
        albumId = 20L,
        path = "/music/$i.flac",
        contentUriString = "content://song/$i",
        albumArtUriString = artUri,
        duration = 215_000L,
        mimeType = "audio/flac",
        bitrate = 1000,
        sampleRate = 44100,
    )

    private val sampleSongs: ImmutableList<Song> = (0 until 8).map { sampleSong(it) }.toImmutableList()

    private val sampleLyrics = Lyrics(
        synced = listOf(
            SyncedLine(1_000, "Abre la puerta, que estoy afuera"),
            SyncedLine(20_000, "Estás grande ya, pero tú eres mi nena"),
            SyncedLine(40_000, "Mira cómo brilla todo por la noche"),
            SyncedLine(60_000, "Las luces bajan sobre el cristal"),
        )
    )

    private val noopPlayerActions = object : FullPlayerActions {
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

    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.playerUiState } returns MutableStateFlow(PlayerUiState())
    }
    private val settingsViewModel = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(SettingsUiState(showQueueHistory = false))
    }
    private val playlistViewModel = mockk<PlaylistViewModel>(relaxed = true)

    @Composable
    private fun ProductionPlayer360(cover: B41Renders.Cover) {
        val state = remember(cover) {
            buildFullPlayerUiState(
                env = PlayerSheetEnv(FullPlayerLoadingTweaks(delayAll = false), false, PlayerSheetState.EXPANDED, false, { 1f }),
                song = sampleSong(1).copy(title = "Cine", artist = "Gemini Sound"),
                queue = sampleSongs,
                queueSourceName = "All Songs",
                mediaItemIndex = 0,
                carouselStyle = CarouselStyle.NO_PEEK,
                artists = persistentListOf(),
                albumArtQuality = AlbumArtQuality.MEDIUM,
                audioMetadata = PlaybackAudioMetadata(mediaId = "1", mimeType = "audio/flac", sampleRate = 44100),
                showFileInfo = true,
                isFavorite = false,
                isBuffering = false,
                isShuffleEnabled = true,
                repeatMode = 0,
                isPlayingProvider = { true },
                playWhenReadyProvider = { true },
                currentPositionProvider = { 22_000L },
                totalDurationProvider = { 245_000L },
                lyrics = PlayerLyricsState(
                    searchState = LyricsSearchUiState.Idle,
                    stablePlayerStateFlow = MutableStateFlow(StablePlayerState()),
                    playbackPositionFlow = MutableStateFlow(0L),
                    externalLyricsEnabled = false,
                    lyricsSyncOffset = 0,
                    immersiveLyricsEnabled = false,
                    immersiveLyricsTimeout = 4000L,
                    isImmersiveTemporarilyDisabled = false,
                    isShuffleEnabled = true,
                    repeatMode = 0,
                    isFavorite = false,
                    lyricsProvider = { sampleLyrics },
                    lyricsResolveCompletedProvider = { true },
                ),
                isLandscape = false,
            )
        }
        CompositionLocalProvider(
            com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme provides
                dapColorScheme(com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent.current, LocalDapPalette.current)
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = { Box(Modifier.padding(top = 24.dp)) { DapPlayerHeader(lyricsActive = false, onCollapse = {}, onLyrics = {}, onDetails = {}) } },
            ) { padding ->
                FullPlayerPortraitContent(
                    paddingValues = padding,
                    albumCoverSection = { m ->
                        PlayerCoverSurface(
                            cover = state.cover,
                            env = state.env,
                            actions = noopPlayerActions,
                            modifier = m,
                        )
                    },
                    songMetadataSection = { PlayerTrackInfoSurface(state.trackInfo, state.env, noopPlayerActions, false, {}, {}) },
                    playerProgressSection = { PlayerProgressSurface(state.progress, state.env, noopPlayerActions) },
                    controlsSection = { PlayerControlsSurface(state.controls, state.env, noopPlayerActions) },
                    lyricsLinesSection = { PlayerLyricsLines(state.lyrics, state.progress, onOpen = {}) },
                )
            }
        }
    }

    @Composable
    private fun RenderScene(spec: Spec) {
        val cover = spec.cover
        when (spec.scene) {
            Scene.ARTIST_PICKER -> {
                B42Fix3Pages.Player(cover)
                val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                PlayerArtistPickerBottomSheet(
                    song = sampleSong(0).copy(artistId = 1L),
                    artists = persistentListOf(
                        Artist(id = 1L, name = "Primary Synth", songCount = 8),
                        Artist(id = 2L, name = "Featured Pulse", songCount = 3),
                    ),
                    sheetState = sheetState,
                    onDismiss = {},
                    onArtistClick = {},
                )
            }

            Scene.FOLDERS -> {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(top = 37.dp)) {
                        DapScreenTitle("Folders", Modifier.padding(horizontal = DapSpacing.Gutter))
                        FolderTabControls(
                            flatList = false,
                            onFlatListChange = {},
                            showSourceSwitch = true,
                            source = FolderSource.INTERNAL,
                            onSourceChange = {},
                            query = "",
                            onQueryChange = {},
                            volumeRoots = listOf("/storage/emulated/0", "/storage/1234-5678"),
                            selectedVolumeRoot = "/storage/emulated/0",
                            internalVolumeRoot = "/storage/emulated/0",
                        )
                        sampleSongs.take(4).forEachIndexed { idx, s ->
                            DapMediaRow(title = s.title, subtitle = s.artist, duration = "3:35", state = DapRowState.Normal, onClick = {})
                        }
                    }
                    LibraryFloatingActions(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.SectionGap),
                        showPlay = true,
                        onPlayClick = {},
                        showShuffle = true,
                        onShuffleClick = {},
                        showLocate = true,
                        onLocateClick = {},
                    )
                }
            }

            Scene.LYRICS -> {
                val playerFlow = remember { MutableStateFlow(StablePlayerState(currentSong = sampleSong(0), isPlaying = true, totalDuration = 215_000L, lyrics = sampleLyrics)) }
                val posFlow = remember { MutableStateFlow(22_000L) }
                Box(Modifier.fillMaxSize().padding(top = 37.dp, bottom = 16.dp)) {
                    LyricsSheet(
                        stablePlayerStateFlow = playerFlow,
                        playbackPositionFlow = posFlow,
                        lyricsSearchUiState = LyricsSearchUiState.Idle,
                        resetLyricsForCurrentSong = {},
                        onSearchLyrics = {},
                        onPickResult = {},
                        onManualSearch = { _, _ -> },
                        onImportLyrics = {},
                        onDismissLyricsSearch = {},
                        lyricsSyncOffset = 0,
                        onLyricsSyncOffsetChange = {},
                        lyricsTextStyle = MaterialTheme.typography.titleLarge,
                        colorScheme = MaterialTheme.colorScheme,
                        onLyricsAlignmentChange = {},
                        onShowLyricsTranslationChange = {},
                        onShowLyricsRomanizationChange = {},
                        onSeekTo = {},
                        onPlayPause = {},
                        immersiveLyricsEnabled = false,
                        immersiveLyricsTimeout = 4000L,
                        isImmersiveTemporarilyDisabled = false,
                        onSaveLyricsToFile = { _, _, _ -> },
                        externalLyricsEnabled = false,
                    )
                }
            }

            Scene.PLAYER360 -> {
                B42Fix3Pages.Player(cover)
            }

            Scene.MENU -> {
                Box(Modifier.fillMaxSize()) {
                    B42Fix3Pages.Player(cover)
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp)) {
                        DapMenuSurface {
                            DapMenuItem(text = "Track details", leadingIcon = { Icon(Icons.Rounded.Palette, null) }, onClick = {})
                            DapMenuItem(text = "Edit tags", leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {})
                            DapMenuItem(text = "Share audio", leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {})
                        }
                    }
                }
            }

            Scene.MULTISEL -> {
                B42Fix3Pages.Home(cover)
                MultiSelectionBottomSheet(
                    selectedSongs = sampleSongs.take(3).toImmutableList(),
                    favoriteSongIds = emptySet(),
                    onDismiss = {},
                    onPlayAll = {},
                    onAddToQueue = {},
                    onPlayNext = {},
                    onAddToPlaylist = {},
                    onToggleLikeAll = {},
                    onShareAll = {},
                    onDeleteAll = { _, _ -> },
                    onBatchEdit = {},
                )
            }

            Scene.BOOKMARK -> {
                B42Fix3Pages.Player(cover)
                AddBookmarkDialog(
                    currentProgressMs = 65_000L,
                    lyricTitle = "Crimson chorus line",
                    onSave = {},
                    onDismiss = {},
                )
            }

            Scene.HOME, Scene.THEME_HOME -> {
                val recent: ImmutableList<RecentlyPlayedSongUiModel> = remember {
                    (0 until 4).map { RecentlyPlayedSongUiModel(sampleSong(it), 1000L + it) }.toImmutableList()
                }
                val artists = remember {
                    listOf(
                        Artist(id = 1L, name = "Artist Alpha", songCount = 5, imageUrl = null),
                        Artist(id = 2L, name = "Beta Wave", songCount = 3, imageUrl = null),
                        Artist(id = 3L, name = "Gamma", songCount = 2, imageUrl = null),
                    )
                }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(37.dp).background(Dap.bar))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = DapSpacing.Sm), verticalArrangement = Arrangement.spacedBy(DapSpacing.SectionGap)) {
                        RecentlyPlayedSection(songs = recent, onSongClick = {}, onOpenAllClick = {}, currentSongId = "song-1")
                        RecentArtistsSection(artists = artists, onArtistClick = {})
                        StatsOverviewCard(summary = null, onClick = {})
                    }
                    DapMiniPlayer(title = "Cine", artist = "Gemini Sound", isPlaying = true, onPlayPause = {}, onNext = {}, onOpen = {}, artwork = {
                        Image(cover.bitmap.asImageBitmap(), null, Modifier.size(40.dp), contentScale = ContentScale.Crop)
                    })
                    DapBottomNav(
                        items = listOf(
                            DapNavItem(R.string.tab_home, DapIconKind.Home),
                            DapNavItem(R.string.search, DapIconKind.Search),
                            DapNavItem(R.string.tab_library, DapIconKind.Library),
                        ),
                        selectedIndex = 0,
                        onSelect = {},
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    )
                }
            }

            Scene.QUEUE -> {
                val density = LocalDensity.current
                val screenHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
                val strip = with(density) { (QueuePeekHeight + QueuePeekBottomGap).toPx() }
                val queueState = rememberQueueSheetState(
                    scope = rememberCoroutineScope(),
                    screenHeightPx = screenHeightPx,
                    density = density,
                    currentBottomPadding = 0.dp,
                    showPlayerContentArea = true,
                    currentSheetContentState = PlayerSheetState.EXPANDED,
                    foldedStripPx = strip,
                )
                androidx.compose.runtime.LaunchedEffect(queueState) {
                    queueState.queueSheetController.animate(true)
                }
                QueueSheetRuntimeEffects(
                    queueSheetController = queueState.queueSheetController,
                    queueSheetOffset = queueState.queueSheetOffset,
                    queueHiddenOffsetPx = queueState.queueHiddenOffsetPx,
                    showQueueSheet = true,
                    allowQueueSheetInteraction = true,
                    onTopEdgeReached = {},
                )
                val albumScheme = remember(cover) { generateColorSchemeFromSeed(cover.dominant).dark }
                val timerDisplay = remember { mutableStateOf<String?>(null) }
                val timerMinutes = remember { mutableStateOf<Int?>(null) }
                val playCount = remember { mutableStateOf(0f) }
                val endOfTrackTimer = remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    B42Pages.Player(cover, bottomRoom = QueuePeekHeight + QueuePeekBottomGap)
                    QueueSurface(
                        composed = true,
                        visible = true,
                        playerViewModel = playerViewModel,
                        albumColorScheme = albumScheme,
                        queueScrimAlpha = 0f,
                        queueSheetOffset = queueState.queueSheetOffset,
                        queueHiddenOffsetPx = queueState.queueHiddenOffsetPx,
                        queueSheetHeightPx = queueState.queueSheetHeightPx,
                        onQueueSheetHeightPxChange = queueState.onQueueSheetHeightPxChange,
                        foldedStripPx = strip,
                        onOpenQueue = {},
                        configurationResetKey = Unit,
                        currentPlaybackQueue = sampleSongs,
                        currentQueueSourceName = "All Songs",
                        currentMediaItemIndex = 0,
                        infrequentPlayerState = StablePlayerState(currentSong = sampleSongs[0], currentMediaItemIndex = 0, isPlaying = true),
                        activeTimerValueDisplay = timerDisplay,
                        activeTimerDurationMinutes = timerMinutes,
                        playCount = playCount,
                        isEndOfTrackTimerActive = endOfTrackTimer,
                        onDismissQueue = {},
                        onSongInfoClick = { _, _ -> },
                        onPlaySong = { _, _ -> },
                        onRemoveSong = { _, _ -> },
                        onReorder = { _, _ -> },
                        onToggleRepeat = {},
                        onToggleShuffle = {},
                        onClearQueue = {},
                        onSetPredefinedTimer = {},
                        onSetEndOfTrackTimer = {},
                        onOpenCustomTimePicker = {},
                        onCancelTimer = {},
                        onCancelCountedPlay = {},
                        onPlayCounter = {},
                        onRequestSaveAsPlaylist = { _, _, _ -> },
                        onQueueDragStart = {},
                        onQueueDrag = {},
                        onQueueRelease = { _, _ -> },
                        playlistViewModel = playlistViewModel,
                        settingsViewModel = settingsViewModel,
                    )
                }
            }

            Scene.SETTINGS -> {
                B42Fix3Pages.Settings(AppColorSource.COVER_GLASS)
            }

            Scene.THEME_SETTINGS -> {
                B42Fix3Pages.Settings(AppColorSource.THEME)
            }
        }
    }

    @Test
    fun render_all_b76_screens() {
        compose.mainClock.autoAdvance = false
        val specs = listOf(
            Spec("artist_picker", Scene.ARTIST_PICKER, darkSat, Fix3Mode.COVER_GLASS),
            Spec("artist_picker", Scene.ARTIST_PICKER, warmLight, Fix3Mode.FROM_ALBUM_ART),
            Spec("folders", Scene.FOLDERS, darkSat, Fix3Mode.COVER_GLASS),
            Spec("folders", Scene.FOLDERS, warmLight, Fix3Mode.FROM_ALBUM_ART),
            Spec("lyrics", Scene.LYRICS, darkSat, Fix3Mode.COVER_GLASS),
            Spec("player360", Scene.PLAYER360, warmLight, Fix3Mode.COVER_GLASS),
            Spec("player360", Scene.PLAYER360, nearGray, Fix3Mode.COVER_GLASS),
            Spec("menu", Scene.MENU, darkSat, Fix3Mode.COVER_GLASS),
            Spec("menu", Scene.MENU, warmLight, Fix3Mode.FROM_ALBUM_ART),
            Spec("multisel", Scene.MULTISEL, darkSat, Fix3Mode.COVER_GLASS),
            Spec("bookmark", Scene.BOOKMARK, darkSat, Fix3Mode.COVER_GLASS),
            Spec("bookmark", Scene.BOOKMARK, warmLight, Fix3Mode.FROM_ALBUM_ART),
            Spec("home", Scene.HOME, warmLight, Fix3Mode.COVER_GLASS),
            Spec("home", Scene.HOME, darkSat, Fix3Mode.FROM_ALBUM_ART),
            Spec("queue", Scene.QUEUE, darkSat, Fix3Mode.COVER_GLASS),
            Spec("settings", Scene.SETTINGS, warmLight, Fix3Mode.COVER_GLASS),
            Spec("theme_home", Scene.THEME_HOME, darkSat, Fix3Mode.THEME),
            Spec("theme_settings", Scene.THEME_SETTINGS, darkSat, Fix3Mode.THEME),
        )
        val active = mutableStateOf(specs.first())
        compose.setContent {
            val s by active
            key(s) {
                val stage = remember(s) { Fix3Stage(s.cover, s.mode) }
                stage.App(
                    expansion = if (s.scene in setOf(Scene.ARTIST_PICKER, Scene.LYRICS, Scene.PLAYER360, Scene.MENU, Scene.BOOKMARK, Scene.QUEUE)) 1f else 0f
                ) {
                    RenderScene(s)
                }
            }
        }
        fun pump(ms: Long = 1_200L) {
            compose.mainClock.advanceTimeByFrame()
            var remaining = ms
            while (remaining > 0L) {
                val step = minOf(100L, remaining)
                compose.mainClock.advanceTimeBy(step)
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                remaining -= step
            }
        }
        for (s in specs) {
            compose.runOnUiThread { active.value = s }
            pump(1_500L)
            val rootsFinder = compose.onAllNodes(androidx.compose.ui.test.isRoot())
            val nodes = rootsFinder.fetchSemanticsNodes()
            val activityIndex = nodes.indexOfFirst { B42Renders.dialogWindow(it) == null }
            check(activityIndex >= 0) { "no activity window for ${s.name}" }
            val actView = (nodes[activityIndex].root as androidx.compose.ui.platform.ViewRootForTest).view
            val actWindow = generateSequence<android.content.Context>(actView.context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<android.app.Activity>()
                .first()
                .window
            val out = B42Renders.windowOf(actWindow).copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            for ((index, node) in nodes.withIndex()) {
                if (index == activityIndex) continue
                val window = B42Renders.dialogWindow(node) ?: continue
                canvas.drawBitmap(B42Renders.windowOf(window), 0f, 0f, null)
            }
            val file = File(outDir, "${s.name}-${s.cover.name}-${s.mode.label}-$phase.png")
            file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("B76 render saved: ${file.name}")
        }
    }

    @Test
    fun check_360dp_lyrics_line_clearance_in_production_and_harness() {
        val showHarness = mutableStateOf(true)
        compose.setContent {
            key(showHarness.value) {
                Fix3Stage(warmLight, Fix3Mode.COVER_GLASS).App(expansion = 1f) {
                    if (showHarness.value) {
                        B42Fix3Pages.Player(warmLight)
                    } else {
                        ProductionPlayer360(warmLight)
                    }
                }
            }
        }
        settleFix3(compose)
        val harnessLyricsTop = compose.onNodeWithTag(PLAYER_LYRICS_LINES_TAG).getBoundsInRoot().top
        println("B76 360dp | harness lyrics top = $harnessLyricsTop")

        compose.runOnIdle { showHarness.value = false }
        settleFix3(compose)
        val prodCoverBottom = compose.onNodeWithTag(PlayerPagesTags.Cover).getBoundsInRoot().bottom
        val prodLyricsTop = compose.onNodeWithTag(PLAYER_LYRICS_LINES_TAG).getBoundsInRoot().top
        val prodGap = prodLyricsTop - prodCoverBottom
        println("B76 360dp | production cover bottom = $prodCoverBottom, lyrics top = $prodLyricsTop, gap = $prodGap")
        assertThat(prodGap.value).isAtLeast(12f)
    }

    // ── Section B: Contrast across five synthetic covers (warm light, dark sat, near gray, very bright, very dark) ──

    private fun veryBrightCover(): B41Renders.Cover {
        val bmp = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, 320f, 320f, paint { shader = LinearGradient(0f, 0f, 0f, 320f, 0xFFFFFFFF.toInt(), 0xFFF3ECE0.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(160f, 160f, 96f, paint { color = 0xFFFAF5E8.toInt() })
        return B41Renders.Cover("verybright", bmp)
    }

    private fun veryDarkCover(): B41Renders.Cover {
        val bmp = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, 320f, 320f, paint { shader = LinearGradient(0f, 0f, 320f, 320f, 0xFF040406.toInt(), 0xFF1A1424.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(160f, 160f, 90f, paint { color = 0xFF141828.toInt() })
        return B41Renders.Cover("verydark", bmp)
    }

    private fun contrastRatio(fg: Color, bg: Color): Float =
        GlassLuma.contrast(fg.luminance(), bg.luminance())

    @Test
    fun audit_five_synthetic_covers_contrast() {
        val covers = listOf(warmLight, darkSat, nearGray, veryBrightCover(), veryDarkCover())
        val base = DapBaseTheme.Graphite.palette
        val topArgb = GlassLuma.greyArgb(GlassBands.TOP)
        val topColor = Color(topArgb)
        val pageScrimColor = Color(GlassLuma.mixArgb(topArgb, base.background.toArgb(), GlassScrim.PAGE_ALPHA))
        val sheetScrimColor = Color(GlassLuma.mixArgb(topArgb, base.background.toArgb(), GlassScrim.SHEET_ALPHA))

        val rawGlass = base.onGlass(GlassBands.of(base, base.accentColor(DapAccent.Sage)))
        val errTop = contrastRatio(rawGlass.error, topColor)
        val warnTop = contrastRatio(rawGlass.warning, topColor)
        val errPage = contrastRatio(rawGlass.error, pageScrimColor)
        val warnPage = contrastRatio(rawGlass.warning, pageScrimColor)
        val errSheet = contrastRatio(rawGlass.error, sheetScrimColor)
        val warnSheet = contrastRatio(rawGlass.warning, sheetScrimColor)
        println(
            "B76 Glass ink adapted | error=#%06X warning=#%06X | on TOP=(%.2f, %.2f) page25=(%.2f, %.2f) sheet55=(%.2f, %.2f)".format(
                rawGlass.error.toArgb() and 0xFFFFFF,
                rawGlass.warning.toArgb() and 0xFFFFFF,
                errTop, warnTop, errPage, warnPage, errSheet, warnSheet,
            )
        )
        assertThat(errTop).isAtLeast(GlassBands.AA_TEXT)
        assertThat(warnTop).isAtLeast(GlassBands.AA_TEXT)
        assertThat(errPage).isAtLeast(GlassBands.AA_TEXT)
        assertThat(warnPage).isAtLeast(GlassBands.AA_TEXT)
        assertThat(errSheet).isAtLeast(GlassBands.AA_TEXT)
        assertThat(warnSheet).isAtLeast(GlassBands.AA_TEXT)
        assertThat(base.error).isEqualTo(Color(0xFFD32F2F))
        assertThat(base.warning).isEqualTo(Color(0xFFD9A066))

        for (cover in covers) {
            val seedArgb = selectSeedColorArgbFromPixels(cover.pixels, ColorExtractionConfig())
            val seed = Color(seedArgb)
            val rawScheme: ColorScheme = generateColorSchemeFromSeed(seed, AlbumArtPaletteStyle.TONAL_SPOT).dark
            val look = AlbumArtColors.look(base, seed, DapAccent.Sage)
            val glassLook = GlassBands.of(look.palette, look.accent)
            val adaptedPalette = look.palette.onGlass(glassLook)
            val scheme = com.lostf1sh.pixelplayeross.ui.ds.dapCoverColorScheme(rawScheme, adaptedPalette)

            val pairs = listOf(
                "onSurface / surface" to (scheme.onSurface to scheme.surface),
                "onSurface / surfaceContainerLow" to (scheme.onSurface to scheme.surfaceContainerLow),
                "onSurface / surfaceContainer" to (scheme.onSurface to scheme.surfaceContainer),
                "onSurface / surfaceContainerHigh" to (scheme.onSurface to scheme.surfaceContainerHigh),
                "onSurface / surfaceContainerHighest" to (scheme.onSurface to scheme.surfaceContainerHighest),
                "onSurfaceVariant / surfaceContainer" to (scheme.onSurfaceVariant to scheme.surfaceContainer),
                "onSurfaceVariant / surfaceContainerHigh" to (scheme.onSurfaceVariant to scheme.surfaceContainerHigh),
                "onPrimaryContainer / primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "onSecondaryContainer / secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "onTertiaryContainer / tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                "onErrorContainer / errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
                "onPrimary / primary" to (scheme.onPrimary to scheme.primary),
                "onSecondary / secondary" to (scheme.onSecondary to scheme.secondary),
                "onTertiary / tertiary" to (scheme.onTertiary to scheme.tertiary),
                "onError / error" to (scheme.onError to scheme.error),
                "onFill / look.accent" to (base.onFill to look.accent),
                "look.accent / surfaceContainer" to (look.accent to scheme.surfaceContainer),
                "glassInk.accent / topColor" to (glassLook.ink.accent to topColor),
                "scheme.error / topColor" to (scheme.error to topColor),
            )
            val minPair = pairs.minBy { (_, p) -> contrastRatio(p.first, p.second) }
            val failures = pairs.filter { (_, p) -> contrastRatio(p.first, p.second) < GlassBands.AA_TEXT }
            println(
                "B76 Cover=${cover.name} seed=#%06X scHigh=#%06X secCont=#%06X prim=#%06X min=%s(%.2f) fails=%s".format(
                    seed.toArgb() and 0xFFFFFF,
                    scheme.surfaceContainerHigh.toArgb() and 0xFFFFFF,
                    scheme.secondaryContainer.toArgb() and 0xFFFFFF,
                    scheme.primary.toArgb() and 0xFFFFFF,
                    minPair.first,
                    contrastRatio(minPair.second.first, minPair.second.second),
                    failures.joinToString { "${it.first}=%.2f".format(contrastRatio(it.second.first, it.second.second)) }.ifEmpty { "none" },
                )
            )
            assertThat(failures).isEmpty()
        }
    }

    // ── Section A2: Song-change recomposition probes ──

    private var bystanderCount = 0
    private var dapTextCount = 0
    private var dapPanelCount = 0
    private var accentReaderCount = 0
    private var tonalButtonCount = 0
    private var surfaceRaisedBoxCount = 0

    @Composable
    private fun BystanderRow() {
        SideEffect { bystanderCount++ }
    }

    @Composable
    private fun DapTextRow() {
        val c = Dap.primaryText
        DapDivider()
        SideEffect { dapTextCount++; @Suppress("UNUSED_EXPRESSION") c }
    }

    @Composable
    private fun DapPanelCard() {
        Box(Modifier.size(20.dp).background(Dap.panel))
        SideEffect { dapPanelCount++ }
    }

    @Composable
    private fun AccentDot() {
        val a = LocalDapAccent.current
        SideEffect { accentReaderCount++; @Suppress("UNUSED_EXPRESSION") a }
    }

    @Composable
    private fun TonalButtonWrapper() {
        DapTonalRoundButton(contentDescription = "Play", onClick = {}) { tint ->
            Box(Modifier.size(4.dp).background(tint))
            SideEffect { tonalButtonCount++ }
        }
    }

    @Composable
    private fun SurfaceRaisedPlaceholder() {
        Box(Modifier.size(20.dp).background(Dap.surfaceRaised))
        SideEffect { surfaceRaisedBoxCount++ }
    }

    @Composable
    private fun RecompProbeTree() {
        Surface(Modifier, color = MaterialTheme.colorScheme.background) {
            Column {
                BystanderRow()
                DapTextRow()
                DapPanelCard()
                AccentDot()
                TonalButtonWrapper()
                SurfaceRaisedPlaceholder()
            }
        }
    }

    @Test
    fun song_change_recomposes_only_tint_and_accent_readers_not_the_rest_of_the_tree() {
        fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        val seeds = listOf(Color(0xFF6D0922), Color(0xFF1A6FA8), Color(0xFF2D8A4E), Color(0xFFB85A1A), Color(0xFF7B3FA0))
        val recompScheme = MutableStateFlow<ColorSchemePair?>(generateColorSchemeFromSeed(seeds[0]))
        val recompUri = MutableStateFlow<String?>("content://art/1")
        val fields = CoverGlassFields(mockk<Context>(relaxed = true)).also { f ->
            f.decoder = { uri ->
                Triple(96, 96, IntArray(96 * 96) { i ->
                    rgb((i % 96 * 2 + uri.hashCode() * 40) and 255, (i / 96 * 2 + uri.hashCode() * 70) and 255, (uri.hashCode() * 90) and 255)
                })
            }
        }
        var painting: AppPainting? = null
        compose.setContent {
            val p = rememberAppPainting(
                appColorSource = AppColorSource.COVER_GLASS,
                appThemeMode = "dark",
                storedBaseTheme = DapBaseTheme.Graphite,
                accent = DapAccent.Sage,
                systemDark = true,
                albumArtColorScheme = recompScheme,
                themedCoverUri = recompUri,
                fields = fields,
            )
            painting = p
            val painted = p.painted
            PixelPlayerTheme(
                darkTheme = painted.useDark,
                accent = DapAccent.Sage,
                baseTheme = painted.baseTheme,
                palette = painted.look?.palette ?: painted.baseTheme.palette,
                accentColorOverride = painted.look?.accent,
                coverSchemeOverride = painted.coverScheme,
                coverAccentOverride = painted.coverAccent,
            ) {
                CompositionLocalProvider(LocalCoverGlass provides p.scene.takeIf { painted.glassShown }) {
                    RecompProbeTree()
                }
            }
        }
        fun settle(previous: Any?) {
            compose.waitUntil(15_000) {
                val x = painting!!.scene.crossfade
                x.current != null && x.current !== previous && x.previous == null && x.blend == 1f
            }
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitForIdle()
        }
        compose.waitForIdle()
        settle(null)
        val b0 = bystanderCount; val t0 = dapTextCount; val p0 = dapPanelCount
        val a0 = accentReaderCount; val d0 = tonalButtonCount; val s0 = surfaceRaisedBoxCount
        repeat(4) { i ->
            val prevField = painting!!.scene.crossfade.current
            recompUri.value = "content://art/${i + 2}"
            recompScheme.value = generateColorSchemeFromSeed(seeds[i + 1])
            settle(prevField)
        }
        val db = bystanderCount - b0; val dt = dapTextCount - t0; val dp = dapPanelCount - p0
        val da = accentReaderCount - a0; val dd = tonalButtonCount - d0; val ds = surfaceRaisedBoxCount - s0
        println("B76 recomposition (4 song changes) | bystander=$db dapText=$dt dapPanel=$dp accentReader=$da tonalButton=$dd surfaceRaisedBox=$ds")
        assertThat(db).isEqualTo(0)
        assertThat(dt).isEqualTo(0)
        assertThat(dp).isEqualTo(0)
        assertThat(da).isEqualTo(4)
        assertThat(dd).isEqualTo(4)
        assertThat(ds).isEqualTo(4)
    }
}
