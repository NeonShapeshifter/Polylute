package com.lostf1sh.pixelplayeross.data.historyimport

import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class B77PartADedupAndOccurrenceTest {

    private val tempDirs = mutableListOf<File>()

    @AfterEach
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun testStore(catalog: com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog): ImportedHistoryStore {
        val dir = Files.createTempDirectory("b77-parta").toFile().also { tempDirs += it }
        val base = mockk<Context>(relaxed = true)
        val ctx = object : ContextWrapper(base) {
            override fun getFilesDir(): File = dir
        }
        return ImportedHistoryStore(
            context = ctx,
            catalogSource = object : CatalogSource { override suspend fun load() = catalog },
            librarySignal = LibraryChangeSignal(),
            preferences = mockk<UserPreferencesRepository>(relaxed = true),
        )
    }

    private val catalog = Kit.catalog(
        listOf(
            Kit.song(1, title = "Canción del Sur", artist = "Inés Val", album = "Alba", durationMs = 200_000L)
                .copy(artists = listOf(ArtistRef(1, "Inés Val", true))),
            Kit.song(2, title = "Harbor Mist", artist = "Sora Linn", album = "Coast", durationMs = 210_000L)
                .copy(artists = listOf(ArtistRef(2, "Sora Linn", true))),
        ),
    )

    @Test
    fun `date-only CSV with same song 3 times on one day counts 3 plays and re-import yields 0 new plays and 3 duplicates`() = runBlocking {
        val csv = """
            date,artist,title,ms_played
            2024-05-01,Inés Val,Canción del Sur,190000
            2024-05-01,Inés Val,Canción del Sur,190000
            2024-05-01,Inés Val,Canción del Sur,190000
        """.trimIndent().toByteArray()

        val store = testStore(catalog)
        val mapping = store.inspectInput(
            listOf(ImportInputSource("day_only.csv") { ByteArrayInputStream(csv) }),
        )
        val first = store.prepareImportWithCatalog(
            sources = listOf(ImportInputSource("day_only.csv") { ByteArrayInputStream(csv) }),
            catalog = catalog,
            csvMapping = mapping,
        )
        assertThat(first.counts.rowsRead).isEqualTo(3)
        assertThat(first.counts.plays).isEqualTo(3)
        assertThat(first.counts.duplicatesSkipped).isEqualTo(0)

        store.commitImport(first)

        val second = store.prepareImportWithCatalog(
            sources = listOf(ImportInputSource("day_only.csv") { ByteArrayInputStream(csv) }),
            catalog = catalog,
            csvMapping = mapping,
        )
        assertThat(second.counts.plays).isEqualTo(0)
        assertThat(second.counts.duplicatesSkipped).isEqualTo(3)
    }

    @Test
    fun `store written with old single key re-imports only rows the old import dropped`() = runBlocking {
        val csv = """
            date,artist,title,ms_played
            2024-05-01,Inés Val,Canción del Sur,190000
            2024-05-01,Inés Val,Canción del Sur,190000
            2024-05-01,Inés Val,Canción del Sur,190000
        """.trimIndent().toByteArray()

        val mapping = HistoryImportPipeline.inspectInput(
            listOf(ImportInputSource("day_only.csv") { ByteArrayInputStream(csv) }),
        )
        val singleRowCsv = """
            date,artist,title,ms_played
            2024-05-01,Inés Val,Canción del Sur,190000
        """.trimIndent().toByteArray()

        val store = testStore(catalog)
        // Simulate an old store that only persisted occurrence 0 and dropped the 2nd & 3rd rows
        val oldPreview = store.prepareImportWithCatalog(
            sources = listOf(ImportInputSource("day_only.csv") { ByteArrayInputStream(singleRowCsv) }),
            catalog = catalog,
            csvMapping = mapping,
        )
        assertThat(oldPreview.counts.plays).isEqualTo(1)
        val ts = ImportDateParser.parseFlexibleUtc("2024-05-01")!!
        val expectedOldKey = HistoryImportPipeline.dedupKey(
            sourceKind = ImportSourceKind.CSV,
            timestampMs = ts,
            artistRaw = "Inés Val",
            title = "Canción del Sur",
            sourceListenedMs = 190_000L,
            occurrence = 0,
        )
        assertThat(DedupKey(oldPreview.prepared.dedupHi[0], oldPreview.prepared.dedupLo[0]))
            .isEqualTo(expectedOldKey)
        store.commitImport(oldPreview)

        // Re-importing the 3-row file brings in only the 2 previously dropped rows
        val reimport = store.prepareImportWithCatalog(
            sources = listOf(ImportInputSource("day_only.csv") { ByteArrayInputStream(csv) }),
            catalog = catalog,
            csvMapping = mapping,
        )
        assertThat(reimport.counts.duplicatesSkipped).isEqualTo(1)
        assertThat(reimport.counts.plays).isEqualTo(2)
        store.commitImport(reimport)

        // Importing once more yields 0 new, 3 duplicates
        val third = store.prepareImportWithCatalog(
            sources = listOf(ImportInputSource("day_only.csv") { ByteArrayInputStream(csv) }),
            catalog = catalog,
            csvMapping = mapping,
        )
        assertThat(third.counts.plays).isEqualTo(0)
        assertThat(third.counts.duplicatesSkipped).isEqualTo(3)
    }

    @Test
    fun `same file picked twice in one import counts once`() = runBlocking {
        val csv = """
            date,artist,title,ms_played
            2024-05-01,Inés Val,Canción del Sur,190000
            2024-05-01,Inés Val,Canción del Sur,190000
        """.trimIndent().toByteArray()

        val mapping = HistoryImportPipeline.inspectInput(
            listOf(
                ImportInputSource("day_only.csv") { ByteArrayInputStream(csv) },
                ImportInputSource("day_only_copy.csv") { ByteArrayInputStream(csv) },
            ),
        )
        val preview = HistoryImportPipeline.prepareImport(
            sources = listOf(
                ImportInputSource("day_only.csv") { ByteArrayInputStream(csv) },
                ImportInputSource("day_only_copy.csv") { ByteArrayInputStream(csv) },
            ),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
            csvMapping = mapping,
        )
        assertThat(preview.counts.rowsRead).isEqualTo(2)
        assertThat(preview.counts.plays).isEqualTo(2)
        assertThat(preview.counts.duplicatesSkipped).isEqualTo(0)
        assertThat(preview.fileNames).containsExactly("day_only.csv")
    }

    @Test
    fun `spotify extended and account overlap with occurrence indexing never double counts`() = runBlocking {
        val extendedJson = """
            [
              {"ts":"2024-02-10T14:30:10Z","ms_played":185000,"master_metadata_track_name":"Canción del Sur","master_metadata_album_artist_name":"Inés Val"},
              {"ts":"2024-02-10T14:30:50Z","ms_played":185000,"master_metadata_track_name":"Canción del Sur","master_metadata_album_artist_name":"Inés Val"}
            ]
        """.trimIndent().toByteArray()
        val accountJson = """
            [
              {"endTime":"2024-02-10 14:30","artistName":"Inés Val","trackName":"Canción del Sur","msPlayed":185000},
              {"endTime":"2024-02-10 14:30","artistName":"Inés Val","trackName":"Canción del Sur","msPlayed":185000},
              {"endTime":"2024-02-10 14:35","artistName":"Inés Val","trackName":"Canción del Sur","msPlayed":190000}
            ]
        """.trimIndent().toByteArray()

        // Combined batch
        val combined = HistoryImportPipeline.prepareImport(
            sources = listOf(
                ImportInputSource("Streaming_History_Audio_2024.json") { ByteArrayInputStream(extendedJson) },
                ImportInputSource("StreamingHistory_music_0.json") { ByteArrayInputStream(accountJson) },
            ),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )
        assertThat(combined.counts.plays).isEqualTo(3)
        assertThat(combined.counts.duplicatesSkipped).isEqualTo(2)

        // Sequential store import: Extended first, then Account
        val store = testStore(catalog)
        val pExt = store.prepareImportWithCatalog(
            listOf(ImportInputSource("Streaming_History_Audio_2024.json") { ByteArrayInputStream(extendedJson) }),
            catalog,
        )
        assertThat(pExt.counts.plays).isEqualTo(2)
        store.commitImport(pExt)

        val pAcc = store.prepareImportWithCatalog(
            listOf(ImportInputSource("StreamingHistory_music_0.json") { ByteArrayInputStream(accountJson) }),
            catalog,
        )
        assertThat(pAcc.counts.plays).isEqualTo(1)
        assertThat(pAcc.counts.duplicatesSkipped).isEqualTo(2)
    }

    @Test
    fun `real-format spotify extended fixture produces exact legacy occurrence-0 keys and counts`() = runBlocking {
        val extendedJson = """
            [
              {"ts":"2023-01-10T08:15:22Z","ms_played":195000,"master_metadata_track_name":"Canción del Sur","master_metadata_album_artist_name":"Inés Val","master_metadata_album_album_name":"Alba","reason_end":"trackdone","skipped":false},
              {"ts":"2023-01-10T08:20:05Z","ms_played":205000,"master_metadata_track_name":"Harbor Mist","master_metadata_album_artist_name":"Sora Linn","master_metadata_album_album_name":"Coast","reason_end":"trackdone","skipped":false},
              {"ts":"2023-01-10T08:25:40Z","ms_played":15000,"master_metadata_track_name":"Harbor Mist","master_metadata_album_artist_name":"Sora Linn","reason_end":"fwdbtn","skipped":true}
            ]
        """.trimIndent().toByteArray()

        val preview = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("Streaming_History_Audio_2023.json") { ByteArrayInputStream(extendedJson) }),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )
        assertThat(preview.counts.rowsRead).isEqualTo(3)
        assertThat(preview.counts.plays).isEqualTo(2)
        assertThat(preview.counts.skips).isEqualTo(1)
        assertThat(preview.counts.duplicatesSkipped).isEqualTo(0)
        assertThat(preview.counts.matchedExact).isEqualTo(2)

        // Verify exact legacy SHA-256 key formula (occurrence 0 has no #\u0000 suffix)
        val end0 = ImportDateParser.parseIsoUtc("2023-01-10T08:15:22Z")!! - 195_000L
        val legacyCanonical0 = "SPOTIFY_EXTENDED\u0000$end0\u0000ines val\u0000cancion del sur\u0000195000"
        val digest = MessageDigest.getInstance("SHA-256").digest(legacyCanonical0.toByteArray(Charsets.UTF_8))
        var hi = 0L
        var lo = 0L
        for (i in 0 until 8) hi = (hi shl 8) or (digest[i].toLong() and 0xffL)
        for (i in 8 until 16) lo = (lo shl 8) or (digest[i].toLong() and 0xffL)
        assertThat(preview.prepared.dedupHi[0]).isEqualTo(hi)
        assertThat(preview.prepared.dedupLo[0]).isEqualTo(lo)
    }
}
