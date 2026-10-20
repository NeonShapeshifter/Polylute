@file:kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularWavyProgressIndicator
import com.lostf1sh.pixelplayeross.presentation.components.player.MorphingPlayPauseIcon
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import coil.size.Size
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapHeights
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.theme.RoundedSans
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel

// Not static (AUD-113): a static local recomposes the whole subtree under its provider whenever the
// value changes, readers or not, and this value changes with every cover — the retained queue of 60
// rows included. A dynamic one invalidates only what reads `.current`.
internal val LocalMaterialTheme = compositionLocalOf<ColorScheme> { error("No ColorScheme provided") }

/** One measured height shared by content, motion and all list reservations. */
val LocalMiniPlayerHeight = compositionLocalOf { 65.dp }
val MiniPlayerHeight: Dp
    @Composable get() = LocalMiniPlayerHeight.current

@Composable
fun rememberMiniPlayerHeight(song: Song?, preparing: Boolean, availableWidth: Dp): Dp {
    val title = when {
        preparing -> stringResource(R.string.player_preparing_playback)
        song != null -> songTitleLabel(song)
        else -> ""
    }
    val artist = if (preparing) stringResource(R.string.player_loading_audio) else song?.let { songArtistLabel(it) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val titleStyle = DapType.MediaRow.copy(fontWeight = FontWeight.SemiBold)
    val artistStyle = DapType.Note
    return remember(title, artist, availableWidth, density, measurer, titleStyle, artistStyle) {
        val textWidth = with(density) {
            (availableWidth - DapSpacing.Md * 2 - DapSizes.ThumbRow - DapSpacing.Md -
                DapSpacing.Sm - DapSizes.MinTouchTarget * 3)
                .coerceAtLeast(1.dp).roundToPx()
        }
        val constraints = Constraints(maxWidth = textWidth)
        val titleHeight = measurer.measure(title, titleStyle, constraints = constraints,
            maxLines = 1, overflow = TextOverflow.Ellipsis).size.height
        val artistHeight = artist?.let { measurer.measure(it, artistStyle, constraints = constraints,
            maxLines = 1, overflow = TextOverflow.Ellipsis).size.height } ?: 0
        with(density) { (titleHeight + artistHeight).toDp() }
            .coerceAtLeast(DapSizes.ThumbRow)
            .coerceAtLeast(DapSizes.MinTouchTarget) + DapSpacing.Sm * 2 + 1.dp
    }
}
const val ANIMATION_DURATION_MS = 140
val MiniPlayerBottomSpacer = 8.dp

@Composable
fun getNavigationBarHeight(): Dp {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    return sanitizeNavigationBarBottomInset(insets.calculateBottomPadding())
}

/**
 * The bottom player shelf.
 *
 * Docked, not floating. It is the top edge of the app's chrome: full-bleed, flush with the bottom
 * navigation, one hairline between it and the content above. The previous shape was a 12 dp-inset
 * rounded card with a drop shadow, which read as a widget laid over whatever screen was behind it
 * rather than as part of the device's face.
 *
 * Transport is the three controls the shelf exists to offer \u2014 previous, play/pause, next \u2014 in the
 * conventional order, each with a full 48 dp target.
 */
@Composable
internal fun MiniPlayerContentInternal(
    song: Song,
    isPlaying: Boolean,
    isPreparingPlayback: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep metadata and transport in one row; the expanded player shows the complete title.
    val hapticFeedback = LocalHapticFeedback.current
    val controlsEnabled = !isPreparingPlayback
    val albumArtModel = song.albumArtUriString?.takeIf { it.isNotBlank() }
    val artShape = RoundedCornerShape(DapRadius.Surface)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Dap.surface)
    ) {
        DapDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DapSpacing.Md, vertical = DapSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(DapSizes.ThumbRow)
                    .clip(artShape),
                contentAlignment = Alignment.Center
            ) {
                key(song.id) {
                    SmartImage(
                        model = albumArtModel,
                        contentDescription = null,
                        shape = artShape,
                        targetSize = Size(120, 120),
                        modifier = Modifier.size(DapSizes.ThumbRow)
                    )
                }
                if (isPreparingPlayback) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.width(DapSpacing.Md))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isPreparingPlayback) stringResource(R.string.player_preparing_playback) else songTitleLabel(song),
                    style = DapType.MediaRow.copy(fontWeight = FontWeight.SemiBold),
                    color = Dap.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val artist = if (isPreparingPlayback) stringResource(R.string.player_loading_audio) else songArtistLabel(song)
                artist?.let { Text(it, style = DapType.Note, color = Dap.secondaryText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }

            Spacer(modifier = Modifier.width(DapSpacing.Sm))
            MiniPlayerGlyphButton(
                kind = DapIconKind.SkipPrevious,
                tint = Dap.secondaryText,
                description = stringResource(R.string.previous_track),
                enabled = controlsEnabled,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPrevious()
                }
            )
            // B33-U2: a plain glyph the size and weight of its neighbours, not a filled circle; the touch target is the same 48 dp.
            MiniPlayerGlyphButton(
                kind = if (isPlaying) DapIconKind.PauseMini else DapIconKind.PlaySmall,
                tint = Dap.primaryText,
                description = stringResource(
                    if (isPlaying) R.string.cd_pause else R.string.cd_play
                ),
                enabled = controlsEnabled,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPlayPause()
                }
            )
            MiniPlayerGlyphButton(
                kind = DapIconKind.SkipNextMini,
                tint = Dap.secondaryText,
                description = stringResource(R.string.next_track),
                enabled = controlsEnabled,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNext()
                }
            )
        }
    }
}

@Composable
private fun MiniPlayerGlyphButton(
    kind: DapIconKind,
    tint: androidx.compose.ui.graphics.Color,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(DapSizes.MinTouchTarget)
            .clip(RoundedCornerShape(DapRadius.Surface))
            .semantics { contentDescription = description }
            .clickable(
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center
    ) {
        DapIcon(kind = kind, tint = tint, size = 16.dp)
    }
}
