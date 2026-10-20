@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.utils.currentLocale
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FabPosition
import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsHost
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafelyReplacing

import android.os.Trace
import com.lostf1sh.pixelplayeross.utils.traceSection
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material3.Button
import androidx.compose.material.icons.rounded.ViewModule
import com.lostf1sh.pixelplayeross.presentation.components.ToggleSegmentButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.model.displayNameRes
import com.lostf1sh.pixelplayeross.presentation.model.methodLabelRes
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import com.lostf1sh.pixelplayeross.data.model.FolderSource
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortDirection
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.model.StorageFilter
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.components.resolveMainScreenBottomGradientHeight
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistArtCollage
import com.lostf1sh.pixelplayeross.presentation.components.ReorderTabsSheet
import com.lostf1sh.pixelplayeross.presentation.components.EditMultipleSongsSheet
import com.lostf1sh.pixelplayeross.presentation.components.SongInfoBottomSheet
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.components.MultiSelectionBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.AlbumMultiSelectionOptionSheet
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistMultiSelectionBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.DescribePlaylistDialog
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistCreationTypeDialog
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.SelectionActionRow
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemePair
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SongInfoBottomSheetViewModel
import com.lostf1sh.pixelplayeross.data.model.LibraryTabId
import com.lostf1sh.pixelplayeross.data.model.toLibraryTabIdOrNull
import com.lostf1sh.pixelplayeross.data.preferences.LibraryNavigationMode
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreCategoriesGrid
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreTypography
import com.lostf1sh.pixelplayeross.presentation.components.SyncProgressBar
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LibraryViewModel
import com.lostf1sh.pixelplayeross.utils.formatSongCountLabel
import androidx.paging.compose.collectAsLazyPagingItems
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lostf1sh.pixelplayeross.presentation.components.AutoScrollingTextOnDemand
import com.lostf1sh.pixelplayeross.presentation.screens.CreatePlaylistDialog
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistContainer
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.PlayingEqIcon
import com.lostf1sh.pixelplayeross.ui.theme.RoundedSans
import java.util.Locale
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.focus.focusModifier
import com.lostf1sh.pixelplayeross.data.model.PlaylistShapeType
import kotlinx.coroutines.flow.first
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.LoadState
import com.lostf1sh.pixelplayeross.presentation.components.ExpressiveScrollBar
import com.lostf1sh.pixelplayeross.presentation.components.LibrarySortBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import timber.log.Timber
import java.io.File
import kotlin.math.abs
import com.lostf1sh.pixelplayeross.presentation.components.rememberModalSheetState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import com.lostf1sh.pixelplayeross.ui.ds.DapAlbumGridItem
import com.lostf1sh.pixelplayeross.ui.ds.DapAlbumRow
import com.lostf1sh.pixelplayeross.ui.ds.DapArtistRow
import com.lostf1sh.pixelplayeross.ui.ds.DapArtworkPlaceholder
import com.lostf1sh.pixelplayeross.ui.ds.DapBackButton
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonList
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapState
import com.lostf1sh.pixelplayeross.ui.ds.DapStateKind
import com.lostf1sh.pixelplayeross.ui.ds.DapTabUnderline
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.presentation.components.artistNameLabel
import com.lostf1sh.pixelplayeross.presentation.components.albumTitleLabel
import com.lostf1sh.pixelplayeross.presentation.components.albumArtistLabel

// Room under the last row for the floating actions: 48 dp of button, 8 dp above and below it.
val ListExtraBottomGap = 64.dp
val PlayerSheetCollapsedCornerRadius = 32.dp
private const val LIBRARY_TAB_TRANSITION_DURATION_MS = 180
private val LibraryTabTransitionSpec = tween<Float>(
    durationMillis = LIBRARY_TAB_TRANSITION_DURATION_MS,
    easing = FastOutSlowInEasing
)
private const val MAX_ALBUM_MULTI_SELECTION = 6
private const val ENABLE_FOLDERS_SOURCE_TOGGLE = true
private const val ENABLE_FOLDERS_STORAGE_FILTER = false
private const val FOLDER_NAVIGATION_ROOT_KEY = "__folder_root__"
private const val FOLDER_NAVIGATION_FORWARD = 1
private const val FOLDER_NAVIGATION_BACKWARD = -1
private const val PULL_REFRESH_MIN_VISIBLE_MS = 900L
private const val PULL_REFRESH_MAX_VISIBLE_MS = 1_500L
private const val INLINE_SYNC_MIN_VISIBLE_MS = 600L

private data class LibraryScreenPlayerProjection(
    val currentFolder: MusicFolder? = null,
    val folderSourceRootPath: String = "",
    val folderSource: FolderSource = FolderSource.INTERNAL,
    val isFoldersPlaylistView: Boolean = false,
    val currentStorageFilter: StorageFilter = StorageFilter.ALL,
    val currentSongSortOption: SortOption = SortOption.SongTitleAZ,
    val currentAlbumSortOption: SortOption = SortOption.AlbumTitleAZ,
    val currentArtistSortOption: SortOption = SortOption.ArtistNameAZ,
    val currentFavoriteSortOption: SortOption = SortOption.LikedSongDateLiked,
    val currentFolderSortOption: SortOption = SortOption.FolderNameAZ,
    val isAlbumsListView: Boolean = false,
    val isSdCardAvailable: Boolean = false,
    val musicFolders: ImmutableList<MusicFolder> = persistentListOf(),
    val isLoadingLibraryCategories: Boolean = true,
    val isSyncingLibrary: Boolean = false,
    val isLoadingInitialSongs: Boolean = true
)

private fun PlayerUiState.toLibraryScreenProjection(): LibraryScreenPlayerProjection =
    LibraryScreenPlayerProjection(
        currentFolder = currentFolder,
        folderSourceRootPath = folderSourceRootPath,
        folderSource = folderSource,
        isFoldersPlaylistView = isFoldersPlaylistView,
        currentStorageFilter = currentStorageFilter,
        currentSongSortOption = currentSongSortOption,
        currentAlbumSortOption = currentAlbumSortOption,
        currentArtistSortOption = currentArtistSortOption,
        currentFavoriteSortOption = currentFavoriteSortOption,
        currentFolderSortOption = currentFolderSortOption,
        isAlbumsListView = isAlbumsListView,
        isSdCardAvailable = isSdCardAvailable,
        musicFolders = musicFolders,
        isLoadingLibraryCategories = isLoadingLibraryCategories,
        isSyncingLibrary = isSyncingLibrary,
        isLoadingInitialSongs = isLoadingInitialSongs
    )

@RequiresApi(Build.VERSION_CODES.R)
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel(),
    songInfoBottomSheetViewModel: SongInfoBottomSheetViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lastTabIndex by playerViewModel.lastLibraryTabIndexFlow.collectAsStateWithLifecycle()
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val syncManager = playerViewModel.syncManager
    var isRefreshing by remember { mutableStateOf(false) }
    val isFetchingChanges by syncManager.isFetchingChanges
        .collectAsStateWithLifecycle(initialValue = false)
    val isSyncing by syncManager.isSyncing
        .collectAsStateWithLifecycle(initialValue = false)

    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    // Cleared whenever the action sheet closes, by keying on its visibility: every
    // dismissal path in this file sets that flag, so this cannot go stale.
    var showTrackDetails by remember(showSongInfoBottomSheet) { mutableStateOf(false) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    var playlistSheetSongs by remember { mutableStateOf<ImmutableList<Song>>(persistentListOf()) }
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val tabTitles by playerViewModel.libraryTabsFlow.collectAsStateWithLifecycle()
    // The tab editor works on the complete set; the strip works on the visible subset.
    val allTabTitles by playerViewModel.libraryAllTabsFlow.collectAsStateWithLifecycle()
    val hiddenTabTitles by playerViewModel.libraryHiddenTabsFlow.collectAsStateWithLifecycle()
    val currentTabId by playerViewModel.currentLibraryTabId.collectAsStateWithLifecycle()
    val libraryNavigationMode by playerViewModel.libraryNavigationMode.collectAsStateWithLifecycle()
    val isCompactNavigation = libraryNavigationMode == LibraryNavigationMode.COMPACT_PILL
    val tabCount = tabTitles.size.coerceAtLeast(1)
    val normalizedLastTabIndex = positiveMod(lastTabIndex, tabCount)
    val compactInitialPage = remember(tabCount, normalizedLastTabIndex) {
        infinitePagerInitialPage(tabCount, normalizedLastTabIndex)
    }
    val pagerState = if (isCompactNavigation) {
        rememberPagerState(initialPage = compactInitialPage) { Int.MAX_VALUE }
    } else {
        rememberPagerState(initialPage = normalizedLastTabIndex) { tabCount }
    }
    val currentTabIndex by remember(pagerState, tabTitles, isCompactNavigation) {
        derivedStateOf {
            resolveTabIndex(
                page = pagerState.currentPage,
                tabCount = tabTitles.size,
                compactMode = isCompactNavigation
            )
        }
    }
    val settledTabIndex by remember(pagerState, tabTitles, isCompactNavigation) {
        derivedStateOf {
            resolveTabIndex(
                page = pagerState.settledPage,
                tabCount = tabTitles.size,
                compactMode = isCompactNavigation
            )
        }
    }
    var requestedTabIndex by remember(tabTitles, isCompactNavigation) { mutableStateOf<Int?>(null) }
    val visualTabIndex = requestedTabIndex ?: currentTabIndex
    val animateToLibraryTab: (Int) -> Unit = { targetTabIndex ->
        scope.launch {
            val safeTargetIndex = positiveMod(targetTabIndex, tabCount)
            val targetPage = targetPageForTabIndex(
                currentPage = pagerState.currentPage,
                targetTabIndex = safeTargetIndex,
                tabCount = tabTitles.size,
                compactMode = isCompactNavigation
            )
            requestedTabIndex = safeTargetIndex
            try {
                pagerState.animateScrollToPage(
                    targetPage,
                    animationSpec = LibraryTabTransitionSpec
                )
            } finally {
                if (requestedTabIndex == safeTargetIndex) {
                    requestedTabIndex = null
                }
            }
        }
    }
    // B32-Q2: a song somebody else asked this screen to show is shown on the Songs tab: the pager goes there (a jump, not the slide through the
    // tabs in between), and the Songs list answers the request. With the Songs tab hidden there is nowhere to show it, and the listener is told.
    val locateRequest by playerViewModel.libraryLocateRequest.collectAsStateWithLifecycle()
    val songsTabHiddenMessage = stringResource(R.string.library_locate_songs_tab_hidden)
    LaunchedEffect(locateRequest?.token) {
        if (locateRequest == null) return@LaunchedEffect
        val songsTabIndex = tabTitles.indexOfFirst { it.toLibraryTabIdOrNull() == LibraryTabId.SONGS }
        if (songsTabIndex < 0) {
            playerViewModel.clearLibraryLocate()
            playerViewModel.sendToast(songsTabHiddenMessage)
        } else if (currentTabIndex != songsTabIndex) {
            pagerState.scrollToPage(
                targetPageForTabIndex(
                    currentPage = pagerState.currentPage,
                    targetTabIndex = songsTabIndex,
                    tabCount = tabTitles.size,
                    compactMode = isCompactNavigation,
                )
            )
        }
    }
    val isSortSheetVisible by playerViewModel.isSortingSheetVisible.collectAsStateWithLifecycle()
    val canNavigateBackInFolders by remember(playerViewModel) {
        playerViewModel.playerUiState
            .map { uiState -> uiState.currentFolder != null && uiState.folderBackGestureNavigationEnabled }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showPlaylistCreationTypeDialog by remember { mutableStateOf(false) }
    var showDescribePlaylistDialog by remember { mutableStateOf(false) }

    val m3uImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { playlistViewModel.importM3u(it) }
    }

    var showReorderTabsSheet by remember { mutableStateOf(false) }
    var showTabSwitcherSheet by remember { mutableStateOf(false) }

    val multiSelectionState = playerViewModel.multiSelectionStateHolder
    val selectedSongs by multiSelectionState.selectedSongs.collectAsStateWithLifecycle()
    val isSelectionMode by multiSelectionState.isSelectionMode.collectAsStateWithLifecycle()
    val selectedSongIds by multiSelectionState.selectedSongIds.collectAsStateWithLifecycle()
    var showMultiSelectionSheet by remember { mutableStateOf(false) }
    var selectedAlbums by remember { mutableStateOf<ImmutableList<Album>>(persistentListOf()) }
    val selectedAlbumIds = remember(selectedAlbums) { selectedAlbums.map { it.id }.toSet() }
    val isAlbumSelectionMode = selectedAlbums.isNotEmpty()
    var showAlbumMultiSelectionSheet by remember { mutableStateOf(false) }
    var showBatchEditSheet by remember { mutableStateOf(false) }

    var songsShowLocateButton by remember { mutableStateOf(false) }
    var likedShowLocateButton by remember { mutableStateOf(false) }
    var foldersShowLocateButton by remember { mutableStateOf(false) }
    var songsLocateAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var likedLocateAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var foldersLocateAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingFoldersLocatePath by remember { mutableStateOf<String?>(null) }

    val onSongLongPress: (Song) -> Unit = remember(multiSelectionState, haptic) {
        { song -> 
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            multiSelectionState.toggleSelection(song) 
        }
    }

    val onSongSelectionToggle: (Song) -> Unit = remember(multiSelectionState) {
        { song -> multiSelectionState.toggleSelection(song) }
    }

    val toggleAlbumSelection: (Album) -> Unit = remember(selectedAlbums, playerViewModel, context) {
        { album ->
            val existingIndex = selectedAlbums.indexOfFirst { it.id == album.id }
            if (existingIndex >= 0) {
                selectedAlbums = selectedAlbums.toMutableList().also { it.removeAt(existingIndex) }.toImmutableList()
            } else if (selectedAlbums.size >= MAX_ALBUM_MULTI_SELECTION) {
                playerViewModel.sendToast(context.getString(R.string.presentation_batch_d_max_albums_selection, MAX_ALBUM_MULTI_SELECTION))
            } else {
                selectedAlbums = (selectedAlbums + album).toImmutableList()
            }
        }
    }

    val onAlbumLongPress: (Album) -> Unit = remember(toggleAlbumSelection, haptic) {
        { album -> 
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            toggleAlbumSelection(album) 
        }
    }

    val onAlbumSelectionToggle: (Album) -> Unit = remember(toggleAlbumSelection) {
        { album -> toggleAlbumSelection(album) }
    }

    val getAlbumSelectionIndex: (Long) -> Int? = remember(selectedAlbums) {
        { albumId ->
            val index = selectedAlbums.indexOfFirst { it.id == albumId }
            if (index >= 0) index + 1 else null
        }
    }

    val playlistMultiSelectionState = playerViewModel.playlistSelectionStateHolder
    val selectedPlaylists by playlistMultiSelectionState.selectedPlaylists.collectAsStateWithLifecycle()
    val selectedPlaylistIds by playlistMultiSelectionState.selectedPlaylistIds.collectAsStateWithLifecycle()
    val isPlaylistSelectionMode by playlistMultiSelectionState.isSelectionMode.collectAsStateWithLifecycle()
    var showPlaylistMultiSelectionSheet by remember { mutableStateOf(false) }
    var showMergePlaylistDialog by remember { mutableStateOf(false) }
    var pendingMergePlaylistIds by remember { mutableStateOf(emptyList<String>()) }

    val onPlaylistLongPress: (com.lostf1sh.pixelplayeross.data.model.Playlist) -> Unit = remember(playlistMultiSelectionState, haptic) {
        { playlist ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            playlistMultiSelectionState.toggleSelection(playlist)
            Timber.tag("PlaylistMultiSelect").d("Toggled: ${playlist.name}, total selected: ${playlistMultiSelectionState.selectedPlaylists.value.size}")
        }
    }

    val onPlaylistSelectionToggle: (com.lostf1sh.pixelplayeross.data.model.Playlist) -> Unit = remember(playlistMultiSelectionState) {
        { playlist -> playlistMultiSelectionState.toggleSelection(playlist) }
    }

    val stableOnMoreOptionsClick: (Song) -> Unit = remember {
        { song ->
            playerViewModel.selectSongForInfo(song)
            showSongInfoBottomSheet = true
        }
    }
    var isMinDelayActive by remember { mutableStateOf(false) }
    var refreshGeneration by remember { mutableStateOf(0) }

    val onRefresh: () -> Unit = remember(scope, syncManager) {
        {
            val currentRefreshGeneration = refreshGeneration + 1
            refreshGeneration = currentRefreshGeneration
            isMinDelayActive = true
            isRefreshing = true
            syncManager.incrementalSync()
            scope.launch {
                kotlinx.coroutines.delay(PULL_REFRESH_MIN_VISIBLE_MS)
                if (currentRefreshGeneration != refreshGeneration) return@launch
                isMinDelayActive = false
                val stillFetching = syncManager.isFetchingChanges.first()
                if (!stillFetching) {
                    isRefreshing = false
                    return@launch
                }

                val remainingVisibleMs =
                    (PULL_REFRESH_MAX_VISIBLE_MS - PULL_REFRESH_MIN_VISIBLE_MS)
                        .coerceAtLeast(0L)
                if (remainingVisibleMs > 0L) {
                    kotlinx.coroutines.delay(remainingVisibleMs)
                }
                if (currentRefreshGeneration != refreshGeneration) return@launch
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(isFetchingChanges) {
        if (!isFetchingChanges && !isMinDelayActive) {
            isRefreshing = false
        }
    }

    var inlineSyncVisible by remember { mutableStateOf(false) }
    var inlineSyncShownAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(isSyncing, isRefreshing) {
        if (isSyncing && !isRefreshing) {
            if (!inlineSyncVisible) {
                inlineSyncShownAt = System.currentTimeMillis()
                inlineSyncVisible = true
            }
        } else if (isRefreshing) {
            inlineSyncVisible = false
            inlineSyncShownAt = null
        } else if (inlineSyncVisible) {
            val shownAt = inlineSyncShownAt
            val elapsed = if (shownAt != null) {
                System.currentTimeMillis() - shownAt
            } else {
                INLINE_SYNC_MIN_VISIBLE_MS
            }
            val remaining = INLINE_SYNC_MIN_VISIBLE_MS - elapsed
            if (remaining > 0) {
                kotlinx.coroutines.delay(remaining)
            }
            inlineSyncVisible = false
            inlineSyncShownAt = null
        }
    }

    val hasSelectionInCurrentTab by remember {
        derivedStateOf {
            when (currentTabId) {
                LibraryTabId.PLAYLISTS -> isPlaylistSelectionMode
                LibraryTabId.ALBUMS -> isAlbumSelectionMode
                LibraryTabId.SONGS,
                LibraryTabId.LIKED,
                LibraryTabId.FOLDERS -> isSelectionMode
                // A generated list has no membership to edit: a track is in a mix because it
                // scored there, and in a collection because it matched. Multi-select therefore has
                // nothing to act on.
                LibraryTabId.ARTISTS,
                LibraryTabId.GENRES,
                LibraryTabId.COLLECTIONS -> false
            }
        }
    }
    val canHandleFolderBack by remember {
        derivedStateOf {
            currentTabId == LibraryTabId.FOLDERS &&
                    canNavigateBackInFolders &&
                    !isSortSheetVisible
        }
    }

    BackHandler(enabled = hasSelectionInCurrentTab || canHandleFolderBack) {
        when {
            hasSelectionInCurrentTab -> {
                when (currentTabId) {
                    LibraryTabId.PLAYLISTS -> {
                        playlistMultiSelectionState.clearSelection()
                        showPlaylistMultiSelectionSheet = false
                        showMergePlaylistDialog = false
                        pendingMergePlaylistIds = emptyList()
                    }

                    LibraryTabId.ALBUMS -> {
                        selectedAlbums = persistentListOf()
                        showAlbumMultiSelectionSheet = false
                    }

                    LibraryTabId.SONGS,
                    LibraryTabId.LIKED,
                    LibraryTabId.FOLDERS -> {
                        multiSelectionState.clearSelection()
                        showMultiSelectionSheet = false
                    }

                    LibraryTabId.ARTISTS,
                    LibraryTabId.GENRES,
                    LibraryTabId.COLLECTIONS -> Unit
                }
            }

            canHandleFolderBack -> {
                playerViewModel.navigateBackFolder()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(playlistViewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            playlistViewModel.playlistCreationEvent.collect { success ->
                if (success) {
                    showCreatePlaylistDialog = false
                    Toast.makeText(context, context.getString(R.string.toast_playlist_created), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(settledTabIndex) {
        traceSection("LibraryScreen.PageChangeTabLoad") {
            playerViewModel.onLibraryTabSelected(settledTabIndex)
        }

        multiSelectionState.clearSelection()
        playlistMultiSelectionState.clearSelection()
        selectedAlbums = persistentListOf()
        showMultiSelectionSheet = false
        showPlaylistMultiSelectionSheet = false
        showAlbumMultiSelectionSheet = false
    }

    val currentTab = tabTitles.getOrNull(currentTabIndex)?.toLibraryTabIdOrNull() ?: currentTabId
    val currentTabTitle = currentTab.displayTitle()

    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)

    val fabTab = currentTabId
    val fabShowLocate = when (fabTab) {
        LibraryTabId.SONGS -> songsShowLocateButton
        LibraryTabId.LIKED -> likedShowLocateButton
        LibraryTabId.FOLDERS -> foldersShowLocateButton
        else -> false
    }
    val fabLocate = when (fabTab) {
        LibraryTabId.SONGS -> songsLocateAction
        LibraryTabId.LIKED -> likedLocateAction
        LibraryTabId.FOLDERS -> foldersLocateAction
        else -> null
    }
    val showFloatingActions = fabTab != LibraryTabId.PLAYLISTS &&
        (fabTab != LibraryTabId.FOLDERS || fabShowLocate)
    // Scaffold places FABs 16 dp above its inset, then our modifier adds 8 dp. Reserve
    // their actual height (48 dp, or two buttons plus an 8 dp gap), plus 8 dp breathing room.
    val floatingActionsGap = if (!showFloatingActions) 16.dp
        else if (fabShowLocate && fabTab != LibraryTabId.FOLDERS) 136.dp else 80.dp

    Scaffold(
        modifier = Modifier.background(Dap.background),
        containerColor = Dap.background,
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            LibraryFloatingActions(
                // Clear the app's own bottom chrome (nav bar + mini-player), which is drawn over
                // this Scaffold rather than by it.
                modifier = Modifier.padding(
                    start = DapSpacing.Md,
                    end = DapSpacing.Md,
                    bottom = bottomBarHeightDp - systemNavBarInset + LocalDapPlayerShelfHeight.current + 8.dp
                ),
                // Play the whole list from the top only where the list *is* the thing to play.
                // Albums and artists are lists of containers: "play all" there is ambiguous.
                showPlay = fabTab == LibraryTabId.SONGS || fabTab == LibraryTabId.LIKED,
                onPlayClick = {
                    when (fabTab) {
                        LibraryTabId.LIKED -> playerViewModel.playFavoritesFromStart()
                        else -> playerViewModel.playLibraryFromStart()
                    }
                },
                showLocate = fabShowLocate,
                onLocateClick = { fabLocate?.invoke() },
                showShuffle = fabTab != LibraryTabId.PLAYLISTS && fabTab != LibraryTabId.FOLDERS,
                onShuffleClick = {
                    when (fabTab) {
                        LibraryTabId.LIKED -> playerViewModel.shuffleFavoriteSongs()
                        LibraryTabId.ALBUMS -> playerViewModel.shuffleRandomAlbum()
                        LibraryTabId.ARTISTS -> playerViewModel.shuffleRandomArtist()
                        else -> playerViewModel.shuffleAllSongs()
                    }
                }
            )
        },

        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Dap.background)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = DapSpacing.Gutter,
                            end = DapSpacing.Xs,
                            top = DapSpacing.Sm,
                            bottom = DapSpacing.Xs
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (isCompactNavigation) {
                        LibraryNavigationCompactTitle(
                            modifier = Modifier.weight(1f),
                            title = currentTabTitle,
                            pageIndex = pagerState.currentPage,
                            onClick = {
                                showTabSwitcherSheet = true
                            },
                            onSwipeLeft = {
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        page = pagerState.currentPage + 1,
                                        animationSpec = LibraryTabTransitionSpec
                                    )
                                }
                            },
                            onSwipeRight = {
                                scope.launch {
                                    if (pagerState.currentPage > 0) {
                                        pagerState.animateScrollToPage(
                                            page = pagerState.currentPage - 1,
                                            animationSpec = LibraryTabTransitionSpec
                                        )
                                    }
                                }
                            }
                        )
                    } else {
                        DapScreenTitle(
                            text = stringResource(R.string.presentation_batch_d_library_title),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Everything that is not the screen title or the category tabs lives in a
                    // single overflow, so the Library has exactly two levels of hierarchy above
                    // the content. Nothing here is removed; it is moved down one level.
                    var showLibraryMenu by remember { mutableStateOf(false) }
                    Box {
                        DapOverflowButton(
                            onClick = { showLibraryMenu = true },
                            description = stringResource(R.string.library_menu_options),
                        )
                        DropdownMenu(
                            expanded = showLibraryMenu,
                            onDismissRequest = { showLibraryMenu = false },
                            containerColor = Dap.surfaceRaised
                        ) {
                            LibraryMenuItem(
                                label = stringResource(R.string.audio_bookmarks_cd_open)
                            ) {
                                showLibraryMenu = false
                                navController.navigateSafely(Screen.AudioBookmarks.route)
                            }
                            LibraryMenuItem(
                                label = stringResource(R.string.presentation_batch_d_reorder_tabs_label)
                            ) {
                                showLibraryMenu = false
                                showReorderTabsSheet = true
                            }
                            LibraryMenuItem(
                                label = stringResource(R.string.presentation_batch_d_open_settings_cd)
                            ) {
                                showLibraryMenu = false
                                navController.navigateSafely(Screen.Settings.route)
                            }
                        }
                    }
                }

                if (!isCompactNavigation) {
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
                        tabTitles.forEachIndexed { index, rawId ->
                            val tabId = rawId.toLibraryTabIdOrNull() ?: LibraryTabId.SONGS
                            DapTabUnderline(
                                label = stringResource(tabId.titleRes).uppercase(currentLocale()),
                                selected = visualTabIndex == index,
                                onClick = { animateToLibraryTab(index) },
                                modifier = Modifier.padding(horizontal = DapSpacing.Sm)
                            )
                        }
                    }
                    DapDivider()
                } else {
                    CompactLibraryPagerIndicator(
                        currentIndex = currentTabIndex,
                        pageCount = tabTitles.size,
                        modifier = Modifier.padding(top = DapSpacing.Xs, bottom = DapSpacing.Xs)
                    )
                }
            }
        },
    ) { innerScaffoldPadding ->
        val playerUiState by remember(playerViewModel) {
            playerViewModel.playerUiState
                .map { uiState -> uiState.toLibraryScreenProjection() }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = LibraryScreenPlayerProjection())
        val isLibraryContentEmpty by remember(playerViewModel) {
            combine(
                playerViewModel.songCountFlow,
                playerViewModel.albumsFlow,
                playerViewModel.artistsFlow
            ) { songCount, albums, artists ->
                songCount == 0 && albums.isEmpty() && artists.isEmpty()
            }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = true)

        Box(
            modifier = Modifier
                // Each tab reserves the bottom chrome in its scroll content, once.
                .padding(top = innerScaffoldPadding.calculateTopPadding())
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .background(Dap.background)
                    .fillMaxSize()
            ) {
                Column(Modifier.fillMaxSize()) {
                        val availableSortOptions by playerViewModel.availableSortOptions.collectAsStateWithLifecycle()
                        val sanitizedSortOptions = remember(availableSortOptions, currentTabId) {
                            val cleaned = availableSortOptions.filterIsInstance<SortOption>()
                            val ensured = if (cleaned.any { option ->
                                    option.storageKey == currentTabId.defaultSort.storageKey
                                }
                            ) {
                                cleaned
                            } else {
                                buildList {
                                    add(currentTabId.defaultSort)
                                    addAll(cleaned)
                                }
                            }

                            val distinctByKey = ensured.distinctBy { it.storageKey }
                            distinctByKey.ifEmpty { listOf(currentTabId.defaultSort) }.toImmutableList()
                        }

                        val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
                        val visiblePlaylists = playlistUiState.playlists
                        val allSongsLazyPagingItems = libraryViewModel.songsPagingFlow.collectAsLazyPagingItems()
                        val albumsLazyPagingItems = libraryViewModel.albumsPagingFlow.collectAsLazyPagingItems()
                        val artistsLazyPagingItems = libraryViewModel.artistsPagingFlow.collectAsLazyPagingItems()
                        val favoritePagingItems = libraryViewModel.favoritesPagingFlow.collectAsLazyPagingItems()
                        val isLibraryLoading by libraryViewModel.isLoadingLibrary.collectAsStateWithLifecycle()
                        val hasCurrentSong by remember(playerViewModel) {
                            playerViewModel.stablePlayerState
                                .map { state -> state.currentSong != null && state.currentSong != Song.emptySong() }
                                .distinctUntilChanged()
                        }.collectAsStateWithLifecycle(initialValue = false)
                        val currentSelectedSortOption: SortOption? = when (currentTabId) {
                            LibraryTabId.SONGS -> playerUiState.currentSongSortOption
                            LibraryTabId.ALBUMS -> playerUiState.currentAlbumSortOption
                            LibraryTabId.ARTISTS -> playerUiState.currentArtistSortOption
                            LibraryTabId.PLAYLISTS -> playlistUiState.currentPlaylistSortOption
                            LibraryTabId.LIKED -> playerUiState.currentFavoriteSortOption
                            LibraryTabId.FOLDERS -> playerUiState.currentFolderSortOption
                            // These tabs have no sort of their own to remember: a mix's order
                            // belongs to the engine that produced it, a collection's to its own
                            // saved sort, and the genre list to the query that returned it.
                            LibraryTabId.GENRES,
                            LibraryTabId.COLLECTIONS -> null
                        }

                        val showLocateButton = when (currentTabId) {
                            LibraryTabId.SONGS -> songsShowLocateButton
                            LibraryTabId.LIKED -> likedShowLocateButton
                            LibraryTabId.FOLDERS -> foldersShowLocateButton
                            else -> false
                        }
                        val locateAction = when (currentTabId) {
                            LibraryTabId.SONGS -> songsLocateAction
                            LibraryTabId.LIKED -> likedLocateAction
                            LibraryTabId.FOLDERS -> foldersLocateAction
                            else -> null
                        }

                        val onSortOptionChanged: (SortOption) -> Unit = remember(playerViewModel, playlistViewModel, currentTabId) {
                            { option ->
                                when (currentTabId) {
                                    LibraryTabId.SONGS -> playerViewModel.sortSongs(option)
                                    LibraryTabId.ALBUMS -> playerViewModel.sortAlbums(option)
                                    LibraryTabId.ARTISTS -> playerViewModel.sortArtists(option)
                                    LibraryTabId.PLAYLISTS -> playlistViewModel.sortPlaylists(option)
                                    LibraryTabId.LIKED -> playerViewModel.sortFavoriteSongs(option)
                                    LibraryTabId.FOLDERS -> playerViewModel.sortFolders(option)
                                    LibraryTabId.GENRES,
                                    LibraryTabId.COLLECTIONS -> Unit
                                }
                            }
                        }

                        AnimatedContent(
                            targetState = isSelectionMode || isPlaylistSelectionMode || isAlbumSelectionMode,
                            label = "ActionRowModeSwitch",
                            transitionSpec = {
                                (slideInHorizontally { -it } + fadeIn()) togetherWith
                                        (slideOutHorizontally { it } + fadeOut())
                            },
                            modifier = Modifier
                                .padding(
                                    top = 6.dp,
                                    start = 10.dp,
                                    end = 10.dp
                                )
                                // PHASE 14.0 — this floor was sized for the old icon-button row
                                // (42 dp controls in a taller mode-switch row). The ordinary row is
                                // now a single 40 dp line of text actions, so 56 dp reserved 16 dp
                                // of dead space between `Title ↑` and the first song, and it was
                                // the real owner of the list's start position: shrinking the sort
                                // control could not move the list, because this floor clamped it.
                                // Proven 1:1 — forcing the slot to 160 px moved the list top from
                                // y379 to y427. A floor is still wanted so the selection-mode bar
                                // and the ordinary row do not jump between each other; it just
                                // matches the row that is actually there now.
                                .heightIn(min = 40.dp)
                        ) { inSelectionMode ->
                            if (inSelectionMode) {
                                if (currentTabId == LibraryTabId.PLAYLISTS && isPlaylistSelectionMode) {
                                    SelectionActionRow(
                                        selectedCount = selectedPlaylists.size,
                                        onSelectAll = {
                                            playerViewModel.playlistSelectionStateHolder.selectAll(visiblePlaylists)
                                        },
                                        onDeselect = { playerViewModel.playlistSelectionStateHolder.clearSelection() },
                                        onOptionsClick = { showPlaylistMultiSelectionSheet = true }
                                    )
                                } else if (currentTabId == LibraryTabId.ALBUMS && isAlbumSelectionMode) {
                                    SelectionActionRow(
                                        selectedCount = selectedAlbums.size,
                                        onSelectAll = {
                                            val remaining = MAX_ALBUM_MULTI_SELECTION - selectedAlbums.size
                                            if (remaining <= 0) {
                                                playerViewModel.sendToast(
                                                    context.getString(
                                                        R.string.presentation_batch_d_max_albums_selection,
                                                        MAX_ALBUM_MULTI_SELECTION
                                                    )
                                                )
                                            } else {
                                                val albumsToAppend = playerViewModel.albumsFlow.value
                                                    .filterNot { selectedAlbumIds.contains(it.id) }
                                                    .take(remaining)
                                                if (albumsToAppend.isNotEmpty()) {
                                                    selectedAlbums = (selectedAlbums + albumsToAppend).toImmutableList()
                                                }
                                            }
                                        },
                                        onDeselect = { selectedAlbums = persistentListOf() },
                                        onOptionsClick = { showAlbumMultiSelectionSheet = true }
                                    )
                                } else {
                                    SelectionActionRow(
                                        selectedCount = selectedSongs.size,
                                        onSelectAll = {
                                            when (tabTitles.getOrNull(currentTabIndex)?.toLibraryTabIdOrNull()) {
                                                LibraryTabId.LIKED -> {
                                                    multiSelectionState.selectAll(favoritePagingItems.itemSnapshotList.items)
                                                }
                                                LibraryTabId.FOLDERS -> {
                                                    val songsToSelect =
                                                        playerViewModel.playerUiState.value.currentFolder?.songs ?: emptyList()
                                                    multiSelectionState.selectAll(songsToSelect)
                                                }
                                                LibraryTabId.SONGS -> {
                                                    scope.launch {
                                                        val songsToSelect =
                                                            playerViewModel.getSongsForCurrentLibrarySelection()
                                                        multiSelectionState.selectAll(songsToSelect)
                                                    }
                                                }
                                                else -> Unit
                                            }
                                        },
                                        onDeselect = { multiSelectionState.clearSelection() },
                                        onOptionsClick = { showMultiSelectionSheet = true }
                                    )
                                }
                            } else {
                                DapLibraryActionRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    onMainActionClick = {
                                        when (tabTitles.getOrNull(currentTabIndex)?.toLibraryTabIdOrNull()) {
                                            LibraryTabId.PLAYLISTS -> showPlaylistCreationTypeDialog = true
                                            LibraryTabId.LIKED -> playerViewModel.shuffleFavoriteSongs()
                                            LibraryTabId.ALBUMS -> playerViewModel.shuffleRandomAlbum()
                                            LibraryTabId.ARTISTS -> playerViewModel.shuffleRandomArtist()
                                            else -> playerViewModel.shuffleAllSongs()
                                        }
                                    },
                                    // Asked of the tab's own list, not of the sanitized one. The
                                    // sanitizer fills an empty list with the tab's default sort so
                                    // that every tab has *a* sort to name — which meant a tab the
                                    // engine deliberately gives no options to still drew a sort
                                    // control, and that control changed nothing.
                                    showSortButton = availableSortOptions.isNotEmpty(),
                                    currentSortOption = currentSelectedSortOption,
                                    showLocateButton = showLocateButton,
                                    onSortClick = { playerViewModel.showSortingSheet() },
                                    onLocateClick = { locateAction?.invoke() },
                                    isPlaylistTab = currentTabId == LibraryTabId.PLAYLISTS,
                                    isFoldersTab = currentTabId == LibraryTabId.FOLDERS && (!playerUiState.isFoldersPlaylistView || playerUiState.currentFolder != null),
                                    onImportM3uClick = { m3uImportLauncher.launch("audio/x-mpegurl") },
                                    currentFolder = playerUiState.currentFolder,
                                    folderRootPath = playerUiState.folderSourceRootPath.ifBlank {
                                        Environment.getExternalStorageDirectory().path
                                    },
                                    folderRootLabel = stringResource(playerUiState.folderSource.displayNameRes()),
                                    onFolderClick = { playerViewModel.navigateToFolder(it) },
                                    onNavigateBack = { playerViewModel.navigateBackFolder() },
                                    showStorageFilterButton = currentTabId == LibraryTabId.SONGS ||
                                            currentTabId == LibraryTabId.ALBUMS ||
                                            currentTabId == LibraryTabId.ARTISTS ||
                                            currentTabId == LibraryTabId.LIKED ||
                                            (ENABLE_FOLDERS_STORAGE_FILTER && currentTabId == LibraryTabId.FOLDERS),
                                    currentStorageFilter = playerUiState.currentStorageFilter,
                                    onStorageFilterClick = { playerViewModel.toggleStorageFilter() }
                                )
                            }
                        }

                        if (isSortSheetVisible && sanitizedSortOptions.isNotEmpty()) {
                            val currentSelectionKey = currentSelectedSortOption?.storageKey
                            val selectedOptionForSheet = sanitizedSortOptions.firstOrNull { option ->
                                option.storageKey == currentSelectionKey
                            }
                                ?: sanitizedSortOptions.firstOrNull { option ->
                                    option.storageKey == currentTabId.defaultSort.storageKey
                                }
                                ?: sanitizedSortOptions.first()


                            val isAlbumTab = currentTabId == LibraryTabId.ALBUMS
                            val isFoldersTab = currentTabId == LibraryTabId.FOLDERS

                            LibrarySortBottomSheet(
                                title = stringResource(R.string.presentation_batch_d_sort_by),
                                options = sanitizedSortOptions,
                                selectedOption = selectedOptionForSheet,
                                onDismiss = { playerViewModel.hideSortingSheet() },
                                onOptionSelected = { option ->
                                    onSortOptionChanged(option)
                                    playerViewModel.hideSortingSheet()
                                },
                                onDirectionToggle = { option ->
                                    onSortOptionChanged(option)
                                },
                                showViewToggle = isFoldersTab,
                                viewSectionTitle = stringResource(R.string.presentation_batch_d_view_section_view),
                                viewToggleLabel = stringResource(R.string.presentation_batch_d_playlist_view),
                                viewToggleChecked = playerUiState.isFoldersPlaylistView,
                                onViewToggleChange = { isChecked ->
                                    playerViewModel.setFoldersPlaylistView(isChecked)
                                },
                                viewToggleContent = if (isAlbumTab) {
                                    {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().height(48.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val isList = playerUiState.isAlbumsListView
                                            val accent = LocalDapAccent.current

                                            ToggleSegmentButton(
                                                modifier = Modifier.weight(1f),
                                                active = !isList,
                                                activeColor = accent,
                                                inactiveColor = Dap.surfaceRaised,
                                                activeContentColor = Dap.onFill,
                                                inactiveContentColor = Dap.secondaryText,
                                                activeCornerRadius = DapRadius.Surface,
                                                onClick = { playerViewModel.setAlbumsListView(false) },
                                                text = stringResource(R.string.presentation_batch_d_view_grid),
                                                imageVector = Icons.Rounded.ViewModule
                                            )

                                            ToggleSegmentButton(
                                                modifier = Modifier.weight(1f),
                                                active = isList,
                                                activeColor = accent,
                                                inactiveColor = Dap.surfaceRaised,
                                                activeContentColor = Dap.onFill,
                                                inactiveContentColor = Dap.secondaryText,
                                                activeCornerRadius = DapRadius.Surface,
                                                onClick = { playerViewModel.setAlbumsListView(true) },
                                                text = stringResource(R.string.presentation_batch_d_view_list),
                                                imageVector = Icons.AutoMirrored.Rounded.ViewList
                                            )
                                        }
                                    }
                                } else null,
                                sourceToggleContent = if (isFoldersTab && ENABLE_FOLDERS_SOURCE_TOGGLE) {
                                    {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().height(48.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val isSdAvailable = playerUiState.isSdCardAvailable
                                            val accent = LocalDapAccent.current
                                            ToggleSegmentButton(
                                                modifier = Modifier.weight(1f),
                                                active = playerUiState.folderSource == FolderSource.INTERNAL,
                                                activeColor = accent,
                                                inactiveColor = Dap.surfaceRaised,
                                                activeContentColor = Dap.onFill,
                                                inactiveContentColor = Dap.secondaryText,
                                                activeCornerRadius = DapRadius.Surface,
                                                onClick = { playerViewModel.setFoldersSource(FolderSource.INTERNAL) },
                                                text = stringResource(R.string.presentation_batch_d_storage_internal)
                                            )
                                            ToggleSegmentButton(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .alpha(if (isSdAvailable) 1f else 0.5f),
                                                active = playerUiState.folderSource == FolderSource.SD_CARD,
                                                activeColor = accent,
                                                inactiveColor = Dap.surfaceRaised,
                                                activeContentColor = Dap.onFill,
                                                inactiveContentColor = Dap.secondaryText,
                                                activeCornerRadius = DapRadius.Surface,
                                                onClick = {
                                                    if (isSdAvailable) {
                                                        playerViewModel.setFoldersSource(FolderSource.SD_CARD)
                                                    }
                                                },
                                                text = stringResource(R.string.presentation_batch_d_storage_sd_card)
                                            )
                                        }
                                        if (!playerUiState.isSdCardAvailable) {
                                            Text(
                                                text = stringResource(R.string.presentation_batch_d_sd_card_unavailable),
                                                style = DapType.Note,
                                                color = Dap.tertiaryText,
                                                modifier = Modifier.padding(top = 8.dp, start = 2.dp)
                                            )
                                        }
                                    }
                                } else null
                            )
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            LibraryInlineSyncIndicator(
                                visible = inlineSyncVisible && !isLibraryContentEmpty,
                                syncManager = syncManager,
                                modifier = Modifier.fillMaxWidth()
                            )
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(top = 0.dp),
                                pageSpacing = 0.dp,
                                beyondViewportPageCount = 1,
                                key = { it }
                            ) { page ->
                                val tabIndex = resolveTabIndex(
                                    page = page,
                                    tabCount = tabTitles.size,
                                    compactMode = isCompactNavigation
                                )
                                when (tabTitles.getOrNull(tabIndex)?.toLibraryTabIdOrNull()) {
                                    LibraryTabId.GENRES -> {
                                        // Reuses the genre grid Search already shows on a blank
                                        // query, rather than a second presentation of the same
                                        // list. Genres were reachable only by opening Search and
                                        // not typing anything, which is not somewhere a listener
                                        // would look for browsing their library.
                                        val genres by playerViewModel.genres.collectAsStateWithLifecycle()
                                        GenreCategoriesGrid(
                                            genres = genres,
                                            bottomContentPadding = bottomBarHeightDp + LocalDapPlayerShelfHeight.current + floatingActionsGap,
                                            onGenreClick = { genre ->
                                                navController.navigateSafely(
                                                    Screen.GenreDetail.createRoute(
                                                        java.net.URLEncoder.encode(genre.id, "UTF-8")
                                                    )
                                                )
                                            },
                                            playerViewModel = playerViewModel,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    LibraryTabId.COLLECTIONS -> {
                                        SmartCollectionsContent(
                                            playerViewModel = playerViewModel,
                                            onSongMoreOptions = stableOnMoreOptionsClick,
                                            bottomContentPadding = bottomBarHeightDp + LocalDapPlayerShelfHeight.current + floatingActionsGap,
                                        )
                                    }

                                    LibraryTabId.SONGS -> {
                                        LibrarySongsTab(
                                            songs = allSongsLazyPagingItems,
                                            isLoading = isLibraryLoading,
                                            playerViewModel = playerViewModel,
                                            bottomBarHeight = bottomBarHeightDp,
                                            extraBottomGap = floatingActionsGap,
                                            onMoreOptionsClick = stableOnMoreOptionsClick,
                                            isRefreshing = isRefreshing,
                                            onRefresh = {
                                                onRefresh()
                                                allSongsLazyPagingItems.refresh()
                                            },
                                            isSelectionMode = isSelectionMode,
                                            selectedSongIds = selectedSongIds,
                                            onSongLongPress = onSongLongPress,
                                            onSongSelectionToggle = onSongSelectionToggle,
                                            getSelectionIndex = playerViewModel.multiSelectionStateHolder::getSelectionIndex,
                                            onLocateCurrentSongVisibilityChanged = { songsShowLocateButton = it },
                                            onRegisterLocateCurrentSongAction = { songsLocateAction = it },
                                            sortOption = playerUiState.currentSongSortOption,
                                            storageFilter = playerUiState.currentStorageFilter,
                                            hasCurrentSong = hasCurrentSong
                                        )
                                    }
                                    LibraryTabId.ALBUMS -> {
                                        val isLoading = playerUiState.isLoadingLibraryCategories

                                        val stableOnAlbumClick: (Long) -> Unit = remember(navController) {
                                            { albumId: Long ->
                                                navController.navigateSafelyReplacing(
                                                    route = Screen.AlbumDetail.createRoute(albumId),
                                                    patternToPop = Screen.AlbumDetail.route
                                                )
                                            }
                                        }
                                        LibraryAlbumsTab(
                                            albums = albumsLazyPagingItems,
                                            isLoading = isLoading,
                                            playerViewModel = playerViewModel,
                                            bottomBarHeight = bottomBarHeightDp,
                                            extraBottomGap = floatingActionsGap,
                                            isListView = playerUiState.isAlbumsListView,
                                            currentAlbumSortOption = playerUiState.currentAlbumSortOption,
                                            onAlbumClick = stableOnAlbumClick,
                                            isRefreshing = isRefreshing,
                                            onRefresh = onRefresh,
                                            isSelectionMode = isAlbumSelectionMode,
                                            selectedAlbumIds = selectedAlbumIds,
                                            onAlbumLongPress = onAlbumLongPress,
                                            onAlbumSelectionToggle = onAlbumSelectionToggle,
                                            getSelectionIndex = getAlbumSelectionIndex,
                                            storageFilter = playerUiState.currentStorageFilter
                                        )
                                    }

                                    LibraryTabId.ARTISTS -> {
                                        val isLoading = playerUiState.isLoadingLibraryCategories

                                        LibraryArtistsTab(
                                            artists = artistsLazyPagingItems,
                                            isLoading = isLoading,
                                            playerViewModel = playerViewModel,
                                            bottomBarHeight = bottomBarHeightDp,
                                            extraBottomGap = floatingActionsGap,
                                            currentArtistSortOption = playerUiState.currentArtistSortOption,
                                            onArtistClick = { artistId ->
                                                navController.navigateSafelyReplacing(
                                                    route = Screen.ArtistDetail.createRoute(artistId),
                                                    patternToPop = Screen.ArtistDetail.route
                                                )
                                            },
                                            isRefreshing = isRefreshing,
                                            onRefresh = onRefresh,
                                            storageFilter = playerUiState.currentStorageFilter
                                        )
                                    }

                                    LibraryTabId.PLAYLISTS -> {
                                        LibraryPlaylistsTab(
                                            playlistUiState = playlistUiState,
                                            filteredPlaylists = visiblePlaylists,
                                            navController = navController,
                                            playerViewModel = playerViewModel,
                                            bottomBarHeight = bottomBarHeightDp,
                                            isRefreshing = isRefreshing,
                                            onRefresh = onRefresh,
                                            isSelectionMode = isPlaylistSelectionMode,
                                            selectedPlaylistIds = selectedPlaylistIds,
                                            onPlaylistLongPress = onPlaylistLongPress,
                                            onPlaylistSelectionToggle = onPlaylistSelectionToggle,
                                            onPlaylistOptionsClick = { showPlaylistMultiSelectionSheet = true }
                                        )
                                    }

                                    LibraryTabId.LIKED -> {
                                        LibraryFavoritesTab(
                                            favoriteSongs = favoritePagingItems,
                                            playerViewModel = playerViewModel,
                                            bottomBarHeight = bottomBarHeightDp,
                                            extraBottomGap = floatingActionsGap,
                                            onMoreOptionsClick = stableOnMoreOptionsClick,
                                            isRefreshing = isRefreshing,
                                            onRefresh = {
                                                onRefresh()
                                                favoritePagingItems.refresh()
                                            },
                                            isSelectionMode = isSelectionMode,
                                            selectedSongIds = selectedSongIds,
                                            onSongLongPress = onSongLongPress,
                                            onSongSelectionToggle = onSongSelectionToggle,
                                            getSelectionIndex = playerViewModel.multiSelectionStateHolder::getSelectionIndex,
                                            sortOption = playerUiState.currentFavoriteSortOption,
                                            onLocateCurrentSongVisibilityChanged = { likedShowLocateButton = it },
                                            onRegisterLocateCurrentSongAction = { likedLocateAction = it },
                                            storageFilter = playerUiState.currentStorageFilter,
                                            hasCurrentSong = hasCurrentSong
                                        )
                                    }

                                    LibraryTabId.FOLDERS -> {
                                        val folders = playerUiState.musicFolders
                                        val currentFolder = playerUiState.currentFolder
                                        val isLoading = playerUiState.isLoadingLibraryCategories
                                        val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
                                        val defaultFolderName = stringResource(R.string.presentation_batch_d_folder_name_fallback)

                                        LibraryFoldersTab(
                                            folders = folders,
                                            currentFolder = currentFolder,
                                            isLoading = isLoading,
                                            bottomBarHeight = bottomBarHeightDp,
                                            extraBottomGap = floatingActionsGap,
                                            stablePlayerState = stablePlayerState,
                                            onNavigateBack = { playerViewModel.navigateBackFolder() },
                                            onFolderClick = { folderPath -> playerViewModel.navigateToFolder(folderPath) },
                                            onFolderAsPlaylistClick = { folder ->
                                                val encodedPath = Uri.encode(folder.path)
                                                navController.navigateSafelyReplacing(
                                                    route = Screen.PlaylistDetail.createRoute(
                                                        "${PlaylistViewModel.FOLDER_PLAYLIST_PREFIX}$encodedPath"
                                                    ),
                                                    patternToPop = Screen.PlaylistDetail.route
                                                )
                                            },
                                            onPlaySong = { song, queue ->
                                                playerViewModel.showAndPlaySong(
                                                    song,
                                                    queue,
                                                    currentFolder?.name ?: defaultFolderName,
                                                    source = ListenSource(ListenSourceKind.OTHER, "folder")
                                                )
                                            },
                                            onMoreOptionsClick = stableOnMoreOptionsClick,
                                            isPlaylistView = playerUiState.isFoldersPlaylistView,
                                            currentSortOption = playerUiState.currentFolderSortOption,
                                            isRefreshing = isRefreshing,
                                            onRefresh = onRefresh,
                                            isSelectionMode = isSelectionMode,
                                            selectedSongIds = selectedSongIds,
                                            onSongLongPress = onSongLongPress,
                                            onSongSelectionToggle = onSongSelectionToggle,
                                            getSelectionIndex = playerViewModel.multiSelectionStateHolder::getSelectionIndex,
                                            onLocateCurrentSongVisibilityChanged = { foldersShowLocateButton = it },
                                            onRegisterLocateCurrentSongAction = { foldersLocateAction = it },
                                            pendingLocatePath = pendingFoldersLocatePath,
                                            onClearPendingLocate = { pendingFoldersLocatePath = null },
                                            onRequestCrossFolderLocate = { folderPath ->
                                                pendingFoldersLocatePath = folderPath
                                                playerViewModel.navigateToFolder(folderPath)
                                            }
                                        )
                                    }

                                    null -> Unit
                                }
                            }

                            val selectionCount = when {
                                currentTabId == LibraryTabId.PLAYLISTS && isPlaylistSelectionMode -> selectedPlaylists.size
                                currentTabId == LibraryTabId.ALBUMS && isAlbumSelectionMode -> selectedAlbums.size
                                else -> selectedSongs.size
                            }
                            // The count of selected items, in the observed-value voice: a mono
                            // number on bg/1, never a pill.
                            if (selectionCount > 0) {
                                Text(
                                    text = selectionCount.toString(),
                                    style = DapType.ValueSmall,
                                    color = LocalDapAccent.current,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .zIndex(1f)
                                        .padding(top = DapSpacing.Sm)
                                        .clip(RoundedCornerShape(DapRadius.Surface))
                                        .background(Dap.surface)
                                        .border(
                                            1.dp,
                                            Dap.border,
                                            RoundedCornerShape(DapRadius.Surface)
                                        )
                                        .padding(
                                            horizontal = DapSpacing.Md,
                                            vertical = DapSpacing.Xs
                                        )
                                )
                            }
                        }
                    }
                if (
                    isLibraryContentEmpty &&
                    (
                            playerUiState.isSyncingLibrary ||
                                    playerUiState.isLoadingInitialSongs ||
                                    playerUiState.isLoadingLibraryCategories
                            )
                ) {
                    LibrarySyncOverlay(syncManager = syncManager)
                }
            }

        }
    }



    PlaylistCreationTypeDialog(
        visible = showPlaylistCreationTypeDialog,
        onDismiss = { showPlaylistCreationTypeDialog = false },
        onManualSelected = {
            showPlaylistCreationTypeDialog = false
            showCreatePlaylistDialog = true
        },
        onDescribeSelected = {
            showPlaylistCreationTypeDialog = false
            showDescribePlaylistDialog = true
        }
    )

    val nlpPlaylistPreviewState by playlistViewModel.nlpPlaylistPreviewState.collectAsStateWithLifecycle()
    DescribePlaylistDialog(
        visible = showDescribePlaylistDialog,
        state = nlpPlaylistPreviewState,
        onGenerate = playlistViewModel::generateNlpPlaylistPreview,
        onSave = { name, songIds ->
            playlistViewModel.createPlaylist(name = name, songIds = songIds)
            playlistViewModel.resetNlpPlaylistPreview()
            showDescribePlaylistDialog = false
        },
        onDismiss = {
            playlistViewModel.resetNlpPlaylistPreview()
            showDescribePlaylistDialog = false
        }
    )

    CreatePlaylistDialog(
        playerViewModel = playerViewModel,
        visible = showCreatePlaylistDialog,
        onDismiss = { showCreatePlaylistDialog = false },
        onCreate = { name, imageUri, color, icon, songIds, cropScale, cropPanX, cropPanY, shapeType, d1, d2, d3, d4, smartRuleKey ->
            playlistViewModel.createPlaylist(
                name = name,
                coverImageUri = imageUri,
                coverColor = color,
                coverIcon = icon,
                songIds = songIds,
                cropScale = cropScale,
                cropPanX = cropPanX,
                cropPanY = cropPanY,
                isQueueGenerated = false,
                coverShapeType = shapeType,
                coverShapeDetail1 = d1,
                coverShapeDetail2 = d2,
                coverShapeDetail3 = d3,
                coverShapeDetail4 = d4,
                smartRuleKey = smartRuleKey
            )
        }
    )


    if (showSongInfoBottomSheet && selectedSongForInfo != null) {
        val currentSong = selectedSongForInfo
        val isFavorite = remember(currentSong?.id, favoriteIds) { derivedStateOf { currentSong?.let {
            favoriteIds.contains(
                it.id)
        } } }.value ?: false

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
                    playerViewModel.sendToast(context.getString(R.string.toast_added_to_queue))
                },
                onAddNextToQueue = {
                    playerViewModel.addSongNextToQueue(currentSong)
                    showSongInfoBottomSheet = false
                    playerViewModel.sendToast(context.getString(R.string.toast_playing_next))
                },
                onAddToPlayList = {
                    playlistSheetSongs = persistentListOf(currentSong)
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
                // Nothing to wire: every list this sheet opens from is a paging list over Room, so
                // deleting the file invalidates it and the row goes on its own.
                removeFromListTrigger = {},
                songInfoViewModel = songInfoBottomSheetViewModel
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
        }
    }

    if (showPlaylistBottomSheet) {
        val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

        PlaylistBottomSheet(
            playlistUiState = playlistUiState,
            songs = playlistSheetSongs,
            onDismiss = { showPlaylistBottomSheet = false },
            bottomBarHeight = bottomBarHeightDp,
            playerViewModel = playerViewModel,
        )
    }

    if (showMultiSelectionSheet && selectedSongs.isNotEmpty()) {
        val activity = context as? android.app.Activity

        MultiSelectionBottomSheet(
            selectedSongs = selectedSongs,
            favoriteSongIds = favoriteIds,
            onDismiss = { showMultiSelectionSheet = false },
            onPlayAll = {
                playerViewModel.playSelectedSongs(selectedSongs)
                showMultiSelectionSheet = false
            },
            onAddToQueue = {
                playerViewModel.addSelectedToQueue(selectedSongs)
                showMultiSelectionSheet = false
            },
            onPlayNext = {
                playerViewModel.addSelectedAsNext(selectedSongs)
                showMultiSelectionSheet = false
            },
            onAddToPlaylist = {
                playlistSheetSongs = selectedSongs
                showMultiSelectionSheet = false
                showPlaylistBottomSheet = true
            },
            onToggleLikeAll = { shouldLike ->
                if (shouldLike) {
                    playerViewModel.likeSelectedSongs(selectedSongs)
                } else {
                    playerViewModel.unlikeSelectedSongs(selectedSongs)
                }
                showMultiSelectionSheet = false
            },
            onShareAll = {
                playerViewModel.shareSelectedAsZip(selectedSongs)
                showMultiSelectionSheet = false
            },
            onDeleteAll = { _, onComplete ->
                activity?.let {
                    playerViewModel.deleteSelectedFromDevice(it, selectedSongs) {
                        showMultiSelectionSheet = false
                        onComplete(true)
                    }
                }
            },
            onBatchEdit = {
                showMultiSelectionSheet = false
                showBatchEditSheet = true
            }
        )
    }

    if (showAlbumMultiSelectionSheet && selectedAlbums.isNotEmpty()) {
        AlbumMultiSelectionOptionSheet(
            selectedAlbums = selectedAlbums,
            maxSelection = MAX_ALBUM_MULTI_SELECTION,
            onDismiss = { showAlbumMultiSelectionSheet = false },
            onPlay = {
                playerViewModel.playSelectedAlbums(selectedAlbums)
                selectedAlbums = persistentListOf()
                showAlbumMultiSelectionSheet = false
            },
            onPlayNext = {
                playerViewModel.addSelectedAlbumsAsNext(selectedAlbums)
                selectedAlbums = persistentListOf()
                showAlbumMultiSelectionSheet = false
            },
            onAddToQueue = {
                playerViewModel.addSelectedAlbumsToQueue(selectedAlbums)
                selectedAlbums = persistentListOf()
                showAlbumMultiSelectionSheet = false
            }
        )
    }

    if (showPlaylistMultiSelectionSheet && selectedPlaylists.isNotEmpty()) {
        val activity = context as? android.app.Activity

        PlaylistMultiSelectionBottomSheet(
            selectedPlaylists = selectedPlaylists,
            onDismiss = {
                showPlaylistMultiSelectionSheet = false
            },
            onDeleteAll = {
                playlistViewModel.deletePlaylistsInBatch(selectedPlaylistIds.toList())
                showPlaylistMultiSelectionSheet = false
                playlistMultiSelectionState.clearSelection()
            },
            onExportAll = {
                playlistViewModel.exportPlaylistsAsM3u(selectedPlaylistIds.toList())
                showPlaylistMultiSelectionSheet = false
                playlistMultiSelectionState.clearSelection()
            },
            onMergeAll = {
                pendingMergePlaylistIds = selectedPlaylistIds.toList()
                showMergePlaylistDialog = true
                showPlaylistMultiSelectionSheet = false
            },
            onShareAll = {
                activity?.let {
                    playlistViewModel.shareSelectedPlaylistsAsZip(selectedPlaylistIds.toList(), it)
                }
                showPlaylistMultiSelectionSheet = false
                playlistMultiSelectionState.clearSelection()
            }
        )
    }

    if (showTabSwitcherSheet) {
        LibraryTabSwitcherSheet(
            tabs = tabTitles,
            currentIndex = currentTabIndex,
            onTabSelected = { index ->
                animateToLibraryTab(index)
                showTabSwitcherSheet = false
            },
            onEditClick = {
                showTabSwitcherSheet = false
                showReorderTabsSheet = true
            },
            onDismiss = { showTabSwitcherSheet = false }
        )
    }

    if (showReorderTabsSheet) {
        ReorderTabsSheet(
            // The editor is given every tab, not just the visible ones, so a hidden tab can
            // always be found and turned back on.
            orderedTabs = allTabTitles,
            hiddenTabs = hiddenTabTitles.toSet(),
            onSave = { order, hidden ->
                playerViewModel.saveLibraryTabConfiguration(order, hidden)
            },
            onReset = {
                playerViewModel.resetLibraryTabsOrder()
            },
            onDismiss = { showReorderTabsSheet = false }
        )
    }

    if (showMergePlaylistDialog && pendingMergePlaylistIds.isNotEmpty()) {
        var mergePlaylistName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = {
                showMergePlaylistDialog = false
                pendingMergePlaylistIds = emptyList()
                mergePlaylistName = ""
            },
            title = { Text(stringResource(R.string.presentation_batch_d_merge_playlists_title)) },
            text = {
                // An alert is a window of its own, so the activity's volume-key path does not reach
                // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                ModalInputBridge()
                Column {
                    Text(stringResource(R.string.presentation_batch_d_merge_playlists_prompt))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = mergePlaylistName,
                        keyboardActions = dapDismissKeyboardActions(),
                        onValueChange = { mergePlaylistName = it },
                        placeholder = { Text(stringResource(R.string.presentation_batch_d_merge_playlists_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().dapTextField()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.presentation_batch_d_merge_playlists_body,
                            pendingMergePlaylistIds.size
                        ),
                        style = DapType.Note,
                        color = Dap.tertiaryText
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (mergePlaylistName.isNotEmpty()) {
                            playlistViewModel.mergePlaylistsIntoOne(
                                pendingMergePlaylistIds,
                                mergePlaylistName
                            )
                            playlistMultiSelectionState.clearSelection()
                            showMergePlaylistDialog = false
                            pendingMergePlaylistIds = emptyList()
                            mergePlaylistName = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_merge))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMergePlaylistDialog = false
                    pendingMergePlaylistIds = emptyList()
                    mergePlaylistName = ""
                }) {
                    Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }

    if (showBatchEditSheet && selectedSongs.isNotEmpty()) {
        EditMultipleSongsSheet(
            visible = showBatchEditSheet,
            songs = selectedSongs,
            onDismiss = { showBatchEditSheet = false },
            onSave = { songs, title, artist, album, albumArtist, composer, genre, lyrics, trackNumber, discNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                playerViewModel.saveBatchMetadata(
                    songs = songs,
                    title = title,
                    artist = artist,
                    album = album,
                    albumArtist = albumArtist,
                    composer = composer,
                    genre = genre,
                    lyrics = lyrics,
                    trackNumber = trackNumber,
                    discNumber = discNumber,
                    replayGainTrackGainDb = replayGainTrackGainDb,
                    replayGainAlbumGainDb = replayGainAlbumGainDb,
                    coverArtUpdate = coverArtUpdate
                )
            }
        )
    }
}

@Composable
private fun CompactLibraryPagerIndicator(
    currentIndex: Int,
    pageCount: Int,
    modifier: Modifier = Modifier
) {
    if (pageCount <= 1) return

    val safeIndex = positiveMod(currentIndex, pageCount)
    val accent = LocalDapAccent.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val selected = index == safeIndex
            val width by animateDpAsState(
                targetValue = if (selected) 22.dp else 10.dp,
                label = "LibraryCompactPagerIndicatorWidth"
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(2.dp)
                    .width(width)
                    .background(if (selected) accent else Dap.interactiveTrack)
            )
        }
    }
}

/**
 * Progress row for startup syncs, background maintenance, and manual refreshes after the
 * short pull-to-refresh confirmation window. It occupies space below [DapLibraryActionRow]
 * and above the pager, so collection actions never sit beneath its text or progress bar.
 *
 * Distinct from [LibrarySyncOverlay], which is reserved for initial empty-library
 * loads. The parent screen also gates this indicator off while the pull spinner is
 * visible, so the two feedback channels do not compete.
 */
@Composable
private fun LibraryInlineSyncIndicator(
    visible: Boolean,
    syncManager: com.lostf1sh.pixelplayeross.data.worker.SyncManager,
    modifier: Modifier = Modifier
) {
    // This row has its own space before the pager, including during its visibility animation.
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn(animationSpec = tween(180)) +
            androidx.compose.animation.slideInVertically(
                initialOffsetY = { -it / 2 },
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ),
        exit = androidx.compose.animation.fadeOut(animationSpec = tween(160)) +
            androidx.compose.animation.slideOutVertically(
                targetOffsetY = { -it / 2 },
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
            )
    ) {
        val syncProgress by syncManager.syncProgress
            .collectAsStateWithLifecycle(initialValue = SyncProgress())

        val phaseLabel = when (syncProgress.phase) {
            SyncProgress.SyncPhase.FETCHING_MEDIASTORE ->
                stringResource(R.string.sync_scanning)
            SyncProgress.SyncPhase.PROCESSING_FILES,
            SyncProgress.SyncPhase.SAVING_TO_DATABASE ->
                stringResource(R.string.sync_processing)
            SyncProgress.SyncPhase.SCANNING_LRC ->
                stringResource(R.string.library_background_sync_lyrics)
            SyncProgress.SyncPhase.CLEANING_CACHE ->
                stringResource(R.string.library_background_sync_cache)
            else ->
                stringResource(R.string.sync_in_progress)
        }

        val accent = LocalDapAccent.current

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Sm)
        ) {
            Text(
                text = phaseLabel,
                style = DapType.ValueSmall,
                color = Dap.tertiaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(DapSpacing.Sm))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Dap.interactiveTrack)
            ) {
                if (syncProgress.hasProgress) {
                    Box(
                        Modifier
                            .fillMaxWidth(syncProgress.progress.coerceIn(0f, 1f))
                            .height(2.dp)
                            .background(accent)
                    )
                }
            }
        }
    }
}

/**
 * P1-1: Isolated sync/loading overlay composable.
 *
 * By collecting [SyncManager.syncProgress] HERE instead of in the parent [LibraryScreen],
 * only this small subtree recomposes on every progress tick (e.g., file count updates
 * during a library scan). The rest of [LibraryScreen] — including the Scaffold, pager,
 * and all tab content — remains unaffected during sync.
 */
@Composable
private fun LibrarySyncOverlay(syncManager: com.lostf1sh.pixelplayeross.data.worker.SyncManager) {
    val syncProgress by syncManager.syncProgress
        .collectAsStateWithLifecycle(initialValue = SyncProgress())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Dap.background)
            .padding(horizontal = DapSpacing.Xl, vertical = DapSpacing.GroupGap),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.syncing_library),
            style = DapType.SectionHeader,
            color = Dap.tertiaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(DapSpacing.Lg))
        if (syncProgress.hasProgress && syncProgress.isRunning) {
            SyncProgressBar(
                syncProgress = syncProgress,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            DapSkeletonList(count = 3, showArt = true)
        }
    }
}

/**
 * Compact library header title: the current tab's name plus the switcher affordance.
 *
 * Tapping opens the tab switcher, swiping horizontally moves between tabs. The name is set in the
 * page-title role and ellipsises rather than shrinking, so a long translation stays readable.
 */
@Composable
private fun LibraryNavigationCompactTitle(
    modifier: Modifier = Modifier,
    title: String,
    pageIndex: Int,
    onClick: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
) {
    val displayedTitle = remember(title) {
        title.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            }
            .pointerInput(Unit) {
                var totalDrag = 0f
                val swipeThresholdPx = 50.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        totalDrag += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        if (totalDrag > swipeThresholdPx) {
                            onSwipeRight()
                        } else if (totalDrag < -swipeThresholdPx) {
                            onSwipeLeft()
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f }
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayedTitle,
            style = DapType.Title,
            color = Dap.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(DapSpacing.Sm))
        DapIcon(
            kind = DapIconKind.ChevronDown,
            tint = Dap.tertiaryText,
            size = 12.dp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTabSwitcherSheet(
    tabs: ImmutableList<String>,
    currentIndex: Int,
    onTabSelected: (Int) -> Unit,
    onEditClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Dap.surface,
    ) {
        // This window is its own surface, so the activity's volume-key path does not
        // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
        ModalInputBridge()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Md),
        ) {
            Text(
                text = stringResource(R.string.presentation_batch_d_library_tabs_sheet_title),
                style = DapType.Heading,
                color = Dap.primaryText
            )
            Spacer(modifier = Modifier.height(DapSpacing.Xs))
            Text(
                text = stringResource(R.string.presentation_batch_d_library_tabs_sheet_subtitle),
                style = DapType.Caption,
                color = Dap.secondaryText
            )
            Spacer(modifier = Modifier.height(DapSpacing.Lg))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                verticalArrangement = Arrangement.spacedBy(DapSpacing.Sm),
                horizontalArrangement = Arrangement.spacedBy(DapSpacing.Lg),
                contentPadding = PaddingValues(bottom = DapSpacing.SectionGap)
            ) {
                itemsIndexed(
                    items = tabs,
                    key = { index, tab -> "$tab-$index" },
                    contentType = { _, _ -> "library_tab_item" }
                ) { index, rawId ->
                    val tabId = rawId.toLibraryTabIdOrNull() ?: return@itemsIndexed
                    LibraryTabGridItem(
                        tabId = tabId,
                        isSelected = index == currentIndex,
                        onClick = { onTabSelected(index) }
                    )
                }

                item(
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "reorder_tabs_action"
                ) {
                    DapSecondaryButton(
                        label = stringResource(R.string.presentation_batch_d_reorder_tabs_label),
                        onClick = onEditClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * One tab in the switcher: a mono label with the accent underline when it is the open tab.
 * No container fill and no icon disc — the tabs read as rows on the sheet surface.
 */
@Composable
private fun LibraryTabGridItem(
    tabId: LibraryTabId,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val accent = LocalDapAccent.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = DapSizes.MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = DapSpacing.Xs, vertical = DapSpacing.Md),
        verticalArrangement = Arrangement.Bottom
    ) {
        Text(
            text = tabId.displayTitle().uppercase(currentLocale()),
            style = DapType.SectionHeader,
            color = if (isSelected) Dap.primaryText else Dap.tertiaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(DapSpacing.Sm))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (isSelected) accent else Dap.divider)
        )
    }
}

private fun positiveMod(value: Int, mod: Int): Int {
    if (mod <= 0) return 0
    return ((value % mod) + mod) % mod
}

private fun infinitePagerInitialPage(tabCount: Int, selectedTabIndex: Int): Int {
    if (tabCount <= 0) return 0
    val midpoint = Int.MAX_VALUE / 2
    val aligned = midpoint - positiveMod(midpoint, tabCount)
    return aligned + positiveMod(selectedTabIndex, tabCount)
}

private fun resolveTabIndex(page: Int, tabCount: Int, compactMode: Boolean): Int {
    if (tabCount <= 0) return 0
    return if (compactMode) positiveMod(page, tabCount) else page.coerceIn(0, tabCount - 1)
}

private fun targetPageForTabIndex(
    currentPage: Int,
    targetTabIndex: Int,
    tabCount: Int,
    compactMode: Boolean
): Int {
    if (tabCount <= 0) return 0
    val safeTarget = positiveMod(targetTabIndex, tabCount)
    if (!compactMode) return safeTarget

    val currentBase = currentPage - positiveMod(currentPage, tabCount)
    val candidate = currentBase + safeTarget
    val prevCandidate = candidate - tabCount
    val nextCandidate = candidate + tabCount

    return listOf(prevCandidate, candidate, nextCandidate)
        .minByOrNull { abs(it - currentPage) }
        ?: candidate
}

private fun LibraryTabId.iconRes(): Int = when (this) {
    LibraryTabId.SONGS -> R.drawable.rounded_music_note_24
    LibraryTabId.ALBUMS -> R.drawable.rounded_album_24
    LibraryTabId.ARTISTS -> R.drawable.rounded_artist_24
    LibraryTabId.PLAYLISTS -> R.drawable.rounded_playlist_play_24
    LibraryTabId.FOLDERS -> R.drawable.rounded_folder_24
    LibraryTabId.LIKED -> R.drawable.round_favorite_24
    LibraryTabId.GENRES -> R.drawable.rounded_artist_24
    LibraryTabId.COLLECTIONS -> R.drawable.rounded_settings_24
}

@Composable
private fun LibraryTabId.displayTitle(): String = stringResource(titleRes)

internal fun resolveFolderNavigationDirection(initialPath: String?, targetPath: String?): Int =
    when {
        initialPath == targetPath -> FOLDER_NAVIGATION_FORWARD
        initialPath == null && targetPath != null -> FOLDER_NAVIGATION_FORWARD
        initialPath != null && targetPath == null -> FOLDER_NAVIGATION_BACKWARD
        initialPath != null && targetPath != null && isDescendantFolderPath(initialPath, targetPath) -> FOLDER_NAVIGATION_FORWARD
        initialPath != null && targetPath != null && isDescendantFolderPath(targetPath, initialPath) -> FOLDER_NAVIGATION_BACKWARD
        else -> FOLDER_NAVIGATION_FORWARD
    }

private fun isDescendantFolderPath(ancestorPath: String, candidatePath: String): Boolean {
    val normalizedAncestor = ancestorPath.trimEnd(File.separatorChar)
    val normalizedCandidate = candidatePath.trimEnd(File.separatorChar)
    if (normalizedAncestor == normalizedCandidate) return false
    return normalizedCandidate.startsWith("$normalizedAncestor${File.separatorChar}")
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryFoldersTab(
    folders: ImmutableList<MusicFolder>,
    currentFolder: MusicFolder?,
    isLoading: Boolean,
    onNavigateBack: () -> Unit,
    onFolderClick: (String) -> Unit,
    onFolderAsPlaylistClick: (MusicFolder) -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    stablePlayerState: StablePlayerState,
    bottomBarHeight: Dp,
    extraBottomGap: Dp = ListExtraBottomGap,
    onMoreOptionsClick: (Song) -> Unit,
    isPlaylistView: Boolean = false,
    currentSortOption: SortOption = SortOption.FolderNameAZ,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    onSongLongPress: (Song) -> Unit = {},
    onSongSelectionToggle: (Song) -> Unit = {},
    getSelectionIndex: (String) -> Int? = { null },
    onLocateCurrentSongVisibilityChanged: (Boolean) -> Unit = {},
    onRegisterLocateCurrentSongAction: ((() -> Unit)?) -> Unit = {},
    pendingLocatePath: String? = null,
    onClearPendingLocate: () -> Unit = {},
    onRequestCrossFolderLocate: (String) -> Unit = {}
) {


    AnimatedContent(
        targetState = Pair(isPlaylistView, currentFolder?.path ?: FOLDER_NAVIGATION_ROOT_KEY),
        label = "FolderNavigation",
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val direction = resolveFolderNavigationDirection(
                initialPath = initialState.second.takeUnless { it == FOLDER_NAVIGATION_ROOT_KEY },
                targetPath = targetState.second.takeUnless { it == FOLDER_NAVIGATION_ROOT_KEY }
            )
            val slideIn = slideInHorizontally { width ->
                if (direction == FOLDER_NAVIGATION_FORWARD) width else -width
            } + fadeIn()
            val slideOut = slideOutHorizontally { width ->
                if (direction == FOLDER_NAVIGATION_FORWARD) -width else width
            } + fadeOut()

            slideIn.togetherWith(slideOut)
        }
    ) { (playlistMode, targetPath) ->
        val listState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()
        val visibilityCallback by rememberUpdatedState(onLocateCurrentSongVisibilityChanged)
        val registerActionCallback by rememberUpdatedState(onRegisterLocateCurrentSongAction)
        var lastHandledFolderSortKey by remember { mutableStateOf(currentSortOption.storageKey) }
        var pendingFolderSortScrollReset by remember { mutableStateOf(false) }

        val flattenedFolders = remember(folders, currentSortOption) {
            sortMusicFoldersByOption(flattenFolders(folders), currentSortOption)
        }

        val isRoot = targetPath == FOLDER_NAVIGATION_ROOT_KEY
        val activeFolder = if (isRoot) null else currentFolder
        val showPlaylistCards = playlistMode && activeFolder == null
        val itemsToShow = remember(activeFolder, folders, flattenedFolders, currentSortOption) {
            when {
                showPlaylistCards -> flattenedFolders
                activeFolder != null -> sortMusicFoldersByOption(activeFolder.subFolders, currentSortOption)
                else -> sortMusicFoldersByOption(folders, currentSortOption)
            }
        }.toImmutableList()

        val songsToShow = remember(activeFolder, currentSortOption) {
            sortSongsForFolderView(activeFolder?.songs ?: emptyList(), currentSortOption)
        }.toImmutableList()
        val currentSong = stablePlayerState.currentSong
        val currentSongId = currentSong?.id
        val currentSongIndexInSongs = remember(songsToShow, currentSongId) {
            currentSongId?.let { songId -> songsToShow.indexOfFirst { it.id == songId } } ?: -1
        }
        val currentSongListIndex = remember(itemsToShow.size, currentSongIndexInSongs) {
            if (currentSongIndexInSongs < 0) -1 else itemsToShow.size + currentSongIndexInSongs
        }
        val songInCurrentFolder = currentSongIndexInSongs >= 0
        val currentSongParentPath: String? = remember(currentSong?.path) {
            currentSong?.path
                ?.takeIf { it.startsWith("/") }
                ?.let { File(it).parentFile?.absolutePath }
        }
        val canCrossFolderLocate = remember(
            playlistMode,
            songInCurrentFolder,
            currentSongParentPath,
            currentFolder?.path
        ) {
            !playlistMode &&
                !songInCurrentFolder &&
                currentSongParentPath != null &&
                currentSongParentPath != currentFolder?.path
        }
        val locateCurrentSongAction: (() -> Unit)? = remember(
            songInCurrentFolder,
            canCrossFolderLocate,
            currentSongListIndex,
            listState,
            currentSongParentPath
        ) {
            when {
                songInCurrentFolder -> {
                    {
                        coroutineScope.launch {
                            listState.animateScrollToItem(currentSongListIndex)
                        }
                    }
                }
                canCrossFolderLocate && currentSongParentPath != null -> {
                    { onRequestCrossFolderLocate(currentSongParentPath) }
                }
                else -> null
            }
        }

        LaunchedEffect(locateCurrentSongAction) {
            registerActionCallback(locateCurrentSongAction)
        }

        LaunchedEffect(currentSortOption) {
            val currentSortKey = currentSortOption.storageKey
            if (currentSortKey == lastHandledFolderSortKey) return@LaunchedEffect
            lastHandledFolderSortKey = currentSortKey
            pendingFolderSortScrollReset = true
            listState.scrollToItem(0)
        }

        LaunchedEffect(itemsToShow, songsToShow, pendingFolderSortScrollReset) {
            if (!pendingFolderSortScrollReset) return@LaunchedEffect
            listState.scrollToItem(0)
            pendingFolderSortScrollReset = false
        }

        LaunchedEffect(
            currentFolder?.path,
            pendingLocatePath,
            currentSongListIndex,
            songsToShow
        ) {
            val pending = pendingLocatePath ?: return@LaunchedEffect
            if (currentFolder?.path != pending) return@LaunchedEffect
            if (currentSongListIndex < 0) return@LaunchedEffect
            listState.animateScrollToItem(currentSongListIndex)
            onClearPendingLocate()
        }

        LaunchedEffect(currentSongListIndex, itemsToShow, songsToShow, listState, canCrossFolderLocate) {
            if (canCrossFolderLocate) {
                visibilityCallback(true)
                return@LaunchedEffect
            }
            if (currentSongListIndex < 0 || songsToShow.isEmpty()) {
                visibilityCallback(false)
                return@LaunchedEffect
            }

            snapshotFlow {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty()) {
                    false
                } else {
                    currentSongListIndex in visibleItems.first().index..visibleItems.last().index
                }
            }
                .distinctUntilChanged()
                .collect { isVisible ->
                    visibilityCallback(!isVisible)
                }
        }

        DisposableEffect(Unit) {
            onDispose {
                visibilityCallback(false)
                registerActionCallback(null)
            }
        }

        val shouldShowLoading = isLoading && itemsToShow.isEmpty() && songsToShow.isEmpty() && isRoot

        Column(modifier = Modifier.fillMaxSize()) {
            when {
                shouldShowLoading -> {
                    // Static skeletons in bg/2; no spinner, no container.
                    Box(modifier = Modifier.fillMaxSize()) {
                        DapSkeletonList(count = 5, showArt = true)
                    }
                }

                itemsToShow.isEmpty() && songsToShow.isEmpty() -> {
                    LibraryExpressiveEmptyState(
                        tabId = LibraryTabId.FOLDERS,
                        storageFilter = StorageFilter.ALL,
                        bottomBarHeight = bottomBarHeight
                    )
                }

                else -> {
                    val foldersPullToRefreshState = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = onRefresh,
                        state = foldersPullToRefreshState,
                        modifier = Modifier.fillMaxSize(),
                        indicator = {
                            PullToRefreshDefaults.LoadingIndicator(
                                state = foldersPullToRefreshState,
                                isRefreshing = isRefreshing,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                modifier = Modifier
                                    .padding(start = 12.dp, end = if (listState.canScrollForward || listState.canScrollBackward) 22.dp else 12.dp)
                                    .fillMaxSize()
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
                                contentPadding = PaddingValues(
                                    bottom = bottomBarHeight + LocalDapPlayerShelfHeight.current + extraBottomGap,
                                    top = 0.dp                            )
                            ) {
                                if (showPlaylistCards) {
                                    items(itemsToShow, key = { it.path }, contentType = { "folder_card" }) { folder ->
                                        FolderPlaylistItem(
                                            folder = folder,
                                            onClick = { onFolderAsPlaylistClick(folder) }
                                        )
                                    }
                                } else {
                                    items(itemsToShow, key = { it.path }, contentType = { "folder_list" }) { folder ->
                                        FolderListItem(
                                            folder = folder,
                                            onClick = { onFolderClick(folder.path) }
                                        )
                                    }
                                }

                                items(songsToShow, key = { it.id }, contentType = { "song" }) { song ->
                                    EnhancedSongListItem(
                                        song = song,
                                        isPlaying = stablePlayerState.currentSong?.id == song.id && stablePlayerState.isPlaying,
                                        isCurrentSong = stablePlayerState.currentSong?.id == song.id,
                                        onMoreOptionsClick = { onMoreOptionsClick(song) },
                                        isSelected = selectedSongIds.contains(song.id),
                                        selectionIndex = if (isSelectionMode) getSelectionIndex(song.id) else null,
                                        isSelectionMode = isSelectionMode,
                                        onLongPress = { onSongLongPress(song) },
                                        onClick = {
                                            if (isSelectionMode) {
                                                onSongSelectionToggle(song)
                                            } else {
                                                onPlaySong(song, songsToShow)
                                            }
                                        }
                                    )
                                }
                            }

                            val bottomPadding = if (stablePlayerState.currentSong != null && stablePlayerState.currentSong != Song.emptySong())
                                bottomBarHeight + LocalDapPlayerShelfHeight.current + 16.dp
                            else
                                bottomBarHeight + 16.dp

                            ExpressiveScrollBar(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 4.dp, top = 16.dp, bottom = bottomPadding),
                                listState = listState
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
fun FolderPlaylistItem(folder: MusicFolder, onClick: () -> Unit) {
    val previewSongs = remember(folder) { folder.collectAllSongs().take(9).toImmutableList() }

    Column(Modifier.fillMaxWidth()) {
        DapAlbumRow(
            title = folder.name,
            subtitle = formatSongCountLabel(folder.totalSongCount),
            onClick = onClick,
            artwork = {
                PlaylistArtCollage(
                    songs = previewSongs,
                    modifier = Modifier.fillMaxSize()
                )
            }
        )
        DapDivider()
    }
}

@Composable
fun FolderListItem(folder: MusicFolder, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        DapAlbumRow(
            title = folder.name,
            subtitle = formatSongCountLabel(folder.totalSongCount),
            onClick = onClick,
            artwork = {
                DapArtworkPlaceholder(modifier = Modifier.fillMaxSize())
            }
        )
        DapDivider()
    }
}

private fun flattenFolders(folders: List<MusicFolder>): List<MusicFolder> {
    return folders.flatMap { folder ->
        val current = if (folder.songs.isNotEmpty()) listOf(folder) else emptyList()
        current + flattenFolders(folder.subFolders)
    }
}

private fun sortMusicFoldersByOption(folders: List<MusicFolder>, sortOption: SortOption): List<MusicFolder> {
    return when (sortOption) {
        SortOption.FolderNameAZ -> folders.sortedWith(
            compareBy<MusicFolder> { it.name.lowercase() }
                .thenBy { it.path }
        )
        SortOption.FolderNameZA -> folders.sortedWith(
            compareByDescending<MusicFolder> { it.name.lowercase() }
                .thenBy { it.path }
        )
        SortOption.FolderSongCountAsc -> folders.sortedWith(
            compareBy<MusicFolder> { it.totalSongCount }
                .thenBy { it.name.lowercase() }
                .thenBy { it.path }
        )
        SortOption.FolderSongCountDesc -> folders.sortedWith(
            compareByDescending<MusicFolder> { it.totalSongCount }
                .thenBy { it.name.lowercase() }
                .thenBy { it.path }
        )
        SortOption.FolderSubdirCountAsc -> folders.sortedWith(
            compareBy<MusicFolder> { it.totalSubFolderCount }
                .thenBy { it.name.lowercase() }
                .thenBy { it.path }
        )
        SortOption.FolderSubdirCountDesc -> folders.sortedWith(
            compareByDescending<MusicFolder> { it.totalSubFolderCount }
                .thenBy { it.name.lowercase() }
                .thenBy { it.path }
        )
        else -> folders.sortedWith(
            compareBy<MusicFolder> { it.name.lowercase() }
                .thenBy { it.path }
        )
    }
}

private fun sortSongsForFolderView(songs: List<Song>, sortOption: SortOption): List<Song> {
    return when (sortOption) {
        SortOption.FolderNameZA -> songs.sortedWith(
            compareByDescending<Song> { it.title.lowercase() }
                .thenBy { it.artist.lowercase() }
                .thenBy { it.id }
        )
        else -> songs.sortedWith(
            compareBy<Song> { it.title.lowercase() }
                .thenBy { it.artist.lowercase() }
                .thenBy { it.id }
        )
    }
}

private fun MusicFolder.collectAllSongs(): List<Song> {
    return songs + subFolders.flatMap { it.collectAllSongs() }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AlbumGridItemRedesigned(
    album: Album,
    albumColorSchemePairFlow: StateFlow<ColorSchemePair?>,
    onClick: () -> Unit,
    isLoading: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    onLongPress: () -> Unit = {},
    onSelectionToggle: () -> Unit = {}
) {
    // `albumColorSchemePairFlow` stays in the signature for its callers, but the grid cell paints
    // no artwork-derived tint: in this system the artwork is the only colour a cell carries.
    val accent = LocalDapAccent.current

    if (isLoading) {
        Column(Modifier.fillMaxWidth()) {
            DapArtworkPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                showGlyph = false
            )
            Spacer(Modifier.height(7.dp))
            SkeletonBar(fraction = 0.7f, height = 12.dp)
            Spacer(Modifier.height(6.dp))
            SkeletonBar(fraction = 0.45f, height = 10.dp)
        }
    } else {
        Box(Modifier.fillMaxWidth()) {
            DapAlbumGridItem(
                title = albumTitleLabel(album),
                // The artist is dropped rather than replaced when the album names none: the line
                // still has the track count to carry, and "Unknown album artist · 3 songs" would
                // spend the row's one glance on a fact that isn't one.
                subtitle = listOfNotNull(
                    albumArtistLabel(album),
                    formatSongCountLabel(album.songCount),
                ).joinToString(" · "),
                artwork = { AlbumArtwork(album.albumArtUriString, albumTitleLabel(album)) },
                onClick = {
                    if (isSelectionMode) {
                        onSelectionToggle()
                    } else {
                        onClick()
                    }
                },
                onLongClick = onLongPress
            )
            if (isSelectionMode && isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(DapSpacing.Sm)
                        .heightIn(min = 24.dp)
                        .background(
                            Dap.background.copy(alpha = 0.82f),
                            RoundedCornerShape(DapRadius.Surface)
                        )
                        .border(1.dp, accent, RoundedCornerShape(DapRadius.Surface))
                        .padding(horizontal = DapSpacing.Xs, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = selectionIndex?.toString() ?: "✓",
                        style = DapType.ValueSmall,
                        color = accent,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ArtistListItem(artist: Artist, onClick: () -> Unit, isLoading: Boolean = false) {
    if (isLoading) {
        DapSkeletonRow(showArt = true)
    } else {
        Column(Modifier.fillMaxWidth()) {
            DapArtistRow(
                title = artistNameLabel(artist),
                subtitle = formatSongCountLabel(artist.songCount),
                onClick = onClick,
                artwork = {
                    if (!artist.effectiveImageUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(artist.effectiveImageUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = artist.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    } else {
                        DapArtworkPlaceholder(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                            showGlyph = true
                        )
                    }
                }
            )
            DapDivider()
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AlbumListItem(
    album: Album,
    albumColorSchemePairFlow: StateFlow<ColorSchemePair?>,
    onClick: () -> Unit,
    isLoading: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    onLongPress: () -> Unit = {},
    onSelectionToggle: () -> Unit = {}
) {
    // `albumColorSchemePairFlow` stays in the signature for its callers; the row itself paints no
    // artwork-derived tint.
    val accent = LocalDapAccent.current

    if (isLoading) {
        DapSkeletonRow(showArt = true)
    } else {
        Column(Modifier.fillMaxWidth()) {
            DapAlbumRow(
                title = albumTitleLabel(album),
                subtitle = listOfNotNull(
                    albumArtistLabel(album),
                    formatSongCountLabel(album.songCount),
                ).joinToString(" · "),
                artwork = { AlbumArtwork(album.albumArtUriString, albumTitleLabel(album)) },
                trailing = if (isSelectionMode && isSelected) {
                    {
                        Text(
                            text = selectionIndex?.toString() ?: "✓",
                            style = DapType.ValueSmall,
                            color = accent,
                            maxLines = 1
                        )
                    }
                } else {
                    null
                },
                onClick = {
                    if (isSelectionMode) {
                        onSelectionToggle()
                    } else {
                        onClick()
                    }
                },
                onLongClick = onLongPress
            )
            DapDivider()
        }
    }
}

/** Square album art at whatever size the row gave it, with a static placeholder behind it. */
@Composable
private fun AlbumArtwork(uriString: String?, title: String) {
    if (uriString.isNullOrEmpty()) {
        DapArtworkPlaceholder(modifier = Modifier.fillMaxSize())
        return
    }
    var isLoadingImage by remember(uriString) { mutableStateOf(true) }
    SmartImage(
        model = uriString,
        contentDescription = stringResource(R.string.cd_album_art_for_title, title),
        contentScale = ContentScale.Crop,
        targetSize = Size(256, 256),
        modifier = Modifier.fillMaxSize(),
        onState = { state ->
            isLoadingImage = state is AsyncImagePainter.State.Loading
        }
    )
    if (isLoadingImage) {
        DapArtworkPlaceholder(modifier = Modifier.fillMaxSize(), showGlyph = false)
    }
}

/** A static skeleton line sized as a fraction of its row, so it never assumes a device width. */
@Composable
private fun SkeletonBar(fraction: Float, height: Dp) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(height)
            .clip(RoundedCornerShape(3.dp))
            .background(Dap.surfaceRaised)
    )
}

/**
 * The library action row, in the design system: one filled primary action (Shuffle, or New for
 * playlists), an optional Import beside it, and the mono text actions — Sort by, Locate current
 * song, and the storage filter — as words rather than icon discs.
 *
 * Every control wraps onto the next line instead of fighting for width, so a long translation or a
 * narrow screen reflows rather than clipping. The signature mirrors the Material row it replaces;
 * `iconRotation` is kept for callers that still animate one.
 */
/**
 * One row in the Library overflow. Plain words on bg/2, 48 dp tall, no icons and no fills.
 */
@Composable
private fun LibraryMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = DapType.CaptionMedium,
                color = Dap.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        onClick = onClick
    )
}

/**
 * The Library's contextual actions, floating over the list: Play at the left edge, level with
 * Shuffle; Shuffle at the right edge with Locate stacked above it.
 *
 * All three are the same tonal circle — the owner's choice: Play was added in exactly the shape
 * Shuffle already had. Locate is the one that comes and goes, so it sits above Shuffle and never
 * moves Shuffle — or Play, which is level with Shuffle.
 *
 * PHASE 14.0 still holds: none of this takes part in the list's layout. These used to be header
 * items, and Locate appearing pushed the header onto a second line and took 104 px off the list.
 */
@Composable
private fun LibraryFloatingActions(
    modifier: Modifier = Modifier,
    showPlay: Boolean,
    onPlayClick: () -> Unit,
    showShuffle: Boolean,
    onShuffleClick: () -> Unit,
    showLocate: Boolean,
    onLocateClick: () -> Unit,
) {
    if (!showPlay && !showShuffle && !showLocate) return
    // Bottom-aligned: Play shares its height with Shuffle, the one that is always there, so Locate
    // appearing above Shuffle never moves Play.
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (showPlay) {
            DapFloatingLibraryAction(
                icon = Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.play_playback),
                onClick = onPlayClick
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showLocate) {
                DapFloatingLibraryAction(
                    icon = Icons.Rounded.MyLocation,
                    contentDescription = stringResource(R.string.cd_locate_current_song),
                    onClick = onLocateClick
                )
            }
            if (showShuffle) {
                DapFloatingLibraryAction(
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = stringResource(R.string.cd_shuffle_play),
                    onClick = onShuffleClick
                )
            }
        }
    }
}

@Composable
private fun DapFloatingLibraryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Dap.surfaceRaised,
            contentColor = Dap.secondaryText
        ),
        modifier = Modifier.size(48.dp)
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

@Composable
private fun DapLibraryActionRow(
    onMainActionClick: () -> Unit,
    showSortButton: Boolean,
    currentSortOption: SortOption?,
    isPlaylistTab: Boolean,
    isFoldersTab: Boolean,
    modifier: Modifier = Modifier,
    onSortClick: () -> Unit = {},
    onLocateClick: () -> Unit = {},
    showLocateButton: Boolean = false,
    showImportButton: Boolean = true,
    onImportM3uClick: () -> Unit = {},
    currentFolder: MusicFolder? = null,
    folderRootPath: String = "",
    folderRootLabel: String = "",
    onFolderClick: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    showStorageFilterButton: Boolean = false,
    currentStorageFilter: StorageFilter = StorageFilter.ALL,
    onStorageFilterClick: () -> Unit = {},
) {
    val accent = LocalDapAccent.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DapSpacing.Gutter - DapSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs)
    ) {
        if (isFoldersTab) {
            DapFolderBreadcrumbs(
                currentFolder = currentFolder,
                rootPath = folderRootPath,
                rootLabel = folderRootLabel,
                onFolderClick = onFolderClick,
                onNavigateBack = onNavigateBack
            )
        } else {
            // One quiet line of content controls. Shuffle is a word here, not a filled button:
            // the list below it is the subject of the screen, not this row.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DapSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                // "Shuffle" here is a command — start shuffled playback of this tab — not a
                // toggle. Tinting it with the accent whenever global shuffle happened to be on
                // made it read as a switch that was already thrown, so tapping it looked like it
                // should turn shuffle off, which it never did. The accent on this row means "a
                // toggle is in a non-default state" (see the storage filter below); a one-shot
                // command does not qualify.
                // PHASE 14.0 — the tab's main action left this row.
                //
                // Shuffle used to be a text action here, and adding the fourth item ("Locate")
                // pushed this FlowRow onto a second line and took 104 px off the song list
                // (measured, LIBRARY_CONTROLS_RECON.md). Shuffle is now a floating action over the
                // list, so the row that remains cannot wrap. Playlists keep "New" here: it is
                // genuinely tab-specific and appears nowhere else.
                if (isPlaylistTab) {
                    DapTextAction(
                        label = stringResource(R.string.library_action_new),
                        onClick = onMainActionClick,
                        color = Dap.secondaryText
                    )
                }
                if (isPlaylistTab && showImportButton) {
                    DapTextAction(
                        label = stringResource(R.string.import_file),
                        onClick = onImportM3uClick
                    )
                }
                if (showSortButton) {
                    // The sort action names what is currently applied. It used to say only "Sort
                    // by", so the active key and direction were invisible until the sheet was
                    // opened — and after a change there was nothing on screen to confirm it took.
                    DapLibrarySortAction(
                        option = currentSortOption,
                        onClick = onSortClick,
                    )
                }
                // PHASE 14.0 — Locate left this row for a floating action (it is conditional, so
                // as a row item it changed the row's line count whenever it appeared), and the
                // storage filter was removed entirely: recon proved it selects the same rows in
                // both states, because every row in the database is local audio.
                //
                // `showLocateButton`, `showStorageFilterButton` and `currentStorageFilter` are
                // still accepted by this composable so no caller has to change; they are simply
                // no longer rendered here.
            }
        }
    }
}

/**
 * The folder path, as mono segments separated by chevrons, scrolling horizontally.
 *
 * The back chevron only exists when there is somewhere to go back to; at the root the slot is kept
 * so the first segment does not shift position.
 */
@Composable
private fun DapFolderBreadcrumbs(
    currentFolder: MusicFolder?,
    rootPath: String,
    rootLabel: String,
    onFolderClick: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val rowState = rememberLazyListState()
    val pathSegments = remember(currentFolder?.path, rootPath, rootLabel) {
        val path = currentFolder?.path ?: rootPath
        val normalizedRoot = rootPath.removeSuffix("/")
        val normalizedPath = path.removeSuffix("/")
        val relativePath = normalizedPath
            .removePrefix(normalizedRoot)
            .removePrefix("/")

        if (!normalizedPath.startsWith(normalizedRoot) || relativePath.isEmpty() || normalizedPath == normalizedRoot) {
            listOf(rootLabel to rootPath)
        } else {
            listOf(rootLabel to rootPath) + relativePath.split("/").scan("") { acc, segment ->
                "$acc/$segment"
            }.drop(1).map {
                val file = File(rootPath, it)
                file.name to file.path
            }
        }
    }

    LaunchedEffect(pathSegments.size) {
        if (pathSegments.isNotEmpty()) {
            rowState.animateScrollToItem(pathSegments.lastIndex)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (currentFolder != null) {
            DapBackButton(onClick = onNavigateBack)
        } else {
            Spacer(Modifier.width(DapSizes.MinTouchTarget))
        }

        LazyRow(
            state = rowState,
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(end = DapSpacing.Sm),
            modifier = Modifier.weight(1f)
        ) {
            items(pathSegments.size, key = { pathSegments[it].second }) { index ->
                val (name, path) = pathSegments[index]
                val isLast = index == pathSegments.lastIndex

                if (index > 0) {
                    DapIcon(
                        kind = DapIconKind.ChevronRight,
                        tint = Dap.disabledText,
                        size = 12.dp
                    )
                }

                Box(
                    modifier = Modifier
                        .heightIn(min = DapSizes.MinTouchTarget)
                        .clip(RoundedCornerShape(DapRadius.Surface))
                        .clickable(enabled = !isLast) { onFolderClick(path) }
                        .padding(horizontal = DapSpacing.Sm),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        style = DapType.RowSmall,
                        color = if (isLast) Dap.primaryText else Dap.tertiaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * The sort action, showing the active key and direction.
 *
 * The arrow is the direction, and it is present only when the option actually has one: several
 * sort keys are one-way by nature, and an arrow that cannot be flipped would be a lie.
 */
@Composable
private fun DapLibrarySortAction(
    option: SortOption?,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DapTextAction(
            // The row's method labels are resolved in the presentation layer: the enum in data/ is
            // English and is also what the sort logic compares against.
            label = option?.let { stringResource(it.methodLabelRes()) }
                ?: stringResource(R.string.presentation_batch_d_sort_by),
            onClick = onClick,
            // PHASE 14.0 — the header's only permanent control is a single text action, and at the
            // full 48 dp target it was mostly air: 38 px of text centred in a 96 px box. 40 dp
            // still centres the text, still spans the label's width, and gives the list back 8 px.
            minHeight = 40.dp,
        )
        option?.direction?.let { direction ->
            Text(
                text = if (direction == SortDirection.Ascending) "\u2191" else "\u2193",
                style = DapType.ValueSmall,
                color = Dap.tertiaryText,
                maxLines = 1,
            )
        }
    }
}
