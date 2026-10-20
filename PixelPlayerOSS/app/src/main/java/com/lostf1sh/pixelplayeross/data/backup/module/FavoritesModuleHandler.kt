package com.lostf1sh.pixelplayeross.data.backup.module

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.database.FavoritesDao
import com.google.gson.annotations.SerializedName
import com.lostf1sh.pixelplayeross.data.database.FavoritesEntity
import com.lostf1sh.pixelplayeross.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesModuleHandler @Inject constructor(
    private val favoritesDao: FavoritesDao,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.FAVORITES

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        gson.toJson(favoritesDao.getAllFavoritesOnce().map { it.toBackupEntry() })
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        favoritesDao.getAllFavoritesOnce().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, FavoriteBackupEntry::class.java).type
        val entries: List<FavoriteBackupEntry> = gson.fromJson(payload, type)
        favoritesDao.replaceAll(entries.map { it.toEntity() })
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)
}

/**
 * The backup wire format for a favourite.
 *
 * This exists because the Room entity was previously the wire format, and that made every database
 * change a silent change to the backup contract. It broke in exactly that way: `FavoritesEntity`
 * gained a non-null `libraryItemId`, and because Gson instantiates through `Unsafe` and writes
 * fields reflectively — **the constructor never runs, so Kotlin defaults do not apply** — every
 * legacy payload decoded that field as `null` instead of `""`. Restores of old backups would have
 * produced entities violating their own non-null type.
 *
 * The DTO fixes that structurally rather than by patching the test: [libraryItemId] is **nullable
 * here on purpose**, because a payload written before durable identities existed simply does not
 * contain it, and a reader must be tolerant of a field that was not there when the data was
 * written.
 *
 * Older payloads remain readable — snake_case alternates are preserved — and a locator-only legacy
 * favourite restores with an empty identity, which the bootstrap migration then resolves.
 */
data class FavoriteBackupEntry(
    @SerializedName(value = "songId", alternate = ["song_id"])
    val songId: Long,
    @SerializedName(value = "isFavorite", alternate = ["is_favorite"])
    val isFavorite: Boolean = true,
    @SerializedName(value = "timestamp", alternate = ["addedAt", "added_at"])
    val timestamp: Long = 0L,
    /** Absent in every payload written before Phase 12.1B. Null is the honest representation. */
    @SerializedName(value = "libraryItemId", alternate = ["library_item_id"])
    val libraryItemId: String? = null,
) {
    fun toEntity(): FavoritesEntity = FavoritesEntity(
        songId = songId,
        isFavorite = isFavorite,
        timestamp = timestamp,
        libraryItemId = libraryItemId ?: "",
    )
}

fun FavoritesEntity.toBackupEntry(): FavoriteBackupEntry = FavoriteBackupEntry(
    songId = songId,
    isFavorite = isFavorite,
    timestamp = timestamp,
    libraryItemId = libraryItemId.ifEmpty { null },
)
