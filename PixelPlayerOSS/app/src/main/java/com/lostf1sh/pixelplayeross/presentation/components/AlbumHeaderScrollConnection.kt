package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** Header consumes expansion only at list top. All remaining distance belongs to the list. */
internal class AlbumHeaderScrollConnection(
    private val height: () -> Float,
    private val setHeight: (Float) -> Unit,
    private val atTop: () -> Boolean,
    private val min: Float,
    private val rest: Float,
    private val pageWidth: Float,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        if (delta > 0 && !atTop()) return Offset.Zero
        val old = height()
        // Flinging a list does not stretch artwork. Finger drag at the top can reach a square.
        val max = if (atTop() && source == NestedScrollSource.UserInput) maxOf(rest, pageWidth) else rest
        val next = (old + delta).coerceIn(min, maxOf(old, max))
        setHeight(next)
        return Offset(0f, next - old)
    }
}
