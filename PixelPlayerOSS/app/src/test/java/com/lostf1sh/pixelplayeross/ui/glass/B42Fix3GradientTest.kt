package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * B42-fix3 (item 7): **"From album art": two colours of the cover, fading into each other, never the picture.**
 *
 * The cover is read for its two strongest distinct colours ([CoverColours]: the heaviest bucket of hue, saturation and lightness by area with a vivid
 * colour counted for more and a grey, a near-black or a near-white for less; then the heaviest that is not near it, so a black ground can be the second colour
 * of a vivid one) and the background is a soft gradient between them ([GlassFieldMath.gradientGrid]) made into a field by the same rule as the glass (darkened only
 * as far as AA needs, never lightened). A cover with one colour gives that colour and a deeper shade of it.
 *
 * Held here: nothing of the picture is in it (two covers that share their two colours and differ in everything else give the same field), the choice of
 * the two colours for the five covers, the field's smoothness, and the ink at AA on it.
 */
class B42Fix3GradientTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun solid(argb: Int) = IntArray(96 * 96) { argb }

    /** [a] on the left and [b] on the right of a 96 x 96 cover, split at [at] of the width. */
    private fun split(a: Int, b: Int, at: Float = 0.5f) = IntArray(96 * 96) { i -> if ((i % 96) < (96 * at).toInt()) a else b }

    /** The same two colours in a totally different layout: a checkerboard of 12 px squares with the same shares. */
    private fun checker(a: Int, b: Int) = IntArray(96 * 96) { i -> if (((i % 96) / 12 + (i / 96) / 12) % 2 == 0) a else b }

    private val graphite = DapBaseTheme.Graphite.palette
    private val band = GlassBands.of(graphite, graphite.accentColor(DapAccent.Sage)).band

    private fun fieldFrom(pixels: IntArray): GlassField {
        val two = CoverColours.pick(pixels, 96, 96)
        return GlassFieldMath.field(GlassFieldMath.gradientGrid(two.lighter, two.darker), band, moves = false)
    }

    @Test
    fun `no part of the picture is in it - two covers with the same two colours and another layout give the same field`() {
        val orange = rgb(238, 122, 24)
        val blue = rgb(40, 90, 200)
        val a = fieldFrom(split(orange, blue))
        val b = fieldFrom(checker(orange, blue))
        val c = fieldFrom(split(blue, orange, at = 0.3f))
        // the colours are what a cover is made of; where they sit is not, and the shares only choose between them
        assertThat(a.argb.toList()).isEqualTo(b.argb.toList())
        assertThat(a.argb.toList()).isEqualTo(c.argb.toList())
        assertThat(a.moves).isFalse()
    }

    @Test
    fun `the field is a smooth gradient between two colours - every neighbouring cell is close and it never repeats the picture's edges`() {
        val f = fieldFrom(split(rgb(238, 122, 24), rgb(40, 90, 200)))
        val n = f.size
        var largest = 0
        for (y in 0 until n) for (x in 0 until n - 1) {
            val a = f.argb[y * n + x]
            val b = f.argb[y * n + x + 1]
            for (shift in listOf(16, 8, 0)) largest = maxOf(largest, abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)))
        }
        for (x in 0 until n) for (y in 0 until n - 1) {
            val a = f.argb[y * n + x]
            val b = f.argb[(y + 1) * n + x]
            for (shift in listOf(16, 8, 0)) largest = maxOf(largest, abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)))
        }
        assertThat(largest).isAtMost(14)
        // the lighter colour is toward the top left and the other toward the bottom right
        assertThat(GlassLuma.luminance(f.argb[0])).isGreaterThan(GlassLuma.luminance(f.argb[n * n - 1]))
    }

    @Test
    fun `a white cover has one colour - white and a deeper shade of it - and is a pearl grey, never white`() {
        val two = CoverColours.pick(solid(rgb(255, 255, 255)), 96, 96)
        assertThat(two.lighter.luminance()).isGreaterThan(two.darker.luminance())
        assertThat(AlbumArtColors.Hsl.of(two.darker).s).isAtMost(0.05f)
        val f = fieldFrom(solid(rgb(255, 255, 255)))
        assertThat(f.maxLuminance).isAtMost(GlassBands.TOP + 0.003f)
        assertThat(f.mean.luminance()).isGreaterThan(0.08f)
        for (p in f.argb) assertThat(maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) - minOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)).isAtMost(3)
    }

    @Test
    fun `a black cover is a deep near-black`() {
        val f = fieldFrom(solid(rgb(0, 0, 0)))
        assertThat(f.maxLuminance).isAtMost(0.004f)
    }

    @Test
    fun `a cover with one colour gives it and a deeper shade of the same hue`() {
        val pink = rgb(240, 120, 170)
        val two = CoverColours.pick(solid(pink), 96, 96)
        val h = AlbumArtColors.Hsl.of(Color(pink)).h
        assertThat(hueDistance(AlbumArtColors.Hsl.of(two.lighter).h, h)).isAtMost(4f)
        assertThat(hueDistance(AlbumArtColors.Hsl.of(two.darker).h, h)).isAtMost(4f)
        assertThat(two.darker.luminance()).isLessThan(two.lighter.luminance() * 0.7f)
    }

    @Test
    fun `a multicolour cover on black gives its strongest vivid colour and the black, not two near-identical blacks or a muddy grey`() {
        val px = IntArray(96 * 96) { rgb(8, 8, 10) }
        for (y in 14 until 62) for (x in 8 until 70) px[y * 96 + x] = rgb(238, 122, 24)          // a big orange block
        for (y in 70 until 84) for (x in 20 until 40) px[y * 96 + x] = rgb(40, 90, 200)          // a small blue one
        val two = CoverColours.pick(px, 96, 96)
        val hl = AlbumArtColors.Hsl.of(two.lighter)
        // the lighter of the two is the vivid orange; the darker is the black ground
        assertThat(hueDistance(hl.h, 28f)).isAtMost(20f)
        assertThat(hl.s).isAtLeast(0.6f)
        assertThat(two.darker.luminance()).isLessThan(0.01f)
    }

    @Test
    fun `a saturated cover with three colours gives two of its own hues`() {
        val px = IntArray(96 * 96) { rgb(230, 20, 90) }                                         // magenta ground, the biggest
        for (y in 0 until 96) for (x in 48 until 96) if (y < 60) px[y * 96 + x] = rgb(24, 190, 230)   // cyan quarter
        for (y in 70 until 96) for (x in 0 until 36) px[y * 96 + x] = rgb(255, 210, 26)          // yellow corner
        val two = CoverColours.pick(px, 96, 96)
        val hues = listOf(two.lighter, two.darker).map { AlbumArtColors.Hsl.of(it).h }
        // two of magenta (~340), cyan (~190) and yellow (~49), and not the same one twice
        val known = listOf(340f, 190f, 49f)
        for (h in hues) assertThat(known.minOf { hueDistance(h, it) }).isAtMost(15f)
        assertThat(hueDistance(hues[0], hues[1])).isAtLeast(35f)
    }

    @Test
    fun `near-identical colours are not two colours`() {
        assertThat(CoverColours.distinct(Color(0xFFE6145A), Color(0xFFE81860))).isFalse()
        assertThat(CoverColours.distinct(Color(0xFFE6145A), Color(0xFF18D4E6))).isTrue()
        assertThat(CoverColours.distinct(Color(0xFF101010), Color(0xFFE6145A))).isTrue()
        assertThat(CoverColours.distinct(Color(0xFF3A5A9A), Color(0xFF3F5C98))).isFalse()
    }

    @Test
    fun `every ink is at AA on the gradient, behind where text is, for the five covers`() {
        val two = listOf(
            solid(rgb(255, 255, 255)), solid(rgb(0, 0, 0)), solid(rgb(244, 198, 216)), split(rgb(230, 20, 90), rgb(24, 190, 230)), split(rgb(238, 122, 24), rgb(8, 8, 10), 0.3f),
        )
        val look = GlassBands.of(graphite, graphite.accentColor(DapAccent.Sage))
        for (pixels in two) {
            val f = fieldFrom(pixels)
            val sorted = f.argb.map { GlassLuma.luminance(it) }.sorted()
            val p90 = sorted[min(sorted.size - 1, (0.9f * sorted.size).toInt())]
            for (ink in listOf(look.ink.primary, look.ink.secondary, look.ink.tertiary)) {
                assertThat(GlassLuma.contrast(ink.luminance(), p90)).isAtLeast(4.5f)
                assertThat(GlassLuma.contrast(ink.luminance(), sorted.last())).isAtLeast(3f)
            }
        }
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b)
        return min(d, 360f - d)
    }
}
