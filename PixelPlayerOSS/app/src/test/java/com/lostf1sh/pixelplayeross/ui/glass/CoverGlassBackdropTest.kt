package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B41: what the glass costs while the app runs.
 *  - it is drawn **once**, behind the root: scrolling a long list over it never redraws it (counted);
 *  - nothing moves outside the full player and the lock screen, and it moves only while a song plays and the player (or lock screen) is
 *    in front, the screen is on, the activity is resumed, and the system allows animation: for each of those, a run of the real host with
 *    the condition off is **no ticks at all** (the ticker is never even started);
 *  - while it moves it is stepped on every frame of the display (B41-fix1, B4), and a step recomposes nothing.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
class CoverGlassBackdropTest {
    @get:Rule
    val compose = createComposeRule()

    private val palette = DapBaseTheme.Graphite.palette
    private val look = GlassBands.of(palette, palette.accentColor(DapAccent.Sage))

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val field: GlassField by lazy {
        val px = IntArray(96 * 96) { i -> rgb((i % 96) * 2, 40 + (i / 96), 200 - (i % 96)) }
        GlassFieldMath.field(GlassFieldMath.grid(px, 96, 96), look.band)
    }

    // ── drawn once ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the glass is drawn once, and scrolling a long list over it never draws it again`() {
        val crossfade = GlassCrossfade()
        val clock = GlassClock()
        var draws = 0
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            listState = rememberLazyListState()
            LaunchedEffect(Unit) { crossfade.show(field) }
            Box(Modifier.fillMaxSize()) {
                CoverGlassBackdrop(crossfade, clock, look.band.base, expansion = { 0f }, onFieldDrawn = { draws++ })
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items((0 until 400).toList()) { i -> Text("Song $i · a long Library list of rows over the glass") }
                }
            }
        }
        compose.waitUntil(10_000) { crossfade.current != null }
        compose.mainClock.advanceTimeBy(GlassMotion.CROSSFADE_MS + 100L)
        compose.waitForIdle()
        compose.onRoot().captureToImage()
        val afterFirstDraw = draws
        assertThat(afterFirstDraw).isGreaterThan(0)
        // fling the list through three hundred rows, drawing the whole window each time
        for (step in 1..30) {
            compose.runOnIdle { kotlinx.coroutines.runBlocking { listState.scrollToItem(step * 10) } }
            compose.waitForIdle()
            compose.onRoot().captureToImage()
        }
        assertThat(listState.firstVisibleItemIndex).isAtLeast(250)
        println("B41 | field layer draws: $afterFirstDraw after the first frame, ${draws} after 30 scroll steps over 300 rows")
        assertThat(draws).isEqualTo(afterFirstDraw)
    }

    // ── when it moves ───────────────────────────────────────────────────────────────────────────────

    private val ok = GlassEnvironment(mutableStateOf(true), mutableStateOf(true), mutableStateOf(true))

    private class Run(val phase: Float, val ticks: Int)

    /** Runs the real host for [seconds] under [conditions]; the phase the clock reached, and how many steps were taken. */
    private fun host(
        playing: Boolean = true,
        expansion: Float = 1f,
        lockVisible: Boolean = false,
        movingOn: Boolean = true,
        environment: GlassEnvironment = ok,
        glass: Boolean = true,
        seconds: Int = 2,
    ): Run {
        val crossfade = GlassCrossfade()
        val clock = GlassClock()
        var ticks = 0
        // a loop that waits for the display's next frame is an animation without an end: the test must not wait for the screen to be idle
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LaunchedEffect(Unit) { if (glass) crossfade.show(field) }
            CoverGlassHost(
                scene = CoverGlassScene(crossfade, clock, look.band.base),
                playing = flowOf(playing),
                playerExpansion = { expansion },
                movingBackgroundOn = movingOn,
                lockSurfaceVisible = lockVisible,
                lockCovers = false,
                environment = environment,
                onStep = { ticks++ },
            )
        }
        compose.mainClock.autoAdvance = false
        if (glass) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitUntil(10_000) { crossfade.current != null }
        }
        ticks = 0
        val start = clock.phase
        compose.mainClock.advanceTimeBy(seconds * 1000L)
        return Run(clock.phase - start, ticks)
    }

    @Test
    fun `in the full player, while a song plays, it moves on every frame the display draws`() {
        val run = host()
        // the test's display draws a frame every 16 ms: 62 to 63 a second
        println("B41-fix1 | moving: ${run.ticks} steps in 2 s of virtual time = ${run.ticks / 2} per second; the clock advanced ${run.phase} s")
        // two seconds of motion in two seconds
        assertThat(run.phase).isWithin(0.2f).of(2f)
        // one step per frame, not one in three (B41 capped it at 20 a second)
        assertThat(run.ticks).isAtLeast(2 * 58)
    }

    @Test
    fun `on the lock screen, while a song plays, it moves too`() {
        val run = host(expansion = 0f, lockVisible = true)
        assertThat(run.phase).isWithin(0.2f).of(2f)
    }

    private fun assertStill(why: String, run: Run) {
        assertThat(run.phase).isEqualTo(0f)
        // not just a clock that did not move: no step was taken at all, so nothing woke
        assertThat(run.ticks).isEqualTo(0)
        assertThat(why).isNotEmpty()
    }

    @Test
    fun `outside the full player and the lock screen nothing moves, even while a song plays`() {
        assertStill("Home, Library, Search, Settings, the mini player", host(expansion = 0f))
    }

    @Test
    fun `a paused player holds still, and costs nothing`() = assertStill("paused", host(playing = false))

    @Test
    fun `with the screen off it holds still, and a dozing lock screen too`() {
        val off = GlassEnvironment(ok.resumed, mutableStateOf(false), ok.animationsAllowed)
        assertStill("screen off", host(environment = off))
    }

    @Test
    fun `with the app not in front it holds still`() {
        val away = GlassEnvironment(mutableStateOf(false), ok.interactive, ok.animationsAllowed)
        assertStill("the player is hidden behind another screen", host(environment = away))
    }

    @Test
    fun `with Remove animations or Battery saver on it holds still`() {
        val saver = GlassEnvironment(ok.resumed, ok.interactive, mutableStateOf(false))
        assertStill("animations off", host(environment = saver))
    }

    @Test
    fun `with Moving background off it holds still`() = assertStill("the switch", host(movingOn = false))

    @Test
    fun `with no glass to show it holds still`() = assertStill("no cover", host(glass = false))

    // ── what a step costs ───────────────────────────────────────────────────────────────────────────

    private class Counter : CompositionTracer {
        val runs = mutableMapOf<String, Int>()
        override fun isTraceInProgress(): Boolean = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(" (").substringAfterLast('.')
            runs[name] = (runs[name] ?: 0) + 1
        }
        override fun traceEventEnd() {}
    }

    @Test
    fun `a step of the glass recomposes nothing`() {
        val crossfade = GlassCrossfade()
        val clock = GlassClock()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LaunchedEffect(Unit) { crossfade.show(field) }
            CoverGlassHost(
                CoverGlassScene(crossfade, clock, look.band.base), flowOf(true), { 1f }, true, false, false,
                environment = ok,
            )
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.waitUntil(10_000) { crossfade.current != null }
        compose.mainClock.advanceTimeBy(GlassMotion.CROSSFADE_MS + 100L)
        val counter = Counter()
        Composer.setTracer(counter)
        try {
            compose.mainClock.advanceTimeBy(3_000)
            assertThat(clock.phase).isGreaterThan(2.5f)
            val recomposed = counter.runs.filterKeys { it.startsWith("Glass") || it.startsWith("CoverGlass") }
            println("B41 | recompositions in 3 s of motion: $recomposed")
            assertThat(recomposed).isEmpty()
        } finally {
            Composer.setTracer(object : CompositionTracer {
                override fun isTraceInProgress() = false
                override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {}
                override fun traceEventEnd() {}
            })
        }
    }
}
