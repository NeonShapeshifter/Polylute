package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapPalette
import com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42-fix1, item 8: what draws over the player in "Cover glass" and has no copy of the glass behind it must be **opaque**, or the player
 * shows through it (the Details of B41). The windows left out of the glass (the tag editors, the equalizer presets, the playlist editor, the
 * backup pages) and the small dialogs fill themselves with the Material roles and the design system's roles listed here; `GlassSheetBoundaryTest`
 * and `B42Fix1PagesGlassBoundaryTest` pin that those files use nothing else. This pins that every one of those roles is a solid colour while
 * the glass is on, for every cover and both themes, and that the three roles that *are* see-through (the page's fill, the panels) are the
 * ones the windows do not use.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class B42Fix1OpaqueFillsTest {
    private val covers = listOf(B41Renders.saturated(), B41Renders.veryLight(), B41Renders.veryDark(), B41Renders.grey())
    private val themes = listOf(DapBaseTheme.Graphite, DapBaseTheme.Light)

    private fun forEverySetup(check: (name: String, palette: DapPalette, glass: B42Setup) -> Unit) {
        for (theme in themes) for (cover in covers) {
            val setup = B42Setup(cover, theme)
            check("${theme.name}/${cover.name}", setup.palette, setup)
        }
    }

    @Test
    fun the_design_systems_solid_roles_stay_solid_while_the_glass_is_on() {
        forEverySetup { name, palette, _ ->
            assertThat(palette.glass).isNotNull()
            val solid = mapOf(
                "background" to palette.background, "surface" to palette.surface, "surfaceRaised" to palette.surfaceRaised,
                "primaryText" to palette.primaryText,
            )
            for ((role, color) in solid) assertWithMessage("$name: Dap.$role").that(color.alpha).isEqualTo(1f)
        }
    }

    @Test
    fun the_material_roles_the_left_out_windows_and_the_small_dialogs_fill_with_are_solid() {
        forEverySetup { name, palette, setup ->
            val scheme = dapColorScheme(setup.glass.ink.accent, palette)
            val roles: Map<String, Color> = mapOf(
                "surface" to scheme.surface, "surfaceVariant" to scheme.surfaceVariant, "surfaceContainer" to scheme.surfaceContainer,
                "surfaceContainerLowest" to scheme.surfaceContainerLowest, "surfaceContainerLow" to scheme.surfaceContainerLow,
                "surfaceContainerHigh" to scheme.surfaceContainerHigh, "surfaceContainerHighest" to scheme.surfaceContainerHighest,
                "primaryContainer" to scheme.primaryContainer, "secondaryContainer" to scheme.secondaryContainer,
                "tertiaryContainer" to scheme.tertiaryContainer, "errorContainer" to scheme.errorContainer,
            )
            for ((role, color) in roles) assertWithMessage("$name: colorScheme.$role").that(color.alpha).isEqualTo(1f)
        }
    }

    @Test
    fun the_roles_that_are_see_through_over_the_glass_are_the_pages_and_the_panels_and_nothing_else() {
        // the control: the check above can see a see-through role when there is one
        forEverySetup { name, palette, setup ->
            assertWithMessage("$name: Dap.page").that(palette.page.alpha).isEqualTo(0f)
            assertWithMessage("$name: colorScheme.background").that(dapColorScheme(setup.glass.ink.accent, palette).background.alpha).isEqualTo(0f)
            assertWithMessage("$name: Dap.panel").that(palette.panel.alpha).isLessThan(1f)
        }
    }

    @Test
    fun without_the_glass_every_role_is_solid_as_it_was() {
        for (theme in themes) for (cover in covers) {
            val look = B42Setup(cover, theme).look
            val scheme = dapColorScheme(look.accent, look.palette)
            assertThat(look.palette.glass).isNull()
            assertThat(look.palette.page.alpha).isEqualTo(1f)
            assertThat(scheme.background.alpha).isEqualTo(1f)
            assertThat(look.palette.panel.alpha).isEqualTo(1f)
        }
    }
}
