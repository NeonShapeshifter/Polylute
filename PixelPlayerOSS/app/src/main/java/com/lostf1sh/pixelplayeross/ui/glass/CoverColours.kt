package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * B42-fix3 (item 7): the two colours of "From album art", the cover's two strongest distinct ones.
 *
 * The cover (a small copy of it, as pixels) is sorted into buckets of hue, saturation and lightness, and every bucket is given a weight: how
 * much of the cover it is, raised for a colour that is vivid and lowered for a grey, a near-black or a near-white (muddy colours do not speak for
 * a cover). The first colour is the heaviest bucket by that count, which for a cover of vivid colours on black is the strongest of the vivid
 * ones; the second is the heaviest bucket that is **not near it** (another hue, or a clearly different lightness, and not nearly the same
 * colour), counted with a grey as it is (so a black ground can be the second colour of a vivid one). A cover with one colour, or none that is
 * distinct, gets that colour and a deeper shade of it.
 *
 * Pure, and the only thing the gradient takes from the cover: no pixel of the picture goes any further than this.
 */
object CoverColours {
    @Immutable
    data class Duo(val lighter: Color, val darker: Color)

    private const val HUE_BINS = 18
    private const val GREY_BINS = 6

    /** A grey, a near-black and a near-white count for this much of what a vivid colour does when choosing the first colour. */
    private const val NEUTRAL_FIRST = 0.12f
    private const val NEUTRAL_SECOND = 0.5f

    /** A second colour must be at least this much of the cover to be one: below it the first colour and its shade are the answer. */
    private const val MIN_SECOND_SHARE = 0.04f

    /** How much deeper the shade of a single colour is (its lightness is multiplied by this). */
    private const val SHADE = 0.55f

    private class Bucket(val id: Int, val neutral: Boolean) {
        var count = 0
        var r = 0L
        var g = 0L
        var b = 0L
        val color: Color get() = Color(r / (count * 255f), g / (count * 255f), b / (count * 255f))
    }

    fun pick(pixels: IntArray, width: Int, height: Int): Duo {
        val n = min(pixels.size, width * height)
        if (n <= 0) return Duo(Color(0xFF808080), Color(0xFF404040))
        val buckets = HashMap<Int, Bucket>()
        for (i in 0 until n) {
            val p = pixels[i]
            val rr = (p shr 16) and 0xFF
            val gg = (p shr 8) and 0xFF
            val bb = p and 0xFF
            val hsl = AlbumArtColors.Hsl.of(Color(rr / 255f, gg / 255f, bb / 255f))
            val neutral = hsl.s < 0.15f || hsl.l < 0.08f || hsl.l > 0.95f
            val id = if (neutral) 1000 + (hsl.l * (GREY_BINS - 0.001f)).toInt() else
                ((hsl.h / (360f / HUE_BINS)).toInt() % HUE_BINS) * 10 + (if (hsl.s < 0.45f) 0 else 1) * 3 + (if (hsl.l < 0.3f) 0 else if (hsl.l < 0.62f) 1 else 2)
            val bucket = buckets.getOrPut(id) { Bucket(id, neutral) }
            bucket.count++
            bucket.r += rr
            bucket.g += gg
            bucket.b += bb
        }
        val all = buckets.values.toList()
        fun vividness(b: Bucket): Float {
            val hsl = AlbumArtColors.Hsl.of(b.color)
            val lightnessFactor = if (hsl.l in 0.2f..0.8f) 1f else 0.6f
            return (1f + 2.5f * hsl.s) * lightnessFactor
        }
        fun weight(b: Bucket, neutralFactor: Float): Float = b.count * (if (b.neutral) neutralFactor else vividness(b))

        val first = all.maxByOrNull { weight(it, NEUTRAL_FIRST) }!!
        val firstColor = first.color
        val second = all
            .filter { it !== first && distinct(firstColor, it.color) && it.count >= MIN_SECOND_SHARE * n }
            .maxByOrNull { weight(it, NEUTRAL_SECOND) }
        val secondColor = second?.color ?: shade(firstColor)
        return if (firstColor.luminance() >= secondColor.luminance()) Duo(firstColor, secondColor) else Duo(secondColor, firstColor)
    }

    /**
     * B56 phase 7.1: the cover's three to five strongest distinct colours, for the live background's blobs (the same buckets and weights as [pick]): the heaviest first, each next one
     * the heaviest that is not near any already taken, and fewer than [min] distinct colours are made up to [min] with deeper shades of the first. At most [max].
     */
    fun palette(pixels: IntArray, width: Int, height: Int, min: Int = 3, max: Int = 5): List<Color> {
        val n = min(pixels.size, width * height)
        if (n <= 0) return List(min) { Color(0xFF808080) }
        val buckets = HashMap<Int, Bucket>()
        for (i in 0 until n) {
            val p = pixels[i]
            val rr = (p shr 16) and 0xFF
            val gg = (p shr 8) and 0xFF
            val bb = p and 0xFF
            val hsl = AlbumArtColors.Hsl.of(Color(rr / 255f, gg / 255f, bb / 255f))
            val neutral = hsl.s < 0.15f || hsl.l < 0.08f || hsl.l > 0.95f
            val id = if (neutral) 1000 + (hsl.l * (GREY_BINS - 0.001f)).toInt() else
                ((hsl.h / (360f / HUE_BINS)).toInt() % HUE_BINS) * 10 + (if (hsl.s < 0.45f) 0 else 1) * 3 + (if (hsl.l < 0.3f) 0 else if (hsl.l < 0.62f) 1 else 2)
            val bucket = buckets.getOrPut(id) { Bucket(id, neutral) }
            bucket.count++; bucket.r += rr; bucket.g += gg; bucket.b += bb
        }
        fun vividness(b: Bucket): Float {
            val hsl = AlbumArtColors.Hsl.of(b.color)
            return (1f + 2.5f * hsl.s) * (if (hsl.l in 0.2f..0.8f) 1f else 0.6f)
        }
        val ranked = buckets.values.sortedWith(compareByDescending<Bucket> { it.count * (if (it.neutral) NEUTRAL_SECOND else vividness(it)) }.thenBy { it.id })
        val taken = ArrayList<Color>()
        for (bucket in ranked) {
            if (taken.size >= max) break
            if (bucket.count < MIN_SECOND_SHARE * n / 2 && taken.size >= min) continue
            val colour = bucket.color
            if (taken.none { !distinct(it, colour) }) taken += colour
        }
        var shade = taken.firstOrNull() ?: Color(0xFF808080)
        while (taken.size < min) { shade = shade(shade); taken += shade }
        return taken
    }

    /** Another hue, or clearly another lightness, and not nearly the same colour: what "distinct" means for the second colour. */
    internal fun distinct(a: Color, b: Color): Boolean {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        if (sqrt(dr * dr + dg * dg + db * db) < 0.22f) return false
        val ha = AlbumArtColors.Hsl.of(a)
        val hb = AlbumArtColors.Hsl.of(b)
        val bothColourful = ha.s >= 0.15f && hb.s >= 0.15f
        if (bothColourful) {
            val hueGap = abs(ha.h - hb.h).let { if (it > 180f) 360f - it else it }
            return hueGap >= 35f || abs(ha.l - hb.l) >= 0.28f
        }
        return true
    }

    /** A deeper shade of [color]: its hue and saturation, a lightness multiplied by [SHADE]. */
    internal fun shade(color: Color): Color {
        val hsl = AlbumArtColors.Hsl.of(color)
        return AlbumArtColors.Hsl(hsl.h, hsl.s, hsl.l * SHADE).toColor()
    }
}
