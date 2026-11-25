package com.lostf1sh.pixelplayeross.data.historyimport

import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.ListenAudioFacts
import com.lostf1sh.pixelplayeross.data.stats.ListenBitPerfect
import com.lostf1sh.pixelplayeross.data.stats.ListenRoute
import com.lostf1sh.pixelplayeross.data.stats.ListenSignal
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import com.lostf1sh.pixelplayeross.data.stats.StatsEngine
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsRankMode
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class B73StoreAndStatsTest {

    private val tempDirs = mutableListOf<File>()

    @AfterEach
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun testContext(): Pair<Context, File> {
        val dir = Files.createTempDirectory("b73-store").toFile().also { tempDirs += it }
        val base = mockk<Context>(relaxed = true)
        val ctx = object : ContextWrapper(base) {
            override fun getFilesDir(): File = dir
        }
        return ctx to dir
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `store writes, lists, removes imports and leaves playback_history json byte-identical`() = runBlocking {
        val (ctx, dir) = testContext()
        val playbackRepo = PlaybackStatsRepository(ctx)
        val now = Kit.at(2026, 6, 15, 18)
        playbackRepo.recordPlayback(
            songId = "1",
            durationMs = 200_000L,
            timestamp = now,
            libraryItemId = "item-1",
            occurrenceId = "occ-1",
            audio = ListenAudioFacts(route = ListenRoute.USB_DIRECT, signal = ListenSignal.PURITY, bitPerfect = ListenBitPerfect.YES),
        )
        val historyFile = File(dir, "playback_history.json")
        val beforeHash = sha256(historyFile)

        val songs = listOf(
            Kit.song(1, title = "Harbor Mist", artist = "Sora Linn", album = "Coast", durationMs = 200_000L)
                .copy(artists = listOf(ArtistRef(1, "Sora Linn", true))),
        )
        val catalog = Kit.catalog(songs)
        val store = ImportedHistoryStore(
            context = ctx,
            catalogSource = object : CatalogSource { override suspend fun load() = catalog },
            librarySignal = LibraryChangeSignal(),
            preferences = mockk<UserPreferencesRepository>(relaxed = true),
        )

        val spotifyJson = """
            [
              {"ts":"2022-04-10T12:00:00Z","ms_played":180000,"master_metadata_track_name":"Harbor Mist","master_metadata_album_artist_name":"Sora Linn","ip_addr":"192.0.2.1"},
              {"ts":"2023-07-12T15:00:00Z","ms_played":250000,"master_metadata_track_name":"Lost Pier","master_metadata_album_artist_name":"Sora Linn","ip_addr":"192.0.2.2"},
              {"ts":"2024-09-01T20:00:00Z","ms_played":260000,"master_metadata_track_name":"Far Beacon","master_metadata_album_artist_name":"Vespera"}
            ]
        """.trimIndent().toByteArray()

        val preview = store.prepareImportWithCatalog(
            sources = listOf(ImportInputSource("Streaming_History_Audio_2022_2024.json") { ByteArrayInputStream(spotifyJson) }),
            catalog = catalog,
        )
        val record = store.commitImport(preview, importedAtMs = now)
        assertThat(store.records.value).hasSize(1)
        assertThat(record.counts.plays).isEqualTo(3)
        assertThat(sha256(historyFile)).isEqualTo(beforeHash)

        // Ensure raw account IP strings were never persisted inside imported_history/
        val storedFiles = File(dir, "imported_history").listFiles().orEmpty()
        assertThat(storedFiles.isNotEmpty()).isTrue()
        assertThat(File(dir, "imported_history/manifest.json").readText()).doesNotContain("192.0.2.1")

        val ledger = ListeningLedger.build(playbackRepo.exportEventsForBackup(), catalog)
        val importedInput = store.statsInput(catalog)

        // Switch ON: 3 years of imported plays + 2026 native play
        val withImported = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), now, Kit.zone, imported = importedInput)
        assertThat(withImported.totals.plays).isEqualTo(4)
        assertThat(withImported.totals.listenedMs).isEqualTo(200_000L + 180_000L + 250_000L + 260_000L)
        // Matched and name-only plays for "Sora Linn" merge into 1 artist row with 3 plays
        val artists = withImported.rankings.artists(StatsRankMode.PLAYS)
        val sora = artists.first { it.name == "Sora Linn" }
        assertThat(sora.plays).isEqualTo(3)
        assertThat(sora.timeMs).isEqualTo(200_000L + 180_000L + 250_000L)
        // Imported years show in historyStartMs (2022)
        assertThat(withImported.historyStartMs).isLessThan(ledger.firstMs!!)
        // Purity / AudioStats never counts imported plays
        assertThat(withImported.audio!!.purityMs).isEqualTo(200_000L)
        assertThat(withImported.audio.recordedMs).isEqualTo(200_000L)

        // Switch OFF (imported = null): identical to native ledger only
        val withoutImported = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), now, Kit.zone, imported = null)
        assertThat(withoutImported.totals.plays).isEqualTo(1)
        assertThat(withoutImported.totals.listenedMs).isEqualTo(200_000L)
        assertThat(withoutImported.historyStartMs).isEqualTo(ledger.firstMs)

        // Remove import deletes its plays and leaves native ledger intact
        assertThat(store.removeImport(record.id)).isTrue()
        assertThat(store.records.value).isEmpty()
        assertThat(store.statsInput(catalog).isEmpty).isTrue()
        assertThat(sha256(historyFile)).isEqualTo(beforeHash)
    }

    @Test
    fun `store deduplicates persistent spotify extended and account data imports in both orders`() = runBlocking {
        val songs = listOf(
            Kit.song(1, title = "Harbor Mist", artist = "Sora Linn", album = "Coast", durationMs = 200_000L)
                .copy(artists = listOf(ArtistRef(1, "Sora Linn", true))),
        )
        val catalog = Kit.catalog(songs)
        val extendedBytes = """
            [
              {"ts":"2024-02-10T14:30:47Z","ms_played":185000,"master_metadata_track_name":"Harbor Mist","master_metadata_album_artist_name":"Sora Linn","master_metadata_album_album_name":"Coast"}
            ]
        """.trimIndent().toByteArray()
        val accountBytes = """
            [
              {"endTime":"2024-02-10 14:30","artistName":"Sora Linn","trackName":"Harbor Mist","msPlayed":185000},
              {"endTime":"2024-02-11 10:00","artistName":"Sora Linn","trackName":"Harbor Mist","msPlayed":190000}
            ]
        """.trimIndent().toByteArray()

        // Order 1: Extended persisted first, then Account imported separately
        val (ctx1, _) = testContext()
        val store1 = ImportedHistoryStore(
            context = ctx1,
            catalogSource = object : CatalogSource { override suspend fun load() = catalog },
            librarySignal = LibraryChangeSignal(),
            preferences = mockk<UserPreferencesRepository>(relaxed = true),
        )
        val p1 = store1.prepareImportWithCatalog(listOf(ImportInputSource("Streaming_History_Audio_2024.json") { ByteArrayInputStream(extendedBytes) }), catalog)
        store1.commitImport(p1)
        val p2 = store1.prepareImportWithCatalog(listOf(ImportInputSource("StreamingHistory_music_0.json") { ByteArrayInputStream(accountBytes) }), catalog)
        assertThat(p2.counts.duplicatesSkipped).isEqualTo(1)
        assertThat(p2.counts.plays).isEqualTo(1)
        store1.commitImport(p2)
        assertThat(store1.statsInput(catalog).timestampsMs.size).isEqualTo(2)

        // Order 2: Account persisted first, then Extended imported separately
        val (ctx2, _) = testContext()
        val store2 = ImportedHistoryStore(
            context = ctx2,
            catalogSource = object : CatalogSource { override suspend fun load() = catalog },
            librarySignal = LibraryChangeSignal(),
            preferences = mockk<UserPreferencesRepository>(relaxed = true),
        )
        val q1 = store2.prepareImportWithCatalog(listOf(ImportInputSource("StreamingHistory_music_0.json") { ByteArrayInputStream(accountBytes) }), catalog)
        store2.commitImport(q1)
        val q2 = store2.prepareImportWithCatalog(listOf(ImportInputSource("Streaming_History_Audio_2024.json") { ByteArrayInputStream(extendedBytes) }), catalog)
        assertThat(q2.counts.duplicatesSkipped).isEqualTo(1)
        assertThat(q2.counts.plays).isEqualTo(0)
    }
}
