package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * B41 rule 8: **no preference key changes.** Moving a row to another page of Settings moves its UI only; an existing install keeps every
 * value. The rows that moved (the lyrics' reading and effects, the DAP lock screen) are read here from a store written with the key
 * names they had before the move, and written through their setters back into those same names. The new rows have new keys that an
 * install that predates them does not have, which read as the defaults the batch promises (on).
 *
 * The values chosen are never the defaults, so a key that moved would read the default and fail.
 */
class B41PreferenceKeysTest {

    private val keyImmersive = booleanPreferencesKey("immersive_lyrics_enabled")
    private val keyImmersiveTimeout = longPreferencesKey("immersive_lyrics_timeout")
    private val keyAnimated = booleanPreferencesKey("use_animated_lyrics")
    private val keyBlur = booleanPreferencesKey("animated_lyrics_blur_enabled")
    private val keyBlurStrength = floatPreferencesKey("animated_lyrics_blur_strength")
    private val keyAlignment = stringPreferencesKey("lyrics_alignment")
    private val keyTranslation = booleanPreferencesKey("show_lyrics_translation")
    private val keyRomanization = booleanPreferencesKey("show_lyrics_romanization")
    private val keyLockScreen = booleanPreferencesKey("dap_lock_screen_enabled")
    private val keyAppColors = stringPreferencesKey("app_color_source_v1")

    @Test
    fun `an install from before the move reads every moved row from the key it always had`() = runTest {
        val dir = Files.createTempDirectory("b41-keys-read")
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("s.preferences_pb").toFile() })
            store.edit {
                it[keyImmersive] = true
                it[keyImmersiveTimeout] = 6000L
                it[keyAnimated] = true
                it[keyBlur] = false
                it[keyBlurStrength] = 1.5f
                it[keyAlignment] = "right"
                it[keyTranslation] = false
                it[keyRomanization] = false
                it[keyLockScreen] = true
            }
            val repo = UserPreferencesRepository(dataStore = store, json = Json)
            assertEquals(true, repo.immersiveLyricsEnabledFlow.first())
            assertEquals(6000L, repo.immersiveLyricsTimeoutFlow.first())
            assertEquals(true, repo.useAnimatedLyricsFlow.first())
            assertEquals(false, repo.animatedLyricsBlurEnabledFlow.first())
            assertEquals(1.5f, repo.animatedLyricsBlurStrengthFlow.first())
            assertEquals("right", repo.lyricsAlignmentFlow.first())
            assertEquals(false, repo.showLyricsTranslationFlow.first())
            assertEquals(false, repo.showLyricsRomanizationFlow.first())
            assertEquals(true, repo.dapLockScreenEnabledFlow.first())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the setters the moved rows call still write the same keys`() = runTest {
        val dir = Files.createTempDirectory("b41-keys-write")
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("s.preferences_pb").toFile() })
            val repo = UserPreferencesRepository(dataStore = store, json = Json)
            repo.setImmersiveLyricsEnabled(true)
            repo.setImmersiveLyricsTimeout(5000L)
            repo.setUseAnimatedLyrics(true)
            repo.setAnimatedLyricsBlurEnabled(false)
            repo.setAnimatedLyricsBlurStrength(1.5f)
            repo.setLyricsAlignment("center")
            repo.setShowLyricsTranslation(false)
            repo.setShowLyricsRomanization(false)
            repo.setDapLockScreenEnabled(true)
            val raw = store.data.first()
            assertEquals(true, raw[keyImmersive])
            assertEquals(5000L, raw[keyImmersiveTimeout])
            assertEquals(true, raw[keyAnimated])
            assertEquals(false, raw[keyBlur])
            assertEquals(1.5f, raw[keyBlurStrength])
            assertEquals("center", raw[keyAlignment])
            assertEquals(false, raw[keyTranslation])
            assertEquals(false, raw[keyRomanization])
            assertEquals(true, raw[keyLockScreen])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the new rows are on for an install that has never heard of them, and live under their own keys`() = runTest {
        val dir = Files.createTempDirectory("b41-keys-new")
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("s.preferences_pb").toFile() })
            val user = UserPreferencesRepository(dataStore = store, json = Json)
            val theme = ThemePreferencesRepository(store)
            // nothing stored: on, and the app's colours are the theme's, as every install has had them
            assertEquals(true, user.lyricsUnderCoverFlow.first())
            assertEquals(true, theme.coverGlassMovingFlow.first())
            assertEquals(AppColorSource.THEME, theme.appColorSourceFlow.first())

            user.setLyricsUnderCover(false)
            theme.setCoverGlassMoving(false)
            theme.setAppColorSource(AppColorSource.COVER_GLASS)
            val raw = store.data.first()
            assertEquals(false, raw[booleanPreferencesKey("lyrics_under_cover_v1")])
            assertEquals(false, raw[booleanPreferencesKey("cover_glass_moving_v1")])
            // the third option is a new value of the key B40 already had, not a new key
            assertEquals("cover_glass", raw[keyAppColors])
            assertEquals(false, user.lyricsUnderCoverFlow.first())
            assertEquals(false, theme.coverGlassMovingFlow.first())
            assertEquals(AppColorSource.COVER_GLASS, theme.appColorSourceFlow.first())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `what B40 stored for the app colours reads the same, and an unknown value is still the theme`() {
        assertEquals(AppColorSource.THEME, AppColorSource.normalize(null))
        assertEquals(AppColorSource.THEME, AppColorSource.normalize("theme"))
        assertEquals(AppColorSource.ALBUM_ART, AppColorSource.normalize("album_art"))
        assertEquals(AppColorSource.COVER_GLASS, AppColorSource.normalize("cover_glass"))
        assertEquals(AppColorSource.THEME, AppColorSource.normalize("liquid"))
        // the two cover options take the cover's accent and tint; only the glass draws the field
        assertEquals(false, AppColorSource.takesColoursFromCover(AppColorSource.THEME))
        assertEquals(true, AppColorSource.takesColoursFromCover(AppColorSource.ALBUM_ART))
        assertEquals(true, AppColorSource.takesColoursFromCover(AppColorSource.COVER_GLASS))
        assertEquals(false, AppColorSource.hasGlass(AppColorSource.ALBUM_ART))
        assertEquals(true, AppColorSource.hasGlass(AppColorSource.COVER_GLASS))
    }
}
