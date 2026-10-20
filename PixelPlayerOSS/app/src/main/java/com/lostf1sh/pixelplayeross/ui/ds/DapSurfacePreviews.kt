package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R

/**
 * Regression cover for the surfaces converted in the final frontend pass.
 *
 * These exist because the defects this product actually shipped were narrow-width and large-font
 * defects that compiled perfectly: tab labels collapsing to `AR…`, a fixed-height controls region
 * pushing its own controls off the sheet, and a 30 dp axis column clipping its last glyph. So each
 * preview steps down to 320 dp or up to 1.5 rather than duplicating every state at every size.
 */

@Preview(name = "Top bar · 320 @1.0", widthDp = 320, heightDp = 120, showBackground = true)
@Preview(name = "Top bar · 320 @1.5", widthDp = 320, heightDp = 120, fontScale = 1.5f, showBackground = true)
@Preview(name = "Top bar · 360 @1.3", widthDp = 360, heightDp = 120, fontScale = 1.3f, showBackground = true)
@Preview(name = "Top bar · 600 @1.0", widthDp = 600, heightDp = 120, showBackground = true)
@Composable
private fun PreviewDapTopBar() {
    Surface {
        Column {
            // The worst case for a bar: a long title next to a trailing action, at the narrowest
            // width and the largest font scale.
            DapTopBar(
                onBack = {},
                actions = { DapTextAction(label = stringResource(R.string.presentation_batch_e_deselect_all), onClick = {}) },
            )
            DapScreenTitle(
                text = stringResource(R.string.duplicates_title),
                modifier = Modifier.padding(horizontal = DapSpacing.Gutter),
            )
        }
    }
}

@Preview(name = "Section header · 320 @1.0", widthDp = 320, heightDp = 90, showBackground = true)
@Preview(name = "Section header · 320 @1.5", widthDp = 320, heightDp = 90, fontScale = 1.5f, showBackground = true)
@Preview(name = "Section header · 411 @1.0", widthDp = 411, heightDp = 90, showBackground = true)
@Composable
private fun PreviewSectionHeader() {
    Surface {
        // The genre-browse heading and the Library filter action, both of which previously rendered
        // as oversized Material titles at these widths.
        Column(Modifier.padding(DapSpacing.Gutter)) {
            Text(
                text = stringResource(R.string.browse_by_genre).uppercase(),
                style = DapType.SectionHeader,
                color = Dap.tertiaryText,
            )
            Spacer(Modifier.height(DapSpacing.Md))
            DapTextAction(
                label = stringResource(R.string.library_storage_filter_all_songs),
                onClick = {},
            )
        }
    }
}

@Preview(name = "Stats card · 320 @1.0", widthDp = 320, heightDp = 220, showBackground = true)
@Preview(name = "Stats card · 320 @1.5", widthDp = 320, heightDp = 220, fontScale = 1.5f, showBackground = true)
@Preview(name = "Stats card · 600 @1.0", widthDp = 600, heightDp = 220, showBackground = true)
@Composable
private fun PreviewStatsCardShape() {
    Surface {
        // The converted Home stats card's shape and hierarchy, at the widths where its big number
        // and its label row are most likely to collide.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DapSpacing.Gutter),
            verticalArrangement = Arrangement.spacedBy(DapSpacing.Lg),
        ) {
            Text(
                text = "128 h 41 min",
                style = DapType.Title,
                color = Dap.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DapSpacing.Lg),
            ) {
                StatLabel(stringResource(R.string.presentation_batch_g_stats_overview_total_plays), Modifier.weight(1f))
                StatLabel(stringResource(R.string.presentation_batch_g_stats_overview_title), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatLabel(label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label.uppercase(),
            style = DapType.SectionHeader,
            color = Dap.tertiaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DapSpacing.Xs))
        Text(text = "1 204", style = DapType.Value, color = Dap.primaryText)
    }
}

/** The preview ground: bg/0 under a Material theme, matching how the surfaces render. */
@Composable
private fun Surface(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Dap.background)) { content() }
    }
}
