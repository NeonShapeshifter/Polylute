@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.utils.currentLocale
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsHost
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafelyReplacing

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.ui.theme.LocalPixelPlayerDarkTheme
import com.lostf1sh.pixelplayeross.ui.theme.PixelPlayerStatusBarStyle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil.compose.AsyncImagePainter
import coil.size.Size
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.presentation.components.ExpressiveScrollBar
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.components.SongInfoBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AlbumDetailViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.utils.formatSongCountLabel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapCollectionActions
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTopBar
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSectionHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonList
import java.util.Locale
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import kotlinx.collections.immutable.persistentListOf
import com.lostf1sh.pixelplayeross.presentation.components.albumTitleLabel
import com.lostf1sh.pixelplayeross.presentation.components.albumArtistLabel
import com.lostf1sh.pixelplayeross.presentation.components.BackRowHeight
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold

private const val UseSharedCollapsibleTopBarProbe = true

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val playbackStates = rememberLibraryPlaybackStates(playerViewModel)
    val isMiniPlayerVisible by remember {
        derivedStateOf { stablePlayerState.currentSong != null }
    }
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()

    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    // Cleared whenever the action sheet closes, by keying on its visibility: every
    // dismissal path in this file sets that flag, so this cannot go stale.
    var showTrackDetails by remember(showSongInfoBottomSheet) { mutableStateOf(false) }
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val isDarkTheme = LocalPixelPlayerDarkTheme.current
    val baseColorScheme = MaterialTheme.colorScheme
    val albumArtUri = uiState.album?.albumArtUriString?.takeIf { it.isNotBlank() }
    val albumColorSchemeFlow = remember(albumArtUri) {
        albumArtUri?.let { playerViewModel.themeStateHolder.getAlbumColorSchemeFlow(it, eager = false) }
    }
    val albumColorSchemePair = albumColorSchemeFlow?.collectAsStateWithLifecycle()?.value
    val albumColorScheme = remember(albumColorSchemePair, isDarkTheme, baseColorScheme) {
        albumColorSchemePair?.let { pair -> if (isDarkTheme) pair.dark else pair.light }
            ?: baseColorScheme
    }
    var headerArtworkLoaded by remember(albumArtUri) { mutableStateOf(albumArtUri == null) }
    var themeRequestIssued by remember(albumArtUri) { mutableStateOf(albumArtUri == null) }
    LaunchedEffect(albumArtUri, headerArtworkLoaded, themeRequestIssued) {
        if (!themeRequestIssued && headerArtworkLoaded && albumArtUri != null) {
            themeRequestIssued = true
            playerViewModel.themeStateHolder.ensureAlbumColorScheme(albumArtUri)
        }
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    MaterialTheme(
        colorScheme = albumColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
    DapBackScaffold(onBack = { navController.popBackStack() }, overHeaderArt = true) {

        val fabBottomPadding by animateDpAsState(
            targetValue = if (isMiniPlayerVisible) MiniPlayerHeight + 16.dp else 16.dp,
            label = "fabPadding"
        )

        when {
            uiState.isLoading && uiState.album == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(top = BackRowHeight)
                        .padding(horizontal = DapSpacing.Gutter)
                ) {
                    // Static skeletons while the album resolves. Nothing spins.
                    DapSkeletonList(count = 5, showArt = true)
                }
            }

            uiState.error != null && uiState.album == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        DapSecondaryButton(
                            label = stringResource(R.string.library_retry),
                            onClick = { viewModel.retry() }
                        )
                    }
                }
            }

            uiState.album != null -> {
                val album = uiState.album!!
                val songs = uiState.songs
                val songsByDisc = remember(songs) {
                    songs.groupBy { it.discNumber ?: 1 }
                }
                val lazyListState = rememberLazyListState()

                val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val minTopBarHeight = 64.dp + statusBarHeight
                val maxTopBarHeight = 300.dp

                val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
                val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }
                val headerImageRequestSize = remember(
                    configuration.screenWidthDp,
                    density.density,
                    maxTopBarHeightPx
                ) {
                    Size(
                        width = with(density) { configuration.screenWidthDp.dp.roundToPx() },
                        height = maxTopBarHeightPx.roundToInt()
                    )
                }

                val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
                val collapseFraction by remember(minTopBarHeightPx, maxTopBarHeightPx) {
                    derivedStateOf {
                        1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(
                            0f,
                            1f
                        )
                    }
                }

                val nestedScrollConnection = remember {
                    object : NestedScrollConnection {
                        override fun onPreScroll(
                            available: Offset,
                            source: NestedScrollSource
                        ): Offset {
                            val delta = available.y
                            val isScrollingDown = delta < 0

                            if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
                                return Offset.Zero
                            }

                            val previousHeight = topBarHeight.value
                            val newHeight =
                                (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                            val consumed = newHeight - previousHeight

                            if (consumed.roundToInt() != 0) {
                                coroutineScope.launch {
                                    topBarHeight.snapTo(newHeight)
                                }
                            }

                            val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                            return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
                        }

                        override suspend fun onPostFling(
                            consumed: Velocity,
                            available: Velocity
                        ): Velocity {
                            return super.onPostFling(consumed, available)
                        }
                    }
                }

                LaunchedEffect(lazyListState.isScrollInProgress) {
                    if (!lazyListState.isScrollInProgress) {
                        val shouldExpand =
                            topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
                        val canExpand =
                            lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0

                        val targetValue = if (shouldExpand && canExpand) {
                            maxTopBarHeightPx
                        } else {
                            minTopBarHeightPx
                        }

                        if (topBarHeight.value != targetValue) {
                            coroutineScope.launch {
                                topBarHeight.animateTo(
                                    targetValue,
                                    spring(stiffness = Spring.StiffnessMedium)
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Dap.background)
                        .nestedScroll(nestedScrollConnection)
                ) {
                    val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }
                    val showScrollBar =
                        collapseFraction > 0.95f &&
                            (lazyListState.canScrollForward || lazyListState.canScrollBackward)

                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .offset {
                                val extraHeight =
                                    (topBarHeight.value - minTopBarHeightPx).roundToInt()
                                IntOffset(0, extraHeight)
                            },
                        contentPadding = PaddingValues(
                            top = minTopBarHeight + 8.dp,
                            start = 16.dp,
                            end = if (showScrollBar) 24.dp else 16.dp,
                            bottom = fabBottomPadding + 80.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        songsByDisc.forEach { (discNumber, discSongs) ->
                            if (songsByDisc.size > 1) {
                                item(key = "disc_header_$discNumber") {
                                    DapSectionHeader(
                                        text = stringResource(
                                            R.string.disc_number_header,
                                            discNumber
                                        ).uppercase(currentLocale()),
                                        modifier = Modifier.padding(top = DapSpacing.Lg)
                                    )
                                }
                            }
                            items(
                                items = discSongs,
                                key = { song -> "album_song_${song.id}" },
                                contentType = { "album_song" }
                            ) { song ->
                                LibraryPlaybackAwareSongItem(
                                    song = song,
                                    playback = playbackStates,
                                    showAlbumArt = false,
                                    onMoreOptionsClick = {
                                        playerViewModel.selectSongForInfo(song)
                                        showSongInfoBottomSheet = true
                                    },
                                    onClick = { playerViewModel.showAndPlaySong(song, songs, source = ListenSource(ListenSourceKind.ALBUM, album.id.toString())) }
                                )
                            }
                        }
                    }

                    if (showScrollBar) {
                        ExpressiveScrollBar(
                            listState = lazyListState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(
                                    top = minTopBarHeight + 12.dp,
                                    bottom = fabBottomPadding + 80.dp
                                )
                        )
                    }

                    if (UseSharedCollapsibleTopBarProbe) {
                        SharedAlbumTopBarProbe(
                            album = album,
                            songsCount = songs.size,
                            collapseFraction = collapseFraction,
                            headerHeight = currentTopBarHeightDp,
                            headerImageRequestSize = headerImageRequestSize,
                            onHeaderArtworkState = { state ->
                                if (state is AsyncImagePainter.State.Success) {
                                    headerArtworkLoaded = true
                                }
                            },
                            onBackPressed = { navController.popBackStack() },
                            onPlayClick = {
                                if (songs.isNotEmpty()) {
                                    // In order, from the top. This used to pick a random track and
                                    // call it "shuffle"; the shuffle control beside it now does the
                                    // real thing, so play has to mean play.
                                    playerViewModel.showAndPlaySong(
                                        songs.first(),
                                        songs,
                                        queueName = album.title,
                                        source = ListenSource(ListenSourceKind.ALBUM, album.id.toString())
                                    )
                                }
                            },
                            onShuffleClick = {
                                if (songs.isNotEmpty()) {
                                    playerViewModel.playSongsShuffled(
                                        songsToPlay = songs,
                                        queueName = album.title,
                                        startAtZero = true,
                                        source = ListenSource(ListenSourceKind.ALBUM, album.id.toString()),
                                    )
                                }
                            }
                        )
                    } else {
                        CollapsingAlbumTopBar(
                            album = album,
                            songsCount = songs.size,
                            collapseFraction = collapseFraction,
                            headerHeight = currentTopBarHeightDp,
                            headerImageRequestSize = headerImageRequestSize,
                            onHeaderArtworkState = { state ->
                                if (state is AsyncImagePainter.State.Success) {
                                    headerArtworkLoaded = true
                                }
                            },
                            onBackPressed = { navController.popBackStack() },
                            onPlayClick = {
                                if (songs.isNotEmpty()) {
                                    // In order, from the top. This used to pick a random track and
                                    // call it "shuffle"; the shuffle control beside it now does the
                                    // real thing, so play has to mean play.
                                    playerViewModel.showAndPlaySong(
                                        songs.first(),
                                        songs,
                                        queueName = album.title,
                                        source = ListenSource(ListenSourceKind.ALBUM, album.id.toString())
                                    )
                                }
                            },
                            onShuffleClick = {
                                if (songs.isNotEmpty()) {
                                    playerViewModel.playSongsShuffled(
                                        songsToPlay = songs,
                                        queueName = album.title,
                                        startAtZero = true,
                                        source = ListenSource(ListenSourceKind.ALBUM, album.id.toString()),
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
        if (showSongInfoBottomSheet && selectedSongForInfo != null) {
            val currentSong = selectedSongForInfo
            val isFavorite = remember(currentSong?.id, favoriteIds) {
                derivedStateOf { currentSong?.let { favoriteIds.contains(it.id) } }
            }.value ?: false

            if (currentSong != null) {
                val removeFromListTrigger = remember(uiState.songs) {
                    {
                        viewModel.update(uiState.songs.filterNot { it.id == currentSong.id })
                    }
                }
                SongInfoBottomSheet(
                    song = currentSong,
                    isFavorite = isFavorite,
                    onToggleFavorite = {
                        playerViewModel.toggleFavoriteSpecificSong(currentSong)
                    },
                    onDismiss = { showSongInfoBottomSheet = false },
                    onPlaySong = {
                        playerViewModel.showAndPlaySong(currentSong)
                        showSongInfoBottomSheet = false
                    },
                    onAddToQueue = {
                        playerViewModel.addSongToQueue(currentSong)
                        showSongInfoBottomSheet = false
                    },
                    onAddNextToQueue = {
                        playerViewModel.addSongNextToQueue(currentSong)
                        showSongInfoBottomSheet = false
                    },
                    onAddToPlayList = {
                        showPlaylistBottomSheet = true;
                    },
                    onDeleteFromDevice = playerViewModel::deleteFromDevice,
                    onNavigateToAlbum = {
                        navController.navigateSafelyReplacing(
                            route = Screen.AlbumDetail.createRoute(currentSong.albumId),
                            patternToPop = Screen.AlbumDetail.route
                        )
                        showSongInfoBottomSheet = false
                    },
                    onNavigateToArtist = {
                        navController.navigateSafelyReplacing(
                            route = Screen.ArtistDetail.createRoute(currentSong.artistId),
                            patternToPop = Screen.ArtistDetail.route
                        )
                        showSongInfoBottomSheet = false
                    },
                    onNavigateToArtistById = { artistId ->
                        navController.navigateSafelyReplacing(
                            route = Screen.ArtistDetail.createRoute(artistId),
                            patternToPop = Screen.ArtistDetail.route
                        )
                        showSongInfoBottomSheet = false
                    },
                    onViewTrackDetails = { showTrackDetails = true },
                    onNavigateToGenre = {
                        currentSong.genre?.let {
                            navController.navigateSafelyReplacing(
                                route = Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")),
                                patternToPop = Screen.GenreDetail.route
                            )
                        }
                        showSongInfoBottomSheet = false
                    },
                    onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                        playerViewModel.editSongMetadata(
                            currentSong,
                            newTitle,
                            newArtist,
                            newAlbum,
                            newAlbumArtist,
                            newComposer,
                            newGenre,
                            newLyrics,
                            newTrackNumber,
                            newDiscNumber,
                            replayGainTrackGainDb,
                            replayGainAlbumGainDb,
                            coverArtUpdate
                        )
                    },
                    removeFromListTrigger = removeFromListTrigger
                )

                // The canonical details surface, composed above this list's own action sheet.
                // Closing it returns here, to the sheet the user actually opened.
                if (showTrackDetails) {
                    TrackDetailsHost(
                        song = currentSong,
                        playerViewModel = playerViewModel,
                        onDismiss = { showTrackDetails = false },
                    )
                }
                if (showPlaylistBottomSheet) {
                    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

                    PlaylistBottomSheet(
                        playlistUiState = playlistUiState,
                        songs = persistentListOf(currentSong),
                        onDismiss = { showPlaylistBottomSheet = false },
                        bottomBarHeight = bottomBarHeightDp,
                        playerViewModel = playerViewModel
                    )
                }
            }
        }

    }
}
}

@Composable
private fun SharedAlbumTopBarProbe(
    album: Album,
    songsCount: Int,
    collapseFraction: Float,
    headerHeight: Dp,
    headerImageRequestSize: Size,
    onHeaderArtworkState: ((AsyncImagePainter.State) -> Unit)? = null,
    onBackPressed: () -> Unit,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit
) {
    // The page ground the artwork fades into; the scrim and the list below resolve to the same one.
    val backgroundColor = Dap.background
    val statusBarColor =
        if (LocalPixelPlayerDarkTheme.current) Color.Black.copy(alpha = 0.6f)
        else Color.White.copy(alpha = 0.4f)
    val solidAlpha = (collapseFraction * 2f).coerceIn(0f, 1f)
    val expandedContentAlpha = 1f - solidAlpha
    val showExpandedContent = expandedContentAlpha > 0.01f
    val headerOverlayBrush = remember(backgroundColor, expandedContentAlpha) {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                backgroundColor.copy(alpha = 0.22f * expandedContentAlpha),
                backgroundColor.copy(alpha = 0.82f * expandedContentAlpha),
                backgroundColor
            )
        )
    }
    val statusBarBrush = remember(statusBarColor) {
        Brush.verticalGradient(colors = listOf(statusBarColor, Color.Transparent))
    }
    val expandedStatusBarFallback = remember(statusBarColor, backgroundColor) {
        statusBarColor.compositeOver(backgroundColor)
    }
    val fallbackStatusBarColor =
        remember(expandedStatusBarFallback, backgroundColor, solidAlpha) {
            lerpColor(expandedStatusBarFallback, backgroundColor, solidAlpha)
        }

    PixelPlayerStatusBarStyle(color = Dap.background)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .clipToBounds()
    ) {
        if (showExpandedContent) {
            SmartImage(
                model = album.albumArtUriString,
                contentDescription = stringResource(R.string.album_cover_for, album.title),
                contentScale = ContentScale.Crop,
                targetSize = headerImageRequestSize,
                allowHardware = true,
                crossfadeDurationMillis = 0,
                alpha = expandedContentAlpha,
                onState = onHeaderArtworkState,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(headerOverlayBrush)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(statusBarBrush)
                .align(Alignment.TopCenter)
        )

        // The back affordance over the artwork: one floating glyph, no bar. This was already only a
        // back glyph with no container, so nothing is lost by dropping the bar that carried it —
        // and one thing is gained, because DapTopBar drew its chevron in every Back mode while the
        // overlay honours them.

        // The album's identity and its one action, anchored to the foot of the artwork and gone by
        // the time the header is half collapsed.
        if (showExpandedContent) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = DapSpacing.Gutter,
                        end = DapSpacing.Gutter,
                        bottom = DapSpacing.Lg
                    )
                    .graphicsLayer { alpha = expandedContentAlpha },
                verticalArrangement = Arrangement.spacedBy(DapSpacing.Lg)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs)) {
                    Text(
                        text = albumTitleLabel(album),
                        style = DapType.Title,
                        color = Dap.primaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Artist and track count: an observed value, in mono.
                    //
                    // With no artist the line is the count alone, not a separator with nothing in
                    // front of it. The resource still formats the two-part case, so the separator
                    // stays the translator's to choose.
                    Text(
                        text = albumArtistLabel(album)?.let { artistLabel ->
                            stringResource(
                                R.string.album_detail_meta_line,
                                artistLabel,
                                formatSongCountLabel(songsCount)
                            )
                        } ?: formatSongCountLabel(songsCount),
                        style = DapType.ValueSmall,
                        color = Dap.tertiaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DapCollectionActions(
                    playLabel = stringResource(R.string.play_playback),
                    shuffleLabel = stringResource(R.string.shortcut_shuffle_short),
                    onPlay = onPlayClick,
                    onShuffle = onShuffleClick,
                )
            }
        }
    }
}

@Composable
private fun CollapsingAlbumTopBar(
    album: Album,
    songsCount: Int,
    collapseFraction: Float,
    headerHeight: Dp,
    headerImageRequestSize: Size,
    onHeaderArtworkState: ((AsyncImagePainter.State) -> Unit)? = null,
    onBackPressed: () -> Unit,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit
) {
    val backgroundColor = Dap.background
    val statusBarColor =
        if (LocalPixelPlayerDarkTheme.current) Color.Black.copy(alpha = 0.6f)
        else Color.White.copy(alpha = 0.4f)

    val backgroundAlpha = collapseFraction
    val headerContentAlpha = 1f - (collapseFraction * 2).coerceAtMost(1f)
    val showExpandedContent = headerContentAlpha > 0.01f
    val headerOverlayBrush = remember(backgroundColor, headerContentAlpha) {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                backgroundColor.copy(alpha = 0.30f * headerContentAlpha),
                backgroundColor.copy(alpha = 0.90f * headerContentAlpha),
                backgroundColor.copy(alpha = headerContentAlpha)
            )
        )
    }
    val statusBarBrush = remember(statusBarColor) {
        Brush.verticalGradient(colors = listOf(statusBarColor, Color.Transparent))
    }
    val solidAlpha = (collapseFraction * 2f).coerceIn(0f, 1f)
    val expandedStatusBarFallback = remember(statusBarColor, backgroundColor) {
        statusBarColor.compositeOver(backgroundColor)
    }
    val fallbackStatusBarColor =
        remember(expandedStatusBarFallback, backgroundColor, solidAlpha) {
            lerpColor(expandedStatusBarFallback, backgroundColor, solidAlpha)
        }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .clipToBounds()
    ) {
        PixelPlayerStatusBarStyle(color = Dap.background)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .background(backgroundColor.copy(alpha = backgroundAlpha))
        ) {
            if (showExpandedContent) {
                SmartImage(
                    model = album.albumArtUriString,
                    contentDescription = stringResource(R.string.album_cover_for, album.title),
                    contentScale = ContentScale.Crop,
                    targetSize = headerImageRequestSize,
                    allowHardware = true,
                    crossfadeDurationMillis = 0,
                    alpha = headerContentAlpha,
                    onState = onHeaderArtworkState,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(headerOverlayBrush)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(statusBarBrush)
                    .align(Alignment.TopCenter)
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {

                if (showExpandedContent) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(
                                start = DapSpacing.Gutter,
                                end = DapSpacing.Gutter,
                                bottom = DapSpacing.Lg
                            )
                            .graphicsLayer { alpha = headerContentAlpha },
                        verticalArrangement = Arrangement.spacedBy(DapSpacing.Lg)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs)) {
                            Text(
                                text = albumTitleLabel(album),
                                style = DapType.Title,
                                color = Dap.primaryText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = albumArtistLabel(album)?.let { artistLabel ->
                                    stringResource(
                                        R.string.album_detail_meta_line,
                                        artistLabel,
                                        formatSongCountLabel(songsCount)
                                    )
                                } ?: formatSongCountLabel(songsCount),
                                style = DapType.ValueSmall,
                                color = Dap.tertiaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DapCollectionActions(
                            playLabel = stringResource(R.string.play_playback),
                            shuffleLabel = stringResource(R.string.shortcut_shuffle_short),
                            onPlay = onPlayClick,
                            onShuffle = onShuffleClick,
                        )
                    }
                }
            }
        }
    }
}
