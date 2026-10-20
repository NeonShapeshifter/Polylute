package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * How a status line is drawn. B22: one vocabulary for every row that says what is applied to the sound.
 *
 * - [Applied]: a filled accent mark and an accent word. Accent means applied, and nothing else.
 * - [Muted]: a hollow mark and a quiet word: frozen, bypassed, or someone else's profile. Something exists and is not
 *   reaching the signal.
 * - [Plain]: no mark, a quiet word. Off, or a state that is simply a fact ("Con fundido cruzado").
 */
enum class DapStatusTone { Applied, Muted, Plain }

/**
 * `● Se aplica`, `○ Congelado · Pure Direct`, `Desactivado`, with an optional `Ruta Android` tag.
 *
 * Mono, one line, never wrapped: the row it ends is the one that gives up width, not this. The words come from the
 * caller (they are string resources resolved from `AudioStatus`); this only draws them, so no screen can print a
 * status in a voice of its own.
 *
 * [routeTag] is the Android-route tag that follows an applied word ("it works, and it leaves USB Direct").
 */
@Composable
fun DapStatusLine(
    text: String,
    tone: DapStatusTone,
    modifier: Modifier = Modifier,
    routeTag: String? = null,
) {
    val accent = LocalDapAccent.current
    val wordColor = if (tone == DapStatusTone.Applied) accent else Dap.secondaryText
    val ringColor = Dap.tertiaryText
    Row(
        // One node that reads "Applied, Android route": the word and its tag are one statement, and the text stays in
        // the tree so a test (or a screen reader) finds the word itself.
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (tone) {
            DapStatusTone.Applied -> Box(
                Modifier
                    .size(DapSizes.StateMark)
                    .background(accent, CircleShape)
            )
            DapStatusTone.Muted -> Box(
                Modifier
                    .size(DapSizes.StateMark)
                    .border(1.5.dp, ringColor, CircleShape)
            )
            DapStatusTone.Plain -> Unit
        }
        if (tone != DapStatusTone.Plain) Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = DapType.Readout,
            color = wordColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (routeTag != null) {
            Spacer(Modifier.width(DapSpacing.Md))
            Icon(
                imageVector = Icons.Rounded.AltRoute,
                contentDescription = null,
                tint = Dap.secondaryText,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(DapSpacing.Xs))
            Text(
                text = routeTag,
                style = DapType.Readout,
                color = Dap.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The output line: where the sound is going, as one mono line with a mark in front.
 *
 * A filled mark means the signal reaches the DAC untouched; a hollow one means something processes it. Used only
 * where the output line lives (the DAP lock screen's footer): it ends in an ellipsis rather than being clipped, and
 * it gives up width to whatever sits beside it (the battery).
 */
@Composable
fun DapOutputLine(
    text: String,
    untouched: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Dap.secondaryText,
) {
    // B31 P3.1: the app's accent, not the cover's: this mark is a fact about the signal and does not change with the song.
    val accent = LocalDapAppAccent.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (untouched) {
            Box(Modifier.size(DapSizes.StateMark).background(accent, CircleShape))
        } else {
            Box(Modifier.size(DapSizes.StateMark).border(1.5.dp, Dap.tertiaryText, CircleShape))
        }
        Spacer(Modifier.width(DapSpacing.Sm))
        Text(
            text = text,
            style = DapType.Readout,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
