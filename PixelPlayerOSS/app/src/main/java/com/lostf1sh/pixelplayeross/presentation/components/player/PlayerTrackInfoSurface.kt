package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import com.lostf1sh.pixelplayeross.ui.ds.DapStateIconButton
import com.lostf1sh.pixelplayeross.ui.ds.DapType
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import com.lostf1sh.pixelplayeross.data.model.Lyrics
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
) {
    val song = info.song
    val currentSongArtists = info.artists
    val loadingTweaks = env.loadingTweaks
    val isSheetDragGestureActive = env.isSheetDragGestureActive
    val expansionFractionProvider = env.expansionFractionProvider
    val currentSheetState = env.currentSheetState
    val isPlayingProvider = info.isPlayingProvider
    val placeholderColor = LocalMaterialTheme.current.onPrimaryContainer.copy(alpha = 0.1f)
    val placeholderOnColor = LocalMaterialTheme.current.onPrimaryContainer.copy(alpha = 0.2f)
    val playerOnBaseColor = LocalMaterialTheme.current.onPrimaryContainer
    val gradientEdgeColor = LocalMaterialTheme.current.primaryContainer
    val chipColor = LocalMaterialTheme.current.onPrimary.copy(alpha = 0.8f)
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
            textColor = playerOnBaseColor,
            artistTextColor = playerOnBaseColor.copy(alpha = 0.7f),
            actions = actions,
            gradientEdgeColor = gradientEdgeColor,
            chipColor = chipColor,
            chipContentColor = chipContentColor,
            isFavorite = info.isFavorite,
            isBuffering = info.isBuffering,
            favoriteLabel = stringResource(
                if (info.isFavorite) R.string.cd_remove_from_favorites
                else R.string.cd_add_to_favorites
            ),
            onClickFavorite = onFavoriteToggle,
            onClickBookmark = onAddBookmark,
            onClickArtist = onArtistClick,
            isPlayingProvider = isPlayingProvider
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
    textColor: Color,
    artistTextColor: Color,
    gradientEdgeColor: Color,
    actions: FullPlayerActions,
    chipColor: Color,
    chipContentColor: Color,
    isFavorite: Boolean,
    isBuffering: Boolean,
    favoriteLabel: String,
    onClickFavorite: () -> Unit,
    onClickBookmark: () -> Unit,
    onClickArtist: () -> Unit,
    modifier: Modifier = Modifier,
    isPlayingProvider: () -> Boolean = { true }
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        song?.let { currentSong ->
            PlayerSongInfo(
                title = songTitleLabel(currentSong),
                // The player's identity block, not a row's description: this line is part of what
                // the screen is, and it is an affordance to the artist, so it says something even
                // when the file names nobody. A row omits a missing artist; the player does not.
                artist = songArtistLabel(currentSong) ?: stringResource(R.string.unknown_artist),
                artistId = currentSong.artistId,
                artists = currentSongArtists,
                expansionFractionProvider = expansionFractionProvider,
                textColor = textColor,
                artistTextColor = artistTextColor,
                gradientEdgeColor = gradientEdgeColor,
                actions = actions,
                onClickArtist = onClickArtist,
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                isPlayingProvider = isPlayingProvider
            )
        }


        AnimatedVisibility(
            visible = isBuffering,
            enter = DapMotion.contentEnter(),
            exit = DapMotion.contentExitTransition()
        ) {
            Surface(
                shape = CircleShape,
                color = chipColor,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Box(
                    modifier = Modifier.padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator(
                        modifier = Modifier.size(28.dp),
                        color = chipContentColor
                    )
                }
            }
        }

        // B22: the bookmark and the heart, side by side at the end of the title. Lyrics lives in the header and nowhere
        // else; the heart is a state button like shuffle (accent and a dot when the track is liked).
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
        DapStateIconButton(
            contentDescription = favoriteLabel,
            active = isFavorite,
            onClick = onClickFavorite,
        ) { tint -> DapIcon(kind = DapIconKind.Heart, tint = tint, size = 24.dp) }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerSongInfo(
    title: String,
    artist: String,
    artistId: Long,
    artists: ImmutableList<Artist>,
    expansionFractionProvider: () -> Float,
    textColor: Color,
    artistTextColor: Color,
    gradientEdgeColor: Color,
    actions: FullPlayerActions,
    onClickArtist: () -> Unit,
    modifier: Modifier = Modifier,
    isPlayingProvider: () -> Boolean = { true }
) {
    val coroutineScope = rememberCoroutineScope()
    var isNavigatingToArtist by remember { mutableStateOf(false) }
    val resolvedArtistId by remember(artists, artistId) {
        derivedStateOf { artists.firstOrNull { it.id != 0L && it.id != -1L }?.id ?: artistId }
    }
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .graphicsLayer {
                val fraction = expansionFractionProvider()
                alpha = fraction
                translationY = (1f - fraction) * 24f
            }
    ) {
        // One line and an ellipsis (B22): a long title ends in "…", it does not scroll or wrap into the controls.
        Text(
            text = title,
            style = DapType.Heading,
            color = Dap.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = artist,
            style = DapType.Subtitle,
            color = Dap.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (isNavigatingToArtist) return@combinedClickable
                        coroutineScope.launch {
                            isNavigatingToArtist = true
                            try {
                                onClickArtist()
                            } finally {
                                isNavigatingToArtist = false
                            }
                        }
                    },
                    onLongClick = {
                        if (isNavigatingToArtist) return@combinedClickable
                        coroutineScope.launch {
                            isNavigatingToArtist = true
                            try {
                                actions.onOpenArtist(resolvedArtistId)
                            } finally {
                                isNavigatingToArtist = false
                            }
                        }
                    }
                ),
        )
    }
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
