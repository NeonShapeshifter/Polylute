package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import kotlin.math.max

/**
 * B57.5 3 (owner's idea, #66): while the scroll bar's thumb is dragged, the letter (or the year, whatever `dragLabelProvider` says) is shown in a **big bubble above the finger**, where
 * the eye can see it, and not in a small disc at the touch point where the finger covers it. It follows the drag and fades out on release. One shared component, so every list that
 * passes a provider gets it, with no code of its own.
 *
 * The bubble is a tonal disc: the same container as the Library's floating discs (`Dap.surfaceRaised`) with the glyph in the accent. It never takes a touch.
 */
internal object ScrollBubble {
    /** 56 dp (the owner found 96 dp, three times the old 32 dp disc, far too big on the HONOR, 11-04). It does not change with the label: a year or a "#" keeps this size and shows in full. */
    val Size: Dp = 56.dp

    /** How far clear of the thumb's top the bubble's bottom edge stays, so the finger's pad (it covers about 18 dp around where it touches) never reaches it. */
    val Gap: Dp = 24.dp

    /** How far the bubble's end edge stays from the bar's end edge when it is above the thumb. */
    val EdgeInset: Dp = 12.dp

    /** How far the thumb has to move for the bubble to slide from "above, at the end edge" to "left of the touch column" (near the top of the list, where there is no room above). */
    val SlideTravel: Dp = 32.dp

    /** The test tag of the bubble. */
    const val TAG = "expressiveScrollBarBubble"

    /**
     * Where the bubble goes, in the bar's own box (origin at its top start corner, [barWidth] wide: the touch column, the thumb at its end edge; [barHeight] tall: the list area). All in px.
     *
     * - **Above the finger**, when there is room: the bubble's bottom edge is [gap] above the thumb's top, and its end edge is [inset] from the bar's end edge.
     * - **Near the top of the list**, where there is no room above inside the list area (a pager page clips what is drawn above it), the bubble stays at the top and slides to the left of the
     *   touch column, [gap] clear of it: it never covers the finger. The slide is spread over [slideTravel] of the thumb's travel, so the bubble moves with the finger and does not jump.
     * - It is always inside the bar's box vertically, so inside the list area and the screen.
     *
     * [thumbTop] is the thumb's top edge; the finger is on the thumb, in its [thumbHeight].
     */
    fun place(barWidth: Float, barHeight: Float, thumbTop: Float, thumbHeight: Float, side: Float, gap: Float, inset: Float, slideTravel: Float): Placement {
        val aboveY = thumbTop - gap - side
        val y = aboveY.coerceIn(0f, max(0f, barHeight - side))
        val atEnd = barWidth - inset - side
        val beside = -gap - side
        // 0 while the thumb is far enough down for the bubble to be above it; 1 once the bubble is held at the top and must be left of the column
        val slide = if (slideTravel <= 0f) (if (aboveY >= 0f) 0f else 1f) else ((side + gap + slideTravel - thumbTop) / slideTravel).coerceIn(0f, 1f)
        val x = atEnd + (beside - atEnd) * slide
        return Placement(x = x, y = y, above = aboveY >= 0f)
    }

    /** [x], [y]: the bubble's top start corner; [above]: its bottom edge is above the thumb (it is not held at the top). */
    data class Placement(val x: Float, val y: Float, val above: Boolean)

    /**
     * The type for [label], so that the bubble keeps its size and the label shows in full: one or two glyphs ("A", "#") are the display size, a year (three or four characters) is the title
     * size, anything longer is the value size.
     */
    fun textStyleFor(label: String): TextStyle = when {
        label.length <= 2 -> DapType.Display.copy(fontSize = 32.sp, lineHeight = 36.sp)
        label.length <= 4 -> DapType.MediaRow
        else -> DapType.ValueSmall
    }
}
