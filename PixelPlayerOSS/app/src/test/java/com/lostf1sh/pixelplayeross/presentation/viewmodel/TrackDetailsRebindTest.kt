package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.model.Song
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Track Details surface while it follows playback from one track to the next.
 *
 * Two properties are pinned here. A bind naming a new song is never treated as a no-op — that is
 * what makes the sheet move at all — and a result computed for a track the view has already left
 * never lands on the track that replaced it, which is what keeps the sheet from showing one
 * track's numbers under another track's title.
 */
class TrackDetailsRebindTest {

    private fun song(id: String, sampleRate: Int? = null, bitrate: Int? = null) =
        Song.emptySong().copy(
            id = id,
            title = "Track $id",
            duration = 180_000L,
            sampleRate = sampleRate,
            bitrate = bitrate,
        )

    private fun reading(songId: String, sampleRateHz: Int? = null) = LiveAudioFormat(
        songId = songId,
        format = "FLAC",
        sampleRateHz = sampleRateHz,
    )

    // ---- G: a rebind for a new song is never swallowed -------------------------------------

    @Test
    fun `a bind naming a new song is never redundant`() {
        // The bug in one line: the details were re-bound for the song they were already showing, so
        // every bind returned early and the sheet never moved. A bind naming a different song must
        // always be a real rebind.
        assertFalse(
            isBindRedundant(
                observedSongId = "A",
                observedLive = reading("A", 44_100),
                songId = "B",
                reading = reading("B", 48_000),
            )
        )
    }

    @Test
    fun `a bind repeating the same song and the same reading changes nothing`() {
        // Recompositions re-issue the same bind constantly; it must not re-read the file each time.
        assertTrue(
            isBindRedundant(
                observedSongId = "A",
                observedLive = reading("A", 44_100),
                songId = "A",
                reading = reading("A", 44_100),
            )
        )
    }

    @Test
    fun `a reading arriving after the first bind is a real rebind`() {
        // The player names a stream a moment after it starts, so the same song is bound twice: once
        // without a reading and once with. The second must not be swallowed.
        assertFalse(
            isBindRedundant(
                observedSongId = "A",
                observedLive = null,
                songId = "A",
                reading = reading("A", 44_100),
            )
        )
    }

    @Test
    fun `the same song bound before anything was observed is a real rebind`() {
        assertFalse(
            isBindRedundant(
                observedSongId = null,
                observedLive = null,
                songId = "A",
                reading = null,
            )
        )
    }

    // ---- D: a late result for the previous song cannot land on the new one ------------------

    @Test
    fun `a result for a song the view has left is refused`() {
        // The file read, the waveform decode and the identifier lookup for A can all still be in
        // flight when B becomes current. Cancelling does not stop work that has already finished,
        // so the result is refused on arrival instead.
        assertFalse(isSongScopedResultCurrent(currentSongId = "B", resultSongId = "A"))
    }

    @Test
    fun `a result for the song on screen is applied`() {
        assertTrue(isSongScopedResultCurrent(currentSongId = "B", resultSongId = "B"))
    }

    @Test
    fun `a result arriving before any song is bound is refused`() {
        assertFalse(isSongScopedResultCurrent(currentSongId = null, resultSongId = "A"))
    }

    @Test
    fun `an A to B to C transition never lets a late result through`() {
        // Walk a skip sequence and, at each step, offer the results that could still be in flight.
        // Only the results belonging to the track now on screen may be applied.
        val applied = mutableListOf<String>()
        var current = "A"
        for (next in listOf("B", "C")) {
            current = next
            listOf("A", "B", "C").forEach { result ->
                if (isSongScopedResultCurrent(current, result)) applied += result
            }
        }

        assertEquals(listOf("B", "C"), applied)
    }

    // ---- B and C: the source and metadata describe the song that is bound --------------------

    @Test
    fun `the source follows the song it is bound to, not the one before it`() {
        val technicalB = AudioMetadataReader.TechnicalAudioInfo(
            format = "FLAC",
            sampleRate = 48_000,
            bitDepth = 24,
            bitrate = 900_000,
            durationMs = 200_000L,
            channels = 2,
        )

        // Bound to B, with B's own file facts and B's own live reading.
        val forB = mergeSourceDetails(
            song = song("B"),
            technical = technicalB,
            live = reading("B", 48_000),
            fileSizeBytes = 42L,
        )

        assertEquals(48_000, forB.sampleRateHz)
        assertEquals("FLAC", forB.format)
        assertEquals(24, forB.bitDepth)

        // Bound to A with A's reading: nothing of B's survives into it either.
        val forA = mergeSourceDetails(
            song = song("A"),
            technical = null,
            live = reading("A", 44_100),
            fileSizeBytes = null,
        )

        assertEquals(44_100, forA.sampleRateHz)
        assertNull(forA.bitDepth)
    }

    @Test
    fun `a reading belonging to another track is refused before it can be merged`() {
        // bind() filters the reading by song before it reaches the merge, and LiveAudioFormat.from
        // refuses to produce one for a track the player is not rendering. Between them, a reading
        // can only ever describe the song it is merged into.
        val readingForA = LiveAudioFormat.from(
            metadata = PlaybackAudioMetadata(mediaId = "A", mimeType = "audio/flac", sampleRate = 44_100),
            durationMs = 180_000,
            songId = "A",
        )
        assertNotNull(readingForA)

        val readingForB = LiveAudioFormat.from(
            metadata = PlaybackAudioMetadata(mediaId = "A", mimeType = "audio/flac", sampleRate = 44_100),
            durationMs = 180_000,
            songId = "B",
        )
        assertNull(readingForB, "a reading for A must not be produced for B")

        // And the merge, given the filtered reading bind() would pass, carries nothing of A.
        val merged = mergeSourceDetails(
            song = song("B", sampleRate = 96_000),
            technical = null,
            live = readingForA?.takeIf { it.songId == "B" },
            fileSizeBytes = null,
        )
        assertEquals(96_000, merged.sampleRateHz, "B's own rate, never A's")
        assertEquals(180_000L, merged.durationMs, "B's own duration, never A's measured one")
    }
}
