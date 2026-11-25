package com.lostf1sh.pixelplayeross.data.historyimport

import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.StatsEngine
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsRankMode
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.data.stats.SyntheticHistory
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Measures parse + match, store write, cold Stats load, and peak heap on 500,000 synthetic plays
 * against a 5,000-song library.
 */
class B73ScalePerformanceTest {

    private val tempDirs = mutableListOf<File>()

    @AfterEach
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `500000 synthetic plays parse, match, persist and compute Stats quickly within bounded memory`() = runBlocking {
        val dir = Files.createTempDirectory("b73-scale").toFile().also { tempDirs += it }
        val base = mockk<Context>(relaxed = true)
        val ctx = object : ContextWrapper(base) {
            override fun getFilesDir(): File = dir
        }

        val songs = SyntheticHistory.songs(5_000)
        val catalog = Kit.catalog(songs)
        val store = ImportedHistoryStore(
            context = ctx,
            catalogSource = object : CatalogSource { override suspend fun load() = catalog },
            librarySignal = LibraryChangeSignal(),
            preferences = mockk<UserPreferencesRepository>(relaxed = true),
        )

        val playCount = 500_000
        val now = Kit.at(2026, 10, 15, 20)
        val startEpoch = now - 6L * 365 * 24 * 60 * 60 * 1000L

        // Build synthetic Spotify Extended JSON stream of 500,000 plays (~85 MB)
        val baos = ByteArrayOutputStream(16 * 1024 * 1024)
        baos.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("[")
            for (i in 0 until playCount) {
                if (i > 0) w.write(",")
                val ts = Instant.ofEpochMilli(startEpoch + i * 360_000L).toString()
                val songIdx = i % 5_500 // 5,000 library songs + 500 unmatched synthetic tracks
                val title = if (songIdx < 5_000) songs[songIdx].title else "Synth Unmatched $songIdx"
                val artist = if (songIdx < 5_000) songs[songIdx].artist else "Synth Artist ${songIdx % 200}"
                w.write("""{"ts":"$ts","ms_played":250000,"master_metadata_track_name":"$title","master_metadata_album_artist_name":"$artist"}""")
            }
            w.write("]")
        }
        val jsonBytes = baos.toByteArray()

        val rt = Runtime.getRuntime()
        System.gc()
        val memBefore = rt.totalMemory() - rt.freeMemory()

        val tParse0 = System.nanoTime()
        val preview = store.prepareImportWithCatalog(
            sources = listOf(ImportInputSource("scale_500k.json") { ByteArrayInputStream(jsonBytes) }),
            catalog = catalog,
        )
        val parseMatchMs = (System.nanoTime() - tParse0) / 1_000_000

        val memAfterParse = rt.totalMemory() - rt.freeMemory()

        val tWrite0 = System.nanoTime()
        store.commitImport(preview, importedAtMs = now)
        val writeMs = (System.nanoTime() - tWrite0) / 1_000_000

        // Cold Stats load (reads gzipped store from disk + builds StatsReport ALL & YEAR)
        val coldStore = ImportedHistoryStore(
            context = ctx,
            catalogSource = object : CatalogSource { override suspend fun load() = catalog },
            librarySignal = LibraryChangeSignal(),
            preferences = mockk<UserPreferencesRepository>(relaxed = true),
        )
        val ledger = ListeningLedger.build(emptyList(), catalog)

        val tStats0 = System.nanoTime()
        val importedInput = coldStore.statsInput(catalog)
        val allReport = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), now, Kit.zone, imported = importedInput)
        val yearReport = StatsEngine.report(ledger, StatsPeriod.Months.year(2022), now, Kit.zone, imported = importedInput)
        allReport.rankings.songs(StatsRankMode.PLAYS)
        allReport.rankings.artists(StatsRankMode.PLAYS)
        val coldStatsMs = (System.nanoTime() - tStats0) / 1_000_000

        // Warm cached Stats report
        val tWarm0 = System.nanoTime()
        val cachedInput = coldStore.statsInput(catalog)
        val warmAll = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), now, Kit.zone, imported = cachedInput)
        val warmStatsMs = (System.nanoTime() - tWarm0) / 1_000_000

        val memPeak = maxOf(memAfterParse, rt.totalMemory() - rt.freeMemory())
        val deltaMb = ((memPeak - memBefore).coerceAtLeast(0L)) / (1024.0 * 1024.0)

        println(
            "B73-SCALE-500K: jsonMb=%.1f parseAndMatchMs=%d writeMs=%d coldStatsLoadMs=%d warmStatsMs=%d heapDeltaMb=%.1f plays=%d allPlays=%d year2022Plays=%d"
                .format(
                    jsonBytes.size / (1024.0 * 1024.0),
                    parseMatchMs,
                    writeMs,
                    coldStatsMs,
                    warmStatsMs,
                    deltaMb,
                    preview.counts.plays,
                    allReport.totals.plays,
                    yearReport.totals.plays,
                ),
        )

        assertThat(preview.counts.plays).isEqualTo(playCount)
        assertThat(allReport.totals.plays).isEqualTo(playCount)
        assertThat(warmAll.totals.plays).isEqualTo(playCount)
        assertThat(yearReport.totals.plays).isGreaterThan(0)
    }
}
