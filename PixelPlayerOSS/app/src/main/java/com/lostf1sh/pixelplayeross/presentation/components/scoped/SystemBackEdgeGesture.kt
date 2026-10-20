package com.lostf1sh.pixelplayeross.presentation.components.scoped

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * The strip Android reserves for its own back gesture, in pixels.
 *
 * The width comes from [WindowInsets.systemGestures], the platform's own answer, not a constant.
 * It is floored at 20 dp because some builds report nothing before the first layout, and capped at
 * 48 dp so a device reporting a wide inset cannot eat into the intentional centre gestures.
 *
 * [owns] is the single definition of "this gesture belongs to Back" and is shared by every
 * surface that needs to stay out of the way.
 */
@Immutable
data class SystemBackEdgeInset(val leftPx: Float, val rightPx: Float) {
    fun owns(x: Float, widthPx: Float): Boolean = x <= leftPx || x >= widthPx - rightPx
}

@Composable
fun systemBackEdgeInset(): SystemBackEdgeInset {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val insets = WindowInsets.systemGestures

    val leftPx = with(density) {
        insets.getLeft(this, layoutDirection).toDp().coerceIn(20.dp, 48.dp).toPx()
    }
    val rightPx = with(density) {
        insets.getRight(this, layoutDirection).toDp().coerceIn(20.dp, 48.dp).toPx()
    }
    return remember(leftPx, rightPx) { SystemBackEdgeInset(leftPx, rightPx) }
}

/**
 * Keeps the platform's back-gesture edges out of a surface that reads *all* edge-origin gestures
 * itself, such as a horizontal pager.
 *
 * A gesture whose **down** lands inside the reserved strip is claimed here, in the Initial pass,
 * so it never reaches the gesture detector below. Android still sees the same events and performs
 * Back as normal. A gesture starting anywhere else is left completely alone.
 *
 * This claims the strip unconditionally, including vertical drags, so it is only appropriate where
 * the surface has no competing vertical gesture. Use
 * [respectSystemBackEdgesForHorizontalDrag] where one does.
 */
@Composable
fun Modifier.respectSystemBackEdges(): Modifier {
    val edge = systemBackEdgeInset()

    return this.pointerInput(edge) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!edge.owns(down.position.x, size.width.toFloat())) return@awaitEachGesture

            // Own the rest of this gesture so the surface cannot read it as its own action. The
            // events are only consumed inside Compose; the platform's own gesture detection has
            // already seen them and still delivers Back.
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { change ->
                    change.consume()
                }
                pressed = event.changes.any { it.pressed }
            }
        }
    }
}

/**
 * Keeps the platform's back-gesture edges out of a surface that reads **horizontal** drags but
 * must still let vertical drags through to an ancestor scroller — the lyrics pager, or the
 * waveform inside a scrollable details page.
 *
 * The claim is decided by direction, not by refusing the strip outright: a gesture that begins on
 * the back edge is claimed only once it turns out to be a horizontal drag, and is released to the
 * scroller untouched when it turns out to be vertical. A tap is never claimed.
 *
 * The direction test uses the platform's own [LocalViewConfiguration.touchSlop]. Because the
 * Initial pass runs before the Main pass for the same event, claiming on the very event that
 * crosses slop means the inner horizontal drag detector sees an already-consumed change and
 * cancels, so no previous/next or seek is ever dispatched for an edge-origin drag.
 */
@Composable
fun Modifier.respectSystemBackEdgesForHorizontalDrag(): Modifier {
    val edge = systemBackEdgeInset()
    val touchSlop = LocalViewConfiguration.current.touchSlop

    return this.pointerInput(edge, touchSlop) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!edge.owns(down.position.x, size.width.toFloat())) return@awaitEachGesture

            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // Released without ever becoming a horizontal drag: a tap. Leave it alone.
                if (!change.pressed) break

                if (!claimed) {
                    val dx = abs(change.position.x - down.position.x)
                    val dy = abs(change.position.y - down.position.y)
                    when {
                        dx >= touchSlop && dx > dy -> claimed = true
                        dy >= touchSlop && dy > dx -> break // vertical: hand it back to the scroller
                    }
                }
                if (claimed) event.changes.forEach { it.consume() }
            }
        }
    }
}
