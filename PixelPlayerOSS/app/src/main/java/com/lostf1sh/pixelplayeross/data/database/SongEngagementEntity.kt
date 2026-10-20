package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

/**
 * Room entity for storing song engagement statistics.
 * This replaces the JSON-based storage in DailyMixManager for better performance
 * and structured querying.
 */
@Entity(
    tableName = "song_engagements",
    indices = [
        Index(value = ["play_count"], unique = false),
        Index(value = ["skip_count"], unique = false),
        Index(value = ["last_played_timestamp"], unique = false),
        Index(value = ["stable_key"], unique = false),
        Index(value = ["library_item_id"], unique = false)
    ]
)
data class SongEngagementEntity(
    @PrimaryKey
    @ColumnInfo(name = "song_id")
    @SerializedName(value = "songId", alternate = ["song_id"])
    val songId: String,
    
    /**
     * The durable anchor: the owning song's [SongEntity.stableKey].
     *
     * `song_id` above remains the current MediaStore locator and is still written, so nothing that
     * joins on it breaks. But when Android renumbers a file the locator changes and the key does
     * not, so this is the column history is actually found by — which is what lets a renumbered
     * track keep its statistics, and stops a reused locator from inheriting someone else's.
     */
    @ColumnInfo(name = "stable_key", defaultValue = "''")
    @SerializedName(value = "stableKey", alternate = ["stable_key"])
    val stableKey: String = "",

    /**
     * The owning song's durable identity — the column history is actually found by.
     *
     * `song_id` above is the current MediaStore locator and is still written so nothing that joins
     * on it breaks. But a locator changes when Android renumbers and an identity does not, which is
     * what lets a renumbered track keep its statistics and stops a reused locator from inheriting.
     */
    @ColumnInfo(name = "library_item_id", defaultValue = "''")
    @SerializedName(value = "libraryItemId", alternate = ["library_item_id"])
    val libraryItemId: String = "",

    @ColumnInfo(name = "play_count")
    @SerializedName(value = "playCount", alternate = ["play_count", "score", "plays"])
    val playCount: Int = 0,
    
    @ColumnInfo(name = "total_play_duration_ms")
    @SerializedName(
        value = "totalPlayDurationMs",
        alternate = ["total_play_duration_ms", "totalDuration", "total_duration", "durationMs", "duration_ms"]
    )
    val totalPlayDurationMs: Long = 0L,
    
    @ColumnInfo(name = "completion_count", defaultValue = "0")
    @SerializedName(value = "completionCount", alternate = ["completion_count"])
    val completionCount: Int = 0,

    @ColumnInfo(name = "skip_count", defaultValue = "0")
    @SerializedName(value = "skipCount", alternate = ["skip_count"])
    val skipCount: Int = 0,

    /**
     * When this track was first ever played.
     *
     * Distinct from [lastPlayedTimestamp] and never rewritten: it is the one field that answers
     * "how long has this been in my listening life", which no amount of derivation recovers once
     * the first play is forgotten.
     */
    @ColumnInfo(name = "first_played_at", defaultValue = "0")
    @SerializedName(value = "firstPlayedAt", alternate = ["first_played_at"])
    val firstPlayedAt: Long = 0L,

    @ColumnInfo(name = "last_completed_at", defaultValue = "0")
    @SerializedName(value = "lastCompletedAt", alternate = ["last_completed_at"])
    val lastCompletedAt: Long = 0L,

    @ColumnInfo(name = "last_skipped_at", defaultValue = "0")
    @SerializedName(value = "lastSkippedAt", alternate = ["last_skipped_at"])
    val lastSkippedAt: Long = 0L,

    @ColumnInfo(name = "last_played_timestamp")
    @SerializedName(
        value = "lastPlayedTimestamp",
        alternate = ["last_played_timestamp", "lastPlayedAt", "last_played_at", "timestamp"]
    )
    val lastPlayedTimestamp: Long = 0L
)
