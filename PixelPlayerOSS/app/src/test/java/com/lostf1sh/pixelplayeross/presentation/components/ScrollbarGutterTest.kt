package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B41-fix1 (B9): lists waste no width on a gutter for the scrollbar. The owner: "in Library there's quite a big space, it's literally for the
 * scroll, but it could be used for more room". Measured on the HONOR (`b41-lib-01.png`): the cards of "Carpetas en la biblioteca" started 18 dp
 * from the left edge and ended **42 dp** from the right, for a thumb 3 dp wide.
 *
 * The pattern was `end = if (state.canScrollForward || state.canScrollBackward) 22..26.dp else 12..16.dp` in a dozen places. Here is the rule
 * (one place), and a scan of every file that had the pattern: none of them gives its content a different end margin when it can scroll.
 */
class ScrollbarGutterTest {

    @Test
    fun `the content's end margin is its start margin, whether or not the list scrolls`() {
        for (margin in listOf(0.dp, 8.dp, 12.dp, 14.dp, 16.dp, 20.dp)) {
            assertEquals(margin, ScrollbarGutter.endMargin(margin))
            val padding = symmetricContentPadding(horizontal = margin, top = 3.dp, bottom = 5.dp)
            assertEquals(padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr), padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))
            assertEquals(margin, padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))
            assertEquals(3.dp, padding.calculateTopPadding())
            assertEquals(5.dp, padding.calculateBottomPadding())
        }
    }

    @Test
    fun `the touch target is 48 dp, the strip at the edge is narrower, and the idle thumb reaches 10 dp from the edge`() {
        assertTrue(ScrollbarGutter.TouchWidth >= 48.dp)
        assertTrue(ScrollbarGutter.JumpStripWidth < ScrollbarGutter.TouchWidth)
        assertEquals(10.dp, ScrollbarGutter.idleReach())
        assertEquals(20.dp, ScrollbarGutter.idleReach(inset = 14.dp))
    }

    @Test
    fun `a thumb covers the content only where the content ends nearer the edge than the thumb reaches`() {
        assertFalse(ScrollbarGutter.thumbCoversContent(12.dp))
        assertFalse(ScrollbarGutter.thumbCoversContent(14.dp))
        assertFalse(ScrollbarGutter.thumbCoversContent(10.dp))
        assertTrue(ScrollbarGutter.thumbCoversContent(8.dp))
        assertTrue(ScrollbarGutter.thumbCoversContent(0.dp))
        // a bar set 14 dp in reaches 20 dp: only content that ends at 20 dp or more is clear of it
        assertTrue(ScrollbarGutter.thumbCoversContent(8.dp, inset = 14.dp))
        assertFalse(ScrollbarGutter.thumbCoversContent(20.dp, inset = 14.dp))
    }

    private val files = B41Fix1ScrollbarGutterSourceTest.files

    private fun source(path: String) = B41Fix1ScrollbarGutterSourceTest.source(path)

    @Test
    fun `every scrollbar says where the content ends, and the lists that had the pattern use the one rule`() {
        for (path in files) {
            val code = source(path)
            val bars = code.split("ExpressiveScrollBar(").drop(1)
            for (call in bars) {
                if (call.trimStart().startsWith(")")) continue
                // the parameter is required (no default), so a call without it would not compile; this keeps it that way
                assertTrue(call.take(600).contains("contentEndMargin ="), "$path: a scrollbar that does not say where the content ends")
            }
        }
        // the rule is applied (not a copy of its result) wherever a list had the pattern
        val rule = Regex("""symmetricSidePadding\(|ScrollbarGutter\.endMargin\(|symmetricContentPadding\(""")
        for (path in files.filter { it != "presentation/components/PlaylistContainer.kt" }) {
            assertTrue(rule.containsMatchIn(source(path)), "$path does not use the shared rule")
        }
    }

    @Test
    fun `the bar takes no room of its own and its box is the touch target`() {
        val bar = source("presentation/components/ExpressiveScrollBar.kt")
        assertTrue(bar.contains(".width(maxOf(ScrollbarGutter.TouchWidth, expandedIndicatorWidth + paddingEnd))"))
        assertTrue(bar.contains("testTag(ScrollbarGutter.TOUCH_TAG)"))
        // the press is the bar's only where it grabs something: nothing is consumed before that is known
        val gesture = bar.substringAfter("override suspend fun AwaitPointerEventScope.run(down: PointerInputChange) {").substringBefore("isPressed = true")
                assertTrue(gesture.contains("return\n"))
        assertFalse(gesture.contains("consume()"))
        // and it is heard on the bar's outermost node, shared with the list under it (nested inside, a shared input does not share)
        val outer = bar.substringAfter("BoxWithConstraints(\n        modifier = modifier").substringBefore(") {\n        val density")
        assertTrue(outer.contains("awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)"))
        assertTrue(outer.contains(".sharedPointerInput {"))
        assertTrue(outer.contains("testTag(ScrollbarGutter.TOUCH_TAG)"))
    }
}
