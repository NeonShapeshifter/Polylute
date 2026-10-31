package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.B33Renders
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch.Delivery
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.components.QueueFold
import com.lostf1sh.pixelplayeross.presentation.components.QueueSurface
import com.lostf1sh.pixelplayeross.presentation.components.scoped.QueueSheetRuntimeEffects
import com.lostf1sh.pixelplayeross.presentation.components.scoped.QueueSheetState
import com.lostf1sh.pixelplayeross.presentation.components.scoped.queueClosedOffsetPx
import com.lostf1sh.pixelplayeross.presentation.components.scoped.rememberQueueSheetState
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B33-U1: one queue. The "A continuación" strip at the foot of the full player **is the queue sheet folded**: the sheet rests with its top
 * showing, a tap opens it, a drag moves it under the finger, the player's own swipe from the transport drags the very same sheet.
 *
 * The real [FullPlayerScreen], the real [QueueSurface] (with the real queue sheet) and the real [QueueSheetController], wired as the player's
 * sheet wires them, over made-up songs at 360 x 805 dp (xhdpi, 2 px per dp), with real touch events. There is **one** state holder: every test
 * reads the sheet's one offset.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class B44Fix1PlayerInsetsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val language get() = context.resources.configuration.locales[0].language
    private val tag get() = if (language == "en") "" else "-$language"
    private val touch by lazy { SyntheticTouch(compose) }

    private fun song(n: Int, title: String) = Song(
        id = "$n", title = title, artist = "Post Malone, Luke Combs", artistId = 7L, album = "F-1 Trillion: Long Bed", albumId = 9L, path = "/m/$n.flac",
        contentUriString = "content://$n", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val first = song(1, "Guy For That (Feat. Luke Combs)")
    private val second = song(2, "Fiesta Pagana")
    private val third = song(3, "Molinos de Viento")
    private val queue: ImmutableList<Song> = (listOf(first, second, third) + (4..40).map { song(it, "Cancion $it") }).toImmutableList()

    private val renderFont = mutableStateOf(1f)
    private val renderLyrics = mutableStateOf(true)
    private val renderBookmark = mutableStateOf(true)
    private val words = mutableStateOf<com.lostf1sh.pixelplayeross.data.model.Lyrics?>(
        com.lostf1sh.pixelplayeross.data.model.Lyrics(synced = listOf(
            com.lostf1sh.pixelplayeross.data.model.SyncedLine(0, "But, buddy, what I really need"), com.lostf1sh.pixelplayeross.data.model.SyncedLine(100000, "Is someone to put her tires back in the drive"))))
    private fun save(name: String) {
        val bitmap = com.lostf1sh.pixelplayeross.ui.glass.B42Renders.screen(compose)
        val phase = if (java.io.File(".").canonicalPath.contains("B44-fix1-before")) "before" else "after"
        val root = java.io.File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B44-fix1-evidence", phase).apply { mkdirs() }
        java.io.File(root, "$name-${bitmap.width}x${bitmap.height}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private var hostView: android.view.View? = null
    private var observedInsets = 0 to 0
    private var sheet: QueueSheetState? = null

    @org.junit.Before fun fileHeader() {
        io.mockk.mockkObject(com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader)
        every { com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader.readTechnical(any<android.content.Context>(), any<android.net.Uri>()) } returns
            com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader.TechnicalAudioInfo("Flac", 44100, 24)
    }
    @org.junit.After fun cleanupHeader() {
        io.mockk.unmockkObject(com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader)
    }
    private val calls = mutableListOf<String>()
    private val offsetsWhileDragging = mutableListOf<Float>()

    private val timerDisplay = mutableStateOf<String?>(null)
    private val timerMinutes = mutableStateOf<Int?>(null)
    private val playCount = mutableStateOf(0f)
    private val endOfTrackTimer = mutableStateOf(false)

    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.playerUiState } returns MutableStateFlow(PlayerUiState())
    }
    private val settingsViewModel = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(SettingsUiState(showQueueHistory = false))
    }
    private val playlistViewModel = mockk<PlaylistViewModel>(relaxed = true)

    private fun uiState(songs: ImmutableList<Song>, index: Int, repeat: Int = Player.REPEAT_MODE_OFF): FullPlayerUiState {
        val current = songs[index]
        val built = buildFullPlayerUiState(
            env = PlayerSheetEnv(
                loadingTweaks = FullPlayerLoadingTweaks(delayAll = false), isSheetDragGestureActive = false,
                currentSheetState = PlayerSheetState.EXPANDED, allowRealtimeUpdates = false, expansionFractionProvider = { 1f },
            ),
            song = current, queue = songs, queueSourceName = "All Songs", mediaItemIndex = index,
            carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
            audioMetadata = PlaybackAudioMetadata(mediaId = current.id, mimeType = "audio/flac", sampleRate = 44100, bitDepth = 24),
            showFileInfo = true, isFavorite = false, isBuffering = false, isShuffleEnabled = false, repeatMode = repeat,
            isPlayingProvider = { false }, playWhenReadyProvider = { false },
            currentPositionProvider = { 83_000L }, totalDurationProvider = { 183_000L },
            lyrics = PlayerLyricsState(
                searchState = LyricsSearchUiState.Idle,
                stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = current, isPlaying = false, totalDuration = 183_000L, lyrics = words.value, lyricsResolveCompleted = true)),
                playbackPositionFlow = MutableStateFlow(9_000L),
                externalLyricsEnabled = true, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
                isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = repeat, isFavorite = false,
                lyricsProvider = { words.value }, lyricsResolveCompletedProvider = { true }, underCover = renderLyrics.value,
            ),
            isLandscape = false,
        )
        // Before-tree compatibility: its info model has no bookmark visibility option yet.
        val info = built.trackInfo
        val constructor = info.javaClass.constructors.firstOrNull { !it.isSynthetic && it.parameterCount == 6 }
        return if (constructor == null) built else built.copy(trackInfo = constructor.newInstance(
            info.song, info.artists, info.isFavorite, info.isBuffering, info.isPlayingProvider, renderBookmark.value,
        ) as PlayerTrackInfoState)
    }

    /**
     * The full player and the queue sheet, wired as `UnifiedPlayerSheetV2` wires them: the sheet's state holder and its one controller, the
     * player's queue swipe handing its three calls to that controller, the strip (drawn by the sheet) opening it.
     */
    private fun show(songs: ImmutableList<Song> = queue, index: Int = 0, strip: Boolean = true, repeat: Int = Player.REPEAT_MODE_OFF) {
        val playing = songs[index]
        compose.setContent {
            val view = androidx.compose.ui.platform.LocalView.current
            val baseDensity = LocalDensity.current
            val density = Density(baseDensity.density, renderFont.value)
            val screenHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
            val status = WindowInsets.statusBars.getTop(density)
            val navigation = WindowInsets.navigationBars.getBottom(density)
            SideEffect {
                hostView = view
                observedInsets = status to navigation
            }
            val foldedStripPx = if (strip) with(density) { (QueuePeekHeight + QueuePeekBottomGap).toPx() } + navigation else 0f
            val state = rememberQueueSheetState(
                scope = rememberCoroutineScope(), screenHeightPx = screenHeightPx, density = density, currentBottomPadding = 0.dp,
                showPlayerContentArea = true, currentSheetContentState = PlayerSheetState.EXPANDED, foldedStripPx = foldedStripPx,
            )
            SideEffect { sheet = state }
            QueueSheetRuntimeEffects(
                queueSheetController = state.queueSheetController, queueSheetOffset = state.queueSheetOffset,
                queueHiddenOffsetPx = state.queueHiddenOffsetPx, showQueueSheet = state.showQueueSheet,
                allowQueueSheetInteraction = state.allowQueueSheetInteraction, onTopEdgeReached = {},
            )
            val controller = state.queueSheetController
            val actions = object : FullPlayerActions by NoActions() {
                override fun onQueueDragStart() {
                    calls += "playerSwipeStart"
                    controller.beginDrag()
                }
                override fun onQueueDrag(dragAmount: Float) { controller.dragBy(dragAmount) }
                override fun onQueueRelease(totalDrag: Float, velocity: Float) {
                    calls += "playerSwipeRelease"
                    controller.endDrag(totalDrag, velocity)
                }
                override fun onSeek(positionMs: Long) { calls += "seek" }
                override fun onCollapse() { calls += "close" }
                override fun onOpenAlbum(albumId: Long) { calls += "album-$albumId" }
            }
            val signal = remember { PlayerPageSignal() }
            CompositionLocalProvider(LocalPlayerPageSignal provides signal, LocalDensity provides density) {
            com.lostf1sh.pixelplayeross.ui.ds.DapTheme(darkTheme = true) {
            Box(Modifier.fillMaxSize().background(Dap.background)) {
                CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) { FullPlayerScreen(uiState(songs, index, repeat), actions) }
                QueueSurface(
                    composed = true, visible = state.showQueueSheet, playerViewModel = playerViewModel, albumColorScheme = darkColorScheme(),
                    queueScrimAlpha = 0f, queueSheetOffset = state.queueSheetOffset, queueHiddenOffsetPx = state.queueHiddenOffsetPx,
                    queueSheetHeightPx = state.queueSheetHeightPx, onQueueSheetHeightPxChange = state.onQueueSheetHeightPxChange,
                    foldedStripPx = foldedStripPx, onOpenQueue = {
                        calls += "open"
                        controller.animate(true)
                    },
                    configurationResetKey = Unit, currentPlaybackQueue = songs, currentQueueSourceName = "All Songs",
                    currentMediaItemIndex = index,
                    infrequentPlayerState = StablePlayerState(currentSong = playing, currentMediaItemIndex = index, isPlaying = false, repeatMode = repeat),
                    activeTimerValueDisplay = timerDisplay, activeTimerDurationMinutes = timerMinutes, playCount = playCount,
                    isEndOfTrackTimerActive = endOfTrackTimer, onDismissQueue = { controller.animate(false) },
                    onSongInfoClick = { _, _ -> }, onPlaySong = { _, _ -> }, onRemoveSong = { _, _ -> }, onReorder = { _, _ -> },
                    onToggleRepeat = {}, onToggleShuffle = {}, onClearQueue = {}, onSetPredefinedTimer = {}, onSetEndOfTrackTimer = {},
                    onOpenCustomTimePicker = {}, onCancelTimer = {}, onCancelCountedPlay = {}, onPlayCounter = {}, onRequestSaveAsPlaylist = { _, _, _ -> },
                    onQueueDragStart = {
                        calls += "sheetDragStart"
                        controller.beginDrag()
                    },
                    onQueueDrag = { amount -> controller.dragBy(amount) },
                    onQueueRelease = { total, velocity ->
                        calls += "sheetDragRelease"
                        controller.endDrag(total, velocity)
                    },
                    playlistViewModel = playlistViewModel, settingsViewModel = settingsViewModel,
                )
            }
            }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(
                hostView!!,
                androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 74, 0, 0))
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 44))
                    .build(),
            )
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        save("player")
    }

    @Test fun `HONOR insets reserve both lyrics lines format and the real Up next strip`() {
        show()
        compose.waitUntil(3000) { compose.onAllNodes(hasText("24-bit", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertThat(observedInsets).isEqualTo(74 to 44)
        val failures = mutableListOf<String>()
        for (font in listOf(1f, 1.3f)) for (lyrics in listOf(false, true)) for (bookmark in listOf(true, false)) {
            compose.runOnIdle {
                renderFont.value = font
                renderLyrics.value = lyrics
                renderBookmark.value = bookmark
            }
            compose.waitForIdle()
            val name = "honor-player-font-$font-lyrics-$lyrics-bookmark-$bookmark"
            save(name)
            val art = artwork()
            val viewport = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxBy { it.boundsInRoot.width }.boundsInRoot
            val controls = compose.onNodeWithContentDescription(s(R.string.cd_play)).fetchSemanticsNode().boundsInRoot
            val strip = compose.onNodeWithContentDescription(s(R.string.queue_title_next) + ", " + second.title).fetchSemanticsNode().boundsInRoot
            println("B44-fix1 HOST $name viewport=${viewport.width / 2}x${viewport.height / 2}dp insets=37/22dp art=${art.width / 2}dp controlsBottom=${controls.bottom / 2}dp stripTop=${strip.top / 2}dp")
            if (kotlin.math.abs(art.width - art.height) > 1) failures += "$name square"
            if (!lyrics && kotlin.math.abs(art.width - viewport.width) > 1) failures += "$name edge to edge"
            if (controls.bottom > strip.top) failures += "$name controls under strip"
            if (lyrics && art.width < viewport.width - 1 && kotlin.math.abs(strip.top - controls.bottom - 16f) > 2) failures += "$name unused cover space"
            if (lyrics && font == 1f && viewport.width == 720f && art.width / 2 <= 306f) failures += "$name no growth over device B44"
            if (lyrics) {
                compose.onNodeWithText("But, buddy, what I really need", useUnmergedTree = true).assertExists()
                compose.onNodeWithText("Is someone to put her tires back in the drive", useUnmergedTree = true).assertExists()
            }
            for (text in listOf(first.title, first.artist, first.album)) {
                val node = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
                    .firstOrNull { it.boundsInRoot.top < controls.top }
                if (node == null || node.boundsInRoot.top < 74 || node.boundsInRoot.bottom > controls.top) failures += "$name metadata $text"
            }
        }
        assertThat(failures).isEmpty()
        compose.runOnIdle { renderLyrics.value = true }
        compose.waitForIdle()
        val before = artwork().width
        compose.runOnIdle {
            words.value = com.lostf1sh.pixelplayeross.data.model.Lyrics(synced = listOf(
                com.lostf1sh.pixelplayeross.data.model.SyncedLine(0, "A much longer sung line that needs two lines"),
                com.lostf1sh.pixelplayeross.data.model.SyncedLine(100000, "Another next line")))
        }
        compose.waitForIdle()
        assertThat(artwork().width).isEqualTo(before)
    }

    private fun artwork(): androidx.compose.ui.geometry.Rect = compose.onAllNodes(
        hasClickAction() and hasAnyAncestor(hasTestTag(PlayerPagesTags.Cover)), useUnmergedTree = true,
    ).fetchSemanticsNodes().first { it.boundsInRoot.width > 100 && it.boundsInRoot.height > 100 }.boundsInRoot
}

@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class B44Fix1PlayerInsetsTallTest : B44Fix1PlayerInsetsTest()
