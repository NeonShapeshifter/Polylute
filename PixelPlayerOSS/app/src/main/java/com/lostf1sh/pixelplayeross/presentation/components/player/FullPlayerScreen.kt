package com.lostf1sh.pixelplayeross.presentation.components.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.scoped.respectSystemBackEdgesForHorizontalDrag
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.components.rememberModalSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.ui.ds.DapMotion

/**
 * The full player: its pages, surfaces, and the layers over them. It draws a [FullPlayerUiState] and calls
 * a [FullPlayerActions]; it never sees a ViewModel (B21: the 2,362-line `FullPlayerContent` it came out of did).
 *
 * What is in the file, and nothing more:
 *  - **page and queue entry**: the folded queue's lyrics/details buttons and the upward drag that opens the queue;
 *    closing uses Back, the downward gesture or the Close player accessibility action;
 *  - **the surfaces** (cover, track info, progress with its technical line, controls), each given only its own
 *    slice of the state, in the portrait or the landscape layout;
 *  - **the layers over them** (lyrics, the bookmark dialog, the artist picker).
 *
 * UI-only state lives here (which layer is open, the skip coordination); nothing that has to survive the screen does.
 *
 * ## Pages (B42-fix1)
 *
 * The screen is a pager of four pages: **Menu** (the song's options), **Player** (everything above: the cover, the track,
 * the bar, the transport; where it always opens), **Lyrics** and **Details**. Nothing says so on screen: no indicator, no dots, no tabs.
 *
 * **Two swipes, told apart by where the finger lands.** A horizontal swipe that begins on the cover changes the song (the cover's carousel is
 * a pager inside this one, and [keepHorizontalSwipeOnThisSurface] makes sure it keeps the whole gesture, even at the first or the last song).
 * A swipe that begins anywhere else on a page changes the page. What drags keeps its drag the same way: the seek bar, the waveform and the
 * lyrics' own scroll consume their gesture before the pager sees it. The back edges belong to the system ([respectSystemBackEdgesForHorizontalDrag]).
 *
 * The folded queue's lyrics and details buttons jump to their pages; system back returns from any other page to Player and closes Player.
 * There is no top bar on these pages. Screen readers have Close player, Options, Lyrics and Details actions.
 * Pages are composed only while they are on or beside the screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FullPlayerScreen(state: FullPlayerUiState, actions: FullPlayerActions, pageSlots: PlayerPageSlots = PlayerPageSlots()) {
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

    val pages = rememberPlayerPagesState()
    val lyricsEntry = rememberPlayerLyricsEntry(song.id, state.lyrics, actions) { pages.goTo(PlayerPage.LYRICS) }

    // Opening the player always lands on Player: a collapsed player is put back there (it is retained composed, and would otherwise reopen
    // on the page it was left on).
    val sheetState = state.env.currentSheetState
    LaunchedEffect(sheetState) {
        if (sheetState != PlayerSheetState.EXPANDED) pages.reset()
    }
    // The layers around the player that belong to its Player page only (the queue's folded strip) are told which page it is on.
    val pageSignal = LocalPlayerPageSignal.current
    if (pageSignal != null) PlayerPageSignalEffect(pages, pageSignal, lyricsEntry)
    PlayerPagesBackHandler(pages)

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
                onPlayerPage = { pages.isOnPlayer },
                currentSheetState = state.env.currentSheetState,
                expansionFractionProvider = state.env.expansionFractionProvider,
                bottomExclusionPx = queueGestureBottomExclusionPx,
                zone = queueZone,
                actions = actions,
            ),
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
            // B41: the two lines of synced lyrics under the cover (the whole composable is nothing when the switch is off or there are none)
            val lyricsLinesSurface: @Composable () -> Unit = {
                PlayerLyricsLines(lyrics = state.lyrics, progress = state.progress, onOpen = { lyricsEntry.open() })
            }
            val controlsSurface: @Composable () -> Unit = {
                // Wrapped only to report where its top edge is: the line the queue swipe starts from.
                Box(Modifier.onGloballyPositioned { queueZone.controlsTopInWindow = it.positionInWindow().y }) {
                    PlayerControlsSurface(state.controls, state.env, surfaceActions)
                }
            }
            // The player's pages (B42-fix1). A page that is not on or next to the screen is not composed, which is also what keeps the cover and
            // its controls out of a screen reader's way while the lyrics are on screen (a second Pause under the lyrics pressed nothing anyone saw).
            val optionsLabel = stringResource(R.string.player_action_options)
            val lyricsLabel = stringResource(R.string.player_header_lyrics)
            val detailsLabel = stringResource(R.string.player_header_details)
            val closeLabel = stringResource(R.string.player_action_close)
            val topInset = paddingValues.calculateTopPadding()
            HorizontalPager(
                state = pages.pager,
                modifier = Modifier
                    .fillMaxSize()
                    // The back edges are the system's: a drag that starts there is Back, not a page.
                    .respectSystemBackEdgesForHorizontalDrag()
                    .testTag(PlayerPagesTags.Pager)
                    // The three pages beside Player have no button of their own on Player (the menu has none at all): a screen reader gets
                    // them as actions.
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(closeLabel) { actions.onCollapse(); true },
                            CustomAccessibilityAction(optionsLabel) { pages.goTo(PlayerPage.MENU); true },
                            CustomAccessibilityAction(lyricsLabel) { lyricsEntry.open(); true },
                            CustomAccessibilityAction(detailsLabel) { pages.goTo(PlayerPage.DETAILS); true },
                        )
                    },
                // lazy: a page is composed while any of it is on screen, and not before
                beyondViewportPageCount = 0,
                key = { it },
            ) { index ->
                when (PlayerPage.entries[index]) {
                    PlayerPage.MENU -> Box(Modifier.fillMaxSize().padding(top = topInset).testTag(PlayerPagesTags.Menu)) {
                        pageSlots.menu(pages)
                    }

                    PlayerPage.PLAYER -> Box(Modifier.fillMaxSize().testTag(PlayerPagesTags.Player)) {
                        if (isLandscape) {
                            FullPlayerLandscapeContent(
                                paddingValues = paddingValues,
                                albumCoverSection = coverSurface,
                                songMetadataSection = trackInfoSurface,
                                playerProgressSection = progressSurface,
                                controlsSection = controlsSurface,
                                lyricsLinesSection = lyricsLinesSurface,
                            )
                        } else {
                            FullPlayerPortraitContent(
                                paddingValues = paddingValues,
                                albumCoverSection = coverSurface,
                                songMetadataSection = trackInfoSurface,
                                playerProgressSection = progressSurface,
                                controlsSection = controlsSurface,
                                lyricsLinesSection = lyricsLinesSurface,
                            )
                        }
                    }

                    // The lyrics' own bar is at the foot of this page, and there is no "A continuación" strip under it: the queue's folded
                    // strip belongs to Player (see [PlayerPageSignal]), so the page keeps clear of the navigation bar only.
                    PlayerPage.LYRICS -> PlayerLyricsPage(
                        entry = lyricsEntry,
                        song = song,
                        lyrics = state.lyrics,
                        actions = actions,
                        modifier = Modifier
                            .padding(top = topInset, bottom = navigationBarBottomInset)
                            .testTag(PlayerPagesTags.Lyrics),
                    )

                    PlayerPage.DETAILS -> Box(Modifier.fillMaxSize().padding(top = topInset).testTag(PlayerPagesTags.Details)) {
                        pageSlots.details(pages)
                    }
                }
            }
            PlayerLyricsDialogs(entry = lyricsEntry, song = song, lyrics = state.lyrics, actions = actions)
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

/** Test tags of the pages and of the pager that holds them. */
internal object PlayerPagesTags {
    const val Pager = "player-pages"
    const val Menu = "player-page-menu"
    const val Player = "player-page-player"
    const val Lyrics = "player-page-lyrics"
    const val Details = "player-page-details"
    /** The cover on the Player page: where a swipe changes the song. */
    const val Cover = "player-cover"
    /** The details' waveform: a control that drags (a scrub), on a page that turns. */
    const val Waveform = "player-details-waveform"
}

/**
 * Tells the layers beside the player which page it is on, in the same frame the page changes (a side effect of its own scope, so a change of
 * page redraws this and not the screen), and puts "on Player" back when the player goes.
 */
@Composable
private fun PlayerPageSignalEffect(pages: PlayerPagesState, signal: PlayerPageSignal, lyricsEntry: PlayerLyricsEntry) {
    val page = pages.current
    SideEffect {
        signal.page = page
        signal.openLyrics = { lyricsEntry.open() }
        signal.openDetails = { pages.goTo(PlayerPage.DETAILS) }
    }
    DisposableEffect(signal) { onDispose { signal.page = PlayerPage.Start; signal.openLyrics = {}; signal.openDetails = {} } }
}

/** Back from Menu, Lyrics or Details is Player; from Player it is not handled here, so it closes the player as it always did. */
@Composable
private fun PlayerPagesBackHandler(pages: PlayerPagesState) {
    val offPlayer by remember(pages) { derivedStateOf { !pages.isOnPlayer } }
    BackHandler(enabled = offPlayer) { pages.back() }
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
    /** The queue is dragged up from the transport: a page with no transport (Menu, Lyrics, Details) has no queue to drag. */
    onPlayerPage: () -> Boolean,
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
        if (!isFullyExpanded || !onPlayerPage()) return@awaitEachGesture

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
