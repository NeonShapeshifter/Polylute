package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.times
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Normalizes [value] into 0..1. Non-finite input is treated as 0 so a single bad value can never
 * poison the rendered progress (NaN would survive every later interpolation).
 */
private fun normalizeValue(value: Float, valueRange: ClosedFloatingPointRange<Float>): Float {
    if (!value.isFinite()) return 0f
    val span = valueRange.endInclusive - valueRange.start
    if (span == 0f) return 0f
    return ((value - valueRange.start) / span).coerceIn(0f, 1f)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WavySliderExpressive(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    onValueCommit: ((Float) -> Unit)? = null,
    /** A gesture torn down without a release (restart or disposal). Never a seek; see below. */
    onValueCancel: (() -> Unit)? = null,
    activeTrackColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    inactiveTrackColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
    thumbColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,

    isPlaying: Boolean = true,
    isVisible: Boolean = true,
    strokeWidth: Dp = 5.dp,
    thumbRadius: Dp = 8.dp,
    trackEdgePadding: Dp = thumbRadius,
    wavelength: Dp = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
    waveSpeed: Dp = WavyProgressIndicatorDefaults.LinearDeterminateWavelength / 2f,

    waveAmplitudeWhenPlaying: Dp = 4.dp,
    thumbLineHeightWhenInteracting: Dp = 24.dp,
    semanticsLabel: String? = null,
    semanticsProgressStep: Float = 0.01f,
    /**
     * Re-arms the wave's scroll window when it changes (a new track, say). The wave also re-arms on
     * its own when the bar appears, when playback starts and after a touch; see [WaveScrollPolicy].
     */
    scrollActivityKey: Any? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
    val trackEdgePaddingPx = with(density) { trackEdgePadding.coerceAtLeast(0.dp).toPx() }
    val thumbLineHeightPx = with(density) { thumbLineHeightWhenInteracting.toPx() }

    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    }

    // Read through the latest provider instead of capturing the first one: callers may swap the
    // state object backing `value` (for example a remember() keyed on the current song), and a
    // captured lambda would keep reading the abandoned state forever.
    val latestValueProvider by rememberUpdatedState(value)

    val normalizedValueState = remember(valueRange) {
        derivedStateOf {
            val v = latestValueProvider()
            if (valueRange.endInclusive == valueRange.start) 0f
            else normalizeValue(v, valueRange)
        }
    }

    val safeSemanticsStep = semanticsProgressStep.coerceIn(0.005f, 0.25f)
    val semanticNormalizedValueState = remember(safeSemanticsStep, normalizedValueState) {
        derivedStateOf {
            val norm = normalizedValueState.value
            ((norm / safeSemanticsStep).roundToInt() * safeSemanticsStep).coerceIn(0f, 1f)
        }
    }
    val semanticSliderValueState = remember(valueRange, semanticNormalizedValueState) {
        derivedStateOf {
            valueRange.start + semanticNormalizedValueState.value * (valueRange.endInclusive - valueRange.start)
        }
    }
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val latestOnValueCommit by rememberUpdatedState(onValueCommit)
    val latestOnValueCancel by rememberUpdatedState(onValueCancel)
    var isPointerSeeking by remember { mutableStateOf(false) }
    val isInteracting = isPointerSeeking

    val thumbInteractionFraction by animateFloatAsState(
        targetValue = if (isInteracting) 1f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "ThumbInteractionAnim"
    )
    val animatedAmplitudeState = animateFloatAsState(
        targetValue = if (enabled && isPlaying && !isInteracting) 1f else 0f,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "amplitude"
    )
    val animatedAmplitude by animatedAmplitudeState

    // AUD-146: Material scrolls the wave for as long as `waveSpeed` is above zero, flat or not, and
    // that scroll is a draw-phase animation: the whole window is redrawn at refresh rate. The speed
    // handed to it is therefore zero unless the wave is visible, has an amplitude, and is inside
    // its scroll window. Only the boolean is observed here, not the amplitude's per-frame value.
    val amplitudeShown by remember { derivedStateOf { animatedAmplitudeState.value > 0f } }
    val waveScrolling by rememberWaveScrolling(
        wanted = WaveScrollPolicy.mayScroll(visible = isVisible, amplitudeShown = amplitudeShown),
        cycleMillis = WaveScrollPolicy.cycleMillis(wavelength.value, waveSpeed.value),
        activityKey = scrollActivityKey
    )

    val currentHalfWidth = remember(thumbRadius, strokeWidth) {
        derivedStateOf {
            val fraction = thumbInteractionFraction
            val radius = thumbRadius
            val halfStroke = strokeWidth * 0.6f
            radius * (1f - fraction) + halfStroke * fraction
        }
    }

    val dynamicGapSize = remember {
        derivedStateOf {
            val fraction = thumbInteractionFraction
            val idleGap = 6.dp
            val draggingGap = currentHalfWidth.value + 1.2.dp
            idleGap + (draggingGap - idleGap) * fraction
        }
    }

    val renderedNormalizedProgress = remember {
        val initialNorm = if (valueRange.endInclusive == valueRange.start) 0f
            else normalizeValue(latestValueProvider(), valueRange)
        mutableFloatStateOf(initialNorm)
    }
    var lastProgressUpdateNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isInteracting, enabled, normalizedValueState) {
        snapshotFlow { normalizedValueState.value }.collect { target ->
            if (!enabled || isInteracting) {
                renderedNormalizedProgress.floatValue = target
                lastProgressUpdateNanos = System.nanoTime()
                return@collect
            }

            // Ordinary playback: draw the sampled position directly.
            //
            // This used to interpolate toward each sample with a per-frame loop lasting
            // roughly as long as the gap between samples. During playback the position advances a
            // little on every sample, so that loop ran again and again with no gap in between --
            // a seek bar that never stopped animating, holding any surface showing one at display
            // refresh rate for the whole of playback.
            //
            // It is not needed. The samples already arrive at 180 ms (about 5.5 Hz) in the expanded
            // player, and between two of them the bar moves by a fraction of a percent of its own
            // width, so the steps are invisible. Direct manipulation is unaffected: while a finger
            // is down the branch above draws the finger's own value, and the branch above that
            // snaps outright on a large jump such as a seek.
            renderedNormalizedProgress.floatValue = target
            lastProgressUpdateNanos = System.nanoTime()
        }
    }

    val containerHeight = max(WavyProgressIndicatorDefaults.LinearContainerHeight, max(thumbRadius * 2, thumbLineHeightWhenInteracting))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(containerHeight)
            .clearAndSetSemantics {
                if (!semanticsLabel.isNullOrBlank()) {
                    contentDescription = semanticsLabel
                }
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = semanticSliderValueState.value,
                    range = valueRange.start..valueRange.endInclusive,
                    steps = 0
                )
                if (enabled) {
                    setProgress { requested ->
                        val coerced = requested.coerceIn(valueRange.start, valueRange.endInclusive)
                        latestOnValueChange(coerced)
                        latestOnValueCommit?.invoke(coerced)
                            ?: latestOnValueChangeFinished?.invoke()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isVisible) {
            LinearWavyProgressIndicator(
                progress = { renderedNormalizedProgress.floatValue },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = trackEdgePadding.coerceAtLeast(0.dp))
                    .clearAndSetSemantics { },
                color = activeTrackColor,
                trackColor = inactiveTrackColor,
                stroke = stroke,
                trackStroke = stroke,
                gapSize = 2f * dynamicGapSize.value * (1.0f + 0.1573f * animatedAmplitude * animatedAmplitude),
                stopSize = 3.dp,
                amplitude = { progress -> if (progress > 0f) animatedAmplitude else 0f },
                wavelength = wavelength,
                waveSpeed = if (waveScrolling) waveSpeed else 0.dp
            )
        } else {
            Spacer(modifier = Modifier.fillMaxWidth().height(containerHeight))
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (!isVisible) return@Canvas
            val edgePaddingPx = trackEdgePaddingPx.coerceIn(0f, size.width / 2f)
            val trackStart = edgePaddingPx
            val trackEnd = size.width - edgePaddingPx
            val trackWidth = (trackEnd - trackStart).coerceAtLeast(0f)
            val thumbY = size.height / 2
            val renderedProgress = renderedNormalizedProgress.floatValue

            fun lerp(start: Float, stop: Float, fraction: Float): Float {
                return start + (stop - start) * fraction
            }

            val currentWidth = lerp(thumbRadiusPx * 2f, strokeWidthPx * 1.2f, thumbInteractionFraction)
            val currentHeight = lerp(thumbRadiusPx * 2f, thumbLineHeightPx, thumbInteractionFraction)
            val rawThumbX = trackStart + (trackWidth * renderedProgress)
            val minThumbCenter = (currentWidth / 2f).coerceAtMost(size.width / 2f)
            val maxThumbCenter = (size.width - currentWidth / 2f).coerceAtLeast(minThumbCenter)
            val thumbX = rawThumbX.coerceIn(minThumbCenter, maxThumbCenter)
            
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(
                    thumbX - currentWidth / 2f,
                    thumbY - currentHeight / 2f
                ),
                size = Size(currentWidth, currentHeight),
                cornerRadius = CornerRadius(currentWidth / 2f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled, valueRange, trackEdgePaddingPx) {
                    if (!enabled) return@pointerInput

                    fun valueForX(rawX: Float): Float {
                        val edgePadding = trackEdgePaddingPx.coerceIn(0f, size.width / 2f)
                        val trackStart = edgePadding
                        val trackEnd = size.width - edgePadding
                        val trackWidth = (trackEnd - trackStart).coerceAtLeast(1f)
                        val normalized = ((rawX - trackStart) / trackWidth).coerceIn(0f, 1f)
                        return valueRange.start +
                            normalized * (valueRange.endInclusive - valueRange.start)
                    }

                    awaitEachGesture {
                        var gestureValue: Float? = null
                        var committed = false
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            isPointerSeeking = true
                            down.consume()
                            var latestGestureValue = valueForX(down.position.x)
                            gestureValue = latestGestureValue
                            latestOnValueChange(latestGestureValue)

                            var pointerId = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: event.changes.firstOrNull { it.pressed }
                                    ?: break

                                pointerId = change.id
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }

                                if (change.position != change.previousPosition) {
                                    change.consume()
                                    latestGestureValue = valueForX(change.position.x)
                                    gestureValue = latestGestureValue
                                    latestOnValueChange(latestGestureValue)
                                }
                            }

                            committed = true
                            latestOnValueCommit?.invoke(latestGestureValue)
                                ?: latestOnValueChangeFinished?.invoke()
                        } finally {
                            isPointerSeeking = false
                            // A gesture can be torn down without an up event (pointerInput restart
                            // or disposal). It must be terminated, or callers that latch a "user is
                            // scrubbing" flag stay latched and stop following playback. It must NOT
                            // be committed: the restart that tears it down is typically a track
                            // change, and committing the stale fraction against the new track's
                            // duration seeks the new track to the same proportion. Measured: a new
                            // track started at 47.2 s after a scrub at 0.52 of the previous one.
                            if (!committed && gestureValue != null) {
                                latestOnValueCancel?.invoke() ?: latestOnValueChangeFinished?.invoke()
                            }
                        }
                    }
                }
        )
    }
}

/**
 * Whether the wave's scroll animation should be running, as a state the caller reads once.
 *
 * Starts the scroll when [wanted] turns true and stops it when it turns false (the wave is flat or
 * hidden, so stopping is invisible). While it runs it counts a window of [WaveScrollPolicy.ACTIVE_WINDOW_MS]
 * on the frame clock, re-armed by [activityKey] and by every return to the foreground, and stops at
 * the first wave-cycle boundary after the window so the freeze does not shift the picture.
 *
 * The cycle origin is the frame Material's own offset animation starts on: the speed is applied by
 * the composition in the frame after the write, and the animation takes its first frame the one
 * after that. It is kept across re-arms (the animation is not restarted by them) and across the
 * foreground/background trips, because both clocks keep counting through a pause. An estimate off by
 * a frame or two moves the freeze by under a few percent of one wavelength.
 *
 * Nothing here runs while the screen is not being drawn: the effect lives inside
 * `repeatOnLifecycle(STARTED)`, and a stopped activity's frame clock is paused by Compose anyway.
 */
@Composable
private fun rememberWaveScrolling(
    wanted: Boolean,
    cycleMillis: Long,
    activityKey: Any?,
): State<Boolean> {
    val scrolling = remember { mutableStateOf(false) }
    val originNanos = remember { LongArray(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(wanted, cycleMillis, activityKey, lifecycle) {
        if (!wanted || cycleMillis <= 0L) {
            scrolling.value = false
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val cycleNanos = cycleMillis * 1_000_000L
            if (!scrolling.value) {
                scrolling.value = true
                withFrameNanos { }                               // the frame that applies the speed
                originNanos[0] = withFrameNanos { it }           // the offset animation's first frame
            }
            val windowStart = withFrameNanos { it }
            var previous = windowStart
            while (true) {
                val now = withFrameNanos { it }
                if (WaveScrollPolicy.shouldFreeze(
                        frameNanos = now,
                        previousFrameNanos = previous,
                        originNanos = originNanos[0],
                        cycleNanos = cycleNanos,
                        windowStartNanos = windowStart,
                        windowNanos = WaveScrollPolicy.windowNanos(),
                    )
                ) {
                    scrolling.value = false
                    break
                }
                previous = now
            }
            awaitCancellation()
        }
    }
    return scrolling
}
