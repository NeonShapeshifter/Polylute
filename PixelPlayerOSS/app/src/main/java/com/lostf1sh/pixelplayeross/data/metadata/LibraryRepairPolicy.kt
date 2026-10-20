package com.lostf1sh.pixelplayeross.data.metadata

/**
 * FIX-3 (B11-fix): the rules for the track, disc and year of a song already in the library, as pure
 * functions, so the sync, the one-off repair and the details screen all say the same thing.
 *
 * What the device showed (backup `device-backup-pre-B11`, `songs` table, against ffprobe on the files):
 *
 * - **track**: `track_number = 1012` for 4869 of 4870 songs, MediaStore's `disc * 1000 + track`
 *   stored as if it were the track. Track Details printed it as "1-1012". [MetadataNormalizer]
 *   decoded the packed value as binary, which is why every scan kept producing it; and the merge kept
 *   whatever nonzero value was already stored, so even a correct decode would not have repaired a row.
 * - **year**: `year = 0` for 4867 of 4870. MediaStore's `YEAR` is NULL for these FLACs and the scan
 *   never reads tags, so the files' `DATE=1998-01-01` was never seen.
 *
 * Nothing here touches title, artist, album or genre, and nothing runs a tag read.
 */
object LibraryRepairPolicy {

    /**
     * Whether the user has edited this row's tags. The editor sets the four `*_user_edited` flags
     * together with the track and disc it writes, so any one of them means the stored track and disc
     * are the user's, not the scan's.
     */
    fun isUserEdited(
        titleEdited: Boolean,
        artistEdited: Boolean,
        albumEdited: Boolean,
        genreEdited: Boolean,
    ): Boolean = titleEdited || artistEdited || albumEdited || genreEdited

    /**
     * The track and disc a re-scanned song is stored with.
     *
     * - A row the **user edited** keeps its stored values (the user's), except that a packed value is
     *   unpacked: nobody types track 1012, it is the packed value copied into the editor.
     * - Any other row takes what the scan just decoded. The stored value is not authoritative: it is
     *   an earlier scan's answer, and keeping it is how a wrong value outlived the code that made it.
     */
    fun mergePosition(
        storedTrack: Int,
        storedDisc: Int?,
        userEdited: Boolean,
        scannedTrack: Int,
        scannedDisc: Int?,
    ): MetadataNormalizer.StoredPosition =
        if (userEdited && storedTrack != 0) {
            MetadataNormalizer.unpackStoredPosition(storedTrack, storedDisc)
        } else {
            MetadataNormalizer.StoredPosition(scannedTrack, scannedDisc)
        }

    /**
     * The year a re-scanned song is stored with: the scan's when it has one, otherwise the stored
     * one. MediaStore has no year for many files; a year read from the file's tags (the repair) must
     * not be zeroed by the next scan merely because MediaStore still does not know it.
     */
    fun mergeYear(storedYear: Int, scannedYear: Int): Int =
        if (scannedYear > 0) scannedYear else storedYear.coerceAtLeast(0)

    /**
     * Whether the year in the library already agrees with MediaStore's. A MediaStore with no year
     * agrees with everything: it is not a claim that the song has none.
     */
    fun isYearUnchanged(storedYear: Int, mediaStoreYear: Int?): Boolean {
        val scanned = MetadataNormalizer.parseYear(mediaStoreYear) ?: return true
        return storedYear == scanned
    }

    /**
     * The "disc-track" a song shows in Track Details, or null when it has no track.
     *
     * Defensive on purpose: a stored packed value (a library not yet repaired, or an edit that wrote
     * one) is unpacked for display, so the screen can never print "1-1012".
     */
    fun trackLabel(track: Int, disc: Int?): String? {
        val position = MetadataNormalizer.unpackStoredPosition(track, disc)
        if (position.track <= 0) return null
        val shownDisc = position.disc?.takeIf { it > 0 }
        return if (shownDisc != null) "$shownDisc-${position.track}" else position.track.toString()
    }

    /**
     * The order the tracks of an album are listed in: disc first (no disc counts as the first), then
     * track (no track last), then title. With the disc and the track stored apart, a sort on the track
     * alone would interleave the discs of a multi-disc album (1-1, 2-1, 1-2, 2-2).
     */
    fun <T> albumOrder(disc: (T) -> Int?, track: (T) -> Int, title: (T) -> String): Comparator<T> =
        compareBy<T> { disc(it)?.takeIf { d -> d > 0 } ?: 1 }
            .thenBy { track(it).takeIf { t -> t > 0 } ?: Int.MAX_VALUE }
            .thenBy { title(it).lowercase() }
}
