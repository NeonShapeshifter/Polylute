package com.lostf1sh.pixelplayeross.data.offline

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
class OfflineDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val downloads: OfflineDownloads,
    private val music: MusicRepository,
    private val lyrics: LyricsRepository,
    private val info: MusicInfoRepository,
    private val dao: MusicDao,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { runBatch() }

    private fun hasLocalCover(song: Song): Boolean {
        val uri = song.albumArtUriString?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            if (LocalArtworkUri.isLocalArtworkUri(uri)) {
                // A lazy locator exists even when embedded-art extraction has never been attempted.
                val songId = LocalArtworkUri.parseSongId(uri) ?: return false
                AlbumArtUtils.ensureAlbumArtCachedFile(applicationContext, songId, song.path)
                    ?.let { it.isFile && it.length() > 0L } == true
            } else {
                AlbumArtUtils.openArtworkInputStream(applicationContext, uri.toUri())?.use { it.read() >= 0 } == true
            }
        }.getOrDefault(false)
    }

    private suspend fun runBatch(): Result {
        val id = inputData.getString("batch") ?: return Result.failure()
        val batch = downloads.load(id) ?: return Result.success()
        downloads.update(id) { if (it.active) it.copy(status = OfflineStatus.RUNNING) else it }
        val deadline = SystemClock.elapsedRealtime() + 4 * 60_000L
        try {
            val pending = batch.targets.indices.filter { it !in batch.completed }.take(25)
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
                            var available = 0; var unavailable = 0; var failed = 0
                            try {
                                val found = when (target.kind) {
                                    OfflineKind.LYRICS -> songs[target.songId]?.let { lyrics.prepareForOffline(it) } != null
                                    OfflineKind.ARTIST_PHOTOS -> {
                                        val photo = info.service.fetchArtistPhoto(target.title, userRequested = true)
                                        if (photo != null) dao.getArtistIdByNormalizedName(target.title)?.let { dao.updateArtistImageUrl(it, photo.path) }
                                        photo != null
                                    }
                                    OfflineKind.COVERS -> {
                                        val song = songs[target.songId]
                                        if (song == null) false
                                        else if (hasLocalCover(song)) true
                                        else {
                                            val cover = info.service.fetchCover(target.title, target.albumArtist.orEmpty(), userRequested = true)
                                            if (cover != null) song.id.toLongOrNull()?.let { info.downloadedCoverArrived(it) }
                                            cover != null
                                        }
                                    }
                                    OfflineKind.DESCRIPTIONS -> info.service.fetchAbout(
                                        if (target.album) AboutKind.ALBUM else AboutKind.ARTIST, target.title,
                                        target.albumArtist, userRequested = true) != null
                                }
                                if (found) available++ else unavailable++
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { failed++ }
                            coroutineContext.ensureActive()
                            if (!downloads.record(id, index, OfflineResult(target.title, available, unavailable, failed))) break
                        }
                    }
                } }.awaitAll()
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
}
