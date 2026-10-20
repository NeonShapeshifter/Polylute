package com.lostf1sh.pixelplayeross.presentation.lockscreen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B22, Part 2: while the lock screen covers the app, a song change does not recompose it or lay it out, and the app
 * comes back as it was.
 *
 * The app underneath is stood in for by a tree that reads "the playing song" the way the app does (a StateFlow through
 * `collectAsStateWithLifecycle`, the only way the project collects: see `NoPlainCollectAsStateTest`), counts every time
 * its composables run (a recomposition counter, as B21 did for the player's controls) and every time its layout is
 * measured. Both counters must stay where they were while covered, and move again when the cover goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class FreezeWhileCoveredTest {

    @get:Rule
    val compose = createComposeRule()

    private class Counters {
        var compositions = 0
        var measures = 0
    }

    private val song = MutableStateFlow("La Costa Del Silencio")
    private val counters = Counters()

    /** The stand-in for the app: its row reads the playing song, and its size depends on it. */
    @Composable
    private fun AppTree() {
        val title by song.collectAsStateWithLifecycle()
        // State the app owns, which is not the player's: it must survive being covered.
        var taps by remember { mutableIntStateOf(0) }
        SideEffect { counters.compositions++ }
        Box(Modifier.fillMaxSize().testTag("tree")) {
            Layout(
                content = { Text(title, modifier = Modifier.testTag("title")) },
                modifier = Modifier.testTag("row"),
            ) { measurables, constraints ->
                counters.measures++
                val p = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                layout(p.maxOf { it.width }, p.maxOf { it.height }) { p.forEach { it.place(0, 0) } }
            }
            Box(Modifier.size(80.dp).clickable { taps++ }.testTag("button")) { Text("taps=$taps") }
        }
    }

    @Test
    fun `a song change recomposes and lays out the app while it is uncovered`() {
        compose.setContent { FreezeWhileCovered(frozen = false, hidden = false, modifier = Modifier.fillMaxSize()) { AppTree() } }
        compose.waitForIdle()
        val compositions = counters.compositions
        val measures = counters.measures
        song.value = "Molinos De Viento"
        compose.waitForIdle()
        compose.onNodeWithText("Molinos De Viento").assertExists()
        assertThat(counters.compositions).isGreaterThan(compositions)
        assertThat(counters.measures).isGreaterThan(measures)
    }

    @Test
    fun `while covered a song change recomposes nothing and lays nothing out, and when the cover goes the app reads the latest song`() {
        var frozen by mutableStateOf(false)
        var hidden by mutableStateOf(false)
        compose.setContent { FreezeWhileCovered(frozen = frozen, hidden = hidden, modifier = Modifier.fillMaxSize()) { AppTree() } }
        compose.waitForIdle()

        frozen = true; hidden = true
        compose.waitForIdle()
        val compositions = counters.compositions
        val measures = counters.measures

        // Five song changes with the lock screen up, as in the reviewer's measurement.
        for (n in 1..5) {
            song.value = "Canción $n"
            compose.waitForIdle()
        }
        assertThat(counters.compositions).isEqualTo(compositions)
        assertThat(counters.measures).isEqualTo(measures)

        // The cover goes: the app hears again, and shows the last one, in one go.
        frozen = false; hidden = false
        compose.waitForIdle()
        compose.onNodeWithText("Canción 5").assertExists()
        assertThat(counters.compositions).isGreaterThan(compositions)
    }

    @Test
    fun `while the surface is being swiped away the app is placed again, still quiet, as it was`() {
        var frozen by mutableStateOf(false)
        var hidden by mutableStateOf(false)
        compose.setContent { FreezeWhileCovered(frozen = frozen, hidden = hidden, modifier = Modifier.fillMaxSize()) { AppTree() } }
        compose.waitForIdle()

        frozen = true; hidden = true
        compose.waitForIdle()
        // Hidden: the tree keeps its size from before and is not placed, so it is not drawn and nothing is laid out for it.
        assertThat(compose.onNodeWithTag("tree").fetchSemanticsNode().layoutInfo.isPlaced).isFalse()

        // Revealing: placed (visible under the sliding surface) but not listening.
        hidden = false
        compose.waitForIdle()
        assertThat(compose.onNodeWithTag("tree").fetchSemanticsNode().layoutInfo.isPlaced).isTrue()
        val compositions = counters.compositions
        song.value = "Mientras se desliza"
        compose.waitForIdle()
        assertThat(counters.compositions).isEqualTo(compositions)
        compose.onNodeWithText("La Costa Del Silencio").assertExists()
    }

    @Test
    fun `the app comes back exactly as it was - what it remembered is still there`() {
        var frozen by mutableStateOf(false)
        compose.setContent { FreezeWhileCovered(frozen = frozen, hidden = frozen, modifier = Modifier.fillMaxSize()) { AppTree() } }
        compose.onNodeWithTag("button").performClick()
        compose.onNodeWithTag("button").performClick()
        compose.onNodeWithText("taps=2").assertExists()

        frozen = true
        compose.waitForIdle()
        song.value = "Otra"
        compose.waitForIdle()
        frozen = false
        compose.waitForIdle()
        // Nothing was removed from the composition: the tab, the scroll position and every `remember` are the same.
        compose.onNodeWithText("taps=2").assertExists()
        compose.onNodeWithText("Otra").assertExists()
    }

    // ── the capped lifecycle ─────────────────────────────────────────────────────────────────────────

    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `the cap holds the lifecycle at CREATED and lifts it again`() {
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val owner = CappedLifecycleOwner(host.lifecycle)
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
        owner.frozen = true
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        owner.frozen = false
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun `the activity going down is followed, with or without the cap, and the cap never raises it`() {
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val owner = CappedLifecycleOwner(host.lifecycle)
        host.registry.currentState = Lifecycle.State.CREATED // stopped
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        owner.frozen = true
        host.registry.currentState = Lifecycle.State.STARTED
        // Frozen: still held at CREATED although the activity is started.
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        owner.frozen = false
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
        owner.frozen = true
        host.registry.currentState = Lifecycle.State.DESTROYED
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
        owner.frozen = false
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
    }
    @Test
    fun `the wake cycle's cover holds the lifecycle by itself and either hold is enough`() {
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val owner = CappedLifecycleOwner(host.lifecycle)
        owner.coverHeld = true
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        // the surface comes on screen while the cover is held: still held
        owner.frozen = true
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        // one hold is released and the other is not
        owner.coverHeld = false
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        owner.frozen = false
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun `a cover set while the activity is stopped keeps the app down through the whole wake`() {
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val owner = CappedLifecycleOwner(host.lifecycle)
        // SCREEN_OFF arms the cycle (the cover), then the activity stops, then it starts and resumes at the wake
        owner.coverHeld = true
        host.registry.currentState = Lifecycle.State.CREATED
        host.registry.currentState = Lifecycle.State.STARTED
        host.registry.currentState = Lifecycle.State.RESUMED
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        owner.coverHeld = false
        assertThat(owner.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }
}
