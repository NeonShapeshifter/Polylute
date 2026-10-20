package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
