package com.lostf1sh.pixelplayeross.presentation.components

import com.lostf1sh.pixelplayeross.utils.currentLocale
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSectionHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import java.util.Locale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.model.RecentlyPlayedSongUiModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ThemeStateHolder
import kotlinx.collections.immutable.ImmutableList
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel

private val HomeRecentlyPlayedPillHeight = 58.dp
private val HomeRecentlyPlayedPillSpacing = 8.dp
// Eighteen, as three rows of six (B25-S8: "Recently played is very small"). It was nine, a 3x3 grid of which about two columns
// show on a 360 dp screen, so the section ended almost as soon as it began. A multiple of three keeps every row full (ten
// left the last item alone on a row), the grid scrolls sideways as a whole, and the "see all" arrow beside the title opens
// the whole history. The cost is nine more pills to compose when Home is scrolled to this section (one image and two lines
// each); the history Home reads holds 64 songs, so there is always something behind the new limit.
private const val HomeRecentlyPlayedPillsLimit = 18
private const val HomeRecentlyPlayedPillsPerColumn = 3
internal const val RecentlyPlayedSectionMinSongsToShow = 4
private val HomeRecentlyPlayedPillArtSize = 38.dp
private val HomeRecentlyPlayedWidthSteps = listOf(148.dp, 166.dp, 184.dp, 202.dp, 220.dp)
private val HomeRecentlyPlayedDefaultContentPadding = PaddingValues(horizontal = 8.dp)

private data class RecentlyPlayedPillCell(
    val item: RecentlyPlayedSongUiModel,
    val width: Dp
)

private data class RecentlyPlayedPillRow(
    val pills: List<RecentlyPlayedPillCell>,
    val contentWidth: Dp
)

@Composable
fun RecentlyPlayedSection(
    songs: ImmutableList<RecentlyPlayedSongUiModel>,
    onSongClick: (Song) -> Unit,
    onOpenAllClick: () -> Unit,
    themeStateHolder: ThemeStateHolder,
    currentSongId: String? = null,
    contentPadding: PaddingValues = HomeRecentlyPlayedDefaultContentPadding,
    modifier: Modifier = Modifier
) {
    val visibleSongs = remember(songs) { songs.take(HomeRecentlyPlayedPillsLimit) }
    if (visibleSongs.size < RecentlyPlayedSectionMinSongsToShow) return

    val layoutDirection = LocalLayoutDirection.current
    val startContentPadding = remember(contentPadding, layoutDirection) {
        contentPadding.calculateLeftPadding(layoutDirection)
    }
    val endContentPadding = remember(contentPadding, layoutDirection) {
        contentPadding.calculateRightPadding(layoutDirection)
    }
    val songRows = remember(visibleSongs, startContentPadding, endContentPadding) {
        buildRecentlyPlayedPillRows(
            visibleSongs = visibleSongs,
            startContentPadding = startContentPadding,
            endContentPadding = endContentPadding
        )
    }
    val maxRowContentWidth = remember(songRows) {
        songRows.maxOfOrNull { it.contentWidth } ?: 0.dp
    }
    val sharedScrollState = rememberScrollState()

    val sectionHeight = HomeRecentlyPlayedPillHeight * HomeRecentlyPlayedPillsPerColumn +
            HomeRecentlyPlayedPillSpacing * (HomeRecentlyPlayedPillsPerColumn - 1)

    Column(
        modifier = modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DapSpacing.Gutter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A tracked mono heading, like every other section on Home. This was a Material
            // titleLarge and read as a page title competing with the screen's own.
            DapSectionHeader(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.presentation_batch_g_recently_played_title)
                    .uppercase(currentLocale())
            )
            DapOverflowButton(
                onClick = onOpenAllClick,
                description = stringResource(R.string.action_view_history),
                enabled = visibleSongs.size >= RecentlyPlayedSectionMinSongsToShow,
                kind = DapIconKind.ChevronRight,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = sectionHeight)
                .horizontalScroll(state = sharedScrollState)
        ) {
            Column(
                modifier = Modifier
                    .width(maxRowContentWidth)
                    .height(sectionHeight),
                verticalArrangement = Arrangement.spacedBy(HomeRecentlyPlayedPillSpacing),
                horizontalAlignment = Alignment.Start
            ) {
                songRows.forEach { row ->
                    if (row.pills.isEmpty()) {
                        Spacer(modifier = Modifier.height(HomeRecentlyPlayedPillHeight))
                    } else {
                        // The content padding is padding, not a spacer: a spacer is a child, so the
                        // row's spacing was added after it and the first pill sat 8 dp further in
                        // than asked — and past the width buildRecentlyPlayedPillRows reserved.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(HomeRecentlyPlayedPillHeight)
                                .padding(start = startContentPadding, end = endContentPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(HomeRecentlyPlayedPillSpacing)
                        ) {
                            row.pills.forEach { cell ->
                                key(cell.item.song.id) {
                                    RecentlyPlayedPill(
                                        item = cell.item,
                                        isCurrentSong = currentSongId == cell.item.song.id,
                                        themeStateHolder = themeStateHolder,
                                        modifier = Modifier.width(cell.width),
                                        onClick = { onSongClick(cell.item.song) }
                                    )
                                }
                            }
                            val trailingGap = (maxRowContentWidth - row.contentWidth).coerceAtLeast(0.dp)
                            if (trailingGap > 0.dp) {
                                Spacer(modifier = Modifier.width(trailingGap))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildRecentlyPlayedPillRows(
    visibleSongs: List<RecentlyPlayedSongUiModel>,
    startContentPadding: Dp,
    endContentPadding: Dp
): List<RecentlyPlayedPillRow> {
    val rows = MutableList(HomeRecentlyPlayedPillsPerColumn) { mutableListOf<RecentlyPlayedPillCell>() }
    val rowPillsWidths = MutableList(HomeRecentlyPlayedPillsPerColumn) { 0.dp }
    val rowTargets = resolveRecentlyPlayedRowTargets(visibleSongs.size)

    var itemIndex = 0
    for (columnIndex in 0 until rowTargets[0]) {
        for (rowIndex in 0 until HomeRecentlyPlayedPillsPerColumn) {
            if (itemIndex >= visibleSongs.size) break
            if (columnIndex >= rowTargets[rowIndex]) continue

            val item = visibleSongs[itemIndex++]
            val cellWidth = resolveRecentlyPlayedPillWidth(item = item)
            val spacingBefore = if (rows[rowIndex].isNotEmpty()) HomeRecentlyPlayedPillSpacing else 0.dp
            rows[rowIndex] += RecentlyPlayedPillCell(
                item = item,
                width = cellWidth
            )
            rowPillsWidths[rowIndex] = rowPillsWidths[rowIndex] + spacingBefore + cellWidth
        }
    }

    return rows.mapIndexed { rowIndex, pills ->
        val rowWidth = rowPillsWidths[rowIndex] + startContentPadding + endContentPadding
        RecentlyPlayedPillRow(
            pills = pills,
            contentWidth = rowWidth
        )
    }
}

private fun resolveRecentlyPlayedRowTargets(totalItems: Int): IntArray {
    val base = totalItems / HomeRecentlyPlayedPillsPerColumn
    val remainder = totalItems % HomeRecentlyPlayedPillsPerColumn
    return intArrayOf(
        base + if (remainder > 0) 1 else 0,
        base + if (remainder > 1) 1 else 0,
        base
    )
}

@Composable
private fun RecentlyPlayedPill(
    item: RecentlyPlayedSongUiModel,
    isCurrentSong: Boolean,
    themeStateHolder: ThemeStateHolder,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val albumColorSchemeState by remember(item.song.albumArtUriString, themeStateHolder) {
        themeStateHolder.getAlbumColorSchemeFlow(item.song.albumArtUriString.orEmpty())
    }.collectAsStateWithLifecycle()

    val albumColorScheme = remember(albumColorSchemeState, isDark) {
        albumColorSchemeState?.let { if (isDark) it.dark else it.light }
    }

    // One flat surface for every pill. The album-derived primaryContainer tint and the animated
    // corner radius were the last place the product still coloured itself from artwork, and the
    // periwinkle they produced belonged to a different design. The current track is marked the
    // way the rest of the system marks it: an accent title.
    val containerColor = Dap.surface
    val titleColor = if (isCurrentSong) LocalDapAccent.current else Dap.primaryText
    val artistColor = Dap.tertiaryText
    val shape = RoundedCornerShape(DapRadius.Surface)
    val artStartPadding = DapSpacing.Md

    Row(
        modifier = modifier
            .height(HomeRecentlyPlayedPillHeight)
            .clip(shape)
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(start = artStartPadding, end = DapSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md)
    ) {
        SmartImage(
            model = item.song.albumArtUriString,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            shape = RoundedCornerShape(DapRadius.Surface),
            targetSize = SmartImageCompactListTargetSize,
            modifier = Modifier.size(HomeRecentlyPlayedPillArtSize)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = songTitleLabel(item.song),
                style = DapType.MediaRow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = titleColor
            )
            songArtistLabel(item.song)?.let { artistLabel ->
                Text(
                    text = artistLabel,
                    style = DapType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = artistColor
                )
            }
        }
    }
}

private fun resolveRecentlyPlayedPillWidth(
    item: RecentlyPlayedSongUiModel
): Dp {
    val titleLength = item.song.title.trim().length
    val artistLength = item.song.displayArtist.trim().length
    val weightedTextLength = titleLength + (artistLength * 0.55f)

    val baseStep = when {
        weightedTextLength < 18f -> 0
        weightedTextLength < 28f -> 1
        weightedTextLength < 40f -> 2
        weightedTextLength < 54f -> 3
        else -> 4
    }

    val resolvedStep = baseStep.coerceIn(0, HomeRecentlyPlayedWidthSteps.lastIndex)
    return HomeRecentlyPlayedWidthSteps[resolvedStep]
}


