package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R

/**
 * Regression cover for the Lyrics family.
 *
 * These exist because the family's defects were all narrow-width and large-font defects that a
 * compile could not catch: the player's own tab labels collapsed to `ART…` at 1.5 (B22: the tabs are icons now), and several
 * fixed-geometry controls clipped their last glyph. So the widths step down to 320 dp and the font
 * scale steps up to 1.5, rather than duplicating every state at every combination.
 *
 * What each covers:
 * - [PreviewLyricsStatusStates] — the three in-page states at the narrowest width that matters and
 *   the largest font scale, where wrapping and the action row are most likely to break.
 * - [PreviewLyricsStatusWide] — the same states at tablet width, where centred prose can become
 *   unreadably wide if the copy is not constrained.
 */

@Preview(name = "Lyrics states · 320 @1.0", widthDp = 320, heightDp = 420, showBackground = true)
@Preview(name = "Lyrics states · 320 @1.5", widthDp = 320, heightDp = 420, fontScale = 1.5f, showBackground = true)
@Composable
private fun PreviewLyricsStatusStates() {
    DapPreviewSurface {
        LyricsStatusPreviewColumn(compact = true)
    }
}

@Preview(name = "Lyrics states · 360 @1.3", widthDp = 360, heightDp = 420, fontScale = 1.3f, showBackground = true)
@Preview(name = "Lyrics states · 600 @1.0", widthDp = 600, heightDp = 420, showBackground = true)
@Composable
private fun PreviewLyricsStatusWide() {
    DapPreviewSurface {
        LyricsStatusPreviewColumn(compact = false)
    }
}

@Composable
private fun LyricsStatusPreviewColumn(compact: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(DapSpacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(DapSpacing.SectionGap),
    ) {
        // No lyrics — the state the picker no longer forces open.
        LyricsStateBlock(
            title = stringResource(R.string.dap_lyrics_empty_title),
            message = stringResource(R.string.dap_lyrics_empty_message),
            action = {
                DapSecondaryButton(
                    label = stringResource(R.string.dap_lyrics_search_online),
                    onClick = {},
                )
                DapTextAction(
                    label = stringResource(R.string.import_file),
                    onClick = {},
                )
            },
            compact = compact,
        )
        LyricsStateBlock(
            title = stringResource(R.string.dap_lyrics_error_title),
            message = stringResource(R.string.failed_to_fetch_lyrics_from_remote),
            action = {
                DapTextAction(label = stringResource(R.string.ok), onClick = {})
            },
            compact = compact,
        )
    }
}

@Composable
private fun LyricsStateBlock(
    title: String,
    message: String,
    action: @Composable () -> Unit,
    compact: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = DapType.SectionHeader,
            color = Dap.tertiaryText,
        )
        Spacer(Modifier.height(DapSpacing.Sm))
        Text(
            text = message,
            style = DapType.Body,
            color = Dap.secondaryText,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(DapSpacing.Md))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { action() }
    }
}

/** The preview ground: bg/0 under the DAP theme, matching how the sheets render. */
@Composable
private fun DapPreviewSurface(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme {
        Box(Modifier.fillMaxSize().background(Dap.background)) { content() }
    }
}
