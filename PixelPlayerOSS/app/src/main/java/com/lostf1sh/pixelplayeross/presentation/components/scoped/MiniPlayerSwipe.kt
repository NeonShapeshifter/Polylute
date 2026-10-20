package com.lostf1sh.pixelplayeross.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** The song a sideways swipe on the mini player asks for: a swipe to the left goes forward, to the right goes back. */
internal enum class MiniSwipe { NEXT, PREVIOUS }

/**
 * When a drag on the mini player is a song change, and when it goes through (B33-U2). Pure, so it can be pinned.
 *
 * **What it is.** Decided once, when the finger has moved past the touch slop, and kept for the whole gesture: a movement at least
 * [HORIZONTAL_DOMINANCE] times as wide as it is tall is the swipe's; anything else (a vertical drag, which expands the player, a diagonal
 * one) is somebody else's and is never consumed here.
 *
 * **When it goes through.** On release, if the finger travelled at least [COMMIT_DP], or flicked faster than [FLICK_VELOCITY_DP_PER_S]
 * over at least [FLICK_MIN_DP]; a short or slow movement springs back and changes nothing.
 */
internal object MiniSwipePolicy {

    const val HORIZONTAL_DOMINANCE = 2f
    const val COMMIT_DP = 72f
    const val FLICK_MIN_DP = 32f
    const val FLICK_VELOCITY_DP_PER_S = 700f

    /** How far the content follows the finger at most, and how much of the movement past [FOLLOW_FREE_DP] it follows. */
    const val FOLLOW_FREE_DP = 96f
    const val FOLLOW_MAX_DP = 160f
    const val FOLLOW_RESISTANCE = 0.35f

    enum class Intent { HORIZONTAL, OTHER }

    fun intentAfterSlop(totalDx: Float, totalDy: Float, slopPx: Float): Intent? {
        if (hypot(totalDx, totalDy) < slopPx) return null
        return if (abs(totalDx) >= HORIZONTAL_DOMINANCE * abs(totalDy)) Intent.HORIZONTAL else Intent.OTHER
    }

    /** What a release does: [totalDx] and [velocityX] are in px (the velocity per second), [density] the screen's px per dp. */
    fun outcome(totalDx: Float, velocityX: Float, density: Float): MiniSwipe? {
        val distanceDp = abs(totalDx) / density
        val velocityDp = abs(velocityX) / density
        val goes = distanceDp >= COMMIT_DP || (distanceDp >= FLICK_MIN_DP && velocityDp >= FLICK_VELOCITY_DP_PER_S)
        if (!goes) return null
        // the direction is the one the finger moved in; a flick that ended against the movement is not a swipe
        return if (totalDx < 0f) MiniSwipe.NEXT else MiniSwipe.PREVIOUS
    }

    /** Where the content sits for a finger that has moved [totalDx] px: with the finger up to [FOLLOW_FREE_DP], then at a resistance, to a cap. */
    fun followOffset(totalDx: Float, density: Float): Float {
        val distanceDp = abs(totalDx) / density
        val followedDp = if (distanceDp <= FOLLOW_FREE_DP) distanceDp
        else (FOLLOW_FREE_DP + (distanceDp - FOLLOW_FREE_DP) * FOLLOW_RESISTANCE)
        return followedDp.coerceAtMost(FOLLOW_MAX_DP) * density * totalDx.sign
    }
}

/**
 * The mini player's sideways swipe: the content follows the finger, and on release springs back; if the movement was a swipe, the song
 * changes with the action the buttons send ([onNext] for a swipe to the left, [onPrevious] to the right). It never touches the queue
 * (B33-U2: the swipe used to discard it).
 */
internal class MiniPlayerSwipeHandler(
    private val scope: CoroutineScope,
    private val density: Density,
    private val hapticFeedback: HapticFeedback,
    private val offsetAnimatable: Animatable<Float, AnimationVector1D>,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
) {
    private var totalDx = 0f
    private var offsetJob: Job? = null

    fun onDragStart() {
        totalDx = 0f
        offsetJob?.cancel()
        offsetJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetAnimatable.stop() }
    }

    fun onDrag(dragAmount: Float) {
        totalDx += dragAmount
        val target = MiniSwipePolicy.followOffset(totalDx, density.density)
        offsetJob?.cancel()
        offsetJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetAnimatable.snapTo(target) }
    }

    /** The finger went up: [velocityX] is its speed along the x axis in px per second. */
    fun onDragEnd(velocityX: Float) {
        val outcome = MiniSwipePolicy.outcome(totalDx, velocityX, density.density)
        totalDx = 0f
        springBack()
        when (outcome) {
            MiniSwipe.NEXT -> { hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); onNext() }
            MiniSwipe.PREVIOUS -> { hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPrevious() }
            null -> Unit
        }
    }

    /** The system or another handler took the gesture: nothing goes through. */
    fun onDragCancel() {
        totalDx = 0f
        springBack()
    }

    private fun springBack() {
        offsetJob?.cancel()
        offsetJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            offsetAnimatable.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium))
        }
    }
}

@Composable
internal fun rememberMiniPlayerSwipeHandler(
    scope: CoroutineScope,
    density: Density,
    hapticFeedback: HapticFeedback,
    offsetAnimatable: Animatable<Float, AnimationVector1D>,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
): MiniPlayerSwipeHandler {
    val onNextState = rememberUpdatedState(onNext)
    val onPreviousState = rememberUpdatedState(onPrevious)
    return remember(scope, density, hapticFeedback, offsetAnimatable) {
        MiniPlayerSwipeHandler(
            scope = scope,
            density = density,
            hapticFeedback = hapticFeedback,
            offsetAnimatable = offsetAnimatable,
            onNext = { onNextState.value() },
            onPrevious = { onPreviousState.value() },
        )
    }
}

/**
 * The mini player's swipe as a pointer handler. Events are consumed only once the movement is a clear horizontal one
 * ([MiniSwipePolicy.intentAfterSlop]), so a tap, a vertical drag that expands the player and the buttons' own taps all reach their owners.
 */
internal fun Modifier.miniPlayerSwipeGesture(enabled: Boolean, handler: MiniPlayerSwipeHandler): Modifier {
    if (!enabled) return this
    return this.pointerInput(handler) {
        val slopPx = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var intent: MiniSwipePolicy.Intent? = null
            var totalDx = 0f
            var totalDy = 0f
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            val finished = drag(down.id) { change ->
                val delta = change.positionChange()
                totalDx += delta.x
                totalDy += delta.y
                change.historical.forEach { velocityTracker.addPosition(it.uptimeMillis, it.position) }
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                when (intent) {
                    null -> {
                        intent = MiniSwipePolicy.intentAfterSlop(totalDx, totalDy, slopPx)
                        if (intent == MiniSwipePolicy.Intent.HORIZONTAL) {
                            handler.onDragStart()
                            change.consume()
                            handler.onDrag(totalDx)
                        }
                    }
                    MiniSwipePolicy.Intent.HORIZONTAL -> {
                        change.consume()
                        handler.onDrag(delta.x)
                    }
                    MiniSwipePolicy.Intent.OTHER -> Unit
                }
            }

            if (intent == MiniSwipePolicy.Intent.HORIZONTAL) {
                if (finished) handler.onDragEnd(velocityTracker.calculateVelocity().x) else handler.onDragCancel()
            }
        }
    }
}
