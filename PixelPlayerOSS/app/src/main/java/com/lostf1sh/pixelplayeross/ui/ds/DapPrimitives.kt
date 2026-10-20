package com.lostf1sh.pixelplayeross.ui.ds

import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The state vocabulary.
 *
 * One rule: a 6 dp mark before a mono word. Never a pill, never a badge, never an icon.
 *
 * This is how the product tells the truth about what it is doing, so the mapping is fixed:
 * accent means confirmed or applied, grey means bypassed, no mark means unavailable, amber means
 * attention. See `03_FRONTEND_BOUNDARIES.md` §5.
 */
enum class DapStateKind {
    /** Carrying audio now. DAC, route. */
    Active,

    /** Read back from hardware. Output rate. */
    Confirmed,

    /** Applied or chosen by the user. EQ, Safety Max, headphones. */
    Selected,

    /** Present, not carrying audio. Ring, not dot. */
    Connected,

    /** Configured, signal does not pass through it. */
    Bypassed,

    /** Unavailable or not requested. No mark, text/3. */
    Off,

    /** Known device, absent now. Grey ring. */
    NotConnected,

    /** Processing required. Amber, the only warm mark. */
    Attention,
}

private data class StateVisuals(val mark: MarkStyle, val word: Color)

private sealed interface MarkStyle {
    data class Filled(val color: Color) : MarkStyle
    data class Ring(val color: Color) : MarkStyle
    data object None : MarkStyle
}

@Composable
private fun visualsFor(kind: DapStateKind): StateVisuals {
    val accent = LocalDapAccent.current
    return when (kind) {
        DapStateKind.Active, DapStateKind.Confirmed, DapStateKind.Selected ->
            StateVisuals(MarkStyle.Filled(accent), accent)
        DapStateKind.Connected ->
            StateVisuals(MarkStyle.Ring(Dap.primaryText), Dap.primaryText)
        DapStateKind.Bypassed ->
            StateVisuals(MarkStyle.Filled(Dap.tertiaryText), Dap.secondaryText)
        DapStateKind.Off ->
            StateVisuals(MarkStyle.None, Dap.disabledText)
        DapStateKind.NotConnected ->
            StateVisuals(MarkStyle.Ring(Dap.disabledText), Dap.disabledText)
        DapStateKind.Attention ->
            StateVisuals(MarkStyle.Filled(Dap.warning), Dap.warning)
    }
}

/** The 6 dp mark alone. */
@Composable
fun DapStateMark(kind: DapStateKind, modifier: Modifier = Modifier) {
    val mark = visualsFor(kind).mark
    Box(modifier.size(DapSizes.StateMark)) {
        when (mark) {
            is MarkStyle.Filled -> Box(
                Modifier
                    .size(DapSizes.StateMark)
                    .background(mark.color, CircleShape)
            )
            is MarkStyle.Ring -> Box(
                Modifier
                    .size(DapSizes.StateMark)
                    .border(1.5.dp, mark.color, CircleShape)
            )
            MarkStyle.None -> Unit
        }
    }
}

/**
 * A state mark and its mono word, laid out as one unit.
 *
 * The mark keeps its 6 dp footprint when absent so words stay on a common left edge across a group.
 */
@Composable
fun DapState(
    kind: DapStateKind,
    label: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = DapType.StateWord,
) {
    val visuals = visualsFor(kind)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DapStateMark(kind)
        Spacer(Modifier.width(DapSpacing.Sm))
        Text(
            text = label,
            style = style,
            color = visuals.word,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Section header: label 11 mono, tracked 10%, text/3.
 *
 * With a [trailing] action (Change, Reset to flat, Close) the header is one row: the label takes the width that is
 * left and the action sits at the end, both centred on the same line. It used to be a `FlowRow` that placed the
 * action straight after the label and aligned both to the top, so a 48 dp action hung below a 11 sp label and
 * floated in the middle of the row (B21, the DAC profile's "Headphones · Change"). A long label wraps inside its
 * own width; it never pushes the action off the row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DapSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (trailing == null) {
        FlowRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
        ) {
            Text(
                text = text,
                style = DapType.SectionHeader,
                color = Dap.tertiaryText,
            )
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = DapType.SectionHeader,
            color = Dap.tertiaryText,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * The hairline. Rows and lightweight surfaces carry the structure; cards do not.
 */
@Composable
fun DapDivider(
    modifier: Modifier = Modifier,
    color: Color = Dap.divider,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

/**
 * A ruled section, as the B22 pages draw it (`.sec` in the design): 12 dp of air, the hairline, [labelGap] below it,
 * the label, then the content. The air above keeps the rule from sitting on the previous row's own rule.
 *
 * [DapGroup] is the same idea with the gap the older pages use (12 dp under the rule); this is the one for the pages
 * the design draws with a taller gap (28 dp on Audio, 24 dp on the equalizer).
 */
@Composable
fun DapRuledSection(
    title: String,
    modifier: Modifier = Modifier,
    labelGap: androidx.compose.ui.unit.Dp = 28.dp,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = DapSpacing.Md)) {
        DapDivider()
        Spacer(Modifier.height(labelGap))
        DapSectionHeader(title, trailing = trailing)
        content()
    }
}

/**
 * A group: a hairline above it, a section header, then its rows.
 *
 * "Section headers gain a hairline above them so the page reads as a panel of labelled groups."
 */
@Composable
fun DapGroup(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        DapDivider()
        Spacer(Modifier.height(DapSpacing.Md))
        DapSectionHeader(title, trailing = trailing)
        content()
    }
}

/**
 * A settings row: a label, an optional explanatory line under it, and an optional trailing value.
 *
 * **[description] and [value] are not the same slot**, and the difference is typographic, not
 * cosmetic:
 *
 * - [description] is **prose**. It explains what the row does or where it leads, it reads as a
 *   sentence, and it is set in Manrope ([DapType.Caption]).
 * - [value] is a **measurement or a state token**. It is a number, a rate, a count, an identifier —
 *   something the system observed rather than something a person wrote — and it is set in mono
 *   ([DapType.Value]).
 *
 * The Settings root used to pass category subtitles through [value], because that was the only
 * trailing slot available. The result was explanatory prose rendered in JetBrains Mono, which reads
 * as a technical reading on a row that is not reporting one. The slot exists so that a caller never
 * has to make that choice.
 *
 * The label and the trailing value are laid out so they can never collide: the value takes the
 * space it needs and the label ellipsises against it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DapSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    description: String? = null,
    valueStyle: androidx.compose.ui.text.TextStyle = DapType.Value,
    valueColor: Color = Dap.primaryText,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = DapHeights.SettingsRow,
) {
    DapRowShell(
        modifier = modifier,
        onClick = onClick,
        showChevron = showChevron,
        minHeight = minHeight,
        dim = dim,
    ) {
        val labelColor = if (dim) Dap.tertiaryText else Dap.primaryText
        if (description.isNullOrBlank()) {
            Text(
                text = label,
                style = DapType.SettingLabel,
                color = labelColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            // One FlowRow item, so the description stays attached to its label rather than being
            // flowed into a column of its own beside it.
            Column {
                Text(
                    text = label,
                    style = DapType.SettingLabel,
                    color = labelColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = DapType.Caption,
                    color = if (dim) Dap.tertiaryText else Dap.secondaryText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = valueStyle,
                color = valueColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A technical value row: a descriptive label on the left, a mono value on the right, 40 dp tall.
 *
 * This is the row Track Details, DAC Profile and the profile screens are built from.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DapValueRow(
    label: String,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    value: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = DapHeights.ValueRow)
                .padding(vertical = DapSpacing.Sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
        ) {
            Text(
                text = label,
                style = DapType.Body,
                color = Dap.secondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null) {
                value()
            }
        }
        if (divider) DapDivider()
    }
}

/** A mono technical value. Every value the system observed is set in mono. */
@Composable
fun DapValue(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Dap.primaryText,
    style: androidx.compose.ui.text.TextStyle = DapType.Value,
    maxLines: Int = 2,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A footnote under a group. Note 12, text/3. */
@Composable
fun DapNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = DapType.Note,
        color = Dap.tertiaryText,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = DapSpacing.Sm),
    )
}

/**
 * The shared shell for a tappable row.
 *
 * Guarantees a touch target of at least 48 dp and keeps the chevron out of the label's way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DapRowShell(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    showChevron: Boolean,
    minHeight: androidx.compose.ui.unit.Dp,
    dim: Boolean,
    content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = minHeight)
    val clickable = if (onClick != null) base.clickableRow(onClick) else base
    // The chevron is a sibling of the flowing content, not one of its items. As a FlowRow item it
    // was placed by the flow, so a label that wrapped to two lines pushed it onto a line of its
    // own — where SpaceBetween left it stranded at the left margin instead of at the trailing edge.
    Row(
        modifier = clickable
            .then(if (dim) Modifier.alpha(0.4f) else Modifier)
            .padding(vertical = DapSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
        if (showChevron) DapChevron()
    }
}

/** The row trailing chevron: a thin 8x14 stroke, text/4, pointing forward. */
@Composable
fun DapChevron(color: Color = Dap.disabledText, modifier: Modifier = Modifier) {
    // ChevronRight, not Chevron. This is a disclosure affordance — it says "there is more this
    // way" — and the back glyph pointed the user at the edge they had just come from.
    DapIcon(DapIconKind.ChevronRight, tint = color, size = 14.dp, modifier = modifier)
}

/** A tappable row modifier matching the appliance tone. */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/**
 * The placeholder artwork surface.
 *
 * One placeholder, bg/2 with a quiet glyph, is the "no artwork" answer. The diagonal-hatch variant
 * is the in-design stand-in used wherever a real cover is not available yet.
 */
@Composable
fun DapArtworkPlaceholder(
    modifier: Modifier = Modifier,
    showGlyph: Boolean = true,
    glyphTint: Color = Dap.disabledText,
) {
    Box(
        modifier.background(Dap.surfaceRaised, androidx.compose.foundation.shape.RoundedCornerShape(DapRadius.Surface)),
        contentAlignment = Alignment.Center,
    ) {
        if (showGlyph) {
            DapIcon(DapIconKind.Note, tint = glyphTint, size = 14.dp)
        }
    }
}
