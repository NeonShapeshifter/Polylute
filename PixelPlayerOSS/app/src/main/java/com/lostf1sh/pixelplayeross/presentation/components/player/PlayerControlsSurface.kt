package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapPlayDisc
import com.lostf1sh.pixelplayeross.ui.ds.DapStateIconButton

/**
 * The transport (B22, `Player.dc.html`): shuffle, previous, play/pause, next, repeat, **in one row**.
 *
 * Shuffle and repeat are the same state button the lock screen and the queue use (accent and a dot when on); the old
 * "Aleatorio / Repetir" text buttons and the bookmark that sat beside them are gone (the bookmark moved next to the
 * heart, on the title row).
 *
 * The surface reads nothing that changes with the song: it draws shuffle, repeat and whether it is playing, and its
 * lambdas are stable for as long as `actions` is, so a song change does not run it (pinned by `FullPlayerRenderTest`).
 */
@Composable
internal fun PlayerControlsSurface(
    controls: PlayerControlsState,
    env: PlayerSheetEnv,
    actions: FullPlayerActions,
) {
    val loadingTweaks = env.loadingTweaks
    val isPlayingProvider = controls.isPlayingProvider
    // Stable for as long as `actions` is: nothing here restarts when the surface recomposes.
    val onPrevious = remember(actions) { actions::onSkipPrevious }
    val onPlayPause = remember(actions) { actions::onPlayPause }
    val onNext = remember(actions) { actions::onSkipNext }
    val onShuffleToggle = remember(actions) { actions::onShuffleToggle }
    val onRepeatToggle = remember(actions) { actions::onRepeatToggle }
    val shouldDelay = loadingTweaks.delayAll || loadingTweaks.delayControls

    DelayedContent(
        shouldDelay = shouldDelay,
        showPlaceholders = loadingTweaks.showPlaceholders,
        applyPlaceholderDelayOnClose = loadingTweaks.applyPlaceholdersOnClose,
        switchOnDragRelease = loadingTweaks.switchOnDragRelease,
        isSheetDragGestureActive = env.isSheetDragGestureActive,
        sharedBoundsModifier = Modifier.fillMaxWidth().heightIn(min = TransportHeight),
        expansionFractionProvider = env.expansionFractionProvider,
        isExpandedOverride = env.currentSheetState == PlayerSheetState.EXPANDED,
        normalStartThreshold = 0.42f,
        delayAppearThreshold = loadingTweaks.contentAppearThresholdPercent / 100f,
        delayCloseThreshold = 1f - (loadingTweaks.contentCloseThresholdPercent / 100f),
        placeholder = { Box(Modifier.fillMaxWidth().height(TransportHeight)) },
    ) {
        PlayerTransportRow(
            isShuffleEnabled = controls.isShuffleEnabled,
            repeatMode = controls.repeatMode,
            isPlayingProvider = isPlayingProvider,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onShuffleToggle = onShuffleToggle,
            onRepeatToggle = onRepeatToggle,
        )
    }
}

/** The play disc is 72 dp: the row is as tall as it. */
private val TransportHeight = 72.dp

@Composable
private fun PlayerTransportRow(
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    isPlayingProvider: () -> Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DapStateIconButton(
            contentDescription = stringResource(R.string.player_shuffle),
            active = isShuffleEnabled,
            onClick = onShuffleToggle,
            stateDescription = stringResource(
                if (isShuffleEnabled) R.string.lock_transport_state_on else R.string.lock_transport_state_off
            ),
        ) { tint -> DapIcon(kind = DapIconKind.Shuffle, tint = tint, size = 24.dp) }
        DapStateIconButton(
            contentDescription = stringResource(R.string.lock_transport_previous),
            active = false,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPrevious()
            },
        ) { tint -> DapIcon(kind = DapIconKind.SkipPrevious, tint = tint, size = 28.dp) }
        PlayPauseDisc(isPlayingProvider = isPlayingProvider, onPlayPause = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onPlayPause()
        })
        DapStateIconButton(
            contentDescription = stringResource(R.string.lock_transport_next),
            active = false,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onNext()
            },
        ) { tint -> DapIcon(kind = DapIconKind.SkipNext, tint = tint, size = 28.dp) }
        DapStateIconButton(
            contentDescription = stringResource(R.string.lock_transport_repeat),
            active = repeatMode != Player.REPEAT_MODE_OFF,
            onClick = onRepeatToggle,
            stateDescription = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> stringResource(R.string.lock_transport_repeat_one)
                Player.REPEAT_MODE_ALL -> stringResource(R.string.lock_transport_repeat_all)
                else -> stringResource(R.string.lock_transport_state_off)
            },
        ) { tint ->
            // Repeat-one is a different mode, not a differently tinted one: a different glyph, so it survives without colour.
            DapIcon(
                kind = if (repeatMode == Player.REPEAT_MODE_ONE) DapIconKind.RepeatOne else DapIconKind.Repeat,
                tint = tint,
                size = 24.dp,
            )
        }
    }
}

/** Reads "is it playing" here, in its own scope, so a pause redraws the disc and nothing around it. */
@Composable
private fun PlayPauseDisc(isPlayingProvider: () -> Boolean, onPlayPause: () -> Unit) {
    val isPlaying = isPlayingProvider()
    val pause = stringResource(R.string.cd_pause)
    val play = stringResource(R.string.cd_play)
    DapPlayDisc(
        contentDescription = if (isPlaying) pause else play,
        onClick = onPlayPause,
    ) { tint ->
        DapIcon(kind = if (isPlaying) DapIconKind.Pause else DapIconKind.Play, tint = tint, size = 20.dp)
    }
}
