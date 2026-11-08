package com.lostf1sh.pixelplayeross.presentation

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.components.ExpressiveScrollBar
import com.lostf1sh.pixelplayeross.presentation.components.ScrollBubble
import com.lostf1sh.pixelplayeross.presentation.components.ScrollDragSignal
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B58 5 (#76): Library > Songs, dragging near the bottom: the 56 dp bubble was drawn under the locate disc and its letter was half hidden. The Library now fades its floating discs while
 * a scroll bar is dragged (my choice over re-parenting the bubble above the Library: the bubble lives inside the list's page, and a fade is one shared signal that every other list
 * ignores). The bar's own touch rules are not touched.
 *
 * The screen here is made-up rows and two stand-ins for the discs, with the Library's own fade rule (the real `LibraryScreen` needs its view models); `LibraryScreen` is checked
 * by a source test for the one line that applies it. The "before" PNG is the same screen with the fade switched off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B58ScrollBubbleOverDiscsTest {
    @get:Rule val compose = createComposeRule()

    private val titles: List<String> = ('A'..'Z').flatMap { l -> (1..14).map { "$l song $it" } }

    @After fun clear() = ScrollDragSignal.reset()

    private fun screen(fadeDiscs: Boolean, dark: Boolean = true) {
        compose.setContent {
            val state = rememberLazyListState()
            val discAlpha by animateFloatAsState(if (fadeDiscs && ScrollDragSignal.dragging) 0f else 1f, tween(ScrollDragSignal.FADE_MS), label = "discs")
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        items(titles.size) { i ->
                            DapMediaRow(
                                title = titles[i], subtitle = "Artist One - Album ${i / 14 + 1}", duration = "3:${10 + i % 50}",
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
                    // the Library's locate and shuffle discs: the same tonal circle, 48 dp, at the end edge over the bottom of the list, drawn after (over) the list
                    Column(
                        Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 150.dp).graphicsLayer { alpha = discAlpha },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(Icons.Rounded.MyLocation, Icons.Rounded.Shuffle).forEach { icon ->
                            FilledTonalIconButton(
                                onClick = {}, shape = CircleShape, modifier = Modifier.size(48.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Dap.surfaceRaised, contentColor = Dap.secondaryText),
                            ) { Icon(icon, contentDescription = null) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Takes the thumb and drags it to the bottom of the list, where the bubble meets the discs. */
    private fun dragToBottom() {
        val node = compose.onRoot().fetchSemanticsNode()
        val width = node.size.width.toFloat()
        val height = node.size.height.toFloat()
        compose.onRoot().performTouchInput {
            down(Offset(width - 12f, 100f))
            moveTo(Offset(width - 12f, 140f))
            moveTo(Offset(width - 12f, height - 200f))
        }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
    }

    @Test fun the_signal_is_true_while_a_thumb_is_held_and_false_after_it_lets_go() {
        screen(fadeDiscs = true)
        assertThat(ScrollDragSignal.dragging).isFalse()
        dragToBottom()
        assertThat(ScrollDragSignal.dragging).isTrue()
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertThat(ScrollDragSignal.dragging).isFalse()
    }

    @Test fun the_signal_counts_holders_so_that_one_letting_go_does_not_hide_another() {
        ScrollDragSignal.begin()
        ScrollDragSignal.begin()
        ScrollDragSignal.end()
        assertThat(ScrollDragSignal.dragging).isTrue()
        ScrollDragSignal.end()
        assertThat(ScrollDragSignal.dragging).isFalse()
        ScrollDragSignal.end()
        assertThat(ScrollDragSignal.dragging).isFalse()
    }

    @Test fun at_the_bottom_of_songs_the_bubble_is_drawn_whole_and_the_discs_are_gone_while_it_is() {
        screen(fadeDiscs = true)
        dragToBottom()
        compose.onNodeWithTag(ScrollBubble.TAG).assertExists()
        val bubble = compose.onNodeWithTag(ScrollBubble.TAG).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        // whole: inside the screen on every side
        assertThat(bubble.top).isAtLeast(root.top)
        assertThat(bubble.bottom).isAtMost(root.bottom)
        assertThat(bubble.right).isAtMost(root.right)
        assertThat(ScrollDragSignal.dragging).isTrue()
        B58Renders.save(compose, "scroll-bubble-songs-bottom-discs-faded")
    }

    @Test fun without_the_fade_the_bubble_meets_the_discs_as_it_did_before() {
        screen(fadeDiscs = false)
        dragToBottom()
        B58Renders.save(compose, "scroll-bubble-songs-bottom-discs-shown")
    }

    @Test fun the_library_fades_its_discs_with_this_signal_and_the_bar_reports_to_it() {
        val library = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/LibraryScreen.kt").readText()
        assertThat(library).contains("ScrollDragSignal.dragging")
        assertThat(library).contains("graphicsLayer { alpha = floatingDiscAlpha }")
        val bar = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/ExpressiveScrollBar.kt").readText()
        assertThat(bar).contains("ScrollDragSignal.begin()")
        assertThat(bar).contains("ScrollDragSignal.end()")
        // the bar's touch rules are as B41/B57.5 left them: the gesture runs from the outer box and the bubble takes no touch
        assertThat(bar).doesNotContain("ScrollDragSignal.dragging")
    }
}
