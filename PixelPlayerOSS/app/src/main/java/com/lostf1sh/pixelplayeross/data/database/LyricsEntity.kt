package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "lyrics",
    // Declared because the migration creates it. An index the migration builds but the entity does
    // not list makes Room reject the upgraded schema outright.
    indices = [Index(value = ["library_item_id"], unique = false)]
)
data class LyricsEntity(
    @PrimaryKey
    @SerializedName(value = "songId", alternate = ["song_id"])
    val songId: Long,
    @SerializedName("content")
    val content: String,
    @SerializedName(value = "isSynced", alternate = ["is_synced"])
    val isSynced: Boolean = false,
    @SerializedName("source")
    val source: String? = null,

    /**
     * The durable owner of these lyrics.
     *
     * [songId] is the MediaStore locator and is kept as a compatibility detail, but it no longer
     * decides ownership: a user's lyrics belong to the logical item, so they survive the item being
     * renumbered and survive it having no projection at all.
     */
    @ColumnInfo(name = "library_item_id", defaultValue = "''")
    @SerializedName(value = "libraryItemId", alternate = ["library_item_id"])
    val libraryItemId: String = ""
)
