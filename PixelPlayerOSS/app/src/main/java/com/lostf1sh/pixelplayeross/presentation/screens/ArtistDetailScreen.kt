@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.ui.ds.DapMotion
import com.lostf1sh.pixelplayeross.ui.ds.DapIconButton
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsHost
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafelyReplacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.ui.theme.LocalPixelPlayerDarkTheme
import com.lostf1sh.pixelplayeross.ui.theme.PixelPlayerStatusBarStyle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.ExpressiveScrollBar
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.SmartImageCompactListTargetSize
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.components.SongInfoBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ArtistDetailViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ArtistAlbumSection
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.utils.formatSongCountLabel
import kotlinx.coroutines.launch
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import kotlinx.collections.immutable.persistentListOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton
import com.lostf1sh.pixelplayeross.ui.ds.DapCollectionActions
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTopBar
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapRoundPlayAction
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonList
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.presentation.components.BackRowHeight
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold

private const val UseSharedCollapsibleTopBarProbe = true

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArtistDetailScreen(
    artistId: String,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()

    var isTransitionFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        isTransitionFinished = true
    }

    val lazyListState = rememberLazyListState()
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
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val isDarkTheme = LocalPixelPlayerDarkTheme.current
    val baseColorScheme = MaterialTheme.colorScheme

    val artistColorSchemePair by viewModel.artistColorScheme.collectAsStateWithLifecycle()
    val artistColorScheme = remember(artistColorSchemePair, isDarkTheme) {
        artistColorSchemePair?.let { pair -> if (isDarkTheme) pair.dark else pair.light }
            ?: baseColorScheme
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.setCustomImage(it) }
    }

    LaunchedEffect(Unit) {
        playerViewModel.collapsePlayerSheet()
    }

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

    // B29-L5: coming back from an album the list is where it was left (its state is saved with the back stack entry), so the
    // header starts collapsed when the list is scrolled, instead of expanding over rows that are no longer at the top.
    val topBarHeight = remember {
        val scrolled = lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0
        Animatable(if (scrolled) minTopBarHeightPx else maxTopBarHeightPx)
    }
    val collapseFraction by remember(minTopBarHeightPx, maxTopBarHeightPx) {
        derivedStateOf {
            1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(0f, 1f)
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0

                if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
                    return Offset.Zero
                }

                val previousHeight = topBarHeight.value
                val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight

                if (consumed.roundToInt() != 0) {
                    coroutineScope.launch {
                        topBarHeight.snapTo(newHeight)
                    }
                }

                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return super.onPostFling(consumed, available)
            }
        }
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0

            val targetValue = if (shouldExpand && canExpand) {
                maxTopBarHeightPx
            } else {
                minTopBarHeightPx
            }

            if (topBarHeight.value != targetValue) {
                coroutineScope.launch {
                    topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium))
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = artistColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Dap.background
    ) {
        DapBackScaffold(Modifier.nestedScroll(nestedScrollConnection), onBack = { navController.popBackStack() }, overHeaderArt = true) {
            when {
                uiState.isLoading && uiState.artist == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(top = BackRowHeight)
                            .padding(horizontal = DapSpacing.Gutter)
                    ) {
                        // Static skeletons while the artist resolves. Nothing spins.
                        DapSkeletonList(count = 6, showArt = false)
                    }
                }
                uiState.error != null && uiState.artist == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
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
                uiState.artist != null -> {
                    val artist = uiState.artist!!
                    val songs = uiState.songs
                    val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }

                    val albumSections = uiState.albumSections
                    val expandedSections = rememberExpandedSections()
                    LaunchedEffect(albumSections) {
                        val currentKeys = albumSections.map { it.collapseKey() }.toSet()
                        currentKeys.forEach { key ->
                            if (expandedSections[key] == null) {
                                expandedSections[key] = true
                            }
                        }
                        expandedSections.keys
                            .filterNot { it in currentKeys }
                            .forEach { staleKey -> expandedSections.remove(staleKey) }
                    }

                    val showScrollBar by remember {
                        derivedStateOf {
                            collapseFraction > 0.95f &&
                                (lazyListState.canScrollForward || lazyListState.canScrollBackward)
                        }
                    }

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
                            bottom = MiniPlayerHeight + 8.dp
                        )
                    ) {
                        albumSections.forEachIndexed { index, section ->
                            if (section.songs.isEmpty()) return@forEachIndexed

                            val sectionKey = section.collapseKey()
                            val isExpanded = expandedSections[sectionKey] ?: true
                            val sectionSongs = if (isTransitionFinished) section.songs else section.songs.take(5)

                            item(
                                key = "${sectionKey}_header",
                                contentType = "artist_section_header"
                            ) {
                                CollapsibleAlbumSectionHeader(
                                    section = section,
                                    isExpanded = isExpanded,
                                    onToggleExpanded = {
                                        expandedSections[sectionKey] = !isExpanded
                                    },
                                    onOpenAlbum = {
                                        // The album's own page; Back returns here, to the same place in the list.
                                        navController.navigateSafelyReplacing(
                                            route = Screen.AlbumDetail.createRoute(section.albumId),
                                            patternToPop = Screen.AlbumDetail.route,
                                        )
                                    },
                                    onPlayAlbum = {
                                        section.songs.firstOrNull()?.let { firstSong ->
                                            playerViewModel.showAndPlaySong(firstSong, section.songs, source = ListenSource(ListenSourceKind.ARTIST, artist.id.toString()))
                                        }
                                    }
                                )
                            }

                            if (isExpanded) {
                                item(
                                    key = "${sectionKey}_song_group_spacer",
                                    contentType = "artist_section_spacer"
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .animateItem(
                                                fadeInSpec = tween(durationMillis = 160),
                                                fadeOutSpec = tween(durationMillis = 120),
                                                placementSpec = tween(durationMillis = 180)
                                            )
                                            .fillMaxWidth()
                                            .height(10.dp)
                                    )
                                }

                                itemsIndexed(
                                    items = sectionSongs,
                                    key = { songIndex, song -> "${sectionKey}_song_${song.id}_$songIndex" },
                                    contentType = { _, _ -> "artist_section_song" }
                                ) { songIndex, song ->
                                    ArtistAlbumSectionSongItem(
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = tween(durationMillis = 180),
                                            fadeOutSpec = tween(durationMillis = 120),
                                            placementSpec = tween(durationMillis = 200)
                                        ),
                                        song = song,
                                        songIndex = songIndex,
                                        songCount = section.songs.size,
                                        isCurrentSong = stablePlayerState.currentSong?.id == song.id,
                                        isPlaying = stablePlayerState.isPlaying,
                                        onSongClick = {
                                            playerViewModel.showAndPlaySong(song, section.songs, source = ListenSource(ListenSourceKind.ARTIST, artist.id.toString()))
                                        },
                                        onMoreOptionsClick = {
                                            playerViewModel.selectSongForInfo(song)
                                            showSongInfoBottomSheet = true
                                        }
                                    )
                                }
                            }

                            item(
                                key = "${sectionKey}_footer",
                                contentType = "artist_section_footer"
                            ) {
                                Spacer(
                                    modifier = Modifier.height(
                                        if (index == albumSections.lastIndex) 24.dp else 16.dp
                                    )
                                )
                            }
                        }


                    }

                    if (collapseFraction > 0.95f) {
                        ExpressiveScrollBar(
                            listState = lazyListState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(
                                    top = minTopBarHeight + 12.dp,
                                    bottom = MiniPlayerHeight + 8.dp
                                )
                        )
                    }

                    if (UseSharedCollapsibleTopBarProbe) {
                        SharedArtistTopBarProbe(
                            artist = artist,
                            effectiveImageUrl = uiState.effectiveImageUrl,
                            songsCount = songs.size,
                            collapseFraction = collapseFraction,
                            headerHeight = currentTopBarHeightDp,
                            headerImageRequestSize = headerImageRequestSize,
                            hasCustomImage = !artist.customImageUri.isNullOrBlank(),
                            onBackPressed = { navController.popBackStack() },
                            onPlayClick = {
                                // The whole artist in the order the page shows it: album by album,
                                // every track (the list itself may show only the first five).
                                val inPageOrder = albumSections.flatMap { it.songs }
                                if (inPageOrder.isNotEmpty()) {
                                    playerViewModel.showAndPlaySong(
                                        inPageOrder.first(),
                                        inPageOrder,
                                        queueName = artist.name,
                                        source = ListenSource(ListenSourceKind.ARTIST, artist.id.toString())
                                    )
                                }
                            },
                            onShuffleClick = {
                                if (songs.isNotEmpty()) {
                                    playerViewModel.playSongsShuffled(
                                        songs,
                                        artist.name,
                                        startAtZero = true,
                                        source = ListenSource(ListenSourceKind.ARTIST, artist.id.toString())
                                    )
                                }
                            },
                            onChangeImage = { imagePickerLauncher.launch("image/*") },
                            onClearCustomImage = { viewModel.clearCustomImage() }
                        )
                    } else {
                        CustomCollapsingTopBar(
                            artist = artist,
                            effectiveImageUrl = uiState.effectiveImageUrl,
                            hasCustomImage = !artist.customImageUri.isNullOrBlank(),
                            songsCount = songs.size,
                            collapseFraction = collapseFraction,
                            headerHeight = currentTopBarHeightDp,
                            headerImageRequestSize = headerImageRequestSize,
                            onBackPressed = { navController.popBackStack() },
                            onPlayClick = {
                                // The whole artist in the order the page shows it: album by album,
                                // every track (the list itself may show only the first five).
                                val inPageOrder = albumSections.flatMap { it.songs }
                                if (inPageOrder.isNotEmpty()) {
                                    playerViewModel.showAndPlaySong(
                                        inPageOrder.first(),
                                        inPageOrder,
                                        queueName = artist.name,
                                        source = ListenSource(ListenSourceKind.ARTIST, artist.id.toString())
                                    )
                                }
                            },
                            onShuffleClick = {
                                if (songs.isNotEmpty()) {
                                    playerViewModel.playSongsShuffled(
                                        songs,
                                        artist.name,
                                        startAtZero = true,
                                        source = ListenSource(ListenSourceKind.ARTIST, artist.id.toString())
                                    )
                                }
                            },
                            onChangeImage = { imagePickerLauncher.launch("image/*") },
                            onClearCustomImage = { viewModel.clearCustomImage() }
                        )
                    }
                }
            }
        }
    }

    if (showSongInfoBottomSheet && selectedSongForInfo != null) {
        val currentSong = selectedSongForInfo
        val isFavorite = remember(currentSong?.id, favoriteIds) {
            currentSong?.let { favoriteIds.contains(it.id) } ?: false
        }

        if (currentSong != null) {
            val removeFromListTrigger = remember(uiState.songs) {
                {
                    viewModel.removeSongFromAlbumSection(currentSong.id)
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
                    showPlaylistBottomSheet = true
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
                    playerViewModel = playerViewModel,
                )
            }
        }
    }
    }
}

private fun ArtistAlbumSection.collapseKey(): String = "artist_album_${albumId}_${title}"

/**
 * Which album blocks of the artist page are folded, kept across a trip to an album's page (B29-L5): the page is left
 * composed-out while the album is on top, and a plain `remember` forgot the folds, so Back returned to a taller list under the
 * same scroll offset.
 */
@Composable
internal fun rememberExpandedSections(): SnapshotStateMap<String, Boolean> = rememberSaveable(
    saver = Saver<SnapshotStateMap<String, Boolean>, List<Any>>(
        save = { map -> map.flatMap { (key, expanded) -> listOf(key, expanded) } },
        restore = { saved ->
            mutableStateMapOf<String, Boolean>().also { map ->
                saved.chunked(2).forEach { (key, expanded) -> map[key as String] = expanded as Boolean }
            }
        },
    ),
) { mutableStateMapOf<String, Boolean>() }

/**
 * The album's block on the artist page. **Two different things, two different targets (B29-L5):** the artwork and the title
 * open the album's page; the chevron folds its songs away (the play disc plays it). Tapping the block used to fold it, so there
 * was no way into the album from the artist.
 */
@Composable
internal fun CollapsibleAlbumSectionHeader(
    section: ArtistAlbumSection,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAlbum: () -> Unit,
    onPlayAlbum: () -> Unit
) {
    val expandIconRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = DapMotion.micro(),
        label = "ArtistAlbumExpandRotation"
    )
    // Resolved in composable scope: the count is a plural resource, so it cannot be read from
    // inside the `remember` lambda below.
    val songCountLabel = formatSongCountLabel(section.songs.size)
    val subtitle = remember(section.year, songCountLabel) {
        buildString {
            section.year?.takeIf { it > 0 }?.let {
                append(it.toString())
                append(" • ")
            }
            append(songCountLabel)
        }
    }

    val playCd = stringResource(R.string.presentation_batch_d_cd_play_title, section.title)
    val openCd = stringResource(R.string.artist_open_album_cd, section.title)
    val toggleCd = if (isExpanded) {
        stringResource(R.string.presentation_batch_d_cd_collapse_title, section.title)
    } else {
        stringResource(R.string.presentation_batch_d_cd_expand_title, section.title)
    }

    // The album's block on the artist page: artwork, title, the year and count in mono, the play
    // action, and the chevron that folds the songs away. A row on the page, not a card.
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DapSizes.MinTouchTarget)
                .padding(vertical = DapSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = openCd, role = Role.Button, onClick = onOpenAlbum),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmartImage(
                    model = section.albumArtUriString,
                    contentDescription = section.title,
                    targetSize = SmartImageCompactListTargetSize,
                    modifier = Modifier
                        .size(DapSizes.ThumbFeature)
                        .clip(RoundedCornerShape(DapRadius.Surface))
                )
                Spacer(modifier = Modifier.width(DapSpacing.Md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.title,
                        style = DapType.MediaRow,
                        color = Dap.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(DapSpacing.Xs))
                    Text(
                        text = subtitle,
                        style = DapType.ValueSmall,
                        color = Dap.tertiaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Two separate targets, each a full 48 dp, 8 dp apart. The play disc used to be 44 dp
            // with the chevron pressed against it — and the chevron was only a glyph, so a touch
            // just right of play landed on the row and folded the album instead of playing it.
            Spacer(modifier = Modifier.width(DapSpacing.Md))
            DapIconButton(
                kind = DapIconKind.Play,
                contentDescription = playCd,
                onClick = onPlayAlbum,
            )
            Spacer(modifier = Modifier.width(DapSpacing.Sm))
            DapIconButton(
                kind = DapIconKind.ChevronDown,
                contentDescription = toggleCd,
                onClick = onToggleExpanded,
                modifier = Modifier.graphicsLayer { rotationZ = expandIconRotation },
            )
        }
        DapDivider()
    }
}

@Composable
private fun ArtistAlbumSectionSongItem(
    modifier: Modifier = Modifier,
    song: Song,
    songIndex: Int,
    songCount: Int,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onSongClick: () -> Unit,
    onMoreOptionsClick: () -> Unit
) {
    // The songs sit directly on the page; the album block above them is what groups them, and a
    // hairline separates one from the next.
    Column(modifier = modifier.fillMaxWidth()) {
        EnhancedSongListItem(
            modifier = Modifier.fillMaxWidth(),
            song = song,
            isCurrentSong = isCurrentSong,
            isPlaying = isPlaying,
            showAlbumArt = false,
            customShape = RectangleShape,
            onMoreOptionsClick = { onMoreOptionsClick() },
            onClick = onSongClick
        )

        if (songIndex < songCount - 1) {
            DapDivider()
        }
    }
}

@Composable
private fun SharedArtistTopBarProbe(
    artist: Artist,
    effectiveImageUrl: String?,
    songsCount: Int,
    collapseFraction: Float,
    headerHeight: Dp,
    headerImageRequestSize: Size,
    hasCustomImage: Boolean,
    onBackPressed: () -> Unit,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onChangeImage: () -> Unit,
    onClearCustomImage: () -> Unit
) {
    var showImageMenu by remember { mutableStateOf(false) }
    val editImageCd = stringResource(R.string.presentation_batch_d_edit_artist_image_cd)
    // The page ground the artwork fades into.
    val backgroundColor = Dap.background
    val statusBarColor =
        if (LocalPixelPlayerDarkTheme.current) Color.Black.copy(alpha = 0.6f)
        else Color.White.copy(alpha = 0.4f)
    val solidAlpha = (collapseFraction * 2f).coerceIn(0f, 1f)
    val expandedContentAlpha = 1f - solidAlpha
    val showExpandedContent = expandedContentAlpha > 0.01f
    val displayUrl = effectiveImageUrl?.takeIf { it.isNotBlank() }
    val headerOverlayBrush = remember(backgroundColor, expandedContentAlpha) {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                backgroundColor.copy(alpha = 0.24f * expandedContentAlpha),
                backgroundColor.copy(alpha = 0.84f * expandedContentAlpha),
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
            if (!displayUrl.isNullOrEmpty()) {
                SmartImage(
                    model = displayUrl,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    targetSize = headerImageRequestSize,
                    allowHardware = true,
                    crossfadeDurationMillis = 0,
                    alpha = expandedContentAlpha,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                MusicIconPattern(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = expandedContentAlpha }
                )
            }

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

        // The Back affordance over the artwork is the overlay, not the bar's own glyph.
        //
        // The bar drew a chevron in every Back mode, including Gestures, where the mode says a
        // control that exists only to perform Back must not be drawn. With no `onBack` the bar
        // renders the image action it carries and nothing else, so the action stays exactly where
        // it was and only the Back affordance changes.
        DapTopBar(
            onBack = null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
            actions = {
                Box {
                    DapOverflowButton(
                        onClick = { showImageMenu = true },
                        description = editImageCd
                    )

                    DropdownMenu(
                        expanded = showImageMenu,
                        onDismissRequest = { showImageMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.presentation_batch_d_change_photo)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.AddAPhoto, contentDescription = null)
                            },
                            onClick = {
                                showImageMenu = false
                                onChangeImage()
                            }
                        )
                        if (hasCustomImage) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.presentation_batch_d_reset_to_default)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Delete, contentDescription = null)
                                },
                                onClick = {
                                    showImageMenu = false
                                    onClearCustomImage()
                                }
                            )
                        }
                    }
                }
            }
        )

        // The artist's name and track count, over the foot of the artwork.
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
                        text = artist.name,
                        style = DapType.Title,
                        color = Dap.primaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatSongCountLabel(songsCount),
                        style = DapType.ValueSmall,
                        color = Dap.tertiaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // The same play/shuffle pair as an album, in the same place.
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CustomCollapsingTopBar(
    artist: Artist,
    effectiveImageUrl: String?,
    hasCustomImage: Boolean,
    songsCount: Int,
    collapseFraction: Float,
    headerHeight: Dp,
    headerImageRequestSize: Size,
    onBackPressed: () -> Unit,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onChangeImage: () -> Unit,
    onClearCustomImage: () -> Unit
) {
    var showImageMenu by remember { mutableStateOf(false) }
    val editImageCd = stringResource(R.string.presentation_batch_d_edit_artist_image_cd)
    val backgroundColor = Dap.background
    val statusBarColor =
        if (LocalPixelPlayerDarkTheme.current) Color.Black.copy(alpha = 0.6f)
        else Color.White.copy(alpha = 0.4f)

    val backgroundAlpha = collapseFraction
    val headerContentAlpha = 1f - (collapseFraction * 2).coerceAtMost(1f)
    val showExpandedContent = headerContentAlpha > 0.01f
    val showImageAction = collapseFraction < 0.5f
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
                val displayUrl = effectiveImageUrl?.takeIf { it.isNotBlank() }
                if (!displayUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(displayUrl)
                            .size(headerImageRequestSize)
                            .allowHardware(true)
                            .crossfade(false)
                            .build(),
                        contentDescription = artist.name,
                        contentScale = ContentScale.Crop,
                        alpha = headerContentAlpha,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    MusicIconPattern(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = headerContentAlpha }
                    )
                }
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
                // Back is the overlay, for the same reason as the branch above; the bar keeps the
                // image action.
                DapTopBar(
                    onBack = null,
                    modifier = Modifier.align(Alignment.TopCenter),
                    actions = {
                        if (showImageAction) {
                            Box {
                                DapOverflowButton(
                                    onClick = { showImageMenu = true },
                                    description = editImageCd
                                )
                                DropdownMenu(
                                    expanded = showImageMenu,
                                    onDismissRequest = { showImageMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.presentation_batch_d_change_photo)) },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.AddAPhoto, contentDescription = null)
                                        },
                                        onClick = {
                                            showImageMenu = false
                                            onChangeImage()
                                        }
                                    )
                                    if (hasCustomImage) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.presentation_batch_d_reset_to_default)) },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.Delete, contentDescription = null)
                                            },
                                            onClick = {
                                                showImageMenu = false
                                                onClearCustomImage()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                )

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
                                text = artist.name,
                                style = DapType.Title,
                                color = Dap.primaryText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatSongCountLabel(songsCount),
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

@Composable
private fun MusicIconPattern(modifier: Modifier = Modifier) {
    // The pattern that stands in for an artist with no image: settled graphite, no tonal
    // container — the page ground with the glyphs read as texture on it.
    val color1 = Dap.disabledText
    val color2 = Dap.tertiaryText.copy(alpha = 0.7f)

    Box(modifier = modifier.background(Dap.surfaceRaised)) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 76.dp, y = 72.dp)
                .size(60.dp)
                .graphicsLayer { rotationZ = -12f }
        )
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 4.dp, y = 78.dp)
                .size(50.dp)
                .graphicsLayer { rotationZ = 18f }
        )
        Icon(
            imageVector = Icons.Rounded.Album,
            contentDescription = null, tint = color2,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 4.dp, y = (-70).dp)
                .size(70.dp)
                .graphicsLayer { rotationZ = 12f }
        )
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = 14.dp, y = (-6).dp)
                .size(60.dp)
                .graphicsLayer { rotationZ = 14f }
        )
        Icon(
            imageVector = Icons.Rounded.SurroundSound,
            contentDescription = null, tint = color2,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(x = (-24).dp, y = 32.dp)
                .size(80.dp)
                .graphicsLayer { rotationZ = 10f }
        )
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-18).dp, y = (-138).dp)
                .size(45.dp)
                .graphicsLayer { rotationZ = -18f }
        )
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null, tint = color2,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 72.dp, y = 34.dp)
                .size(45.dp)
                .graphicsLayer { rotationZ = -8f }
        )
    }
}
