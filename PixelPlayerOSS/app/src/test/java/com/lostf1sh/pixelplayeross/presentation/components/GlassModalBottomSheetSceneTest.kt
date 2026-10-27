package com.lostf1sh.pixelplayeross.presentation.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42Setup
import com.lostf1sh.pixelplayeross.ui.glass.CoverGlassBackdrop
import com.lostf1sh.pixelplayeross.ui.glass.CoverGlassFields
import com.lostf1sh.pixelplayeross.ui.glass.CoverGlassScene
import com.lostf1sh.pixelplayeross.ui.glass.GlassMotion
import com.lostf1sh.pixelplayeross.ui.glass.LocalCoverGlass
import com.lostf1sh.pixelplayeross.ui.glass.GlassKind
import com.lostf1sh.pixelplayeross.ui.glass.rememberCoverGlassScene
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42: the real bottom sheet, in its own window, over the scene the app builds (`rememberCoverGlassScene`: the cover settles, the field is made
 * once and cached, a song change cross-fades), seen as the pixels of the sheet's window.
 *
 *  - opening and closing the sheet builds nothing: no decode, no field, no transition;
 *  - a song change while the sheet is open is **one** transition, and the sheet follows it **in step**: at every moment of the fade its pixels are
 *    the old glass and the new one in the proportion the fade says (the app's own glass behind it is the same fade);
 *  - the player's glass stepping on for a minute changes nothing of what the sheet shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class GlassModalBottomSheetSceneTest {
    @get:Rule
    val compose = createComposeRule()

    private val setup = B42Setup(B41Renders.saturated(), DapBaseTheme.Graphite)

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun cover(seed: Int) = Triple(96, 96, IntArray(96 * 96) { i -> rgb((i % 96 * 2 + seed * 40) % 256, (i / 96 * 2 + seed * 70) % 256, (seed * 90) % 256) })

    private val fields = CoverGlassFields(mockk<Context>(relaxed = true)).also { f -> f.decoder = { uri -> cover(uri.hashCode() and 7) } }
    private val uris = MutableStateFlow<String?>("content://art/one")
    private var sheetOpen by mutableStateOf(false)
    private var scene: CoverGlassScene? = null

    private fun show() {
        compose.setContent {
            val s = rememberCoverGlassScene(GlassKind.GLASS, setup.glass.band, uris, fields)
            scene = s
            DapTheme(darkTheme = true, baseTheme = setup.theme, accent = setup.accent, palette = setup.palette, accentColorOverride = setup.glass.ink.accent) {
                CompositionLocalProvider(LocalCoverGlass provides (if (s.visible) s else null)) {
                    Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) {
                            CoverGlassBackdrop(s.crossfade, s.clock, s.base, expansion = { 0f })
                            if (sheetOpen) {
                                GlassModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                                    Column(Modifier.padding(24.dp)) {
                                        Text("A sheet", color = Dap.primaryText)
                                        Column(Modifier.height(260.dp)) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { scene?.crossfade?.current != null }
        compose.waitUntil(10_000) { scene?.crossfade?.previous == null && scene?.crossfade?.blend == 1f }
    }

    @Test
    fun `opening and closing the sheet again and again builds no field and makes no transition`() {
        show()
        val decodes = fields.decodes.get()
        val builds = fields.builds.get()
        val transitions = scene!!.crossfade.transitions
        assertThat(builds).isEqualTo(1)
        repeat(5) {
            compose.runOnIdle { sheetOpen = true }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            compose.runOnIdle { sheetOpen = false }
            compose.waitForIdle()
        }
        println("B42 | five opens and closes of the real sheet: decodes $decodes -> ${fields.decodes.get()}, builds $builds -> ${fields.builds.get()}, transitions $transitions -> ${scene!!.crossfade.transitions}")
        assertThat(fields.decodes.get()).isEqualTo(decodes)
        assertThat(fields.builds.get()).isEqualTo(builds)
        assertThat(scene!!.crossfade.transitions).isEqualTo(transitions)
    }

    private fun channel(p: Int, shift: Int) = (p shr shift) and 0xFF

    @Test
    fun `a song change while the sheet is open is one transition and the sheet follows it in step`() {
        show()
        compose.runOnIdle { sheetOpen = true }
        val before = B42Renders.sheetWindow(compose)
        val x = 690
        val y = before.height - 250
        val glassBefore = before.getPixel(x, y)
        val transitions = scene!!.crossfade.transitions
        val builds = fields.builds.get()

        // the song changes: the cover settles, the field is made once (off the main thread), then one cross-fade
        compose.mainClock.autoAdvance = false
        uris.value = "content://art/two"
        compose.mainClock.advanceTimeBy(GlassMotion.SETTLE_MS + 100)
        compose.waitUntil(10_000) { scene!!.crossfade.transitions == transitions + 1 }
        val samples = mutableListOf<Triple<Float, Int, Int>>()   // blend, the sheet's pixel
        repeat(8) {
            compose.mainClock.advanceTimeBy(50)
            val blend = scene!!.crossfade.blend
            val window = B42Renders.sheetWindowNow(compose)
            samples += Triple(blend, window.getPixel(x, y), 0)
        }
        compose.mainClock.advanceTimeBy(GlassMotion.CROSSFADE_MS + 200L)
        compose.mainClock.autoAdvance = true
        val glassAfter = B42Renders.sheetWindow(compose).getPixel(x, y)

        assertThat(scene!!.crossfade.transitions).isEqualTo(transitions + 1)
        assertThat(fields.builds.get()).isEqualTo(builds + 1)
        // the two glasses are not the same colour, or the test would prove nothing
        val span = (0..2).maxOf { Math.abs(channel(glassBefore, it * 8) - channel(glassAfter, it * 8)) }
        assertThat(span).isAtLeast(6)
        var inStep = 0
        var worst = 0
        for ((blend, pixel, _) in samples) {
            // the old glass and the new one in the proportion the fade says (the old stack is drawn at full strength under the new)
            val worstChannel = (0..2).maxOf { c ->
                val shift = c * 8
                val expected = channel(glassBefore, shift) + (channel(glassAfter, shift) - channel(glassBefore, shift)) * blend
                Math.ceil(Math.abs(channel(pixel, shift) - expected).toDouble()).toInt()
            }
            worst = maxOf(worst, worstChannel)
            if (blend in 0.05f..0.95f) inStep++
        }
        println("B42 | through the fade the sheet is at most $worst levels from the proportion the fade says ($inStep samples mid-fade)")
        assertThat(inStep).isAtLeast(3)
        assertThat(worst).isAtMost(3)
    }

    @Test
    fun `the player's glass stepping on for a minute changes nothing of the sheet's window`() {
        show()
        compose.runOnIdle { sheetOpen = true }
        val before = B42Renders.sheetWindow(compose)
        repeat(60) {
            compose.runOnIdle { repeat(60) { scene!!.clock.advance(1f / 60f) } }
            compose.waitForIdle()
        }
        assertThat(scene!!.clock.phase).isWithin(0.5f).of(60f)
        val after = B42Renders.sheetWindow(compose)
        var different = 0
        for (yy in 0 until before.height) for (xx in 0 until before.width) if (before.getPixel(xx, yy) != after.getPixel(xx, yy)) different++
        println("B42 | the sheet's window after a minute of the player's glass: $different pixels of ${before.width * before.height} changed")
        assertThat(different).isEqualTo(0)
    }
}
