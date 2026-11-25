package com.lostf1sh.pixelplayeross.data.historyimport

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.backup.model.toBackupEntry
import com.lostf1sh.pixelplayeross.data.backup.model.toPlaybackEvent
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.data.stats.ListenTrackMetadata
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B78HistoryRoundTripTest {
    private val tempDirs = mutableListOf<File>()
    private val song = Kit.song(1, title = "Fixture Signal", artist = "Synthetic Quartet", album = "Test Pressing", durationMs = 180_000)
        .copy(path = "/synthetic/signal.flac")
    private val otherSong = Kit.song(2, title = "Fixture Echo", artist = "Synthetic Quartet", album = "Test Pressing", durationMs = 180_000)
        .copy(path = "/synthetic/echo.flac")
    private val catalog = Kit.catalog(listOf(song, otherSong))
    private val startMs = Kit.at(2025, 3, 2, 14, 10)
    private val durationMs = 180_000L
    private val listenedMs = 110_000L

    @After
    fun removeFixtures() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun testContext(): Context {
        val dir = Files.createTempDirectory("b78-history").toFile().also(tempDirs::add)
        return object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir(): File = dir
        }
    }

    private class Phone(
        val native: PlaybackStatsRepository,
        val store: ImportedHistoryStore,
        val exporter: UniversalExporter,
        val applier: UnifiedImportApplier,
    )

    private fun phone(
        catalog: LibraryCatalog,
        context: Context = testContext(),
        native: PlaybackStatsRepository = PlaybackStatsRepository(context),
    ): Phone {
        val catalogSource = object : CatalogSource {
            override suspend fun load() = catalog
        }
        val prefs = mockk<UserPreferencesRepository>().also {
            every { it.artistDelimitersFlow } returns flowOf(UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS)
        }
        val store = ImportedHistoryStore(context, catalogSource, LibraryChangeSignal(), prefs, native)
        return Phone(
            native, store,
            UniversalExporter(context, catalogSource, native, store, mockk(), mockk()),
            UnifiedImportApplier(store, mockk(), mockk()),
        )
    }

    private fun exportSource(kind: UniversalExportKind, plays: List<UniversalExportPlay>): ImportInputSource {
        val out = ByteArrayOutputStream()
        val extension = when (kind) {
            UniversalExportKind.HISTORY_CSV -> {
                UniversalExporter.writeHistoryCsv(out, plays, ZoneOffset.UTC)
                "csv"
            }
            UniversalExportKind.HISTORY_JSON -> {
                UniversalExporter.writeHistoryJson(out, plays, ZoneOffset.UTC)
                "json"
            }
            else -> error("Expected a history export")
        }
        val bytes = out.toByteArray()
        return ImportInputSource("fixture-history.$extension") { bytes.inputStream() }
    }

    @Test
    fun `CSV and JSON add no native plays back to the same phone and all plays to an empty phone`() = runBlocking {
        val source = phone(catalog)
        // One occurrence split around a pause, plus a distinct play of another track.
        source.native.recordPlayback(song.id, 55_000, startMs + 55_000, song.libraryItemId, "split", itemDurationMs = durationMs)
        source.native.recordPlayback(song.id, 55_000, startMs + 140_000, song.libraryItemId, "split", itemDurationMs = durationMs)
        source.native.recordPlayback(otherSong.id, listenedMs, startMs + 300_000 + listenedMs, otherSong.libraryItemId, "other", itemDurationMs = durationMs)
        val plays = source.exporter.collectHistoryPlays(catalog)
        assertThat(plays.map { it.playedAtMs }).containsExactly(startMs, startMs + 300_000).inOrder()

        for (format in listOf(UniversalExportKind.HISTORY_CSV, UniversalExportKind.HISTORY_JSON)) {
            val input = listOf(exportSource(format, plays))
            val samePhone = source.store.prepareImport(input)
            assertThat(samePhone.counts.duplicatesSkipped).isEqualTo(2)
            assertThat(samePhone.counts.plays).isEqualTo(0)
            assertThat(source.applier.apply(samePhone).playsAdded).isEqualTo(0)
            assertThat(source.store.records.value).isEmpty()

            val emptyPhone = phone(catalog)
            assertThat(emptyPhone.native.exportEventsForBackup()).isEmpty()
            val differentPhone = emptyPhone.store.prepareImport(input)
            assertThat(differentPhone.counts.duplicatesSkipped).isEqualTo(0)
            assertThat(emptyPhone.applier.apply(differentPhone).playsAdded).isEqualTo(2)
            assertThat(emptyPhone.store.statsInput(catalog).timestampsMs.toList())
                .containsExactly(startMs, startMs + 300_000).inOrder()
        }
        val ledger = ListeningLedger.build(source.native.exportEventsForBackup(), catalog)
        assertThat(ledger.occurrences.count { it.isPlay }).isEqualTo(2)
    }

    @Test
    fun `window includes both 2 second bounds and leaves other tracks and other formats alone`(): Unit = runBlocking {
        val source = phone(catalog)
        source.native.recordPlayback(song.id, listenedMs, startMs + listenedMs, song.libraryItemId, "one", itemDurationMs = durationMs)
        val exported = source.exporter.collectHistoryPlays(catalog).single()
        val within = listOf(exported.copy(playedAtMs = startMs - 2_000L), exported.copy(playedAtMs = startMs + 2_000L))
        val outside = listOf(exported.copy(playedAtMs = startMs - 2_001L), exported.copy(playedAtMs = startMs + 2_001L))
        val differentSong = exported.copy(title = otherSong.title, path = otherSong.path)
        val csv = exportSource(UniversalExportKind.HISTORY_CSV, within + outside + differentSong)
        val spotify = """
            [{
                "ts":"${Instant.ofEpochMilli(startMs + listenedMs)}",
                "ms_played":$listenedMs,
                "master_metadata_track_name":"${song.title}",
                "master_metadata_album_artist_name":"${song.artist}",
                "master_metadata_album_album_name":"${song.album}"
            }]
        """.trimIndent().toByteArray()
        val otherFormat = ImportInputSource("Streaming_History_Audio_fixture.json") { spotify.inputStream() }

        val preview = source.store.prepareImport(listOf(csv, otherFormat))
        assertThat(preview.counts.duplicatesSkipped).isEqualTo(2)
        assertThat(preview.counts.plays).isEqualTo(4)
        assertThat(preview.prepared.timestampsMs.toList())
            .containsExactly(startMs - 2_001L, startMs + 2_001L, startMs, startMs)
    }

    @Test
    fun `missing library tracks retain recorded names after restart and skip a nameless legacy play`() = runBlocking {
        val emptyCatalog = Kit.catalog(emptyList())
        val context = testContext()
        val native = PlaybackStatsRepository(context)
        val names = ListenTrackMetadata(song.title, song.artist, song.album)
        native.recordPlayback(
            song.id, listenedMs, startMs + listenedMs,
            libraryItemId = song.libraryItemId,
            occurrenceId = "named",
            itemDurationMs = durationMs,
            trackMetadata = names,
        )
        native.recordPlayback(
            otherSong.id, listenedMs, startMs + 300_000 + listenedMs,
            libraryItemId = otherSong.libraryItemId,
            occurrenceId = "legacy",
            itemDurationMs = durationMs,
        )
        val solo = ListenTrackMetadata("Solo Fixture")
        native.recordPlayback(
            "3", listenedMs, startMs + 600_000 + listenedMs,
            libraryItemId = "item-3",
            occurrenceId = "title-only",
            itemDurationMs = durationMs,
            trackMetadata = solo,
        )
        val source = phone(emptyCatalog, context)
        val restoredEvents = source.native.exportEventsForBackup()
        assertThat(restoredEvents.map { it.trackMetadata }).containsExactly(names, null, solo).inOrder()
        val backup = restoredEvents.first().toBackupEntry()
        assertThat(backup.title).isNull()
        assertThat(backup.toPlaybackEvent().trackMetadata).isEqualTo(names)
        val plays = source.exporter.collectHistoryPlays(emptyCatalog)
        assertThat(plays).containsExactly(
            UniversalExportPlay(startMs, song.artist, song.title, song.album, listenedMs, durationMs, path = ""),
            UniversalExportPlay(startMs + 600_000, "", solo.title, "", listenedMs, durationMs, path = ""),
        ).inOrder()
        for (format in listOf(UniversalExportKind.HISTORY_CSV, UniversalExportKind.HISTORY_JSON)) {
            val input = listOf(exportSource(format, plays))
            val samePhone = source.store.prepareImport(input)
            assertThat(samePhone.counts.duplicatesSkipped).isEqualTo(2)
            assertThat(source.applier.apply(samePhone).playsAdded).isEqualTo(0)

            val emptyPhone = phone(emptyCatalog)
            val preview = emptyPhone.store.prepareImport(input)
            assertThat(emptyPhone.applier.apply(preview).playsAdded).isEqualTo(2)
            val original = preview.prepared.tracks.first()
            assertThat(original.title).isEqualTo(song.title)
            assertThat(original.artistRaw).isEqualTo(song.artist)
            assertThat(original.album).isEqualTo(song.album)
            val titleOnly = preview.prepared.tracks.last()
            assertThat(titleOnly.title).isEqualTo(solo.title)
            assertThat(titleOnly.artistRaw).isEmpty()
            assertThat(titleOnly.album).isNull()
        }
    }
}
