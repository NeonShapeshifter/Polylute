package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * B40-F1: the accent of the song that is playing, for the two cover modes ("From album art" and "Cover glass").
 *
 * B42-fix3 (item 7): the **tint is gone**. B40 blended 10, 13 and 16 % of the cover's hue into the page, the panel and the raised panel; the
 * cover now decides the whole backdrop (a gradient of its two colours, or the blurred cover itself), and the surfaces are veils over it, so
 * the palette is the theme's own and **the same for every song**. What the cover still decides here is the accent.
 *
 * Nothing here is allowed to make the app harder to read: **the accent** is the cover's colour, moved in lightness (its hue and saturation
 * kept) until it reads at [ACCENT_CONTRAST] or better (WCAG AA for text) against the page, the panel and the raised panel, and until the ink of
 * a filled control reads on it. It always can: lightness has an end that passes on any surface. A cover with no colour of its own gives an
 * accent with none, which is true to the cover; one that cannot be read at all gives the theme's own accent. (The glass lifts it further,
 * to read on the lightest page glass: [com.lostf1sh.pixelplayeross.ui.glass.GlassBands.of].)
 *
 * Pure: the cover arrives as a colour, the answer is a palette and an accent.
 */
object AlbumArtColors {
    /** WCAG AA for body text: what the accent has to reach on every surface, because it is also used as text. */
    const val ACCENT_CONTRAST = 4.5f

    private const val ACCENT_SATURATION_FLOOR = 0.0f
    private const val ACCENT_SATURATION_CAP = 0.85f

    /** What the app wears for one song: the theme's palette (unchanged) and the accent. */
    data class Look(val palette: DapPalette, val accent: Color)

    /**
     * @param base the palette of the theme the cover modes draw on.
     * @param cover the cover's main colour (its scheme's primary), or null when the song has no cover or none was read yet.
     * @param themeAccent the accent the listener picked, resolved for [base]: used when the cover gives nothing.
     */
    fun look(base: DapPalette, cover: Color?, themeAccent: DapAccent): Look {
        val fallback = Look(base, base.accentColor(themeAccent))
        if (cover == null || cover.alpha < 0.99f) return fallback
        val accent = readableAccent(Hsl.of(cover), base) ?: return fallback
        return Look(base, accent)
    }

    /** The cover's colour moved in lightness until it reads on every surface of [palette] and carries its ink; null if it never does. */
    internal fun readableAccent(cover: Hsl, palette: DapPalette): Color? {
        val surfaces = listOf(palette.background, palette.surface, palette.surfaceRaised)
        val towardLight = !palette.isLight
        val saturation = cover.s.coerceIn(ACCENT_SATURATION_FLOOR, ACCENT_SATURATION_CAP)
        // start from the cover's own lightness, held to the band an accent lives in, then walk to the end that always passes
        var l = cover.l.coerceIn(0.28f, 0.82f)
        val step = if (towardLight) STEP else -STEP
        repeat(MAX_STEPS) {
            val candidate = Hsl(cover.h, saturation, l).toColor()
            if (surfaces.all { contrast(candidate, it) >= ACCENT_CONTRAST } && contrast(candidate, palette.onFill) >= ACCENT_CONTRAST) {
                return candidate
            }
            l += step
            if (l !in 0.02f..0.98f) return null
        }
        return null
    }

    /** WCAG relative contrast, 1 to 21. */
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private const val STEP = 0.01f
    private const val MAX_STEPS = 100

    /** Hue (0..360), saturation and lightness (0..1) of an sRGB colour. */
    internal data class Hsl(val h: Float, val s: Float, val l: Float) {
        fun toColor(): Color {
            val c = (1f - abs(2f * l - 1f)) * s
            val hp = (h % 360f + 360f) % 360f / 60f
            val x = c * (1f - abs(hp % 2f - 1f))
            val (r1, g1, b1) = when {
                hp < 1f -> Triple(c, x, 0f)
                hp < 2f -> Triple(x, c, 0f)
                hp < 3f -> Triple(0f, c, x)
                hp < 4f -> Triple(0f, x, c)
                hp < 5f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            val m = l - c / 2f
            return Color(red = (r1 + m).coerceIn(0f, 1f), green = (g1 + m).coerceIn(0f, 1f), blue = (b1 + m).coerceIn(0f, 1f))
        }

        companion object {
            fun of(color: Color): Hsl {
                val r = color.red
                val g = color.green
                val b = color.blue
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val l = (mx + mn) / 2f
                val d = mx - mn
                if (d < 1e-6f) return Hsl(0f, 0f, l)
                val s = d / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-6f)
                val h = when (mx) {
                    r -> ((g - b) / d) % 6f
                    g -> (b - r) / d + 2f
                    else -> (r - g) / d + 4f
                } * 60f
                return Hsl((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
            }
        }
    }
}
