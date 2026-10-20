package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

/**
 * True while a list is moving on its own momentum (or being scrolled by code), false while a finger
 * is dragging it and when it is at rest.
 *
 * A finger-driven scroll is slow enough to read what goes by, so covers keep loading; a fling is not,
 * so rows that enter during one wait (see [LocalArtworkLoadDeferred]). It is a [State] and not a
 * Boolean so that reading it recomposes the few readers and not the list that owns it.
 */
@Composable
fun rememberFlingState(listState: ScrollableState, interactionSource: InteractionSource): State<Boolean> {
    val dragged = interactionSource.collectIsDraggedAsState()
    return remember(listState, dragged) {
        derivedStateOf { listState.isScrollInProgress && !dragged.value }
    }
}
