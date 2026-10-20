package com.lostf1sh.pixelplayeross.data.stats

import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository.PlaybackEvent
import kotlin.random.Random

/** A made-up library and a made-up two-year history of the size D9 measures. Nothing here is a listener's. */
internal object SyntheticHistory {
    const val MIN = 60_000L
    const val DAY = 24 * 60 * MIN

    fun songs(count: Int): List<Song> = (1..count).map { i ->
        val artist = "Artist ${i % 400}"
        Song.emptySong().copy(
            id = (1000 + i).toString(),
            libraryItemId = "00000000-0000-4000-8000-%012d".format(i),
            title = "Song $i",
            artist = artist,
            artists = listOf(ArtistRef(id = (i % 400).toLong(), name = artist, isPrimary = true)),
            album = "Album ${i / 12}",
            albumId = (i / 12).toLong(),
            genre = "Genre ${i % 17}",
            duration = (2 + i % 7) * MIN,
            trackNumber = i % 12 + 1,
            mimeType = "audio/flac",
            isFavorite = i % 97 == 0,
            albumArtUriString = null,
        )
    }

    /** [events] listens spread over [days] days ending at [endMs], in the shape B28a writes, with a long-tailed taste. */
    fun events(count: Int, songCount: Int, endMs: Long, days: Int = 730, seed: Int = 7): List<PlaybackEvent> {
        val random = Random(seed)
        val span = days * DAY
        val audio = listOf(
            ListenAudioFacts(ListenRoute.USB_DIRECT, ListenSignal.PURITY, ListenBitPerfect.UNKNOWN, 44_100, 16, "audio/flac", 44_100, 16, "audio/raw", "DAC One"),
            ListenAudioFacts(ListenRoute.USB_DIRECT, ListenSignal.PURITY, ListenBitPerfect.UNKNOWN, 96_000, 24, "audio/flac", 96_000, 24, "audio/raw", "DAC One"),
            ListenAudioFacts(ListenRoute.SPEAKER, null, ListenBitPerfect.NO, 44_100, 16, "audio/flac"),
        )
        val ends = LongArray(count) { endMs - (random.nextDouble() * span).toLong() }.also { it.sort() }
        return List(count) { n ->
            // A long tail: low numbers are heard far more than high ones.
            val song = 1 + (songCount * Math.pow(random.nextDouble(), 2.2)).toInt().coerceAtMost(songCount - 1)
            val length = (2 + song % 7) * MIN
            val skipped = random.nextInt(100) < 35
            val listened = if (skipped) 5_000L + random.nextLong(40_000) else length - random.nextLong(10_000)
            PlaybackEvent(
                songId = (1000 + song).toString(),
                timestamp = ends[n],
                durationMs = listened,
                startTimestamp = ends[n] - listened,
                endTimestamp = ends[n],
                libraryItemId = "00000000-0000-4000-8000-%012d".format(song),
                occurrenceId = "occ$n",
                outcome = if (skipped) PlaybackOutcome.SKIPPED else PlaybackOutcome.COMPLETED,
                endReason = if (skipped) TrackEndReason.USER_NEXT else TrackEndReason.NATURAL_END,
                itemDurationMs = length,
                audio = audio[random.nextInt(audio.size)],
                context = ListenContext(ListenSourceKind.LIBRARY, shuffle = random.nextBoolean()),
            )
        }
    }
}
