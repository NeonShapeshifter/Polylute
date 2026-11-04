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

        /** B56-7.1: with the switch on, "Lively" instead of "Gentle". Absent = gentle. */
        val COVER_GLASS_LIVELY = booleanPreferencesKey("cover_glass_lively_v1")

        /** B56-7.2: "Animated covers". Absent = the mode's own default ([AnimatedCovers.defaultEnabled]): on in app mode, off on a DAP. */
        val ANIMATED_COVERS = booleanPreferencesKey("animated_covers_v1")

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

    /** B56-7.1: Lively (true) or Gentle (false) while the background moves. */
    val coverGlassLivelyFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.COVER_GLASS_LIVELY] ?: false
    }

    /** B56-7.2: what the listener chose for "Animated covers", or null while they have not: [AnimatedCovers.effective] reads it with the mode. */
    val animatedCoversStoredFlow: Flow<Boolean?> = dataStore.data.map { preferences -> preferences[Keys.ANIMATED_COVERS] }

    suspend fun setAnimatedCovers(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.ANIMATED_COVERS] = enabled }
    }

    /** B56-7.1: "Background motion", the two keys read as one choice. */
    val backgroundMotionFlow: Flow<BackgroundMotion> = dataStore.data.map { preferences ->
        BackgroundMotion.of(
            moving = preferences[Keys.COVER_GLASS_MOVING] ?: DEFAULT_COVER_GLASS_MOVING,
            lively = preferences[Keys.COVER_GLASS_LIVELY] ?: false,
        )
    }

    suspend fun setBackgroundMotion(motion: BackgroundMotion) =
        dataStore.edit { preferences ->
            preferences[Keys.COVER_GLASS_MOVING] = motion.moves
            // the level is kept while Still: switching back on returns to what it was, unless the listener picked Gentle or Lively
            if (motion.moves) preferences[Keys.COVER_GLASS_LIVELY] = motion == BackgroundMotion.LIVELY
        }

    /**
     * Whether the full player, the queue and the DAP lock screen wear the **song's** colour or the listener's accent.
     *
     * The stored "player theme" key was retired in 1.1.1 (it stays in storage for backups) and this used to return ALBUM_ART always, so the player wore the song's colour
     * even with Settings > Appearance > App colours on "Theme and accent": a black-and-white cover made the shuffle icon, the seek bar and Play grey under a purple
     * accent (B57 2.1). It now follows App colours, the one setting that says where the colours come from: "Theme and accent" -> [ThemePreference.DEFAULT] (the listener's
     * accent); "From album art" and "Cover glass" -> [ThemePreference.ALBUM_ART] (the cover's, as before). Only what `LocalDapAccent` gets in the player changes: the Material
     * scheme is still never the song's (B42-fix3).
     */
    val playerThemePreferenceFlow: Flow<String> = dataStore.data.map { preferences ->
        if (AppColorSource.normalize(preferences[Keys.APP_COLOR_SOURCE]) == AppColorSource.THEME) ThemePreference.DEFAULT else ThemePreference.ALBUM_ART
    }

    val albumArtPaletteStyleFlow: Flow<AlbumArtPaletteStyle> = dataStore.data.map {
        AlbumArtPaletteStyle.TONAL_SPOT
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
