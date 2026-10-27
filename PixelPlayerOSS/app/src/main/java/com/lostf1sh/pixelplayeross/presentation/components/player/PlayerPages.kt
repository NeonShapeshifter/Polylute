package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The full player is four pages side by side (B42-fix1): **Menu** to the left of **Player** (where it always opens), then **Lyrics** and
 * **Details** to the right. The order is the pager's: the ordinal is the page's index.
 *
 * Nothing on screen says so: no dots, no tabs, no indicator. The header's lyrics and details icons jump to their pages, the system back
 * returns to Player from any of the others, and a screen reader is offered the three pages as actions ([PlayerPages]).
 */
internal enum class PlayerPage {
    MENU,
    PLAYER,
    LYRICS,
    DETAILS;

    companion object {
        /** Where the player opens, every time. */
        val Start = PLAYER
    }
}

/** The pages' state: the pager, and the moves between pages (a jump from a header icon, the way back). */
@Stable
internal class PlayerPagesState(
    val pager: PagerState,
    private val scope: CoroutineScope,
) {
    /** The page the pager is on (nearest to the middle while a finger is moving it). Read only where a change of page has to be drawn. */
    val current: PlayerPage get() = PlayerPage.entries[pager.currentPage.coerceIn(0, PlayerPage.entries.size - 1)]

    val isOnPlayer: Boolean get() = pager.currentPage == PlayerPage.PLAYER.ordinal

    /** Goes to [page], sliding. */
    fun goTo(page: PlayerPage) {
        scope.launch { pager.animateScrollToPage(page.ordinal) }
    }

    /** From a page that is not Player: back to Player. False (and nothing done) on Player itself, where back closes the player. */
    fun back(): Boolean {
        if (isOnPlayer) return false
        goTo(PlayerPage.Start)
        return true
    }

    /** Lands on Player without a slide: the player was collapsed and is opening, or being left. */
    suspend fun reset() {
        if (!isOnPlayer) pager.scrollToPage(PlayerPage.Start.ordinal)
    }
}

@Composable
internal fun rememberPlayerPagesState(): PlayerPagesState {
    val pager = rememberPagerState(initialPage = PlayerPage.Start.ordinal) { PlayerPage.entries.size }
    val scope = rememberCoroutineScope()
    return remember(pager, scope) { PlayerPagesState(pager, scope) }
}

/**
 * What goes on the pages the full player does not draw itself: the options of the song (Menu) and its details. The screen never sees a
 * ViewModel, so the host hands these in as slots; Player and Lyrics are the screen's own.
 */
@Stable
internal class PlayerPageSlots(
    /** The song's options, as a page. Told how to move between pages, so a row that leads to the details goes there. */
    val menu: @Composable (pages: PlayerPagesState) -> Unit = {},
    val details: @Composable (pages: PlayerPagesState) -> Unit = {},
)

/**
 * A swipe that begins on the cover changes the song, and **only** the song (B42-fix1). The cover's carousel is a pager inside the player's
 * pager, and Compose hands whatever a nested scroller cannot use to the one around it: at the first or the last song, or with one song in
 * the queue, the rest of the drag would have moved the page. This takes the horizontal remainder (and the fling's) so it dies on the cover.
 */
internal fun Modifier.keepHorizontalSwipeOnThisSurface(): Modifier = nestedScroll(AbsorbHorizontalLeftovers)

private object AbsorbHorizontalLeftovers : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(available.x, 0f)
}

/**
 * Whether the full player is on its Player page, for the layers around it that belong to that page only: the queue's folded strip ("A
 * continuación") is Player's, and is not drawn on the other pages (B42-fix1). Set by the screen, read by the queue; true where nothing sets it.
 */
@Stable
internal class PlayerPageSignal {
    /** The page the player is on (the nearest one to the middle while a finger moves it). */
    var page: PlayerPage by mutableStateOf(PlayerPage.Start)

    val onPlayerPage: Boolean get() = page == PlayerPage.PLAYER
}

internal val LocalPlayerPageSignal = staticCompositionLocalOf<PlayerPageSignal?> { null }
