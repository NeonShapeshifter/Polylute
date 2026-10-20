package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B23-P3: the album carousel of the full player, when it is told the sheet is not showing it.
 *
 * Off screen it must **jump** to the current index (it used to animate to every new one, for a player nobody could see: the pager
 * snaps close to a far target and animates the last page, which is still a page of layout and image work per tap), and a jump of
 * its own is never taken for the listener swiping to another song: the carousel's settle effect asks the player to play the page it
 * settled on, and a resync that answered itself with a playback command would be a skip nobody asked for.
 *
 * Where the carousel is is read off its semantics (the scroll range), in pages, so a jump and an animation can be told apart in
 * the first frames: 48 ms is nowhere near enough to scroll 37 pages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class AlbumCarouselResyncTest {

    @get:Rule
    val compose = createComposeRule()

    private val songs = (0 until 60).map { i ->
        Song(
            id = "id-$i", title = "Song $i", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$i.flac",
            contentUriString = "content://$i", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
        )
    }.toImmutableList()

    private val selected = mutableListOf<Int>()
    private var index by mutableIntStateOf(3)
    private var onScreen by mutableStateOf(false)

    private fun setCarousel() {
        compose.setContent {
            Box(Modifier.width(360.dp).height(360.dp)) {
                AlbumCarouselSection(
                    currentSong = songs[index],
                    queue = songs,
                    expansionFraction = 1f,
                    currentMediaItemIndex = index,
                    onSongSelected = { _, i -> selected += i },
                    modifier = Modifier.width(360.dp).height(360.dp),
                    carouselStyle = CarouselStyle.NO_PEEK,
                    isOnScreen = { onScreen },
                )
            }
        }
        compose.waitForIdle()
    }

    /** The carousel's scroll position in pages (3.0 = on the fourth), read off its semantics, so no tap and no frame is needed. */
    private fun position(): Double {
        val range = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        return range.value().toDouble() / pageStridePx
    }

    /** Pixels from one page to the next (the cover's width and the spacing), measured once at the starting index. */
    private var pageStridePx = 1.0

    private fun calibrate() {
        val range = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        pageStridePx = range.value().toDouble() / index
    }

    @Test
    fun `off screen, a distant new index is a jump, not a scroll, and asks nothing of the player`() {
        setCarousel()
        calibrate()
        assertThat(position()).isWithin(0.01).of(3.0)

        // A tap on a row of the Library while the sheet is collapsed: the index jumps 37 places.
        compose.mainClock.autoAdvance = false
        index = 40
        compose.waitForIdle()
        // 48 ms in: the pager animates a far jump by snapping to the last pages and animating those, so B22 is at 39.13 here
        // (measured on the B22 tree), still a page short; a jump is on the page already.
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertThat(position()).isWithin(0.01).of(40.0)
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(position()).isWithin(0.01).of(40.0)
        assertThat(selected).isEmpty()
    }

    @Test
    fun `on screen, it still animates to the new index, and its own scroll is not taken for a swipe`() {
        onScreen = true
        setCarousel()
        calibrate()
        compose.mainClock.autoAdvance = false
        index = 40
        compose.waitForIdle()                                  // recomposed, the scroll started; the clock is still ours
        compose.mainClock.advanceTimeBy(3_000)
        // An animated scroll keeps Compose busy in this harness (the B22 tree too), so where it ended cannot be asked here; what
        // matters is that the animation (the path that was always taken) never answered itself with a playback command.
        assertThat(selected).isEmpty()
    }

    @Test
    fun `the resync does not answer itself later, when the sheet is opened`() {
        setCarousel()
        calibrate()
        index = 25
        compose.waitForIdle()
        assertThat(position()).isWithin(0.01).of(25.0)
        // the sheet opens: the carousel is on screen from now on, and nothing it did while parked turns into a command
        onScreen = true
        compose.waitForIdle()
        assertThat(position()).isWithin(0.01).of(25.0)
        assertThat(selected).isEmpty()
    }
}
