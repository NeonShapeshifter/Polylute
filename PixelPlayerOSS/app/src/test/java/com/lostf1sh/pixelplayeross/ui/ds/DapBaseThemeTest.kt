package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The base-theme contract.
 *
 * Three appearance choices, one of which — Graphite — every existing install is already on and must
 * not be moved off. The rest is hierarchy: a palette whose surfaces are indistinguishable is a
 * palette that has changed one value and called it a theme.
 */
class DapBaseThemeTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app", relative),
            File("src/main/java/com/lostf1sh/pixelplayeross/$relative"),
            File("app/src/main/java/com/lostf1sh/pixelplayeross/$relative"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
    }

    private fun luma(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    private fun contrast(a: Color, b: Color): Float {
        fun lin(c: Float) = if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        fun l(c: Color) = 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)
        val la = l(a); val lb = l(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    // --- Enum and persistence ---------------------------------------------------------------------

    @Test
    fun `the default base theme is Graphite`() {
        assertThat(DapBaseTheme.DEFAULT).isEqualTo(DapBaseTheme.Graphite)
    }

    @Test
    fun `a store with no base theme resolves to Graphite`() {
        // Every install upgrading into base themes reads exactly this.
        assertThat(DapBaseTheme.fromStorageKey(null)).isEqualTo(DapBaseTheme.Graphite)
    }

    @Test
    fun `an unknown base theme resolves to Graphite rather than throwing`() {
        assertThat(DapBaseTheme.fromStorageKey("vantablack")).isEqualTo(DapBaseTheme.Graphite)
        assertThat(DapBaseTheme.fromStorageKey("")).isEqualTo(DapBaseTheme.Graphite)
        assertThat(DapBaseTheme.fromStorageKey("GRAPHITE")).isEqualTo(DapBaseTheme.Graphite)
    }

    @Test
    fun `every base theme round-trips through its storage key`() {
        DapBaseTheme.entries.forEach {
            assertThat(DapBaseTheme.fromStorageKey(it.storageKey)).isEqualTo(it)
        }
    }

    @Test
    fun `storage keys are unique and lowercase, because they are persisted`() {
        val keys = DapBaseTheme.entries.map { it.storageKey }
        assertThat(keys).containsNoDuplicates()
        keys.forEach { assertThat(it).matches("[a-z]+") }
    }

    @Test
    fun `there are exactly three base themes`() {
        assertThat(DapBaseTheme.entries).hasSize(3)
        assertThat(DapBaseTheme.entries.map { it.storageKey })
            .containsExactly("graphite", "black", "light")
    }

    @Test
    fun `each theme selects its own palette`() {
        assertThat(DapBaseTheme.Graphite.palette).isEqualTo(DapPalette.Graphite)
        assertThat(DapBaseTheme.Black.palette).isEqualTo(DapPalette.Black)
        assertThat(DapBaseTheme.Light.palette).isEqualTo(DapPalette.Light)
    }

    // --- Graphite parity --------------------------------------------------------------------------

    @Test
    fun `Graphite is untouched by this pass, including the new fields`() {
        val g = DapPalette.Graphite
        assertThat(g.background).isEqualTo(Color(0xFF0E0F11))
        assertThat(g.surface).isEqualTo(Color(0xFF16181B))
        assertThat(g.surfaceRaised).isEqualTo(Color(0xFF1E2125))
        assertThat(g.primaryText).isEqualTo(Color(0xFFEDEEF0))
        assertThat(g.onFill).isEqualTo(Color(0xFF0E0F11))
        // New in this pass, and chosen to match what the app already did.
        assertThat(g.scrim).isEqualTo(Color(0xCC000000))
        assertThat(g.isLight).isFalse()
    }

    @Test
    fun `only Light is a light palette`() {
        assertThat(DapPalette.Light.isLight).isTrue()
        assertThat(DapPalette.Graphite.isLight).isFalse()
        assertThat(DapPalette.Black.isLight).isFalse()
    }

    // --- Black hierarchy --------------------------------------------------------------------------

    @Test
    fun `Black has a true-black page but keeps visible surfaces above it`() {
        val b = DapPalette.Black
        assertThat(b.background).isEqualTo(Color(0xFF000000))
        assertThat(luma(b.surface)).isGreaterThan(luma(b.background))
        assertThat(luma(b.surfaceRaised)).isGreaterThan(luma(b.surface))
        // "Not #000 everywhere": the raised surface must be clearly above the page, not a rounding
        // error above it.
        assertThat(luma(b.surfaceRaised) - luma(b.background)).isGreaterThan(0.005f)
    }

    @Test
    fun `Black keeps the graphite text hierarchy`() {
        val b = DapPalette.Black
        assertThat(b.primaryText).isEqualTo(DapPalette.Graphite.primaryText)
        assertThat(luma(b.primaryText)).isGreaterThan(luma(b.secondaryText))
        assertThat(luma(b.secondaryText)).isGreaterThan(luma(b.tertiaryText))
        assertThat(luma(b.tertiaryText)).isGreaterThan(luma(b.disabledText))
    }

    @Test
    fun `Black keeps warning and error semantic rather than theme-derived`() {
        assertThat(DapPalette.Black.warning).isEqualTo(DapPalette.Graphite.warning)
        assertThat(DapPalette.Black.error).isEqualTo(DapPalette.Graphite.error)
    }

    // --- Light hierarchy --------------------------------------------------------------------------

    @Test
    fun `Light is off-white, not white`() {
        val l = DapPalette.Light
        assertThat(l.background).isNotEqualTo(Color(0xFFFFFFFF))
        // Plain (non-linearised) luma: pure white is 1.0, so "off-white" has to sit clearly below
        // it while still reading as a light page.
        assertThat(luma(l.background)).isLessThan(0.99f)
        assertThat(luma(l.background)).isGreaterThan(0.90f)
    }

    @Test
    fun `Light inverts the stacking direction and keeps the steps visible`() {
        // On a light page a raised container is darker, which is Material's own convention and what
        // the previous hardcoded light scheme did.
        val l = DapPalette.Light
        assertThat(luma(l.surface)).isLessThan(luma(l.background))
        assertThat(luma(l.surfaceRaised)).isLessThan(luma(l.surface))
        assertThat(luma(l.background) - luma(l.surfaceRaised)).isGreaterThan(0.005f)
    }

    @Test
    fun `Light text hierarchy is dark and descending`() {
        val l = DapPalette.Light
        assertThat(luma(l.primaryText)).isLessThan(0.2f)
        assertThat(luma(l.primaryText)).isLessThan(luma(l.secondaryText))
        assertThat(luma(l.secondaryText)).isLessThan(luma(l.tertiaryText))
        assertThat(luma(l.tertiaryText)).isLessThan(luma(l.disabledText))
    }

    @Test
    fun `Light hairlines are dark, because white-on-white draws nothing`() {
        val l = DapPalette.Light
        listOf(l.divider, l.border, l.trackStroke, l.interactiveTrack, l.dividerStrong).forEach {
            assertThat(it.red).isWithin(0.001f).of(0f)
            assertThat(it.alpha).isLessThan(1f)
        }
    }

    @Test
    fun `Light text passes contrast on every one of its surfaces`() {
        val l = DapPalette.Light
        listOf(l.background, l.surface, l.surfaceRaised).forEach { bg ->
            assertThat(contrast(l.primaryText, bg)).isAtLeast(4.5f)
            assertThat(contrast(l.secondaryText, bg)).isAtLeast(4.5f)
        }
    }

    @Test
    fun `Light warning and error stay readable on the page`() {
        val l = DapPalette.Light
        assertThat(contrast(l.warning, l.background)).isAtLeast(3f)
        assertThat(contrast(l.error, l.background)).isAtLeast(3f)
    }

    // --- Accent presentation ----------------------------------------------------------------------

    @Test
    fun `dark palettes present the accent unchanged, so graphite does not shift`() {
        DapAccent.entries.forEach { accent ->
            assertThat(DapPalette.Graphite.presentedAccent(accent.color)).isEqualTo(accent.color)
            assertThat(DapPalette.Black.presentedAccent(accent.color)).isEqualTo(accent.color)
        }
    }

    @Test
    fun `a light palette darkens the accent rather than storing a different one`() {
        DapAccent.entries.forEach { accent ->
            val presented = DapPalette.Light.presentedAccent(accent.color)
            assertThat(presented).isNotEqualTo(accent.color)
            assertThat(luma(presented)).isLessThan(luma(accent.color))
            assertThat(presented.alpha).isWithin(0.001f).of(1f)
            // Hue is preserved: the channel ordering of the darkened colour matches the original,
            // so a green stays green rather than drifting toward grey.
            fun order(c: Color) = listOf(c.red, c.green, c.blue, c.red)
                .zipWithNext().map { (a, b) -> a >= b }
            assertThat(order(presented)).isEqualTo(order(accent.color))
        }
    }

    @Test
    fun `every accent is legible on the light palette, on every surface`() {
        val l = DapPalette.Light
        DapAccent.entries.forEach { accent ->
            val presented = l.presentedAccent(accent.color)
            listOf(l.background, l.surface, l.surfaceRaised).forEach { bg ->
                assertThat(contrast(presented, bg)).isAtLeast(4.5f)
            }
            // ...and the ink on an accent-filled control still reads.
            assertThat(contrast(l.onFill, presented)).isAtLeast(4.5f)
        }
    }

    @Test
    fun `every accent is legible on the black palette`() {
        val b = DapPalette.Black
        DapAccent.entries.forEach { accent ->
            listOf(b.background, b.surface, b.surfaceRaised).forEach { bg ->
                assertThat(contrast(accent.color, bg)).isAtLeast(4.5f)
            }
        }
    }

    // --- Wiring ------------------------------------------------------------------------------------

    @Test
    fun `the Material scheme is derived from the palette, not from a second set of neutrals`() {
        val theme = source("ui/ds/DapTheme.kt")
        // The old lightScheme hardcoded its own neutrals; a Light palette under it would have been
        // two authorities disagreeing on the same screen.
        assertThat(theme).doesNotContain("internal fun lightScheme(")
        assertThat(theme).doesNotContain("internal fun darkScheme(")
        assertThat(theme).contains("internal fun dapColorScheme(accent: Color, palette: DapPalette)")
        // Every surface role must come from the palette.
        assertThat(theme).contains("background = palette.background")
        assertThat(theme).contains("surfaceContainerHighest = palette.surfaceRaised")
    }

    @Test
    fun `the theme presents the accent before providing it`() {
        val theme = source("ui/ds/DapTheme.kt")
        assertThat(theme).contains("val accentColor = palette.presentedAccent(accent.color)")
        assertThat(theme).contains("LocalDapAccent provides accentColor")
        // The raw accent must not leak past the presentation step.
        assertThat(theme).doesNotContain("LocalDapAccent provides accent.color")
    }

    @Test
    fun `the theme still has exactly one palette provider`() {
        val theme = source("ui/ds/DapTheme.kt")
        assertThat(theme.split("LocalDapPalette provides").size - 1).isEqualTo(1)
    }

    @Test
    fun `the base theme is persisted with the existing settings store`() {
        val repo = source("data/preferences/ThemePreferencesRepository.kt")
        assertThat(repo).contains("dap_base_theme_v1")
        assertThat(repo).contains("val baseThemeFlow")
        assertThat(repo).contains("suspend fun setBaseTheme")
        // Missing or unknown resolves to the default, so an upgrade cannot move a user's theme.
        assertThat(repo).contains("DapBaseTheme.fromStorageKey")
    }
}
