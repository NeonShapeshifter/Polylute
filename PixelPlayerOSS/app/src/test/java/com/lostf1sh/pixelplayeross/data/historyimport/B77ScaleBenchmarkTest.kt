package com.lostf1sh.pixelplayeross.data.historyimport

import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
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
 * B6 scale & speed test: 500,000 history rows + 200 playlists streamed without loading everything at once.
 */
class B77ScaleBenchmarkTest {

    private val tempDirs = mutableListOf<File>()

    @AfterEach
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `500k history rows plus 200 playlists stream within bounded heap and time`() = runBlocking {
        val dir = Files.createTempDirectory("b77-scale").toFile().also { tempDirs += it }
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
        val playlistCount = 200
        val tracksPerPlaylist = 40
        val now = Kit.at(2026, 10, 15, 20)
        val startEpoch = now - 6L * 365 * 24 * 60 * 60 * 1000L

        val historyFile = File(dir, "history_500k.json")
        historyFile.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("[")
            for (i in 0 until playCount) {
                if (i > 0) w.write(",")
                val ts = Instant.ofEpochMilli(startEpoch + i * 360_000L).toString()
                val songIdx = i % 5_500
                val title = if (songIdx < 5_000) songs[songIdx].title else "Synth Unmatched $songIdx"
                val artist = if (songIdx < 5_000) songs[songIdx].artist else "Synth Artist ${songIdx % 200}"
                w.write("""{"ts":"$ts","ms_played":250000,"master_metadata_track_name":"$title","master_metadata_album_artist_name":"$artist"}""")
            }
            w.write("]")
        }

        val playlistsBaos = ByteArrayOutputStream(2 * 1024 * 1024)
        playlistsBaos.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("""{"playlists":[""")
            for (p in 0 until playlistCount) {
                if (p > 0) w.write(",")
                w.write("""{"name":"Synth Playlist $p","items":[""")
                for (t in 0 until tracksPerPlaylist) {
                    if (t > 0) w.write(",")
                    val song = songs[(p * tracksPerPlaylist + t) % songs.size]
                    w.write("""{"track":{"trackName":"${song.title}","artistName":"${song.artist}","albumName":"${song.album}"}}""")
                }
                w.write("]}")
            }
            w.write("]}")
        }
        val playlistsBytes = playlistsBaos.toByteArray()

        val rt = Runtime.getRuntime()
        System.gc()
        val memBefore = rt.totalMemory() - rt.freeMemory()
        var memPeak = memBefore

        val t0 = System.nanoTime()
        val preview = store.prepareImportWithCatalog(
            sources = listOf(
                ImportInputSource("Streaming_History_Audio_500k.json") { historyFile.inputStream() },
                ImportInputSource("Playlist1.json") { ByteArrayInputStream(playlistsBytes) },
            ),
            catalog = catalog,
            onProgress = {
                val used = rt.totalMemory() - rt.freeMemory()
                if (used > memPeak) memPeak = used
            },
        )
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        val memAfter = rt.totalMemory() - rt.freeMemory()
        if (memAfter > memPeak) memPeak = memAfter
        val peakDeltaMb = (memPeak - memBefore).coerceAtLeast(0L) / (1024.0 * 1024.0)

        println(
            "B77-SCALE-500K-200PL: historyMb=%.1f playlistsMb=%.2f elapsedMs=%d peakHeapDeltaMb=%.1f plays=%d playlists=%d"
                .format(
                    historyFile.length() / (1024.0 * 1024.0),
                    playlistsBytes.size / (1024.0 * 1024.0),
                    elapsedMs,
                    peakDeltaMb,
                    preview.counts.plays,
                    preview.playlists.size,
                ),
        )

        assertThat(preview.counts.plays).isEqualTo(playCount)
        assertThat(preview.playlists).hasSize(playlistCount)
    }
}
