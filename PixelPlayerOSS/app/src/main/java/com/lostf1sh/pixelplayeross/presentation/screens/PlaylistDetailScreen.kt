package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsHost
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafelyReplacing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil.size.Size
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.QueuePlaylistSongItem
import com.lostf1sh.pixelplayeross.presentation.components.SongPickerBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.ExpressiveScrollBar
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.components.SongInfoBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.TightWrapText
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel.Companion.FOLDER_PLAYLIST_PREFIX
import com.lostf1sh.pixelplayeross.presentation.utils.LocalAppHapticsConfig
import com.lostf1sh.pixelplayeross.presentation.utils.performAppCompatHapticFeedback
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistSongsOrderMode
import com.lostf1sh.pixelplayeross.utils.formatSongCountLabel
import com.lostf1sh.pixelplayeross.utils.formatTotalDuration
import com.lostf1sh.pixelplayeross.ui.ds.DapCollectionActions
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton
import com.lostf1sh.pixelplayeross.ui.ds.DapScreen
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapTopBar
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.lostf1sh.pixelplayeross.presentation.components.LibrarySortBottomSheet
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.model.PlaylistShapeType
import com.lostf1sh.pixelplayeross.data.model.isSmartPlaylist
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.lostf1sh.pixelplayeross.presentation.components.rememberModalSheetState
import kotlinx.collections.immutable.persistentListOf
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(
    ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
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
    val hasCurrentSong by remember {
        derivedStateOf { playerStableState.currentSong != null }
    }
    val context = LocalContext.current
    val fallbackPlaylistName = stringResource(R.string.shortcut_playlist_short)
    val sortSongsLabel = stringResource(R.string.presentation_batch_b_sort_songs)
    val moreOptionsLabel = stringResource(R.string.presentation_batch_b_more_options)
    val playItLabel = stringResource(R.string.presentation_batch_b_play_it)
    val shuffleLabel = stringResource(R.string.shortcut_shuffle_short)
    val addSongsCd = stringResource(R.string.presentation_batch_b_add_songs)
    val addLabel = stringResource(R.string.presentation_batch_b_add)
    val removeLabel = stringResource(R.string.cd_remove)
    val removeSongsCd = stringResource(R.string.presentation_batch_b_remove_songs)
    val reorderLabel = stringResource(R.string.presentation_batch_b_reorder)
    val reorderSongsCd = stringResource(R.string.presentation_batch_b_reorder_songs)
    val reorderSongCd = stringResource(R.string.presentation_batch_b_reorder_song)
    val playlistEmptyTitle = stringResource(R.string.presentation_batch_b_playlist_empty_title)
    val playlistEmptyFolder = stringResource(R.string.presentation_batch_b_playlist_empty_folder_body)
    val playlistEmptyAddHint = stringResource(R.string.presentation_batch_b_playlist_empty_add_hint)
    val playlistOptionsTitle = stringResource(R.string.presentation_batch_b_playlist_options_title)
    val editPlaylistLabel = stringResource(R.string.presentation_batch_b_edit_playlist)
    val deletePlaylistLabel = stringResource(R.string.presentation_batch_b_delete_playlist)
    val setDefaultTransitionLabel = stringResource(R.string.presentation_batch_b_set_default_transition)
    val exportPlaylistLabel = stringResource(R.string.presentation_batch_b_export_playlist)
    val deletePlaylistConfirmTitle = stringResource(R.string.presentation_batch_b_delete_playlist_confirm_title)
    val deletePlaylistConfirmBody = stringResource(R.string.presentation_batch_b_delete_playlist_confirm_body)
    val sortSheetTitle = stringResource(R.string.presentation_batch_b_sort_songs)
    val toastAddedToQueue = stringResource(R.string.toast_added_to_queue)
    val toastPlayingNext = stringResource(R.string.toast_playing_next)
    val currentPlaylist = uiState.currentPlaylistDetails
    val isFolderPlaylist = currentPlaylist?.id?.startsWith(FOLDER_PLAYLIST_PREFIX) == true
    val isSmartPlaylist = currentPlaylist?.isSmartPlaylist == true
    val isEditablePlaylist = !isFolderPlaylist && !isSmartPlaylist
    val songsInPlaylist = uiState.currentPlaylistSongs

    LaunchedEffect(playlistId) {
        playlistViewModel.loadPlaylistDetails(playlistId)
    }

    var showAddSongsSheet by remember { mutableStateOf(false) }

    var isReorderModeEnabled by remember { mutableStateOf(false) }
    var isRemoveModeEnabled by remember { mutableStateOf(false) }
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    // Cleared whenever the action sheet closes, by keying on its visibility: every
    // dismissal path in this file sets that flag, so this cannot go stale.
    var showTrackDetails by remember(showSongInfoBottomSheet) { mutableStateOf(false) }
    var showPlaylistOptionsSheet by remember { mutableStateOf(false) }
    var showEditPlaylistDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val m3uExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        uri?.let {
            currentPlaylist?.let { playlist ->
                playlistViewModel.exportM3u(playlist, it, context)
            }
        }
    }

    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val stableOnMoreOptionsClick: (Song) -> Unit = remember {
        { song ->
            playerViewModel.selectSongForInfo(song)
            showSongInfoBottomSheet = true
        }
    }
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    var localReorderableSongs by remember(songsInPlaylist) { mutableStateOf(songsInPlaylist) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val appHapticsConfig = LocalAppHapticsConfig.current
    var lastMovedFrom by remember { mutableStateOf<Int?>(null) }
    var lastMovedTo by remember { mutableStateOf<Int?>(null) }

    val reorderableState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            localReorderableSongs = localReorderableSongs.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            if (lastMovedFrom == null) {
                lastMovedFrom = from.index
            }
            lastMovedTo = to.index
        }
    )

    LaunchedEffect(reorderableState.isAnyItemDragging, isEditablePlaylist) {
        if (isEditablePlaylist && !reorderableState.isAnyItemDragging && lastMovedFrom != null && lastMovedTo != null) {
            currentPlaylist?.let {
                playlistViewModel.reorderSongsInPlaylist(it.id, lastMovedFrom!!, lastMovedTo!!)
            }
            lastMovedFrom = null
            lastMovedTo = null
        } else if (!isEditablePlaylist && !reorderableState.isAnyItemDragging) {
            lastMovedFrom = null
            lastMovedTo = null
        }
    }

    DapBackScaffold(Modifier.fillMaxSize(), onBack = onBackClick) {
        Column(Modifier.fillMaxSize()) {
            // Back is the overlay, not the bar's own glyph: the bar draws a chevron in every Back
            // mode, including Gestures, where the mode says a Back-only control must not be drawn.
            // The bar keeps both real actions — the sort action and the overflow — exactly where
            // they are.
            DapTopBar(
                onBack = null,
                actions = {
                    DapTextAction(
                        label = sortSongsLabel,
                        onClick = {
                            playerViewModel.showSortingSheet()
                        },
                    )
                    if (!isFolderPlaylist) {
                        DapOverflowButton(
                            onClick = { showPlaylistOptionsSheet = true },
                            description = moreOptionsLabel,
                        )
                    }
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DapSpacing.Gutter),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DapScreenTitle(text = currentPlaylist?.name ?: fallbackPlaylistName)
                Text(
                    text = stringResource(
                        R.string.presentation_batch_f_status_bullet_step,
                        formatSongCountLabel(songsInPlaylist.size),
                        formatTotalDuration(songsInPlaylist)
                    ),
                    style = DapType.Caption,
                    color = Dap.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (uiState.isLoading && currentPlaylist == null) {
                Box(
                    Modifier
                        .fillMaxSize(), Alignment.Center
                ) { ContainedLoadingIndicator() }
            } else if (uiState.playlistNotFound) {
                Box(
                    Modifier
                        .fillMaxSize(), Alignment.Center
                ) { Text(stringResource(id = R.string.playlist_not_found)) }
            } else if (currentPlaylist == null) {
                Box(
                    Modifier
                        .fillMaxSize(), Alignment.Center
                ) { ContainedLoadingIndicator() }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    // The row height the edit/reorder buttons below align to.
                    val actionButtonsHeight = 48.dp
                    val playbackControlBottomPadding = if (isFolderPlaylist) 8.dp else 6.dp
                    // The same play / shuffle geometry as an album: play is the filled circle on
                    // the left, shuffle the icon-only circle beside it. This row used to be two
                    // equally weighted buttons whose content was 76 dp inside a 62 dp row.
                    DapCollectionActions(
                        playLabel = playItLabel,
                        shuffleLabel = shuffleLabel,
                        onPlay = {
                            if (localReorderableSongs.isNotEmpty()) {
                                playerViewModel.playSongs(
                                    localReorderableSongs,
                                    localReorderableSongs.first(),
                                    currentPlaylist.name,
                                    source = ListenSource(ListenSourceKind.PLAYLIST, currentPlaylist.id)
                                )
                                if (playerStableState.isShuffleEnabled) playerViewModel.turnShuffleOff()
                            }
                        },
                        onShuffle = {
                            if (localReorderableSongs.isNotEmpty()) {
                                playerViewModel.playSongsShuffled(
                                    songsToPlay = localReorderableSongs,
                                    queueName = currentPlaylist.name,
                                    playlistId = currentPlaylist.id,
                                    startAtZero = true,
                                    source = ListenSource(ListenSourceKind.PLAYLIST, currentPlaylist.id),
                                )
                            }
                        },
                        modifier = Modifier.padding(
                            horizontal = 20.dp,
                            vertical = playbackControlBottomPadding,
                        ),
                    )
                    if (isEditablePlaylist) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp, top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.CenterVertically
                        ) {
                            val reorderCornerRadius by animateDpAsState(
                                targetValue = if (isReorderModeEnabled) 24.dp else 12.dp,
                                label = "reorderCornerRadius"
                            )
                            val reorderButtonColor by animateColorAsState(
                                targetValue = if (isReorderModeEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                label = "reorderButtonColor"
                            )
                            val reorderIconColor by animateColorAsState(
                                targetValue = if (isReorderModeEnabled) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                                label = "reorderIconColor"
                            )

                            val removeCornerRadius by animateDpAsState(
                                targetValue = if (isRemoveModeEnabled) 24.dp else 12.dp,
                                label = "removeCornerRadius"
                            )
                            val removeButtonColor by animateColorAsState(
                                targetValue = if (isRemoveModeEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                label = "removeButtonColor"
                            )
                            val removeIconColor by animateColorAsState(
                                targetValue = if (isRemoveModeEnabled) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                                label = "removeIconColor"
                            )

                            Button(
                                onClick = { showAddSongsSheet = true },
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                ),
                                modifier = Modifier
                                    .heightIn(min = actionButtonsHeight)
                                    .animateContentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = addSongsCd,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    modifier = Modifier.padding(end = 4.dp),
                                    text = addLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Button(
                                onClick = { isRemoveModeEnabled = !isRemoveModeEnabled },
                                shape = RoundedCornerShape(removeCornerRadius),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = removeButtonColor,
                                    contentColor = removeIconColor
                                ),
                                modifier = Modifier
                                    .heightIn(min = actionButtonsHeight)
                                    .animateContentSize()
                                    .clip(RoundedCornerShape(removeCornerRadius))
                            ) {
                                Icon(
                                    modifier = Modifier.size(18.dp),
                                    imageVector = Icons.Default.RemoveCircleOutline,
                                    contentDescription = removeSongsCd,
                                    tint = removeIconColor
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    modifier = Modifier.padding(end = 4.dp),
                                    text = removeLabel,
                                    color = removeIconColor,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Button(
                                onClick = { isReorderModeEnabled = !isReorderModeEnabled },
                                shape = RoundedCornerShape(reorderCornerRadius),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = reorderButtonColor,
                                    contentColor = reorderIconColor
                                ),
                                modifier = Modifier
                                    .heightIn(min = actionButtonsHeight)
                                    .animateContentSize()
                                    .clip(RoundedCornerShape(reorderCornerRadius))
                            ) {
                                Icon(
                                    modifier = Modifier.size(22.dp),
                                    painter = painterResource(R.drawable.drag_order_icon),
                                    contentDescription = reorderSongsCd,
                                    tint = reorderIconColor
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    modifier = Modifier.padding(end = 4.dp),
                                    text = reorderLabel,
                                    color = reorderIconColor,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                        }
                    }

                    if (localReorderableSongs.isEmpty()) {
                        Box(Modifier
                            .fillMaxSize()
                            .weight(1f), Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.MusicOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text(playlistEmptyTitle, style = MaterialTheme.typography.titleMedium)
                                val emptyMessage = when {
                                    isFolderPlaylist -> playlistEmptyFolder
                                    isSmartPlaylist -> stringResource(R.string.presentation_batch_b_playlist_empty_smart_body)
                                    else -> playlistEmptyAddHint
                                }
                                Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        ) {
                            val playbackStates = rememberLibraryPlaybackStates(playerViewModel)
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(
                                        AbsoluteSmoothCornerShape(
                                            cornerRadiusTR = 32.dp,
                                            smoothnessAsPercentTR = 60,
                                            cornerRadiusTL = 32.dp,
                                            smoothnessAsPercentTL = 60,
                                        )
                                    )
                                    .background(color = MaterialTheme.colorScheme.surfaceContainerHigh),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(
                                    top = 12.dp,
                                    bottom = MiniPlayerHeight + 16.dp,
                                    end = if (listState.canScrollForward || listState.canScrollBackward) 24.dp else 0.dp
                                ).let {
                                    PaddingValues(
                                        top = it.calculateTopPadding(),
                                        bottom = it.calculateBottomPadding(),
                                        start = it.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                                        end = if (listState.canScrollForward || listState.canScrollBackward) 24.dp else 0.dp
                                    )
                                }
                            ) {
                                itemsIndexed(
                                    localReorderableSongs,
                                    key = { _, item -> item.id },
                                    contentType = { _, _ -> "playlist_song" }) { _, song ->
                                    // One collection of the player state for the list, a derived answer per row (B13).
                                    val playbackUiState by remember(song.id, playbackStates) {
                                        derivedStateOf { playbackStates.uiStateFor(song.id) }
                                    }
                                    ReorderableItem(
                                        state = reorderableState,
                                        key = song.id,
                                    ) { isDragging ->
                                        val scale by animateFloatAsState(
                                            if (isDragging) 1.05f else 1f,
                                            label = "scale"
                                        )

                                        QueuePlaylistSongItem(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 0.dp)
                                                .graphicsLayer {
                                                    scaleX = scale
                                                    scaleY = scale
                                                },
                                            onClick = {
                                                playerViewModel.playSongs(
                                                    localReorderableSongs,
                                                    song,
                                                    currentPlaylist.name,
                                                    currentPlaylist.id,
                                                    source = ListenSource(ListenSourceKind.PLAYLIST, currentPlaylist.id)
                                                )
                                            },
                                            song = song,
                                            isCurrentSong = playbackUiState.isCurrentSong,
                                            isPlaying = playbackUiState.isPlaying,
                                            isDragging = isDragging,
                                            onRemoveClick = {
                                                if (isEditablePlaylist) {
                                                    currentPlaylist.let {
                                                        playlistViewModel.removeSongFromPlaylist(it.id, song.id)
                                                    }
                                                }
                                            },
                                            isFromPlaylist = true,
                                            isReorderModeEnabled = isReorderModeEnabled,
                                            isDragHandleVisible = isReorderModeEnabled && isEditablePlaylist,
                                            isRemoveButtonVisible = isRemoveModeEnabled && isEditablePlaylist,
                                            onMoreOptionsClick = stableOnMoreOptionsClick,
                                            dragHandle = {
                                                IconButton(
                                                    onClick = {},
                                                    modifier = Modifier
                                                        .draggableHandle(
                                                            onDragStarted = {
                                                                performAppCompatHapticFeedback(
                                                                    view,
                                                                    appHapticsConfig,
                                                                    HapticFeedbackConstantsCompat.GESTURE_START
                                                                )
                                                            },
                                                            onDragStopped = {
                                                                performAppCompatHapticFeedback(
                                                                    view,
                                                                    appHapticsConfig,
                                                                    HapticFeedbackConstantsCompat.GESTURE_END
                                                                )
                                                            }
                                                        )
                                                        .size(40.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.DragIndicator,
                                                        contentDescription = reorderSongCd,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            ExpressiveScrollBar(
                                listState = listState,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(
                                        bottom = if (hasCurrentSong) MiniPlayerHeight + 20.dp else 16.dp,
                                        end = 14.dp,
                                        top = 18.dp
                                    )
                            )
                        }
                    }
                }
            }
        }
        // The bar above holds the actions; the Back affordance lives here, in the screen's
        // BoxScope, so it floats over the page and obeys the Back mode.
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
    if (showPlaylistOptionsSheet && !isFolderPlaylist) {
        val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { showPlaylistOptionsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 4.dp,
        ) {
            // This window is its own surface, so the activity's volume-key path does not
            // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
            ModalInputBridge()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = playlistOptionsTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    currentPlaylist?.name?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                PlaylistActionItem(
                    icon = painterResource(R.drawable.rounded_edit_24),
                    label = editPlaylistLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        showEditPlaylistDialog = true
                    }
                )
                PlaylistActionItem(
                    icon = painterResource(R.drawable.rounded_delete_24),
                    label = deletePlaylistLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        showDeleteConfirmation = true
                    }
                )
                PlaylistActionItem(
                    icon = painterResource(R.drawable.outline_graph_1_24),
                    label = setDefaultTransitionLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        navController.navigateSafely(Screen.EditTransition.createRoute(playlistId))
                    }
                )
                PlaylistActionItem(
                    icon = painterResource(R.drawable.rounded_attach_file_24),
                    label = exportPlaylistLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        val sanitizedName = PlaylistViewModel.sanitizeFileName(
                            currentPlaylist?.name ?: fallbackPlaylistName
                        )
                        m3uExportLauncher.launch("$sanitizedName.m3u")
                    }
                )
            }
        }
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
            title = { Text(deletePlaylistConfirmTitle) },
            text = {
                // An alert is a window of its own, so the activity's volume-key path does not reach
                // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                ModalInputBridge()
                Text(deletePlaylistConfirmBody)
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

    val isSortSheetVisible by playerViewModel.isSortingSheetVisible.collectAsStateWithLifecycle()

    if (isSortSheetVisible) {
        val isManualMode = uiState.playlistSongsOrderMode is PlaylistSongsOrderMode.Manual
        val rawOption = uiState.currentPlaylistSongsSortOption
        val currentSortOption = if (isManualMode) {
            SortOption.SongDefaultOrder
        } else if (currentPlaylist != null) {
            rawOption
        } else {
            SortOption.SongTitleAZ
        }

        val songSortOptions = persistentListOf(
            SortOption.SongDefaultOrder,
            SortOption.SongTitleAZ,
            SortOption.SongTitleZA,
            SortOption.SongArtist,
            SortOption.SongArtistDesc,
            SortOption.SongAlbum,
            SortOption.SongAlbumDesc,
            SortOption.SongDateAdded,
            SortOption.SongDateAddedAsc,
            SortOption.SongDuration,
            SortOption.SongDurationAsc
        )

        LibrarySortBottomSheet(
            title = sortSheetTitle,
            options = songSortOptions,
            selectedOption = currentSortOption,
            onDismiss = { playerViewModel.hideSortingSheet() },
            onOptionSelected = { option ->
                 playlistViewModel.sortPlaylistSongs(option)
                 playerViewModel.hideSortingSheet()
                 scope.launch {
                     kotlinx.coroutines.delay(100)
                     listState.animateScrollToItem(0)
                 }
            },
            onDirectionToggle = { option ->
                playlistViewModel.sortPlaylistSongs(option)
                scope.launch {
                    kotlinx.coroutines.delay(100)
                    listState.animateScrollToItem(0)
                }
            },
            showViewToggle = false 
        )
    }
}


@Composable
private fun PlaylistActionItem(
    icon: Painter,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
