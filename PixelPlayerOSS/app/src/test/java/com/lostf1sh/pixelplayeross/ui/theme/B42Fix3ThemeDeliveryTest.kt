package com.lostf1sh.pixelplayeross.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.lostf1sh.pixelplayeross.ui.glass.onGlass
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAppAccent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B42-fix3, item 1: **how the per-song colours are delivered** (the cause, one level below the Library).
 *
 * Material3 1.5 hands its colour scheme to everything below `MaterialTheme` through a single *static* composition local that holds the scheme by
 * value (the scheme is immutable now: no fields to update in place). The theme used to give it the scheme of the accent in force, which in the cover
 * modes is the song's: a scheme that differs by one colour changed that static local, and a changed static local leaves the whole content under its
 * provider with no group that can skip. **A composable with no parameters, a Library row, an artwork painter: all of it ran again, on every song.**
 *
 * What is held here, with a child that has no parameters and reads nothing (so that anything that runs it again is the theme's doing):
 *  - a new accent from the cover does not run it, under `PixelPlayerTheme` or `DapTheme`;
 *  - the composables that **wear** the accent ([LocalDapAccent], [LocalDapAppAccent]) do run, once, and see the new accent (so the accent really moved);
 *  - a change of **theme** (the palette) still reaches everything, as it must;
 *  - the Material scheme is the listener's own accent whatever the song's is ("Theme and accent" is exactly what it was).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B42Fix3ThemeDeliveryTest {
    @get:Rule
    val compose = createComposeRule()

    private val accent = MutableStateFlow(Color(0xFFD08020))
    private val theme = MutableStateFlow(DapBaseTheme.Graphite)
    private var bystanders = 0
    private var wearers = 0
    private var appWearers = 0
    private var lastWorn: Color? = null
    private var lastAppWorn: Color? = null
    private var lastPrimary: Color? = null
    private var lastBackground: Color? = null

    /** Reads nothing from the theme and has no parameters: it runs again only if the theme took its group away from skipping. */
    @Composable
    private fun Bystander() {
        SideEffect { bystanders++ }
    }

    @Composable
    private fun Wearer() {
        val worn = LocalDapAccent.current
        SideEffect { wearers++; lastWorn = worn }
    }

    @Composable
    private fun AppWearer() {
        val worn = LocalDapAppAccent.current
        SideEffect { appWearers++; lastAppWorn = worn }
    }

    @Composable
    private fun Everything() {
        Bystander()
        Wearer()
        AppWearer()
        val primary = MaterialTheme.colorScheme.primary
        val background = Dap.background
        SideEffect { lastPrimary = primary; lastBackground = background }
    }

    private fun show(root: @Composable (Color, DapBaseTheme) -> Unit) {
        compose.setContent {
            val a by accent.collectAsState()
            val t by theme.collectAsState()
            root(a, t)
        }
        compose.waitForIdle()
    }

    @Test
    fun a_new_accent_does_not_run_what_does_not_wear_it_under_pixel_player_theme() {
        show { a, t -> PixelPlayerTheme(darkTheme = true, accent = DapAccent.Sage, baseTheme = t, accentColorOverride = a) { Everything() } }
        val initialBystanders = bystanders
        val initialWearers = wearers
        repeat(5) { n ->
            accent.value = Color(0xFF000000 or (0x1A2B3CL * (n + 1)).toInt().toLong())
            compose.waitForIdle()
        }
        assertThat(bystanders - initialBystanders).isEqualTo(0)
        // the ones that wear it do run, once per song, and see the song's accent
        assertThat(wearers - initialWearers).isEqualTo(5)
        assertThat(lastWorn).isEqualTo(accent.value)
        assertThat(lastAppWorn).isEqualTo(accent.value)
    }

    @Test
    fun a_new_accent_does_not_run_what_does_not_wear_it_under_dap_theme() {
        show { a, t -> DapTheme(darkTheme = true, accent = DapAccent.Sage, baseTheme = t, accentColorOverride = a) { Everything() } }
        val initialBystanders = bystanders
        repeat(5) { n ->
            accent.value = Color(0xFF000000 or (0x1A2B3CL * (n + 1)).toInt().toLong())
            compose.waitForIdle()
        }
        assertThat(bystanders - initialBystanders).isEqualTo(0)
    }

    @Test
    fun the_material_scheme_is_the_listeners_own_accent_whatever_the_songs_is() {
        show { a, t -> PixelPlayerTheme(darkTheme = true, accent = DapAccent.Amber, baseTheme = t, accentColorOverride = a) { Everything() } }
        val listeners = DapBaseTheme.Graphite.palette.accentColor(DapAccent.Amber)
        assertThat(lastPrimary).isEqualTo(listeners)
        accent.value = Color(0xFF2090D0)
        compose.waitForIdle()
        assertThat(lastPrimary).isEqualTo(listeners)
        assertThat(lastWorn).isEqualTo(Color(0xFF2090D0))
    }

    @Test
    fun over_the_glass_the_material_accent_is_the_listeners_lifted_to_read_and_the_same_for_every_song() {
        val base = DapBaseTheme.Graphite.palette
        val glassPalette = base.onGlass(com.lostf1sh.pixelplayeross.ui.glass.GlassBands.of(base, base.accentColor(DapAccent.Sage)))
        show { a, _ -> PixelPlayerTheme(darkTheme = true, accent = DapAccent.Sage, baseTheme = DapBaseTheme.Graphite, palette = glassPalette, accentColorOverride = a) { Everything() } }
        val lifted = com.lostf1sh.pixelplayeross.ui.glass.GlassBands.of(base, base.accentColor(DapAccent.Sage)).ink.accent
        assertThat(lastPrimary).isEqualTo(lifted)
        // it reads on the lightest page glass (the same bar the song's accent is lifted to)
        assertThat(com.lostf1sh.pixelplayeross.ui.glass.GlassLuma.contrast(lifted.luminance(), 0.10f)).isAtLeast(4.5f)
        val before = bystanders
        repeat(3) { n ->
            accent.value = Color(0xFF000000 or (0x2A1B0CL * (n + 1)).toInt().toLong())
            compose.waitForIdle()
            assertThat(lastPrimary).isEqualTo(lifted)
        }
        assertThat(bystanders - before).isEqualTo(0)
    }

    @Test
    fun with_no_override_the_accent_is_the_listeners_everywhere_as_it_always_was() {
        show { _, t -> PixelPlayerTheme(darkTheme = true, accent = DapAccent.Teal, baseTheme = t, accentColorOverride = null) { Everything() } }
        val listeners = DapBaseTheme.Graphite.palette.accentColor(DapAccent.Teal)
        assertThat(lastPrimary).isEqualTo(listeners)
        assertThat(lastWorn).isEqualTo(listeners)
        assertThat(lastAppWorn).isEqualTo(listeners)
    }

    @Test
    fun a_change_of_theme_still_reaches_everything_and_the_palette_is_the_new_ones() {
        show { a, t -> PixelPlayerTheme(darkTheme = !t.palette.isLight, accent = DapAccent.Sage, baseTheme = t, accentColorOverride = a) { Everything() } }
        assertThat(lastBackground).isEqualTo(DapBaseTheme.Graphite.palette.background)
        val before = bystanders
        theme.value = DapBaseTheme.Navy
        compose.waitForIdle()
        // a theme is a different palette: it is a static change and everything under it runs, as it did and as it should
        assertThat(bystanders - before).isAtLeast(1)
        assertThat(lastBackground).isEqualTo(DapBaseTheme.Navy.palette.background)
        theme.value = DapBaseTheme.Graphite
        compose.waitForIdle()
    }
}
