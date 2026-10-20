package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The palette migration contract.
 *
 * Two things have to stay true: Graphite must be byte-for-byte the appearance the app already had,
 * and the old static authority must not come back. The first is a parity guarantee, the second is
 * the whole point of the migration — a stray static read would be a colour no theme can change,
 * which is the bug this pass exists to remove.
 */
class DapPaletteTest {

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

    private fun productionSources(): List<File> {
        val roots = listOf(
            File("app/src/main/java/com/lostf1sh/pixelplayeross"),
            File("src/main/java/com/lostf1sh/pixelplayeross"),
        )
        val root = roots.firstOrNull { it.isDirectory } ?: return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    // --- Graphite parity --------------------------------------------------------------------------

    @Test
    fun `Graphite is byte-for-byte the appearance the app already had`() {
        val g = DapPalette.Graphite
        // These are the exact constants of the static object this replaced. If one changes, the app
        // changes for every existing install — so they are pinned rather than described.
        assertThat(g.background).isEqualTo(Color(0xFF0E0F11))
        assertThat(g.surface).isEqualTo(Color(0xFF16181B))
        assertThat(g.surfaceRaised).isEqualTo(Color(0xFF1E2125))

        assertThat(g.dividerFaint).isEqualTo(Color(0x0FFFFFFF))
        assertThat(g.divider).isEqualTo(Color(0x14FFFFFF))
        assertThat(g.border).isEqualTo(Color(0x1AFFFFFF))
        assertThat(g.trackStroke).isEqualTo(Color(0x1FFFFFFF))
        assertThat(g.interactiveTrack).isEqualTo(Color(0x24FFFFFF))
        assertThat(g.dividerStrong).isEqualTo(Color(0x29FFFFFF))

        assertThat(g.primaryText).isEqualTo(Color(0xFFEDEEF0))
        assertThat(g.secondaryText).isEqualTo(Color(0xFFA0A2A6))
        assertThat(g.tertiaryText).isEqualTo(Color(0xFF7B7D82))
        assertThat(g.disabledText).isEqualTo(Color(0xFF5A5C61))

        assertThat(g.warning).isEqualTo(Color(0xFFD9A066))

        assertThat(g.artPlaceholderHigh).isEqualTo(Color(0xFF2A2E33))
        assertThat(g.artPlaceholderLow).isEqualTo(Color(0xFF22262A))
        assertThat(g.artPlaceholderDisabled).isEqualTo(Color(0xFF3A3E44))

        assertThat(g.onFill).isEqualTo(Color(0xFF0E0F11))
    }

    @Test
    fun `ink on a filled control is the page colour, as it always was`() {
        // `OnFill` was defined as a reference to `Bg0`, not as a second literal. Keep the invariant.
        assertThat(DapPalette.Graphite.onFill).isEqualTo(DapPalette.Graphite.background)
    }

    @Test
    fun `the default palette is Graphite`() {
        assertThat(DapPalette.Default).isEqualTo(DapPalette.Graphite)
    }

    @Test
    fun `the palette has a safe default, so a surface outside the theme still renders`() {
        // LocalDapPalette's default is what a preview, a test, or a stray composable sees.
        val fallback = DapPalette.Default
        assertThat(fallback).isEqualTo(DapPalette.Graphite)
    }

    @Test
    fun `every palette colour is opaque or a deliberate low-alpha hairline`() {
        val g = DapPalette.Graphite
        val hairlines = listOf(
            g.dividerFaint, g.divider, g.border,
            g.trackStroke, g.interactiveTrack, g.dividerStrong,
        )
        val solids = listOf(
            g.background, g.surface, g.surfaceRaised,
            g.primaryText, g.secondaryText, g.tertiaryText, g.disabledText,
            g.warning, g.error,
            g.artPlaceholderHigh, g.artPlaceholderLow, g.artPlaceholderDisabled,
            g.onFill,
        )
        solids.forEach { assertThat(it.alpha).isWithin(0.001f).of(1f) }
        // Hairs are expressed as white at low alpha so they sit correctly on any surface.
        hairlines.forEach {
            assertThat(it.red).isWithin(0.001f).of(1f)
            assertThat(it.alpha).isLessThan(1f)
        }
    }

    @Test
    fun `text emphasis decreases monotonically`() {
        // A palette where "tertiary" is brighter than "primary" is a palette that will invert
        // under a future theme. Pin the order now, while there is only one to check.
        val g = DapPalette.Graphite
        fun luma(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        assertThat(luma(g.primaryText)).isGreaterThan(luma(g.secondaryText))
        assertThat(luma(g.secondaryText)).isGreaterThan(luma(g.tertiaryText))
        assertThat(luma(g.tertiaryText)).isGreaterThan(luma(g.disabledText))
    }

    @Test
    fun `surfaces stack lightest-on-top`() {
        val g = DapPalette.Graphite
        fun luma(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        assertThat(luma(g.surface)).isGreaterThan(luma(g.background))
        assertThat(luma(g.surfaceRaised)).isGreaterThan(luma(g.surface))
    }

    // --- The static authority must not return ------------------------------------------------------

    @Test
    fun `no production source reads the removed static palette`() {
        val offenders = productionSources()
            .filter { it.readText().contains("DapColors") }
            .map { it.path }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the legacy static palette object no longer exists`() {
        val offenders = productionSources()
            .filter { it.readText().contains("object DapColors") }
            .map { it.path }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `there is no mutable global palette store`() {
        // The shortcut this migration refused: a `var` palette a theme could write to. It would
        // leave every call site untouched and be a second source of truth.
        val palette = source("ui/ds/DapPalette.kt")
        assertThat(palette).doesNotContain("var ")
        assertThat(palette).contains("val LocalDapPalette = staticCompositionLocalOf")
    }

    @Test
    fun `the theme is the only authority that provides the palette`() {
        val theme = source("ui/ds/DapTheme.kt")
        assertThat(theme).contains("LocalDapPalette provides palette")
        // A second provider would let a screen switch theme locally.
        assertThat(theme.split("LocalDapPalette provides").size - 1).isEqualTo(1)
    }

    @Test
    fun `the accent authority is unchanged by this migration`() {
        val theme = source("ui/ds/DapTheme.kt")
        // The authority is unchanged; what it provides is now the *presented* accent, so a light
        // palette can darken it for legibility without altering the stored preference. See
        // DapBaseThemeTest for that contract.
        assertThat(theme).contains("LocalDapAccent provides accentColor")
        assertThat(theme).contains("val accentColor = palette.presentedAccent(accent.color)")
        // B29-L11: dynamic since the accent follows the cover on the full player and the lock screen (see AccentRecompositionTest); it was
        // static. Still one local, still the app's default: the authority itself is the same.
        assertThat(theme).contains("val LocalDapAccent = compositionLocalOf { DapAccent.DEFAULT.color }")
        // Accent must not be absorbed into the palette.
        assertThat(theme).contains("accent: DapAccent = DapAccent.Sage")
    }

    @Test
    fun `the palette does not carry accent, warning or error duty for the accent`() {
        // Warning and error are roles the palette owns; the accent is a separate authority and must
        // never be asked to carry a warning meaning.
        val palette = source("ui/ds/DapPalette.kt")
        assertThat(palette).contains("val warning: Color")
        assertThat(palette).contains("val error: Color")
        assertThat(palette).doesNotContain("val accent")
    }
}
