package com.lostf1sh.pixelplayeross.ui.theme

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.AppThemeMode
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import org.junit.jupiter.api.Test

/** The single Theme row: how its four choices are stored, shown and drawn. */
class ThemeAppearanceTest {

    @Test
    fun `an install that never chose keeps drawing Graphite on a light system`() {
        // (Graphite, FOLLOW_SYSTEM) is what every untouched install holds. It must not turn light.
        val drawn = resolveBaseTheme(AppThemeMode.FOLLOW_SYSTEM, DapBaseTheme.Graphite, systemDark = false)
        assertThat(drawn).isEqualTo(DapBaseTheme.Graphite)
        assertThat(appearanceChoiceOf(AppThemeMode.FOLLOW_SYSTEM, DapBaseTheme.Graphite))
            .isEqualTo(DapAppearanceChoice.Graphite)
    }

    @Test
    fun `automatic is Light by day and Graphite by night`() {
        val (base, mode) = storedPairFor(DapAppearanceChoice.Automatic)
        assertThat(resolveBaseTheme(mode, base, systemDark = false)).isEqualTo(DapBaseTheme.Light)
        assertThat(resolveBaseTheme(mode, base, systemDark = true)).isEqualTo(DapBaseTheme.Graphite)
    }

    @Test
    fun `every choice reads back as itself`() {
        DapAppearanceChoice.entries.forEach { choice ->
            val (base, mode) = storedPairFor(choice)
            assertThat(appearanceChoiceOf(mode, base)).isEqualTo(choice)
        }
    }

    @Test
    fun `explicit choices ignore the system and draw what was chosen`() {
        listOf(DapAppearanceChoice.Graphite, DapAppearanceChoice.Black, DapAppearanceChoice.Light).forEach { choice ->
            val (base, mode) = storedPairFor(choice)
            assertThat(resolveBaseTheme(mode, base, systemDark = false)).isEqualTo(base)
            assertThat(resolveBaseTheme(mode, base, systemDark = true)).isEqualTo(base)
        }
    }

    @Test
    fun `a legacy contradiction resolves to the base theme, which is what was painted`() {
        // App theme "light" with base "Graphite" used to leave the app Graphite with light headers.
        assertThat(resolveBaseTheme(AppThemeMode.LIGHT, DapBaseTheme.Graphite, systemDark = false))
            .isEqualTo(DapBaseTheme.Graphite)
        assertThat(appearanceChoiceOf(AppThemeMode.LIGHT, DapBaseTheme.Graphite))
            .isEqualTo(DapAppearanceChoice.Graphite)
    }

    @Test
    fun `an unknown key falls back to Graphite`() {
        assertThat(DapAppearanceChoice.fromKey("sepia")).isEqualTo(DapAppearanceChoice.Graphite)
        assertThat(DapAppearanceChoice.fromKey(null)).isEqualTo(DapAppearanceChoice.Graphite)
    }
}
