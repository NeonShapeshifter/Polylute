package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

/**
 * How often the equaliser bars' underlying phase is re-evaluated.
 *
 * ~8 Hz. The curves it samples have 3.6 s and 12 s periods, so this is far more resolution than
 * those curves can visibly use, and it costs roughly a thirteenth of the frames a per-frame
 * animation would.
 */
private const val EQ_SAMPLE_INTERVAL_MS = 125L

@Composable
fun PlayingEqIcon(
    modifier: Modifier = Modifier,
    color: Color,
    isPlaying: Boolean = true,
    bars: Int = 3,
    minHeightFraction: Float = 0.28f,
    maxHeightFraction: Float = 1.0f,
    phaseDurationMillis: Int = 3600,
    wanderDurationMillis: Int = 12000,
    gapFraction: Float = 0.30f
) {
    val fullRotation = (2f * PI).toFloat()
    // Sampled on a timer rather than driven by Animatable.
    //
    // These two loops used to be `Animatable.animateTo` chained forever, which is a per-frame
    // animation: every one of them redrew its Canvas at display refresh rate for as long as the
    // row was on screen. A queue or a song list showing the playing row therefore held the whole
    // app at ~60 fps on its own.
    //
    // Neither loop is fast enough to need that. Their periods are seconds long -- 3.6 s of phase
    // and 12 s of wander -- so sampling them a few times a second traces the same path to the eye
    // while costing a fraction of the frames. This is the same curve, evaluated less often.
    var phaseAnim by remember { mutableFloatStateOf(0f) }
    var wanderAnim by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying, phaseDurationMillis) {
        if (!isPlaying) return@LaunchedEffect
        var elapsed = 0L
        while (isActive) {
            delay(EQ_SAMPLE_INTERVAL_MS)
            elapsed = (elapsed + EQ_SAMPLE_INTERVAL_MS) % phaseDurationMillis
            phaseAnim = elapsed.toFloat() / phaseDurationMillis * fullRotation
        }
    }

    LaunchedEffect(isPlaying, wanderDurationMillis) {
        if (!isPlaying) return@LaunchedEffect
        var elapsed = 0L
        while (isActive) {
            delay(EQ_SAMPLE_INTERVAL_MS)
            elapsed = (elapsed + EQ_SAMPLE_INTERVAL_MS) % wanderDurationMillis
            wanderAnim = elapsed.toFloat() / wanderDurationMillis * fullRotation
        }
    }

    val activity by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "activity"
    )

    val speeds = remember(bars) { List(bars) { (it + 1).toFloat() } }
    val shifts = remember(bars) { List(bars) { i -> i * 0.9f } }

    Canvas(modifier = modifier) {
        val phase = phaseAnim
        val wander = wanderAnim
        val w = size.width
        val h = size.height

        val tentativeBarW = w / (bars + (bars - 1) * (1f + gapFraction))
        val gap = tentativeBarW * gapFraction
        val barW = tentativeBarW
        val corner = CornerRadius(barW / 2f, barW / 2f)

        repeat(bars) { i ->
            val slowShift = 0.6f * sin(wander + i * 0.4f)
            val slowAmp   = 0.85f + 0.15f * sin(wander * 0.5f + 1.1f + i * 0.3f)

            val v = (sin(phase * speeds[i] + shifts[i] + slowShift) * slowAmp + 1f) * 0.5f

            val eased = v * v * (3 - 2 * v)

            val fracBars = minHeightFraction + (maxHeightFraction - minHeightFraction) * eased
            val barH = h * fracBars

            val dotH = barW

            val blendedH = dotH + (barH - dotH) * activity

            val top = (h - blendedH) / 2f
            val left = i * (barW + gap)

            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barW, blendedH),
                cornerRadius = corner
            )
        }
    }
}
