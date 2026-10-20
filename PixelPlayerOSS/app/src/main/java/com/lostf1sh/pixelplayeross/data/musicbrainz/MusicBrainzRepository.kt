package com.lostf1sh.pixelplayeross.data.musicbrainz

import com.lostf1sh.pixelplayeross.R
import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.data.database.MusicBrainzCurrentState
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class MusicBrainzRepository @Inject constructor(
    private val api: MusicBrainzApiService,
    private val musicDao: MusicDao
) {
    suspend fun search(song: Song): List<MusicBrainzMatch> = withContext(Dispatchers.IO) {
        api.searchRecording(
            title = song.title,
            artist = song.displayArtist,
            album = song.album,
            durationMs = song.duration.takeIf { it > 0L }
        )
    }

    /**
     * Writes a chosen MusicBrainz match and reports **what actually changed**.
     *
     * The write is conditional: a text field is filled only when the stored one is blank or a
     * placeholder, and the year only when it is non-positive. So "the request succeeded" and "the
     * metadata changed" are different facts, and before this the caller could not tell them apart —
     * it reported success either way.
     *
     * The comparison mirrors the update's own rules, which live in `MusicDao.applyMusicBrainzMatch`.
     * If those rules change, these must change with them; the two are deliberately adjacent.
     */
    suspend fun apply(song: Song, match: MusicBrainzMatch): MusicBrainzApplyResult =
        withContext(Dispatchers.IO) {
            val songId = song.id.toLongOrNull()
                ?: musicDao.getSongIdByContentUri(song.contentUriString)
                ?: error("Song is not present in the unified library")

            val before = musicDao.musicBrainzCurrentState(songId)

            val changedFields = buildSet {
                if (before == null || needsFill(before.title, UNKNOWN_TITLE)) {
                    add(MusicBrainzField.TITLE)
                }
                if (before == null || needsFill(before.artistName, UNKNOWN_ARTIST)) {
                    add(MusicBrainzField.ARTIST)
                }
                if (before == null || needsFill(before.albumName, UNKNOWN_ALBUM)) {
                    add(MusicBrainzField.ALBUM)
                }
                if (before == null || before.year <= 0) {
                    add(MusicBrainzField.YEAR)
                }
            }

            // The identifiers are written unconditionally by the update, so they "changed" only if
            // they were not already the ones being written.
            val identifiersChanged = before == null ||
                before.mbRecordingId != match.recordingId ||
                before.mbReleaseId != match.releaseId ||
                before.mbArtistId != match.artistId

            musicDao.applyMusicBrainzMatch(
                songId = songId,
                title = match.title,
                artist = match.artist,
                album = match.album,
                year = match.year,
                recordingId = match.recordingId,
                releaseId = match.releaseId,
                artistId = match.artistId
            )

            MusicBrainzApplyResult(
                changedFields = changedFields,
                identifiersChanged = identifiersChanged,
            )
        }

    /** True when the update's `CASE` would replace this stored value. */
    private fun needsFill(stored: String, placeholder: String): Boolean =
        stored.isBlank() || stored.trim().equals(placeholder, ignoreCase = true)

    private companion object {
        // The literal placeholder rules the SQL update uses.
        const val UNKNOWN_TITLE = "unknown title"
        const val UNKNOWN_ARTIST = "unknown artist"
        const val UNKNOWN_ALBUM = "unknown album"
    }
}

/**
 * Which human-readable fields a MusicBrainz write actually filled.
 *
 * [labelRes] is here rather than in the UI so the enum and its copy cannot drift; it is the only
 * resource reference in this file.
 */
enum class MusicBrainzField(@param:StringRes val labelRes: Int) {
    TITLE(R.string.song_field_title),
    ARTIST(R.string.song_field_artist),
    ALBUM(R.string.song_field_album),
    YEAR(R.string.song_field_year),
}

/**
 * What a MusicBrainz write changed.
 *
 * [identifiersChanged] is separate from [changedFields] because the identifiers are always written:
 * they can change on their own, when the match agrees with the names already stored.
 */
data class MusicBrainzApplyResult(
    val changedFields: Set<MusicBrainzField>,
    val identifiersChanged: Boolean,
) {
    /** True when anything at all was persisted differently. */
    val changedAnything: Boolean
        get() = changedFields.isNotEmpty() || identifiersChanged
}
