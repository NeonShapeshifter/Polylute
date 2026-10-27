package com.lostf1sh.pixelplayeross.ui.glass

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B41: a cover's glass is made once and kept: the second time a cover is shown it costs two lookups and no work. Building it is a few
 * milliseconds on the host, off the main thread, the same on every API level (no `RenderEffect`, no per-frame blur).
 */
class CoverGlassFieldsTest {
    private val graphite = DapBaseTheme.Graphite.palette
    private fun bandOf(p: com.lostf1sh.pixelplayeross.ui.ds.DapPalette) = GlassBands.of(p, p.accentColor(DapAccent.Sage)).band

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A cover with something in it, so a field built from it is not the same as another's. */
    private fun cover(seed: Int): Triple<Int, Int, IntArray> {
        val px = IntArray(96 * 96) { i -> rgb((i % 96 * 2 + seed * 40) % 256, (i / 96 * 2 + seed * 70) % 256, (seed * 90) % 256) }
        return Triple(96, 96, px)
    }

    private fun fields(decoder: suspend (String) -> Triple<Int, Int, IntArray>?): CoverGlassFields =
        CoverGlassFields(mockk<Context>(relaxed = true)).also { it.decoder = decoder }

    @Test
    fun `a cover shown again is the same glass, with no decode and no build`() = runTest {
        val fields = fields { cover(1) }
        val first = fields.fieldFor("content://art/1", GlassKind.GLASS, bandOf(graphite))
        val second = fields.fieldFor("content://art/1", GlassKind.GLASS, bandOf(graphite))
        assertThat(first).isNotNull()
        assertThat(second).isSameInstanceAs(first)
        assertThat(fields.decodes.get()).isEqualTo(1)
        assertThat(fields.builds.get()).isEqualTo(1)
    }

    @Test
    fun `the other backdrop for the same cover is another field, with its own grid`() = runTest {
        val fields = fields { cover(2) }
        val glass = fields.fieldFor("content://art/2", GlassKind.GLASS, bandOf(graphite))!!
        val gradient = fields.fieldFor("content://art/2", GlassKind.GRADIENT, bandOf(graphite))!!
        assertThat(gradient).isNotSameInstanceAs(glass)
        assertThat(glass.moves).isTrue()
        assertThat(gradient.moves).isFalse()
        assertThat(fields.builds.get()).isEqualTo(2)
        // and each is kept: asking again builds nothing
        assertThat(fields.fieldFor("content://art/2", GlassKind.GLASS, bandOf(graphite))).isSameInstanceAs(glass)
        assertThat(fields.fieldFor("content://art/2", GlassKind.GRADIENT, bandOf(graphite))).isSameInstanceAs(gradient)
        assertThat(fields.builds.get()).isEqualTo(2)
    }

    @Test
    fun `a different cover is decoded`() = runTest {
        val fields = fields { uri -> cover(uri.length) }
        fields.fieldFor("content://art/a", GlassKind.GLASS, bandOf(graphite))
        fields.fieldFor("content://art/bb", GlassKind.GLASS, bandOf(graphite))
        assertThat(fields.decodes.get()).isEqualTo(2)
    }

    @Test
    fun `a cover that cannot be read is no glass, and a later one is fine`() = runTest {
        var fail = true
        val fields = fields { if (fail) null else cover(3) }
        assertThat(fields.fieldFor("content://art/3", GlassKind.GLASS, bandOf(graphite))).isNull()
        fail = false
        assertThat(fields.fieldFor("content://art/3", GlassKind.GLASS, bandOf(graphite))).isNotNull()
    }

    @Test
    fun `clearing forgets what was made`() = runTest {
        val fields = fields { cover(4) }
        fields.fieldFor("content://art/4", GlassKind.GLASS, bandOf(graphite))
        fields.clear()
        fields.fieldFor("content://art/4", GlassKind.GLASS, bandOf(graphite))
        assertThat(fields.decodes.get()).isEqualTo(2)
    }

    // ── what it costs ───────────────────────────────────────────────────────────────────────────────

    private fun median(runs: Int, block: () -> Unit): Double {
        repeat(30) { block() }                       // warm the JIT: what is measured is the steady cost, not the first call
        val times = DoubleArray(runs) {
            val t0 = System.nanoTime()
            block()
            (System.nanoTime() - t0) / 1e6
        }
        times.sort()
        return times[runs / 2]
    }

    @Test
    fun `building the glass costs milliseconds, and the gradient less`() {
        val (w, h, px) = cover(5)
        val band = bandOf(graphite)
        val gridMs = median(200) { GlassFieldMath.grid(px, w, h) }
        val grid = GlassFieldMath.grid(px, w, h)
        val fieldMs = median(200) { GlassFieldMath.field(grid, band) }
        val colours = CoverColours.pick(px, w, h)
        val pickMs = median(200) { CoverColours.pick(px, w, h) }
        val gradientMs = median(200) { GlassFieldMath.field(GlassFieldMath.gradientGrid(colours.lighter, colours.darker), band, moves = false) }
        // a 128 x 128 decode is what the colour extraction itself uses: the cover's own size changes only the first step
        val big = IntArray(128 * 128) { px[it % px.size] }
        val gridBigMs = median(100) { GlassFieldMath.grid(big, 128, 128) }
        println(
            "B42-fix3 | cost on the host (median, warm): cover -> blurred 32x32 grid ${"%.3f".format(gridMs)} ms (96x96), ${"%.3f".format(gridBigMs)} ms (128x128); " +
                "grid -> field (darkened only as far as AA needs) ${"%.3f".format(fieldMs)} ms; two colours ${"%.3f".format(pickMs)} ms, gradient field ${"%.3f".format(gradientMs)} ms"
        )
        // generous bounds: this is about the order of magnitude (a frame is 16 ms), not a benchmark
        assertThat(gridMs).isLessThan(5.0)
        assertThat(fieldMs).isLessThan(10.0)
        assertThat(pickMs).isLessThan(10.0)
        assertThat(gradientMs).isLessThan(10.0)
    }
}
