package com.lostf1sh.pixelplayeross.presentation.components.scoped

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.lostf1sh.pixelplayeross.presentation.lockscreen.FreezeWhileCovered

/**
 * B23-P3: the value an offscreen surface is *shown* while it is parked.
 *
 * The full player and the queue sheet stay composed while collapsed or closed (the first open of either costs far more than
 * keeping them), but nobody can see them, and the song they draw changes on every tap and every skip. They used to follow it
 * all the same: the carousel animated to every new index, the queue rebuilt its keys, and both were laid out again. This
 * returns the last [value] seen while not [held], so the surface's inputs do not change while it is parked and Compose skips
 * it. The moment [held] ends the live value flows in once, in the same recomposition, and the surface catches up as it opens.
 *
 * The first composition always takes the live value (there is nothing older). Main thread, composition only.
 */
@Composable
internal fun <T> rememberHeldWhile(held: Boolean, value: T): T {
    val box = remember { HeldBox(value) }
    if (!held) box.value = value
    return box.value
}

private class HeldBox<T>(@JvmField var value: T)

/**
 * The full player while the sheet is collapsed and the player is parked offscreen (B23-P3).
 *
 * While [parked]:
 *  - **it is not recomposed from outside the subtree**: the caller passes [rememberHeldWhile] values, so nothing it was
 *    given changes, and what it collects itself stops with its lifecycle (the cap [FreezeWhileCovered] puts at CREATED);
 *  - **it is not measured or placed**: its layout reports the size and does nothing else, so a song change cannot
 *    ask a hidden pager or a hidden text to lay out.
 *
 * It is let through **one layout pass first** ([warm]): the 650 ms warm latch (`rememberFullPlayerCompositionPolicy`) exists so
 * the first expand does not pay for composing *and measuring* Now Playing, and a tree that was never measured would give that
 * back. After the pass its nodes keep their last layout; the next measure is the one at the expand, with the new song.
 *
 * Nothing is removed from the composition: scroll, the artist picker, the lyrics state and every `remember` stay as they were.
 */
@Composable
internal fun ParkedWhileCollapsed(
    parked: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var warm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // The frame that follows the first composition has done its measure and layout.
        withFrameNanos { }
        withFrameNanos { }
        warm = true
    }
    FreezeWhileCovered(frozen = parked, hidden = parked && warm, modifier = modifier, content = content)
}
