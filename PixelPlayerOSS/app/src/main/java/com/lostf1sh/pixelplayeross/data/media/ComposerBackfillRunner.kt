package com.lostf1sh.pixelplayeross.data.media

import android.os.Process
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.worker.SyncManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * B57 4.5: starts the composer fill when the app starts and finishes it in the background, off the main thread, at background priority, pausing while a library sync runs (see
 * [ComposerBackfill.runToCompletion]). A restart simply starts it again: what is left is what the database says.
 */
@Singleton
class ComposerBackfillRunner @Inject constructor(
    private val dao: MusicDao,
    private val syncManager: SyncManager,
) {
    private val gate = Mutex()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            if (!gate.tryLock()) return@launch
            try {
                val run = withContext(Dispatchers.IO) {
                    ComposerBackfill(dao).runToCompletion(
                        isSyncRunning = { runCatching { syncManager.isSyncing.first() }.getOrDefault(false) },
                        lowerPriority = { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) },
                    )
                }
                if (run.songs > 0) Timber.tag("ComposerBackfill").i("read the composer of %d songs in %d batches (paused %d ms for syncs)", run.songs, run.batches, run.pausedForSyncMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("ComposerBackfill").w(e, "composer fill stopped; it resumes at the next start")
            } finally {
                gate.unlock()
            }
        }
    }
}
