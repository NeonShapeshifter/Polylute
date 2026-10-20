package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The button set. Five variants and one special case, each with one job:
 *
 * | Variant       | Job                                                        |
 * |---------------|------------------------------------------------------------|
 * | Primary       | The one action a screen or dialog exists for. At most one. |
 * | Secondary     | Any other labelled action that deserves a shape.           |
 * | Text          | Low-weight actions in rows and headers: Rename, Edit, Sort.|
 * | Destructive   | An action that removes something and is meant to.          |
 * | Icon          | A glyph action or toggle: back, overflow, shuffle, queue.  |
 * | Play disc     | The full player's play/pause. The only disc in the product.|
 *
 * Geometry: every variant is at least [DapSizes.MinTouchTarget] tall and wide *as a touch target*.
 * Labelled variants draw a [DapHeights.Control] (44 dp) shape inside that target; the 2 dp above and
 * below are touchable but not painted. Corners are [DapRadius.Surface]. No pills, no FABs.
 *
 * Press feedback is a tonal overlay over [DapMotion.micro] — in at 100 ms, out at 90 ms. There is no
 * ripple: a ripple is a Material voice, and it travels. Disabled is real: the control stops taking
 * input and says so to accessibility services, not just a lower alpha.
 */
enum class DapButtonVariant { Primary, Secondary, Text, Destructive }

/** The glyph size inside a [DapIconButton]. Declared before the inset that is derived from it. */
private val DapIconButtonGlyph = 20.dp

/**
 * How far a [DapIconButton]'s glyph sits inside its touch target. A row that ends in an icon
 * button pads its end by `DapSpacing.Gutter - DapIconButtonEdgeInset`, so the *glyph* lands on the
 * screen gutter while the 48 dp target still extends past it.
 */
val DapIconButtonEdgeInset = (DapSizes.MinTouchTarget - DapIconButtonGlyph) / 2

/** A labelled button. [variant] picks the job; everything else is fixed by the set. */
@Composable
fun DapButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: DapButtonVariant = DapButtonVariant.Secondary,
    enabled: Boolean = true,
    leading: DapIconKind? = null,
) {
    DapButtonImpl(label, onClick, modifier, variant, enabled, leading, forcePressed = false)
}

/**
 * A glyph-only action with a full touch target.
 *
 * [contentDescription] is required: the glyph is never the only name a control has. [selected]
 * is for a toggle that is on (shuffle, repeat, favourite): it takes the accent, because accent
 * means applied.
 */
@Composable
fun DapIconButton(
    kind: DapIconKind,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    DapIconButtonImpl(kind, contentDescription, onClick, modifier, enabled, selected, forcePressed = false)
}

/**
 * The full player's play/pause. [DapSizes.PlayButton] (72 dp), a filled circle — the one exception
 * to "no discs", because it is the one control the whole product exists to reach.
 *
 * The glyph is passed in so the player keeps its play/pause morph.
 */
@Composable
fun DapPlayDisc(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: @Composable (tint: Color) -> Unit,
) {
    DapPlayDiscImpl(contentDescription, onClick, modifier, enabled, forcePressed = false, glyph = glyph)
}

// --- Implementation ------------------------------------------------------------------------------
// `forcePressed` exists only so the reference sheet can draw the pressed state side by side with
// the others. Production call sites use the public functions above, which never set it.

/** How strongly a press darkens or lightens a surface. */
private const val PressOverlayAlpha = 0.10f

/** The play disc's press: a small no-overshoot scale, the one kept from the audit (PLAYER-12). */
private const val PlayDiscPressedScale = 0.96f

internal data class DapButtonColors(val container: Color, val content: Color)

/** The colour table, in one place. Pressed is computed on top of the resting colours. */
@Composable
internal fun dapButtonColors(variant: DapButtonVariant, enabled: Boolean): DapButtonColors = when {
    !enabled -> when (variant) {
        DapButtonVariant.Text -> DapButtonColors(Color.Transparent, Dap.disabledText)
        else -> DapButtonColors(Dap.surface, Dap.disabledText)
    }
    variant == DapButtonVariant.Primary -> DapButtonColors(Dap.primaryText, Dap.onFill)
    variant == DapButtonVariant.Secondary -> DapButtonColors(Dap.surfaceRaised, Dap.primaryText)
    variant == DapButtonVariant.Destructive -> DapButtonColors(Dap.surfaceRaised, Dap.warning)
    else -> DapButtonColors(Color.Transparent, Dap.secondaryText)
}

/** The resting colour with the press overlay laid over it. */
@Composable
private fun pressedOver(resting: Color, onFilled: Boolean): Color {
    // A light fill darkens under the finger; everything else lightens. Transparent containers take
    // the overlay over the page so a text or icon action still shows where it was touched.
    val ink = if (onFilled) Dap.background else Dap.primaryText
    val base = if (resting == Color.Transparent) Dap.background else resting
    return ink.copy(alpha = PressOverlayAlpha).compositeOver(base)
}

@Composable
internal fun DapButtonImpl(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    variant: DapButtonVariant,
    enabled: Boolean,
    leading: DapIconKind?,
    forcePressed: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = forcePressed || interaction.collectIsPressedAsState().value
    val resting = dapButtonColors(variant, enabled)
    val target = if (pressed && enabled) {
        pressedOver(resting.container, onFilled = variant == DapButtonVariant.Primary)
    } else {
        resting.container
    }
    val container by animateColorAsState(target, DapMotion.micro(entering = pressed), label = "DapButton")

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = DapSizes.MinTouchTarget, minHeight = DapSizes.MinTouchTarget)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(DapHeights.Control)
                .clip(RoundedCornerShape(DapRadius.Surface))
                .background(container)
                .padding(
                    horizontal = if (variant == DapButtonVariant.Text) DapSpacing.Md else DapSpacing.Gutter,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leading != null) {
                DapIcon(leading, tint = resting.content, size = 16.dp)
                Spacer(Modifier.width(DapSpacing.Sm))
            }
            Text(
                text = label,
                style = if (variant == DapButtonVariant.Text) DapType.Action else DapType.Button,
                color = resting.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun DapIconButtonImpl(
    kind: DapIconKind,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    selected: Boolean,
    forcePressed: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = forcePressed || interaction.collectIsPressedAsState().value
    val tint = when {
        !enabled -> Dap.disabledText
        selected -> LocalDapAccent.current
        else -> Dap.secondaryText
    }
    val target = if (pressed && enabled) pressedOver(Color.Transparent, onFilled = false) else Color.Transparent
    val container by animateColorAsState(target, DapMotion.micro(entering = pressed), label = "DapIconButton")

    Box(
        modifier = modifier
            .size(DapSizes.MinTouchTarget)
            .clip(RoundedCornerShape(DapRadius.Surface))
            .background(container)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        DapIcon(kind, tint = tint, size = DapIconButtonGlyph)
    }
}

@Composable
internal fun DapPlayDiscImpl(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    forcePressed: Boolean,
    glyph: @Composable (tint: Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = forcePressed || interaction.collectIsPressedAsState().value
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) PlayDiscPressedScale else 1f,
        animationSpec = DapMotion.micro(entering = pressed),
        label = "DapPlayDisc",
    )
    val container = if (enabled) Dap.primaryText else Dap.surfaceRaised
    val tint = if (enabled) Dap.onFill else Dap.disabledText

    Box(
        modifier = modifier
            .size(DapSizes.PlayButton)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(container)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        glyph(tint)
    }
}
