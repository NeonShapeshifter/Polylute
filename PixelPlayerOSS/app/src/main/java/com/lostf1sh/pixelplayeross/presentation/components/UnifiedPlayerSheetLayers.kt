package com.lostf1sh.pixelplayeross.presentation.components

import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.components.player.FullPlayerContent
import com.lostf1sh.pixelplayeross.presentation.components.scoped.FullPlayerVisualState
import com.lostf1sh.pixelplayeross.presentation.components.scoped.ParkedWhileCollapsed
import com.lostf1sh.pixelplayeross.presentation.components.scoped.rememberFullPlayerRuntimePolicy
import com.lostf1sh.pixelplayeross.presentation.components.scoped.rememberHeldWhile
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette

@OptIn(UnstableApi::class)
@Composable
internal fun BoxScope.UnifiedPlayerMiniAndFullLayers(
    currentSong: Song?,
    miniPlayerScheme: ColorScheme?,
    overallSheetTopCornerRadiusProvider: () -> Dp,
    infrequentPlayerState: StablePlayerState,
    isPreparingPlayback: Boolean,
    playerContentExpansionFraction: Animatable<Float, AnimationVector1D>,
    albumColorScheme: ColorScheme,
    /** The cover's accent for the full player (B29-L11), or null to keep the app's. The docked mini player stays graphite, as it was designed. */
    albumAccent: androidx.compose.ui.graphics.Color? = null,
    bottomSheetOpenFraction: Float,
    fullPlayerVisualState: FullPlayerVisualState,
    containerHeight: Dp,
    currentQueueSourceName: String,
    currentSheetContentState: PlayerSheetState,
    carouselStyle: String,
    fullPlayerLoadingTweaks: FullPlayerLoadingTweaks,
    isSheetDragGestureActive: Boolean = false,
    playerViewModel: PlayerViewModel,
    currentPositionProvider: () -> Long,
    isFavorite: Boolean,
    shouldRenderFullPlayer: Boolean = true,
    /** B33-U2: how far the mini player's content is carried by its sideways swipe, in px (read where it is drawn). */
    miniSwipeOffsetProvider: () -> Float = { 0f },
    onShowSongInfo: () -> Unit,
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit
) {
    currentSong?.let { currentSongNonNull ->
        miniPlayerScheme?.let { readyScheme ->
            CompositionLocalProvider(
                LocalMaterialTheme provides readyScheme
            ) {
                val miniPlayerZIndex by remember {
                    derivedStateOf {
                        if (playerContentExpansionFraction.value < 0.5f) 1f else 0f
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(MiniPlayerHeight)
                        .graphicsLayer {
                            alpha = (1f - playerContentExpansionFraction.value * 2f)
                                .coerceIn(0f, 1f)
                        }
                        .zIndex(miniPlayerZIndex)
                ) {
                    val isMiniPlayerVisible by remember {
                        derivedStateOf { playerContentExpansionFraction.value < 0.01f }
                    }
                    MiniPlayerContentInternal(
                        song = currentSongNonNull,
                        isPlaying = infrequentPlayerState.isPlaying,
                        isPreparingPlayback = isPreparingPlayback,
                        onPlayPause = { playerViewModel.playPause() },
                        onPrevious = { playerViewModel.previousSong() },
                        onNext = { playerViewModel.nextSong() },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = miniSwipeOffsetProvider() }
                    )
                }
            }
        }

        if (shouldRenderFullPlayer) {
            // Now Playing is graphite. The artwork carries the colour; the surface does not.
            // Album-derived tint stays available as the low-chroma wash behind the cover only.
            CompositionLocalProvider(
                LocalMaterialTheme provides dapPlayerScheme
            ) {
                val fullPlayerScale by remember(bottomSheetOpenFraction) {
                    derivedStateOf { lerp(1f, 0.972f, bottomSheetOpenFraction) }
                }

                val fullPlayerZIndex by remember {
                    derivedStateOf {
                        if (playerContentExpansionFraction.value >= 0.5f) 1f else 0f
                    }
                }
                val fullPlayerOffset by remember {
                    derivedStateOf {
                        if (playerContentExpansionFraction.value <= 0.01f) IntOffset(0, 10000)
                        else IntOffset.Zero
                    }
                }
                val fullPlayerRuntimePolicy = rememberFullPlayerRuntimePolicy(
                    currentSheetState = currentSheetContentState,
                    expansionFraction = playerContentExpansionFraction,
                    bottomSheetOpenFraction = bottomSheetOpenFraction
                )

                val liveQueue by playerViewModel.queueFlow
                    .collectAsStateWithLifecycle()

                // B23-P3: parked = collapsed and offscreen (the same test as `fullPlayerOffset`). While it is, what the full
                // player is given does not change, so a tap on a row in the Library or a skip does not make it animate its
                // carousel to the new index, rebuild its page keys or lay itself out: B22 measured two frames of 160-630 ms
                // for every tap, and media-next (one index away) cost none. The live values flow in the moment it is not
                // parked, in one recomposition, and the carousel snaps to the current index without animating.
                val parked by remember(currentSheetContentState) {
                    derivedStateOf {
                        currentSheetContentState != PlayerSheetState.EXPANDED &&
                            playerContentExpansionFraction.value <= 0.01f
                    }
                }
                val heldSong = rememberHeldWhile(parked, currentSongNonNull)
                val heldInfrequentState = rememberHeldWhile(parked, infrequentPlayerState)
                val heldIsFavorite = rememberHeldWhile(parked, isFavorite)
                val heldQueueSourceName = rememberHeldWhile(parked, currentQueueSourceName)
                val currentPlaybackQueue = rememberHeldWhile(parked, liveQueue)
                // The accent follows the cover while the player can be seen and is held while it is parked, like everything else it is given.
                val appAccent = LocalDapAccent.current
                val playerAccent = rememberHeldWhile(parked, albumAccent) ?: appAccent

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(containerHeight)
                        .graphicsLayer {
                            alpha = fullPlayerVisualState.contentAlpha
                            translationY = fullPlayerVisualState.translationY
                            scaleX = fullPlayerScale
                            scaleY = fullPlayerScale
                        }
                        .zIndex(fullPlayerZIndex)
                        .offset { fullPlayerOffset }
                ) {
                    ParkedWhileCollapsed(parked = parked, modifier = Modifier.fillMaxSize()) {
                        val latestInfrequentPlayerState = rememberUpdatedState(heldInfrequentState)
                        val latestIsFavorite = rememberUpdatedState(heldIsFavorite)
                        val expansionFractionProvider = remember(playerContentExpansionFraction) {
                            { playerContentExpansionFraction.value }
                        }
                        val isPlayingProvider = remember {
                            { latestInfrequentPlayerState.value.isPlaying }
                        }
                        val playWhenReadyProvider = remember {
                            { latestInfrequentPlayerState.value.playWhenReady }
                        }
                        val totalDurationProvider = remember {
                            { latestInfrequentPlayerState.value.totalDuration }
                        }
                        val lyricsProvider = remember {
                            { latestInfrequentPlayerState.value.lyrics }
                        }
                        val lyricsResolveCompletedProvider = remember {
                            { latestInfrequentPlayerState.value.lyricsResolveCompleted }
                        }
                        val externalLyricsEnabled by playerViewModel.externalLyricsEnabled
                            .collectAsStateWithLifecycle()
                        val isFavoriteProvider = remember {
                            { latestIsFavorite.value }
                        }
                        val onPlayPause = remember(playerViewModel) { playerViewModel::playPause }
                        val onSeek = remember(playerViewModel) { playerViewModel::seekTo }
                        val onNext = remember(playerViewModel) { playerViewModel::nextSong }
                        val onPrevious = remember(playerViewModel) { playerViewModel::previousSong }
                        val onCollapse = remember(playerViewModel) {
                            { playerViewModel.collapsePlayerSheet() }
                        }
                        val onShuffleToggle = remember(playerViewModel) {
                            { playerViewModel.toggleShuffle() }
                        }
                        val onRepeatToggle = remember(playerViewModel) { playerViewModel::cycleRepeatMode }
                        val onFavoriteToggle = remember(playerViewModel) { playerViewModel::toggleFavorite }

                        CompositionLocalProvider(LocalDapAccent provides playerAccent) {
                        FullPlayerContent(
                            currentSong = heldSong,
                            currentPlaybackQueue = currentPlaybackQueue,
                            currentQueueSourceName = heldQueueSourceName,
                            currentMediaItemIndex = heldInfrequentState.currentMediaItemIndex,
                            isShuffleEnabled = heldInfrequentState.isShuffleEnabled,
                            repeatMode = heldInfrequentState.repeatMode,
                            allowRealtimeUpdates = fullPlayerRuntimePolicy.allowRealtimeUpdates,
                            expansionFractionProvider = expansionFractionProvider,
                            currentSheetState = currentSheetContentState,
                            carouselStyle = carouselStyle,
                            loadingTweaks = fullPlayerLoadingTweaks,
                            isSheetDragGestureActive = isSheetDragGestureActive,
                            playerViewModel = playerViewModel,
                            currentPositionProvider = currentPositionProvider,
                            isPlayingProvider = isPlayingProvider,
                            playWhenReadyProvider = playWhenReadyProvider,
                            totalDurationProvider = totalDurationProvider,
                            lyricsProvider = lyricsProvider,
                            lyricsResolveCompletedProvider = lyricsResolveCompletedProvider,
                            externalLyricsEnabled = externalLyricsEnabled,
                            isFavoriteProvider = isFavoriteProvider,
                            onPlayPause = onPlayPause,
                            onSeek = onSeek,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onCollapse = onCollapse,
                            onShowSongInfo = onShowSongInfo,
                            onQueueDragStart = onQueueDragStart,
                            onQueueDrag = onQueueDrag,
                            onQueueRelease = onQueueRelease,
                            onShuffleToggle = onShuffleToggle,
                            onRepeatToggle = onRepeatToggle,
                            onFavoriteToggle = onFavoriteToggle
                        )
                        }
                    }
                }
            }
        }
    }
}


/** The player's surface scheme. Graphite, always; the accent is the one curated accent. */
// A composable getter, not a stored value. As a static `val` this captured one accent at class
// initialisation and could never follow a palette or accent change.
private val dapPlayerScheme: ColorScheme
    @Composable get() = dapColorScheme(
        LocalDapPalette.current.presentedAccent(LocalDapAccent.current),
        LocalDapPalette.current,
    )
