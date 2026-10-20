package com.lostf1sh.pixelplayeross.presentation.components.scoped

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B23-P3: the collapsed full player, parked offscreen (`fullPlayerOffset` = y 10,000), is neither recomposed nor laid out by a tap on
 * a row in the Library or by a skip, and it shows the right song when it opens.
 *
 * B22 measured two frames of 160-630 ms for every tap (`measureAndLayout` 140-230 ms, `Recomposer` 40-174 ms) while media-next cost none.
 * Its own open point was that the collapsed full player was still composed *and measured* off screen. The player is stood in for by
 * a tree built the way the real one is fed: its song and queue index come in as parameters (here the same `rememberHeldWhile` the
 * sheet uses), it also collects a flow with its lifecycle, and it counts every time it is composed and every time it is measured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class ParkedWhileCollapsedTest {

    @get:Rule
    val compose = createComposeRule()

    private class Counters {
        var compositions = 0
        var measures = 0
        var collected = 0
    }

    private val counters = Counters()
    private val flowSong = MutableStateFlow("flow-0")

    /** What the sheet's parent holds: the live song, and whether the sheet is collapsed. */
    private var parked by mutableStateOf(false)
    private var liveSong by mutableStateOf("song-0")
    private var liveIndex by mutableIntStateOf(0)

    @Composable
    private fun FullPlayerStandIn(song: String, index: Int) {
        val fromFlow by flowSong.collectAsStateWithLifecycle()
        var taps by remember { mutableIntStateOf(0) }
        SideEffect { counters.compositions++ }
        // every distinct value of the flow this player is shown
        LaunchedEffect(fromFlow) { counters.collected++ }
        Box(Modifier.fillMaxSize().testTag("player")) {
            Layout(
                content = { Text("$song@$index/$fromFlow", modifier = Modifier.testTag("title")) },
                modifier = Modifier.testTag("carousel"),
            ) { measurables, constraints ->
                counters.measures++
                val p = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                layout(p.maxOf { it.width }, p.maxOf { it.height }) { p.forEach { it.place(0, 0) } }
            }
            Box(Modifier.size(80.dp).clickable { taps++ }.testTag("button")) { Text("taps=$taps") }
        }
    }

    private fun setSheet(startParked: Boolean = false) {
        parked = startParked
        compose.setContent {
            val heldSong = rememberHeldWhile(parked, liveSong)
            val heldIndex = rememberHeldWhile(parked, liveIndex)
            ParkedWhileCollapsed(parked = parked, modifier = Modifier.fillMaxSize()) { FullPlayerStandIn(heldSong, heldIndex) }
        }
        compose.waitForIdle()
        // the warm-up: two frames after the first composition
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    @Test
    fun `open, a song change recomposes and lays out the player (the baseline the parking removes)`() {
        setSheet()
        val compositions = counters.compositions
        val measures = counters.measures
        liveSong = "a song with a much longer title"; liveIndex = 1
        compose.waitForIdle()
        compose.onNodeWithText("a song with a much longer title@1/flow-0").assertExists()
        assertThat(counters.compositions).isGreaterThan(compositions)
        assertThat(counters.measures).isGreaterThan(measures)
    }

    @Test
    fun `parked, a tap on a row and a song change cost zero compositions and zero measure passes`() {
        setSheet()
        parked = true
        compose.waitForIdle()
        val compositions = counters.compositions
        val measures = counters.measures
        val collected = counters.collected

        // Five taps on rows of the Library (each a seek to a distant index) and a skip: the song and the index the player
        // would be given change six times, and so does the flow it collects.
        for (n in 1..5) {
            liveSong = "tap-$n"; liveIndex = n * 900
            flowSong.value = "flow-$n"
            compose.waitForIdle()
        }
        liveSong = "next"; liveIndex = 4501
        compose.waitForIdle()

        assertThat(counters.compositions).isEqualTo(compositions)
        assertThat(counters.measures).isEqualTo(measures)
        assertThat(counters.collected).isEqualTo(collected)
        // not placed either: it is not laid out and not drawn while it is parked
        assertThat(compose.onNodeWithTag("player").fetchSemanticsNode().layoutInfo.isPlaced).isFalse()
    }

    @Test
    fun `opening it afterwards shows the current song and index, in one go`() {
        setSheet()
        parked = true
        compose.waitForIdle()
        for (n in 1..5) { liveSong = "tap-$n"; liveIndex = n * 900; flowSong.value = "flow-$n"; compose.waitForIdle() }
        val compositions = counters.compositions

        parked = false
        compose.waitForIdle()
        compose.onNodeWithText("tap-5@4500/flow-5").assertExists()
        assertThat(compose.onNodeWithTag("player").fetchSemanticsNode().layoutInfo.isPlaced).isTrue()
        // caught up, not replayed: the player did not step through the five songs it missed
        assertThat(counters.compositions - compositions).isAtMost(3)
    }

    @Test
    fun `what the player remembered is still there after it was parked and opened`() {
        setSheet()
        compose.onNodeWithTag("button").performClick()
        compose.onNodeWithTag("button").performClick()
        compose.onNodeWithText("taps=2").assertExists()
        parked = true
        compose.waitForIdle()
        liveSong = "while-parked"; compose.waitForIdle()
        parked = false
        compose.waitForIdle()
        compose.onNodeWithText("taps=2").assertExists()
        compose.onNodeWithText("while-parked@0/flow-0", substring = true).assertExists()
    }

    @Test
    fun `a player that is parked from its very first composition is measured once, so the first expand does not pay for it`() {
        // The 650 ms warm latch composes the full player while the sheet is collapsed: it must still be laid out once there.
        setSheet(startParked = true)
        assertThat(counters.measures).isAtLeast(1)
        val measures = counters.measures
        liveSong = "later"; compose.waitForIdle()
        assertThat(counters.measures).isEqualTo(measures)       // and then it is parked for real
        assertThat(compose.onNodeWithTag("player").fetchSemanticsNode().layoutInfo.isPlaced).isFalse()
    }

    @Test
    fun `the held value is the last live one, and the first composition takes the live one`() {
        var flag by mutableStateOf(true)
        var value by mutableStateOf("a")
        var seen = ""
        compose.setContent { seen = rememberHeldWhile(flag, value) }
        compose.waitForIdle()
        assertThat(seen).isEqualTo("a")          // nothing older to hold, even when held from the start
        value = "b"; compose.waitForIdle()
        assertThat(seen).isEqualTo("a")
        flag = false; compose.waitForIdle()
        assertThat(seen).isEqualTo("b")
        value = "c"; compose.waitForIdle()
        assertThat(seen).isEqualTo("c")
    }
}
