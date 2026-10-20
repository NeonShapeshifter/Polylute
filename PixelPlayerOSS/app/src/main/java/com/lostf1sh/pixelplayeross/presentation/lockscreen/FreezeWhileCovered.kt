package com.lostf1sh.pixelplayeross.presentation.lockscreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The app, while the DAP lock screen covers it (B22, Part 2).
 *
 * The lock surface is drawn inside `MainActivity`'s own composition, on top of the app. Until now the app underneath
 * went on living: every song change recomposed whatever reads the playing song (the library's rows, the mini player,
 * the full player offscreen) and laid it out again, for a screen nobody could see. B20 measured that as 2 frames of
 * 156–313 ms on every change, on the debug build.
 *
 * This wrapper stops it, in two ways that do not depend on what the app draws:
 *
 *  - **[frozen]: the app stops hearing.** Everything the app draws reads the player through
 *    `collectAsStateWithLifecycle` (the project has no plain `collectAsState`), and that stops collecting while its
 *    lifecycle is below STARTED. The subtree is given a [LifecycleOwner] that follows the activity but is held at
 *    CREATED while [frozen], so no flow reaches it, nothing is invalidated, and nothing is recomposed. When the cap is
 *    lifted every collector resumes and reads the latest value, exactly as an app coming back from the background does.
 *    Nothing is removed from the composition: tab, scroll position, back stack and every `remember` stay as they were.
 *  - **[hidden]: the app is not laid out.** The wrapper's layout reports its size and does not measure or place its
 *    content, so a node that asks to be laid out (an animation, a size that depends on the playing song) waits until it
 *    is placed again, and it is not drawn. [hidden] is lifted while the surface is being swiped away, so the app shows
 *    under it as it slides off, exactly as it was before the screen went dark.
 *
 * The lock screen itself (and its wake cycle) sits outside this wrapper and keeps the real lifecycle.
 *
 * **B25-S4: held from the moment the screen goes off, not from the moment the surface is on screen.** [frozen] follows
 * the surface, which is reported from an effect a frame or two after the screen comes on; by then the activity had
 * already restarted, every collector of the covered app had resumed and recomposed it, and that work was the wake's first
 * frame (a 467 ms `doFrame` with 181 ms of recomposition on the debug build, in the frame the USB writer lost its core
 * to). [cover] is the signal the wake cycle sets itself, synchronously, when it arms at `SCREEN_OFF`: the lifecycle cap
 * is applied there, before the activity can restart, and held until the cycle ends.
 */
@Composable
fun FreezeWhileCovered(
    frozen: Boolean,
    hidden: Boolean,
    modifier: Modifier = Modifier,
    cover: CoverSignal? = null,
    content: @Composable () -> Unit,
) {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { CappedLifecycleOwner(parent.lifecycle) }
    DisposableEffect(owner) { onDispose { owner.release() } }
    // The signal reaches the lifecycle directly, outside composition: that is the whole point of it.
    DisposableEffect(owner, cover) {
        cover?.attach { owner.coverHeld = it }
        owner.coverHeld = cover?.covered == true
        onDispose { cover?.attach(null) }
    }
    // After composition, not during it: moving a lifecycle is a side effect, and the content does not read the cap.
    SideEffect { owner.frozen = frozen }

    val hiddenNow = rememberUpdatedState(hidden)
    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        Layout(content = content, modifier = modifier) { measurables, constraints ->
            if (hiddenNow.value && constraints.hasFixedWidth && constraints.hasFixedHeight) {
                // Same size, no work: the content is neither measured nor placed, so it is not drawn either.
                layout(constraints.maxWidth, constraints.maxHeight) {}
            } else {
                val placeables = measurables.map { it.measure(constraints) }
                val width = placeables.maxOfOrNull { it.width }?.coerceAtLeast(constraints.minWidth) ?: constraints.minWidth
                val height = placeables.maxOfOrNull { it.height }?.coerceAtLeast(constraints.minHeight) ?: constraints.minHeight
                layout(width, height) { placeables.forEach { it.place(0, 0) } }
            }
        }
    }
}

/**
 * A lifecycle that is the [parent]'s, held no higher than CREATED while [frozen].
 *
 * It follows the parent's every event (so it goes to STOPPED and DESTROYED with the activity), and the cap only lowers
 * it: a destroyed parent is destroyed here whatever the cap says. Main thread only, like every lifecycle.
 */
internal class CappedLifecycleOwner(private val parent: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private val follow = LifecycleEventObserver { _, _ -> sync() }

    var frozen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sync()
        }

    /** B25-S4: held by the wake cycle ([CoverSignal]), independently of [frozen]; either one caps the lifecycle. */
    var coverHeld: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sync()
        }

    override val lifecycle: Lifecycle get() = registry

    init {
        parent.addObserver(follow)
        sync()
    }

    private fun sync() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        val ceiling = if (frozen || coverHeld) Lifecycle.State.CREATED else Lifecycle.State.RESUMED
        val target = if (parent.currentState < ceiling) parent.currentState else ceiling
        if (registry.currentState != target) registry.currentState = target
    }

    fun release() {
        parent.removeObserver(follow)
    }
}

/**
 * Whether the lock surface has the app covered, or is about to (B25-S4): set by the wake cycle on its own events (the
 * screen going off arms it, the cycle ending releases it), read by [FreezeWhileCovered].
 *
 * Two readers, two paths. [FreezeWhileCovered] is told by a listener, synchronously on the thread that sets it, so the
 * app's lifecycle is capped before the activity can restart; composition reads [covered] as snapshot state, to leave the
 * app unplaced. Main thread only, like the receiver that sets it.
 */
class CoverSignal {
    private val state = mutableStateOf(false)
    private var listener: ((Boolean) -> Unit)? = null

    var covered: Boolean
        get() = state.value
        set(value) {
            if (state.value == value) return
            state.value = value
            listener?.invoke(value)
        }

    internal fun attach(listener: ((Boolean) -> Unit)?) {
        this.listener = listener
    }
}

/** Which wake-cycle states hold the app under the lock surface (B25-S4); pure, so it can be pinned. */
object WakeCycleCover {
    /**
     * @param hasSong whether there is a song **now** (the surface needs one to be drawn at all).
     */
    fun covers(state: WakeCycleState, hasSong: Boolean): Boolean = when (state) {
        WakeCycleState.IDLE -> false
        WakeCycleState.ARMED, WakeCycleState.WAITING_FOR_KEYGUARD -> true
        WakeCycleState.VISIBLE -> hasSong
    }
}
