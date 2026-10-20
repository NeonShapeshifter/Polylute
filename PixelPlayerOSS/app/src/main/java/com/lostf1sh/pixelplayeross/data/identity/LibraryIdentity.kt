package com.lostf1sh.pixelplayeross.data.identity

import java.security.MessageDigest

/**
 * PixelPlayer's own durable identity for a library item.
 *
 * ## The problem this solves
 *
 * Until now the only identity a song had was `SongEntity.id` — the MediaStore `_ID` verbatim. That
 * is a *locator* owned by Android, not an identity owned by this app. It survives ordinary rescans,
 * metadata refreshes, artwork refreshes and restarts, which is why nothing appeared broken. It does
 * **not** survive a MediaStore renumber (a remount, a scanner rebuild, a delete-and-re-copy), and
 * because nothing recorded what a row's statistics were actually *about*, a reused `_ID` could in
 * principle hand one track's listening history to a completely different file.
 *
 * The fix is a second, app-owned datum: a key derived from evidence that is intrinsic to the file
 * and cheap to obtain. Engagement then hangs off that key rather than off Android's numbering, which
 * makes the two failure modes fall out structurally rather than needing special cases:
 *
 * * **renumber** — the same file yields the same key under a new locator, so history reattaches;
 * * **reuse** — a different file yields a different key, so it has no history to inherit.
 *
 * ## Why not a content hash
 *
 * Hashing the audio payload would be the strongest possible identity and is the wrong trade here.
 * The brief's §19 is explicit that a rescan must not become "hash gigabytes": a library of a few
 * thousand tracks would turn every scan into minutes of I/O, to answer a question that duration,
 * size and tags already answer. This key is computed from the MediaStore cursor row alone — no file
 * reads at all.
 *
 * ## What it deliberately is not
 *
 * This is **library-item** identity, not *recording* identity. Two encodes of the same performance,
 * the same song on a standard and a deluxe edition, and a lossless and a lossy copy all produce
 * different keys and stay separate, because they are separate things in the library. Collapsing
 * them would be a product decision about recordings, and the brief is explicit that this is not
 * that pass.
 */
object LibraryIdentity {

    /** Separator chosen because it cannot appear in a normalised metadata field. */
    private const val SEP = ""

    /**
     * Computes the stable key for one library item.
     *
     * Every input is taken from the **file as MediaStore reports it**, never from a user-edited
     * database value. That is what makes the key survive an in-app metadata edit: the stored key
     * was computed from the file, and a renumber recomputes from the same file, so the two agree.
     * Feeding it the edited title instead would break exactly the case identity exists to protect.
     *
     * [fileSize] may be 0 when the platform will not report it; that weakens the key but does not
     * invalidate it, and the ambiguity policy is deliberately biased against merging.
     */
    fun computeKey(
        durationMs: Long,
        fileSize: Long,
        title: String?,
        artist: String?,
        album: String?,
        trackNumber: Int = 0,
        discNumber: Int = 0,
    ): String {
        val payload = buildString {
            append(durationMs.coerceAtLeast(0L))
            append(SEP)
            append(fileSize.coerceAtLeast(0L))
            append(SEP)
            append(normalise(title))
            append(SEP)
            append(normalise(artist))
            append(SEP)
            append(normalise(album))
            append(SEP)
            append(trackNumber.coerceAtLeast(0))
            append(SEP)
            append(discNumber.coerceAtLeast(0))
        }
        return sha1Hex(payload)
    }

    /**
     * The track and disc that go into [computeKey] for a MediaStore `TRACK` value. **Frozen.**
     *
     * This is the arithmetic the stored keys were made with: the low 12 bits are the track, the next
     * 12 the disc. It is *not* how MediaStore packs the value (that is `disc * 1000 + track`, which
     * [com.lostf1sh.pixelplayeross.data.metadata.MetadataNormalizer.decodePackedTrack] now decodes
     * correctly for display), and it must not follow that decoder. A fingerprint is matching evidence
     * that has to be reproducible from a scan years later; it only has to be *stable*, not
     * meaningful. Checked against the owner's library (B11-fix FIX-3): all 4870 stored keys equal
     * the key made with this arithmetic and only one equals the key made with the decimal decode.
     * Switching would change ~4869 fingerprints at once, and an item whose `file_signature` was never
     * recorded (4814 of 4870 active items) reads a changed fingerprint as "different file under a
     * reused number", mints a new identity, and orphans its favourites, lyrics, bookmarks, playlist
     * memberships and play history.
     *
     * Returns (track, disc), zero meaning none.
     */
    fun fingerprintPosition(rawTrack: Int): Pair<Int, Int> {
        if (rawTrack <= 0) return 0 to 0
        return (rawTrack and 0x0FFF) to ((rawTrack shr 12) and 0x0FFF)
    }

    /**
     * The file-intrinsic part of the evidence: duration and size, with no tag-derived fields.
     *
     * This exists to answer one question that the full fingerprint cannot: when a locator comes
     * back carrying different evidence, was the *file* replaced or merely re-tagged? Tag edits move
     * the fingerprint — that is what a tag edit is — so the fingerprint cannot tell the two apart,
     * while duration and size, which no tag edit meaningfully moves, can.
     */
    fun computeFileSignature(durationMs: Long, fileSize: Long): String {
        val payload = "${durationMs.coerceAtLeast(0L)}$SEP${fileSize.coerceAtLeast(0L)}"
        return sha1Hex(payload)
    }

    /**
     * Normalises a metadata field for comparison.
     *
     * Case and surrounding whitespace are not identity: `"Song Title "` and `"song title"` are the
     * same track, and treating them as different would orphan history for no reason. Internal
     * whitespace is collapsed for the same reason. Nothing more aggressive is done — stripping
     * punctuation or accents starts merging genuinely different titles, which is the failure this
     * whole file exists to avoid.
     */
    internal fun normalise(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    /**
     * Lowercase hex of the SHA-1 of [payload], two digits per byte: the same text
     * `"%02x".format(byte)` produced for each byte, without a `String.format` per digit.
     *
     * The scan computes two of these for every file in the library, so on a 4,870-song library the
     * formatter alone was ~200,000 calls per sync and a measurable share of the CPU a fling competes
     * with right after the app starts (B13). The output is byte for byte what it was, which matters:
     * these strings are stored identities.
     */
    private fun sha1Hex(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val chars = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val value = digest[i].toInt() and 0xFF
            chars[i * 2] = HEX_DIGITS[value ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return String(chars)
    }
}

/**
 * How confidently a scan decided that a currently-present file is a previously-known library item.
 *
 * The asymmetry is the point, and it is the same asymmetry the brief asks for: losing a historical
 * attachment is a small, recoverable loss, while attaching history to the wrong track is a silent
 * corruption that no later pass can detect. So [EXACT] is the only outcome that reattaches
 * automatically, and everything less certain is refused.
 */
enum class IdentityMatchConfidence {
    /** The same locator, or a locator change with identical intrinsic evidence. Safe to reattach. */
    EXACT,

    /**
     * Evidence is suggestive but not conclusive — typically a duplicate pair.
     *
     * Deliberately never merged automatically. Two files that look alike are kept as two library
     * items, each with its own history, which is the conservative reading of the brief's §3.
     */
    AMBIGUOUS,

    /** Nothing known matches: this is a new library item. */
    NEW,
}
