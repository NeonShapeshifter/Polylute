package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerSkipCoordinator
import com.lostf1sh.pixelplayeross.presentation.components.player.rememberPlayerSkipCoordinator
import kotlin.math.abs
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B56 phase 1.1 (#47): in Library > Songs, tap shuffle; while the songs change the player's cover stays on the old image.
 *
 * What the tests do: they drive the real skip coordinator and the real cover carousel (the two halves `FullPlayerScreen` puts together) with the states the view
 * model publishes around a shuffle (`PlayerViewModel.internalPlaySongs`, `updateCurrentPlaybackQueueFromPlayer`): the queue the screen lists is first the source list
 * in the order it is shown (base order) and the player's index is a row of the first block the player holds; a moment later the list is the player's play order.
 * The player answers a skip some milliseconds after it was asked, with the next song of the *dealt* sequence.
 *
 * "The drawn art matches the current song" is read off the carousel's scroll position: the page it rests on is the page of the current song in the list the carousel
 * was given, and the focal page's art carries that song's title (the placeholder's description).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class B56ShuffleCoverFollowsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(i: Int) = Song(
        id = "id-$i", title = "Song $i", artist = "Mägo de Oz", artistId = 7L, album = "Album $i", albumId = 100L + i, path = "/m/$i.flac",
        contentUriString = "content://$i", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    /** The source list as the Songs tab shows it. */
    private val base: ImmutableList<Song> = (0 until LIBRARY).map { song(it) }.toImmutableList()

    /** The sequence the shuffle dealt: a fixed permutation of the library (no two neighbours in the list are neighbours here). */
    private val dealt: List<Int> = (0 until LIBRARY).map { (it * 37 + 11) % LIBRARY }

    private var queue: ImmutableList<Song> by mutableStateOf(base)
    private var current: Song by mutableStateOf(base[3])
    private var rowIndex by mutableIntStateOf(3)
    private val selected = mutableListOf<Int>()
    private var skipRequests = 0
    private lateinit var skip: PlayerSkipCoordinator
    private lateinit var scrollRange: ScrollAxisRange
    private var pageStridePx = 1.0

    private fun setUp(style: String) {
        compose.setContent {
            val coordinator = rememberPlayerSkipCoordinator(
                song = current,
                currentPlaybackQueue = queue,
                currentMediaItemIndex = rowIndex,
                repeatMode = Player.REPEAT_MODE_OFF,
                onNext = { skipRequests++ },
                onPrevious = { },
                previousDecisionPositionMs = { 10_000L },
            )
            skip = coordinator
            Box(Modifier.width(360.dp).height(360.dp)) {
                AlbumCarouselSection(
                    currentSong = current,
                    queue = queue,
                    expansionFraction = 1f,
                    currentMediaItemIndex = coordinator.currentQueueIndex ?: rowIndex,
                    requestedScrollIndex = coordinator.pendingCarouselIndex,
                    onSongSelected = { _, i -> selected += i },
                    modifier = Modifier.width(360.dp).height(360.dp),
                    carouselStyle = style,
                    isOnScreen = { true },
                )
            }
        }
        compose.waitForIdle()
        scrollRange = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        pageStridePx = scrollRange.value().toDouble() / rowIndex
        compose.mainClock.autoAdvance = false
    }

    private fun frame(ms: Long = 16) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
    }

    private fun position(): Double = scrollRange.value().toDouble() / pageStridePx

    /** The row of the current song in the list the carousel has. */
    private fun rowOfCurrent(): Int = queue.indexOfFirst { it.id == current.id }

    // ---- what the view model publishes ------------------------------------------------------------------------------------------

    /** `internalPlaySongs`: the source list as shown, the start song of the dealt sequence, and a row of the first block as the index. */
    private fun shuffleTapped() {
        queue = base
        current = base[dealt[0]]
        rowIndex = BLOCK_START_ROW
        frame()
    }

    /** `updateCurrentPlaybackQueueFromPlayer`: the player's play order. [songs] of it, as many as the player holds yet. */
    private fun listedInPlayOrder(songs: Int, playing: Int) {
        queue = dealt.take(songs).map { base[it] }.toImmutableList()
        rowIndex = playing
        frame()
    }

    /** The player answers a skip: the next song of the dealt sequence, in the row the play-order list has it. */
    private fun playerAnswers(position: Int, listIsPlayOrder: Boolean) {
        current = base[dealt[position]]
        rowIndex = if (listIsPlayOrder) position else (position % BLOCK_SIZE)   // before the list is re-listed the row is the player's own
        frame()
    }

    private fun settleFor(ms: Long) {
        var elapsed = 0L
        while (elapsed < ms) { frame(); elapsed += 16 }
    }

    private fun assertDrawnArtIsCurrent(why: String) {
        val row = rowOfCurrent()
        assertThat(row).isAtLeast(0)
        val at = position()
        println("$why: cover rests at page ${"%.3f".format(at)}, the current song ${current.id} is row $row of ${queue.size}")
        assertThat(abs(at - row)).isLessThan(0.02)
    }

    // ---- the tests ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `after a shuffle tap the cover is on the current song once the list is in play order, then three Next each followed`() {
        setUp(CarouselStyle.NO_PEEK)
        shuffleTapped()
        settleFor(300)
        listedInPlayOrder(songs = BLOCK_SIZE, playing = 0)
        settleFor(900)
        assertDrawnArtIsCurrent("the shuffle was tapped and the block is listed")

        for (step in 1..3) {
            skip.requestNext()
            frame(60)                                  // the player answers 60 ms later
            playerAnswers(position = step, listIsPlayOrder = true)
            settleFor(900)
            assertDrawnArtIsCurrent("Next number $step, list in play order")
        }
        assertThat(selected).isEmpty()
    }

    @Test
    fun `Next pressed while the list is still the source order shows the song the player answered, not the neighbour in the source list`() {
        setUp(CarouselStyle.NO_PEEK)
        shuffleTapped()
        settleFor(400)                                 // the list still is the source order: the first block has not been listed yet
        assertDrawnArtIsCurrent("just after the tap, list in source order")

        for (step in 1..3) {
            skip.requestNext()
            frame(60)
            playerAnswers(position = step, listIsPlayOrder = false)
            settleFor(450)                             // three songs in the time the old hold would still be waiting
        }
        println("pending after the burst: ${skip.pendingCarouselIndex}")
        assertDrawnArtIsCurrent("three Next in a burst, list in source order")
        assertThat(selected).isEmpty()
    }

    @Test
    fun `a burst of Next on a list in play order ends on the current song`() {
        setUp(CarouselStyle.ONE_PEEK)
        shuffleTapped()
        settleFor(200)
        listedInPlayOrder(songs = LIBRARY, playing = 0)
        settleFor(900)
        for (step in 1..6) {
            skip.requestNext()
            frame(40)
            playerAnswers(position = step, listIsPlayOrder = true)
            frame(120)
        }
        settleFor(1_500)
        assertDrawnArtIsCurrent("six Next 160 ms apart")
        assertThat(selected).isEmpty()
    }

    @Test
    fun `the cover heads for the predicted row while the player has not answered, and the hint ends when the player answers another song`() {
        setUp(CarouselStyle.NO_PEEK)
        shuffleTapped()
        settleFor(400)                                 // source order listed

        skip.requestNext()
        frame(48)
        assertThat(skip.pendingCarouselIndex).isEqualTo(12)      // the row after the playing one (11): the player has not said anything yet
        settleFor(200)
        assertThat(skip.pendingCarouselIndex).isEqualTo(12)      // still waiting: a slow player is not contradicted by silence

        playerAnswers(position = 1, listIsPlayOrder = false)     // the player plays the dealt sequence's second song, nowhere near row 12
        assertThat(skip.pendingCarouselIndex).isNull()           // contradicted: the hint is gone in the very composition that sees the answer
        settleFor(600)
        assertDrawnArtIsCurrent("after the player answered another song")
        assertThat(selected).isEmpty()
    }

    @Test
    fun `a burst of predicted skips keeps its hint until the last predicted song arrives`() {
        setUp(CarouselStyle.NO_PEEK)
        shuffleTapped()
        settleFor(200)
        listedInPlayOrder(songs = LIBRARY, playing = 0)
        settleFor(900)

        repeat(3) { skip.requestNext(); frame(32) }               // three skips asked before the player answered any
        assertThat(skip.pendingCarouselIndex).isEqualTo(3)
        playerAnswers(position = 1, listIsPlayOrder = true)
        assertThat(skip.pendingCarouselIndex).isEqualTo(3)       // it answered the first of the three: two are still on their way
        playerAnswers(position = 2, listIsPlayOrder = true)
        assertThat(skip.pendingCarouselIndex).isEqualTo(3)
        playerAnswers(position = 3, listIsPlayOrder = true)
        settleFor(900)
        assertThat(skip.pendingCarouselIndex).isNull()
        assertDrawnArtIsCurrent("the burst arrived")
        assertThat(selected).isEmpty()
    }

    private companion object {
        const val LIBRARY = 300
        const val BLOCK_SIZE = 26
        const val BLOCK_START_ROW = 5
    }
}
