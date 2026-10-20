package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.data.model.LibraryTabId
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.StorageFilter
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.paging.compose.LazyPagingItems
import androidx.paging.LoadState
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.ExpressiveScrollBar
import com.lostf1sh.pixelplayeross.presentation.components.LocalArtworkLoadDeferred
import com.lostf1sh.pixelplayeross.presentation.components.rememberFlingState
import com.lostf1sh.pixelplayeross.presentation.components.songFastScrollLabel
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType


@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibrarySongsTab(
    songs: LazyPagingItems<Song>,
    isLoading: Boolean,
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    extraBottomGap: Dp = ListExtraBottomGap,
    onMoreOptionsClick: (Song) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    sortOption: SortOption,
    isSelectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    onSongLongPress: (Song) -> Unit = {},
    onSongSelectionToggle: (Song) -> Unit = {},
    getSelectionIndex: (String) -> Int? = { null },
    onLocateCurrentSongVisibilityChanged: (Boolean) -> Unit = {},
    onRegisterLocateCurrentSongAction: ((() -> Unit)?) -> Unit = {},
    storageFilter: StorageFilter = StorageFilter.ALL,
    hasCurrentSong: Boolean = false
) {
    val listState = rememberLazyListState()
    val flinging = rememberFlingState(listState, listState.interactionSource)
    val pullToRefreshState = rememberPullToRefreshState()
    val coroutineScope = rememberCoroutineScope()
    // One collection of the player state for the whole list; each row only derives its own answer.
    val playbackStates = rememberLibraryPlaybackStates(playerViewModel)
    val songFastScrollLabelProvider = remember(songs, sortOption) {
        { index: Int ->
            songFastScrollLabel(
                song = songs.peek(index),
                sortOption = sortOption
            )
        }
    }
    var lastHandledSongSortKey by remember { mutableStateOf(sortOption.storageKey) }
    var pendingSongSortScrollReset by remember { mutableStateOf(false) }
    var songSortSawRefreshLoading by remember { mutableStateOf(false) }
    // B24-Q2: the id is read inside LocateCurrentSongEffects' effects, never in this body, so a song change does not re-run it.
    val currentSongId = rememberCurrentSongIdState(playerViewModel)

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(playerViewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            playerViewModel.scrollToIndexEvent.collect { index ->
                if (index >= 0) scrollSongsListTo(listState, index)
            }
        }
    }

    // B32-Q2: a song Stats or a collection asked the Library to show. The request waits in the view model until this list has rows to
    // scroll in (the Library may have been on another tab, or not composed at all), is answered with the song's index in the list as
    // shown, and the row is marked for a few seconds. The scroll is the one the current-song locate does.
    // B32-fix1 R1: the request is this effect's key, so it stays published until the query AND the scroll have finished (`complete`);
    // clearing it earlier made Compose cancel the effect in the middle of its own query and scroll. A Back or a newer request still
    // cancels it, and then nothing is marked or said.
    val locateRequest by playerViewModel.libraryLocateRequest.collectAsStateWithLifecycle()
    val locatedSongId by playerViewModel.locatedSongId.collectAsStateWithLifecycle()
    // the list this tab shows can be replaced while a request waits for rows (a sort, a refresh): the wait follows the current one
    val currentSongs by rememberUpdatedState(songs)
    LaunchedEffect(locateRequest?.token) {
        val request = locateRequest ?: return@LaunchedEffect
        // keyed on the request alone: a list that is still loading (its count moving) must not cancel it half way
        snapshotFlow { currentSongs.itemCount }.first { it > 0 }
        val index = playerViewModel.takeLibraryLocate(request)
        if (index >= 0) {
            // the rows can arrive in the very frame the list is first laid out; a scroll asked before the list has measured the row it is
            // going to lands short of it. Wait (a moment, not for ever) until the list knows the row, then scroll as the current-song locate does.
            withTimeoutOrNull(LocateLayoutWaitMs) { snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > index } }
            scrollSongsListTo(listState, index)
        }
        playerViewModel.completeLibraryLocate(request)
    }

    LocateCurrentSongEffects(
        listState = listState,
        currentSongId = currentSongId,
        itemCount = { songs.itemCount },
        isLoading = isLoading,
        onVisibilityChanged = onLocateCurrentSongVisibilityChanged,
        onRegisterAction = onRegisterLocateCurrentSongAction,
        requestLocate = { playerViewModel.requestLocateCurrentSong() },
    )

    LaunchedEffect(sortOption) {
        val currentSortKey = sortOption.storageKey
        if (currentSortKey == lastHandledSongSortKey) return@LaunchedEffect
        lastHandledSongSortKey = currentSortKey
        pendingSongSortScrollReset = true
        songSortSawRefreshLoading = false
        listState.scrollToItem(0)
    }

    LaunchedEffect(songs.loadState.refresh, pendingSongSortScrollReset) {
        if (!pendingSongSortScrollReset) return@LaunchedEffect
        if (songs.loadState.refresh is LoadState.Loading) {
            songSortSawRefreshLoading = true
            return@LaunchedEffect
        }
        if (!songSortSawRefreshLoading) return@LaunchedEffect
        listState.scrollToItem(0)
        pendingSongSortScrollReset = false
    }
    
    val refreshState = songs.loadState.refresh
    val reachedEndOfPagination = songs.loadState.append.endOfPaginationReached
    val shouldShowInitialLoading = songs.itemCount == 0 && (
        isLoading ||
            refreshState is LoadState.Loading ||
            (refreshState is LoadState.NotLoading && !reachedEndOfPagination)
    )

    when {
        refreshState is LoadState.Error && songs.itemCount == 0 -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DapSpacing.Lg),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.library_error_loading_songs),
                        style = DapType.Heading,
                        color = Dap.primaryText,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(DapSpacing.Sm))
                    Text(
                        text = stringResource(R.string.library_error_body),
                        style = DapType.Body,
                        color = Dap.secondaryText,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(DapSpacing.Lg))
                    DapSecondaryButton(
                        label = stringResource(R.string.library_retry),
                        onClick = { songs.retry() }
                    )
                }
            }
        }
        shouldShowInitialLoading -> {
            LazyColumn(
                modifier = Modifier
                    .padding(start = 12.dp, end = 24.dp, bottom = 6.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 26.dp,
                            topEnd = 26.dp,
                            bottomStart = PlayerSheetCollapsedCornerRadius,
                            bottomEnd = PlayerSheetCollapsedCornerRadius
                        )
                    )
                    .fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = bottomBarHeight + LocalDapPlayerShelfHeight.current + extraBottomGap)
            ) {
                items(12, key = { "skeleton_song_$it" }) {
                    EnhancedSongListItem(
                        song = Song.emptySong(),
                        isPlaying = false,
                        isLoading = true,
                        isCurrentSong = false,
                        onMoreOptionsClick = {},
                        onClick = {}
                    )
                }
            }
        }
        songs.itemCount == 0 && refreshState is LoadState.NotLoading && reachedEndOfPagination -> {
            LibraryExpressiveEmptyState(
                tabId = LibraryTabId.SONGS,
                storageFilter = storageFilter,
                bottomBarHeight = bottomBarHeight
            )
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    state = pullToRefreshState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = pullToRefreshState,
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Covers of rows that scroll in during a fling wait until it slows down.
                        CompositionLocalProvider(LocalArtworkLoadDeferred provides flinging) {
                        LazyColumn(
                            modifier = Modifier
                                .padding(start = 12.dp, end = if (listState.canScrollForward || listState.canScrollBackward) 22.dp else 12.dp, bottom = 6.dp)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 26.dp,
                                        topEnd = 26.dp,
                                        bottomStart = PlayerSheetCollapsedCornerRadius,
                                        bottomEnd = PlayerSheetCollapsedCornerRadius
                                    )
                                ),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = bottomBarHeight + LocalDapPlayerShelfHeight.current + extraBottomGap)
                        ) {

                            items(
                                count = songs.itemCount,
                                key = { index -> songs.peek(index)?.id ?: "song_placeholder_$index" },
                                contentType = { "song" }
                            ) { index ->
                                val song = songs[index]
                                
                                if (song != null) {
                                    val isSelected = selectedSongIds.contains(song.id)
                                    
                                    val rememberedOnClick: () -> Unit = remember(song, isSelectionMode) {
                                        if (isSelectionMode) {
                                            { onSongSelectionToggle(song) }
                                        } else {
                                            { playerViewModel.showAndPlaySongFromLibrary(song) }
                                        }
                                    }
                                    
                                    val rememberedOnLongPress: () -> Unit = remember(song) {
                                        { onSongLongPress(song) }
                                    }

                                    LibraryPlaybackAwareSongItem(
                                        song = song,
                                        playback = playbackStates,
                                        isLocated = locatedSongId == song.id,
                                        isSelected = isSelected,
                                        isSelectionMode = isSelectionMode,
                                        selectionIndex = if (isSelectionMode) getSelectionIndex(song.id) else null,
                                        onLongPress = rememberedOnLongPress,
                                        onMoreOptionsClick = onMoreOptionsClick,
                                        onClick = rememberedOnClick
                                    )
                                } else {
                                     EnhancedSongListItem(
                                        song = Song.emptySong(),
                                        isPlaying = false,
                                        isLoading = true,
                                        isCurrentSong = false,
                                        onMoreOptionsClick = {},
                                        onClick = {}
                                     )
                                }
                            }
                        }
                        }

                        val bottomPadding = if (hasCurrentSong)
                            bottomBarHeight + LocalDapPlayerShelfHeight.current + 16.dp
                        else 
                            bottomBarHeight + 16.dp

                        ExpressiveScrollBar(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 4.dp, top = 16.dp, bottom = bottomPadding),
                            listState = listState,
                            dragLabelProvider = songFastScrollLabelProvider
                        )
                    }
                }
            }
        }
    }
}

/** How long a locate for another page waits for the list to lay out the row it scrolls to before it scrolls anyway. */
internal const val LocateLayoutWaitMs = 3_000L

/**
 * Scrolls the Songs list to [index]: a jump when it is far, an animation when it is near. The one scroll the "locate current song" button
 * and a locate asked for by another page (B32-Q2) share.
 */
internal suspend fun scrollSongsListTo(listState: androidx.compose.foundation.lazy.LazyListState, index: Int) {
    val firstVisible = listState.firstVisibleItemIndex
    if (Math.abs(index - firstVisible) > 20) {
        listState.scrollToItem(index)
    } else {
        listState.animateScrollToItem(index)
    }
}
