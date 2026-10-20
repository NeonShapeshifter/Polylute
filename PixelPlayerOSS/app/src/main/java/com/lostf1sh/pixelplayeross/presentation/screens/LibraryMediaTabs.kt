@file:OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalMaterial3Api::class,
    kotlinx.coroutines.FlowPreview::class
)

package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.LibraryTabId
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.model.StorageFilter
import com.lostf1sh.pixelplayeross.presentation.components.ExpressiveScrollBar
import com.lostf1sh.pixelplayeross.presentation.components.LocalArtworkLoadDeferred
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistContainer
import com.lostf1sh.pixelplayeross.presentation.components.albumFastScrollLabel
import com.lostf1sh.pixelplayeross.presentation.components.artistFastScrollLabel
import com.lostf1sh.pixelplayeross.presentation.components.rememberFlingState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemePair
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import kotlinx.collections.immutable.ImmutableList

private val EmptyColorSchemePairFlow: StateFlow<ColorSchemePair?> = MutableStateFlow(null)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LibraryAlbumsTab(
    albums: LazyPagingItems<Album>,
    isLoading: Boolean,
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    extraBottomGap: Dp = ListExtraBottomGap,
    isListView: Boolean,
    currentAlbumSortOption: SortOption,
    onAlbumClick: (Long) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedAlbumIds: Set<Long> = emptySet(),
    onAlbumLongPress: (Album) -> Unit = {},
    onAlbumSelectionToggle: (Album) -> Unit = {},
    getSelectionIndex: (Long) -> Int? = { null },
    storageFilter: StorageFilter = StorageFilter.ALL
) {
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val listFlinging = rememberFlingState(listState, listState.interactionSource)
    val gridFlinging = rememberFlingState(gridState, gridState.interactionSource)
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    val albumFastScrollLabelProvider = remember(albums, currentAlbumSortOption) {
        { index: Int ->
            albumFastScrollLabel(
                album = albums.peek(index),
                sortOption = currentAlbumSortOption
            )
        }
    }

    var lastHandledAlbumSortKey by remember {
        mutableStateOf(currentAlbumSortOption.storageKey)
    }
    var pendingAlbumSortScrollReset by remember { mutableStateOf(false) }
    var albumSortSawRefreshLoading by remember { mutableStateOf(false) }

    LaunchedEffect(currentAlbumSortOption) {
        val currentSortKey = currentAlbumSortOption.storageKey
        if (currentSortKey == lastHandledAlbumSortKey) return@LaunchedEffect
        lastHandledAlbumSortKey = currentSortKey
        pendingAlbumSortScrollReset = true
        albumSortSawRefreshLoading = false
        if (isListView) {
            listState.scrollToItem(0)
        } else {
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(albums.loadState.refresh, pendingAlbumSortScrollReset, isListView) {
        if (!pendingAlbumSortScrollReset) return@LaunchedEffect
        if (albums.loadState.refresh is LoadState.Loading) {
            albumSortSawRefreshLoading = true
            return@LaunchedEffect
        }
        if (!albumSortSawRefreshLoading) return@LaunchedEffect
        if (isListView) {
            listState.scrollToItem(0)
        } else {
            gridState.scrollToItem(0)
        }
        pendingAlbumSortScrollReset = false
    }

    LaunchedEffect(albums, gridState, listState, isListView) {
        if (isListView) {
            snapshotFlow { listState.layoutInfo }
                .debounce(150)
                .distinctUntilChanged()
                .collect { layoutInfo ->
                    val visibleItemsInfo = layoutInfo.visibleItemsInfo
                    if (visibleItemsInfo.isNotEmpty() && albums.itemCount > 0) {
                        val lastVisibleItemIndex = visibleItemsInfo.last().index
                        val totalItemsCount = albums.itemCount
                        val prefetchThreshold = 5
                        val prefetchCount = 4

                        if (totalItemsCount > lastVisibleItemIndex + 1 && lastVisibleItemIndex + prefetchThreshold >= totalItemsCount - prefetchCount) {
                            val startIndexToPrefetch = lastVisibleItemIndex + 1
                            val endIndexToPrefetch = (startIndexToPrefetch + prefetchCount).coerceAtMost(totalItemsCount)

                            (startIndexToPrefetch until endIndexToPrefetch).forEach { indexToPrefetch ->
                                val album = albums.peek(indexToPrefetch)
                                album?.albumArtUriString?.let { uri ->
                                    val request = ImageRequest.Builder(context)
                                        .data(uri)
                                        .size(Size(256, 256))
                                        .build()
                                    imageLoader.enqueue(request)
                                }
                            }
                        }
                    }
                }
        } else {
            snapshotFlow { gridState.layoutInfo }
                .debounce(150)
                .distinctUntilChanged()
                .collect { layoutInfo ->
                    val visibleItemsInfo = layoutInfo.visibleItemsInfo
                    if (visibleItemsInfo.isNotEmpty() && albums.itemCount > 0) {
                        val lastVisibleItemIndex = visibleItemsInfo.last().index
                        val totalItemsCount = albums.itemCount
                        val prefetchThreshold = 5
                        val prefetchCount = 4

                        if (totalItemsCount > lastVisibleItemIndex + 1 && lastVisibleItemIndex + prefetchThreshold >= totalItemsCount - prefetchCount) {
                            val startIndexToPrefetch = lastVisibleItemIndex + 1
                            val endIndexToPrefetch = (startIndexToPrefetch + prefetchCount).coerceAtMost(totalItemsCount)

                            (startIndexToPrefetch until endIndexToPrefetch).forEach { indexToPrefetch ->
                                val album = albums.peek(indexToPrefetch)
                                album?.albumArtUriString?.let { uri ->
                                    val request = ImageRequest.Builder(context)
                                        .data(uri)
                                        .size(Size(256, 256))
                                        .build()
                                    imageLoader.enqueue(request)
                                }
                            }
                        }
                    }
            }
        }
    }

    val refreshState = albums.loadState.refresh
    val reachedEndOfPagination = albums.loadState.append.endOfPaginationReached
    val shouldShowInitialLoading = albums.itemCount == 0 && (
        isLoading ||
            refreshState is LoadState.Loading ||
            (refreshState is LoadState.NotLoading && !reachedEndOfPagination)
    )

    when {
        refreshState is LoadState.Error && albums.itemCount == 0 -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DapSpacing.Lg),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.library_error_loading_albums),
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
                        onClick = { albums.retry() }
                    )
                }
            }
        }

        shouldShowInitialLoading -> {
            if (isListView) {
                LazyColumn(
                    modifier = Modifier
                        .padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = PlayerSheetCollapsedCornerRadius,
                                bottomEnd = PlayerSheetCollapsedCornerRadius
                            )
                        )
                        .fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = bottomBarHeight + LocalDapPlayerShelfHeight.current + extraBottomGap + 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(8, key = { "skeleton_album_list_$it" }) {
                        AlbumListItem(
                            album = Album.empty(),
                            albumColorSchemePairFlow = EmptyColorSchemePairFlow,
                            onClick = {},
                            isLoading = true
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    modifier = Modifier
                        .padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = PlayerSheetCollapsedCornerRadius,
                                bottomEnd = PlayerSheetCollapsedCornerRadius
                            )
                        )
                        .fillMaxSize(),
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(bottom = bottomBarHeight + LocalDapPlayerShelfHeight.current + extraBottomGap + 4.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(8, key = { "skeleton_album_grid_$it" }) {
                        AlbumGridItemRedesigned(
                            album = Album.empty(),
                            albumColorSchemePairFlow = EmptyColorSchemePairFlow,
                            onClick = {},
                            isLoading = true
                        )
                    }
                }
            }
        }

        albums.itemCount == 0 && refreshState is LoadState.NotLoading -> {
            LibraryExpressiveEmptyState(
                tabId = LibraryTabId.ALBUMS,
                storageFilter = storageFilter,
                bottomBarHeight = bottomBarHeight
            )
        }

        else -> {
            Box(modifier = Modifier.fillMaxSize()) {
                val albumsPullToRefreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    state = albumsPullToRefreshState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = albumsPullToRefreshState,
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isListView) {
                            CompositionLocalProvider(LocalArtworkLoadDeferred provides listFlinging) {
                            LazyColumn(
                                modifier = Modifier
                                    .padding(start = 14.dp, end = if (listState.canScrollForward || listState.canScrollBackward) 24.dp else 14.dp, bottom = 6.dp)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = PlayerSheetCollapsedCornerRadius,
                                            bottomEnd = PlayerSheetCollapsedCornerRadius
                                        )
                                    ),
                                state = listState,
                                contentPadding = PaddingValues(bottom = bottomBarHeight + LocalDapPlayerShelfHeight.current + extraBottomGap + 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    count = albums.itemCount,
                                    key = { index -> albums.peek(index)?.id ?: "album_placeholder_$index" },
                                    contentType = { "album_list_item" }
                                ) { index ->
                                    val album = albums[index]
                                    if (album != null) {
                                        // The row paints no artwork tint, so it must not ask for one: this used to
                                        // start a palette extraction for every album that scrolled into view (B13).
                                        // The detail screen requests its own scheme when it opens.
                                        val albumSpecificColorSchemeFlow = EmptyColorSchemePairFlow
                                        val rememberedOnClick = remember(album.id, onAlbumClick) {
                                            { onAlbumClick(album.id) }
                                        }
                                        val rememberedOnLongPress = remember(album.id, onAlbumLongPress) {
                                            { onAlbumLongPress(album) }
                                        }
                                        val rememberedOnSelectionToggle = remember(album.id, onAlbumSelectionToggle) {
                                            { onAlbumSelectionToggle(album) }
                                        }
                                        AlbumListItem(
                                            album = album,
                                            albumColorSchemePairFlow = albumSpecificColorSchemeFlow,
                                            onClick = rememberedOnClick,
                                            isLoading = false,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = selectedAlbumIds.contains(album.id),
                                            selectionIndex = getSelectionIndex(album.id),
                                            onLongPress = rememberedOnLongPress,
                                            onSelectionToggle = rememberedOnSelectionToggle
                                        )
                                    } else {
                                        AlbumListItem(
                                            album = Album.empty(),
                                            albumColorSchemePairFlow = EmptyColorSchemePairFlow,
                                            onClick = {},
                                            isLoading = true
                                        )
                                    }
                                }
                            }
                            }
                            val hasActiveSong by remember {
                                playerViewModel.stablePlayerState
                                    .map { it.currentSong != null && it.currentSong != Song.emptySong() }
                                    .distinctUntilChanged()
                            }.collectAsStateWithLifecycle(initialValue = false)
                            val bottomPadding = if (hasActiveSong)
                                bottomBarHeight + LocalDapPlayerShelfHeight.current + 16.dp
                            else
                                bottomBarHeight + 16.dp

                            ExpressiveScrollBar(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 4.dp, top = 16.dp, bottom = bottomPadding),
                                listState = listState,
                                dragLabelProvider = albumFastScrollLabelProvider
                            )
                        } else {
                            CompositionLocalProvider(LocalArtworkLoadDeferred provides gridFlinging) {
                            LazyVerticalGrid(
                                modifier = Modifier
                                    .padding(start = 14.dp, end = if (gridState.canScrollForward || gridState.canScrollBackward) 24.dp else 14.dp, bottom = 6.dp)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = PlayerSheetCollapsedCornerRadius,
                                            bottomEnd = PlayerSheetCollapsedCornerRadius
                                        )
                                    ),
                                state = gridState,
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(bottom = bottomBarHeight + LocalDapPlayerShelfHeight.current + extraBottomGap + 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    count = albums.itemCount,
                                    key = { index -> albums.peek(index)?.id ?: "album_grid_placeholder_$index" },
                                    contentType = { "album_grid_item" }
                                ) { index ->
                                    val album = albums[index]
                                    if (album != null) {
                                        // The row paints no artwork tint, so it must not ask for one: this used to
                                        // start a palette extraction for every album that scrolled into view (B13).
                                        // The detail screen requests its own scheme when it opens.
                                        val albumSpecificColorSchemeFlow = EmptyColorSchemePairFlow
                                        val rememberedOnClick = remember(album.id, onAlbumClick) {
                                            { onAlbumClick(album.id) }
                                        }
                                        val rememberedOnLongPress = remember(album.id, onAlbumLongPress) {
                                            { onAlbumLongPress(album) }
                                        }
                                        val rememberedOnSelectionToggle = remember(album.id, onAlbumSelectionToggle) {
                                            { onAlbumSelectionToggle(album) }
                                        }
                                        AlbumGridItemRedesigned(
                                            album = album,
                                            albumColorSchemePairFlow = albumSpecificColorSchemeFlow,
                                            onClick = rememberedOnClick,
                                            isLoading = false,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = selectedAlbumIds.contains(album.id),
                                            selectionIndex = getSelectionIndex(album.id),
                                            onLongPress = rememberedOnLongPress,
                                            onSelectionToggle = rememberedOnSelectionToggle
                                        )
                                    } else {
                                        AlbumGridItemRedesigned(
                                            album = Album.empty(),
                                            albumColorSchemePairFlow = EmptyColorSchemePairFlow,
                                            onClick = {},
                                            isLoading = true
                                        )
                                    }
                                }
                            }
                            }

                            val hasActiveSong by remember {
                                playerViewModel.stablePlayerState
                                    .map { it.currentSong != null && it.currentSong != Song.emptySong() }
                                    .distinctUntilChanged()
                            }.collectAsStateWithLifecycle(initialValue = false)
                            val bottomPadding = if (hasActiveSong)
                                bottomBarHeight + LocalDapPlayerShelfHeight.current + 16.dp
                            else
                                bottomBarHeight + 16.dp

                            ExpressiveScrollBar(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 4.dp, top = 16.dp, bottom = bottomPadding),
                                gridState = gridState,
                                dragLabelProvider = albumFastScrollLabelProvider
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LibraryArtistsTab(
    artists: LazyPagingItems<Artist>,
    isLoading: Boolean,
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    extraBottomGap: Dp = ListExtraBottomGap,
    currentArtistSortOption: SortOption,
    onArtistClick: (Long) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    storageFilter: StorageFilter = StorageFilter.ALL
) {
    val listState = rememberLazyListState()
    val flinging = rememberFlingState(listState, listState.interactionSource)
    val artistFastScrollLabelProvider = remember(artists, currentArtistSortOption) {
        { index: Int ->
            artistFastScrollLabel(
                artist = artists.peek(index),
                sortOption = currentArtistSortOption
            )
        }
    }
    var lastHandledArtistSortKey by remember {
        mutableStateOf(currentArtistSortOption.storageKey)
    }
    var pendingArtistSortScrollReset by remember { mutableStateOf(false) }
    var artistSortSawRefreshLoading by remember { mutableStateOf(false) }

    LaunchedEffect(currentArtistSortOption) {
        val currentSortKey = currentArtistSortOption.storageKey
        if (currentSortKey == lastHandledArtistSortKey) return@LaunchedEffect
        lastHandledArtistSortKey = currentSortKey
        pendingArtistSortScrollReset = true
        artistSortSawRefreshLoading = false
        listState.scrollToItem(0)
    }

    LaunchedEffect(artists.loadState.refresh, pendingArtistSortScrollReset) {
        if (!pendingArtistSortScrollReset) return@LaunchedEffect
        if (artists.loadState.refresh is LoadState.Loading) {
            artistSortSawRefreshLoading = true
            return@LaunchedEffect
        }
        if (!artistSortSawRefreshLoading) return@LaunchedEffect
        listState.scrollToItem(0)
        pendingArtistSortScrollReset = false
    }

    val refreshState = artists.loadState.refresh
    val reachedEndOfPagination = artists.loadState.append.endOfPaginationReached
    val shouldShowInitialLoading = artists.itemCount == 0 && (
        isLoading ||
            refreshState is LoadState.Loading ||
            (refreshState is LoadState.NotLoading && !reachedEndOfPagination)
    )

    when {
        refreshState is LoadState.Error && artists.itemCount == 0 -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DapSpacing.Lg),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.library_error_loading_artists),
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
                        onClick = { artists.retry() }
                    )
                }
            }
        }

        shouldShowInitialLoading -> {
            LazyColumn(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
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
                item(key = "skeleton_top_spacer") { Spacer(Modifier.height(4.dp)) }
                items(10, key = { "skeleton_artist_$it" }) {
                    ArtistListItem(
                        artist = Artist.empty(),
                        onClick = {},
                        isLoading = true
                    )
                }
            }
        }

        artists.itemCount == 0 && refreshState is LoadState.NotLoading -> {
            LibraryExpressiveEmptyState(
                tabId = LibraryTabId.ARTISTS,
                storageFilter = storageFilter,
                bottomBarHeight = bottomBarHeight
            )
        }

        else -> {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                val genresPullToRefreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    state = genresPullToRefreshState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = genresPullToRefreshState,
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
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
                                count = artists.itemCount,
                                key = { index -> artists.peek(index)?.id ?: "artist_placeholder_$index" },
                                contentType = { "artist" }
                            ) { index ->
                                val artist = artists[index]
                                if (artist != null) {
                                    val rememberedOnClick = remember(artist.id, onArtistClick) {
                                        { onArtistClick(artist.id) }
                                    }
                                    ArtistListItem(artist = artist, onClick = rememberedOnClick)
                                } else {
                                    ArtistListItem(
                                        artist = Artist.empty(),
                                        onClick = {},
                                        isLoading = true
                                    )
                                }
                            }
                        }
                        }

                        val hasActiveSong by remember {
                            playerViewModel.stablePlayerState
                                .map { it.currentSong != null && it.currentSong != Song.emptySong() }
                                .distinctUntilChanged()
                        }.collectAsStateWithLifecycle(initialValue = false)
                        val bottomPadding = if (hasActiveSong)
                            bottomBarHeight + LocalDapPlayerShelfHeight.current + 16.dp
                        else
                            bottomBarHeight + 16.dp

                        ExpressiveScrollBar(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 4.dp, top = 16.dp, bottom = bottomPadding),
                            listState = listState,
                            dragLabelProvider = artistFastScrollLabelProvider
                        )
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LibraryPlaylistsTab(
    playlistUiState: PlaylistUiState,
    filteredPlaylists: ImmutableList<com.lostf1sh.pixelplayeross.data.model.Playlist> = playlistUiState.playlists,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedPlaylistIds: Set<String> = emptySet(),
    onPlaylistLongPress: (com.lostf1sh.pixelplayeross.data.model.Playlist) -> Unit = {},
    onPlaylistSelectionToggle: (com.lostf1sh.pixelplayeross.data.model.Playlist) -> Unit = {},
    onPlaylistOptionsClick: () -> Unit = {}
) {
    PlaylistContainer(
        playlistUiState = playlistUiState,
        filteredPlaylists = filteredPlaylists,
        currentSortOption = playlistUiState.currentPlaylistSortOption,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        bottomBarHeight = bottomBarHeight,
        playerShelfHeight = LocalDapPlayerShelfHeight.current,
        navController = navController,
        playerViewModel = playerViewModel,
        isSelectionMode = isSelectionMode,
        selectedPlaylistIds = selectedPlaylistIds,
        onPlaylistLongPress = onPlaylistLongPress,
        onPlaylistSelectionToggle = onPlaylistSelectionToggle
    )
}
