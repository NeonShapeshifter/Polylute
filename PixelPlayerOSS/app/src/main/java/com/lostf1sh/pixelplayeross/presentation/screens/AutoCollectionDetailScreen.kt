package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollection
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionId
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.presentation.collections.AutoCollectionsViewModel
import com.lostf1sh.pixelplayeross.presentation.collections.emptyText
import com.lostf1sh.pixelplayeross.presentation.collections.nameText
import com.lostf1sh.pixelplayeross.presentation.collections.reasonText
import com.lostf1sh.pixelplayeross.presentation.collections.songCountText
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.SongInfoBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsHost
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.stats.RowCover
import com.lostf1sh.pixelplayeross.presentation.stats.StatsTarget
import com.lostf1sh.pixelplayeross.presentation.stats.route
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapCollectionActions
import com.lostf1sh.pixelplayeross.ui.ds.DapEmptyState
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRowState
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonList
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.utils.formatDuration
import com.lostf1sh.pixelplayeross.utils.formatListeningDurationCompact
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One automatic collection: its name and the line that says why these songs, how many and for how long, Play and Shuffle, and the songs.
 *
 * The list that is open is the list that plays: Play queues exactly these songs in this order and Shuffle puts random on top of the
 * same list, never a different one. A row names a song and a tap opens it in the Library (B32-Q2; it used to play from there: playing one
 * song is the overflow's "Play"), and the overflow opens the same song actions as everywhere else.
 * An empty collection says why it is empty.
 */
@Composable
fun AutoCollectionDetailScreen(
    collectionKey: String,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: AutoCollectionsViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
) {
    val id = AutoCollectionId.fromKey(collectionKey)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val collection = id?.let { state.collection(it) }
    val isHidden = id != null && id in state.hidden
    val name = id?.nameText().orEmpty()
    val source = remember(id) { ListenSource(ListenSourceKind.COLLECTION, id?.key) }

    val currentSongId by remember {
        playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    var showSongInfoSheet by remember { mutableStateOf(false) }
    var showTrackDetails by remember(showSongInfoSheet) { mutableStateOf(false) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }

    val items = collection?.items.orEmpty().map { it.song }
    val bottomBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    if (showSongInfoSheet && selectedSongForInfo != null) {
        val song = selectedSongForInfo!!
        SongInfoBottomSheet(
            song = song,
            isFavorite = favoriteSongIds.contains(song.id),
            onToggleFavorite = { playerViewModel.toggleFavoriteSpecificSong(song) },
            onDismiss = { showSongInfoSheet = false },
            onPlaySong = {
                playerViewModel.showAndPlaySong(song, items, name, updatesLastPlaylistShortcut = false, source = source)
                showSongInfoSheet = false
            },
            onAddToQueue = {
                playerViewModel.addSongToQueue(song)
                showSongInfoSheet = false
            },
            onAddNextToQueue = {
                playerViewModel.addSongNextToQueue(song)
                showSongInfoSheet = false
            },
            onAddToPlayList = { showPlaylistBottomSheet = true },
            onDeleteFromDevice = playerViewModel::deleteFromDevice,
            onNavigateToAlbum = {
                navController.navigateSafely(Screen.AlbumDetail.createRoute(song.albumId))
                showSongInfoSheet = false
            },
            onNavigateToArtist = {
                navController.navigateSafely(Screen.ArtistDetail.createRoute(song.artistId))
                showSongInfoSheet = false
            },
            onNavigateToArtistById = { artistId ->
                navController.navigateSafely(Screen.ArtistDetail.createRoute(artistId))
                showSongInfoSheet = false
            },
            onViewTrackDetails = { showTrackDetails = true },
            onNavigateToGenre = {
                song.genre?.let {
                    navController.navigateSafely(Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")))
                }
                showSongInfoSheet = false
            },
            onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                playerViewModel.editSongMetadata(
                    song, newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics,
                    newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate,
                )
            },
            // After a file is deleted from the device the library changes and the collections are rebuilt; only the mix of the
            // day, which is kept for the day, needs the track taken out by hand.
            removeFromListTrigger = {
                if (id == AutoCollectionId.DAILY_MIX) playerViewModel.removeFromDailyMix(song.id)
            },
        )
        if (showTrackDetails) {
            TrackDetailsHost(song = song, playerViewModel = playerViewModel, onDismiss = { showTrackDetails = false })
        }
        if (showPlaylistBottomSheet) {
            val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
            PlaylistBottomSheet(
                playlistUiState = playlistUiState,
                songs = persistentListOf(song),
                onDismiss = { showPlaylistBottomSheet = false },
                bottomBarHeight = bottomBarHeight,
                playerViewModel = playerViewModel,
            )
        }
    }

    CollectionPage(
        id = id,
        collection = collection,
        isHidden = isHidden,
        currentSongId = currentSongId,
        onHide = {
            id?.let(viewModel::hide)
            navController.popBackStack()
        },
        onShow = { id?.let(viewModel::show) },
        onPlay = { playerViewModel.playSongs(items, items.first(), name, source = source) },
        onShuffle = { playerViewModel.playSongsShuffled(items, name, startAtZero = true, source = source) },
        // B32-Q2: a row names a song and opens it in the Library (its Songs tab, scrolled to it and marked), as in Stats; it plays nothing.
        // Play and Shuffle above play the collection, and the row's overflow has "Play" for one song.
        onOpenSong = { song -> navController.navigateSafely(StatsTarget.Song(song.id).route()) },
        onMore = { song ->
            playerViewModel.selectSongForInfo(song)
            showSongInfoSheet = true
        },
    )
}

/** The page of a collection as a function of its state: what the route above feeds and the render tests draw. */
@Composable
internal fun CollectionPage(
    id: AutoCollectionId?,
    collection: AutoCollection?,
    isHidden: Boolean,
    currentSongId: String?,
    onHide: () -> Unit,
    onShow: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onOpenSong: (com.lostf1sh.pixelplayeross.data.model.Song) -> Unit,
    onMore: (com.lostf1sh.pixelplayeross.data.model.Song) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val name = id?.nameText().orEmpty()
    val playLabel = stringResource(R.string.presentation_batch_b_play_it)
    val shuffleLabel = stringResource(R.string.shortcut_shuffle_short)
    // The list stops where the navigation bar begins because the scaffold does that for every pushed screen (B29-L9; it was done here, per
    // list, in B28-fix2 E4): its rows never run through the strip between the shell's mini player and the gesture bar.
    DapBackScaffold(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DapSpacing.Gutter,
                end = DapSpacing.Gutter,
                top = DapSpacing.Sm,
                bottom = LocalDapPlayerShelfHeight.current + 24.dp,
            ),
        ) {
            item(key = "title") {
                DapScreenTitle(
                    text = name,
                    trailing = if (id != null && !isHidden) {
                        {
                            androidx.compose.foundation.layout.Box {
                                DapOverflowButton(
                                    onClick = { menuOpen = true },
                                    description = stringResource(R.string.playlists_collection_options, name),
                                )
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.playlists_hide)) },
                                        onClick = {
                                            menuOpen = false
                                            onHide()
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            when {
                id == null -> item(key = "unknown") {
                    DapEmptyState(
                        title = stringResource(R.string.collection_unknown_title),
                        message = stringResource(R.string.collection_unknown_body),
                        modifier = Modifier.padding(vertical = DapSpacing.GroupGap),
                    )
                }
                collection == null -> item(key = "loading") {
                    Column(Modifier.padding(top = DapSpacing.SectionGap)) { DapSkeletonList(count = 8) }
                }
                else -> {
                    item(key = "why") { Why(collection) }
                    if (isHidden) {
                        item(key = "hidden") {
                            Row(Modifier.fillMaxWidth().padding(top = DapSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.collection_hidden_note),
                                    style = DapType.Caption,
                                    color = Dap.tertiaryText,
                                    modifier = Modifier.weight(1f),
                                )
                                DapTextAction(label = stringResource(R.string.playlists_show), onClick = onShow)
                            }
                        }
                    }
                    if (collection.isEmpty) {
                        item(key = "empty") {
                            DapEmptyState(
                                title = stringResource(R.string.collection_empty_title),
                                message = collection.emptyText(),
                                modifier = Modifier.padding(vertical = DapSpacing.GroupGap),
                            )
                        }
                    } else {
                        item(key = "actions") {
                            DapCollectionActions(
                                onPlay = onPlay,
                                onShuffle = onShuffle,
                                playLabel = playLabel,
                                shuffleLabel = shuffleLabel,
                                modifier = Modifier.padding(top = DapSpacing.Lg, bottom = DapSpacing.Sm),
                            )
                        }
                        items(collection.items, key = { it.identity }) { item ->
                            val song = item.song
                            DapMediaRow(
                                title = item.title,
                                subtitle = item.artistLine.ifBlank { null },
                                duration = formatDuration(song.duration),
                                state = if (currentSongId == song.id) DapRowState.Current else DapRowState.Normal,
                                leading = { RowCover(song.albumArtUriString, item.title) },
                                trailing = { DapOverflowButton(onClick = { onMore(song) }) },
                                onClick = { onOpenSong(song) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The line that says why these songs, then how many and for how long, then (for the high-resolution one) where it learned it. */
@Composable
private fun Why(collection: AutoCollection) {
    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Xs)) {
        Text(text = collection.reasonText(), style = DapType.Body, color = Dap.secondaryText)
        if (!collection.isEmpty) {
            Spacer(Modifier.height(DapSpacing.Xs))
            val totalMs = collection.items.sumOf { it.lengthMs }
            Text(
                text = "${songCountText(collection.items.size)} · ${formatListeningDurationCompact(totalMs)}",
                style = DapType.ValueSmall,
                color = Dap.tertiaryText,
            )
        }
        if (collection.id == AutoCollectionId.HIGH_RESOLUTION && !collection.isEmpty) {
            Spacer(Modifier.height(DapSpacing.Xs))
            Text(
                text = stringResource(
                    R.string.collection_high_resolution_source,
                    collection.fromLibrary,
                    collection.items.size - collection.fromLibrary,
                ),
                style = DapType.Note,
                color = Dap.tertiaryText,
            )
        }
    }
}
