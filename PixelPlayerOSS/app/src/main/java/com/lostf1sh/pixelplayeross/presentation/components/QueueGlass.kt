package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.glass.CoverGlassScene
import com.lostf1sh.pixelplayeross.ui.glass.StillGlassBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * B42: the queue's own copy of the glass.
 *
 * The queue is retained composed and rests *folded* at the foot of the full player, where its top is the "Up next" strip; it opens over a
 * player whose glass moves. It cannot be see-through (the moving glass would show through it, and a sheet never moves), so it carries its
 * own still copy ([StillGlassBackdrop]), frozen at the phase of the instant it starts to open.
 *
 * **Folded, it is what it was**: the strip is the mini player's opaque cover-coloured shelf, drawn here, so it matches the mini player without showing the page behind it. The glass comes in over it as the sheet opens, [foldProgress] (0 folded, 1 open, read in the draw phase) being how
 * much of it shows, so there is no cut and no frame is see-through. Nothing here is recomposed by the progress.
 *
 * Draws nothing without glass (null [scene]): the sheet's own surface is then the theme's, as before.
 *
 * @param open the queue is open (or opening).
 */
@Composable
internal fun QueueGlass(scene: CoverGlassScene?, open: Boolean, foldProgress: () -> Float) {
    if (scene == null) return
    var clock by remember(scene.clock) { mutableStateOf(scene.clock.frozenCopy()) }
    val isOpen = rememberUpdatedState(open)
    val progress = rememberUpdatedState(foldProgress)
    // The glass is taken at the instant the queue starts to show and again when it has gone: while it is invisible it costs and shows
    // nothing, so the phase it is drawn at is always the one of the moment the sheet began to open. A sheet with no folded state (the
    // landscape player) has a progress of 1 all the time, so it is "showing" when it is open; a folded one is also showing while it is
    // between its two states, however it got there (a tap, a drag, a fling).
    LaunchedEffect(scene.clock) {
        snapshotFlow {
            val p = progress.value()
            isOpen.value || (p > SHOWING_FROM && p < 1f)
        }
            .distinctUntilChanged()
            .collect { clock = scene.clock.frozenCopy() }
    }
    val surface = Dap.shelf
    Box(Modifier.fillMaxSize()) {
        // The surface stays opaque under the glass, and the glass comes in over it: every frame of the opening is opaque (a surface fading
        // out while the glass fades in would be see-through half way, with the player showing through the sheet).
        Box(Modifier.fillMaxSize().drawBehind { drawRect(surface) })
        StillGlassBackdrop(scene, clock, visibility = { progress.value() })
    }
}

/** Below this much open the queue counts as not showing: the glass is invisible, and its phase is taken again when it starts to rise. */
private const val SHOWING_FROM = 0.02f
