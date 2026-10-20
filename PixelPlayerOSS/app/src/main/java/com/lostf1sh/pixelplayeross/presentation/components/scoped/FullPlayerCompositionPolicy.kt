package com.lostf1sh.pixelplayeross.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal data class FullPlayerCompositionPolicy(
    val shouldRenderFullPlayer: Boolean,
    /**
     * Whether Now Playing is actually **on screen** — the sheet is expanded and the content has
     * begun to appear. Distinct from [shouldRenderFullPlayer], which is true for a collapsed tree
     * that is composed but parked offscreen. Anything that costs work per unit time must key off
     * this, never off composition.
     */
    val isFullPlayerRendered: Boolean
)

/**
 * Decides whether the full-player composable tree should be in composition.
 *
 * Accepts [Animatable] instead of a raw Float so that the expansion fraction is
 * read inside [derivedStateOf] / [snapshotFlow] — never as a `remember` key or
 * `LaunchedEffect` key. This prevents per-frame recomposition of the caller during
 * sheet drag gestures.
 *
 * The two returned values answer different questions and must not be conflated:
 *
 *  - [shouldRenderFullPlayer] is a **composition lifetime** question: "should this tree exist?"
 *  - [isFullPlayerRendered] is a **visibility** question: "is it on screen right now?"
 *
 * A collapsed player answers yes to the first and no to the second.
 *
 * The lifetime is a property of **a song existing**, not of *which* song it is. It used to be keyed
 * on the track id, which meant every skip reset the latch, dropped the tree out of composition for
 * the collapse-warm delay, and rebuilt the whole of Now Playing — twice, because the discarded
 * prewarm copy composed in the same window. A track change is an update to this tree, not a reason
 * for it to stop existing. `song -> null` still disposes, and that is deliberate.
 */
@Composable
internal fun rememberFullPlayerCompositionPolicy(
    currentSongId: String?,
    currentSheetState: PlayerSheetState,
    expansionFraction: Animatable<Float, AnimationVector1D>,
    collapsedWarmDelayMs: Long = 650L
): FullPlayerCompositionPolicy {
    // Not keyed on `currentSongId`: see the lifetime note above. The id is read through
    // [rememberUpdatedState] so the derived state still sees `song -> null` and disposes on it.
    var keepFullPlayerComposed by remember { mutableStateOf(false) }
    val latestSongId = rememberUpdatedState(currentSongId)

    LaunchedEffect(currentSongId, currentSheetState) {
        if (currentSongId == null) {
            keepFullPlayerComposed = false
            return@LaunchedEffect
        }

        if (currentSheetState == PlayerSheetState.EXPANDED) {
            keepFullPlayerComposed = true
        } else {
            delay(collapsedWarmDelayMs)
            keepFullPlayerComposed = true
        }
    }

    LaunchedEffect(currentSongId) {
        if (currentSongId == null) return@LaunchedEffect
        snapshotFlow {
            keepFullPlayerComposed || expansionFraction.value > 0.12f
        }.first { it }
        if (!keepFullPlayerComposed) keepFullPlayerComposed = true
    }

    val shouldRenderFullPlayer by remember(currentSheetState) {
        derivedStateOf {
            latestSongId.value != null && (
                currentSheetState == PlayerSheetState.EXPANDED ||
                    expansionFraction.value > 0.015f ||
                    keepFullPlayerComposed
                )
        }
    }

    val isFullPlayerRendered by remember(currentSheetState) {
        derivedStateOf {
            latestSongId.value != null &&
                currentSheetState == PlayerSheetState.EXPANDED &&
                expansionFraction.value > 0.015f
        }
    }

    return FullPlayerCompositionPolicy(
        shouldRenderFullPlayer = shouldRenderFullPlayer,
        isFullPlayerRendered = isFullPlayerRendered
    )
}
