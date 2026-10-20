package com.lostf1sh.pixelplayeross.presentation.components

import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapTonalRoundButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.PlayingEqIcon
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.utils.LocalAppHapticsConfig
import com.lostf1sh.pixelplayeross.presentation.utils.performAppCompatHapticFeedback
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import com.lostf1sh.pixelplayeross.presentation.components.scoped.QueueItemDismissGestureHandler
import androidx.compose.ui.zIndex
import coil.size.Size
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.RandomAccess
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlin.math.abs
import kotlinx.collections.immutable.ImmutableList
import com.lostf1sh.pixelplayeross.ui.ds.DapTopBar
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel

private data class QueueUndoBarProjection(
    val isVisible: Boolean = false,
    val removedSongTitle: String = ""
)

private fun PlayerUiState.toQueueUndoBarProjection(): QueueUndoBarProjection =
    QueueUndoBarProjection(
        isVisible = showQueueItemUndoBar,
        removedSongTitle = lastRemovedQueueSong?.title.orEmpty()
    )

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun QueueBottomSheet(
    viewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    queue: ImmutableList<Song>,
    currentQueueSourceName: String,
    currentSongId: String?,
    currentMediaItemIndex: Int = -1,
    isVisible: Boolean,
    isPlaying: Boolean,
    repeatMode: Int,
    isShuffleOn: Boolean,
    onDismiss: () -> Unit,
    onSongInfoClick: (Song, Int) -> Unit,
    onPlaySong: (Song, Int) -> Unit,
    onRemoveSong: (songId: String, queueIndex: Int) -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    activeTimerValueDisplay: androidx.compose.runtime.State<String?>,
    activeTimerDurationMinutes: androidx.compose.runtime.State<Int?>,
    playCount: androidx.compose.runtime.State<Float>,
    isEndOfTrackTimerActive: androidx.compose.runtime.State<Boolean>,
    onSetPredefinedTimer: (minutes: Int) -> Unit,
    onSetEndOfTrackTimer: (enable: Boolean) -> Unit,
    onOpenCustomTimePicker: () -> Unit,
    onCancelTimer: () -> Unit,
    onCancelCountedPlay: () -> Unit,
    onPlayCounter: (count: Int) -> Unit,
    onRequestSaveAsPlaylist: (
        songs: ImmutableList<Song>,
        defaultName: String,
        onConfirm: (String, Set<String>) -> Unit
    ) -> Unit,
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * B33-U1: how far the sheet is open, 0 (folded: only its top shows, as the player's "Up next" strip) to 1 (open), read in the draw phase.
     * The sheet's own header fades in as it opens ([QueueFold]); folded, a tap on the header opens the sheet and a drag moves it, like
     * everywhere else on the sheet. The default is "open", which is the sheet on its own.
     */
    foldProgress: () -> Float = { 1f },
    onOpenFolded: () -> Unit = {},
    tonalElevation: Dp = 10.dp,
    shape: RoundedCornerShape = RoundedCornerShape(topStart = DapRadius.SheetTop, topEnd = DapRadius.SheetTop),
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var showTimerOptions by rememberSaveable { mutableStateOf(false) }
    var showClearQueueDialog by remember { mutableStateOf(false) }
    var isFabExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            showTimerOptions = false
            showClearQueueDialog = false
            isFabExpanded = false
        }
    }

    BackHandler(enabled = isVisible && isFabExpanded) {
        isFabExpanded = false
    }

    val currentSongIndex = remember(queue, currentSongId, currentMediaItemIndex) {
        if (currentMediaItemIndex in queue.indices && queue[currentMediaItemIndex].id == currentSongId) {
            currentMediaItemIndex
        } else {
            queue.indexOfFirst { it.id == currentSongId }
        }
    }

    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val showQueueHistory = settingsState.showQueueHistory

    val queueIndexOffset = if (showQueueHistory || currentSongIndex < 0) 0 else currentSongIndex

    val displaySongs = remember(queue, queueIndexOffset) {
        if (queueIndexOffset == 0) {
            queue
        } else if (queue is RandomAccess) {
            queue.subList(queueIndexOffset, queue.size)
        } else {
            queue.drop(queueIndexOffset)
        }
    }

    val currentSongDisplayIndex = remember(currentSongIndex, queueIndexOffset) {
        if (currentSongIndex < 0) -1 else currentSongIndex - queueIndexOffset
    }

    val listState = rememberLazyListState()
    val queueCoroutineScope = rememberCoroutineScope()
    val displaySongCount = displaySongs.size

    var reorderPreviewOrder by remember { mutableStateOf<List<Int>?>(null) }
    var reorderPreviewKeys by remember { mutableStateOf<List<Long>?>(null) }
    var reorderPreviewBaseQueue by remember { mutableStateOf<List<Song>?>(null) }
    var pendingReorderExpectedIds by remember { mutableStateOf<List<String>?>(null) }
    var pendingReorderGraceUpdates by remember { mutableIntStateOf(0) }

    var committedDisplaySongIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var committedDisplayKeys by remember { mutableStateOf<List<Long>>(emptyList()) }
    var nextStableQueueItemKey by remember { mutableLongStateOf(0L) }

    var reorderPreviewQueueSignature by remember { mutableStateOf<Int?>(null) }
    val displaySongsSignature = remember(displaySongs, queueIndexOffset) {
        (queueIndexOffset * 31) + System.identityHashCode(displaySongs)
    }
    val activeKeys = reorderPreviewKeys
        ?: committedDisplayKeys.takeIf { it.size == displaySongCount }
    val activeSongSource = reorderPreviewBaseQueue ?: queue
    fun activeQueueIndexAt(index: Int): Int =
        reorderPreviewOrder?.getOrNull(index) ?: (queueIndexOffset + index)

    fun activeKeyAt(index: Int): Long =
        activeKeys?.getOrNull(index) ?: (queueIndexOffset + index).toLong()

    var lastMovedFrom by remember { mutableStateOf<Int?>(null) }
    var lastMovedTo by remember { mutableStateOf<Int?>(null) }
    var reorderHandleInUse by remember { mutableStateOf(false) }
    val updatedReorderHandleInUse by rememberUpdatedState(reorderHandleInUse)

    val shouldMapActiveKeys = reorderHandleInUse || reorderPreviewOrder != null
    val activeKeyToLocalIndex = remember(
        activeKeys,
        displaySongCount,
        queueIndexOffset,
        shouldMapActiveKeys
    ) {
        if (!shouldMapActiveKeys) {
            emptyMap()
        } else {
            HashMap<Long, Int>(displaySongCount).apply {
                for (index in 0 until displaySongCount) {
                    val stableKey = activeKeys?.getOrNull(index) ?: (queueIndexOffset + index).toLong()
                    put(stableKey, index)
                }
            }
        }
    }

    fun remapCommittedKeysForDisplay(newSongs: List<Song>) {
        if (committedDisplaySongIds.isNotEmpty() && newSongs.isNotEmpty()) {
            val firstNewId = newSongs.first().id
            val startIndex = committedDisplaySongIds.indexOf(firstNewId)
            if (startIndex >= 0 && startIndex + newSongs.size <= committedDisplaySongIds.size) {
                var suffixMatches = true
                for (i in newSongs.indices) {
                    if (committedDisplaySongIds[startIndex + i] != newSongs[i].id) {
                        suffixMatches = false
                        break
                    }
                }
                if (suffixMatches) {
                    committedDisplaySongIds = committedDisplaySongIds.subList(startIndex, startIndex + newSongs.size).toList()
                    committedDisplayKeys = committedDisplayKeys.subList(startIndex, startIndex + newSongs.size).toList()
                    return
                }
            }
        }

        val reusableKeysBySongId = mutableMapOf<String, ArrayDeque<Long>>()
        committedDisplaySongIds.forEachIndexed { index, songId ->
            val key = committedDisplayKeys.getOrNull(index) ?: return@forEachIndexed
            reusableKeysBySongId.getOrPut(songId) { ArrayDeque() }.addLast(key)
        }

        var nextKey = nextStableQueueItemKey
        if (committedDisplaySongIds.isEmpty() && committedDisplayKeys.isEmpty()) {
            nextKey = queueIndexOffset.toLong()
        }
        val newKeys = ArrayList<Long>(newSongs.size)
        newSongs.forEach { song ->
            val bucket = reusableKeysBySongId[song.id]
            val reusedKey = if (bucket != null && bucket.isNotEmpty()) bucket.removeFirst() else null
            if (reusedKey != null) {
                newKeys.add(reusedKey)
            } else {
                newKeys.add(nextKey)
                nextKey++
            }
        }

        committedDisplaySongIds = newSongs.map { it.id }
        committedDisplayKeys = newKeys
        nextStableQueueItemKey = nextKey
    }

    LaunchedEffect(displaySongsSignature, queueIndexOffset) {
        val expectedIds = pendingReorderExpectedIds

        if (expectedIds != null) {
            val currentDisplayIds = displaySongs.map { it.id }
            if (currentDisplayIds == expectedIds) {
                reorderPreviewKeys
                    ?.takeIf { it.size == displaySongs.size }
                    ?.let { previewKeys ->
                        committedDisplaySongIds = currentDisplayIds
                        committedDisplayKeys = previewKeys
                    }
                reorderPreviewOrder = null
                reorderPreviewKeys = null
                reorderPreviewBaseQueue = null
                pendingReorderExpectedIds = null
                pendingReorderGraceUpdates = 0
                remapCommittedKeysForDisplay(displaySongs)
                reorderPreviewQueueSignature = displaySongsSignature
                return@LaunchedEffect
            }

            if (reorderPreviewOrder != null && pendingReorderGraceUpdates > 0) {
                pendingReorderGraceUpdates -= 1
                reorderPreviewQueueSignature = displaySongsSignature
                return@LaunchedEffect
            }

            pendingReorderExpectedIds = null
            pendingReorderGraceUpdates = 0
            reorderPreviewOrder = null
            reorderPreviewKeys = null
            reorderPreviewBaseQueue = null
        }

        if (reorderPreviewQueueSignature != null && reorderPreviewQueueSignature != displaySongsSignature) {
            reorderPreviewOrder = null
            reorderPreviewKeys = null
            reorderPreviewBaseQueue = null
        }
        remapCommittedKeysForDisplay(displaySongs)
        reorderPreviewQueueSignature = displaySongsSignature
    }

    fun mapKeyToLocalIndex(key: Any?, keyToLocalIndex: Map<Long, Int>): Int? {
        val stableKey = key as? Long ?: return null
        keyToLocalIndex[stableKey]?.let { return it }
        activeKeys?.let { keys ->
            return keys.indexOf(stableKey).takeIf { it >= 0 }
        }
        val defaultIndex = (stableKey - queueIndexOffset).toInt()
        return defaultIndex.takeIf { it in 0 until displaySongCount }
    }

    val reorderableState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            if (reorderPreviewOrder == null) {
                reorderPreviewBaseQueue = queue
            }
            val currentOrder = reorderPreviewOrder
                ?: List(displaySongCount) { queueIndexOffset + it }
            val currentKeys = reorderPreviewKeys
                ?: List(displaySongCount) { activeKeyAt(it) }

            val fromLocalIndex = mapKeyToLocalIndex(from.key, activeKeyToLocalIndex) ?: return@rememberReorderableLazyListState
            val toLocalIndex = mapKeyToLocalIndex(to.key, activeKeyToLocalIndex) ?: return@rememberReorderableLazyListState
            if (fromLocalIndex == toLocalIndex) return@rememberReorderableLazyListState

            reorderPreviewOrder = currentOrder.toMutableList().apply {
                add(toLocalIndex, removeAt(fromLocalIndex))
            }
            reorderPreviewKeys = currentKeys.toMutableList().apply {
                add(toLocalIndex, removeAt(fromLocalIndex))
            }
            if (lastMovedFrom == null) {
                lastMovedFrom = fromLocalIndex
            }
            lastMovedTo = toLocalIndex
        },
    )
    val isReordering by remember {
        derivedStateOf { reorderableState.isAnyItemDragging }
    }
    val updatedIsReordering by rememberUpdatedState(isReordering)

    var isFirstScrollByCurrentSongId by remember(currentSongId) { mutableStateOf(true) }

    LaunchedEffect(currentSongId) {
        if (!isReordering && !reorderHandleInUse && currentSongDisplayIndex >= 0 && currentSongDisplayIndex < displaySongCount) {
            val firstVisible = listState.firstVisibleItemIndex
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0

            if (currentSongDisplayIndex !in firstVisible..lastVisible) {
                if (isFirstScrollByCurrentSongId || Math.abs(currentSongDisplayIndex - firstVisible) > 20) {
                    listState.scrollToItem(currentSongDisplayIndex)
                } else {
                    listState.animateScrollToItem(currentSongDisplayIndex)
                }
            }
            isFirstScrollByCurrentSongId = false
        }
    }

    val canDragSheetFromList by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    val updatedCanDragSheet by rememberUpdatedState(canDragSheetFromList)
    var draggingSheetFromList by remember { mutableStateOf(false) }
    var listDragAccumulated by remember { mutableStateOf(0f) }
    val view = LocalView.current
    val appHapticsConfig = LocalAppHapticsConfig.current

    val updatedFoldProgress by rememberUpdatedState(foldProgress)
    val updatedOnOpenFolded by rememberUpdatedState(onOpenFolded)
    val updatedOnQueueDragStart by rememberUpdatedState(onQueueDragStart)
    val updatedOnQueueDrag by rememberUpdatedState(onQueueDrag)
    val updatedOnQueueRelease by rememberUpdatedState(onQueueRelease)

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            val fromIndex = lastMovedFrom
            val toIndex = lastMovedTo

            lastMovedFrom = null
            lastMovedTo = null

            if (fromIndex != null && toIndex != null) {
                val fromQueueIndex = fromIndex + queueIndexOffset
                val toQueueIndex = toIndex + queueIndexOffset

                val fromWithinQueue = fromQueueIndex in queue.indices
                val toWithinQueue = toQueueIndex in queue.indices

                if (fromWithinQueue && toWithinQueue && fromQueueIndex != toQueueIndex) {
                    val previewBase = reorderPreviewBaseQueue ?: queue
                    val expectedIds = reorderPreviewOrder
                        ?.mapNotNull { previewBase.getOrNull(it)?.id }
                        ?.takeIf { it.size == displaySongCount }
                    pendingReorderExpectedIds = expectedIds
                    pendingReorderGraceUpdates = if (expectedIds != null) 6 else 0
                    onReorder(fromQueueIndex, toQueueIndex)
                    return@LaunchedEffect
                }
            }

            reorderPreviewOrder = null
            reorderPreviewKeys = null
            reorderPreviewBaseQueue = null
            pendingReorderExpectedIds = null
            pendingReorderGraceUpdates = 0
        }
    }

    val useLightweightQueueListShape by remember {
        derivedStateOf {
            listState.isScrollInProgress ||
                draggingSheetFromList ||
                isReordering ||
                reorderHandleInUse
        }
    }
    val queueListShape = remember(useLightweightQueueListShape) {
        if (useLightweightQueueListShape) {
            RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
        } else {
            AbsoluteSmoothCornerShape(
                cornerRadiusTR = 26.dp,
                smoothnessAsPercentTR = 60,
                cornerRadiusTL = 26.dp,
                smoothnessAsPercentTL = 60,
                cornerRadiusBR = 0.dp,
                smoothnessAsPercentBR = 60,
                cornerRadiusBL = 0.dp,
                smoothnessAsPercentBL = 60
            )
        }
    }

    fun finalizeListDrag(velocity: Float = 0f) {
        if (draggingSheetFromList) {
            updatedOnQueueRelease(listDragAccumulated, velocity)
            draggingSheetFromList = false
            listDragAccumulated = 0f
        }
    }

    val listDragConnection = remember(updatedCanDragSheet) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (updatedIsReordering || updatedReorderHandleInUse) return Offset.Zero

                if (draggingSheetFromList && available.y < 0f) {
                    finalizeListDrag()
                    return Offset.Zero
                }

                if (draggingSheetFromList) {
                    listDragAccumulated += available.y
                    updatedOnQueueDrag(available.y)
                    return available
                }

                if (available.y > 0 && updatedCanDragSheet) {
                    if (!draggingSheetFromList) {
                        draggingSheetFromList = true
                        listDragAccumulated = 0f
                        updatedOnQueueDragStart()
                    }
                    listDragAccumulated += available.y
                    updatedOnQueueDrag(available.y)
                    return Offset(0f, available.y)
                }

                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (updatedIsReordering || updatedReorderHandleInUse) return Velocity.Zero

                if (draggingSheetFromList && available.y < 0f) {
                    finalizeListDrag(available.y)
                    return Velocity.Zero
                }

                if (available.y > 0 && updatedCanDragSheet) {
                    if (!draggingSheetFromList) {
                        draggingSheetFromList = true
                        listDragAccumulated = 0f
                        updatedOnQueueDragStart()
                    }
                    updatedOnQueueRelease(listDragAccumulated, available.y)
                    draggingSheetFromList = false
                    listDragAccumulated = 0f
                    return available
                }
                return Velocity.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (updatedIsReordering || updatedReorderHandleInUse) return Offset.Zero

                if (draggingSheetFromList && source == NestedScrollSource.UserInput && available.y != 0f) {
                    listDragAccumulated += available.y
                    updatedOnQueueDrag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (updatedIsReordering || updatedReorderHandleInUse) return Velocity.Zero

                if (draggingSheetFromList) return available.also { finalizeListDrag(available.y) }
                return Velocity.Zero
            }
        }
    }

    val directSheetDragModifier =
        if (updatedIsReordering || updatedReorderHandleInUse) {
            Modifier
        } else {
            Modifier.pointerInput(updatedOnQueueDragStart, updatedOnQueueDrag, updatedOnQueueRelease) {
                var dragTotal = 0f
                val dragVelocityTracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = {
                        dragTotal = 0f
                        dragVelocityTracker.resetTracking()
                        updatedOnQueueDragStart()
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragTotal += dragAmount
                        dragVelocityTracker.addPosition(change.uptimeMillis, change.position)
                        updatedOnQueueDrag(dragAmount)
                    },
                    onDragEnd = {
                        val velocity = dragVelocityTracker.calculateVelocity().y
                        updatedOnQueueRelease(dragTotal, velocity)
                    },
                    onDragCancel = {
                        val velocity = dragVelocityTracker.calculateVelocity().y
                        updatedOnQueueRelease(dragTotal, velocity)
                    }
                )
            }
        }

    Surface(
        modifier = modifier,
        shape = shape,
        tonalElevation = 0.dp,
        // B22 (`Queue.dc.html`): the sheet is the design's surface; nothing tints it with elevation.
        color = Dap.surface,
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            val toolbarOccupiedHeight = QueueBottomBarHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Column(Modifier.fillMaxSize().padding(bottom = toolbarOccupiedHeight)) {
                val headerTopPadding = WindowInsets.statusBars
                    .asPaddingValues()
                    .calculateTopPadding() + 10.dp

                QueueHeaderSection(
                    isPlaying = isPlaying,
                    queueSourceName = currentQueueSourceName,
                    queueCount = displaySongCount,
                    topPadding = headerTopPadding,
                    onPrevious = { viewModel.previousSong() },
                    onPlayPause = { viewModel.playPause() },
                    onNext = { viewModel.nextSong() },
                    onLocateCurrentSong = {
                        if (QueueFold.isFolded(updatedFoldProgress())) {
                            // folded, the header is the strip the player shows: a tap on it opens the sheet
                            updatedOnOpenFolded()
                        } else if (currentSongDisplayIndex in 0..<displaySongCount) {
                            queueCoroutineScope.launch {
                                val firstVisible = listState.firstVisibleItemIndex
                                if (abs(currentSongDisplayIndex - firstVisible) > 20) {
                                    listState.scrollToItem(currentSongDisplayIndex)
                                } else {
                                    listState.animateScrollToItem(currentSongDisplayIndex)
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = QueueFold.contentAlpha(updatedFoldProgress()) }
                        .pointerInput(Unit) {
                            detectTapGestures { if (QueueFold.isFolded(updatedFoldProgress())) updatedOnOpenFolded() }
                        }
                        .then(directSheetDragModifier)
                )

                if (displaySongCount == 0) {
                    Box(
                        modifier         = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.presentation_batch_e_queue_empty),
                            color = colors.onSurface
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (isReordering || reorderHandleInUse) {
                                        Modifier
                                    } else {
                                        Modifier.nestedScroll(listDragConnection)
                                    }
                                ),
                            userScrollEnabled = !(isReordering || reorderHandleInUse),
                            // Rows carry their own hairline (B22): no gap, no container.
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                            // B32-Q4: no extra 8 dp at the start. The row already pads itself by 8 dp, which put the reorder glyph at 28 dp on the
                            // device (20 dp in the JVM render the L7 change was made from): its design margin is 20.
                            contentPadding = PaddingValues(
                                start = QueueListStartPadding,
                                end = if (listState.canScrollForward || listState.canScrollBackward) 26.dp else 8.dp,
                                bottom = 16.dp
                            )
                        ) {

                            items(
                                count = displaySongCount,
                                key = { index -> activeKeyAt(index) },
                                contentType = { "queue_song" }
                            ) { index ->
                                val queueIndex = activeQueueIndexAt(index)
                                if (queueIndex !in activeSongSource.indices) return@items
                                val itemStableKey = activeKeyAt(index)
                                val song = activeSongSource[queueIndex]
                                val canReorder = index > currentSongDisplayIndex
                                ReorderableItem(
                                    state = reorderableState,
                                    key = itemStableKey,
                                    enabled = canReorder,
                                    animateItemModifier = when {
                                        isReordering || reorderHandleInUse || reorderPreviewOrder != null -> Modifier.animateItem(
                                            placementSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                        else -> Modifier.animateItem(
                                            fadeInSpec = tween(durationMillis = 140),
                                            fadeOutSpec = tween(durationMillis = 120),
                                            placementSpec = tween(durationMillis = 180)
                                        )
                                    }
                                ) { isDragging ->
                                    val scale by animateFloatAsState(
                                        targetValue = if (isDragging) 1.015f else 1f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        ),
                                        label = "scaleAnimation"
                                    )

                                    QueuePlaylistSongItem(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 0.dp)
                                            .graphicsLayer {
                                                scaleX = scale
                                                scaleY = scale
                                            },
                                        onClick = { onPlaySong(song, queueIndex) },
                                        song = song,
                                        isCurrentSong = index == currentSongDisplayIndex,
                                        isPlaying = isPlaying && isVisible,
                                        isDragging = isDragging,
                                        onRemoveClick = { onRemoveSong(song.id, queueIndex) },
                                        isReorderModeEnabled = false,
                                        isDragHandleVisible = canReorder,
                                        isRemoveButtonVisible = false,
                                        enableSwipeToDismiss = canReorder,
                                        swipeStateIdentity = itemStableKey,
                                        onDismissSong = { onRemoveSong(song.id, queueIndex) },
                                        isFromPlaylist = true,
                                        onMoreOptionsClick = { onSongInfoClick(song, queueIndex) },
                                        dragHandle = {
                                            IconButton(
                                                onClick = {},
                                                modifier = Modifier
                                                    .draggableHandle(
                                                        onDragStarted = {
                                                            draggingSheetFromList = false
                                                            reorderHandleInUse = true
                                                            performAppCompatHapticFeedback(
                                                                view,
                                                                appHapticsConfig,
                                                                HapticFeedbackConstantsCompat.GESTURE_START
                                                            )
                                                        },
                                                        onDragStopped = {
                                                            reorderHandleInUse = false
                                                            performAppCompatHapticFeedback(
                                                                view,
                                                                appHapticsConfig,
                                                                HapticFeedbackConstantsCompat.GESTURE_END
                                                            )
                                                        }
                                                    )
                                                    .size(DapSizes.MinTouchTarget)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DragIndicator,
                                                    contentDescription = stringResource(R.string.presentation_batch_b_reorder_song),
                                                    tint = Dap.disabledText
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
                                    top = 24.dp,
                                    end = 14.dp,
                                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 14.dp
                                )
                        )
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                val fabSpacing = 16.dp
                val menuSpacing = 20.dp
                val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                val isTimerActiveDerived = remember {
                    derivedStateOf { activeTimerValueDisplay.value != null || playCount.value > 0f }
                }
                // The bottom bar (B22): shuffle, repeat and the timer on the left, the actions on the right, all of them
                // the same tonal round button the library floats its own on. They keep their own state and their own actions.
                QueueBottomBar(
                    isShuffleOn = isShuffleOn,
                    repeatMode = repeatMode,
                    isTimerActive = isTimerActiveDerived,
                    onToggleShuffle = onToggleShuffle,
                    onToggleRepeat = onToggleRepeat,
                    onTimerClick = { showTimerOptions = true },
                    onMoreClick = { isFabExpanded = !isFabExpanded },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .then(directSheetDragModifier),
                )

                AnimatedVisibility(
                    visible = isFabExpanded,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .zIndex(20f)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                isFabExpanded = false
                            }
                    )
                }

                AnimatedVisibility(
                    visible = isFabExpanded,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.surfaceContainerLowest
                                    )
                                )
                            )
                            .clickable {
                                isFabExpanded = !isFabExpanded
                            }
                            .zIndex(30f),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Column(
                            modifier = Modifier
                                .wrapContentWidth(Alignment.CenterHorizontally)
                                .padding(bottom = fabSpacing + menuSpacing),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (currentSongDisplayIndex >= 0 && currentSongDisplayIndex < displaySongCount) {
                                QueueToolbarMenuButton(
                                    text = stringResource(R.string.presentation_batch_e_action_locate_current_song),
                                    icon = Icons.Rounded.MyLocation,
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    onClick = {
                                        isFabExpanded = false
                                        queueCoroutineScope.launch {
                                            val firstVisible = listState.firstVisibleItemIndex
                                            if (Math.abs(currentSongDisplayIndex - firstVisible) > 20) {
                                                listState.scrollToItem(currentSongDisplayIndex)
                                            } else {
                                                listState.animateScrollToItem(currentSongDisplayIndex)
                                            }
                                        }
                                    }
                                )
                            }
                            QueueToolbarMenuButton(
                                text = stringResource(R.string.presentation_batch_e_action_clear_queue),
                                icon = Icons.Filled.ClearAll,
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                onClick = {
                                    isFabExpanded = false
                                    showClearQueueDialog = true
                                }
                            )
                            QueueToolbarMenuButton(
                                text = stringResource(R.string.presentation_batch_e_action_save_as_playlist),
                                icon = Icons.Filled.LibraryAdd,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                onClick = {
                                    isFabExpanded = false
                                    val res = context.resources
                                    val defaultName = if (currentQueueSourceName.isNotBlank()) {
                                        res.getString(
                                            R.string.presentation_batch_e_queue_named_suffix,
                                            currentQueueSourceName
                                        )
                                    } else {
                                        res.getString(R.string.presentation_batch_e_queue_current)
                                    }
                                    onRequestSaveAsPlaylist(
                                        queue,
                                        defaultName
                                    ) { name, selectedIds ->
                                        val orderedSelection = queue
                                            .filter { selectedIds.contains(it.id) }
                                            .map { it.id }
                                        if (orderedSelection.isNotEmpty()) {
                                            playlistViewModel.createPlaylist(
                                                name = name,
                                                songIds = orderedSelection,
                                                isQueueGenerated = true
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            val queueUndoBarState by remember(viewModel) {
                viewModel.playerUiState
                    .map { it.toQueueUndoBarProjection() }
                    .distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = QueueUndoBarProjection())
            AnimatedVisibility(
                visible = queueUndoBarState.isVisible,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp
                    )
                    .zIndex(50f),
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.inverseSurface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = queueUndoBarState.removedSongTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inverseOnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.presentation_batch_e_removed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inverseOnSurface.copy(alpha = 0.7f),
                        )
                        TextButton(
                            onClick = { viewModel.undoRemoveSongFromQueue() }
                        ) {
                            Text(
                                text = stringResource(R.string.action_undo),
                                color = colors.inversePrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (showTimerOptions) {
            TimerOptionsBottomSheet(
                onPlayCounter = onPlayCounter,
                activeTimerValueDisplay = activeTimerValueDisplay.value,
                activeTimerDurationMinutes = activeTimerDurationMinutes.value,
                playCount = playCount.value,
                isEndOfTrackTimerActive = isEndOfTrackTimerActive.value,
                onDismiss = { showTimerOptions = false },
                onSetPredefinedTimer = onSetPredefinedTimer,
                onSetEndOfTrackTimer = onSetEndOfTrackTimer,
                onOpenCustomTimePicker = onOpenCustomTimePicker,
                onCancelCountedPlay = onCancelCountedPlay,
                onCancelTimer = onCancelTimer
            )
        }

        if (showClearQueueDialog) {
            AlertDialog(
                onDismissRequest = { showClearQueueDialog = false },
                title = {
                    // An alert is a window of its own, so the activity's volume-key path does not reach
                    // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                    ModalInputBridge()
                    Text(stringResource(R.string.presentation_batch_e_clear_queue_dialog_title))
                },
                text = { Text(stringResource(R.string.presentation_batch_e_clear_queue_dialog_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onClearQueue()
                            showClearQueueDialog = false
                        }
                    ) {
                        Text(stringResource(R.string.presentation_batch_b_clear), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showClearQueueDialog = false }
                    ) {
                        Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueToolbarMenuButton(
    text: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .widthIn(min = 184.dp, max = 260.dp)
            .heightIn(min = 48.dp)
            .wrapContentWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        shape = RoundedCornerShape(DapRadius.Surface),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor
            )
            Text(
                text = text,
                style = DapType.Button,
                color = contentColor
            )
        }
    }
}

@Composable
private fun QueueHeaderSection(
    isPlaying: Boolean,
    queueSourceName: String,
    queueCount: Int,
    topPadding: Dp,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    colorScheme: ColorScheme? = null,
    onLocateCurrentSong: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 2.dp, bottom = 14.dp)
                .width(42.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.onSurface.copy(alpha = 0.14f))
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            QueueHeader(
                queueSourceName = queueSourceName,
                queueCount = queueCount,
                modifier = Modifier.fillMaxWidth(),
                onLocateCurrentSong = onLocateCurrentSong
            )
        }
    }
}

@Composable
internal fun QueueHeader(
    queueSourceName: String,
    queueCount: Int,
    modifier: Modifier = Modifier,
    onLocateCurrentSong: () -> Unit = {}
) {
    val view = LocalView.current
    val appHapticsConfig = LocalAppHapticsConfig.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs)
    ) {
        Text(
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                performAppCompatHapticFeedback(
                    view,
                    appHapticsConfig,
                    HapticFeedbackConstantsCompat.GESTURE_START
                )
                onLocateCurrentSong()
            },
            text = stringResource(R.string.queue_title_next),
            style = DapType.Title,
            color = Dap.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // "6 pistas · Búsqueda: mago": how many, and where the queue came from, in the mono voice.
        val count = pluralStringResource(R.plurals.queue_track_count, queueCount, queueCount)
        Text(
            text = when {
                queueCount <= 0 -> stringResource(R.string.presentation_batch_e_queue_subtitle_empty)
                queueSourceName.isNotBlank() -> stringResource(R.string.audio_row_join, count, queueSourceName)
                else -> count
            },
            style = DapType.ValueSmall,
            color = Dap.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The bar's height: a 56 dp disc with 16 dp above it and 24 dp below. */
private val QueueBottomBarHeight = 56.dp + 16.dp + 24.dp

/**
 * The queue's bottom bar (`Queue.dc.html`): shuffle, repeat, timer, and at the other end the actions.
 *
 * Shuffle and repeat are lit by the same rule as everywhere (accent and a dot); the timer is lit while one runs.
 */
@Composable
internal fun QueueBottomBar(
    isShuffleOn: Boolean,
    repeatMode: Int,
    isTimerActive: androidx.compose.runtime.State<Boolean>,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onTimerClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Dap.surface)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    ) {
        DapDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = DapSpacing.Gutter, end = DapSpacing.Gutter, top = DapSpacing.Lg, bottom = DapSpacing.SectionGap),
            horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DapTonalRoundButton(
                contentDescription = stringResource(R.string.presentation_batch_e_cd_toggle_shuffle),
                onClick = onToggleShuffle,
                active = isShuffleOn,
                stateDescription = stringResource(
                    if (isShuffleOn) R.string.lock_transport_state_on else R.string.lock_transport_state_off
                ),
            ) { tint -> DapIcon(kind = DapIconKind.Shuffle, tint = tint, size = 24.dp) }
            DapTonalRoundButton(
                contentDescription = stringResource(R.string.presentation_batch_e_cd_toggle_repeat),
                onClick = onToggleRepeat,
                active = repeatMode != Player.REPEAT_MODE_OFF,
                stateDescription = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> stringResource(R.string.lock_transport_repeat_one)
                    Player.REPEAT_MODE_ALL -> stringResource(R.string.lock_transport_repeat_all)
                    else -> stringResource(R.string.lock_transport_state_off)
                },
            ) { tint ->
                DapIcon(
                    kind = if (repeatMode == Player.REPEAT_MODE_ONE) DapIconKind.RepeatOne else DapIconKind.Repeat,
                    tint = tint,
                    size = 24.dp,
                )
            }
            DapTonalRoundButton(
                contentDescription = stringResource(R.string.presentation_batch_e_cd_sleep_timer),
                onClick = onTimerClick,
                active = isTimerActive.value,
            ) { tint ->
                Icon(imageVector = Icons.Rounded.Timer, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.weight(1f))
            DapTonalRoundButton(
                contentDescription = stringResource(R.string.presentation_batch_e_cd_queue_actions),
                onClick = onMoreClick,
            ) { tint ->
                Icon(imageVector = Icons.Rounded.MoreHoriz, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveQueueAsPlaylistSheet(
    songs: ImmutableList<Song>,
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Set<String>) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val animatedAlbumCornerRadius = 60.dp
    val albumShape = remember(animatedAlbumCornerRadius) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = animatedAlbumCornerRadius,
            smoothnessAsPercentTR = 60,
            cornerRadiusTR = animatedAlbumCornerRadius,
            smoothnessAsPercentBR = 60,
            cornerRadiusBL = animatedAlbumCornerRadius,
            smoothnessAsPercentBL = 60,
            cornerRadiusBR = animatedAlbumCornerRadius,
            smoothnessAsPercentTL = 60
        )
    }

    var playlistName by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(defaultName, selection = TextRange(defaultName.length)))
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val selectedSongIds = remember(songs) {
        mutableStateMapOf<String, Boolean>().apply {
            songs.forEach { put(it.id, true) }
        }
    }

    val filteredSongs = remember(searchQuery, songs) {
        if (searchQuery.isBlank()) songs
        else songs.filter {
            it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true)
        }
    }

    val hasSelection by remember {
        derivedStateOf { selectedSongIds.any { it.value } }
    }
    val allSelected by remember {
        derivedStateOf { selectedSongIds.isNotEmpty() && selectedSongIds.all { it.value } }
    }

    LaunchedEffect(Unit) {
        delay(250)
        focusRequester.requestFocus()
    }

    BackHandler(onBack = { onDismiss() })

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        // This window is its own surface, so the activity's volume-key path does not
        // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
        ModalInputBridge()
        Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    Column {
                        // Inventory of the bar this replaces: a title; a navigation Close
                        // (`onDismiss`, cd `cd_close`); and one trailing control that is a stateful
                        // select-all toggle — a 40 dp clickable Surface whose container/content
                        // colours and corner radius animate on `allSelected`, carrying a DoneAll /
                        // RemoveDone icon and a label that reads "Select all" or "Deselect all".
                        // Its click sets every `selectedSongIds` entry true, or false when already
                        // all selected.
                        //
                        // Re-hosted as a stateful text action: the label still carries both
                        // semantics and still changes with `allSelected`, and the accent marks the
                        // active state — the same vocabulary the Library storage filter uses. What
                        // goes is the Material expressiveness (the animated shape and container
                        // fill), not the state or either behaviour.
                        DapTopBar(
                            onBack = { onDismiss() },
                            actions = {
                                DapTextAction(
                                    label = if (allSelected) {
                                        stringResource(R.string.presentation_batch_e_deselect_all)
                                    } else {
                                        stringResource(R.string.presentation_batch_b_select_all)
                                    },
                                    onClick = {
                                        if (allSelected) {
                                            selectedSongIds.keys.forEach {
                                                selectedSongIds[it] = false
                                            }
                                        } else {
                                            selectedSongIds.keys.forEach {
                                                selectedSongIds[it] = true
                                            }
                                        }
                                    },
                                    color = if (allSelected) {
                                        LocalDapAccent.current
                                    } else {
                                        Dap.secondaryText
                                    },
                                )
                            },
                        )
                        DapScreenTitle(
                            text = stringResource(R.string.presentation_batch_e_save_as_playlist_sheet_title),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = playlistName,
                                keyboardActions = dapDismissKeyboardActions(),
                                textStyle = DapType.Body,
                                onValueChange = { playlistName = it },
                                label = { Text(stringResource(R.string.presentation_batch_e_playlist_name_label)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester).dapTextField(),
                                shape = RoundedCornerShape(DapRadius.Surface),
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                    unfocusedIndicatorColor = Color.Transparent,
                                )
                            )

                            OutlinedTextField(
                                value = searchQuery,
                                keyboardActions = dapDismissKeyboardActions(),
                                textStyle = DapType.Body,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(stringResource(R.string.presentation_batch_e_search_songs_to_include_placeholder)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = null
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(
                                                Icons.Filled.Clear,
                                                contentDescription = stringResource(R.string.cd_clear_search)
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().dapTextField(),
                                shape = RoundedCornerShape(DapRadius.Surface),
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                )
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                },
                bottomBar = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.ime)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(16.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 6.dp,
                            shadowElevation = 4.dp,
                            shape = RoundedCornerShape(DapRadius.Surface),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp)
                                ) {
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.presentation_batch_e_n_songs_selected,
                                            selectedSongIds.count { it.value },
                                            selectedSongIds.count { it.value }
                                        ),
                                        style = DapType.SettingLabel,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = if (playlistName.text.isNotBlank()) {
                                            stringResource(
                                                R.string.presentation_batch_e_save_as_format,
                                                playlistName.text
                                            )
                                        } else {
                                            stringResource(R.string.presentation_batch_e_enter_playlist_name)
                                        },
                                        style = DapType.Note,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                            alpha = 0.8f
                                        )
                                    )
                                }

                                Button(
                                    onClick = {
                                        if (hasSelection) {
                                            val finalName =
                                                playlistName.text.ifBlank { defaultName }
                                            val chosenIds = selectedSongIds
                                                .filterValues { it }
                                                .keys
                                            onConfirm(finalName, chosenIds)
                                            onDismiss()
                                        }
                                    },
                                    enabled = hasSelection,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(DapRadius.Surface),
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 20.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.action_save), style = DapType.Button)
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding()
                        )
                        .consumeWindowInsets(innerPadding)
                        .imePadding(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (filteredSongs.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = stringResource(
                                        R.string.presentation_batch_e_no_songs_match_query,
                                        searchQuery
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(filteredSongs, key = { it.id }) { song ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(CircleShape)
                                    .clickable {
                                        val currentSelection = selectedSongIds[song.id] ?: false
                                        selectedSongIds[song.id] = !currentSelection
                                    }
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                        shape = CircleShape
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selectedSongIds[song.id] ?: false,
                                    onCheckedChange = { isChecked ->
                                        selectedSongIds[song.id] = isChecked
                                    }
                                )
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            CircleShape
                                        )
                                ) {
                                    SmartImage(
                                        model = song.albumArtUriString,
                                        contentDescription = song.title,
                                        shape = albumShape,
                                        targetSize = Size(168, 168),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(songTitleLabel(song), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    // Only when the track names an artist, so an untagged queue
                                    // entry shows one line rather than a line of `<unknown>`.
                                    songArtistLabel(song)?.let { artistLabel ->
                                        Text(
                                            artistLabel,
                                            style = DapType.Note,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

/**
 * The gap between the reorder handle's glyph and the album thumbnail (B33-U1): [DapSpacing.Md], the DS's gap between a row's leading element
 * and its art. It is the handle's own 48 dp touch target around its 24 dp glyph (12 dp each side), so the target no longer overlaps the
 * artwork and the glyph still sits on the 20 dp content margin (8 dp row padding + 12 dp).
 */
internal val QueueHandleArtGap = DapSpacing.Md

/** The queue list's own padding at the start: none (it was 8 dp, on top of the row's own 8 dp); the rows are drawn from the screen's edge. */
internal val QueueListStartPadding = 0.dp

@Composable
fun QueuePlaylistSongItem(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    song: Song,
    isCurrentSong: Boolean,
    isPlaying: Boolean? = null,
    isDragging: Boolean,
    onRemoveClick: () -> Unit,
    dragHandle: @Composable () -> Unit,
    isReorderModeEnabled: Boolean,
    onMoreOptionsClick: (song: Song) -> Unit,
    isDragHandleVisible: Boolean,
    isRemoveButtonVisible: Boolean,
    enableSwipeToDismiss: Boolean = false,
    swipeStateIdentity: Long = 0L,
    onDismissSong: () -> Unit = {},
    isFromPlaylist: Boolean
) {
    val colors = MaterialTheme.colorScheme

    // Rows are flat. A queue entry is separated from the next by a hairline, not by a container,
    // and the row does not change shape when it becomes current.
    val itemShape = RoundedCornerShape(DapRadius.Row)
    val albumShape = RoundedCornerShape(DapRadius.Surface)

    val accent = LocalDapAccent.current
    val backgroundColor = Color.Transparent
    val hapticView = LocalView.current
    val appHapticsConfig = LocalAppHapticsConfig.current
    val dismissScope = rememberCoroutineScope()
    val dismissEnabled = enableSwipeToDismiss && !isDragging
    val density = LocalDensity.current

    val dismissOffsetAnimatable = remember(swipeStateIdentity) { Animatable(0f) }
    var itemWidthPx by remember { mutableStateOf(0f) }

    val dismissHandler = remember(swipeStateIdentity, dismissEnabled, itemWidthPx) {
        if (dismissEnabled && itemWidthPx > 0f) {
            QueueItemDismissGestureHandler(
                scope = dismissScope,
                density = density,
                hapticView = hapticView,
                appHapticsConfig = appHapticsConfig,
                offsetAnimatable = dismissOffsetAnimatable,
                itemWidthPx = itemWidthPx,
                onDismiss = onDismissSong
            )
        } else null
    }

    val isSwipeTargeted = dismissHandler?.isInDismissZone == true
    val currentOffsetPx = dismissOffsetAnimatable.value
    val revealWidthPx = (-currentOffsetPx).coerceAtLeast(0f)
    val revealProgress = if (density.density > 0f) {
        (revealWidthPx / (56.dp.value * density.density)).coerceIn(0f, 1f)
    } else 0f

    val dismissBackgroundColor by animateColorAsState(
        targetValue = if (isSwipeTargeted) colors.errorContainer else colors.errorContainer.copy(alpha = 0.82f),
        animationSpec = tween(durationMillis = 150),
        label = "dismissBackgroundColor"
    )
    val dismissIconAlpha by animateFloatAsState(
        targetValue = revealProgress * if (isSwipeTargeted) 1f else 0.88f,
        animationSpec = tween(durationMillis = 120),
        label = "dismissIconAlpha"
    )
    val dismissIconScale by animateFloatAsState(
        targetValue = if (isSwipeTargeted) 1.08f else 0.95f,
        animationSpec = tween(durationMillis = 120),
        label = "dismissIconScale"
    )

    var surfaceHeightPx by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val measuredWidth = coordinates.size.width.toFloat()
                if (measuredWidth != itemWidthPx) itemWidthPx = measuredWidth
            }
    ) {
        if (revealWidthPx > 0f && surfaceHeightPx > 0f) {
            val revealWidthDp = with(density) { revealWidthPx.toDp() }
            val surfaceHeightDp = with(density) { surfaceHeightPx.toDp() }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .height(surfaceHeightDp)
                    .width(revealWidthDp)
                    .clip(CircleShape)
                    .background(dismissBackgroundColor),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    painter = painterResource(R.drawable.rounded_close_24),
                    contentDescription = stringResource(R.string.presentation_batch_e_cd_dismiss_song),
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .graphicsLayer {
                            alpha = dismissIconAlpha
                            scaleX = dismissIconScale
                            scaleY = dismissIconScale
                        },
                    tint = colors.onErrorContainer
                )
            }
        }

        Surface(
            modifier = Modifier
                .graphicsLayer { translationX = currentOffsetPx }
                .onGloballyPositioned { coordinates ->
                    val h = coordinates.size.height.toFloat()
                    if (h != surfaceHeightPx) surfaceHeightPx = h
                }
                // B29-L7: 8 dp, so the handle's 48 dp touch area starts 8 dp in and its 24 dp glyph sits on the 20 dp content margin
                // (it sat at 28 dp, indented as if centred); the artwork follows the touch area directly, as in the design.
                .padding(horizontal = 8.dp)
                .clip(itemShape)
                .clickable(
                    enabled = currentOffsetPx == 0f
                ) {
                    onClick()
                },
            shape = itemShape,
            color = backgroundColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 72.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // A bounded leading slot keeps the handle at the left and titles aligned,
                // including the current row whose handle is disabled by the queue policy.
                //
                // B33-U1: the handle's slot is its whole 48 dp touch target and its 24 dp glyph sits on the 20 dp margin, so there is
                // [QueueHandleArtGap] (12 dp) of air between the glyph and the thumbnail. (B32 had the target overlap the artwork by 12 dp,
                // which left the glyph touching the thumbnail.) The title and artist still start 8 dp earlier than before B32: the list's
                // own extra 8 dp of start padding is gone.
                Box(
                    Modifier.size(DapSizes.MinTouchTarget),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDragHandleVisible) dragHandle()
                }

                val dismissGestureModifier = if (dismissEnabled && dismissHandler != null) {
                    Modifier.pointerInput(swipeStateIdentity, dismissHandler) {
                        detectHorizontalDragGestures(
                            onDragStart = { dismissHandler.onDragStart() },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dismissHandler.onHorizontalDrag(dragAmount)
                            },
                            onDragEnd = { dismissHandler.onDragEnd() },
                            onDragCancel = { dismissHandler.onDragCancel() }
                        )
                    }
                } else Modifier

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .then(dismissGestureModifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmartImage(
                        model = song.albumArtUriString,
                        shape = albumShape,
                        contentDescription = stringResource(R.string.cd_album_art_for_title, song.title),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(albumShape),
                        contentScale = ContentScale.Crop,
                        targetSize = SmartImageCompactListTargetSize
                    )

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        // The current track is the accent; the rest of the queue is the ordinary text.
                        Text(
                            songTitleLabel(song), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (isCurrentSong) accent else Dap.primaryText,
                            style = DapType.SettingLabel
                        )
                        songArtistLabel(song)?.let { artistLabel ->
                            Text(
                                artistLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = DapType.Body,
                                color = Dap.secondaryText
                            )
                        }
                    }

                    if (isCurrentSong && isPlaying != null) {
                        PlayingEqIcon(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(width = 18.dp, height = 16.dp),
                            color = accent,
                            isPlaying = isPlaying
                        )
                    }

                    if (isFromPlaylist) {
                        // Was `FilledIconButton(Modifier.size(36.dp).padding(end = 14.dp))`. The
                        // padding sat *after* the size, so it ate into the already-sized box rather
                        // than sitting outside it: the touch area collapsed to ~22 dp and drifted
                        // away from the dots it drew, which left most of the glyph sitting over the
                        // row's own play gesture. A miss did not just fail — it started playback.
                        //
                        // DapOverflowButton is the primitive every other list in the app already
                        // uses for this: a 48 dp target with an 18 dp glyph centred inside it. The
                        // 14 dp end padding is kept as spacing *outside* the target, so the visual
                        // rhythm against the reorder handle is unchanged.
                        DapOverflowButton(
                            onClick = { onMoreOptionsClick(song) },
                            description = stringResource(R.string.presentation_batch_e_more_options_for_song, song.title),
                            // The container used to carry the "this row is current" signal. With no
                            // container, the accent moves onto the glyph, which is how the rest of
                            // this system marks an active thing.
                            tint = if (isCurrentSong) accent else Dap.secondaryText,
                        )
                    }

                    AnimatedVisibility(visible = isRemoveButtonVisible && !enableSwipeToDismiss) {
                        FilledIconButton(
                            onClick = onRemoveClick,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = colors.surfaceContainer,
                                contentColor = colors.onSurface
                            ),
                            modifier = Modifier
                                .padding(start = 4.dp, end = 8.dp)
                                .size(DapSizes.MinTouchTarget)
                        ) {
                            Icon(
                                modifier = Modifier.size(18.dp),
                                painter = painterResource(R.drawable.rounded_close_24),
                                contentDescription = stringResource(R.string.presentation_batch_e_cd_remove_from_playlist),
                            )
                        }
                    }
                }
            }
            // The hairline starts where it always did (16 dp from the edge), whatever the row's own start is.
            DapDivider(Modifier.padding(start = 8.dp))
            }
        }
    }
}
