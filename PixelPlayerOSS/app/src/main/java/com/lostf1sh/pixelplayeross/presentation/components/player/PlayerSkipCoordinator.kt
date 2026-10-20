package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PreviousTrackPolicy
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

internal enum class SkipDirection { PREVIOUS, NEXT }

/**
 * What the transport's skip buttons and the artwork carousel agree on.
 *
 * A skip is requested here: the artwork starts scrolling to where the skip will land at once
 * ([pendingCarouselIndex]) and the player is told in the order the requests came ([requestNext],
 * [requestPrevious]).
 */
@Stable
internal class PlayerSkipCoordinator(
    /** The queue position of the song on screen (the player's index, checked against the queue). */
    val currentQueueIndex: Int?,
    /** Where the artwork is already heading, or null. */
    val pendingCarouselIndex: Int?,
    val requestNext: () -> Unit,
    val requestPrevious: () -> Unit,
)

/**
 * Builds the coordinator and runs its collector. Moved here unchanged from `FullPlayerContent` (B21): the order of
 * requests is the order of dispatches, and a single skip is never delayed by anything.
 */
@Composable
internal fun rememberPlayerSkipCoordinator(
    song: Song,
    currentPlaybackQueue: ImmutableList<Song>,
    currentMediaItemIndex: Int,
    repeatMode: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    previousDecisionPositionMs: () -> Long,
): PlayerSkipCoordinator {
    var pendingCarouselIndex by remember { mutableStateOf<Int?>(null) }
    val currentQueueIndex = remember(song.id, currentMediaItemIndex, currentPlaybackQueue) {
        resolveQueueIndex(
            queue = currentPlaybackQueue,
            songId = song.id,
            currentMediaItemIndex = currentMediaItemIndex
        )
    }
    val skipRequests = remember {
        MutableSharedFlow<SkipDirection>(
            extraBufferCapacity = 16
        )
    }
    val latestQueue by rememberUpdatedState(currentPlaybackQueue)
    val latestSongId by rememberUpdatedState(song.id)
    val latestCurrentQueueIndex by rememberUpdatedState(currentQueueIndex)
    val latestRepeatMode by rememberUpdatedState(repeatMode)
    val latestOnNext by rememberUpdatedState(onNext)
    val latestOnPrevious by rememberUpdatedState(onPrevious)

    LaunchedEffect(currentQueueIndex, pendingCarouselIndex) {
        if (pendingCarouselIndex == currentQueueIndex) {
            pendingCarouselIndex = null
        }
    }

    LaunchedEffect(pendingCarouselIndex, currentQueueIndex) {
        val targetIndex = pendingCarouselIndex ?: return@LaunchedEffect
        kotlinx.coroutines.delay(900)
        if (pendingCarouselIndex == targetIndex && currentQueueIndex != targetIndex) {
            pendingCarouselIndex = null
        }
    }

    // Skips are dispatched the moment they are requested. Ordering is already guaranteed: this is
    // a single collector over an ordered, buffered channel, and the command underneath
    // (ExoPlayer seekToNext / seekToPrevious) is a synchronous playlist operation, so N requests
    // advance exactly N items in the order they were asked for.
    //
    // This used to hold a fixed 96 ms cooldown after every dispatch. It never gated the first
    // command, so a single skip was never delayed by it — what it actually paced was a *burst* of
    // taps, which meant ten quick taps kept skipping for most of a second after the user had
    // stopped, and the cooldown could stretch well past 96 ms when the main thread was busy
    // (measured up to ~224 ms). It protected no invariant the collector does not already provide,
    // so it is gone.
    LaunchedEffect(skipRequests) {
        skipRequests.collect { direction ->
            when (direction) {
                SkipDirection.NEXT -> latestOnNext()
                SkipDirection.PREVIOUS -> latestOnPrevious()
            }
        }
    }

    fun predictSkipCarouselIndex(direction: SkipDirection): Int? {
        val queueSnapshot = latestQueue
        val baseIndex = pendingCarouselIndex
            ?: latestCurrentQueueIndex
            ?: queueSnapshot.indexOfFirst { it.id == latestSongId }.takeIf { it >= 0 }

        return when (direction) {
            SkipDirection.NEXT -> predictSkipNextCarouselIndex(
                currentIndex = baseIndex,
                queue = queueSnapshot,
                repeatMode = latestRepeatMode
            )
            SkipDirection.PREVIOUS -> predictSkipPreviousCarouselIndex(
                currentIndex = baseIndex,
                queue = queueSnapshot,
                // The same engine position previousSong() decides with, not the display tick.
                currentPositionMs = previousDecisionPositionMs(),
                repeatMode = latestRepeatMode
            )
        }
    }

    fun requestSkip(direction: SkipDirection) {
        val predictedTargetIndex = predictSkipCarouselIndex(direction)
        if (skipRequests.tryEmit(direction) && predictedTargetIndex != null) {
            pendingCarouselIndex = predictedTargetIndex
        }
    }

    val onNextWithOptimisticCarousel = {
        requestSkip(SkipDirection.NEXT)
        Unit
    }

    val onPreviousWithOptimisticCarousel = {
        requestSkip(SkipDirection.PREVIOUS)
        Unit
    }
    return PlayerSkipCoordinator(
        currentQueueIndex = currentQueueIndex,
        pendingCarouselIndex = pendingCarouselIndex,
        requestNext = onNextWithOptimisticCarousel,
        requestPrevious = onPreviousWithOptimisticCarousel,
    )
}

internal fun resolveQueueIndex(
    queue: ImmutableList<Song>,
    songId: String,
    currentMediaItemIndex: Int
): Int? {
    if (currentMediaItemIndex in queue.indices && queue[currentMediaItemIndex].id == songId) {
        return currentMediaItemIndex
    }
    return queue.indexOfFirst { it.id == songId }.takeIf { it >= 0 }
}

internal fun predictSkipNextCarouselIndex(
    currentIndex: Int?,
    queue: ImmutableList<Song>,
    repeatMode: Int
): Int? {
    if (queue.size <= 1) return null
    val safeCurrentIndex = currentIndex?.takeIf { it in queue.indices } ?: return null

    return when {
        safeCurrentIndex < queue.lastIndex -> safeCurrentIndex + 1
        repeatMode == Player.REPEAT_MODE_ALL -> 0
        else -> null
    }
}

internal fun predictSkipPreviousCarouselIndex(
    currentIndex: Int?,
    queue: ImmutableList<Song>,
    currentPositionMs: Long,
    repeatMode: Int
): Int? {
    if (queue.size <= 1) return null
    val safeCurrentIndex = currentIndex?.takeIf { it in queue.indices } ?: return null
    val previousIndex = when {
        safeCurrentIndex > 0 -> safeCurrentIndex - 1
        repeatMode == Player.REPEAT_MODE_ALL -> queue.lastIndex
        else -> null
    }
    // One rule with PlaybackStateHolder.previousSong(): a restart moves no artwork (AUD-116).
    if (PreviousTrackPolicy.restartsCurrent(currentPositionMs, hasPrevious = previousIndex != null)) return null
    return previousIndex
}
