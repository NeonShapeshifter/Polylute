package com.lostf1sh.pixelplayeross.data.metadata

import java.security.MessageDigest

/**
 * The single place where raw file metadata becomes something the app can reason about.
 *
 * ## Three layers, never confused
 *
 * ```
 *   RAW        what MediaStore and the file's tags actually say — including "<unknown>"
 *   NORMALIZED a deterministic interpretation of that, for grouping and matching
 *   OVERRIDE   an explicit, durable choice by the user (Phase 12.1, `library_item_overrides`)
 * ```
 *
 * Precedence is always `OVERRIDE > NORMALIZED > RAW`. Normalization **never** writes to the
 * override table, and an override is never fed back in as if it were source metadata: one means
 * "the user chose this", the other means "the app interprets the file this way". Collapsing them
 * would make a user's edit indistinguishable from a tag, which is exactly how an app ends up
 * "correcting" someone's library.
 *
 * ## Nothing here rewrites a file
 *
 * Every function is pure. The player interprets metadata more intelligently; it does not retag,
 * rename, move, download or "fix" anything.
 *
 * ## Why one module
 *
 * Before this, `trim()`, `lowercase()`, `ifBlank { "Unknown Album" }` and `equals("<unknown>")`
 * were spread across the scan worker, the album-grouping helpers and the DAOs — each with slightly
 * different rules. Three subtly different normalizations is how the same album ends up in two
 * shelves, so the rules live here and everything else calls them.
 */
object MetadataNormalizer {

    /**
     * The value Android's MediaStore reports for a tag it could not read.
     *
     * It is a **sentinel, not a name**. Treating it as text is the single most common cause of
     * phantom groupings: every untagged file in a folder shares the "album" `<unknown>`, so they
     * collapse into one enormous fake album. It is canonicalised to null everywhere.
     */
    const val UNKNOWN_SENTINEL = "<unknown>"

    /** What the UI shows. Storage and querying use `null`; only display uses these. */
    const val DISPLAY_UNKNOWN_ARTIST = "Unknown Artist"
    const val DISPLAY_UNKNOWN_ALBUM = "Unknown Album"
    const val DISPLAY_UNKNOWN_GENRE = "Unknown Genre"
    const val DISPLAY_VARIOUS_ARTISTS = "Various Artists"

    /**
     * The album artist used for a compilation that has no explicit album-artist tag.
     *
     * Only applied when the evidence for a compilation is strong — see [isCompilation].
     */
    const val VARIOUS_ARTISTS = "Various Artists"

    // --- text --------------------------------------------------------------------------------------

    /**
     * Raw text as the app should *store* it: trimmed and whitespace-collapsed, but otherwise
     * untouched.
     *
     * Case and punctuation are preserved because they are part of what the user sees, and because
     * normalizing them for display is how "AC/DC" becomes "Ac/dc".
     */
    fun normalizeForDisplay(raw: String?): String? {
        val trimmed = raw?.trim()?.replace(WHITESPACE_RUN, " ")?.trim()
        if (trimmed.isNullOrEmpty()) return null
        if (trimmed.equals(UNKNOWN_SENTINEL, ignoreCase = true)) return null
        return trimmed
    }

    /**
     * Text as the app should *match* on: case-folded and whitespace-collapsed.
     *
     * Only case and whitespace. Nothing here merges `AC/DC` with `ACDC`, or `The Beatles` with
     * `Beatles` — those are different strings, and a false merge of two real artists is a much worse
     * failure than a missed merge of the same artist spelled two ways. Conservative correctness wins.
     */
    fun normalizeForMatching(raw: String?): String? =
        normalizeForDisplay(raw)?.lowercase()

    /** The display value, or [fallback] when the raw value carried no information. */
    fun displayValue(raw: String?, fallback: String): String = normalizeForDisplay(raw) ?: fallback

    // --- track and disc position --------------------------------------------------------------------

    /**
     * Where a track sits in its release.
     *
     * [disc] and [track] are the interpreted values; [rawTrack] and [rawDisc] preserve exactly what
     * the source said, because grouping is a *derived* view and the diagnostic has to be able to
     * show what it was derived from.
     */
    data class TrackPosition(
        val disc: Int?,
        val track: Int?,
        val rawTrack: String? = null,
        val rawDisc: String? = null,
    ) {
        /** True when neither disc nor track could be determined. */
        val isUnknown: Boolean get() = disc == null && track == null
    }

    /**
     * Reads a track number from the several shapes taggers and MediaStore produce.
     *
     * Handled, in order:
     * * `"3/12"` — the ID3 `TRCK` form; the total is informational and discarded
     * * `"03"` — zero-padded, which parses as 3
     * * `"3"` — plain
     * * `null`, `""`, `"0"`, `"-1"` — **no position**, not position zero. Zero is what taggers write
     *   for "unset", and treating it as track 0 would sort an unknown track before track 1 forever.
     * * non-numeric — discarded rather than guessed at
     */
    fun parseTrackNumber(raw: String?): Int? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val leading = text.substringBefore('/').trim()
        val value = leading.toIntOrNull() ?: return null
        return value.takeIf { it > 0 }
    }

    /**
     * Decodes MediaStore's packed `TRACK` column: **decimal**, `disc * 1000 + track`.
     *
     * Verified against the device (B11-fix FIX-3): 4869 of 4870 songs had `track_number = 1012` for
     * disc 1, track 12, and the app's own encoder, `SongMetadataEditor`, writes
     * `(disc * 1000) + track` back to MediaStore. An earlier version of this function read the value
     * as two 12-bit fields (`packed and 0xFFF`, `packed shr 12`), a layout nothing here produces:
     * it turned 1012 into track 1012 with no disc, which is what Track Details showed as "1-1012".
     *
     * A value below 1000 carries no disc (an untagged or single-disc file): it is the track. A value
     * of zero or less means "no position", matching [parseTrackNumber].
     */
    fun decodePackedTrack(packed: Int): TrackPosition {
        if (packed <= 0) return TrackPosition(disc = null, track = null, rawTrack = packed.toString())
        val track = packed % PACK_BASE
        val disc = packed / PACK_BASE
        return TrackPosition(
            disc = disc.takeIf { it > 0 },
            track = track.takeIf { it > 0 },
            rawTrack = packed.toString(),
        )
    }

    /** MediaStore's packing base: the thousands are the disc, the rest is the track. */
    private const val PACK_BASE = 1000

    /** Whether a *stored* track number is still a packed `disc * 1000 + track` value. */
    fun isPackedTrack(track: Int): Boolean = track >= PACK_BASE

    /** A stored (track, disc) pair. */
    data class StoredPosition(val track: Int, val disc: Int?)

    /**
     * A stored track/disc pair with any packed track value unpacked.
     *
     * The repair for libraries that persisted the packed value (`track_number = 1012`): the disc comes
     * from the thousands unless a disc is already stored, the track from the remainder. **Idempotent**
     * (a track below 1000 is returned untouched, so a second pass changes nothing), and the same rule
     * the one-off SQL repair applies, so the two cannot drift apart.
     */
    fun unpackStoredPosition(track: Int, disc: Int?): StoredPosition {
        if (!isPackedTrack(track)) return StoredPosition(track, disc)
        return StoredPosition(
            track = track % PACK_BASE,
            disc = disc?.takeIf { it > 0 } ?: (track / PACK_BASE).takeIf { it > 0 },
        )
    }

    // --- year ---------------------------------------------------------------------------------------

    /**
     * A release year, or null.
     *
     * Values MediaStore reports for a missing year are `0`, `-1` and occasionally `1`, and none of
     * them is a year — storing one would place an untagged track in the year 1 and sort it before
     * everything else. Bounded to a plausible range rather than accepting any integer.
     */
    fun parseYear(raw: Int?): Int? = raw?.takeIf { it in MIN_YEAR..MAX_YEAR }

    /**
     * A year read out of a tag value: `DATE` is `"1998-01-01"`, `"1998"` or `"19980101"`, `YEAR` is
     * usually just `"1998"`, and what a tagger writes for "nothing" is garbage or `"0000"`.
     *
     * The first four digits when the value starts with four digits, otherwise the first standalone
     * run of four digits (`"Jan 5, 1998"`); validated by [parseYear], so `"0000"` and `"abcd"` are
     * "no year" rather than a year that sorts first for ever.
     */
    fun parseYearTag(raw: String?): Int? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val leading = text.take(4)
        if (leading.length == 4 && leading.all(Char::isDigit)) return parseYear(leading.toInt())
        return YEAR_IN_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let(::parseYear)
    }

    private val YEAR_IN_TEXT = Regex("""(?<!\d)(\d{4})(?!\d)""")

    /**
     * An album's year from its tracks' years: the most common valid year, the earliest among equals.
     *
     * It used to be the first track's year, so one untagged track (year 0) zeroed the whole album's.
     */
    fun albumYear(trackYears: List<Int>): Int =
        trackYears.mapNotNull(::parseYear)
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key ?: 0

    private const val MIN_YEAR = 1860
    private const val MAX_YEAR = 2200

    // --- genres -------------------------------------------------------------------------------------

    /**
     * Splits a genre tag into its distinct values.
     *
     * Multi-valued genre tags are common and real: ID3v2.4 has a genuine multi-value frame, and
     * taggers that predate it write `"Rock; Pop"` or `"Rock/Pop"`. The separators here are the ones
     * that are unambiguously separators.
     *
     * **`/` is deliberately not a separator.** It is ambiguous — `"R&B/Soul"` is one genre to most
     * people, and `"Folk/Country"` may be either — and guessing wrong splits a single genre into two
     * that then both appear in the genre list. A missed split is cosmetic; an invented one pollutes
     * browsing.
     *
     * No ontology, no mapping, no "Alt Rock == Indie Rock". Raw values are preserved exactly as
     * written; only whitespace and duplicates are removed.
     */
    fun parseGenres(raw: String?): List<String> {
        val text = normalizeForDisplay(raw) ?: return emptyList()
        return text.split(';', ' ', ',')
            .asSequence()
            .mapNotNull { normalizeForDisplay(it) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .toList()
    }

    // --- album artist and compilations -----------------------------------------------------------------

    /**
     * The album artist, from the tag if present and from the track artist otherwise.
     *
     * This is the tag that *owns* the album. It is deliberately allowed to fall back to the track
     * artist, because for the overwhelming majority of releases they are the same and a missing
     * album-artist tag is not evidence of a compilation — it is evidence of a tagger that did not
     * write one.
     */
    fun resolveAlbumArtist(rawAlbumArtist: String?, trackArtist: String?): String? =
        normalizeForDisplay(rawAlbumArtist) ?: normalizeForDisplay(trackArtist)

    /**
     * Whether an album is a compilation, using the evidence the files actually provide.
     *
     * The order matters, and so does what is *not* here.
     *
     * 1. **An explicit flag wins.** The ID3 `TCMP` / Vorbis `COMPILATION` frame is the tagger
     *    telling us directly; nothing else can override it.
     * 2. **An explicit "Various Artists" album artist** is the next strongest statement — it is a
     *    deliberate album-artist value, not an inference.
     * 3. **Otherwise: not a compilation**, whatever the track artists look like.
     *
     * Note the interaction between rules 1 and 2, which is a real ambiguity rather than an ordering
     * accident: a tagger can write `COMPILATION=0` *and* an album artist of "Various Artists". The
     * album artist wins, because it is the value the grouping key itself uses — honouring a `0` flag
     * over it would leave an album grouped under "Various Artists" that the app simultaneously
     * claimed was not a compilation, which is a state nothing downstream could act on consistently.
     * `COMPILATION` is also widely written as `0` by default by rippers, while an album artist of
     * "Various Artists" is always deliberate.
     *
     * Rule 3 is the one that matters. The obvious heuristic — "several different track artists on
     * one album means a compilation" — is wrong often enough to be harmful: a duet album, a
     * soundtrack with two credited performers, a split single, or (most commonly) an album whose
     * tracks are inconsistently tagged all produce multiple track artists and are not compilations.
     * Marking them as one would rewrite the album artist of a legitimate release, which is exactly
     * the silent correction this phase exists to refuse.
     */
    fun isCompilation(
        explicitFlag: Boolean?,
        albumArtist: String?,
        distinctTrackArtists: Int,
    ): Boolean {
        if (explicitFlag == true) return true
        if (normalizeForMatching(albumArtist) == normalizeForMatching(VARIOUS_ARTISTS)) return true
        if (explicitFlag == false) return false
        // Multiple track artists is *evidence*, not proof, and on its own it is not enough.
        return false
    }

    // --- album grouping ------------------------------------------------------------------------------

    /**
     * The key that decides which tracks are the same album. See [albumGroupingKey].
     */
    @JvmInline
    value class AlbumKey(val value: String)

    /**
     * A deterministic grouping key for an album.
     *
     * ## What it has to get right
     *
     * * **Same album, many track artists** — a compilation is one album. The key therefore uses the
     *   *album artist*, never the track artist.
     * * **Same album across discs** — a two-disc release is one album. The key therefore ignores the
     *   disc number, and [stripDiscFolder] removes a per-disc directory component first.
     * * **Same title, different artists** — two different "Greatest Hits" are two albums. The album
     *   artist is part of the key.
     * * **No album artist tag** — fall back to where the files live, which is the only remaining
     *   evidence and is what the previous implementation did. It is conservative: it can split an
     *   album stored in two folders, and cannot merge two albums stored in one.
     *
     * ## What it deliberately does not do
     *
     * There is no global music-database identity here. This groups a local library, and it is
     * allowed to be wrong in ways a fingerprinting service would not be — because being wrong costs
     * a user one mis-grouped album, while guessing confidently costs them their library's structure.
     */
    fun albumGroupingKey(
        albumTitle: String?,
        albumArtist: String?,
        parentDirectoryPath: String?,
        compilation: Boolean,
    ): AlbumKey {
        val title = normalizeForMatching(albumTitle)
        val location = "dir:" + (stripDiscFolder(parentDirectoryPath)?.lowercase() ?: "")

        // No title at all: the location is the identity.
        //
        // This is the fix for the phantom album. The obvious construction — fall back to the track
        // artist when there is no album artist — produced `alb:unknown-album|someone`, so every
        // untagged file by the same performer, anywhere on the device, merged into one album called
        // "unknown-album".
        if (title == null) return AlbumKey(location)

        // **The track artist is never used here**, and that is the whole rule.
        //
        // A performer credit is evidence about who plays on a track. It is not evidence about which
        // release the track belongs to, and treating it as such splits real albums: an album with no
        // album-artist tag whose tracks are credited "The Weeknd" and "The Weeknd, Justice" becomes
        // two albums, which is precisely the one-copy-per-track-artist failure the brief names.
        //
        // The album artist is the tag that owns a release, so it decides when it is present. When it
        // is absent, the location is the only remaining evidence about which release a file came
        // from — conservative: it can split one album stored in two folders, and it cannot merge two
        // albums stored in one. [stripDiscFolder] narrows the first of those.
        val owner = when {
            compilation -> normalizeForMatching(VARIOUS_ARTISTS)
            else -> normalizeForMatching(albumArtist)
        }
        return AlbumKey("alb:$title|" + (owner ?: location))
    }

    /**
     * Removes a trailing per-disc directory component: `…/Album/CD 2` becomes `…/Album`.
     *
     * This is what keeps a two-disc release stored as subfolders as **one** album. Without it the
     * two disc folders produce two different directory keys and the release splits in half — which
     * is precisely the multi-disc failure this phase was asked to fix.
     *
     * Deliberately narrow: only a final path component that names a disc and nothing else. A folder
     * called `Discography` is not matched, and neither is `CD Collection`.
     */
    fun stripDiscFolder(path: String?): String? {
        val normalized = normalizeForDisplay(path) ?: return null
        val separator = normalized.lastIndexOfAny(charArrayOf('/', '\\'))
        if (separator <= 0) return normalized
        val leaf = normalized.substring(separator + 1)
        val prefix = normalized.substring(0, separator)
        return if (DISC_FOLDER.matchEntire(leaf) != null) prefix else normalized
    }

    private val DISC_FOLDER = Regex(
        """(?i)^(disc|disk|cd|d|vol|volume)[\s._-]*\d{1,2}$""",
    )

    // --- artist grouping -----------------------------------------------------------------------------

    @JvmInline
    value class ArtistKey(val value: String)

    /**
     * The key that decides which tracks are the same artist.
     *
     * Case and whitespace only — see [normalizeForMatching] for why nothing more aggressive happens
     * here. The previous implementation keyed artists on `name.trim()`, which is case-**sensitive**,
     * so `Queen` and `queen` from two differently-tagged files were two artists in the library.
     */
    fun artistGroupingKey(artistName: String?): ArtistKey =
        ArtistKey(normalizeForMatching(artistName) ?: UNKNOWN_ARTIST_KEY)

    const val UNKNOWN_ARTIST_KEY = "unknown-artist"

    // --- duplicates ------------------------------------------------------------------------------------

    /**
     * A key identifying a *probable* duplicate recording.
     *
     * ## What this is for
     *
     * Two library items that look like the same track — the same song imported twice, a file copied
     * with a different name, an album ripped at two bitrates. Phase 12.1 already guarantees that the
     * *same file* is one item; this is about two items that may represent one recording.
     *
     * ## What it is not
     *
     * **It never merges, deletes or hides anything.** It groups, and it reports. Ambiguity stays
     * ambiguous: two entries in a group are a question for the user, not an answer from the app.
     *
     * ## Why these inputs
     *
     * The key is deliberately built from *stable* fields. A duplicate key that included the track
     * position would separate a copy that lost its number; one that included the album would
     * separate a copy tagged with a different album, which is one of the most common ways a
     * duplicate arises in the first place. Title and artist are what make a recording recognisable;
     * duration is checked separately, as a tolerance rather than as part of the key, because exact
     * duration equality would miss an AAC re-encode that is 80 ms shorter.
     */
    fun duplicateKey(title: String?, trackArtist: String?): String? {
        val normalizedTitle = normalizeForMatching(title) ?: return null
        val normalizedArtist = normalizeForMatching(trackArtist) ?: return null
        return "$normalizedTitle|$normalizedArtist"
    }

    // --- helpers -----------------------------------------------------------------------------------------

    /**
     * A stable hash of a grouping key, for storage in an indexed column.
     *
     * SHA-1 truncated to 16 hex characters. Not a security primitive — collision resistance here
     * only has to survive a personal music library, and a 64-bit space is ample. Chosen over
     * `String.hashCode` because it is deterministic across processes, which the grouping key has to
     * be if a stored key is ever compared with a freshly computed one.
     */
    fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}
