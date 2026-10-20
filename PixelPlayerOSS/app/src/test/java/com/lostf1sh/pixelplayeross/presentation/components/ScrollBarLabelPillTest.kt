package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.presentation.B29Renders
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * B29-fix1 F5: the letter beside the Library scrollbar's thumb, at the HONOR's size (720 x 1610 px, density 2.0).
 *
 * On the phone the pill showed with no letter in it. The scrollbar's own box is [indicatorExpandedWidth] + [paddingEnd] = 9 dp wide, and
 * the label's 32 dp circle was laid out inside it, so it inherited that width as its maximum and came out 9 dp wide: a narrow capsule
 * with no room for the glyph (its 8 dp of padding each side left less than nothing). The text was in the semantics tree all along, which
 * is all B29's test looked at; this one reads the label's **size and its pixels**, over the paged list the real Songs screen draws and
 * with the provider that screen builds (`songs.peek(index)`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class ScrollBarLabelPillTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(title: String) = Song.emptySong().copy(id = title, title = title)

    /** 40 titles from A, then 30 that start with I (the phone's "Intro (A86)", "Introducción…", "Invertebrado"…), then 30 from Z. */
    private val songs: List<Song> = (
        (0 until 40).map { "Abba song $it" } +
            (0 until 30).map { listOf("Intro (A86)", "Introducción - Remastered", "Invertebrado", "Invincible", "IRIS OUT", "Iron Man")[it % 6] + " $it" } +
            (0 until 30).map { "Zeta song $it" }
        ).map { song(it) }

    private var pillColorArgb = 0

    private fun show(): androidx.compose.foundation.lazy.LazyListState {
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            state = rememberLazyListState()
            val paged = flowOf(PagingData.from(songs)).collectAsLazyPagingItems()
            pillColorArgb = Dap.surfaceRaised.toArgb()
            // exactly what LibrarySongsTab builds
            val provider = { index: Int -> songFastScrollLabel(song = paged.peek(index), sortOption = SortOption.SongTitleAZ) }
            Box(Modifier.fillMaxSize().background(Dap.background)) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(count = paged.itemCount, key = paged.itemKey { it.id }) { index ->
                        val s = paged[index]
                        Box(Modifier.fillMaxWidth().height(56.dp)) { Text(s?.title.orEmpty()) }
                    }
                }
                ExpressiveScrollBar(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 16.dp, bottom = 140.dp),
                    listState = state,
                    dragLabelProvider = provider,
                )
            }
        }
        compose.waitForIdle()
        return state
    }

    private fun dragToTheMiddle() {
        val node = compose.onRoot().fetchSemanticsNode()
        val width = node.size.width.toFloat()
        val height = node.size.height.toFloat()
        compose.onRoot().performTouchInput {
            // the thumb is at the top of the track, on the right edge: 12 px in from the window's edge
            down(Offset(width - 12f, 100f))
            moveTo(Offset(width - 12f, height * 0.42f))
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun pill() = compose.onAllNodesWithText("I").fetchSemanticsNodes().single { it.size.width < 200 }

    /** How many pixels around ([x], [y]) have the pill's own colour, going [dx], [dy] each way. */
    private fun span(bitmap: android.graphics.Bitmap, x: Int, y: Int, dx: Int, dy: Int): Int {
        fun isPill(px: Int, py: Int) = px in 0 until bitmap.width && py in 0 until bitmap.height &&
            abs(luma(bitmap.getPixel(px, py)) - luma(pillColorArgb)) <= 3
        var n = 0
        var px = x
        var py = y
        while (isPill(px, py)) { n++; px += dx; py += dy }
        px = x - dx
        py = y - dy
        while (isPill(px, py)) { n++; px -= dx; py -= dy }
        return n
    }

    @Test
    fun `holding the thumb over the I rows shows a letter I in a round pill of 32 dp`() {
        val state = show()
        dragToTheMiddle()
        B29Renders.saveFix1(compose, "F5-letter-pill-I-720x1610")
        assertThat(state.firstVisibleItemIndex).isIn(com.google.common.collect.Range.closed(40, 69))

        val glyph = pill().boundsInRoot
        // the letter has a width: in a pill 9 dp wide it was given none (0 px)
        assertThat(glyph.width).isAtLeast(6f)
        assertThat(glyph.height).isAtLeast(10f)

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val cx = glyph.center.x.toInt()
        val cy = glyph.center.y.toInt()
        // 32 dp at 2 px per dp is 64 px: a chord 18 px above the centre is ~52 px, the column through the centre ~64 px.
        // The scrollbar's own box is 9 dp (18 px) wide, which is what the pill used to be.
        assertThat(span(bitmap, cx, cy - 18, dx = 1, dy = 0)).isAtLeast(40)
        assertThat(span(bitmap, cx - 16, cy, dx = 0, dy = 1)).isAtLeast(50)
    }

    @Test
    fun `the letter is drawn, not only present in the semantics tree`() {
        show()
        dragToTheMiddle()
        val bounds = pill().boundsInRoot
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val pillLuma = luma(pillColorArgb)
        // the pill around the letter: a glyph in the text colour is much lighter than the pill
        val cx = bounds.center.x.toInt()
        val cy = bounds.center.y.toInt()
        var lighter = 0
        for (y in cy - 14..cy + 14) for (x in cx - 10..cx + 10) {
            if (luma(bitmap.getPixel(x, y)) - pillLuma > 60) lighter++
        }
        assertThat(lighter).isAtLeast(15)
    }

    @Test
    fun `a row that is still a placeholder has no label, so there is no pill to be empty`() {
        assertThat(songFastScrollLabel(null, SortOption.SongTitleAZ)).isNull()
        assertThat(songFastScrollLabel(Song.emptySong(), SortOption.SongTitleAZ)).isNull()
    }

    @Test
    fun `no pill is drawn at all while the finger is up`() {
        show()
        assertThat(compose.onAllNodesWithText("I").fetchSemanticsNodes().filter { it.size.width < 200 }).isEmpty()
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
