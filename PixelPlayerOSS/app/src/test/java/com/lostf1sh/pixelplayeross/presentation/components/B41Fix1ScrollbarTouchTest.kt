package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B41-fix1 (B9): the scrollbar over the end margin. Its touch target is 48 dp wide and can be reached; it grabs only the thumb (or the thin strip
 * at the very edge), so a tap on a row's trailing control and a scroll that starts near the edge are the list's; and a thumb that would sit over
 * the content while idle shows only while the list scrolls and fades out after a moment.
 *
 * The page is 360 dp wide at 2 px per dp (the HONOR's 720 px): the thumb is 3 dp wide, 7 dp from the end edge (a 4 dp inset and a 3 dp margin).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B41Fix1ScrollbarTouchTest {
    @get:Rule
    val compose = createComposeRule()

    private val px = 2f
    private var clicks = 0
    private lateinit var state: LazyListState
    private val card = Color(0xFF2A2F36)
    private val page = Color(0xFF101214)

    @Composable
    private fun Page(contentEndMargin: androidx.compose.ui.unit.Dp) {
        state = rememberLazyListState()
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            Box(Modifier.fillMaxSize().background(page)) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = symmetricContentPadding(horizontal = contentEndMargin)) {
                    items((0 until 120).toList()) { i ->
                        Row(Modifier.fillMaxWidth().height(56.dp).background(card), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            // a row's trailing control, at the content's end
                            Box(Modifier.size(40.dp).clickable { if (i == 3) clicks++ })
                        }
                    }
                }
                ExpressiveScrollBar(
                    contentEndMargin = contentEndMargin,
                    listState = state,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 16.dp, bottom = 16.dp),
                )
            }
        }
    }

    private fun show(margin: androidx.compose.ui.unit.Dp) {
        compose.setContent { Page(margin) }
        compose.waitForIdle()
    }

    private fun touchBounds() = compose.onNodeWithTag(ScrollbarGutter.TOUCH_TAG).fetchSemanticsNode().boundsInRoot

    @Test
    fun the_touch_target_is_48_dp_wide_over_the_end_margin_and_the_content_s_edge() {
        show(12.dp)
        val bounds = touchBounds()
        assertThat(bounds.width).isAtLeast(48f * px)
        // at the end edge (less the inset), over the last 48 dp of the page
        assertThat(bounds.right).isAtLeast(360f * px - 4f * px - 1f)
        // and the page's content under it is not shifted: the rows end where the end margin ends
        assertThat(ScrollbarGutter.TouchWidth.value).isAtLeast(48f)
    }

    @Test
    fun a_thumb_held_at_26_dp_from_the_edge_scrolls_the_list_not_only_the_thin_strip() {
        show(12.dp)
        val bounds = touchBounds()
        // 26 dp from the end edge of the target: not in the 16 dp strip, on the thumb's row (it is at the top, 40 dp tall)
        val x = bounds.width - 26f * px
        compose.onNodeWithTag(ScrollbarGutter.TOUCH_TAG).performTouchInput {
            down(Offset(x, 10f * px))
            moveBy(Offset(0f, 120f * px), delayMillis = 100)
        }
        compose.waitForIdle()
        val reached = state.firstVisibleItemIndex
        compose.onNodeWithTag(ScrollbarGutter.TOUCH_TAG).performTouchInput { up() }
        assertThat(reached).isAtLeast(10)
    }

    @Test
    fun the_thin_strip_at_the_edge_still_jumps_to_where_it_is_pressed() {
        show(12.dp)
        val bounds = touchBounds()
        // 5 dp from the end edge, nowhere near the thumb: the track
        val x = bounds.width - 5f * px
        compose.onNodeWithTag(ScrollbarGutter.TOUCH_TAG).performTouchInput {
            down(Offset(x, 300f * px))
            moveBy(Offset(0f, 40f * px), delayMillis = 100)
        }
        compose.waitForIdle()
        val reached = state.firstVisibleItemIndex
        compose.onNodeWithTag(ScrollbarGutter.TOUCH_TAG).performTouchInput { up() }
        assertThat(reached).isAtLeast(20)
    }

    @Test
    fun a_press_in_the_wide_target_that_is_not_on_the_thumb_is_not_the_bars() {
        show(12.dp)
        val bounds = touchBounds()
        // 26 dp from the edge and 300 dp down: neither the thin strip nor the thumb, so a drag here must not move the thumb
        val x = bounds.width - 26f * px
        compose.onNodeWithTag(ScrollbarGutter.TOUCH_TAG).performTouchInput {
            down(Offset(x, 300f * px))
            moveBy(Offset(0f, -200f * px), delayMillis = 100)
        }
        compose.waitForIdle()
        // the list took the drag itself: by its own scrolling (200 dp of rows, 3 or 4 of them), not a jump to a place on the track
        val reached = state.firstVisibleItemIndex
        compose.onNodeWithTag(ScrollbarGutter.TOUCH_TAG).performTouchInput { up() }
        compose.waitForIdle()
        assertThat(reached).isIn(com.google.common.collect.Range.closed(1, 8))
    }

    @Test
    fun a_tap_on_a_rows_trailing_control_inside_the_wide_target_reaches_the_row() {
        show(12.dp)
        val bounds = touchBounds()
        // the control of row 3 is 40 dp wide and ends 12 dp from the end edge, so its middle is 32 dp from it: inside the 48 dp target,
        // outside the 16 dp strip. The list starts 16 dp above the target's top (the bar is inset 16 dp), and a row is 56 dp.
        val y = 3 * 56f * px + 28f * px - 16f * px
        compose.onNodeWithTag(ScrollbarGutter.TOUCH_TAG).performTouchInput {
            click(Offset(bounds.width - 32f * px, y))
        }
        compose.waitForIdle()
        assertThat(clicks).isEqualTo(1)
    }

    // ── the thumb over the content ───────────────────────────────────────────────────────────────────

    private fun thumbPixel(): Int {
        val image = compose.onRoot().captureToImage().toPixelMap()
        val x = ((360f - 8.5f) * px).toInt()
        val y = (36f * px).toInt()
        return image[x, y].toArgb()
    }

    @Test
    fun a_thumb_that_would_cover_the_content_is_there_while_it_scrolls_and_gone_a_moment_after() {
        compose.mainClock.autoAdvance = false
        show(0.dp)
        compose.mainClock.advanceTimeBy(100)
        val shown = thumbPixel()
        // idle for longer than the fade's delay and its duration
        compose.mainClock.advanceTimeBy(ScrollbarGutter.IDLE_FADE_DELAY_MS + 600)
        val gone = thumbPixel()
        assertThat(shown).isNotEqualTo(gone)
        assertThat(Color(gone).red).isAtMost(Color(shown).red)
        // scrolling brings it back
        compose.runOnIdle { kotlinx.coroutines.runBlocking { state.scrollToItem(10) } }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertThat(Color(thumbPixel()).alpha).isEqualTo(1f)
    }

    @Test
    fun a_thumb_that_sits_in_the_margin_stays_where_it_is_and_covers_nothing() {
        compose.mainClock.autoAdvance = false
        show(12.dp)
        compose.mainClock.advanceTimeBy(100)
        val shown = thumbPixel()
        compose.mainClock.advanceTimeBy(ScrollbarGutter.IDLE_FADE_DELAY_MS + 600)
        assertThat(thumbPixel()).isEqualTo(shown)
        // geometry: the idle thumb reaches 10 dp from the edge, the content ends 12 dp from it
        assertThat(ScrollbarGutter.thumbCoversContent(12.dp)).isFalse()
        assertThat(ScrollbarGutter.idleReach()).isLessThan(12.dp)
        // and the row's own pixels at the end of the content are the card's, not the thumb's
        val image = compose.onRoot().captureToImage().toPixelMap()
        val endOfContent = ((360f - 12f) * px).toInt() - 2
        assertThat(image[endOfContent, (36f * px).toInt()].toArgb()).isEqualTo(card.toArgb())
    }
}
