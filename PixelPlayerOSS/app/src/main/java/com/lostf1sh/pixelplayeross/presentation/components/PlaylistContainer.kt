package com.lostf1sh.pixelplayeross.presentation.components

import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionId
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionsState
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.presentation.collections.AutoCollectionRow
import com.lostf1sh.pixelplayeross.presentation.collections.AutoCollectionsViewModel
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapSectionHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapSettingRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonList
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.theme.RoundedSans
import com.lostf1sh.pixelplayeross.utils.formatSongCountLabel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Playlists list (Library > Lists), rebuilt (B28).
 *
 * Two groups in one list. On top, the **automatic collections**: lists the app builds from what you play, each with the one line that
 * says why those songs, how many there are, and a menu that can only hide it; they cannot be deleted or renamed. Below, **your
 * playlists**, as they were, with the same selection and sorting. A "hidden (N)" control in the first group's header brings hidden collections
 * back one by one. The last row leads to the rule collections (saved queries), which used to be reached from the old mixes page.
 *
 * Picking a playlist to add a song to (`isAddingToPlaylist`) lists only your playlists: a collection is not somewhere to put a song.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistContainer(
    playlistUiState: PlaylistUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    bottomBarHeight: Dp,
    playerShelfHeight: Dp = MiniPlayerHeight,
    currentSong: Song? = null,
    navController: NavController?,
    playerViewModel: PlayerViewModel,
    isAddingToPlaylist: Boolean = false,
    selectedPlaylists: SnapshotStateMap<String, Boolean>? = null,
    filteredPlaylists: ImmutableList<Playlist> = playlistUiState.playlists,
    currentSortOption: SortOption? = null,
    isSelectionMode: Boolean = false,
    selectedPlaylistIds: Set<String> = emptySet(),
    onPlaylistLongPress: (Playlist) -> Unit = {},
    onPlaylistSelectionToggle: (Playlist) -> Unit = {},
) {
    val listBottom = bottomBarHeight + playerShelfHeight + 30.dp
    if (isAddingToPlaylist) {
        AddToPlaylistList(
            playlistUiState = playlistUiState,
            filteredPlaylists = filteredPlaylists,
            selectedPlaylists = selectedPlaylists,
            observeSongs = playerViewModel::observeSongs,
            listBottom = listBottom,
        )
        return
    }

    val collectionsViewModel: AutoCollectionsViewModel = hiltViewModel()
    val collections by collectionsViewModel.state.collectAsStateWithLifecycle()
    PlaylistsList(
        collections = collections,
        playlists = filteredPlaylists,
        playlistsLoading = playlistUiState.isLoading,
        currentSortOption = currentSortOption,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        listBottom = listBottom,
        scrollBarBottom = bottomBarHeight + playerShelfHeight + 16.dp,
        isSelectionMode = isSelectionMode,
        selectedPlaylistIds = selectedPlaylistIds,
        observeSongs = playerViewModel::observeSongs,
        onOpenCollection = { id -> navController?.navigateSafely(Screen.AutoCollection.createRoute(id.key)) },
        onHideCollection = collectionsViewModel::hide,
        onShowCollection = collectionsViewModel::show,
        onOpenPlaylist = { playlist ->
            if (isSelectionMode) onPlaylistSelectionToggle(playlist)
            else navController?.navigateSafely(Screen.PlaylistDetail.createRoute(playlist.id))
        },
        onPlaylistLongPress = onPlaylistLongPress,
        onOpenRuleCollections = { navController?.navigateSafely(Screen.SmartCollections.route) },
    )
}

/** The Playlists list as a function of its state: what the route above feeds and the render tests draw. */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistsList(
    collections: AutoCollectionsState,
    playlists: ImmutableList<Playlist>,
    playlistsLoading: Boolean,
    currentSortOption: SortOption?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    listBottom: Dp,
    scrollBarBottom: Dp,
    isSelectionMode: Boolean,
    selectedPlaylistIds: Set<String>,
    observeSongs: (List<String>) -> Flow<List<Song>>,
    onOpenCollection: (AutoCollectionId) -> Unit,
    onHideCollection: (AutoCollectionId) -> Unit,
    onShowCollection: (AutoCollectionId) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlaylistLongPress: (Playlist) -> Unit,
    onOpenRuleCollections: () -> Unit,
) {
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // The row the first of your playlists sits at, so the fast-scroll label names playlists and not collections.
    val hiddenShown = if (showHidden) collections.hiddenCollections.size else 0
    val collectionRows = if (collections.isReady) collections.visible.size + hiddenShown else SkeletonRows
    val firstPlaylistIndex = 1 + collectionRows + 1
    val labelProvider = remember(playlists, currentSortOption, firstPlaylistIndex) {
        { index: Int ->
            playlistFastScrollLabel(
                playlist = playlists.getOrNull(index - firstPlaylistIndex),
                sortOption = currentSortOption,
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = DapSpacing.Gutter,
                    end = DapSpacing.Gutter,
                    bottom = listBottom,
                ),
            ) {
                item(key = "collections-header") {
                    DapSectionHeader(
                        text = stringResource(R.string.playlists_collections_header),
                        modifier = Modifier.padding(top = DapSpacing.Md),
                        trailing = if (collections.hidden.isNotEmpty() && collections.isReady) {
                            {
                                DapTextAction(
                                    label = stringResource(
                                        if (showHidden) R.string.playlists_hidden_close else R.string.playlists_hidden_toggle,
                                        collections.hidden.size,
                                    ),
                                    onClick = { showHidden = !showHidden },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
                if (!collections.isReady) {
                    item(key = "collections-loading") { DapSkeletonList(count = SkeletonRows) }
                } else {
                    items(collections.visible, key = { "collection-${it.id.key}" }) { collection ->
                        AutoCollectionRow(
                            collection = collection,
                            hidden = false,
                            onOpen = { onOpenCollection(collection.id) },
                            onHide = { onHideCollection(collection.id) },
                            onShow = { onShowCollection(collection.id) },
                        )
                    }
                    if (showHidden) {
                        items(collections.hiddenCollections, key = { "hidden-${it.id.key}" }) { collection ->
                            AutoCollectionRow(
                                collection = collection,
                                hidden = true,
                                onOpen = { onOpenCollection(collection.id) },
                                onHide = { onHideCollection(collection.id) },
                                onShow = { onShowCollection(collection.id) },
                            )
                        }
                    }
                }

                item(key = "playlists-header") {
                    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.SectionGap)) {
                        DapSectionHeader(
                            text = stringResource(R.string.playlists_your_header),
                            trailing = if (playlists.isNotEmpty()) {
                                { Text(playlists.size.toString(), style = DapType.ValueSmall, color = Dap.tertiaryText) }
                            } else {
                                null
                            },
                        )
                    }
                }
                when {
                    playlistsLoading && playlists.isEmpty() -> item(key = "playlists-loading") { DapSkeletonList(count = 2) }
                    playlists.isEmpty() -> item(key = "playlists-empty") {
                        Column(Modifier.fillMaxWidth().padding(vertical = DapSpacing.Md)) {
                            Text(
                                text = stringResource(R.string.presentation_batch_e_no_playlist_created),
                                style = DapType.RowSmall,
                                color = Dap.secondaryText,
                            )
                            Spacer(Modifier.height(DapSpacing.Xs))
                            Text(
                                text = stringResource(
                                    R.string.presentation_batch_e_new_playlist_hint,
                                    stringResource(R.string.library_action_new),
                                ),
                                style = DapType.Caption,
                                color = Dap.tertiaryText,
                            )
                        }
                    }
                    else -> items(playlists, key = { "playlist-${it.id}" }) { playlist ->
                        val selectionIndex = remember(playlist.id, selectedPlaylistIds) {
                            if (selectedPlaylistIds.contains(playlist.id)) selectedPlaylistIds.toList().indexOf(playlist.id) else -1
                        }
                        UserPlaylistRow(
                            playlist = playlist,
                            observeSongs = observeSongs,
                            onClick = { onOpenPlaylist(playlist) },
                            onLongClick = { onPlaylistLongPress(playlist) },
                            isSelected = selectedPlaylistIds.contains(playlist.id),
                            selectionIndex = selectionIndex,
                        )
                    }
                }

                item(key = "rule-collections") {
                    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.SectionGap)) {
                        DapDivider()
                        DapSettingRow(
                            label = stringResource(R.string.daily_mix_smart_collections),
                            description = stringResource(R.string.daily_mix_smart_collections_subtitle),
                            showChevron = true,
                            onClick = onOpenRuleCollections,
                        )
                    }
                }
            }
        }

        ExpressiveScrollBar(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp, top = 16.dp, bottom = scrollBarBottom),
            listState = listState,
            dragLabelProvider = labelProvider,
        )
    }
}

private const val SkeletonRows = 4

/** The list the "add to playlist" sheet shows: your playlists, each with a check. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun AddToPlaylistList(
    playlistUiState: PlaylistUiState,
    filteredPlaylists: ImmutableList<Playlist>,
    selectedPlaylists: SnapshotStateMap<String, Boolean>?,
    observeSongs: (List<String>) -> Flow<List<Song>>,
    listBottom: Dp,
) {
    if (playlistUiState.isLoading && filteredPlaylists.isEmpty()) {
        DapSkeletonList(count = 3, modifier = Modifier.padding(horizontal = DapSpacing.Gutter))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = DapSpacing.Gutter, end = DapSpacing.Gutter, bottom = listBottom),
    ) {
        items(filteredPlaylists, key = { it.id }) { playlist ->
            UserPlaylistRow(
                playlist = playlist,
                observeSongs = observeSongs,
                onClick = { selectedPlaylists?.let { it[playlist.id] = !(it[playlist.id] ?: false) } },
                checked = selectedPlaylists?.get(playlist.id) ?: false,
                onCheckedChange = { selectedPlaylists?.set(playlist.id, it) },
            )
        }
    }
}

/**
 * One of your playlists: its cover, name and song count. In selection mode the selected ones carry their place in the
 * selection; in the add-to-playlist sheet each one carries a check.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserPlaylistRow(
    playlist: Playlist,
    observeSongs: (List<String>) -> Flow<List<Song>>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
    selectionIndex: Int = -1,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
) {
    val previewSongIds = remember(playlist.songIds) { playlist.songIds.take(4) }
    val initial = remember(previewSongIds) { if (previewSongIds.isEmpty()) emptyList<Song>() else null }
    val songs by remember(previewSongIds, observeSongs) {
        observeSongs(previewSongIds).map<List<Song>, List<Song>?> { it }
    }.collectAsStateWithLifecycle(initialValue = initial)
    val accent = LocalDapAccent.current

    Column(modifier.fillMaxWidth().then(if (isSelected) Modifier.background(Dap.surface) else Modifier)) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = DapSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistCover(
                playlist = playlist,
                playlistSongs = remember(songs) { (songs ?: emptyList()).toImmutableList() },
                size = DapSizes.ThumbFeature,
            )
            Spacer(Modifier.width(DapSpacing.Lg))
            Column(Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = DapType.RowSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Dap.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = formatSongCountLabel(playlist.songIds.size),
                    style = DapType.Note,
                    color = Dap.secondaryText,
                    maxLines = 1,
                )
            }
            if (isSelected && selectionIndex >= 0) {
                Spacer(Modifier.width(DapSpacing.Md))
                Box(
                    Modifier
                        .size(DapSizes.StateMark * 6)
                        .background(accent, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = (selectionIndex + 1).toString(), style = DapType.Value, color = Dap.onFill)
                }
            }
            if (checked != null) {
                Spacer(Modifier.width(DapSpacing.Sm))
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            }
        }
        DapDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePlaylistDialogRedesigned(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var playlistName by remember { mutableStateOf("") }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        // This window is its own surface, so the activity's volume-key path does not
        // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
        ModalInputBridge()
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.create_playlist_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RoundedSans,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = playlistName,
                    keyboardActions = dapDismissKeyboardActions(),
                    onValueChange = { playlistName = it },
                    label = { Text(stringResource(R.string.create_playlist_name_label)) },
                    placeholder = { Text(stringResource(R.string.create_playlist_name_placeholder)) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp).dapTextField(),
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Button(
                        onClick = { onCreate(playlistName) },
                        modifier = Modifier.weight(1f),
                        enabled = playlistName.isNotEmpty(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(stringResource(R.string.action_create), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
