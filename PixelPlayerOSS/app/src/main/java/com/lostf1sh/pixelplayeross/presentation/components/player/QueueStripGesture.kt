package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

@Composable
internal fun Modifier.queueStripDrag(
    enabled: Boolean,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onRelease: (Float, Float) -> Unit,
): Modifier {
    val active = rememberUpdatedState(enabled)
    val startAction = rememberUpdatedState(onStart)
    val dragAction = rememberUpdatedState(onDrag)
    val releaseAction = rememberUpdatedState(onRelease)
    return pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!active.value) return@awaitEachGesture
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var dragging = false
        var total = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            velocity.addPosition(change.uptimeMillis, change.position)
            if (!change.pressed) {
                if (dragging) { change.consume(); releaseAction.value(total, velocity.calculateVelocity().y) }
                break
            }
            val fromDown = change.position - down.position
            if (!dragging && fromDown.y < -viewConfiguration.touchSlop && abs(fromDown.y) > abs(fromDown.x)) {
                dragging = true; startAction.value(); total = fromDown.y; change.consume(); dragAction.value(total)
            } else if (dragging) {
                val dy = change.positionChange().y; total += dy; change.consume(); dragAction.value(dy)
            }
        }
    }
}
}
