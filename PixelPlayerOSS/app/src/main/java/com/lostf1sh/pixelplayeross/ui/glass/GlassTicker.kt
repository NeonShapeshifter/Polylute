package com.lostf1sh.pixelplayeross.ui.glass

import android.animation.ValueAnimator
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * B41: advances [clock] while [running], and does nothing at all otherwise (no coroutine wakes, no state is written, nothing is invalidated):
 * a paused player, a hidden one, a dark screen cost no more than a still picture.
 *
 * B41-fix1 (B4): once per **display frame**, at the display's own rate (60, 90 or 120 a second), not at a cap of 20. The owner asked for the
 * glass to be smoother and said whoever picks the mode does not mind the battery. The cost of a step is small and does not grow with the rate:
 * the clock is read only in the layers' draw phase, so a step recomposes and measures nothing, it moves two layers' RenderNodes
 * ([GlassClock], [GlassMotion.layer]). Every stop is the same as before ([GlassMotionPolicy]): `running` is false for a paused player, a hidden
 * one, a dark screen, Battery saver and "Remove animations", and outside the full player and the lock screen.
 *
 * The step taken is how long the frame really was (capped by [GlassMotionPolicy.stepSeconds]), so a stalled main thread makes the glass
 * late, not jumpy.
 *
 * @param onStep called after each step, on the main thread; a test counts them.
 */
@Composable
fun GlassTicker(clock: GlassClock, running: Boolean, onStep: () -> Unit = {}, minFrameNanos: Long = 0L) {
    val latestOnStep = rememberUpdatedState(onStep)
    LaunchedEffect(running, minFrameNanos) {
        if (!running) return@LaunchedEffect
        clock.runOnFrames(minFrameNanos) { latestOnStep.value() }
    }
}

/**
 * Steps this clock on every frame of the coroutine's frame clock, by the time since the frame before, until it is cancelled. The write is made
 * inside the frame's own callback, so it is drawn in that same frame.
 */
internal suspend fun GlassClock.runOnFrames(minFrameNanos: Long = 0L, onStep: () -> Unit = {}): Nothing {
    var last = withFrameNanos { it }
    while (true) {
        var stepped = false
        withFrameNanos { frameNanos ->
            // B56-7.1: a capped clock (the live background, 30 a second) waits until a frame is [minFrameNanos] old, and then takes the time that really passed
            if (frameNanos - last >= minFrameNanos) {
                step(GlassMotionPolicy.stepSeconds(frameNanos - last))
                last = frameNanos
                stepped = true
            }
        }
        if (stepped) onStep()
    }
}

/**
 * Whether the system lets an animation run: "Remove animations" (the animator duration scale is 0) and Battery saver both turn animators off,
 * and the glass follows them. Read again whenever either changes, and when a copy of the glass starts to follow.
 *
 * B42-fix3 (item 4): the registration behind it is the process's one ([GlassSystemSignals]); a copy only holds a lease on it while it is
 * composed, so five copies are one registration and a copy that comes and goes costs none.
 */
@Composable
fun rememberSystemAnimationsAllowed(): State<Boolean> {
    val signals = GlassSystemSignals.of(LocalContext.current)
    DisposableEffect(signals) {
        val lease = signals.acquire()
        onDispose { lease.release() }
    }
    return signals.animationsAllowed
}

internal fun systemAnimationsAllowed(context: Context): Boolean {
    val scale = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
    val saver = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    return scale > 0f && !saver && ValueAnimator.areAnimatorsEnabled()
}

/** Whether the screen is on and interactive: a lock screen on a dark (dozing) screen holds still. Follows the screen's own broadcasts (one registration, see above). */
@Composable
fun rememberScreenInteractive(): State<Boolean> {
    val signals = GlassSystemSignals.of(LocalContext.current)
    DisposableEffect(signals) {
        val lease = signals.acquire()
        onDispose { lease.release() }
    }
    return signals.interactiveScreen
}

internal fun screenInteractive(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false

/** Whether the activity is in front (resumed): a permission dialog, the system's screens and another app all take it away. */
@Composable
fun rememberResumed(): State<Boolean> {
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> state.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer)
        state.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}
