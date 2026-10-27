package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B40-F1: the accent of the song that is playing. The cover decides the accent; the theme decides light or dark and keeps text readable. A cover
 * cannot make the app harder to read, so the hard ones are here: nearly black, nearly white, fully saturated, grey, pure black and pure white,
 * on every theme and with every accent the listener could have picked.
 *
 * B42-fix3 (item 7): the cover no longer **tints** the surfaces (B40's 10, 13 and 16 %): the cover modes draw a backdrop of the cover's own
 * colours and the surfaces are veils over it, so the palette a cover leaves is the theme's own, the same for every song. What a cover still decides
 * here is the accent; the tests that pinned the tint now pin its absence.
 */
class AlbumArtColorsTest {
    private val themes = listOf(DapPalette.Graphite, DapPalette.Black, DapPalette.Light, DapPalette.Gray, DapPalette.Navy)

    private val hardCovers = mapOf(
        "very dark" to Color(0xFF05050A),
        "pure black" to Color(0xFF000000),
        "very light" to Color(0xFFF8F8F0),
        "pure white" to Color(0xFFFFFFFF),
        "saturated red" to Color(0xFFFF0000),
        "saturated yellow" to Color(0xFFFFE600),
        "saturated blue" to Color(0xFF0000FF),
        "saturated green" to Color(0xFF00FF40),
        "mid grey" to Color(0xFF808080),
        "dark purple" to Color(0xFF2A0A4A),
        "pastel pink" to Color(0xFFFFD1DC),
        "muddy brown" to Color(0xFF4A3B2A),
    )

    private fun surfaces(p: DapPalette) = listOf(p.background, p.surface, p.surfaceRaised)

    @Test
    fun `whatever the cover, the accent reads at 4_5 to 1 on every surface of the theme, with the ink on top`() {
        for (base in themes) for ((name, cover) in hardCovers) {
            val look = AlbumArtColors.look(base, cover, DapAccent.Sage)
            for (surface in surfaces(look.palette)) {
                assertThat(AlbumArtColors.contrast(look.accent, surface)).isAtLeast(4.5f)
            }
            assertThat(AlbumArtColors.contrast(look.accent, look.palette.onFill)).isAtLeast(4.5f)
            assertThat(name).isNotEmpty()
        }
    }

    @Test
    fun `whatever the cover, the palette is the theme's own - nothing of the cover is in a surface, so a song change leaves it as it was`() {
        for (base in themes) for ((name, cover) in hardCovers) {
            val look = AlbumArtColors.look(base, cover, DapAccent.Sage)
            assertThat(look.palette).isEqualTo(base)
            assertThat(name).isNotEmpty()
        }
    }

    @Test
    fun `it follows each song - two covers give two accents and one palette, and the same cover gives the same look`() {
        val a = AlbumArtColors.look(DapPalette.Graphite, Color(0xFFD08020), DapAccent.Sage)
        val b = AlbumArtColors.look(DapPalette.Graphite, Color(0xFF20A0D0), DapAccent.Sage)
        assertThat(a.accent).isNotEqualTo(b.accent)
        assertThat(a.palette).isEqualTo(b.palette)
        assertThat(AlbumArtColors.look(DapPalette.Graphite, Color(0xFFD08020), DapAccent.Sage)).isEqualTo(a)
    }

    @Test
    fun `the accent keeps the cover's hue when it has to move in lightness to be readable`() {
        // a pale pink on white has to darken; a dark purple on black has to lighten; the hue stays
        val onWhite = AlbumArtColors.look(DapPalette.Light, Color(0xFFFFD1DC), DapAccent.Sage).accent
        val pink = AlbumArtColors.Hsl.of(Color(0xFFFFD1DC)).h
        assertThat(hueDistance(AlbumArtColors.Hsl.of(onWhite).h, pink)).isAtMost(4f)
        assertThat(onWhite.luminance()).isLessThan(Color(0xFFFFD1DC).luminance())
        val purple = AlbumArtColors.Hsl.of(Color(0xFF2A0A4A)).h
        val onBlack = AlbumArtColors.look(DapPalette.Black, Color(0xFF2A0A4A), DapAccent.Sage).accent
        assertThat(hueDistance(AlbumArtColors.Hsl.of(onBlack).h, purple)).isAtMost(4f)
        assertThat(onBlack.luminance()).isGreaterThan(Color(0xFF2A0A4A).luminance())
    }

    @Test
    fun `no cover means the theme and the accent the listener picked, untouched`() {
        for (base in themes) for (accent in DapAccent.entries) {
            val look = AlbumArtColors.look(base, null, accent)
            assertThat(look.palette).isEqualTo(base)
            assertThat(look.accent).isEqualTo(base.accentColor(accent))
        }
    }

    @Test
    fun `a translucent colour is not a cover colour`() {
        val look = AlbumArtColors.look(DapPalette.Graphite, Color(0x80FF0000), DapAccent.Sky)
        assertThat(look.palette).isEqualTo(DapPalette.Graphite)
        assertThat(look.accent).isEqualTo(DapPalette.Graphite.accentColor(DapAccent.Sky))
    }

    @Test
    fun `a grey cover gives an accent with no colour of its own - true to the cover`() {
        val look = AlbumArtColors.look(DapPalette.Graphite, Color(0xFF808080), DapAccent.Sage)
        assertThat(AlbumArtColors.Hsl.of(look.accent).s).isAtMost(0.05f)
    }

    @Test
    fun `settings - the preference defaults to the theme and an unknown value means the theme`() {
        assertThat(com.lostf1sh.pixelplayeross.data.preferences.AppColorSource.normalize(null)).isEqualTo("theme")
        assertThat(com.lostf1sh.pixelplayeross.data.preferences.AppColorSource.normalize("")).isEqualTo("theme")
        assertThat(com.lostf1sh.pixelplayeross.data.preferences.AppColorSource.normalize("sepia")).isEqualTo("theme")
        assertThat(com.lostf1sh.pixelplayeross.data.preferences.AppColorSource.normalize("album_art")).isEqualTo("album_art")
    }

    @Test
    fun `wiring - the root takes the cover in both cover modes, after a settle, and the theme provides the override`() {
        val activity = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/MainActivity.kt").readText()
        // B42-fix3: the root asks the one place that decides how the app is painted, and hands the theme the accent it gives
        assertThat(activity).contains("rememberAppPainting(")
        assertThat(activity).contains("accentColorOverride = themedLook?.accent")
        val painting = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/ui/theme/AppPainting.kt").readText()
        // B41: the glass option takes the same colours, so "the cover decides" is both cover options
        assertThat(painting).contains("if (AppColorSource.takesColoursFromCover(appColorSource)) {")
        assertThat(painting).contains("AlbumArtColors.look(drawnBaseTheme.palette, coverColor, accent)")
        assertThat(painting).contains(".debounce(settleMs)")
        assertThat(painting).contains("const val ALBUM_ART_THEME_SETTLE_MS = 350L")
        val theme = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/ui/theme/Theme.kt").readText()
        assertThat(theme).contains("accentColorOverride ?: palette.accentColor(accent)")
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b)
        return minOf(d, 360f - d)
    }
}
