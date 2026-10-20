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
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.PLAYBACK_HISTORY

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val entries = playbackStatsRepository.exportEventsForBackup().map { event -> event.toBackupEntry() }
        gson.toJson(entries)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        playbackStatsRepository.exportEventsForBackup().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, PlaybackHistoryBackupEntry::class.java).type
        val entries: List<PlaybackHistoryBackupEntry> = gson.fromJson(payload, type)
        try {
            playbackStatsRepository.importEventsFromBackup(
                events = entries.map { entry -> entry.toPlaybackEvent() },
                clearExisting = true
            )
        } catch (e: PlaybackHistoryWriteException) {
            // B35 (RT-03): a history the disk refused is a failed module, with its own words: the executor rolls back and says so.
            throw BackupMessageException(BackupMessage.of(R.string.backup_error_history_write_failed)).apply { initCause(e) }
        }
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)
}
