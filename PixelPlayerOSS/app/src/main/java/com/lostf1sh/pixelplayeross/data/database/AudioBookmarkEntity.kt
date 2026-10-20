package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audio_bookmarks",
    // Created by the migration; listing it here is what keeps the upgraded schema valid.
    indices = [Index(value = ["library_item_id"], unique = false)]
)
data class AudioBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "song_title") val songTitle: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_art_uri") val albumArtUri: String?,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "created_time") val createdTime: Long = System.currentTimeMillis(),

    /**
     * The durable owner of this bookmark.
     *
     * [songId] remains as the locator the bookmark was created against, but a bookmark is a note
     * about a *track*, so it is keyed by identity and survives the track moving or going away.
     */
    @ColumnInfo(name = "library_item_id", defaultValue = "''") val libraryItemId: String = ""
)
