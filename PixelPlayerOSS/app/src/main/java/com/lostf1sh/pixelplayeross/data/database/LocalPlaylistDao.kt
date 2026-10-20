package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalPlaylistDao {
    @Transaction
    @Query("SELECT * FROM playlists ORDER BY last_modified DESC")
    fun observePlaylistsWithSongs(): Flow<List<PlaylistWithSongsEntity>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    fun observePlaylistWithSongs(playlistId: String): Flow<PlaylistWithSongsEntity?>

    @Query("SELECT * FROM playlist_songs WHERE playlist_id = :playlistId ORDER BY sort_order ASC")
    fun observePlaylistSongs(playlistId: String): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: String): PlaylistEntity?

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun getPlaylistCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(entity: PlaylistEntity)

    @Update
    suspend fun updatePlaylist(entity: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistSongs(entities: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: String)

    @Query("DELETE FROM playlist_songs")
    suspend fun clearAllPlaylistSongs()

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()

    // --- Identity-authoritative membership --------------------------------------------------------
    //
    // "This track is in this playlist" is a statement about the *track*, so membership belongs to the
    // logical item. Storing it against the locator meant a renumbered track silently fell out of
    // every playlist it was in — the entry stayed, pointing at a MediaStore number that now
    // describes nothing.
    //
    // Writes go through [upsertPlaylistSongResolvingIdentity], which fills the owner from the song's
    // current locator at the moment of the write — the one time the locator is still the correct
    // link. Reads resolve from the locator the caller holds to the identity that owns it, so an entry
    // written before a renumber is still found afterwards.

    @Query(
        "INSERT OR REPLACE INTO `playlist_songs` " +
            "(`playlist_id`, `song_id`, `sort_order`, `library_item_id`) " +
            "VALUES (:playlistId, :songId, :sortOrder, COALESCE(" +
            "(SELECT `library_item_id` FROM `songs` WHERE `id` = CAST(:songId AS INTEGER)), ''))"
    )
    suspend fun upsertPlaylistSongResolvingIdentity(playlistId: String, songId: String, sortOrder: Int)

    /**
     * The current locator of each entry's owner, in playlist order.
     *
     * Resolution is by identity, with the stored locator as a fallback for rows whose identity is
     * empty — legacy entries written before the migration, which have nothing else to resolve by.
     * `COALESCE` returns the identity's current locator when it has one, so an entry survives its
     * track being renumbered.
     */
    @Query(
        "SELECT COALESCE(" +
            "(SELECT s.`id` FROM `songs` s WHERE s.`library_item_id` = `playlist_songs`.`library_item_id` " +
            "AND `playlist_songs`.`library_item_id` <> '' LIMIT 1), " +
            "CAST(`playlist_songs`.`song_id` AS INTEGER)) " +
            "FROM `playlist_songs` WHERE `playlist_id` = :playlistId ORDER BY `sort_order` ASC"
    )
    suspend fun getPlaylistLocatorsResolved(playlistId: String): List<Long>

    @Query(
        "UPDATE `playlist_songs` SET `library_item_id` = COALESCE(" +
            "(SELECT `library_item_id` FROM `songs` WHERE `id` = CAST(`playlist_songs`.`song_id` AS INTEGER)), '') " +
            "WHERE `library_item_id` = ''"
    )
    suspend fun bindPlaylistMembershipIdentities()

    @Transaction
    suspend fun replacePlaylistSongs(playlistId: String, songIds: List<String>) {
        clearPlaylistSongs(playlistId)
        if (songIds.isEmpty()) return
        songIds.forEachIndexed { index, songId ->
            upsertPlaylistSongResolvingIdentity(playlistId, songId, index)
        }
    }

    @Transaction
    suspend fun replacePlaylistSongsByIdentities(playlistId: String, libraryItemIds: List<String>) {
        clearPlaylistSongs(playlistId)
        if (libraryItemIds.isEmpty()) return
        libraryItemIds.forEachIndexed { index, libraryItemId ->
            upsertPlaylistSongs(
                listOf(
                    PlaylistSongEntity(
                        playlistId = playlistId,
                        songId = "",
                        sortOrder = index,
                        libraryItemId = libraryItemId,
                    )
                )
            )
        }
    }

    @Transaction
    suspend fun replaceAllPlaylistsTransactional(playlists: List<Pair<PlaylistEntity, List<String>>>) {
        clearAllPlaylistSongs()
        clearAllPlaylists()
        playlists.forEach { (entity, songIds) ->
            upsertPlaylist(entity)
            replacePlaylistSongs(entity.id, songIds)
        }
    }
}
