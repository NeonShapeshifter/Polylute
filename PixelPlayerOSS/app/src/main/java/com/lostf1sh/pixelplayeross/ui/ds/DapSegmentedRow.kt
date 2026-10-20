package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A compact segmented choice, the kind that sits at the end of a row: "Pista | Álbum", "Clásico | Rápido",
 * "Izquierda | Centro".
 *
 * One choice, saved. The selected segment is the filled one ([Dap.primaryText] with ink on it); the others are quiet.
 * Each segment is a 48 dp touch target tall with its label on one line, and the picker is only as wide as its labels
 * need, so a long label in another language grows the picker instead of overflowing it.
 */
@Composable
fun <T> DapSegmentedPicker(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Segments share the width equally. Used when the picker has a line of its own. */
    fillWidth: Boolean = false,
    /** The track the segments sit on: the page's surface, or the page itself when the picker is on a surface. */
    containerColor: Color = Dap.surface,
) {
    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max))
            .clip(RoundedCornerShape(DapRadius.Surface))
            .background(containerColor)
            .padding(2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .then(if (fillWidth) Modifier.weight(1f) else Modifier)
                    .defaultMinSize(minHeight = 44.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (isSelected) Dap.primaryText else Color.Transparent)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = DapType.Action,
                    color = if (isSelected) Dap.onFill else Dap.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    // A picker that has the whole width shares it in thirds: its labels get every dp they can.
                    modifier = Modifier.padding(horizontal = if (fillWidth) DapSpacing.Xs else DapSpacing.Md, vertical = DapSpacing.Sm),
                )
            }
        }
    }
}

/**
 * A row whose answer is a segmented choice: the label (and an optional line under it) at the start, the picker at
 * the end.
 *
 * When the picker and the label do not fit on one line (a long Swedish label, a large font) the picker goes under the
 * label and takes the full width. Nothing is clipped and nothing overflows: that is the whole point of laying it out
 * by measuring rather than by padding.
 *
 * [dim] is the "frozen" treatment (40 %): the control stays editable, it just reads as kept rather than applied.
 */
@Composable
fun <T> DapSegmentedRow(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    description: String? = null,
    dim: Boolean = false,
    enabled: Boolean = true,
    divider: Boolean = true,
    containerColor: Color = Dap.surface,
) {
    Column(modifier.fillMaxWidth().then(if (dim) Modifier.alpha(0.4f) else Modifier)) {
        SideBySideOrStacked(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = DapHeights.SettingsRow)
                .padding(vertical = DapSpacing.Sm),
            minLabelWidth = 96.dp,
            gap = DapSpacing.Md,
            label = {
                Column {
                    Text(
                        text = label,
                        style = DapType.SettingLabel,
                        color = Dap.primaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!description.isNullOrBlank()) {
                        Text(
                            text = description,
                            style = DapType.Caption,
                            color = Dap.secondaryText,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            trailing = {
                DapSegmentedPicker(
                    options = options,
                    selected = selected,
                    onSelect = onSelect,
                    label = optionLabel,
                    enabled = enabled,
                    containerColor = containerColor,
                )
            },
        )
        if (divider) DapDivider()
    }
}

/**
 * Lays out a label and a trailing control on one line when the label keeps at least [minLabelWidth], and puts the
 * control under the label (at its natural width, at the start) when it does not.
 *
 * It decides from the control's own natural width, which is a property of its labels, so the answer is stable from
 * one measure pass to the next and the control is composed exactly once.
 */
@Composable
private fun SideBySideOrStacked(
    modifier: Modifier,
    minLabelWidth: Dp,
    gap: Dp,
    label: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.layoutId(LabelId)) { label() }
            Box(Modifier.layoutId(TrailingId)) { trailing() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val labelM = measurables.first { it.layoutId == LabelId }
        val trailingM = measurables.first { it.layoutId == TrailingId }
        val gapPx = gap.roundToPx()
        val minLabelPx = minLabelWidth.roundToPx()
        val maxW = constraints.maxWidth
        val naturalTrailing = trailingM.maxIntrinsicWidth(constraints.maxHeight).coerceAtMost(maxW)
        val trailingP = trailingM.measure(Constraints(maxWidth = naturalTrailing, maxHeight = constraints.maxHeight))
        if (maxW - trailingP.width - gapPx >= minLabelPx) {
            val labelP = labelM.measure(Constraints(maxWidth = maxW - trailingP.width - gapPx, maxHeight = constraints.maxHeight))
            val height = maxOf(labelP.height, trailingP.height)
            layout(maxW, height) {
                labelP.placeRelative(0, (height - labelP.height) / 2)
                trailingP.placeRelative(maxW - trailingP.width, (height - trailingP.height) / 2)
            }
        } else {
            val labelP = labelM.measure(Constraints(maxWidth = maxW, maxHeight = constraints.maxHeight))
            val stackGap = DapSpacing.Sm.roundToPx()
            layout(maxW, labelP.height + stackGap + trailingP.height) {
                labelP.placeRelative(0, 0)
                trailingP.placeRelative(0, labelP.height + stackGap)
            }
        }
    }
}

private const val LabelId = "label"
private const val TrailingId = "trailing"
