package com.lostf1sh.pixelplayeross.data.metadata

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.YearRepairCandidate
import com.lostf1sh.pixelplayeross.data.metadata.YearRepairRun.Read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * FIX-3: the year repair walks the library in pages, reads only the year, is resumable without state
 * and does not read a hopeless file twice. The store and the tag read are fakes.
 */
class YearRepairRunTest {

    private fun songs(n: Int, albumOf: (Int) -> Long = { it / 10L }) =
        (1..n).map { YearRepairCandidate(id = it.toLong(), filePath = "/m/$it.flac", albumId = albumOf(it), dateModified = 100L) }

    /** The set of songs still without a year; filling one removes it, as the real store does. */
    private class FakeLibrary(initial: List<YearRepairCandidate>) {
        val withoutYear = initial.toMutableList()
        val filled = mutableMapOf<Long, Int>()
        var pageCalls = 0
        var storeCalls = 0
        val albumsTouched = mutableSetOf<Long>()

        fun page(afterId: Long, limit: Int): List<YearRepairCandidate> {
            pageCalls++
            return withoutYear.filter { it.id > afterId }.sortedBy { it.id }.take(limit)
        }

        fun store(years: List<Pair<Long, Int>>, albums: Collection<Long>): Int {
            storeCalls++
            var n = 0
            for ((id, year) in years) {
                if (withoutYear.removeAll { it.id == id }) { filled[id] = year; n++ }
            }
            albumsTouched += albums
            return n
        }
    }

    @Test
    fun `every song without a year is read once and filled, in pages`() = runTest {
        val library = FakeLibrary(songs(3000))
        val reads = mutableListOf<Long>()
        val result = YearRepairRun.run(
            attempts = mutableSetOf(),
            page = { after, limit -> library.page(after, limit) },
            read = { reads += it.id; Read.Year(1990 + (it.id % 10).toInt()) },
            store = { years, albums -> library.store(years, albums) },
            saveAttempts = {},
        )
        assertThat(result.scanned).isEqualTo(3000)
        assertThat(result.filled).isEqualTo(3000)
        assertThat(reads).hasSize(3000)
        assertThat(reads).containsNoDuplicates()
        assertThat(library.withoutYear).isEmpty()
        // 3000 songs at 100 a page: a bounded number of store calls, not one per song.
        assertThat(library.storeCalls).isEqualTo(30)
        assertThat(library.albumsTouched).isNotEmpty()
    }

    @Test
    fun `an interrupted run is resumed by running again, and what was filled is not read again`() = runTest {
        val library = FakeLibrary(songs(500))
        val reads = mutableListOf<Long>()
        // The run is cut off in the middle of the third page (the process ended, the job was cancelled).
        try {
            YearRepairRun.run(
                attempts = mutableSetOf(),
                page = { after, limit -> library.page(after, limit) },
                read = { if (it.id == 250L) throw CancellationException("cut off"); reads += it.id; Read.Year(2000) },
                store = { years, albums -> library.store(years, albums) },
                saveAttempts = {},
            )
        } catch (_: CancellationException) {
        }
        val filledBefore = library.filled.keys.toSet()
        // Two whole pages were stored; the third was cut off before its store, so nothing of it counts.
        assertThat(filledBefore).hasSize(200)

        val second = mutableListOf<Long>()
        val result = YearRepairRun.run(
            attempts = mutableSetOf(),
            page = { after, limit -> library.page(after, limit) },
            read = { second += it.id; Read.Year(2000) },
            store = { years, albums -> library.store(years, albums) },
            saveAttempts = {},
        )
        assertThat(library.withoutYear).isEmpty()
        assertThat(result.filled).isEqualTo(300)
        // Nothing the first run filled was read again (what it read but had not stored yet is redone).
        assertThat(second.intersect(filledBefore)).isEmpty()
        assertThat(reads).isNotEmpty()
    }

    @Test
    fun `a file with no year tag is remembered and not read again, until it is re-tagged`() = runTest {
        val library = FakeLibrary(songs(10))
        val attempts = mutableSetOf<String>()
        var reads = 0
        var saved = 0
        suspend fun run() = YearRepairRun.run(
            attempts = attempts,
            page = { after, limit -> library.page(after, limit) },
            read = { reads++; Read.NoYear },
            store = { years, albums -> library.store(years, albums) },
            saveAttempts = { saved++ },
        )
        val first = run()
        assertThat(first.noYearTag).isEqualTo(10)
        assertThat(reads).isEqualTo(10)
        assertThat(saved).isAtLeast(1)

        val second = run()
        assertThat(second.scanned).isEqualTo(0)
        assertThat(second.skippedKnownNoYear).isEqualTo(10)
        assertThat(reads).isEqualTo(10)

        // A re-tagged file has a new date_modified, so it is asked again.
        library.withoutYear.replaceAll { it.copy(dateModified = 999L) }
        run()
        assertThat(reads).isEqualTo(20)
    }

    @Test
    fun `a file that cannot be read now is asked again next time, not given up on`() = runTest {
        val library = FakeLibrary(songs(5))
        val attempts = mutableSetOf<String>()
        val result = YearRepairRun.run(
            attempts = attempts,
            page = { after, limit -> library.page(after, limit) },
            read = { Read.Unreadable },
            store = { years, albums -> library.store(years, albums) },
            saveAttempts = {},
        )
        assertThat(result.unreadable).isEqualTo(5)
        assertThat(attempts).isEmpty()
    }

    @Test
    fun `only the songs that got a year reach the store, with their albums`() = runTest {
        val library = FakeLibrary(songs(20, albumOf = { if (it <= 10) 1L else 2L }))
        YearRepairRun.run(
            attempts = mutableSetOf(),
            page = { after, limit -> library.page(after, limit) },
            read = { if (it.id <= 10) Read.Year(1998) else Read.NoYear },
            store = { years, albums -> library.store(years, albums) },
            saveAttempts = {},
        )
        assertThat(library.filled.keys).containsExactlyElementsIn((1L..10L).toList())
        assertThat(library.albumsTouched).containsExactly(1L)
    }

    @Test
    fun `an empty library costs one query`() = runTest {
        val library = FakeLibrary(emptyList())
        val result = YearRepairRun.run(
            attempts = mutableSetOf(),
            page = { after, limit -> library.page(after, limit) },
            read = { error("nothing to read") },
            store = { years, albums -> library.store(years, albums) },
            saveAttempts = {},
        )
        assertThat(result.scanned).isEqualTo(0)
        assertThat(library.pageCalls).isEqualTo(1)
    }

    // ── B12a-8 #4: "no year tag" and "could not be read" are different facts ─────────────────────────

    private suspend fun runWith(
        library: FakeLibrary,
        attempts: MutableSet<String>,
        failures: MutableMap<String, Int>,
        read: (YearRepairCandidate) -> Read,
        saved: MutableList<Pair<Set<String>, Map<String, Int>>> = mutableListOf(),
    ) = YearRepairRun.run(
        attempts = attempts,
        page = { after, limit -> library.page(after, limit) },
        read = read,
        store = { years, albums -> library.store(years, albums) },
        saveAttempts = { saved += attempts.toSet() to failures.toMap() },
        readFailures = failures,
    )

    @Test
    fun `one failed read is not a file without a year - it is asked again, and a later success fills it`() = runTest {
        val library = FakeLibrary(songs(3))
        val attempts = mutableSetOf<String>()
        val failures = mutableMapOf<String, Int>()
        // run 1: song 2's read fails (a busy file); the others have a year
        val first = runWith(library, attempts, failures, { if (it.id == 2L) Read.ReadFailed else Read.Year(1999) })
        assertThat(first.filled).isEqualTo(2)
        assertThat(first.readFailed).isEqualTo(1)
        assertThat(first.noYearTag).isEqualTo(0)
        assertThat(attempts).isEmpty()                                 // NOT remembered as "no year"
        assertThat(failures).containsExactly("2:100", 1)               // but counted
        // run 2: the file reads
        val second = runWith(library, attempts, failures, { Read.Year(2004) })
        assertThat(second.filled).isEqualTo(1)
        assertThat(library.filled[2L]).isEqualTo(2004)
        assertThat(failures).isEmpty()                                  // and forgotten
        assertThat(library.withoutYear).isEmpty()
    }

    @Test
    fun `a file whose read fails every time is given up after a few runs and then left alone`() = runTest {
        val library = FakeLibrary(songs(2))
        val attempts = mutableSetOf<String>()
        val failures = mutableMapOf<String, Int>()
        val reads = mutableListOf<Long>()
        val read = { c: YearRepairCandidate -> reads += c.id; if (c.id == 1L) Read.ReadFailed else Read.Year(1980) }
        for (run in 1 until YearRepairRun.MAX_READ_FAILURES) {
            val r = runWith(library, attempts, failures, read)
            assertThat(r.readFailed).isEqualTo(1)
            assertThat(attempts).isEmpty()                              // still being asked
        }
        val last = runWith(library, attempts, failures, read)
        assertThat(last.gaveUp).isEqualTo(1)
        assertThat(attempts).isEmpty()                                 // failure is not confirmed absence
        assertThat(failures).containsExactly("1:100", YearRepairRun.MAX_READ_FAILURES)
        val before = reads.count { it == 1L }
        val after = runWith(library, attempts, failures, read)
        assertThat(after.skippedKnownNoYear).isEqualTo(0)
        assertThat(after.gaveUp).isEqualTo(1)
        assertThat(reads.count { it == 1L }).isEqualTo(before)         // not opened again
        assertThat(before).isEqualTo(YearRepairRun.MAX_READ_FAILURES)
    }

    @Test
    fun `a file that changed since the failures is asked afresh`() = runTest {
        val old = YearRepairCandidate(id = 7, filePath = "/m/7.flac", albumId = 1, dateModified = 100L)
        val library = FakeLibrary(listOf(old))
        val attempts = mutableSetOf("7:100")                            // given up on, before the file was replaced
        val failures = mutableMapOf<String, Int>()
        library.withoutYear[0] = old.copy(dateModified = 250L)          // re-tagged / replaced
        val r = runWith(library, attempts, failures, { Read.Year(2011) })
        assertThat(r.filled).isEqualTo(1)
    }

    @Test
    fun `no year tag is still remembered at once and a missing file is still not counted`() = runTest {
        val library = FakeLibrary(songs(3))
        val attempts = mutableSetOf<String>()
        val failures = mutableMapOf<String, Int>()
        val r = runWith(library, attempts, failures, {
            when (it.id) {
                1L -> Read.NoYear
                2L -> Read.Unreadable
                else -> Read.Year(1975)
            }
        })
        assertThat(r.noYearTag).isEqualTo(1)
        assertThat(r.unreadable).isEqualTo(1)
        assertThat(attempts).containsExactly("1:100")
        assertThat(failures).isEmpty()                                  // a file that is not there is not a failed read
    }

    @Test
    fun `the counts are saved with the attempts, so they survive the process`() = runTest {
        val library = FakeLibrary(songs(1))
        val attempts = mutableSetOf<String>()
        val failures = mutableMapOf<String, Int>()
        val saved = mutableListOf<Pair<Set<String>, Map<String, Int>>>()
        runWith(library, attempts, failures, { Read.ReadFailed }, saved)
        assertThat(saved).isNotEmpty()
        assertThat(saved.last().second).containsExactly("1:100", 1)
    }
}
