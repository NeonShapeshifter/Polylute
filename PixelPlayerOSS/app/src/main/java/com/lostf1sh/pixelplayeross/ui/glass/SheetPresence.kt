package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf

/**
 * B56 phase 7: whether a bottom sheet (or the open queue) is over the player. A sheet carries its own still copy of the glass (B42), so what moves behind it is wasted work: the
 * live background and an animated cover both hold still while [anyOpen]. Every sheet the app opens goes through `GlassModalBottomSheet`, which holds one of these for as long as it
 * is composed; the queue holds one while it is more than half open. Main thread only (composition effects).
 */
object SheetPresence {
    private val open = mutableIntStateOf(0)

    /** A sheet is open. Read in composition or in the draw phase: it is snapshot state. */
    val anyOpen: Boolean get() = open.intValue > 0

    /** For the tests: how many holds there are. */
    internal val holds: Int get() = open.intValue

    /** Holds the presence while [held] and this call are in the composition. */
    @Composable
    fun Hold(held: Boolean = true) {
        DisposableEffect(held) {
            if (held) open.intValue += 1
            onDispose { if (held) open.intValue -= 1 }
        }
    }
}
