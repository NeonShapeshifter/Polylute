package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import coil.size.Size
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.AutoScrollingText
import com.lostf1sh.pixelplayeross.presentation.components.ShimmerBox
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

/** Shapes and styles that never change, built once rather than once per row that scrolls in. */
private val RowShape: Shape = RoundedCornerShape(DapRadius.Surface)
private val TitleTextStyle: TextStyle = DapType.MediaRow.merge(fontWeight = FontWeight.SemiBold)

private val HighlightSpec: AnimationSpec<Float> = tween(durationMillis = 400)
private val SelectionVisualSpec: AnimationSpec<Float> = tween(durationMillis = 250)
private val SelectionScaleSpec: AnimationSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow
)

/**
 * A 0..1 value that follows [target] with [spec], and costs one state object while it is at rest.
 *
 * Each row used to own an `updateTransition` plus three `animateFloat`s for the highlight and the
 * selection, so every row that scrolled into view built a transition and three animations that
 * almost never ran. The animation machinery is now only composed while a value is actually moving:
 * a row at rest is a `mutableFloatStateOf`, a row whose state just changed animates exactly as before.
 */
@Composable
private fun rememberRowProgress(target: Float, spec: AnimationSpec<Float>): Float {
    val progress = remember { mutableFloatStateOf(target) }
    if (progress.floatValue != target) {
        LaunchedEffect(target) {
            animate(
                initialValue = progress.floatValue,
                targetValue = target,
                animationSpec = spec
            ) { value, _ -> progress.floatValue = value }
        }
    }
    return progress.floatValue
}

/** One line of row text. `Text` from Material 3 resolves nothing more than this when the caller passes a style. */
@Composable
private fun RowText(text: String, style: TextStyle, color: Color) {
    BasicText(
        text = text,
        style = style.merge(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Enhanced song list item with multi-selection support.
 * 
 * @param song The song to display
 * @param isPlaying Whether this song is currently playing
 * @param isCurrentSong Whether this is the current song in the queue (may be paused)
 * @param isLoading Whether to show loading shimmer state
 * @param showAlbumArt Whether to show the album art
 * @param albumArtSize Size of the album art thumbnail when shown
 * @param customShape Optional custom shape for the surface
 * @param isSelected Whether this item is selected in multi-selection mode
 * @param isSelectionMode Whether multi-selection mode is active
 * @param onLongPress Callback for long press gesture (activates selection)
 * @param onMoreOptionsClick Callback for more options button
 * @param onClick Callback for tap gesture
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedSongListItem(
    modifier: Modifier = Modifier,
    song: Song,
    isPlaying: Boolean,
    isCurrentSong: Boolean = false,
    isLoading: Boolean = false,
    showAlbumArt: Boolean = true,
    albumArtSize: Dp = DapSizes.ThumbRow,
    customShape: Shape? = null,
    containerColorOverride: Color? = null,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    showMoreOptionsButton: Boolean = true,
    onLongPress: () -> Unit = {},
    onMoreOptionsClick: (Song) -> Unit,
    onClick: () -> Unit
) {
    if (isLoading) {
        SongRowSkeleton(
            modifier = modifier,
            showAlbumArt = showAlbumArt,
            albumArtSize = albumArtSize,
            containerColorOverride = containerColorOverride
        )
        return
    }

    val isHighlighted = isCurrentSong
    val highlightProgress = rememberRowProgress(if (isHighlighted) 1f else 0f, HighlightSpec)
    val selectionVisualProgress = rememberRowProgress(if (isSelected) 1f else 0f, SelectionVisualSpec)
    val selectionScaleProgress = rememberRowProgress(if (isSelected) 1f else 0f, SelectionScaleSpec)

    val palette = Dap
    val surfaceShape = if (customShape != null && !isHighlighted) customShape else RowShape

    // Rows, not cards: a row sits on the page and a hairline separates it from the next one.
    val baseContainerColor = containerColorOverride ?: Color.Transparent
    val accent = LocalDapAccent.current
    val showSelectionDecoration = selectionVisualProgress > 0.001f

    // At rest (the case for nearly every row on screen) nothing is blended, so nothing pays for the
    // Oklab conversions `lerp` does; a row that is highlighting or selecting blends as before.
    val containerColor: Color
    val contentColor: Color
    val moreTint: Color
    if (highlightProgress == 0f && selectionVisualProgress == 0f) {
        containerColor = baseContainerColor
        contentColor = palette.primaryText
        moreTint = palette.tertiaryText
    } else {
        val playbackContainerColor = lerpColor(baseContainerColor, palette.surface, highlightProgress)
        containerColor = lerpColor(playbackContainerColor, palette.surface, selectionVisualProgress)
        val playbackContentColor = lerpColor(palette.primaryText, accent, highlightProgress)
        contentColor = lerpColor(playbackContentColor, palette.primaryText, selectionVisualProgress)
        moreTint = lerpColor(palette.tertiaryText, palette.secondaryText, highlightProgress)
    }

    val resources = LocalContext.current.resources
    val albumArtTargetSizePx = with(LocalDensity.current) { albumArtSize.roundToPx() }

    var rowModifier = modifier.fillMaxWidth()
    if (selectionScaleProgress != 0f) {
        rowModifier = rowModifier.scale(lerpFloat(1f, 0.98f, selectionScaleProgress))
    }
    rowModifier = rowModifier.clip(surfaceShape)
    if (showSelectionDecoration) {
        val colors = MaterialTheme.colorScheme
        rowModifier = rowModifier.border(
            width = lerpDp(0.dp, 2.5.dp, selectionVisualProgress),
            color = lerpColor(colors.primary.copy(alpha = 0f), colors.primary, selectionVisualProgress),
            shape = surfaceShape
        )
    }

    Box(
        modifier = rowModifier
            .background(containerColor, surfaceShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick {
                    if (isSelectionMode) {
                        onLongPress()
                    } else {
                        onClick()
                    }
                    true
                }
                onLongClick { onLongPress(); true }
            }
            .pointerInput(isSelectionMode) {
                detectTapGestures(
                    onTap = {
                        if (isSelectionMode) {
                            onLongPress()
                        } else {
                            onClick()
                        }
                    },
                    onLongPress = {
                        onLongPress()
                    }
                )
            },
        propagateMinConstraints = true
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showAlbumArt) {
                Box(
                    modifier = Modifier
                        .size(albumArtSize)
                        .background(palette.surfaceRaised, RowShape)
                ) {
                    SmartImage(
                        model = song.albumArtUriString,
                        contentDescription = null,
                        shape = RowShape,
                        targetSize = Size(albumArtTargetSizePx, albumArtTargetSizePx),
                        modifier = Modifier.fillMaxSize()
                    )

                    if (showSelectionDecoration) {
                        val colors = MaterialTheme.colorScheme
                        val selectionOverlayContentColor = lerpColor(
                            Color.Transparent,
                            colors.onPrimary,
                            selectionVisualProgress
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    color = lerpColor(
                                        Color.Transparent,
                                        colors.primary.copy(alpha = 0.7f),
                                        selectionVisualProgress
                                    ),
                                    shape = RowShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectionIndex != null && selectionIndex >= 0) {
                                Text(
                                    text = selectionIndex.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = selectionOverlayContentColor
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = stringResource(R.string.presentation_batch_g_list_cd_selected),
                                    tint = selectionOverlayContentColor,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
            } else {
                Spacer(modifier = Modifier.width(4.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
            ) {
                if (isHighlighted && !isSelectionMode) {
                    // The text below takes its colour from LocalContentColor; Material's Surface used
                    // to set it from the row's container colour, so keep setting exactly that.
                    CompositionLocalProvider(LocalContentColor provides contentColorFor(containerColor)) {
                        AutoScrollingText(
                            text = songTitleLabel(song),
                            style = DapType.MediaRow.copy(fontWeight = FontWeight.SemiBold),
                            gradientEdgeColor = containerColor,
                        )
                    }
                } else {
                    RowText(
                        text = songTitleLabel(song),
                        style = TitleTextStyle,
                        color = contentColor
                    )
                }
                // No artist line, and no spacer for one, when the track names no artist. A row
                // whose second line reads `<unknown>` is worse than a row with one line.
                songArtistLabel(song)?.let { artistLabel ->
                    Spacer(modifier = Modifier.height(4.dp))
                    RowText(
                        text = artistLabel,
                        style = DapType.Note,
                        color = palette.secondaryText
                    )
                }
            }

            val showPlayingIndicator = isCurrentSong && isPlaying && !isSelectionMode
            val showTrailingAction = showMoreOptionsButton && !isSelectionMode

            if (showPlayingIndicator) {
                PlayingEqIcon(
                    modifier = Modifier
                        .padding(start = DapSpacing.Sm)
                        .size(width = 18.dp, height = 16.dp),
                    color = accent,
                    isPlaying = isPlaying
                )
            }

            if (showPlayingIndicator || showTrailingAction) {
                Spacer(modifier = Modifier.width(DapSpacing.Sm))
            }

            if (showTrailingAction) {
                Box(
                    modifier = Modifier
                        .size(DapSizes.MinTouchTarget)
                        .clip(RowShape)
                        .clickable { onMoreOptionsClick(song) }
                        .semantics {
                            // Read when the semantics are collected, not on every composition of every row.
                            contentDescription = resources.getString(
                                R.string.presentation_batch_g_list_cd_more_for_title,
                                song.title
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    DapIcon(kind = DapIconKind.More, tint = moreTint, size = 18.dp)
                }
            }
        }
    }
}

/** The loading row: same footprint as a song row, static shimmer blocks. */
@Composable
private fun SongRowSkeleton(
    modifier: Modifier,
    showAlbumArt: Boolean,
    albumArtSize: Dp,
    containerColorOverride: Color?
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(containerColorOverride ?: MaterialTheme.colorScheme.surfaceContainerLow, RowShape),
        propagateMinConstraints = true
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showAlbumArt) {
                ShimmerBox(
                    modifier = Modifier
                        .size(albumArtSize)
                        .clip(RowShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (showAlbumArt) 0.dp else 4.dp)
            ) {
                ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(20.dp)
                        .clip(SkeletonLineShape)
                )
                Spacer(modifier = Modifier.height(4.dp))
                ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(16.dp)
                        .clip(SkeletonLineShape)
                )
                Spacer(modifier = Modifier.height(2.dp))
                ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.3f)
                        .height(16.dp)
                        .clip(SkeletonLineShape)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            ShimmerBox(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RowShape)
            )
        }
    }
}

private val SkeletonLineShape: Shape = RoundedCornerShape(4.dp)
