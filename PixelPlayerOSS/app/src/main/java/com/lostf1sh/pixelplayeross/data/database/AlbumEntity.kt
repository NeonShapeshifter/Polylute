package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import com.lostf1sh.pixelplayeross.utils.normalizeMetadataTextOrEmpty

@Entity(
    tableName = "albums",
    indices = [
        Index(value = ["title"], unique = false),
        Index(value = ["artist_id"], unique = false),
        Index(value = ["artist_name"], unique = false),
        Index(value = ["album_artist"], unique = false),
        Index(value = ["grouping_key"], unique = false)
    ]
)
data class AlbumEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "artist_id") val artistId: Long,
    @ColumnInfo(name = "album_art_uri_string") val albumArtUriString: String?,
    @ColumnInfo(name = "song_count") val songCount: Int,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
    @ColumnInfo(name = "year") val year: Int,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,

    /**
     * The deterministic key this album was grouped under.
     *
     * Distinct from [id], which is a MediaStore locator: several MediaStore albums can legitimately
     * carry one grouping key — a compilation whose tracks Android split across per-artist album ids,
     * or a two-disc release stored in per-disc folders — and they are one album here.
     */
    @ColumnInfo(name = "grouping_key", defaultValue = "''") val groupingKey: String = "",

    /** See `MetadataNormalizer.isCompilation`. Explicit evidence only. */
    @ColumnInfo(name = "is_compilation", defaultValue = "0") val isCompilation: Boolean = false
)

fun AlbumEntity.toAlbum(): Album {
    val effectiveAlbumArtUri = when {
        this.albumArtUriString.isNullOrBlank() -> null
        LocalArtworkUri.looksLikeVolatileArtworkUri(this.albumArtUriString) ->
            LocalArtworkUri.parseSongIdFromVolatileArtworkUri(this.albumArtUriString)
                ?.let { LocalArtworkUri.buildSongUri(it) }
        else -> this.albumArtUriString
    }

    return Album(
        id = this.id,
        title = this.title.normalizeMetadataTextOrEmpty(),
        artist = this.artistName.normalizeMetadataTextOrEmpty(),
        albumArtist = this.albumArtist?.normalizeMetadataTextOrEmpty()?.takeIf { it.isNotBlank() },
        albumArtUriString = effectiveAlbumArtUri,
        songCount = this.songCount,
        dateAdded = this.dateAdded,
        year = this.year
    )
}

fun List<AlbumEntity>.toAlbums(): List<Album> {
    return this.map { it.toAlbum() }
}

fun Album.toEntity(artistIdForAlbum: Long): AlbumEntity {
    return AlbumEntity(
        id = this.id,
        title = this.title,
        artistName = this.artist,
        artistId = artistIdForAlbum,
        albumArtUriString = this.albumArtUriString,
        songCount = this.songCount,
        dateAdded = this.dateAdded,
        year = this.year,
        albumArtist = this.albumArtist
    )
}
