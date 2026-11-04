package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import com.lostf1sh.pixelplayeross.ui.ds.DapStateIconButton
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapMotion
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun PlayerTrackInfoSurface(
    info: PlayerTrackInfoState,
    env: PlayerSheetEnv,
    actions: FullPlayerActions,
    isLandscape: Boolean,
    onArtistClick: () -> Unit,
    onAddBookmark: () -> Unit,
    isVisible: Boolean = true,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val metadataVisible = isVisible && env.allowRealtimeUpdates &&
        env.currentSheetState == PlayerSheetState.EXPANDED && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val song = info.song
    val currentSongArtists = info.artists
    val loadingTweaks = env.loadingTweaks
    val isSheetDragGestureActive = env.isSheetDragGestureActive
    val expansionFractionProvider = env.expansionFractionProvider
    val currentSheetState = env.currentSheetState
    val placeholderColor = LocalMaterialTheme.current.onPrimaryContainer.copy(alpha = 0.1f)
    val placeholderOnColor = LocalMaterialTheme.current.onPrimaryContainer.copy(alpha = 0.2f)
    val chipContentColor = LocalMaterialTheme.current.primary
    val onFavoriteToggle = remember(actions) { actions::onFavoriteToggle }
    val shouldDelay = loadingTweaks.delayAll || loadingTweaks.delaySongMetadata

    DelayedContent(
        shouldDelay = shouldDelay,
        showPlaceholders = loadingTweaks.showPlaceholders,
        applyPlaceholderDelayOnClose = loadingTweaks.applyPlaceholdersOnClose,
        switchOnDragRelease = loadingTweaks.switchOnDragRelease,
        isSheetDragGestureActive = isSheetDragGestureActive,
        sharedBoundsModifier = Modifier.fillMaxWidth().heightIn(min = 70.dp),
        expansionFractionProvider = expansionFractionProvider,
        isExpandedOverride = currentSheetState == PlayerSheetState.EXPANDED,
        normalStartThreshold = 0.20f,
        delayAppearThreshold = loadingTweaks.contentAppearThresholdPercent / 100f,
        delayCloseThreshold = 1f - (loadingTweaks.contentCloseThresholdPercent / 100f),
        placeholder = {
            if (loadingTweaks.transparentPlaceholders) {
                Box(Modifier.fillMaxWidth().height(70.dp))
            } else {
                MetadataPlaceholder(
                    expansionFractionProvider = expansionFractionProvider,
                    color = placeholderColor,
                    onColor = placeholderOnColor,
                    showQueueButtons = isLandscape
                )
            }
        }
    ) {
        SongMetadataDisplaySection(
            modifier = Modifier
                .padding(start = 0.dp),
            song = song,
            currentSongArtists = currentSongArtists,
            expansionFractionProvider = expansionFractionProvider,
            actions = actions,
            chipContentColor = chipContentColor,
            isFavorite = info.isFavorite,
            isBuffering = info.isBuffering,
            favoriteLabel = stringResource(
                if (info.isFavorite) R.string.cd_remove_from_favorites
                else R.string.cd_add_to_favorites
            ),
            onClickFavorite = onFavoriteToggle,
            onClickBookmark = onAddBookmark,
            showBookmarkButton = info.showBookmarkButton,
            onClickArtist = onArtistClick,
            metadataVisible = metadataVisible,
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SongMetadataDisplaySection(
    song: Song?,
    currentSongArtists: ImmutableList<Artist>,
    expansionFractionProvider: () -> Float,
    actions: FullPlayerActions,
    chipContentColor: Color,
    isFavorite: Boolean,
    isBuffering: Boolean,
    favoriteLabel: String,
    onClickFavorite: () -> Unit,
    onClickBookmark: () -> Unit,
    onClickArtist: () -> Unit,
    showBookmarkButton: Boolean,
    modifier: Modifier = Modifier,
    metadataVisible: Boolean,
) {
    val coroutineScope = rememberCoroutineScope()
    var navigating by remember { mutableStateOf(false) }
    val artistId = currentSongArtists.firstOrNull { it.id != 0L && it.id != -1L }?.id ?: song?.artistId ?: 0L
    val artistClick = {
        if (!navigating) {
            coroutineScope.launch {
                navigating = true
                try {
                    onClickArtist()
                } finally {
                    navigating = false
                }
            }
        }
        Unit
    }
    val artistLongClick = {
        if (!navigating) {
            coroutineScope.launch {
                navigating = true
                try {
                    actions.onOpenArtist(artistId)
                } finally {
                    navigating = false
                }
            }
        }
        Unit
    }
    Column(
        modifier.fillMaxWidth().graphicsLayer {
            val fraction = expansionFractionProvider()
            alpha = fraction
            translationY = (1f - fraction) * 24f
        },
    ) {
        val motionAllowed = metadataVisible && !com.lostf1sh.pixelplayeross.ui.ds.rememberDapReducedMotion()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SongTitleText(
                title = song?.let { songTitleLabel(it) }.orEmpty(),
                motionAllowed = motionAllowed,
                // B56-4: the title opens its place in the Library, as the artist and the album open theirs
                onClick = song?.id?.takeIf { it.isNotBlank() }?.let { id -> { actions.onLocateSongInLibrary(id) } },
                modifier = Modifier.weight(1f),
            )
            if (isBuffering) {
                LoadingIndicator(Modifier.size(28.dp), color = chipContentColor)
            }
            if (showBookmarkButton) {
                DapStateIconButton(
                    contentDescription = stringResource(R.string.player_add_bookmark),
                    active = false,
                    onClick = onClickBookmark,
                ) { tint ->
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.BookmarkBorder,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            DapStateIconButton(
                contentDescription = favoriteLabel,
                active = isFavorite,
                onClick = onClickFavorite,
            ) { tint -> DapIcon(kind = DapIconKind.Heart, tint = tint, size = 24.dp) }
        }
        Spacer(Modifier.height(2.dp))
        ArtistAlbumMetadata(
            artist = song?.let { songArtistLabel(it) } ?: stringResource(R.string.unknown_artist),
            album = song?.album?.ifBlank { null } ?: stringResource(R.string.unknown_album),
            motionAllowed = motionAllowed,
            onArtistClick = artistClick,
            onArtistLongClick = artistLongClick,
            onAlbumClick = { song?.let { actions.onOpenAlbum(it.albumId) } },
        )
    }
}

internal val PlayerMetadataScrolling = SemanticsPropertyKey<Boolean>("PlayerMetadataScrolling")

/**
 * B47-B2: the song's title, on the space the buttons beside it leave (the weight the caller gives it). One that fits does not move. One that does not fit scrolls with the
 * same marquee and under the same gate as the metadata line below it ([motionAllowed]: the player page is visible, the sheet expanded, realtime updates allowed, the screen
 * RESUMED, motion not reduced); without the gate it ends in an ellipsis, as it always did. The buttons are not part of this and neither move nor shrink.
 */
@Composable
private fun SongTitleText(title: String, motionAllowed: Boolean, onClick: (() -> Unit)?, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    // The slot the buttons leave, and the title's natural width against it: decided from the measurement, as the metadata line does. (The text's own layout cannot say: under the
    // marquee it is laid out with unbounded width and never overflows, which would turn the marquee off, and back on, without end.)
    BoxWithConstraints(
        modifier
            .then(
                if (onClick == null) Modifier
                else Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            )
    ) {
        val titleWidth = measurer.measure(title, DapType.Heading, maxLines = 1, softWrap = false).size.width
        val scrolling = titleWidth > constraints.maxWidth && motionAllowed
        Text(
            text = title,
            style = DapType.Heading,
            color = Dap.primaryText,
            maxLines = 1,
            overflow = if (scrolling) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier
                .playerMarquee(scrolling, title)
                .semantics { this[PlayerMetadataScrolling] = scrolling },
        )
    }
}

/** B47-B1: how the artist and the album share the line. Widths in pixels. */
internal data class ArtistAlbumLinePlan(
    /** The artist takes all the width the other parts leave (a weight of 1); otherwise it keeps its natural width. */
    val artistFills: Boolean,
    val albumFills: Boolean,
    /** What each shows does not fit the width it gets: it scrolls if motion is allowed, else it ends in an ellipsis. */
    val artistOverflows: Boolean,
    val albumOverflows: Boolean,
)

/**
 * B47-B1, the owner's screenshots of 2026-10-29: "Bad Bunny" / "nadie sabe lo que va a pasar mañana" took two equal halves, the separator disappeared and about half the line stood
 * empty. The rule now:
 *  - both fit on the line with the separator: natural widths, nothing moves;
 *  - one of them is short (at most half of what the separator leaves): it keeps its natural width, then the separator, and the other takes ALL the rest and scrolls only if that is
 *    still not enough;
 *  - both are long: each gets half (as before), and each scrolls only if it overflows its share.
 * The separator is always there: it is part of the line whenever there are two texts.
 */
internal fun planArtistAlbumLine(artistWidth: Int, albumWidth: Int, separatorWidth: Int, available: Int): ArtistAlbumLinePlan {
    if (artistWidth + separatorWidth + albumWidth <= available) return ArtistAlbumLinePlan(false, false, false, false)
    val share = ((available - separatorWidth) / 2).coerceAtLeast(0)
    return when {
        artistWidth <= share -> ArtistAlbumLinePlan(false, true, false, albumWidth > available - artistWidth - separatorWidth)
        albumWidth <= share -> ArtistAlbumLinePlan(true, false, artistWidth > available - albumWidth - separatorWidth, false)
        else -> ArtistAlbumLinePlan(true, true, artistWidth > share, albumWidth > share)
    }
}

/** Short credits share their natural width. Overflow: the short side keeps its width and the long one takes the rest; two long ones get equal halves (see [planArtistAlbumLine]). */
@Composable
private fun ArtistAlbumMetadata(
    artist: String,
    album: String,
    motionAllowed: Boolean,
    onArtistClick: () -> Unit,
    onArtistLongClick: () -> Unit,
    onAlbumClick: () -> Unit,
) {
    val measurer = rememberTextMeasurer(cacheSize = 6)
    val style = DapType.BodyLarge
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val artistWidth = measurer.measure(artist, style, maxLines = 1, softWrap = false).size.width
        val albumWidth = measurer.measure(album, style, maxLines = 1, softWrap = false).size.width
        val separatorWidth = measurer.measure(" · ", style, maxLines = 1, softWrap = false).size.width
        val plan = planArtistAlbumLine(artistWidth, albumWidth, separatorWidth, constraints.maxWidth)
        Row(Modifier.fillMaxWidth()) {
            MetadataText(
                text = artist,
                modifier = if (plan.artistFills) Modifier.weight(1f) else Modifier,
                scrolling = plan.artistOverflows && motionAllowed,
                onClick = onArtistClick,
                onLongClick = onArtistLongClick,
            )
            Text(" · ", style = style, color = Dap.secondaryText, maxLines = 1)
            MetadataText(
                text = album,
                modifier = if (plan.albumFills) Modifier.weight(1f) else Modifier,
                scrolling = plan.albumOverflows && motionAllowed,
                onClick = onAlbumClick,
                onLongClick = onAlbumClick,
            )
        }
    }
}

@Composable
private fun MetadataText(
    text: String,
    modifier: Modifier,
    scrolling: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Text(
        text = text,
        style = DapType.BodyLarge,
        color = Dap.secondaryText,
        maxLines = 1,
        overflow = if (scrolling) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = modifier
            .playerMarquee(scrolling, text)
            .semantics { this[PlayerMetadataScrolling] = scrolling }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun MetadataPlaceholder(
    expansionFractionProvider: () -> Float,
    color: Color,
    onColor: Color,
    showQueueButtons: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp)
            .graphicsLayer {
                val expansionFraction = expansionFractionProvider().coerceIn(0f, 1f)
                alpha = expansionFraction.coerceIn(0f, 1f)
                translationY = (1f - expansionFraction.coerceIn(0f, 1f)) * 24f
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PlaceholderBox(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(27.dp),
                cornerRadius = 8.dp,
                color = color
            )
            PlaceholderBox(
                modifier = Modifier
                    .fillMaxWidth(0.46f)
                    .height(17.dp),
                cornerRadius = 8.dp,
                color = onColor
            )
        }

        if (showQueueButtons) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(height = 42.dp, width = 50.dp),
                    shape = RoundedCornerShape(
                        topStart = 50.dp,
                        topEnd = 6.dp,
                        bottomStart = 50.dp,
                        bottomEnd = 6.dp
                    ),
                    color = onColor,
                    tonalElevation = 0.dp
                ) {}
                Surface(
                    modifier = Modifier.size(height = 42.dp, width = 50.dp),
                    shape = RoundedCornerShape(
                        topStart = 6.dp,
                        topEnd = 50.dp,
                        bottomStart = 6.dp,
                        bottomEnd = 50.dp
                    ),
                    color = onColor,
                    tonalElevation = 0.dp
                ) {}
            }
        } else {
            PlaceholderBox(
                modifier = Modifier.size(width = 48.dp, height = 48.dp),
                cornerRadius = 24.dp,
                color = onColor
            )
        }
    }
}
