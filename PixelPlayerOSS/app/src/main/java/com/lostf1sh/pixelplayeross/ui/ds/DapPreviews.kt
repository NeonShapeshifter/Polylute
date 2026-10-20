package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.lostf1sh.pixelplayeross.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Visual QA for the design system across the widths and font scales the product must survive.
 *
 * These are deliberately primitive-level: the real screens are ViewModel-backed and cannot render
 * in a preview, so the previews exercise the shared pieces they are built from — which is also
 * where a layout failure shows up first. Long hostile strings are used on purpose.
 *
 * Font scaling is applied per preview via [Preview.fontScale] where it matters, because wrapping is
 * a function of both available width and text size.
 */
private val PREVIEW_WIDTHS = listOf(320, 360, 390, 411, 480, 600)

private const val LONG_TITLE =
    "This Is An Extremely Long Song Title Intended To Break The Layout"
private const val LONG_ARTIST = "An Artist With An Unnecessarily Long Display Name"
private const val LONG_DAC = "Qudelix-5K High Resolution USB Audio Device"
private const val LONG_HEADPHONE = "Sennheiser HD 660S2 Reference Headphone Profile"

/** The row every list in the product is made of, at every width, with hostile content. */
@Preview(name = "Row · 320", widthDp = 320, heightDp = 220, showBackground = true)
@Preview(name = "Row · 360", widthDp = 360, heightDp = 220, showBackground = true)
@Preview(name = "Row · 390", widthDp = 390, heightDp = 220, showBackground = true)
@Preview(name = "Row · 411", widthDp = 411, heightDp = 220, showBackground = true)
@Preview(name = "Row · 480", widthDp = 480, heightDp = 220, showBackground = true)
@Preview(name = "Row · 600", widthDp = 600, heightDp = 220, showBackground = true)
@Composable
private fun PreviewMediaRow() {
    DapTheme {
        Column(Modifier.fillMaxWidth().background(Dap.background).padding(DapSpacing.Gutter)) {
            DapMediaRow(
                title = LONG_TITLE,
                subtitle = LONG_ARTIST,
                duration = "3:04",
                leading = { ArtSwatch(DapSizes.ThumbRow) },
            )
            DapMediaRow(
                title = "Current track",
                subtitle = LONG_ARTIST,
                duration = "12:04",
                state = DapRowState.Current,
                leading = { ArtSwatch(DapSizes.ThumbRow) },
            )
            DapMediaRow(title = "Loading row", state = DapRowState.Loading)
            DapMediaRow(
                title = "Unavailable row",
                subtitle = "Missing file",
                duration = "0:00",
                state = DapRowState.Unavailable,
                leading = { ArtSwatch(DapSizes.ThumbRow) },
            )
        }
    }
}

/** Technical rows: the label/value pair must wrap rather than collide. */
@Preview(name = "Value rows · 320", widthDp = 320, heightDp = 320, showBackground = true)
@Preview(name = "Value rows · 360", widthDp = 360, heightDp = 320, showBackground = true)
@Preview(name = "Value rows · 411", widthDp = 411, heightDp = 320, showBackground = true)
@Preview(name = "Value rows · 600", widthDp = 600, heightDp = 320, showBackground = true)
@Composable
private fun PreviewValueRows() {
    DapTheme {
        Column(Modifier.fillMaxWidth().background(Dap.background).padding(DapSpacing.Gutter)) {
            DapGroup(title = "OUTPUT") {
                Spacer(Modifier.height(DapSpacing.Md))
                DapValueRow(label = "Route") {
                    DapState(kind = DapStateKind.Active, label = "USB Direct · $LONG_DAC")
                }
                DapValueRow(label = "Output rate") {
                    DapState(kind = DapStateKind.Confirmed, label = "44.1 kHz")
                }
                DapValueRow(label = "Device mixer rate") {
                    DapValue(text = "48 kHz · device property", color = Dap.tertiaryText)
                }
                DapValueRow(label = "Sample rate", divider = false) {
                    DapValue("96 kHz")
                }
            }
        }
    }
}

/** Settings rows, including the longest device and profile names the product can produce. */
@Preview(name = "Settings · 320", widthDp = 320, heightDp = 360, showBackground = true)
@Preview(name = "Settings · 360", widthDp = 360, heightDp = 360, showBackground = true)
@Preview(name = "Settings · 411", widthDp = 411, heightDp = 360, showBackground = true)
@Preview(name = "Settings · 600", widthDp = 600, heightDp = 360, showBackground = true)
@Composable
private fun PreviewSettingsRows() {
    DapTheme {
        Column(Modifier.fillMaxWidth().background(Dap.background).padding(DapSpacing.Gutter)) {
            DapGroup(title = "LISTENING") {
                DapSettingRow(
                    label = "Headphones",
                    value = LONG_HEADPHONE,
                    valueStyle = DapType.CaptionMedium,
                    valueColor = Dap.secondaryText,
                    showChevron = true,
                    onClick = {},
                )
                DapDivider()
                DapSettingRow(
                    label = "DACs",
                    value = "$LONG_DAC · 2 known",
                    valueStyle = DapType.CaptionMedium,
                    valueColor = Dap.secondaryText,
                    showChevron = true,
                    onClick = {},
                )
            }
            Spacer(Modifier.height(DapSpacing.SectionGap))
            DapGroup(title = "PLAYBACK") {
                DapSettingRow(label = "Resume on launch", value = null, onClick = {})
                DapDivider()
                DapSettingRow(label = "Crossfade", value = "Off", valueStyle = DapType.CaptionMedium, valueColor = Dap.secondaryText)
            }
        }
    }
}

/** The EQ editor at both real band counts. The 15-band table is the tight one. */
@Preview(name = "EQ 10-band · 320", widthDp = 320, heightDp = 300, showBackground = true)
@Preview(name = "EQ 15-band · 320", widthDp = 320, heightDp = 300, showBackground = true)
@Preview(name = "EQ 15-band · 360", widthDp = 360, heightDp = 300, showBackground = true)
@Preview(name = "EQ 15-band · 411", widthDp = 411, heightDp = 300, showBackground = true)
@Composable
private fun PreviewEqEditor() {
    val tenLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val fifteenLabels = listOf(
        "25", "40", "63", "100", "160", "250", "400", "630", "1k",
        "1.6k", "2.5k", "4k", "6.3k", "10k", "16k",
    )
    DapTheme {
        Column(Modifier.fillMaxWidth().background(Dap.background).padding(DapSpacing.Gutter)) {
            DapEqEditor(
                gains = List(15) { (it % 7) - 3 },
                labels = fifteenLabels,
                selectedIndex = 4,
                summaryDescription = "EQ band summary",
            )
            Spacer(Modifier.height(DapSpacing.SectionGap))
            DapEqEditor(
                gains = List(10) { (it % 5) - 2 },
                labels = tenLabels,
                summaryDescription = "EQ band summary",
            )
        }
    }
}

/** The transport and the player header, which carry the product's identity. */
@Preview(name = "Transport · 320", widthDp = 320, heightDp = 260, showBackground = true)
@Preview(name = "Transport · 600", widthDp = 600, heightDp = 260, showBackground = true)
@Composable
private fun PreviewPlayerChrome() {
    DapTheme {
        Column(Modifier.fillMaxWidth().background(Dap.background)) {
            DapPlayerHeader(
                lyricsActive = false,
                onCollapse = {},
                onLyrics = {},
                onDetails = {},
            )
            Row(
                Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DapIcon(DapIconKind.SkipPrevious, tint = Dap.primaryText, size = 22.dp)
                Spacer(Modifier.size(14.dp))
                Box(
                    Modifier.size(DapSizes.PlayButton).clip(RoundedCornerShape(DapRadius.Full)).background(Dap.primaryText),
                    contentAlignment = Alignment.Center,
                ) { DapIcon(DapIconKind.Play, tint = Dap.onFill, size = 22.dp) }
                Spacer(Modifier.size(14.dp))
                DapIcon(DapIconKind.SkipNext, tint = Dap.primaryText, size = 22.dp)
            }
            DapSignalReadout(
                source = "FLAC 44.1k/16",
                output = LONG_DAC,
                rate = "44.1k",
                rateState = DapStateKind.Confirmed,
            )
        }
    }
}

/** The state vocabulary, which must stay legible when a word is long. */
@Preview(name = "States · 320", widthDp = 320, heightDp = 320, showBackground = true)
@Composable
private fun PreviewStates() {
    DapTheme {
        Column(
            Modifier.fillMaxWidth().background(Dap.background).padding(DapSpacing.Gutter),
            verticalArrangement = Arrangement.spacedBy(DapSpacing.Md),
        ) {
            DapState(DapStateKind.Active, "ACTIVE")
            DapState(DapStateKind.Confirmed, "CONFIRMED · 44.1 kHz")
            DapState(DapStateKind.Connected, "CONNECTED · IDLE")
            DapState(DapStateKind.Bypassed, "BYPASSED · USB DIRECT")
            DapState(DapStateKind.Off, "OFF")
            DapState(DapStateKind.NotConnected, "NOT CONNECTED")
            DapState(DapStateKind.Attention, "REQUESTED, NOT APPLIED")
        }
    }
}

/** Font-scale previews: the same content at the four scales the product must survive. */
@Preview(name = "Row · 360 @1.15", widthDp = 360, heightDp = 260, fontScale = 1.15f, showBackground = true)
@Preview(name = "Row · 360 @1.30", widthDp = 360, heightDp = 260, fontScale = 1.30f, showBackground = true)
@Preview(name = "Row · 360 @1.50", widthDp = 360, heightDp = 300, fontScale = 1.50f, showBackground = true)
@Preview(name = "Row · 320 @1.50", widthDp = 320, heightDp = 300, fontScale = 1.50f, showBackground = true)
@Composable
private fun PreviewFontScaleRows() {
    DapTheme {
        Column(Modifier.fillMaxWidth().background(Dap.background).padding(DapSpacing.Gutter)) {
            DapMediaRow(
                title = LONG_TITLE,
                subtitle = LONG_ARTIST,
                duration = "3:04",
                leading = { ArtSwatch(DapSizes.ThumbRow) },
            )
            DapValueRow(label = "Headphone profile") {
                DapValue(LONG_HEADPHONE, color = Dap.secondaryText)
            }
            DapSettingRow(
                label = "DACs",
                value = LONG_DAC,
                valueStyle = DapType.CaptionMedium,
                valueColor = Dap.secondaryText,
                showChevron = true,
                onClick = {},
            )
        }
    }
}

/** The mini-player and nav bar, which every screen shares. */
@Preview(name = "Chrome · 320", widthDp = 320, heightDp = 200, showBackground = true)
@Preview(name = "Chrome · 360", widthDp = 360, heightDp = 200, showBackground = true)
@Preview(name = "Chrome · 600", widthDp = 600, heightDp = 200, showBackground = true)
@Composable
private fun PreviewBottomChrome() {
    DapTheme {
        Column(Modifier.fillMaxWidth().background(Dap.background)) {
            DapMiniPlayer(
                title = LONG_TITLE,
                artist = LONG_ARTIST,
                isPlaying = true,
                onPlayPause = {},
                onNext = {},
                onOpen = {},
                artwork = { ArtSwatch(DapSizes.ThumbRow) },
            )
            DapBottomNav(
                items = listOf(
                    DapNavItem(R.string.tab_home, DapIconKind.Home),
                    DapNavItem(R.string.search, DapIconKind.Search),
                    DapNavItem(R.string.tab_library, DapIconKind.Library),
                ),
                selectedIndex = 0,
                onSelect = {},
            )
        }
    }
}

@Preview(name = "Icons · 360", widthDp = 360, heightDp = 320, showBackground = true)
@Composable
private fun PreviewIcons() {
    DapTheme {
        Column(
            Modifier.fillMaxWidth().background(Dap.background).padding(DapSpacing.Gutter),
            verticalArrangement = Arrangement.spacedBy(DapSpacing.Lg),
        ) {
            DapIconKind.entries.chunked(7).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DapSpacing.Lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.forEach { kind ->
                        DapIconBox(kind = kind, tint = Dap.secondaryText, iconSize = 20.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtSwatch(size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(DapRadius.Surface))
            .background(Dap.artPlaceholderHigh)
    ) {
        Text(
            text = "",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
