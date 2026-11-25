package com.lostf1sh.pixelplayeross.data.historyimport

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.stats.Kit
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class B73MatchingAndDedupTest {

    private val librarySongs = listOf(
        Kit.song(1, title = "Canción del Sur", artist = "Inés Val", album = "Alba", durationMs = 200_000L)
            .copy(artists = listOf(ArtistRef(1, "Inés Val", true))),
        Kit.song(2, title = "Midnight Train", artist = "Echo & Pine", album = "North", durationMs = 240_000L)
            .copy(artists = listOf(ArtistRef(2, "Echo", true), ArtistRef(3, "Pine", false))),
        Kit.song(3, title = "Skyline", artist = "Taro Sato", album = "Neon", durationMs = 210_000L)
            .copy(artists = listOf(ArtistRef(4, "Taro Sato", true))),
        Kit.song(4, title = "Skyline (Club Remix)", artist = "Taro Sato", album = "Neon Remixes", durationMs = 260_000L)
            .copy(artists = listOf(ArtistRef(4, "Taro Sato", true))),
        Kit.song(5, title = "Homeward", artist = "Band Alpha", album = "First", durationMs = 190_000L)
            .copy(artists = listOf(ArtistRef(5, "Band Alpha", true))),
        Kit.song(6, title = "Homeward", artist = "Band Beta", album = "Second", durationMs = 220_000L)
            .copy(artists = listOf(ArtistRef(6, "Band Beta", true))),
        Kit.song(7, title = "Paper Lantern", artist = "Lena M", album = "Glow", durationMs = 180_000L)
            .copy(artists = listOf(ArtistRef(7, "Lena M", true))),
    )
    private val catalog = Kit.catalog(librarySongs)
    private val matcher = LibraryTrackMatcher.build(catalog)

    @Test
    fun `matches exact, folded accents, safe suffix stripping, and multi-artist credits`() {
        val (exact, exactItem) = matcher.match("Inés Val", "Canción del Sur", "Alba")
        assertThat(exact.confidence).isEqualTo(MatchConfidence.EXACT)
        assertThat(exactItem?.identity).isEqualTo("item-1")

        val (foldedAccent, accentItem) = matcher.match("Ines Val", "Cancion del Sur", "Alba")
        assertThat(foldedAccent.confidence).isEqualTo(MatchConfidence.FOLDED)
        assertThat(accentItem?.identity).isEqualTo("item-1")

        val (strippedRemaster, remItem) = matcher.match("Ines Val", "Cancion del Sur - Remastered 2011", "Alba")
        assertThat(strippedRemaster.confidence).isEqualTo(MatchConfidence.FOLDED)
        assertThat(remItem?.identity).isEqualTo("item-1")

        val (strippedTags, tagItem) = matcher.match("Lena M", "Paper Lantern (Radio Edit) [Explicit]", "Glow")
        assertThat(strippedTags.confidence).isEqualTo(MatchConfidence.FOLDED)
        assertThat(tagItem?.identity).isEqualTo("item-7")

        val (strippedLive, liveItem) = matcher.match("Lena M", "Paper Lantern (Live)", "Glow")
        assertThat(strippedLive.confidence).isEqualTo(MatchConfidence.FOLDED)
        assertThat(liveItem?.identity).isEqualTo("item-7")

        val (partialMulti, multiItem) = matcher.match("Echo", "Midnight Train (feat. Pine)", "North")
        assertThat(partialMulti.confidence).isEqualTo(MatchConfidence.PARTIAL)
        assertThat(multiItem?.identity).isEqualTo("item-2")
    }

    @Test
    fun `keeps Remix when a Remix exists in library and disambiguates identical titles by artist`() {
        // Library has both "Skyline" and "Skyline (Club Remix)" -> Remix is NOT stripped to "Skyline"
        val (clubRemix, clubItem) = matcher.match("Taro Sato", "Skyline (Club Remix)", "Neon Remixes")
        assertThat(clubRemix.confidence).isEqualTo(MatchConfidence.EXACT)
        assertThat(clubItem?.identity).isEqualTo("item-4")

        val (otherRemix, otherItem) = matcher.match("Taro Sato", "Skyline (Dub Remix)", "Singles")
        assertThat(otherRemix.confidence).isEqualTo(MatchConfidence.NONE)
        assertThat(otherItem).isNull()

        // No Acoustic version exists in library -> Acoustic fallback allowed as PARTIAL
        val (acousticFallback, acousticItem) = matcher.match("Lena M", "Paper Lantern (Acoustic)", null)
        assertThat(acousticFallback.confidence).isEqualTo(MatchConfidence.PARTIAL)
        assertThat(acousticItem?.identity).isEqualTo("item-7")

        // Same title by different artists
        val (_, alpha) = matcher.match("Band Alpha", "Homeward", null)
        val (_, beta) = matcher.match("Band Beta", "Homeward", null)
        val (unknown, none) = matcher.match("Band Gamma", "Homeward", null)
        assertThat(alpha?.identity).isEqualTo("item-5")
        assertThat(beta?.identity).isEqualTo("item-6")
        assertThat(unknown.confidence).isEqualTo(MatchConfidence.NONE)
        assertThat(none).isNull()
    }

    @Test
    fun `dedup prevents double counting on re-import and overlapping exports, and PlayRule handles known, unknown and skips`() = runBlocking {
        // item-1 has duration 200_000 ms (PlayRule threshold = 100_000 ms).
        // Unmatched track with unknown length uses 30_000 ms threshold (B74 B2).
        val exportA = """
            [
              {"ts":"2023-05-01T10:00:00Z","ms_played":110000,"master_metadata_track_name":"Canción del Sur","master_metadata_album_artist_name":"Inés Val"},
              {"ts":"2023-05-01T10:10:00Z","ms_played":80000,"master_metadata_track_name":"Canción del Sur","master_metadata_album_artist_name":"Inés Val"},
              {"ts":"2023-05-01T10:20:00Z","ms_played":250000,"master_metadata_track_name":"Unknown Long","master_metadata_album_artist_name":"Ghost"},
              {"ts":"2023-05-01T10:30:00Z","ms_played":20000,"master_metadata_track_name":"Unknown Short","master_metadata_album_artist_name":"Ghost"}
            ]
        """.trimIndent().toByteArray()

        val first = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("a.json") { ByteArrayInputStream(exportA) }),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )
        // Plays: item-1 (110s >= 100s) + Unknown Long (250s >= 30s) = 2 plays; skips: 80s (<100s) + 20s (<30s) = 2 skips
        assertThat(first.counts.plays).isEqualTo(2)
        assertThat(first.counts.skips).isEqualTo(2)

        val existingKeys = first.prepared.dedupHi.indices.mapTo(HashSet()) { idx ->
            DedupKey(first.prepared.dedupHi[idx], first.prepared.dedupLo[idx])
        }

        // Importing exact same file again adds 0 plays (stored plays deduped; skips re-evaluated as skips)
        val secondSame = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("a.json") { ByteArrayInputStream(exportA) }),
            catalog = catalog,
            existingDedupKeys = { existingKeys },
        )
        assertThat(secondSame.counts.plays).isEqualTo(0)
        assertThat(secondSame.counts.duplicatesSkipped).isEqualTo(2)
        assertThat(secondSame.counts.skips).isEqualTo(2)

        // Overlapping export with 1 overlapping row + 1 new row adds only the 1 new play
        val exportB = """
            [
              {"ts":"2023-05-01T10:00:00Z","ms_played":110000,"master_metadata_track_name":"Canción del Sur","master_metadata_album_artist_name":"Inés Val"},
              {"ts":"2023-06-01T10:00:00Z","ms_played":200000,"master_metadata_track_name":"Canción del Sur","master_metadata_album_artist_name":"Inés Val"}
            ]
        """.trimIndent().toByteArray()
        val overlapping = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("b.json") { ByteArrayInputStream(exportB) }),
            catalog = catalog,
            existingDedupKeys = { existingKeys },
        )
        assertThat(overlapping.counts.duplicatesSkipped).isEqualTo(1)
        assertThat(overlapping.counts.plays).isEqualTo(1)
    }

    @Test
    fun `spotify extended and account data overlapping on same minute never double count`() = runBlocking {
        val extendedJson = """
            [
              {"ts":"2024-02-10T14:30:47Z","ms_played":185000,"master_metadata_track_name":"Canción del Sur","master_metadata_album_artist_name":"Inés Val","master_metadata_album_album_name":"Alba"}
            ]
        """.trimIndent().toByteArray()
        val accountJson = """
            [
              {"endTime":"2024-02-10 14:30","artistName":"Inés Val","trackName":"Canción del Sur","msPlayed":185000},
              {"endTime":"2024-02-10 14:35","artistName":"Inés Val","trackName":"Canción del Sur","msPlayed":190000}
            ]
        """.trimIndent().toByteArray()

        // Extended imported first -> Account row in same UTC end minute + same msPlayed is deduped
        val extendedFirst = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("Streaming_History_Audio_2024.json") { ByteArrayInputStream(extendedJson) }),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )
        assertThat(extendedFirst.counts.plays).isEqualTo(1)

        // Same-batch combined pick also keeps total unique plays = 2 (no double counting)
        val combined = HistoryImportPipeline.prepareImport(
            sources = listOf(
                ImportInputSource("Streaming_History_Audio_2024.json") { ByteArrayInputStream(extendedJson) },
                ImportInputSource("StreamingHistory_music_0.json") { ByteArrayInputStream(accountJson) },
            ),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )
        assertThat(combined.counts.plays).isEqualTo(2)
        assertThat(combined.counts.duplicatesSkipped).isEqualTo(1)

        // Reverse same-batch order (Account first, Extended second) also keeps plays = 2
        val reversedCombined = HistoryImportPipeline.prepareImport(
            sources = listOf(
                ImportInputSource("StreamingHistory_music_0.json") { ByteArrayInputStream(accountJson) },
                ImportInputSource("Streaming_History_Audio_2024.json") { ByteArrayInputStream(extendedJson) },
            ),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )
        assertThat(reversedCombined.counts.plays).isEqualTo(2)
        assertThat(reversedCombined.counts.duplicatesSkipped).isEqualTo(1)
    }
}
