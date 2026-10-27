package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.lostf1sh.pixelplayeross.ui.ds.DapPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * B42-fix3 (item 7): how light the cover's glass may be behind the ink, and the ink.
 *
 * **There is no light and dark any more: the cover decides.** B41 let the theme decide the lightness and the cover only the colours, which put
 * the field into a pale band under dark ink on Light and washed a colourful cover out to a faint pink. Now the ink is always light (the
 * owner: "leave it like this") and the field is darkened **only as far as AA needs** for that ink, and never lightened: a black cover is a deep
 * near-black, a white one a pearl grey, and a colourful one is itself.
 *
 * The limit is not the lightest cell of the field. It is measured the way text is seen: against the area behind a block of text, at a high
 * percentile ([PERCENTILE]) of it, so one small light patch of a cover (a white logo on a dark one) does not darken the whole glass. A cell may
 * pass [top] up to [high], where the ink still keeps 3:1 (a control), but only where fewer than a tenth of the cells do ([GlassFieldMath.field]).
 *
 * @property low the lowest relative luminance (WCAG) a cell may have: 0, a black cover stays black.
 * @property top the highest relative luminance at which the dimmest ink keeps 4.5:1 (with a margin), for the 90th percentile of the cells.
 * @property high the highest any cell may have: where the ink still keeps 3:1.
 * @property base the theme's own opaque background: what the scrim moves the field toward, and what is drawn under it.
 */
@Immutable
data class GlassBand(val low: Float, val top: Float, val high: Float, val base: Color) {
    /** Identifies the band, so a field built for another one is not taken for this one's. */
    val key: String get() = "${"%.4f".format(low)}:${"%.4f".format(top)}:${"%.4f".format(high)}"
}

/**
 * The ink the app draws on the glass: always light. [primary], [secondary] and [tertiary] are near-white so they pass AA on a field as light as
 * [GlassBands.TOP]; the hierarchy comes from size and weight more than from dimness. [accent] is the cover's colour lifted until it reads.
 */
@Immutable
data class GlassInk(val primary: Color, val secondary: Color, val tertiary: Color, val accent: Color)

/** What the glass is for one accent: the band, and the ink to draw on it. */
@Immutable
data class GlassLook(val band: GlassBand, val ink: GlassInk)

object GlassBands {
    /** WCAG AA for text. */
    const val AA_TEXT = 4.5f

    /** WCAG for a control or a large glyph. */
    const val AA_CONTROL = 3f

    /**
     * A margin kept inside the AA bound, for what happens after the grid is built: the sRGB mix of two layers is not the mix of their
     * luminances, the GPU rounds to 8 bits, and the scrim moves the colours.
     */
    const val MARGIN = 0.03f

    /** The share of the area behind a block of text that may be lighter than [TOP] (and up to [HIGH]): the 90th percentile is what is held. */
    const val PERCENTILE = 0.90f

    val PRIMARY: Color = Color.White
    val SECONDARY: Color = Color(0xFFFAFAFA)
    val TERTIARY: Color = Color(0xFFF5F5F5)

    /** The highest luminance a field may have at [PERCENTILE] for an ink of [inkLuminance] to keep [AA_TEXT] on it, with [MARGIN]. */
    fun topFor(inkLuminance: Float, contrast: Float = AA_TEXT): Float = ((inkLuminance + 0.05f) / contrast - 0.05f) / (1f + MARGIN)

    /**
     * The brightest the glass goes where text is: set by the dimmest of the three inks (the tertiary), about 0.159. B42-fix2's was 0.09. The
     * primary alone would allow about 0.178, the secondary about 0.172: a level that cannot reach AA higher is what holds the top here.
     */
    val TOP: Float = min(topFor(PRIMARY.luminance()), min(topFor(SECONDARY.luminance()), topFor(TERTIARY.luminance())))

    /** No cell is lighter than this: the dimmest ink still keeps 3:1 on it (a control, a glyph, large text). */
    val HIGH: Float = min(0.22f, topFor(TERTIARY.luminance(), AA_CONTROL))

    /** The glass for [palette]'s background and the [accent] the cover gives: lifted for text on pages and the player's lighter glass. */
    fun of(palette: DapPalette, accent: Color): GlassLook {
        val base = palette.background.copy(alpha = 1f)
        // The accent is also text on the player's pages (for example the active output route in Details), where the strong page scrim is
        // absent. Calibrating only for that scrim left those words at about 4:1. Keep AA at the field's text percentile itself; scrims help.
        val textFloor = AA_TEXT * (TOP * (1f + MARGIN) + 0.05f) - 0.05f
        // and as a control or a mark (the shuffle dot, the heart, the progress fill) it must keep 3:1 on the lightest pixel of the player's glass
        val controlFloor = AA_CONTROL * (HIGH * (1f + MARGIN) + 0.05f) - 0.05f
        val floor = max(textFloor, controlFloor)
        return GlassLook(
            GlassBand(low = 0f, top = TOP, high = HIGH, base = base),
            GlassInk(PRIMARY, SECONDARY, TERTIARY, liftedTo(accent, floor)),
        )
    }

    /** [ink] with white mixed in until its luminance is at least [minLuminance]: unchanged when it already is. */
    internal fun liftedTo(ink: Color, minLuminance: Float): Color {
        if (ink.luminance() >= minLuminance) return ink
        return mixToward(ink, Color.White) { it >= minLuminance }
    }

    /** The smallest mix of [ink] toward [target] whose luminance satisfies [enough]: luminance is monotonic along the mix, so bisection. */
    private fun mixToward(ink: Color, target: Color, enough: (Float) -> Boolean): Color {
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (enough(mix(ink, target, mid).luminance())) hi = mid else lo = mid
        }
        return mix(ink, target, hi)
    }

    private fun mix(a: Color, b: Color, t: Float) =
        Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha)

}

/**
 * This palette with the glass behind the app: the ink of [look], and the see-through panels. Only while a field is on screen: the theme's
 * own ink is what the app draws the rest of the time.
 *
 * Nothing in the result depends on the song: the ink is the same for every cover, and the panels are the theme's background at a fixed
 * strength over whatever glass is behind them. What changes with the song is the glass, drawn behind, and the accent.
 */
fun DapPalette.onGlass(look: GlassLook): DapPalette = copy(
    primaryText = look.ink.primary,
    secondaryText = look.ink.secondary,
    tertiaryText = look.ink.tertiary,
    glass = GlassSurfaces.of(this),
)

/** Relative luminance and contrast on the integer pixels the field is made of (the same constants as [androidx.compose.ui.graphics.luminance]). */
object GlassLuma {
    private val lut = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    /** sRGB channel (0..1) to linear light. */
    fun linear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * linear(r) + 0.7152f * linear(g) + 0.0722f * linear(b)

    fun luminance(argb: Int): Float =
        0.2126f * lut[(argb shr 16) and 0xFF] + 0.7152f * lut[(argb shr 8) and 0xFF] + 0.0722f * lut[argb and 0xFF]

    /** WCAG contrast of two luminances, 1 to 21. */
    fun contrast(a: Float, b: Float): Float = (max(a, b) + 0.05f) / (min(a, b) + 0.05f)

    /** Packs sRGB channels (0..1) into an opaque ARGB int. */
    fun pack(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 16) or
            ((g.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** The grey (sRGB, opaque) whose relative luminance is [y]: what the 8-bit pixel nearest to it is. */
    fun greyArgb(y: Float): Int {
        var lo = 0
        var hi = 255
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (luminance(pack(mid / 255f, mid / 255f, mid / 255f)) < y) lo = mid + 1 else hi = mid
        }
        return pack(lo / 255f, lo / 255f, lo / 255f)
    }

    /** [top] over [bottom] at [alpha], in sRGB, rounded to 8 bits: what the GPU draws for a flat veil. */
    fun mixArgb(bottom: Int, top: Int, alpha: Float): Int {
        fun ch(shift: Int): Float {
            val b = ((bottom shr shift) and 0xFF).toFloat()
            val t = ((top shr shift) and 0xFF).toFloat()
            return (b * (1f - alpha) + t * alpha) / 255f
        }
        return pack(ch(16), ch(8), ch(0))
    }
}
