package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Existing page/render tests navigate through the accessibility actions now that the top bar is gone. Strip taps have separate real-touch tests. */
internal fun ComposeContentTestRule.playerPageAction(label: String, toggle: Boolean = false) {
    val node = onNodeWithTag(PlayerPagesTags.Pager)
    val actions = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
    val page = if (actions.indexOfFirst { it.label == label } == 2) PlayerPagesTags.Lyrics else PlayerPagesTags.Details
    if (toggle && onAllNodesWithTag(page).fetchSemanticsNodes().any { it.boundsInRoot.left >= -1f && it.boundsInRoot.left < 10f }) {
        node.performScrollToIndex(PlayerPage.PLAYER.ordinal)
    } else {
        val action = actions.first { it.label == label }
        runOnUiThread { action.action() }
    }
    mainClock.advanceTimeBy(2000); waitForIdle()
}
