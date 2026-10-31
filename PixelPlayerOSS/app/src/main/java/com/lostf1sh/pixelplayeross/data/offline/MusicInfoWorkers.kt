package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lostf1sh.pixelplayeross.data.network.RequestPriority
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@HiltWorker
class MusicInfoFindWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val downloads: MusicInfoDownloads,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(infoBackground + RequestPriority(background = true)) {
        downloads.initialize()
        val deadline = android.os.SystemClock.elapsedRealtime() + 4 * 60_000L
        repeat(25) {
            currentCoroutineContext().ensureActive()
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                downloads.enqueueFind()
                return@withContext Result.success()
            }
            val row = downloads.dao.nextCheck() ?: return@withContext Result.success()
            downloads.checkOne(row)
        }
        if (downloads.dao.nextCheck() != null) downloads.enqueueFind()
        Result.success()
    }
}

@HiltWorker
class MusicInfoQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val downloads: MusicInfoDownloads,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(infoBackground + RequestPriority(background = true)) {
        downloads.initialize()
        val deadline = android.os.SystemClock.elapsedRealtime() + 4 * 60_000L
        repeat(25) {
            currentCoroutineContext().ensureActive()
            if (downloads.dao.queueOnce()?.paused == true) return@withContext Result.success()
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                downloads.enqueueDownloads()
                return@withContext Result.success()
            }
            val row = downloads.dao.nextDownload(System.currentTimeMillis())
            if (row == null) {
                downloads.dao.nextRetry()?.let { at -> downloads.enqueueDownloads((at - System.currentTimeMillis()).coerceAtLeast(0)) }
                return@withContext Result.success()
            }
            downloads.downloadOne(row)
        }
        downloads.dao.nextRetry()?.let { at -> downloads.enqueueDownloads((at - System.currentTimeMillis()).coerceAtLeast(0)) }
        Result.success()
    }
}
