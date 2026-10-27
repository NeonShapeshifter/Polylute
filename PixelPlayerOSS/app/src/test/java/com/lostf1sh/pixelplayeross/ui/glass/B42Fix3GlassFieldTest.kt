package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * B42-fix3 (item 7): **the cover decides, and the glass is as light and as coloured as AA allows.**
 *
 * B42-fix2's field was pulled into a band whose top was a relative luminance of 0.09 (very dark) by a mapping that compressed every cover into it;
 * the owner: "can the colours be a bit lighter? Some light colours aren't that light". Now the ink is always light (near-white), the field keeps the
 * luminance the cover gave each cell and is brought down only where the ink could not sit on it, and the limit is held at the **90th percentile of
 * the area behind a block of text**, not at the lightest cell, so a small light patch of a cover darkens nothing around it.
 *
 * Five covers (a pure white, a pure black, a pale pastel, a saturated one, a multicolour on black) and a dark cover with one small bright patch are
 * made into the glass; the ink (primary, secondary and tertiary text, the accent as text and as a control) is measured on what is drawn behind the
 * player's title, its lyrics and its controls (the three layers at eight phases of the motion, under the player's scrim) and behind a list on a page
 * (under the page's veil), at the percentile for text (4.5:1) and at every pixel for a control (3:1). The same covers are made into **B42-fix2's**
 * glass (its algorithm is kept below, as it was) and the new one is measured against it: lighter, more saturated, closer to the cover.
 *
 * The assertions also run against the tree before the batch. Its band schema was different: the old API's `high` stands for the new text `top`,
 * and its constructor started with `dark`. That API-only adaptation is saved with the before results; production in the before copy is unchanged.
 */
class B42Fix3GlassFieldTest {
    private class Cover(val name: String, val pixels: IntArray, val w: Int = 96, val h: Int = 96)

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun solid(name: String, argb: Int) = Cover(name, IntArray(96 * 96) { argb })

    private fun withDisc(name: String, base: Int, spot: Int, cx: Int, cy: Int, radius: Int, w: Int = 96, h: Int = 96): Cover {
        val px = IntArray(w * h) { base }
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= radius * radius) px[y * w + x] = spot
        }
        return Cover(name, px, w, h)
    }

    /** Stripes and blocks of orange, green, red, yellow and blue on black: a busy cover with a lot of colour on a dark ground. */
    private fun multicolourOnBlack(): Cover {
        val px = IntArray(96 * 96) { rgb(8, 8, 10) }
        fun block(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in y0 until y1) for (x in x0 until x1) px[y * 96 + x] = c
        }
        block(8, 10, 40, 30, rgb(238, 122, 24))
        block(46, 14, 88, 34, rgb(46, 170, 70))
        block(12, 38, 52, 60, rgb(216, 36, 36))
        block(58, 42, 90, 62, rgb(240, 208, 44))
        block(20, 68, 76, 86, rgb(40, 90, 200))
        return Cover("multicolour on black", px)
    }

    private val white = solid("pure white", rgb(255, 255, 255))
    private val black = solid("pure black", rgb(0, 0, 0))
    private val pastel = solid("pale pastel", rgb(244, 198, 216))
    private val saturated = solid("saturated", rgb(230, 20, 90))
    private val multicolour = multicolourOnBlack()
    private val covers = listOf(white, black, pastel, saturated, multicolour)

    /** A dark navy cover with one small white logo low on it, where the player's text is. */
    private val logoOnDark = withDisc("white logo on a dark cover", rgb(14, 22, 52), rgb(255, 255, 255), 48, 70, 9)
    private val darkWithoutLogo = solid("dark navy", rgb(14, 22, 52))

    private val palette = DapBaseTheme.Graphite.palette
    private val look: GlassLook = GlassBands.of(palette, palette.accentColor(DapAccent.Sage))

    private fun fieldOf(cover: Cover): GlassField = GlassFieldMath.field(GlassFieldMath.grid(cover.pixels, cover.w, cover.h), look.band)

    private fun argbOf(c: Color): Int = (0xFF shl 24) or ((c.red * 255f + 0.5f).toInt() shl 16) or ((c.green * 255f + 0.5f).toInt() shl 8) or (c.blue * 255f + 0.5f).toInt()

    private fun meanLuminance(f: GlassField): Float = f.argb.map { GlassLuma.luminance(it) }.average().toFloat()

    private fun chroma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    // ── B42-fix2's glass, as it was (kept so the new one can be measured against it) ─────────────────────────────────────────────

    /** B42-fix2: a dark theme's band, its top at 0.09, and every cell mapped into it with `t = 0.12 + 0.56 sqrt(Y) + 0.32 glow`. */
    private fun b42Fix2Field(cover: Cover): GlassField {
        val grid = GlassFieldMath.grid(cover.pixels, cover.w, cover.h)
        val top = 0.09f
        val band = GlassBand(0.0f, top, top, look.band.base).let { fixBand(it) }
        val out = IntArray(grid.size * grid.size)
        for (i in out.indices) {
            val c = Color(grid.r[i].coerceIn(0f, 1f), grid.g[i].coerceIn(0f, 1f), grid.b[i].coerceIn(0f, 1f))
            val hsl = AlbumArtColors.Hsl.of(c)
            val yc = GlassLuma.luminance(grid.r[i], grid.g[i], grid.b[i])
            val root = sqrt(yc)
            val glow = hsl.s * min(1f, root * 1.6f)
            val t = (0.12f + 0.56f * root + 0.32f * glow).coerceIn(0f, 1f)
            val low = min(look.band.base.luminance(), top) * 0.6f
            val target = low + t * (top - low)
            val saturation = min(1f, hsl.s * 1.15f)
            out[i] = oldLightnessFor(hsl.h, saturation, target, low, top)
        }
        return GlassField(grid.size, out, band)
    }

    private fun fixBand(b: GlassBand) = b

    private fun oldLightnessFor(hue: Float, saturation: Float, target: Float, low: Float, high: Float): Int {
        var lo = 0f
        var hi = 1f
        var best = 0
        var bestError = Float.MAX_VALUE
        repeat(22) {
            val mid = (lo + hi) / 2f
            val c = AlbumArtColors.Hsl(hue, saturation, mid).toColor()
            val p = GlassLuma.pack(c.red, c.green, c.blue)
            val y = GlassLuma.luminance(p)
            if (y in low..high && abs(y - target) < bestError) {
                best = p
                bestError = abs(y - target)
            }
            if (y < target) lo = mid else hi = mid
        }
        return best
    }

    // ── where the text is ───────────────────────────────────────────────────────────────────────────────────────────────────

    private class Zone(val name: String, val y0: Float, val y1: Float)

    private val playerZones = listOf(Zone("the player's title", 0.60f, 0.68f), Zone("the player's lyrics", 0.70f, 0.80f), Zone("the player's controls", 0.84f, 0.96f))
    private val pageZone = Zone("a list on a page", 0.10f, 0.92f)

    private val phases = listOf(0f, 7f, 13f, 29f, 41f, 77f, 150f, 600f)

    private val screenW = 360
    private val screenH = 800

    /** The player's scrim at height [y] (0..1): the gradient of the full player, strongest at its ends, nothing in the middle. */
    private fun playerScrimAlpha(y: Float): Float = when {
        y < 0.28f -> GlassScrim.EDGE_ALPHA * (1f - y / 0.28f)
        y < 0.62f -> 0f
        else -> GlassScrim.EDGE_ALPHA * ((y - 0.62f) / 0.38f)
    }

    /** What is drawn behind a block of text in [zone]: every pixel of it, at every phase, composited as the backdrop draws it. */
    private fun luminancesIn(field: GlassField, zone: Zone, player: Boolean): FloatArray {
        val base = argbOf(look.band.base)
        val out = ArrayList<Float>()
        for (phase in phases) {
            val layers = GlassMotion.layers(phase)
            var py = (zone.y0 * screenH).toInt()
            while (py < (zone.y1 * screenH).toInt()) {
                var px = (0.05f * screenW).toInt()
                while (px < (0.95f * screenW).toInt()) {
                    val alpha = if (player) playerScrimAlpha(py / screenH.toFloat()) else GlassScrim.PAGE_ALPHA
                    out += GlassLuma.luminance(GlassSample.pixel(field, layers, screenW, screenH, px, py, base, alpha))
                    px += 9
                }
                py += 8
            }
        }
        return out.toFloatArray().also { it.sort() }
    }

    private fun inks(): List<Pair<String, Color>> = listOf(
        "primary" to look.ink.primary, "secondary" to look.ink.secondary, "tertiary" to look.ink.tertiary,
    )

    private fun percentile90(sorted: FloatArray) = percentileOf(sorted, 0.90f)

    // ── tests ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_accent_keeps_text_contrast_on_the_players_lighter_glass() {
        for (accent in DapAccent.entries) {
            val ink = GlassBands.of(palette, palette.accentColor(accent)).ink.accent
            assertThat(GlassLuma.contrast(ink.luminance(), look.band.top)).isAtLeast(4.5f)
        }
    }

    @Test
    fun `behind the player's title, lyrics and controls every ink is at AA at the 90th percentile and at 3 to 1 everywhere, for the five covers`() {
        for (cover in covers + logoOnDark) {
            val field = fieldOf(cover)
            for (zone in playerZones) {
                val sorted = luminancesIn(field, zone, player = true)
                val p90 = percentile90(sorted)
                for ((name, ink) in inks()) {
                    assertThat(GlassLuma.contrast(ink.luminance(), p90)).isAtLeast(4.5f)
                    assertThat(GlassLuma.contrast(ink.luminance(), sorted.last())).isAtLeast(3f)
                    assertThat(name).isNotEmpty()
                }
                // the accent is a control and a mark in the player (the shuffle dot, the heart): 3 to 1 on every pixel
                assertThat(GlassLuma.contrast(look.ink.accent.luminance(), sorted.last())).isAtLeast(3f)
            }
        }
    }

    @Test
    fun `behind a list on a page every ink, and the accent as text, is at AA at the 90th percentile and at 3 to 1 everywhere`() {
        for (cover in covers + logoOnDark) {
            val field = fieldOf(cover)
            val sorted = luminancesIn(field, pageZone, player = false)
            val p90 = percentile90(sorted)
            for ((_, ink) in inks()) {
                assertThat(GlassLuma.contrast(ink.luminance(), p90)).isAtLeast(4.5f)
                assertThat(GlassLuma.contrast(ink.luminance(), sorted.last())).isAtLeast(3f)
            }
            assertThat(GlassLuma.contrast(look.ink.accent.luminance(), p90)).isAtLeast(4.5f)
        }
    }

    @Test
    fun `the ink is always light - near-white, in order, and the same for every cover`() {
        assertThat(look.ink.primary.luminance()).isAtLeast(0.99f)
        assertThat(look.ink.secondary.luminance()).isAtLeast(0.9f)
        assertThat(look.ink.tertiary.luminance()).isAtLeast(0.88f)
        assertThat(look.ink.primary.luminance()).isGreaterThan(look.ink.secondary.luminance())
        assertThat(look.ink.secondary.luminance()).isGreaterThan(look.ink.tertiary.luminance())
        // the accent is the cover's colour lifted until it reads: light too
        for (accent in DapAccent.entries) {
            val l = GlassBands.of(palette, palette.accentColor(accent))
            assertThat(l.ink.primary).isEqualTo(look.ink.primary)
            assertThat(l.ink.tertiary).isEqualTo(look.ink.tertiary)
            assertThat(l.ink.accent.luminance()).isAtLeast(0.6f)
        }
    }

    @Test
    fun `the band's top is nearly twice B42-fix2's, and the dimmest ink keeps AA on it`() {
        assertThat(GlassBands.TOP).isGreaterThan(0.09f * 1.6f)
        assertThat(GlassBands.TOP).isAtMost(0.19f)
        assertThat(GlassBands.HIGH).isAtLeast(GlassBands.TOP)
        assertThat(GlassBands.HIGH).isAtMost(0.22f)
        for ((_, ink) in inks()) {
            assertThat(GlassLuma.contrast(ink.luminance(), GlassBands.TOP * (1f + GlassBands.MARGIN))).isAtLeast(4.5f - 0.001f)
            assertThat(GlassLuma.contrast(ink.luminance(), GlassBands.HIGH)).isAtLeast(3f)
        }
        assertThat(look.band.top).isEqualTo(GlassBands.TOP)
        assertThat(look.band.high).isEqualTo(GlassBands.HIGH)
        assertThat(look.band.low).isEqualTo(0f)
    }

    @Test
    fun `the glass is measurably lighter than B42-fix2's behind text and outside it, for every cover but the black one, which stays black`() {
        for (cover in covers) {
            val now = fieldOf(cover)
            val before = b42Fix2Field(cover)
            val meanNow = meanLuminance(now)
            val meanBefore = meanLuminance(before)
            val titleNow = percentile90(luminancesIn(now, playerZones[0], player = true))
            val titleBefore = percentile90(luminancesIn(before, playerZones[0], player = true))
            val listNow = percentile90(luminancesIn(now, pageZone, player = false))
            val listBefore = percentile90(luminancesIn(before, pageZone, player = false))
            println(
                "B42-fix3 item 7 | ${cover.name}: mean luminance ${"%.3f".format(meanBefore)} -> ${"%.3f".format(meanNow)}; " +
                    "behind the title (p90) ${"%.3f".format(titleBefore)} -> ${"%.3f".format(titleNow)}; behind a list (p90) ${"%.3f".format(listBefore)} -> ${"%.3f".format(listNow)}",
            )
            if (cover === black) {
                // nothing lifts a black cover (the glass is never lightened): it is a deep near-black, a little deeper than B42-fix2's lifted one
                assertThat(meanNow).isAtMost(0.004f)
                assertThat(meanNow).isAtMost(meanBefore)
                continue
            }
            assertThat(meanNow).isAtLeast(meanBefore * 1.25f)
            assertThat(titleNow).isAtLeast(titleBefore * 1.2f)
            assertThat(listNow).isAtLeast(listBefore * 1.2f)
        }
    }

    @Test
    fun `a pure white cover is a pearl grey at the top of the band, never a white glass, and a pure black one is a deep near-black`() {
        val w = fieldOf(white)
        for (p in w.argb) {
            assertThat(chroma(p)).isAtMost(3)                                         // no colour is invented
            assertThat(GlassLuma.luminance(p)).isAtMost(GlassBands.TOP + 0.003f)
            assertThat(GlassLuma.luminance(p)).isAtLeast(GlassBands.TOP - 0.02f)        // the top of the band: as light as the ink allows
        }
        assertThat(w.mean.luminance()).isGreaterThan(0.12f)                           // B42-fix2's was 0.09 at most
        val b = fieldOf(black)
        assertThat(b.maxLuminance).isAtMost(0.004f)
        // and a white cover is not a white glass: far from the cover's own white
        assertThat(w.maxLuminance).isLessThan(0.3f)
    }

    @Test
    fun `the glass is darkened only as far as AA needs and never lightened - no cell is lighter than the cover's own`() {
        for (cover in covers + logoOnDark) {
            val grid = GlassFieldMath.grid(cover.pixels, cover.w, cover.h)
            val field = GlassFieldMath.field(grid, look.band)
            for (i in field.argb.indices) {
                val own = GlassLuma.luminance(grid.r[i], grid.g[i], grid.b[i])
                // an 8-bit pixel may sit a hair above the float it was made from
                assertThat(GlassLuma.luminance(field.argb[i])).isAtMost(own + 0.004f)
            }
        }
    }

    @Test
    fun `the glass keeps more of the cover's colour than B42-fix2's - more saturation, and a mean colour closer to the cover's`() {
        for (cover in listOf(pastel, saturated, multicolour, logoOnDark)) {
            val now = fieldOf(cover)
            val before = b42Fix2Field(cover)
            val coverMean = meanColour(cover.pixels)
            val dNow = distance(now.mean, coverMean)
            val dBefore = distance(before.mean, coverMean)
            val sNow = meanSaturation(now)
            val sBefore = meanSaturation(before)
            println(
                "B42-fix3 item 7 | ${cover.name}: distance of the glass's mean colour from the cover's ${"%.3f".format(dBefore)} -> ${"%.3f".format(dNow)}; " +
                    "mean saturation (HSL) ${"%.3f".format(sBefore)} -> ${"%.3f".format(sNow)}",
            )
            assertThat(dNow).isLessThan(dBefore * 0.95)
            // a cover that is already fully saturated in the glass cannot be more so; every other one is
            assertThat(sNow).isAtLeast(sBefore)
            if (sBefore < 0.9f) assertThat(sNow).isGreaterThan(sBefore * 1.05f)
        }
        // a white cover: a pearl grey is closer to white than B42-fix2's dark silver was
        val coverMean = meanColour(white.pixels)
        assertThat(distance(fieldOf(white).mean, coverMean)).isLessThan(distance(b42Fix2Field(white).mean, coverMean))
    }

    @Test
    fun `a saturated cover keeps its hue, and a multicolour one keeps every one of its colours, where they sit`() {
        val s = fieldOf(saturated)
        val hue = AlbumArtColors.Hsl.of(saturated.pixels[0].let { Color(it) }).h
        for (p in s.argb) {
            val h = AlbumArtColors.Hsl.of(Color(p))
            assertThat(hueDistance(h.h, hue)).isAtMost(6f)
        }
        // the multicolour cover: the orange block's corner of the field is orange, the green one's is green, the blue one's blue
        val m = fieldOf(multicolour)
        fun hueAt(u: Float, v: Float) = AlbumArtColors.Hsl.of(Color(GlassSample.bilinear(m, u, v))).h
        assertThat(hueDistance(hueAt(0.25f, 0.20f), 28f)).isAtMost(25f)             // orange, top left
        assertThat(hueDistance(hueAt(0.70f, 0.25f), 135f)).isAtMost(30f)            // green, top right
        assertThat(hueDistance(hueAt(0.50f, 0.78f), 222f)).isAtMost(30f)            // blue, bottom
    }

    @Test
    fun `one small bright patch darkens only itself - a white logo does not take the rest of the glass down`() {
        val withLogo = fieldOf(logoOnDark)
        val without = fieldOf(darkWithoutLogo)
        // the cells far from the logo are the ones the cover without it gives
        val n = withLogo.size
        var worst = 0f
        for (y in 0 until n) for (x in 0 until n) {
            val u = (x + 0.5f) / n
            val v = (y + 0.5f) / n
            val dist = sqrt((u - 0.5f) * (u - 0.5f) + (v - 0.73f) * (v - 0.73f))
            if (dist < 0.3f) continue
            val i = y * n + x
            worst = max(worst, abs(GlassLuma.luminance(withLogo.argb[i]) - GlassLuma.luminance(without.argb[i])))
        }
        assertThat(worst).isAtMost(0.004f)
        // the logo itself is brought down, to the cap and no further
        assertThat(withLogo.maxLuminance).isAtMost(GlassBands.HIGH + 0.003f)
        // and the text under it is readable at the percentile, as the player's tests measure
        val sorted = luminancesIn(withLogo, playerZones[0], player = true)
        for ((_, ink) in inks()) assertThat(GlassLuma.contrast(ink.luminance(), percentile90(sorted))).isAtLeast(4.5f)
    }

    @Test
    fun `no shape of the cover survives the blur`() {
        // a hard-edged shape (a half black, half white cover) is a soft ramp in the field: no two neighbouring cells differ by much
        val halves = Cover("halves", IntArray(96 * 96) { i -> if (i % 96 < 48) rgb(0, 0, 0) else rgb(255, 255, 255) })
        val f = fieldOf(halves)
        val n = f.size
        fun channel(p: Int, shift: Int) = (p shr shift) and 0xFF
        val top = f.argb.maxOf { max(channel(it, 16), max(channel(it, 8), channel(it, 0))) }
        for (y in 0 until n) for (x in 0 until n - 1) {
            val a = f.argb[y * n + x]
            val b = f.argb[y * n + x + 1]
            // no edge: a step from one cell to the next (in the 8-bit values that are drawn) is a fraction of the whole range of the field
            for (shift in listOf(16, 8, 0)) assertThat(abs(channel(a, shift) - channel(b, shift))).isAtMost((0.3f * top).toInt())
        }
    }

    private fun meanColour(pixels: IntArray): Color {
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val n = pixels.size * 255f
        return Color(r / n, g / n, b / n)
    }

    /** The mean HSL saturation of the cells that are not black: how much of its colour the glass shows, whatever its brightness. */
    private fun meanSaturation(f: GlassField): Float {
        val cells = f.argb.filter { GlassLuma.luminance(it) > 0.002f }
        return if (cells.isEmpty()) 0f else cells.map { AlbumArtColors.Hsl.of(Color(it)).s }.average().toFloat()
    }

    private fun distance(a: Color, b: Color): Double {
        val dr = (a.red - b.red).toDouble()
        val dg = (a.green - b.green).toDouble()
        val db = (a.blue - b.blue).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b)
        return min(d, 360f - d)
    }
}
