package com.lostf1sh.pixelplayeross.presentation.components

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * B57.5 3: where the big bubble goes, as a pure function of the thumb. Everything in dp (1 px = 1 dp here). The bar's box is the touch column (48 dp) by the list area; the finger is on the thumb,
 * in the touch column, and covers about 18 dp around where it touches.
 */
class B575ScrollBubblePlacementTest {
    private val w = 48f
    private val h = 600f
    private val thumbH = 40f
    private val side = 56f
    private val gap = 24f
    private val inset = 12f
    private val travel = 32f
    private val pad = 18f

    private fun place(thumbTop: Float, height: Float = h) = ScrollBubble.place(w, height, thumbTop, thumbH, side, gap, inset, travel)

    private data class R(val l: Float, val t: Float, val r: Float, val b: Float) {
        fun overlaps(o: R) = l < o.r && o.l < r && t < o.b && o.t < b
    }

    private fun bubble(p: ScrollBubble.Placement) = R(p.x, p.y, p.x + side, p.y + side)

    /** The finger: the touch column, along the thumb and the pad of the finger above and below it. */
    private fun finger(thumbTop: Float) = R(0f, thumbTop - pad, w, thumbTop + thumbH + pad)

    @Test
    fun `at every position of the thumb the bubble is clear of the finger`() {
        var top = 0f
        while (top <= h - thumbH) {
            val p = place(top)
            assertWithMessage("thumb at $top: bubble ${bubble(p)} over finger ${finger(top)}").that(bubble(p).overlaps(finger(top))).isFalse()
            top += 0.5f
        }
    }

    @Test
    fun `at every position of the thumb the bubble is inside the list area`() {
        var top = 0f
        while (top <= h - thumbH) {
            val p = place(top)
            assertThat(p.y).isAtLeast(0f)
            assertThat(p.y + side).isAtMost(h)
            top += 0.5f
        }
    }

    @Test
    fun `with room above the bubble is above the thumb, its bottom edge the gap clear of it`() {
        for (top in listOf(side + gap, side + gap + 1f, 200f, 300f, h - thumbH)) {
            val p = place(top)
            assertThat(p.above).isTrue()
            assertThat(p.y + side).isWithin(0.001f).of(top - gap)
        }
        // at the bottom of the list too: the bubble is above the thumb, inside the area
        val bottom = place(h - thumbH)
        assertThat(bottom.y).isWithin(0.001f).of(h - thumbH - gap - side)
    }

    @Test
    fun `far from the top the bubble is at the end edge, over the touch column's end, clear of the screen's edge by the inset`() {
        val p = place(300f)
        assertThat(p.x + side).isWithin(0.001f).of(w - inset)
    }

    @Test
    fun `near the top, where there is no room above, it is held at the top and left of the touch column`() {
        for (top in listOf(0f, 10f, 40f, side, side + gap - 1f)) {
            val p = place(top)
            assertThat(p.above).isFalse()
            assertThat(p.y).isWithin(0.001f).of(0f)
            assertThat(p.x + side).isAtMost(-gap + 0.001f)
        }
    }

    @Test
    fun `it moves with the finger and never jumps, from the top to the bottom and back`() {
        var previous = place(0f)
        var top = 0.5f
        val maxStepX = (kotlin.math.abs((w - inset - side) - (-gap - side)) / travel) * 0.5f + 0.01f
        while (top <= h - thumbH) {
            val p = place(top)
            assertWithMessage("x at $top").that(kotlin.math.abs(p.x - previous.x)).isAtMost(maxStepX)
            assertWithMessage("y at $top").that(kotlin.math.abs(p.y - previous.y)).isAtMost(0.51f)
            previous = p
            top += 0.5f
        }
    }

    @Test
    fun `a list area too short for the bubble keeps it at the top, and it is still never over the finger`() {
        for (height in listOf(200f, 150f, 96f, 60f)) {
            var top = 0f
            while (top <= height - thumbH) {
                val p = place(top, height)
                assertThat(p.y).isAtLeast(0f)
                if (height >= side) assertThat(p.y + side).isAtMost(height)
                assertWithMessage("area $height, thumb at $top: bubble ${bubble(p)} over finger ${finger(top)}").that(bubble(p).overlaps(finger(top))).isFalse()
                top += 1f
            }
        }
    }

    @Test
    fun `the size is the owner's 56 dp and does not change with the label`() {
        // 96 dp (three times the old disc) was far too big on the HONOR (owner, 11-04)
        assertThat(ScrollBubble.Size.value).isEqualTo(56f)
    }

    @Test
    fun `a long label is given a smaller type so that it fits the same bubble`() {
        val one = ScrollBubble.textStyleFor("A").fontSize.value
        val hash = ScrollBubble.textStyleFor("#").fontSize.value
        val year = ScrollBubble.textStyleFor("2024").fontSize.value
        val long = ScrollBubble.textStyleFor("Various").fontSize.value
        assertThat(hash).isEqualTo(one)
        assertThat(year).isLessThan(one)
        assertThat(long).isLessThan(year)
        // four digits of the year's type fit the bubble's inside with room (a digit is at most ~0.7 em)
        assertThat(year * 0.7f * 4).isLessThan(ScrollBubble.Size.value - 16f)
        // and five characters of the small value type (mono, 0.6 em); no provider gives more than a year today
        assertThat(long * 0.6f * 5).isLessThan(ScrollBubble.Size.value - 16f)
    }
}
