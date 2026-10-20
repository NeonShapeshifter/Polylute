package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The buttons that carry a state: shuffle, repeat, lyrics (B22).
 *
 * One rule, drawn the same way on the player, the lock screen and the queue: **active = the accent, plus a 6 dp dot
 * under the glyph.** The dot is what survives without colour, and what tells "on" from "pressed" at a glance. Off is the
 * ordinary glyph colour.
 *
 * The glyph is passed in as a lambda that receives the tint, so a button can carry a DS glyph ([DapIcon]) or a Material
 * one without this knowing which.
 */

/** The dot under an active state button. */
private val StateDot = DapSizes.StateMark

/**
 * The flat state button: a 48 dp touch target and no container. Shuffle, repeat and lyrics on the player's own surface
 * and on the lock screen.
 *
 * [stateDescription] is what a screen reader says after the name ("Aleatorio, activado").
 */
@Composable
fun DapStateIconButton(
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    enabled: Boolean = true,
    icon: @Composable (tint: Color) -> Unit,
) {
    val accent = LocalDapAccent.current
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState().value
    val overlay by animateColorAsState(
        targetValue = if (pressed && enabled) Dap.primaryText.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = DapMotion.micro(entering = pressed),
        label = "DapStateIconButton",
    )
    Box(
        modifier = modifier
            .size(DapSizes.MinTouchTarget)
            .clip(CircleShape)
            .background(overlay)
            .semantics {
                this.contentDescription = contentDescription
                if (stateDescription != null) this.stateDescription = stateDescription
                this.selected = active
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        icon(if (!enabled) Dap.disabledText else if (active) accent else Dap.primaryText)
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(StateDot)
                    .background(accent, CircleShape)
            )
        }
    }
}

/**
 * The tonal round button: the same 48 dp tonal circle the Library floats its Shuffle and Locate buttons on
 * (`FilledTonalIconButton`, `Dap.surfaceRaised`), at the size the design uses on the queue and the lyrics bar (56 dp).
 *
 * Any new floating or toolbar disc copies this, so the app has one tonal disc and not one per screen. [active] is the
 * same accent-and-dot rule as [DapStateIconButton]; [size] is the only thing a call site may change.
 */
@Composable
fun DapTonalRoundButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    active: Boolean = false,
    stateDescription: String? = null,
    enabled: Boolean = true,
    icon: @Composable (tint: Color) -> Unit,
) {
    val accent = LocalDapAccent.current
    val tint = if (!enabled) Dap.disabledText else if (active) accent else Dap.primaryText
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            // The library's own colours: the theme's secondary container (`Dap.surfaceRaised`), with the glyph tinted here.
            colors = IconButtonDefaults.filledTonalIconButtonColors(),
            modifier = Modifier
                .size(size)
                .semantics {
                    this.contentDescription = contentDescription
                    if (stateDescription != null) this.stateDescription = stateDescription
                    this.selected = active
                },
        ) {
            icon(tint)
        }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 9.dp)
                    .size(StateDot)
                    .background(accent, CircleShape)
            )
        }
    }
}
