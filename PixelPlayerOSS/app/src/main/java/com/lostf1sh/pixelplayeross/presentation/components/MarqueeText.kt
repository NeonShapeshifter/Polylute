package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints

/**
 * How many times an over-long title scrolls before the marquee settles.
 *
 * Enough to read the whole string at the marquee's 25 dp/s velocity, and then it stops. The point
 * is that it is finite: an unbounded marquee is a per-frame animation that never ends, so a single
 * over-long title visible anywhere on screen keeps the app rendering at display refresh rate for as
 * long as playback continues.
 */
private const val MARQUEE_ITERATIONS = 3

@Composable
fun AutoScrollingTextOnDemand(
    text: String,
    style: TextStyle,
    gradientEdgeColor: Color,
    expansionFractionProvider: () -> Float,
    modifier: Modifier = Modifier,
    canScroll: Boolean = true
) {
    var overflow by remember(text, style) { mutableStateOf(false) }
    val canStart by remember(text, style) { derivedStateOf { expansionFractionProvider() > 0.99f && overflow } }


    if (!overflow) {
        Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            onTextLayout = { res: TextLayoutResult -> overflow = res.hasVisualOverflow },
            modifier = modifier
        )
    } else {
        // A single clipped line with a trailing fade, not a scrolling marquee.
        //
        // The marquee draws the text twice, so at most scroll offsets both copies were on screen
        // together and the title read as two fragments spliced together — and once the gap was
        // widened to prevent that, the title went blank for a full viewport instead. Neither is a
        // readable title. The design's own answer is the restrained one: one line, faded at the
        // edge that overflows, always legible, never duplicated.
        AutoScrollingText(
            text = text,
            style = style,
            textAlign = TextAlign.Start,
            gradientEdgeColor = gradientEdgeColor,
            modifier = modifier,
            canScroll = false
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AutoScrollingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    textAlign: TextAlign? = null,
    gradientEdgeColor: Color,
    gradientWidth: Dp = 24.dp,
    canScroll: Boolean = true
) {
    SubcomposeLayout(modifier = modifier.clipToBounds()) { constraints ->
        val textPlaceable = subcompose("text") {
            Text(text = text, style = style, maxLines = 1)
        }[0].measure(constraints.copy(maxWidth = Int.MAX_VALUE))

        val isOverflowing = textPlaceable.width > constraints.maxWidth

        // The marquee draws the text twice and translates one copy, so the region it draws into
        // has to be definitely bounded. When the incoming width was unbounded this layout fell
        // back to the text's own intrinsic width, which made the layer wide enough to contain BOTH
        // copies. Clamping the measured width is what keeps the second copy outside the clip where
        // it belongs.
        val measuredWidth = constraints.maxWidth.takeIf { it != Constraints.Infinity }
            ?: textPlaceable.width

        // The gap between the two copies has to be at least a viewport wide. With the old 30 dp
        // spacing the tail of one copy and the head of the next were both on screen at the same
        // time for most of the scroll, so the title read as two fragments spliced together rather
        // than as one title. A full-viewport gap means only one copy is ever visible.
        val copySpacing = (measuredWidth + gradientWidth.roundToPx()).toDp()

        val content = @Composable {
            if (isOverflowing && canScroll) {
                val initialDelayMillis = 2000
                val fadeAnimationDuration = 500

                var isScrolling by remember(text, canScroll) { mutableStateOf(false) }
                LaunchedEffect(text, canScroll) {
                    isScrolling = false
                    kotlinx.coroutines.delay(initialDelayMillis.toLong())
                    isScrolling = true
                }

                val animatedLeftGradientStartColor by animateColorAsState(
                    targetValue = if (isScrolling) Color.Transparent else gradientEdgeColor,
                    animationSpec = tween(durationMillis = fadeAnimationDuration),
                    label = "LeftGradientStartColor"
                )

                Box(
                    modifier = Modifier
                        .clipToBounds()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val gradientWidthPx = gradientWidth.toPx()

                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(animatedLeftGradientStartColor, gradientEdgeColor),
                                    startX = 0f,
                                    endX = gradientWidthPx
                                ),
                                blendMode = BlendMode.DstIn
                            )
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(gradientEdgeColor, Color.Transparent),
                                    startX = size.width - gradientWidthPx,
                                    endX = size.width
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    Text(
                        text = text,
                        style = style,
                        textAlign = textAlign,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(
                            // Bounded, not infinite. `basicMarquee` drives a per-frame translate
                            // for as long as it runs, so `Int.MAX_VALUE` here meant that any
                            // over-long title being visible during playback held the entire app at
                            // a rendered frame every ~17 ms *for the whole duration of playback* —
                            // every screen, whether or not that screen had anything else to do.
                            // Measured: with a title that overflowed the app never dropped below
                            // 60 fps; with one that fitted it rendered 0 frames.
                            //
                            // Scrolling still happens, which is the point of it: the title is
                            // revealed, and a couple of passes is enough to read it. What it no
                            // longer does is keep redrawing after it has finished being useful.
                            iterations = MARQUEE_ITERATIONS,
                            spacing = MarqueeSpacing(copySpacing),
                            velocity = 25.dp,
                            initialDelayMillis = initialDelayMillis
                        )
                    )
                }
            } else if (isOverflowing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val gradientWidthPx = gradientWidth.toPx()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(gradientEdgeColor, Color.Transparent),
                                    startX = size.width - gradientWidthPx,
                                    endX = size.width
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    Text(
                        text = text,
                        style = style,
                        textAlign = textAlign,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Text(
                    text = text,
                    style = style,
                    textAlign = textAlign,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        val contentPlaceable = subcompose("content", content)[0]
            .measure(constraints.copy(maxWidth = measuredWidth))

        layout(measuredWidth, contentPlaceable.height) {
            contentPlaceable.place(0, 0)
        }
    }
}
