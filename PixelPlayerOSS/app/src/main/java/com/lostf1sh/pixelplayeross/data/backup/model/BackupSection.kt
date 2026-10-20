package com.lostf1sh.pixelplayeross.data.backup.model

import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.R

/**
 * One restorable slice of the user's data.
 *
 * [labelRes] and [descriptionRes] are resource ids rather than literals: these names are shown on
 * the section cards, in the progress dialog and inside progress sentences, so they are product copy
 * and have to follow the app's language. [key] is the opposite — it is the wire identifier written
 * into the backup manifest and matched on restore, so it must never be localized or renamed, or
 * existing backups stop resolving.
 */
enum class BackupSection(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val iconRes: Int,
    val sinceVersion: Int = 1
) {
    PLAYLISTS(
        key = "playlists",
        labelRes = R.string.backup_section_playlists,
        descriptionRes = R.string.backup_section_playlists_desc,
        iconRes = R.drawable.rounded_playlist_play_24
    ),
    GLOBAL_SETTINGS(
        key = "global_settings",
        labelRes = R.string.backup_section_global_settings,
        descriptionRes = R.string.backup_section_global_settings_desc,
        iconRes = R.drawable.rounded_settings_24
    ),
    FAVORITES(
        key = "favorites",
        labelRes = R.string.backup_section_favorites,
        descriptionRes = R.string.backup_section_favorites_desc,
        iconRes = R.drawable.rounded_favorite_24
    ),
    SMART_COLLECTIONS(
        key = "smart_collections",
        labelRes = R.string.backup_section_smart_collections,
        descriptionRes = R.string.backup_section_smart_collections_desc,
        iconRes = R.drawable.rounded_settings_24,
        // The backup *format* version is not bumped for this: an added section is backward
        // compatible in both directions — an older app reading a newer backup ignores a section it
        // does not know, and a newer app reading an older backup simply finds this one absent.
        sinceVersion = 4
    ),
    LYRICS(
        key = "lyrics",
        labelRes = R.string.backup_section_lyrics,
        descriptionRes = R.string.backup_section_lyrics_desc,
        iconRes = R.drawable.rounded_lyrics_24
    ),
    SEARCH_HISTORY(
        key = "search_history",
        labelRes = R.string.backup_section_search_history,
        descriptionRes = R.string.backup_section_search_history_desc,
        iconRes = R.drawable.rounded_search_24
    ),
    TRANSITIONS(
        key = "transitions",
        labelRes = R.string.backup_section_transitions,
        descriptionRes = R.string.backup_section_transitions_desc,
        iconRes = R.drawable.rounded_align_justify_space_even_24
    ),
    ENGAGEMENT_STATS(
        key = "engagement_stats",
        labelRes = R.string.backup_section_stats,
        descriptionRes = R.string.backup_section_stats_desc,
        iconRes = R.drawable.rounded_monitoring_24
    ),
    PLAYBACK_HISTORY(
        key = "playback_history",
        labelRes = R.string.backup_section_playback_history,
        descriptionRes = R.string.backup_section_playback_history_desc,
        iconRes = R.drawable.rounded_schedule_24
    ),
    QUICK_FILL(
        key = "quick_fill",
        labelRes = R.string.backup_section_quick_fill,
        descriptionRes = R.string.backup_section_quick_fill_desc,
        iconRes = R.drawable.rounded_instant_mix_24,
        sinceVersion = 3
    ),
    ARTIST_IMAGES(
        key = "artist_images",
        labelRes = R.string.backup_section_artist_images,
        descriptionRes = R.string.backup_section_artist_images_desc,
        iconRes = R.drawable.rounded_person_24,
        sinceVersion = 3
    ),
    EQUALIZER(
        key = "equalizer",
        labelRes = R.string.backup_section_equalizer,
        descriptionRes = R.string.backup_section_equalizer_desc,
        iconRes = R.drawable.rounded_surround_sound_24,
        sinceVersion = 3
    );

    companion object {
        val defaultSelection: Set<BackupSection> = entries.toSet()

        fun fromKey(key: String): BackupSection? = entries.find { it.key == key }
    }
}
