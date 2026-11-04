package com.lostf1sh.pixelplayeross.presentation

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.presentation.components.ExpressiveScrollBar
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapAlbumGridItem
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B57.5 3, PNG: the scroll bar's label mid-drag on a Songs-like list and on an Albums-like grid, light and dark, at the HONOR's size. Made-up rows (the real tabs need the app's view models); the
 * scroll bar is the real shared one, with the settings the Library gives it. The same file, run in `B57-5-before/`, writes the small disc beside the thumb as `-before.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B575ScrollBubbleRenderTest {
    @get:Rule val compose = createComposeRule()

    private val letters = ('A'..'Z').toList()
    private val titles: List<String> = letters.flatMap { l -> (1..14).map { "$l song $it" } }
    private val albums: List<String> = letters.flatMap { l -> (1..8).map { "$l album $it" } }

    private fun songs(name: String, dark: Boolean) {
        compose.setContent {
            val state = rememberLazyListState()
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        items(titles.size) { i ->
                            DapMediaRow(
                                title = titles[i], subtitle = "Gemini Sound · Album ${i / 14 + 1}", duration = "3:${(10 + i % 50)}",
                                leading = { Box(Modifier.size(48.dp).background(Dap.surfaceRaised, RoundedCornerShape(8.dp))) },
                            )
                        }
                    }
                    ExpressiveScrollBar(
                        contentEndMargin = 12.dp,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 16.dp, bottom = 140.dp),
                        listState = state,
                        dragLabelProvider = { index -> titles.getOrNull(index)?.first()?.toString() },
                    )
                }
            }
        }
        compose.waitForIdle()
        drag()
        B575Renders.save(compose, name)
        compose.onRoot().performTouchInput { up() }
    }

    private fun albumsGrid(name: String, dark: Boolean) {
        compose.setContent {
            val state = rememberLazyGridState()
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    LazyVerticalGrid(columns = GridCells.Fixed(2), state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                        items(albums.size) { i ->
                            DapAlbumGridItem(albums[i], "Gemini Sound", Modifier.padding(6.dp), detail = "2019 · 9 Songs · 38 min")
                        }
                    }
                    ExpressiveScrollBar(
                        contentEndMargin = 12.dp,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 16.dp, bottom = 140.dp),
                        gridState = state,
                        dragLabelProvider = { index -> albums.getOrNull(index)?.first()?.toString() },
                    )
                }
            }
        }
        compose.waitForIdle()
        drag()
        B575Renders.save(compose, name)
        compose.onRoot().performTouchInput { up() }
    }

    /** Presses the thumb at the top of the track and drags it to a little below the middle of the list (so there is room above the finger). */
    private fun drag() {
        val node = compose.onRoot().fetchSemanticsNode()
        val width = node.size.width.toFloat()
        val height = node.size.height.toFloat()
        compose.onRoot().performTouchInput {
            down(Offset(width - 12f, 100f))
            moveTo(Offset(width - 12f, 140f))
            moveTo(Offset(width - 12f, height * 0.55f))
        }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
    }

    @Test fun songs_dark() = songs("scroll-label-songs-dark", dark = true)
    @Test fun songs_light() = songs("scroll-label-songs-light", dark = false)
    @Test fun albums_dark() = albumsGrid("scroll-label-albums-dark", dark = true)
    @Test fun albums_light() = albumsGrid("scroll-label-albums-light", dark = false)
}
