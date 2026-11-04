package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The undo window and snapshots survive process loss. Physical deletion happens only at expiry. */
internal class MusicInfoUndo(
    private val context: Context,
    private val database: PixelPlayerDatabase,
    private val info: MusicInfoRepository,
    private val lyrics: LyricsRepository,
    private val music: MusicRepository,
    private val control: Mutex,
    private val enqueue: () -> Unit,
) {
    private val dao get() = database.musicInfoDiscoveryDao()

    suspend fun recover() {
        val actions = dao.pendingUndoActions()
        // Room is authoritative after process loss, including a crash between staging a hold and its snapshot.
        info.deletedDownloads.restoreHolds(actions.filter { it.action == "DELETE" }.associate { action ->
            action.token to dao.undoItems(action.token).map { it.key }
        })
        actions.forEach { schedule(it) }
    }

    suspend fun delete(rows: List<InfoDiscovery>, knownBytes: Long? = null): InfoUndo = control.withLock {
        begin("DELETE", rows.mapNotNull { dao.get(it.key) }.filter { it.saved }, knownBytes)
    }

    suspend fun cancel(): InfoUndo = control.withLock {
        val action = info.deletedDownloads.withCommitLock { begin("CANCEL", dao.pendingDownloads()) }
        WorkManager.getInstance(context).cancelUniqueWork(MusicInfoDownloads.DOWNLOAD_WORK).result.get()
        action
    }

    suspend fun clearSelection(scope: String): InfoUndo = control.withLock {
        val action = database.withTransaction {
            val totals = dao.clearTotals(scope)
            val action = InfoUndo(UUID.randomUUID().toString(), "CLEAR", System.currentTimeMillis() + WINDOW_MS,
                totals.downloads, totals.knownBytes ?: 0)
            if (action.count > 0) {
                dao.insertUndo(action)
                dao.captureClear(scope, action.token)
                dao.clearSelection(scope)
            }
            action
        }
        if (action.count > 0) schedule(action)
        action
    }

    private suspend fun begin(kind: String, rows: List<InfoDiscovery>, knownBytes: Long? = null): InfoUndo {
        val token = UUID.randomUUID().toString()
        val action = InfoUndo(token, kind, System.currentTimeMillis() + WINDOW_MS, rows.size, knownBytes ?: rows.sumOf { it.sourceBytes ?: 0 })
        if (rows.isEmpty()) return action
        if (kind == "DELETE") info.deletedDownloads.hold(token, rows.map { it.key })
        try {
            database.withTransaction {
                dao.insertUndo(action)
                dao.insertUndoItems(rows.map { row -> InfoUndoItem(token, row.key, row.kind, row.title,
                    row.albumArtist.ifBlank { row.artistName }, row.songId, row.downloadState, row.selected, row.saved,
                    row.attempts, row.retryAt, row.downloadEpoch + 1, row.deletedByUser) })
                for (row in rows) {
                    when (kind) {
                        "DELETE" -> dao.pendingDelete(row.key)
                        "CLEAR" -> dao.setSelection(row.key, false)
                        else -> dao.cancelDownload(row.key)
                    }
                }
            }
        } catch (error: Exception) {
            if (kind == "DELETE") info.deletedDownloads.releaseHold(token)
            throw error
        }
        schedule(action)
        return action
    }

    suspend fun undo(token: String): Boolean = control.withLock {
        val action = dao.undoAction(token) ?: return@withLock false
        if (action.untilAt <= System.currentTimeMillis()) {
            finish(action)
            return@withLock false
        }
        info.deletedDownloads.withCommitLock {
            database.withTransaction {
                dao.undoItems(token).forEach { item ->
                    if (action.action == "CLEAR") dao.setSelection(item.key, item.selected)
                    else dao.restoreUndoItem(item.key, item.epoch, item.saved, item.selected,
                        if (item.state == "DOWNLOADING") "QUEUED" else item.state, item.attempts, item.retryAt, item.deleted)
                }
            }
        }
        if (action.action == "DELETE") info.deletedDownloads.releaseHold(token)
        database.withTransaction { dao.removeUndoItems(token); dao.removeUndo(token) }
        if (dao.queueOnce()?.paused != true && dao.pendingDownloads().isNotEmpty()) enqueue()
        true
    }

    suspend fun finishExpired(now: Long = System.currentTimeMillis()) = control.withLock {
        var firstFailure: Exception? = null
        dao.pendingUndoActions().filter { it.untilAt <= now }.forEach { action ->
            try { finish(action) }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                dao.undoFailed(action.token)
                if (firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
    }

    suspend fun retryDelete(token: String, now: Long) = control.withLock {
        val action = dao.undoAction(token) ?: return@withLock
        check(action.untilAt <= now) { "The Undo window is still open" }
        try { finish(action) }
        catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            dao.undoFailed(token)
            throw error
        }
    }

    private suspend fun finish(action: InfoUndo) {
        if (action.action == "DELETE") {
            for (item in dao.undoItems(action.token)) {
                val current = dao.get(item.key) ?: continue
                // An explicit later download owns a new generation and supersedes this deletion.
                if (current.downloadEpoch != item.epoch || !current.deletedByUser) continue
                info.deletedDownloads.mark(listOf(item.key))
                when {
                    item.title.startsWith("cache:") -> info.deleteLegacyEntry(item.title.removePrefix("cache:"))
                    item.kind == InfoKind.LYRICS.name -> lyrics.deleteDownloadedLyrics(song(item))
                    else -> info.deleteDiscovered(InfoKind.valueOf(item.kind), item.title, item.artist, item.songId)
                }
            }
            info.deletedDownloads.releaseHold(action.token)
        }
        database.withTransaction { dao.removeUndoItems(action.token); dao.removeUndo(action.token) }
    }

    private suspend fun song(item: InfoUndoItem): Song {
        val identity = item.key.removePrefix("lyrics|").takeIf { !it.startsWith("locator:") }.orEmpty()
        val locator = identity.takeIf { it.isNotEmpty() }?.let { database.musicDao().currentLocatorFor(it)?.toString() } ?: item.songId
        return music.getSongsByIds(listOf(locator)).first().firstOrNull()
            ?: Song.emptySong().copy(id = item.songId, libraryItemId = identity, title = item.title)
    }

    private fun schedule(action: InfoUndo) {
        val request = OneTimeWorkRequestBuilder<MusicInfoUndoWorker>()
            .setInitialDelay((action.untilAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("music-info-undo-${action.token}", ExistingWorkPolicy.REPLACE, request)
    }

    companion object { const val WINDOW_MS = 5_000L }
}
