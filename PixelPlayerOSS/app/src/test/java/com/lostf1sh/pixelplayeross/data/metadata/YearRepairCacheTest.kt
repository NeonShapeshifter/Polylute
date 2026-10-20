package com.lostf1sh.pixelplayeross.data.metadata

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.YearRepairCandidate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class YearRepairCacheTest {
    /** Commit stages values together, just as a SharedPreferences XML replacement does. */
    private class Disk {
        val values = mutableMapOf<String, Any>("no_year_tag" to setOf("1:100", "2:100"), "read_failures" to setOf("3:100|2"))
        var failCommit = false
        var migrations = 0
        fun open(): SharedPreferences {
            val prefs = mockk<SharedPreferences>()
            every { prefs.getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
            every { prefs.getStringSet(any(), any()) } answers { (values[firstArg<String>()] as? Set<String> ?: secondArg<Set<String>>()).toMutableSet() }
            every { prefs.edit() } answers {
                val editor = mockk<SharedPreferences.Editor>()
                val staged = mutableMapOf<String, Any?>()
                every { editor.remove(any()) } answers { staged[firstArg()] = null; editor }
                every { editor.putInt(any(), any()) } answers { staged[firstArg()] = secondArg<Int>(); editor }
                every { editor.putStringSet(any(), any()) } answers { staged[firstArg()] = secondArg<Set<String>>().toSet(); editor }
                every { editor.commit() } answers {
                    if (failCommit) false else {
                        if (YearRepairCache.KEY_VERSION in staged) migrations++
                        staged.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                        true
                    }
                }
                editor
            }
            return prefs
        }
    }

    @Test
    fun `migration retries a failed commit and is durable and idempotent`() {
        val disk = Disk()
        disk.values["unrelated"] = "keep"
        disk.failCommit = true
        assertThrows(IllegalStateException::class.java) { YearRepairCache(disk.open()).load() }
        assertThat(disk.values["no_year_tag"]).isEqualTo(setOf("1:100", "2:100"))
        disk.failCommit = false
        val cache = YearRepairCache(disk.open())
        val state = cache.load()
        assertThat(state.noYear).isEmpty()
        assertThat(state.failures).isEmpty()
        state.noYear += "2:100"
        state.failures["3:100"] = YearRepairRun.MAX_READ_FAILURES
        cache.save(state)
        val reopened = YearRepairCache(disk.open()).load()
        assertThat(reopened.noYear).containsExactly("2:100")
        assertThat(reopened.failures).containsExactly("3:100", YearRepairRun.MAX_READ_FAILURES)
        assertThat(disk.migrations).isEqualTo(1)
        assertThat(disk.values["unrelated"]).isEqualTo("keep")
    }

    @Test
    fun `an install already marked with the first reader version is migrated once more and never again`() {
        val disk = Disk()
        // What a build outside the history can have left: the marker of 1 with negatives under it.
        disk.values["reader_version"] = 1
        disk.values["unrelated"] = "keep"

        val state = YearRepairCache(disk.open()).load()

        assertThat(state.noYear).isEmpty()
        assertThat(state.failures).isEmpty()
        assertThat(disk.values["reader_version"]).isEqualTo(YearRepairCache.READER_VERSION)
        assertThat(YearRepairCache.READER_VERSION).isAtLeast(2)
        assertThat(disk.values["unrelated"]).isEqualTo("keep")
        // A negative recorded now survives the next start: the migration is once per version.
        state.noYear += "2:100"
        YearRepairCache(disk.open()).save(state)
        assertThat(YearRepairCache(disk.open()).load().noYear).containsExactly("2:100")
        assertThat(disk.migrations).isEqualTo(1)
    }

    @Test
    fun `interrupted migration scan resumes pages and recovers omitted years once`() = runTest {
        val disk = Disk()
        val rows = (1L..3L).map { YearRepairCandidate(it, "/$it.flac", 7, 100) }.toMutableList()
        val years = mutableMapOf(99L to 1987) // existing valid year never enters the candidate query
        val reads = mutableListOf<Long>()
        suspend fun scan(interrupt: Boolean) {
            val cache = YearRepairCache(disk.open())
            val state = cache.load()
            YearRepairRun.run(state.noYear, page = { after, n -> rows.filter { it.id > after }.take(n) },
                read = { reads += it.id; if (interrupt && it.id == 2L) throw CancellationException();
                    if (it.id == 2L) YearRepairRun.Read.NoYear else YearRepairRun.Read.Year(2001) },
                store = { found, _ -> found.forEach { (id, year) -> years[id] = year; rows.removeAll { it.id == id } }; found.size },
                saveAttempts = { cache.save(state) }, pageSize = 1, readFailures = state.failures)
        }
        try { scan(true) } catch (_: CancellationException) { }
        scan(false)
        val afterResume = reads.size
        scan(false)
        assertThat(reads.size).isEqualTo(afterResume)
        assertThat(years).containsExactly(99L, 1987, 1L, 2001, 3L, 2001)
        assertThat(disk.migrations).isEqualTo(1)
    }
}
