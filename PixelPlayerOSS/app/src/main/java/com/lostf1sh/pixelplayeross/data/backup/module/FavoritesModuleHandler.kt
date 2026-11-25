package com.lostf1sh.pixelplayeross.data.backup.module

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupResolutionCounts
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupRestoreMode
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupSongCatalogResolver
import com.lostf1sh.pixelplayeross.data.backup.restore.EmptyCatalogSource
import com.lostf1sh.pixelplayeross.data.backup.restore.PortableSongKeys
import com.lostf1sh.pixelplayeross.data.backup.restore.SongResolutionWay
import com.lostf1sh.pixelplayeross.data.database.FavoritesDao
import com.google.gson.annotations.SerializedName
import com.lostf1sh.pixelplayeross.data.database.FavoritesEntity
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesModuleHandler @Inject constructor(
    private val favoritesDao: FavoritesDao,
    @BackupGson private val gson: Gson,
    private val catalogSource: CatalogSource,
) : BackupModuleHandler, ReportableRestoreHandler {

    constructor(
        favoritesDao: FavoritesDao,
        gson: Gson,
    ) : this(favoritesDao, gson, EmptyCatalogSource)

    override val section = BackupSection.FAVORITES

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val catalog = runCatching { catalogSource.load() }.getOrNull()
        val entries = favoritesDao.getAllFavoritesOnce().map { entity ->
            val item = catalog?.let {
                it.itemAtLocator(entity.songId.toString())
                    ?: entity.libraryItemId.takeIf { id -> id.isNotBlank() }?.let(it::item)
            }
            entity.toBackupEntry(BackupSongCatalogResolver.keysFor(item))
        }
        gson.toJson(entries)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        favoritesDao.getAllFavoritesOnce().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) {
        restoreWithReport(payload, BackupRestoreMode.REPLACE)
    }

    override suspend fun restoreWithReport(
        payload: String,
        mode: BackupRestoreMode,
    ): BackupResolutionCounts = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, FavoriteBackupEntry::class.java).type
        val entries: List<FavoriteBackupEntry> = gson.fromJson(payload, type) ?: emptyList()
        val resolver = BackupSongCatalogResolver.loadIfAvailable(catalogSource)

        var byId = 0
        var byKeys = 0
        var unresolved = 0
        val resolvedEntities = ArrayList<FavoritesEntity>(entries.size)

        for (entry in entries) {
            if (resolver == null) {
                resolvedEntities.add(entry.toEntity())
                continue
            }
            val keys = entry.portableKeys()
            val resolved = resolver.resolve(
                backupSongId = entry.songId.toString(),
                backupLibraryItemId = entry.libraryItemId,
                keys = keys,
            )
            when (resolved.way) {
                SongResolutionWay.BY_ID -> byId++
                SongResolutionWay.BY_KEYS -> byKeys++
                SongResolutionWay.UNRESOLVED -> unresolved++
            }
            val targetSongId = resolved.songId?.toLongOrNull()
            if (targetSongId != null) {
                resolvedEntities.add(
                    FavoritesEntity(
                        songId = targetSongId,
                        isFavorite = entry.isFavorite,
                        timestamp = entry.timestamp,
                        libraryItemId = resolved.libraryItemId ?: entry.libraryItemId ?: "",
                    )
                )
            } else if (!keys.hasAny() && mode == BackupRestoreMode.REPLACE) {
                // Old .pxpl without portable keys: keep legacy restore behavior verbatim.
                resolvedEntities.add(entry.toEntity())
            }
        }

        if (mode == BackupRestoreMode.REPLACE) {
            favoritesDao.replaceAll(resolvedEntities)
        } else {
            val existing = favoritesDao.getAllFavoritesOnce()
            val existingBySongId = existing.associateBy { it.songId }.toMutableMap()
            val existingFavoriteLibIds = existing
                .filter { it.isFavorite && it.libraryItemId.isNotBlank() }
                .map { it.libraryItemId }
                .toMutableSet()
            val toInsert = ArrayList<FavoritesEntity>()
            for (entity in resolvedEntities) {
                val cur = existingBySongId[entity.songId]
                val matchesLib = entity.libraryItemId.isNotBlank() && entity.libraryItemId in existingFavoriteLibIds
                if (cur != null) {
                    if (!cur.isFavorite && entity.isFavorite) {
                        val updated = cur.copy(
                            isFavorite = true,
                            timestamp = maxOf(cur.timestamp, entity.timestamp),
                            libraryItemId = cur.libraryItemId.ifBlank { entity.libraryItemId },
                        )
                        existingBySongId[entity.songId] = updated
                        toInsert.add(updated)
                    }
                } else if (!matchesLib) {
                    existingBySongId[entity.songId] = entity
                    if (entity.isFavorite && entity.libraryItemId.isNotBlank()) {
                        existingFavoriteLibIds.add(entity.libraryItemId)
                    }
                    toInsert.add(entity)
                }
            }
            if (toInsert.isNotEmpty()) {
                favoritesDao.insertAll(toInsert)
            }
        }

        BackupResolutionCounts(
            resolvedById = byId,
            resolvedByKeys = byKeys,
            unresolved = unresolved,
        )
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
    @SerializedName("title")
    val title: String? = null,
    @SerializedName("artist")
    val artist: String? = null,
    @SerializedName("album")
    val album: String? = null,
    @SerializedName(value = "duration_ms", alternate = ["durationMs", "duration"])
    val trackDurationMs: Long? = null,
    @SerializedName("path")
    val path: String? = null,
) {
    fun portableKeys(): PortableSongKeys = PortableSongKeys(
        title = title,
        artist = artist,
        album = album,
        durationMs = trackDurationMs,
        path = path,
    )

    fun toEntity(): FavoritesEntity = FavoritesEntity(
        songId = songId,
        isFavorite = isFavorite,
        timestamp = timestamp,
        libraryItemId = libraryItemId ?: "",
    )
}

fun FavoritesEntity.toBackupEntry(portableKeys: PortableSongKeys? = null): FavoriteBackupEntry = FavoriteBackupEntry(
    songId = songId,
    isFavorite = isFavorite,
    timestamp = timestamp,
    libraryItemId = libraryItemId.ifEmpty { null },
    title = portableKeys?.title,
    artist = portableKeys?.artist,
    album = portableKeys?.album,
    trackDurationMs = portableKeys?.durationMs,
    path = portableKeys?.path,
)
