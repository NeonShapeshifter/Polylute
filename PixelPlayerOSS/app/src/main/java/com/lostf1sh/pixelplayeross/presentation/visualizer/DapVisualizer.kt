package com.lostf1sh.pixelplayeross.presentation.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * How often the bars are redrawn.
 *
 * ~24 fps, and enforced here rather than by whatever rate the capture listener happens to fire at.
 * The acquisition rate is a property of the audio effect; the draw rate is a property of the
 * screen, and letting the first dictate the second is how a decoration ends up driving the display
 * at its refresh rate.
 *
 * A frame loop that is bounded, visible-gated and stopped when idle — the opposite of the
 * unconditional `invalidate` that caused this project's earlier playback-rendering problem.
 */
private const val REDRAW_INTERVAL_MS = 42L

/**
 * The playback visualiser.
 *
 * Draws only what [VisualizerDataSource] observed. When the source has nothing truthful to report
 * the caller does not compose this at all — it falls back to artwork rather than showing an empty
 * frame pretending to be a quiet passage.
 *
 * Decorative by contract: the canvas clears its own semantics so a screen reader is not made to
 * enumerate thirty-two meaningless bars. Nothing about playback is communicated only here.
 */
@Composable
fun DapVisualizer(
    bands: FloatArray,
    accent: Color,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    active: Boolean = true,
) {
    var displayed by remember { mutableStateOf(FloatArray(bands.size)) }

    LaunchedEffect(active) {
        if (!active) {
            displayed = FloatArray(displayed.size)
            return@LaunchedEffect
        }
        while (isActive) {
            delay(REDRAW_INTERVAL_MS)
            displayed = bands
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { },
    ) {
        val count = displayed.size
        if (count == 0 || size.width <= 0f) return@Canvas

        val gap = size.width * 0.006f
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)

        for (i in 0 until count) {
            val level = displayed[i].coerceIn(0f, 1f)
            // A floor so a silent band is a visible dot rather than a gap: the bar count is part
            // of the shape and should not appear to change with the music.
            val barHeight = (size.height * level).coerceAtLeast(barWidth)
            val left = i * (barWidth + gap)
            drawRoundRect(
                color = accent,
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = corner,
            )
        }
    }
}
