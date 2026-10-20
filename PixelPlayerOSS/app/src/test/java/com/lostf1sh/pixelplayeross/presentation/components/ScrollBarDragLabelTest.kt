package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B29-L8 (N-10): the label while the Library's scrollbar is dragged, on the real [ExpressiveScrollBar] over a list ordered the way
 * the library orders it. The provider is a plain lookup of the row under the thumb in the list that is on screen: no sort and no
 * query per move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class ScrollBarDragLabelTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(title: String, dateAdded: Long = 0L) = Song.emptySong().copy(id = title, title = title, dateAdded = dateAdded)

    /** 26 letters x 12 songs, with a few symbol- and digit-led titles at the head, in `COLLATE NOCASE` order. */
    private val songs: List<Song> = (listOf("!!!!!!!", "\"Quoted\"", "#Hash", "\$Cash", "007", "1999") +
        ('A'..'Z').flatMap { letter -> (1..12).map { "$letter song $it" } }).map { song(it) }

    private var lookups = 0

    private fun show(sort: SortOption): androidx.compose.foundation.lazy.LazyListState {
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            state = rememberLazyListState()
            Box(Modifier.fillMaxSize().background(Dap.background)) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(songs, key = { _, s -> s.id }) { _, s ->
                        Box(Modifier.fillMaxWidth().height(56.dp)) { Text(s.title) }
                    }
                }
                ExpressiveScrollBar(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    listState = state,
                    dragLabelProvider = { index -> lookups++; songFastScrollLabel(songs.getOrNull(index), sort) },
                )
            }
        }
        compose.waitForIdle()
        return state
    }

    private fun labelsOnScreen(): List<String> = ('A'..'Z').map { it.toString() }.plus("#").filter {
        compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `dragging the thumb shows the section under it, and it is gone when the finger lifts`() {
        val state = show(SortOption.SongTitleAZ)
        val width = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
        val height = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        // the thumb is at the top of the track, on the right edge
        compose.onRoot().performTouchInput {
            down(Offset(width - 12f, 120f))
            moveTo(Offset(width - 12f, height * 0.5f))
        }
        compose.waitForIdle()
        val target = state.firstVisibleItemIndex
        assertThat(target).isGreaterThan(100)
        val expected = songFastScrollLabel(songs[target], SortOption.SongTitleAZ)!!
        // the row's own title starts with it, so the label is the title's first letter and not a guess
        assertThat(songs[target].title.first().toString()).isEqualTo(expected)
        assertThat(compose.onAllNodesWithText(expected).fetchSemanticsNodes()).isNotEmpty()
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test
    fun `the symbols and digits at the head of the list are the hash section`() {
        show(SortOption.SongTitleAZ)
        val labels = songs.take(6).map { songFastScrollLabel(it, SortOption.SongTitleAZ) }
        assertThat(labels).containsExactly("#", "#", "#", "#", "#", "#")
        assertThat(songFastScrollLabel(songs[6], SortOption.SongTitleAZ)).isEqualTo("A")
    }

    @Test
    fun `a sort that is not alphabetical never shows a letter while dragging`() {
        val state = show(SortOption.SongDuration)
        val width = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
        val height = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        compose.onRoot().performTouchInput {
            down(Offset(width - 12f, 120f))
            moveTo(Offset(width - 12f, height * 0.5f))
        }
        compose.waitForIdle()
        assertThat(state.firstVisibleItemIndex).isGreaterThan(100)
        assertThat(labelsOnScreen().filter { it.length == 1 && compose.onAllNodesWithText(it).fetchSemanticsNodes().any { n -> n.size.width < 200 } }).isEmpty()
        compose.onRoot().performTouchInput { up() }
    }

    @Test
    fun `the provider is a lookup, not a sort or a query, and dragging asks it for one row per move`() {
        show(SortOption.SongTitleAZ)
        val width = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
        val height = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        lookups = 0
        compose.onRoot().performTouchInput {
            down(Offset(width - 12f, 120f))
            repeat(10) { step -> moveTo(Offset(width - 12f, 120f + (height * 0.5f - 120f) * (step + 1) / 10f)) }
        }
        compose.waitForIdle()
        // a handful of reads per frame at most, never a pass over the list (which would be 150+ per move)
        assertThat(lookups).isLessThan(120)
        compose.onRoot().performTouchInput { up() }
    }
}
