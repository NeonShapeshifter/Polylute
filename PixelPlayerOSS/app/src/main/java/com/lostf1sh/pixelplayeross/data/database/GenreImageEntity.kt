package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "genre_images")
data class GenreImageEntity(
    @PrimaryKey @ColumnInfo(name = "genre_key") val genreKey: String,
    @ColumnInfo(name = "image_path") val imagePath: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
