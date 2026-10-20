package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One level the listener can move: an output's volume, the app's digital gain, a brightness. */
data class DapVolumeChannel(
    val label: String,
    /** Position on the track, 0..1. */
    val fraction: Float,
    val valueText: String,
    val onChange: (Float) -> Unit,
    /** A double tap on the channel's label or value (the track itself keeps its drag). */
    val onDoubleTap: (() -> Unit)? = null,
)

private fun Modifier.doubleTap(action: (() -> Unit)?): Modifier =
    if (action == null) this else pointerInput(action) { detectTapGestures(onDoubleTap = { action() }) }

/**
 * A titled level: a header line and one thin track, or two labelled tracks when [secondary] is set.
 *
 * With one channel the value sits in the header and the track carries no label, so the ordinary
 * case is as quiet as it can be. When [secondary] appears the same block splits into two labelled
 * tracks instead of adding a second block. Tracks are inset to the title, not the icon, so the icon
 * reads as the row's marker and the track as belonging to the text.
 */
@Composable
fun DapLevelControl(
    title: String,
    primary: DapVolumeChannel,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    secondary: DapVolumeChannel? = null,
    secondaryFill: Color = LocalDapAccent.current,
) {
    val split = secondary != null
    // Kept after it goes away so the exit animation still has something to draw.
    var lastSecondary by remember { mutableStateOf(secondary) }
    if (secondary != null) lastSecondary = secondary

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(ICON_SLOT), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(DapSpacing.Md))
            Column(Modifier.weight(1f)) {
                Text(title, style = DapType.SettingLabel, color = Dap.primaryText, maxLines = 1)
                if (detail != null) {
                    Text(
                        detail,
                        style = DapType.Caption,
                        color = Dap.tertiaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!split) {
                Spacer(Modifier.width(DapSpacing.Md))
                Text(
                    primary.valueText,
                    style = DapType.Value,
                    color = Dap.primaryText,
                    maxLines = 1,
                    modifier = Modifier.doubleTap(primary.onDoubleTap),
                )
            }
        }
        Column(Modifier.padding(start = ICON_SLOT + DapSpacing.Md)) {
            LevelTrack(primary, labelled = split, fill = Dap.primaryText)
            AnimatedVisibility(
                visible = split,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                lastSecondary?.let { LevelTrack(it, labelled = true, fill = secondaryFill) }
            }
        }
    }
}

/**
 * The volume block. The icon follows the primary level, and the second track — the app's digital
 * gain — takes the accent, because it is the one that means "processing is on".
 */
@Composable
fun DapVolumeControl(
    title: String,
    detail: String,
    primary: DapVolumeChannel,
    secondary: DapVolumeChannel?,
    modifier: Modifier = Modifier,
) {
    DapLevelControl(
        title = title,
        detail = detail,
        primary = primary,
        secondary = secondary,
        modifier = modifier,
        leading = {
            DapIcon(
                kind = when {
                    primary.fraction <= 0f -> DapIconKind.VolumeMute
                    primary.fraction < 0.5f -> DapIconKind.VolumeLow
                    else -> DapIconKind.VolumeHigh
                },
                tint = Dap.secondaryText,
                size = 18.dp,
            )
        },
    )
}

/** "−6 dB", "−6.5 dB", "0 dB": a true minus sign, and no decimal a whole step does not need. */
fun formatDecibels(db: Float): String {
    val rounded = (db * 10f).roundToInt() / 10f
    val magnitude = abs(rounded)
    val number = if (magnitude % 1f == 0f) magnitude.toInt().toString()
    else String.format(Locale.US, "%.1f", magnitude)
    return if (rounded < 0f) "−$number" else number
}

@Composable
private fun LevelTrack(channel: DapVolumeChannel, labelled: Boolean, fill: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (labelled) {
            Text(
                channel.label,
                style = DapType.Caption,
                color = Dap.secondaryText,
                maxLines = 1,
                modifier = Modifier.width(LABEL_WIDTH).doubleTap(channel.onDoubleTap),
            )
        }
        DapSlider(
            value = channel.fraction,
            onValueChange = channel.onChange,
            fillColor = fill,
            thumbColor = fill,
            modifier = Modifier.weight(1f),
        )
        if (labelled) {
            Text(
                channel.valueText,
                style = DapType.ValueSmall,
                color = Dap.primaryText,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.width(VALUE_WIDTH).doubleTap(channel.onDoubleTap),
            )
        }
    }
}

private val ICON_SLOT = 20.dp
private val LABEL_WIDTH = 52.dp
private val VALUE_WIDTH = 56.dp
