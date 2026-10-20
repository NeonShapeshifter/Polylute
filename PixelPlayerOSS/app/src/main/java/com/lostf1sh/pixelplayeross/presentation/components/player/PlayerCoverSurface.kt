package com.lostf1sh.pixelplayeross.presentation.components.player

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.presentation.components.AlbumCarouselSection
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState

/** The sheet's expansion from which the cover carousel counts as on screen: it animates and takes swipes only then (B23-P3). */
internal const val CAROUSEL_ON_SCREEN_FRACTION = 0.5f

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
internal fun PlayerCoverSurface(
    cover: PlayerCoverState,
    env: PlayerSheetEnv,
    actions: FullPlayerActions,
    modifier: Modifier = Modifier
) {
    val song = cover.song
    val currentPlaybackQueue = cover.queue
    val currentMediaItemIndex = cover.mediaItemIndex
    val carouselStyle = cover.carouselStyle
    val albumArtQuality = cover.albumArtQuality
    val requestedScrollIndex = cover.requestedScrollIndex
    val loadingTweaks = env.loadingTweaks
    val isSheetDragGestureActive = env.isSheetDragGestureActive
    val expansionFractionProvider = env.expansionFractionProvider
    val currentSheetState = env.currentSheetState
    val placeholderColor = LocalMaterialTheme.current.onPrimaryContainer.copy(alpha = 0.1f)
    val placeholderOnColor = LocalMaterialTheme.current.onPrimaryContainer.copy(alpha = 0.2f)
    val onSongSelected: (Song, Int) -> Unit = { newSong, index ->
        actions.onPlayFromCarousel(newSong, cover.queue, cover.queueSourceName, index)
    }
    val onAlbumClick: (Song) -> Unit = { albumSong -> actions.onOpenAlbum(albumSong.albumId) }
    val shouldDelay = loadingTweaks.delayAll || loadingTweaks.delayAlbumCarousel
    // Artwork keeps its size when paused: shrinking it to 95% made the cover look disabled, and
    // playback state is already explicit in the central control.

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        val carouselHeight = when (carouselStyle) {
            CarouselStyle.NO_PEEK -> maxWidth
            CarouselStyle.ONE_PEEK -> maxWidth * 0.8f
            CarouselStyle.TWO_PEEK -> maxWidth * 0.6f
            else -> maxWidth * 0.8f
            // Square by width is the design's intent, but the portrait column hands down a
            // bounded height, and the art is what yields when the two disagree.
        }.coerceAtMost(maxHeight)

        DelayedContent(
            shouldDelay = shouldDelay,
            showPlaceholders = loadingTweaks.showPlaceholders,
            applyPlaceholderDelayOnClose = loadingTweaks.applyPlaceholdersOnClose,
            switchOnDragRelease = loadingTweaks.switchOnDragRelease,
            isSheetDragGestureActive = isSheetDragGestureActive,
            sharedBoundsModifier = Modifier.fillMaxWidth().height(carouselHeight),
            expansionFractionProvider = expansionFractionProvider,
            isExpandedOverride = currentSheetState == PlayerSheetState.EXPANDED,
            normalStartThreshold = 0.08f,
            delayAppearThreshold = loadingTweaks.contentAppearThresholdPercent / 100f,
            delayCloseThreshold = 1f - (loadingTweaks.contentCloseThresholdPercent / 100f),
            placeholder = {
                if (loadingTweaks.transparentPlaceholders) {
                    Box(
                        Modifier
                            .height(carouselHeight)
                            .fillMaxWidth()
                    )
                } else {
                    AlbumPlaceholder(
                        height = carouselHeight,
                        color = placeholderColor,
                        onColor = placeholderOnColor,
                    )
                }
            }
        ) {
            AlbumCarouselSection(
                currentSong = song,
                queue = currentPlaybackQueue,
                expansionFraction = 1f,
                currentMediaItemIndex = currentMediaItemIndex,
                requestedScrollIndex = requestedScrollIndex,
                onSongSelected = { newSong, index ->
                    if (newSong.id != song.id || index != currentMediaItemIndex) {
                        onSongSelected(newSong, index)
                    }
                },
                onAlbumClick = onAlbumClick,
                carouselStyle = carouselStyle,
                modifier = Modifier.height(carouselHeight),
                albumArtQuality = albumArtQuality,
                // B23-P3: the sheet is mostly open. Below that the carousel is being parked or revealed, not used.
                isOnScreen = { expansionFractionProvider() >= CAROUSEL_ON_SCREEN_FRACTION }
            )
        }
    }
}

@Composable
private fun AlbumPlaceholder(
    height: Dp,
    color: Color,
    onColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(18.dp),
        color = color,
        tonalElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                modifier = Modifier.size(86.dp),
                painter = painterResource(R.drawable.pixelplayer_base_monochrome),
                contentDescription = null,
                tint = onColor
            )
        }
    }
}
