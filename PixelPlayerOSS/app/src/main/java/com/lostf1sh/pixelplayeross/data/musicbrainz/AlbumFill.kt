package com.lostf1sh.pixelplayeross.data.musicbrainz

import java.text.Normalizer
import java.util.Locale

/**
 * B57 1.3, "Fill from MusicBrainz" in the album editor: what a release says against what the album has now, field by field.
 *
 * Pure: it only compares and never writes. The editor shows [propose]'s result as a diff with a checkbox per field and applies the checked ones to its
 * own form with [apply]; **nothing reaches a file until Save**.
 */
data class AlbumFormState(
    val album: String,
    val albumArtist: String,
    /** `YYYY`, `YYYY-MM` or `YYYY-MM-DD`, or empty. */
    val date: String,
    val genre: String,
    val tracks: List<AlbumFormTrack>,
)

data class AlbumFormTrack(val songId: String, val title: String, val trackNumber: Int, val discNumber: Int?)

/** One thing the release would change. [id] is stable for the checkbox: album-level fields are named, track fields carry the song. */
data class FieldProposal(val id: String, val kind: Kind, val songId: String?, val current: String, val proposed: String) {
    enum class Kind { ALBUM, ALBUM_ARTIST, DATE, GENRE, TRACK_TITLE, TRACK_NUMBER, DISC_NUMBER }
}

object AlbumFill {

    /** The release that applies to this album: the MBID the files carry (most common among them) when there is one. */
    fun knownReleaseId(releaseIdsOfSongs: List<String?>): String? =
        releaseIdsOfSongs.filterNotNull().filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    fun propose(state: AlbumFormState, release: MusicBrainzRelease): List<FieldProposal> = buildList {
        fun offer(kind: FieldProposal.Kind, songId: String?, current: String, proposed: String?) {
            val value = proposed?.trim().orEmpty()
            if (value.isEmpty() || value == current.trim()) return
            add(FieldProposal(id = if (songId == null) kind.name else "${kind.name}:$songId", kind = kind, songId = songId, current = current, proposed = value))
        }
        offer(FieldProposal.Kind.ALBUM, null, state.album, release.title)
        offer(FieldProposal.Kind.ALBUM_ARTIST, null, state.albumArtist, release.artist)
        // A release date is only offered when it adds something: the same year in a longer spelling (2019 -> 2019-05-03) is a proposal, a different year is too.
        offer(FieldProposal.Kind.DATE, null, state.date, release.date)
        offer(FieldProposal.Kind.GENRE, null, state.genre, release.genres.firstOrNull()?.let(::titleCase))

        val matches = matchTracks(state.tracks, release.tracks)
        for (track in state.tracks) {
            val match = matches[track.songId] ?: continue
            offer(FieldProposal.Kind.TRACK_TITLE, track.songId, track.title, match.title)
            if (track.trackNumber != match.position) offer(FieldProposal.Kind.TRACK_NUMBER, track.songId, track.trackNumber.toString(), match.position.toString())
            if ((track.discNumber ?: 0) != match.disc && (match.disc > 1 || (track.discNumber ?: 0) > 0)) {
                offer(FieldProposal.Kind.DISC_NUMBER, track.songId, track.discNumber?.toString().orEmpty(), match.disc.toString())
            }
        }
    }

    /** The form with the [accepted] proposals applied; every field not accepted is as it was. */
    fun apply(state: AlbumFormState, proposals: List<FieldProposal>, accepted: Set<String>): AlbumFormState {
        var next = state
        for (proposal in proposals) {
            if (proposal.id !in accepted) continue
            next = when (proposal.kind) {
                FieldProposal.Kind.ALBUM -> next.copy(album = proposal.proposed)
                FieldProposal.Kind.ALBUM_ARTIST -> next.copy(albumArtist = proposal.proposed)
                FieldProposal.Kind.DATE -> next.copy(date = proposal.proposed)
                FieldProposal.Kind.GENRE -> next.copy(genre = proposal.proposed)
                FieldProposal.Kind.TRACK_TITLE -> next.withTrack(proposal.songId) { it.copy(title = proposal.proposed) }
                FieldProposal.Kind.TRACK_NUMBER -> next.withTrack(proposal.songId) { it.copy(trackNumber = proposal.proposed.toIntOrNull() ?: it.trackNumber) }
                FieldProposal.Kind.DISC_NUMBER -> next.withTrack(proposal.songId) { it.copy(discNumber = proposal.proposed.toIntOrNull()) }
            }
        }
        return next
    }

    private fun AlbumFormState.withTrack(songId: String?, change: (AlbumFormTrack) -> AlbumFormTrack) =
        copy(tracks = tracks.map { if (it.songId == songId) change(it) else it })

    /**
     * Which release track each song is. By disc + track number when the song has them and the release has that slot; otherwise by the title, when exactly one
     * release track has it. A song that matches neither (or matches a slot another song already took) gets no proposals: a doubtful match is never offered.
     */
    internal fun matchTracks(songs: List<AlbumFormTrack>, release: List<MusicBrainzReleaseTrack>): Map<String, MusicBrainzReleaseTrack> {
        val bySlot = release.groupBy { it.disc to it.position }
        val byTitle = release.groupBy { normalise(it.title) }
        val taken = HashSet<Pair<Int, Int>>()
        val result = LinkedHashMap<String, MusicBrainzReleaseTrack>()
        for (song in songs) {
            val bySlotMatch = if (song.trackNumber > 0) bySlot[(song.discNumber?.takeIf { it > 0 } ?: 1) to song.trackNumber]?.singleOrNull() else null
            val candidate = bySlotMatch ?: byTitle[normalise(song.title)]?.singleOrNull()
            if (candidate != null && taken.add(candidate.disc to candidate.position)) result[song.songId] = candidate
        }
        return result
    }

    internal fun normalise(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun titleCase(genre: String) = genre.split(' ', '-').joinToString(" ") { part -> part.replaceFirstChar { it.titlecase(Locale.ROOT) } }
}
