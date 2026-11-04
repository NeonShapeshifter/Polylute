package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The durable logical-item store.
 *
 * Every mutation here is about lifecycle, never about user state — user state belongs to the item
 * and is not touched by anything in this file.
 */
/**
 * Which dormant items the manual Maintenance clean-up may remove (AUD-106). Never run automatically:
 * absent is not deleted. An item qualifies only if it is dormant, has no projection, and carries no
 * user state at all — no favourite, lyric, metadata edit, legacy play counter, playlist entry or bookmark.
 * The count shown to the listener and the delete use this same condition.
 *
 * Plays are not in this condition. Since B28 they are the listening history (`playback_history.json`), which a SQL
 * condition cannot see and `song_engagements` no longer holds; `DormantItemCleanup` adds that protection around these
 * queries (R28-01). `song_engagements` stays here as the legacy counters it still is.
 */
internal const val PURGEABLE_DORMANT_WHERE = """
    state = 'DORMANT'
    AND NOT EXISTS (SELECT 1 FROM songs s WHERE s.library_item_id = library_items.library_item_id)
    AND NOT EXISTS (SELECT 1 FROM favorites f WHERE f.library_item_id = library_items.library_item_id)
    AND NOT EXISTS (SELECT 1 FROM lyrics l WHERE l.library_item_id = library_items.library_item_id)
    AND NOT EXISTS (SELECT 1 FROM library_item_overrides o WHERE o.library_item_id = library_items.library_item_id)
    AND NOT EXISTS (SELECT 1 FROM song_engagements e WHERE e.library_item_id = library_items.library_item_id)
    AND NOT EXISTS (SELECT 1 FROM playlist_songs p WHERE p.library_item_id = library_items.library_item_id)
    AND NOT EXISTS (SELECT 1 FROM audio_bookmarks b WHERE b.library_item_id = library_items.library_item_id)
"""

@Dao
interface LibraryItemDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<LibraryItemEntity>): List<Long>

    @Query("SELECT * FROM library_items WHERE library_item_id = :libraryItemId")
    suspend fun getById(libraryItemId: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items")
    suspend fun getAll(): List<LibraryItemEntity>

    @Query("SELECT * FROM library_items WHERE state = :state")
    suspend fun getByState(state: String): List<LibraryItemEntity>

    @Query("SELECT * FROM library_items WHERE state = :state")
    fun observeByState(state: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT COUNT(*) FROM library_items WHERE state = :state")
    suspend fun countByState(state: String): Int

    /** A live projection now exists for this item. */
    @Query(
        "UPDATE library_items SET state = 'ACTIVE', current_locator = :locator, " +
            "stable_key = :stableKey, file_signature = :fileSignature, last_seen_at = :now " +
            "WHERE library_item_id = :libraryItemId"
    )
    suspend fun markActive(
        libraryItemId: String,
        locator: Long,
        stableKey: String,
        fileSignature: String,
        now: Long,
    )

    /**
     * The item is known but has no projection right now.
     *
     * Note this clears the locator and **changes nothing else**. Matching evidence is deliberately
     * kept: it is what allows the item to be recognised when it comes back, and discarding it on
     * absence would make every temporary loss permanent.
     */
    @Query(
        "UPDATE library_items SET state = 'DORMANT', current_locator = NULL " +
            "WHERE library_item_id = :libraryItemId"
    )
    suspend fun markDormant(libraryItemId: String)

    @Query(
        "UPDATE library_items SET state = 'AMBIGUOUS', current_locator = NULL " +
            "WHERE library_item_id = :libraryItemId"
    )
    suspend fun markAmbiguous(libraryItemId: String)

    @Query("UPDATE library_items SET stable_key = :stableKey, file_signature = :fileSignature WHERE library_item_id = :libraryItemId")
    suspend fun updateEvidence(libraryItemId: String, stableKey: String, fileSignature: String)

    @Query("SELECT * FROM library_items WHERE stable_key = :stableKey")
    suspend fun getByFingerprint(stableKey: String): List<LibraryItemEntity>

    /** The dormant items the manual clean-up may consider; see [PURGEABLE_DORMANT_WHERE]. The history's own are taken off by `DormantItemCleanup`. */
    @Query("SELECT library_item_id FROM library_items WHERE " + PURGEABLE_DORMANT_WHERE)
    suspend fun purgeableDormantIds(): List<String>

    /**
     * Removes the items of [ids] that still satisfy [PURGEABLE_DORMANT_WHERE] when this runs, so something a listener saved or a scan
     * brought back since [purgeableDormantIds] was read is kept. Manual only (AUD-106). Keep [ids] short (SQLite's variable limit).
     */
    @Query("DELETE FROM library_items WHERE library_item_id IN (:ids) AND " + PURGEABLE_DORMANT_WHERE)
    suspend fun deletePurgeableDormant(ids: List<String>): Int

    // ── B57 1.2: the one-time repair of items orphaned by an edit made before the editor recorded its evidence ──

    @Query("SELECT library_item_id, stable_key, file_signature, first_seen_at, last_seen_at FROM library_items WHERE state = 'DORMANT'")
    suspend fun dormantForRepair(): List<RepairDormantRow>

    /** Every locator the item's own user-state rows name (favourite, lyrics, playlist entries, bookmarks, legacy counters); 0 is a non-local row. */
    @Query(
        "SELECT songId AS locator FROM favorites WHERE library_item_id = :libraryItemId " +
            "UNION SELECT songId FROM lyrics WHERE library_item_id = :libraryItemId " +
            "UNION SELECT CAST(song_id AS INTEGER) FROM playlist_songs WHERE library_item_id = :libraryItemId " +
            "UNION SELECT CAST(song_id AS INTEGER) FROM audio_bookmarks WHERE library_item_id = :libraryItemId " +
            "UNION SELECT CAST(song_id AS INTEGER) FROM song_engagements WHERE library_item_id = :libraryItemId"
    )
    suspend fun userStateLocators(libraryItemId: String): List<Long>

    /** How many user-state rows hang off the item. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM favorites WHERE library_item_id = :libraryItemId) " +
            "+ (SELECT COUNT(*) FROM lyrics WHERE library_item_id = :libraryItemId) " +
            "+ (SELECT COUNT(*) FROM library_item_overrides WHERE library_item_id = :libraryItemId) " +
            "+ (SELECT COUNT(*) FROM song_engagements WHERE library_item_id = :libraryItemId) " +
            "+ (SELECT COUNT(*) FROM playlist_songs WHERE library_item_id = :libraryItemId) " +
            "+ (SELECT COUNT(*) FROM audio_bookmarks WHERE library_item_id = :libraryItemId)"
    )
    suspend fun userStateRowCount(libraryItemId: String): Int

    /** The ACTIVE items (other than [excluding]) whose projection sits on [locator]. */
    @Query(
        "SELECT s.library_item_id AS library_item_id, s.id AS locator, s.duration AS duration, s.file_size AS file_size, " +
            "li.first_seen_at AS first_seen_at FROM songs s INNER JOIN library_items li ON li.library_item_id = s.library_item_id " +
            "WHERE s.id = :locator AND li.state = 'ACTIVE' AND s.library_item_id <> :excluding"
    )
    suspend fun liveAtLocator(locator: Long, excluding: String): List<RepairLiveRow>

    @Query("UPDATE favorites SET library_item_id = :to WHERE library_item_id = :from")
    suspend fun moveFavorites(from: String, to: String)

    @Query("UPDATE lyrics SET library_item_id = :to WHERE library_item_id = :from")
    suspend fun moveLyrics(from: String, to: String)

    @Query("UPDATE library_item_overrides SET library_item_id = :to WHERE library_item_id = :from")
    suspend fun moveOverrides(from: String, to: String)

    @Query("UPDATE song_engagements SET library_item_id = :to WHERE library_item_id = :from")
    suspend fun moveEngagements(from: String, to: String)

    @Query("UPDATE playlist_songs SET library_item_id = :to WHERE library_item_id = :from")
    suspend fun movePlaylistEntries(from: String, to: String)

    @Query("UPDATE audio_bookmarks SET library_item_id = :to WHERE library_item_id = :from")
    suspend fun moveBookmarks(from: String, to: String)

    @Query("UPDATE songs SET is_favorite = EXISTS (SELECT 1 FROM favorites f WHERE f.library_item_id = :libraryItemId AND f.isFavorite = 1) WHERE library_item_id = :libraryItemId")
    suspend fun refreshFavouriteCache(libraryItemId: String)

    @Query("DELETE FROM library_items WHERE library_item_id = :libraryItemId AND state = 'DORMANT'")
    suspend fun deleteDormant(libraryItemId: String): Int

    /**
     * Moves everything that hangs off the dormant item [from] onto the live item [to], then removes [from], in one transaction.
     * Only [OrphanedIdentityRepair] calls this, and only for a pair it has proved.
     */
    @Transaction
    suspend fun mergeOrphan(from: String, to: String) {
        moveFavorites(from, to)
        moveLyrics(from, to)
        moveOverrides(from, to)
        moveEngagements(from, to)
        movePlaylistEntries(from, to)
        moveBookmarks(from, to)
        refreshFavouriteCache(to)
        deleteDormant(from)
    }
}

data class RepairDormantRow(
    @ColumnInfo(name = "library_item_id") val libraryItemId: String,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "file_signature") val fileSignature: String,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
)

data class RepairLiveRow(
    @ColumnInfo(name = "library_item_id") val libraryItemId: String,
    @ColumnInfo(name = "locator") val locator: Long,
    @ColumnInfo(name = "duration") val duration: Long,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
)
