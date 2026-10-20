package com.lostf1sh.pixelplayeross.ui.ds

import com.lostf1sh.pixelplayeross.R
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The chrome rule: the bottom bar shows only on roots; the mini-player persists on every
 * non-player screen. That is the whole thing.
 */

/** A back chevron with a full touch target. */
@Composable
fun DapBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Dap.secondaryText,
) {
    val backLabel = stringResource(R.string.auth_cd_back)
    Box(
        modifier
            .size(DapSizes.MinTouchTarget)
            .clip(RoundedCornerShape(DapRadius.Surface))
            .semantics { contentDescription = backLabel }
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DapIcon(DapIconKind.Chevron, tint = tint, size = 16.dp)
    }
}

/** A trailing overflow glyph with a full touch target. */
@Composable
fun DapOverflowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Dap.secondaryText,
    kind: DapIconKind = DapIconKind.More,
    iconSize: Dp = 18.dp,
    enabled: Boolean = true,
    description: String = stringResource(R.string.presentation_batch_b_more_options),
) {
    Box(
        modifier
            .size(DapSizes.MinTouchTarget)
            .clip(RoundedCornerShape(DapRadius.Surface))
            .semantics { contentDescription = description }
            .clickable(
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        DapIcon(
            kind,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            size = iconSize,
        )
    }
}

/**
 * The standard bar above a screen's content: a back glyph on the left, optional actions on the
 * right. No elevation, no container colour, no title — titles are set in the page body.
 */
@Composable
fun DapTopBar(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DapSpacing.Gutter - 12.dp, vertical = DapSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (onBack != null) DapBackButton(onBack) else Spacer(Modifier.width(DapSizes.MinTouchTarget))
        if (actions != null) actions()
    }
}

/** A screen title: 26 / 600, tracking -.02em. */
@Composable
fun DapScreenTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        // Centred, so a trailing 48 dp action sits level with the title rather than hanging below
        // its baseline. Without a trailing action there is one child and alignment is moot.
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = DapType.Title,
            color = Dap.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailing != null) {
            Spacer(Modifier.width(DapSpacing.Md))
            trailing()
        }
    }
}

/** A small mono count or state shown at the trailing edge of a title row. */
@Composable
fun DapTitleMeta(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = DapType.ValueSmall,
        color = Dap.tertiaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The bottom navigation. Three roots, words and glyphs only. */
@Composable
fun DapBottomNav(
    items: List<DapNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(bottom = 0.dp),
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Dap.surface)
    ) {
        DapDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
                .padding(contentPadding)
                .padding(horizontal = DapSpacing.SectionGap)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(DapRadius.Surface))
                        .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab) { onSelect(index) }
                        .padding(vertical = DapSpacing.Xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DapIcon(
                        kind = item.icon,
                        tint = if (selected) Dap.primaryText else Dap.tertiaryText,
                        size = 20.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(item.labelRes),
                        style = DapType.NavLabel,
                        color = if (selected) Dap.primaryText else Dap.tertiaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * A bottom-navigation destination.
 *
 * The label is a string resource rather than a `String`, so a nav label cannot be hardcoded. Three
 * of them were — "Home", "Search", "Library" as literals at the call site — which is why the bar
 * stayed English on a Spanish device while every other surface translated.
 */
data class DapNavItem(@param:StringRes val labelRes: Int, val icon: DapIconKind)

/**
 * The mini-player: 60 dp, bg/1, 40 dp art, title and artist, pause and skip.
 *
 * Artwork shrinks to the thumbnail; nothing else morphs.
 */
@Composable
fun DapMiniPlayer(
    title: String,
    artist: String,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: (@Composable () -> Unit)? = null,
) {
    val playPauseLabel = stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play)
    val nextLabel = stringResource(R.string.next_track)
    Column(
        modifier
            .fillMaxWidth()
            .background(Dap.surface)
    ) {
        DapDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .height(DapHeights.MiniPlayer)
                .padding(horizontal = DapSpacing.Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(DapSizes.ThumbRow)
                    .clip(RoundedCornerShape(DapRadius.Surface))
                    .clickable(onClick = onOpen)
            ) {
                if (artwork != null) artwork() else DapArtworkPlaceholder(Modifier.size(DapSizes.ThumbRow))
            }
            Spacer(Modifier.width(DapSpacing.Md))
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
            ) {
                Text(
                    text = title,
                    style = DapType.MediaRow.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    ),
                    color = Dap.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = artist,
                    style = DapType.Note,
                    color = Dap.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(DapSpacing.Md))
            Box(
                Modifier
                    .size(DapSizes.MinTouchTarget)
                    .clip(RoundedCornerShape(DapRadius.Surface))
                    .semantics { contentDescription = playPauseLabel }
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                DapIcon(
                    kind = if (isPlaying) DapIconKind.PauseSmall else DapIconKind.PlaySmall,
                    tint = Dap.primaryText,
                    size = 16.dp,
                )
            }
            Spacer(Modifier.width(DapSpacing.Xs))
            Box(
                Modifier
                    .size(DapSizes.MinTouchTarget)
                    .clip(RoundedCornerShape(DapRadius.Surface))
                    .semantics { contentDescription = nextLabel }
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onNext),
                contentAlignment = Alignment.Center,
            ) {
                DapIcon(DapIconKind.SkipNext, tint = Dap.secondaryText, size = 16.dp)
            }
        }
    }
}

/** The sheet surface: bg/1, 20 dp top corners, a grab handle, an optional title row. */
@Composable
fun DapSheetSurface(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = DapRadius.SheetTop, topEnd = DapRadius.SheetTop))
            .background(Dap.surface)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Dap.border)
            )
        }
        if (title != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = DapSpacing.Gutter,
                        end = DapSpacing.Gutter,
                        top = 18.dp,
                    ),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = DapType.Heading,
                        color = Dap.primaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = subtitle,
                            style = DapType.Caption,
                            color = Dap.secondaryText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (actions != null) {
                    Spacer(Modifier.width(DapSpacing.Lg))
                    actions()
                }
            }
        }
        content()
    }
}

/** The readout row at the foot of every player sibling: source, arrow, output, confirmed rate. */
@Composable
fun DapSignalReadout(
    source: String,
    output: String,
    rate: String,
    rateState: DapStateKind,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = source,
            style = DapType.Readout,
            color = Dap.tertiaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(DapSpacing.Md))
        Text("→", style = DapType.Readout, color = Dap.disabledText, maxLines = 1)
        Spacer(Modifier.width(DapSpacing.Md))
        Text(
            text = output,
            style = DapType.Readout,
            color = Dap.tertiaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(DapSpacing.Md))
        DapState(kind = rateState, label = rate)
    }
}

/** An empty state: a title and a truthful explanation. Never implies a removed capability. */
@Composable
fun DapEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DapSpacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = DapType.RowSmall,
            color = Dap.secondaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DapSpacing.Md))
        Text(
            text = message,
            style = DapType.Caption,
            color = Dap.secondaryText,
            modifier = Modifier.fillMaxWidth(),
        )
        if (action != null) {
            Spacer(Modifier.height(DapSpacing.SectionGap))
            action()
        }
    }
}

/** A list of static skeleton rows, used while a screen's real content resolves. */
@Composable
fun DapSkeletonList(
    count: Int,
    modifier: Modifier = Modifier,
    showArt: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        repeat(count) { DapSkeletonRow(showArt = showArt) }
    }
}

/**
 * The default screen container: bg/0 with the standard gutter.
 *
 * The content is a [BoxScope], so a screen can place a pinned overlay — a floating Back affordance,
 * for instance — as a sibling of its scrolling content, instead of reserving a row of the page for
 * it or wrapping itself in a box of its own to get an alignment scope.
 */
@Composable
fun DapScreen(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Dap.background)
    ) { content() }
}

/**
 * Constrains a page's content width on large screens.
 *
 * Rows read badly when stretched across a tablet, so a single-column page stops widening at
 * [DapSpacing.MaxContentWidth] and centres. Below that the content fills the width it is given.
 */
@Composable
fun DapContentWidth(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                .widthIn(max = DapSpacing.MaxContentWidth)
        ) { content() }
    }
}

/**
 * The player header (B22, `Player.dc.html`): the collapse glyph on the left, and on the right the two entries to the
 * other surfaces: Lyrics and Details. **Icons only, no tabs**, so it does not depend on the language and nothing
 * in it looks like a tab that is not one. There is no Queue button (B33-U1): the queue is the sheet folded at the foot of the player.
 *
 * Lyrics is a state button, like shuffle and repeat: it is on (accent and a dot) while the lyrics are drawn over the
 * cover, and pressing it again goes back to the cover on the same screen. Details opens another surface,
 * so it has no on state.
 *
 * "Header and readout never move": it is a static layer above whichever surface is showing, so switching surface never
 * re-lays-out the chrome.
 */
@Composable
fun DapPlayerHeader(
    lyricsActive: Boolean,
    onCollapse: () -> Unit,
    onLyrics: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = DapSpacing.Sm, vertical = DapSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
    ) {
        DapStateIconButton(
            contentDescription = stringResource(R.string.player_header_collapse),
            active = false,
            onClick = onCollapse,
        ) { tint -> DapIcon(DapIconKind.ChevronDown, tint = tint, size = 18.dp) }
        Spacer(Modifier.weight(1f))
        DapStateIconButton(
            contentDescription = stringResource(R.string.player_header_lyrics),
            active = lyricsActive,
            onClick = onLyrics,
            stateDescription = stringResource(
                if (lyricsActive) R.string.lock_transport_state_on else R.string.lock_transport_state_off
            ),
        ) { tint -> Icon(Icons.Outlined.Lyrics, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) }
        DapStateIconButton(
            contentDescription = stringResource(R.string.player_header_details),
            active = false,
            onClick = onDetails,
        ) { tint -> Icon(Icons.Outlined.Info, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) }
    }
}

/**
 * The wordmark's widest size (B32-Q1): 170 dp, 15 % under the 200 dp it was drawn at since B26. The proportions (1048 : 240) and the
 * left edge are the vector's own; only the width the mark may take changed.
 */
val DapBrandMaxWidth = 170.dp

/** Owner's complete vector mark, tinted by the selected application accent. */
@Composable
fun DapBrandHeader(modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Icon(
            painter = painterResource(R.drawable.polylute_wordmark),
            contentDescription = "POLYLUTE",
            tint = LocalDapAccent.current,
            modifier = Modifier.widthIn(max = DapBrandMaxWidth).weight(1f, fill = false).aspectRatio(1048f / 240f),
        )
        Spacer(Modifier.width(DapSpacing.Md))
        trailing()
    }
}
