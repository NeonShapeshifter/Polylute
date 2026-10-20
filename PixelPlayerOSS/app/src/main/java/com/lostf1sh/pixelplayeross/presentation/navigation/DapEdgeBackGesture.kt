package com.lostf1sh.pixelplayeross.presentation.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.runtime.rememberUpdatedState

/**
 * The app's own edge Back gesture, from either side of the screen (B25-S6).
 *
 * This is not a whole-screen horizontal swipe. It owns a narrow strip at each side edge and nothing
 * else: a touch that begins anywhere else is never examined, let alone consumed, so a seek bar,
 * an EQ slider, a queue row being swiped away, a horizontal carousel, a crop surface and the
 * mini-player's dismiss drag all keep their gestures exactly as they were. Even inside the strip a
 * gesture is only claimed once it has shown itself to be a deliberate rightward move; a vertical
 * scroll that happens to start at the edge is released untouched, which is the common case on a
 * list whose first column of pixels is the left edge of its rows.
 *
 * The strip is fed to [EdgeBackGesturePolicy], which is where the actual decision lives and which is
 * unit-tested directly. This file is only the plumbing between that policy and the pointer stream.
 */
@Composable
fun Modifier.dapEdgeBackGesture(
    enabled: Boolean,
    onBack: () -> Unit,
    edgeWidth: Dp = EdgeBackGesturePolicy.EdgeWidth,
    /**
     * How far through the gesture the finger is, 0f to 1f, for a caller that wants to show it.
     *
     * Reported as 0f the moment the gesture is not being made — before it is claimed, after it is
     * released, and after it triggers — so an indicator driven by this is shown only while the
     * gesture is genuinely in progress. The gesture worked without any feedback at all before this,
     * which meant a deliberate edge swipe and a missed one looked identical until the screen changed.
     */
    onProgress: (Float) -> Unit = {},
    /** Which side the gesture in progress started on, told when it starts, so the indicator is drawn on that side. */
    onEdge: (BackEdge) -> Unit = {},
): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val edgeWidthPx = with(density) { edgeWidth.toPx() }
    val activationPx = with(density) { EdgeBackGesturePolicy.ActivationDistance.toPx() }
    // The lambda is read through a state holder so a recomposition that hands over a new lambda does
    // not restart the pointer loop mid-gesture.
    val progressSink = rememberUpdatedState(onProgress)
    val edgeSink = rememberUpdatedState(onEdge)
    return this.pointerInput(edgeWidthPx, activationPx) {
        awaitEachGesture {
            // A fresh tracker per gesture. The policy accumulates travel, so one that outlived its
            // gesture would start the next one already part-way to the threshold.
            val policy = EdgeBackGesturePolicy(
                edgeWidthPx = edgeWidthPx,
                activationDistancePx = activationPx,
                slopPx = viewConfiguration.touchSlop,
            )
            // Initial pass: the shell is the outermost node, so this is the earliest any modifier in
            // the activity can see the gesture. Nothing is consumed here until the policy claims it.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // B25-S6: either side. The policy only knows "inward from the edge", so a right-edge gesture is fed
            // mirrored (leftward is positive for it) and is otherwise the same gesture, thresholds and refusals included.
            val edge = EdgeBackGesturePolicy.edgeAt(down.position.x, size.width.toFloat(), edgeWidthPx)
                ?: return@awaitEachGesture
            edgeSink.value(edge)

            var claimed = false
            var triggered = false
            var travelledX = 0f

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break

                    val delta = change.position - change.previousPosition
                    travelledX += edge.inward(delta.x)
                    when (policy.onMove(edge.inward(delta.x), delta.y, change.previousUptimeMillis, change.uptimeMillis)) {
                        EdgeBackDecision.IGNORED -> break
                        EdgeBackDecision.PENDING -> Unit
                        EdgeBackDecision.CLAIMED -> claimed = true
                        EdgeBackDecision.TRIGGERED -> {
                            claimed = true
                            triggered = true
                        }
                    }
                    // Once the gesture is ours, keep it: a child that started tracking the same drag
                    // must not also act on it. Before that point nothing is consumed.
                    if (claimed) change.consume()
                    // Nothing is reported until the gesture is claimed: before that it may still turn
                    // out to be a scroll, and an indicator that appeared for every edge touch would
                    // be noise rather than confirmation.
                    progressSink.value(
                        if (claimed) (travelledX / activationPx).coerceIn(0f, 1f) else 0f
                    )
                }
            } finally {
                // Covers every exit: released early, rejected as a scroll, or triggered. A progress
                // left standing after the finger lifts would strand the indicator on screen.
                progressSink.value(0f)
            }

            if (triggered) onBack()
        }
    }
}

/** Which side edge a Back gesture started on (B25-S6). */
enum class BackEdge {
    LEFT,
    RIGHT;

    /**
     * A horizontal movement as [EdgeBackGesturePolicy] reads it: toward the inside of the screen is positive on both
     * edges, so one rule (and one set of thresholds) serves both.
     */
    fun inward(deltaX: Float): Float = if (this == LEFT) deltaX else -deltaX
}

/** What the tracker currently makes of the gesture in progress. */
enum class EdgeBackDecision {
    /** Not Back: stop tracking, and leave the rest of the gesture to whoever else wants it. */
    IGNORED,

    /** Still ambiguous. Keep watching, consume nothing. */
    PENDING,

    /** This is a rightward drag from the edge, but it has not yet travelled far enough. */
    CLAIMED,

    /** The threshold has been met. Perform Back. */
    TRIGGERED,
}

/**
 * The edge-Back rule, as a value object.
 *
 * Separated from the pointer plumbing so the interesting part — when a touch at the edge becomes a
 * Back — is a pure function of the samples fed to it, and can be tested without a device.
 */
class EdgeBackGesturePolicy(
    private val edgeWidthPx: Float,
    private val activationDistancePx: Float,
    private val slopPx: Float,
    private val velocityWindowPx: Float = slopPx * 2f,
    private val velocityPerSecondPx: Float = VELOCITY_PX_PER_SECOND,
) {
    private var travelledX = 0f
    private var travelledY = 0f
    private var claimed = false
    private var lastUptimeMillis = 0L

    /** Whether a touch that landed at [x] is inside the activation strip at all. */
    fun startsAtEdge(x: Float): Boolean = x <= edgeWidthPx

    /**
     * Feeds one movement sample.
     *
     * A gesture is rejected the moment it is clearly vertical, and claimed the moment it is clearly
     * a rightward horizontal move that began at the edge. Claim and reject are mutually exclusive and
     * both are final: a claimed gesture cannot later be rejected, which is what stops a diagonal
     * scroll from being half-Back.
     */
    fun onMove(deltaX: Float, deltaY: Float, previousUptimeMillis: Long, uptimeMillis: Long): EdgeBackDecision {
        if (uptimeMillis > lastUptimeMillis) lastUptimeMillis = uptimeMillis
        travelledX += deltaX
        travelledY += deltaY

        if (!claimed) {
            // Clearly a scroll, not Back. Release it entirely.
            if (abs(travelledY) > slopPx && abs(travelledY) > abs(travelledX)) {
                return EdgeBackDecision.IGNORED
            }
            // Clearly a rightward drag from the edge. From here the gesture is ours.
            if (travelledX > slopPx && travelledX > abs(travelledY)) {
                claimed = true
            } else {
                return EdgeBackDecision.PENDING
            }
        }

        if (travelledX >= activationDistancePx) return EdgeBackDecision.TRIGGERED

        val elapsedMs = uptimeMillis - previousUptimeMillis
        val flicked = travelledX >= velocityWindowPx &&
            elapsedMs > 0L &&
            (deltaX / (elapsedMs / 1000f)) >= velocityPerSecondPx
        return if (flicked) EdgeBackDecision.TRIGGERED else EdgeBackDecision.CLAIMED
    }

    companion object {
        /**
         * Which edge strip a touch at [x] lands in, in a container [containerWidthPx] wide; null for anywhere else. Where the
         * two strips would meet (a container narrower than two strips) the left one wins.
         */
        fun edgeAt(x: Float, containerWidthPx: Float, edgeWidthPx: Float): BackEdge? = when {
            x <= edgeWidthPx -> BackEdge.LEFT
            x >= containerWidthPx - edgeWidthPx -> BackEdge.RIGHT
            else -> null
        }

        /** The strip a Back gesture may start in. Narrow on purpose: 22 dp is a thumb's edge. */
        val EdgeWidth = 22.dp

        /** How far right the gesture must travel before it means Back. */
        val ActivationDistance = 40.dp

        /** A short, fast flick counts as much as a long, slow drag. */
        const val VELOCITY_PX_PER_SECOND = 1200f
    }
}
