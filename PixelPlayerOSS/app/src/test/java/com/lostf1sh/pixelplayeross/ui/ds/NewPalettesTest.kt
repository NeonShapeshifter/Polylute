package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B40-F1: the two new themes, gray and navy. Their text reads on all three surfaces, their surfaces step up so there is depth (a palette
 * whose surfaces are indistinguishable has changed one value and called it a theme), and what they must not move stays where it is.
 */
class NewPalettesTest {
    private val palettes = mapOf("Gray" to DapPalette.Gray, "Navy" to DapPalette.Navy)

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) = AlbumArtColors.contrast(a, b)

    @Test
    fun `primary, secondary and tertiary text read on the page, the panel and the raised panel`() {
        for ((name, p) in palettes) {
            for (surface in listOf(p.background, p.surface, p.surfaceRaised)) {
                assertThat(contrast(p.primaryText, surface)).isAtLeast(7f)
                assertThat(contrast(p.secondaryText, surface)).isAtLeast(4.5f)
                assertThat(contrast(p.tertiaryText, surface)).isAtLeast(4.5f)
            }
            assertThat(name).isNotEmpty()
        }
    }

    @Test
    fun `warning and error are readable on the page and the panel`() {
        for ((_, p) in palettes) {
            for (surface in listOf(p.background, p.surface, p.surfaceRaised)) {
                assertThat(contrast(p.warning, surface)).isAtLeast(4.5f)
                assertThat(contrast(p.error, surface)).isAtLeast(4.5f)
            }
        }
    }

    @Test
    fun `the surfaces step up in lightness, so there is depth`() {
        for ((_, p) in palettes) {
            assertThat(p.background.luminanceValue()).isLessThan(p.surface.luminanceValue())
            assertThat(p.surface.luminanceValue()).isLessThan(p.surfaceRaised.luminanceValue())
        }
    }

    @Test
    fun `gray is a visible gray and not black, and navy is blue`() {
        val g = DapPalette.Gray
        assertThat(g.background.luminanceValue()).isGreaterThan(DapPalette.Graphite.surfaceRaised.luminanceValue())
        assertThat(g.background.red).isWithin(0.06f).of(g.background.blue)   // neutral
        val n = DapPalette.Navy
        assertThat(n.background.blue).isGreaterThan(n.background.red + 0.05f)
        assertThat(n.surfaceRaised.blue).isGreaterThan(n.surfaceRaised.red + 0.1f)
    }

    @Test
    fun `the ink of a filled control reads on the primary text, and the scrim dims with black`() {
        for ((_, p) in palettes) {
            assertThat(contrast(p.onFill, p.primaryText)).isAtLeast(7f)
            assertThat(p.scrim.red).isEqualTo(0f)
            assertThat(p.isLight).isFalse()
        }
    }

    @Test
    fun `the three palettes that shipped are not touched by gray and navy`() {
        assertThat(DapPalette.Graphite.background).isEqualTo(androidx.compose.ui.graphics.Color(0xFF0E0F11))
        assertThat(DapPalette.Black.background).isEqualTo(androidx.compose.ui.graphics.Color(0xFF000000))
        assertThat(DapPalette.Light.background).isEqualTo(androidx.compose.ui.graphics.Color(0xFFF7F7F8))
    }

    private fun androidx.compose.ui.graphics.Color.luminanceValue() = this.luminance()
}
