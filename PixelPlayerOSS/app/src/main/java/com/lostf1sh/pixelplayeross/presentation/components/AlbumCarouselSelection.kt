package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.*
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration

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
    isOnScreen: () -> Boolean = { true },
    /**
     * B56-7.2: drawn over a page's still cover, inside the page (so the carousel's rounded mask and a swipe carry it). [isCurrent] is true on the one page that shows the song that is
     * playing; the animated cover is drawn there and nowhere else.
     */
    pageOverlay: @Composable BoxScope.(song: Song, isCurrent: Boolean) -> Unit = { _, _ -> }
) {
    if (queue.isEmpty()) return

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
    // Ordered occurrences identify the pager's geometry. Artwork/statistics republications keep it.
    val queueOrder = remember(queue) { queue.map(Song::id) }
    val pageCount = queue.size
    val carouselState = remember(queueOrder) { CarouselState(currentSongIndex, itemCount = { pageCount }) }
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
    var programmaticScrollInProgress by remember(carouselState) { mutableStateOf(false) }
    var lastSettledSongId by remember(carouselState) { mutableStateOf(currentSong?.id) }
    val fingerTrace = remember(carouselState) { FingerTrace() }
    val latestSong by rememberUpdatedState(currentSong?.id)
    val latestTarget by rememberUpdatedState(effectiveTargetIndex)
    val latestIsOnScreen by rememberUpdatedState(isOnScreen)
    val latestOnSongSelected by rememberUpdatedState(onSongSelected)
    val pager = carouselState.pagerState

    fun resetFinger(reason: String) {
        if (fingerTrace.down || fingerTrace.grabPage != null) {
            CoverFollowLog.reset(latestSong, latestTarget, reason)
        }
        fingerTrace.cancel()
    }

    fun snapToTarget() {
        fingerTrace.grabPage = null
        if (!carouselState.isAtRestOn(latestTarget)) pager.requestScrollToPage(latestTarget)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resyncing by remember(lifecycle) { mutableStateOf(!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle, carouselState) {
        val observer = LifecycleEventObserver { _, _ ->
            val parked = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (parked && !resyncing) {
                resetFinger("parked-or-frozen")
                fingerTrace.resyncOnShow = true
            }
            resyncing = parked
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            resetFinger("pager-disposed")
        }
    }

    val songId = currentSong?.id
    SideEffect {
        fingerTrace.acknowledge(songId, currentSongIndex, queue)
        // These cases bind in the composition commit, independently of the animation coroutine.
        if (!resyncing && (fingerTrace.resyncOnShow || !isOnScreen())) {
            resetFinger("off-screen")
            fingerTrace.resyncOnShow = false
            snapToTarget()
        }
    }

    LaunchedEffect(carouselState) {
        pager.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    if (fingerTrace.down && !resyncing && latestIsOnScreen()) {
                        fingerTrace.activeDrag = interaction
                        fingerTrace.grabPage = pager.currentPage
                    } else {
                        CoverFollowLog.reset(latestSong, latestTarget, "drag-start-without-pointer")
                    }
                }
                is DragInteraction.Stop -> {
                    if (fingerTrace.activeDrag === interaction.start) fingerTrace.activeDrag = null
                }
                is DragInteraction.Cancel -> {
                    if (fingerTrace.activeDrag === interaction.start) resetFinger("drag-cancelled")
                }
            }
        }
    }

    LaunchedEffect(carouselState, songId, effectiveTargetIndex, requestedTargetIndex, queue, resyncing, fingerTrace.releaseGeneration) {
        if (resyncing) return@LaunchedEffect
        var attempts = 0
        try {
            while (attempts++ < MAX_FOLLOW_ATTEMPTS) {
                val isShiftOnly = songId != null && songId == lastSettledSongId && requestedTargetIndex == null
                if (isShiftOnly || !isOnScreen()) {
                    if (!isOnScreen()) resetFinger("off-screen")
                    if (!fingerTrace.down && fingerTrace.grabPage == null) {
                        snapToTarget()
                        break
                    }
                }
                if (carouselState.isAtRestOn(effectiveTargetIndex) && !fingerTrace.down && fingerTrace.grabPage == null) break

                // Without a real drag to settle, an existing scroll cannot hold up the binding.
                if (pager.isScrollInProgress && !fingerTrace.down && fingerTrace.grabPage == null) {
                    snapToTarget()
                    break
                }
                if (pager.isScrollInProgress || fingerTrace.down) {
                    CoverFollowLog.waiting(songId, effectiveTargetIndex, attempts, pager.isScrollInProgress, fingerTrace.down)
                    val idle = withTimeoutOrNull(FOLLOW_WAIT_MS) {
                        snapshotFlow { carouselState.pagerState.isScrollInProgress || fingerTrace.down }.first { !it }
                    }
                    if (idle == null) {
                        CoverFollowLog.reset(songId, effectiveTargetIndex, "wait-timeout")
                        if (fingerTrace.down) {
                            // The raw release will restart this binding. A held finger keeps its gesture.
                            fingerTrace.followAfterRelease = true
                            return@LaunchedEffect
                        }
                        snapToTarget()
                        break
                    }
                    // Let the settle collector record a genuine swipe before we move the pager.
                    yield()
                }
                if (fingerTrace.holdsPage(pager.currentPage, effectiveTargetIndex)) break
                if (carouselState.isAtRestOn(effectiveTargetIndex)) break
                try {
                    programmaticScrollInProgress = true
                    val animated = withTimeoutOrNull(FOLLOW_WAIT_MS) {
                        carouselState.animateScrollToItem(effectiveTargetIndex)
                        true
                    }
                    if (animated == null && !fingerTrace.down && fingerTrace.grabPage == null) {
                        CoverFollowLog.reset(songId, effectiveTargetIndex, "animation-timeout")
                        snapToTarget()
                        break
                    }
                } catch (interrupted: CancellationException) {
                    // A competing scroll may cancel the animation without cancelling this binding.
                    currentCoroutineContext().ensureActive()
                    yield()
                }
            }
        } finally {
            programmaticScrollInProgress = false
        }
        if (attempts > MAX_FOLLOW_ATTEMPTS && !carouselState.isAtRestOn(effectiveTargetIndex)) {
            CoverFollowLog.gaveUp(songId, effectiveTargetIndex, queue.size, carouselState.currentItem, MAX_FOLLOW_ATTEMPTS)
            if (fingerTrace.down) {
                fingerTrace.followAfterRelease = true
                return@LaunchedEffect
            }
            if (!fingerTrace.holdsPage(pager.currentPage, effectiveTargetIndex)) snapToTarget()
        }

        // A requested jump is applied at the next measure. Only the diagnostic waits for layout.
        if (!carouselState.isAtRestOn(effectiveTargetIndex) && !fingerTrace.holdsPage(pager.currentPage, effectiveTargetIndex) && isOnScreen()) {
            withTimeoutOrNull(FOLLOW_WAIT_MS) {
                snapshotFlow { carouselState.isAtRestOn(effectiveTargetIndex) }.first { it }
            }
        }
        lastSettledSongId = songId
        val target = queue.getOrNull(effectiveTargetIndex)
        Timber.tag(CoverFollowLog.TAG).d(
            "COVER_BIND song=%s art=%s page=%d target=%d of=%d rest=%b targetSong=%s",
            songId,
            target?.albumArtUriString?.takeLast(48),
            carouselState.currentItem,
            effectiveTargetIndex,
            queue.size,
            carouselState.isAtRestOn(effectiveTargetIndex),
            target?.id,
        )
    }

    val hapticFeedback = LocalHapticFeedback.current
    LaunchedEffect(carouselState, songId, currentSongIndex, queue, resyncing) {
        snapshotFlow { pager.isScrollInProgress || fingerTrace.down }
            .filter { !it }
            .collect {
                if (resyncing || !isOnScreen()) return@collect
                val grabbedOn = fingerTrace.grabPage ?: return@collect
                fingerTrace.grabPage = null
                val settled = pager.currentPage
                if (grabbedOn == settled || settled == currentSongIndex) return@collect
                val selected = queue.getOrNull(settled) ?: return@collect
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                fingerTrace.choice = CoverChoice(settled, songId, currentSongIndex, queue)
                latestOnSongSelected(selected, settled)
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
            modifier = Modifier.width(availableWidth).fillMaxHeight().align(androidx.compose.ui.Alignment.TopCenter)
                .pointerInput(carouselState) {
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.any { it.pressed }
                                if (pressed && event.changes.any { !it.previousPressed } && !resyncing && latestIsOnScreen()) {
                                    fingerTrace.down = true
                                } else if (!pressed && fingerTrace.down) {
                                    if (event.changes.any { it.isConsumed } || !latestIsOnScreen()) {
                                        resetFinger("pointer-cancelled-or-hidden")
                                    } else {
                                        fingerTrace.release()
                                    }
                                }
                            }
                        }
                    } finally {
                        resetFinger("pointer-input-ended")
                    }
                },
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
                    pageOverlay(song, isFocusedItem && song.id == currentSong?.id)
                }
            }
        )
    }
}

internal object CoverFollowLog {
    const val TAG = "CoverBind"

    fun waitingLine(song: String?, target: Int, attempt: Int, scrolling: Boolean, fingerDown: Boolean): String {
        val why = when {
            fingerDown && scrolling -> "finger down and scroll in progress"
            fingerDown -> "finger down"
            else -> "scroll in progress"
        }
        return "COVER_BIND_WAIT song=$song target=$target attempt=$attempt why=$why"
    }

    fun gaveUpLine(song: String?, target: Int, queueSize: Int, page: Int, attempts: Int): String =
        "COVER_BIND_GAVE_UP song=$song target=$target of=$queueSize page=$page attempts=$attempts"

    fun waiting(song: String?, target: Int, attempt: Int, scrolling: Boolean, fingerDown: Boolean) {
        Timber.tag(TAG).d(waitingLine(song, target, attempt, scrolling, fingerDown))
    }

    fun gaveUp(song: String?, target: Int, queueSize: Int, page: Int, attempts: Int) {
        Timber.tag(TAG).d(gaveUpLine(song, target, queueSize, page, attempts))
    }

    fun reset(song: String?, target: Int, reason: String) {
        Timber.tag(TAG).d("COVER_BIND_RESET song=%s target=%d reason=%s", song, target, reason)
    }
}

private const val MAX_FOLLOW_ATTEMPTS = 6
private const val FOLLOW_WAIT_MS = 1_000L

private class FingerTrace {
    // Raw pointer lifetime is independent of the interaction source's lossy buffer.
    var down by mutableStateOf(false)
    var activeDrag: DragInteraction.Start? = null
    var grabPage: Int? = null
    var choice: CoverChoice? = null
    var resyncOnShow = false
    var followAfterRelease = false
    var releaseGeneration by mutableIntStateOf(0)
        private set

    fun release() {
        down = false
        activeDrag = null
        if (followAfterRelease) {
            followAfterRelease = false
            releaseGeneration++
        }
    }

    fun cancel() {
        grabPage = null
        release()
    }

    fun acknowledge(song: String?, page: Int, queue: ImmutableList<Song>) {
        val pending = choice ?: return
        if (pending.fromSong != song || pending.fromPage != page || pending.queue != queue) choice = null
    }

    fun holdsPage(page: Int, target: Int): Boolean =
        choice?.let { it.page == page && it.fromPage == target } == true
}

private data class CoverChoice(
    val page: Int,
    val fromSong: String?,
    val fromPage: Int,
    val queue: ImmutableList<Song>,
)

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
