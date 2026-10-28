package com.lostf1sh.pixelplayeross.presentation.components.player

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
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
open class B43PlayerRedesignTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val language get() = context.resources.configuration.locales[0].language
    private val tag get() = if (language == "en") "" else "-$language"
    private val touch by lazy { SyntheticTouch(compose) }

    private fun song(n: Int, title: String) = Song(
        id = "$n", title = title, artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$n.flac",
        contentUriString = "content://$n", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val first = song(1, "La Costa Del Silencio")
    private val second = song(2, "Fiesta Pagana")
    private val third = song(3, "Molinos de Viento")
    private val queue: ImmutableList<Song> = (listOf(first, second, third) + (4..40).map { song(it, "Cancion $it") }).toImmutableList()

    private val words = mutableStateOf<com.lostf1sh.pixelplayeross.data.model.Lyrics?>(
        com.lostf1sh.pixelplayeross.data.model.Lyrics(synced = listOf(
            com.lostf1sh.pixelplayeross.data.model.SyncedLine(0, "Sung line"), com.lostf1sh.pixelplayeross.data.model.SyncedLine(100000, "Next line"))))
    private fun save(name: String) {
        val bitmap = com.lostf1sh.pixelplayeross.ui.glass.B42Renders.screen(compose)
        val root = java.io.File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B43-evidence", System.getenv("B43_VARIANT") ?: "after").apply { mkdirs() }
        java.io.File(root, "$name-${bitmap.width}x${bitmap.height}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private var sheet: QueueSheetState? = null
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
        return buildFullPlayerUiState(
            env = PlayerSheetEnv(
                loadingTweaks = FullPlayerLoadingTweaks(delayAll = false), isSheetDragGestureActive = false,
                currentSheetState = PlayerSheetState.EXPANDED, allowRealtimeUpdates = false, expansionFractionProvider = { 1f },
            ),
            song = current, queue = songs, queueSourceName = "All Songs", mediaItemIndex = index,
            carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
            audioMetadata = PlaybackAudioMetadata(mediaId = current.id, mimeType = "audio/flac", sampleRate = 44100),
            showFileInfo = true, isFavorite = false, isBuffering = false, isShuffleEnabled = false, repeatMode = repeat,
            isPlayingProvider = { false }, playWhenReadyProvider = { false },
            currentPositionProvider = { 83_000L }, totalDurationProvider = { 183_000L },
            lyrics = PlayerLyricsState(
                searchState = LyricsSearchUiState.Idle,
                stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = current, isPlaying = false, totalDuration = 183_000L, lyrics = words.value, lyricsResolveCompleted = true)),
                playbackPositionFlow = MutableStateFlow(9_000L),
                externalLyricsEnabled = true, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
                isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = repeat, isFavorite = false,
                lyricsProvider = { words.value }, lyricsResolveCompletedProvider = { true }, underCover = true,
            ),
            isLandscape = false,
        )
    }

    /**
     * The full player and the queue sheet, wired as `UnifiedPlayerSheetV2` wires them: the sheet's state holder and its one controller, the
     * player's queue swipe handing its three calls to that controller, the strip (drawn by the sheet) opening it.
     */
    private fun show(songs: ImmutableList<Song> = queue, index: Int = 0, strip: Boolean = true, repeat: Int = Player.REPEAT_MODE_OFF) {
        val playing = songs[index]
        compose.setContent {
            val baseDensity = LocalDensity.current
            val density = Density(baseDensity.density, 1.3f)
            val screenHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
            val foldedStripPx = if (strip) with(density) { (QueuePeekHeight + QueuePeekBottomGap).toPx() } else 0f
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
                override fun onQueueDragStart() { calls += "playerSwipeStart"; controller.beginDrag() }
                override fun onQueueDrag(dragAmount: Float) { controller.dragBy(dragAmount) }
                override fun onQueueRelease(totalDrag: Float, velocity: Float) { calls += "playerSwipeRelease"; controller.endDrag(totalDrag, velocity) }
                override fun onSeek(positionMs: Long) { calls += "seek" }
                override fun onCollapse() { calls += "close" }
            }
            val signal = remember { PlayerPageSignal() }
            CompositionLocalProvider(LocalPlayerPageSignal provides signal, LocalDensity provides density) {
            Box(Modifier.fillMaxSize().background(Dap.background)) {
                CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) { FullPlayerScreen(uiState(songs, index, repeat), actions) }
                QueueSurface(
                    composed = true, visible = state.showQueueSheet, playerViewModel = playerViewModel, albumColorScheme = darkColorScheme(),
                    queueScrimAlpha = 0f, queueSheetOffset = state.queueSheetOffset, queueHiddenOffsetPx = state.queueHiddenOffsetPx,
                    queueSheetHeightPx = state.queueSheetHeightPx, onQueueSheetHeightPxChange = state.onQueueSheetHeightPxChange,
                    foldedStripPx = foldedStripPx, onOpenQueue = { calls += "open"; controller.animate(true) },
                    configurationResetKey = Unit, currentPlaybackQueue = songs, currentQueueSourceName = "All Songs",
                    currentMediaItemIndex = index,
                    infrequentPlayerState = StablePlayerState(currentSong = playing, currentMediaItemIndex = index, isPlaying = false, repeatMode = repeat),
                    activeTimerValueDisplay = timerDisplay, activeTimerDurationMinutes = timerMinutes, playCount = playCount,
                    isEndOfTrackTimerActive = endOfTrackTimer, onDismissQueue = { controller.animate(false) },
                    onSongInfoClick = { _, _ -> }, onPlaySong = { _, _ -> }, onRemoveSong = { _, _ -> }, onReorder = { _, _ -> },
                    onToggleRepeat = {}, onToggleShuffle = {}, onClearQueue = {}, onSetPredefinedTimer = {}, onSetEndOfTrackTimer = {},
                    onOpenCustomTimePicker = {}, onCancelTimer = {}, onCancelCountedPlay = {}, onPlayCounter = {}, onRequestSaveAsPlaylist = { _, _, _ -> },
                    onQueueDragStart = { calls += "sheetDragStart"; controller.beginDrag() },
                    onQueueDrag = { amount -> controller.dragBy(amount) },
                    onQueueRelease = { total, velocity -> calls += "sheetDragRelease"; controller.endDrag(total, velocity) },
                    playlistViewModel = playlistViewModel, settingsViewModel = settingsViewModel,
                )
            }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        save("player")
    }

    private val offset get() = sheet!!.queueSheetOffset.value
    private val folded get() = sheet!!.queueHiddenOffsetPx
    private val isOpen get() = sheet!!.showQueueSheet

    private fun spoken(next: Song?) = listOfNotNull(s(R.string.queue_title_next), next?.title).joinToString(", ")
    private fun strip(next: Song? = second) = compose.onNode(hasContentDescription(spoken(next)))
    private val stripBounds get() = strip().fetchSemanticsNode().boundsInRoot
    private val controlsBounds get() = compose.onNodeWithContentDescription(s(R.string.cd_play)).fetchSemanticsNode().boundsInRoot
    private val seekBar get() = compose.onNode(
        SemanticsMatcher("the seek bar") { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) || it.config.contains(SemanticsActions.SetProgress) },
        useUnmergedTree = true,
    ).fetchSemanticsNode().boundsInRoot

    @Test fun `strip lyrics opens its page and the top chevron is absent`() {
        show()
        compose.onAllNodesWithContentDescription(s(R.string.player_header_collapse)).assertCountEquals(0)
        compose.onNodeWithContentDescription(s(R.string.player_header_lyrics)).performClick()
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.onNodeWithTag(PlayerPagesTags.Lyrics).assertExists()
        save("lyrics")
        assertThat(isOpen).isFalse()
    }
    @Test fun `strip details opens its page without opening the queue`() {
        show()
        compose.onNodeWithContentDescription(s(R.string.player_header_details)).performClick()
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.onNodeWithTag(PlayerPagesTags.Details).assertExists()
        save("details")
        assertThat(isOpen).isFalse()
    }
    @Test fun `a text tap opens the queue`() {
        show()
        val b = stripBounds
        touch.tap(Offset(b.left + 60f, b.center.y))
        assertThat(isOpen).isTrue()
    }
    @Test fun `an upward drag starting on the lyrics button opens the queue and never the lyrics page`() {
        show()
        val button = compose.onNodeWithContentDescription(s(R.string.player_header_lyrics)).fetchSemanticsNode().boundsInRoot
        touch.swipeAndHold(button.center, dyPx = -700f, millis = 700, settleMs = 0) { }
        compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
        assertThat(isOpen).isTrue()
        compose.onNodeWithTag(PlayerPagesTags.Player).assertExists()
        assertThat(calls).contains("sheetDragStart")
    }
    @Test fun `the player exposes an accessible close action`() {
        show()
        val actions = compose.onNodeWithTag(PlayerPagesTags.Pager).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val close = actions.firstOrNull { it.label == "Close player" }
        assertThat(close).isNotNull()
        compose.runOnUiThread { close!!.action() }
        assertThat(calls).contains("close")
    }
    @Test fun `enabling under cover lyrics reserves the same cover size when the next song has no lyrics`() {
        show()
        fun bounds() = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(PlayerPagesTags.Cover)), useUnmergedTree = true)
            .fetchSemanticsNodes().first { it.boundsInRoot.width > 100 && it.boundsInRoot.height > 100 }.boundsInRoot
        val before = bounds()
        compose.runOnIdle { words.value = null }; compose.waitForIdle()
        assertThat(bounds().width).isEqualTo(before.width)
        assertThat(bounds().height).isEqualTo(before.height)
    }
    @Test fun `at large font scale the whole cover stays square with lyrics enabled`() {
        show()
        val nodes = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(PlayerPagesTags.Cover)), useUnmergedTree = true).fetchSemanticsNodes()
        val art = nodes.first { it.boundsInRoot.width > 100 && it.boundsInRoot.height > 100 }.boundsInRoot
        assertThat(kotlin.math.abs(art.width - art.height)).isAtMost(1f)
        println("B43 full player cover at 1.3x = ${art.width / 2} by ${art.height / 2} dp")
    }
}

@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class B43PlayerRedesignTallTest : B43PlayerRedesignTest()
