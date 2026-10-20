package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import com.lostf1sh.pixelplayeross.presentation.components.player.QueueFoldedStrip
import com.lostf1sh.pixelplayeross.presentation.components.player.upNextSong
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.scoped.rememberHeldWhile
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.roundToInt

/**
 * The queue's surface boundary.
 *
 * This exists to separate **the queue's composition policy** from the player scene. The scene says
 * whether the queue is *visible*; this surface decides whether the queue *exists in the
 * composition*, and renders it. Before this boundary the scene owned both, in
 * `UnifiedPlayerQueueLayer`'s three-clause expression, and the queue sheet's first mount landed
 * inside Now Playing's entry frame as a result.
 *
 * **The queue is a CORE RETAINED surface.** [composed] is true whenever a playback session exists,
 * not whenever the queue is on screen, so the sheet's cold mount is paid once during startup instead
 * of landing on the Now Playing entry frame. Phases 14.0X2–X4 measured that trade: retaining costs
 * +372 ms of startup frame time once and ~5 MB, removes a fresh mount from every player entry
 * (341 ms → 88 ms) and from every queue open, and produces **zero** frames while hidden and idle.
 * §21 of the reconnaissance records the closeout.
 *
 * This is an **exception justified by measurement, not a general player rule**. A surface may be
 * retained only if it is central to the product, bounded to one instance, expensive to mount,
 * bounded in retained memory, provably idle while hidden, bounded in hidden update cost, and used
 * in long-lived sessions. The queue satisfies all seven; Lyrics, Details and the visualizer do not.
 *
 * This surface owns nothing but rendering: no queue contents, no playback authority, no reorder
 * session. Those remain where they were — the queue is authoritative in the MediaController,
 * mirrored by `PlaybackStateHolder`, and the reorder preview stays inside `QueueBottomSheet`.
 *
 * Height: because the sheet is composed from the moment a session exists, its height is measured by
 * that one boot composition. Nothing force-composes it to learn its size, and the
 * `queueSheetHeightPx == 0f` clause that used to exist for exactly that reason is gone.
 */
@Composable
internal fun QueueSurface(
    /**
     * Whether the queue UI exists in the composition at all. True for as long as a playback
     * session exists — see the retention note above. Deliberately not tied to [visible].
     */
    composed: Boolean,
    /** Whether the queue is on screen. False for a retained-but-hidden sheet. */
    visible: Boolean,
    playerViewModel: PlayerViewModel,
    albumColorScheme: ColorScheme,
    queueScrimAlpha: Float,
    queueSheetOffset: Animatable<Float, AnimationVector1D>,
    /** Where the sheet rests when it is not open (its folded position), in px; 0 until the sheet has been measured. */
    queueHiddenOffsetPx: Float,
    queueSheetHeightPx: Float,
    onQueueSheetHeightPxChange: (Float) -> Unit,
    /** B33-U1: how much of the sheet's top shows at rest (its folded state), in px; 0 when it has none (the landscape player). */
    foldedStripPx: Float = 0f,
    /** Opens the sheet: the folded state's accessibility action. */
    onOpenQueue: () -> Unit = {},
    configurationResetKey: Any,
    /** B29-fix1 F7: the cover's accent (null with no cover, or the player colour set to neutral): what the current row wears. */
    albumAccent: Color? = null,
    currentPlaybackQueue: ImmutableList<Song>,
    currentQueueSourceName: String,
    currentMediaItemIndex: Int,
    infrequentPlayerState: StablePlayerState,
    activeTimerValueDisplay: State<String?>,
    activeTimerDurationMinutes: State<Int?>,
    playCount: State<Float>,
    isEndOfTrackTimerActive: State<Boolean>,
    onDismissQueue: () -> Unit,
    onSongInfoClick: (Song, Int) -> Unit,
    onPlaySong: (Song, Int) -> Unit,
    onRemoveSong: (songId: String, queueIndex: Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    onSetPredefinedTimer: (Int) -> Unit,
    onSetEndOfTrackTimer: (Boolean) -> Unit,
    onOpenCustomTimePicker: () -> Unit,
    onCancelTimer: () -> Unit,
    onCancelCountedPlay: () -> Unit,
    onPlayCounter: (Int) -> Unit,
    onRequestSaveAsPlaylist: (List<Song>, String, (String, Set<String>) -> Unit) -> Unit,
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit,
    /** What the sheet reads besides the player: injected in the app, handed in by the JVM tests (which have no Hilt). */
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    // The sheet's height, as it was last measured for this configuration (B33-U1). It is reported **from here** (not from the layout callback),
    // so that the order the first layout and the effects run in decides nothing: the folded state needs the height at boot, because the offset
    // it rests at is the height less the strip. A new configuration starts unmeasured (0) and is measured again by its own layout.
    val measuredHeightPx = remember(configurationResetKey) { mutableFloatStateOf(0f) }
    LaunchedEffect(measuredHeightPx) {
        snapshotFlow { measuredHeightPx.floatValue }.collect { onQueueSheetHeightPxChange(it) }
    }

    val song = rememberQueueSheetSong(
        visible = visible,
        currentSongId = infrequentPlayerState.currentSong?.id,
        currentMediaItemIndex = currentMediaItemIndex,
        isPlaying = infrequentPlayerState.isPlaying,
        repeatMode = infrequentPlayerState.repeatMode,
        isShuffleOn = infrequentPlayerState.isShuffleEnabled,
    )

    // B33-U1: the folded state. The sheet is one object at one offset (the controller's); at rest it is drawn with its top showing, and that top
    // is the "Up next" strip of the player. How open it is comes from that one offset, read where it is drawn.
    val folded = foldedStripPx > 0f
    val hiddenOffsetState = rememberUpdatedState(queueHiddenOffsetPx)
    val foldProgress: () -> Float = remember(queueSheetOffset, folded) {
        // a sheet with no folded state (the landscape player) is just the sheet: always "open" to its own header
        if (folded) ({ QueueFold.progress(queueSheetOffset.value, hiddenOffsetState.value) }) else ({ 1f })
    }
    val nextSong = remember(currentPlaybackQueue, infrequentPlayerState.currentMediaItemIndex, infrequentPlayerState.repeatMode) {
        upNextSong(currentPlaybackQueue, infrequentPlayerState.currentMediaItemIndex, infrequentPlayerState.repeatMode)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (queueScrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(0f)
                    .graphicsLayer { alpha = queueScrimAlpha }
                    .background(MaterialTheme.colorScheme.scrim)
            )
        }

        if (composed) {
            // First open has no hidden offset to animate from (see the height note above).
            LaunchedEffect(visible, queueSheetHeightPx) {
                if (visible && queueSheetHeightPx == 0f) queueSheetOffset.snapTo(0f)
            }

            // B29-fix1 F7: the sheet is a sibling of the full player, not inside it, so the cover's accent that the player wears
            // (B29-L11) did not reach it: its round buttons took the cover's colour from the Material scheme above while the current
            // row's title and playing mark kept the app's accent. Same value, same rule as the player's: the cover's, held while the
            // sheet is hidden (it is retained composed, and a song change must not recompose its rows), the app's with no cover.
            val appAccent = LocalDapAccent.current
            val queueAccent = rememberHeldWhile(!visible, albumAccent) ?: appAccent
            MaterialTheme(
                colorScheme = albumColorScheme,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes
            ) {
                CompositionLocalProvider(LocalDapAccent provides queueAccent) {
                  Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, queueSheetOffset.value.roundToInt()) }
                        .graphicsLayer {
                            alpha = if (visible || folded) 1f else 0f
                        }
                        .onGloballyPositioned { coordinates -> measuredHeightPx.floatValue = coordinates.size.height.toFloat() }
                  ) {
                    QueueBottomSheet(
                        viewModel = playerViewModel,
                        playlistViewModel = playlistViewModel,
                        settingsViewModel = settingsViewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            // folded, only the strip below is for a screen reader; the rows under it are off-screen
                            .then(if (folded && !visible) Modifier.semantics { hideFromAccessibility() } else Modifier),
                        foldProgress = foldProgress,
                        onOpenFolded = onOpenQueue,
                        queue = currentPlaybackQueue,
                        currentQueueSourceName = currentQueueSourceName,
                        currentSongId = song.currentSongId,
                        currentMediaItemIndex = song.currentMediaItemIndex,
                        isVisible = visible,
                        isPlaying = song.isPlaying,
                        onDismiss = onDismissQueue,
                        onSongInfoClick = onSongInfoClick,
                        onPlaySong = onPlaySong,
                        onRemoveSong = onRemoveSong,
                        onReorder = onReorder,
                        repeatMode = song.repeatMode,
                        isShuffleOn = song.isShuffleOn,
                        onToggleRepeat = onToggleRepeat,
                        onToggleShuffle = onToggleShuffle,
                        onClearQueue = onClearQueue,
                        activeTimerValueDisplay = activeTimerValueDisplay,
                        activeTimerDurationMinutes = activeTimerDurationMinutes,
                        playCount = playCount,
                        isEndOfTrackTimerActive = isEndOfTrackTimerActive,
                        onSetPredefinedTimer = onSetPredefinedTimer,
                        onSetEndOfTrackTimer = onSetEndOfTrackTimer,
                        onOpenCustomTimePicker = onOpenCustomTimePicker,
                        onCancelTimer = onCancelTimer,
                        onCancelCountedPlay = onCancelCountedPlay,
                        onPlayCounter = onPlayCounter,
                        onRequestSaveAsPlaylist = onRequestSaveAsPlaylist,
                        onQueueDragStart = onQueueDragStart,
                        onQueueDrag = onQueueDrag,
                        onQueueRelease = onQueueRelease
                    )
                    if (folded) {
                        QueueFoldedStrip(
                            next = nextSong,
                            interactive = !visible,
                            onOpen = onOpenQueue,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .graphicsLayer { alpha = QueueFold.stripAlpha(foldProgress()) },
                        )
                    }
                  }
                }
            }
        }
    }
}

/**
 * How the queue sheet's folded state and its open state trade places as one object moves between them (B33-U1). Pure, so it can be pinned.
 * [progress] is 0 folded and 1 open, from the sheet's one offset; the strip the player shows is there while the sheet is nearly folded and
 * the sheet's own header takes over as it opens, so neither is drawn over the other.
 */
internal object QueueFold {
    /** Below this progress the sheet counts as folded: a tap on its header opens it. */
    const val FOLDED_BELOW = 0.5f

    private const val STRIP_FADES_OUT_BY = 0.4f
    private const val HEADER_FADES_IN_FROM = 0.35f
    private const val HEADER_FADES_IN_OVER = 0.4f

    fun progress(offsetPx: Float, closedOffsetPx: Float): Float =
        if (closedOffsetPx <= 0f) 1f else (1f - offsetPx / closedOffsetPx).coerceIn(0f, 1f)

    fun isFolded(progress: Float): Boolean = progress < FOLDED_BELOW

    fun stripAlpha(progress: Float): Float = (1f - progress / STRIP_FADES_OUT_BY).coerceIn(0f, 1f)

    fun contentAlpha(progress: Float): Float = ((progress - HEADER_FADES_IN_FROM) / HEADER_FADES_IN_OVER).coerceIn(0f, 1f)
}

/** What the queue sheet is told about the playing song (B23-P3). */
internal data class QueueSheetSong(
    val currentSongId: String?,
    val currentMediaItemIndex: Int,
    val isPlaying: Boolean,
    val repeatMode: Int,
    val isShuffleOn: Boolean,
)

/**
 * B23-P3: what the sheet knows about *the current song* does not change while it is closed. The queue is retained composed (see
 * [QueueSurface]), and with the history off its rows are the suffix that starts at the current song, so every song change used to
 * re-reconcile its occurrence keys (O(queue): a map over thousands of ids on a backward jump) and scroll its hidden list, for a sheet
 * nobody could see. Held, the sheet is skipped; it still follows the queue itself (a revision reconciles once, as before). The live
 * song flows in the moment the sheet opens (in the same recomposition that makes it visible) and it catches up then: its rows start
 * at the current song, its list scrolled there.
 */
@Composable
internal fun rememberQueueSheetSong(
    visible: Boolean,
    currentSongId: String?,
    currentMediaItemIndex: Int,
    isPlaying: Boolean,
    repeatMode: Int,
    isShuffleOn: Boolean,
): QueueSheetSong = rememberHeldWhile(
    held = !visible,
    value = QueueSheetSong(currentSongId, currentMediaItemIndex, isPlaying, repeatMode, isShuffleOn),
)
