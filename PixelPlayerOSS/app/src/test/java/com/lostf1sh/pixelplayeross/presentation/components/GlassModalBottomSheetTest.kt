package com.lostf1sh.pixelplayeross.presentation.components

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42GlassApp
import com.lostf1sh.pixelplayeross.ui.glass.B42PlainApp
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42Setup
import com.lostf1sh.pixelplayeross.ui.glass.GlassLuma
import com.lostf1sh.pixelplayeross.ui.glass.GlassScrim
import com.lostf1sh.pixelplayeross.ui.glass.LocalCoverGlass
import com.lostf1sh.pixelplayeross.ui.glass.rememberB42Scene
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42: the app's bottom sheet in its own window ([GlassModalBottomSheet]), seen as pixels (the platform's `PixelCopy` of the sheet's window):
 *
 *  - in "Cover glass" its surface is its own copy of the glass: the pixels of the body are the glass under the sheet's veil, the drag handle
 *    sits on top of it, and the surface's shape cuts it at the rounded corners;
 *  - in the other two options of "App colours" it is Material's sheet exactly: the container colour the caller gave, to the pixel;
 *  - a cover that goes away while a sheet is open takes the glass out from under it and keeps what the sheet holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class GlassModalBottomSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val setup = B42Setup(B41Renders.saturated(), DapBaseTheme.Graphite)

    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF

    private fun scrimOnly(p: Int) = B42Renders.isScrimOnly(p)

    /** The row the sheet's top edge is at, found from the window's pixels: the first one that is not just the scrim, in column [x]. */
    private fun sheetTop(window: Bitmap, x: Int): Int {
        for (y in 0 until window.height) if (!scrimOnly(window.getPixel(x, y))) return y
        error("no sheet in the window")
    }

    private fun luminance(p: Int) = GlassLuma.luminance(p)

    /** The lowest and highest luminance the sheet's glass can have under its veil: the field's own pixels mixed toward the theme's background. */
    private fun glassRange(): ClosedFloatingPointRange<Float> {
        val base = setup.glass.band.base
        val a = GlassScrim.SHEET_ALPHA
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (p in setup.field.argb) {
            val mr = (r(p) / 255f) * (1f - a) + base.red * a
            val mg = (g(p) / 255f) * (1f - a) + base.green * a
            val mb = (b(p) / 255f) * (1f - a) + base.blue * a
            val l = GlassLuma.luminance(mr, mg, mb)
            lo = minOf(lo, l)
            hi = maxOf(hi, l)
        }
        return lo..hi
    }

    @Test
    fun `in Cover glass the sheet's surface is its own copy of the glass, the handle on top of it, cut at the corners by the shape`() {
        compose.setContent {
            val scene = rememberB42Scene(setup, 14f)
            B42GlassApp(setup, scene) {
                GlassModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Primary text on the sheet", color = Dap.primaryText)
                        Column(Modifier.height(220.dp)) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        val window = B42Renders.sheetWindow(compose)
        val top = sheetTop(window, 360)
        val range = glassRange()
        val bodyPixel = window.getPixel(690, top + 250)
        val bottomPixel = window.getPixel(690, window.height - 2)
        val surface = setup.palette.surface.toArgb()
        println("B42 | sheet top at $top px; body pixel luminance ${luminance(bodyPixel)}, glass range $range; theme surface ${luminance(surface)}")
        // the body is glass: a pixel inside what the veiled field can be, and not the theme's opaque surface
        assertThat(luminance(bodyPixel)).isAtLeast(range.start - 0.004f)
        assertThat(luminance(bodyPixel)).isAtMost(range.endInclusive + 0.004f)
        assertThat(bodyPixel and 0xFFFFFF).isNotEqualTo(surface and 0xFFFFFF)
        assertThat(bodyPixel and 0xFFFFFF).isNotEqualTo(setup.palette.background.toArgb() and 0xFFFFFF)
        // it reaches the bottom of the window (the insets under the content are the sheet's too)
        assertThat(scrimOnly(bottomPixel)).isFalse()
        assertThat(luminance(bottomPixel)).isAtLeast(range.start - 0.004f)
        assertThat(luminance(bottomPixel)).isAtMost(range.endInclusive + 0.004f)
        // the drag handle is on the glass, not under it: something in the top band of the sheet at the centre is lighter than any glass can be
        var lightest = 0f
        for (y in top until top + 90) lightest = maxOf(lightest, luminance(window.getPixel(360, y)))
        assertThat(lightest).isGreaterThan(range.endInclusive + 0.02f)
        // the shape cuts the glass: just inside the window's corner, above the sheet's rounded corner, only the scrim shows
        assertThat(scrimOnly(window.getPixel(2, top + 2))).isTrue()
        assertThat(scrimOnly(window.getPixel(window.width - 3, top + 2))).isTrue()
        // and a pixel a little further in, where the corner has curved away, is the sheet
        assertThat(scrimOnly(window.getPixel(80, top + 12))).isFalse()
    }

    @Test
    fun `without Cover glass it is Material's sheet exactly, the container the caller gave to the pixel`() {
        val given = Color(0xFF123456)
        compose.setContent {
            B42PlainApp(DapBaseTheme.Graphite, setup.look.palette) {
                GlassModalBottomSheet(
                    onDismissRequest = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = given,
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Primary text on the sheet", color = Dap.primaryText)
                        Column(Modifier.height(220.dp)) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        val window = B42Renders.sheetWindow(compose)
        val top = sheetTop(window, 360)
        assertThat(window.getPixel(690, top + 250) and 0xFFFFFF).isEqualTo(given.toArgb() and 0xFFFFFF)
        assertThat(window.getPixel(690, window.height - 2) and 0xFFFFFF).isEqualTo(given.toArgb() and 0xFFFFFF)
        assertThat(scrimOnly(window.getPixel(2, top + 2))).isTrue()
    }

    @Test
    fun `a cover that goes away while a sheet is open takes the glass out and keeps the sheet and what it holds`() {
        var glassOn by mutableStateOf(true)
        var taps by mutableIntStateOf(0)
        compose.setContent {
            val scene = rememberB42Scene(setup, 14f)
            B42GlassApp(setup, scene) {
                CompositionLocalProvider(LocalCoverGlass provides (if (glassOn) scene else null)) {
                    GlassModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        // a state that lives inside the sheet's content: a scroll position, a half-typed search
                        var inside by remember { mutableIntStateOf(0) }
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Text("inside=$inside", color = Dap.primaryText, modifier = Modifier.testTag("inside").clickable { inside++; taps++ })
                            Column(Modifier.height(220.dp)) {}
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        repeat(3) { compose.onNodeWithTag("inside").performClick() }
        compose.onNodeWithTag("inside").assertTextEquals("inside=3")
        val withGlass = B42Renders.sheetWindow(compose)
        val top = sheetTop(withGlass, 360)

        compose.runOnIdle { glassOn = false }
        compose.waitForIdle()
        // the same sheet, the same window, what it held is still there
        compose.onNodeWithTag("inside").assertTextEquals("inside=3")
        val without = B42Renders.sheetWindow(compose)
        val topAfter = sheetTop(without, 360)
        val container = setup.palette.background
        println("B42 | glass out from under an open sheet: top $top -> $topAfter, body ${Integer.toHexString(without.getPixel(690, topAfter + 250))}")
        assertThat(topAfter).isEqualTo(top)
        // opaque now, the container Material gives a sheet in this theme
        assertThat(without.getPixel(690, topAfter + 250) and 0xFFFFFF).isEqualTo(container.toArgb() and 0xFFFFFF)
        assertThat(taps).isEqualTo(3)
    }
}
