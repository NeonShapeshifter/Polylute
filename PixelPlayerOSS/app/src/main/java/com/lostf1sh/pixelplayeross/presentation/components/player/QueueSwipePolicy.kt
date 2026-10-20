package com.lostf1sh.pixelplayeross.presentation.components.player

import kotlin.math.abs
import kotlin.math.hypot

/**
 * When an upward drag on the full player is the queue's (B29-L7, N-05).
 *
 * The player has pulled the queue up with an upward drag since before B21, from anywhere above the navigation strip and from a
 * 4 dp movement, which is under the touch slop (8 dp): it took the gesture **before** it could tell what it was, so a sideways
 * swipe on the transport row that drifted up, a diagonal one, and any swipe up on the cover (over its horizontal pager) all pulled
 * the queue. (The seek bar was never taken from: its own drag consumes its events first, and the tests pin that it still does.)
 * Two rules now:
 *
 *  - **Where it starts.** The lower part of the player: from the top of the transport controls down to the strip the system keeps
 *    for its own gestures. The cover and its pager, the title and the seek bar are above that line and never start it.
 *  - **What it is.** Decided once, when the finger has moved past the touch slop, and kept for the whole gesture: an upward
 *    movement that is at least [VERTICAL_DOMINANCE] times as long as it is wide is the queue's; anything else (a sideways swipe,
 *    a downward drag, a diagonal one) is somebody else's and is never consumed by the queue.
 *
 * Pure, so it can be pinned.
 */
internal object QueueSwipePolicy {

    /** An upward movement must be this many times longer than it is wide (about 63 degrees above the horizontal). */
    const val VERTICAL_DOMINANCE = 2f

    enum class Intent { QUEUE, OTHER }

    /**
     * Whether a touch at [downY] starts a queue swipe. [downY] and [controlsTop] are in the same coordinates (the window's);
     * [bottomBoundaryY] is where the system's own gesture strip begins. An unknown [controlsTop] (the controls were never laid
     * out) starts nothing.
     */
    fun startsInZone(downY: Float, controlsTop: Float, bottomBoundaryY: Float): Boolean =
        controlsTop.isFinite() && downY >= controlsTop && downY < bottomBoundaryY

    /**
     * The intent once the finger is [slopPx] away from where it touched down, from its total movement so far; null until then.
     * A batched event that carries the whole movement of a fast flick decides on its first look, like any other.
     */
    fun intentAfterSlop(totalDx: Float, totalDy: Float, slopPx: Float): Intent? {
        if (hypot(totalDx, totalDy) < slopPx) return null
        return if (totalDy < 0f && -totalDy >= VERTICAL_DOMINANCE * abs(totalDx)) Intent.QUEUE else Intent.OTHER
    }
}

/**
 * Where the transport controls are, as the gesture needs it (B29-L7): the window y of their top edge and of the screen's own
 * top, set by `onGloballyPositioned` and read by the gesture. Plain fields, not state: nothing recomposes for them.
 */
internal class QueueSwipeZone {
    var controlsTopInWindow: Float = Float.NaN
    var screenTopInWindow: Float = 0f
}
