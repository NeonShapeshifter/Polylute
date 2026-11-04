package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B57.5 3, on the real [ExpressiveScrollBar] (the one every list shares) at the HONOR's size (720 x 1610 px, density 2): while the thumb is dragged a big bubble with the label is above the
 * finger, it follows the drag, it keeps its size for a year or a "#", it never takes a touch, and it is gone after release. The bar's touch rules are B41's and are tested there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B575ScrollBubbleTest {
    @get:Rule val compose = createComposeRule()

    private val px = 2f
    private var label: String? = "A"
    private var lookups = 0

    private fun show(rows: Int = 400, dark: Boolean = true) {
        compose.setContent {
            val state = rememberLazyListState()
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        items(rows) { i -> Box(Modifier.fillMaxWidth().height(56.dp)) { Text("Row $i") } }
                    }
                    ExpressiveScrollBar(
                        contentEndMargin = 12.dp,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 16.dp, bottom = 140.dp),
                        listState = state,
                        dragLabelProvider = { lookups++; label },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private val width get() = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
    private val height get() = compose.onRoot().fetchSemanticsNode().size.height.toFloat()

    /** Presses the thumb (at the top of the track, on the end edge) and drags to [toY] px. Returns the finger's position. */
    private fun dragTo(toY: Float): Offset {
        val x = width - 12f
        compose.onRoot().performTouchInput {
            down(Offset(x, 100f))
            moveTo(Offset(x, 100f + 40f))
            moveTo(Offset(x, toY))
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        return Offset(x, toY)
    }

    private fun release() {
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun bubble(): Rect = compose.onNodeWithTag(ScrollBubble.TAG).fetchSemanticsNode().boundsInRoot

    // ── drawn while dragging, gone after ─────────────────────────────────────────────────────────────

    @Test
    fun `the bubble is drawn while the thumb is dragged and gone after release`() {
        show()
        compose.onNodeWithTag(ScrollBubble.TAG).assertDoesNotExist()
        dragTo(height * 0.5f)
        compose.onNodeWithTag(ScrollBubble.TAG).assertIsDisplayed()
        compose.onNodeWithText("A").assertIsDisplayed()
        release()
        compose.onNodeWithTag(ScrollBubble.TAG).assertDoesNotExist()
    }

    @Test
    fun `a press that does not drag shows no bubble, and a list that cannot scroll has none`() {
        show(rows = 3)
        compose.onRoot().performTouchInput { down(Offset(width - 12f, 100f)); moveTo(Offset(width - 12f, 600f)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag(ScrollBubble.TAG).assertDoesNotExist()
        compose.onRoot().performTouchInput { up() }
    }

    @Test
    fun `a position with no label (a sort without letters) shows no bubble`() {
        label = null
        show()
        dragTo(height * 0.5f)
        compose.onNodeWithTag(ScrollBubble.TAG).assertDoesNotExist()
        release()
        label = "A"
    }

    // ── above the finger ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `in the middle of the list the bubble is above the finger, the gap clear of it`() {
        show()
        val finger = dragTo(height * 0.5f)
        val b = bubble()
        assertThat(b.bottom).isLessThan(finger.y)
        // the thumb's top is at or above the finger; the bubble's bottom edge is at least the gap above it
        assertThat(finger.y - b.bottom).isAtLeast(ScrollBubble.Gap.value * px)
        assertThat(b.contains(finger)).isFalse()
        // inside the screen
        assertThat(b.left).isAtLeast(0f)
        assertThat(b.right).isAtMost(width)
        assertThat(b.top).isAtLeast(0f)
    }

    @Test
    fun `it follows the drag`() {
        show()
        val first = dragTo(height * 0.3f)
        val a = bubble()
        compose.onRoot().performTouchInput { moveTo(Offset(first.x, height * 0.55f)) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val b = bubble()
        assertThat(b.top - a.top).isWithin(6f).of(height * 0.25f)
        release()
    }

    @Test
    fun `at the bottom of the list the bubble is above the thumb and inside the screen`() {
        show()
        val finger = dragTo(height - 150f * px)
        val b = bubble()
        assertThat(b.contains(finger)).isFalse()
        assertThat(b.bottom).isLessThan(finger.y)
        assertThat(b.bottom).isAtMost(height)
        assertThat(b.left).isAtLeast(0f)
        assertThat(b.right).isAtMost(width)
        release()
    }

    @Test
    fun `at the top of the list, with no room above, it is left of the finger's column, never under the finger`() {
        show()
        val finger = dragTo(100f + 60f)
        val b = bubble()
        assertThat(b.contains(finger)).isFalse()
        // the touch column is the 48 dp at the end edge, less the 4 dp inset
        val columnLeft = width - (4f + 48f) * px
        assertThat(b.right).isAtMost(columnLeft)
        assertThat(b.top).isAtLeast(0f)
        assertThat(b.left).isAtLeast(0f)
        release()
    }

    @Test
    fun `from the top to the bottom the bubble never covers the finger and never leaves the screen`() {
        show()
        val x = width - 12f
        compose.onRoot().performTouchInput { down(Offset(x, 100f)); moveTo(Offset(x, 140f)) }
        var y = 140f
        while (y < height - 300f) {
            compose.onRoot().performTouchInput { moveTo(Offset(x, y)) }
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
            val b = bubble()
            val finger = Offset(x, y)
            assertWithMessage("finger at $y: bubble $b").that(b.contains(finger)).isFalse()
            assertWithMessage("finger at $y: bubble $b").that(b.left >= 0f && b.right <= width && b.top >= 0f && b.bottom <= height).isTrue()
            y += 90f
        }
        release()
    }

    // ── its size ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the bubble is 56 dp, and a letter a hash and a year keep that size`() {
        val side = ScrollBubble.Size.value * px
        show()
        for (text in listOf("A", "#", "2024", "W")) {
            label = text
            dragTo(height * 0.5f)
            val b = bubble()
            assertWithMessage("label $text").that(b.width).isWithin(2f).of(side)
            assertWithMessage("label $text").that(b.height).isWithin(2f).of(side)
            release()
        }
    }

    @Test
    fun `a year is shown in full inside the bubble`() {
        label = "2024"
        show()
        dragTo(height * 0.5f)
        val b = bubble()
        val text = compose.onNodeWithText("2024")
        val tb = text.fetchSemanticsNode().boundsInRoot
        assertThat(tb.left).isAtLeast(b.left)
        assertThat(tb.right).isAtMost(b.right)
        assertThat(tb.top).isAtLeast(b.top)
        assertThat(tb.bottom).isAtMost(b.bottom)
        val results = mutableListOf<TextLayoutResult>()
        text.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertThat(results).isNotEmpty()
        val r = results.first()
        // not cut: one line, no ellipsis, and narrower than the bubble's inside (its own padding left out)
        assertThat(r.isLineEllipsized(0)).isFalse()
        assertThat(r.layoutInput.text.text).isEqualTo("2024")
        assertThat(r.size.width.toFloat()).isAtMost(b.width - 2f * 8f * px)
        assertThat(results.first().lineCount).isEqualTo(1)
        release()
    }

    // ── the bubble takes no touch ────────────────────────────────────────────────────────────────────

    @Test
    fun `the bubble has no click or long click, and the bar's own input is the only one`() {
        show()
        dragTo(height * 0.5f)
        val config = compose.onNodeWithTag(ScrollBubble.TAG).fetchSemanticsNode().config
        assertThat(config.contains(SemanticsActions.OnClick)).isFalse()
        assertThat(config.contains(SemanticsActions.OnLongClick)).isFalse()
        release()
        val bar = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/ExpressiveScrollBar.kt").readText()
        assertThat(Regex("\\.sharedPointerInput \\{").findAll(bar).count()).isEqualTo(1)
        assertThat(bar).doesNotContain(".pointerInput(")
        assertThat(bar).doesNotContain("clickable(")
        assertThat(bar).doesNotContain("Surface(")
    }

    // ── the colours: a tonal disc, the accent on it ─────────────────────────────────────────────────

    @Test
    fun `the bubble is the floating discs' tonal container with the glyph in the accent`() {
        var container = 0
        var accent = 0
        compose.setContent {
            val state = rememberLazyListState()
            DapTheme(darkTheme = true, baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                container = Dap.surfaceRaised.toArgb()
                accent = com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent.current.toArgb()
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) { items(400) { Box(Modifier.fillMaxWidth().height(56.dp)) { Text("Row $it") } } }
                    ExpressiveScrollBar(
                        contentEndMargin = 12.dp,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 16.dp, bottom = 140.dp),
                        listState = state,
                        dragLabelProvider = { "W" },
                    )
                }
            }
        }
        compose.waitForIdle()
        dragTo(height * 0.5f)
        val b = bubble()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        // the disc's body, away from the glyph (the glyph is centred): a point a quarter of the way in from the top edge
        val body = bitmap.getPixel(b.center.x.toInt(), (b.top + b.height * 0.12f).toInt())
        assertThat(body).isEqualTo(container)
        // and some of the glyph's pixels are the accent
        var accentPixels = 0
        for (y in b.top.toInt()..b.bottom.toInt()) for (x in b.left.toInt()..b.right.toInt()) {
            val p = bitmap.getPixel(x, y)
            if (kotlin.math.abs(((p shr 16) and 0xFF) - ((accent shr 16) and 0xFF)) < 12 && kotlin.math.abs(((p shr 8) and 0xFF) - ((accent shr 8) and 0xFF)) < 12 && kotlin.math.abs((p and 0xFF) - (accent and 0xFF)) < 12) accentPixels++
        }
        assertThat(accentPixels).isAtLeast(150)
        release()
    }

    // ── no work per frame beyond today's ─────────────────────────────────────────────────────────────

    @Test
    fun `the label is read only while dragging, once per move at most a handful of times`() {
        show()
        lookups = 0
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(lookups).isEqualTo(0)
        val x = width - 12f
        compose.onRoot().performTouchInput { down(Offset(x, 100f)); moveTo(Offset(x, 140f)) }
        lookups = 0
        repeat(10) { step -> compose.onRoot().performTouchInput { moveTo(Offset(x, 140f + step * 60f)) } }
        compose.waitForIdle()
        assertThat(lookups).isLessThan(120)
        release()
        lookups = 0
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(lookups).isEqualTo(0)
    }
}
