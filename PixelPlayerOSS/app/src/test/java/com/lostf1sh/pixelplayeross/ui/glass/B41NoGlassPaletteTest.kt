package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapPalette
import com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme
import org.junit.jupiter.api.Test

/**
 * B41: "Theme and accent" and "From the cover" draw exactly what B40 drew. The glass is carried by one field of the palette (`glass`), null
 * for both of them, and every role the pages read for the glass (`page`, `panel`, `panelRaised`, `fadeEdge`) is then the role it replaces.
 * The pixels are compared against the tree as it was in `before-probes/` (same screens, byte for byte); this is the contract underneath.
 */
class B41NoGlassPaletteTest {
    private val themes = DapBaseTheme.entries

    private fun assertB40(palette: DapPalette) {
        assertThat(palette.glass).isNull()
        assertThat(palette.page).isEqualTo(palette.background)
        assertThat(palette.panel).isEqualTo(palette.surface)
        assertThat(palette.panelRaised).isEqualTo(palette.surfaceRaised)
        assertThat(palette.bar).isEqualTo(palette.background)
        // and the Material scheme is the one B40 built: the page is the opaque background, nothing see-through anywhere
        val scheme = dapColorScheme(palette.accentColor(DapAccent.Sage), palette)
        assertThat(scheme.background).isEqualTo(palette.background)
        assertThat(scheme.background.alpha).isEqualTo(1f)
        assertThat(scheme.surface).isEqualTo(palette.background)
        assertThat(scheme.surfaceContainerLow).isEqualTo(palette.background)
        assertThat(scheme.surfaceContainerHigh).isEqualTo(palette.surface)
        assertThat(scheme.surfaceContainerHighest).isEqualTo(palette.surfaceRaised)
    }

    @Test
    fun `Theme and accent is the theme's palette, with nothing from the glass in it`() {
        for (theme in themes) assertB40(theme.palette)
    }

    @Test
    fun `From the cover is the theme's own palette with the cover's accent, with nothing from the glass in it`() {
        val covers = listOf(Color(0xFFB4246C), Color(0xFF202020), Color(0xFFF4F4F4), Color(0xFF22AA55), null)
        for (theme in themes) for (cover in covers) {
            val look = AlbumArtColors.look(theme.palette, cover, DapAccent.Sage)
            assertB40(look.palette)
        }
    }

    @Test
    fun `only the glass changes a page, a panel and a bar, and only the glass makes the Material background see-through`() {
        val p = DapBaseTheme.Graphite.palette
        val look = GlassBands.of(p, p.accentColor(DapAccent.Sage))
        val g = p.onGlass(look)
        assertThat(g.glass).isNotNull()
        assertThat(g.page.alpha).isEqualTo(0f)
        assertThat(g.page.copy(alpha = 1f)).isEqualTo(p.background)            // its colour is still the theme's: its luminance still says dark
        // B42-fix3 (item 8): a panel and a bar are a veil of the theme's own background over the glass, the same for every song; a raised one a little light
        assertThat(g.panel.alpha).isGreaterThan(0f)
        assertThat(g.panel.alpha).isLessThan(1f)
        assertThat(g.panel.copy(alpha = 1f)).isEqualTo(p.background)
        assertThat(g.bar).isEqualTo(g.panel)
        assertThat(g.panelRaised.alpha).isLessThan(1f)
        // every other role is the theme's, so a dialog, a sheet or a menu (its own window) never shows what is behind it
        val scheme = dapColorScheme(g.accentColor(DapAccent.Sage), g)
        assertThat(scheme.background.alpha).isEqualTo(0f)
        for (opaque in listOf(scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)) {
            assertThat(opaque.alpha).isEqualTo(1f)
        }
    }

    @Test
    fun `the glass changes the ink only as far as the band asks, and nothing else of the palette`() {
        for (theme in themes) {
            val p = theme.palette
            val look = GlassBands.of(p, p.accentColor(DapAccent.Sage))
            val g = p.onGlass(look)
            assertThat(g.copy(primaryText = p.primaryText, secondaryText = p.secondaryText, tertiaryText = p.tertiaryText, glass = null)).isEqualTo(p)
        }
    }

    @Test
    fun `an unknown or old stored value still means Theme and accent, and Cover glass is its own value`() {
        assertThat(AppColorSource.normalize("theme")).isEqualTo(AppColorSource.THEME)
        assertThat(AppColorSource.normalize("album_art")).isEqualTo(AppColorSource.ALBUM_ART)
        assertThat(AppColorSource.normalize("cover_glass")).isEqualTo(AppColorSource.COVER_GLASS)
        assertThat(AppColorSource.hasGlass(AppColorSource.THEME)).isFalse()
        // B42-fix3: "From album art" has a backdrop too (a gradient), but it is not the glass that moves
        assertThat(AppColorSource.hasGlass(AppColorSource.ALBUM_ART)).isFalse()
        assertThat(AppColorSource.hasBackdrop(AppColorSource.ALBUM_ART)).isTrue()
        assertThat(AppColorSource.hasBackdrop(AppColorSource.COVER_GLASS)).isTrue()
        assertThat(AppColorSource.hasBackdrop(AppColorSource.THEME)).isFalse()
    }
}
