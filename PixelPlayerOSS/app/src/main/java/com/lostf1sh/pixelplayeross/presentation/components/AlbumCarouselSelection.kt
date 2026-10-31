package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import coil.size.Size
import com.lostf1sh.pixelplayeross.presentation.components.scoped.respectSystemBackEdges
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.presentation.components.scoped.PrefetchAlbumNeighbors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException

import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberRoundedParallaxCarouselState(
    initialPage: Int,
    pageCount: () -> Int
): CarouselState = rememberCarouselState(initialItem = initialPage, itemCount = pageCount)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumCarouselSection(
    currentSong: Song?,
    queue: ImmutableList<Song>,
    expansionFraction: Float,
    currentMediaItemIndex: Int = -1,
    requestedScrollIndex: Int? = null,
    onSongSelected: (Song, Int) -> Unit,
    onAlbumClick: (Song) -> Unit = {},
    modifier: Modifier = Modifier,
    carouselStyle: String = CarouselStyle.NO_PEEK,
    itemSpacing: Dp = 8.dp,
    albumArtQuality: AlbumArtQuality = AlbumArtQuality.MEDIUM,
    /**
     * B23-P3: whether the carousel is on screen *now*, read when it has something to do (never during composition). Off
     * screen it is moved to a new index **without animating** and a settle it did not cause by a finger is never taken for
     * the listener's choice. The full player is parked offscreen while the sheet is collapsed, holds the song it had, and on
     * expand is handed the current one: it must jump there, not scroll a distant index at the start of the sheet's
     * own animation, and it must not answer its own resync with a playback command.
     */
    isOnScreen: () -> Boolean = { true }
) {
    if (queue.isEmpty()) return

    val initialIndex = remember(currentSong?.id, currentMediaItemIndex, queue) {
        resolveCurrentQueueIndex(
            currentSong = currentSong,
            currentMediaItemIndex = currentMediaItemIndex,
            queue = queue
        )
    }

    val carouselState = rememberRoundedParallaxCarouselState(
        initialPage = initialIndex,
        pageCount = { queue.size }
    )

    // Never decode past what the screen can show. "Original" is the quality of the source, not a
    // decode size: at 2048 px every carousel page cost a 16 MB bitmap plus GC on each track change
    // (the P0 of MOTION-PERFORMANCE.md), for artwork drawn at ~600 px on a 720 px screen.
    val screenPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val targetSize = remember(albumArtQuality, screenPx) {
        val requested = if (albumArtQuality.maxSize == 0) MaxSafeAlbumArtDimensionPx else albumArtQuality.maxSize
        val side = minOf(requested, screenPx).coerceAtLeast(1)
        Size(side, side)
    }

    val currentSongIndex = remember(currentSong?.id, currentMediaItemIndex, queue) {
        resolveCurrentQueueIndex(
            currentSong = currentSong,
            currentMediaItemIndex = currentMediaItemIndex,
            queue = queue
        )
    }
    val requestedTargetIndex = remember(requestedScrollIndex, queue) {
        requestedScrollIndex?.takeIf { it in queue.indices }
    }
    val effectiveTargetIndex = requestedTargetIndex ?: currentSongIndex
    val carouselItemKeys = remember(queue) {
        buildQueueOccurrenceKeys(queue)
    }

    PrefetchAlbumNeighbors(
        isActive = expansionFraction > 0.08f,
        pagerState = carouselState.pagerState,
        queue = queue,
        radius = 1,
        targetSize = targetSize,
        anchorIndex = effectiveTargetIndex
    )
    var ignoreNextSettledSelectionForPage by remember { mutableStateOf<Int?>(null) }
    var programmaticScrollInProgress by remember { mutableStateOf(false) }
    // B23-P3: the carousel is moving itself (a jump to the current index), and what it settles on is its own doing.
    var resyncing by remember { mutableStateOf(false) }
    var lastSettledSongId by remember { mutableStateOf(currentSong?.id) }
    // B48-fix1: what the listener's finger did to the carousel, for the two effects below (see [FingerTrace]).
    val fingerTrace = remember { FingerTrace() }
    LaunchedEffect(carouselState) {
        carouselState.pagerState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    fingerTrace.down = true
                    fingerTrace.dragged = true
                    fingerTrace.grabPage = carouselState.pagerState.currentPage
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> fingerTrace.down = false
            }
        }
    }
    LaunchedEffect(effectiveTargetIndex, requestedTargetIndex, queue) {
        // B48-fix1: carries the cover to the current item and does not stop until it is there. A scroll of the follower's own can be taken over (a finger that lands on the
        // cover while it is on its way, the settle correction) and the take-over ends it with a CancellationException that is not this effect's own: the effect used to end with it,
        // leaving the cover wherever the other scroll put it. It waits for the other scroll to end and looks again, unless what that scroll chose is the listener's.
        var attempts = 0
        try {
            while (attempts++ < MAX_FOLLOW_ATTEMPTS) {
                // A held finger counts as a scroll in progress: its drag holds the pager's scroll but `isScrollInProgress` stays false until it moves, and an attempt made
                // under it fails at once (every attempt would be spent before the finger lifts).
                snapshotFlow { carouselState.pagerState.isScrollInProgress || fingerTrace.down }
                    .first { !it }
                // The settle collector below wakes on the same end of a scroll; after one yield it has said whether the listener chose the page the cover rests on.
                yield()
                if (fingerTrace.chosenPage == carouselState.pagerState.currentPage && fingerTrace.chosenWhileTargetWas == effectiveTargetIndex) break
                // "There" is the page AND the offset. This effect is keyed on the queue too: when the queue is published again while the cover is on its way (artwork or
                // statistics of the playing song, a moment after a skip) it restarts, and a scroll cancelled after the middle leaves `currentPage` on the target and the cover short of it.
                if (carouselState.isAtRestOn(effectiveTargetIndex)) break
                try {
                    val isShiftOnly = currentSong?.id != null &&
                                      currentSong.id == lastSettledSongId &&
                                      requestedTargetIndex == null

                    if (isShiftOnly || !isOnScreen()) {
                        // A queue shift, or a carousel nobody can see: jump, no animation. `resyncing` stays up until the pager reports the
                        // target page and one frame has gone by, so the settle collector below cannot read the end of this jump (or the
                        // page the pager still showed before it) as a swipe.
                        resyncing = true
                        try {
                            carouselState.pagerState.scrollToPage(effectiveTargetIndex)
                            withFrameNanos { }
                        } finally {
                            resyncing = false
                        }
                    } else {
                        if (requestedTargetIndex != null) {
                            ignoreNextSettledSelectionForPage = effectiveTargetIndex
                        }
                        // Up for the whole follow and not per attempt: between two attempts the NO_PEEK settle correction would otherwise wake, snap the cover back to the page it
                        // is nearest to (the previous song's, before the middle of the trip) and take the next attempt's animation away from it.
                        programmaticScrollInProgress = true
                        carouselState.animateScrollToItem(effectiveTargetIndex)
                    }
                } catch (interrupted: CancellationException) {
                    // This effect's own cancellation (a new target, the carousel leaving the composition) ends it here; another scroll taking over goes round again.
                    currentCoroutineContext().ensureActive()
                }
            }
        } finally {
            programmaticScrollInProgress = false
        }
        lastSettledSongId = currentSong?.id
    }

    val hapticFeedback = LocalHapticFeedback.current
    LaunchedEffect(carouselState, currentSongIndex, queue) {
        snapshotFlow { carouselState.pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                // B23-P3: a carousel that is moving itself, or that nobody can see, has nobody swiping it.
                if (resyncing || !isOnScreen()) return@collect
                val settled = carouselState.pagerState.currentPage
                if (ignoreNextSettledSelectionForPage == settled) {
                    ignoreNextSettledSelectionForPage = null
                    return@collect
                }
                // B48-fix1: only a drag chooses a song. A touch that stops a skip's animation makes the scroll end half way (that end is read here before the drag, if there is one,
                // has begun), and the cover that is then let go settles on the previous page: neither is the listener asking for the previous song. And a drag that ends on the
                // page it began on chose nothing either.
                val dragged = fingerTrace.dragged
                val grabbedOn = fingerTrace.grabPage
                if (!fingerTrace.down) {
                    fingerTrace.dragged = false
                    fingerTrace.grabPage = null
                }
                if (!dragged || grabbedOn == settled) return@collect
                if (settled != currentSongIndex) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    fingerTrace.chosenPage = settled
                    fingerTrace.chosenWhileTargetWas = currentSongIndex
                    queue.getOrNull(settled)?.let { onSongSelected(it, settled) }
                }
            }
    }

    val corner = 18.dp

    // Edge-origin drags belong to the system Back gesture; centre drags stay with the pager.
    BoxWithConstraints(modifier = modifier.respectSystemBackEdges()) {
        val factor = if (carouselState.pagerState.pageCount == 1 || carouselStyle == CarouselStyle.NO_PEEK) 1f
            else if (carouselStyle == CarouselStyle.TWO_PEEK) 0.6f else 0.8f
        val availableWidth = minOf(maxWidth, maxHeight / factor)

        RoundedHorizontalMultiBrowseCarousel(
            state = carouselState,
            modifier = Modifier.width(availableWidth).fillMaxHeight().align(androidx.compose.ui.Alignment.TopCenter),
            itemSpacing = itemSpacing,
            itemCornerRadius = corner,
            suppressNoPeekSettleCorrection = requestedTargetIndex != null || programmaticScrollInProgress || resyncing,
            carouselStyle = if (carouselState.pagerState.pageCount == 1) CarouselStyle.NO_PEEK else carouselStyle,
            carouselWidth = availableWidth,
            itemKey = { index -> carouselItemKeys.getOrNull(index) ?: "queue_item_$index" },
            content = { index ->
                val song = queue[index]
                val isFocusedItem = carouselState.pagerState.currentPage == index
                Box(
                    Modifier
                        .fillMaxSize()
                        .aspectRatio(1f)
                        .clickable(
                            enabled = isFocusedItem && song.albumId != -1L,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onAlbumClick(song) }
                        )
                ) {
                    OptimizedAlbumArt(
                        uri = song.albumArtUriString,
                        title = song.title,
                        modifier = Modifier.fillMaxSize(),
                        targetSize = targetSize,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
            }
        )
    }
}

/** How many times the follower looks again after another scroll took over from it: a listener poking at the cover is a few, never this many. */
private const val MAX_FOLLOW_ATTEMPTS = 6

/**
 * B48-fix1: what the listener's finger did to the carousel, read by the follower and the settle collector. [dragged] is whether a drag has begun since the last settle was
 * looked at, [grabPage] the page the cover was nearest to when it began and [down] (observable) whether it is still going; [chosenPage] is a page the settle collector asked the player to play
 * while the current item was [chosenWhileTargetWas], so the follower does not pull the cover back from it before the player has answered. Touched only on the main thread.
 */
private class FingerTrace {
    var down by mutableStateOf(false)
    var dragged = false
    var grabPage: Int? = null
    var chosenPage: Int? = null
    var chosenWhileTargetWas: Int? = null
}

private fun resolveCurrentQueueIndex(
    currentSong: Song?,
    currentMediaItemIndex: Int,
    queue: ImmutableList<Song>
): Int {
    val songId = currentSong?.id ?: return 0
    if (currentMediaItemIndex in queue.indices && queue[currentMediaItemIndex].id == songId) {
        return currentMediaItemIndex
    }
    return queue.indexOfFirst { it.id == songId }
        .takeIf { it >= 0 }
        ?: queue.indexOf(currentSong)
            .takeIf { it >= 0 }
        ?: 0
}

private fun buildQueueOccurrenceKeys(queue: ImmutableList<Song>): List<String> {
    val occurrencesBySongId = HashMap<String, Int>()
    return queue.mapIndexed { index, song ->
        val occurrence = occurrencesBySongId.getOrDefault(song.id, 0)
        occurrencesBySongId[song.id] = occurrence + 1
        "queue_carousel_${song.id}_${occurrence}_${song.albumArtUriString.orEmpty().hashCode()}_$index"
    }
}
