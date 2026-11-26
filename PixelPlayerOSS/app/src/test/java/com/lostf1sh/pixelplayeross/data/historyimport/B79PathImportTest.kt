package com.lostf1sh.pixelplayeross.data.historyimport

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.playlist.M3uManager
import com.lostf1sh.pixelplayeross.data.stats.Kit
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * B79 item 2: `file://` URI, plain, and Windows-slash path normalization across history, likes CSV, playlists CSV, and M3U8.
 */
class B79PathImportTest {

    private val specialPlainPath = "/storage/XXXX-XXXX/Music/A & % # + å/01 Track & % # + å.flac"
    private val specialUriPath = "file:///storage/XXXX-XXXX/Music/A%20%26%20%25%20%23%20+%20%C3%A5/01%20Track%20%26%20%25%20%23%20%2B%20%C3%A5.flac"
    private val specialWindowsPath = """\storage\XXXX-XXXX\Music\A & % # + å\01 Track & % # + å.flac"""

    private fun specialCatalog() = Kit.catalog(
        listOf(
            Kit.song(
                id = 10L,
                title = "Real Title",
                artist = "Real Artist",
                album = "Real Album",
                durationMs = 210_000L,
            ).copy(
                artists = listOf(ArtistRef(10L, "Real Artist", true)),
                path = specialPlainPath,
            ),
        ),
    )

    @Test
    fun `file URI, plain, and Windows-slash forms of path with special chars each match EXACT`() = runBlocking {
        val catalog = specialCatalog()
        for ((label, candidatePath) in listOf(
            "uri" to specialUriPath,
            "plain" to specialPlainPath,
            "windows" to specialWindowsPath,
        )) {
            // Use non-matching artist/title so only path lookup can match.
            val csv = """
                played_at,artist,title,album,ms_played,duration_ms,path
                2026-11-26T10:00:00Z,Other Artist,Other Title,Other Album,180000,210000,"$candidatePath"
            """.trimIndent().toByteArray()

            val preview = HistoryImportPipeline.prepareImport(
                sources = listOf(ImportInputSource("history_$label.csv") { ByteArrayInputStream(csv) }),
                catalog = catalog,
                existingDedupKeys = { emptySet() },
            )
            assertThat(preview.counts.matchedExact).isEqualTo(1)
            assertThat(preview.counts.unmatched).isEqualTo(0)
            assertThat(preview.prepared.tracks.single().match.confidence).isEqualTo(MatchConfidence.EXACT)
            assertThat(preview.prepared.tracks.single().match.songId).isEqualTo("10")
        }
    }

    @Test
    fun `likes CSV of 3 rows in file URI form matches all 3 by path`() = runBlocking {
        val songs = listOf(
            Kit.song(id = 1L, title = "Song One", artist = "Alpha", durationMs = 180_000L)
                .copy(path = specialPlainPath),
            Kit.song(id = 2L, title = "Song Two", artist = "Beta", durationMs = 190_000L)
                .copy(path = "/storage/XXXX-XXXX/Music/Artist B/Track + 2.flac"),
            Kit.song(id = 3L, title = "Song Three", artist = "Gamma", durationMs = 200_000L)
                .copy(path = "/storage/XXXX-XXXX/Music/Café/100% #3.flac"),
        )
        val catalog = Kit.catalog(songs)

        // Deliberately mismatched artist/title columns so matching proves path resolution works.
        val likesCsv = """
            artist,title,album,duration_ms,path
            Unrelated 1,Mismatch 1,,180000,"$specialUriPath"
            Unrelated 2,Mismatch 2,,190000,"file:///storage/XXXX-XXXX/Music/Artist%20B/Track%20+%202.flac"
            Unrelated 3,Mismatch 3,,200000,"file:/storage/XXXX-XXXX/Music/Caf%C3%A9/100%25%20%233.flac"
        """.trimIndent().toByteArray()

        val preview = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("polylute_likes.csv") { ByteArrayInputStream(likesCsv) }),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )

        assertThat(preview.likes.matchedCount).isEqualTo(3)
        assertThat(preview.likes.unmatchedCount).isEqualTo(0)
        assertThat(preview.likes.matchedTracks.map { it.songId }).containsExactly("1", "2", "3").inOrder()
    }

    @Test
    fun `playlists CSV and M3U8 entries in file URI form match by path`() = runBlocking {
        // Put another track with the same filename in a different directory so filename-only fallback would be ambiguous.
        val otherDirSong = Kit.song(id = 99L, title = "Wrong Copy", artist = "Other", durationMs = 210_000L)
            .copy(path = "/storage/XXXX-XXXX/Other/01 Track & % # + å.flac")
        val targetSong = specialCatalog().items.single().song
        val catalog = Kit.catalog(listOf(otherDirSong, targetSong))

        val playlistsCsv = """
            playlist,position,artist,title,album,duration_ms,path
            URI List,0,Wrong Artist,Wrong Title,,210000,"$specialUriPath"
        """.trimIndent().toByteArray()

        val m3u8 = """
            #EXTM3U
            $specialUriPath
        """.trimIndent().toByteArray()

        val preview = HistoryImportPipeline.prepareImport(
            sources = listOf(
                ImportInputSource("polylute_playlists.csv") { ByteArrayInputStream(playlistsCsv) },
                ImportInputSource("uri_list.m3u8") { ByteArrayInputStream(m3u8) },
            ),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )

        assertThat(preview.playlists).hasSize(2)
        assertThat(preview.playlists[0].matchedTracks.map { it.songId }).containsExactly("10")
        assertThat(preview.playlists[1].matchedTracks.map { it.songId }).containsExactly("10")

        val m3uDirect = M3uManager.parseM3u(ByteArrayInputStream(m3u8), "uri_list.m3u8", catalog)
        assertThat(m3uDirect.items.single().matched?.locator).isEqualTo("10")
    }

    @Test
    fun `content URIs and relative paths are not guessed by path lookup`() = runBlocking {
        val song = Kit.song(id = 7L, title = "Real Title", artist = "Real Artist", durationMs = 180_000L)
            .copy(path = "/storage/Music/Sub/Track.flac", contentUriString = "content://media/external/audio/media/7")
        val catalog = Kit.catalog(listOf(song))

        val csv = """
            played_at,artist,title,album,ms_played,duration_ms,path
            2026-11-26T10:00:00Z,Wrong Artist,Wrong Title,,180000,180000,"content://media/external/audio/media/7"
            2026-11-26T10:05:00Z,Wrong Artist,Wrong Title,,180000,180000,"Sub/Track.flac"
        """.trimIndent().toByteArray()

        val preview = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("history_nonguess.csv") { ByteArrayInputStream(csv) }),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )

        assertThat(preview.counts.matchedExact).isEqualTo(0)
        assertThat(preview.counts.unmatched).isEqualTo(2)
    }
}
