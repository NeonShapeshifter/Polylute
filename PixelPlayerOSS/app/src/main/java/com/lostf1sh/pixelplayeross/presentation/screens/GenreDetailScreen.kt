package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.presentation.components.BackRowHeight
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.components.SongInfoBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsHost
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafelyReplacing
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.stats.RowCover
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreDetailUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreDetailViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapEmptyState
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRowState
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonList
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * A genre (B33-U4): a playlist-like page of its **songs only**. A header (the cover of the genre's biggest album behind its name, the counts line),
 * Play and Shuffle as the album and the playlist draw them, and then the songs, once each, ordered by title (then artist, then album:
 * [SourceOrder.genrePage]). There are no tabs and no sort: the albums and the artists of a genre are reached from a song's options ("Go to
 * album", "Go to artist") and from the Search screen's genre section.
 *
 * What is listed is what plays (B24): Play queues that list from its first song, a row starts from its own place in it, and Shuffle puts
 * random on top of the same list. The unknown genre also offers "Fill genres" (the quick fill) under the header, on that page only.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun GenreDetailScreen(
    navController: NavHostController,
    genreId: String,
    decodedGenreId: String = java.net.URLDecoder.decode(genreId, "UTF-8"),
    playerViewModel: PlayerViewModel,
    viewModel: GenreDetailViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The id only: a position or a buffering flag in the player's state must not recompose the list.
    val currentSongId by remember(playerViewModel) {
        playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val customGenres by playerViewModel.customGenres.collectAsStateWithLifecycle()
    val customGenreIcons by playerViewModel.customGenreIcons.collectAsStateWithLifecycle()

    val genreDisplayName = remember(uiState.genre, decodedGenreId) {
        uiState.genre?.name ?: decodedGenreId
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    }
    val genreShuffleLabel = stringResource(R.string.presentation_batch_b_genre_shuffle_label, genreDisplayName)
    val source = remember(genreId) { ListenSource(ListenSourceKind.GENRE, genreId) }
    val toastAddedToQueue = stringResource(R.string.toast_added_to_queue)
    val toastPlayingNext = stringResource(R.string.toast_playing_next)

    var showQuickFillDialog by remember { mutableStateOf(false) }
    var showSongOptionsSheet by remember { mutableStateOf<Song?>(null) }
    // Cleared whenever the action sheet closes, by keying on its visibility: every
    // dismissal path in this file sets that flag, so this cannot go stale.
    var showTrackDetails by remember(showSongOptionsSheet) { mutableStateOf(false) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val isUnknownGenre = remember(decodedGenreId) { isUnknownGenreId(decodedGenreId) }

    // Play and Shuffle act on the list as the page shows it (B24): the same songs, in the same order.
    val songs = uiState.songs

    GenrePage(
        title = genreDisplayName,
        state = uiState,
        currentSongId = currentSongId,
        isUnknownGenre = isUnknownGenre,
        onBack = { navController.popBackStack() },
        onRetry = viewModel::reload,
        onPlay = {
            songs.firstOrNull()?.let { first -> playerViewModel.playSongs(songs, first, genreDisplayName, source = source) }
        },
        onShuffle = {
            if (songs.isNotEmpty()) {
                playerViewModel.playSongsShuffled(
                    songsToPlay = songs,
                    queueName = genreShuffleLabel,
                    startAtZero = true,
                    source = source,
                )
            }
        },
        onQuickFill = { showQuickFillDialog = true },
        onPlaySong = { song -> playerViewModel.showAndPlaySong(song, songs, genreDisplayName, source = source) },
        onMoreSong = { song -> showSongOptionsSheet = song },
    )

    QuickFillDialog(
        visible = showQuickFillDialog,
        songs = remember(uiState.songs) { uiState.songs.toImmutableList() },
        customGenres = customGenres,
        customGenreIcons = customGenreIcons,
        onDismiss = { showQuickFillDialog = false },
        onApply = { songsToEdit, genre ->
            playerViewModel.batchEditGenre(songsToEdit, genre)
            showQuickFillDialog = false
        },
        onAddCustomGenre = { genre, iconRes -> playerViewModel.addCustomGenre(genre, iconRes) }
    )

    showSongOptionsSheet?.let { song ->
        SongInfoBottomSheet(
            song = song,
            isFavorite = favoriteSongIds.contains(song.id),
            onToggleFavorite = { playerViewModel.toggleFavoriteSpecificSong(song) },
            onDismiss = { showSongOptionsSheet = null },
            onPlaySong = {
                playerViewModel.showAndPlaySong(song, songs, genreDisplayName, source = source)
                showSongOptionsSheet = null
            },
            onAddToQueue = {
                playerViewModel.addSongToQueue(song)
                showSongOptionsSheet = null
                playerViewModel.sendToast(toastAddedToQueue)
            },
            onAddNextToQueue = {
                playerViewModel.addSongNextToQueue(song)
                showSongOptionsSheet = null
                playerViewModel.sendToast(toastPlayingNext)
            },
            onAddToPlayList = { showPlaylistBottomSheet = true },
            onDeleteFromDevice = playerViewModel::deleteFromDevice,
            onNavigateToAlbum = {
                navController.navigateSafelyReplacing(
                    route = Screen.AlbumDetail.createRoute(song.albumId),
                    patternToPop = Screen.AlbumDetail.route,
                )
                showSongOptionsSheet = null
            },
            onNavigateToArtist = {
                navController.navigateSafelyReplacing(
                    route = Screen.ArtistDetail.createRoute(song.artistId),
                    patternToPop = Screen.ArtistDetail.route,
                )
                showSongOptionsSheet = null
            },
            onNavigateToArtistById = { artistId ->
                navController.navigateSafelyReplacing(
                    route = Screen.ArtistDetail.createRoute(artistId),
                    patternToPop = Screen.ArtistDetail.route,
                )
                showSongOptionsSheet = null
            },
            onViewTrackDetails = { showTrackDetails = true },
            onNavigateToGenre = {
                song.genre?.let {
                    navController.navigateSafelyReplacing(
                        route = Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")),
                        patternToPop = Screen.GenreDetail.route,
                    )
                }
                showSongOptionsSheet = null
            },
            onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                playerViewModel.editSongMetadata(
                    song, newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics,
                    newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate,
                )
            },
            removeFromListTrigger = { viewModel.reload() }
        )

        // The canonical details surface, composed above this list's own action sheet.
        // Closing it returns here, to the sheet the user actually opened.
        if (showTrackDetails) {
            TrackDetailsHost(song = song, playerViewModel = playerViewModel, onDismiss = { showTrackDetails = false })
        }

        if (showPlaylistBottomSheet) {
            com.lostf1sh.pixelplayeross.presentation.components.PlaylistBottomSheet(
                playlistUiState = playlistUiState,
                songs = persistentListOf(song),
                onDismiss = { showPlaylistBottomSheet = false },
                bottomBarHeight = 0.dp,
                playerViewModel = playerViewModel
            )
        }
    }
}

/** Whether a genre route names the unknown genre (the one the quick fill is for). */
internal fun isUnknownGenreId(decodedGenreId: String): Boolean =
    decodedGenreId.equals("unknown", ignoreCase = true) || decodedGenreId.equals("unknown genre", ignoreCase = true)

/** The genre page as a function of its state: what the route above feeds and the render tests draw. */
@Composable
internal fun GenrePage(
    title: String,
    state: GenreDetailUiState,
    currentSongId: String?,
    isUnknownGenre: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onQuickFill: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onMoreSong: (Song) -> Unit,
) {
    val playLabel = stringResource(R.string.presentation_batch_b_play_it)
    val shuffleLabel = stringResource(R.string.shortcut_shuffle_short)
    val showing = state.songs.isNotEmpty()
    val coverUri = remember(state.albums) {
        state.albums.filter { !it.artUri.isNullOrBlank() }.maxByOrNull { it.songCount }?.artUri
    }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val barHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + BackRowHeight
    val barPx = with(density) { barHeight.toPx() }
    DapBackScaffold(Modifier.fillMaxSize(), onBack = onBack, overHeaderArt = true) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalDapPlayerShelfHeight.current + 24.dp),
        ) {
            item(key = "header") {
                GenreHeader(
                    title = title,
                    counts = if (showing) genreCountsLine(state) else null,
                    coverUri = coverUri,
                    playLabel = playLabel,
                    shuffleLabel = shuffleLabel,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                )
            }
            when {
                state.error != null && state.songs.isEmpty() -> item(key = "error") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter).padding(top = DapSpacing.SectionGap),
                        verticalArrangement = Arrangement.spacedBy(DapSpacing.Md),
                    ) {
                        Text(text = state.error, style = DapType.Body, color = Dap.secondaryText)
                        DapSecondaryButton(label = stringResource(R.string.library_retry), onClick = onRetry)
                    }
                }
                state.isLoadingSongs && state.songs.isEmpty() -> item(key = "loading") {
                    Column(Modifier.padding(horizontal = DapSpacing.Gutter).padding(top = DapSpacing.SectionGap)) { DapSkeletonList(count = 8) }
                }
                state.songs.isEmpty() -> item(key = "empty") {
                    DapEmptyState(
                        title = stringResource(R.string.genre_empty_title),
                        message = stringResource(R.string.genre_empty_body),
                        modifier = Modifier.padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.GroupGap),
                    )
                }
                else -> {
                    // The unknown genre's quick fill (B33-U4): a button under the header, on that page only. It used to live in the sort
                    // sheet this page no longer has.
                    if (isUnknownGenre) {
                        item(key = "quick_fill") {
                            DapSecondaryButton(
                                label = stringResource(R.string.genre_quick_fill),
                                onClick = onQuickFill,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter).padding(top = DapSpacing.Md, bottom = DapSpacing.Xs),
                                leading = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            )
                        }
                    }
                    items(state.songs, key = { it.id }, contentType = { "song" }) { song ->
                        DapMediaRow(
                            title = songTitleLabel(song),
                            subtitle = songArtistLabel(song),
                            state = if (currentSongId == song.id) DapRowState.Current else DapRowState.Normal,
                            leading = { RowCover(song.albumArtUriString, song.title) },
                            trailing = { DapOverflowButton(onClick = { onMoreSong(song) }) },
                            onClick = { onPlaySong(song) },
                            modifier = Modifier.padding(horizontal = DapSpacing.Gutter),
                        )
                    }
                }
            }
        }
        // Rows scroll under the status bar and the Back chevron once the header has gone: the bar fades in to the page's ground as the
        // header's foot reaches it (drawn from the scroll in the layer, so nothing recomposes per scroll pixel).
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(barHeight)
                .graphicsLayer { alpha = heroBarAlpha(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0, barPx) }
                .background(Dap.background)
        )
    }
}

/**
 * How solid the bar under the status bar is while a hero page scrolls: transparent while the header's foot is more than two bars below
 * the top, solid when it reaches the bar. Past the first item it is solid. [firstItemSizePx] is the header's height.
 */
internal fun heroBarAlpha(firstVisibleIndex: Int, firstVisibleOffsetPx: Int, firstItemSizePx: Int, barPx: Float): Float {
    if (firstVisibleIndex > 0) return 1f
    if (barPx <= 0f) return 0f
    val fadeStart = (firstItemSizePx - 2f * barPx).coerceAtLeast(0f)
    return ((firstVisibleOffsetPx - fadeStart) / barPx).coerceIn(0f, 1f)
}

/** "1,365 songs · 526 albums · 319 artists", the three counts of what the genre holds. */
@Composable
private fun genreCountsLine(state: GenreDetailUiState): String = listOf(
    pluralStringResource(R.plurals.genre_count_songs, state.songs.size, state.songs.size),
    pluralStringResource(R.plurals.genre_count_albums, state.albums.size, state.albums.size),
    pluralStringResource(R.plurals.genre_count_artists, state.artists.size, state.artists.size),
).joinToString(" · ")
