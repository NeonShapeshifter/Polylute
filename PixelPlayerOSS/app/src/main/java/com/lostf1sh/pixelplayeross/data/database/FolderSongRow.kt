package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo

/**
 * Minimal projection used to build the folder tree without loading full song rows.
 */
data class FolderSongRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "parent_directory_path") val parentDirectoryPath: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "album_art_uri_string") val albumArtUriString: String?,
    // Reviewer 11-24 (rc.4): the folder view plays an album folder in track order, so the tree needs these two.
    @ColumnInfo(name = "track_number") val trackNumber: Int = 0,
    @ColumnInfo(name = "disc_number") val discNumber: Int? = null,
    // B74: folder sorting distinguishes one-album vs mixed folders (by album) and sorts mixed folders by file name.
    @ColumnInfo(name = "album_name") val albumName: String = "",
    @ColumnInfo(name = "album_id") val albumId: Long = -1L,
    @ColumnInfo(name = "file_path") val filePath: String = "",
    @ColumnInfo(name = "artist_name") val artistName: String = "",
)
