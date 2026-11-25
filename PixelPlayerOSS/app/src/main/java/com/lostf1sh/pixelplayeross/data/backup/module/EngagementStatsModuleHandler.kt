package com.lostf1sh.pixelplayeross.data.backup.module

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupResolutionCounts
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupRestoreMode
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupSongCatalogResolver
import com.lostf1sh.pixelplayeross.data.backup.restore.EmptyCatalogSource
import com.lostf1sh.pixelplayeross.data.backup.restore.PortableSongKeys
import com.lostf1sh.pixelplayeross.data.backup.restore.SongResolutionWay
import com.lostf1sh.pixelplayeross.data.database.EngagementDao
import com.lostf1sh.pixelplayeross.data.database.SongEngagementEntity
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The legacy engagement counters, kept in the backup as they are (B28): nothing writes them any more and nothing ranks by them, but they
 * are the only record of listening from before the history file was kept, and a restore of an older backup brings them back.
 */
@Singleton
class EngagementStatsModuleHandler @Inject constructor(
    private val engagementDao: EngagementDao,
    @BackupGson private val gson: Gson,
    private val catalogSource: CatalogSource,
) : BackupModuleHandler, ReportableRestoreHandler {

    constructor(
        engagementDao: EngagementDao,
        gson: Gson,
    ) : this(engagementDao, gson, EmptyCatalogSource)

    override val section = BackupSection.ENGAGEMENT_STATS

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val catalog = runCatching { catalogSource.load() }.getOrNull()
        val array = com.google.gson.JsonArray()
        engagementDao.getAllEngagements().forEach { entity ->
            val obj = gson.toJsonTree(entity).asJsonObject
            val item = catalog?.let {
                it.itemAtLocator(entity.songId)
                    ?: entity.libraryItemId.takeIf { id -> id.isNotBlank() }?.let(it::item)
            }
            BackupSongCatalogResolver.keysFor(item)?.let { keys ->
                keys.title?.let { obj.addProperty("title", it) }
                keys.artist?.let { obj.addProperty("artist", it) }
                keys.album?.let { obj.addProperty("album", it) }
                keys.durationMs?.let { obj.addProperty("duration_ms", it) }
                keys.path?.let { obj.addProperty("path", it) }
            }
            array.add(obj)
        }
        gson.toJson(array)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        engagementDao.getAllEngagements().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) {
        restoreWithReport(payload, BackupRestoreMode.REPLACE)
    }

    override suspend fun restoreWithReport(
        payload: String,
        mode: BackupRestoreMode,
    ): BackupResolutionCounts = withContext(Dispatchers.IO) {
        val parsed = JsonParser.parseString(payload)
        require(parsed.isJsonArray) { "Engagement stats payload must be a JSON array." }

        val sourceEntries = parsed.asJsonArray.size()
        val parsedWithKeys = parseEntriesWithKeys(parsed.asJsonArray)
        if (sourceEntries > 0 && parsedWithKeys.isEmpty()) {
            throw IllegalArgumentException("Engagement stats backup does not contain any valid entries.")
        }

        val resolver = BackupSongCatalogResolver.loadIfAvailable(catalogSource)
        var byId = 0
        var byKeys = 0
        var unresolved = 0
        val resolvedMap = linkedMapOf<String, SongEngagementEntity>()

        for ((entity, keys) in parsedWithKeys) {
            if (resolver == null) {
                resolvedMap.merge(entity.songId, entity, ::mergeEntries)
                continue
            }
            val resolved = resolver.resolve(
                backupSongId = entity.songId,
                backupLibraryItemId = entity.libraryItemId,
                keys = keys,
            )
            when (resolved.way) {
                SongResolutionWay.BY_ID -> byId++
                SongResolutionWay.BY_KEYS -> byKeys++
                SongResolutionWay.UNRESOLVED -> unresolved++
            }
            val target = if (resolved.songId != null) {
                entity.copy(
                    songId = resolved.songId,
                    libraryItemId = resolved.libraryItemId ?: entity.libraryItemId,
                )
            } else if (!keys.hasAny() && mode == BackupRestoreMode.REPLACE) {
                entity
            } else {
                null
            }
            if (target != null) {
                resolvedMap.merge(target.songId, target, ::mergeEntries)
            }
        }

        val stats = resolvedMap.values.toList()
        if (mode == BackupRestoreMode.REPLACE) {
            engagementDao.replaceAll(stats)
        } else {
            val existingById = engagementDao.getAllEngagements().associateBy { it.songId }
            val toInsert = stats.filter { it.songId !in existingById }
            if (toInsert.isNotEmpty()) {
                engagementDao.upsertEngagements(toInsert)
            }
        }

        BackupResolutionCounts(
            resolvedById = byId,
            resolvedByKeys = byKeys,
            unresolved = unresolved,
        )
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)

    private fun parseEntriesWithKeys(array: com.google.gson.JsonArray): List<Pair<SongEngagementEntity, PortableSongKeys>> {
        val result = ArrayList<Pair<SongEngagementEntity, PortableSongKeys>>(array.size())
        array.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val obj = element.asJsonObject
            val entry = parseEntry(element) ?: return@forEach
            val keys = PortableSongKeys(
                title = readString(obj, "title"),
                artist = readString(obj, "artist"),
                album = readString(obj, "album"),
                durationMs = readLong(obj, "duration_ms"),
                path = readString(obj, "path"),
            )
            result.add(entry to keys)
        }
        return result
    }

    private fun parseEntry(element: JsonElement): SongEngagementEntity? {
        if (!element.isJsonObject) return null

        val obj = element.asJsonObject
        val songId = readString(obj, "songId", "song_id")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return SongEngagementEntity(
            songId = songId,
            playCount = (readInt(obj, "playCount", "play_count", "score", "plays") ?: 0).coerceAtLeast(0),
            totalPlayDurationMs = (
                readLong(
                    obj,
                    "totalPlayDurationMs",
                    "total_play_duration_ms",
                    "totalDuration",
                    "total_duration",
                    "durationMs",
                    "duration_ms"
                ) ?: 0L
            ).coerceAtLeast(0L),
            lastPlayedTimestamp = (
                readLong(
                    obj,
                    "lastPlayedTimestamp",
                    "last_played_timestamp",
                    "lastPlayedAt",
                    "last_played_at",
                    "timestamp"
                ) ?: 0L
            ).coerceAtLeast(0L)
        )
    }

    private fun mergeEntries(
        existing: SongEngagementEntity,
        incoming: SongEngagementEntity
    ): SongEngagementEntity {
        return SongEngagementEntity(
            songId = existing.songId,
            playCount = maxOf(existing.playCount, incoming.playCount),
            totalPlayDurationMs = maxOf(existing.totalPlayDurationMs, incoming.totalPlayDurationMs),
            lastPlayedTimestamp = maxOf(existing.lastPlayedTimestamp, incoming.lastPlayedTimestamp)
        )
    }

    private fun readString(obj: JsonObject, vararg keys: String): String? {
        return keys.asSequence()
            .mapNotNull { key ->
                obj.get(key)
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
            }
            .firstOrNull()
    }

    private fun readInt(obj: JsonObject, vararg keys: String): Int? {
        return keys.asSequence()
            .mapNotNull { key -> readLongValue(obj.get(key))?.toInt() }
            .firstOrNull()
    }

    private fun readLong(obj: JsonObject, vararg keys: String): Long? {
        return keys.asSequence()
            .mapNotNull { key -> readLongValue(obj.get(key)) }
            .firstOrNull()
    }

    private fun readLongValue(element: JsonElement?): Long? {
        val primitive = element?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            primitive.isNumber -> primitive.asNumber.toLong()
            primitive.isString -> primitive.asString.toLongOrNull()
            else -> null
        }
    }
}
