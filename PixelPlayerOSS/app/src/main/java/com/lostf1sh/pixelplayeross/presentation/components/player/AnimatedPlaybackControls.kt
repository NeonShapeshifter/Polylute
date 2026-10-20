package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import kotlinx.coroutines.delay
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

private enum class PlaybackButtonType { NONE, PREVIOUS, PLAY_PAUSE, NEXT }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AnimatedPlaybackControls(
    isPlayingProvider: () -> Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 90.dp,
    baseWeight: Float = 1f,
    expansionWeight: Float = 1.1f,
    compressionWeight: Float = 0.65f,
    pressAnimationSpec: AnimationSpec<Float>,
    releaseDelay: Long = 220L,
    playPauseCornerPlaying: Dp = 60.dp,
    playPauseCornerPaused: Dp = 26.dp,
    colorOtherButtons: Color = LocalMaterialTheme.current.secondaryContainer,
    colorPlayPause: Color = LocalMaterialTheme.current.primary,
    tintPlayPauseIcon: Color = LocalMaterialTheme.current.onPrimary,
    tintOtherIcons: Color = LocalMaterialTheme.current.onSecondaryContainer,
    colorPreviousButton: Color = colorOtherButtons,
    colorNextButton: Color = colorOtherButtons,
    tintPreviousIcon: Color = tintOtherIcons,
    tintNextIcon: Color = tintOtherIcons,
    playPauseIconSize: Dp = 36.dp,
    iconSize: Dp = 32.dp,
) {
    val isPlaying = isPlayingProvider()
    var lastClicked by remember { mutableStateOf<PlaybackButtonType?>(null) }
    val latestIsPlayingProvider by rememberUpdatedState(newValue = isPlayingProvider)
    val latestLastClicked by rememberUpdatedState(newValue = lastClicked)
    val isPlayPauseLocked =
        lastClicked == PlaybackButtonType.NEXT || lastClicked == PlaybackButtonType.PREVIOUS
    var playPauseVisualState by remember { mutableStateOf(isPlaying) }
    var pendingPlayPauseState by remember { mutableStateOf<Boolean?>(null) }
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(lastClicked) {
        if (lastClicked != null) {
            delay(releaseDelay)
            lastClicked = null
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            pendingPlayPauseState = true
            return@LaunchedEffect
        }

        val shouldDelay = latestLastClicked != PlaybackButtonType.PLAY_PAUSE
        if (shouldDelay) {
            delay(releaseDelay)
        }
        if (!latestIsPlayingProvider()) {
            pendingPlayPauseState = false
        }
    }

    LaunchedEffect(isPlayPauseLocked, pendingPlayPauseState) {
        if (!isPlayPauseLocked) {
            pendingPlayPauseState?.let {
                playPauseVisualState = it
                pendingPlayPauseState = null
            }
        }
    }

    // Restrained transport: two glyphs and one filled circle. Skip is a button, never a gesture.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val prevScale by animateFloatAsState(
            targetValue = if (lastClicked == PlaybackButtonType.PREVIOUS) 0.86f else 1f,
            animationSpec = pressAnimationSpec,
            label = "prevScale"
        )
        TransportGlyphButton(
            kind = DapIconKind.SkipPrevious,
            description = stringResource(R.string.previous_track),
            scale = prevScale,
            onClick = {
                lastClicked = PlaybackButtonType.PREVIOUS
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPrevious()
            }
        )

        Spacer(modifier = Modifier.width(14.dp))

        val playScale by animateFloatAsState(
            targetValue = if (lastClicked == PlaybackButtonType.PLAY_PAUSE) 0.92f else 1f,
            animationSpec = pressAnimationSpec,
            label = "playScale"
        )
        val playPauseLabel = stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play)
        Box(
            modifier = Modifier
                .size(DapSizes.PlayButton)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                }
                .clip(CircleShape)
                .background(Dap.primaryText)
                .semantics { contentDescription = playPauseLabel }
                .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                    lastClicked = PlaybackButtonType.PLAY_PAUSE
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPlayPause()
                },
            contentAlignment = Alignment.Center
        ) {
            DapIcon(
                kind = if (playPauseVisualState) DapIconKind.Pause else DapIconKind.Play,
                tint = Dap.onFill,
                // The pause bars fill their 18 x 20 box; the play triangle sits inside a
                // 12 x 14 box with margin. The triangle is drawn a little larger so the two
                // states read as the same optical weight.
                size = if (playPauseVisualState) 20.dp else 22.dp
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        val nextScale by animateFloatAsState(
            targetValue = if (lastClicked == PlaybackButtonType.NEXT) 0.86f else 1f,
            animationSpec = pressAnimationSpec,
            label = "nextScale"
        )
        TransportGlyphButton(
            kind = DapIconKind.SkipNext,
            description = stringResource(R.string.next_track),
            scale = nextScale,
            onClick = {
                lastClicked = PlaybackButtonType.NEXT
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onNext()
            }
        )
    }
}

/**
 * A plain transport glyph. It carries no container: at this level the artwork and the play button
 * are the only things allowed to be loud.
 */
@Composable
private fun TransportGlyphButton(
    kind: DapIconKind,
    description: String,
    scale: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(DapSizes.MinTouchTarget)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(DapRadius.Surface))
            .semantics { contentDescription = description }
            .clickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Slightly larger than the transport glyphs used to be: on the device they read as
        // small next to the play button. Still clearly subordinate to it.
        DapIcon(kind = kind, tint = Dap.primaryText, size = 25.dp)
    }
}
