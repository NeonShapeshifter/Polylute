package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import com.lostf1sh.pixelplayeross.utils.normalizeMetadataText
import com.lostf1sh.pixelplayeross.utils.normalizeMetadataTextOrEmpty
import org.json.JSONArray
import org.json.JSONObject

/** Integer constants for the `source_type` column. Every row is local audio. */
object SourceType {
    const val LOCAL = 0
}

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["title"], unique = false),
        Index(value = ["album_id"], unique = false),
        Index(value = ["artist_id"], unique = false),
        Index(value = ["artist_name"], unique = false),
        Index(value = ["genre"], unique = false),
        Index(value = ["parent_directory_path"], unique = false),
        Index(value = ["file_path"], unique = false),
        Index(value = ["content_uri_string"], unique = false),
        Index(value = ["date_added"], unique = false),
        Index(value = ["duration"], unique = false),
        Index(value = ["source_type"], unique = false),
        Index(value = ["album_artist_id"], unique = false),
        Index(value = ["parent_directory_path", "source_type", "album_id"], unique = false),
        Index(value = ["parent_directory_path", "source_type", "id"], unique = false),
        // Declared so the schema Room validates equals the schema the migrations actually build.
        // `MIGRATION_7_8` and `MIGRATION_8_9` create these two and the entity did not list them,
        // which made every upgrade that ran either migration fail validation with
        // "Migration didn't properly handle: songs" — a launch-blocking failure nothing caught,
        // because the migration was never executed against a real database until now.
        Index(value = ["stable_key"], unique = false),
        Index(value = ["library_item_id"], unique = false),
        Index(value = ["album_grouping_key"], unique = false),
        Index(value = ["artist_grouping_key"], unique = false)
    ],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artist_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class SongEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "artist_id") val artistId: Long,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    @ColumnInfo(name = "album_artist_id", defaultValue = "0") val albumArtistId: Long = 0L,
    @ColumnInfo(name = "album_name") val albumName: String,
    @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "content_uri_string") val contentUriString: String,
    @ColumnInfo(name = "album_art_uri_string") val albumArtUriString: String?,
    @ColumnInfo(name = "duration") val duration: Long,
    @ColumnInfo(name = "genre") val genre: String?,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "parent_directory_path") val parentDirectoryPath: String,
    @ColumnInfo(name = "is_favorite", defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(name = "lyrics", defaultValue = "null") val lyrics: String? = null,
    @ColumnInfo(name = "track_number", defaultValue = "0") val trackNumber: Int = 0,
    @ColumnInfo(name = "disc_number", defaultValue = "null") val discNumber: Int? = null,
    @ColumnInfo(name = "year", defaultValue = "0") val year: Int = 0,
    @ColumnInfo(name = "date_added", defaultValue = "0") val dateAdded: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "bitrate") val bitrate: Int? = null,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int? = null,
    @ColumnInfo(name = "artists_json") val artistsJson: String? = null,
    @ColumnInfo(name = "source_type", defaultValue = "0") val sourceType: Int = SourceType.LOCAL,
    @ColumnInfo(name = "media_store_date_added", defaultValue = "0") val mediaStoreDateAdded: Long = 0L,
    @ColumnInfo(name = "media_store_date_modified", defaultValue = "0") val mediaStoreDateModified: Long = 0L,
    @ColumnInfo(name = "title_user_edited", defaultValue = "0") val titleUserEdited: Boolean = false,
    @ColumnInfo(name = "artist_user_edited", defaultValue = "0") val artistUserEdited: Boolean = false,
    @ColumnInfo(name = "album_user_edited", defaultValue = "0") val albumUserEdited: Boolean = false,
    @ColumnInfo(name = "genre_user_edited", defaultValue = "0") val genreUserEdited: Boolean = false,
    /**
     * The durable, PixelPlayer-owned identity for this library item.
     *
     * Opaque, generated once when the item is first seen, and **never recomputed from metadata**.
     * User state (engagement, and eventually anything else durable) keys off this and nothing else.
     * `id` above remains the MediaStore locator — how a scan finds the row, never what the row is.
     *
     * Note this is deliberately *not* [stableKey]: a fingerprint is matching evidence that can be
     * shared by two identical files and can legitimately change when a file is re-tagged. Identity
     * has to survive both, so it cannot be derived from either.
     */
    @ColumnInfo(name = "library_item_id", defaultValue = "''") val libraryItemId: String = "",

    /**
     * PixelPlayer's matching evidence for this library item — see
     * [com.lostf1sh.pixelplayeross.data.identity.LibraryIdentity]. Never the identity itself.
     *
     * See [com.lostf1sh.pixelplayeross.data.identity.LibraryIdentity]. Empty until the first scan
     * after migration 7→8 fills it; user data never keys off this column directly, it keys off the
     * same value on `song_engagements`.
     */
    @ColumnInfo(name = "stable_key", defaultValue = "''") val stableKey: String = "",

    /**
     * File size in bytes as MediaStore reports it.
     *
     * Stored rather than re-queried because it is an input to [stableKey]: identity has to be
     * recomputable at scan time without a second cursor.
     */
    @ColumnInfo(name = "file_size", defaultValue = "0") val fileSize: Long = 0L,

    /**
     * The deterministic key that decides which album this track belongs to.
     *
     * Derived from normalized album title and album artist, never from the MediaStore album id —
     * see `MetadataNormalizer.albumGroupingKey`. Stored rather than recomputed so that grouping can
     * be queried and indexed, and so the Maintenance diagnostic can show what the grouping actually
     * used.
     */
    @ColumnInfo(name = "album_grouping_key", defaultValue = "''") val albumGroupingKey: String = "",

    /** The deterministic key that decides which artist this track belongs to. Case-folded only. */
    @ColumnInfo(name = "artist_grouping_key", defaultValue = "''") val artistGroupingKey: String = "",

    /**
     * Every genre the source provided, as a JSON array, or null when it provided none.
     *
     * [genre] keeps the original tag verbatim for display; this is the parsed form used for
     * matching. A slash is deliberately *not* a separator — see `MetadataNormalizer.parseGenres`.
     */
    @ColumnInfo(name = "genres_json") val genresJson: String? = null,

    /**
     * The track and disc values exactly as the source reported them.
     *
     * Kept because [trackNumber] and [discNumber] are *interpretations*: a tagger's `"3/12"` becomes
     * `3`, and MediaStore's packed integer becomes a disc and a track. When a track sorts somewhere
     * surprising, the diagnostic needs to show what it was derived from.
     */
    @ColumnInfo(name = "raw_track_number") val rawTrackNumber: String? = null,
    @ColumnInfo(name = "raw_disc_number") val rawDiscNumber: String? = null,

    /**
     * Whether this track's album is a compilation.
     *
     * Set from explicit evidence only — an ID3 `TCMP`/Vorbis `COMPILATION` flag, or an explicit
     * album artist of "Various Artists". Multiple track artists is **not** evidence; see
     * `MetadataNormalizer.isCompilation`.
     */
    @ColumnInfo(name = "is_compilation", defaultValue = "0") val isCompilation: Boolean = false,

    @ColumnInfo(name = "mb_recording_id") val mbRecordingId: String? = null,
    @ColumnInfo(name = "mb_release_id") val mbReleaseId: String? = null,
    @ColumnInfo(name = "mb_artist_id") val mbArtistId: String? = null
)

private fun SongEntity.toSongInternal(
    artists: List<ArtistRef>,
    artworkAbsent: (Long) -> Boolean = { false }
): Song {
    return Song(
        id = this.id.toString(),
        libraryItemId = this.libraryItemId,
        title = this.title.normalizeMetadataTextOrEmpty(),
        artist = this.artistName.normalizeMetadataTextOrEmpty(),
        artistId = this.artistId,
        artists = artists,
        album = this.albumName.normalizeMetadataTextOrEmpty(),
        albumId = this.albumId,
        albumArtist = this.albumArtist?.normalizeMetadataText(),
        path = this.filePath,
        contentUriString = this.contentUriString,
        // PHASE 14.0Z3 — a song the extraction path has proved has no artwork must not
        // advertise a synthesized cover uri. Defaulted, so untouched callers are unchanged.
        albumArtUriString = if (artworkAbsent(this.id)) {
            null
        } else {
            LocalArtworkUri.resolveSongArtworkUri(
                storedUri = this.albumArtUriString,
                songId = this.id,
                contentUriString = this.contentUriString
            )
        },
        duration = this.duration,
        genre = this.genre.normalizeMetadataText(),
        lyrics = this.lyrics?.normalizeMetadataText(),
        isFavorite = this.isFavorite,
        trackNumber = this.trackNumber,
        discNumber = this.discNumber,
        dateAdded = this.dateAdded,
        year = this.year,
        mimeType = this.mimeType,
        bitrate = this.bitrate,
        sampleRate = this.sampleRate
    )
}

fun SongEntity.toSong(artworkAbsent: (Long) -> Boolean = { false }): Song {
    val artists = parseArtistsJson(this.artistsJson)
    return toSongInternal(artists = artists, artworkAbsent = artworkAbsent)
}

/**
 * Parses the artists_json column back into a list of ArtistRef.
 */
private fun parseArtistsJson(json: String?): List<ArtistRef> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ArtistRef(
                id = obj.getLong("id"),
                name = obj.getString("name"),
                isPrimary = obj.optBoolean("primary", false)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Serializes a list of ArtistRef to JSON for storage in artists_json column.
 */
fun serializeArtistRefs(artists: List<ArtistRef>): String {
    val arr = JSONArray()
    artists.forEach { ref ->
        arr.put(JSONObject().apply {
            put("id", ref.id)
            put("name", ref.name)
            put("primary", ref.isPrimary)
        })
    }
    return arr.toString()
}

/**
 * Converts a SongEntity to Song with artists from the junction table.
 */
fun SongEntity.toSongWithArtistRefs(artists: List<ArtistEntity>, crossRefs: List<SongArtistCrossRef>): Song {
    val crossRefByArtistId = crossRefs.associateBy { it.artistId }
    val artistRefs = artists.map { artist ->
        val crossRef = crossRefByArtistId[artist.id]
        ArtistRef(
            id = artist.id,
            name = artist.name.normalizeMetadataTextOrEmpty(),
            isPrimary = crossRef?.isPrimary ?: false
        )
    }.sortedByDescending { it.isPrimary }

    return toSongInternal(artists = artistRefs)
}

fun List<SongEntity>.toSongs(): List<Song> {
    return this.map { it.toSong() }
}

fun Song.toEntity(filePathFromMediaStore: String, parentDirFromMediaStore: String): SongEntity {
    return SongEntity(
        id = this.id.toLong(),
        title = this.title,
        artistName = this.artist,
        artistId = this.artistId,
        albumArtist = this.albumArtist,
        albumName = this.album,
        albumId = this.albumId,
        contentUriString = this.contentUriString,
        albumArtUriString = this.albumArtUriString,
        duration = this.duration,
        genre = this.genre,
        isFavorite = this.isFavorite,
        lyrics = this.lyrics,
        trackNumber = this.trackNumber,
        discNumber = this.discNumber,
        filePath = filePathFromMediaStore,
        parentDirectoryPath = parentDirFromMediaStore,
        dateAdded = this.dateAdded,
        year = this.year,
        mimeType = this.mimeType,
        bitrate = this.bitrate,
        sampleRate = this.sampleRate,
        sourceType = SourceType.LOCAL
    )
}

/** Lightweight projection for backup song matching. */
data class SongSummary(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_name") val albumName: String,
    val duration: Long
)

fun Song.toEntityWithoutPaths(): SongEntity {
    return SongEntity(
        id = this.id.toLong(),
        title = this.title,
        artistName = this.artist,
        artistId = this.artistId,
        albumArtist = this.albumArtist,
        albumName = this.album,
        albumId = this.albumId,
        contentUriString = this.contentUriString,
        albumArtUriString = this.albumArtUriString,
        duration = this.duration,
        genre = this.genre,
        isFavorite = this.isFavorite,
        lyrics = this.lyrics,
        trackNumber = this.trackNumber,
        discNumber = this.discNumber,
        filePath = "",
        parentDirectoryPath = "",
        dateAdded = this.dateAdded,
        year = this.year,
        mimeType = this.mimeType,
        bitrate = this.bitrate,
        sampleRate = this.sampleRate,
        sourceType = SourceType.LOCAL
    )
}
