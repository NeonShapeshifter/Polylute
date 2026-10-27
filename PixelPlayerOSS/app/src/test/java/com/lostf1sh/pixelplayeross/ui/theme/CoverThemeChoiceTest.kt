package com.lostf1sh.pixelplayeross.ui.theme

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.data.preferences.AppThemeMode
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * B41-fix1 (B1), as B42-fix3 (item 7) leaves it: while the cover decides the colours the app is drawn on **Graphite**, whatever is stored, and
 * the explicit theme the listener had is not lost.
 *
 * B41-fix1 gave the cover modes a "Theme" row of their own (Dark, Light, Follow the system) written to `cover_theme_choice_v1`. The owner: "there's no
 * light/dark like before any more: now it's the cover in full". The row is gone and nothing reads that choice; **the key, its store and
 * what was saved in it are kept** (a backup made before still restores it), and the tests below pin that: the store still round-trips and never
 * touches the explicit theme, so that going back to "Theme and accent" shows the theme they had.
 */
class CoverThemeChoiceTest {

    // ── what is drawn ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with the cover deciding the colours, dark draws Graphite whatever explicit theme is stored`() {
        for (source in listOf(AppColorSource.ALBUM_ART, AppColorSource.COVER_GLASS)) {
            for (stored in DapBaseTheme.entries) {
                for (mode in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT, AppThemeMode.FOLLOW_SYSTEM)) {
                    assertEquals(
                        DapBaseTheme.Graphite,
                        resolveDrawnBaseTheme(source, mode, stored, systemDark = false),
                        "over a stored $stored/$mode in $source",
                    )
                }
            }
        }
    }

    @Test
    fun `the cover modes are Graphite by day and by night, whatever the system says and whatever light or dark was chosen there before`() {
        for (source in listOf(AppColorSource.ALBUM_ART, AppColorSource.COVER_GLASS)) {
            for (systemDark in listOf(false, true)) {
                for (stored in DapBaseTheme.entries) {
                    assertEquals(DapBaseTheme.Graphite, resolveDrawnBaseTheme(source, AppThemeMode.FOLLOW_SYSTEM, stored, systemDark))
                    assertEquals(DapBaseTheme.Graphite, resolveDrawnBaseTheme(source, AppThemeMode.LIGHT, stored, systemDark))
                }
            }
        }
    }

    @Test
    fun `with Theme and accent the stored theme is drawn exactly as it always was`() {
        for (stored in DapBaseTheme.entries) {
            for (mode in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT, AppThemeMode.FOLLOW_SYSTEM)) {
                for (systemDark in listOf(false, true)) {
                    assertEquals(
                        resolveBaseTheme(mode, stored, systemDark),
                        resolveDrawnBaseTheme(AppColorSource.THEME, mode, stored, systemDark),
                    )
                }
            }
        }
        assertEquals(DapBaseTheme.Navy, resolveDrawnBaseTheme(AppColorSource.THEME, AppThemeMode.DARK, DapBaseTheme.Navy, systemDark = false))
    }

    // ── what it is before the listener chooses ──────────────────────────────────────────────────────

    @Test
    fun `before a choice is made, an install on Cover glass keeps the light or dark it has been seeing`() {
        // Light and Automatic are what they say; every other explicit theme is dark
        assertEquals(CoverThemeChoice.LIGHT, CoverThemeChoice.derivedFrom(AppThemeMode.LIGHT, DapBaseTheme.Light))
        assertEquals(CoverThemeChoice.SYSTEM, CoverThemeChoice.derivedFrom(AppThemeMode.FOLLOW_SYSTEM, DapBaseTheme.Light))
        for (dark in listOf(DapBaseTheme.Graphite, DapBaseTheme.Black, DapBaseTheme.Gray, DapBaseTheme.Navy)) {
            assertEquals(CoverThemeChoice.DARK, CoverThemeChoice.derivedFrom(AppThemeMode.DARK, dark))
        }
        // an install that never touched the rows holds (Graphite, follow system): Graphite is what it draws, so dark, not "system"
        assertEquals(CoverThemeChoice.DARK, CoverThemeChoice.derivedFrom(AppThemeMode.FOLLOW_SYSTEM, DapBaseTheme.Graphite))
        // (B42-fix3: the value is derived and kept, and no longer drawn: the cover modes are Graphite)
        assertEquals(DapBaseTheme.Graphite, resolveDrawnBaseTheme(AppColorSource.COVER_GLASS, AppThemeMode.LIGHT, DapBaseTheme.Light, systemDark = true))
    }

    @Test
    fun `a value that is not a choice is nothing, not a theme`() {
        assertNull(CoverThemeChoice.normalize(null))
        assertNull(CoverThemeChoice.normalize("navy"))
        assertNull(CoverThemeChoice.normalize(""))
        assertEquals(CoverThemeChoice.SYSTEM, CoverThemeChoice.normalize("system"))
    }

    // ── what is stored, and what is not lost ────────────────────────────────────────────────────────

    @Test
    fun `going back to Theme and accent shows the theme the listener had before`() = runTest {
        val dir = Files.createTempDirectory("b41fix1-cover-theme")
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("s.preferences_pb").toFile() })
            val prefs = ThemePreferencesRepository(store)
            // the listener chose Navy in "Theme and accent"
            val (base, mode) = storedPairFor(DapAppearanceChoice.Navy)
            prefs.setBaseTheme(base)
            prefs.setAppThemeMode(mode)
            // then chose "Cover glass", and picked Light there, then System, then Dark
            prefs.setAppColorSource(AppColorSource.COVER_GLASS)
            assertEquals(CoverThemeChoice.DARK, prefs.coverThemeChoiceFlow.first())   // nothing chosen yet: what Navy says
            for (choice in listOf(CoverThemeChoice.LIGHT, CoverThemeChoice.SYSTEM, CoverThemeChoice.DARK, CoverThemeChoice.LIGHT)) {
                prefs.setCoverThemeChoice(choice)
                assertEquals(choice, prefs.coverThemeChoiceFlow.first())
                // the explicit theme is not touched by any of it
                assertEquals(DapBaseTheme.Navy, prefs.baseThemeFlow.first())
                assertEquals(AppThemeMode.DARK, prefs.appThemeModeFlow.first())
            }
            // back to "Theme and accent": Navy, as it was
            prefs.setAppColorSource(AppColorSource.THEME)
            assertEquals(
                DapBaseTheme.Navy,
                resolveDrawnBaseTheme(prefs.appColorSourceFlow.first(), prefs.appThemeModeFlow.first(), prefs.baseThemeFlow.first(), systemDark = false),
            )
            assertEquals(DapAppearanceChoice.Navy, appearanceChoiceOf(prefs.appThemeModeFlow.first(), prefs.baseThemeFlow.first()))
            // and back to the cover: it is drawn on Graphite, and what they picked there is still stored
            prefs.setAppColorSource(AppColorSource.COVER_GLASS)
            assertEquals(DapBaseTheme.Graphite, resolveDrawnBaseTheme(prefs.appColorSourceFlow.first(), prefs.appThemeModeFlow.first(), prefs.baseThemeFlow.first(), systemDark = false))
            assertEquals(CoverThemeChoice.LIGHT, prefs.coverThemeChoiceFlow.first())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the only new key is the one for the light or dark choice, and it holds nothing else`() = runTest {
        val dir = Files.createTempDirectory("b41fix1-cover-theme-key")
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("s.preferences_pb").toFile() })
            val prefs = ThemePreferencesRepository(store)
            val before = store.data.first().asMap().keys.map { it.name }.toSet()
            prefs.setCoverThemeChoice(CoverThemeChoice.LIGHT)
            prefs.setCoverThemeChoice("navy")   // not a choice: it is stored as dark, never as a theme
            val raw = store.data.first()
            assertEquals(setOf("cover_theme_choice_v1"), raw.asMap().keys.map { it.name }.toSet() - before)
            assertEquals("dark", raw[stringPreferencesKey("cover_theme_choice_v1")])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
