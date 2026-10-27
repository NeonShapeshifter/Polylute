package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * B41-fix1 (B9): the one rule for the space a scrollbar needs, in one place.
 *
 * A list used to give its content a **wider margin at the end than at the start whenever it could scroll** (`end = if (canScroll) 22.dp else
 * 12.dp`, in a dozen places), so that the bar had a column of its own beside the content. On the HONOR the cards of "Carpetas en la
 * biblioteca" started 18 dp from the left edge and ended **42 dp** from the right, for a 3 dp thumb. Now:
 *  - the content's margins are **symmetric**, whether or not the list scrolls ([endMargin], [symmetricContentPadding], [symmetricSidePadding]);
 *  - the bar is drawn **over** the end margin, not beside it (`ExpressiveScrollBar` is aligned to the end edge and takes no room of its own);
 *  - where the idle thumb would cover content (a margin narrower than [idleReach]), the thumb shows only while the list scrolls or is
 *    held and fades out after [IDLE_FADE_DELAY_MS], like Android's own scrollbars ([thumbCoversContent]);
 *  - the fast-scroll target is [TouchWidth] wide (48 dp), invisible, over the end margin and the content's edge: the thumb is grabbed anywhere in
 *    it, and the thin strip at the very edge ([JumpStripWidth]) still jumps to where it is pressed.
 */
object ScrollbarGutter {
    /** The fast-scroll target's width: a thumb 3 dp wide can still be grabbed. */
    val TouchWidth: Dp = 48.dp

    /** The strip at the very end edge where a press anywhere along the bar starts a drag (the track), as it always did. */
    val JumpStripWidth: Dp = 16.dp

    /** How far above and below the thumb a press still grabs it. */
    val GrabSlack: Dp = 8.dp

    /** What most pages leave between the bar and the end edge of the screen. */
    val DefaultInset: Dp = 4.dp

    /** The space the bar keeps between the end of its box and the thumb (ExpressiveScrollBar's `paddingEnd`). */
    val ThumbMargin: Dp = 3.dp

    /** The idle thumb's width. */
    val ThumbThickness: Dp = 3.dp

    /** How long a thumb that fades waits, after the list stops and the finger lifts, before it goes. */
    const val IDLE_FADE_DELAY_MS = 1200L

    /** The test tag of the touch target. */
    const val TOUCH_TAG = "expressiveScrollBarTouch"

    /** The content's end margin: its start margin, whether or not the list can scroll. */
    fun endMargin(startMargin: Dp): Dp = startMargin

    /** How far from the end edge the idle thumb reaches: the bar's own inset, its margin to the thumb, and the thumb. */
    fun idleReach(inset: Dp = DefaultInset): Dp = inset + ThumbMargin + ThumbThickness

    /** The idle thumb would be over the content: the content ends less than [idleReach] from the end edge. */
    fun thumbCoversContent(contentEndMargin: Dp, inset: Dp = DefaultInset): Boolean = contentEndMargin < idleReach(inset)
}

/** Content padding with the same margin at both sides: the end is [ScrollbarGutter.endMargin] of the start. */
fun symmetricContentPadding(horizontal: Dp, top: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues =
    PaddingValues(start = horizontal, top = top, end = ScrollbarGutter.endMargin(horizontal), bottom = bottom)

/** [Modifier.padding] with the same margin at both sides (see [ScrollbarGutter]). */
fun Modifier.symmetricSidePadding(horizontal: Dp, top: Dp = 0.dp, bottom: Dp = 0.dp): Modifier =
    padding(start = horizontal, top = top, end = ScrollbarGutter.endMargin(horizontal), bottom = bottom)
