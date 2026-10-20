package com.lostf1sh.pixelplayeross.data.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import com.lostf1sh.pixelplayeross.utils.AudioMeta
import timber.log.Timber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine

private val SONG_SEARCH_QUERY_TOKEN_REGEX = Regex("""[\p{L}\p{N}]+""")
private const val EMPTY_SONG_SEARCH_MATCH_QUERY = "pixelplayeremptyquery*"

private fun buildSongTitleSearchMatchQuery(query: String): String {
    val tokens = SONG_SEARCH_QUERY_TOKEN_REGEX
        .findAll(query)
        .map { it.value.trim() }
        .filter { it.isNotEmpty() }
        .take(6)
        .toList()

    if (tokens.isEmpty()) return EMPTY_SONG_SEARCH_MATCH_QUERY

    return tokens.joinToString(separator = " AND ") { "title:${it}*" }
}

private fun buildSongSearchMatchQuery(query: String): String {
    val tokens = SONG_SEARCH_QUERY_TOKEN_REGEX
        .findAll(query)
        .map { it.value.trim() }
        .filter { it.isNotEmpty() }
        .take(6)
        .toList()

    if (tokens.isEmpty()) return EMPTY_SONG_SEARCH_MATCH_QUERY

    return tokens.joinToString(separator = " AND ") { "${it}*" }
}

private const val SONG_DETAIL_PROJECTION = """
    songs.id AS id,
    songs.title AS title,
    songs.artist_name AS artist_name,
    songs.artist_id AS artist_id,
    songs.album_artist AS album_artist,
    songs.album_artist_id AS album_artist_id,
    songs.album_name AS album_name,
    songs.album_id AS album_id,
    songs.content_uri_string AS content_uri_string,
    songs.album_art_uri_string AS album_art_uri_string,
    songs.duration AS duration,
    songs.genre AS genre,
    songs.file_path AS file_path,
    songs.parent_directory_path AS parent_directory_path,
    songs.is_favorite AS is_favorite,
    COALESCE(song_lyrics.content, songs.lyrics) AS lyrics,
    songs.track_number AS track_number,
    songs.disc_number AS disc_number,
    songs.year AS year,
    songs.date_added AS date_added,
    songs.mime_type AS mime_type,
    songs.bitrate AS bitrate,
    songs.sample_rate AS sample_rate,
    songs.artists_json AS artists_json,
    songs.source_type AS source_type,
    songs.media_store_date_added AS media_store_date_added,
    songs.media_store_date_modified AS media_store_date_modified,
    songs.title_user_edited AS title_user_edited,
    songs.artist_user_edited AS artist_user_edited,
    songs.album_user_edited AS album_user_edited,
    songs.genre_user_edited AS genre_user_edited,
    songs.stable_key AS stable_key,
    songs.library_item_id AS library_item_id,
    songs.file_size AS file_size,
    songs.mb_recording_id AS mb_recording_id,
    songs.mb_release_id AS mb_release_id,
    songs.mb_artist_id AS mb_artist_id,
    songs.album_grouping_key AS album_grouping_key,
    songs.artist_grouping_key AS artist_grouping_key,
    songs.genres_json AS genres_json,
    songs.raw_track_number AS raw_track_number,
    songs.raw_disc_number AS raw_disc_number,
    songs.is_compilation AS is_compilation
"""

private const val SONG_LIST_PROJECTION = """
    id, title, artist_name, artist_id, album_artist, album_artist_id, album_name, album_id,
    content_uri_string, album_art_uri_string, duration, genre, file_path,
    parent_directory_path, is_favorite, NULL AS lyrics, track_number, disc_number,
    year, date_added, mime_type, bitrate, sample_rate, artists_json, source_type,
    media_store_date_added, media_store_date_modified, title_user_edited,
    artist_user_edited, album_user_edited, genre_user_edited,
    stable_key, file_size, songs.library_item_id AS library_item_id,
    mb_recording_id, mb_release_id, mb_artist_id,
    album_grouping_key, artist_grouping_key, genres_json, raw_track_number, raw_disc_number,
    is_compilation
"""

data class DeviceCapabilitySongRow(
    val filePath: String,
    val contentUriString: String,
    val mimeType: String?,
    val duration: Long,
    val bitrate: Int?,
    val sampleRate: Int?,
    val sourceType: Int
)

/**
 * Single-pass audio aggregates for the diagnostic performance report.
 * Computed in one SQL pass so it stays cheap even on large libraries —
 * we never materialize every row. File-size figures are *estimated* from
 * bitrate × duration because raw byte sizes are not stored in the DB; this
 * avoids per-file filesystem stat calls just to build a report.
 */
data class LibraryAudioStatsRow(
    val totalCount: Int,
    val hiResCount: Int,
    val ultraHiResCount: Int,
    val likelyExpensiveCount: Int,
    val maxBitrate: Int?,
    val minSampleRate: Int?,
    val maxSampleRate: Int?,
    val estMinBytes: Long?,
    val estAvgBytes: Double?,
    val estMaxBytes: Long?
)

data class MediaStoreIdentityProjection(
    val id: Long,
    val libraryItemId: String,
    val duration: Long,
    val fileSize: Long,
)

data class MimeTypeCountRow(
    val mimeType: String?,
    val count: Int
)

/**
 * The columns a MusicBrainz write reads and overwrites, as one row.
 *
 * [title], [artistName] and [albumName] are compared against the same placeholder rules the update
 * uses; the three id columns are always overwritten, so they are compared for equality.
 */
data class MusicBrainzCurrentState(
    val title: String,
    val artistName: String,
    val albumName: String,
    val year: Int,
    val mbRecordingId: String?,
    val mbReleaseId: String?,
    val mbArtistId: String?,
)

@Dao
interface MusicDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongsIgnoreConflicts(songs: List<SongEntity>): List<Long>

    @Update
    suspend fun updateSongs(songs: List<SongEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumsIgnoreConflicts(albums: List<AlbumEntity>): List<Long>

    @Update
    suspend fun updateAlbums(albums: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtistsIgnoreConflicts(artists: List<ArtistEntity>): List<Long>

    @Update
    suspend fun updateArtists(artists: List<ArtistEntity>)

    @Query("SELECT * FROM artists WHERE id IN (:artistIds)")
    suspend fun getArtistsByIds(artistIds: List<Long>): List<ArtistEntity>

    @Transaction
    suspend fun insertSongs(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        val insertResults = insertSongsIgnoreConflicts(songs)
        val songsToUpdate = mutableListOf<SongEntity>()
        insertResults.forEachIndexed { index, rowId ->
            if (rowId == -1L) songsToUpdate.add(songs[index])
        }
        if (songsToUpdate.isNotEmpty()) {
            updateSongs(songsToUpdate)
        }
    }

    @Transaction
    suspend fun insertAlbums(albums: List<AlbumEntity>) {
        if (albums.isEmpty()) return
        val insertResults = insertAlbumsIgnoreConflicts(albums)
        val albumsToUpdate = mutableListOf<AlbumEntity>()
        insertResults.forEachIndexed { index, rowId ->
            if (rowId == -1L) albumsToUpdate.add(albums[index])
        }
        if (albumsToUpdate.isNotEmpty()) {
            updateAlbums(albumsToUpdate)
        }
    }

    @Transaction
    suspend fun insertArtists(artists: List<ArtistEntity>) {
        if (artists.isEmpty()) return
        val insertResults = insertArtistsIgnoreConflicts(artists)
        val artistsToUpdate = mutableListOf<ArtistEntity>()
        insertResults.forEachIndexed { index, rowId ->
            if (rowId == -1L) artistsToUpdate.add(artists[index])
        }
        if (artistsToUpdate.isNotEmpty()) {
            val existingById = getArtistsByIds(artistsToUpdate.map { it.id }).associateBy { it.id }
            val mergedArtists = artistsToUpdate.map { incoming ->
                val existing = existingById[incoming.id]
                if (existing == null) {
                    incoming
                } else {
                    incoming.copy(
                        imageUrl = incoming.imageUrl ?: existing.imageUrl,
                        customImageUri = incoming.customImageUri ?: existing.customImageUri
                    )
                }
            }
            updateArtists(mergedArtists)
        }
    }



    @Transaction
    suspend fun insertMusicData(songs: List<SongEntity>, albums: List<AlbumEntity>, artists: List<ArtistEntity>) {
        insertArtists(artists)
        insertAlbums(albums)
        insertSongs(songs)
    }

    @Transaction
    suspend fun clearAllMusicData() {
        clearAllSongs()
        clearAllAlbums()
        clearAllArtists()
    }

    @Query("DELETE FROM songs")
    suspend fun clearAllSongs()

    @Query("DELETE FROM songs WHERE source_type = 0")
    suspend fun clearLocalSongs()

    @Query("DELETE FROM albums")
    suspend fun clearAllAlbums()

    @Query("DELETE FROM artists")
    suspend fun clearAllArtists()

    @Query("SELECT id FROM songs")
    suspend fun getAllSongIds(): List<Long>

    @Query("SELECT id FROM songs WHERE source_type = 0")
    suspend fun getAllMediaStoreSongIds(): List<Long>

    /** Compact stored evidence; no artwork, lyrics or file reads during reconciliation. */
    @Query("""SELECT s.id, s.library_item_id AS libraryItemId, s.duration, s.file_size AS fileSize
        FROM songs s JOIN library_items li
        ON li.library_item_id = s.library_item_id AND li.current_locator = s.id
        WHERE s.source_type = 0 AND li.state = 'ACTIVE'""")
    suspend fun getMediaStoreIdentityProjections(): List<MediaStoreIdentityProjection>

    @Query("DELETE FROM songs WHERE id IN (:songIds)")
    suspend fun deleteSongsByIds(songIds: List<Long>)

    @Query("DELETE FROM song_artist_cross_ref WHERE song_id IN (:songIds)")
    suspend fun deleteCrossRefsBySongIds(songIds: List<Long>)

    @Query("DELETE FROM song_artist_cross_ref WHERE song_id IN (SELECT id FROM songs WHERE source_type = 0)")
    suspend fun deleteLocalSongArtistCrossRefs()

    @Query("DELETE FROM favorites WHERE songId IN (:songIds)")
    suspend fun deleteFavoritesBySongIds(songIds: List<Long>)

    @Query("DELETE FROM favorites WHERE songId IN (SELECT id FROM songs WHERE source_type = 0)")
    suspend fun deleteLocalFavorites()

    @Query("DELETE FROM lyrics WHERE songId IN (:songIds)")
    suspend fun deleteLyricsBySongIds(songIds: List<Long>)

    @Query("DELETE FROM lyrics WHERE songId IN (SELECT id FROM songs WHERE source_type = 0)")
    suspend fun deleteLocalLyrics()

    @Query("""
        UPDATE artists
        SET track_count = (
            SELECT COUNT(DISTINCT song_artist_cross_ref.song_id)
            FROM song_artist_cross_ref
            WHERE song_artist_cross_ref.artist_id = artists.id
        )
    """)
    suspend fun refreshArtistTrackCounts()

    @Query("""
        UPDATE albums
        SET song_count = (
            SELECT COUNT(*)
            FROM songs
            WHERE songs.album_id = albums.id
        )
    """)
    suspend fun refreshAlbumSongCounts()

    // --- Transactional library-item reattachment (Phase 12.1B) ------------------------------------

    @Query("UPDATE favorites SET songId = :newId WHERE songId = :oldId")
    suspend fun moveFavoriteToLocator(oldId: Long, newId: Long)

    @Query("UPDATE lyrics SET songId = :newId WHERE songId = :oldId")
    suspend fun moveLyricsToLocator(oldId: Long, newId: Long)

    @Query("UPDATE playlist_songs SET song_id = :newId WHERE song_id = :oldId")
    suspend fun movePlaylistMembership(oldId: String, newId: String)

    @Query("UPDATE audio_bookmarks SET song_id = :newId WHERE song_id = :oldId")
    suspend fun moveBookmarksToLocator(oldId: String, newId: String)

    /** Only the locator column moves. The durable identity on this row already points at the item. */
    @Query("UPDATE song_engagements SET song_id = :newId WHERE song_id = :oldId")
    suspend fun moveEngagementLocator(oldId: String, newId: String)

    // --- Durable lifecycle (Phase 12.1B). None of these touch user state. ------------------------

    /**
     * The logical item is known but currently has no projection.
     *
     * Matching evidence is deliberately retained: it is what makes a return recognisable, and
     * discarding it on absence would turn every temporary loss into a permanent one.
     */
    @Query(
        "UPDATE library_items SET state = 'DORMANT', current_locator = NULL " +
            "WHERE library_item_id = :libraryItemId"
    )
    suspend fun markLibraryItemDormant(libraryItemId: String)

    @Query("UPDATE library_items SET state = 'ACTIVE', current_locator = :locator WHERE library_item_id = :libraryItemId")
    suspend fun markLibraryItemActive(libraryItemId: String, locator: Long)

    @Query("SELECT COUNT(*) FROM library_items WHERE state = 'DORMANT'")
    suspend fun dormantLibraryItemCount(): Int

    @Query("UPDATE songs SET library_item_id = :libraryItemId WHERE id = :locator")
    suspend fun assignLibraryItemId(locator: Long, libraryItemId: String)

    @Query("SELECT * FROM songs WHERE id = :locator")
    suspend fun getSongByLocatorOnce(locator: Long): SongEntity?

    /** The durable identity the locator currently projects, or null when the locator is not a library row (B28a). */
    @Query("SELECT library_item_id FROM songs WHERE id = :locator LIMIT 1")
    suspend fun libraryItemIdForLocator(locator: Long): String?

    @Query("SELECT COUNT(*) FROM songs WHERE library_item_id = :libraryItemId")
    suspend fun countProjectionsForLibraryItem(libraryItemId: String): Int

    // --- The active-projection predicate -----------------------------------------------------------
    //
    // A row in `songs` is a *projection* of a file MediaStore can currently see. It is ACTIVE only
    // when all three of these hold together:
    //
    //   1. it belongs to a logical item             songs.library_item_id = library_items.library_item_id
    //   2. that item is currently present           library_items.state = 'ACTIVE'
    //   3. the item points back at this very row    library_items.current_locator = songs.id
    //
    // All three, never one. Testing lifecycle alone would call a row active while its item had
    // already moved elsewhere; testing the locator alone would resurrect a dormant item that still
    // happens to hold a number. This is defined once, here, rather than re-derived per query —
    // three subtly different lifecycle checks across twenty queries is how a phantom track survives
    // in the one screen nobody re-checked.
    @Query(
        "SELECT songs.* FROM songs " +
            "INNER JOIN library_items ON library_items.library_item_id = songs.library_item_id " +
            "WHERE songs.id = :locator " +
            "AND library_items.state = 'ACTIVE' " +
            "AND library_items.current_locator = songs.id " +
            "LIMIT 1"
    )
    suspend fun getActiveSongByLocator(locator: Long): SongEntity?

    @Query(
        "SELECT COUNT(*) FROM songs " +
            "INNER JOIN library_items ON library_items.library_item_id = songs.library_item_id " +
            "WHERE library_items.state = 'ACTIVE' " +
            "AND library_items.current_locator = songs.id"
    )
    suspend fun countActiveProjections(): Int

    @Query("SELECT current_locator FROM library_items WHERE library_item_id = :libraryItemId")
    suspend fun currentLocatorFor(libraryItemId: String): Long?

    @Query("SELECT isFavorite FROM favorites WHERE library_item_id = :libraryItemId LIMIT 1")
    suspend fun isFavoriteForLibraryItem(libraryItemId: String): Boolean?

    @Query("UPDATE favorites SET songId = :newLocator WHERE library_item_id = :libraryItemId")
    suspend fun retargetFavouriteLocator(libraryItemId: String, newLocator: Long)

    /**
     * Moves a library item's **physical projection** from one MediaStore locator to another.
     *
     * This is the whole of reattachment, and it is deliberately small. An earlier version of this
     * method moved favourites, lyrics, playlist membership, bookmarks and engagement by hand. All of
     * that was a symptom of user state being owned by the locator: once it belongs to the logical
     * item, there is nothing to move, because none of it ever pointed at the locator in the first
     * place. What is left is genuinely physical — which row the file is, and which evidence
     * describes it.
     *
     * The old projection is **removed**, not merely superseded. Leaving it behind produces a phantom
     * track: still visible in the library, still searchable, still playable, for a file that is no
     * longer at that locator. Removal also cascades its artist cross-refs and fires the FTS delete
     * trigger, so derived state follows automatically.
     *
     * The favourite cache on the incoming projection is resolved from **identity**, so a projection
     * created after a locator change still shows as favourited.
     */
    @Transaction
    suspend fun reattachProjection(
        libraryItemId: String,
        oldLocator: Long?,
        incoming: SongEntity,
        stableKey: String,
        fileSignature: String,
    ): Boolean {
        if (libraryItemId.isEmpty()) return false

        if (oldLocator != null && oldLocator != incoming.id) {
            deleteCrossRefsBySongIds(listOf(oldLocator))
            deleteSongsByIds(listOf(oldLocator))
        }

        val favourite = isFavoriteForLibraryItem(libraryItemId) ?: false
        insertSongs(listOf(incoming.copy(libraryItemId = libraryItemId, isFavorite = favourite)))

        // The compatibility read paths still hand back locators, so the locator columns on the
        // logical-state rows are retargeted to follow the item. Identity is what decides ownership;
        // these are derived mirrors, maintained from the same source of truth, in the same way
        // `songs.is_favorite` is derived from the favourites row.
        //
        // `OR REPLACE` deliberately: the locator column is a primary key on `lyrics` and a key
        // component on `playlist_songs`, so if the item is moving onto a number that some stale row
        // already occupies, the stale row loses. It is about a file that is not there any more.
        retargetFavouriteLocator(libraryItemId, incoming.id)
        retargetLyricsLocator(libraryItemId, incoming.id)
        retargetBookmarkLocators(libraryItemId, incoming.id)
        retargetPlaylistMemberships(libraryItemId, incoming.id)

        markLibraryItemActive(libraryItemId, incoming.id)
        updateLibraryItemEvidence(libraryItemId, stableKey, fileSignature)
        return true
    }

    @Query(
        "UPDATE library_items SET stable_key = :stableKey, file_signature = :fileSignature " +
            "WHERE library_item_id = :libraryItemId"
    )
    suspend fun updateLibraryItemEvidence(
        libraryItemId: String,
        stableKey: String,
        fileSignature: String,
    )

    @Transaction
    suspend fun updateLibraryItemEvidenceBatch(items: List<LibraryItemEntity>) {
        items.forEach { updateLibraryItemEvidence(it.libraryItemId, it.stableKey, it.fileSignature) }
    }

    /**
     * The dormant transition — which **removes the stale projection**.
     *
     * Retaining the row used to be the safe-looking choice, because the row held the user's metadata
     * overrides. That is no longer true: overrides live in [library_item_overrides], keyed by
     * identity. With them out of the way, keeping the row has no justification left and a real cost —
     * it is a phantom track. The row is still in the library list, still matches a search, still in
     * the FTS index, still carries artist cross-refs, and still resolves if something asks for that
     * locator. Nothing about the file is true any more; Android simply cannot see it.
     *
     * Removing it also removes the derived state for free: cross-refs cascade, and the `songs_fts`
     * delete trigger fires, so search stops returning a track that is not there. Derived state is
     * *supposed* to be rebuilt from the projection, which is exactly what makes it safe to drop.
     *
     * The locator is checked before deleting. If the number has already been handed to a different
     * file, the row there is not this item's to remove.
     */
    @Transaction
    suspend fun markLibraryItemsDormantAndDropProjections(libraryItemIds: List<String>) {
        if (libraryItemIds.isEmpty()) return

        var removed = 0
        libraryItemIds.forEach { libraryItemId ->
            if (libraryItemId.isEmpty()) return@forEach
            val locator = currentLocatorFor(libraryItemId)
            if (locator != null) {
                val projected = getSongByLocatorOnce(locator)
                if (projected != null && projected.libraryItemId == libraryItemId) {
                    deleteCrossRefsBySongIds(listOf(locator))
                    deleteSongsByIds(listOf(locator))
                    removed++
                }
            }
            markLibraryItemDormant(libraryItemId)
        }

        if (removed > 0) {
            deleteOrphanedAlbums()
            deleteOrphanedArtists()
            refreshAlbumSongCounts()
            refreshArtistTrackCounts()
        }
    }

    /**
     * Gives every projection a durable item to belong to.
     *
     * A row in `songs` whose identity has no `library_items` entry is invisible to every
     * active-projection query, because the predicate requires the join to succeed. That gap is
     * reachable in ordinary use: a fresh install has no `library_items` rows at all until the first
     * scan, and `MIGRATION_9_10` only seeds the songs that existed *at upgrade time*. So after each
     * scan the two are reconciled here.
     *
     * `INSERT OR IGNORE` on purpose. An existing row is authoritative — a dormant item must not be
     * dragged back to ACTIVE by the mere presence of a stale projection, and the unique index on
     * `current_locator` means a locator already claimed by another item is skipped rather than
     * stealing it.
     */
    @Query(
        "INSERT OR IGNORE INTO `library_items` " +
            "(`library_item_id`, `state`, `stable_key`, `file_signature`, `current_locator`, " +
            "`first_seen_at`, `last_seen_at`) " +
            "SELECT `library_item_id`, 'ACTIVE', `stable_key`, '', `id`, `date_added`, `date_added` " +
            "FROM `songs` WHERE `library_item_id` <> ''"
    )
    suspend fun seedLibraryItemsFromProjections()

    @Query("SELECT * FROM library_items")
    suspend fun getAllLibraryItems(): List<LibraryItemEntity>

    // --- Compatibility locator maintenance ---------------------------------------------------------
    //
    // Identity owns these rows; the locator each carries is a mirror kept in step so the many
    // locator-shaped consumers stay coherent without being rewritten. These are **not** a second
    // authority: nothing reads them to decide what a row belongs to. They exist because the older
    // read paths hand out locators, and a locator that no longer resolves is indistinguishable from
    // a deleted track.

    @Query("UPDATE OR REPLACE `lyrics` SET `songId` = :newLocator WHERE `library_item_id` = :libraryItemId")
    suspend fun retargetLyricsLocator(libraryItemId: String, newLocator: Long)

    @Query("UPDATE `audio_bookmarks` SET `song_id` = CAST(:newLocator AS TEXT) WHERE `library_item_id` = :libraryItemId")
    suspend fun retargetBookmarkLocators(libraryItemId: String, newLocator: Long)

    @Query("UPDATE OR REPLACE `playlist_songs` SET `song_id` = CAST(:newLocator AS TEXT) WHERE `library_item_id` = :libraryItemId")
    suspend fun retargetPlaylistMemberships(libraryItemId: String, newLocator: Long)

    /**
     * Recomputes the projection's display values and edit flags from the durable overrides.
     *
     * This is the recurrence that makes "one writable authority" true rather than aspirational: the
     * user's edit is written once, to [library_item_overrides], and every projection picks it up from
     * there. The columns on `songs` are then a cache, exactly like `songs.is_favorite` — always
     * derivable, never the source.
     *
     * It runs over the whole table rather than per row because it has to also *clear* flags whose
     * override has been removed. A per-row update on the changed items only would leave a projection
     * claiming an edit that no longer exists, and the scan consults those flags when deciding whether
     * a file is unchanged — so a stale flag would freeze the track's metadata permanently.
     */
    @Query(
        "UPDATE `songs` SET " +
            "`title` = COALESCE((SELECT o.`title` FROM `library_item_overrides` o " +
            "WHERE o.`library_item_id` = `songs`.`library_item_id` AND o.`title_user_edited` = 1), `title`), " +
            "`artist_name` = COALESCE((SELECT o.`artist` FROM `library_item_overrides` o " +
            "WHERE o.`library_item_id` = `songs`.`library_item_id` AND o.`artist_user_edited` = 1), `artist_name`), " +
            "`album_name` = COALESCE((SELECT o.`album` FROM `library_item_overrides` o " +
            "WHERE o.`library_item_id` = `songs`.`library_item_id` AND o.`album_user_edited` = 1), `album_name`), " +
            "`genre` = COALESCE((SELECT o.`genre` FROM `library_item_overrides` o " +
            "WHERE o.`library_item_id` = `songs`.`library_item_id` AND o.`genre_user_edited` = 1), `genre`), " +
            "`title_user_edited` = COALESCE((SELECT o.`title_user_edited` FROM `library_item_overrides` o " +
            "WHERE o.`library_item_id` = `songs`.`library_item_id`), 0), " +
            "`artist_user_edited` = COALESCE((SELECT o.`artist_user_edited` FROM `library_item_overrides` o " +
            "WHERE o.`library_item_id` = `songs`.`library_item_id`), 0), " +
            "`album_user_edited` = COALESCE((SELECT o.`album_user_edited` FROM `library_item_overrides` o " +
            "WHERE o.`library_item_id` = `songs`.`library_item_id`), 0), " +
            "`genre_user_edited` = COALESCE((SELECT o.`genre_user_edited` FROM `library_item_overrides` o " +
            "WHERE o.`library_item_id` = `songs`.`library_item_id`), 0) " +
            "WHERE `library_item_id` <> '' AND (" +
            "EXISTS (SELECT 1 FROM `library_item_overrides` o " +
            "WHERE o.`library_item_id` = `songs`.`library_item_id`) " +
            "OR `title_user_edited` = 1 OR `artist_user_edited` = 1 " +
            "OR `album_user_edited` = 1 OR `genre_user_edited` = 1)"
    )
    suspend fun reapplyLibraryItemOverridesToProjections()

    @Transaction
    suspend fun deleteSongsAndRelatedData(songIds: List<Long>) {
        if (songIds.isEmpty()) return
        songIds.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
            deleteCrossRefsBySongIds(chunk)
            deleteFavoritesBySongIds(chunk)
            deleteLyricsBySongIds(chunk)
            deleteSongsByIds(chunk)
        }
        deleteOrphanedAlbums()
        deleteOrphanedArtists()
        refreshAlbumSongCounts()
        refreshArtistTrackCounts()
    }

    /**
     * Incrementally sync music data: upsert new/modified songs and remove deleted ones.
     * More efficient than clear-and-replace for large libraries with few changes.
     */
    @Transaction
    suspend fun incrementalSyncMusicData(
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
        crossRefs: List<SongArtistCrossRef>,
        deletedSongIds: List<Long>
    ) {
        if (deletedSongIds.isNotEmpty()) {
            deletedSongIds.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
                deleteCrossRefsBySongIds(chunk)
                deleteFavoritesBySongIds(chunk)
                deleteLyricsBySongIds(chunk)
                deleteSongsByIds(chunk)
            }
        }

        insertArtists(artists)
        insertAlbums(albums)

        songs.chunked(SONG_BATCH_SIZE).forEach { chunk ->
            insertSongs(chunk)
        }

        val updatedSongIds = songs.map { it.id }
        updatedSongIds.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
            deleteCrossRefsBySongIds(chunk)
        }
        crossRefs.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
            insertSongArtistCrossRefs(chunk)
        }

        deleteOrphanedAlbums()
        deleteOrphanedArtists()
        refreshAlbumSongCounts()
        refreshArtistTrackCounts()
        refreshLocalFavoriteCache()
    }

    @Query("SELECT DISTINCT parent_directory_path FROM songs")
    suspend fun getDistinctParentDirectories(): List<String>

    /**
     * Reactive variant of [getDistinctParentDirectories]. Re-emits whenever the songs
     * table changes (e.g. after a sync adds songs in new folders), so cached directory
     * filters stay consistent with the actual library instead of freezing at an early,
     * possibly-empty snapshot taken before the first sync completes.
     */
    @Query("SELECT DISTINCT parent_directory_path FROM songs")
    fun getDistinctParentDirectoriesFlow(): Flow<List<String>>

    @Query("SELECT " + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        ORDER BY title ASC
    """)
    fun getSongs(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("SELECT " + SONG_LIST_PROJECTION + " FROM songs WHERE id IN (:songIds)")
    suspend fun getSongsByIdsListSimple(songIds: List<Long>): List<SongEntity>

    /**
     * Resolves the unified-table song id for a given content URI. Used when the
     * currently-playing song was loaded from a non-unified source and we need the
     * matching negative-Long id to position the song inside the library list.
     */
    @Query("SELECT id FROM songs WHERE content_uri_string = :contentUri LIMIT 1")
    suspend fun getSongIdByContentUri(contentUri: String): Long?

    @Query(
        "SELECT " + SONG_DETAIL_PROJECTION + """
        FROM songs
        LEFT JOIN lyrics AS song_lyrics ON song_lyrics.songId = songs.id
        WHERE songs.id = :songId
        """
    )
    fun getSongById(songId: Long): Flow<SongEntity?>

    @Query(
        "SELECT " + SONG_DETAIL_PROJECTION + """
        FROM songs
        LEFT JOIN lyrics AS song_lyrics ON song_lyrics.songId = songs.id
        WHERE songs.id = :songId
        """
    )
    suspend fun getSongByIdOnce(songId: Long): SongEntity?

    @Query(
        "SELECT " + SONG_DETAIL_PROJECTION + """
        FROM songs
        LEFT JOIN lyrics AS song_lyrics ON song_lyrics.songId = songs.id
        WHERE songs.file_path = :path
        LIMIT 1
        """
    )
    suspend fun getSongByPath(path: String): SongEntity?

    @Query("SELECT " + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE id IN (:songIds)
        AND (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
    """)
    fun getSongsByIds(
        songIds: List<Long>,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE album_id = :albumId ORDER BY disc_number ASC, track_number ASC")
    fun getSongsByAlbumId(albumId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE artist_id = :artistId ORDER BY title ASC")
    fun getSongsByArtistId(artistId: Long): Flow<List<SongEntity>>

    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN songs_fts ON songs_fts.rowid = songs.id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND songs_fts MATCH :matchQuery
        ORDER BY songs.title ASC
    """)
    fun searchSongsMatch(
        matchQuery: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (title LIKE '%' || :query || '%' OR artist_name LIKE '%' || :query || '%')
        ORDER BY title ASC
    """)
    fun searchSongsLike(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    fun searchSongs(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>> {
        val ftsFlow = searchSongsMatch(
            matchQuery = buildSongSearchMatchQuery(query),
            allowedParentDirs = allowedParentDirs,
            applyDirectoryFilter = applyDirectoryFilter
        )
        val likeFlow = searchSongsLike(
            query = query.trim(),
            allowedParentDirs = allowedParentDirs,
            applyDirectoryFilter = applyDirectoryFilter
        )
        return ftsFlow.combine(likeFlow) { ftsResults, likeResults ->
            val seen = LinkedHashMap<Long, SongEntity>(ftsResults.size + likeResults.size)
            ftsResults.forEach { seen.putIfAbsent(it.id, it) }
            likeResults.forEach { seen.putIfAbsent(it.id, it) }
            seen.values.toList()
        }
    }

    @Query("SELECT COUNT(*) FROM songs")
    fun getSongCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongCountOnce(): Int

    @Query("""
        SELECT
            file_path AS filePath,
            content_uri_string AS contentUriString,
            mime_type AS mimeType,
            duration,
            bitrate,
            sample_rate AS sampleRate,
            source_type AS sourceType
        FROM songs
    """)
    suspend fun getDeviceCapabilitySongRows(): List<DeviceCapabilitySongRow>

    /**
     * Single-pass audio aggregates for the diagnostic performance report.
     * Hi-res thresholds: > 48 kHz = hi-res, >= 176.4 kHz = ultra-hi-res.
     * Estimated bytes = bitrate(bps) * duration(ms) / 8000.
     */
    @Query("""
        SELECT
            COUNT(*) AS totalCount,
            COALESCE(SUM(CASE WHEN sample_rate > 48000 THEN 1 ELSE 0 END), 0) AS hiResCount,
            COALESCE(SUM(CASE WHEN sample_rate >= 176400 THEN 1 ELSE 0 END), 0) AS ultraHiResCount,
            COALESCE(SUM(CASE
                WHEN sample_rate > 48000
                    OR mime_type LIKE '%flac%'
                    OR mime_type LIKE '%alac%'
                    OR mime_type LIKE '%wav%'
                    OR mime_type LIKE '%aiff%'
                    OR mime_type LIKE '%ape%'
                THEN 1 ELSE 0 END), 0) AS likelyExpensiveCount,
            MAX(bitrate) AS maxBitrate,
            MIN(NULLIF(sample_rate, 0)) AS minSampleRate,
            MAX(sample_rate) AS maxSampleRate,
            MIN(CASE WHEN bitrate > 0 AND duration > 0 THEN bitrate * duration / 8000 END) AS estMinBytes,
            AVG(CASE WHEN bitrate > 0 AND duration > 0 THEN bitrate * duration / 8000 END) AS estAvgBytes,
            MAX(CASE WHEN bitrate > 0 AND duration > 0 THEN bitrate * duration / 8000 END) AS estMaxBytes
        FROM songs
    """)
    suspend fun getLibraryAudioStats(): LibraryAudioStatsRow

    /** Per-MIME song counts for the diagnostic performance report. */
    @Query("SELECT mime_type AS mimeType, COUNT(*) AS count FROM songs GROUP BY mime_type ORDER BY count DESC")
    suspend fun getMimeTypeCounts(): List<MimeTypeCountRow>

    @Query("""
        SELECT """ + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        ORDER BY title COLLATE NOCASE ASC, artist_name COLLATE NOCASE ASC, id ASC
        LIMIT 1
    """)
    suspend fun getFirstPlayableSong(
        allowedParentDirs: List<String> = emptyList(),
        applyDirectoryFilter: Boolean = false
    ): SongEntity?

    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
    """)
    fun getAllSongs(
        allowedParentDirs: List<String> = emptyList(),
        applyDirectoryFilter: Boolean = false
    ): Flow<List<SongEntity>>

    @Query("""
        SELECT """ + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE id IN (
            SELECT MIN(id)
            FROM songs
            WHERE album_art_uri_string IS NOT NULL
            AND album_art_uri_string != ''
            AND (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
            GROUP BY album_art_uri_string
        )
        ORDER BY title COLLATE NOCASE ASC, artist_name COLLATE NOCASE ASC, id ASC
    """)
    fun getDistinctAlbumArtSongs(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("""
        SELECT """ + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        ORDER BY date_added DESC, id DESC
        LIMIT :limit
    """)
    fun getHomeMixPreviewSongs(
        limit: Int,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("""
        SELECT id, parent_directory_path, title, album_art_uri_string FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND source_type = 0
            )
            OR (
                :filterMode = 2
                AND source_type != 0
            )
        )
        ORDER BY parent_directory_path ASC, title ASC
    """)
    fun getFolderSongs(
        allowedParentDirs: List<String> = emptyList(),
        applyDirectoryFilter: Boolean = false,
        filterMode: Int
    ): Flow<List<FolderSongRow>>

    @Query("""
        SELECT id FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND source_type = 0
            )
            OR (
                :filterMode = 2
                AND source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'song_default_order' THEN track_number END ASC,
            CASE WHEN :sortOrder = 'song_title_az' THEN title END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'song_title_za' THEN title END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'song_artist' THEN artist_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'song_artist_desc' THEN artist_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'song_album' THEN album_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'song_album_desc' THEN album_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'song_date_added' THEN date_added END DESC,
            CASE WHEN :sortOrder = 'song_date_added_asc' THEN date_added END ASC,
            CASE WHEN :sortOrder = 'song_duration' THEN duration END DESC,
            CASE WHEN :sortOrder = 'song_duration_asc' THEN duration END ASC,
            title COLLATE NOCASE ASC,
            id ASC
    """)
    suspend fun getSongIdsSorted(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int
    ): List<Long>

    @Query("""
        SELECT songs.id FROM songs
        INNER JOIN favorites ON songs.id = favorites.songId AND favorites.isFavorite = 1
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'liked_title_az' THEN songs.title END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'liked_title_za' THEN songs.title END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'liked_artist' THEN songs.artist_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'liked_artist_desc' THEN songs.artist_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'liked_album' THEN songs.album_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'liked_album_desc' THEN songs.album_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'liked_date_liked' THEN favorites.timestamp END DESC,
            CASE WHEN :sortOrder = 'liked_date_liked_asc' THEN favorites.timestamp END ASC,
            songs.title COLLATE NOCASE ASC,
            songs.id ASC
    """)
    suspend fun getFavoriteSongIdsSorted(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int
    ): List<Long>

    /**
     * Returns a PagingSource for songs, enabling efficient pagination for large libraries.
     * Room auto-generates the PagingSource implementation.
     */
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND source_type = 0
            )
            OR (
                :filterMode = 2
                AND source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'song_default_order' THEN track_number END ASC,
            CASE WHEN :sortOrder = 'song_title_az' THEN title END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'song_title_za' THEN title END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'song_artist' THEN artist_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'song_artist_desc' THEN artist_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'song_album' THEN album_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'song_album_desc' THEN album_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'song_date_added' THEN date_added END DESC,
            CASE WHEN :sortOrder = 'song_date_added_asc' THEN date_added END ASC,
            CASE WHEN :sortOrder = 'song_duration' THEN duration END DESC,
            CASE WHEN :sortOrder = 'song_duration_asc' THEN duration END ASC,

            -- Secondary sort falls back to title for consistency (case-insensitive)
            title COLLATE NOCASE ASC,
            id ASC
    """)
    fun getSongsPaginated(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int
    ): PagingSource<Int, SongEntity>

    @Query("""
        SELECT """ + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND source_type = 0
            )
            OR (
                :filterMode = 2
                AND source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'song_default_order' THEN track_number END ASC,
            CASE WHEN :sortOrder = 'song_title_az' THEN title END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'song_title_za' THEN title END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'song_artist' THEN artist_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'song_artist_desc' THEN artist_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'song_album' THEN album_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'song_album_desc' THEN album_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'song_date_added' THEN date_added END DESC,
            CASE WHEN :sortOrder = 'song_date_added_asc' THEN date_added END ASC,
            CASE WHEN :sortOrder = 'song_duration' THEN duration END DESC,
            CASE WHEN :sortOrder = 'song_duration_asc' THEN duration END ASC,
            title COLLATE NOCASE ASC,
            id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getSongsPage(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int,
        limit: Int,
        offset: Int
    ): List<SongEntity>

    /**
     * Returns a PagingSource for favorite songs, enabling efficient pagination.
     * Joins songs with favorites table and supports multi-sort.
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN favorites ON songs.id = favorites.songId AND favorites.isFavorite = 1
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'liked_title_az' THEN songs.title END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'liked_title_za' THEN songs.title END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'liked_artist' THEN songs.artist_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'liked_artist_desc' THEN songs.artist_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'liked_album' THEN songs.album_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'liked_album_desc' THEN songs.album_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'liked_date_liked' THEN favorites.timestamp END DESC,
            CASE WHEN :sortOrder = 'liked_date_liked_asc' THEN favorites.timestamp END ASC,
            songs.title COLLATE NOCASE ASC,
            songs.id ASC
    """)
    fun getFavoriteSongsPaginated(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int
    ): PagingSource<Int, SongEntity>

    /**
     * Returns all favorite songs as a list (for playback queue when shuffling).
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN favorites ON songs.id = favorites.songId AND favorites.isFavorite = 1
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        ORDER BY songs.title COLLATE NOCASE ASC
    """)
    suspend fun getFavoriteSongsList(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int
    ): List<SongEntity>

    @Query("""
        SELECT """ + SONG_LIST_PROJECTION + """
        FROM songs
        INNER JOIN favorites ON songs.id = favorites.songId AND favorites.isFavorite = 1
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'liked_title_az' THEN songs.title END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'liked_title_za' THEN songs.title END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'liked_artist' THEN songs.artist_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'liked_artist_desc' THEN songs.artist_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'liked_album' THEN songs.album_name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'liked_album_desc' THEN songs.album_name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'liked_date_liked' THEN favorites.timestamp END DESC,
            CASE WHEN :sortOrder = 'liked_date_liked_asc' THEN favorites.timestamp END ASC,
            songs.title COLLATE NOCASE ASC,
            songs.id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getFavoriteSongsPage(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int,
        limit: Int,
        offset: Int
    ): List<SongEntity>

    /**
     * Returns the count of favorite songs (reactive).
     */
    @Query("""
        SELECT COUNT(*) FROM songs
        INNER JOIN favorites ON songs.id = favorites.songId AND favorites.isFavorite = 1
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
    """)
    fun getFavoriteSongCount(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int
    ): Flow<Int>

    /**
     * Returns a PagingSource for search results, enabling efficient pagination for large result sets.
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN songs_fts ON songs_fts.rowid = songs.id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND songs_fts MATCH :matchQuery
        ORDER BY songs.title ASC
    """)
    fun searchSongsPaginatedMatch(
        matchQuery: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): PagingSource<Int, SongEntity>

    fun searchSongsPaginated(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): PagingSource<Int, SongEntity> = searchSongsPaginatedMatch(
        matchQuery = buildSongSearchMatchQuery(query),
        allowedParentDirs = allowedParentDirs,
        applyDirectoryFilter = applyDirectoryFilter
    )

    /**
     * Search songs with a result limit for non-paginated contexts (FTS).
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN songs_fts ON songs_fts.rowid = songs.id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND songs_fts MATCH :matchQuery
        ORDER BY songs.title ASC
        LIMIT :limit
    """)
    fun searchSongsLimitedMatch(
        matchQuery: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        limit: Int
    ): Flow<List<SongEntity>>

    /**
     * LIKE-based search focusing only on titles.
     */
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND title LIKE '%' || :query || '%'
        ORDER BY title ASC
        LIMIT :limit
    """)
    fun searchSongsLimitedByTitleLike(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        limit: Int
    ): Flow<List<SongEntity>>

    /**
     * LIKE-based fallback search for songs that FTS tokenization may miss.
     */
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (title LIKE '%' || :query || '%' OR artist_name LIKE '%' || :query || '%')
        ORDER BY title ASC
        LIMIT :limit
    """)
    fun searchSongsLimitedLike(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        limit: Int
    ): Flow<List<SongEntity>>

    fun searchSongsLimited(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        limit: Int,
        titleOnly: Boolean = false
    ): Flow<List<SongEntity>> {
        val ftsFlow = searchSongsLimitedMatch(
            matchQuery = if (titleOnly) buildSongTitleSearchMatchQuery(query) else buildSongSearchMatchQuery(query),
            allowedParentDirs = allowedParentDirs,
            applyDirectoryFilter = applyDirectoryFilter,
            limit = limit
        )
        val likeFlow = if (titleOnly) {
            searchSongsLimitedByTitleLike(
                query = query.trim(),
                allowedParentDirs = allowedParentDirs,
                applyDirectoryFilter = applyDirectoryFilter,
                limit = limit
            )
        } else {
            searchSongsLimitedLike(
                query = query.trim(),
                allowedParentDirs = allowedParentDirs,
                applyDirectoryFilter = applyDirectoryFilter,
                limit = limit
            )
        }
        // The FTS index is a fast path, not the only path. `songs_fts` is created in the database's
        // onCreate callback and is not repaired by any migration, so an install that predates the
        // index (or a restored database) throws here on every keystroke. Without this catch the
        // throw propagated out of `combine`, out of the repository's own `combine`, and into
        // SearchStateHolder's catch-all — which published an empty list. The user saw "no results"
        // for a query the LIKE branch had already matched.
        val ftsOrEmpty = ftsFlow.catch { error ->
            Timber.e(error, "Song search: FTS branch failed; continuing with LIKE only")
            emit(emptyList())
        }
        return ftsOrEmpty.combine(likeFlow) { ftsResults, likeResults ->
            val seen = LinkedHashMap<Long, SongEntity>(ftsResults.size + likeResults.size)
            ftsResults.forEach { seen.putIfAbsent(it.id, it) }
            likeResults.forEach { seen.putIfAbsent(it.id, it) }
            seen.values.toList().take(limit)
        }
    }

    /**
     * Returns a PagingSource for songs in a specific genre.
     */
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR parent_directory_path IN (:allowedParentDirs))
        AND genre LIKE :genreName
        ORDER BY title ASC
    """)
    fun getSongsByGenrePaginated(
        genreName: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): PagingSource<Int, SongEntity>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
    albums.grouping_key AS grouping_key,
    albums.is_compilation AS is_compilation,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM songs
        INNER JOIN albums ON albums.id = songs.album_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year,
            albums.grouping_key,
            albums.is_compilation
        HAVING COUNT(songs.id) >= :minTracks
        ORDER BY albums.title ASC
    """)
    fun getAlbums(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int,
        minTracks: Int
    ): Flow<List<AlbumEntity>>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
    albums.grouping_key AS grouping_key,
    albums.is_compilation AS is_compilation,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM songs
        INNER JOIN albums ON albums.id = songs.album_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year,
            albums.grouping_key,
            albums.is_compilation
        HAVING COUNT(songs.id) >= :minTracks
        ORDER BY
            CASE WHEN :sortOrder = 'album_title_az' THEN albums.title END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'album_title_za' THEN albums.title END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'album_artist' THEN COALESCE(NULLIF(TRIM(albums.album_artist), ''), albums.artist_name) END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'album_artist_desc' THEN COALESCE(NULLIF(TRIM(albums.album_artist), ''), albums.artist_name) END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'album_release_year' THEN albums.year END DESC,
            CASE WHEN :sortOrder = 'album_release_year_asc' THEN albums.year END ASC,
            CASE WHEN :sortOrder = 'album_date_added' THEN albums.date_added END DESC,
            CASE WHEN :sortOrder = 'album_size_asc' THEN song_count END ASC,
            CASE WHEN :sortOrder = 'album_size_desc' THEN song_count END DESC,
            albums.title COLLATE NOCASE ASC,
            albums.artist_name COLLATE NOCASE ASC,
            albums.id ASC
    """)
    fun getAlbumsPaginated(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int,
        sortOrder: String,
        minTracks: Int
    ): PagingSource<Int, AlbumEntity>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
    albums.grouping_key AS grouping_key,
    albums.is_compilation AS is_compilation,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM songs
        INNER JOIN albums ON albums.id = songs.album_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year,
            albums.grouping_key,
            albums.is_compilation
        HAVING COUNT(songs.id) >= :minTracks
        ORDER BY
            CASE WHEN :sortOrder = 'album_title_az' THEN albums.title END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'album_title_za' THEN albums.title END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'album_artist' THEN COALESCE(NULLIF(TRIM(albums.album_artist), ''), albums.artist_name) END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'album_artist_desc' THEN COALESCE(NULLIF(TRIM(albums.album_artist), ''), albums.artist_name) END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'album_release_year' THEN albums.year END DESC,
            CASE WHEN :sortOrder = 'album_release_year_asc' THEN albums.year END ASC,
            CASE WHEN :sortOrder = 'album_date_added' THEN albums.date_added END DESC,
            CASE WHEN :sortOrder = 'album_size_asc' THEN song_count END ASC,
            CASE WHEN :sortOrder = 'album_size_desc' THEN song_count END DESC,
            albums.title COLLATE NOCASE ASC,
            albums.artist_name COLLATE NOCASE ASC,
            albums.id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getAlbumsPage(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int,
        sortOrder: String,
        minTracks: Int,
        limit: Int,
        offset: Int
    ): List<AlbumEntity>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
    albums.grouping_key AS grouping_key,
    albums.is_compilation AS is_compilation,
            (
                SELECT COUNT(*)
                FROM songs
                WHERE songs.album_id = albums.id
            ) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM albums
        WHERE albums.id = :albumId
        LIMIT 1
    """)
    fun getAlbumById(albumId: Long): Flow<AlbumEntity?>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
    albums.grouping_key AS grouping_key,
    albums.is_compilation AS is_compilation,
            (
                SELECT COUNT(*)
                FROM songs
                WHERE songs.album_id = albums.id
            ) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM albums
        WHERE albums.title LIKE '%' || :query || '%'
        AND song_count >= :minTracks
        ORDER BY albums.title ASC
    """)
    fun searchAlbums(query: String, minTracks: Int = 1): Flow<List<AlbumEntity>>

    @Query("SELECT COUNT(*) FROM albums")
    fun getAlbumCount(): Flow<Int>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
    albums.grouping_key AS grouping_key,
    albums.is_compilation AS is_compilation,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM songs
        INNER JOIN albums ON albums.id = songs.album_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year,
            albums.grouping_key,
            albums.is_compilation
        HAVING COUNT(songs.id) >= :minTracks
        ORDER BY albums.title ASC
    """)
    suspend fun getAllAlbumsList(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        minTracks: Int
    ): List<AlbumEntity>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
    albums.grouping_key AS grouping_key,
    albums.is_compilation AS is_compilation,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM albums
        LEFT JOIN songs ON albums.id = songs.album_id
        WHERE albums.artist_id = :artistId
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year,
            albums.grouping_key,
            albums.is_compilation
        ORDER BY albums.title ASC
    """)
    fun getAlbumsByArtistId(artistId: Long): Flow<List<AlbumEntity>>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
    albums.grouping_key AS grouping_key,
    albums.is_compilation AS is_compilation,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM songs
        INNER JOIN albums ON albums.id = songs.album_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (albums.title LIKE '%' || :query || '%' OR albums.artist_name LIKE '%' || :query || '%')
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year,
            albums.grouping_key,
            albums.is_compilation
        HAVING COUNT(songs.id) >= :minTracks
        ORDER BY albums.title ASC
    """)
    fun searchAlbums(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        minTracks: Int
    ): Flow<List<AlbumEntity>>

    @Query("""
        SELECT DISTINCT artists.* FROM artists
        INNER JOIN songs ON artists.id = songs.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        ORDER BY artists.name ASC
    """)
    fun getArtists(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<ArtistEntity>>

    @Query("""
        SELECT artists.id, artists.name, artists.image_url, artists.custom_image_uri,
               COUNT(DISTINCT songs.id) AS track_count
        FROM songs
        INNER JOIN song_artist_cross_ref ON song_artist_cross_ref.song_id = songs.id
        INNER JOIN artists ON artists.id = song_artist_cross_ref.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY artists.id
        ORDER BY
            CASE WHEN :sortOrder = 'artist_name_az' THEN artists.name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'artist_name_za' THEN artists.name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'artist_num_songs_desc' THEN track_count END DESC,
            CASE WHEN :sortOrder = 'artist_num_songs_asc' THEN track_count END ASC,
            artists.name COLLATE NOCASE ASC,
            artists.id ASC
    """)
    fun getArtistsPaginated(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int,
        sortOrder: String
    ): PagingSource<Int, ArtistEntity>

    /**
     * Album-artist variant of [getArtistsPaginated], used when "Group by Album Artist" is on.
     * Collapses the Artists tab onto each song's effective album artist (songs.album_artist_id)
     * instead of every track-level artist, so featured/compilation artists stop cluttering the
     * list. Counts are per-album-artist; sorting and source/directory filtering mirror the
     * track-artist query so toggling the preference only swaps which query backs the tab.
     */
    @Query("""
        SELECT artists.id, artists.name, artists.image_url, artists.custom_image_uri,
               COUNT(DISTINCT songs.id) AS track_count
        FROM songs
        INNER JOIN artists ON artists.id = songs.album_artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY artists.id
        ORDER BY
            CASE WHEN :sortOrder = 'artist_name_az' THEN artists.name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'artist_name_za' THEN artists.name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'artist_num_songs_desc' THEN track_count END DESC,
            CASE WHEN :sortOrder = 'artist_num_songs_asc' THEN track_count END ASC,
            artists.name COLLATE NOCASE ASC,
            artists.id ASC
    """)
    fun getArtistsPaginatedByAlbumArtist(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int,
        sortOrder: String
    ): PagingSource<Int, ArtistEntity>

    @Query("""
        SELECT artists.id, artists.name, artists.image_url, artists.custom_image_uri,
               COUNT(DISTINCT songs.id) AS track_count
        FROM songs
        INNER JOIN song_artist_cross_ref ON song_artist_cross_ref.song_id = songs.id
        INNER JOIN artists ON artists.id = song_artist_cross_ref.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY artists.id
        ORDER BY
            CASE WHEN :sortOrder = 'artist_name_az' THEN artists.name END COLLATE NOCASE ASC,
            CASE WHEN :sortOrder = 'artist_name_za' THEN artists.name END COLLATE NOCASE DESC,
            CASE WHEN :sortOrder = 'artist_num_songs_desc' THEN track_count END DESC,
            CASE WHEN :sortOrder = 'artist_num_songs_asc' THEN track_count END ASC,
            artists.name COLLATE NOCASE ASC,
            artists.id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getArtistsPage(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int,
        sortOrder: String,
        limit: Int,
        offset: Int
    ): List<ArtistEntity>

    /**
     * Unfiltered list of all artists (including those only reachable via cross-refs).
     */
    @Query("SELECT * FROM artists ORDER BY name ASC")
    fun getAllArtistsRaw(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :artistId")
    fun getArtistById(artistId: Long): Flow<ArtistEntity?>

    @Query("SELECT * FROM artists WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchArtists(query: String): Flow<List<ArtistEntity>>

    @Query("SELECT COUNT(*) FROM artists")
    fun getArtistCount(): Flow<Int>

    @Query("""
        SELECT DISTINCT artists.* FROM artists
        INNER JOIN songs ON artists.id = songs.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        ORDER BY artists.name ASC
    """)
    suspend fun getAllArtistsList(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): List<ArtistEntity>

    /**
     * Unfiltered list of all artists (one-shot).
     */
    @Query("SELECT * FROM artists ORDER BY name ASC")
    suspend fun getAllArtistsListRaw(): List<ArtistEntity>

    @Query("""
        SELECT artists.id, artists.name, artists.image_url, artists.custom_image_uri,
               COUNT(DISTINCT songs.id) AS track_count
        FROM songs
        INNER JOIN song_artist_cross_ref ON song_artist_cross_ref.song_id = songs.id
        INNER JOIN artists ON artists.id = song_artist_cross_ref.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND artists.name LIKE '%' || :query || '%'
        GROUP BY artists.id
        ORDER BY artists.name ASC
    """)
    fun searchArtists(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<ArtistEntity>>

    @Query("SELECT image_url FROM artists WHERE id = :artistId")
    suspend fun getArtistImageUrl(artistId: Long): String?

    @Query("SELECT image_url FROM artists WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun getArtistImageUrlByNormalizedName(name: String): String?

    @Query("UPDATE artists SET image_url = :imageUrl WHERE id = :artistId")
    suspend fun updateArtistImageUrl(artistId: Long, imageUrl: String)

    @Query("SELECT id FROM artists WHERE name = :name LIMIT 1")
    suspend fun getArtistIdByName(name: String): Long?

    @Query("SELECT id FROM artists WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun getArtistIdByNormalizedName(name: String): Long?

    @Query("SELECT MAX(id) FROM artists")
    suspend fun getMaxArtistId(): Long?

    @Query("UPDATE artists SET custom_image_uri = :uri WHERE id = :artistId")
    suspend fun updateArtistCustomImage(artistId: Long, uri: String?)

    @Query("SELECT custom_image_uri FROM artists WHERE id = :artistId")
    suspend fun getArtistCustomImage(artistId: Long): String?

    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND genre LIKE :genreName
        ORDER BY title ASC
    """)
    fun getSongsByGenre(
        genreName: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    // Multi-genre aware query: matches songs where the genre column equals the name (case-
    // insensitively, via LIKE), or contains it as part of a comma-separated list.
    // SQLite LIKE is case-insensitive for ASCII letters by default, which is sufficient
    // for genre names. All six arms use LIKE so that "rock" matches "Rock", "Rock,Pop",
    // "Rock, Pop", "Pop,Rock", "Pop, Rock", and "Pop,Rock,Jazz".
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (
            genre LIKE :genreName
            OR genre LIKE :genrePrefix
            OR genre LIKE :genreSuffixWithSpace
            OR genre LIKE :genreSuffix
            OR genre LIKE :genreMiddleWithSpace
            OR genre LIKE :genreMiddle
        )
        ORDER BY title ASC
    """)
    fun getSongsByGenreContaining(
        genreName: String,
        genrePrefix: String,
        genreSuffixWithSpace: String,
        genreSuffix: String,
        genreMiddleWithSpace: String,
        genreMiddle: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (genre IS NULL OR genre = '')
        ORDER BY title ASC
    """)
    fun getSongsWithNullGenre(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("SELECT DISTINCT genre FROM songs WHERE genre IS NOT NULL AND genre != '' ORDER BY genre ASC")
    fun getUniqueGenres(): Flow<List<String>>

    @Query("""
        SELECT DISTINCT genre FROM songs
        WHERE genre IS NOT NULL AND genre != ''
        AND (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        ORDER BY genre ASC
    """)
    fun getUniqueGenres(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<String>>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM songs
            WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
            AND (genre IS NULL OR genre = '')
        )
    """)
    fun hasUnknownGenre(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<Boolean>

    @Query("SELECT DISTINCT album_art_uri_string FROM songs WHERE album_art_uri_string IS NOT NULL")
    fun getAllUniqueAlbumArtUrisFromSongs(): Flow<List<String>>

    @Query("DELETE FROM albums WHERE NOT EXISTS (SELECT 1 FROM songs WHERE songs.album_id = albums.id)")
    suspend fun deleteOrphanedAlbums()

    /**
     * An artist is only orphaned when nothing references it: not the cross-ref table, not
     * songs.artist_id, and not songs.album_artist_id. The songs.artist_id check is load-bearing
     * — the songs FK is declared ON DELETE SET NULL but the column is NOT NULL, so deleting an
     * artist a song still points at would abort with a constraint error instead of nulling. The
     * album_artist_id check keeps album-artist-only rows (e.g. "Various Artists", which never
     * appear as a track artist and so have no cross-ref) alive for the "Group by Album Artist" tab.
     */
    @Query("""
        DELETE FROM artists
        WHERE NOT EXISTS (SELECT 1 FROM song_artist_cross_ref WHERE song_artist_cross_ref.artist_id = artists.id)
          AND NOT EXISTS (SELECT 1 FROM songs WHERE songs.artist_id = artists.id)
          AND NOT EXISTS (SELECT 1 FROM songs WHERE songs.album_artist_id = artists.id)
    """)
    suspend fun deleteOrphanedArtists()

    @Query("UPDATE songs SET is_favorite = :isFavorite WHERE id = :songId")
    suspend fun setFavoriteStatus(songId: Long, isFavorite: Boolean)

    /**
     * Re-derives the `songs.is_favorite` cache for local songs from the `favorites` table, by locator
     * or by the durable identity. The favourites triggers only fire on writes to `favorites`, and the
     * scanner always writes `isFavorite = false`, so a rebuild or an incremental rewrite of an
     * existing song otherwise cleared the cache while the favourite survived (R3-1, AUD-109). Only
     * rows whose cached value is wrong are written.
     */
    @Query("""
        UPDATE songs SET is_favorite = NOT is_favorite
        WHERE source_type = 0 AND is_favorite != EXISTS(
            SELECT 1 FROM favorites f
            WHERE f.isFavorite = 1
              AND (f.songId = songs.id OR (songs.library_item_id != '' AND f.library_item_id = songs.library_item_id))
        )
    """)
    suspend fun refreshLocalFavoriteCache()

    @Query("SELECT is_favorite FROM songs WHERE id = :songId")
    suspend fun getFavoriteStatus(songId: Long): Boolean?

    @Transaction
    suspend fun toggleFavoriteStatus(songId: Long): Boolean {
        val currentStatus = getFavoriteStatus(songId) ?: false
        val newStatus = !currentStatus
        setFavoriteStatus(songId, newStatus)
        return newStatus
    }

    @Query("""
        UPDATE songs
        SET title = :title,
            artist_name = :artist,
            artist_id = :artistId,
            artists_json = :artistsJson,
            album_name = :album,
            genre = :genre,
            track_number = :trackNumber,
            disc_number = :discNumber,
            title_user_edited = 1,
            artist_user_edited = 1,
            album_user_edited = 1,
            genre_user_edited = 1
        WHERE id = :songId
    """)
    suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        artistId: Long,
        artistsJson: String?,
        album: String,
        genre: String?,
        trackNumber: Int,
        discNumber: Int?
    )

    @Transaction
    suspend fun updateSongMetadataAndArtistLinks(
        songId: Long,
        title: String,
        artist: String,
        artistId: Long,
        artistsJson: String?,
        album: String,
        genre: String?,
        trackNumber: Int,
        discNumber: Int?,
        artistsToEnsure: List<ArtistEntity>,
        crossRefs: List<SongArtistCrossRef>
    ) {
        if (artistsToEnsure.isNotEmpty()) {
            insertArtistsIgnoreConflicts(artistsToEnsure)
        }

        updateSongMetadata(
            songId = songId,
            title = title,
            artist = artist,
            artistId = artistId,
            artistsJson = artistsJson,
            album = album,
            genre = genre,
            trackNumber = trackNumber,
            discNumber = discNumber
        )

        deleteCrossRefsForSong(songId)
        if (crossRefs.isNotEmpty()) {
            crossRefs.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
                insertSongArtistCrossRefs(chunk)
            }
        }

        deleteOrphanedArtists()
        refreshArtistTrackCounts()
    }

    @Query("UPDATE songs SET album_art_uri_string = :albumArtUri WHERE id = :songId")
    suspend fun updateSongAlbumArt(songId: Long, albumArtUri: String?)

    // --- B11-fix FIX-3: library repair -----------------------------------------------------------
    //
    // Only these columns, only these rows. Nothing here reads or writes title, artist, album, genre or
    // any `*_user_edited` flag, and nothing deletes: the library holds the listener's favourites,
    // lyrics and play history, and a repair that could lose them is worse than the defect.

    /**
     * Unpacks a track number that was stored as MediaStore's packed `disc * 1000 + track`
     * (`track_number = 1012` for disc 1, track 12; 4869 of 4870 songs on the owner's device).
     *
     * One statement, so it is atomic. **Idempotent:** it selects only `track_number >= 1000`, and the
     * result is always below 1000, so a second run matches nothing. A stored disc is kept; the thousands
     * become the disc only when there is none. The same rule as
     * `MetadataNormalizer.unpackStoredPosition`. It is deliberately applied to user-edited rows too:
     * nobody types track 1012, it is the packed value the editor copied from the row, and leaving it
     * is what showed "1-1012". A sane user-edited track (below 1000) is never touched.
     *
     * @return the number of songs repaired.
     */
    @Query(
        """
        UPDATE songs
        SET disc_number = CASE WHEN COALESCE(disc_number, 0) > 0 THEN disc_number ELSE track_number / 1000 END,
            track_number = track_number % 1000
        WHERE track_number >= 1000
        """
    )
    suspend fun repairPackedTrackNumbers(): Int

    /** Songs with no year, in id order after [afterId]: one page of the year repair. */
    @Query(
        """
        SELECT id, file_path AS filePath, album_id AS albumId, media_store_date_modified AS dateModified
        FROM songs
        WHERE year <= 0 AND id > :afterId
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun songsWithoutYear(afterId: Long, limit: Int): List<YearRepairCandidate>

    /** Sets a year only where there is none, so a year already stored is never overwritten. */
    @Query("UPDATE songs SET year = :year WHERE id = :songId AND year <= 0")
    suspend fun fillYear(songId: Long, year: Int): Int

    /**
     * An album's year is the most common valid year among its songs, the earliest among equals (the
     * same rule as `MetadataNormalizer.albumYear`).
     */
    @Query(
        """
        UPDATE albums
        SET year = COALESCE(
            (SELECT s.year FROM songs s WHERE s.album_id = albums.id AND s.year > 0
             GROUP BY s.year ORDER BY COUNT(*) DESC, s.year ASC LIMIT 1), 0)
        WHERE id IN (:albumIds)
        """
    )
    suspend fun refreshAlbumYears(albumIds: List<Long>)

    /** Albums that have no year although a song on them does (the year used to be the first track's). */
    @Query(
        """
        UPDATE albums
        SET year = COALESCE(
            (SELECT s.year FROM songs s WHERE s.album_id = albums.id AND s.year > 0
             GROUP BY s.year ORDER BY COUNT(*) DESC, s.year ASC LIMIT 1), 0)
        WHERE year <= 0 AND EXISTS (SELECT 1 FROM songs s WHERE s.album_id = albums.id AND s.year > 0)
        """
    )
    suspend fun refreshZeroAlbumYears(): Int

    /** One page of years and the albums they touch, together: a page is applied whole or not at all. */
    @Transaction
    suspend fun applyYearPage(years: List<Pair<Long, Int>>, albumIds: Collection<Long>): Int {
        var filled = 0
        years.forEach { (songId, year) -> filled += fillYear(songId, year) }
        albumIds.distinct().chunked(400).forEach { refreshAlbumYears(it) }
        return filled
    }

    /**
     * Stores canonical MusicBrainz identifiers and fills only metadata that is currently absent.
     * Existing user/library names are deliberately preserved; choosing a match must not silently
     * regroup albums or artists in the local library.
     */
    @Query(
        """
        UPDATE songs
        SET title = CASE
                WHEN TRIM(title) = '' OR LOWER(title) = 'unknown title' THEN :title
                ELSE title
            END,
            artist_name = CASE
                WHEN TRIM(artist_name) = '' OR LOWER(artist_name) = 'unknown artist' THEN :artist
                ELSE artist_name
            END,
            album_name = CASE
                WHEN TRIM(album_name) = '' OR LOWER(album_name) = 'unknown album' THEN :album
                ELSE album_name
            END,
            year = CASE WHEN year <= 0 THEN :year ELSE year END,
            mb_recording_id = :recordingId,
            mb_release_id = :releaseId,
            mb_artist_id = :artistId
        WHERE id = :songId
        """
    )
    suspend fun applyMusicBrainzMatch(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        year: Int,
        recordingId: String,
        releaseId: String?,
        artistId: String?
    )

    /**
     * The stored values a MusicBrainz apply would compare against.
     *
     * Read before the write so the caller can report what actually changed. `applyMusicBrainzMatch`
     * fills a text field only when it is currently blank or a placeholder, so "did this change
     * anything" cannot be answered by comparing the match to the incoming `Song` alone — the row is
     * the authority.
     */
    @Query(
        """
        SELECT title,
               artist_name AS artistName,
               album_name AS albumName,
               year,
               mb_recording_id AS mbRecordingId,
               mb_release_id AS mbReleaseId,
               mb_artist_id AS mbArtistId
        FROM songs WHERE id = :songId
        """
    )
    suspend fun musicBrainzCurrentState(songId: Long): MusicBrainzCurrentState?

    @Query("UPDATE songs SET lyrics = :lyrics WHERE id = :songId")
    suspend fun updateLyrics(songId: Long, lyrics: String)

    @Query("UPDATE songs SET lyrics = NULL WHERE id = :songId")
    suspend fun resetLyrics(songId: Long)

    @Query("UPDATE songs SET lyrics = NULL")
    suspend fun resetAllLyrics()

    @Query("SELECT " + SONG_LIST_PROJECTION + " FROM songs")
    suspend fun getAllSongsList(): List<SongEntity>

    @Query("SELECT id, title, artist_name, album_name, duration FROM songs WHERE source_type = 0")
    suspend fun getAllLocalSongSummaries(): List<SongSummary>

    @Query("SELECT album_art_uri_string FROM songs WHERE id=:id")
    suspend fun getAlbumArtUriById(id: Long) : String?

    @Query("DELETE FROM songs WHERE id=:id")
    suspend fun deleteById(id: Long)

    @Query("""
    SELECT mime_type AS mimeType,
           bitrate,
           sample_rate AS sampleRate
    FROM songs
    WHERE id = :id
    """)
    suspend fun getAudioMetadataById(id: Long): AudioMeta?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongArtistCrossRefs(crossRefs: List<SongArtistCrossRef>)

    @Query("SELECT * FROM song_artist_cross_ref")
    fun getAllSongArtistCrossRefs(): Flow<List<SongArtistCrossRef>>

    @Query("SELECT * FROM song_artist_cross_ref")
    suspend fun getAllSongArtistCrossRefsList(): List<SongArtistCrossRef>

    @Query("DELETE FROM song_artist_cross_ref")
    suspend fun clearAllSongArtistCrossRefs()

    @Query("DELETE FROM song_artist_cross_ref WHERE song_id = :songId")
    suspend fun deleteCrossRefsForSong(songId: Long)

    @Query("DELETE FROM song_artist_cross_ref WHERE artist_id = :artistId")
    suspend fun deleteCrossRefsForArtist(artistId: Long)

    /**
     * Get all artists for a specific song using the junction table.
     */
    @Query("""
        SELECT artists.* FROM artists
        INNER JOIN song_artist_cross_ref ON artists.id = song_artist_cross_ref.artist_id
        WHERE song_artist_cross_ref.song_id = :songId
        ORDER BY song_artist_cross_ref.is_primary DESC, artists.name ASC
    """)
    fun getArtistsForSong(songId: Long): Flow<List<ArtistEntity>>

    /**
     * Get all artists for a specific song (one-shot).
     */
    @Query("""
        SELECT artists.* FROM artists
        INNER JOIN song_artist_cross_ref ON artists.id = song_artist_cross_ref.artist_id
        WHERE song_artist_cross_ref.song_id = :songId
        ORDER BY song_artist_cross_ref.is_primary DESC, artists.name ASC
    """)
    suspend fun getArtistsForSongList(songId: Long): List<ArtistEntity>

    /**
     * Get all songs for a specific artist using the junction table.
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN song_artist_cross_ref ON songs.id = song_artist_cross_ref.song_id
        WHERE song_artist_cross_ref.artist_id = :artistId
        ORDER BY songs.title ASC
    """)
    fun getSongsForArtist(artistId: Long): Flow<List<SongEntity>>

    /**
     * Album-artist variant of [getSongsForArtist]: every song whose effective album artist is
     * [artistId]. Used by the artist detail screen when "Group by Album Artist" is on so the
     * detail view stays consistent with the collapsed Artists tab (tapping "Various Artists"
     * shows the compilation tracks rather than an empty screen).
     */
    @Query("""
        SELECT * FROM songs
        WHERE album_artist_id = :artistId
        ORDER BY title ASC
    """)
    fun getSongsForArtistByAlbumArtist(artistId: Long): Flow<List<SongEntity>>

    /**
     * Get all songs for a specific artist (one-shot).
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN song_artist_cross_ref ON songs.id = song_artist_cross_ref.song_id
        WHERE song_artist_cross_ref.artist_id = :artistId
        ORDER BY songs.title ASC
    """)
    suspend fun getSongsForArtistList(artistId: Long): List<SongEntity>

    /**
     * Get the cross-references for a specific song.
     */
    @Query("SELECT * FROM song_artist_cross_ref WHERE song_id = :songId")
    suspend fun getCrossRefsForSong(songId: Long): List<SongArtistCrossRef>

    /**
     * Get the primary artist for a song.
     */
    @Query("""
        SELECT artists.id AS artist_id, artists.name FROM artists
        INNER JOIN song_artist_cross_ref ON artists.id = song_artist_cross_ref.artist_id
        WHERE song_artist_cross_ref.song_id = :songId AND song_artist_cross_ref.is_primary = 1
        LIMIT 1
    """)
    suspend fun getPrimaryArtistForSong(songId: Long): PrimaryArtistInfo?

    /**
     * Get song count for an artist from the junction table.
     */
    @Query("SELECT COUNT(*) FROM song_artist_cross_ref WHERE artist_id = :artistId")
    suspend fun getSongCountForArtist(artistId: Long): Int

    /**
     * Get all artists with their song counts computed from the junction table.
     */
    @Query("""
        SELECT artists.id, artists.name, artists.image_url, artists.custom_image_uri,
               COUNT(DISTINCT song_artist_cross_ref.song_id) AS track_count
        FROM artists
        LEFT JOIN song_artist_cross_ref ON artists.id = song_artist_cross_ref.artist_id
        GROUP BY artists.id
        ORDER BY artists.name ASC
    """)
    fun getArtistsWithSongCounts(): Flow<List<ArtistEntity>>

    /**
     * Get all artists with song counts, filtered by allowed directories.
     */
    @Query("""
        SELECT artists.id, artists.name, artists.image_url, artists.custom_image_uri,
               COUNT(DISTINCT songs.id) AS track_count
        FROM songs
        INNER JOIN song_artist_cross_ref ON song_artist_cross_ref.song_id = songs.id
        INNER JOIN artists ON artists.id = song_artist_cross_ref.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY artists.id
        ORDER BY artists.name ASC
    """)
    fun getArtistsWithSongCountsFiltered(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int
    ): Flow<List<ArtistEntity>>

    /**
     * Clear all music data including cross-references.
     */
    @Transaction
    suspend fun clearAllMusicDataWithCrossRefs() {
        clearAllSongArtistCrossRefs()
        clearAllSongs()
        clearAllAlbums()
        clearAllArtists()
    }

    /**
     * Insert music data with cross-references in a single transaction.
     * Uses chunked inserts for cross-refs to avoid SQLite variable limits.
     */
    @Transaction
    suspend fun insertMusicDataWithCrossRefs(
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
        crossRefs: List<SongArtistCrossRef>
    ) {
        insertArtists(artists)
        insertAlbums(albums)
        insertSongs(songs)
        crossRefs.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
            insertSongArtistCrossRefs(chunk)
        }
    }

    @Transaction
    suspend fun rebuildLocalMusicDataWithCrossRefs(
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
        crossRefs: List<SongArtistCrossRef>
    ) {
        // Favourites and lyrics are not projections: they are user state owned by the durable
        // `library_item_id` (no foreign key to `songs`), so rebuilding the projections must not
        // delete them — least of all when the scan came back empty (AUD-009).
        deleteLocalSongArtistCrossRefs()
        clearLocalSongs()
        deleteOrphanedAlbums()
        deleteOrphanedArtists()

        insertArtists(artists)
        insertAlbums(albums)
        insertSongs(songs)
        crossRefs.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
            insertSongArtistCrossRefs(chunk)
        }
        refreshAlbumSongCounts()
        refreshArtistTrackCounts()
        refreshLocalFavoriteCache()
    }

    companion object {
        /**
         * SQLite has a limit on the number of variables per statement (default 999, higher in newer versions).
         * Each SongArtistCrossRef insert uses 3 variables (songId, artistId, isPrimary).
         * The batch size is calculated so that batchSize * 3 <= SQLITE_MAX_VARIABLE_NUMBER.
         */
        private const val SQLITE_MAX_VARIABLE_NUMBER = 999
        private const val CROSS_REF_FIELDS_PER_OBJECT = 3
        val CROSS_REF_BATCH_SIZE: Int = SQLITE_MAX_VARIABLE_NUMBER / CROSS_REF_FIELDS_PER_OBJECT

        /**
         * Batch size for song inserts during incremental sync.
         * Allows database reads to interleave with writes for better UX.
         */
        const val SONG_BATCH_SIZE = 500
    }
}
