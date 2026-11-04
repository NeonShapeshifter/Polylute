package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.bottomCenter
import androidx.compose.ui.test.bottomRight
import androidx.compose.ui.test.center
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.topLeft
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B57 2.3 and 2.7 on the lyrics page. A song change starts the lyrics at the top in the first frame; a tap anywhere on an immersive page brings the controls back, which is
 * why the arrow button that did it could go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class B57LyricsPageTest {
    @get:Rule val compose = createComposeRule()

    private class SongHolder { var songId by mutableStateOf("first") }

    private class Captured { var synced: LyricsListStatesProbe? = null }
    private class LyricsListStatesProbe(val states: Pair<LazyListState, LazyListState>)

    @Composable private fun Lists(holder: SongHolder, captured: Captured, keyedBySong: Boolean) {
        val states = if (keyedBySong) rememberLyricsListStates(holder.songId, 0) else rememberLazyListState() to rememberLazyListState()
        captured.synced = LyricsListStatesProbe(states)
        LazyColumn(state = states.first, modifier = Modifier.fillMaxSize().testTag("lines")) { items((1..120).toList()) { Text("line $it", modifier = Modifier.height(40.dp)) } }
    }

    private fun scrolledThenSongChanged(keyedBySong: Boolean): Int {
        val holder = SongHolder()
        val captured = Captured()
        compose.mainClock.autoAdvance = false
        compose.setContent { Lists(holder, captured, keyedBySong) }
        compose.mainClock.advanceTimeByFrame()
        runBlocking { captured.synced!!.states.first.scrollToItem(60) }
        compose.mainClock.advanceTimeByFrame()
        assertThat(captured.synced!!.states.first.firstVisibleItemIndex).isEqualTo(60)
        val before = captured.synced!!.states.first
        // the song changes; the frame it is composed in is the first frame the new song's lyrics are on screen in
        compose.runOnIdle { holder.songId = "second" }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        println("B57 2.3 harness keyed=$keyedBySong sameState=${captured.synced!!.states.first === before} index=${captured.synced!!.states.first.firstVisibleItemIndex}")
        return captured.synced!!.states.first.firstVisibleItemIndex
    }

    @Test fun the_first_frame_of_a_new_song_shows_the_top_of_the_lyrics() {
        assertThat(scrolledThenSongChanged(keyedBySong = true)).isEqualTo(0)
    }

    @Test fun without_the_song_key_the_list_stays_where_it_was_which_is_the_fault() {
        assertThat(scrolledThenSongChanged(keyedBySong = false)).isEqualTo(60)
    }

    // ── immersive ──────────────────────────────────────────────────────────────────────────────────

    private class Immersive { var on by mutableStateOf(true); var restarts = 0 }

    @Test fun a_tap_anywhere_on_an_immersive_page_brings_the_controls_back() {
        val page = Immersive()
        val gate = ImmersiveTouchGate()
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("page").immersiveTouchRestart(gate, isImmersive = { page.on }, restart = { page.on = false; page.restarts++ })) {
                Box(Modifier.size(1.dp))
            }
        }
        val spots = listOf<androidx.compose.ui.test.TouchInjectionScope.() -> androidx.compose.ui.geometry.Offset>(
            { topLeft }, { center }, { bottomCenter }, { bottomRight }, { androidx.compose.ui.geometry.Offset(width * 0.5f, height - 96.dp.toPx()) },
        )
        for (spot in spots) {
            page.on = true
            compose.waitForIdle()
            compose.onNodeWithTag("page").performTouchInput { click(spot()) }
            compose.waitForIdle()
            assertThat(page.on).isFalse()
        }
        assertThat(page.restarts).isAtLeast(spots.size)
    }

    @Test fun the_arrow_that_brought_the_controls_back_is_gone_and_the_dim_is_one_token() {
        val sheet = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/LyricsSheet.kt").readText()
        assertThat(sheet).doesNotContain("cd_show_controls")
        assertThat(sheet).doesNotContain("DapIconKind.ChevronUp")
        assertThat(sheet).contains("DapLyricsImmersive.DimAlpha")
        assertThat(sheet).contains("rememberLyricsListStates(currentSong?.id")
    }
}
