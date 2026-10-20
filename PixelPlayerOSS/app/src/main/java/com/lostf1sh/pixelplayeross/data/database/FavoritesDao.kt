package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface FavoritesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setFavorite(favorite: FavoritesEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(favorites: List<FavoritesEntity>)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun removeFavorite(songId: Long)

    // --- Identity-authoritative favourites ------------------------------------------------------
    //
    // `songId` above remains the compatibility key for the existing read paths, which still hand
    // back a list of locators. Ownership, however, is [libraryItemId]: a favourite belongs to the
    // logical item and must survive the item moving to a different MediaStore number.

    @Query("DELETE FROM favorites WHERE library_item_id = :libraryItemId")
    suspend fun deleteFavoriteForLibraryItem(libraryItemId: String)

    @Query("SELECT isFavorite FROM favorites WHERE library_item_id = :libraryItemId LIMIT 1")
    suspend fun isFavoriteForLibraryItem(libraryItemId: String): Boolean?

    /**
     * Marks a **logical item** as a favourite.
     *
     * Keyed by identity, so the favourite is unaffected by the item being renumbered. The locator
     * column is filled from the item's current projection purely so the older locator-shaped read
     * paths stay coherent; it is a compatibility detail, not the owner.
     */
    @Transaction
    suspend fun setFavoriteForLibraryItem(
        libraryItemId: String,
        isFavorite: Boolean,
        timestamp: Long,
        currentLocator: Long,
    ) {
        deleteFavoriteForLibraryItem(libraryItemId)
        insertAll(
            listOf(
                FavoritesEntity(
                    songId = currentLocator,
                    isFavorite = isFavorite,
                    timestamp = timestamp,
                    libraryItemId = libraryItemId,
                )
            )
        )
    }

    @Query("SELECT library_item_id FROM favorites WHERE isFavorite = 1 ORDER BY library_item_id")
    suspend fun getFavoriteLibraryItemIds(): List<String>

    @Query("UPDATE favorites SET songId = :newLocator WHERE library_item_id = :libraryItemId")
    suspend fun retargetFavouriteLocator(libraryItemId: String, newLocator: Long)

    @Query("SELECT isFavorite FROM favorites WHERE songId = :songId")
    suspend fun isFavorite(songId: Long): Boolean?

    @Query("SELECT songId FROM favorites WHERE isFavorite = 1 ORDER BY songId")
    fun getFavoriteSongIdsRaw(): Flow<List<Long>>

    fun getFavoriteSongIds(): Flow<List<Long>> = getFavoriteSongIdsRaw().distinctUntilChanged()

    @Query("SELECT songId FROM favorites WHERE isFavorite = 1 ORDER BY songId")
    suspend fun getFavoriteSongIdsOnce(): List<Long>

    @Query("SELECT * FROM favorites")
    suspend fun getAllFavoritesOnce(): List<FavoritesEntity>

    @Query("DELETE FROM favorites")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(favorites: List<FavoritesEntity>) {
        clearAll()
        if (favorites.isNotEmpty()) insertAll(favorites)
    }
}
