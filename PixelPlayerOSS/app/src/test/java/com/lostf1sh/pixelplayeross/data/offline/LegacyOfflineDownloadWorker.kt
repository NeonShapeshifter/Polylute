package com.lostf1sh.pixelplayeross.data.offline

import com.lostf1sh.pixelplayeross.data.network.RemoteOutcome
import android.content.Context
import android.os.SystemClock
import androidx.core.net.toUri
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.musicinfo.AboutKind
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext

/** Bounded slices are chained under one persistent task, so a library does not depend on one long-lived worker. */
@HiltWorker
class LegacyOfflineDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val downloads: LegacyOfflineDownloads,
    private val music: MusicRepository,
    private val lyrics: LyricsRepository,
    private val info: MusicInfoRepository,
    private val dao: MusicDao,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO + com.lostf1sh.pixelplayeross.data.network.RequestPriority(background = true)) { runBatch() }

    private suspend fun runBatch(): Result {
        val id = inputData.getString("batch") ?: return Result.failure()
        val batch = downloads.load(id) ?: return Result.success()
        return withContext(com.lostf1sh.pixelplayeross.data.network.DownloadOwner(batch.planId) { key -> downloads.claim(batch.planId, key) }) {
            runOwnedBatch(id, batch)
        }
    }

    private suspend fun runOwnedBatch(id: String, batch: OfflineBatch): Result {
        downloads.update(id) { if (it.active) it.copy(status = OfflineStatus.RUNNING) else it }
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + 4 * 60_000L
        try {
            val pending = batch.targets.indices.filter { it !in batch.completed && (batch.retries[it]?.readyAtMs ?: 0L) <= System.currentTimeMillis() }.take(25)
            val ids = pending.mapNotNull { batch.targets[it].songId }.distinct()
            val songs = if (ids.isEmpty()) emptyMap() else music.getSongsByIds(ids).first().associateBy { it.id }
            val lanes = Semaphore(2)
            coroutineScope {
                pending.groupBy { batch.targets[it].kind }.values.map { indices -> async {
                    lanes.withPermit {
                        for (index in indices) {
                            coroutineContext.ensureActive()
                            if (isStopped || downloads.load(id) == null || SystemClock.elapsedRealtime() >= deadline) break
                            val target = batch.targets[index]
                            val result = fetchResult(batch, target, songs[target.songId])
                            coroutineContext.ensureActive()
                            if (!downloads.record(id, index, result)) break
                        }
                    }
                } }.awaitAll()
            }
            downloads.update(id) { current ->
                val count = current.next - batch.next
                if (count > 0) current.copy(measuredMs = current.measuredMs + SystemClock.elapsedRealtime() - startedAt,
                    measuredCount = current.measuredCount + count) else current
            }
            val current = downloads.load(id) ?: return Result.success()
            if (current.next >= current.total) downloads.update(id) { it.copy(status = OfflineStatus.COMPLETE) }
            else downloads.continueLater(id)
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled // WorkManager resumes the same persistent cursor when constraints return.
        } catch (error: Exception) {
            downloads.update(id) { it.copy(status = OfflineStatus.PAUSED) }
            return Result.failure()
        }
    }
    private suspend fun fetchResult(batch: OfflineBatch, target: OfflineTarget, song: Song?): OfflineResult {
        if (downloads.wasDeleted(target, song)) return deletedResult(target)
        val cacheKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val owner = com.lostf1sh.pixelplayeross.data.network.DownloadOwner(batch.planId) { key ->
            cacheKeys += key
            downloads.claim(batch.planId, key)
        }
        val outcome = try {
            withContext(owner) {
                RemoteOutcome.lookup { fetchTarget(target, song).takeIf { it } }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RemoteOutcome.Failed
        }
        coroutineContext.ensureActive()
        if (downloads.wasDeleted(target, song)) return deletedResult(target)
        return when (outcome) {
            is RemoteOutcome.Found -> OfflineResult(target.title, 1, 0, 0, cacheKeys.toSet())
            RemoteOutcome.NotFound -> OfflineResult(target.title, 0, 1, 0, cacheKeys.toSet())
            RemoteOutcome.Failed -> OfflineResult(target.title, 0, 0, 1, cacheKeys.toSet())
        }
    }

    private fun deletedResult(target: OfflineTarget): OfflineResult {
        timber.log.Timber.tag("LegacyOfflineDownloads").i("Skipped %s target deleted by you", target.kind)
        return OfflineResult(target.title, 0, 0, 0, deletedByUser = true)
    }

    private suspend fun fetchTarget(target: OfflineTarget, song: Song?): Boolean = when (target.kind) {
        OfflineKind.LYRICS -> song?.let { lyrics.prepareForOffline(it) } != null
        OfflineKind.ARTIST_PHOTOS -> {
            val photo = info.service.fetchArtistPhoto(target.title, userRequested = true)
            if (photo != null) {
                info.deletedDownloads.commitIfAllowed(com.lostf1sh.pixelplayeross.data.musicinfo.DownloadKeys.photo(target.title)) {
                    dao.getArtistIdByNormalizedName(target.title)?.let { dao.updateArtistImageUrl(it, photo.path) }
                }
            }
            photo != null
        }
        OfflineKind.COVERS -> {
            if (song == null) {
                false
            } else if (OfflineArtwork.hasLocalCover(applicationContext, song)) {
                true
            } else {
                val cover = info.service.fetchCover(target.title, target.albumArtist.orEmpty(), userRequested = true)
                if (cover != null) song.id.toLongOrNull()?.let { info.downloadedCoverArrived(it) }
                cover != null
            }
        }
        OfflineKind.DESCRIPTIONS -> info.service.fetchAbout(
            if (target.album) AboutKind.ALBUM else AboutKind.ARTIST,
            target.title,
            target.albumArtist,
            userRequested = true,
        ) != null
    }

}
