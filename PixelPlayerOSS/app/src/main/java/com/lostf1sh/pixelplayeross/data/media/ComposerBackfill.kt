package com.lostf1sh.pixelplayeross.data.media

import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SongComposerEntity
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** What reading a file's COMPOSER tag gave. */
sealed interface ComposerRead {
    data class Found(val composer: String) : ComposerRead
    data object None : ComposerRead
    data object Unreadable : ComposerRead
}

/**
 * B56 phase 6: reads the COMPOSER tag of the songs that have no composer row yet, a batch at a time, so composers can be searched.
 *
 * It runs at the end of every library sync and does at most [BATCH] songs, so the first sync after the update reads the library over several syncs instead of making one long
 * one; a library with nothing left to read costs one query. A file that is gone or cannot be read is recorded as "no composer" (the song is not asked for again until the sync
 * changes it, which removes its row). The tag read is TagLib's, the one the sync already makes for MusicBrainz ids; [read] is a parameter so the tests need no native library.
 */
class ComposerBackfill(
    private val dao: MusicDao,
    private val read: (File) -> ComposerRead = ComposerBackfill::readWithTagLib,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Reads one batch; the number of songs done (0 when nothing is left). */
    suspend fun runOnce(limit: Int = BATCH): Int {
        val rows = dao.songsNeedingComposer(limit)
        if (rows.isEmpty()) return 0
        val at = now()
        dao.upsertComposers(rows.map { row ->
            val file = File(row.path)
            val composer = if (!file.isFile) "" else when (val result = read(file)) {
                is ComposerRead.Found -> result.composer
                ComposerRead.None, ComposerRead.Unreadable -> ""
            }
            SongComposerEntity(songId = row.songId, composer = composer, readAt = at)
        })
        return rows.size
    }

    /** What [runToCompletion] did: how many songs it read and in how many batches. */
    data class Run(val songs: Int, val batches: Int, val pausedForSyncMs: Long)

    /**
     * B57 4.5: reads **every** song that still has no composer row, batch after batch, until none is left (B56 did 400 per sync, so a 4,953-song library took a dozen syncs before most
     * composers could be searched). It is made to be cheap for a player that is playing:
     *  - small batches ([BACKGROUND_BATCH]) with a pause between them ([pauseMs]), so file reads never form one long burst;
     *  - [lowerPriority] runs first (the caller puts its thread at background priority);
     *  - it **waits while a sync runs** ([isSyncRunning]): the sync has its own reads and its own database work;
     *  - what is left is whatever the database says (songs with no `song_composers` row), so it **resumes after a restart** with nothing to remember, and a library with nothing left costs
     *    one query.
     * Never on the main thread: the callers run it on `Dispatchers.IO` ([ComposerBackfillRunner]).
     */
    suspend fun runToCompletion(
        isSyncRunning: suspend () -> Boolean = { false },
        batch: Int = BACKGROUND_BATCH,
        pauseMs: Long = PAUSE_MS,
        syncWaitMs: Long = SYNC_WAIT_MS,
        lowerPriority: () -> Unit = {},
    ): Run {
        lowerPriority()
        var songs = 0
        var batches = 0
        var paused = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            while (isSyncRunning()) {
                delay(syncWaitMs)
                paused += syncWaitMs
                currentCoroutineContext().ensureActive()
            }
            val done = runOnce(batch)
            if (done == 0) break
            songs += done
            batches++
            delay(pauseMs)
        }
        return Run(songs, batches, paused)
    }

    companion object {
        const val BATCH = 400

        /** The size of a batch of the background fill. */
        const val BACKGROUND_BATCH = 100
        const val PAUSE_MS = 50L
        const val SYNC_WAIT_MS = 2_000L

        private fun readWithTagLib(file: File): ComposerRead = AudioMetadataReader.readComposer(file)
    }
}
