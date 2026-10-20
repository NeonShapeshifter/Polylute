package com.lostf1sh.pixelplayeross.data.stats

import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository.PlaybackEvent
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Synthetic libraries and histories for the stats tests (B28). Nothing here comes from a listener's data: every title, artist and
 * identity is made up by the test that asks for it.
 */
internal object Kit {
    val zone: ZoneId = ZoneId.of("UTC")
    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    const val DAY = 24 * HOUR

    fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** A track of the library: [id] is the locator, [identity] the durable id (defaults to `item-<id>`). */
    fun song(
        id: Long,
        identity: String = "item-$id",
        title: String = "Song $id",
        artist: String = "Artist",
        album: String = "",
        albumId: Long = 0L,
        durationMs: Long = 4 * MIN,
        favourite: Boolean = false,
        sampleRate: Int? = null,
        track: Int = 0,
    ): Song = Song.emptySong().copy(
        id = id.toString(),
        libraryItemId = identity,
        title = title,
        artist = artist,
        artists = listOf(ArtistRef(id = artist.hashCode().toLong(), name = artist, isPrimary = true)),
        album = album,
        albumId = albumId,
        duration = durationMs,
        isFavorite = favourite,
        sampleRate = sampleRate,
        trackNumber = track,
        albumArtUriString = null,
    )

    fun catalog(songs: List<Song>, firstSeen: Map<String, Long> = emptyMap(), withoutFile: Set<String> = emptySet()): LibraryCatalog =
        LibraryCatalog.of(songs, firstSeen, withoutFile)

    /** One finished listen of [songId] that ended at [endMs] after [listenedMs]. */
    fun event(
        songId: Long,
        endMs: Long,
        listenedMs: Long,
        identity: String? = null,
        occurrence: String? = null,
        outcome: PlaybackOutcome? = null,
        reason: TrackEndReason? = null,
        itemMs: Long? = null,
        audio: ListenAudioFacts? = null,
    ) = PlaybackEvent(
        songId = songId.toString(),
        timestamp = endMs,
        durationMs = listenedMs,
        startTimestamp = endMs - listenedMs,
        endTimestamp = endMs,
        libraryItemId = identity,
        occurrenceId = occurrence,
        outcome = outcome,
        endReason = reason,
        itemDurationMs = itemMs,
        audio = audio,
    )

    /** A listen of a track that is a play on its own: three of its four minutes. */
    fun play(songId: Long, endMs: Long, identity: String? = "item-$songId", occurrence: String? = "o$songId-$endMs") =
        event(songId, endMs, 3 * MIN, identity, occurrence, PlaybackOutcome.PLAYED, TrackEndReason.USER_NEXT, 4 * MIN)

    fun ledger(events: List<PlaybackEvent>, catalog: LibraryCatalog): ListeningLedger = ListeningLedger.build(events, catalog)
}
