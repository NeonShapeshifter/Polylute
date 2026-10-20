package com.lostf1sh.pixelplayeross.presentation.components

import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.FolderSource
import com.lostf1sh.pixelplayeross.presentation.model.displayNameRes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.isSmartPlaylist
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.LibraryActionRow
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaylistBottomSheet(
    playlistUiState: PlaylistUiState,
    songs: ImmutableList<Song>,
    onDismiss: () -> Unit,
    bottomBarHeight: Dp,
    playerViewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    currentPlaylistId: String? = null
) {
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val sheetState = rememberModalSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { true }
    )

    var searchQuery by remember { mutableStateOf("") }
    val editablePlaylists = remember(playlistUiState.playlists) {
        playlistUiState.playlists.filterNot { it.isSmartPlaylist }.toImmutableList()
    }
    val filteredPlaylists = remember(searchQuery, editablePlaylists) {
        if (searchQuery.isBlank()) editablePlaylists
        else editablePlaylists.filter { it.name.contains(searchQuery, true) }.toImmutableList()
    }
    val selectedPlaylists = remember {
        mutableStateMapOf<String, Boolean>().apply {
            if (songs.size == 1) {
                val songId = songs.first().id
                filteredPlaylists.forEach {
                    put(it.id, it.songIds.contains(songId))
                }
            } else {
                filteredPlaylists.forEach {
                    put(it.id, false)
                }
            }
        }
    }

    val initiallySelectedIds = remember { selectedPlaylists.filterValues { it }.keys.toSet() }
    val isAnyPlaylistSelected = selectedPlaylists.values.any { it }
    val hasPendingRemoval = songs.size == 1 && initiallySelectedIds.any { selectedPlaylists[it] == false }
    val canSave = isAnyPlaylistSelected || hasPendingRemoval

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = DapRadius.SheetTop, topEnd = DapRadius.SheetTop),
        contentWindowInsets = { BottomSheetDefaults.modalWindowInsets }
    ) {
        // This window is its own surface, so the activity's volume-key path does not
        // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
        ModalInputBridge()
        Box(modifier = Modifier.fillMaxSize()) {
            val playlistCreatedToast = stringResource(R.string.playlist_created_songs_added_toast)
            val songsAddedToast = stringResource(R.string.playlist_songs_added_toast)
            val savedToast = stringResource(R.string.playlist_saved_toast)

            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DapSpacing.Gutter, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (songs.size > 1) {
                            stringResource(R.string.playlist_picker_add_songs_title, songs.size)
                        } else {
                            stringResource(R.string.playlist_picker_select_playlists)
                        },
                        style = DapType.Heading
                    )
                }
                OutlinedTextField(
                    value = searchQuery,
                    keyboardActions = dapDismissKeyboardActions(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        unfocusedTrailingIconColor = Color.Transparent,
                        focusedSupportingTextColor = Color.Transparent,
                    ),
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.search_playlists_hint), style = DapType.Caption) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp).dapTextField(),
                    textStyle = DapType.Body,
                    shape = RoundedCornerShape(DapRadius.Surface),
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) IconButton(onClick = {
                            searchQuery = ""
                        }) { Icon(Icons.Filled.Clear, stringResource(R.string.cd_clear_search)) }
                    }
                )




                LibraryActionRow(
                    modifier = Modifier.padding(
                        top = 10.dp,
                        start = 10.dp,
                        end = 10.dp
                    ),
                    onMainActionClick = {
                        showCreatePlaylistDialog = true
                    },
                    iconRotation = 0f,
                    showSortButton = false,
                    showImportButton = false,
                    onSortClick = { },
                    isPlaylistTab = true,
                    isFoldersTab = false,
                    currentFolder = null,
                    folderRootPath = "",
                    folderRootLabel = stringResource(FolderSource.INTERNAL.displayNameRes()),
                    onFolderClick = { },
                    onNavigateBack = { }
                )

                if (!canSave) {
                    Text(
                        stringResource(R.string.playlist_select_hint),
                        style = DapType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = DapSpacing.Gutter, vertical = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                PlaylistContainer(
                    playlistUiState = playlistUiState,
                    isRefreshing = false,
                    onRefresh = { },
                    bottomBarHeight = bottomBarHeight,
                    navController = null,
                    playerViewModel = playerViewModel,
                    isAddingToPlaylist = true,
                    currentSong = songs.firstOrNull() ?: Song.emptySong(),
                    filteredPlaylists = filteredPlaylists,
                    selectedPlaylists = selectedPlaylists
                )

                if (showCreatePlaylistDialog) {
                    CreatePlaylistDialogRedesigned(
                        onDismiss = { showCreatePlaylistDialog = false },
                        onCreate = { name ->
                            playlistViewModel.createPlaylist(name, songIds = songs.map { it.id })
                            showCreatePlaylistDialog = false
                            onDismiss()
                            playerViewModel.sendToast(playlistCreatedToast)
                        }
                    )
                }
            }

            Button(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 18.dp, end = DapSpacing.Gutter).heightIn(min = 48.dp),
                enabled = canSave,
                shape = RoundedCornerShape(DapRadius.Surface),
                colors = ButtonDefaults.buttonColors(),
                onClick = {
                    if (songs.size == 1) {
                         playlistViewModel.addOrRemoveSongFromPlaylists(
                            songs.first().id,
                            selectedPlaylists.filter { it.value }.keys.toList(),
                            currentPlaylistId
                        )
                    } else {
                         val selectedPlaylistIds = selectedPlaylists.filter { it.value }.keys.toList()
                         if (selectedPlaylistIds.isNotEmpty()) {
                             playlistViewModel.addSongsToPlaylists(
                                 songs.map { it.id },
                                 selectedPlaylistIds
                             )
                         }
                    }
                    onDismiss()
                    playerViewModel.sendToast(if (songs.size > 1) songsAddedToast else savedToast)
                    playerViewModel.multiSelectionStateHolder.clearSelection()
                },
            ) {
                Icon(Icons.Rounded.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_save), style = DapType.Button)
            }
        }
    }
}
