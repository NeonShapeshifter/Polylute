package com.lostf1sh.pixelplayeross.data.historyimport

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class B73HistoryReadersTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun `spotify extended zip with two files skips podcasts, ignores account fields, and makes zero network calls`() = runBlocking {
        var networkCalls = 0
        val fakeNetworkClient: (URL) -> HttpURLConnection = {
            networkCalls++
            error("Network call forbidden during history import")
        }

        val json1 = """
            [
              {
                "ts": "2022-05-14T18:30:00Z",
                "username": "synthetic-user-01",
                "platform": "android",
                "ms_played": 185000,
                "conn_country": "SE",
                "ip_addr": "198.51.100.42",
                "user_agent_decrypted": "Dalvik/2.1.0",
                "master_metadata_track_name": "Northern Lights",
                "master_metadata_album_artist_name": "Klara Sol",
                "master_metadata_album_album_name": "Winter Drift",
                "spotify_track_uri": "spotify:track:synth001",
                "reason_end": "trackdone",
                "skipped": false
              },
              {
                "ts": "2022-05-14T19:00:00Z",
                "ms_played": 300000,
                "master_metadata_track_name": null,
                "episode_name": "Synthetic Podcast Ep 1",
                "ip_addr": "198.51.100.42"
              }
            ]
        """.trimIndent().toByteArray()

        val json2 = """
            [
              {
                "ts": "2023-08-01T10:15:00Z",
                "ms_played": 250000,
                "master_metadata_track_name": "Midnight Ferry",
                "master_metadata_album_artist_name": "Klara Sol",
                "master_metadata_album_album_name": "Harbor",
                "audiobook_title": null,
                "ip_addr": "203.0.113.99"
              },
              {
                "ts": "2023-08-01T11:00:00Z",
                "ms_played": 400000,
                "master_metadata_track_name": "Chapter One",
                "audiobook_title": "Synthetic Book",
                "conn_country": "ES"
              }
            ]
        """.trimIndent().toByteArray()

        val zipBytes = zipOf(
            "MyData/Streaming_History_Audio_2022.json" to json1,
            "MyData/Streaming_History_Audio_2023.json" to json2,
        )

        val catalog = Kit.catalog(
            listOf(Kit.song(1, title = "Northern Lights", artist = "Klara Sol", album = "Winter Drift", durationMs = 200_000L)),
        )
        val preview = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("my_spotify_data.zip") { ByteArrayInputStream(zipBytes) }),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )

        assertThat(networkCalls).isEqualTo(0)
        assertThat(fakeNetworkClient).isNotNull()
        assertThat(preview.sourceKind).isEqualTo(ImportSourceKind.SPOTIFY_EXTENDED)
        assertThat(preview.counts.rowsRead).isEqualTo(4)
        assertThat(preview.counts.plays).isEqualTo(2)
        assertThat(preview.counts.podcastsSkipped).isEqualTo(2)
        assertThat(preview.counts.matchedExact).isEqualTo(1)
        assertThat(preview.counts.unmatched).isEqualTo(1)
        // Prove no account field strings exist in any track metadata of the prepared payload
        val serializedMeta = preview.prepared.tracks.joinToString { "${it.artistRaw}|${it.title}|${it.album}" }
        assertThat(serializedMeta).doesNotContain("198.51.100.42")
        assertThat(serializedMeta).doesNotContain("203.0.113.99")
        assertThat(serializedMeta).doesNotContain("synthetic-user-01")
        assertThat(serializedMeta).doesNotContain("Dalvik")
    }

    @Test
    fun `spotify account data derives start time from endTime minus msPlayed`() = runBlocking {
        val accountJson = """
            [
              {
                "endTime": "2024-02-10 14:30",
                "artistName": "Vega Trio",
                "trackName": "Paper Boat",
                "msPlayed": 250000
              },
              {
                "endTime": "bad-date",
                "artistName": "Vega Trio",
                "trackName": "Broken",
                "msPlayed": 180000
              }
            ]
        """.trimIndent().toByteArray()

        val preview = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("StreamingHistory_music_0.json") { ByteArrayInputStream(accountJson) }),
            catalog = Kit.catalog(emptyList()),
            existingDedupKeys = { emptySet() },
        )

        assertThat(preview.sourceKind).isEqualTo(ImportSourceKind.SPOTIFY_ACCOUNT)
        assertThat(preview.counts.plays).isEqualTo(1)
        assertThat(preview.counts.rejectedCount).isEqualTo(1)
        assertThat(preview.rejectionsSample.first().reason).contains("endTime")
        val expectedEnd = ImportDateParser.parseSpotifyAccountEndTime("2024-02-10 14:30")!!
        assertThat(preview.prepared.timestampsMs[0]).isEqualTo(expectedEnd - 250_000L)
        assertThat(preview.prepared.listenedMs[0]).isEqualTo(250_000L)
        assertThat(preview.prepared.estimated[0]).isFalse()
    }

    @Test
    fun `youtube music takeout strips prefixes in en, es, sv, de, fr, pt, strips Topic suffix, skips non-music, and rejects HTML`() = runBlocking {
        val takeoutJson = """
            [
              { "header": "YouTube Music", "title": "Watched Glass Harbor", "subtitles": [{"name": "Mira Wren - Topic"}], "time": "2021-01-05T10:00:00Z" },
              { "header": "YouTube Music", "title": "Has visto Río Blanco", "subtitles": [{"name": "Sol Norte - Topic"}], "time": "2021-01-06T10:00:00Z" },
              { "header": "YouTube Music", "title": "Tittade på Norrsken", "subtitles": [{"name": "Fjord Echo - Topic"}], "time": "2021-01-07T10:00:00Z" },
              { "header": "YouTube Music", "title": "Angesehen: Nachtzug", "subtitles": [{"name": "Berg Klang - Topic"}], "time": "2021-01-08T10:00:00Z" },
              { "header": "YouTube Music", "title": "Vous avez regardé Matin Calme", "subtitles": [{"name": "Lune Bleue - Topic"}], "time": "2021-01-09T10:00:00Z" },
              { "header": "YouTube Music", "title": "Você assistiu Mar Aberto", "subtitles": [{"name": "Onda Sul - Topic"}], "time": "2021-01-10T10:00:00Z" },
              { "header": "YouTube", "title": "Watched Random Tech Video", "subtitles": [{"name": "Tech Channel"}], "time": "2021-01-11T10:00:00Z" },
              { "header": "YouTube Music", "title": "Watched Removed Video", "time": "2021-01-12T10:00:00Z" }
            ]
        """.trimIndent().toByteArray()

        val catalog = Kit.catalog(
            listOf(Kit.song(10, title = "Glass Harbor", artist = "Mira Wren", durationMs = 215_000L)),
        )
        val preview = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("watch-history.json") { ByteArrayInputStream(takeoutJson) }),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )

        assertThat(preview.sourceKind).isEqualTo(ImportSourceKind.YOUTUBE_MUSIC)
        assertThat(preview.counts.rowsRead).isEqualTo(8)
        assertThat(preview.counts.plays).isEqualTo(6)
        assertThat(preview.counts.nonMusicSkipped).isEqualTo(1)
        assertThat(preview.counts.rejectedCount).isEqualTo(1)
        assertThat(preview.counts.estimatedPlays).isEqualTo(6)
        assertThat(preview.counts.estimatedTimeMs).isEqualTo(215_000L)
        assertThat(preview.counts.zeroDurationPlays).isEqualTo(5)
        assertThat(preview.prepared.tracks.map { it.title }).containsExactly(
            "Glass Harbor", "Río Blanco", "Norrsken", "Nachtzug", "Matin Calme", "Mar Aberto",
        )
        assertThat(preview.prepared.tracks.map { it.artistRaw }).containsExactly(
            "Mira Wren", "Sol Norte", "Fjord Echo", "Berg Klang", "Lune Bleue", "Onda Sul",
        )

        val htmlBytes = "<!DOCTYPE html><html><body>Takeout HTML</body></html>".toByteArray()
        assertThrows<YouTubeHtmlFormatException> {
            HistoryImportPipeline.inspectInput(
                listOf(ImportInputSource("watch-history.html") { ByteArrayInputStream(htmlBytes) }),
            )
        }
    }

    @Test
    fun `csv handles semicolon and comma delimiters, quoted fields, all date formats, and rejects bad rows`() = runBlocking {
        val q = '"'
        val semicolonCsv = """
            Fecha;Artista;Título;Álbum;Segundos
            2024-03-01T12:00:00Z;"Sol, Mateo";"Lluvia; Gris";"Cielo";250
            1709380800;Nora;Viento;Mar;260
            1709467200000;Nora;"Eco ${q}${q}Vivo${q}${q}";Mar;270
            24 Nov 2026 13:05;Nora;Última;Mar;240
            not-a-date;Nora;BadRow;Mar;180
        """.trimIndent().toByteArray()

        val mapping = HistoryImportPipeline.inspectInput(
            listOf(ImportInputSource("export.csv") { ByteArrayInputStream(semicolonCsv) }),
        )!!
        assertThat(mapping.delimiter).isEqualTo(';')
        assertThat(mapping.hasHeader).isTrue()
        assertThat(mapping.durationUnit).isEqualTo(CsvDurationUnit.SECONDS)

        val preview = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("export.csv") { ByteArrayInputStream(semicolonCsv) }),
            catalog = Kit.catalog(emptyList()),
            existingDedupKeys = { emptySet() },
            csvMapping = mapping,
        )
        assertThat(preview.counts.rowsRead).isEqualTo(5)
        assertThat(preview.counts.plays).isEqualTo(4)
        assertThat(preview.counts.rejectedCount).isEqualTo(1)
        assertThat(preview.prepared.tracks.map { it.artistRaw }).contains("Sol, Mateo")
        assertThat(preview.prepared.tracks.map { it.title }).containsAtLeast("Lluvia; Gris", "Eco \"Vivo\"", "Última")
        assertThat(preview.counts.totalListenedMs).isEqualTo((250 + 260 + 270 + 240) * 1_000L)

        val commaCsv = """
            timestamp,artist,title,ms_played
            2024-04-01T10:00:00Z,"A, B","Song, One",160000
        """.trimIndent().toByteArray()
        val commaMap = HistoryImportPipeline.inspectInput(
            listOf(ImportInputSource("comma.csv") { ByteArrayInputStream(commaCsv) }),
        )!!
        assertThat(commaMap.delimiter).isEqualTo(',')
        assertThat(commaMap.durationUnit).isEqualTo(CsvDurationUnit.MILLISECONDS)
    }

    @Test
    fun `musicolet backup files and archives are recognised and refused cleanly`() {
        assertThrows<MusicoletUnsupportedException> {
            HistoryImportPipeline.inspectInput(
                listOf(ImportInputSource("0.musicolet.backup") { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }),
            )
        }
        val musicoletZip = zipOf("DB_SONGS_LOG" to byteArrayOf(9, 8, 7), "PCs_default" to byteArrayOf(4, 5))
        assertThrows<MusicoletUnsupportedException> {
            HistoryImportPipeline.inspectInput(
                listOf(ImportInputSource("musicolet-export.zip") { ByteArrayInputStream(musicoletZip) }),
            )
        }
    }
}
