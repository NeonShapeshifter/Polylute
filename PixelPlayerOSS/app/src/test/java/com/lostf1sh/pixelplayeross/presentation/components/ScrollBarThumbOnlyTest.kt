package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.B29Renders
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * B29-fix1 F6: the scrollbar is a thumb and nothing else.
 *
 * On the phone (Library > Listas and Songs) the right edge showed a bright thumb, a gap and a long dim track below it (and one above),
 * which read as two scroll indicators. It was one `ExpressiveScrollBar` drawing its track in two pieces around the thumb. The track is
 * gone; this reads the scrollbar's own column of pixels: one run of colour where the thumb is, none above or below it.
 *
 * The rows are made-up stand-ins for the Songs and the Listas pages (the lists on those pages need the app's view models); the
 * scrollbar is the real one, at the HONOR's size (720 x 1610 px, density 2.0), with the settings the Library gives it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class ScrollBarThumbOnlyTest {

    @get:Rule
    val compose = createComposeRule()

    private var backgroundArgb = 0

    private fun show(rows: List<Pair<String, String>>) {
        compose.setContent {
            val state = rememberLazyListState()
            backgroundArgb = Dap.background.toArgb()
            Box(Modifier.fillMaxSize().background(Dap.background)) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(rows.size) { i ->
                        Column(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp)) {
                            Text(rows[i].first, style = DapType.Body, color = Dap.primaryText)
                            Text(rows[i].second, style = DapType.Value, color = Dap.secondaryText)
                        }
                    }
                }
                ExpressiveScrollBar(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 16.dp, bottom = 140.dp),
                    listState = state,
                )
                androidx.compose.runtime.LaunchedEffect(Unit) { state.scrollToItem(rows.size / 2) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** The runs of non-background pixels down the column the scrollbar is drawn in (its middle: 4 dp padding + 3 dp thumb, from the edge). */
    private fun runsInScrollbarColumn(): List<IntRange> {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val x = bitmap.width - (4 + 3 + 1) * 2 // 8 dp in from the edge, at 2 px per dp: inside the 3 dp thumb and the old track's 3 dp line
        val runs = ArrayList<IntRange>()
        var start = -1
        for (y in 0 until bitmap.height) {
            val differs = abs(luma(bitmap.getPixel(x, y)) - luma(backgroundArgb)) > 6
            if (differs && start < 0) start = y
            if (!differs && start >= 0) { runs += start until y; start = -1 }
        }
        if (start >= 0) runs += start until bitmap.height
        return runs
    }

    private fun luma(argb: Int): Int = (((argb shr 16) and 0xFF) * 299 + ((argb shr 8) and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000

    private val songs = (1..300).map { "Song title number $it" to "Artist ${it % 40} - Album ${it % 17}" }
    private val playlists = (1..120).map { "Playlist $it" to "${it % 50 + 3} songs" }

    @Test
    fun `the Songs list shows a thumb and no track above or below it`() {
        show(songs)
        B29Renders.saveFix1(compose, "F6-songs-scrollbar-thumb-only")
        val runs = runsInScrollbarColumn()
        assertThat(runs).hasSize(1)
        // the thumb: 40 dp at 2 px per dp
        assertThat(runs.single().last - runs.single().first + 1).isIn(com.google.common.collect.Range.closed(70, 90))
    }

    @Test
    fun `the Listas list shows a thumb and no track above or below it`() {
        show(playlists)
        B29Renders.saveFix1(compose, "F6-listas-scrollbar-thumb-only")
        val runs = runsInScrollbarColumn()
        assertThat(runs).hasSize(1)
        assertThat(runs.single().last - runs.single().first + 1).isIn(com.google.common.collect.Range.closed(70, 90))
    }
}
