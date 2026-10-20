package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.utils.currentLocale
import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsHost
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafelyReplacing

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.lostf1sh.pixelplayeross.ui.ds.DapIconButtonEdgeInset
import com.lostf1sh.pixelplayeross.ui.ds.DapIconButton
import com.lostf1sh.pixelplayeross.ui.ds.DapAlbumRow
import com.lostf1sh.pixelplayeross.ui.ds.DapArtworkPlaceholder
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapEmptyState
import com.lostf1sh.pixelplayeross.ui.ds.DapHeights
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapRoundPlayAction
import com.lostf1sh.pixelplayeross.ui.ds.DapSectionHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTabUnderline
import com.lostf1sh.pixelplayeross.ui.ds.DapTitleMeta
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import java.util.Locale
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.model.SearchHistoryItem
import com.lostf1sh.pixelplayeross.data.model.SearchResultItem
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.components.SmartImageListTargetSize
import com.lostf1sh.pixelplayeross.presentation.components.SongInfoBottomSheet
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.theme.LocalPixelPlayerDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusModifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistCover
import com.lostf1sh.pixelplayeross.presentation.components.resolveMainScreenBottomGradientHeight
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreCategoriesGrid
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreSearchSections
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.rememberSettledQuery
import com.lostf1sh.pixelplayeross.presentation.utils.SearchGenreMatcher
import com.lostf1sh.pixelplayeross.presentation.utils.searchSectionOrder
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.utils.formatSongCountLabel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.presentation.components.albumTitleLabel
import com.lostf1sh.pixelplayeross.presentation.components.albumArtistLabel

private data class SearchUiSlice(
    val selectedSearchFilter: SearchFilterType = SearchFilterType.ALL,
    val searchResults: ImmutableList<SearchResultItem> = persistentListOf()
)

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchScreen(
    paddingValues: PaddingValues,
    playerViewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    navController: NavHostController,
    onSearchBarActiveChange: (Boolean) -> Unit = {}
) {
    var searchQuery by rememberSaveable { mutableStateOf(playerViewModel.searchQuery) }
    val statusBarTopInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    val bottomGradientHeight = resolveMainScreenBottomGradientHeight(navBarCompactMode, MiniPlayerHeight)
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val searchUiState by remember(playerViewModel) {
        playerViewModel.playerUiState
            .map { uiState ->
                SearchUiSlice(
                    selectedSearchFilter = uiState.selectedSearchFilter,
                    searchResults = uiState.searchResults
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = SearchUiSlice())
    val currentFilter = searchUiState.selectedSearchFilter
    val genres by playerViewModel.genres.collectAsStateWithLifecycle()
    // B33-U5: the genres the (settled) query names, for the folded section at the foot of All. In memory only (the genre list the browser
    // already holds): nothing is read from the library on a keystroke, and nothing here touches the ordinary search.
    val settledQuery = rememberSettledQuery(searchQuery)
    val matchedGenres = remember(settledQuery, genres, currentFilter) {
        if (currentFilter == SearchFilterType.ALL) SearchGenreMatcher.match(settledQuery, genres) else emptyList()
    }
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    // Cleared whenever the action sheet closes, by keying on its visibility: every
    // dismissal path in this file sets that flag, so this cannot go stale.
    var showTrackDetails by remember(showSongInfoBottomSheet) { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchInputFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        onSearchBarActiveChange(false)
    }

    LaunchedEffect(playerViewModel, keyboardController) {
        playerViewModel.searchNavDoubleTapEvents.collect {
            delay(40L)
            searchInputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(searchQuery, currentFilter) {
        playerViewModel.performSearch(searchQuery)
    }
    val searchResults = searchUiState.searchResults
    val handleSongMoreOptionsClick: (Song) -> Unit = { song ->
        playerViewModel.selectSongForInfo(song)
        showSongInfoBottomSheet = true
    }

    val colorScheme = MaterialTheme.colorScheme
    val clearQueryCd = stringResource(R.string.cd_clear_search_query)
    // Hoisted out of the remember: a CompositionLocal cannot be read inside it, and keying on the
    // captured colour is what stops the brush keeping a stale palette after a theme change.
    val pageBackground = Dap.background
    val bottomGradientBrush = remember(pageBackground) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.2f to Color.Transparent,
                0.8f to pageBackground,
                1.0f to pageBackground
            )
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            onSearchBarActiveChange(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Dap.background)
    ) {

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = DapSpacing.Gutter,
                        top = statusBarTopInset + DapSpacing.Md,
                        // The row ends in an icon button: its glyph, not its target, sits on the
                        // gutter. The old 4 dp end left the "Ajustes" label 8 dp from the edge.
                        end = DapSpacing.Gutter - DapIconButtonEdgeInset
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // A flat field on bg/1: 44 tall, a 4 dp radius, one hairline outline, the search
                // glyph at text/3. No pill, no tonal container, no elevation.
                // The whole bar is one field for the keyboard: touching the clear button keeps the
                // keyboard up, touching anything outside the bar closes it.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .dapTextField()
                        .height(DapHeights.Control)
                        .clip(RoundedCornerShape(DapRadius.Surface))
                        .background(Dap.surface)
                        .border(
                            1.dp,
                            Dap.border,
                            RoundedCornerShape(DapRadius.Surface)
                        )
                        .padding(horizontal = DapSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DapIcon(kind = DapIconKind.Search, tint = Dap.tertiaryText, size = 16.dp)
                    Spacer(Modifier.width(DapSpacing.Md))
                    Box(Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_placeholder),
                                style = DapType.Body,
                                color = Dap.tertiaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                playerViewModel.updateSearchQuery(it)
                            },
                            singleLine = true,
                            textStyle = DapType.Body.copy(color = Dap.primaryText),
                            cursorBrush = SolidColor(LocalDapAccent.current),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            // Search submits and finishes: focus goes too, not just the keyboard,
                            // so the cursor stops blinking in a field nobody is typing into.
                            keyboardActions = dapDismissKeyboardActions(onSubmit = {
                                if (searchQuery.isNotBlank()) {
                                    playerViewModel.onSearchQuerySubmitted(searchQuery)
                                }
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchInputFocusRequester)
                        )
                    }
                    if (searchQuery.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .size(DapSizes.MinTouchTarget)
                                .clip(RoundedCornerShape(DapRadius.Surface))
                                .clickable {
                                    searchQuery = ""
                                    playerViewModel.updateSearchQuery("")
                                }
                                .semantics {
                                    contentDescription = clearQueryCd
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // The set has no cross glyph, so the clear affordance is typed.
                            Text(
                                text = "\u00D7",
                                style = DapType.Body,
                                color = Dap.secondaryText,
                                maxLines = 1
                            )
                        }
                    }
                }

                // The same gear as Home: one action, one way of drawing it.
                Spacer(Modifier.width(DapSpacing.Sm))
                DapIconButton(
                    kind = DapIconKind.Gear,
                    contentDescription = stringResource(R.string.presentation_batch_d_open_settings_cd),
                    onClick = { navController.navigateSafely(Screen.Settings.route) }
                )
            }

            val showGenreBrowse by remember(searchQuery) { derivedStateOf { searchQuery.isBlank() } }
            if (showGenreBrowse) {
                GenreCategoriesGrid(
                    genres = genres,
                    onGenreClick = { genre ->
                        Timber.tag("SearchScreen")
                            .d("Genre clicked: ${genre.name} (ID: ${genre.id})")
                        val encodedGenreId = java.net.URLEncoder.encode(genre.id, "UTF-8")
                        navController.navigateSafely(Screen.GenreDetail.createRoute(encodedGenreId))
                    },
                    playerViewModel = playerViewModel,
                    modifier = Modifier.padding(top = 12.dp)
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                        // The filters are the same mono tab strip the Library uses: an accent
                        // underline under the active one, scrollable when the labels are long.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(
                                    start = DapSpacing.Gutter - DapSpacing.Xs,
                                    end = DapSpacing.Gutter
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SearchFilterChip(SearchFilterType.ALL, currentFilter, playerViewModel)
                            SearchFilterChip(SearchFilterType.SONGS, currentFilter, playerViewModel)
                            SearchFilterChip(SearchFilterType.ALBUMS, currentFilter, playerViewModel)
                            SearchFilterChip(SearchFilterType.ARTISTS, currentFilter, playerViewModel)
                            SearchFilterChip(SearchFilterType.PLAYLISTS, currentFilter, playerViewModel)
                        }
                        DapDivider()
                        val onResultSelected = {
                            if (searchQuery.isNotBlank()) {
                                playerViewModel.onSearchQuerySubmitted(searchQuery)
                            }
                        }
                        // The folded "Genre · X" sections, last in All (B33-U5); none when the query names no genre or a tab other than All is open.
                        val genreSections: (@Composable () -> Unit)? = if (matchedGenres.isEmpty()) null else {
                            {
                                GenreSearchSections(
                                    genres = matchedGenres,
                                    load = { name -> playerViewModel.songsOfGenreOnce(name) },
                                    onSeeAll = { genre ->
                                        navController.navigateSafely(Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(genre.id, "UTF-8")))
                                        onResultSelected()
                                    },
                                    onOpenAlbum = { album ->
                                        navController.navigateSafelyReplacing(
                                            route = Screen.AlbumDetail.createRoute(album.albumId),
                                            patternToPop = Screen.AlbumDetail.route
                                        )
                                        onResultSelected()
                                    },
                                    onOpenArtist = { artist ->
                                        navController.navigateSafelyReplacing(
                                            route = Screen.ArtistDetail.createRoute(artist.artistId),
                                            patternToPop = Screen.ArtistDetail.route
                                        )
                                        onResultSelected()
                                    },
                                    onPlaySong = { song, genreSongs, genre ->
                                        playerViewModel.showAndPlaySong(song, genreSongs, genre.name, source = ListenSource(ListenSourceKind.GENRE, genre.id))
                                        onResultSelected()
                                    },
                                )
                            }
                        }
                        Crossfade(
                            targetState = searchResults.isEmpty(),
                            animationSpec = tween(durationMillis = 190),
                            label = "search_results_fade",
                            modifier = Modifier.padding(horizontal = DapSpacing.Gutter)
                        ) { isEmpty ->
                            if (isEmpty) {
                                if (genreSections == null) {
                                    EmptySearchResults(
                                        searchQuery = searchQuery,
                                        colorScheme = colorScheme
                                    )
                                } else {
                                    // nothing matched the query itself, but it names a genre: the empty message stays, the section follows it
                                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = searchListBottomPadding())) {
                                        item(key = "empty") {
                                            EmptySearchResults(searchQuery = searchQuery, colorScheme = colorScheme)
                                        }
                                        item(key = "genre_sections") { genreSections() }
                                    }
                                }
                            } else {
                                SearchResultsList(
                                    results = searchResults,
                                    searchQuery = searchQuery,
                                    playerViewModel = playerViewModel,
                                    onItemSelected = onResultSelected,
                                    currentPlayingSongId = stablePlayerState.currentSong?.id,
                                    isPlaying = stablePlayerState.isPlaying,
                                    onSongMoreOptionsClick = handleSongMoreOptionsClick,
                                    navController = navController,
                                    trailingContent = genreSections
                                )
                            }
                        }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(bottomGradientHeight)
                .background(brush = bottomGradientBrush)
        )
    }

    if (showSongInfoBottomSheet && selectedSongForInfo != null) {
        val currentSong = selectedSongForInfo
        val isFavorite = remember(currentSong?.id, favoriteSongIds) {
            derivedStateOf {
                currentSong?.let { favoriteSongIds.contains(it.id) }
            }
        }.value ?: false
        val removeFromListTrigger = remember(currentSong) {
            {
                searchQuery = "$searchQuery "
            }
        }

        if (currentSong != null) {
            SongInfoBottomSheet(
                song = currentSong,
                isFavorite = isFavorite,
                removeFromListTrigger = removeFromListTrigger,
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
                        navController.navigateSafely(Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")))
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

/**
 * A group heading with its count: a tracked mono label, and the number of results for the group at
 * the trailing edge.
 */
@Composable
fun SearchResultSectionHeader(title: String, count: Int? = null) {
    DapSectionHeader(
        text = title.uppercase(currentLocale()),
        modifier = Modifier.padding(
            start = DapSpacing.Xs,
            end = DapSpacing.Xs,
            top = DapSpacing.Md,
            bottom = DapSpacing.Xs
        ),
        trailing = if (count != null) {
            { DapTitleMeta(count.toString()) }
        } else {
            null
        }
    )
}

/**
 * No results: the query that found nothing, and the truth about what to try instead.
 *
 * `colorScheme` is retained for its callers; the empty state takes its colours from the system.
 */
@Composable
fun EmptySearchResults(searchQuery: String, colorScheme: ColorScheme) {
    DapEmptyState(
        title = if (searchQuery.isNotBlank()) {
            stringResource(R.string.search_no_results_for_query, searchQuery)
        } else {
            stringResource(R.string.search_nothing_found)
        },
        message = stringResource(R.string.search_try_different_or_filters),
        modifier = Modifier.padding(
            horizontal = DapSpacing.Gutter,
            vertical = DapSpacing.GroupGap
        )
    )
}


@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SearchResultsList(
    results: ImmutableList<SearchResultItem>,
    searchQuery: String,
    playerViewModel: PlayerViewModel,
    onItemSelected: () -> Unit,
    currentPlayingSongId: String?,
    isPlaying: Boolean,
    onSongMoreOptionsClick: (Song) -> Unit,
    navController: NavHostController,
    /** B33-U5: what follows the last result, inside the same list (Search's folded genre sections). Adds one item and changes none. */
    trailingContent: (@Composable () -> Unit)? = null
) {
    val playerStableState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()

    if (results.isEmpty()) {
        DapEmptyState(
            title = stringResource(R.string.search_no_results_found),
            message = stringResource(R.string.search_try_different_or_filters),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = DapSpacing.Gutter)
        )
        return
    }

    val groupedResults = remember(results) {
        results.groupBy { item ->
            when (item) {
                is SearchResultItem.SongItem -> SearchFilterType.SONGS
                is SearchResultItem.AlbumItem -> SearchFilterType.ALBUMS
                is SearchResultItem.ArtistItem -> SearchFilterType.ARTISTS
                is SearchResultItem.PlaylistItem -> SearchFilterType.PLAYLISTS
            }
        }
    }
    val songResultsQueue = remember(groupedResults) {
        buildList {
            groupedResults[SearchFilterType.SONGS]
                ?.forEach { item ->
                    val song = (item as? SearchResultItem.SongItem)?.song ?: return@forEach
                    add(song)
                }
        }
    }
    // The queue name is drawn in the queue, so it is a UI string. "None" is a different thing: it is
    // the player's sentinel for "leave the last-playlist shortcut alone", and it is never built here.
    val trimmedSearchQuery = searchQuery.trim()
    val searchQueueName = if (trimmedSearchQuery.isEmpty()) {
        stringResource(R.string.search_queue_name)
    } else {
        stringResource(R.string.search_queue_prefix, trimmedSearchQuery)
    }
    val onSongResultClick = remember(playerViewModel, onItemSelected, songResultsQueue, searchQueueName) {
        { song: Song ->
            val playbackQueue = if (songResultsQueue.any { it.id == song.id }) {
                songResultsQueue
            } else {
                listOf(song)
            }
            playerViewModel.showAndPlaySong(song, playbackQueue, searchQueueName, source = ListenSource(ListenSourceKind.SEARCH))
            onItemSelected()
        }
    }

    // Best match first: typing an album's name exactly should not mean scrolling past every song
    // that credits one of its artists. See searchSectionOrder.
    val sectionOrder = remember(groupedResults, trimmedSearchQuery) {
        searchSectionOrder(groupedResults, trimmedSearchQuery)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = DapSpacing.Xs,
            bottom = searchListBottomPadding()
        )
    ) {
        sectionOrder.forEach { filterType ->
            val itemsForSection = groupedResults[filterType] ?: emptyList()

            if (itemsForSection.isNotEmpty()) {
                item(key = "header_${filterType.name}") {
                    SearchResultSectionHeader(
                        title = when (filterType) {
                            SearchFilterType.SONGS -> stringResource(R.string.search_results_songs)
                            SearchFilterType.ALBUMS -> stringResource(R.string.search_results_albums)
                            SearchFilterType.ARTISTS -> stringResource(R.string.search_results_artists)
                            SearchFilterType.PLAYLISTS -> stringResource(R.string.search_results_playlists)
                            else -> stringResource(R.string.search_results_other)
                        },
                        count = itemsForSection.size
                    )
                }

                items(
                    count = itemsForSection.size,
                    key = { index ->
                        val item = itemsForSection[index]
                        when (item) {
                            is SearchResultItem.SongItem -> "song_${item.song.id}"
                            is SearchResultItem.AlbumItem -> "album_${item.album.id}"
                            is SearchResultItem.ArtistItem -> "artist_${item.artist.id}"
                            is SearchResultItem.PlaylistItem -> "playlist_${item.playlist.id}_${index}"
                        }
                    },
                    contentType = { index ->
                        when (itemsForSection[index]) {
                            is SearchResultItem.SongItem -> "search_song"
                            is SearchResultItem.AlbumItem -> "search_album"
                            is SearchResultItem.ArtistItem -> "search_artist"
                            is SearchResultItem.PlaylistItem -> "search_playlist"
                        }
                    }
                ) { index ->
                    val item = itemsForSection[index]
                    Box(modifier = Modifier.fillMaxWidth()) {
                        when (item) {
                            is SearchResultItem.SongItem -> {
                                EnhancedSongListItem(
                                    song = item.song,
                                    isPlaying = isPlaying,
                                    isCurrentSong = currentPlayingSongId == item.song.id,
                                    onMoreOptionsClick = onSongMoreOptionsClick,
                                    onClick = { onSongResultClick(item.song) }
                                )
                            }

                            is SearchResultItem.AlbumItem -> {
                                val onPlayClick = remember(item.album, playerViewModel, onItemSelected) {
                                    {
                                        Timber.tag("SearchScreen")
                                            .d("Album clicked: ${item.album.title}")
                                        playerViewModel.playAlbum(item.album)
                                        onItemSelected()
                                    }
                                }
                                val onOpenClick = remember(
                                    item.album,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafelyReplacing(
                                            route = Screen.AlbumDetail.createRoute(item.album.id),
                                            patternToPop = Screen.AlbumDetail.route
                                        )
                                        onItemSelected()
                                    }
                                }
                                SearchResultAlbumItem(
                                    album = item.album,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick
                                )
                            }

                            is SearchResultItem.ArtistItem -> {
                                val onPlayClick = remember(item.artist, playerViewModel, onItemSelected) {
                                    {
                                        Timber.tag("SearchScreen")
                                            .d("Artist clicked: ${item.artist.name}")
                                        playerViewModel.playArtist(item.artist)
                                        onItemSelected()
                                    }
                                }
                                val onOpenClick = remember(
                                    item.artist,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafelyReplacing(
                                            route = Screen.ArtistDetail.createRoute(item.artist.id),
                                            patternToPop = Screen.ArtistDetail.route
                                        )
                                        onItemSelected()
                                    }
                                }
                                SearchResultArtistItem(
                                    artist = item.artist,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick
                                )
                            }

                            is SearchResultItem.PlaylistItem -> {
                                val playlistSongs by remember(item.playlist.songIds, playerViewModel) {
                                    playerViewModel.observeSongs(item.playlist.songIds)
                                        .map { it.toImmutableList() }
                                }.collectAsStateWithLifecycle(initialValue = persistentListOf())
                                val coroutineScope = rememberCoroutineScope()
                                val emptyPlaylistMessage = stringResource(R.string.search_toast_empty_playlist)
                                val onPlayClick: () -> Unit = {
                                    coroutineScope.launch {
                                        val songs = playerViewModel.getSongs(item.playlist.songIds)
                                        if (songs.isNotEmpty()) {
                                            playerViewModel.playSongs(
                                                songs,
                                                songs.first(),
                                                item.playlist.name,
                                                source = ListenSource(ListenSourceKind.PLAYLIST, item.playlist.id)
                                            )
                                            if (playerStableState.isShuffleEnabled) playerViewModel.turnShuffleOff()
                                        } else {
                                            playerViewModel.sendToast(emptyPlaylistMessage)
                                        }
                                        onItemSelected()
                                    }
                                }
                                val onOpenClick = remember(
                                    item.playlist,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafely(Screen.PlaylistDetail.createRoute(item.playlist.id))
                                        onItemSelected()
                                    }
                                }
                                SearchResultPlaylistItem(
                                    playlist = item.playlist,
                                    playlistSongs = playlistSongs,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick
                                )
                            }
                        }
                    }
                }
            }
        }
        if (trailingContent != null) {
            item(key = "genre_sections", contentType = "search_genre_sections") {
                Box(modifier = Modifier.fillMaxWidth()) { trailingContent() }
            }
        }
    }
}

/** The room a search list leaves at its foot: the mini player and the system bars, or the keyboard when it is up. */
@Composable
private fun searchListBottomPadding(): androidx.compose.ui.unit.Dp {
    val localDensity = LocalDensity.current
    val imePadding = WindowInsets.ime.getBottom(localDensity).dp
    val systemBarPaddingBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 94.dp
    return if (imePadding <= 8.dp) (MiniPlayerHeight + systemBarPaddingBottom) else imePadding
}

@Composable
fun SearchResultAlbumItem(
    album: Album,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val playCd = stringResource(R.string.cd_play_album)

    Column(Modifier.fillMaxWidth()) {
        DapAlbumRow(
            title = albumTitleLabel(album),
            subtitle = albumArtistLabel(album),
            artwork = {
                SmartImage(
                    model = album.albumArtUriString,
                    contentDescription = stringResource(R.string.cd_album_art_of, albumTitleLabel(album)),
                    targetSize = SmartImageListTargetSize,
                    modifier = Modifier.fillMaxSize()
                )
            },
            trailing = {
                DapRoundPlayAction(
                    onClick = onPlayClick,
                    modifier = Modifier.semantics { contentDescription = playCd }
                )
            },
            onClick = onOpenClick
        )
        DapDivider()
    }
}

@Composable
fun SearchResultArtistItem(
    artist: Artist,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val playCd = stringResource(R.string.cd_play_artist)

    DapMediaRow(
        title = artist.name,
        subtitle = formatSongCountLabel(artist.songCount),
        leading = {
            Box(
                modifier = Modifier
                    .size(DapSizes.ThumbRow)
                    .clip(CircleShape)
                    .background(Dap.surfaceRaised),
                contentAlignment = Alignment.Center
            ) {
                if (!artist.effectiveImageUrl.isNullOrBlank()) {
                    SmartImage(
                        model = artist.effectiveImageUrl,
                        contentDescription = stringResource(R.string.cd_artist_format, artist.name),
                        targetSize = SmartImageListTargetSize,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    DapArtworkPlaceholder(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        showGlyph = false
                    )
                }
            }
        },
        trailing = {
            DapRoundPlayAction(
                onClick = onPlayClick,
                modifier = Modifier.semantics { contentDescription = playCd }
            )
        },
        onClick = onOpenClick
    )
}

@Composable
fun SearchResultPlaylistItem(
    playlist: Playlist,
    playlistSongs: ImmutableList<Song>,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val playCd = stringResource(R.string.cd_play_playlist)

    Column(Modifier.fillMaxWidth()) {
        DapAlbumRow(
            title = playlist.name,
            subtitle = formatSongCountLabel(playlist.songIds.size),
            artwork = {
                PlaylistCover(
                    playlist = playlist,
                    playlistSongs = playlistSongs,
                    modifier = Modifier.fillMaxSize(),
                    size = DapSizes.ThumbFeature
                )
            },
            trailing = {
                DapRoundPlayAction(
                    onClick = onPlayClick,
                    modifier = Modifier.semantics { contentDescription = playCd }
                )
            },
            onClick = onOpenClick
        )
        DapDivider()
    }
}

/**
 * A search filter, as a mono label with an accent underline when it is the active one. The set and
 * its order are unchanged; only the way it is drawn is.
 */
@Composable
fun SearchFilterChip(
    filterType: SearchFilterType,
    currentFilter: SearchFilterType,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    // Localized, not derived from the enum name. `filterType.name.lowercase()` produced "Songs" /
    // "Albums" / "Artists" in every language, which is why a Spanish interface showed English
    // filter labels.
    val label = stringResource(
        when (filterType) {
            SearchFilterType.ALL -> R.string.search_filter_all
            SearchFilterType.SONGS -> R.string.search_filter_songs
            SearchFilterType.ALBUMS -> R.string.search_filter_albums
            SearchFilterType.ARTISTS -> R.string.search_filter_artists
            SearchFilterType.PLAYLISTS -> R.string.search_filter_playlists
        }
    ).uppercase(currentLocale())

    DapTabUnderline(
        label = label,
        selected = filterType == currentFilter,
        onClick = { playerViewModel.updateSearchFilter(filterType) },
        modifier = modifier
    )
}
