package com.lostf1sh.pixelplayeross.presentation.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42GlassApp
import com.lostf1sh.pixelplayeross.ui.glass.B42Setup
import com.lostf1sh.pixelplayeross.ui.glass.CoverGlassScene
import com.lostf1sh.pixelplayeross.ui.glass.StillGlassBackdrop
import com.lostf1sh.pixelplayeross.ui.glass.rememberB42Scene
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42: the queue's own copy of the glass ([QueueGlass]), on its own, with the two things the queue sheet tells it (is it open, how far) as
 * plain states the test moves.
 *
 *  - folded it is the mini player's opaque cover-coloured shelf, to the pixel;
 *  - open it is the glass of the instant it started to open (a reference copy, taken at that phase, is the same pixels), however long the
 *    sheet was closed before and however far the player's glass moved meanwhile;
 *  - and it does not move while it is open, nor is it recomposed by the player's glass stepping.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class QueueGlassTest {
    @get:Rule
    val compose = createComposeRule()

    private val setup = B42Setup(B41Renders.saturated(), DapBaseTheme.Graphite)

    private var open by mutableStateOf(false)
    private var fold by mutableFloatStateOf(0f)
    private var scene: CoverGlassScene? = null

    /** The queue's glass in the top half and, under it, a reference copy of the glass taken at [referencePhase], the same size. */
    private fun show(bootPhase: Float, referencePhase: Float, withFold: Boolean = true) {
        compose.setContent {
            val s = rememberB42Scene(setup, bootPhase)
            scene = s
            B42GlassApp(setup, s) {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(400.dp).clipToBounds()) {
                        QueueGlass(s, open = open, foldProgress = if (withFold) ({ fold }) else ({ 1f }))
                    }
                    Box(Modifier.fillMaxWidth().height(400.dp).clipToBounds()) {
                        val reference = androidx.compose.runtime.remember(s, referencePhase) {
                            com.lostf1sh.pixelplayeross.ui.glass.GlassClock().also { it.advance(referencePhase) }
                        }
                        StillGlassBackdrop(s, reference)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun rows(shot: Bitmap, from: Int, to: Int): IntArray {
        val out = IntArray(shot.width * (to - from))
        shot.getPixels(out, 0, shot.width, 0, from, shot.width, to - from)
        return out
    }

    private fun diff(a: IntArray, b: IntArray): Int {
        var worst = 0
        for (i in a.indices) for (shift in intArrayOf(16, 8, 0)) worst = maxOf(worst, Math.abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)))
        return worst
    }

    /** The queue's pixels and the reference's, read from one capture of the window. */
    private fun halves(): Pair<IntArray, IntArray> {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        // two boxes of 400 dp, one over the other, at the window's density
        val box = (400 * compose.density.density).toInt()
        return rows(shot, 0, box) to rows(shot, box, 2 * box)
    }

    private fun openQueue() {
        compose.runOnIdle { open = true; fold = 1f }
        compose.waitForIdle()
    }

    @Test
    fun `folded, the queue is the same opaque cover colour as the mini player`() {
        show(bootPhase = 14f, referencePhase = 14f)
        val (queue, _) = halves()
        val surface = scene!!.shelf.toArgb()
        val distinct = queue.map { it and 0xFFFFFF }.toSet()
        assertThat(distinct).containsExactly(surface and 0xFFFFFF)
    }

    @Test
    fun `open, the queue is the glass of the instant it started to open, however far the player's glass moved while it was closed`() {
        show(bootPhase = 14f, referencePhase = 61f)
        // closed for a while: the player's glass moved on (the sheet is not showing, so nothing is drawn of it)
        compose.runOnIdle { scene!!.clock.advance(47f) }
        compose.waitForIdle()
        openQueue()
        val (queue, reference) = halves()
        val worst = diff(queue, reference)
        println("B42 | the queue opened at phase 61 against a reference taken at 61: ${worst} levels apart")
        assertThat(worst).isAtMost(1)
        // and it is not the glass of the phase it was composed at
        val atBoot = com.lostf1sh.pixelplayeross.ui.glass.GlassClock().also { it.advance(14f) }
        assertThat(atBoot.phase).isEqualTo(14f)
    }

    @Test
    fun `it is taken again at each opening, not kept from the first`() {
        show(bootPhase = 14f, referencePhase = 14f)
        openQueue()
        val (firstOpen, firstReference) = halves()
        assertThat(diff(firstOpen, firstReference)).isAtMost(1)
        compose.runOnIdle { open = false; fold = 0f }
        compose.waitForIdle()
        compose.runOnIdle { scene!!.clock.advance(300f) }
        compose.waitForIdle()
        openQueue()
        val (secondOpen, _) = halves()
        // the second opening is at phase 314, not 14: the queue is a different arrangement of the same colours
        val moved = diff(firstOpen, secondOpen)
        println("B42 | the queue opened a second time, 300 s later: ${moved} levels from the first opening")
        assertThat(moved).isAtLeast(3)
    }

    @Test
    fun `open, it does not move while the player's glass steps on for a minute`() {
        show(bootPhase = 14f, referencePhase = 14f)
        openQueue()
        val (before, _) = halves()
        repeat(60) {
            compose.runOnIdle { repeat(60) { scene!!.clock.advance(1f / 60f) } }
            compose.waitForIdle()
        }
        assertThat(scene!!.clock.phase).isWithin(0.5f).of(74f)
        val (after, _) = halves()
        assertThat(diff(before, after)).isEqualTo(0)
    }

    @Test
    fun `closing does not jump the glass, which stays the one it opened with until it has gone`() {
        show(bootPhase = 14f, referencePhase = 14f)
        openQueue()
        compose.runOnIdle { scene!!.clock.advance(30f) }
        compose.waitForIdle()
        compose.runOnIdle { fold = 0.6f }
        compose.waitForIdle()
        val (opening, _) = halves()
        // closing starts: the sheet is no longer "open", it is still 60 % up
        compose.runOnIdle { open = false }
        compose.waitForIdle()
        val (closing, _) = halves()
        assertThat(diff(opening, closing)).isEqualTo(0)
    }

    @Test
    fun `a sheet with no folded state, the landscape player's, takes the glass when it opens`() {
        show(bootPhase = 14f, referencePhase = 71f, withFold = false)
        compose.runOnIdle { scene!!.clock.advance(57f) }
        compose.waitForIdle()
        compose.runOnIdle { open = true }
        compose.waitForIdle()
        val (queue, reference) = halves()
        assertThat(diff(queue, reference)).isAtMost(1)
    }

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
    fun `the player's glass stepping on every frame recomposes nothing of the queue's, open or folded`() {
        show(bootPhase = 14f, referencePhase = 14f)
        val counter = Counter()
        Composer.setTracer(counter)
        try {
            repeat(60) { compose.runOnIdle { scene!!.clock.advance(1f / 60f) }; compose.waitForIdle() }
            openQueue()
            counter.runs.clear()
            repeat(120) { compose.runOnIdle { scene!!.clock.advance(1f / 60f) }; compose.waitForIdle() }
            val recomposed = counter.runs.filterKeys { it.startsWith("QueueGlass") || it.startsWith("StillGlass") || it.startsWith("GlassField") }
            println("B42 | recompositions of the queue's glass in 120 steps of the player's: $recomposed")
            assertThat(recomposed).isEmpty()
        } finally {
            Composer.setTracer(object : CompositionTracer {
                override fun isTraceInProgress() = false
                override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {}
                override fun traceEventEnd() {}
            })
        }
    }

    @Test
    fun `without glass it draws nothing at all`() {
        compose.setContent {
            Box(Modifier.fillMaxWidth().height(400.dp)) { QueueGlass(null, open = true, foldProgress = { 1f }) }
        }
        compose.waitForIdle()
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        assertThat(rows(shot, 0, shot.height).toSet()).hasSize(1)
    }
}
