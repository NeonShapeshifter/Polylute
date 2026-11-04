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
 * B56 phase 3.4 (#24): the lyrics mode "Off" takes the Lyrics page out of the player, and **every swipe rule of B42-fix1 stays**: with three pages (Menu, Player,
 * Details) as with four, a swipe that begins on the cover changes the song and never the page (also at the first and the last song), and one that begins anywhere else
 * changes the page and never the song.
 *
 * The harness is B42-fix1's (the real [FullPlayerScreen] over three songs and the real carousel, real touch events); what changes is [mode].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class B56LyricsModePagesTest {

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

    private var mode by mutableStateOf(com.lostf1sh.pixelplayeross.data.preferences.LyricsMode.OVER_COVER)
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
            this@B56LyricsModePagesTest.index = index
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
                underCover = mode.showsLinesOnPlayer, mode = mode,
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
                ) { FullPlayerScreen(uiState(), recording, slots, mountModalInputBridge = false) }
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

    // ── three pages and four ──────────────────────────────────────────────────────────────────────

    private fun pagesPresent() = PlayerPage.entries.filter {
        present(when (it) {
            PlayerPage.MENU -> PlayerPagesTags.Menu
            PlayerPage.PLAYER -> PlayerPagesTags.Player
            PlayerPage.LYRICS -> PlayerPagesTags.Lyrics
            PlayerPage.DETAILS -> PlayerPagesTags.Details
        })
    }

    private fun showIn(lyricsMode: com.lostf1sh.pixelplayeross.data.preferences.LyricsMode, startAt: Int = 1, overscroll: Boolean = true) {
        mode = lyricsMode
        show(startAt = startAt, overscroll = overscroll)
    }

    private val threePages = com.lostf1sh.pixelplayeross.data.preferences.LyricsMode.OFF
    private val fourPages = com.lostf1sh.pixelplayeross.data.preferences.LyricsMode.FULL_PAGE

    @Test
    fun with_the_lyrics_off_the_player_is_menu_player_details_and_opens_on_player() {
        showIn(threePages)
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
        // right of Player is the Details: there is no Lyrics between them
        swipeLeftAt(titleSpot())
        assertThat(page()).isEqualTo(PlayerPage.DETAILS)
        assertThat(pagesPresent()).containsExactly(PlayerPage.DETAILS)
        compose.onAllNodesWithText("The details page").assertCountEquals(1)
        // nothing beyond them
        swipeLeftAt(Offset(540f, 1200f))
        assertThat(page()).isEqualTo(PlayerPage.DETAILS)
        // and to the left of Player is still the Menu
        swipeRightAt(Offset(540f, 1200f))
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
        swipeRightAt(titleSpot())
        assertThat(page()).isEqualTo(PlayerPage.MENU)
    }

    @Test
    fun with_four_pages_the_lyrics_are_still_between_player_and_details() {
        showIn(fourPages)
        swipeLeftAt(titleSpot())
        assertThat(page()).isEqualTo(PlayerPage.LYRICS)
        swipeLeftAt(Offset(540f, 1200f))
        assertThat(page()).isEqualTo(PlayerPage.DETAILS)
    }

    private fun coverSwipeChangesTheSongAndNotThePage(m: com.lostf1sh.pixelplayeross.data.preferences.LyricsMode) {
        showIn(m, startAt = 1)
        swipeCover(coverBounds.center, dxPx = -450f)
        assertThat(calls).containsExactly("playFrom:2")
        assertThat(signal.onPlayerPage).isTrue()
    }

    @Test fun with_three_pages_a_swipe_on_the_cover_changes_the_song_and_not_the_page() = coverSwipeChangesTheSongAndNotThePage(threePages)

    @Test fun with_four_pages_a_swipe_on_the_cover_changes_the_song_and_not_the_page() = coverSwipeChangesTheSongAndNotThePage(fourPages)

    private fun coverSwipeAtTheEndsDoesNotTurnThePage(m: com.lostf1sh.pixelplayeross.data.preferences.LyricsMode) {
        showIn(m, startAt = 0)
        swipeCover(coverBounds.center, dxPx = 800f)
        assertThat(signal.page).isEqualTo(PlayerPage.PLAYER)
        assertThat(calls.filter { it.startsWith("playFrom") }).isEmpty()
    }

    @Test fun with_three_pages_a_swipe_on_the_cover_at_the_first_song_does_not_turn_the_page() = coverSwipeAtTheEndsDoesNotTurnThePage(threePages)

    @Test fun with_four_pages_a_swipe_on_the_cover_at_the_first_song_does_not_turn_the_page() = coverSwipeAtTheEndsDoesNotTurnThePage(fourPages)

    @Test
    fun with_three_pages_a_swipe_on_the_cover_at_the_last_song_does_not_turn_the_page_and_neither_does_one_with_no_overscroll_effect() {
        showIn(threePages, startAt = 2)
        swipeCover(coverBounds.center, dxPx = -800f)
        assertThat(signal.page).isEqualTo(PlayerPage.PLAYER)
        assertThat(calls.filter { it.startsWith("playFrom") }).isEmpty()
    }

    @Test
    fun with_three_pages_and_no_overscroll_effect_a_swipe_on_the_cover_at_the_last_song_still_does_not_turn_the_page() {
        showIn(threePages, startAt = 2, overscroll = false)
        swipeCover(coverBounds.center, dxPx = -800f)
        assertThat(signal.page).isEqualTo(PlayerPage.PLAYER)
        assertThat(calls.filter { it.startsWith("playFrom") }).isEmpty()
    }

    private fun swipeElsewhereChangesThePageAndNeverTheSong(m: com.lostf1sh.pixelplayeross.data.preferences.LyricsMode) {
        showIn(m)
        swipeLeftAt(titleSpot())
        assertThat(page()).isNotEqualTo(PlayerPage.PLAYER)
        assertThat(calls.filter { it.startsWith("playFrom") }).isEmpty()
    }

    @Test fun with_three_pages_a_swipe_that_starts_elsewhere_changes_the_page_and_never_the_song() = swipeElsewhereChangesThePageAndNeverTheSong(threePages)

    @Test fun with_four_pages_a_swipe_that_starts_elsewhere_changes_the_page_and_never_the_song() = swipeElsewhereChangesThePageAndNeverTheSong(fourPages)

    @Test
    fun the_lyrics_header_action_is_not_a_page_when_the_lyrics_are_off_and_asks_for_the_sheet_instead() {
        showIn(threePages)
        // there is no Lyrics page to go to: the screen reader's Lyrics action opens the small sheet that brings it back
        compose.playerPageAction(s(R.string.player_header_lyrics), toggle = false)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertThat(page()).isEqualTo(PlayerPage.PLAYER)
        compose.onAllNodesWithText(s(R.string.lyrics_mode_title)).assertCountEquals(1)
    }
}
