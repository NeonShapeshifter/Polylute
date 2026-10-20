package com.lostf1sh.pixelplayeross.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import kotlinx.coroutines.CoroutineScope

internal data class QueueSheetState(
    val showQueueSheet: Boolean,
    val allowQueueSheetInteraction: Boolean,
    val queueSheetOffset: Animatable<Float, AnimationVector1D>,
    val queueSheetHeightPx: Float,
    /**
     * Where the sheet rests when it is **not open** (B33-U1): its folded position, with [foldedStripPx] of its top showing above the bottom
     * of the screen, or fully off-screen when that is 0 (the landscape player has no folded state). The drag, the snaps and every animation of the
     * sheet run between 0 (open) and this.
     */
    val queueHiddenOffsetPx: Float,
    val queueSheetController: QueueSheetController,
    val onQueueSheetHeightPxChange: (Float) -> Unit
)

/**
 * Where the sheet rests when it is not open (B33-U1), in px below its open position: all the way off-screen ([sheetHeightPx] plus the bottom
 * padding) less [foldedStripPx] of its top that stays above the bottom of the screen. 0 until the sheet has been measured; at least 1 px once
 * it has, so a measured sheet always has a range to be dragged over.
 */
internal fun queueClosedOffsetPx(sheetHeightPx: Float, bottomPaddingPx: Float, foldedStripPx: Float): Float =
    if (sheetHeightPx == 0f) 0f else (sheetHeightPx + bottomPaddingPx - foldedStripPx).coerceAtLeast(1f)

@Composable
internal fun rememberQueueSheetState(
    scope: CoroutineScope,
    screenHeightPx: Float,
    density: Density,
    currentBottomPadding: Dp,
    showPlayerContentArea: Boolean,
    currentSheetContentState: PlayerSheetState,
    /** How much of the sheet's top shows at rest, in px, bottom of the screen up: 0 for no folded state. */
    foldedStripPx: Float = 0f
): QueueSheetState {
    var showQueueSheet by remember { mutableStateOf(false) }
    val allowQueueSheetInteraction by remember(showPlayerContentArea, currentSheetContentState) {
        derivedStateOf {
            showPlayerContentArea && currentSheetContentState == PlayerSheetState.EXPANDED
        }
    }
    val queueSheetOffset = remember(screenHeightPx) { Animatable(screenHeightPx) }
    var queueSheetHeightPx by remember { mutableFloatStateOf(0f) }
    val queueHiddenOffsetPx by remember(currentBottomPadding, queueSheetHeightPx, density, foldedStripPx) {
        derivedStateOf {
            queueClosedOffsetPx(queueSheetHeightPx, with(density) { currentBottomPadding.toPx() }, foldedStripPx)
        }
    }
    val minQueueDragThresholdPx = remember(density) { with(density) { 14.dp.toPx() } }
    val maxQueueDragThresholdPx = remember(density) { with(density) { 56.dp.toPx() } }
    val queueDragThresholdPx by remember(
        queueHiddenOffsetPx,
        minQueueDragThresholdPx,
        maxQueueDragThresholdPx
    ) {
        derivedStateOf {
            (queueHiddenOffsetPx * 0.05f).coerceIn(minQueueDragThresholdPx, maxQueueDragThresholdPx)
        }
    }
    val queueMinFlingTravelPx by remember(density) {
        derivedStateOf { with(density) { 12.dp.toPx() } }
    }

    val queueHiddenOffsetPxState = rememberUpdatedState(queueHiddenOffsetPx)
    val allowQueueSheetInteractionState = rememberUpdatedState(allowQueueSheetInteraction)
    val queueMinFlingTravelPxState = rememberUpdatedState(queueMinFlingTravelPx)
    val queueDragThresholdPxState = rememberUpdatedState(queueDragThresholdPx)
    val showQueueSheetState = rememberUpdatedState(showQueueSheet)
    val queueSheetController = remember(
        scope,
        queueSheetOffset
    ) {
        QueueSheetController(
            scope = scope,
            queueSheetOffset = queueSheetOffset,
            hiddenOffsetProvider = { queueHiddenOffsetPxState.value },
            allowInteractionProvider = { allowQueueSheetInteractionState.value },
            minFlingTravelPxProvider = { queueMinFlingTravelPxState.value },
            dragThresholdPxProvider = { queueDragThresholdPxState.value },
            showQueueSheetProvider = { showQueueSheetState.value },
            onShowQueueSheetChange = { showQueueSheet = it }
        )
    }

    return QueueSheetState(
        showQueueSheet = showQueueSheet,
        allowQueueSheetInteraction = allowQueueSheetInteraction,
        queueSheetOffset = queueSheetOffset,
        queueSheetHeightPx = queueSheetHeightPx,
        queueHiddenOffsetPx = queueHiddenOffsetPx,
        queueSheetController = queueSheetController,
        onQueueSheetHeightPxChange = { queueSheetHeightPx = it }
    )
}
