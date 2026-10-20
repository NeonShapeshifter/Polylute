package com.lostf1sh.pixelplayeross.data.metadata

import com.lostf1sh.pixelplayeross.data.database.YearRepairCandidate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * FIX-3 (B11-fix): fills in the release year of songs that have none, from the files' own tags.
 *
 * On the owner's library 4867 of 4870 songs had `year = 0`: MediaStore's `YEAR` is NULL for these FLACs
 * and the scan (rightly) reads no tags. This walks the songs without a year in pages, reads **only
 * the year** from each file and stores it.
 *
 * - **Never blocks anything:** it is a background job over pages (one DAO call and one transaction per
 *   page), cooperative at every page and every file, so cancelling it is immediate.
 * - **Resumable without state:** a song that got a year is no longer a candidate, so an interrupted
 *   run simply continues with what is left. Pagination is by id within one run.
 * - **Does not read the same hopeless file twice:** a file with no year tag stays a candidate for ever,
 *   so it is remembered as `id:dateModified` ([attempts]); a re-tagged file changes `dateModified`
 *   and is asked again. A file that cannot be read now (missing, volume out) is *not* remembered.
 * - **Tells "no year" from "could not be read" (B12a-8 #4):** a read that failed (an I/O error, a busy file) used to
 *   come back as "no year" and be remembered as such, so one hiccup meant the song never got its year. A failed read
 *   ([Read.ReadFailed]) is counted per `id:dateModified` ([readFailures]) and asked again, up to
 *   [MAX_READ_FAILURES] times; a file that fails every time is then retained as an exhausted failure, so it is not
 *   opened for ever (it is asked again when the file changes).
 * - **Never overwrites:** the store only sets a year where there is none (`fillYear`).
 *
 * Pure apart from the lambdas: the page query, the tag read and the page write are arguments.
 */
internal object YearRepairRun {
    const val PAGE_SIZE = 100

    /** A file whose read fails this many runs in a row is not retried until it changes. */
    const val MAX_READ_FAILURES = 3

    /** What reading one file's year gave. */
    sealed interface Read {
        data class Year(val year: Int) : Read

        /** The file was read and carries no valid year. */
        data object NoYear : Read

        /** The file is not there to read now (missing, its volume is out); ask again another time, without counting. */
        data object Unreadable : Read

        /** The file is there and the read failed (I/O, busy, the tag library gave nothing): ask again, a few times. */
        data object ReadFailed : Read
    }

    data class Result(
        val scanned: Int,
        val filled: Int,
        val noYearTag: Int,
        val unreadable: Int,
        val skippedKnownNoYear: Int,
        /** Reads that failed this run and will be asked again (B12a-8 #4). */
        val readFailed: Int = 0,
        /** Files given up on this run after [MAX_READ_FAILURES] failed reads. */
        val gaveUp: Int = 0,
    )

    fun attemptKey(candidate: YearRepairCandidate): String = "${candidate.id}:${candidate.dateModified}"

    suspend fun run(
        attempts: MutableSet<String>,
        page: suspend (afterId: Long, limit: Int) -> List<YearRepairCandidate>,
        read: (YearRepairCandidate) -> Read,
        store: suspend (years: List<Pair<Long, Int>>, albumIds: Collection<Long>) -> Int,
        saveAttempts: () -> Unit,
        pageSize: Int = PAGE_SIZE,
        /** Failed reads so far per `id:dateModified`; persisted by [saveAttempts] with [attempts]. */
        readFailures: MutableMap<String, Int> = mutableMapOf(),
        maxReadFailures: Int = MAX_READ_FAILURES,
    ): Result {
        var afterId = 0L
        var scanned = 0
        var filled = 0
        var noYear = 0
        var unreadable = 0
        var skipped = 0
        var readFailed = 0
        var gaveUp = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val candidates = page(afterId, pageSize)
            if (candidates.isEmpty()) break
            afterId = candidates.last().id

            val years = ArrayList<Pair<Long, Int>>()
            val albumIds = HashSet<Long>()
            var attemptsChanged = false
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()
                val key = attemptKey(candidate)
                if (key in attempts) {
                    skipped++
                    continue
                }
                if ((readFailures[key] ?: 0) >= maxReadFailures) {
                    gaveUp++
                    continue
                }
                scanned++
                when (val result = read(candidate)) {
                    is Read.Year -> {
                        years += candidate.id to result.year
                        albumIds += candidate.albumId
                        if (readFailures.remove(key) != null) attemptsChanged = true
                    }
                    Read.NoYear -> {
                        noYear++
                        attempts += key
                        readFailures.remove(key)
                        attemptsChanged = true
                    }
                    Read.Unreadable -> unreadable++
                    Read.ReadFailed -> {
                        val failures = (readFailures[key] ?: 0) + 1
                        if (failures >= maxReadFailures) {
                            // Keep exhausted failures separate from confirmed absence.
                            gaveUp++
                            readFailures[key] = maxReadFailures
                        } else {
                            readFailed++
                            readFailures[key] = failures
                        }
                        attemptsChanged = true
                    }
                }
            }
            if (years.isNotEmpty()) filled += store(years, albumIds)
            if (attemptsChanged) saveAttempts()
            if (candidates.size < pageSize) break
        }
        return Result(scanned, filled, noYear, unreadable, skipped, readFailed, gaveUp)
    }
}
