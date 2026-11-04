package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.presentation.model.RecentlyPlayedSongUiModel
import com.lostf1sh.pixelplayeross.ui.ds.*
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=Application::class,qualifiers="es-w360dp-h805dp-xhdpi")
class B55RecentGridTest {
    @get:Rule val compose=createComposeRule()
    // B57 3.1 changes this test on purpose (owner, 11-03: B55's 2 x 3 grid "is too big"): Recently played is a horizontal carousel of small cards, about 15 songs. What it keeps from
    // B55: the order shown is the order tapped (and the queue's), and no card is cut through its text.
    @Test fun `the carousel holds fifteen songs in order, the next card peeks and no card is cut through its text`() {
        val items=(1L..20).map { RecentlyPlayedSongUiModel(Kit.song(it,title="Synthetic song $it"),1000L-it) }.toImmutableList()
        val taps=mutableListOf<String>()
        compose.setContent { DapTheme { RecentlyPlayedSection(items,{taps+=it.id},{},contentPadding=PaddingValues(horizontal=DapSpacing.Gutter)) } }
        compose.waitForIdle()
        assertThat(recentCarouselOrder(items).map { it.song.id }).isEqualTo((1L..15).map { it.toString() })
        compose.onNodeWithTag("recent-carousel").assertExists()
        val first=compose.onNodeWithTag("recent-tile-1").fetchSemanticsNode().boundsInRoot
        val second=compose.onNodeWithTag("recent-tile-2").fetchSemanticsNode().boundsInRoot
        assertThat(first.width).isWithin(1f).of(second.width)
        assertThat(first.width).isAtMost(240f) // small: a 116 dp card (232 px at xhdpi), not B55's half-width tiles
        // on a 360 dp phone: two whole cards, and the third starts inside the window but does not end in it: it peeks
        val third=compose.onNodeWithTag("recent-tile-3").fetchSemanticsNode().boundsInRoot
        assertThat(third.left).isLessThan(720f)
        assertThat(third.width).isLessThan(first.width) // the window clips it: its visible part is narrower than a whole card
        // the cards that are on screen are whole: each one's left edge is on screen and its text is not cut at the left
        assertThat(first.left).isAtLeast(40f)
        // a card past the 15th does not exist
        compose.onNodeWithTag("recent-carousel").performScrollToIndex(14)
        compose.onNodeWithTag("recent-tile-15").assertExists()
        compose.onAllNodesWithTag("recent-tile-16").assertCountEquals(0)
        compose.onNodeWithTag("recent-tile-15").performClick()
        assertThat(taps).containsExactly("15")
    }
    @Test fun `the entire Recently played header opens history`() {
        var opens=0
        val items=(1L..6).map { RecentlyPlayedSongUiModel(Kit.song(it),1000L) }.toImmutableList()
        compose.setContent { DapTheme { RecentlyPlayedSection(items,{}, {opens++}) } }
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(com.lostf1sh.pixelplayeross.R.string.presentation_batch_g_recently_played_title),ignoreCase=true).performClick()
        assertThat(opens).isEqualTo(1)
    }
}
