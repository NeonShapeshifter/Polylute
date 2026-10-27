package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.theme.CoverThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** B41: the cover glass moves unless the listener says it should not. */
const val DEFAULT_COVER_GLASS_MOVING = true

@Singleton
class ThemePreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val PLAYER_THEME_PREFERENCE = stringPreferencesKey("player_theme_preference_v2")
        val ALBUM_ART_PALETTE_STYLE = stringPreferencesKey("album_art_palette_style_v1")
        val ALBUM_ART_COLOR_ACCURACY = intPreferencesKey("album_art_color_accuracy_v1")
        val APP_THEME_MODE = stringPreferencesKey("app_theme_mode")

        /**
         * Absent on every install that predates accents, which is exactly why the flow below
         * resolves a missing value to [DapAccent.DEFAULT]: an existing user keeps the appearance
         * they already had rather than being moved onto a new one by upgrading.
         */
        val DAP_ACCENT = stringPreferencesKey("dap_accent_v1")

        /**
         * Base appearance. Absent on every install that predates base themes, which is why the
         * flow resolves a missing value to [DapBaseTheme.DEFAULT]: upgrading must not move an
         * existing user onto a different appearance.
         */
        val DAP_BASE_THEME = stringPreferencesKey("dap_base_theme_v1")

        /** B40-F1: absent on every install that predates it, which is [AppColorSource.THEME]: upgrading moves nobody's colours. */
        val APP_COLOR_SOURCE = stringPreferencesKey("app_color_source_v1")

        /** B41: the cover glass drifts in the full player and on the lock screen. Absent = on (it only matters once "Cover glass" is chosen). */
        val COVER_GLASS_MOVING = booleanPreferencesKey("cover_glass_moving_v1")

        /**
         * B41-fix1 (B1): the light or dark the listener picked while the cover decides the colours. A key of its own so the explicit theme
         * (`dap_base_theme_v1` and `app_theme_mode`) is never touched by it. Absent = what that pair already says ([CoverThemeChoice.derivedFrom]).
         */
        val COVER_THEME_CHOICE = stringPreferencesKey("cover_theme_choice_v1")
    }

    val appThemeModeFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[Keys.APP_THEME_MODE] ?: AppThemeMode.FOLLOW_SYSTEM
    }

    val accentFlow: Flow<DapAccent> = dataStore.data.map { preferences ->
        DapAccent.fromStorageKey(preferences[Keys.DAP_ACCENT])
    }

    val baseThemeFlow: Flow<DapBaseTheme> = dataStore.data.map { preferences ->
        DapBaseTheme.fromStorageKey(preferences[Keys.DAP_BASE_THEME])
    }

    /** B40-F1: [AppColorSource.THEME] or [AppColorSource.ALBUM_ART]. */
    val appColorSourceFlow: Flow<String> = dataStore.data.map { preferences ->
        AppColorSource.normalize(preferences[Keys.APP_COLOR_SOURCE])
    }

    /**
     * B41-fix1 (B1): [CoverThemeChoice.DARK], [CoverThemeChoice.LIGHT] or [CoverThemeChoice.SYSTEM]: what "Theme" says while the cover decides
     * the colours. Until the listener chooses there, it is what the stored theme pair already says.
     */
    val coverThemeChoiceFlow: Flow<String> = dataStore.data.map { preferences ->
        CoverThemeChoice.normalize(preferences[Keys.COVER_THEME_CHOICE])
            ?: CoverThemeChoice.derivedFrom(
                preferences[Keys.APP_THEME_MODE] ?: AppThemeMode.FOLLOW_SYSTEM,
                DapBaseTheme.fromStorageKey(preferences[Keys.DAP_BASE_THEME]),
            )
    }

    /** B41: "Moving background" (Appearance, Player): on unless the listener turned it off. */
    val coverGlassMovingFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.COVER_GLASS_MOVING] ?: DEFAULT_COVER_GLASS_MOVING
    }

    val playerThemePreferenceFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[Keys.PLAYER_THEME_PREFERENCE] ?: ThemePreference.ALBUM_ART
    }

    val albumArtPaletteStyleFlow: Flow<AlbumArtPaletteStyle> = dataStore.data.map { preferences ->
        AlbumArtPaletteStyle.fromStorageKey(preferences[Keys.ALBUM_ART_PALETTE_STYLE])
    }

    val albumArtColorAccuracyFlow: Flow<Int> = dataStore.data.map { preferences ->
        AlbumArtColorAccuracy.clamp(preferences[Keys.ALBUM_ART_COLOR_ACCURACY] ?: AlbumArtColorAccuracy.DEFAULT)
    }

    suspend fun setPlayerThemePreference(themeMode: String) =
        dataStore.edit { preferences ->
            preferences[Keys.PLAYER_THEME_PREFERENCE] = themeMode
        }

    suspend fun setAppColorSource(source: String) =
        dataStore.edit { preferences ->
            preferences[Keys.APP_COLOR_SOURCE] = AppColorSource.normalize(source)
        }

    suspend fun setCoverThemeChoice(choice: String) =
        dataStore.edit { preferences ->
            preferences[Keys.COVER_THEME_CHOICE] = CoverThemeChoice.normalize(choice) ?: CoverThemeChoice.DARK
        }

    suspend fun setCoverGlassMoving(moving: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.COVER_GLASS_MOVING] = moving
        }

    suspend fun setAccent(accent: DapAccent) =
        dataStore.edit { preferences ->
            preferences[Keys.DAP_ACCENT] = accent.storageKey
        }

    suspend fun setBaseTheme(baseTheme: DapBaseTheme) =
        dataStore.edit { preferences ->
            preferences[Keys.DAP_BASE_THEME] = baseTheme.storageKey
        }

    suspend fun setAppThemeMode(themeMode: String) =
        dataStore.edit { preferences ->
            preferences[Keys.APP_THEME_MODE] = themeMode
        }

    suspend fun initializeAppThemeMode(themeMode: String) =
        dataStore.edit { preferences ->
            if (preferences[Keys.APP_THEME_MODE] == null) {
                preferences[Keys.APP_THEME_MODE] = themeMode
            }
        }

    suspend fun setAlbumArtPaletteStyle(style: AlbumArtPaletteStyle) =
        dataStore.edit { preferences ->
            preferences[Keys.ALBUM_ART_PALETTE_STYLE] = style.storageKey
        }

    suspend fun setAlbumArtColorAccuracy(level: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.ALBUM_ART_COLOR_ACCURACY] = AlbumArtColorAccuracy.clamp(level)
        }

    suspend fun setAlbumArtPaletteSettings(
        style: AlbumArtPaletteStyle,
        accuracyLevel: Int
    ) = dataStore.edit { preferences ->
        preferences[Keys.ALBUM_ART_PALETTE_STYLE] = style.storageKey
        preferences[Keys.ALBUM_ART_COLOR_ACCURACY] = AlbumArtColorAccuracy.clamp(accuracyLevel)
    }
}
