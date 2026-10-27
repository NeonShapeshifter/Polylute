package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B41-fix1 (B5), the outside review of B41's R2 ported: **the first cover appears without the fade**. With no previous field the backdrop drew the
 * current one at full strength, so the 400 ms of the transition never showed (the progress was below 0.2 and the centre pixel was already the
 * settled one). The first field now fades in from the theme's own background; the fade between two covers, the review's positive control, is
 * what it was.
 *
 * It names nothing the batch added, so it compiles against the tree as it was before it, and `first_cover_field_fades_in_from_the_theme_background`
 * **fails there** (`before-b5-first-field.log` in `B41-fix1-evidence/`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B41Fix1FirstFieldFadeTest {
    @get:Rule
    val compose = createComposeRule()

    private val pinkPixels = IntArray(96 * 96) { 0xFFF296BD.toInt() }

    private val graphiteLook = GlassBands.of(DapBaseTheme.Graphite.palette, DapBaseTheme.Graphite.palette.accentColor(DapAccent.Sage))!!

    /**
     * Ported from the review (`first_cover_field_fades_in_from_the_theme_background` and its positive control): early in the 400 ms the first
     * field must not already be what it is when settled; the fade between two covers is the control.
     */
    private fun fieldFade(fromEmpty: Boolean) {
        val pinkField = GlassFieldMath.field(GlassFieldMath.grid(pinkPixels, 96, 96), graphiteLook.band)
        val bluePixels = IntArray(96 * 96) { 0xFF2288FF.toInt() }
        val blueField = GlassFieldMath.field(GlassFieldMath.grid(bluePixels, 96, 96), graphiteLook.band)
        val crossfade = GlassCrossfade()
        val clock = GlassClock()
        var shown by mutableStateOf(pinkField)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LaunchedEffect(shown) { crossfade.show(shown) }
            CoverGlassBackdrop(crossfade, clock, graphiteLook.band.base, expansion = { 0f })
        }
        if (!fromEmpty) {
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle { shown = blueField }
            repeat(12) {
                if (crossfade.current !== blueField) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                }
            }
            assertThat(crossfade.current).isSameInstanceAs(blueField)
        }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        assertThat(crossfade.current).isNotNull()
        assertThat(crossfade.blend).isLessThan(0.2f)
        val early = compose.onRoot().captureToImage().toPixelMap()
        val earlyPixel = early[early.width / 2, early.height / 2]
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertThat(crossfade.blend).isEqualTo(1f)
        val late = compose.onRoot().captureToImage().toPixelMap()
        val latePixel = late[late.width / 2, late.height / 2]
        println("B41-fix1 B5 | fromEmpty=$fromEmpty early=$earlyPixel settled=$latePixel")
        assertThat(earlyPixel).isNotEqualTo(latePixel)
        if (fromEmpty) {
            // from the theme's own background: early on the pixel is much nearer the base than the settled field is
            val base = graphiteLook.band.base
            fun dist(c: Color) = kotlin.math.abs(c.red - base.red) + kotlin.math.abs(c.green - base.green) + kotlin.math.abs(c.blue - base.blue)
            assertThat(dist(earlyPixel)).isLessThan(dist(latePixel) * 0.5f)
        }
    }

    @Test
    fun first_cover_field_fades_in_from_the_theme_background() = fieldFade(fromEmpty = true)

    @Test
    fun positive_control_one_cover_field_fades_to_a_different_cover_field() = fieldFade(fromEmpty = false)
}
