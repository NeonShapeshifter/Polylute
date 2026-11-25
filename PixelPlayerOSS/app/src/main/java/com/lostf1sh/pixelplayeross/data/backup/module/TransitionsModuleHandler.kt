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
import com.lostf1sh.pixelplayeross.data.database.TransitionDao
import com.lostf1sh.pixelplayeross.data.database.TransitionRuleEntity
import com.lostf1sh.pixelplayeross.data.model.TransitionSettings
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransitionsModuleHandler @Inject constructor(
    private val transitionDao: TransitionDao,
    @BackupGson private val gson: Gson,
    private val catalogSource: CatalogSource,
) : BackupModuleHandler, ReportableRestoreHandler {

    constructor(
        transitionDao: TransitionDao,
        gson: Gson,
    ) : this(transitionDao, gson, EmptyCatalogSource)

    override val section = BackupSection.TRANSITIONS

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val catalog = runCatching { catalogSource.load() }.getOrNull()
        val entries = transitionDao.getAllRulesOnce().map { rule ->
            TransitionRuleBackupEntry(
                id = rule.id,
                playlistId = rule.playlistId,
                fromTrackId = rule.fromTrackId,
                toTrackId = rule.toTrackId,
                settings = rule.settings,
                fromTrackKeys = rule.fromTrackId?.let { id ->
                    BackupSongCatalogResolver.keysFor(catalog?.itemAtLocator(id))
                },
                toTrackKeys = rule.toTrackId?.let { id ->
                    BackupSongCatalogResolver.keysFor(catalog?.itemAtLocator(id))
                },
            )
        }
        gson.toJson(entries)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        transitionDao.getAllRulesOnce().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) {
        restoreWithReport(payload, BackupRestoreMode.REPLACE)
    }

    override suspend fun restoreWithReport(
        payload: String,
        mode: BackupRestoreMode,
    ): BackupResolutionCounts = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, TransitionRuleBackupEntry::class.java).type
        val entries: List<TransitionRuleBackupEntry> = gson.fromJson(payload, type) ?: emptyList()
        val resolver = BackupSongCatalogResolver.loadIfAvailable(catalogSource)

        var byId = 0
        var byKeys = 0
        var unresolved = 0
        val resolvedRules = ArrayList<TransitionRuleEntity>(entries.size)

        for (entry in entries) {
            val settings = entry.settings ?: continue
            if (resolver == null) {
                resolvedRules.add(
                    TransitionRuleEntity(
                        id = entry.id,
                        playlistId = entry.playlistId,
                        fromTrackId = entry.fromTrackId,
                        toTrackId = entry.toTrackId,
                        settings = settings,
                    )
                )
                continue
            }

            var resolvedFrom = entry.fromTrackId
            var fromValid = true
            if (!entry.fromTrackId.isNullOrBlank()) {
                val res = resolver.resolve(entry.fromTrackId, null, entry.fromTrackKeys)
                when (res.way) {
                    SongResolutionWay.BY_ID -> byId++
                    SongResolutionWay.BY_KEYS -> byKeys++
                    SongResolutionWay.UNRESOLVED -> unresolved++
                }
                if (res.songId != null) {
                    resolvedFrom = res.songId
                } else if (entry.fromTrackKeys?.hasAny() == true || mode == BackupRestoreMode.MERGE) {
                    fromValid = false
                }
            }

            var resolvedTo = entry.toTrackId
            var toValid = true
            if (!entry.toTrackId.isNullOrBlank()) {
                val res = resolver.resolve(entry.toTrackId, null, entry.toTrackKeys)
                when (res.way) {
                    SongResolutionWay.BY_ID -> byId++
                    SongResolutionWay.BY_KEYS -> byKeys++
                    SongResolutionWay.UNRESOLVED -> unresolved++
                }
                if (res.songId != null) {
                    resolvedTo = res.songId
                } else if (entry.toTrackKeys?.hasAny() == true || mode == BackupRestoreMode.MERGE) {
                    toValid = false
                }
            }

            if (fromValid && toValid) {
                resolvedRules.add(
                    TransitionRuleEntity(
                        id = if (mode == BackupRestoreMode.MERGE) 0L else entry.id,
                        playlistId = entry.playlistId,
                        fromTrackId = resolvedFrom,
                        toTrackId = resolvedTo,
                        settings = settings,
                    )
                )
            }
        }

        if (mode == BackupRestoreMode.REPLACE) {
            transitionDao.replaceAllRules(resolvedRules)
        } else {
            val existingKeys = transitionDao.getAllRulesOnce().mapTo(HashSet()) {
                Triple(it.playlistId, it.fromTrackId, it.toTrackId)
            }
            val toInsert = resolvedRules.filter { rule ->
                existingKeys.add(Triple(rule.playlistId, rule.fromTrackId, rule.toTrackId))
            }
            if (toInsert.isNotEmpty()) {
                transitionDao.setRules(toInsert)
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

data class TransitionRuleBackupEntry(
    @SerializedName("id")
    val id: Long = 0,
    @SerializedName("playlistId")
    val playlistId: String = "",
    @SerializedName(value = "fromTrackId", alternate = ["fromSongId", "from_song_id"])
    val fromTrackId: String? = null,
    @SerializedName(value = "toTrackId", alternate = ["toSongId", "to_song_id"])
    val toTrackId: String? = null,
    @SerializedName("settings")
    val settings: TransitionSettings? = null,
    @SerializedName("fromTrackKeys")
    val fromTrackKeys: PortableSongKeys? = null,
    @SerializedName("toTrackKeys")
    val toTrackKeys: PortableSongKeys? = null,
)
