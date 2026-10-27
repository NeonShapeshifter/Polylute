package com.lostf1sh.pixelplayeross.ui.theme

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.AppThemeMode
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import org.junit.jupiter.api.Test

/** The single Theme row: how its choices (graphite, black, light, and gray and navy since B40-F1, and automatic) are stored, shown and drawn. */
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
        listOf(
            DapAppearanceChoice.Graphite, DapAppearanceChoice.Black, DapAppearanceChoice.Light, DapAppearanceChoice.Gray, DapAppearanceChoice.Navy,
        ).forEach { choice ->
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

    @Test
    fun `B40-F1 - gray and navy are dark choices of their own, stored as their base theme and read back`() {
        assertThat(storedPairFor(DapAppearanceChoice.Gray)).isEqualTo(DapBaseTheme.Gray to AppThemeMode.DARK)
        assertThat(storedPairFor(DapAppearanceChoice.Navy)).isEqualTo(DapBaseTheme.Navy to AppThemeMode.DARK)
        assertThat(appearanceChoiceOf(AppThemeMode.DARK, DapBaseTheme.Gray)).isEqualTo(DapAppearanceChoice.Gray)
        assertThat(appearanceChoiceOf(AppThemeMode.DARK, DapBaseTheme.Navy)).isEqualTo(DapAppearanceChoice.Navy)
        assertThat(DapAppearanceChoice.fromKey("gray")).isEqualTo(DapAppearanceChoice.Gray)
        assertThat(DapAppearanceChoice.fromKey("navy")).isEqualTo(DapAppearanceChoice.Navy)
        // a dark choice is drawn dark whatever the system says
        assertThat(resolveBaseTheme(AppThemeMode.DARK, DapBaseTheme.Navy, systemDark = false)).isEqualTo(DapBaseTheme.Navy)
        assertThat(DapBaseTheme.Gray.palette.isLight).isFalse()
        assertThat(DapBaseTheme.Navy.palette.isLight).isFalse()
    }

    @Test
    fun `B40-F1 - what the shipped themes store is unchanged`() {
        assertThat(storedPairFor(DapAppearanceChoice.Graphite)).isEqualTo(DapBaseTheme.Graphite to AppThemeMode.DARK)
        assertThat(storedPairFor(DapAppearanceChoice.Black)).isEqualTo(DapBaseTheme.Black to AppThemeMode.DARK)
        assertThat(storedPairFor(DapAppearanceChoice.Light)).isEqualTo(DapBaseTheme.Light to AppThemeMode.LIGHT)
        assertThat(storedPairFor(DapAppearanceChoice.Automatic)).isEqualTo(DapBaseTheme.Light to AppThemeMode.FOLLOW_SYSTEM)
    }
}
