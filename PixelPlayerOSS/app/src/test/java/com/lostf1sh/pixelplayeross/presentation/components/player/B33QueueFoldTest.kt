package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
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
open class B33QueueFoldTest {

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
                stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = current, isPlaying = false, totalDuration = 183_000L, lyrics = null, lyricsResolveCompleted = true)),
                playbackPositionFlow = MutableStateFlow(9_000L),
                externalLyricsEnabled = true, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
                isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = repeat, isFavorite = false,
                lyricsProvider = { null }, lyricsResolveCompletedProvider = { true },
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
            val density = LocalDensity.current
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
            }
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
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
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

    // ── folded: the sheet at rest ────────────────────────────────────────────────────────────────────

    @Test
    fun `at rest the sheet is folded, its top shows under the transport with Up next and the next song`() {
        show()
        // the one offset is the folded one: the sheet's top is 68 dp (the strip) above the bottom of the screen
        assertThat(folded).isGreaterThan(0f)
        assertThat(offset).isWithin(2f).of(folded)
        assertThat(isOpen).isFalse()
        val strip = stripBounds
        assertThat(strip.top).isAtLeast(controlsBounds.bottom)
        assertThat(strip.bottom).isAtMost(1610f)
        assertThat(strip.height).isAtLeast(48f * 2f)
        B33Renders.save(compose, "u1-folded$tag")
    }

    @Test
    fun `the player's layout draws no peek of its own, it only keeps the room the sheet's strip takes`() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Dap.background)) {
                CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) { FullPlayerScreen(uiState(queue, 0), NoActions()) }
            }
        }
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithContentDescription(spoken(second)).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithContentDescription(s(R.string.player_queue_peek_open)).fetchSemanticsNodes()).isEmpty()
        // the transport ends at least a strip above the bottom of the screen
        val transportBottom = controlsBounds.bottom
        assertThat(1610f - transportBottom).isAtLeast((QueuePeekHeight + QueuePeekBottomGap).value * 2f)
    }

    @Test
    fun `with nothing next the strip is the label alone, and a queue that repeats names its first song`() {
        val three = persistentListOf(first, second, third)
        show(songs = three, index = 2)
        strip(next = null).assertExists()
    }

    @Test
    fun `a queue that repeats names its first song at the end`() {
        val three = persistentListOf(first, second, third)
        show(songs = three, index = 2, repeat = Player.REPEAT_MODE_ALL)
        strip(next = first).assertExists()
    }

    // ── the strip opens the sheet ────────────────────────────────────────────────────────────────────

    @Test
    fun `a tap on the strip opens the sheet fully`() {
        show()
        touch.tap(stripBounds.center)
        assertThat(isOpen).isTrue()
        assertThat(offset).isWithin(1f).of(0f)
        assertThat(calls).contains("open")
        B33Renders.save(compose, "u1-open$tag")
    }

    @Test
    fun `a drag up from the strip unfolds the same sheet under the finger, continuously`() {
        show()
        val start = stripBounds.center
        val readings = mutableListOf<Float>()
        touch.swipeAndHold(start, dyPx = -700f, millis = 700, settleMs = 0) {
            readings += offset
            // the picture the owner asked for: the sheet half-way, with the finger still down
            B33Renders.save(compose, "u1-half$tag")
        }
        // held 700 px up (less the touch slop): the sheet is where the finger took it, not folded and not open
        assertThat(readings.single()).isWithin(60f).of(folded - 700f)
        assertThat(isOpen).isTrue()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        // released with a committed drag: it goes on to open
        assertThat(offset).isWithin(1f).of(0f)
    }

    @Test
    fun `a short slow drag up folds it back where it was`() {
        show()
        touch.swipe(stripBounds.center, dyPx = -50f, millis = 1_200, delivery = Delivery.FRAMED)
        assertThat(offset).isWithin(2f).of(folded)
        assertThat(isOpen).isFalse()
    }

    @Test
    fun `a drag down from the strip does nothing, the sheet cannot go lower than folded`() {
        show()
        touch.swipe(stripBounds.center, dyPx = 300f, millis = 200, delivery = Delivery.FRAMED)
        assertThat(offset).isWithin(2f).of(folded)
        assertThat(isOpen).isFalse()
    }

    @Test
    fun `dragging the open sheet down folds it back to the strip`() {
        show()
        touch.tap(stripBounds.center)
        assertThat(offset).isWithin(1f).of(0f)
        // the sheet's own header, near its top
        touch.swipe(Offset(360f, 200f), dyPx = 700f, millis = 250, delivery = Delivery.FRAMED)
        assertThat(isOpen).isFalse()
        assertThat(offset).isWithin(2f).of(folded)
        strip().assertExists()
    }

    // ── the player's own swipe drags this same sheet ─────────────────────────────────────────────────

    @Test
    fun `a swipe up from the transport moves the same sheet, the one offset`() {
        show()
        val readings = mutableListOf<Float>()
        touch.swipeAndHold(controlsBounds.center, dyPx = -700f, millis = 700, settleMs = 0) { readings += offset }
        assertThat(calls).contains("playerSwipeStart")
        assertThat(readings.single()).isWithin(60f).of(folded - 700f)
        assertThat(isOpen).isTrue()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertThat(offset).isWithin(1f).of(0f)
        // it is the sheet the strip opens: there is no second sheet to have moved
        assertThat(calls.count { it == "sheetDragStart" }).isEqualTo(0)
    }

    @Test
    fun `a seek drag moves nothing and opens nothing`() {
        show()
        val bar = seekBar
        touch.swipe(Offset(bar.left + 60f, bar.center.y), dyPx = -10f, millis = 300, delivery = Delivery.FRAMED, dxPx = 300f)
        assertThat(offset).isWithin(2f).of(folded)
        assertThat(isOpen).isFalse()
        assertThat(calls).doesNotContain("playerSwipeStart")
        assertThat(calls).contains("seek")
    }

    @Test
    fun `a sideways swipe on the strip is not the sheet's`() {
        show()
        touch.swipe(stripBounds.center, dyPx = -20f, millis = 200, delivery = Delivery.FRAMED, dxPx = 400f)
        assertThat(isOpen).isFalse()
        assertThat(offset).isWithin(2f).of(folded)
    }

    // ── no queue button in the header; an accessible way in ──────────────────────────────────────────

    @Test
    fun `the header has no queue button, only collapse, lyrics and details`() {
        show()
        for (cd in listOf(R.string.player_header_collapse, R.string.player_header_lyrics, R.string.player_header_details)) {
            compose.onAllNodesWithContentDescription(s(cd))[0].assertExists()
        }
        // no node anywhere names a queue button of the header: the strip's own description and the sheet's are the only mentions of the queue
        val queueButtons = compose.onAllNodes(
            SemanticsMatcher("a button called Queue") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d == "Queue" || d == "Cola" || d == "Kö" } == true
            }, useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertThat(queueButtons).isEmpty()
    }

    @Test
    fun `the strip is one button with a description and an accessibility action that opens the queue`() {
        show()
        val node = strip().fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(androidx.compose.ui.semantics.Role.Button)
        assertThat(node.config.getOrNull(SemanticsProperties.ContentDescription)).containsExactly(spoken(second))
        val click = node.config.getOrNull(SemanticsActions.OnClick)
        assertThat(click?.label).isEqualTo(s(R.string.player_queue_peek_open))
        val custom = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertThat(custom.map { it.label }).containsExactly(s(R.string.player_queue_peek_open))
        compose.runOnUiThread { custom.single().action() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertThat(isOpen).isTrue()
        assertThat(offset).isWithin(1f).of(0f)
    }

    @Test
    fun `the semantic click opens it too, and while it is open the strip is not offered`() {
        show()
        strip().performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertThat(isOpen).isTrue()
        assertThat(compose.onAllNodesWithContentDescription(spoken(second)).fetchSemanticsNodes()).isEmpty()
    }

    // ── a player with no folded state ────────────────────────────────────────────────────────────────

    @Test
    fun `with no folded state the sheet rests off-screen and the player shows no strip`() {
        show(strip = false)
        assertThat(offset).isWithin(2f).of(folded)
        assertThat(folded).isGreaterThan(1500f)
        assertThat(compose.onAllNodesWithContentDescription(spoken(second)).fetchSemanticsNodes()).isEmpty()
        // the player's own swipe still pulls the sheet up
        touch.swipe(controlsBounds.center, dyPx = -700f, millis = 300, delivery = Delivery.FRAMED)
        assertThat(isOpen).isTrue()
    }

    // ── the numbers ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the closed offset leaves the strip showing, and never less than a pixel of range once measured`() {
        assertThat(queueClosedOffsetPx(0f, 0f, 136f)).isEqualTo(0f)
        assertThat(queueClosedOffsetPx(1610f, 0f, 136f)).isEqualTo(1474f)
        assertThat(queueClosedOffsetPx(1610f, 48f, 136f)).isEqualTo(1522f)
        assertThat(queueClosedOffsetPx(1610f, 0f, 0f)).isEqualTo(1610f)
        assertThat(queueClosedOffsetPx(100f, 0f, 5_000f)).isEqualTo(1f)
    }

    @Test
    fun `the fold trades the strip for the sheet's header as the sheet opens, one at a time`() {
        assertThat(QueueFold.progress(1474f, 1474f)).isEqualTo(0f)
        assertThat(QueueFold.progress(0f, 1474f)).isEqualTo(1f)
        assertThat(QueueFold.progress(737f, 1474f)).isWithin(0.001f).of(0.5f)
        assertThat(QueueFold.progress(100f, 0f)).isEqualTo(1f)
        // folded: the strip is solid and the header is gone; open: the other way round
        assertThat(QueueFold.stripAlpha(0f)).isEqualTo(1f)
        assertThat(QueueFold.contentAlpha(0f)).isEqualTo(0f)
        assertThat(QueueFold.stripAlpha(1f)).isEqualTo(0f)
        assertThat(QueueFold.contentAlpha(1f)).isEqualTo(1f)
        // never both fully visible at the same time over the same place
        for (i in 0..100) {
            val p = i / 100f
            assertThat(QueueFold.stripAlpha(p) + QueueFold.contentAlpha(p)).isAtMost(1.2f)
        }
        assertThat(QueueFold.isFolded(0.2f)).isTrue()
        assertThat(QueueFold.isFolded(0.8f)).isFalse()
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
class B33QueueFoldSpanishTest : B33QueueFoldTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xhdpi")
class B33QueueFoldSwedishTest : B33QueueFoldTest()
