package com.lostf1sh.pixelplayeross.ui.ds

import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

/**
 * The one song row.
 *
 * The same row appears in Library, Queue, Search, album detail, playlists and pickers. Its states
 * are fixed by the design: normal, current, selected, no artwork, loading, unavailable.
 */

/** The state of a media row. */
enum class DapRowState { Normal, Current, Loading, Unavailable }

@Composable
fun DapMediaRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    duration: String? = null,
    state: DapRowState = DapRowState.Normal,
    selectionIndex: Int? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    minHeight: Dp = DapHeights.MediaRow,
) {
    val accent = LocalDapAccent.current
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (selectionIndex != null) Modifier.background(Dap.surface) else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .then(
                    if (onClick != null || onLongClick != null) {
                        Modifier.combinedClickableSafe(onClick, onLongClick)
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = DapSpacing.Sm)
                .then(
                    if (state == DapRowState.Unavailable) Modifier.alpha(0.4f) else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(DapSpacing.Md))
            } else if (selectionIndex != null) {
                Box(
                    Modifier
                        .size(DapSizes.ThumbRow)
                        .clip(RoundedCornerShape(DapRadius.Surface))
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = selectionIndex.toString(),
                        style = DapType.Value,
                        color = Dap.onFill,
                    )
                }
                Spacer(Modifier.width(DapSpacing.Md))
            }

            Column(Modifier.weight(1f)) {
                if (state == DapRowState.Loading) {
                    DapSkeletonBar(width = 120.dp, height = 12.dp, color = Dap.surfaceRaised)
                    Spacer(Modifier.height(6.dp))
                    DapSkeletonBar(width = 80.dp, height = 10.dp, color = Dap.surface)
                } else {
                    Text(
                        text = title,
                        style = if (state == DapRowState.Current) {
                            DapType.MediaRow.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        } else {
                            DapType.MediaRow
                        },
                        color = if (state == DapRowState.Current) accent else Dap.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = DapType.Note,
                            color = Dap.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (duration != null) {
                Spacer(Modifier.width(DapSpacing.Md))
                Text(
                    text = duration,
                    style = DapType.ValueSmall,
                    color = Dap.tertiaryText,
                    maxLines = 1,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(DapSpacing.Md))
                trailing()
            }
        }
        if (divider) DapDivider()
    }
}

/** A static skeleton bar. The design's loading state is static bg/2 — no shimmer. */
@Composable
fun DapSkeletonBar(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    color: Color = Dap.surfaceRaised,
) {
    Box(
        modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
    )
}

/** A full-width skeleton row, matching the media row's geometry. */
@Composable
fun DapSkeletonRow(
    modifier: Modifier = Modifier,
    showArt: Boolean = true,
    minHeight: Dp = DapHeights.MediaRow,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .padding(vertical = DapSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showArt) {
                Box(
                    Modifier
                        .size(DapSizes.ThumbRow)
                        .clip(RoundedCornerShape(DapRadius.Surface))
                        .background(Dap.surfaceRaised)
                )
                Spacer(Modifier.width(DapSpacing.Md))
            }
            Column(Modifier.weight(1f)) {
                DapSkeletonBar(120.dp, 12.dp, color = Dap.surfaceRaised)
                Spacer(Modifier.height(6.dp))
                DapSkeletonBar(80.dp, 10.dp, color = Dap.surface)
            }
        }
        DapDivider()
    }
}

/** The album grid cell: square art, title, subtitle. */
@Composable
fun DapAlbumGridItem(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    artwork: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickableSafe(onClick, onLongClick)
                } else {
                    Modifier
                }
            )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(DapRadius.Surface))
        ) {
            if (artwork != null) artwork() else DapArtworkPlaceholder(Modifier.fillMaxWidth().aspectRatio(1f))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = title,
            style = DapType.Note.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = Dap.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = DapType.Note.copy(fontSize = 11.sp),
                color = Dap.tertiaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** An album row for list view: 56 dp art, title, artist. */
@Composable
fun DapAlbumRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    artwork: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = DapHeights.MediaRowTall)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickableSafe(onClick, onLongClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = DapSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(DapSizes.ThumbFeature)
                .clip(RoundedCornerShape(DapRadius.Surface))
        ) {
            if (artwork != null) artwork() else DapArtworkPlaceholder(Modifier.size(DapSizes.ThumbFeature))
        }
        Spacer(Modifier.width(DapSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = DapType.MediaRow,
                color = Dap.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = DapType.Note,
                    color = Dap.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(DapSpacing.Md))
            trailing()
        }
    }
}

/** An artist row: circular avatar, name, count. */
@Composable
fun DapArtistRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    artwork: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = DapHeights.MediaRowTall)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickableSafe(onClick, onLongClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = DapSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(DapSizes.ThumbRow)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Dap.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) artwork() else DapArtworkPlaceholder(
                Modifier.size(DapSizes.ThumbRow).clip(androidx.compose.foundation.shape.CircleShape),
                showGlyph = false,
            )
        }
        Spacer(Modifier.width(DapSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = DapType.MediaRow,
                color = Dap.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = DapType.Note,
                    color = Dap.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A feature row: 52 dp art, title + descriptive line, optional round action. */
@Composable
fun DapFeatureRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    artwork: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = DapSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(DapSizes.ThumbFeature)
                    .clip(RoundedCornerShape(DapRadius.Surface))
            ) {
                if (artwork != null) artwork() else DapArtworkPlaceholder(Modifier.size(DapSizes.ThumbFeature))
            }
            Spacer(Modifier.width(DapSpacing.Lg))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = DapType.RowSmall.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    ),
                    color = Dap.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        style = DapType.Note,
                        color = Dap.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(DapSpacing.Md))
                trailing()
            }
        }
        if (divider) DapDivider()
    }
}

/**
 * The round filled action used on feature rows, queue rows and the Home mix header.
 *
 * [kind] defaults to Play because that is what it was built for and what nearly every call site
 * wants. It is a parameter so the same 44 dp round shape can carry a different glyph — the Home
 * mix header offers shuffle, and drawing a second, near-identical round action just to change one
 * vector would be two primitives where one shape is meant.
 */
@Composable
fun DapRoundPlayAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = DapSizes.PlayButtonSmall,
    background: Color = Dap.primaryText,
    tint: Color = Dap.onFill,
    kind: DapIconKind = DapIconKind.Play,
    iconSize: Dp = 12.dp,
    /** Required for a glyph that is not self-evident; the icon alone must not be the only label. */
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        DapIcon(kind, tint = tint, size = iconSize)
    }
}

/** A "N more" affordance used under a truncated search group. */
@Composable
fun DapMoreRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = DapType.CaptionMedium, color = Dap.tertiaryText, maxLines = 1)
    }
}

private fun Modifier.combinedClickableSafe(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
): Modifier = this.combinedClickable(
    onClick = { onClick?.invoke() },
    onLongClick = onLongClick?.let { { it() } },
)
