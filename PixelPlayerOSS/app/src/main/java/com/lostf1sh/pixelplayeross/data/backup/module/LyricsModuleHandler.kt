package com.lostf1sh.pixelplayeross.data.backup.module

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupResolutionCounts
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupRestoreMode
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupSongCatalogResolver
import com.lostf1sh.pixelplayeross.data.backup.restore.EmptyCatalogSource
import com.lostf1sh.pixelplayeross.data.backup.restore.PortableSongKeys
import com.lostf1sh.pixelplayeross.data.backup.restore.SongResolutionWay
import com.lostf1sh.pixelplayeross.data.database.LyricsDao
import com.lostf1sh.pixelplayeross.data.database.LyricsEntity
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsModuleHandler @Inject constructor(
    private val lyricsDao: LyricsDao,
    @BackupGson private val gson: Gson,
    private val catalogSource: CatalogSource,
) : BackupModuleHandler, ReportableRestoreHandler {

    constructor(
        lyricsDao: LyricsDao,
        gson: Gson,
    ) : this(lyricsDao, gson, EmptyCatalogSource)

    override val section = BackupSection.LYRICS

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val catalog = runCatching { catalogSource.load() }.getOrNull()
        val entries = lyricsDao.getAll().map { entity ->
            val item = catalog?.let {
                it.itemAtLocator(entity.songId.toString())
                    ?: entity.libraryItemId.takeIf { id -> id.isNotBlank() }?.let(it::item)
            }
            val keys = BackupSongCatalogResolver.keysFor(item)
            LyricsBackupEntry(
                songId = entity.songId,
                content = entity.content,
                isSynced = entity.isSynced,
                source = entity.source,
                libraryItemId = entity.libraryItemId,
                title = keys?.title,
                artist = keys?.artist,
                album = keys?.album,
                trackDurationMs = keys?.durationMs,
                path = keys?.path,
            )
        }
        gson.toJson(entries)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        lyricsDao.getAll().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) {
        restoreWithReport(payload, BackupRestoreMode.REPLACE)
    }

    override suspend fun restoreWithReport(
        payload: String,
        mode: BackupRestoreMode,
    ): BackupResolutionCounts = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, LyricsBackupEntry::class.java).type
        val entries: List<LyricsBackupEntry> = gson.fromJson(payload, type) ?: emptyList()
        val resolver = BackupSongCatalogResolver.loadIfAvailable(catalogSource)

        var byId = 0
        var byKeys = 0
        var unresolved = 0
        val resolvedEntities = ArrayList<LyricsEntity>(entries.size)

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
                    LyricsEntity(
                        songId = targetSongId,
                        content = entry.content.orEmpty(),
                        isSynced = entry.isSynced,
                        source = entry.source,
                        libraryItemId = resolved.libraryItemId ?: entry.libraryItemId.orEmpty(),
                    )
                )
            } else if (!keys.hasAny() && mode == BackupRestoreMode.REPLACE) {
                resolvedEntities.add(entry.toEntity())
            }
        }

        if (mode == BackupRestoreMode.REPLACE) {
            lyricsDao.replaceAll(resolvedEntities)
        } else {
            val existing = lyricsDao.getAll()
            val existingSongIds = existing.mapTo(HashSet()) { it.songId }
            val existingLibIds = existing.mapNotNullTo(HashSet()) { it.libraryItemId.takeIf { id -> id.isNotBlank() } }
            val toInsert = resolvedEntities.filter { entity ->
                entity.songId !in existingSongIds &&
                    (entity.libraryItemId.isBlank() || entity.libraryItemId !in existingLibIds)
            }
            if (toInsert.isNotEmpty()) {
                lyricsDao.insertAllSanitised(toInsert)
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

data class LyricsBackupEntry(
    @SerializedName(value = "songId", alternate = ["song_id"])
    val songId: Long,
    @SerializedName("content")
    val content: String? = "",
    @SerializedName(value = "isSynced", alternate = ["is_synced"])
    val isSynced: Boolean = false,
    @SerializedName("source")
    val source: String? = null,
    @SerializedName(value = "libraryItemId", alternate = ["library_item_id"])
    val libraryItemId: String? = "",
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

    fun toEntity(): LyricsEntity = LyricsEntity(
        songId = songId,
        content = content.orEmpty(),
        isSynced = isSynced,
        source = source,
        libraryItemId = libraryItemId.orEmpty(),
    )
}
