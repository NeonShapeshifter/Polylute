package com.lostf1sh.pixelplayeross.ui.ds

import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight

/**
 * One button shape (4 dp, 44 tall), one switch, one slider, one segmented control.
 *
 * No pills, no FABs, no icon buttons with filled circles except Play.
 */

/** Primary: filled text/1 with ink on top. Used once per screen at most. */
@Composable
fun DapPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    DapButtonShell(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        background = Dap.primaryText,
        contentColor = Dap.onFill,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(DapSpacing.Md))
        }
        Text(label, style = DapType.Button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Secondary: bg/2 with text/1. */
@Composable
fun DapSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    DapButtonShell(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        background = Dap.surfaceRaised,
        contentColor = Dap.primaryText,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(DapSpacing.Md))
        }
        Text(label, style = DapType.Button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DapButtonShell(
    onClick: () -> Unit,
    modifier: Modifier,
    background: Color,
    contentColor: Color,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = DapHeights.Control)
            .clip(RoundedCornerShape(DapRadius.Surface))
            // A disabled button keeps its shape and loses its weight, rather than disappearing —
            // the same way DapTextAction and the rows treat disabled.
            .background(if (enabled) background else Dap.surfaceRaised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DapSpacing.Gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                if (enabled) contentColor else Dap.disabledText
        ) { content() }
    }
}

/** A text-only action: Rename, Forget, Undo, Clear, Save, Sort, Change, Edit. */
@Composable
fun DapTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Dap.secondaryText,
    enabled: Boolean = true,
    // PHASE 14.0 — defaults to the full touch target. A dense header action may ask for less;
    // nothing changes for callers that do not.
    minHeight: Dp = DapSizes.MinTouchTarget,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(DapRadius.Surface))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DapSpacing.Xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = DapType.Action,
            color = if (enabled) color else Dap.disabledText,
            overflow = TextOverflow.Clip,
        )
    }
}

/** Destructive text action. Amber is the only warm mark in the interface. */
@Composable
fun DapDestructiveAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = DapTextAction(label, onClick, modifier, color = Dap.warning)

/**
 * The play / shuffle pair at the head of a collection.
 *
 * One geometry for albums, playlists, artists and genres, so the same two actions are in the same
 * two places on every collection in the product. Play is the primary action and the only filled
 * circle on the page; shuffle is icon-only beside it, because a crossed-arrows mark is unambiguous
 * and a second text button of equal width made the two look like a choice between equals.
 *
 * [shuffleLabel] is required: the shuffle control has no text, so its content description is the
 * only thing that names it for a screen reader.
 */
@Composable
fun DapCollectionActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    playLabel: String,
    shuffleLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
    ) {
        Box(
            modifier = Modifier
                .size(DapHeights.Control)
                .clip(CircleShape)
                .background(Dap.primaryText)
                // The label is the control's name, not only its click hint: with onClickLabel
                // alone TalkBack announced a bare "button" and read the action after it.
                .semantics { contentDescription = playLabel }
                .clickable(role = Role.Button, onClickLabel = playLabel, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            DapIcon(kind = DapIconKind.Play, tint = Dap.onFill, size = 16.dp)
        }
        Box(
            modifier = Modifier
                .size(DapHeights.Control)
                .clip(CircleShape)
                .background(Dap.surfaceRaised)
                .semantics { contentDescription = shuffleLabel }
                .clickable(role = Role.Button, onClickLabel = shuffleLabel, onClick = onShuffle),
            contentAlignment = Alignment.Center,
        ) {
            DapIcon(kind = DapIconKind.Shuffle, tint = Dap.primaryText, size = 18.dp)
        }
    }
}

/**
 * The switch. 40 x 22, full round; the design keeps switches round while everything else squares off.
 */
@Composable
fun DapSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = LocalDapAccent.current
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = DapSizes.MinTouchTarget, minWidth = DapSizes.MinTouchTarget)
            .semantics {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(22.dp)
                .clip(CircleShape)
                .background(if (checked) accent else Dap.surfaceRaised)
                .then(
                    if (checked) Modifier
                    else Modifier.border(1.dp, Dap.interactiveTrack, CircleShape)
                )
        ) {
            Box(
                Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 2.dp)
                    .size(if (checked) 18.dp else 16.dp)
                    .clip(CircleShape)
                    .background(if (checked) Dap.onFill else Dap.tertiaryText)
            )
        }
    }
}

/**
 * The slider: a 2 dp track, a 10 dp thumb. Used everywhere, including seek.
 *
 * The visible track is thin; the touch target is not. The gesture surface is padded to a full
 * 48 dp so the control stays operable one-handed.
 */
@Composable
fun DapSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    trackColor: Color = Dap.interactiveTrack,
    fillColor: Color = Dap.primaryText,
    thumbColor: Color = Dap.primaryText,
    enabled: Boolean = true,
) {
    var widthPx = remember { intArrayOf(1) }
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { 5.dp.toPx() }
    val heightPx = with(density) { 48.dp.toPx() }

    fun fractionAt(x: Float): Float {
        val usable = (widthPx[0] - thumbRadiusPx * 2).coerceAtLeast(1f)
        return ((x - thumbRadiusPx) / usable).coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .progressSemantics(value.coerceIn(0f, 1f))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> onValueChange(fractionAt(offset.x)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = { onValueChangeFinished?.invoke() },
                    onDragCancel = { onValueChangeFinished?.invoke() },
                ) { change, _ ->
                    onValueChange(fractionAt(change.position.x))
                }
            }
            .onSizeChanged { widthPx[0] = it.width },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(trackColor, CircleShape)
        )
        Box(
            Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .height(2.dp)
                .background(fillColor, CircleShape)
        )
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(thumbLeftPx(value, widthPx[0], thumbRadiusPx), 0) }
                .size(10.dp)
                .background(thumbColor, CircleShape)
        )
    }
}

/** Left edge of the thumb, so the thumb travels edge-to-edge inside the track. */
private fun thumbLeftPx(value: Float, widthPx: Int, thumbRadiusPx: Float): Int {
    val usable = (widthPx - thumbRadiusPx * 2).coerceAtLeast(0f)
    val center = thumbRadiusPx + usable * value.coerceIn(0f, 1f)
    return (center - thumbRadiusPx).roundToInt()
}

/**
 * The segmented control.
 *
 * Used only where a choice is genuinely binary and user-owned. The EQ band count is the case it
 * exists for: Off · 10-band · 15-band.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun <T> DapSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = DapType.PlayerTab,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DapRadius.Surface))
            .background(Dap.surface)
            .padding(2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (isSelected) Dap.primaryText else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = style,
                    color = if (isSelected) Dap.onFill else Dap.tertiaryText,
                    modifier = Modifier.padding(vertical = 9.dp, horizontal = DapSpacing.Sm),
                )
            }
        }
    }
}

/**
 * A tab label in the player header. Mono, tracked; the active one carries a 2 dp underline.
 *
 * Tapping switches sibling. The underline slides between labels.
 */
@Composable
fun DapHeaderTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Dap.primaryText,
    inactiveColor: Color = Dap.tertiaryText,
) {
    val accent = LocalDapAccent.current
    androidx.compose.foundation.layout.Column(
        modifier = modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = DapType.PlayerTab,
            color = if (selected) activeColor else inactiveColor,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(if (selected) 22.dp else 0.dp)
                .height(2.dp)
                .background(if (selected) accent else Color.Transparent)
        )
    }
}

/** A thin header underline for the Library tab row, drawn as a full-width rule plus an accent. */
@Composable
fun DapTabUnderline(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalDapAccent.current
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = DapSpacing.Xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = DapType.PlayerTab.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified),
            color = if (selected) Dap.primaryText else Dap.tertiaryText,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) accent else Color.Transparent)
        )
    }
}

/**
 * The determinate progress bar: the same hairline track as [DapIndeterminateBar], filled to
 * [progress].
 *
 * A scan that knows how far it has got reports it here rather than falling back to the
 * indeterminate travelling segment, which said "working" and never "how much is left".
 */
@Composable
fun DapProgressBar(progress: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(Dap.surfaceRaised)
            .progressSemantics(progress.coerceIn(0f, 1f)),
    ) {
        Box(
            modifier = Modifier
                .width(maxWidth * progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(Dap.secondaryText),
        )
    }
}

/**
 * The indeterminate progress bar: a hairline track with a travelling segment.
 *
 * The design system has no spinner and no wavy indicator — this is what "working, no known
 * duration" looks like here. It replaces Material's `LinearWavyProgressIndicator` and
 * `CircularWavyProgressIndicator` wherever a surface needs to say it is busy.
 */
@Composable
fun DapIndeterminateBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dapIndeterminate")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dapIndeterminateProgress",
    )

    BoxWithConstraints(
        modifier = modifier
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(Dap.surfaceRaised),
    ) {
        val segmentWidth = maxWidth * 0.35f
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - segmentWidth) * progress)
                .width(segmentWidth)
                .fillMaxHeight()
                .background(Dap.tertiaryText),
        )
    }
}
