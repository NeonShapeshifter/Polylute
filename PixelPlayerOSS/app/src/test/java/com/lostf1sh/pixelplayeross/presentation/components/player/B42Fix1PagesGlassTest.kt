package com.lostf1sh.pixelplayeross.presentation.components.player

import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.B42Fix1Renders
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.components.QueueSurface
import com.lostf1sh.pixelplayeross.presentation.components.SongInfoBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.SongOptionsPresentation
import com.lostf1sh.pixelplayeross.presentation.components.scoped.QueueSheetRuntimeEffects
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.components.scoped.rememberQueueSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputDetails
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputRoute
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SongInfoBottomSheetViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TrackDetailsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42GlassApp
import com.lostf1sh.pixelplayeross.ui.glass.B42Ink
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42Setup
import com.lostf1sh.pixelplayeross.ui.glass.GlassWindowBackdrop
import com.lostf1sh.pixelplayeross.ui.glass.LocalCoverGlass
import com.lostf1sh.pixelplayeross.ui.glass.rememberB42Scene
import io.mockk.every
import io.mockk.mockk
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
 * B42-fix1, item 8: "the details are not opaque at all, you can't read it". On the HONOR (B41, "Cover glass", `B40-device/honor/details-01.png`) the
 * track details were drawn over the player with no fill: its cover, title, seek bar, transport buttons and "Up next" all showed through the rows.
 *
 * **Cause (confirmed below, [a_window_with_nothing_behind_its_page_shows_the_player_through_it]).** The details were a window of their own (a
 * `Dialog`). In "Cover glass" the page fill (`Dap.page`, `DapBackScaffold`) is *nothing*, so that the glass behind the app shows, and a window of its
 * own has no glass behind it: it has the window below. B42 §2.4 gave that window a copy of the glass; **now the Details are a page of the player's
 * pager and there is no window and nothing under it** (and the Menu and the Lyrics are pages too).
 *
 * What is pinned, for each of the three pages, over four covers, with Graphite or Light saved (ignored in the cover mode):
 *  - **nothing of another page shows through**: the real player (cover, title, bar, transport, the lines under the cover, and the real queue
 *    sheet with its "A continuación" strip) is drawn twice over the same glass with *different* content (another position, another shuffle and
 *    repeat, another favourite, other songs next); the area of the page must be the same pixels both times. Anything of the player that showed
 *    through it would make them differ.
 *  - **every ink is at AA** on the pixels under it, or no worse than without the glass (the design's own).
 * And the arrangement of B41, reproduced as a control that proves the comparison can see a leak, and B42's, which does not leak.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class B42Fix1PagesGlassTest {
    @get:Rule
    val compose = createComposeRule()

    protected open val theme: DapBaseTheme = DapBaseTheme.Graphite
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    /** What the player says, apart from what the pages say: the test changes it and nothing in a page may follow. */
    private var variant by mutableIntStateOf(0)
    private var glassOn by mutableStateOf(true)
    private var windowOver by mutableStateOf(false)
    private var windowHasGlass by mutableStateOf(true)
    private var playerShown by mutableStateOf(true)

    private fun song(n: Int, title: String) = Song(
        id = "$n", title = title, artist = "Gemini Sound", artistId = 7L, album = "Noche", albumId = 9L,
        path = "/storage/emulated/0/Music/Noche/$title.flac", contentUriString = "content://$n", albumArtUriString = null,
        duration = 245_000L, mimeType = "audio/flac", bitrate = 1000, sampleRate = 44100, genre = "Pop",
    )

    private val current = song(1, "Cine")

    /** The queue the player and the real queue sheet share: the song on screen, and the two after it, which differ with the variant. */
    private fun queue() = (listOf(current) + if (variant == 0) listOf(song(2, "Fiesta Pagana"), song(3, "Molinos de Viento")) else listOf(song(4, "Reiki y yoga"), song(5, "Zzz Zzz Zzz"))).toImmutableList()

    /** Lines with a translation and a romanization on some of them: every ink a lyrics page draws, the dimmed ones included. */
    private val syncedLyrics = Lyrics(
        synced = (0 until 30).map {
            SyncedLine(
                it * 8_000, listOf("Abre la puerta, que estoy afuera", "Estás grande ya, pero tú eres mi nena", "Mira cómo brilla todo por la noche")[it % 3],
                translation = if (it % 3 == 0) "Open the door, I am outside" else null,
                romanization = if (it % 3 == 1) "Estas grande ya, pero tu eres mi nena" else null,
            )
        },
    )
    private val unsyncedLyrics = Lyrics(plain = (0 until 40).map { listOf("Abre la puerta, que estoy afuera", "Estás grande ya, pero tú eres mi nena", "Mira cómo brilla todo por la noche")[it % 3] })
    private var unsynced = false
    private val lyrics get() = if (unsynced) unsyncedLyrics else syncedLyrics
    private val backAuthority = DapBackAuthority(OnBackPressedDispatcher())

    private val infoViewModel = mockk<SongInfoBottomSheetViewModel>(relaxed = true).also {
        every { it.audioMeta } returns MutableStateFlow(null)
        every { it.resolvedArtists } returns MutableStateFlow(persistentListOf())
        every { it.musicBrainzState } returns MutableStateFlow(SongInfoBottomSheetViewModel.MusicBrainzUiState.Idle)
        every { it.getSongLocationInfo(any()) } returns SongInfoBottomSheetViewModel.SongLocationInfo("Folder", "Music/Noche")
        every { it.isSongEditable(any()) } returns true
    }
    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.playerUiState } returns MutableStateFlow(PlayerUiState())
    }
    private val settingsViewModel = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(SettingsUiState(showQueueHistory = false))
    }
    private val playlistViewModel = mockk<PlaylistViewModel>(relaxed = true)
    private val timerDisplay = mutableStateOf<String?>(null)
    private val timerMinutes = mutableStateOf<Int?>(null)
    private val playCount = mutableStateOf(0f)
    private val endOfTrackTimer = mutableStateOf(false)

    private fun uiState(): FullPlayerUiState {
        val v = variant
        return buildFullPlayerUiState(
            env = PlayerSheetEnv(FullPlayerLoadingTweaks(delayAll = false), false, PlayerSheetState.EXPANDED, false, { 1f }),
            song = current, queue = queue(), queueSourceName = "All Songs", mediaItemIndex = 0, carouselStyle = CarouselStyle.NO_PEEK,
            artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
            audioMetadata = PlaybackAudioMetadata(mediaId = current.id, mimeType = "audio/flac", sampleRate = 44100), showFileInfo = true,
            isFavorite = v == 1, isBuffering = false, isShuffleEnabled = v == 0, repeatMode = if (v == 0) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL,
            isPlayingProvider = { true }, playWhenReadyProvider = { true },
            currentPositionProvider = { if (v == 0) 22_000L else 150_000L }, totalDurationProvider = { 245_000L },
            lyrics = PlayerLyricsState(
                searchState = LyricsSearchUiState.Idle,
                stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = current, isPlaying = true, totalDuration = 245_000L, lyrics = lyrics, lyricsResolveCompleted = true)),
                // what the lyrics page follows is constant: only what the *player* says changes
                playbackPositionFlow = MutableStateFlow(22_000L), externalLyricsEnabled = false, lyricsSyncOffset = 0, immersiveLyricsEnabled = false,
                immersiveLyricsTimeout = 4000L, isImmersiveTemporarilyDisabled = false, isShuffleEnabled = true, repeatMode = Player.REPEAT_MODE_OFF, isFavorite = false,
                lyricsProvider = { lyrics }, lyricsResolveCompletedProvider = { true },
            ),
            isLandscape = false,
        )
    }

    private val details = TrackDetailsUiState(song = current, output = OutputDetails(route = OutputRoute.SPEAKER))

    private val slots = PlayerPageSlots(
        menu = {
            SongInfoBottomSheet(
                song = current, isFavorite = false, onToggleFavorite = {}, onDismiss = {}, onPlaySong = {}, onAddToQueue = {}, onAddNextToQueue = {},
                onAddToPlayList = {}, onDeleteFromDevice = { _, _, _ -> }, onNavigateToAlbum = {}, onNavigateToArtist = {}, onNavigateToGenre = {},
                onViewTrackDetails = {}, onViewCredits = {},
                onEditSong = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                removeFromListTrigger = {}, songInfoViewModel = infoViewModel, presentation = SongOptionsPresentation.PAGE,
            )
        },
        details = { TrackDetailsBody(state = details, currentPositionProvider = { 22_000L }, totalDurationMs = 245_000L, onSeek = {}) },
    )

    private val pageSignal = PlayerPageSignal()

    /** The real full player and the real queue sheet over the real glass, as `UnifiedPlayerSheetV2` wires them, with a window over them on request. */
    private fun show(cover: B41Renders.Cover) {
        val setup = B42Setup(cover, theme)
        compose.setContent {
            val scene = rememberB42Scene(setup, 14f)
            val density = LocalDensity.current
            val screenHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
            val foldedStripPx = with(density) { (QueuePeekHeight + QueuePeekBottomGap).toPx() }
            val state = rememberQueueSheetState(
                scope = rememberCoroutineScope(), screenHeightPx = screenHeightPx, density = density, currentBottomPadding = 0.dp,
                showPlayerContentArea = true, currentSheetContentState = PlayerSheetState.EXPANDED, foldedStripPx = foldedStripPx,
            )
            QueueSheetRuntimeEffects(
                queueSheetController = state.queueSheetController, queueSheetOffset = state.queueSheetOffset,
                queueHiddenOffsetPx = state.queueHiddenOffsetPx, showQueueSheet = state.showQueueSheet,
                allowQueueSheetInteraction = state.allowQueueSheetInteraction, onTopEdgeReached = {},
            )
            val songs = queue()
            B42GlassApp(setup, scene, expansion = 1f, glassOn = glassOn) {
                CompositionLocalProvider(LocalPlayerPageSignal provides pageSignal, LocalDapBackNavigation provides backAuthority) {
                    if (playerShown) CompositionLocalProvider(
                        LocalMaterialTheme provides com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme(
                            com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent.current, com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette.current,
                        ),
                    ) { FullPlayerScreen(uiState(), NoActions(), slots) }
                    if (playerShown) QueueSurface(
                        composed = true, visible = state.showQueueSheet, playerViewModel = playerViewModel, albumColorScheme = androidx.compose.material3.darkColorScheme(),
                        queueScrimAlpha = 0f, queueSheetOffset = state.queueSheetOffset, queueHiddenOffsetPx = state.queueHiddenOffsetPx,
                        queueSheetHeightPx = state.queueSheetHeightPx, onQueueSheetHeightPxChange = state.onQueueSheetHeightPxChange,
                        foldedStripPx = foldedStripPx, onOpenQueue = {}, configurationResetKey = Unit, currentPlaybackQueue = songs,
                        currentQueueSourceName = "All Songs", currentMediaItemIndex = 0,
                        infrequentPlayerState = StablePlayerState(currentSong = current, currentMediaItemIndex = 0, isPlaying = true, repeatMode = Player.REPEAT_MODE_OFF),
                        activeTimerValueDisplay = timerDisplay, activeTimerDurationMinutes = timerMinutes, playCount = playCount,
                        isEndOfTrackTimerActive = endOfTrackTimer, onDismissQueue = {},
                        onSongInfoClick = { _, _ -> }, onPlaySong = { _, _ -> }, onRemoveSong = { _, _ -> }, onReorder = { _, _ -> },
                        onToggleRepeat = {}, onToggleShuffle = {}, onClearQueue = {}, onSetPredefinedTimer = {}, onSetEndOfTrackTimer = {},
                        onOpenCustomTimePicker = {}, onCancelTimer = {}, onCancelCountedPlay = {}, onPlayCounter = {}, onRequestSaveAsPlaylist = { _, _, _ -> },
                        onQueueDragStart = {}, onQueueDrag = {}, onQueueRelease = { _, _ -> },
                        playlistViewModel = playlistViewModel, settingsViewModel = settingsViewModel,
                    )
                    if (windowOver) {
                        // the player's track details as they were drawn in a window of their own: the B41 arrangement (no glass behind the page),
                        // and B42's (a copy of it)
                        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                            if (windowHasGlass) GlassWindowBackdrop(LocalCoverGlass.current)
                            TrackDetailsSheet(state = details, currentPositionProvider = { 22_000L }, totalDurationMs = 245_000L, onSeek = {}, onBackClick = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private enum class Page { MENU, LYRICS, DETAILS }

    private fun goTo(page: Page) {
        when (page) {
            Page.MENU -> {
                val action = compose.onNodeWithTag(PlayerPagesTags.Pager).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)
                    .orEmpty().first { it.label == s(R.string.player_action_options) }
                compose.runOnUiThread { action.action() }
            }
            Page.LYRICS -> compose.onNodeWithContentDescription(s(R.string.player_header_lyrics)).performClick()
            Page.DETAILS -> compose.onNodeWithContentDescription(s(R.string.player_header_details)).performClick()
        }
        settle()
    }

    private fun tagOf(page: Page) = when (page) {
        Page.MENU -> PlayerPagesTags.Menu
        Page.LYRICS -> PlayerPagesTags.Lyrics
        Page.DETAILS -> PlayerPagesTags.Details
    }

    private fun screen(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)

    private fun switchVariant(to: Int) {
        compose.runOnIdle { variant = to }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** How many pixels of [from] down differ between [a] and [b] by more than a few levels, and the box they are in (for the failure's message). */
    private class Difference(val count: Int, val area: Int, val box: String)

    private fun difference(a: Bitmap, b: Bitmap, fromY: Int): Difference {
        var count = 0
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (y in fromY until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            val d = maxOf(Math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)), Math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)), Math.abs((p and 0xFF) - (q and 0xFF)))
            if (d > 6) {
                count++
                minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
            }
        }
        return Difference(count, (a.height - fromY) * a.width, if (count == 0) "none" else "x $minX..$maxX, y $minY..$maxY")
    }

    /** The page, over the glass, with nothing of the player in it: the player is drawn twice with different content and the page does not move. */
    private fun nothingShowsThrough(page: Page, cover: B41Renders.Cover, file: String, unsyncedLyrics: Boolean = false) {
        unsynced = unsyncedLyrics
        show(cover)
        goTo(page)
        val pageTop = compose.onNodeWithTag(tagOf(page)).fetchSemanticsNode().boundsInWindow.top.toInt()
        val first = screen()
        B42Fix1Renders.save(compose, file)
        switchVariant(1)
        val second = screen()
        val d = difference(first, second, pageTop)
        println("B42-fix1 | $file: ${d.count} of ${d.area} pixels of the page area differ when the player's content changes (${d.box})")
        assertThat(d.count).isAtMost(d.area / 2_000)
        // ink: every text of the page against the pixels under it, on the glass and without it
        switchVariant(0)
        val window = screen()
        val setup = B42Setup(cover, theme)
        val exempt = setOf(setup.palette.disabledText, setup.look.palette.disabledText)
        val onGlass = B42Ink.measure(compose, window, exempt, activityWindow = true, belowPx = pageTop.toFloat())
        compose.runOnIdle { glassOn = false }
        compose.waitForIdle()
        val without = B42Ink.measure(compose, screen(), exempt, activityWindow = true, belowPx = pageTop.toFloat())
        val worse = B42Ink.regressions(onGlass, without)
        println(
            "B42-fix1 | $file: ${onGlass.size} texts measured, lowest contrast ${onGlass.minOfOrNull { it.contrast }}; made worse by the glass: " +
                "${B42Ink.report(worse).ifEmpty { "none" }}; under AA with no glass too: ${B42Ink.alsoBelowWithout(onGlass, without).joinToString("; ") { "'${it.text}' %.2f".format(it.contrast) }.ifEmpty { "none" }}"
        )
        assertThat(onGlass).isNotEmpty()
        assertThat(worse).isEmpty()
    }

    @Test fun the_menu_over_a_saturated_cover() = nothingShowsThrough(Page.MENU, B41Renders.saturated(), "item8-menu-saturated-${theme.name.lowercase()}")
    @Test fun the_menu_over_a_very_light_cover() = nothingShowsThrough(Page.MENU, B41Renders.veryLight(), "item8-menu-verylight-${theme.name.lowercase()}")
    @Test fun the_menu_over_a_very_dark_cover() = nothingShowsThrough(Page.MENU, B41Renders.veryDark(), "item8-menu-verydark-${theme.name.lowercase()}")
    @Test fun the_menu_over_a_grey_cover() = nothingShowsThrough(Page.MENU, B41Renders.grey(), "item8-menu-grey-${theme.name.lowercase()}")
    @Test fun the_lyrics_over_a_saturated_cover() = nothingShowsThrough(Page.LYRICS, B41Renders.saturated(), "item8-lyrics-saturated-${theme.name.lowercase()}")
    @Test fun the_lyrics_over_a_very_light_cover() = nothingShowsThrough(Page.LYRICS, B41Renders.veryLight(), "item8-lyrics-verylight-${theme.name.lowercase()}")
    @Test fun the_lyrics_over_a_very_dark_cover() = nothingShowsThrough(Page.LYRICS, B41Renders.veryDark(), "item8-lyrics-verydark-${theme.name.lowercase()}")
    @Test fun the_lyrics_over_a_grey_cover() = nothingShowsThrough(Page.LYRICS, B41Renders.grey(), "item8-lyrics-grey-${theme.name.lowercase()}")
    @Test fun the_unsynced_lyrics_over_a_saturated_cover() = nothingShowsThrough(Page.LYRICS, B41Renders.saturated(), "item8-lyrics-unsynced-saturated-${theme.name.lowercase()}", unsyncedLyrics = true)
    @Test fun the_unsynced_lyrics_over_a_very_light_cover() = nothingShowsThrough(Page.LYRICS, B41Renders.veryLight(), "item8-lyrics-unsynced-verylight-${theme.name.lowercase()}", unsyncedLyrics = true)
    @Test fun the_details_over_a_saturated_cover() = nothingShowsThrough(Page.DETAILS, B41Renders.saturated(), "item8-details-saturated-${theme.name.lowercase()}")
    @Test fun the_details_over_a_very_light_cover() = nothingShowsThrough(Page.DETAILS, B41Renders.veryLight(), "item8-details-verylight-${theme.name.lowercase()}")
    @Test fun the_details_over_a_very_dark_cover() = nothingShowsThrough(Page.DETAILS, B41Renders.veryDark(), "item8-details-verydark-${theme.name.lowercase()}")
    @Test fun the_details_over_a_grey_cover() = nothingShowsThrough(Page.DETAILS, B41Renders.grey(), "item8-details-grey-${theme.name.lowercase()}")

    // ── the arrangement that was seen on the HONOR, and the one that fixed it ────────────────────────

    /** The clock is the test's while a window is open over the playing player: its animations never let the compose rule go idle on their own. */
    private fun stepManually() {
        repeat(12) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }

    /**
     * The window open over the playing player, and the same window with the player taken away: what differs is the player showing through the
     * page (the arrangement of item 8's screenshot, `B40-device/honor/details-01.png`).
     */
    private fun windowOverThePlayer(hasGlass: Boolean): Difference {
        show(B41Renders.saturated())
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { windowHasGlass = hasGlass; windowOver = true }
        stepManually()
        val first = B42Renders.screen(compose)
        B42Fix1Renders.save(first, "item8-window-${if (hasGlass) "with" else "without"}-glass-${theme.name.lowercase()}")
        compose.runOnIdle { playerShown = false }
        stepManually()
        val second = B42Renders.screen(compose)
        return difference(first, second, 0).also {
            println("B42-fix1 | track details in a window, ${if (hasGlass) "with" else "without"} the glass behind the page: ${it.count} of ${it.area} pixels are the player's, seen through the page (${it.box})")
        }
    }

    @Test
    fun a_window_with_nothing_behind_its_page_shows_the_player_through_it() {
        // B41: DapBackScaffold fills with Dap.page (nothing, over the glass) and a window of its own has no glass behind it: the player is behind it
        val d = windowOverThePlayer(hasGlass = false)
        assertThat(d.count).isGreaterThan(d.area / 100)
    }

    @Test
    fun a_window_with_the_glass_behind_its_page_shows_nothing_of_the_player() {
        // B42 §2.4
        val d = windowOverThePlayer(hasGlass = true)
        assertThat(d.count).isAtMost(d.area / 2_000)
    }
}

/** With Light saved: the cover mode ignores it and still paints the cover with light ink (B42-fix3). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42Fix1PagesGlassLightTest : B42Fix1PagesGlassTest() {
    override val theme: DapBaseTheme = DapBaseTheme.Light
}
