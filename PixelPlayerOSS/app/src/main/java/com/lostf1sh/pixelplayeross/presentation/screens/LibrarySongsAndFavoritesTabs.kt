@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.LibraryTabId
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.model.StorageFilter
import com.lostf1sh.pixelplayeross.presentation.components.ExpressiveScrollBar
import com.lostf1sh.pixelplayeross.presentation.components.LocalArtworkLoadDeferred
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.presentation.components.rememberFlingState
import com.lostf1sh.pixelplayeross.presentation.components.songFastScrollLabel
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LibraryFavoritesTab(
    favoriteSongs: LazyPagingItems<Song>,
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    extraBottomGap: Dp = ListExtraBottomGap,
    onMoreOptionsClick: (Song) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    onSongLongPress: (Song) -> Unit = {},
    onSongSelectionToggle: (Song) -> Unit = {},
    getSelectionIndex: (String) -> Int? = { null },
    sortOption: SortOption,
    onLocateCurrentSongVisibilityChanged: (Boolean) -> Unit = {},
    onRegisterLocateCurrentSongAction: ((() -> Unit)?) -> Unit = {},
    storageFilter: StorageFilter = StorageFilter.ALL,
    hasCurrentSong: Boolean = false
) {
    val listState = rememberLazyListState()
    val flinging = rememberFlingState(listState, listState.interactionSource)
    val coroutineScope = rememberCoroutineScope()
    val favoriteFastScrollLabelProvider = remember(favoriteSongs, sortOption) {
        { index: Int ->
            songFastScrollLabel(
                song = favoriteSongs.peek(index),
                sortOption = sortOption
            )
        }
    }
    val playbackStates = rememberLibraryPlaybackStates(playerViewModel)
    var lastHandledFavoriteSortKey by remember { mutableStateOf(sortOption.storageKey) }
    var pendingFavoriteSortScrollReset by remember { mutableStateOf(false) }
    var favoriteSortSawRefreshLoading by remember { mutableStateOf(false) }
    // B24-Q2: read inside LocateCurrentSongEffects' effects, never in this body (see LibraryLocateCurrentSong.kt).
    val currentSongId = rememberCurrentSongIdState(playerViewModel)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(playerViewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            playerViewModel.scrollToIndexEvent.collect { index ->
                if (index >= 0) {
                    val firstVisible = listState.firstVisibleItemIndex
                    if (Math.abs(index - firstVisible) > 20) {
                        listState.scrollToItem(index)
                    } else {
                        listState.animateScrollToItem(index)
                    }
                }
            }
        }
    }

    LocateCurrentSongEffects(
        listState = listState,
        currentSongId = currentSongId,
        itemCount = { favoriteSongs.itemCount },
        isLoading = false,
        onVisibilityChanged = onLocateCurrentSongVisibilityChanged,
        onRegisterAction = onRegisterLocateCurrentSongAction,
        requestLocate = { playerViewModel.requestLocateCurrentSong() },
    )

    LaunchedEffect(sortOption) {
        val currentSortKey = sortOption.storageKey
        if (currentSortKey == lastHandledFavoriteSortKey) return@LaunchedEffect
        lastHandledFavoriteSortKey = currentSortKey
        pendingFavoriteSortScrollReset = true
        favoriteSortSawRefreshLoading = false
        listState.scrollToItem(0)
    }

    LaunchedEffect(favoriteSongs.loadState.refresh, pendingFavoriteSortScrollReset) {
        if (!pendingFavoriteSortScrollReset) return@LaunchedEffect
        if (favoriteSongs.loadState.refresh is LoadState.Loading) {
            favoriteSortSawRefreshLoading = true
            return@LaunchedEffect
        }
        if (!favoriteSortSawRefreshLoading) return@LaunchedEffect
        listState.scrollToItem(0)
        pendingFavoriteSortScrollReset = false
    }

    val shouldShowInitialLoading = favoriteSongs.itemCount == 0 &&
        favoriteSongs.loadState.refresh is LoadState.Loading

    if (shouldShowInitialLoading) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = bottomBarHeight + LocalDapPlayerShelfHeight.current + extraBottomGap)
        ) {
            items(8, key = { "favorite_skeleton_$it" }) {
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
    } else if (favoriteSongs.itemCount == 0 && favoriteSongs.loadState.refresh !is LoadState.Loading) {
        LibraryExpressiveEmptyState(
            tabId = LibraryTabId.LIKED,
            storageFilter = storageFilter,
            bottomBarHeight = bottomBarHeight
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val songsPullToRefreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                state = songsPullToRefreshState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = songsPullToRefreshState,
                        isRefreshing = isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalArtworkLoadDeferred provides flinging) {
                    LazyColumn(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
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
                            count = favoriteSongs.itemCount,
                            key = { index -> favoriteSongs.peek(index)?.id ?: "favorite_placeholder_$index" },
                            contentType = { "song" }
                        ) { index ->
                            val song = favoriteSongs[index]
                            if (song != null) {
                                LibraryPlaybackAwareSongItem(
                                    song = song,
                                    playback = playbackStates,
                                    onMoreOptionsClick = { onMoreOptionsClick(song) },
                                    isSelected = selectedSongIds.contains(song.id),
                                    selectionIndex = if (isSelectionMode) getSelectionIndex(song.id) else null,
                                    isSelectionMode = isSelectionMode,
                                    onLongPress = { onSongLongPress(song) },
                                    onClick = {
                                        if (isSelectionMode) {
                                            onSongSelectionToggle(song)
                                        } else {
                                            playerViewModel.showAndPlaySongFromFavorites(song)
                                        }
                                    }
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
                        dragLabelProvider = favoriteFastScrollLabelProvider
                    )
                }
            }
        }
    }
}
