package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.lostf1sh.pixelplayeross.data.stats.Kit.DAY
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * How the history file is written (B28): an event is appended in place, the file is a whole JSON array before and after, an old event is
 * never rewritten, and a file whose end was torn is read up to the tear and healed by the next write.
 */
class PlaybackHistoryStoreTest {
    private val dirs = mutableListOf<File>()

    private fun store(): Pair<PlaybackStatsRepository, File> {
        val (repo, dir) = newHistoryRepository()
        dirs += dir
        return repo to File(dir, "playback_history.json")
    }

    @AfterEach
    fun tearDown() {
        releaseHistoryFiles()
        dirs.forEach { it.deleteRecursively() }
    }

    private val legacy = """[{"songId":"1","timestamp":1700000060000,"durationMs":60000,"startTimestamp":1700000000000,"endTimestamp":1700000060000},""" +
        """{"songId":"2","timestamp":1700000200000,"durationMs":90000,"startTimestamp":1700000110000,"endTimestamp":1700000200000}]"""

    private fun record(repo: PlaybackStatsRepository, id: String, endMs: Long, occurrence: String? = "o-$id") = runBlocking {
        repo.recordPlayback(songId = id, durationMs = 30_000, timestamp = endMs, libraryItemId = "item-$id", occurrenceId = occurrence)
    }

    @Test
    fun `an event is appended in place and the old ones stay byte for byte as they were`() {
        val (repo, file) = store()
        file.writeText(legacy)
        record(repo, "3", 1_700_000_300_000)

        val text = file.readText()
        assertThat(text.startsWith(legacy.dropLast(1) + ",")).isTrue()
        assertThat(text.endsWith("]")).isTrue()
        val array = JsonParser.parseString(text).asJsonArray
        assertThat(array.size()).isEqualTo(3)
        assertThat(array[2].asJsonObject.get("libraryItemId").asString).isEqualTo("item-3")
        // The first two events have exactly their own five keys: nothing was filled in.
        assertThat(array[0].asJsonObject.keySet()).containsExactly("songId", "timestamp", "durationMs", "startTimestamp", "endTimestamp")
    }

    @Test
    fun `the first event creates the file and every later one keeps it a valid array`() {
        val (repo, file) = store()
        record(repo, "1", 1_700_000_000_000)
        assertThat(JsonParser.parseString(file.readText()).asJsonArray.size()).isEqualTo(1)
        repeat(5) { record(repo, "${it + 2}", 1_700_000_100_000 + it * 100_000L) }
        assertThat(JsonParser.parseString(file.readText()).asJsonArray.size()).isEqualTo(6)
        assertThat(runBlocking { repo.exportEventsForBackup() }.map { it.songId }).containsExactly("1", "2", "3", "4", "5", "6").inOrder()
    }

    @Test
    fun `an empty array and a file with trailing whitespace take an append`() {
        val (repo, file) = store()
        file.writeText("[]\n")
        record(repo, "1", 1_700_000_000_000)
        assertThat(JsonParser.parseString(file.readText()).asJsonArray.size()).isEqualTo(1)
        file.writeText("[ ]")
        val (repo2, file2) = store()
        file2.writeText("[ ]  \n")
        record(repo2, "9", 1_700_000_000_000)
        assertThat(JsonParser.parseString(file2.readText()).asJsonArray.size()).isEqualTo(1)
    }

    @Test
    fun `a file torn in the middle of an append is read up to the tear and healed by the next write`() {
        val (repo, file) = store()
        file.writeText(legacy.dropLast(1) + ""","{"songId":"3","timestamp":17000003""")
        val events = runBlocking { repo.exportEventsForBackup() }
        assertThat(events.map { it.songId }).containsExactly("1", "2").inOrder()

        record(repo, "4", 1_700_000_400_000)
        val healed = JsonParser.parseString(file.readText()).asJsonArray
        assertThat(healed.map { it.asJsonObject.get("songId").asString }).containsExactly("1", "2", "4").inOrder()
    }

    @Test
    fun `a file that lost its closing bracket is recovered whole`() {
        val (repo, file) = store()
        file.writeText(legacy.dropLast(1))
        assertThat(runBlocking { repo.exportEventsForBackup() }).hasSize(2)
        record(repo, "3", 1_700_000_300_000)
        assertThat(JsonParser.parseString(file.readText()).asJsonArray.size()).isEqualTo(3)
    }

    @Test
    fun `a reader that holds some events is given only the ones after them, until the history is replaced`() {
        val (repo, _) = store()
        record(repo, "1", 1_700_000_000_000)
        val first = runBlocking { repo.readSince(-1, 0) }
        assertThat(first.replaced).isTrue()
        assertThat(first.events).hasSize(1)

        record(repo, "2", 1_700_000_100_000)
        record(repo, "3", 1_700_000_200_000)
        val delta = runBlocking { repo.readSince(first.epoch, 1) }
        assertThat(delta.replaced).isFalse()
        assertThat(delta.events.map { it.songId }).containsExactly("2", "3").inOrder()

        runBlocking { repo.importEventsFromBackup(first.events, clearExisting = true) }
        val after = runBlocking { repo.readSince(first.epoch, 3) }
        assertThat(after.replaced).isTrue()
        assertThat(after.events).hasSize(1)
    }

    @Test
    fun `an event older than two years is dropped when the file is next rewritten and not before`() {
        val (repo, file) = store()
        val now = 1_800_000_000_000L
        record(repo, "1", now - 800 * DAY)
        record(repo, "2", now - 700 * DAY) // the old one is not 730 days older than this one: kept
        assertThat(runBlocking { repo.exportEventsForBackup() }).hasSize(2)
        record(repo, "3", now) // now it is
        assertThat(runBlocking { repo.exportEventsForBackup() }.map { it.songId }).containsExactly("2", "3").inOrder()
        assertThat(JsonParser.parseString(file.readText()).asJsonArray.size()).isEqualTo(2)
        assertThat(TimeUnit.DAYS.toMillis(730)).isEqualTo(PlaybackStatsRepository.MAX_HISTORY_AGE_MS)
    }

    @Test
    fun `a reader reads what the writer wrote, every field`() {
        val (repo, _) = store()
        val facts = ListenAudioFacts(
            route = ListenRoute.USB_DIRECT, signal = ListenSignal.PURITY, bitPerfect = ListenBitPerfect.UNKNOWN,
            sourceRateHz = 96_000, sourceBits = 24, sourceCodec = "audio/flac", transportRateHz = 96_000, transportBits = 24,
            transportCodec = "audio/raw", deviceName = "DAC One", share = 80,
        )
        runBlocking {
            repo.recordPlayback(
                songId = "5", durationMs = 120_000, timestamp = 1_700_000_900_000, libraryItemId = "item-5", occurrenceId = "occ",
                outcome = PlaybackOutcome.PLAYED, endReason = TrackEndReason.USER_REPLAY, itemDurationMs = 240_000, audio = facts,
                context = ListenContext(ListenSourceKind.COLLECTION, shuffle = true, ref = "on_repeat"),
            )
        }
        // A second repository over the same file reads it from disk.
        val read = runBlocking { repo.exportEventsForBackup() }.single()
        assertThat(read.audio).isEqualTo(facts)
        assertThat(read.context).isEqualTo(ListenContext(ListenSourceKind.COLLECTION, shuffle = true, ref = "on_repeat"))
        assertThat(read.endReason).isEqualTo(TrackEndReason.USER_REPLAY)
        assertThat(read.outcome).isEqualTo(PlaybackOutcome.PLAYED)
        assertThat(read.itemDurationMs).isEqualTo(240_000L)
    }
}
