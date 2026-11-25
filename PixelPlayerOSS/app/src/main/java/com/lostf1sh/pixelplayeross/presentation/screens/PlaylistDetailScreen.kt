package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.PlaylistShapeType
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortDirection
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.model.isSmartPlaylist
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.components.ExpressiveScrollBar
import com.lostf1sh.pixelplayeross.presentation.components.LibrarySortBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.SongInfoBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.SongPickerBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsHost
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.components.symmetricContentPadding
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongRowHorizontalPadding
import com.lostf1sh.pixelplayeross.presentation.model.methodLabelRes
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafelyReplacing
import com.lostf1sh.pixelplayeross.presentation.utils.LocalAppHapticsConfig
import com.lostf1sh.pixelplayeross.presentation.utils.performAppCompatHapticFeedback
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistSongsOrderMode
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel.Companion.FOLDER_PLAYLIST_PREFIX
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapCollectionActions
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapMenu
import com.lostf1sh.pixelplayeross.ui.ds.DapMenuItem
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapTopBar
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.utils.formatSongCountLabel
import com.lostf1sh.pixelplayeross.utils.formatTotalDuration
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBackClick: () -> Unit,
    onDeletePlayListClick: () -> Unit,
    playerViewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    navController: NavController
) {
    val uiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val playerStableState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val currentPlaylist = uiState.currentPlaylistDetails?.takeIf { it.id == playlistId }
    val isFolderPlaylist = playlistId.startsWith(FOLDER_PLAYLIST_PREFIX)
    val isSmartPlaylist = currentPlaylist?.isSmartPlaylist == true
    val isEditablePlaylist = currentPlaylist != null && !isFolderPlaylist && !isSmartPlaylist
    val songs = uiState.currentPlaylistSongs.takeIf { currentPlaylist != null }.orEmpty()
    val sortOption = (uiState.playlistSongsOrderMode as? PlaylistSongsOrderMode.Sorted)?.option
        ?: SortOption.SongDefaultOrder
    val orderLabel = playlistOrderLabel(sortOption, isEditablePlaylist)
    val editSongsLabel = stringResource(R.string.playlist_edit_songs)
    val toastAddedToQueue = stringResource(R.string.toast_added_to_queue)
    val toastPlayingNext = stringResource(R.string.toast_playing_next)

    LaunchedEffect(playlistId) {
        playlistViewModel.loadPlaylistDetails(playlistId)
    }

    var showAddSongsSheet by remember(playlistId) { mutableStateOf(false) }
    var showSortSheet by remember(playlistId) { mutableStateOf(false) }
    var editing by remember(playlistId) { mutableStateOf(false) }
    var showSongInfoBottomSheet by remember(playlistId) { mutableStateOf(false) }
    var showTrackDetails by remember(showSongInfoBottomSheet) { mutableStateOf(false) }
    var showEditPlaylistDialog by remember(playlistId) { mutableStateOf(false) }
    var showDeleteConfirmation by remember(playlistId) { mutableStateOf(false) }

    val m3uExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        if (uri != null && currentPlaylist != null) {
            playlistViewModel.exportM3u(currentPlaylist, uri, context)
        }
    }

    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val onSongOptions: (Song) -> Unit = remember(playerViewModel, playlistId) {
        { song ->
            playerViewModel.selectSongForInfo(song)
            showSongInfoBottomSheet = true
        }
    }
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showPlaylistBottomSheet by remember(playlistId) { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val located = rememberSourceLocate(
        Screen.PlaylistDetail.createRoute(android.net.Uri.encode(playlistId)),
        listState,
        currentPlaylist != null && !uiState.isLoading,
    ) { id -> songs.indexOfFirst { it.id == id } }
    val scope = rememberCoroutineScope()
    var reorderPreview by remember(playlistId) { mutableStateOf<List<Song>?>(null) }
    var movement by remember(playlistId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingEdit by remember(playlistId) { mutableStateOf<Job?>(null) }
    val shownSongs = reorderPreview ?: songs
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        reorderPreview = (reorderPreview ?: songs).toMutableList().apply { add(to.index, removeAt(from.index)) }
        movement = (movement?.first ?: from.index) to to.index
    }

    LaunchedEffect(pendingEdit) {
        val save = pendingEdit ?: return@LaunchedEffect
        save.join()
        reorderPreview = null
        pendingEdit = null
    }

    fun saveReorder() {
        val (from, to) = movement ?: return
        movement = null
        pendingEdit = playlistViewModel.reorderSongsInPlaylist(playlistId, from, to)
        if (pendingEdit == null) reorderPreview = null
    }

    fun finishEditing() {
        saveReorder()
        editing = false
    }

    fun withSavedSongs(action: (List<Song>) -> Unit) {
        val save = pendingEdit
        if (save == null) {
            if (shownSongs.isNotEmpty()) action(shownSongs)
            return
        }
        scope.launch {
            save.join()
            val saved = playlistViewModel.uiState.value
            if (saved.currentPlaylistDetails?.id == playlistId && saved.currentPlaylistSongs.isNotEmpty()) {
                action(saved.currentPlaylistSongs)
            }
        }
    }

    BackHandler(enabled = editing, onBack = ::finishEditing)

    PlaylistPage(
        title = currentPlaylist?.name ?: stringResource(R.string.shortcut_playlist_short),
        caption = if (editing) {
            stringResource(R.string.playlist_edit_songs_hint)
        } else {
            stringResource(
                R.string.presentation_batch_f_status_bullet_step,
                formatSongCountLabel(shownSongs.size),
                formatTotalDuration(shownSongs),
            )
        },
        onBack = onBackClick,
        topBarLabel = editSongsLabel.takeIf { editing },
        topBarActions = if (editing) {
            {
                DapTextAction(
                    label = stringResource(R.string.action_done),
                    onClick = ::finishEditing,
                    color = LocalDapAccent.current,
                )
            }
        } else {
            currentPlaylist?.let { playlist ->
                {
                    key(playlist.id) {
                        PlaylistDetailMenu(
                            orderLabel = orderLabel,
                            isFolderPlaylist = isFolderPlaylist,
                            isEditablePlaylist = isEditablePlaylist,
                            onAdd = { showAddSongsSheet = true },
                            onSort = { showSortSheet = true },
                            onEditSongs = { editing = true },
                            onEditPlaylist = { showEditPlaylistDialog = true },
                            onTransition = { navController.navigateSafely(Screen.EditTransition.createRoute(playlistId)) },
                            onExport = { m3uExportLauncher.launch("${PlaylistViewModel.sanitizeFileName(playlist.name)}.m3u") },
                            onDelete = { showDeleteConfirmation = true },
                        )
                    }
                }
            }
        },
        belowHeader = if (currentPlaylist != null && !editing) {
            {
                val source = ListenSource(ListenSourceKind.PLAYLIST, playlistId)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Sm),
                    horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DapCollectionActions(
                        playLabel = stringResource(R.string.presentation_batch_b_play_it),
                        shuffleLabel = stringResource(R.string.shortcut_shuffle_short),
                        onPlay = {
                            withSavedSongs { queue ->
                                playerViewModel.playSongs(queue, queue.first(), currentPlaylist.name, source = source)
                                if (playerStableState.isShuffleEnabled) playerViewModel.turnShuffleOff()
                            }
                        },
                        onShuffle = {
                            withSavedSongs { queue ->
                                playerViewModel.playSongsShuffled(
                                    songsToPlay = queue,
                                    queueName = currentPlaylist.name,
                                    playlistId = playlistId,
                                    startAtZero = true,
                                    source = source,
                                )
                            }
                        },
                    )
                    Text(
                        text = orderLabel,
                        modifier = Modifier.weight(1f),
                        style = DapType.Caption,
                        color = Dap.secondaryText,
                        textAlign = TextAlign.End,
                    )
                }
            }
        } else {
            null
        },
    ) {
        when {
            uiState.playlistNotFound -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(stringResource(R.string.playlist_not_found))
            }
            currentPlaylist == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                ContainedLoadingIndicator()
            }
            shownSongs.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.MusicOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.presentation_batch_b_playlist_empty_title), style = MaterialTheme.typography.titleMedium)
                    val message = when {
                        isFolderPlaylist -> R.string.presentation_batch_b_playlist_empty_folder_body
                        isSmartPlaylist -> R.string.presentation_batch_b_playlist_empty_smart_body
                        else -> R.string.presentation_batch_b_playlist_empty_add_hint
                    }
                    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> Box(Modifier.fillMaxSize()) {
                val playback = rememberLibraryPlaybackStates(playerViewModel)
                val shelfHeight = LocalDapPlayerShelfHeight.current
                val view = LocalView.current
                val haptics = LocalAppHapticsConfig.current
                val endPadding = shelfHeight + DapSpacing.Xl
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = symmetricContentPadding(
                        horizontal = PlaylistSongListInset,
                        top = DapSpacing.Sm,
                        bottom = endPadding,
                    ),
                ) {
                    items(shownSongs, key = { it.id }, contentType = { "playlist_song" }) { song ->
                        if (editing) {
                            ReorderableItem(state = reorderState, key = song.id) { isDragging ->
                                EnhancedSongListItem(
                                    song = song,
                                    isPlaying = false,
                                    albumArtSize = 50.dp,
                                    containerColorOverride = if (isDragging) Dap.panelRaised else null,
                                    showMoreOptionsButton = false,
                                    onMoreOptionsClick = {},
                                    onClick = null,
                                    leading = {
                                        IconButton(
                                            modifier = Modifier.onGutter(),
                                            enabled = pendingEdit == null && !reorderState.isAnyItemDragging,
                                            onClick = { pendingEdit = playlistViewModel.removeSongFromPlaylist(playlistId, song.id) },
                                        ) {
                                            Icon(
                                                Icons.Filled.RemoveCircleOutline,
                                                stringResource(R.string.cd_remove),
                                                tint = if (pendingEdit == null && !reorderState.isAnyItemDragging) Dap.error else Dap.disabledText,
                                            )
                                        }
                                    },
                                    trailing = {
                                        IconButton(
                                            onClick = {},
                                            enabled = pendingEdit == null,
                                            modifier = Modifier.draggableHandle(
                                                enabled = pendingEdit == null,
                                                onDragStarted = {
                                                    performAppCompatHapticFeedback(view, haptics, HapticFeedbackConstantsCompat.GESTURE_START)
                                                },
                                                onDragStopped = {
                                                    saveReorder()
                                                    performAppCompatHapticFeedback(view, haptics, HapticFeedbackConstantsCompat.GESTURE_END)
                                                },
                                            ),
                                        ) {
                                            Icon(
                                                Icons.Rounded.DragIndicator,
                                                stringResource(R.string.presentation_batch_b_reorder_song),
                                                tint = Dap.secondaryText,
                                            )
                                        }
                                    },
                                )
                            }
                        } else {
                            LibraryPlaybackAwareSongItem(
                                song = song,
                                playback = playback,
                                showDuration = true,
                                isLocated = located == song.id,
                                onMoreOptionsClick = onSongOptions,
                                onClick = {
                                    withSavedSongs { queue ->
                                        queue.firstOrNull { it.id == song.id }?.let { start ->
                                            playerViewModel.playSongs(
                                                queue, start, currentPlaylist.name, playlistId,
                                                source = ListenSource(ListenSourceKind.PLAYLIST, playlistId),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                ExpressiveScrollBar(
                    listState = listState,
                    contentEndMargin = PlaylistSongListInset,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 16.dp, bottom = endPadding),
                )
            }
        }
    }

    if (showAddSongsSheet && currentPlaylist != null && isEditablePlaylist) {
        SongPickerBottomSheet(
            // The shared playback instance from the nav graph, never a second one scoped to this
            // destination.
            playerViewModel = playerViewModel,
            initiallySelectedSongIds = currentPlaylist.songIds.toSet(),
            onDismiss = { showAddSongsSheet = false },
            onConfirm = { selectedIds ->
                playlistViewModel.addSongsToPlaylist(currentPlaylist.id, selectedIds.toList())
                showAddSongsSheet = false
            }
        )
    }

    if (showEditPlaylistDialog && currentPlaylist != null) {
        val initialShapeType = try {
            currentPlaylist.coverShapeType?.let { PlaylistShapeType.valueOf(it) } ?: PlaylistShapeType.Circle
        } catch (e: Exception) {
            PlaylistShapeType.Circle
        }

        EditPlaylistDialog(
            visible = showEditPlaylistDialog,
            currentName = currentPlaylist.name,
            currentImageUri = currentPlaylist.coverImageUri,
            currentColor = currentPlaylist.coverColorArgb,
            currentIconName = currentPlaylist.coverIconName,
            currentShapeType = initialShapeType,
            currentShapeDetail1 = currentPlaylist.coverShapeDetail1,
            currentShapeDetail2 = currentPlaylist.coverShapeDetail2,
            currentShapeDetail3 = currentPlaylist.coverShapeDetail3,
            currentShapeDetail4 = currentPlaylist.coverShapeDetail4,
            onDismiss = { showEditPlaylistDialog = false },
            onSave = { name, imageUri, color, icon, scale, panX, panY, shapeType, d1, d2, d3, d4 ->
                playlistViewModel.updatePlaylistParameters(
                    playlistId = currentPlaylist.id,
                    name = name,
                    coverImageUri = imageUri,
                    coverColor = color,
                    coverIcon = icon,
                    cropScale = scale,
                    cropPanX = panX,
                    cropPanY = panY,
                    coverShapeType = shapeType,
                    coverShapeDetail1 = d1,
                    coverShapeDetail2 = d2,
                    coverShapeDetail3 = d3,
                    coverShapeDetail4 = d4
                )
                showEditPlaylistDialog = false
            }
        )
    }
    if (showDeleteConfirmation && currentPlaylist != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.presentation_batch_b_delete_playlist_confirm_title)) },
            text = {
                // An alert is a window of its own, so the activity's volume-key path does not reach
                // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                ModalInputBridge()
                Text(stringResource(R.string.presentation_batch_b_delete_playlist_confirm_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        playlistViewModel.deletePlaylist(currentPlaylist.id)
                        onDeletePlayListClick()
                        showDeleteConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.delete_action), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }

    if (showSongInfoBottomSheet && selectedSongForInfo != null) {
        val currentSong = selectedSongForInfo
        val isFavorite = remember(currentSong?.id, favoriteIds) {
            derivedStateOf {
                currentSong?.let {
                    favoriteIds.contains(
                        it.id
                    )
                }
            }
        }.value ?: false

        if (currentSong != null) {
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
                    playerViewModel.sendToast(toastAddedToQueue)
                },
                onAddNextToQueue = {
                    playerViewModel.addSongNextToQueue(currentSong)
                    showSongInfoBottomSheet = false
                    playerViewModel.sendToast(toastPlayingNext)
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
                onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, newDate, coverArtUpdate ->
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
                        coverArtUpdate,
                        newDate = newDate
                    )
                },
                removeFromListTrigger = {
                    playlistViewModel.removeSongFromPlaylist(playlistId, currentSong.id)
                }
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
                    onDismiss = {
                        showPlaylistBottomSheet = false
                    },
                    currentPlaylistId = playlistId,
                    bottomBarHeight = bottomBarHeightDp,
                    playerViewModel = playerViewModel,
                )
            }
        }
    }

    if (showSortSheet && currentPlaylist != null) {
        fun selectSort(option: SortOption) {
            scope.launch {
                pendingEdit?.join()
                playlistViewModel.sortPlaylistSongs(option)
                if (listState.layoutInfo.totalItemsCount > 0) listState.scrollToItem(0)
            }
        }
        LibrarySortBottomSheet(
            title = stringResource(R.string.presentation_batch_b_sort_songs),
            options = PlaylistSongSortOptions,
            selectedOption = sortOption,
            onDismiss = { showSortSheet = false },
            onOptionSelected = { option ->
                selectSort(option)
                showSortSheet = false
            },
            onDirectionToggle = ::selectSort,
        )
    }
}

private val PlaylistSongSortOptions = persistentListOf(
    SortOption.SongDefaultOrder,
    SortOption.SongTitleAZ, SortOption.SongTitleZA,
    SortOption.SongArtist, SortOption.SongArtistDesc,
    SortOption.SongAlbum, SortOption.SongAlbumDesc,
    SortOption.SongDateAdded, SortOption.SongDateAddedAsc,
    SortOption.SongDuration, SortOption.SongDurationAsc,
)

@Composable
private fun playlistOrderLabel(option: SortOption, editable: Boolean): String {
    if (option == SortOption.SongDefaultOrder) {
        return stringResource(if (editable) R.string.playlist_custom_order else R.string.presentation_batch_g_sort_original_order)
    }
    val method = stringResource(option.methodLabelRes())
    return when (option.methodKey) {
        SortOption.SongTitleAZ.methodKey, SortOption.SongArtist.methodKey, SortOption.SongAlbum.methodKey -> {
            val format = if (option.direction == SortDirection.Ascending) R.string.playlist_order_az else R.string.playlist_order_za
            stringResource(format, method)
        }
        else -> {
            val direction = if (option.direction == SortDirection.Ascending) {
                R.string.presentation_batch_g_sort_ascending
            } else {
                R.string.presentation_batch_g_sort_descending
            }
            stringResource(R.string.presentation_batch_f_status_bullet_step, method, stringResource(direction))
        }
    }
}

@Composable
internal fun PlaylistDetailMenu(
    orderLabel: String,
    isFolderPlaylist: Boolean,
    isEditablePlaylist: Boolean,
    onAdd: () -> Unit,
    onSort: () -> Unit,
    onEditSongs: () -> Unit,
    onEditPlaylist: () -> Unit,
    onTransition: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DapOverflowButton(onClick = { expanded = true })
        DapMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun choose(action: () -> Unit) {
                expanded = false
                action()
            }
            if (isEditablePlaylist) {
                DapMenuItem(stringResource(R.string.presentation_batch_b_add_songs), { choose(onAdd) }, leadingIcon = { MenuIcon(Icons.Rounded.Add) })
            }
            DapMenuItem(stringResource(R.string.playlist_sort), { choose(onSort) }, subtitle = orderLabel, leadingIcon = { MenuIcon(Icons.AutoMirrored.Rounded.Sort) })
            if (isEditablePlaylist) {
                DapMenuItem(stringResource(R.string.playlist_edit_songs), { choose(onEditSongs) }, leadingIcon = { MenuIcon(Icons.Rounded.Edit) })
            }
            if (!isFolderPlaylist) {
                DapDivider(Modifier.padding(horizontal = DapSpacing.Md))
                DapMenuItem(stringResource(R.string.presentation_batch_b_edit_playlist), { choose(onEditPlaylist) }, leadingIcon = { MenuIcon(Icons.Rounded.DriveFileRenameOutline) })
                DapMenuItem(stringResource(R.string.presentation_batch_b_set_default_transition), { choose(onTransition) }, leadingIcon = { MenuIcon(Icons.Rounded.Merge) })
                DapMenuItem(stringResource(R.string.presentation_batch_b_export_playlist), { choose(onExport) }, leadingIcon = { MenuIcon(Icons.Rounded.IosShare) })
                DapMenuItem(stringResource(R.string.presentation_batch_b_delete_playlist), { choose(onDelete) }, textColor = Dap.error, leadingIcon = { MenuIcon(Icons.Outlined.Delete, tint = Dap.error) })
            }
        }
    }
}

internal val PlaylistSongListInset = DapSpacing.Gutter - EnhancedSongRowHorizontalPadding

@Composable
private fun MenuIcon(icon: ImageVector, tint: Color = LocalContentColor.current) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
}

/**
 * The edit-mode remove control keeps its 48 dp touch target but takes 36 dp of the row, shifted left by
 * the target's 12 dp inset, so the glyph starts on the page gutter like the art in the normal rows.
 */
private fun Modifier.onGutter(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val inset = 12.dp.roundToPx()
    layout(placeable.width - inset, placeable.height) { placeable.place(-inset, 0) }
}

@Composable
internal fun PlaylistPage(
    title: String,
    caption: String,
    onBack: () -> Unit,
    topBarLabel: String? = null,
    topBarActions: (@Composable () -> Unit)? = null,
    belowHeader: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    DapBackScaffold(
        modifier = Modifier.fillMaxSize(),
        onBack = onBack,
        topBar = { back ->
            DapTopBar(
                onBack = back.takeIf { topBarLabel == null },
                leading = topBarLabel?.let { label ->
                    {
                        Text(
                            text = label,
                            modifier = Modifier.padding(start = 12.dp),
                            style = DapType.Row,
                            color = Dap.primaryText,
                        )
                    }
                },
                actions = topBarActions,
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DapScreenTitle(text = title)
                Text(text = caption, style = DapType.Caption, color = Dap.secondaryText)
            }
            belowHeader?.invoke()
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}
