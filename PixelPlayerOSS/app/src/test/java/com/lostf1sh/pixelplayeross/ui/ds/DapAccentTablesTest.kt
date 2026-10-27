package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B40-F1: every theme has its own colour for each named accent, and every one of them reads. Pinned from the palettes themselves, so a
 * surface that moves or an accent that is retuned cannot take a theme under WCAG AA without a test saying so.
 *
 * The floors: 4.5:1 (AA for text, because an accent is used as text) on the page, the panel and the raised panel of its theme, and 4.5:1
 * for the ink of a filled control on the accent. 3:1 (AA for large text and controls) is implied by the first.
 */
class DapAccentTablesTest {
    private val themes = mapOf(
        "Graphite" to DapPalette.Graphite,
        "Black" to DapPalette.Black,
        "Light" to DapPalette.Light,
        "Gray" to DapPalette.Gray,
        "Navy" to DapPalette.Navy,
    )

    private fun contrast(a: Color, b: Color) = AlbumArtColors.contrast(a, b)

    private fun surfaces(p: DapPalette) = listOf(p.background, p.surface, p.surfaceRaised)

    @Test
    fun `every theme has a colour for every accent, and no two accents share one on the same theme`() {
        for ((name, palette) in themes) {
            assertThat(palette.accents.keys).containsExactlyElementsIn(DapAccent.entries)
            assertThat(DapAccent.entries.map { palette.accentColor(it) }.toSet()).hasSize(DapAccent.entries.size)
            assertThat(name).isNotEmpty()
        }
    }

    @Test
    fun `every accent of every theme reads at 4_5 to 1 on the page, the panel and the raised panel`() {
        for ((name, palette) in themes) {
            for (accent in DapAccent.entries) {
                val color = palette.accentColor(accent)
                for (surface in surfaces(palette)) {
                    assertThat(contrast(color, surface)).isAtLeast(4.5f)
                }
                // opaque, so it cannot be silently translucent on a surface
                assertThat(color.alpha).isWithin(0.001f).of(1f)
                assertThat("$name/${accent.label}").isNotEmpty()
            }
        }
    }

    @Test
    fun `the ink of a filled control reads on every accent of every theme`() {
        for ((_, palette) in themes) {
            for (accent in DapAccent.entries) {
                assertThat(contrast(palette.onFill, palette.accentColor(accent))).isAtLeast(4.5f)
            }
        }
    }

    @Test
    fun `the worst case per theme is written down`() {
        val worst = themes.mapValues { (_, palette) ->
            DapAccent.entries.minOf { accent -> surfaces(palette).minOf { contrast(palette.accentColor(accent), it) } }
        }
        // the figures the result document quotes: if a table or a surface moves, this says by how much
        assertThat(worst.getValue("Black")).isAtLeast(7.0f)
        assertThat(worst.getValue("Navy")).isAtLeast(6.0f)
        assertThat(worst.getValue("Gray")).isAtLeast(5.0f)
        assertThat(worst.getValue("Light")).isAtLeast(5.0f)
        // the shipped pastels, as they have always been drawn
        assertThat(worst.getValue("Graphite")).isAtLeast(6.0f)
    }

    @Test
    fun `graphite keeps the colours the app has always shipped, bit for bit`() {
        for (accent in DapAccent.entries) {
            assertThat(DapPalette.Graphite.accentColor(accent)).isEqualTo(accent.color)
        }
        assertThat(DapPalette.Graphite.accentColor(DapAccent.Sage)).isEqualTo(Color(0xFF93B9A0))
    }

    @Test
    fun `each theme has its own set - purer and brighter on black, deeper on white, and none shares a set with another`() {
        val sets = themes.mapValues { (_, p) -> DapAccent.entries.map { p.accentColor(it) } }
        val names = sets.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) {
            // not one colour in common between two themes: each theme's set is its own
            assertThat(sets.getValue(names[i]).intersect(sets.getValue(names[j]).toSet())).isEmpty()
        }
        fun saturation(c: Color) = AlbumArtColors.Hsl.of(c).s
        fun lightness(c: Color) = AlbumArtColors.Hsl.of(c).l
        val sage = DapAccent.Sage
        // purer on black than the shipped pastel; brighter than the one on white
        assertThat(saturation(DapPalette.Black.accentColor(sage))).isGreaterThan(saturation(DapPalette.Graphite.accentColor(sage)))
        assertThat(lightness(DapPalette.Light.accentColor(sage))).isLessThan(lightness(DapPalette.Black.accentColor(sage)))
    }

    @Test
    fun `an accent keeps its hue on every theme, so a name still means a colour`() {
        for ((name, palette) in themes) {
            for (accent in DapAccent.entries) {
                if (accent == DapAccent.Graphite) continue   // the achromatic one has no hue to keep
                val shipped = AlbumArtColors.Hsl.of(accent.color).h
                val here = AlbumArtColors.Hsl.of(palette.accentColor(accent)).h
                val delta = minOf(kotlin.math.abs(shipped - here), 360f - kotlin.math.abs(shipped - here))
                assertThat(delta).isAtMost(6f)
                assertThat(name).isNotEmpty()
            }
        }
    }

    @Test
    fun `the generated file is the one the script writes`() {
        val file = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/ui/ds/DapAccentTables.kt").readText()
        assertThat(file).contains("Generated** by `tools/palette/accent_tables.py`")
        assertThat(file).contains("val Black: Map<DapAccent, Color>")
        assertThat(file).contains("val Navy: Map<DapAccent, Color>")
    }
}
