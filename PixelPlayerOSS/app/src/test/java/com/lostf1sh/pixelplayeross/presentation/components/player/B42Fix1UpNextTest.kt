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
import androidx.compose.ui.test.performClick
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
import com.lostf1sh.pixelplayeross.presentation.B42Fix1Renders
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
 * B42-fix1, item 2: "'Up next' shouldn't be there: it uses a lot." The "A continuación" strip at the foot of the player is the queue sheet folded
 * (B33-U1), and it belongs to the **Player page**: on the lyrics (and on the Menu and the Details) the sheet rests one strip lower, wholly off
 * screen, so nothing of it is drawn and nothing of it takes a touch from the page. The real [FullPlayerScreen] and the real [QueueSurface],
 * wired as the sheet wires them, with the page signal the sheet provides to both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42Fix1UpNextTest {

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

    private val lyrics = com.lostf1sh.pixelplayeross.data.model.Lyrics(
        synced = (0 until 30).map { com.lostf1sh.pixelplayeross.data.model.SyncedLine(it * 8_000, "Line number $it of the song") },
    )
    private val pageSignal = PlayerPageSignal()
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
                stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = current, isPlaying = false, totalDuration = 183_000L, lyrics = lyrics, lyricsResolveCompleted = true)),
                playbackPositionFlow = MutableStateFlow(9_000L),
                externalLyricsEnabled = true, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
                isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = repeat, isFavorite = false,
                lyricsProvider = { lyrics }, lyricsResolveCompletedProvider = { true },
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
                CompositionLocalProvider(LocalPlayerPageSignal provides pageSignal) {
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

    private val rootHeightPx = 1610f // 805 dp at 2 px per dp

    private fun clickHeader(id: Int) {
        compose.onNodeWithContentDescription(s(id)).performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    @Test
    fun on_player_the_strip_is_there_and_on_the_lyrics_it_is_not() {
        show()
        val onPlayer = stripBounds
        assertThat(onPlayer.bottom).isAtMost(rootHeightPx)
        assertThat(onPlayer.top).isAtLeast(controlsBounds.bottom)

        clickHeader(R.string.player_header_lyrics)
        assertThat(pageSignal.onPlayerPage).isFalse()
        // the sheet rests one strip lower: all of the strip is below the foot of the screen, none of it drawn
        assertThat(stripBounds.top).isAtLeast(rootHeightPx - 1f)
        B42Fix1Renders.save(compose, "item2-lyrics-no-up-next$tag")

        clickHeader(R.string.player_header_lyrics)
        assertThat(pageSignal.onPlayerPage).isTrue()
        // and back with Player, where it was
        val back = stripBounds
        assertThat(back.top).isWithin(1f).of(onPlayer.top)
        assertThat(back.bottom).isWithin(1f).of(onPlayer.bottom)
    }

    @Test
    fun a_tap_where_the_strip_was_does_nothing_on_the_lyrics_page_and_the_lyrics_bar_is_pressable() {
        show()
        val where = stripBounds.center
        clickHeader(R.string.player_header_lyrics)
        calls.clear()
        touch.tap(where)
        assertThat(calls).doesNotContain("open")
        assertThat(isOpen).isFalse()
        // the lyrics' bar is at the foot of the page, where the strip would be: its options button answers
        compose.onNodeWithContentDescription(s(R.string.lyrics_options)).assertExists()
    }

    @Test
    fun the_menu_and_the_details_have_no_strip_either() {
        show()
        clickHeader(R.string.player_header_details)
        assertThat(pageSignal.onPlayerPage).isFalse()
        assertThat(stripBounds.top).isAtLeast(rootHeightPx - 1f)
        clickHeader(R.string.player_header_details)
        assertThat(stripBounds.bottom).isAtMost(rootHeightPx)
    }

    @Test
    fun an_open_queue_is_still_shown_on_any_page() {
        show()
        clickHeader(R.string.player_header_lyrics)
        // the queue opened some other way while on the lyrics: it is the open sheet that is on screen, the page signal does not hide it
        compose.runOnUiThread { sheet!!.queueSheetController.animate(true) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertThat(isOpen).isTrue()
        assertThat(offset).isWithin(2f).of(0f)
    }
}
