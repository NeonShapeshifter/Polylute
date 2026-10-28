package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.activity.ComponentActivity
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.media.Waveform
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TrackDetailsUiState
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch.Delivery
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42-fix1, item 1: the full player is four pages, **Menu · Player · Lyrics · Details**, and opens on Player.
 *
 * The real [FullPlayerScreen] over three made-up songs and the real cover carousel, at 360 x 805 dp (xxhdpi, 3 px per dp), with real touch
 * events ([SyntheticTouch]). The Menu and the Details are given as slots (the host builds them from view models; here they are two labels that
 * also say when they are composed), so what is pinned is the pager's own contract:
 *
 *  - a swipe that **starts on the cover** changes the song and never the page, also at the first and the last song;
 *  - a swipe that starts **anywhere else** changes the page and never the song; what drags (the seek bar) keeps its drag; a drag that starts
 *    on the back edge is Back's;
 *  - the header's lyrics and details icons jump to their page (and, again, back to Player); the system back goes from Menu, Lyrics or Details
 *    to Player, and from Player it is not handled here; the chevron closes the player from any page;
 *  - a screen reader is offered Options, Lyrics and Details; nothing on screen says there are pages (no tab, no indicator);
 *  - the pages are lazy, and a collapsed player reopens on Player.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class B42Fix1PlayerPagesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val touch by lazy { SyntheticTouch(compose) }

    private fun song(n: Int, title: String) = Song(
        id = "$n", title = title, artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$n.flac",
        contentUriString = "content://$n", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val songs = persistentListOf(song(1, "La Costa Del Silencio"), song(2, "Fiesta Pagana"), song(3, "Molinos de Viento"))
    private val calls = mutableListOf<String>()

    private val lyrics = com.lostf1sh.pixelplayeross.data.model.Lyrics(
        synced = (0 until 30).map { com.lostf1sh.pixelplayeross.data.model.SyncedLine(it * 8_000, "Line number $it of the song") },
    )

    private var sheet by mutableStateOf(PlayerSheetState.EXPANDED)
    private var index by mutableStateOf(1)

    /** What a slot says about itself: composed now, and how many times it has come into composition. */
    private class Slot { var composed = false; var entered = 0 }
    private val menuSlot = Slot()
    private var realDetails = false
    private val seeks = mutableListOf<Long>()
    private val detailsSlot = Slot()

    private val recording = object : FullPlayerActions by NoActions() {
        override fun onPlayFromCarousel(song: Song, queue: kotlinx.collections.immutable.ImmutableList<Song>, queueName: String, index: Int) {
            calls += "playFrom:$index"
            // the player follows the selection, as it does in the app (the carousel puts itself back where the player says the song is)
            this@B42Fix1PlayerPagesTest.index = index
        }
        override fun onCollapse() { calls += "collapse" }
        override fun onSeek(positionMs: Long) { calls += "seek" }
        override fun onQueueDragStart() { calls += "queueStart" }
    }

    private fun uiState(): FullPlayerUiState {
        val current = songs[index]
        return buildFullPlayerUiState(
            env = PlayerSheetEnv(
                loadingTweaks = FullPlayerLoadingTweaks(delayAll = false), isSheetDragGestureActive = false,
                currentSheetState = sheet, allowRealtimeUpdates = false, expansionFractionProvider = { 1f },
            ),
            song = current, queue = songs, queueSourceName = "All Songs", mediaItemIndex = index,
            carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
            audioMetadata = PlaybackAudioMetadata(mediaId = current.id, mimeType = "audio/flac", sampleRate = 44100),
            showFileInfo = true, isFavorite = false, isBuffering = false, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF,
            isPlayingProvider = { true }, playWhenReadyProvider = { true },
            currentPositionProvider = { 100_000L }, totalDurationProvider = { 280_000L },
            lyrics = PlayerLyricsState(
                searchState = LyricsSearchUiState.Idle,
                stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = current, isPlaying = true, totalDuration = 280_000L, lyrics = lyrics, lyricsResolveCompleted = true)),
                playbackPositionFlow = MutableStateFlow(9_000L),
                externalLyricsEnabled = true, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
                isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF, isFavorite = false,
                lyricsProvider = { lyrics }, lyricsResolveCompletedProvider = { true },
            ),
            isLandscape = false,
        )
    }

    /** The activity dispatcher: what the system back reaches. */
    private val backDispatcher get() = compose.activity.onBackPressedDispatcher
    private val signal = PlayerPageSignal()

    private val slots = PlayerPageSlots(
        menu = {
            DisposableEffect(Unit) { menuSlot.composed = true; menuSlot.entered++; onDispose { menuSlot.composed = false } }
            Text("The menu page")
        },
        details = {
            DisposableEffect(Unit) { detailsSlot.composed = true; detailsSlot.entered++; onDispose { detailsSlot.composed = false } }
            if (realDetails) {
                // the real details body, with a waveform that seeks: a control on a page that drags
                TrackDetailsBody(
                    state = TrackDetailsUiState(
                        song = songs[index],
                        waveform = Waveform(bucketCount = 100, peaks = FloatArray(100) { 0.2f + 0.6f * ((it * 7) % 10) / 10f }, rms = FloatArray(100) { 0.3f }, durationMs = 280_000L),
                    ),
                    currentPositionProvider = { 0L }, totalDurationMs = 280_000L, onSeek = { seeks += it },
                )
            } else {
                Text("The details page")
            }
        },
    )

    private fun show(startAt: Int = 1, overscroll: Boolean = true) {
        // the carousel and the pager settle with animations that never let Compose go idle in this harness: the clock is the test's
        compose.mainClock.autoAdvance = false
        index = startAt
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Dap.background)) {
                CompositionLocalProvider(
                    LocalMaterialTheme provides darkColorScheme(),
                    LocalPlayerPageSignal provides signal,
                    // the stretch effect at the end of a scroller takes what the scroller cannot use, so nothing reaches the pager around it; with
                    // the effect off (the app turns it off inside some pages, and a device can) what is left over is what the pager would get
                    LocalOverscrollFactory provides (if (overscroll) LocalOverscrollFactory.current else null),
                ) { FullPlayerScreen(uiState(), recording, slots) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /**
     * Whether the page is on screen. A page the pager has left is kept in its reuse pool as an inactive slot (its effects stopped, nothing placed), and
     * its tag is still in the tree: only a placed node counts.
     */
    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { it.layoutInfo.isPlaced && it.size.width > 0 }

    /** The page the pager is on, from which page is composed. */
    private fun page(): PlayerPage {
        val on = PlayerPage.entries.filter {
            present(when (it) {
                PlayerPage.MENU -> PlayerPagesTags.Menu
                PlayerPage.PLAYER -> PlayerPagesTags.Player
                PlayerPage.LYRICS -> PlayerPagesTags.Lyrics
                PlayerPage.DETAILS -> PlayerPagesTags.Details
            })
        }
        assertWithMessage("after a swipe only one page is on screen").that(on).hasSize(1)
        return on.single()
    }

    private val playerBounds get() = compose.onNodeWithTag(PlayerPagesTags.Player).fetchSemanticsNode().boundsInRoot
    private val coverBounds get() = compose.onNodeWithTag(PlayerPagesTags.Cover).fetchSemanticsNode().boundsInRoot

    private fun swipeLeftAt(from: Offset) = touch.swipe(from, dyPx = 0f, dxPx = -450f, millis = 250, delivery = Delivery.FRAMED)
    private fun swipeRightAt(from: Offset) = touch.swipe(from, dyPx = 0f, dxPx = 450f, millis = 250, delivery = Delivery.FRAMED)

    /**
     * A swipe on the cover, and the clock run on without asking Compose whether it is idle: once the carousel has moved, this harness never reports
     * idle (an animated pager does not in Robolectric), so nothing after it reads the semantic tree. What it reports is the player's own state: what
     * the carousel asked it to play, and the page it says it is on ([PlayerPageSignal]).
     */
    private fun swipeCover(from: Offset, dxPx: Float) {
        // the touches are handed to the view directly, a frame at a time, with no idle wait between them (the harness's wait does not come back
        // once the carousel has been dragged, also when it has nowhere to go)
        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val downTime = SystemClock.uptimeMillis()
        val samples = touch.path(from, listOf(Offset(from.x + dxPx, from.y)), 300)
        fun send(action: Int, sample: SyntheticTouch.Sample) {
            val event = MotionEvent.obtain(downTime, downTime + sample.t, action, sample.x, sample.y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, samples.first())
        compose.mainClock.advanceTimeByFrame()
        var frame = 0L
        for (sample in samples.drop(1).dropLast(1)) {
            send(MotionEvent.ACTION_MOVE, sample)
            if (sample.t / 16 != frame) {
                frame = sample.t / 16
                compose.mainClock.advanceTimeByFrame()
            }
        }
        send(MotionEvent.ACTION_UP, samples.last())
        compose.mainClock.advanceTimeBy(2_500)
    }

    /** A place on the Player page that is not the cover, not the bar and not a button: the title row's text. */
    private fun titleSpot(): Offset = compose.onAllNodesWithText(songs[index].title)[0].fetchSemanticsNode().boundsInRoot.center

    /**
     * The clock run on in slices with the looper let run between them: effects and the coroutines they launch are posted to it, and one long
     * `advanceTimeBy` runs the frames without ever giving them a turn. (Not after a swipe on the cover: see [swipeCover].)
     */
    private fun runFor(ms: Long) {
        var elapsed = 0L
        while (elapsed < ms) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            elapsed += 100
        }
    }

    private fun clickHeader(id: Int) {
        compose.playerPageAction(s(id), toggle = true)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { backDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    // ── the pages ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_pages_are_menu_player_lyrics_details_in_that_order_and_it_opens_on_player() {
        assertThat(PlayerPage.entries).containsExactly(PlayerPage.MENU, PlayerPage.PLAYER, PlayerPage.LYRICS, PlayerPage.DETAILS).inOrder()
        assertThat(PlayerPage.Start).isEqualTo(PlayerPage.PLAYER)
        show()
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
    }

    @Test
    fun menu_is_to_the_left_of_player_and_lyrics_and_details_are_to_the_right() {
        show()
        // a swipe to the right reveals what is on the left of Player: the Menu
        swipeRightAt(titleSpot())
        assertThat(page()).isEqualTo(PlayerPage.MENU)
        compose.onAllNodesWithText("The menu page").assertCountEquals(1)
        swipeLeftAt(Offset(540f, 1200f))
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
        // and to the left, Lyrics and then Details
        swipeLeftAt(titleSpot())
        assertThat(page()).isEqualTo(PlayerPage.LYRICS)
        swipeLeftAt(Offset(540f, 1200f))
        assertThat(page()).isEqualTo(PlayerPage.DETAILS)
        compose.onAllNodesWithText("The details page").assertCountEquals(1)
        // and there is nothing beyond them
        swipeLeftAt(Offset(540f, 1200f))
        assertThat(page()).isEqualTo(PlayerPage.DETAILS)
    }

    // ── the two swipes ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_swipe_on_the_cover_towards_the_next_song_changes_the_song_and_not_the_page() {
        show(startAt = 1)
        swipeCover(coverBounds.center, dxPx = -450f)
        assertThat(calls).containsExactly("playFrom:2")
        assertThat(signal.onPlayerPage).isTrue()
    }

    @Test
    fun a_swipe_on_the_cover_towards_the_previous_song_changes_the_song_and_not_the_page() {
        show(startAt = 1)
        swipeCover(coverBounds.center, dxPx = 450f)
        assertThat(calls).containsExactly("playFrom:0")
        assertThat(signal.onPlayerPage).isTrue()
    }

    @Test
    fun a_swipe_on_the_cover_at_the_first_song_does_not_go_on_to_turn_the_page() {
        // the first song: a swipe that would show the previous one has nothing to move, and must not move the page either. It is longer than
        // half a page (800 of 1080 px): a shorter drag that leaked to the pager would move it and snap it back, and nothing would show
        show(startAt = 0)
        swipeCover(coverBounds.center, dxPx = 800f)
        assertThat(signal.page).isEqualTo(PlayerPage.PLAYER)
        assertThat(calls.filter { it.startsWith("playFrom") }).isEmpty()
    }

    @Test
    fun a_swipe_on_the_cover_at_the_last_song_does_not_go_on_to_turn_the_page() {
        show(startAt = 2)
        swipeCover(coverBounds.center, dxPx = -800f)
        assertThat(signal.page).isEqualTo(PlayerPage.PLAYER)
        assertThat(calls.filter { it.startsWith("playFrom") }).isEmpty()
    }

    @Test
    fun with_no_overscroll_effect_a_swipe_on_the_cover_at_the_first_song_still_does_not_turn_the_page() {
        show(startAt = 0, overscroll = false)
        swipeCover(coverBounds.center, dxPx = 800f)
        assertThat(signal.page).isEqualTo(PlayerPage.PLAYER)
        assertThat(calls.filter { it.startsWith("playFrom") }).isEmpty()
    }

    @Test
    fun with_no_overscroll_effect_a_swipe_on_the_cover_at_the_last_song_still_does_not_turn_the_page() {
        show(startAt = 2, overscroll = false)
        swipeCover(coverBounds.center, dxPx = -800f)
        assertThat(signal.page).isEqualTo(PlayerPage.PLAYER)
        assertThat(calls.filter { it.startsWith("playFrom") }).isEmpty()
    }

    @Test
    fun a_swipe_that_starts_elsewhere_changes_the_page_and_never_the_song() {
        show()
        swipeLeftAt(titleSpot())
        assertThat(page()).isEqualTo(PlayerPage.LYRICS)
        assertThat(calls.filter { it.startsWith("playFrom") }).isEmpty()
    }

    @Test
    fun the_seek_bar_keeps_its_drag() {
        show()
        val bar = compose.onNode(
            SemanticsMatcher("the seek bar") { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) || it.config.contains(SemanticsActions.SetProgress) },
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        touch.swipe(Offset(bar.left + 100f, bar.center.y), dyPx = 0f, dxPx = 350f, millis = 300, delivery = Delivery.FRAMED)
        assertThat(calls).contains("seek")
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
    }

    @Test
    fun the_waveform_on_the_details_page_keeps_its_drag_and_the_rest_of_the_page_turns_it() {
        realDetails = true
        show()
        clickHeader(R.string.player_header_details)
        assertThat(page()).isEqualTo(PlayerPage.DETAILS)
        val waveform = compose.onNodeWithTag(PlayerPagesTags.Waveform).fetchSemanticsNode().boundsInRoot
        // a drag towards the lyrics that starts on the waveform is a scrub: it seeks, and the page stays
        swipeRightAt(Offset(waveform.left + 200f, waveform.center.y))
        assertThat(seeks).isNotEmpty()
        assertThat(page()).isEqualTo(PlayerPage.DETAILS)
        // the same drag from the page's title (nothing there drags) is the pager's
        swipeRightAt(Offset(300f, waveform.top - 220f))
        assertThat(page()).isEqualTo(PlayerPage.LYRICS)
    }

    @Test
    fun a_drag_that_starts_on_the_back_edge_is_backs_and_not_a_page() {
        show()
        // 20 px is inside the strip the system keeps for its back gesture (20 dp at least, 60 px here)
        swipeLeftAt(Offset(20f, 1200f))
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
        swipeRightAt(Offset(1060f, 1200f))
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
    }

    // ── the way in and out ───────────────────────────────────────────────────────────────────────────

    @Test
    fun the_headers_icons_jump_to_their_pages_and_a_second_press_goes_back_to_player() {
        show()
        clickHeader(R.string.player_header_lyrics)
        assertThat(page()).isEqualTo(PlayerPage.LYRICS)
        clickHeader(R.string.player_header_lyrics)
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
        clickHeader(R.string.player_header_details)
        assertThat(page()).isEqualTo(PlayerPage.DETAILS)
        // from one page to the other without passing through Player
        clickHeader(R.string.player_header_lyrics)
        assertThat(page()).isEqualTo(PlayerPage.LYRICS)
        clickHeader(R.string.player_header_details)
        assertThat(page()).isEqualTo(PlayerPage.DETAILS)
        clickHeader(R.string.player_header_details)
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
    }

    @Test
    fun back_from_menu_lyrics_or_details_returns_to_player_and_from_player_it_is_not_taken() {
        show()
        // on Player nothing here takes Back: it closes the player as it always did
        assertThat(backDispatcher.hasEnabledCallbacks()).isFalse()
        for (route in listOf(
            { swipeRightAt(titleSpot()) },
            { clickHeader(R.string.player_header_lyrics) },
            { clickHeader(R.string.player_header_details) },
        )) {
            route()
            assertThat(page()).isNotEqualTo(PlayerPage.PLAYER)
            assertThat(backDispatcher.hasEnabledCallbacks()).isTrue()
            pressBack()
            assertThat(page()).isEqualTo(PlayerPage.PLAYER)
            assertThat(backDispatcher.hasEnabledCallbacks()).isFalse()
        }
    }

    @Test
    fun the_chevron_closes_the_player_from_any_page() {
        show()
        for (route in listOf({ swipeRightAt(titleSpot()) }, { clickHeader(R.string.player_header_lyrics) }, { clickHeader(R.string.player_header_details) })) {
            route()
            assertThat(page()).isNotEqualTo(PlayerPage.PLAYER)
            calls.clear()
            compose.playerPageAction(s(R.string.player_action_close))
            assertThat(calls).containsExactly("collapse")
            pressBack()
        }
    }

    @Test
    fun a_screen_reader_is_offered_options_lyrics_and_details() {
        show()
        val actions = compose.onNodeWithTag(PlayerPagesTags.Pager).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertThat(actions.map { it.label }).containsExactly(
            s(R.string.player_action_close), s(R.string.player_action_options), s(R.string.player_header_lyrics), s(R.string.player_header_details),
        ).inOrder()
        // and they do what they say
        compose.runOnUiThread { actions.first { it.label == s(R.string.player_action_options) }.action() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertThat(page()).isEqualTo(PlayerPage.MENU)
    }

    @Test
    fun nothing_on_screen_says_there_are_pages() {
        show()
        for (route in listOf({}, { swipeRightAt(titleSpot()) }, { swipeLeftAt(Offset(540f, 1200f)) ; swipeLeftAt(Offset(540f, 1200f)) }, { swipeLeftAt(Offset(540f, 1200f)) })) {
            route()
            // no tab, no dots: nothing with the role of a tab, and no indicator by name
            compose.onAllNodes(SemanticsMatcher("a tab") { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }).assertCountEquals(0)
            compose.onAllNodesWithTag("page-indicator").assertCountEquals(0)
        }
    }

    // ── cheap, and always opening on Player ──────────────────────────────────────────────────────────

    @Test
    fun the_pages_are_lazy() {
        show()
        // on Player: neither the menu, nor the lyrics, nor the details exist
        assertThat(menuSlot.composed).isFalse()
        assertThat(detailsSlot.composed).isFalse()
        assertThat(present(PlayerPagesTags.Lyrics)).isFalse()
        assertThat(menuSlot.entered).isEqualTo(0)
        assertThat(detailsSlot.entered).isEqualTo(0)
        // on Lyrics, the page beside Player, the details are not composed either
        clickHeader(R.string.player_header_lyrics)
        assertThat(present(PlayerPagesTags.Lyrics)).isTrue()
        assertThat(detailsSlot.composed).isFalse()
        assertThat(menuSlot.composed).isFalse()
        // and Player itself is gone while Lyrics is the page
        assertThat(present(PlayerPagesTags.Player)).isFalse()
        clickHeader(R.string.player_header_details)
        assertThat(detailsSlot.composed).isTrue()
        assertThat(present(PlayerPagesTags.Lyrics)).isFalse()
        // a page that was left is not kept running: its effects are stopped when the pager lets it go
        clickHeader(R.string.player_header_details)
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
        assertThat(detailsSlot.composed).isFalse()
    }

    @Test
    fun a_collapsed_player_reopens_on_player_and_tells_the_layers_beside_it_which_page_it_is_on() {
        show()
        assertThat(signal.onPlayerPage).isTrue()
        clickHeader(R.string.player_header_lyrics)
        assertThat(signal.onPlayerPage).isFalse()
        compose.runOnUiThread { sheet = PlayerSheetState.COLLAPSED }
        runFor(1_000)
        compose.runOnUiThread { sheet = PlayerSheetState.EXPANDED }
        runFor(1_000)
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
        assertThat(signal.onPlayerPage).isTrue()
    }

    @Test
    fun the_queue_is_dragged_up_from_player_only() {
        show()
        val play = compose.onNodeWithContentDescription(s(R.string.cd_pause)).fetchSemanticsNode().boundsInRoot.center
        touch.swipe(play, dyPx = -450f, millis = 200, delivery = Delivery.FRAMED)
        assertThat(calls).contains("queueStart")
        calls.clear()
        clickHeader(R.string.player_header_lyrics)
        // the same drag, from the same place, on a page that has no transport
        touch.swipe(play, dyPx = -450f, millis = 200, delivery = Delivery.FRAMED)
        assertThat(calls).doesNotContain("queueStart")
    }

    @Test
    fun the_lyrics_page_has_no_header_of_its_own_no_handle_and_no_swipe_to_skip() {
        show()
        clickHeader(R.string.player_header_lyrics)
        assertThat(page()).isEqualTo(PlayerPage.LYRICS)
        // the only collapse in sight is the player's own chevron; no tab, no "Up next"
        compose.onAllNodesWithContentDescription(s(R.string.player_header_collapse)).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher("a tab") { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.queue_title_next)).assertCountEquals(0)
        // a swipe on the lyrics changes the page, never the song, and shows no big button: it is the pager's, as everywhere else
        calls.clear()
        swipeLeftAt(Offset(540f, 1100f))
        assertThat(page()).isEqualTo(PlayerPage.DETAILS)
        assertThat(calls).isEmpty()
    }
}
