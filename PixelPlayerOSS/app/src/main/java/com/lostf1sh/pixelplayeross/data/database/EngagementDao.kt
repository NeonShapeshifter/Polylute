package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO for the legacy `song_engagements` counters.
 *
 * Nothing writes this table any more (B28): plays, completions and skips are derived from the listening history, per occurrence and per
 * durable identity (`ListeningLedger`). The rows that exist are kept, because they are part of a backup and are the only record of
 * listening from before the history was kept, and they are read for the Maintenance diagnostic, labelled legacy. Restoring a backup
 * replaces them whole.
 */
@Dao
interface EngagementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEngagements(engagements: List<SongEngagementEntity>)

    /** Looked up by durable identity: survives a renumber, and refuses a reused locator. */
    @Query("SELECT * FROM song_engagements WHERE library_item_id = :libraryItemId AND library_item_id <> ''")
    suspend fun getEngagementByLibraryItem(libraryItemId: String): SongEngagementEntity?

    @Query("SELECT * FROM song_engagements WHERE song_id = :songId")
    suspend fun getEngagement(songId: String): SongEngagementEntity?

    @Query("SELECT * FROM song_engagements")
    suspend fun getAllEngagements(): List<SongEngagementEntity>

    @Query("DELETE FROM song_engagements")
    suspend fun clearAllEngagements()

    @Transaction
    suspend fun replaceAll(engagements: List<SongEngagementEntity>) {
        clearAllEngagements()
        if (engagements.isNotEmpty()) upsertEngagements(engagements)
    }
}
