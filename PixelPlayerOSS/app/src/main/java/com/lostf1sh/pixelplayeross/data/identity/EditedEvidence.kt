package com.lostf1sh.pixelplayeross.data.identity

/**
 * What a library scan will read for a file once the metadata editor has rewritten it.
 *
 * B57 1.1. `SongMetadataEditor` writes tags, and a tag write changes every input of
 * [LibraryIdentity.computeKey]: the title, artist, album and track/disc, and, whenever the new
 * tags do not fit the file's padding (a cover, a long lyric, a longer title), the file size. The
 * item's stored evidence (`library_items.stable_key` and `file_signature`) was never updated, so the
 * next scan read "same locator, different fingerprint, different file signature, evidence not empty"
 * as Android reusing the number for another file, minted a new identity and left the old item
 * dormant with the favourites, plays, playlists, lyrics and bookmarks (found by B56,
 * `B56EditThenRescanIdentityTest`).
 *
 * The fix keeps [LibraryIdentity.computeKey] and [LibraryIdentity.fingerprintPosition] exactly as
 * they are and keeps the resolver's reuse rule: after a successful write the editor records the
 * evidence **a scan would now read**, computed here with those same functions.
 */
data class EditedFileFacts(
    val durationMs: Long,
    val fileSize: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    /** MediaStore's raw `TRACK` value: `disc * 1000 + track`, which is what the editor writes and the scanner reports. */
    val rawTrack: Int,
)

/** The pair the resolver compares: [stableKey] is the fingerprint, [fileSignature] the duration + size evidence. */
data class ItemEvidence(
    val stableKey: String,
    val fileSignature: String,
) {
    val isEmpty: Boolean get() = stableKey.isEmpty() && fileSignature.isEmpty()

    companion object {
        /**
         * "No evidence recorded." The resolver already reads this as "nothing here contradicts the
         * locator" (`LibraryIdentityResolver`, the empty-evidence rule `MIGRATION_7_8` made reachable): the item
         * is kept and the scan fills the evidence in. The editor uses it to park an item for the
         * duration of a write.
         */
        val NONE = ItemEvidence(stableKey = "", fileSignature = "")
    }
}

object EditedEvidence {

    /** The raw `TRACK` value the editor puts in MediaStore for an edited track/disc pair. */
    fun rawTrack(trackNumber: Int, discNumber: Int?): Int =
        (discNumber ?: 0).coerceAtLeast(0) * 1000 + trackNumber.coerceAtLeast(0)

    /**
     * The evidence a scan computes for [facts]: the same calls `SyncWorker` makes for a MediaStore
     * row, in the same order, with the frozen track/disc arithmetic.
     */
    fun of(facts: EditedFileFacts): ItemEvidence {
        val (keyTrack, keyDisc) = LibraryIdentity.fingerprintPosition(facts.rawTrack)
        return ItemEvidence(
            stableKey = LibraryIdentity.computeKey(
                durationMs = facts.durationMs,
                fileSize = facts.fileSize,
                title = facts.title,
                artist = facts.artist,
                album = facts.album,
                trackNumber = keyTrack,
                discNumber = keyDisc,
            ),
            fileSignature = LibraryIdentity.computeFileSignature(facts.durationMs, facts.fileSize),
        )
    }
}
