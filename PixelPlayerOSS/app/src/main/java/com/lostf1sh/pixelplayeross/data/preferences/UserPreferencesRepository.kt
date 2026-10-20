package com.lostf1sh.pixelplayeross.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.diagnostics.AdvancedPerformanceDiagnostics
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.model.FolderSource
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.TransitionSettings
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.model.StorageFilter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.text.get
import kotlin.text.set
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsDataStore.NAME,
    // A corrupt file resets to defaults instead of crash-looping the HOME app (AUD-032).
    corruptionHandler = SettingsDataStore.corruptionHandler,
)

object ThemePreference {
    const val DEFAULT = "default"
    const val DYNAMIC = "dynamic"
    const val ALBUM_ART = "album_art"
    const val GLOBAL = "global"
}

object AppThemeMode {
    const val FOLLOW_SYSTEM = "follow_system"
    const val LIGHT = "light"
    const val DARK = "dark"
}

const val MIN_NAV_BAR_CORNER_RADIUS = 0
const val MAX_NAV_BAR_CORNER_RADIUS = 60
const val DAP_BRIGHTNESS_FOLLOW_SYSTEM = -1

/**
 * The one default for `show_queue_history`, read by the repository and mirrored by the Settings
 * UI state so the switch cannot render a value the store does not hold.
 */
const val DEFAULT_SHOW_QUEUE_HISTORY = false

/**
 * The DAP lock surface is opt-in.
 *
 * Conservative on purpose: it is the only setting in the app that changes what appears when the
 * screen comes on, and enabling it by default would alter an existing dedicated player's wake
 * behaviour without being asked.
 */
const val DEFAULT_DAP_LOCK_SCREEN_ENABLED = false

/**
 * Animated-lyrics blur strength: the shipped default and the legal range, in one place so the
 * stored value, the slider and the renderer cannot disagree about what is representable.
 *
 * The renderer multiplies this by line distance and clamps the product at 10 dp, so the upper
 * bound is about what the slider can express, not about what the effect can survive.
 */
const val ANIMATED_LYRICS_BLUR_STRENGTH_DEFAULT = 2.5f
const val ANIMATED_LYRICS_BLUR_STRENGTH_MIN = 0.1f
const val ANIMATED_LYRICS_BLUR_STRENGTH_MAX = 3.0f

/** Lyric line alignment values the renderer understands. */
val LYRICS_ALIGNMENTS = setOf("left", "center", "right")

const val LYRICS_ALIGNMENT_DEFAULT = "left"

/**
 * The declared owner of the lyrics preference key strings.
 *
 * These four keys were written as raw literals in `LyricsSheet`, which made that file the de-facto
 * owner of a persisted schema. The strings are deliberately unchanged — they are on disk on every
 * existing install — but they now have one declaration that both the repository and the sheet use,
 * so the schema cannot drift between two copies of a literal.
 *
 * `KEEP_SCREEN_ON` is declared for the same reason but stays a **session** control owned by the
 * lyrics surface: it resets itself when the screen turns off, so it is not a persistent preference
 * and must not be presented as one in Settings.
 */
object LyricsPreferenceKeys {
    val ALIGNMENT = stringPreferencesKey("lyrics_alignment")
    val SHOW_TRANSLATION = booleanPreferencesKey("show_lyrics_translation")
    val SHOW_ROMANIZATION = booleanPreferencesKey("show_lyrics_romanization")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_lyrics")
}
const val DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM = 0L
const val DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING = -1L

internal fun sanitizeNavBarCornerRadius(radius: Int): Int =
        radius.coerceIn(MIN_NAV_BAR_CORNER_RADIUS, MAX_NAV_BAR_CORNER_RADIUS)

/**
 * The corner radius the product uses now that the nav bar no longer rounds.
 *
 * Kept as the value the retired preference defaulted to, so frozen reads resolve to exactly what an
 * installation that never touched the setting already had.
 */
internal const val DEFAULT_NAV_BAR_CORNER_RADIUS = 32

/**
 * Album art quality settings for developer options.
 * Controls maximum resolution for album artwork in player view.
 * Thumbnails in lists always use low resolution for performance.
 *
 * @property maxSize Maximum size in pixels (0 = original size)
 * @property label Human-readable label for UI
 */
enum class AlbumArtQuality(val maxSize: Int, val label: String) {
    LOW(256, "Low (256px) - Better performance"),
    MEDIUM(512, "Medium (512px) - Balanced"),
    HIGH(800, "High (800px) - Best quality"),
    ORIGINAL(0, "Original - Maximum quality")
}

data class AdvancedPerformanceDiagnosticsSettings(
    val enabled: Boolean,
    val sessionStartedEpochMs: Long?,
    val expiresAtEpochMs: Long?
) {
    fun isActive(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        enabled && expiresAtEpochMs?.let { nowEpochMs < it } == true
}

@Singleton
class UserPreferencesRepository
@Inject
constructor(
        private val dataStore: DataStore<Preferences>,
        private val json: Json,
        private val positionStore: PlaybackPositionStore = InMemoryPlaybackPositionStore(),
) {

    private val backupExcludedKeyNames = setOf(
        PreferencesKeys.INITIAL_SETUP_DONE.name,
        "lastfm_session_key"
    )

    private object PreferencesKeys {
        val APP_REBRAND_DIALOG_SHOWN = booleanPreferencesKey("app_rebrand_dialog_shown")
        val ALLOWED_DIRECTORIES = stringSetPreferencesKey("allowed_directories")
        val BLOCKED_DIRECTORIES = stringSetPreferencesKey("blocked_directories")
        val INITIAL_SETUP_DONE = booleanPreferencesKey("initial_setup_done")
        val PLAYER_THEME_PREFERENCE = stringPreferencesKey("player_theme_preference_v2")
        val ALBUM_ART_PALETTE_STYLE = stringPreferencesKey("album_art_palette_style_v1")
        val APP_THEME_MODE = stringPreferencesKey("app_theme_mode")
        val FAVORITE_SONG_IDS = stringSetPreferencesKey("favorite_song_ids")
        val USER_PLAYLISTS = stringPreferencesKey("user_playlists_json_v1")
        val PLAYLIST_SONG_ORDER_MODES = stringPreferencesKey("playlist_song_order_modes")

        val SONGS_SORT_OPTION = stringPreferencesKey("songs_sort_option")
        val SONGS_SORT_OPTION_MIGRATED = booleanPreferencesKey("songs_sort_option_migrated_v2")
        val ALBUMS_SORT_OPTION = stringPreferencesKey("albums_sort_option")
        val ARTISTS_SORT_OPTION = stringPreferencesKey("artists_sort_option")
        val PLAYLISTS_SORT_OPTION = stringPreferencesKey("playlists_sort_option")
        val FOLDERS_SORT_OPTION = stringPreferencesKey("folders_sort_option")
        val LIKED_SONGS_SORT_OPTION = stringPreferencesKey("liked_songs_sort_option")

        val LAST_LIBRARY_TAB_INDEX =
                intPreferencesKey("last_library_tab_index")
        val LAST_STORAGE_FILTER = stringPreferencesKey("last_storage_filter")
        val MOCK_GENRES_ENABLED = booleanPreferencesKey("mock_genres_enabled")
        /** The automatic collections the listener hid (their keys); they come back with "show hidden collections". */
        val HIDDEN_AUTO_COLLECTIONS = stringSetPreferencesKey("hidden_auto_collections")
        /** The mix of the day and Home's second list, as library identities and the local day they were made for (B28). */
        val AUTO_MIX_SNAPSHOT = stringPreferencesKey("auto_mix_snapshot")
        val NAV_BAR_CORNER_RADIUS = intPreferencesKey("nav_bar_corner_radius")
        val NAV_BAR_STYLE = stringPreferencesKey("nav_bar_style")
        val NAV_BAR_COMPACT_MODE = booleanPreferencesKey("nav_bar_compact_mode")
        val CAROUSEL_STYLE = stringPreferencesKey("carousel_style")
        val LIBRARY_NAVIGATION_MODE = stringPreferencesKey("library_navigation_mode")
        val LAUNCH_TAB = stringPreferencesKey("launch_tab")

        val GLOBAL_TRANSITION_SETTINGS = stringPreferencesKey("global_transition_settings_json")
        val LIBRARY_TABS_ORDER = stringPreferencesKey("library_tabs_order")

        /**
         * Tab ids the user has hidden from the Library strip, as a JSON list.
         *
         * Deliberately a separate key from [LIBRARY_TABS_ORDER]: the order key keeps holding every
         * tab, so it stays a complete record and the existing FOLDERS migration keeps working
         * untouched. A hidden tab is absent from the strip but still present in the order, so it is
         * never lost and can always be brought back. Absent key means nothing is hidden, which is
         * what every existing install has.
         */
        val LIBRARY_TABS_HIDDEN = stringPreferencesKey("library_tabs_hidden")
        val IS_FOLDER_FILTER_ACTIVE = booleanPreferencesKey("is_folder_filter_active")
        val IS_FOLDERS_PLAYLIST_VIEW = booleanPreferencesKey("is_folders_playlist_view")
        val FOLDERS_SOURCE = stringPreferencesKey("folders_source")
        val FOLDER_BACK_GESTURE_NAVIGATION = booleanPreferencesKey("folder_back_gesture_navigation")

        /**
         * Back navigation mode. Stored as the [BackNavigationMode] id, so the key survives a mode
         * being added or removed without its type changing underneath it.
         */
        val BACK_NAVIGATION_MODE = stringPreferencesKey("back_navigation_mode")
        val USE_SMOOTH_CORNERS = booleanPreferencesKey("use_smooth_corners")
        val KEEP_PLAYING_IN_BACKGROUND = booleanPreferencesKey("keep_playing_in_background")
        val IS_CROSSFADE_ENABLED = booleanPreferencesKey("is_crossfade_enabled")
        /** F-02 Pure Direct lock, and the DAC steps it took down to carry the frozen attenuation. */
        val PRISTINE_LOCK = booleanPreferencesKey("pristine_lock_enabled")
        val PRISTINE_LOWERED_STEPS = intPreferencesKey("pristine_lock_lowered_steps")
        val SMART_CROSSFADE_ENABLED = booleanPreferencesKey("smart_crossfade_enabled")
        val DAP_BRIGHTNESS_PERCENT = intPreferencesKey("dap_brightness_percent")
        val DAP_SCREEN_TIMEOUT_MODE = longPreferencesKey("dap_screen_timeout_mode")
        /**
         * **Retired.** The legacy global crossfade duration, superseded by
         * [GLOBAL_TRANSITION_SETTINGS]. Read once by [migrateLegacyCrossfadeDurationIfNeeded] and
         * then removed; no other code may treat it as a duration.
         */
        val CROSSFADE_DURATION = intPreferencesKey("crossfade_duration")

        /** Sentinel for [migrateLegacyCrossfadeDurationIfNeeded]. */
        val CROSSFADE_DURATION_MIGRATED = booleanPreferencesKey("crossfade_duration_migrated_v1")
        val PLAYBACK_SPEED = androidx.datastore.preferences.core.floatPreferencesKey("playback_speed")
        val CUSTOM_GENRES = androidx.datastore.preferences.core.stringSetPreferencesKey("custom_genres")
        val CUSTOM_GENRE_ICONS = stringPreferencesKey("custom_genre_icons")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
        val IS_SHUFFLE_ON = booleanPreferencesKey("is_shuffle_on")
        val PERSISTENT_SHUFFLE_ENABLED = booleanPreferencesKey("persistent_shuffle_enabled")

        /** What switching shuffle on does to the order (B18-R1). Stored as the [RandomMode] id; absent means the default (classic). */
        val RANDOM_MODE = stringPreferencesKey("random_mode")
        val RESUME_ON_HEADSET_RECONNECT = booleanPreferencesKey("resume_on_headset_reconnect")
        val SHOW_QUEUE_HISTORY = booleanPreferencesKey("show_queue_history")

        /**
         * Whether waking the dedicated player raises the DAP lock surface. Off by default: it
         * changes what the device does when the screen comes on, and no existing install should
         * discover that by surprise.
         */
        val DAP_LOCK_SCREEN_ENABLED = booleanPreferencesKey("dap_lock_screen_enabled")

        val PLAYBACK_QUEUE_SNAPSHOT = stringPreferencesKey("playback_queue_snapshot_v1")
        val FULL_PLAYER_SHOW_FILE_INFO = booleanPreferencesKey("full_player_show_file_info")
        val FULL_PLAYER_DELAY_ALL = booleanPreferencesKey("full_player_delay_all")
        val FULL_PLAYER_DELAY_ALBUM = booleanPreferencesKey("full_player_delay_album")
        val FULL_PLAYER_DELAY_METADATA = booleanPreferencesKey("full_player_delay_metadata")
        val FULL_PLAYER_DELAY_PROGRESS = booleanPreferencesKey("full_player_delay_progress")
        val FULL_PLAYER_DELAY_CONTROLS = booleanPreferencesKey("full_player_delay_controls")
        val FULL_PLAYER_PLACEHOLDERS = booleanPreferencesKey("full_player_placeholders")
        val FULL_PLAYER_PLACEHOLDER_TRANSPARENT = booleanPreferencesKey("full_player_placeholder_transparent")
        val FULL_PLAYER_PLACEHOLDERS_ON_CLOSE = booleanPreferencesKey("full_player_placeholders_on_close")
        val FULL_PLAYER_SWITCH_ON_DRAG_RELEASE = booleanPreferencesKey("full_player_switch_on_drag_release")
        val FULL_PLAYER_DELAY_THRESHOLD = intPreferencesKey("full_player_delay_threshold_percent")
        val FULL_PLAYER_CLOSE_THRESHOLD = intPreferencesKey("full_player_close_threshold_percent")
        val USE_PLAYER_SHEET_V2 = booleanPreferencesKey("use_player_sheet_v2")

        val ARTIST_DELIMITERS = stringPreferencesKey("artist_delimiters")
        val ARTIST_WORD_DELIMITERS = stringPreferencesKey("artist_word_delimiters")
        val EXTRACT_ARTISTS_FROM_TITLE = booleanPreferencesKey("extract_artists_from_title")
        val GROUP_BY_ALBUM_ARTIST = booleanPreferencesKey("group_by_album_artist")
        val ARTIST_SETTINGS_RESCAN_REQUIRED =
                booleanPreferencesKey("artist_settings_rescan_required")

        val BACKUP_INFO_DISMISSED = booleanPreferencesKey("backup_info_dismissed")

        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val DIRECTORY_RULES_VERSION = intPreferencesKey("directory_rules_version")
        val LAST_APPLIED_DIRECTORY_RULES_VERSION =
            intPreferencesKey("last_applied_directory_rules_version")

        val LYRICS_SYNC_OFFSETS = stringPreferencesKey("lyrics_sync_offsets_json")

        val LYRICS_SOURCE_PREFERENCE = stringPreferencesKey("lyrics_source_preference")
        val AUTO_SCAN_LRC_FILES = booleanPreferencesKey("auto_scan_lrc_files")
        val EXTERNAL_LYRICS_ENABLED = booleanPreferencesKey("external_lyrics_enabled")
        val EXTERNAL_ARTIST_IMAGES_ENABLED = booleanPreferencesKey("external_artist_images_enabled")

        val ALBUM_ART_QUALITY = stringPreferencesKey("album_art_quality")
        val ALBUM_ART_CACHE_LIMIT_MB = intPreferencesKey("album_art_cache_limit_mb")
        val TAP_BACKGROUND_CLOSES_PLAYER = booleanPreferencesKey("tap_background_closes_player")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val ADVANCED_PERFORMANCE_DIAGNOSTICS_ENABLED =
            booleanPreferencesKey("advanced_performance_diagnostics_enabled")
        val ADVANCED_PERFORMANCE_DIAGNOSTICS_STARTED_AT =
            longPreferencesKey("advanced_performance_diagnostics_started_at_epoch_ms")
        val ADVANCED_PERFORMANCE_DIAGNOSTICS_EXPIRES_AT =
            longPreferencesKey("advanced_performance_diagnostics_expires_at_epoch_ms")
        val IMMERSIVE_LYRICS_ENABLED = booleanPreferencesKey("immersive_lyrics_enabled")
        val IMMERSIVE_LYRICS_TIMEOUT = longPreferencesKey("immersive_lyrics_timeout")
        val USE_ANIMATED_LYRICS = booleanPreferencesKey("use_animated_lyrics")
        val ANIMATED_LYRICS_BLUR_ENABLED = booleanPreferencesKey("animated_lyrics_blur_enabled")
        val ANIMATED_LYRICS_BLUR_STRENGTH = androidx.datastore.preferences.core.floatPreferencesKey("animated_lyrics_blur_strength")

        val IS_GENRE_GRID_VIEW = booleanPreferencesKey("is_genre_grid_view")

        val IS_ALBUMS_LIST_VIEW = booleanPreferencesKey("is_albums_list_view")

        val COLLAGE_PATTERN = stringPreferencesKey("collage_pattern")
        val COLLAGE_AUTO_ROTATE = booleanPreferencesKey("collage_auto_rotate")

        val LAST_PLAYLIST_ID = stringPreferencesKey("last_playlist_id")
        val LAST_PLAYLIST_NAME = stringPreferencesKey("last_playlist_name")

        val MIN_SONG_DURATION = intPreferencesKey("min_song_duration_ms")

        val MIN_TRACKS_PER_ALBUM = intPreferencesKey("min_tracks_per_album")

        val REPLAYGAIN_ENABLED = booleanPreferencesKey("replaygain_enabled")
        val REPLAYGAIN_USE_ALBUM_GAIN = booleanPreferencesKey("replaygain_use_album_gain")
    }

    val appRebrandDialogShownFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.APP_REBRAND_DIALOG_SHOWN] ?: false
            }

    suspend fun setAppRebrandDialogShown(wasShown: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_REBRAND_DIALOG_SHOWN] = wasShown
        }
    }

    val isCrossfadeEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.IS_CROSSFADE_ENABLED] ?: false
            }

    suspend fun setCrossfadeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_CROSSFADE_ENABLED] = enabled
        }
    }

    /** F-02: the single persisted Pure Direct state every requirement reader follows. */
    val pristineLockFlow: Flow<Boolean> =
            dataStore.data.map { preferences -> preferences[PreferencesKeys.PRISTINE_LOCK] ?: false }

    suspend fun pristineLockState(): Pair<Boolean, Int> {
        val preferences = dataStore.data.first()
        return (preferences[PreferencesKeys.PRISTINE_LOCK] ?: false) to
            (preferences[PreferencesKeys.PRISTINE_LOWERED_STEPS] ?: 0)
    }

    suspend fun setPristineLock(locked: Boolean, loweredSteps: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PRISTINE_LOCK] = locked
            preferences[PreferencesKeys.PRISTINE_LOWERED_STEPS] = loweredSteps.coerceAtLeast(0)
        }
    }

    val smartCrossfadeEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.SMART_CROSSFADE_ENABLED] ?: false
            }

    suspend fun setSmartCrossfadeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SMART_CROSSFADE_ENABLED] = enabled
        }
    }

    val dapBrightnessPercentFlow: Flow<Int> =
        dataStore.data.map { preferences ->
            (preferences[PreferencesKeys.DAP_BRIGHTNESS_PERCENT]
                ?: DAP_BRIGHTNESS_FOLLOW_SYSTEM)
                .let { value ->
                    if (value == DAP_BRIGHTNESS_FOLLOW_SYSTEM) value else value.coerceIn(5, 100)
                }
        }.distinctUntilChanged()

    suspend fun setDapBrightnessPercent(percent: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DAP_BRIGHTNESS_PERCENT] = percent.coerceIn(5, 100)
        }
    }

    val dapScreenTimeoutModeFlow: Flow<Long> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.DAP_SCREEN_TIMEOUT_MODE]
                ?: DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM
        }.distinctUntilChanged()

    suspend fun setDapScreenTimeoutMode(mode: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DAP_SCREEN_TIMEOUT_MODE] = mode
        }
    }

    val backupInfoDismissedFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.BACKUP_INFO_DISMISSED] ?: false
    }

    suspend fun setBackupInfoDismissed(dismissed: Boolean) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.BACKUP_INFO_DISMISSED] = dismissed }
    }

    enum class EqualizerViewMode {
        SLIDERS, GRAPH, HYBRID
    }

    /**
     * Adopts the retired `crossfade_duration` slider value into the global transition
     * configuration, once, then retires the key.
     *
     * The legacy key was the effective global duration for every install that predates
     * [globalTransitionSettingsFlow] becoming the authority. A user who set 8 s on that slider set
     * a real intention, so the value is carried over rather than discarded — but only when there is
     * **no** user-authored global transition configuration to overwrite. An install that has one
     * (the global editor has been reachable from a playlist screen for long enough that this is
     * possible) keeps it untouched, including its mode and curves.
     *
     * The key is then removed in every case. Leaving it behind would let a backup restore, or a
     * later reader, resurrect a second duration concept; nothing reads it after this runs, so
     * removal is the safe end state rather than a loss. Playlist-specific overrides live in the
     * `transition_rules` table and are not touched by any of this.
     *
     * Guarded by its own sentinel so it runs once and is idempotent under concurrent starts.
     */
    suspend fun migrateLegacyCrossfadeDurationIfNeeded() {
        dataStore.edit { preferences ->
            if (preferences[PreferencesKeys.CROSSFADE_DURATION_MIGRATED] == true) return@edit

            val legacy = preferences[PreferencesKeys.CROSSFADE_DURATION]
            val authored = preferences[PreferencesKeys.GLOBAL_TRANSITION_SETTINGS]

            if (legacy != null && authored == null) {
                val adopted = TransitionSettings().copy(durationMs = legacy.coerceIn(1000, 12000))
                preferences[PreferencesKeys.GLOBAL_TRANSITION_SETTINGS] = json.encodeToString(adopted)
            }

            preferences.remove(PreferencesKeys.CROSSFADE_DURATION)
            preferences[PreferencesKeys.CROSSFADE_DURATION_MIGRATED] = true
        }
    }

    val playbackSpeedFlow: Flow<Float> =
            dataStore.data.map { preferences ->
                (preferences[PreferencesKeys.PLAYBACK_SPEED] ?: 1.0f).coerceIn(0.25f, 3.0f)
            }

    suspend fun setPlaybackSpeed(speed: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYBACK_SPEED] = speed.coerceIn(0.25f, 3.0f)
        }
    }

    val customGenresFlow: Flow<Set<String>> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.CUSTOM_GENRES] ?: emptySet()
        }

    val customGenreIconsFlow: Flow<Map<String, Int>> =
        dataStore.data.map { preferences ->
            val jsonString = preferences[PreferencesKeys.CUSTOM_GENRE_ICONS]
            if (jsonString != null) {
                try {
                    json.decodeFromString<Map<String, Int>>(jsonString)
                } catch (e: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }
        }

    suspend fun addCustomGenre(genre: String, iconResId: Int? = null) {
        dataStore.edit { preferences ->
            val currentGenres = preferences[PreferencesKeys.CUSTOM_GENRES] ?: emptySet()
            preferences[PreferencesKeys.CUSTOM_GENRES] = currentGenres + genre

            if (iconResId != null) {
                val currentIconsJson = preferences[PreferencesKeys.CUSTOM_GENRE_ICONS]
                val currentIcons = if (currentIconsJson != null) {
                    try {
                        json.decodeFromString<Map<String, Int>>(currentIconsJson)
                    } catch (e: Exception) {
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }

                val newIcons = currentIcons.toMutableMap()
                newIcons[genre] = iconResId
                preferences[PreferencesKeys.CUSTOM_GENRE_ICONS] = json.encodeToString(newIcons)
            }
        }
    }
    val repeatModeFlow: Flow<Int> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.REPEAT_MODE] ?: Player.REPEAT_MODE_OFF
            }

    suspend fun setRepeatMode(@Player.RepeatMode mode: Int) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.REPEAT_MODE] = mode }
    }

    val isShuffleOnFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.IS_SHUFFLE_ON] ?: false
            }

    suspend fun setShuffleOn(on: Boolean) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.IS_SHUFFLE_ON] = on }
    }

    val persistentShuffleEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.PERSISTENT_SHUFFLE_ENABLED] ?: false
            }

    suspend fun setPersistentShuffleEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.PERSISTENT_SHUFFLE_ENABLED] = enabled }
    }

    /**
     * What switching shuffle on does to the order: a new random order each time (classic, the default) or a flag only (fast).
     * Nothing is written on upgrade: an install with no stored value reads [RandomMode.DEFAULT].
     */
    val randomModeFlow: Flow<RandomMode> = dataStore.data
        .map { preferences -> RandomMode.fromId(preferences[PreferencesKeys.RANDOM_MODE]) }
        .distinctUntilChanged()

    suspend fun setRandomMode(mode: RandomMode) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.RANDOM_MODE] = mode.id }
    }

    /** Queue items and cursor in separate keys, so a skip does not re-encode the queue (AUD-113). */
    private val playbackSnapshotStore = PlaybackSnapshotStore(json)

    val playbackQueueSnapshotFlow: Flow<PlaybackQueueSnapshot?> =
            dataStore.data.map { preferences -> playbackSnapshotStore.read(preferences) }

    suspend fun getPlaybackQueueSnapshotOnce(): PlaybackQueueSnapshot? {
        return PlaybackSnapshotStore.merge(playbackQueueSnapshotFlow.first(), positionStore.read())
    }

    suspend fun setPlaybackQueueSnapshot(snapshot: PlaybackQueueSnapshot?) {
        dataStore.edit { preferences -> playbackSnapshotStore.write(preferences, snapshot) }
        if (snapshot == null) positionStore.clear()
    }

    /**
     * Only the playing position (AUD-121), in its own small store: written every 5 s while playing,
     * it must not rewrite this DataStore — which holds the queue snapshot — nor wake its readers
     * (AUD-113). Read back through [getPlaybackQueueSnapshotOnce].
     */
    suspend fun setPlaybackPosition(mediaId: String, positionMs: Long) {
        positionStore.write(PlaybackPositionMark(mediaId, positionMs, System.currentTimeMillis()))
    }

    val artistDelimitersFlow: Flow<List<String>> =
            dataStore.data.map { preferences ->
                val stored = preferences[PreferencesKeys.ARTIST_DELIMITERS]
                if (stored != null) {
                    try {
                        json.decodeFromString<List<String>>(stored)
                    } catch (e: Exception) {
                        DEFAULT_ARTIST_DELIMITERS
                    }
                } else {
                    DEFAULT_ARTIST_DELIMITERS
                }
            }

    suspend fun setArtistDelimiters(delimiters: List<String>) {
        if (delimiters.isEmpty()) {
            return
        }

        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ARTIST_DELIMITERS] = json.encodeToString(delimiters)
            preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] = true
        }
    }

    suspend fun resetArtistDelimitersToDefault() {
        setArtistDelimiters(DEFAULT_ARTIST_DELIMITERS)
    }

    val artistWordDelimitersFlow: Flow<List<String>> =
        dataStore.data.map { preferences ->
            val stored = preferences[PreferencesKeys.ARTIST_WORD_DELIMITERS]
            if (stored != null) {
                try {
                    json.decodeFromString<List<String>>(stored)
                } catch (e: Exception) {
                    DEFAULT_ARTIST_WORD_DELIMITERS
                }
            } else {
                DEFAULT_ARTIST_WORD_DELIMITERS
            }
        }

    suspend fun setArtistWordDelimiters(delimiters: List<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ARTIST_WORD_DELIMITERS] = json.encodeToString(delimiters)
            preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] = true
        }
    }

    suspend fun resetArtistWordDelimitersToDefault() {
        setArtistWordDelimiters(DEFAULT_ARTIST_WORD_DELIMITERS)
    }

    val extractArtistsFromTitleFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.EXTRACT_ARTISTS_FROM_TITLE] ?: true
        }

    suspend fun setExtractArtistsFromTitle(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EXTRACT_ARTISTS_FROM_TITLE] = enabled
            preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] = true
        }
    }

    val groupByAlbumArtistFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.GROUP_BY_ALBUM_ARTIST] ?: false
            }

    suspend fun setGroupByAlbumArtist(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.GROUP_BY_ALBUM_ARTIST] = enabled
            preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] = true
        }
    }

    val artistSettingsRescanRequiredFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] ?: false
            }

    suspend fun clearArtistSettingsRescanRequired() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] = false
        }
    }

    val lastSyncTimestampFlow: Flow<Long> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP] ?: 0L
            }

    val directoryRulesVersionFlow: Flow<Int> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.DIRECTORY_RULES_VERSION] ?: 0
            }

    val lastAppliedDirectoryRulesVersionFlow: Flow<Int> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAST_APPLIED_DIRECTORY_RULES_VERSION] ?: 0
            }

    suspend fun getLastSyncTimestamp(): Long {
        return lastSyncTimestampFlow.first()
    }

    suspend fun getDirectoryRulesVersion(): Int {
        return directoryRulesVersionFlow.first()
    }

    suspend fun getLastAppliedDirectoryRulesVersion(): Int {
        return lastAppliedDirectoryRulesVersionFlow.first()
    }

    suspend fun setLastSyncTimestamp(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP] = timestamp
        }
    }

    suspend fun markDirectoryRulesVersionApplied(version: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_APPLIED_DIRECTORY_RULES_VERSION] = version
        }
    }

    /**
     * Lyrics sync offset per song in milliseconds.
     * Stored as a JSON map: { "songId": offsetMs, ... }
     * Positive values = lyrics appear later (use when lyrics are ahead of audio)
     * Negative values = lyrics appear earlier (use when lyrics are behind audio)
     */
    private val lyricsSyncOffsetsFlow: Flow<Map<String, Int>> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LYRICS_SYNC_OFFSETS]?.let { jsonString ->
                    try {
                        json.decodeFromString<Map<String, Int>>(jsonString)
                    } catch (e: Exception) {
                        emptyMap()
                    }
                } ?: emptyMap()
            }

    fun getLyricsSyncOffsetFlow(songId: String): Flow<Int> {
        return lyricsSyncOffsetsFlow.map { offsets -> offsets[songId] ?: 0 }
    }

    suspend fun getLyricsSyncOffset(songId: String): Int {
        return getLyricsSyncOffsetFlow(songId).first()
    }

    suspend fun setLyricsSyncOffset(songId: String, offsetMs: Int) {
        dataStore.edit { preferences ->
            val currentOffsets = preferences[PreferencesKeys.LYRICS_SYNC_OFFSETS]?.let { jsonString ->
                try {
                    json.decodeFromString<Map<String, Int>>(jsonString).toMutableMap()
                } catch (e: Exception) {
                    mutableMapOf()
                }
            } ?: mutableMapOf()

            if (offsetMs == 0) {
                currentOffsets.remove(songId)
            } else {
                currentOffsets[songId] = offsetMs
            }

            preferences[PreferencesKeys.LYRICS_SYNC_OFFSETS] = json.encodeToString(currentOffsets)
        }
    }

    val lyricsSourcePreferenceFlow: Flow<LyricsSourcePreference> =
            dataStore.data.map { preferences ->
                LyricsSourcePreference.fromName(preferences[PreferencesKeys.LYRICS_SOURCE_PREFERENCE])
            }

    suspend fun setLyricsSourcePreference(preference: LyricsSourcePreference) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LYRICS_SOURCE_PREFERENCE] = preference.name
        }
    }

    val externalLyricsEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.EXTERNAL_LYRICS_ENABLED] ?: false
            }.distinctUntilChanged()

    suspend fun setExternalLyricsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EXTERNAL_LYRICS_ENABLED] = enabled
            if (!enabled && preferences[PreferencesKeys.LYRICS_SOURCE_PREFERENCE] == LyricsSourcePreference.API_FIRST.name) {
                preferences[PreferencesKeys.LYRICS_SOURCE_PREFERENCE] = LyricsSourcePreference.EMBEDDED_FIRST.name
            }
        }
    }

    val externalArtistImagesEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.EXTERNAL_ARTIST_IMAGES_ENABLED] ?: false
            }.distinctUntilChanged()

    suspend fun setExternalArtistImagesEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EXTERNAL_ARTIST_IMAGES_ENABLED] = enabled
        }
    }

    val autoScanLrcFilesFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.AUTO_SCAN_LRC_FILES] ?: false
            }

    suspend fun setAutoScanLrcFiles(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_SCAN_LRC_FILES] = enabled
        }
    }

    /** B32-Q7: what is stored, read through [ImmersiveLyrics.effective], so a stored "on" is off while the feature is hidden. */
    val immersiveLyricsEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                ImmersiveLyrics.effective(preferences[PreferencesKeys.IMMERSIVE_LYRICS_ENABLED] ?: false)
            }

    val immersiveLyricsTimeoutFlow: Flow<Long> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.IMMERSIVE_LYRICS_TIMEOUT] ?: 4000L
            }

    suspend fun setImmersiveLyricsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IMMERSIVE_LYRICS_ENABLED] = enabled
        }
    }

    suspend fun setImmersiveLyricsTimeout(timeout: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IMMERSIVE_LYRICS_TIMEOUT] = timeout
        }
    }

    /**
     * Lyrics display preferences that used to exist only as raw key literals inside the lyrics
     * sheet, which meant the only way to change them was from inside a song.
     *
     * The key strings in [LyricsPreferenceKeys] are unchanged, so values already on disk keep
     * working and keep being backed up; what changes is that they now have one declared owner, and
     * the persistent ones have a Settings surface. The sheet still reads them as flows and applies
     * a change immediately — nothing here caches.
     */
    val lyricsAlignmentFlow: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[LyricsPreferenceKeys.ALIGNMENT] ?: LYRICS_ALIGNMENT_DEFAULT
            }.distinctUntilChanged()

    suspend fun setLyricsAlignment(alignment: String) {
        dataStore.edit { preferences ->
            if (alignment in LYRICS_ALIGNMENTS) {
                preferences[LyricsPreferenceKeys.ALIGNMENT] = alignment
            }
        }
    }

    val showLyricsTranslationFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[LyricsPreferenceKeys.SHOW_TRANSLATION] ?: true
            }.distinctUntilChanged()

    suspend fun setShowLyricsTranslation(show: Boolean) {
        dataStore.edit { preferences ->
            preferences[LyricsPreferenceKeys.SHOW_TRANSLATION] = show
        }
    }

    val showLyricsRomanizationFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[LyricsPreferenceKeys.SHOW_ROMANIZATION] ?: true
            }.distinctUntilChanged()

    suspend fun setShowLyricsRomanization(show: Boolean) {
        dataStore.edit { preferences ->
            preferences[LyricsPreferenceKeys.SHOW_ROMANIZATION] = show
        }
    }

    /**
     * The **global transition configuration**, and the single authority for the global transition
     * duration that playback resolves.
     *
     * This used to end with `settings.copy(durationMs = duration)`, re-imposing the legacy
     * `crossfade_duration` int over whatever the user had configured here. Because
     * [saveGlobalTransitionSettings] only ever wrote this blob, that copy made the duration the
     * global transition editor showed and saved **dead**: editing it changed nothing, and the
     * value playback used came from a different key that the editor never touched. Two duration
     * concepts, one of them a dead end.
     *
     * The blob is the authority now. `durationMs` is read from it, exactly as `mode`, `curveIn`
     * and `curveOut` always were, so the editor, the stored payload and playback all name the
     * same number.
     */
    val globalTransitionSettingsFlow: Flow<TransitionSettings> =
            dataStore.data.map { preferences -> decodeGlobalTransitionSettings(preferences) }

    suspend fun saveGlobalTransitionSettings(settings: TransitionSettings) {
        dataStore.edit { preferences ->
            val jsonString = json.encodeToString(settings)
            preferences[PreferencesKeys.GLOBAL_TRANSITION_SETTINGS] = jsonString
        }
    }

    private fun decodeGlobalTransitionSettings(preferences: Preferences): TransitionSettings =
            preferences[PreferencesKeys.GLOBAL_TRANSITION_SETTINGS]?.let { jsonString ->
                try {
                    json.decodeFromString<TransitionSettings>(jsonString)
                } catch (e: Exception) {
                    TransitionSettings()
                }
            } ?: TransitionSettings()

    /** The keys of the automatic collections that are hidden. Hiding is the only thing that can be done to one. */
    val hiddenAutoCollectionsFlow: Flow<Set<String>> =
            dataStore.data.map { preferences -> preferences[PreferencesKeys.HIDDEN_AUTO_COLLECTIONS] ?: emptySet() }

    suspend fun setAutoCollectionHidden(key: String, hidden: Boolean) {
        dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.HIDDEN_AUTO_COLLECTIONS] ?: emptySet()
            preferences[PreferencesKeys.HIDDEN_AUTO_COLLECTIONS] = if (hidden) current + key else current - key
        }
    }

    suspend fun showAllAutoCollections() {
        dataStore.edit { preferences -> preferences.remove(PreferencesKeys.HIDDEN_AUTO_COLLECTIONS) }
    }

    suspend fun autoMixSnapshotJson(): String? = dataStore.data.first()[PreferencesKeys.AUTO_MIX_SNAPSHOT]

    suspend fun saveAutoMixSnapshotJson(jsonString: String) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.AUTO_MIX_SNAPSHOT] = jsonString }
    }

    val isGenreGridViewFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.IS_GENRE_GRID_VIEW] ?: true
        }

    suspend fun setGenreGridView(isGrid: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_GENRE_GRID_VIEW] = isGrid
        }
    }

    val isAlbumsListViewFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.IS_ALBUMS_LIST_VIEW] ?: false
        }

    suspend fun setAlbumsListView(isList: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_ALBUMS_LIST_VIEW] = isList
        }
    }

    /** Minimum song duration in milliseconds. Default 10000ms (10 seconds). */
    val minSongDurationFlow: Flow<Int> =
        dataStore.data.map { preferences ->
            (preferences[PreferencesKeys.MIN_SONG_DURATION] ?: 10000).coerceIn(0, 120000)
        }

    suspend fun setMinSongDuration(durationMs: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIN_SONG_DURATION] = durationMs.coerceIn(0, 120000)
        }
    }

    suspend fun getMinSongDuration(): Int {
        return minSongDurationFlow.first()
    }

    /**
     * The album paging flow combines on this, so it must only emit when the value actually moves.
     *
     * Every preference repository shares one DataStore, so a bare `dataStore.data.map` re-emits on
     * *any* write anywhere in the app — a queue snapshot, a favourite, a theme change. As a
     * `combine` input that cancelled and restarted the album `Pager` from page 0 each time, which
     * is what made album sorting look stale and unpredictable.
     */
    val minTracksPerAlbumFlow: Flow<Int> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.MIN_TRACKS_PER_ALBUM] ?: 1
        }.distinctUntilChanged()

    suspend fun setMinTracksPerAlbum(minTracks: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIN_TRACKS_PER_ALBUM] = minTracks
        }
    }

    val replayGainEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.REPLAYGAIN_ENABLED] ?: false
        }

    val replayGainUseAlbumGainFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.REPLAYGAIN_USE_ALBUM_GAIN] ?: false
        }

    suspend fun setReplayGainEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.REPLAYGAIN_ENABLED] = enabled
        }
    }

    suspend fun setReplayGainUseAlbumGain(useAlbumGain: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.REPLAYGAIN_USE_ALBUM_GAIN] = useAlbumGain
        }
    }

    val allowedDirectoriesFlow: Flow<Set<String>> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.ALLOWED_DIRECTORIES] ?: emptySet()
            }.distinctUntilChanged()

    val blockedDirectoriesFlow: Flow<Set<String>> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.BLOCKED_DIRECTORIES] ?: emptySet()
            }.distinctUntilChanged()

    val initialSetupDoneFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.INITIAL_SETUP_DONE] ?: false
            }

    val keepPlayingInBackgroundFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.KEEP_PLAYING_IN_BACKGROUND] ?: true
            }

    val resumeOnHeadsetReconnectFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.RESUME_ON_HEADSET_RECONNECT] ?: false
            }

    /**
     * Whether the queue sheet opens at the top of the history rather than at the current song.
     *
     * This default is the authority. [SettingsUiState.showQueueHistory] used to start at `true`
     * while this map started at `false`, so the switch rendered "on" for one frame and then
     * corrected itself — a control that contradicted its own stored value before the user touched
     * anything.
     */
    val showQueueHistoryFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.SHOW_QUEUE_HISTORY] ?: DEFAULT_SHOW_QUEUE_HISTORY
            }

    val dapLockScreenEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.DAP_LOCK_SCREEN_ENABLED] ?: DEFAULT_DAP_LOCK_SCREEN_ENABLED
        }


    suspend fun setDapLockScreenEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DAP_LOCK_SCREEN_ENABLED] = enabled
        }
    }

    suspend fun setShowQueueHistory(show: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_QUEUE_HISTORY] = show
        }
    }

    val showPlayerFileInfoFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.FULL_PLAYER_SHOW_FILE_INFO] ?: true
            }

    suspend fun setShowPlayerFileInfo(show: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_SHOW_FILE_INFO] = show
        }
    }

    // Legacy keys remain readable by backup/import code, but the DAP presentation
    // policy is fixed: player content and controls are immediately available.
    val fullPlayerLoadingTweaksFlow: Flow<FullPlayerLoadingTweaks> = dataStore.data
        .map { FullPlayerLoadingTweaks() }

    val favoriteSongIdsFlow: Flow<Set<String>> =
            dataStore.data
                    .map { preferences ->
                preferences[PreferencesKeys.FAVORITE_SONG_IDS] ?: emptySet()
            }

    val playlistSongOrderModesFlow: Flow<Map<String, String>> =
            dataStore.data.map { preferences ->
                val serializedModes = preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES]
                if (serializedModes.isNullOrBlank()) {
                    emptyMap()
                } else {
                    runCatching { json.decodeFromString<Map<String, String>>(serializedModes) }
                            .getOrDefault(emptyMap())
                }
            }

    val legacyUserPlaylistsFlow: Flow<List<Playlist>> =
            dataStore.data.map { preferences ->
                val jsonString = preferences[PreferencesKeys.USER_PLAYLISTS]
                if (jsonString != null) {
                    runCatching { json.decodeFromString<List<Playlist>>(jsonString) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }

    suspend fun getLegacyUserPlaylistsOnce(): List<Playlist> = legacyUserPlaylistsFlow.first()

    suspend fun clearLegacyUserPlaylists() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.USER_PLAYLISTS)
        }
    }

    suspend fun setPlaylistSongOrderMode(playlistId: String, modeValue: String) {
        dataStore.edit { preferences ->
            val existingModes =
                    preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES]?.let { raw ->
                        runCatching { json.decodeFromString<Map<String, String>>(raw) }
                                .getOrDefault(emptyMap())
                    }
                            ?: emptyMap()

            val updated = existingModes.toMutableMap()
            updated[playlistId] = modeValue

            preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES] = json.encodeToString(updated)
        }
    }

    suspend fun setPlaylistSongOrderModes(modes: Map<String, String>) {
        dataStore.edit { preferences ->
            if (modes.isEmpty()) {
                preferences.remove(PreferencesKeys.PLAYLIST_SONG_ORDER_MODES)
            } else {
                preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES] = json.encodeToString(modes)
            }
        }
    }

    suspend fun clearPlaylistSongOrderMode(playlistId: String) {
        dataStore.edit { preferences ->
            val existingModes =
                    preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES]?.let { raw ->
                        runCatching { json.decodeFromString<Map<String, String>>(raw) }
                                .getOrDefault(emptyMap())
                    }
                            ?: emptyMap()

            if (!existingModes.containsKey(playlistId)) return@edit

            val updated = existingModes.toMutableMap()
            updated.remove(playlistId)

            preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES] = json.encodeToString(updated)
        }
    }

    suspend fun updateAllowedDirectories(allowedPaths: Set<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALLOWED_DIRECTORIES] = allowedPaths
            preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP] = 0L
            val currentVersion = preferences[PreferencesKeys.DIRECTORY_RULES_VERSION] ?: 0
            preferences[PreferencesKeys.DIRECTORY_RULES_VERSION] =
                if (currentVersion == Int.MAX_VALUE) 0 else currentVersion + 1
        }
    }

    suspend fun updateDirectorySelections(allowedPaths: Set<String>, blockedPaths: Set<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALLOWED_DIRECTORIES] = allowedPaths
            preferences[PreferencesKeys.BLOCKED_DIRECTORIES] = blockedPaths
            preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP] = 0L
            val currentVersion = preferences[PreferencesKeys.DIRECTORY_RULES_VERSION] ?: 0
            preferences[PreferencesKeys.DIRECTORY_RULES_VERSION] =
                if (currentVersion == Int.MAX_VALUE) 0 else currentVersion + 1
        }
    }

    suspend fun toggleFavoriteSong(
            songId: String,
            removing: Boolean = false
    ) {
        dataStore.edit { preferences ->
            val currentFavorites = preferences[PreferencesKeys.FAVORITE_SONG_IDS] ?: emptySet()
            val contains = currentFavorites.contains(songId)

            if (contains) preferences[PreferencesKeys.FAVORITE_SONG_IDS] = currentFavorites - songId
            else {
                if (removing)
                        preferences[PreferencesKeys.FAVORITE_SONG_IDS] = currentFavorites - songId
                else preferences[PreferencesKeys.FAVORITE_SONG_IDS] = currentFavorites + songId
            }
        }
    }

    suspend fun setFavoriteSong(songId: String, isFavorite: Boolean) {
        dataStore.edit { preferences ->
            val currentFavorites = preferences[PreferencesKeys.FAVORITE_SONG_IDS] ?: emptySet()
            preferences[PreferencesKeys.FAVORITE_SONG_IDS] = if (isFavorite) {
                currentFavorites + songId
            } else {
                currentFavorites - songId
            }
        }
    }

    suspend fun clearFavoriteSongIds() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FAVORITE_SONG_IDS] = emptySet()
        }
    }

    suspend fun setInitialSetupDone(isDone: Boolean) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.INITIAL_SETUP_DONE] = isDone }
    }

    val songsSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.SONGS_SORT_OPTION],
                                SortOption.SONGS,
                                SortOption.SongTitleAZ
                        )
                        .storageKey
            }

    val albumsSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.ALBUMS_SORT_OPTION],
                                SortOption.ALBUMS,
                                SortOption.AlbumTitleAZ
                        )
                        .storageKey
            }

    val artistsSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.ARTISTS_SORT_OPTION],
                                SortOption.ARTISTS,
                                SortOption.ArtistNameAZ
                        )
                        .storageKey
            }

    val playlistsSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.PLAYLISTS_SORT_OPTION],
                                SortOption.PLAYLISTS,
                                SortOption.PlaylistNameAZ
                        )
                        .storageKey
            }

    val foldersSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.FOLDERS_SORT_OPTION],
                                SortOption.FOLDERS,
                                SortOption.FolderNameAZ
                        )
                        .storageKey
            }

    val likedSongsSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.LIKED_SONGS_SORT_OPTION],
                                SortOption.LIKED,
                                SortOption.LikedSongDateLiked
                        )
                        .storageKey
            }

    suspend fun setSongsSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SONGS_SORT_OPTION] = optionKey
            preferences[PreferencesKeys.SONGS_SORT_OPTION_MIGRATED] = true
        }
    }

    suspend fun setAlbumsSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALBUMS_SORT_OPTION] = optionKey
        }
    }

    suspend fun setArtistsSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ARTISTS_SORT_OPTION] = optionKey
        }
    }

    suspend fun setPlaylistsSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYLISTS_SORT_OPTION] = optionKey
        }
    }

    suspend fun setFoldersSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FOLDERS_SORT_OPTION] = optionKey
        }
    }

    suspend fun setLikedSongsSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIKED_SONGS_SORT_OPTION] = optionKey
        }
    }

    suspend fun ensureLibrarySortDefaults() {
        dataStore.edit { preferences ->
            val songsMigrated = preferences[PreferencesKeys.SONGS_SORT_OPTION_MIGRATED] ?: false
            val rawSongSort = preferences[PreferencesKeys.SONGS_SORT_OPTION]
            val resolvedSongSort =
                    SortOption.fromStorageKey(rawSongSort, SortOption.SONGS, SortOption.SongTitleAZ)
            val shouldForceSongDefault =
                    !songsMigrated &&
                            (rawSongSort.isNullOrBlank() ||
                                    rawSongSort == SortOption.SongTitleZA.storageKey ||
                                    rawSongSort == SortOption.SongTitleZA.displayName)

            preferences[PreferencesKeys.SONGS_SORT_OPTION] =
                    if (shouldForceSongDefault) {
                        SortOption.SongTitleAZ.storageKey
                    } else {
                        resolvedSongSort.storageKey
                    }
            if (!songsMigrated) {
                preferences[PreferencesKeys.SONGS_SORT_OPTION_MIGRATED] = true
            }

            migrateSortPreference(
                    preferences,
                    PreferencesKeys.SONGS_SORT_OPTION,
                    SortOption.SONGS,
                    SortOption.SongTitleAZ
            )
            migrateSortPreference(
                    preferences,
                    PreferencesKeys.ALBUMS_SORT_OPTION,
                    SortOption.ALBUMS,
                    SortOption.AlbumTitleAZ
            )
            migrateSortPreference(
                    preferences,
                    PreferencesKeys.ARTISTS_SORT_OPTION,
                    SortOption.ARTISTS,
                    SortOption.ArtistNameAZ
            )
            migrateSortPreference(
                    preferences,
                    PreferencesKeys.PLAYLISTS_SORT_OPTION,
                    SortOption.PLAYLISTS,
                    SortOption.PlaylistNameAZ
            )
            migrateSortPreference(
                    preferences,
                    PreferencesKeys.FOLDERS_SORT_OPTION,
                    SortOption.FOLDERS,
                    SortOption.FolderNameAZ
            )
            migrateSortPreference(
                    preferences,
                    PreferencesKeys.LIKED_SONGS_SORT_OPTION,
                    SortOption.LIKED,
                    SortOption.LikedSongDateLiked
            )
        }
    }

    private fun migrateSortPreference(
            preferences: MutablePreferences,
            key: Preferences.Key<String>,
            allowed: Collection<SortOption>,
            fallback: SortOption
    ) {
        val resolved = SortOption.fromStorageKey(preferences[key], allowed, fallback)
        if (preferences[key] != resolved.storageKey) {
            preferences[key] = resolved.storageKey
        }
    }

    val lastLibraryTabIndexFlow: Flow<Int> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAST_LIBRARY_TAB_INDEX] ?: 0
            }

    suspend fun saveLastLibraryTabIndex(tabIndex: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_LIBRARY_TAB_INDEX] = tabIndex
        }
    }

    /**
     * A persisted `"ONLINE"` is a leftover from the removed server-music architecture, where it
     * selected cloud-only songs. That filter can never match a local library, so restoring it
     * would show an upgrading user an empty Songs tab. It now restores as [StorageFilter.ALL]
     * instead — the write path no longer produces `"ONLINE"`, so this only ever applies to
     * preferences saved before the cloud removal.
     */
    val lastStorageFilterFlow: Flow<StorageFilter> =
        dataStore.data.map { preferences ->
            when (preferences[PreferencesKeys.LAST_STORAGE_FILTER]) {
                "OFFLINE" -> StorageFilter.OFFLINE
                else      -> StorageFilter.ALL
            }
        }

    suspend fun saveLastStorageFilter(filter: StorageFilter) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_STORAGE_FILTER] = filter.name
        }
    }

    val mockGenresEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.MOCK_GENRES_ENABLED] ?: false
            }

    suspend fun setMockGenresEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.MOCK_GENRES_ENABLED] = enabled }
    }

    companion object {
        /**
         * The stored screen-timeout choice, for callers that have no injected repository (the boot
         * receiver, which re-applies it if the system value drifted: B11-fix, FIX-2).
         */
        suspend fun readDapScreenTimeoutMode(context: Context): Long =
            context.dataStore.data.first()[PreferencesKeys.DAP_SCREEN_TIMEOUT_MODE]
                ?: DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM

        /** Default character delimiters for splitting multi-artist tags */
        val DEFAULT_ARTIST_DELIMITERS = listOf("/", ";", ",", "+", "&")
        /** Default word-based delimiters (matched case-insensitively with whitespace boundaries) */
        val DEFAULT_ARTIST_WORD_DELIMITERS = listOf("featuring", "feat.", "feat", "ft.", "ft", "vs.", "vs", "versus", "with", "prod.", "prod")
        const val DEFAULT_ALBUM_ART_CACHE_LIMIT_MB = 200
    }

    /**
     * The appearance keys whose controls were retired, frozen at the value the product now uses.
     *
     * Each of these still reaches a live render path — a corner radius, a bar height, a carousel
     * layout — but the Settings rows that wrote them were removed, so nothing can change them back.
     * A value left in DataStore or carried in by a restored backup would therefore decide part of
     * the final appearance with no way for the user to disagree: the worst of both, a setting and no
     * setting. They are read as constants now, so the stored value can no longer deform anything,
     * and the keys, setters and backup participation are kept so old backups still round-trip.
     *
     * `collage_pattern` and `collage_auto_rotate` are not here: the Home collage they configured was
     * replaced, nothing renders them, and an inert value is already harmless.
     */

    /** The nav bar is flat and never rounds; its radius is no longer a preference. */
    val navBarCornerRadiusFlow: Flow<Int> = flowOf(DEFAULT_NAV_BAR_CORNER_RADIUS)

    /** The player shelf and undo bar keep the full-width inset. */
    val navBarStyleFlow: Flow<String> = flowOf(NavBarStyle.DEFAULT)

    /** The bar keeps its standard height, and screens keep the standard bottom clearance. */
    val navBarCompactModeFlow: Flow<Boolean> = flowOf(false)

    /** The carousel keeps its no-peek layout. */
    val carouselStyleFlow: Flow<String> = flowOf(CarouselStyle.NO_PEEK)

    /** The player shelf draws as a rounded rectangle, which is what it draws with a song loaded. */
    val useSmoothCornersFlow: Flow<Boolean> = flowOf(false)


    suspend fun setNavBarCornerRadius(radius: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.NAV_BAR_CORNER_RADIUS] = sanitizeNavBarCornerRadius(radius)
        }
    }


    suspend fun setNavBarStyle(style: String) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.NAV_BAR_STYLE] = style }
    }


    suspend fun setNavBarCompactMode(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.NAV_BAR_COMPACT_MODE] = enabled
        }
    }

    val libraryNavigationModeFlow: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LIBRARY_NAVIGATION_MODE]
                        ?: LibraryNavigationMode.TAB_ROW
            }

    suspend fun setLibraryNavigationMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIBRARY_NAVIGATION_MODE] = mode
        }
    }


    suspend fun setCarouselStyle(style: String) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.CAROUSEL_STYLE] = style }
    }

    val launchTabFlow: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAUNCH_TAB] ?: LaunchTab.HOME
            }

    suspend fun setLaunchTab(tab: String) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.LAUNCH_TAB] = tab }
    }

    suspend fun setKeepPlayingInBackground(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEEP_PLAYING_IN_BACKGROUND] = enabled
        }
    }

    suspend fun setResumeOnHeadsetReconnect(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.RESUME_ON_HEADSET_RECONNECT] = enabled
        }
    }












    suspend fun clearDeprecatedPlayerSheetPreference() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.USE_PLAYER_SHEET_V2)
        }
    }

    val useAnimatedLyricsFlow: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.USE_ANIMATED_LYRICS] ?: false
        }

    suspend fun setUseAnimatedLyrics(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_ANIMATED_LYRICS] = enabled
        }
    }

    val animatedLyricsBlurEnabledFlow: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.ANIMATED_LYRICS_BLUR_ENABLED] ?: true
        }

    suspend fun setAnimatedLyricsBlurEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ANIMATED_LYRICS_BLUR_ENABLED] = enabled
        }
    }

    /**
     * Blur radius multiplier for the depth-of-field effect on inactive lyric lines.
     *
     * The range spans [ANIMATED_LYRICS_BLUR_STRENGTH_DEFAULT] rather than stopping short of it. The
     * default was 2.5 while the only control for it topped out at 2.0, so the value the app ships
     * with was one the slider could not represent: opening the screen showed a thumb pinned at the
     * end of its track next to a stored value it could not produce. The default is the intended
     * behaviour and existing installs already hold it, so the range was widened to include it
     * rather than the default lowered to fit the range.
     */
    val animatedLyricsBlurStrengthFlow: Flow<Float> = dataStore.data
        .map { preferences ->
            (preferences[PreferencesKeys.ANIMATED_LYRICS_BLUR_STRENGTH]
                ?: ANIMATED_LYRICS_BLUR_STRENGTH_DEFAULT)
                .coerceIn(ANIMATED_LYRICS_BLUR_STRENGTH_MIN, ANIMATED_LYRICS_BLUR_STRENGTH_MAX)
        }

    suspend fun setAnimatedLyricsBlurStrength(strength: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ANIMATED_LYRICS_BLUR_STRENGTH] =
                strength.coerceIn(ANIMATED_LYRICS_BLUR_STRENGTH_MIN, ANIMATED_LYRICS_BLUR_STRENGTH_MAX)
        }
    }

    val libraryTabsOrderFlow: Flow<String?> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.LIBRARY_TABS_ORDER]
        }

    suspend fun saveLibraryTabsOrder(order: String) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.LIBRARY_TABS_ORDER] = order }
    }

    suspend fun resetLibraryTabsOrder() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.LIBRARY_TABS_ORDER)
            preferences.remove(PreferencesKeys.LIBRARY_TABS_HIDDEN)
        }
    }

    val libraryHiddenTabsFlow: Flow<List<String>> = dataStore.data
        .map { preferences -> decodeHiddenTabs(preferences[PreferencesKeys.LIBRARY_TABS_HIDDEN]) }

    suspend fun saveLibraryHiddenTabs(hidden: List<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIBRARY_TABS_HIDDEN] = json.encodeToString(hidden)
        }
    }

    /**
     * A malformed or missing hidden list reads as "nothing hidden" rather than as an error: the
     * failure mode of a corrupt preference must never be an empty Library.
     */
    private fun decodeHiddenTabs(raw: String?): List<String> {
        if (raw == null) return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun migrateTabOrder() {
        dataStore.edit { preferences ->
            val orderJson = preferences[PreferencesKeys.LIBRARY_TABS_ORDER]
            if (orderJson != null) {
                try {
                    val order = json.decodeFromString<MutableList<String>>(orderJson)
                    if (!order.contains("FOLDERS")) {
                        val likedIndex = order.indexOf("LIKED")
                        if (likedIndex != -1) {
                            order.add(likedIndex + 1, "FOLDERS")
                        } else {
                            order.add("FOLDERS")
                        }
                        preferences[PreferencesKeys.LIBRARY_TABS_ORDER] = json.encodeToString(order)
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    val isFolderFilterActiveFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.IS_FOLDER_FILTER_ACTIVE] ?: false
            }

    suspend fun setFolderFilterActive(isActive: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_FOLDER_FILTER_ACTIVE] = isActive
        }
    }

    val isFoldersPlaylistViewFlow: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.IS_FOLDERS_PLAYLIST_VIEW] ?: false
        }

    val foldersSourceFlow: Flow<FolderSource> = dataStore.data
        .map { preferences ->
            FolderSource.fromStorageKey(preferences[PreferencesKeys.FOLDERS_SOURCE])
        }

    val folderBackGestureNavigationFlow: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.FOLDER_BACK_GESTURE_NAVIGATION] ?: true
        }

    /**
     * How the app performs Back: visible affordances, the app's edge gesture, or both.
     *
     * This is the only reader and the only writer. The mode is resolved once by the shell and
     * published to the composition, so a screen cannot end up reading a different answer from the
     * one the gesture is using.
     *
     * Nothing is written on upgrade — an install with no stored value resolves to
     * [BackNavigationMode.DEFAULT], which is why the migration is a default and not a write.
     */
    val backNavigationModeFlow: Flow<BackNavigationMode> = dataStore.data
        .map { preferences ->
            BackNavigationMode.fromId(preferences[PreferencesKeys.BACK_NAVIGATION_MODE])
        }
        .distinctUntilChanged()

    suspend fun setBackNavigationMode(mode: BackNavigationMode) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.BACK_NAVIGATION_MODE] = mode.id
        }
    }


    suspend fun setUseSmoothCorners(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_SMOOTH_CORNERS] = enabled
        }
    }

    suspend fun setFoldersPlaylistView(isPlaylistView: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_FOLDERS_PLAYLIST_VIEW] = isPlaylistView
        }
    }

    suspend fun setFoldersSource(source: FolderSource) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FOLDERS_SOURCE] = source.storageKey
        }
    }

    suspend fun setFolderBackGestureNavigation(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FOLDER_BACK_GESTURE_NAVIGATION] = enabled
        }
    }

    /**
     * Album art quality for player view.
     * Controls the maximum resolution for album artwork displayed in the full player.
     * Thumbnails in lists always use low resolution (256px) for optimal performance.
     */
    val albumArtQualityFlow: Flow<AlbumArtQuality> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.ALBUM_ART_QUALITY]
                ?.let {
                    try { AlbumArtQuality.valueOf(it) }
                    catch (e: Exception) { AlbumArtQuality.MEDIUM }
                }
                ?: AlbumArtQuality.MEDIUM
        }

    suspend fun setAlbumArtQuality(quality: AlbumArtQuality) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALBUM_ART_QUALITY] = quality.name
        }
    }

    val albumArtCacheLimitMbFlow: Flow<Int> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.ALBUM_ART_CACHE_LIMIT_MB] ?: DEFAULT_ALBUM_ART_CACHE_LIMIT_MB
        }

    suspend fun setAlbumArtCacheLimitMb(limitMb: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALBUM_ART_CACHE_LIMIT_MB] = limitMb.coerceIn(50, 1500)
        }
    }

    /**
     * Whether tapping the background area of the player sheet closes it.
     * Default is false to avoid accidental dismissals while interacting with the full player.
     */
    val tapBackgroundClosesPlayerFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.TAP_BACKGROUND_CLOSES_PLAYER] ?: false
        }

    suspend fun setTapBackgroundClosesPlayer(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TAP_BACKGROUND_CLOSES_PLAYER] = enabled
        }
    }

    val hapticsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.HAPTICS_ENABLED] ?: true
        }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAPTICS_ENABLED] = enabled
        }
    }

    val advancedPerformanceDiagnosticsSettingsFlow: Flow<AdvancedPerformanceDiagnosticsSettings> =
        dataStore.data.map { preferences ->
            AdvancedPerformanceDiagnosticsSettings(
                enabled = preferences[PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_ENABLED] ?: false,
                sessionStartedEpochMs =
                    preferences[PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_STARTED_AT],
                expiresAtEpochMs =
                    preferences[PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_EXPIRES_AT]
            )
        }.distinctUntilChanged()

    suspend fun setAdvancedPerformanceDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            if (enabled) {
                val now = System.currentTimeMillis()
                preferences[PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_ENABLED] = true
                preferences[PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_STARTED_AT] = now
                preferences[PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_EXPIRES_AT] =
                    now + AdvancedPerformanceDiagnostics.DEFAULT_SESSION_DURATION_MS
            } else {
                preferences[PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_ENABLED] = false
                preferences.remove(PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_STARTED_AT)
                preferences.remove(PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_EXPIRES_AT)
            }
        }
    }

    suspend fun disableExpiredAdvancedPerformanceDiagnostics(nowEpochMs: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            val enabled = preferences[PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_ENABLED] ?: false
            val expiresAt = preferences[PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_EXPIRES_AT]
            if (enabled && (expiresAt == null || nowEpochMs >= expiresAt)) {
                preferences[PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_ENABLED] = false
                preferences.remove(PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_STARTED_AT)
                preferences.remove(PreferencesKeys.ADVANCED_PERFORMANCE_DIAGNOSTICS_EXPIRES_AT)
            }
        }
    }

    suspend fun clearPreferencesByKeys(keyNames: Set<String>) {
        if (keyNames.isEmpty()) return
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { key -> key.name in keyNames && key.name !in backupExcludedKeyNames }
                .forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    preferences.remove(key as Preferences.Key<Any>)
                }
        }
    }

    suspend fun clearPreferencesExceptKeys(excludedKeyNames: Set<String>) {
        val protectedKeyNames = excludedKeyNames + backupExcludedKeyNames
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filterNot { key -> key.name in protectedKeyNames }
                .forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    preferences.remove(key as Preferences.Key<Any>)
                }
        }
    }

    suspend fun exportPreferencesForBackup(): List<PreferenceBackupEntry> {
        val snapshot = dataStore.data.first().asMap()
        return snapshot.mapNotNull { (key, value) ->
            val keyName = key.name
            if (keyName in backupExcludedKeyNames) {
                return@mapNotNull null
            }
            when (value) {
                is String -> PreferenceBackupEntry(
                    key = keyName,
                    type = "string",
                    stringValue = value
                )
                is Int -> PreferenceBackupEntry(
                    key = keyName,
                    type = "int",
                    intValue = value
                )
                is Long -> PreferenceBackupEntry(
                    key = keyName,
                    type = "long",
                    longValue = value
                )
                is Boolean -> PreferenceBackupEntry(
                    key = keyName,
                    type = "boolean",
                    booleanValue = value
                )
                is Float -> PreferenceBackupEntry(
                    key = keyName,
                    type = "float",
                    floatValue = value
                )
                is Double -> PreferenceBackupEntry(
                    key = keyName,
                    type = "double",
                    doubleValue = value
                )
                is Set<*> -> {
                    val stringSet = value.filterIsInstance<String>().toSet()
                    PreferenceBackupEntry(
                        key = keyName,
                        type = "string_set",
                        stringSetValue = stringSet
                    )
                }
                else -> null
            }
        }
    }

    suspend fun importPreferencesFromBackup(
        entries: List<PreferenceBackupEntry>,
        clearExisting: Boolean = true
    ) {
        dataStore.edit { preferences ->
            if (clearExisting) {
                preferences.asMap().keys
                    .filterNot { key -> key.name in backupExcludedKeyNames }
                    .forEach { key ->
                        @Suppress("UNCHECKED_CAST")
                        preferences.remove(key as Preferences.Key<Any>)
                    }
            }

            entries.forEach { entry ->
                if (entry.key in backupExcludedKeyNames) {
                    return@forEach
                }
                when (entry.type) {
                    "string" -> {
                        val value = entry.stringValue ?: return@forEach
                        preferences[stringPreferencesKey(entry.key)] = value
                    }
                    "int" -> {
                        val value = entry.intValue
                            ?: entry.doubleValue?.toInt()
                            ?: entry.longValue?.toInt()
                            ?: return@forEach
                        preferences[intPreferencesKey(entry.key)] = value
                    }
                    "long" -> {
                        val value = entry.longValue
                            ?: entry.doubleValue?.toLong()
                            ?: entry.intValue?.toLong()
                            ?: return@forEach
                        preferences[longPreferencesKey(entry.key)] = value
                    }
                    "boolean" -> {
                        val value = entry.booleanValue ?: return@forEach
                        preferences[booleanPreferencesKey(entry.key)] = value
                    }
                    "float" -> {
                        val value = entry.floatValue
                            ?: entry.doubleValue?.toFloat()
                            ?: return@forEach
                        preferences[androidx.datastore.preferences.core.floatPreferencesKey(entry.key)] = value
                    }
                    "double" -> {
                        val value = entry.doubleValue
                            ?: entry.floatValue?.toDouble()
                            ?: return@forEach
                        preferences[androidx.datastore.preferences.core.doublePreferencesKey(entry.key)] = value
                    }
                    "string_set" -> {
                        val value = entry.stringSetValue ?: return@forEach
                        preferences[stringSetPreferencesKey(entry.key)] = value
                    }
                }
            }
        }
    }

    val collagePatternFlow: Flow<CollagePattern> =
        dataStore.data.map { preferences ->
            CollagePattern.fromStorageKey(preferences[PreferencesKeys.COLLAGE_PATTERN])
        }

    suspend fun setCollagePattern(pattern: CollagePattern) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.COLLAGE_PATTERN] = pattern.storageKey
        }
    }

    val collageAutoRotateFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.COLLAGE_AUTO_ROTATE] ?: false
        }

    suspend fun setCollageAutoRotate(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.COLLAGE_AUTO_ROTATE] = enabled
        }
    }

    val lastPlaylistIdFlow: Flow<String?> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.LAST_PLAYLIST_ID]?.takeIf { it.isNotBlank() }
        }

    val lastPlaylistNameFlow: Flow<String?> =
        dataStore.data.map { it[PreferencesKeys.LAST_PLAYLIST_NAME] }

    suspend fun setLastPlaylist(playlistId: String, playlistName: String) {
        dataStore.edit {
            it[PreferencesKeys.LAST_PLAYLIST_ID] = playlistId
            it[PreferencesKeys.LAST_PLAYLIST_NAME] = playlistName
        }
    }

    suspend fun clearLastPlaylist() {
        dataStore.edit {
            it.remove(PreferencesKeys.LAST_PLAYLIST_ID)
            it.remove(PreferencesKeys.LAST_PLAYLIST_NAME)
        }
    }
}
