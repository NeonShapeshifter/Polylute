package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B39-A3: the old PixelPlayer glyph (`monochrome_player`, `pixelplayer_base_monochrome`) was what the media notification, the HONOR's
 * capsule, Samsung's Now Bar and the status bar showed, and what a song with no artwork was drawn on. Both are the Polylute logo now:
 * the cover with the logo's own lines, the small icon as the same contours closed into a solid mark (at 24 dp the four lines are about a
 * pixel apart and read as grey bands). Nothing is redrawn: every contour of both is one of the launcher's monochrome layer.
 */
class ProductLogoBoundaryTest {
    private val app = File("src")
    private val res = File(app, "main/res")

    private fun text(file: File) = file.readText().replace("\r\n", "\n")
    private fun contours(file: File) = Regex("""android:pathData="([^"]+)"""").findAll(text(file)).map { it.groupValues[1] }.toList()

    private val logo = contours(File(res, "drawable/polylute_icon_monochrome.xml"))
    private val statIcon = File(res, "drawable/ic_stat_polylute.xml")
    private val coverMark = File(res, "drawable/polylute_mark.xml")

    @Test
    fun `no reference to the old PixelPlayer drawables remains, and the files are gone`() {
        for (name in listOf("monochrome_player", "pixelplayer_base_monochrome")) {
            assertThat(File(res, "drawable/$name.xml").exists()).isFalse()
            val everywhere = listOf("main", "debug", "release", "benchmark", "test", "androidTest").map { File(app, it) }.filter { it.exists() }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "java") }.toList() }
                .filter { it.name != "ProductLogoBoundaryTest.kt" }
                .filter { text(it).contains(name) }
                .map { it.path }
            assertThat(everywhere).isEmpty()
        }
        // the lint baseline does not carry an entry for a file that no longer exists
        assertThat(text(File("lint-baseline.xml"))).doesNotContain("monochrome_player")
    }

    @Test
    fun `every notification builder uses the Polylute small icon, and the cover placeholder uses the Polylute mark`() {
        val sources = mapOf(
            "data/service/MusicService.kt" to 2,
            "data/volume/SafetyMaxRefusalNotifier.kt" to 1,
            "data/volume/FixedOutputNoticeNotifier.kt" to 1,
        )
        for ((relative, count) in sources) {
            val code = text(File(app, "main/java/com/lostf1sh/pixelplayeross/$relative"))
            assertThat(Regex("""setSmallIcon\(R\.drawable\.ic_stat_polylute\)""").findAll(code).count()).isEqualTo(count)
            assertThat(Regex("""setSmallIcon\((?!R\.drawable\.ic_stat_polylute\))""").containsMatchIn(code)).isFalse()
        }
        val cover = text(File(app, "main/java/com/lostf1sh/pixelplayeross/presentation/components/player/PlayerCoverSurface.kt"))
        assertThat(cover).contains("painterResource(R.drawable.polylute_mark)")
    }

    @Test
    fun `the cover mark and the small icon are made of the logo's own contours, nothing redrawn`() {
        assertThat(logo).hasSize(4)
        assertThat(contours(coverMark)).isEqualTo(logo)
        assertThat(contours(statIcon)).isEqualTo(logo)
        // a white silhouette: every fill and stroke is opaque white, so the system's tint is all that colours it
        for (file in listOf(statIcon, coverMark)) {
            val colours = Regex("""(?:fillColor|strokeColor)="(#[0-9A-Fa-f]+)"""").findAll(text(file)).map { it.groupValues[1].uppercase() }.toSet()
            assertThat(colours).containsExactly("#FFFFFFFF")
        }
        assertThat(text(statIcon)).contains("android:width=\"24dp\"")
    }

    // ── a pixel check of the claim "reads at 24 dp" ────────────────────────────────────────────────

    /** Draws the drawable's paths into a [px] x [px] square as java2d does (even-odd fill, then its stroke), and returns the alpha plane. */
    private fun alphaOf(file: File, px: Int, withStroke: Boolean = true): IntArray {
        System.setProperty("java.awt.headless", "true")
        val xml = text(file)
        val viewport = Regex("""android:viewportWidth="([\d.]+)"""").find(xml)!!.groupValues[1].toDouble()
        val tx = Regex("""android:translateX="(-?[\d.]+)"""").find(xml)!!.groupValues[1].toDouble()
        val ty = Regex("""android:translateY="(-?[\d.]+)"""").find(xml)!!.groupValues[1].toDouble()
        val strokeWidth = Regex("""android:strokeWidth="([\d.]+)"""").find(xml)?.groupValues?.get(1)?.toDouble()?.takeIf { withStroke } ?: 0.0
        val scale = px / viewport
        val image = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = java.awt.Color.WHITE
        val toPixels = AffineTransform.getScaleInstance(scale, scale).apply { translate(tx, ty) }
        for (data in contours(file)) {
            val path = pathOf(data).createTransformedShape(toPixels)
            g.fill(path)
            if (strokeWidth > 0.0) {
                g.stroke = BasicStroke((strokeWidth * scale).toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                g.draw(path)
            }
        }
        g.dispose()
        return IntArray(px * px) { (image.getRGB(it % px, it / px) ushr 24) and 0xFF }
    }

    /** The absolute `M L C Z` commands the logo's contours use. */
    private fun pathOf(data: String): Path2D.Double {
        val tokens = Regex("""[MLCZ]|-?\d+\.?\d*""").findAll(data).map { it.value }.toList()
        val path = Path2D.Double(Path2D.WIND_EVEN_ODD)
        var i = 0
        var command = ' '
        fun number() = tokens[i++].toDouble()
        while (i < tokens.size) {
            if (tokens[i][0].isLetter()) command = tokens[i++][0]
            when (command) {
                'M' -> path.moveTo(number(), number())
                'L' -> path.lineTo(number(), number())
                'C' -> path.curveTo(number(), number(), number(), number(), number(), number())
                'Z' -> path.closePath()
                else -> error("unsupported command $command")
            }
        }
        return path
    }

    /**
     * The grey a gap between two lines leaves: of the pixels the mark touches, the share that is only partly covered although
     * it has covered neighbours on all four sides (so it is not the mark's own edge). A solid mark has none; four lines a pixel apart are full of it.
     */
    private fun greyBetweenLines(alpha: IntArray, px: Int): Double {
        fun touched(x: Int, y: Int) = x in 0 until px && y in 0 until px && alpha[y * px + x] > 16
        var touchedCount = 0
        var grey = 0
        for (y in 0 until px) for (x in 0 until px) {
            if (!touched(x, y)) continue
            touchedCount++
            val a = alpha[y * px + x]
            if (a < 240 && touched(x - 1, y) && touched(x + 1, y) && touched(x, y - 1) && touched(x, y + 1)) grey++
        }
        return grey.toDouble() / touchedCount
    }

    @Test
    fun `the small icon is a solid silhouette at 24 dp on the densities a status bar uses, and the plain logo is not`() {
        for (px in listOf(48, 72, 96)) {   // xhdpi, xxhdpi, xxxhdpi
            val solid = greyBetweenLines(alphaOf(statIcon, px), px)
            assertThat(solid).isAtMost(0.02)
        }
        // the control: the same contours without closing the gaps are full of grey at 48 px (that is why the icon is not the plain logo)
        assertThat(greyBetweenLines(alphaOf(statIcon, 48, withStroke = false), 48)).isAtLeast(0.20)
    }

    @Test
    fun `the small icon fills its box the way a status bar icon should, centred, with room to breathe`() {
        val px = 96
        val alpha = alphaOf(statIcon, px)
        val rows = (0 until px).filter { y -> (0 until px).any { x -> alpha[y * px + x] > 16 } }
        val columns = (0 until px).filter { x -> (0 until px).any { y -> alpha[y * px + x] > 16 } }
        assertThat((rows.last() - rows.first() + 1) / px.toDouble()).isIn(com.google.common.collect.Range.closed(0.80, 0.92))
        // centred on both axes
        assertThat(kotlin.math.abs((rows.first() + rows.last()) / 2.0 - px / 2.0)).isAtMost(2.0)
        assertThat(kotlin.math.abs((columns.first() + columns.last()) / 2.0 - px / 2.0)).isAtMost(2.0)
    }
}
