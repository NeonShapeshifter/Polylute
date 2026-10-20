package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["timestamp"], unique = false),
        Index(value = ["library_item_id"], unique = false)
    ]
)
data class FavoritesEntity(
    @PrimaryKey
    @SerializedName(value = "songId", alternate = ["song_id"])
    val songId: Long,
    @SerializedName(value = "isFavorite", alternate = ["is_favorite"])
    val isFavorite: Boolean = true,
    @SerializedName(value = "timestamp", alternate = ["addedAt", "added_at"])
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * The durable owner of this favourite.
     *
     * [songId] above is the current MediaStore locator and is still maintained so the existing
     * locator-shaped read paths keep working. It is **not** what decides ownership: a favourite
     * belongs to the logical item, and must survive that item being renumbered.
     */
    @ColumnInfo(name = "library_item_id", defaultValue = "''")
    @SerializedName(value = "libraryItemId", alternate = ["library_item_id"])
    val libraryItemId: String = ""
)
