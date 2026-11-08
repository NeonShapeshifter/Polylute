package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * B58 5 (#76): "a scroll bar's thumb is being dragged right now", for the screens that have something floating over their lists.
 *
 * The scroll bar's bubble is drawn inside the list's page, so a floating disc of the Library (locate, shuffle, play) is drawn **over** it, and when the bubble is dragged near the
 * bottom it ended up half under the locate disc (`B57-5-review/shot-lock-1.png`). The Library fades its discs while this is true, so the bubble is never covered by one (and never
 * cut: nothing is above it). It is one shared fact, not one per list: a finger drags one bar at a time, and a screen with no discs reads nothing.
 *
 * A counter and not a flag, because a pager keeps the neighbouring page's bar composed: one bar letting go must not hide that another is still held.
 */
internal object ScrollDragSignal {
    private var holders by mutableIntStateOf(0)

    /** True while any scroll bar is being dragged. Reading it in a composition or a graphics layer makes that read follow it. */
    val dragging: Boolean get() = holders > 0

    fun begin() {
        holders += 1
    }

    fun end() {
        if (holders > 0) holders -= 1
    }

    /** For the tests: nobody is dragging. */
    internal fun reset() {
        holders = 0
    }

    /** How long the discs take to go and to come back (ms): short, so that they are gone when the bubble rises and back right after the finger lifts. */
    const val FADE_MS = 150
}
