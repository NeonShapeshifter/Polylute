package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapPalette
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B42: the ink on a sheet's own copy of the glass, measured on the lightest and the darkest of it.
 *
 * A sheet draws the field frozen at **any** phase the root's clock can be at when it opens, under the sheet's veil of the theme's background
 * ([GlassScrim.SHEET_ALPHA]). Every cover of B41's sweep (white, black, grey, a pastel, a saturated one, a pink suit on white, a night with
 * red windows, and the four of the renders) is made into the field of every theme, and the glass the sheet shows (three layers, at nine
 * phases, under the veil) is sampled on a 90 x 200 grid. On it, and on what a sheet puts under its text (the theme's surfaces, the raised
 * surface, the see-through panel of the queue's bottom bar): 4.5:1 for text, 3:1 for a control, never less, with every one of the fifteen
 * accents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SheetGlassContrastTest {
    // B42-fix3 (item 7): the cover modes are always drawn on Graphite, with light ink; no other theme has a glass any more
    private val themes = listOf(DapBaseTheme.Graphite)

    private class Cover(val name: String, val pixels: IntArray, val w: Int = 96, val h: Int = 96)

    private fun solid(name: String, argb: Int) = Cover(name, IntArray(96 * 96) { argb })
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun withSpot(name: String, base: Int, spot: Int, cx: Int, cy: Int, radius: Int): Cover {
        val px = IntArray(96 * 96) { base }
        for (y in 0 until 96) for (x in 0 until 96) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= radius * radius) px[y * 96 + x] = spot
        }
        return Cover(name, px)
    }

    private val covers = listOf(
        solid("white", rgb(255, 255, 255)), solid("black", rgb(0, 0, 0)), solid("grey", rgb(128, 128, 128)),
        solid("light pastel", rgb(244, 198, 216)), solid("saturated", rgb(230, 20, 90)),
        withSpot("white with a pink suit", rgb(255, 255, 255), rgb(240, 70, 130), 30, 60, 20),
        withSpot("night with red windows", rgb(6, 10, 22), rgb(235, 25, 20), 66, 54, 22),
    ) + B41Renders.all.map { Cover("render ${it.name}", it.pixels, it.width, it.height) }

    private val phases = listOf(0f, 7f, 13f, 29f, 41f, 77f, 150f, 600f, 3_001f)

    private fun argbOf(c: Color): Int = (0xFF shl 24) or ((c.red * 255f + 0.5f).toInt() shl 16) or ((c.green * 255f + 0.5f).toInt() shl 8) or (c.blue * 255f + 0.5f).toInt()

    private data class Ink(val name: String, val color: Color, val needed: Float)

    private fun inksOf(look: GlassLook) = listOf(
        Ink("primary", look.ink.primary, 4.5f), Ink("secondary", look.ink.secondary, 4.5f), Ink("tertiary", look.ink.tertiary, 4.5f),
        Ink("accent as text", look.ink.accent, 4.5f), Ink("accent as a control", look.ink.accent, 3f),
    )

    /** What the glass a sheet shows is at ([px], [py]) of a 90 x 200 sheet, in the sheet's veil. */
    private fun sheetPixel(field: GlassField, phase: Float, palette: DapPalette, px: Int, py: Int): Int =
        GlassSample.pixel(field, GlassMotion.layers(phase), 90, 200, px, py, argbOf(palette.background), GlassScrim.SHEET_ALPHA)

    private fun over(pixel: Int, panel: Color): Int {
        val a = panel.alpha
        val p = argbOf(panel.copy(alpha = 1f))
        val r = (((pixel shr 16) and 0xFF) * (1 - a) + ((p shr 16) and 0xFF) * a).toInt()
        val g = (((pixel shr 8) and 0xFF) * (1 - a) + ((p shr 8) and 0xFF) * a).toInt()
        val b = ((pixel and 0xFF) * (1 - a) + (p and 0xFF) * a).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun fieldOf(cover: Cover, look: GlassLook): GlassField = GlassFieldMath.field(GlassFieldMath.grid(cover.pixels, cover.w, cover.h), look.band)

    private class Worst(var ratio: Float = Float.MAX_VALUE, var where: String = "")

    private fun measure(cover: Cover, theme: DapBaseTheme, accent: DapAccent, phases: List<Float>, worst: MutableMap<String, Worst>) {
        val palette = theme.palette
        val look = GlassBands.of(palette, palette.accentColor(accent))
        val field = fieldOf(cover, look)
        val glass = palette.onGlass(look).glass!!
        val inks = inksOf(look)
        for (phase in phases) for (py in 0 until 200 step 5) for (px in 0 until 90 step 3) {
            val pixel = sheetPixel(field, phase, palette, px, py)
            val grounds = mapOf("the glass" to pixel, "a panel on the glass" to over(pixel, glass.panel), "a raised panel on the glass" to over(pixel, glass.panelRaised))
            for ((groundName, ground) in grounds) {
                val y = GlassLuma.luminance(ground)
                for (ink in inks) {
                    val ratio = GlassLuma.contrast(ink.color.luminance(), y) / ink.needed
                    val key = "${ink.name} on $groundName"
                    val w = worst.getOrPut(key) { Worst() }
                    if (ratio < w.ratio) { w.ratio = ratio; w.where = "${theme.name} / ${cover.name} / ${accent.name} / phase $phase at $px,$py" }
                }
            }
        }
    }

    @Test
    fun `on a sheet's glass every ink is at AA on the lightest and the darkest of it, for every cover on every theme, at every phase`() {
        val worst = mutableMapOf<String, Worst>()
        for (theme in themes) for (cover in covers) measure(cover, theme, DapAccent.Sage, phases, worst)
        report(worst)
        for ((key, w) in worst) assertThat(w.ratio).isAtLeast(1f)
    }

    @Test
    fun `with every one of the fifteen accents`() {
        val worst = mutableMapOf<String, Worst>()
        val few = covers.filter { it.name in setOf("white", "black", "saturated", "night with red windows") }
        for (theme in themes) for (accent in DapAccent.entries) for (cover in few) measure(cover, theme, accent, listOf(0f, 41f, 600f), worst)
        report(worst)
        for ((key, w) in worst) assertThat(w.ratio).isAtLeast(1f)
    }

    private fun report(worst: Map<String, Worst>) {
        for ((key, w) in worst.toSortedMap()) println("B42 | worst contrast, $key: ${"%.2f".format(w.ratio)} x its floor (${w.where})")
    }

    /** What a sheet puts under its text beside the glass: the theme's own surfaces, which the glass's ink only moves away from. */
    @Test
    fun `the glass's ink reads on the theme's own surfaces a sheet puts under its text`() {
        for (theme in themes) for (accent in DapAccent.entries) {
            val palette = theme.palette
            val look = GlassBands.of(palette, palette.accentColor(accent))
            for (ink in inksOf(look)) for ((name, surface) in listOf("surface" to palette.surface, "raised" to palette.surfaceRaised, "background" to palette.background)) {
                val ratio = GlassLuma.contrast(ink.color.luminance(), argbLuminance(surface))
                assertThat(ratio).isAtLeast(ink.needed)
                assertThat(name).isNotEmpty()
            }
        }
    }

    private fun argbLuminance(c: Color) = GlassLuma.luminance(argbOf(c.copy(alpha = 1f)))

    @Test
    fun `a sheet's veil is stronger than a page's and weaker than nothing at all`() {
        assertThat(GlassScrim.SHEET_ALPHA).isGreaterThan(GlassScrim.PAGE_ALPHA)
        assertThat(GlassScrim.SHEET_ALPHA).isLessThan(1f)
    }
}
