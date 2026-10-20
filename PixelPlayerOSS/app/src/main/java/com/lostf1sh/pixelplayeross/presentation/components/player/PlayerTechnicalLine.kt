package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapType

/**
 * The technical line under the seek bar: `FLAC · 44.1 kHz · 16-bit`.
 *
 * Its own surface (B21) so the line that says what the file *is* has one home, and so the output line that the
 * redesign may put beside it has somewhere to sit. One line, one fallback.
 */
@Composable
internal fun PlayerTechnicalLine(
    technicalIdentity: SongTechnicalIdentity,
    modifier: Modifier = Modifier,
) {
    // One line, one fallback.
    //
    // The canonical identity is drawn whenever it fits, and the width it is measured
    // against is the container's — which does not depend on what is drawn inside it — so
    // there is no measurement feeding back into its own input. The line is never
    // ellipsised and never scrolled, because a value ending in an ellipsis reads as broken.
    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = 58.dp)
    ) {
        val measurer = rememberTextMeasurer()
        val metaStyle = DapType.ValueSmall.copy(color = Dap.tertiaryText)
        val density = LocalDensity.current
        val availablePx = with(density) { maxWidth.toPx() }.toInt()
        val canonical = technicalIdentity.canonical
        val fallback = technicalIdentity.withoutBitDepth
        val chosen = remember(canonical, fallback, availablePx, density.fontScale) {
            val fits = canonical != null && measurer.measure(
                text = canonical,
                style = metaStyle,
                maxLines = 1
            ).size.width <= availablePx
            chooseTechnicalLine(canonical, fallback, fits)
        }
        Text(
            text = chosen,
            style = metaStyle,
            maxLines = 1,
            softWrap = false
        )
    }
}
