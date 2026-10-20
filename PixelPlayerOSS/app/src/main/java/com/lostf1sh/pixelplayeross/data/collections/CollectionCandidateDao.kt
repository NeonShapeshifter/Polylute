package com.lostf1sh.pixelplayeross.data.collections

import androidx.room.Dao
import androidx.room.Query

/** One row of the bulk read the rule engine evaluates over. */
data class CollectionCandidateRow(
    val libraryItemId: String,
    val locator: Long,
    val title: String?,
    val trackArtist: String?,
    val albumArtist: String?,
    val album: String?,
    val genresJson: String?,
    val legacyGenre: String?,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int?,
    val releaseYear: Int,
    val bitrate: Int?,
    val sampleRate: Int?,
    val firstSeenAt: Long,
    val isFavourite: Boolean,
    val isCompilation: Boolean,
    val albumGroupingKey: String,
)

/**
 * The one read the rule engine performs.
 *
 * It reads the library and nothing about listening: plays, completions, skips and the dates of the last
 * ones come from the listening ledger (`SmartCollectionRepository.candidates`), so a rule and the Stats screen count
 * the same plays. The old join to `song_engagements` (counters no longer written since B28) is gone.
 *
 * ## Why a bulk read rather than compiled SQL
 *
 * A rule set is user-defined and arbitrarily combineable. Compiling it to SQL would mean building
 * query text from stored user values, which the brief rules out and which is a bad idea on its own
 * merits — the safety of a rule system should not rest on getting string escaping right.
 *
 * So the whole library is read once, in one indexed query, and evaluated in memory. At a few
 * thousand rows that is a single read returning a handful of columns; a rule set over it is
 * arithmetic. The consequence worth stating: **there is no shape in which the engine can issue a
 * query per candidate**, which is the failure mode the brief calls out.
 *
 * ## The active-projection predicate
 *
 * Carried here exactly as it is everywhere else — a track with no live projection is not a result a
 * user can play, and a collection that offered one would be offering a broken tap. A dormant item is
 * excluded by the same rule that excludes it from every other surface.
 *
 * ## Resolved metadata
 *
 * `songs.title`, `album_name` and `artist_name` already hold the *resolved* values: the scan writes
 * the user's override into the projection (Phase 12.1's derived mirror). Rules therefore see what
 * the user decided, not what the file said, without this query needing to know about overrides at
 * all.
 */
@Dao
interface CollectionCandidateDao {

    @Query(
        "SELECT " +
            "songs.library_item_id AS libraryItemId, songs.id AS locator, " +
            "songs.title AS title, songs.artist_name AS trackArtist, " +
            "songs.album_artist AS albumArtist, songs.album_name AS album, " +
            "songs.genres_json AS genresJson, songs.genre AS legacyGenre, " +
            "songs.duration AS durationMs, " +
            "songs.track_number AS trackNumber, songs.disc_number AS discNumber, " +
            "songs.year AS releaseYear, songs.bitrate AS bitrate, songs.sample_rate AS sampleRate, " +
            "library_items.first_seen_at AS firstSeenAt, " +
            "songs.is_favorite AS isFavourite, songs.is_compilation AS isCompilation, " +
            "songs.album_grouping_key AS albumGroupingKey " +
            "FROM songs " +
            "INNER JOIN library_items ON library_items.library_item_id = songs.library_item_id " +
            "WHERE library_items.state = 'ACTIVE' AND library_items.current_locator = songs.id"
    )
    suspend fun allCandidates(): List<CollectionCandidateRow>
}
