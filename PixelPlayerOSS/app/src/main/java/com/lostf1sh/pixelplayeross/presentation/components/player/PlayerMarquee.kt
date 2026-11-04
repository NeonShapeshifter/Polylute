package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * B57 2.5 (owner): the title and the "artist · album" line scroll to show **all** their text, and never start or rest cut through a letter.
 *
 * The owner calls this an experiment and judges it on the phone, so **every number is here**, in one place. A text that fits does not move (the callers only ask for
 * the marquee when it overflows). The right edge always ends in a soft fade instead of a hard clip (so "·Too Rare to Die!" fades out rather than being sliced); the left
 * edge stays clean while the text rests at its beginning (the first letter is whole), and fades in once the text starts moving, so the letters leaving on the left also
 * fade instead of being cut.
 */
object PlayerMarquee {
    /** How long the beginning rests, whole, before the text starts to move. */
    const val InitialDelayMs = 1_800

    /** The pause at the end of each pass before it goes round again. */
    const val RepeatDelayMs = 1_200

    /** How fast the text moves. */
    val Velocity: Dp = 28.dp

    /** The gap between the end of the text and its beginning coming round again. */
    val Spacing: Dp = 36.dp

    /** How wide the soft edge is. */
    val Fade: Dp = 16.dp

    /** How long the left edge takes to fade in when the text starts to move. */
    const val LeftFadeInMs = 250
}

/**
 * The marquee with soft edges, for a single-line text that overflows ([scrolling] true); the plain clipped bounds for one that fits. [resetKey] is the text: a new text
 * rests at its beginning again.
 */
@Composable
fun Modifier.playerMarquee(scrolling: Boolean, resetKey: Any?): Modifier {
    if (!scrolling) return this.clipToBounds()
    var moving by remember(resetKey) { mutableStateOf(false) }
    LaunchedEffect(resetKey) {
        moving = false
        delay(PlayerMarquee.InitialDelayMs.toLong())
        moving = true
    }
    val leftFade by animateFloatAsState(if (moving) 1f else 0f, tween(PlayerMarquee.LeftFadeInMs), label = "marqueeLeftFade")
    return this
        .clipToBounds()
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = PlayerMarquee.Fade.toPx().coerceAtMost(size.width / 3f)
            if (fade <= 0f) return@drawWithContent
            // the right edge: always soft
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f),
                size = androidx.compose.ui.geometry.Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
            // the left edge: soft only once the text has started to move
            if (leftFade > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 1f - leftFade), Color.Black), startX = 0f, endX = fade),
                    topLeft = Offset.Zero,
                    size = androidx.compose.ui.geometry.Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
        .basicMarquee(
            iterations = Int.MAX_VALUE,
            repeatDelayMillis = PlayerMarquee.RepeatDelayMs,
            initialDelayMillis = PlayerMarquee.InitialDelayMs,
            spacing = MarqueeSpacing(PlayerMarquee.Spacing),
            velocity = PlayerMarquee.Velocity,
        )
}
