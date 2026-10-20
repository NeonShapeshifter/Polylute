package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The row vocabulary every Settings surface is assembled from.
 *
 * A setting is a row, not a card. The page carries one background; hairlines and spacing do the
 * grouping. The previous shape — a rounded container, an icon, a 22 sp title and a 13 sp subtitle
 * per control — cost roughly 90 dp per setting and turned a five-toggle page into a scroll.
 *
 * Every row here is a single line unless its description genuinely needs a second, and every row
 * keeps a 48 dp touch target. Trailing values are right-aligned against the gutter so a column of
 * values reads as a column, which is the point of a dense settings page.
 *
 * Technical values are mono ([DapType.Value]); every label is Manrope.
 */

/** How much of a frozen control is drawn: kept, still editable, not applied. */
const val FrozenAlpha = 0.4f

/**
 * The label-plus-description stack shared by every row that has both.
 *
 * [extra] is what goes under them: the status line (B22), which is part of the row rather than a note beside it.
 */
@Composable
private fun RowLabels(
    label: String,
    description: String?,
    labelColor: Color,
    modifier: Modifier = Modifier,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(modifier) {
        Text(
            text = label,
            style = DapType.SettingLabel,
            color = labelColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = DapType.Caption,
                color = Dap.secondaryText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (extra != null) {
            Spacer(Modifier.height(DapSpacing.Xs))
            extra()
        }
    }
}

/** Shared row frame: gutter padding, a 48 dp floor, and a trailing slot that never collides. */
@Composable
private fun RowFrame(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    divider: Boolean,
    dim: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().then(if (dim) Modifier.alpha(FrozenAlpha) else Modifier)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = DapHeights.SettingsRow)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = DapSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (divider) DapDivider()
    }
}

/**
 * A navigation row: label, optional description, optional trailing value, then the chevron.
 *
 * [value] is set in mono. It is for something the system observed — a route, a rate, a count.
 * Anything that is prose belongs in [description].
 *
 * [trailing] replaces the disclosure chevron when a caller has its own trailing affordance. Passing
 * null — the default — draws the chevron, which is what a navigating row wants.
 */
@Composable
fun DapNavRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    valueStyle: TextStyle = DapType.Value,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    divider: Boolean = true,
    enabled: Boolean = true,
    /** The row's status line, under its description. */
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    /** Frozen: kept and still usable, drawn at 40 %. */
    dim: Boolean = false,
) {
    RowFrame(modifier, onClick.takeIf { enabled }, divider, dim) {
        if (leading != null) {
            Box(
                Modifier.padding(end = DapSpacing.Md),
                contentAlignment = Alignment.Center,
            ) { leading() }
        }
        RowLabels(
            label = label,
            description = description,
            labelColor = if (enabled) Dap.primaryText else Dap.tertiaryText,
            modifier = Modifier.weight(1f),
            extra = extra,
        )
        if (value != null) {
            Spacer(Modifier.width(DapSpacing.Md))
            Text(
                text = value,
                style = valueStyle,
                color = if (enabled) Dap.primaryText else Dap.tertiaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(DapSpacing.Sm))
            Box(contentAlignment = Alignment.Center) { trailing() }
        } else if (onClick != null) {
            Spacer(Modifier.width(DapSpacing.Sm))
            DapChevron(color = Dap.disabledText)
        }
    }
}

/**
 * A row whose one tap is one destructive action (B32-Q5).
 *
 * The label names what the tap does, in the destructive colour ([Dap.warning], the one [DapDestructiveAction] is drawn in), and the
 * description says what it costs. There is no second control inside the row: a row that said "Delete" and carried a "Delete" button
 * said the same word twice and asked for the same tap in two places. Whatever confirmation the action has is the caller's, after the tap.
 */
@Composable
fun DapDestructiveRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    divider: Boolean = true,
) {
    RowFrame(modifier, onClick, divider) {
        RowLabels(
            label = label,
            description = description,
            labelColor = Dap.warning,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A switch row. The switch is the trailing element; there is no chevron and no container. */
@Composable
fun DapSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
    /** The row's status line, under its description. */
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    /** Frozen: kept and still usable, drawn at 40 %. */
    dim: Boolean = false,
) {
    RowFrame(modifier, onClick = null, divider = divider, dim = dim) {
        if (leading != null) {
            Box(
                Modifier.padding(end = DapSpacing.Md),
                contentAlignment = Alignment.Center,
            ) { leading() }
        }
        RowLabels(
            label = label,
            description = description,
            labelColor = if (enabled) Dap.primaryText else Dap.tertiaryText,
            modifier = Modifier.weight(1f),
            extra = extra,
        )
        Spacer(Modifier.width(DapSpacing.Md))
        DapSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * A slider row: the label and its current value on one line, the track underneath.
 *
 * The value is mono because it is a measurement the user is setting, not prose.
 */
@Composable
fun DapSliderRow(
    label: String,
    value: Float,
    valueText: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = DapSpacing.Md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowLabels(
                    label = label,
                    description = description,
                    labelColor = if (enabled) Dap.primaryText else Dap.tertiaryText,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(DapSpacing.Md))
                Text(
                    text = valueText,
                    style = DapType.Value,
                    color = if (enabled) Dap.primaryText else Dap.tertiaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DapSlider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                enabled = enabled,
            )
        }
        if (divider) DapDivider()
    }
}

/**
 * A row with actions rather than a value: a destructive action, a maintenance action, a scan.
 *
 * Actions are text-weight buttons laid out in a row, not full-width filled blocks. A settings page
 * that shows three 48 dp filled buttons stacked is three calls to action competing for the same
 * decision; this keeps each one the size of the thing it does.
 */
@Composable
fun DapActionRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    divider: Boolean = true,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = DapSpacing.Md)) {
        RowLabels(
            label = label,
            description = description,
            labelColor = Dap.primaryText,
        )
        Spacer(Modifier.height(DapSpacing.Sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DapSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) { actions() }
        Spacer(Modifier.height(DapSpacing.Sm))
        if (divider) DapDivider()
    }
}

/**
 * A choice row: the label, the currently selected option as its value, and a chevron.
 *
 * The selection is stated as a value rather than as a pill or a badge, so a column of choice rows
 * reads down the middle.
 */
@Composable
fun DapChoiceRow(
    label: String,
    selectedLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    divider: Boolean = true,
) {
    DapNavRow(
        label = label,
        description = description,
        value = selectedLabel,
        // A selection is a word the user picked from a list, not a reading the system took. The
        // options are prose — "Follow System", "Embedded First", "Compact pill & grid" — and mono
        // is reserved for measurements. Numeric options ("3s", "Medium (512px)") read as prose too,
        // because the number is part of a name rather than a value being reported.
        valueStyle = DapType.CaptionMedium,
        onClick = onClick,
        modifier = modifier,
        divider = divider,
    )
}

/**
 * A disabled row: present so the page's shape is stable, and honest about why it does nothing.
 *
 * A row that simply vanishes when a capability is absent makes the user hunt for it later.
 *
 * The row carries no clickable modifier, so it can never ripple or swallow a tap — but that also
 * means it would otherwise be indistinguishable from ordinary text sitting in a list of live
 * actions. The explicit [disabled] semantics is what makes it a *disabled control* rather than a
 * label, for accessibility and for anyone reading the tree.
 */
@Composable
fun DapUnavailableRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    divider: Boolean = true,
) {
    RowFrame(
        modifier.semantics { disabled() },
        onClick = null,
        divider = divider,
    ) {
        RowLabels(
            label = label,
            description = description,
            labelColor = Dap.tertiaryText,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The trailing state of a row that reports rather than navigates. */
@Composable
fun DapStateRow(
    label: String,
    state: DapStateKind,
    stateLabel: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    divider: Boolean = true,
) {
    RowFrame(modifier, onClick = null, divider = divider) {
        RowLabels(
            label = label,
            description = description,
            labelColor = Dap.primaryText,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(DapSpacing.Md))
        DapState(kind = state, label = stateLabel)
    }
}

/** A small leading glyph for a row, sized to the row rather than to a card. */
@Composable
fun DapRowLeading(kind: DapIconKind, tint: Color = Dap.tertiaryText) {
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        DapIcon(kind = kind, tint = tint, size = 18.dp)
    }
}

/** A quiet rule under a group's last row, so a page ends rather than stopping. */
@Composable
fun DapRowTail(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Dap.divider))
}
