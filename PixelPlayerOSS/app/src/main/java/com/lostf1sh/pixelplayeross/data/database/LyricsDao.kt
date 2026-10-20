package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface LyricsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(lyrics: LyricsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lyrics: List<LyricsEntity>)

    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun getLyrics(songId: Long): LyricsEntity?

    @Query("DELETE FROM lyrics WHERE songId = :songId")
    suspend fun deleteLyrics(songId: Long)

    @Query("DELETE FROM lyrics")
    suspend fun deleteAll()

    @Query("SELECT * FROM lyrics")
    suspend fun getAll(): List<LyricsEntity>

    @Query("SELECT songId FROM lyrics WHERE songId IN (:songIds) AND content != ''")
    suspend fun getSongIdsWithLyrics(songIds: List<Long>): List<Long>

    // --- Identity-authoritative lyrics -----------------------------------------------------------
    //
    // The locator above is now a *resolution input*, not the owner. A user's lyrics are a statement
    // about a track, so they belong to the logical item: they have to survive the item being
    // renumbered, and they have to be reachable after it moves.
    //
    // The query below is the shape every runtime read uses. The caller still holds the locator it
    // was given — that is what a caller has, and it is always the *current* one — but the row it
    // resolves to is chosen by identity. So `A -> L1`, renumber to `B`, read via `B`: the subquery
    // turns `B` into `L1` and finds the lyrics written under `A`, with nothing moved in between.
    //
    // This is the same relationship `library_items.current_locator` already has to identity: the
    // locator says *which* item you mean, the identity says what the row *is*.

    @Query("SELECT * FROM lyrics WHERE library_item_id = :libraryItemId LIMIT 1")
    suspend fun getLyricsForLibraryItem(libraryItemId: String): LyricsEntity?

    @Query(
        "SELECT * FROM lyrics WHERE library_item_id = " +
            "(SELECT library_item_id FROM songs WHERE id = :songId) LIMIT 1"
    )
    suspend fun getLyricsResolvedByIdentity(songId: Long): LyricsEntity?

    @Query("DELETE FROM lyrics WHERE library_item_id = :libraryItemId")
    suspend fun deleteLyricsForLibraryItem(libraryItemId: String)

    @Query("SELECT library_item_id FROM lyrics WHERE library_item_id <> ''")
    suspend fun getLyricsLibraryItemIds(): List<String>

    @Transaction
    suspend fun replaceAll(lyrics: List<LyricsEntity>) {
        deleteAll()
        if (lyrics.isNotEmpty()) insertAll(lyrics.map { it.sanitised() })
    }

    @Transaction
    suspend fun insertAllSanitised(lyrics: List<LyricsEntity>) {
        if (lyrics.isNotEmpty()) insertAll(lyrics.map { it.sanitised() })
    }
}

/**
 * Coalesces a decoded-null identity to the empty string.
 *
 * This is not defensive noise, it is a specific hazard. Backups serialise these entities with Gson,
 * and **Gson instantiates objects through `Unsafe`** — the constructor never runs, so a payload
 * written before `library_item_id` existed decodes that field as `null` in a property the type
 * system says cannot be null. Binders then hand SQLite a null for a `NOT NULL` column and the
 * restore fails.
 *
 * That exact mechanism already caused one real regression in this phase, when `libraryItemId` was
 * added to [FavoritesEntity]. The lesson was that a Room entity is not a wire format; failing to
 * give these three the same treatment would be repeating a mistake that had already been diagnosed.
 */
private fun LyricsEntity.sanitised(): LyricsEntity =
    if (libraryItemId.isNullOrEmpty()) copy(libraryItemId = "") else this
