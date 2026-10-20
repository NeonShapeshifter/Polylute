package com.lostf1sh.pixelplayeross.presentation.components.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.components.rememberModalSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.ui.ds.DapMotion
import com.lostf1sh.pixelplayeross.ui.ds.DapPlayerHeader

/**
 * The full player: the header, the surfaces, and the layers over them. It draws a [FullPlayerUiState] and calls
 * a [FullPlayerActions]; it never sees a ViewModel (B21: the 2,362-line `FullPlayerContent` it came out of did).
 *
 * What is in the file, and nothing more:
 *  - **header / details entry / queue entry**: the Scaffold's top bar (collapse, the Artwork / Lyrics / Details
 *    tabs, the queue glyph) and the upward drag that pulls the queue sheet up;
 *  - **the surfaces** (cover, track info, progress with its technical line, controls), each given only its own
 *    slice of the state, in the portrait or the landscape layout;
 *  - **the layers over them** (lyrics, the bookmark dialog, the artist picker).
 *
 * UI-only state lives here (which layer is open, the skip coordination); nothing that has to survive the screen does.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FullPlayerScreen(state: FullPlayerUiState, actions: FullPlayerActions) {
    val song = state.cover.song
    var showArtistPicker by rememberSaveable { mutableStateOf(false) }
    var showSaveBookmarkDialog by rememberSaveable { mutableStateOf(false) }

    val navigationBarBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val queueGestureBottomExclusion = maxOf(20.dp, navigationBarBottomInset + 8.dp)
    val queueGestureBottomExclusionPx = with(LocalDensity.current) {
        queueGestureBottomExclusion.toPx()
    }

    // B29-L7: the queue swipe starts at the transport controls and below (see [QueueSwipePolicy]).
    val queueZone = remember { QueueSwipeZone() }

    val lyricsEntry = rememberPlayerLyricsEntry(song.id, state.lyrics, actions)

    val skip = rememberPlayerSkipCoordinator(
        song = song,
        currentPlaybackQueue = state.cover.queue,
        currentMediaItemIndex = state.cover.mediaItemIndex,
        repeatMode = state.controls.repeatMode,
        onNext = actions::onNext,
        onPrevious = actions::onPrevious,
        previousDecisionPositionMs = actions::previousDecisionPositionMs,
    )
    // The actions the surfaces call for a skip go through the coordinator of *this* composition.
    val latestSkip by rememberUpdatedState(skip)
    val surfaceActions = remember(actions) {
        object : FullPlayerActions by actions {
            override fun onSkipNext() = latestSkip.requestNext()
            override fun onSkipPrevious() = latestSkip.requestPrevious()
        }
    }

    val cover = state.cover.copy(
        mediaItemIndex = skip.currentQueueIndex ?: state.cover.mediaItemIndex,
        requestedScrollIndex = skip.pendingCarouselIndex,
    )

    val onArtistClick = remember(state.trackInfo.artists, song.artistId, actions) {
        {
            val resolvedArtistId = state.trackInfo.artists.firstOrNull { it.id != 0L && it.id != -1L }?.id ?: song.artistId
            if (state.trackInfo.artists.size > 1) {
                showArtistPicker = true
            } else {
                actions.onOpenArtist(resolvedArtistId)
            }
        }
    }
    val onAddBookmark = remember { { showSaveBookmarkDialog = true } }

    if (showSaveBookmarkDialog) {
        val bookmarkProgressMs = remember(showSaveBookmarkDialog) { state.progress.currentPositionProvider() }
        val lyricBookmarkTitle = resolveLyricBookmarkTitle(
            lyrics = state.lyrics.lyricsProvider(),
            timestampMs = bookmarkProgressMs,
            lyricsSyncOffsetMs = state.lyrics.lyricsSyncOffset
        )
        MaterialTheme(
            colorScheme = LocalMaterialTheme.current,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes
        ) {
            AddBookmarkDialog(
                currentProgressMs = bookmarkProgressMs,
                lyricTitle = lyricBookmarkTitle,
                onSave = { title ->
                    actions.onAddBookmark(song, title, bookmarkProgressMs)
                    showSaveBookmarkDialog = false
                },
                onDismiss = { showSaveBookmarkDialog = false }
            )
        }
    }

    val isLandscape = state.isLandscape
    // The header reads only this: a song change replaces `state`, and a header that captured `state` would redraw with it.
    val expansionFractionProvider = state.env.expansionFractionProvider

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier
            .onGloballyPositioned { queueZone.screenTopInWindow = it.positionInWindow().y }
            .playerQueueDragGesture(
                currentSheetState = state.env.currentSheetState,
                expansionFractionProvider = state.env.expansionFractionProvider,
                bottomExclusionPx = queueGestureBottomExclusionPx,
                zone = queueZone,
                actions = actions,
            ),
        topBar = {
            AnimatedVisibility(
                visible = !isLandscape,
                // The header arrives with the surface, not after it: Surface timing, not 350 ms.
                enter = fadeIn(animationSpec = DapMotion.surface()) +
                        slideInVertically(
                            initialOffsetY = { -it / 2 },
                            animationSpec = DapMotion.surface()
                        ),
                exit = fadeOut(animationSpec = DapMotion.surfaceExit()) +
                       slideOutVertically(
                           targetOffsetY = { -it / 2 },
                           animationSpec = DapMotion.surfaceExit()
                       )
            ) {
                DapPlayerHeader(
                    modifier = Modifier.graphicsLayer {
                        val fraction = expansionFractionProvider()
                        alpha = (fraction / 1f).coerceIn(0f, 1f)
                    },
                    lyricsActive = lyricsEntry.isShowing,
                    onCollapse = actions::onCollapse,
                    // The lyrics button is a switch on the same screen: on opens the lyrics, off goes back to the cover.
                    onLyrics = { if (lyricsEntry.isShowing) lyricsEntry.close() else lyricsEntry.open() },
                    onDetails = actions::onShowDetails,
                )
            }
        }
    ) { paddingValues ->
        // No fade on (re)composition. The 380 ms whole-content fade that used to run here fired on
        // every first expansion, not just on rotation, and only advertised that a layout was built.
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            val coverSurface: @Composable (Modifier) -> Unit = { modifier ->
                PlayerCoverSurface(cover, state.env, surfaceActions, modifier)
            }
            val trackInfoSurface: @Composable () -> Unit = {
                PlayerTrackInfoSurface(
                    info = state.trackInfo,
                    env = state.env,
                    actions = surfaceActions,
                    isLandscape = isLandscape,
                    onArtistClick = onArtistClick,
                    onAddBookmark = onAddBookmark,
                )
            }
            val progressSurface: @Composable () -> Unit = {
                PlayerProgressSurface(state.progress, state.env, surfaceActions)
            }
            val controlsSurface: @Composable () -> Unit = {
                // Wrapped only to report where its top edge is: the line the queue swipe starts from.
                Box(Modifier.onGloballyPositioned { queueZone.controlsTopInWindow = it.positionInWindow().y }) {
                    PlayerControlsSurface(state.controls, state.env, surfaceActions)
                }
            }
            // While the lyrics are over the cover, the cover and its controls are not there for a screen reader either:
            // a second Pause and a second heart under the lyrics would be focusable and press nothing anyone can see.
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (lyricsEntry.isShowing) Modifier.clearAndSetSemantics { } else Modifier)
            ) {
                if (isLandscape) {
                    FullPlayerLandscapeContent(
                        paddingValues = paddingValues,
                        albumCoverSection = coverSurface,
                        songMetadataSection = trackInfoSurface,
                        playerProgressSection = progressSurface,
                        controlsSection = controlsSurface
                    )
                } else {
                    FullPlayerPortraitContent(
                        paddingValues = paddingValues,
                        albumCoverSection = coverSurface,
                        songMetadataSection = trackInfoSurface,
                        playerProgressSection = progressSurface,
                        controlsSection = controlsSurface,
                    )
                }
            }
            // The lyrics take the place of the cover and the controls, under the same header: the header's Lyrics
            // button is on, and pressing it again goes back to the cover (B22).
            PlayerLyricsLayer(
                entry = lyricsEntry,
                song = song,
                lyrics = state.lyrics,
                actions = actions,
                // B33-U1: the queue sheet's folded strip is at the foot of the portrait player, over the lyrics as over the cover, so the
                // lyrics keep clear of it (the room the strip takes and the navigation bar under it).
                modifier = Modifier.padding(
                    top = paddingValues.calculateTopPadding(),
                    bottom = if (isLandscape) 0.dp else QueuePeekHeight + QueuePeekBottomGap + navigationBarBottomInset,
                ),
            )
        }
    }

    val artistPickerSheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    if (showArtistPicker && state.trackInfo.artists.isNotEmpty()) {
        PlayerArtistPickerBottomSheet(
            song = song,
            artists = state.trackInfo.artists,
            sheetState = artistPickerSheetState,
            onDismiss = { showArtistPicker = false },
            onArtistClick = { artist ->
                actions.onOpenArtist(artist.id)
                showArtistPicker = false
            }
        )
    }
}

/** True in landscape. Read once by the host; the screen takes it as state so it can be drawn in either. */
@Composable
internal fun isLandscapeNow(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * The upward drag that pulls the queue sheet up, started from the transport controls or below (B29-L7).
 *
 * See [QueueSwipePolicy]: where it may start, and what it must look like once the finger is past the touch slop. A gesture that
 * turns out to be sideways, downward or diagonal is never consumed, so the seek bar, the cover pager and the sheet's own
 * collapse drag get it untouched. The queue's own drag (`onQueueDrag`) is handed the movement from the touch-down, so the queue
 * is under the finger from the first pixel it is the queue's.
 */
private fun Modifier.playerQueueDragGesture(
    currentSheetState: PlayerSheetState,
    expansionFractionProvider: () -> Float,
    bottomExclusionPx: Float,
    zone: QueueSwipeZone,
    actions: FullPlayerActions,
): Modifier = pointerInput(currentSheetState, bottomExclusionPx) {
    val slopPx = viewConfiguration.touchSlop

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val isFullyExpanded = currentSheetState == PlayerSheetState.EXPANDED && expansionFractionProvider() >= 0.99f
        if (!isFullyExpanded) return@awaitEachGesture

        val bottomBoundaryY = (size.height.toFloat() - bottomExclusionPx).coerceAtLeast(0f)
        val downInWindow = down.position.y + zone.screenTopInWindow
        val boundaryInWindow = bottomBoundaryY + zone.screenTopInWindow
        if (!QueueSwipePolicy.startsInZone(downInWindow, zone.controlsTopInWindow, boundaryInWindow)) return@awaitEachGesture

        var intent: QueueSwipePolicy.Intent? = null
        val velocityTracker = VelocityTracker()
        var totalDx = 0f
        var totalDy = 0f
        velocityTracker.addPosition(down.uptimeMillis, down.position)

        drag(down.id) { change ->
            val delta = change.positionChange()
            totalDx += delta.x
            totalDy += delta.y
            change.historical.forEach { velocityTracker.addPosition(it.uptimeMillis, it.position) }
            velocityTracker.addPosition(change.uptimeMillis, change.position)

            when (intent) {
                null -> {
                    intent = QueueSwipePolicy.intentAfterSlop(totalDx, totalDy, slopPx)
                    if (intent == QueueSwipePolicy.Intent.QUEUE) {
                        actions.onQueueDragStart()
                        change.consume()
                        actions.onQueueDrag(totalDy)
                    }
                }
                QueueSwipePolicy.Intent.QUEUE -> {
                    change.consume()
                    actions.onQueueDrag(delta.y)
                }
                QueueSwipePolicy.Intent.OTHER -> Unit
            }
        }

        if (intent == QueueSwipePolicy.Intent.QUEUE) {
            actions.onQueueRelease(totalDy, velocityTracker.calculateVelocity().y)
        }
    }
}
