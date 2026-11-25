package com.lostf1sh.pixelplayeross.data.backup.module

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessage
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessageException
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.PlaybackHistoryBackupEntry
import com.lostf1sh.pixelplayeross.data.backup.model.toBackupEntry
import com.lostf1sh.pixelplayeross.data.backup.model.toPlaybackEvent
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupResolutionCounts
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupRestoreMode
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupSongCatalogResolver
import com.lostf1sh.pixelplayeross.data.backup.restore.EmptyCatalogSource
import com.lostf1sh.pixelplayeross.data.backup.restore.PortableSongKeys
import com.lostf1sh.pixelplayeross.data.backup.restore.SongResolutionWay
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.PlaybackHistoryWriteException
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import com.lostf1sh.pixelplayeross.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackHistoryModuleHandler @Inject constructor(
    private val playbackStatsRepository: PlaybackStatsRepository,
    @BackupGson private val gson: Gson,
    private val catalogSource: CatalogSource,
) : BackupModuleHandler, ReportableRestoreHandler {

    constructor(
        playbackStatsRepository: PlaybackStatsRepository,
        gson: Gson,
    ) : this(playbackStatsRepository, gson, EmptyCatalogSource)

    override val section = BackupSection.PLAYBACK_HISTORY

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val catalog = runCatching { catalogSource.load() }.getOrNull()
        val entries = playbackStatsRepository.exportEventsForBackup().map { event ->
            val item = catalog?.let {
                it.itemAtLocator(event.songId)
                    ?: event.libraryItemId?.takeIf { id -> id.isNotBlank() }?.let(it::item)
            }
            event.toBackupEntry(BackupSongCatalogResolver.keysFor(item))
        }
        gson.toJson(entries)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        playbackStatsRepository.exportEventsForBackup().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) {
        restoreWithReport(payload, BackupRestoreMode.REPLACE)
    }

    override suspend fun restoreWithReport(
        payload: String,
        mode: BackupRestoreMode,
    ): BackupResolutionCounts = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, PlaybackHistoryBackupEntry::class.java).type
        val entries: List<PlaybackHistoryBackupEntry> = gson.fromJson(payload, type) ?: emptyList()
        val resolver = BackupSongCatalogResolver.loadIfAvailable(catalogSource)

        var byId = 0
        var byKeys = 0
        var unresolved = 0
        val eventsToImport = ArrayList<PlaybackStatsRepository.PlaybackEvent>(entries.size)

        for (entry in entries) {
            if (resolver == null) {
                eventsToImport.add(entry.toPlaybackEvent())
                continue
            }
            val keys = PortableSongKeys(
                title = entry.title,
                artist = entry.artist,
                album = entry.album,
                durationMs = entry.trackDurationMs ?: entry.itemDurationMs,
                path = entry.path,
            )
            val resolved = resolver.resolve(
                backupSongId = entry.songId,
                backupLibraryItemId = entry.libraryItemId,
                keys = keys,
            )
            when (resolved.way) {
                SongResolutionWay.BY_ID -> byId++
                SongResolutionWay.BY_KEYS -> byKeys++
                SongResolutionWay.UNRESOLVED -> unresolved++
            }
            if (resolved.songId != null) {
                eventsToImport.add(
                    entry.toPlaybackEvent().copy(
                        songId = resolved.songId,
                        libraryItemId = resolved.libraryItemId ?: entry.libraryItemId,
                    )
                )
            } else if (!keys.hasAny() && mode == BackupRestoreMode.REPLACE) {
                eventsToImport.add(entry.toPlaybackEvent())
            }
        }

        try {
            playbackStatsRepository.importEventsFromBackup(
                events = eventsToImport,
                clearExisting = (mode == BackupRestoreMode.REPLACE),
            )
        } catch (e: PlaybackHistoryWriteException) {
            // B35 (RT-03): a history the disk refused is a failed module, with its own words: the executor rolls back and says so.
            throw BackupMessageException(BackupMessage.of(R.string.backup_error_history_write_failed)).apply { initCause(e) }
        }

        BackupResolutionCounts(
            resolvedById = byId,
            resolvedByKeys = byKeys,
            unresolved = unresolved,
        )
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)
}
