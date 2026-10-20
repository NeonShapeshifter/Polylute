package com.lostf1sh.pixelplayeross.data.metadata

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.worker.SyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * FIX-3 (B11-fix): the library's one-off, idempotent repairs, run in the background.
 *
 * 1. **Track numbers.** `track_number >= 1000` is MediaStore's `disc * 1000 + track` stored as the
 *    track (shown as "1-1012"); one SQL statement unpacks it ([MusicDao.repairPackedTrackNumbers]).
 * 2. **Years.** Songs with no year get it from the file's tags ([YearRepairRun]); the albums they are on
 *    are recomputed.
 *
 * Runs when the app starts and every time a library sync finishes (new songs arrive with no year), and
 * never while one is running. Only the `track_number`, `disc_number`, `year` columns of songs and the
 * `year` of albums are written; no deletion, no identity, no user state.
 */
@Singleton
class LibraryMetadataRepair @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    private val syncManager: SyncManager,
) {
    private val mutex = Mutex()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            syncManager.isSyncing
                .distinctUntilChanged()
                .filter { syncing -> !syncing }
                .collect { runOnce() }
        }
    }

    /** Both repairs, once. Safe to call at any time and from anywhere: serialised, idempotent. */
    suspend fun runOnce() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val unpacked = runCatching { musicDao.repairPackedTrackNumbers() }
                .onFailure { Timber.tag(TAG).w(it, "Track number repair failed") }
                .getOrDefault(0)
            if (unpacked > 0) {
                Timber.tag(TAG).i(
                    "Track repair: %d songs had a packed disc*1000+track and were unpacked (%d ms)",
                    unpacked, System.currentTimeMillis() - startedAt
                )
            }

            val yearStartedAt = System.currentTimeMillis()
            val cache = YearRepairCache(prefs())
            // B15-F6: a migration whose commit failed (the disk is full) throws from here. It used to leave `runOnce`
            // and end the collector of `start` (and, in a scope with no handler, the app): the years would then
            // never be repaired on that install. It is retried at the next sync or start instead.
            val cached = runCatching { cache.load() }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Timber.tag(TAG).w(it, "Year repair cache could not be migrated; the year repair waits for the next run")
                }
                .getOrNull() ?: return@withContext
            val attempts = cached.noYear
            val readFailures = cached.failures
            val result = runCatching {
                YearRepairRun.run(
                    attempts = attempts,
                    page = { afterId, limit -> musicDao.songsWithoutYear(afterId, limit) },
                    read = { candidate -> readYear(candidate.filePath) },
                    store = { years, albumIds ->
                        // Leaves the disk a moment between pages: this is background work.
                        delay(PAGE_PAUSE_MS)
                        musicDao.applyYearPage(years, albumIds)
                    },
                    saveAttempts = { cache.save(cached) },
                    readFailures = readFailures,
                )
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Timber.tag(TAG).w(it, "Year repair failed")
            }.getOrNull()
            val zeroAlbums = runCatching { musicDao.refreshZeroAlbumYears() }.getOrDefault(0)
            if (result != null && (result.scanned > 0 || result.filled > 0 || zeroAlbums > 0)) {
                Timber.tag(TAG).i(
                    "Year repair: scanned %d, filled %d, no year tag %d, unreadable %d, known-no-year %d, " +
                        "albums fixed %d, in %d ms (read failed %d to retry, %d given up)",
                    result.scanned, result.filled, result.noYearTag, result.unreadable,
                    result.skippedKnownNoYear, zeroAlbums, System.currentTimeMillis() - yearStartedAt,
                    result.readFailed, result.gaveUp
                )
            }
        }
    }

    private fun readYear(path: String): YearRepairRun.Read {
        val file = File(path)
        if (!file.isFile) return YearRepairRun.Read.Unreadable
        // B12a-8 #4: "the tag is not there" and "the read failed" are different facts (readYear lost the difference).
        return when (val read = AudioMetadataReader.readYearTag(file)) {
            is AudioMetadataReader.YearTagRead.Found -> YearRepairRun.Read.Year(read.year)
            AudioMetadataReader.YearTagRead.NoYearTag -> YearRepairRun.Read.NoYear
            AudioMetadataReader.YearTagRead.Failed -> YearRepairRun.Read.ReadFailed
        }
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val TAG = "LibraryRepair"
        const val PREFS = "library_year_repair"
        const val PAGE_PAUSE_MS = 25L
    }
}
