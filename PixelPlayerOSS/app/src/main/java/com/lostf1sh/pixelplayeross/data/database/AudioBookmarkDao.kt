package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioBookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: AudioBookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bookmarks: List<AudioBookmarkEntity>)

    @Query("SELECT * FROM audio_bookmarks ORDER BY created_time DESC")
    suspend fun getAllBookmarksOnce(): List<AudioBookmarkEntity>

    @Query("DELETE FROM audio_bookmarks")
    suspend fun deleteAll()

    @Query("DELETE FROM audio_bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("SELECT * FROM audio_bookmarks ORDER BY created_time DESC")
    fun getAllBookmarksFlow(): Flow<List<AudioBookmarkEntity>>

    @Query("SELECT * FROM audio_bookmarks WHERE song_id = :songId ORDER BY created_time DESC")
    suspend fun getBookmarksForSong(songId: String): List<AudioBookmarkEntity>

    @Query("SELECT * FROM audio_bookmarks WHERE id = :id")
    suspend fun getBookmarkById(id: Long): AudioBookmarkEntity?

    // --- Identity-authoritative bookmarks ---------------------------------------------------------
    //
    // A bookmark is a note about a *track* — "chapter one starts here" — so it belongs to the
    // logical item and must survive that item being renumbered. The locator column is kept because
    // older rows and the bookmark list still carry it, but it no longer decides ownership.

    @Query("SELECT * FROM audio_bookmarks WHERE library_item_id = :libraryItemId ORDER BY created_time DESC")
    suspend fun getBookmarksForLibraryItem(libraryItemId: String): List<AudioBookmarkEntity>

    /**
     * Resolves bookmarks for whichever item currently owns `songId`.
     *
     * The caller holds the locator it was given — always the current one — and identity decides which
     * rows that means, so a bookmark saved before a renumber is still found afterwards.
     */
    @Query(
        "SELECT * FROM audio_bookmarks WHERE library_item_id = " +
            "(SELECT library_item_id FROM songs WHERE id = :songId) ORDER BY created_time DESC"
    )
    suspend fun getBookmarksResolvedByIdentity(songId: String): List<AudioBookmarkEntity>

    /** Fills the durable owner from the song the bookmark is being created against. */
    @Query(
        "UPDATE audio_bookmarks SET library_item_id = COALESCE(" +
            "(SELECT library_item_id FROM songs WHERE id = CAST(:songId AS INTEGER)), '') " +
            "WHERE song_id = :songId AND library_item_id = ''"
    )
    suspend fun bindBookmarkIdentity(songId: String)

    @Transaction
    suspend fun insertBookmarkWithIdentity(bookmark: AudioBookmarkEntity): Long {
        val id = insertBookmarkReturningId(bookmark.sanitised())
        bindBookmarkIdentity(bookmark.songId)
        return id
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarkReturningId(bookmark: AudioBookmarkEntity): Long

    @Transaction
    suspend fun insertAllSanitised(bookmarks: List<AudioBookmarkEntity>) {
        if (bookmarks.isNotEmpty()) insertAll(bookmarks.map { it.sanitised() })
    }

    @Transaction
    suspend fun replaceAll(bookmarks: List<AudioBookmarkEntity>) {
        deleteAll()
        insertAllSanitised(bookmarks)
    }
}

/**
 * Coalesces a decoded-null identity to the empty string — see the note on the same function in
 * `LyricsDao`. Gson writes fields reflectively without running a constructor, so a bookmark restored
 * from a backup taken before `library_item_id` existed carries `null` in a non-null property, and
 * binding that to a `NOT NULL` column aborts the restore.
 */
private fun AudioBookmarkEntity.sanitised(): AudioBookmarkEntity =
    if (libraryItemId.isNullOrEmpty()) copy(libraryItemId = "") else this
