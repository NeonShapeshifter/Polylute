package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * The single writable authority for user metadata edits.
 *
 * Everything here is keyed by `library_item_id`. There is deliberately **no** locator-shaped
 * overload: the bug this table exists to remove was user state owned by a disposable row, and
 * offering a locator-based write path back into it would let the old defect return through a call
 * site nobody re-checked.
 */
@Dao
interface LibraryItemOverrideDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(override: LibraryItemOverrideEntity)

    @Query("SELECT * FROM library_item_overrides WHERE library_item_id = :libraryItemId")
    suspend fun getForLibraryItem(libraryItemId: String): LibraryItemOverrideEntity?

    @Query("SELECT * FROM library_item_overrides WHERE library_item_id IN (:libraryItemIds)")
    suspend fun getForLibraryItems(libraryItemIds: List<String>): List<LibraryItemOverrideEntity>

    @Query("UPDATE library_item_overrides SET title = :value, title_user_edited = :edited WHERE library_item_id = :libraryItemId")
    suspend fun updateTitle(libraryItemId: String, value: String?, edited: Boolean)

    @Query("UPDATE library_item_overrides SET artist = :value, artist_user_edited = :edited WHERE library_item_id = :libraryItemId")
    suspend fun updateArtist(libraryItemId: String, value: String?, edited: Boolean)

    @Query("UPDATE library_item_overrides SET album = :value, album_user_edited = :edited WHERE library_item_id = :libraryItemId")
    suspend fun updateAlbum(libraryItemId: String, value: String?, edited: Boolean)

    @Query("UPDATE library_item_overrides SET genre = :value, genre_user_edited = :edited WHERE library_item_id = :libraryItemId")
    suspend fun updateGenre(libraryItemId: String, value: String?, edited: Boolean)

    @Query("DELETE FROM library_item_overrides WHERE library_item_id = :libraryItemId")
    suspend fun deleteForLibraryItem(libraryItemId: String)

    @Query("SELECT COUNT(*) FROM library_item_overrides")
    suspend fun count(): Int

    /**
     * Records (or clears) the user's title for a logical item.
     *
     * `edited = false` stores **NULL**, not the file's title. A cleared override has to read as
     * "no opinion" so the next scan is free to take the file's current tag; storing the old text
     * with the flag off would leave a value that looks edited but is no longer wanted.
     */
    @Transaction
    suspend fun setTitle(libraryItemId: String, value: String?, edited: Boolean = true) {
        if (libraryItemId.isEmpty()) return
        insertIfAbsent(LibraryItemOverrideEntity(libraryItemId = libraryItemId))
        updateTitle(libraryItemId, value, edited)
    }

    @Transaction
    suspend fun setArtist(libraryItemId: String, value: String?, edited: Boolean = true) {
        if (libraryItemId.isEmpty()) return
        insertIfAbsent(LibraryItemOverrideEntity(libraryItemId = libraryItemId))
        updateArtist(libraryItemId, value, edited)
    }

    @Transaction
    suspend fun setAlbum(libraryItemId: String, value: String?, edited: Boolean = true) {
        if (libraryItemId.isEmpty()) return
        insertIfAbsent(LibraryItemOverrideEntity(libraryItemId = libraryItemId))
        updateAlbum(libraryItemId, value, edited)
    }

    @Transaction
    suspend fun setGenre(libraryItemId: String, value: String?, edited: Boolean = true) {
        if (libraryItemId.isEmpty()) return
        insertIfAbsent(LibraryItemOverrideEntity(libraryItemId = libraryItemId))
        updateGenre(libraryItemId, value, edited)
    }

    /** Records all four fields of one edit in a single round trip. */
    @Transaction
    suspend fun setAll(
        libraryItemId: String,
        title: String?,
        titleEdited: Boolean,
        artist: String?,
        artistEdited: Boolean,
        album: String?,
        albumEdited: Boolean,
        genre: String?,
        genreEdited: Boolean,
    ) {
        if (libraryItemId.isEmpty()) return
        insertIfAbsent(LibraryItemOverrideEntity(libraryItemId = libraryItemId))
        updateTitle(libraryItemId, title, titleEdited)
        updateArtist(libraryItemId, artist, artistEdited)
        updateAlbum(libraryItemId, album, albumEdited)
        updateGenre(libraryItemId, genre, genreEdited)
    }
}
