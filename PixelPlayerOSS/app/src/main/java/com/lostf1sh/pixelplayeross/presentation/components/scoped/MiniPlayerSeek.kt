package com.lostf1sh.pixelplayeross.presentation.components.scoped

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import kotlin.math.roundToLong

/**
 * B40-F6: the mini player's progress line, and the rule that tells its drag from the mini player's song swipe (B33-U2).
 *
 * **Two gestures, one rule: where the finger goes down.** The line owns the top [ZONE_DP] of the mini player (the line itself is its top
 * 2 dp; the zone is the room a fingertip needs). A touch that goes down inside it is the line's; a touch that goes down anywhere else is
 * the song swipe's (and the tap, and the buttons'), exactly as before. The two never compete for one touch, so there is no direction to
 * guess: inside the zone a mostly sideways drag seeks, below it a mostly sideways drag changes the song. A vertical drag is neither's, in
 * either place: it goes on to the sheet and opens the player. A **tap** on the line is not a seek either: it opens the player like a tap
 * on the rest of the mini player (a thin line is easy to touch by accident, and the tap is the mini player's main action).
 *
 * **The feel of the full player's bar.** The same absolute rule (the finger's x is the position), the same haptic tick every 5 %, the same
 * 2 dp track, the same end of it: the position is sought when the finger lifts, not while it moves, and a system cancel seeks nothing.
 *
 * **Cheap.** It adds no sampler and no recomposition: the position it draws is the one the sheet already collects (the shared progress loop
 * runs at 1 Hz when no slider is mounted, which for a 2 dp line on a phone is one to two dp a step), read in the draw phase only, so a tick
 * redraws a few pixels and recomposes nothing; and it reads nothing at all while the mini player is hidden behind the full one.
 */
internal object MiniSeekPolicy {
    /** The height of the touch zone at the top of the mini player. The line is the top [TRACK_DP] of it. */
    const val ZONE_DP = 16f
    const val TRACK_DP = 2f
    const val TRACK_SEEKING_DP = 4f
    const val THUMB_DP = 10f

    /** The full player's tick: one haptic click for every twentieth of the song the finger passes. */
    const val HAPTIC_STEPS = 20

    enum class Owner { SEEK, SWIPE }

    /** Who a touch that goes down [downY] px below the mini player's top edge belongs to; a [zonePx] of 0 means there is no line. */
    fun ownerOf(downY: Float, zonePx: Float): Owner = if (zonePx > 0f && downY < zonePx) Owner.SEEK else Owner.SWIPE

    /** The length the line is drawn against: the player's own once it has one, else the library's. */
    fun durationMs(playerDurationMs: Long, songDurationMs: Long): Long =
        if (playerDurationMs > 0L) playerDurationMs else songDurationMs.coerceAtLeast(0L)

    /** The line exists only for a song with a known length, once it is playing and not while it is still being prepared. */
    fun available(durationMs: Long, preparing: Boolean): Boolean = durationMs > 0L && !preparing

    /** The finger's place on a line [widthPx] wide, as a fraction of the song. */
    fun fractionAt(x: Float, widthPx: Float): Float = if (widthPx <= 0f) 0f else (x / widthPx).coerceIn(0f, 1f)

    fun fractionOf(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    fun positionMs(fraction: Float, durationMs: Long): Long =
        (fraction.coerceIn(0f, 1f) * durationMs).roundToLong().coerceIn(0L, durationMs.coerceAtLeast(0L))

    fun hapticStep(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * HAPTIC_STEPS).toInt()
}

/**
 * The line. [positionProvider] is read in the draw phase only; [activeProvider] is false while the full player covers the mini one,
 * and then nothing is read. [onSeek] gets the position when the finger lifts.
 */
@Composable
internal fun MiniPlayerSeekLine(
    songId: String,
    positionProvider: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    activeProvider: () -> Boolean = { true },
) {
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.cd_playback_position)
    // -1 while nobody is seeking; the finger's fraction while one is. Read in the draw phase only.
    var seekFraction by remember(songId) { mutableFloatStateOf(NOT_SEEKING) }
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestDurationMs by rememberUpdatedState(durationMs)
    val latestPositionProvider by rememberUpdatedState(positionProvider)
    val latestActiveProvider by rememberUpdatedState(activeProvider)
    val latestHaptics by rememberUpdatedState(haptics)
    val activeColor = Dap.primaryText
    val trackColor = Dap.interactiveTrack

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniSeekPolicy.ZONE_DP.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = MiniSeekPolicy.fractionOf(latestPositionProvider(), latestDurationMs),
                    range = 0f..1f,
                )
                setProgress { target ->
                    latestOnSeek(MiniSeekPolicy.positionMs(target, latestDurationMs))
                    true
                }
            }
            .pointerInput(songId) {
                val slopPx = viewConfiguration.touchSlop
                awaitEachGesture {
                    // The down is not consumed: a tap on the line is a tap on the mini player.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var intent: MiniSwipePolicy.Intent? = null
                    var totalDx = 0f
                    var totalDy = 0f
                    var lastStep = -1
                    val finished = drag(down.id) { change ->
                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        if (intent == null) intent = MiniSwipePolicy.intentAfterSlop(totalDx, totalDy, slopPx)
                        if (intent == MiniSwipePolicy.Intent.HORIZONTAL) {
                            change.consume()
                            val fraction = MiniSeekPolicy.fractionAt(change.position.x, size.width.toFloat())
                            seekFraction = fraction
                            val step = MiniSeekPolicy.hapticStep(fraction)
                            if (step != lastStep) {
                                lastStep = step
                                latestHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                    if (intent == MiniSwipePolicy.Intent.HORIZONTAL) {
                        val fraction = seekFraction
                        seekFraction = NOT_SEEKING
                        if (finished && fraction >= 0f) latestOnSeek(MiniSeekPolicy.positionMs(fraction, latestDurationMs))
                    }
                }
            }
            .drawBehind {
                if (!latestActiveProvider()) return@drawBehind
                val seeking = seekFraction >= 0f
                val fraction = if (seeking) seekFraction else MiniSeekPolicy.fractionOf(latestPositionProvider(), latestDurationMs)
                val track = (if (seeking) MiniSeekPolicy.TRACK_SEEKING_DP else MiniSeekPolicy.TRACK_DP).dp.toPx()
                drawRect(trackColor, size = Size(size.width, track))
                drawRect(activeColor, size = Size(size.width * fraction, track))
                if (seeking) {
                    val radius = (MiniSeekPolicy.THUMB_DP / 2f).dp.toPx()
                    drawCircle(activeColor, radius = radius, center = Offset(size.width * fraction, radius))
                }
            },
    )
}

private const val NOT_SEEKING = -1f
