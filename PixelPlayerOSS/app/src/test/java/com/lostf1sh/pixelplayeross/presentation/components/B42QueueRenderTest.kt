package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.player.QueuePeekBottomGap
import com.lostf1sh.pixelplayeross.presentation.components.player.QueuePeekHeight
import com.lostf1sh.pixelplayeross.presentation.components.scoped.QueueSheetRuntimeEffects
import com.lostf1sh.pixelplayeross.presentation.components.scoped.QueueSheetState
import com.lostf1sh.pixelplayeross.presentation.components.scoped.rememberQueueSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette
import com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42GlassApp
import com.lostf1sh.pixelplayeross.ui.glass.B42Pages
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42Setup
import com.lostf1sh.pixelplayeross.ui.glass.rememberB42Scene
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.ImmutableList
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
 * B42: the real queue (the real [QueueSurface], with the real [QueueBottomSheet] and its real sheet state and controller) over the real full
 * player, in "Cover glass": folded, half way up and open, for four covers with Graphite or Light saved (the cover mode ignores the stored theme).
 *
 * The queue exists only inside the full player (it is the player's sheet), so it is shown over the player; it cannot be opened over Home.
 * The PNGs go to `B42-evidence/queue-<cover>-<theme>-<state>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42QueueRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val covers = B41Renders.all

    private fun song(n: Int, title: String) = Song(
        id = "$n", title = title, artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$n.flac",
        contentUriString = "content://$n", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val queue: ImmutableList<Song> =
        (listOf(song(1, "Cine"), song(2, "Ventana roja"), song(3, "Luces de la ciudad")) + (4..30).map { song(it, "Cancion $it") }).toImmutableList()

    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also { every { it.playerUiState } returns MutableStateFlow(PlayerUiState()) }
    private val settingsViewModel = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(SettingsUiState(showQueueHistory = false))
    }
    private val playlistViewModel = mockk<PlaylistViewModel>(relaxed = true)
    private val timerDisplay = mutableStateOf<String?>(null)
    private val timerMinutes = mutableStateOf<Int?>(null)
    private val playCount = mutableStateOf(0f)
    private val endOfTrackTimer = mutableStateOf(false)

    private var index by mutableIntStateOf(0)
    private var sheet: QueueSheetState? = null
    private var sceneRef: com.lostf1sh.pixelplayeross.ui.glass.CoverGlassScene? = null

    private fun stage(theme: DapBaseTheme, playing: Boolean = true) {
        compose.setContent {
            val cover = covers[index]
            key(cover.name) {
                val setup = remember(cover, theme) { B42Setup(cover, theme) }
                val scene = rememberB42Scene(setup, 14f)
                SideEffect { sceneRef = scene }
                B42GlassApp(setup, scene, expansion = 1f) {
                    val density = LocalDensity.current
                    val screenHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
                    val strip = with(density) { (QueuePeekHeight + QueuePeekBottomGap).toPx() }
                    val state = rememberQueueSheetState(
                        scope = rememberCoroutineScope(), screenHeightPx = screenHeightPx, density = density, currentBottomPadding = 0.dp,
                        showPlayerContentArea = true, currentSheetContentState = com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState.EXPANDED,
                        foldedStripPx = strip,
                    )
                    SideEffect { sheet = state }
                    QueueSheetRuntimeEffects(
                        queueSheetController = state.queueSheetController, queueSheetOffset = state.queueSheetOffset,
                        queueHiddenOffsetPx = state.queueHiddenOffsetPx, showQueueSheet = state.showQueueSheet,
                        allowQueueSheetInteraction = state.allowQueueSheetInteraction, onTopEdgeReached = {},
                    )
                    val controller = state.queueSheetController
                    Box(Modifier.fillMaxSize()) {
                        B42Pages.Player(cover, bottomRoom = QueuePeekHeight + QueuePeekBottomGap)
                        QueueSurface(
                            composed = true, visible = state.showQueueSheet, playerViewModel = playerViewModel,
                            albumColorScheme = dapColorScheme(LocalDapAccent.current, LocalDapPalette.current),
                            queueScrimAlpha = 0f, queueSheetOffset = state.queueSheetOffset, queueHiddenOffsetPx = state.queueHiddenOffsetPx,
                            queueSheetHeightPx = state.queueSheetHeightPx, onQueueSheetHeightPxChange = state.onQueueSheetHeightPxChange,
                            foldedStripPx = strip, onOpenQueue = { controller.animate(true) },
                            configurationResetKey = Unit, currentPlaybackQueue = queue, currentQueueSourceName = "All Songs",
                            currentMediaItemIndex = 0,
                            infrequentPlayerState = StablePlayerState(currentSong = queue[0], currentMediaItemIndex = 0, isPlaying = playing),
                            activeTimerValueDisplay = timerDisplay, activeTimerDurationMinutes = timerMinutes, playCount = playCount,
                            isEndOfTrackTimerActive = endOfTrackTimer, onDismissQueue = { controller.animate(false) },
                            onSongInfoClick = { _, _ -> }, onPlaySong = { _, _ -> }, onRemoveSong = { _, _ -> }, onReorder = { _, _ -> },
                            onToggleRepeat = {}, onToggleShuffle = {}, onClearQueue = {}, onSetPredefinedTimer = {}, onSetEndOfTrackTimer = {},
                            onOpenCustomTimePicker = {}, onCancelTimer = {}, onCancelCountedPlay = {}, onPlayCounter = {},
                            onRequestSaveAsPlaylist = { _, _, _ -> },
                            onQueueDragStart = { controller.beginDrag() }, onQueueDrag = { controller.dragBy(it) },
                            onQueueRelease = { total, velocity -> controller.endDrag(total, velocity) },
                            playlistViewModel = playlistViewModel, settingsViewModel = settingsViewModel,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun open() {
        compose.runOnIdle { sheet!!.queueSheetController.animate(true) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun halfWay() {
        open()
        compose.runOnIdle { runBlocking { sheet!!.queueSheetOffset.snapTo(sheet!!.queueHiddenOffsetPx * 0.42f) } }
        compose.waitForIdle()
    }

    private fun renderEach(theme: DapBaseTheme, state: String, put: () -> Unit) {
        stage(theme)
        for (i in covers.indices) {
            if (i > 0) {
                compose.runOnIdle { index = i }
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
            }
            put()
            B42Renders.save(compose, "queue-${covers[i].name}-${theme.name.lowercase()}-$state")
        }
    }

    @Test
    fun `the queue folded, over the player, four covers, on Graphite`() = renderEach(DapBaseTheme.Graphite, "folded") { }

    @Test
    fun `the queue half way up, over the player, four covers, on Graphite`() = renderEach(DapBaseTheme.Graphite, "half") { halfWay() }

    @Test
    fun `the queue open, four covers, on Graphite`() = renderEach(DapBaseTheme.Graphite, "open") { open() }

    @Test
    fun `the queue folded, over the player, four covers, with Light saved`() = renderEach(DapBaseTheme.Light, "folded") { }

    @Test
    fun `the queue half way up, over the player, four covers, with Light saved`() = renderEach(DapBaseTheme.Light, "half") { halfWay() }

    @Test
    fun `the queue open, four covers, with Light saved`() = renderEach(DapBaseTheme.Light, "open") { open() }

    @Test
    fun `open over the real player, the queue's body is the glass and not the theme's surface, and the player's glass moving behind changes nothing of it`() {
        // a paused song: the current row's playing mark animates, and that is not the glass
        stage(DapBaseTheme.Graphite, playing = false)
        open()
        val setup = B42Setup(covers[0], DapBaseTheme.Graphite)
        val before = B42Renders.screen(compose)
        // an empty part of the open sheet: the right-hand gutter, between the header and the bottom bar
        val sample = before.getPixel(712, 700) and 0xFFFFFF
        assertThat(sample).isNotEqualTo(setup.palette.surface.toArgb() and 0xFFFFFF)
        assertThat(sample).isNotEqualTo(setup.palette.background.toArgb() and 0xFFFFFF)
        // the player behind it steps on for a minute: not one pixel of the open queue changes (it covers the whole window)
        repeat(60) {
            compose.runOnIdle { repeat(60) { sceneRef!!.clock.advance(1f / 60f) } }
            compose.waitForIdle()
        }
        assertThat(sceneRef!!.clock.phase).isWithin(0.5f).of(74f)
        val after = B42Renders.screen(compose)
        val a = IntArray(before.width * before.height).also { before.getPixels(it, 0, before.width, 0, 0, before.width, before.height) }
        val b = IntArray(after.width * after.height).also { after.getPixels(it, 0, after.width, 0, 0, after.width, after.height) }
        var different = 0
        // the two rounded top corners show what is behind the sheet, which is the player's moving glass: not part of the sheet
        val corners = 64 * before.width
        for (i in corners until a.size) if (a[i] != b[i]) different++
        println("B42 | open queue over the real player, a minute of the player's glass later: $different pixels of ${a.size} changed")
        assertThat(different).isEqualTo(0)
    }
}
