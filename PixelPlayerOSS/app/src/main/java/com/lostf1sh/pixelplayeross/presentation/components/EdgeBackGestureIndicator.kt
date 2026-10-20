package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.presentation.navigation.BackEdge
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind

/**
 * The visual acknowledgement that an edge swipe is being read as Back.
 *
 * The gesture already worked, but silently: a swipe that was going to go Back and a swipe that was
 * going to do nothing looked identical until the screen changed underneath. This is the missing
 * confirmation — the same thing a phone or a laptop does when it shows you the page edge following
 * your finger.
 *
 * **This is feedback only.** It reads a progress value that the gesture already computes and draws
 * something; it decides nothing. It cannot claim a gesture, cannot trigger Back, cannot make a
 * swipe succeed that would otherwise fail, and does not move the activation threshold. If it were
 * removed the gesture would behave exactly as it does now, one frame of feedback poorer.
 *
 * It is drawn where the Back affordance is drawn — the left edge, vertically centred — because that
 * is the part of the screen the gesture belongs to, and it slides right with [progress] so the
 * movement itself says how far the swipe has gone.
 */
@Composable
fun EdgeBackGestureIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    /** The side the gesture started on (B25-S6): the glyph mirrors and slides in from that edge. */
    edge: BackEdge = BackEdge.LEFT,
) {
    // Smoothed so the glyph follows the finger without jittering on every pointer sample, and so it
    // fades out rather than vanishing when the gesture is released.
    val shown by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 90),
        label = "EdgeBackGestureProgress",
    )

    if (shown <= 0.001f) return

    // Rises quickly and then holds: the point is to confirm the gesture has been recognised, which
    // is true from the first few pixels of travel, not only at the threshold.
    val presence = (shown / 0.35f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .graphicsLayer {
                // Travels with the gesture. The range is deliberately short — this is a nudge, not a
                // page that follows the finger — and never reaches past the strip the gesture owns.
                translationX = edge.inward((shown * 14.dp.toPx()) - 18.dp.toPx())
            }
            .alpha(presence)
            .scale(0.92f + 0.08f * presence),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Dap.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            DapIcon(
                kind = if (edge == BackEdge.LEFT) DapIconKind.Chevron else DapIconKind.ChevronRight,
                tint = Dap.primaryText,
                size = 18.dp,
            )
        }
    }
}
