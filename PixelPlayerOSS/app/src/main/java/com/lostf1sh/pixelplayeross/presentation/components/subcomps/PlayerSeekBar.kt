package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.utils.formatDuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import com.lostf1sh.pixelplayeross.presentation.components.scoped.SeekReconciliation
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.presentation.components.WavySliderExpressive
import com.lostf1sh.pixelplayeross.utils.formatDuration
import kotlin.math.roundToLong

@Composable
fun PlayerSeekBar(
    backgroundColor: Color,
    onBackgroundColor: Color,
    primaryColor: Color,
    currentPosition: Long,
    totalDuration: Long,
    onSeek: (Long) -> Unit,
    onSeekPreview: ((Long?) -> Unit)? = null,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    val progressFraction = remember(currentPosition, totalDuration) {
        if (totalDuration > 0) {
            (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    var isUserSeeking by remember { mutableStateOf(false) }
    var lastSeekFinishedTime by remember { mutableStateOf(0L) }
    var targetSeekFraction by remember { mutableFloatStateOf(-1f) }
    var seekAnchorFraction by remember { mutableFloatStateOf(-1f) }
    var seekFraction by remember { mutableFloatStateOf(progressFraction) }
    val lastHapticStep = remember { intArrayOf(-1) }

    LaunchedEffect(progressFraction, isUserSeeking) {
        if (!isUserSeeking) {
            val elapsed = System.currentTimeMillis() - lastSeekFinishedTime
            // Only hand authority back once the player has actually moved for the seek. Adopting
            // `progressFraction` before that is what used to snap the thumb back to the position
            // the user had just dragged it away from.
            if (targetSeekFraction < 0f ||
                SeekReconciliation.hasSettled(
                    progressFraction, targetSeekFraction, seekAnchorFraction, elapsed
                )
            ) {
                seekFraction = progressFraction
                targetSeekFraction = -1f
                seekAnchorFraction = -1f
            }
        }
    }

    // B22 (`Player.dc.html`, the lyrics bar): the timeline lives in a pill on the sheet's own surface: the elapsed time
    // in mono, then the same 2 dp track and small thumb as every other slider, in the accent. No shadow, no wave.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(backgroundColor)
            .padding(horizontal = 20.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatDuration(
                (if (isUserSeeking) (seekFraction * totalDuration).roundToLong() else currentPosition).coerceAtLeast(0L)
            ),
            style = DapType.ValueSmall,
            color = onBackgroundColor,
            maxLines = 1,
        )
        WavySliderExpressive(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 0.dp),
            value = { seekFraction },
            onValueChange = { newFraction ->
                isUserSeeking = true
                seekFraction = newFraction
                onSeekPreview?.invoke((newFraction * totalDuration).roundToLong())
                val quantized = (newFraction.coerceIn(0f, 1f) * 20f).toInt()
                if (quantized != lastHapticStep[0]) {
                    lastHapticStep[0] = quantized
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
            onValueCommit = { finalFraction ->
                seekFraction = finalFraction
                onSeek((finalFraction * totalDuration).roundToLong())
                onSeekPreview?.invoke(null)
                targetSeekFraction = finalFraction
                // The player's own position at the moment of the seek, captured before it has had
                // time to move, so reconciliation can tell movement-for-the-seek from not-yet.
                seekAnchorFraction = progressFraction
                lastSeekFinishedTime = System.currentTimeMillis()
                isUserSeeking = false
            },
            onValueCancel = {
                isUserSeeking = false
                onSeekPreview?.invoke(null)
            },
            strokeWidth = 2.dp,
            thumbRadius = 6.dp,
            activeTrackColor = primaryColor,
            inactiveTrackColor = Dap.interactiveTrack,
            thumbColor = primaryColor,
            waveAmplitudeWhenPlaying = 0.dp,
            isPlaying = false,
            semanticsLabel = stringResource(R.string.cd_playback_position)
        )
    }
}
