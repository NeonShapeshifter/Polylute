package com.lostf1sh.pixelplayeross.presentation.lockscreen

import android.content.res.Resources
import android.provider.Settings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackProgressRates
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapButton
import com.lostf1sh.pixelplayeross.ui.ds.DapButtonVariant
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapMotion
import com.lostf1sh.pixelplayeross.ui.ds.DapPlayDisc
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.layoutId
import androidx.compose.runtime.rememberUpdatedState
import com.lostf1sh.pixelplayeross.ui.ds.DapOutputLine
import com.lostf1sh.pixelplayeross.ui.ds.DapStateIconButton
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import timber.log.Timber

/** The cover, as the design sets it (`LockScreen.dc.html`): 216 dp on the HONOR. It shrinks on a shorter screen, never grows. */
private val LockArtworkSize = 216.dp

/**
 * The DAP lock surface: a playback face for a dedicated player.
 *
 * Deliberately not a home screen. It shows the time, what is playing and where the sound is going,
 * lets you control playback, and offers one obvious way back into the app. There is no library, no
 * settings, no queue and no song actions — a lock screen that can do everything is just the app
 * with a different background.
 *
 * Two blocks: the time at the top, where the eye lands when the screen comes on, and the playback
 * block at the bottom, where the thumb already is.
 *
 * **It owns no playback state.** Every value here is passed in from the same authority every other
 * transport surface reads, and every control calls the same callback the docked player calls. There
 * is no lock-screen player, no lock-screen shuffle flag and no lock-screen position.
 *
 * **Progress is read-only.** A pocket brush on a lock screen must not move the song; changing the
 * position is the player's job.
 *
 * This is not security. It is not Android's lock screen and does not pretend to be; see
 * [DapLockScreenPolicy] for the rule that keeps the real Keyguard in charge whenever it is present.
 *
 * B22: the look is `LockScreen.dc.html`. [onRevealingChange] says when the surface has been moved off the app
 * (a swipe, or the way out being taken), so whoever holds the app under it can show it while it slides away.
 */
@Composable
fun DapLockScreen(
    song: Song,
    isPlaying: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    currentPositionProvider: () -> Long,
    totalDuration: Long,
    /** Where the sound is going: "SNOWSKY Melody · USB Direct · 24 bits · 48 kHz". */
    outputLine: String?,
    batteryPercent: Int?,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The signal reaches the DAC untouched (the filled mark before the output line). */
    outputUntouched: Boolean = false,
    onRevealingChange: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // The surface follows the finger (Direct), and a release carries the finger's speed to rest
    // (settle): out past the threshold, back into place short of it. It used to stay still under
    // the finger and then cut to the app, with no sign of how far was far enough.
    val offset = remember { LockSurfaceOffset() }
    var surfaceHeight by remember { mutableFloatStateOf(0f) }
    val velocityTracker = remember { VelocityTracker() }
    val finger = remember { FingerTrace() }
    val leave: (initialVelocity: Float, fromGesture: Boolean) -> Unit = { velocity, fromGesture ->
        offset.settle(
            scope = scope,
            target = -surfaceHeight,
            velocity = velocity,
            spec = if (fromGesture) DapMotion.settle() else DapMotion.surfaceExit(),
            then = onDismiss,
        )
    }

    // The surface is off the app from the first pixel it moves until it is back at rest: that is when what is under it
    // must be there to be seen.
    val latestRevealingChange by rememberUpdatedState(onRevealingChange)
    LaunchedEffect(offset) {
        snapshotFlow { offset.px < 0f }.distinctUntilChanged().collect { latestRevealingChange(it) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { surfaceHeight = it.height.toFloat() }
            // B25-S2: the finger's speed is measured from the touch-down, through the slop and into the drag. The drag
            // detector below reports only what comes after the slop, so a short flick handed the tracker two or three
            // points (or none) and read as slow. This sees every event of the gesture first (Initial pass) and consumes none.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val tracked = event.changes.firstOrNull() ?: continue
                        if (event.changes.size == 1) {
                            if (tracked.changedToDownIgnoreConsumed()) {
                                velocityTracker.resetTracking()
                                finger.onDown(tracked.position.y, tracked.uptimeMillis)
                            }
                            tracked.historical.forEach { velocityTracker.addPosition(it.uptimeMillis, it.position) }
                            velocityTracker.addPosition(tracked.uptimeMillis, tracked.position)
                            // B29-fix1: the lift's own position. The drag detector below throws away the movement an UP event
                            // carries (it ends the drag instead of reporting it), so this is the only place that sees it.
                            if (tracked.changedToUpIgnoreConsumed()) finger.onLift(tracked.position.y, tracked.uptimeMillis)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    // Upward only: a downward drag holds the surface at rest, so a pocket brush or
                    // a mis-aimed tap cannot move it off.
                    onVerticalDrag = { change, amount ->
                        finger.onDrag(change.position.y)
                        offset.drag(amount)
                    },
                    onDragEnd = {
                        // B29-fix1: the release is decided on what the finger did, not on which of its events reached the drag.
                        // `adb shell input swipe` hands each event over and waits for the app to finish it; when the first frame of
                        // the gesture is long (the reveal) it wakes with its clock past the end of the swipe and sends the lift,
                        // at the swipe's end point, as the very next thing. The surface was then 40 dp up with nothing between.
                        val liftedPastTheDrag = finger.unreportedDy()
                        if (liftedPastTheDrag != 0f) offset.drag(liftedPastTheDrag)
                        val tracked = velocityTracker.calculateVelocity().y
                        val velocity = minOf(tracked, finger.averageVelocityY())
                        val travelDp = with(density) { (-offset.px).toDp().value }
                        val upwardDpPerS = with(density) { (-velocity).toDp().value }
                        val open = LockDismissPolicy.shouldOpen(travelDp, upwardDpPerS)
                        Timber.tag(LOCK_LOG_TAG).i(
                            "release open=%s travel=%.0fdp speed=%.0fdp/s (tracker %.0f, gesture %.0f, lift past drag %.0fpx)",
                            open, travelDp, upwardDpPerS, with(density) { (-tracked).toDp().value },
                            with(density) { (-finger.averageVelocityY()).toDp().value }, liftedPastTheDrag,
                        )
                        if (open) {
                            leave(velocity, true)
                        } else {
                            offset.settle(scope, target = 0f, velocity = velocity, spec = DapMotion.settle())
                        }
                    },
                    onDragCancel = { offset.settle(scope, target = 0f, velocity = 0f, spec = DapMotion.settle()) },
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = offset.px }
                .background(Dap.background)
        ) {
            LockLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = DapSpacing.Gutter),
                top = {
                    // The design puts the clock 72 dp from the top of the screen; the status bar is the first of them.
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(DapSpacing.Xl))
                        LockClock()
                    }
                },
                cover = { LockArtwork(song) },
                middle = {
                    Column(Modifier.fillMaxWidth()) {
                        NowPlayingText(song)
                        Spacer(Modifier.height(DapSpacing.Lg + DapSpacing.Xs))
                        LockProgress(
                            isPlaying = isPlaying,
                            songId = song.id,
                            currentPositionProvider = currentPositionProvider,
                            totalDuration = totalDuration,
                        )
                        Spacer(Modifier.height(DapSpacing.Lg))
                        LockTransportRow(
                            isPlaying = isPlaying,
                            isShuffleEnabled = isShuffleEnabled,
                            repeatMode = repeatMode,
                            onPrevious = onPrevious,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            onToggleShuffle = onToggleShuffle,
                            onToggleRepeat = onToggleRepeat,
                        )
                    }
                },
                bottom = {
                    Column(Modifier.fillMaxWidth()) {
                        SignalReadout(outputLine = outputLine, untouched = outputUntouched, batteryPercent = batteryPercent)
                        Spacer(Modifier.height(DapSpacing.Md))
                        DismissAffordance(onOpen = { leave(0f, false) })
                        Spacer(Modifier.height(DapSpacing.Sm))
                    }
                },
            )
        }
    }
}

/**
 * Where the lock surface is, in px: 0 at rest, negative once it has been carried up.
 *
 * B29-L1: one plain value, written **in the pointer callback itself**, with no coroutine between the finger and the value.
 * The surface used to keep it in an `Animatable` and move it with `scope.launch { snapTo(...) }` once per drag event. A
 * coroutine launched on the UI dispatcher runs after the message that launched it, and the release is handled in the same
 * message as the last batch of moves (and, when the main thread was busy, in the same message as the whole gesture), so
 * `onDragEnd` read a travel that lacked the last frame or all of it, and refused flicks that were plainly long enough
 * (`adb input swipe` of 450 px in 100 ms opened one time in three). The release animation writes the same value, frame by
 * frame; a new touch stops it where it is.
 */
@Stable
internal class LockSurfaceOffset {
    var px by mutableFloatStateOf(0f)
        private set

    private var running: Job? = null

    /** The finger moved by [amountPx]: up carries the surface, down never moves it past rest. */
    fun drag(amountPx: Float) {
        stop()
        px = (px + amountPx).coerceAtMost(0f)
    }

    /** Carries the surface from where it is to [target], keeping the finger's [velocity]; [then] runs if it gets there. */
    fun settle(scope: CoroutineScope, target: Float, velocity: Float, spec: AnimationSpec<Float>, then: () -> Unit = {}) {
        stop()
        running = scope.launch {
            animate(initialValue = px, targetValue = target, initialVelocity = velocity, animationSpec = spec) { value, _ -> px = value }
            then()
        }
    }

    fun stop() {
        running?.cancel()
        running = null
    }
}

/**
 * What one finger did, as the surface needs it at the release (B29-fix1): where and when it went down, where its last drag event was,
 * and where and when it lifted. The drag detector reports a finger's movement event by event and drops the lift's own, and the
 * velocity tracker forgets samples older than a gap of 40 ms; either one reads a gesture whose frames were stalled as shorter and
 * slower than it was. This keeps the three points the release needs, written by the Initial-pass observer, which sees every event
 * of the gesture first.
 */
internal class FingerTrace {
    private var downY = 0f
    private var downAt = 0L
    private var liftY = Float.NaN
    private var liftAt = 0L
    private var lastDragY = Float.NaN

    fun onDown(y: Float, at: Long) {
        downY = y
        downAt = at
        liftY = Float.NaN
        lastDragY = Float.NaN
    }

    fun onDrag(y: Float) {
        lastDragY = y
    }

    fun onLift(y: Float, at: Long) {
        liftY = y
        liftAt = at
    }

    /** How far the finger moved between the last drag event and the lift (px, negative = up): what the detector did not report. */
    fun unreportedDy(): Float = if (liftY.isNaN() || lastDragY.isNaN()) 0f else liftY - lastDragY

    /** The finger's speed from touch-down to lift (px/s, negative = up); 0 if it has no span. */
    fun averageVelocityY(): Float {
        val span = liftAt - downAt
        return if (liftY.isNaN() || span <= 0L) 0f else (liftY - downY) / span * 1_000f
    }
}

private const val LOCK_LOG_TAG = "DapLockScreen"

/**
 * The surface's four blocks, stacked the way the design stacks them: the clock, the cover, the title and controls,
 * and, at the foot, the output line and the way out.
 *
 * The cover gets what the other three leave (at most [LockArtworkSize], and never wider than the content), and
 * whatever is still left goes **between the controls and the footer**, as in the design, instead of floating around
 * the cover. The three blocks are measured first, so a longer title or a larger font shrinks the cover, not the
 * controls.
 */
@Composable
private fun LockLayout(
    modifier: Modifier,
    top: @Composable () -> Unit,
    cover: @Composable () -> Unit,
    middle: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        content = {
            Box(Modifier.layoutId("top")) { top() }
            Box(Modifier.layoutId("cover")) { cover() }
            Box(Modifier.layoutId("middle")) { middle() }
            Box(Modifier.layoutId("bottom")) { bottom() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        fun byId(id: String) = measurables.first { it.layoutId == id }
        val topP = byId("top").measure(loose)
        val middleP = byId("middle").measure(loose)
        val bottomP = byId("bottom").measure(loose)
        val gapAbove = DapSpacing.GroupGap.roundToPx()
        val gapBelow = 28.dp.roundToPx()
        val room = constraints.maxHeight - topP.height - middleP.height - bottomP.height - gapAbove - gapBelow
        val side = minOf(LockArtworkSize.roundToPx(), constraints.maxWidth, room).coerceAtLeast(0)
        val coverP = byId("cover").measure(androidx.compose.ui.unit.Constraints.fixed(side, side))
        layout(constraints.maxWidth, constraints.maxHeight) {
            var y = 0
            topP.placeRelative(0, y); y += topP.height + gapAbove
            coverP.placeRelative((constraints.maxWidth - coverP.width) / 2, y); y += coverP.height + gapBelow
            middleP.placeRelative(0, y)
            bottomP.placeRelative(0, constraints.maxHeight - bottomP.height)
        }
    }
}

/**
 * The wall clock, in the device's time zone and 12/24-hour setting, and the date under it.
 *
 * Updated by the system's minute tick and by time or zone changes — never by a timer of its own. It
 * used to compute minutes since the epoch modulo a day, which is UTC: on this device it showed 01:29
 * at 20:29.
 */
@Composable
private fun LockClock() {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales.get(0)
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    DisposableEffect(context) {
        now = ZonedDateTime.now()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                now = ZonedDateTime.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val is24Hour = remember(now) { systemUses24HourClock(context) }
    val timeText = remember(now, is24Hour, locale) {
        DateTimeFormatter.ofPattern(if (is24Hour) "H:mm" else "h:mm", locale).format(now)
    }
    val dateText = remember(now.toLocalDate(), locale) {
        DateTimeFormatter
            .ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)
            .format(now)
    }

    Text(text = timeText, style = DapType.Display, color = Dap.primaryText, maxLines = 1)
    Spacer(Modifier.height(DapSpacing.Sm))
    Text(text = dateText, style = DapType.BodyLarge, color = Dap.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Whether the *system* shows a 24-hour clock.
 *
 * `DateFormat.is24HourFormat(context)` falls back to the context's locale when the user never set
 * 12/24 explicitly — and this app's context carries the app language, not the device region. On
 * this device (region es-PA, no explicit setting) that gave 20:48 while the status bar said 8:48.
 * The explicit setting wins; otherwise the device region decides, exactly as the status bar does.
 */
private fun systemUses24HourClock(context: Context): Boolean {
    when (Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)) {
        "24" -> return true
        "12" -> return false
    }
    val region = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(region, "jm")
    return pattern.contains('H') || pattern.contains('k')
}

/** The cover: it fills the square [LockLayout] gives it (at most the design's 216 dp). */
@Composable
private fun LockArtwork(song: Song) {
    SmartImage(
        model = song.albumArtUriString?.takeIf { it.isNotBlank() },
        contentDescription = stringResource(R.string.cd_album_art_for_title, song.title),
        shape = RoundedCornerShape(DapRadius.Surface),
        // The largest rendered size (216 dp at this density), not the file's full resolution.
        targetSize = coil.size.Size(648, 648),
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(DapRadius.Surface)),
    )
}

/** Title and artist under the cover. */
@Composable
private fun NowPlayingText(song: Song) {
    Column(Modifier.fillMaxWidth()) {
        // Two lines and an ellipsis. This surface can stay lit for a long time, so nothing on
        // it moves by itself — a scrolling title would be the one thing that never stops.
        Text(
            text = song.title,
            style = DapType.Heading,
            color = Dap.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        song.artist.takeIf { it.isNotBlank() }?.let { artist ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = artist,
                style = DapType.BodyLarge,
                color = Dap.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Elapsed and total time around a thin read-only line.
 *
 * The first frame already shows the real position: the value is read when the surface is composed,
 * then sampled coarsely while playing. It used to start at zero and jump a frame later, on every
 * wake.
 */
@Composable
internal fun LockProgress(
    isPlaying: Boolean,
    songId: String,
    currentPositionProvider: () -> Long,
    totalDuration: Long,
    /** The screen's lifecycle: sampling runs only while it is at least STARTED (B25-S3: nothing is read with the screen off). */
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle,
) {
    var position by remember(songId) { mutableLongStateOf(currentPositionProvider()) }
    LaunchedEffect(isPlaying, songId, lifecycle) {
        // B25-S3: the surface asks for the position itself, every second while it is on screen and the song plays (the
        // provider reads the player, not a flow that needs a collector, and the app under the surface is held and has
        // none). With the screen off the activity is stopped and this block is cancelled; it starts again, with a fresh
        // read, when the screen comes back. A paused song is read once.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            position = currentPositionProvider()
            while (isPlaying) {
                // Whole seconds are all this surface prints, so a faster sample only repeats a value.
                delay(PlaybackProgressRates.LOCK_SURFACE_TICK_MS)
                position = currentPositionProvider()
            }
        }
    }
    val duration = totalDuration.coerceAtLeast(0L)
    val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val positionDescription = stringResource(R.string.cd_playback_position)

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Dap.trackStroke)
                .semantics {
                    contentDescription = positionDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                }
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(Dap.primaryText)
            )
        }
        Spacer(Modifier.height(DapSpacing.Sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatLockTime(position), style = DapType.Value, color = Dap.secondaryText)
            Text(formatLockTime(duration), style = DapType.Value, color = Dap.secondaryText)
        }
    }
}

/** `03:00`, as the design writes it (and as the player does): minutes are two digits. */
internal fun formatLockTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

@Composable
private fun LockTransportRow(
    isPlaying: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Shuffle and repeat are the same state button the player and the queue use: accent and a dot when on.
        DapStateIconButton(
            contentDescription = stringResource(R.string.player_shuffle),
            active = isShuffleEnabled,
            onClick = onToggleShuffle,
            stateDescription = stringResource(
                if (isShuffleEnabled) R.string.lock_transport_state_on else R.string.lock_transport_state_off
            ),
        ) { tint -> DapIcon(kind = DapIconKind.Shuffle, tint = tint, size = 24.dp) }
        DapStateIconButton(
            contentDescription = stringResource(R.string.lock_transport_previous),
            active = false,
            onClick = onPrevious,
        ) { tint -> DapIcon(kind = DapIconKind.SkipPrevious, tint = tint, size = 28.dp) }
        // The one large target on the surface, and the only filled one.
        val pauseDescription = stringResource(R.string.cd_pause)
        val playDescription = stringResource(R.string.cd_play)
        DapPlayDisc(
            contentDescription = if (isPlaying) pauseDescription else playDescription,
            onClick = onPlayPause,
        ) { tint ->
            DapIcon(
                kind = if (isPlaying) DapIconKind.Pause else DapIconKind.Play,
                tint = tint,
                size = 20.dp,
            )
        }
        DapStateIconButton(
            contentDescription = stringResource(R.string.lock_transport_next),
            active = false,
            onClick = onNext,
        ) { tint -> DapIcon(kind = DapIconKind.SkipNext, tint = tint, size = 28.dp) }
        DapStateIconButton(
            contentDescription = stringResource(R.string.lock_transport_repeat),
            active = repeatMode != Player.REPEAT_MODE_OFF,
            onClick = onToggleRepeat,
            stateDescription = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> stringResource(R.string.lock_transport_repeat_one)
                Player.REPEAT_MODE_ALL -> stringResource(R.string.lock_transport_repeat_all)
                else -> stringResource(R.string.lock_transport_state_off)
            },
        ) { tint ->
            // Repeat-one is a different mode, not a differently tinted one — it is drawn as a
            // different glyph so the state survives without colour.
            DapIcon(
                kind = if (repeatMode == Player.REPEAT_MODE_ONE) DapIconKind.RepeatOne else DapIconKind.Repeat,
                tint = tint,
                size = 24.dp,
            )
        }
    }
}

/**
 * The signal path and the battery, as one mono line at the foot of the surface (the one place the output line lives
 * besides Details and the Settings root).
 *
 * It must fit at 360 dp, or end in an ellipsis: the line gives up width to the battery, never the other way round, and
 * it is never clipped mid-glyph.
 */
@Composable
private fun SignalReadout(outputLine: String?, untouched: Boolean, batteryPercent: Int?) {
    if (outputLine == null && batteryPercent == null) return
    val batteryTitle = stringResource(R.string.dap_battery_title)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (outputLine != null) {
            DapOutputLine(
                text = outputLine,
                untouched = untouched,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        batteryPercent?.let { percent ->
            val percentText = stringResource(R.string.lock_battery_percent, percent)
            Spacer(Modifier.width(DapSpacing.Md))
            Text(
                text = percentText,
                style = DapType.Readout,
                color = Dap.secondaryText,
                maxLines = 1,
                modifier = Modifier.semantics { contentDescription = "$batteryTitle $percentText" },
            )
        }
    }
}

/**
 * The way out of the surface, in both forms.
 *
 * The chevron is the gesture made visible — an unlabelled swipe is undiscoverable, and a surface
 * whose only exit is a gesture has no exit for anyone who cannot perform it. The button under it
 * is the accessible route, and it is a real control rather than a hint.
 */
@Composable
private fun DismissAffordance(onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        DapIcon(kind = DapIconKind.ChevronUp, tint = Dap.tertiaryText, size = 16.dp)
        DapButton(
            label = stringResource(R.string.lock_screen_open_app),
            onClick = onOpen,
            variant = DapButtonVariant.Text,
        )
    }
}
