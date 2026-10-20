package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlist_id", "song_id"],
    indices = [
        Index(value = ["playlist_id", "sort_order"]),
        Index(value = ["song_id"]),
        // Created by the migration; listing it here is what keeps the upgraded schema valid.
        Index(value = ["library_item_id"])
    ]
)
data class PlaylistSongEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,
    @ColumnInfo(name = "song_id")
    val songId: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,

    /**
     * The durable owner of this membership.
     *
     * [songId] stays as the locator the entry was written with, but membership is the user's
     * statement that a *track* is in a playlist — not that a particular MediaStore number is — so it
     * is keyed by identity and survives the track moving.
     */
    @ColumnInfo(name = "library_item_id", defaultValue = "''")
    val libraryItemId: String = "",
)
