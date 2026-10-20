package com.lostf1sh.pixelplayeross.data.backup.validation

import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessage
import com.google.gson.JsonParser
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.BackupValidationResult
import com.lostf1sh.pixelplayeross.data.backup.model.Severity
import com.lostf1sh.pixelplayeross.data.backup.model.ValidationError
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModuleSchemaValidator @Inject constructor(
    private val contentSanitizer: ContentSanitizer
) {
    private data class NumericFieldResult(
        val present: Boolean,
        val value: Long?
    )

    companion object {
        const val MAX_STRING_LENGTH = 50_000
        const val MAX_ENTRIES_PER_MODULE = 100_000
        private val VALID_PREF_TYPES = setOf("string", "int", "long", "boolean", "float", "double", "string_set")
    }

    fun validate(section: BackupSection, payload: String): BackupValidationResult {
        val errors = mutableListOf<ValidationError>()

        val jsonElement = try {
            JsonParser.parseString(payload)
        } catch (e: Exception) {
            errors.add(ValidationError(
                    "INVALID_JSON",
                    BackupMessage.of(R.string.backup_validation_invalid_json, section.key),
                    module = section.key,
                ))
            return BackupValidationResult.Invalid(errors)
        }

        if (section == BackupSection.PLAYLISTS) {
            validatePlaylistsModule(jsonElement, errors)
            return if (errors.any { it.severity == Severity.ERROR }) {
                BackupValidationResult.Invalid(errors)
            } else if (errors.isNotEmpty()) {
                BackupValidationResult.Invalid(errors)
            } else {
                BackupValidationResult.Valid
            }
        }

        if (section != BackupSection.QUICK_FILL && section != BackupSection.EQUALIZER) {
            if (!jsonElement.isJsonArray) {
                errors.add(ValidationError(
                    "NOT_ARRAY",
                    BackupMessage.of(R.string.backup_validation_not_array, section.key),
                    module = section.key,
                ))
                return BackupValidationResult.Invalid(errors)
            }
            val array = jsonElement.asJsonArray
            if (array.size() > MAX_ENTRIES_PER_MODULE) {
                errors.add(ValidationError(
                    "TOO_MANY_ENTRIES",
                    BackupMessage.of(
                        R.string.backup_validation_too_many_entries,
                        section.key,
                        array.size(),
                        MAX_ENTRIES_PER_MODULE,
                    ),
                    module = section.key,
                ))
                return BackupValidationResult.Invalid(errors)
            }
        }

        when (section) {
            BackupSection.PLAYLISTS -> {
            }
            // A collection whose rules this build cannot parse is dropped by the restore handler
            // rather than rejected here: the payload is well-formed, the *content* is from another
            // version, and refusing the whole backup over it would lose the sections that are fine.
            BackupSection.SMART_COLLECTIONS -> {
            }
            BackupSection.FAVORITES -> validateFavorites(jsonElement.asJsonArray, errors)
            BackupSection.LYRICS -> validateLyrics(jsonElement.asJsonArray, errors)
            BackupSection.SEARCH_HISTORY -> validateSearchHistory(jsonElement.asJsonArray, errors)
            BackupSection.ENGAGEMENT_STATS -> validateEngagementStats(jsonElement.asJsonArray, errors)
            BackupSection.PLAYBACK_HISTORY -> validatePlaybackHistory(jsonElement.asJsonArray, errors)
            BackupSection.ARTIST_IMAGES -> validateArtistImages(jsonElement.asJsonArray, errors)
            BackupSection.TRANSITIONS -> validateTransitions(jsonElement.asJsonArray, errors)
            BackupSection.GLOBAL_SETTINGS,
            BackupSection.QUICK_FILL,
            BackupSection.EQUALIZER -> {
                validatePreferenceEntries(jsonElement, section.key, errors)
            }
        }

        return if (errors.any { it.severity == Severity.ERROR }) {
            BackupValidationResult.Invalid(errors)
        } else if (errors.isNotEmpty()) {
            BackupValidationResult.Invalid(errors)
        } else {
            BackupValidationResult.Valid
        }
    }

    private fun validatePlaylistsModule(
        jsonElement: com.google.gson.JsonElement,
        errors: MutableList<ValidationError>
    ) {
        if (jsonElement.isJsonArray) {
            validatePreferenceEntries(jsonElement, BackupSection.PLAYLISTS.key, errors)
            return
        }

        if (!jsonElement.isJsonObject) {
            errors.add(
                ValidationError(
                    "INVALID_PLAYLISTS_PAYLOAD",
                    BackupMessage.of(
                        R.string.backup_validation_playlists_payload,
                        BackupSection.PLAYLISTS.key,
                    ),
                    module = BackupSection.PLAYLISTS.key
                )
            )
            return
        }

        val obj = jsonElement.asJsonObject
        val playlistsArray = obj.getAsJsonArray("playlists")
        if (playlistsArray != null && playlistsArray.size() > MAX_ENTRIES_PER_MODULE) {
            errors.add(
                ValidationError(
                    "TOO_MANY_ENTRIES",
                    BackupMessage.of(
                        R.string.backup_validation_playlists_too_many,
                        BackupSection.PLAYLISTS.key,
                        playlistsArray.size(),
                        MAX_ENTRIES_PER_MODULE,
                    ),
                    module = BackupSection.PLAYLISTS.key
                )
            )
            return
        }

        playlistsArray?.forEachIndexed { index, element ->
            if (!element.isJsonObject) {
                errors.add(
                    ValidationError(
                        "INVALID_PLAYLIST_ENTRY",
                        BackupMessage.of(R.string.backup_validation_playlist_entry, index),
                        module = BackupSection.PLAYLISTS.key,
                        severity = Severity.WARNING
                    )
                )
                return@forEachIndexed
            }
            val playlistObj = element.asJsonObject
            val id = playlistObj.get("id")?.asString
            val name = playlistObj.get("name")?.asString
            if (id.isNullOrBlank()) {
                errors.add(
                    ValidationError(
                        "MISSING_PLAYLIST_ID",
                        BackupMessage.of(R.string.backup_validation_playlist_missing_id, index),
                        module = BackupSection.PLAYLISTS.key,
                        severity = Severity.WARNING
                    )
                )
            }
            if (name.isNullOrBlank()) {
                errors.add(
                    ValidationError(
                        "MISSING_PLAYLIST_NAME",
                        BackupMessage.of(R.string.backup_validation_playlist_missing_name, index),
                        module = BackupSection.PLAYLISTS.key,
                        severity = Severity.WARNING
                    )
                )
            }
        }

        val sortOption = obj.get("playlistsSortOption")?.asString
        if (sortOption != null && sortOption.length > 200) {
            errors.add(
                ValidationError(
                    "INVALID_SORT_OPTION",
                    BackupMessage.of(
                        R.string.backup_validation_sort_option,
                        "playlistsSortOption",
                    ),
                    module = BackupSection.PLAYLISTS.key,
                    severity = Severity.WARNING
                )
            )
        }
    }

    private fun validateFavorites(array: com.google.gson.JsonArray, errors: MutableList<ValidationError>) {
        array.forEachIndexed { i, element ->
            if (!element.isJsonObject) return@forEachIndexed
            val obj = element.asJsonObject
            val songId = readNumericField(obj, "songId", "song_id")
            if (!songId.present || songId.value == null || songId.value <= 0L) {
                errors.add(
                    ValidationError(
                        "INVALID_SONG_ID",
                        BackupMessage.of(R.string.backup_validation_favorite_song_id, i),
                        module = "favorites",
                        severity = Severity.WARNING
                    )
                )
            }
        }
    }

    private fun validateLyrics(array: com.google.gson.JsonArray, errors: MutableList<ValidationError>) {
        array.forEachIndexed { i, element ->
            if (!element.isJsonObject) return@forEachIndexed
            val obj = element.asJsonObject
            val content = obj.get("content")?.asString ?: ""
            if (content.length > MAX_STRING_LENGTH) {
                errors.add(ValidationError(
                        "LYRICS_TOO_LONG",
                        BackupMessage.of(R.string.backup_validation_lyrics_too_long, i),
                        module = "lyrics",
                        severity = Severity.WARNING,
                    ))
            }
        }
    }

    private fun validateSearchHistory(array: com.google.gson.JsonArray, errors: MutableList<ValidationError>) {
        array.forEachIndexed { i, element ->
            if (!element.isJsonObject) return@forEachIndexed
            val obj = element.asJsonObject
            val query = obj.get("query")?.asString ?: ""
            if (query.length > 500) {
                errors.add(ValidationError(
                        "QUERY_TOO_LONG",
                        BackupMessage.of(R.string.backup_validation_query_too_long, i),
                        module = "search_history",
                        severity = Severity.WARNING,
                    ))
            }
        }
    }

    private fun validateEngagementStats(array: com.google.gson.JsonArray, errors: MutableList<ValidationError>) {
        val seenSongIds = mutableSetOf<String>()
        array.forEachIndexed { i, element ->
            if (!element.isJsonObject) {
                errors.add(
                    ValidationError(
                        "INVALID_ENGAGEMENT_ENTRY",
                        BackupMessage.of(R.string.backup_validation_stats_entry, i),
                        module = "engagement_stats",
                        severity = Severity.WARNING
                    )
                )
                return@forEachIndexed
            }
            val obj = element.asJsonObject
            val songId = readStringField(obj, "songId", "song_id")?.trim()
            if (songId.isNullOrEmpty()) {
                errors.add(
                    ValidationError(
                        "MISSING_SONG_ID",
                        BackupMessage.of(R.string.backup_validation_stats_missing_song_id, i),
                        module = "engagement_stats",
                        severity = Severity.WARNING
                    )
                )
            } else if (!seenSongIds.add(songId)) {
                errors.add(
                    ValidationError(
                        "DUPLICATE_SONG_ID",
                        BackupMessage.of(
                            R.string.backup_validation_stats_duplicate_song_id,
                            i,
                            songId,
                        ),
                        module = "engagement_stats",
                        severity = Severity.WARNING
                    )
                )
            }

            val playCount = readNumericField(obj, "play_count", "playCount", "score", "plays")
            if (playCount.present && playCount.value == null) {
                errors.add(
                    ValidationError(
                        "INVALID_PLAY_COUNT",
                        BackupMessage.of(R.string.backup_validation_stats_play_count, i),
                        module = "engagement_stats",
                        severity = Severity.WARNING
                    )
                )
            } else if ((playCount.value ?: 0L) < 0L) {
                errors.add(
                    ValidationError(
                        "NEGATIVE_PLAY_COUNT",
                        BackupMessage.of(R.string.backup_validation_stats_negative_play_count, i),
                        module = "engagement_stats",
                        severity = Severity.WARNING
                    )
                )
            }

            val totalDuration = readNumericField(
                obj,
                "total_play_duration_ms",
                "totalPlayDurationMs",
                "totalDuration",
                "total_duration",
                "durationMs",
                "duration_ms"
            )
            if (totalDuration.present && totalDuration.value == null) {
                errors.add(
                    ValidationError(
                        "INVALID_TOTAL_DURATION",
                        BackupMessage.of(R.string.backup_validation_stats_total_duration, i),
                        module = "engagement_stats",
                        severity = Severity.WARNING
                    )
                )
            } else if ((totalDuration.value ?: 0L) < 0L) {
                errors.add(
                    ValidationError(
                        "NEGATIVE_TOTAL_DURATION",
                        BackupMessage.of(R.string.backup_validation_stats_negative_duration, i),
                        module = "engagement_stats",
                        severity = Severity.WARNING
                    )
                )
            }

            val lastPlayed = readNumericField(
                obj,
                "last_played_timestamp",
                "lastPlayedTimestamp",
                "lastPlayedAt",
                "last_played_at",
                "timestamp"
            )
            if (lastPlayed.present && lastPlayed.value == null) {
                errors.add(
                    ValidationError(
                        "INVALID_LAST_PLAYED_TIMESTAMP",
                        BackupMessage.of(R.string.backup_validation_stats_last_played, i),
                        module = "engagement_stats",
                        severity = Severity.WARNING
                    )
                )
            } else if ((lastPlayed.value ?: 0L) < 0L) {
                errors.add(
                    ValidationError(
                        "NEGATIVE_LAST_PLAYED_TIMESTAMP",
                        BackupMessage.of(R.string.backup_validation_stats_negative_last_played, i),
                        module = "engagement_stats",
                        severity = Severity.WARNING
                    )
                )
            }
        }
    }

    private fun validatePlaybackHistory(array: com.google.gson.JsonArray, errors: MutableList<ValidationError>) {
        array.forEachIndexed { i, element ->
            if (!element.isJsonObject) return@forEachIndexed
            val obj = element.asJsonObject
            val durationMs = obj.get("durationMs")?.asLong ?: 0
            if (durationMs < 0) {
                errors.add(ValidationError(
                        "NEGATIVE_DURATION",
                        BackupMessage.of(R.string.backup_validation_history_negative_duration, i),
                        module = "playback_history",
                        severity = Severity.WARNING,
                    ))
            }
        }
    }

    private fun validateArtistImages(array: com.google.gson.JsonArray, errors: MutableList<ValidationError>) {
        array.forEachIndexed { i, element ->
            if (!element.isJsonObject) return@forEachIndexed
            val obj = element.asJsonObject
            val imageUrl = obj.get("imageUrl")?.asString ?: ""
            if (imageUrl.isNotEmpty() && !imageUrl.startsWith("https://")) {
                errors.add(ValidationError(
                        "INSECURE_URL",
                        BackupMessage.of(R.string.backup_validation_artist_insecure_url, i),
                        module = "artist_images",
                        severity = Severity.WARNING,
                    ))
            }
            if (imageUrl.length > 2000) {
                errors.add(ValidationError(
                        "URL_TOO_LONG",
                        BackupMessage.of(R.string.backup_validation_artist_url_too_long, i),
                        module = "artist_images",
                        severity = Severity.WARNING,
                    ))
            }
        }
    }

    private fun validateTransitions(array: com.google.gson.JsonArray, errors: MutableList<ValidationError>) {
        array.forEachIndexed { i, element ->
            if (!element.isJsonObject) return@forEachIndexed
            val obj = element.asJsonObject
            val settings = obj.getAsJsonObject("settings")
            if (settings != null) {
                val durationMs = settings.get("durationMs")?.asInt ?: 0
                if (durationMs < 0 || durationMs > 30_000) {
                    errors.add(ValidationError(
                        "INVALID_TRANSITION_DURATION",
                        BackupMessage.of(R.string.backup_validation_transition_duration, i),
                        module = "transitions",
                        severity = Severity.WARNING,
                    ))
                }
            }
        }
    }

    private fun validatePreferenceEntries(jsonElement: com.google.gson.JsonElement, moduleKey: String, errors: MutableList<ValidationError>) {
        if (!jsonElement.isJsonArray) return
        jsonElement.asJsonArray.forEachIndexed { i, element ->
            if (!element.isJsonObject) return@forEachIndexed
            val obj = element.asJsonObject
            val key = obj.get("key")?.asString
            val type = obj.get("type")?.asString
            if (key.isNullOrBlank()) {
                errors.add(ValidationError(
                        "MISSING_PREF_KEY",
                        BackupMessage.of(R.string.backup_validation_pref_missing_key, i),
                        module = moduleKey,
                        severity = Severity.WARNING,
                    ))
            }
            if (type == null || type !in VALID_PREF_TYPES) {
                errors.add(ValidationError(
                        "INVALID_PREF_TYPE",
                        BackupMessage.of(
                            R.string.backup_validation_pref_invalid_type,
                            i,
                            type ?: "",
                        ),
                        module = moduleKey,
                        severity = Severity.WARNING,
                    ))
            }
        }
    }

    private fun readStringField(obj: com.google.gson.JsonObject, vararg keys: String): String? {
        return keys.asSequence()
            .mapNotNull { key ->
                obj.get(key)
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
            }
            .firstOrNull()
    }

    private fun readNumericField(obj: com.google.gson.JsonObject, vararg keys: String): NumericFieldResult {
        keys.forEach { key ->
            val primitive = obj.get(key)
                ?.takeIf { it.isJsonPrimitive }
                ?.asJsonPrimitive
                ?: return@forEach
            return when {
                primitive.isNumber -> NumericFieldResult(present = true, value = primitive.asNumber.toLong())
                primitive.isString -> NumericFieldResult(present = true, value = primitive.asString.toLongOrNull())
                else -> NumericFieldResult(present = true, value = null)
            }
        }
        return NumericFieldResult(present = false, value = null)
    }
}
