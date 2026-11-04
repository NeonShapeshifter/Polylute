package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * B41: a cover's colour layout: the cover shrunk to a small grid and blurred until only where its colours sit is left (no shape can be
 * recognised). It is the part of the glass that depends on the cover and on nothing else, so it is built once per cover and kept; the
 * theme's own brightness is applied to it afterwards ([GlassFieldMath.field]), which is far cheaper.
 *
 * Channels are sRGB, 0..1, row-major, [size] x [size].
 */
class GlassGrid(val size: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
    init {
        require(r.size == size * size && g.size == size * size && b.size == size * size) { "a $size x $size grid needs ${size * size} cells" }
    }

    fun argb(index: Int): Int = pack(r[index], g[index], b[index])
}

/**
 * The glass behind the app for one cover and one theme: [size] x [size] opaque sRGB pixels, every one of them inside the theme's
 * [GlassBand], which is what keeps the ink readable wherever it falls. Drawn stretched over the screen with bilinear filtering: a small
 * image scaled up is a blur that costs nothing per frame.
 */
@Immutable
class GlassField(val size: Int, val argb: IntArray, val band: GlassBand, val moves: Boolean = true) {
    init {
        require(argb.size == size * size) { "a $size x $size field needs ${size * size} pixels" }
    }

    val minLuminance: Float by lazy(LazyThreadSafetyMode.NONE) { argb.minOf { GlassLuma.luminance(it) } }
    val maxLuminance: Float by lazy(LazyThreadSafetyMode.NONE) { argb.maxOf { GlassLuma.luminance(it) } }

    private val sortedLuminance: FloatArray by lazy(LazyThreadSafetyMode.NONE) { FloatArray(argb.size) { GlassLuma.luminance(argb[it]) }.also { it.sort() } }

    /** B56-7.1: three to five colours of the field (the cover's own, in the theme's band): what the live background's blobs are painted with. */
    val palette: List<Color> by lazy(LazyThreadSafetyMode.NONE) { CoverColours.palette(argb, size, size) }

    /** The relative luminance that [p] (0..1) of the cells are at or below: the 90th percentile is what the ink's contrast is held at. */
    fun luminanceAt(p: Float): Float = percentileOf(sortedLuminance, p)

    /** The field's average colour: the one solid colour that stands for it where a surface cannot show the field itself. */
    val mean: Color by lazy(LazyThreadSafetyMode.NONE) {
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in argb) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val n = argb.size.toFloat() * 255f
        Color(r / n, g / n, b / n)
    }
}

/** The value [p] (0..1) of the way up an ascending array: the smallest one that at least [p] of the values are at or below. */
internal fun percentileOf(ascending: FloatArray, p: Float): Float {
    if (ascending.isEmpty()) return 0f
    val index = kotlin.math.ceil(p.coerceIn(0f, 1f) * ascending.size).toInt().coerceIn(1, ascending.size) - 1
    return ascending[index]
}

/** Packs sRGB channels (0..1) into an opaque ARGB int. */
internal fun pack(r: Float, g: Float, b: Float): Int =
    (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255f).roundToInt() shl 16) or
        ((g.coerceIn(0f, 1f) * 255f).roundToInt() shl 8) or (b.coerceIn(0f, 1f) * 255f).roundToInt()

object GlassFieldMath {
    /** The grid is this many cells a side: enough to hold where a cover's colours are, few enough that building it is microseconds. */
    const val GRID = 32

    /** Three box blurs of this radius (in cells) are close to a Gaussian of 2.5 cells: under a twelfth of the width, and no shape is left. */
    const val BLUR_RADIUS = 2
    const val BLUR_PASSES = 3

    /**
     * Shrinks [pixels] (ARGB, [width] x [height], any cover) to [GRID] x [GRID] by area average and blurs it. The cover's shape does not
     * survive; where its colours are does. Transparency is ignored (a cover is opaque).
     */
    fun grid(pixels: IntArray, width: Int, height: Int): GlassGrid {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "no pixels" }
        val n = GRID
        val r = FloatArray(n * n)
        val g = FloatArray(n * n)
        val b = FloatArray(n * n)
        for (gy in 0 until n) {
            val y0 = gy * height / n
            val y1 = max(y0 + 1, (gy + 1) * height / n)
            for (gx in 0 until n) {
                val x0 = gx * width / n
                val x1 = max(x0 + 1, (gx + 1) * width / n)
                var sr = 0L
                var sg = 0L
                var sb = 0L
                for (y in y0 until min(y1, height)) {
                    val row = y * width
                    for (x in x0 until min(x1, width)) {
                        val p = pixels[row + x]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                    }
                }
                val count = (min(y1, height) - y0) * (min(x1, width) - x0)
                val i = gy * n + gx
                r[i] = sr / (count * 255f)
                g[i] = sg / (count * 255f)
                b[i] = sb / (count * 255f)
            }
        }
        repeat(BLUR_PASSES) {
            boxBlur(r, n, BLUR_RADIUS)
            boxBlur(g, n, BLUR_RADIUS)
            boxBlur(b, n, BLUR_RADIUS)
        }
        return GlassGrid(n, r, g, b)
    }

    /** One box blur (horizontal, then vertical) of radius [radius] cells, the edge cell repeated beyond the grid. */
    private fun boxBlur(c: FloatArray, n: Int, radius: Int) {
        val tmp = FloatArray(n)
        val window = 2 * radius + 1
        // rows
        for (y in 0 until n) {
            val row = y * n
            var sum = 0f
            for (k in -radius..radius) sum += c[row + k.coerceIn(0, n - 1)]
            for (x in 0 until n) {
                tmp[x] = sum / window
                sum += c[row + (x + radius + 1).coerceIn(0, n - 1)] - c[row + (x - radius).coerceIn(0, n - 1)]
            }
            for (x in 0 until n) c[row + x] = tmp[x]
        }
        // columns
        for (x in 0 until n) {
            var sum = 0f
            for (k in -radius..radius) sum += c[k.coerceIn(0, n - 1) * n + x]
            for (y in 0 until n) {
                tmp[y] = sum / window
                sum += c[(y + radius + 1).coerceIn(0, n - 1) * n + x] - c[(y - radius).coerceIn(0, n - 1) * n + x]
            }
            for (y in 0 until n) c[y * n + x] = tmp[y]
        }
    }

    /** How much a cell's saturation is raised: a cover's colours read as colour on the glass, not as a tint. Greys stay grey. */
    const val SATURATION_BOOST = 1.30f

    /** Below this a cell is a grey (a white, a black, a silver): it is not given a colour it does not have. */
    const val NEUTRAL_SATURATION = 0.05f

    /** Where the knee starts, as a share of the cap: below it a cell keeps the luminance the cover gave it. */
    const val KNEE = 0.6f

    /**
     * B42-fix3 (item 7): the grid as the glass of [band]. **Darkened only as far as AA needs, never lightened.**
     *
     * Every cell keeps the cover's own hue, a raised saturation ([SATURATION_BOOST]) and the luminance the cover gave it, except where that
     * is lighter than the ink can sit on: there it is brought down through a soft knee ([knee]) toward a cap chosen for the whole field
     * ([capFor]) so that the 90th percentile of the cells is at [GlassBand.top] at most. So:
     *  - a **black** cover is a deep near-black (nothing lifts it), a **dark** one with a lit window keeps the window;
     *  - a **white** cover is a pearl grey at the top of the band, no colour invented; a pastel one is a deep, clear version of itself;
     *  - **one small light patch** (a white logo on a dark cover) is a patch brought down to the cap: it does not make the rest darker, because
     *    the cap only moves when more than a tenth of the cells are light;
     *  - the order of light and dark across the field is the cover's own (the knee is monotonic), so its layout shows.
     */
    fun field(grid: GlassGrid, band: GlassBand, moves: Boolean = true): GlassField {
        val count = grid.size * grid.size
        val luminance = FloatArray(count) { GlassLuma.luminance(grid.r[it], grid.g[it], grid.b[it]) }
        val cap = if (moves) capFor(luminance, band) else band.top
        val out = IntArray(count)
        for (i in out.indices) {
            val hsl = AlbumArtColors.Hsl.of(Color(grid.r[i].coerceIn(0f, 1f), grid.g[i].coerceIn(0f, 1f), grid.b[i].coerceIn(0f, 1f)))
            val saturation = if (hsl.s < NEUTRAL_SATURATION) hsl.s else min(1f, hsl.s * SATURATION_BOOST)
            out[i] = lightnessFor(hsl.h, saturation, knee(luminance[i], cap), band)
        }
        if (!moves) {
            // Smooth *after* hue/luminance mapping as well. Mapping the coarse grid could reintroduce steps near grey.
            val channels = listOf(16, 8, 0).map { shift -> FloatArray(count) { ((out[it] shr shift) and 255) / 255f } }
            repeat(3) { channels.forEach { boxBlur(it, grid.size, 10) } }
            for (i in out.indices) out[i] = GlassLuma.pack(channels[0][i], channels[1][i], channels[2][i])
        }
        return GlassField(grid.size, out, band, moves)
    }

    /** [y] brought under [cap] smoothly: unchanged up to [KNEE] of it, then easing toward it, never past it and never above [y]. Monotonic. */
    internal fun knee(y: Float, cap: Float): Float {
        val k = KNEE * cap
        if (y <= k) return y
        val room = cap - k
        return k + room * (1f - kotlin.math.exp(-(y - k) / room))
    }

    /**
     * The highest cap in [[GlassBand.top], [GlassBand.high]] for which the 90th percentile of the cells, after the knee, is at [GlassBand.top]
     * at most: [GlassBand.high] when no more than a tenth of the cells are lighter than the top (a small light patch is let through, held
     * under [GlassBand.high]); the top itself when more are (a pale cover is brought to a pearl grey).
     */
    internal fun capFor(luminance: FloatArray, band: GlassBand): Float {
        fun percentileAt(cap: Float): Float {
            val after = FloatArray(luminance.size) { knee(luminance[it], cap) }
            after.sort()
            return percentileOf(after, GlassBands.PERCENTILE)
        }
        if (percentileAt(band.high) <= band.top) return band.high
        var lo = band.top
        var hi = band.high
        repeat(14) {
            val mid = (lo + hi) / 2f
            if (percentileAt(mid) <= band.top) lo = mid else hi = mid
        }
        return lo
    }

    /**
     * B42-fix3 (item 7): the grid of "From album art": [first] and [second], the cover's two strongest colours, as a soft gradient across the
     * screen (the lighter at the top left, easing into the other toward the bottom right). It is made from two colours and nothing else, so no
     * part of the cover is in it: two covers with the same two colours give the same grid. It goes through [field] like the blurred cover does.
     */
    fun gradientGrid(first: Color, second: Color): GlassGrid {
        val n = GRID
        val r = FloatArray(n * n)
        val g = FloatArray(n * n)
        val b = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val u = (x + 0.5f) / n
            val v = (y + 0.5f) / n
            val t = smoothstep(0.12f, 0.88f, 0.4f * u + 0.6f * v)
            val i = y * n + x
            r[i] = first.red + (second.red - first.red) * t
            g[i] = first.green + (second.green - first.green) * t
            b[i] = first.blue + (second.blue - first.blue) * t
        }
        return GlassGrid(n, r, g, b)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The colour of this hue and saturation whose relative luminance is [target]: luminance grows with HSL lightness, so bisection finds it.
     * It looks at the 8-bit pixel that will be drawn, not at the float it came from, and never answers a pixel outside [band]: the
     * rounding to 8 bits must not take a pixel across the line the ink's contrast is measured at.
     */
    internal fun lightnessFor(hue: Float, saturation: Float, target: Float, band: GlassBand): Int {
        var lo = 0f
        var hi = 1f
        var best = 0
        var bestError = Float.MAX_VALUE
        var last = 0
        repeat(22) {
            val mid = (lo + hi) / 2f
            val c = AlbumArtColors.Hsl(hue, saturation, mid).toColor()
            last = pack(c.red, c.green, c.blue)
            val y = GlassLuma.luminance(last)
            if (y >= band.low && y <= band.high && kotlin.math.abs(y - target) < bestError) {
                best = last
                bestError = kotlin.math.abs(y - target)
            }
            if (y < target) lo = mid else hi = mid
        }
        if (bestError != Float.MAX_VALUE) return best
        // no pixel of this hue and saturation lands inside the band (a saturated colour at the very top of a narrow one): step toward grey
        return desaturateInto(last, band)
    }

    private fun desaturateInto(argb: Int, band: GlassBand): Int {
        var r = ((argb shr 16) and 0xFF) / 255f
        var g = ((argb shr 8) and 0xFF) / 255f
        var b = (argb and 0xFF) / 255f
        val grey = (r + g + b) / 3f
        repeat(16) {
            val y = GlassLuma.luminance(pack(r, g, b))
            if (y in band.low..band.high) return pack(r, g, b)
            val shift = if (y > band.high) -0.01f else 0.01f
            r = (r + (grey - r) * 0.25f + shift).coerceIn(0f, 1f)
            g = (g + (grey - g) * 0.25f + shift).coerceIn(0f, 1f)
            b = (b + (grey - b) * 0.25f + shift).coerceIn(0f, 1f)
        }
        val y = GlassLuma.luminance(pack(r, g, b))
        // last resort: a grey inside the band
        val target = y.coerceIn(band.low, band.high)
        var lo = 0f
        var hi = 1f
        repeat(16) {
            val mid = (lo + hi) / 2f
            if (GlassLuma.luminance(pack(mid, mid, mid)) < target) lo = mid else hi = mid
        }
        return pack(hi, hi, hi)
    }
}
