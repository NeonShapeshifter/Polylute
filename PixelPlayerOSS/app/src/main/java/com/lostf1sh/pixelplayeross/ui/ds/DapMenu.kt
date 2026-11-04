package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * B56 (#42): the one overflow menu of the app. Every "⋮" opens this, nothing else (`DropdownMenu(` outside `ui/ds` fails a test).
 *
 * What the owner asked for, by hand (2026-11-02): Pixel-style, a rounded and soft container (not square), the tonal colour of the chosen accent in light and dark,
 * a pill-shaped highlight on the option being pressed, a margin from the screen edge, and the menu opening **under its button**.
 *
 * - **Where:** under the parent of the call (the `Box` around the button), right-aligned to the button when the button is in the right half of the window and
 *   left-aligned when it is in the left half, never closer to the window's edge than [DapMenuDefaults.EdgeMargin] ([DapMenuPlacement]); above the button only when
 *   there is no room below.
 * - **Colour:** the raised surface with the listener's accent over it at [DapMenuDefaults.TintAlpha]; a pressed option is the accent at [DapMenuDefaults.PressedAlpha]
 *   over that. Text is the palette's primary text; nothing here is a colour of its own.
 * - **Behaviour:** a focusable popup, so a tap outside and system Back dismiss it ([onDismissRequest]); it fades and scales in from the button's corner.
 */
@Composable
fun DapMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded
    if (!visible.currentState && !visible.targetState) return
    val edge = with(LocalDensity.current) { DapMenuDefaults.EdgeMargin.roundToPx() }
    val placement = remember(edge) { DapMenuPositionProvider(edge) }
    Popup(
        popupPositionProvider = placement,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(DapMenuDefaults.EnterMs)) + scaleIn(tween(DapMenuDefaults.EnterMs), initialScale = 0.92f, transformOrigin = TransformOrigin(0.9f, 0f)),
            exit = fadeOut(tween(DapMenuDefaults.ExitMs)) + scaleOut(tween(DapMenuDefaults.ExitMs), targetScale = 0.96f, transformOrigin = TransformOrigin(0.9f, 0f)),
        ) {
            DapMenuSurface(modifier, content)
        }
    }
}

/** The menu's container, without the popup around it: what the window shows, so a render can place it under a button without a window of its own. */
@Composable
internal fun DapMenuSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(DapRadius.Menu)
    Column(
        modifier
            .shadow(DapMenuDefaults.Elevation, shape, clip = false)
            .clip(shape)
            .background(dapMenuContainer())
            .widthIn(min = DapMenuDefaults.MinWidth, max = DapMenuDefaults.MaxWidth)
            .width(IntrinsicSize.Max)
            .padding(vertical = DapSpacing.Sm)
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

/**
 * One option: a 48 dp row; while it is pressed a pill of the accent's tone lies behind it, edge to edge of the menu minus [DapSpacing.Sm].
 * [leadingIcon] is drawn in the secondary text colour in a 24 dp box.
 */
@Composable
fun DapMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pill = RoundedCornerShape(DapRadius.Full)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DapSpacing.Sm)
            .heightIn(min = DapHeights.SettingsRow)
            .clip(pill)
            .background(if (pressed && enabled) dapMenuPressed() else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DapSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
    ) {
        if (leadingIcon != null) {
            Box(Modifier.size(DapMenuDefaults.IconBox), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides (if (enabled) Dap.secondaryText else Dap.disabledText)) { leadingIcon() }
            }
        }
        Text(
            text = text,
            style = DapType.RowSmall,
            color = if (enabled) Dap.primaryText else Dap.disabledText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

object DapMenuDefaults {
    /** The least gap between the menu and the window's edge. */
    val EdgeMargin: Dp = DapSpacing.Md
    val MinWidth: Dp = 200.dp
    val MaxWidth: Dp = 300.dp
    val Elevation: Dp = 6.dp
    val IconBox: Dp = 24.dp

    /** How much of the accent the container carries over the raised surface, and the pressed pill over that. */
    const val TintAlpha = 0.16f
    const val PressedAlpha = 0.24f
    const val EnterMs = 140
    const val ExitMs = 90
}

/** The menu's fill: the raised surface (made opaque over the glass) with the accent's tone over it. */
@Composable
internal fun dapMenuContainer(): Color {
    val raised = Dap.surfaceRaised
    val opaque = if (raised.alpha < 1f) raised.compositeOver(if (Dap.isLight) Color.White else Color.Black) else raised
    return LocalDapAccent.current.copy(alpha = DapMenuDefaults.TintAlpha).compositeOver(opaque)
}

@Composable
internal fun dapMenuPressed(): Color = LocalDapAccent.current.copy(alpha = DapMenuDefaults.PressedAlpha).compositeOver(dapMenuContainer())

/** The platform's way of asking where a popup goes, answered by [DapMenuPlacement]. */
internal class DapMenuPositionProvider(private val edgePx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = DapMenuPlacement.place(anchorBounds, windowSize, popupContentSize, edgePx)
}

/** Where the menu goes, as numbers (pixels in the window): under the button, on the button's side of the window, [edge] px from every edge at the least. */
internal object DapMenuPlacement {
    fun place(anchor: IntRect, window: IntSize, content: IntSize, edge: Int): IntOffset {
        val inRightHalf = anchor.center.x > window.width / 2
        val preferredX = if (inRightHalf) anchor.right - content.width else anchor.left
        val maxX = (window.width - content.width - edge).coerceAtLeast(edge)
        val x = preferredX.coerceIn(edge, maxX)

        val below = anchor.bottom
        val fitsBelow = below + content.height <= window.height - edge
        val above = anchor.top - content.height
        val y = when {
            fitsBelow -> below
            above >= edge -> above
            else -> below.coerceIn(edge, (window.height - content.height - edge).coerceAtLeast(edge))
        }
        return IntOffset(x, y)
    }
}
