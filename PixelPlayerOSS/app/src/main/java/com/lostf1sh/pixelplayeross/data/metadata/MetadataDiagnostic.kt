package com.lostf1sh.pixelplayeross.data.metadata

/**
 * What the app believes about one track's metadata, and *why*.
 *
 * The question this answers is the one a grouping bug always produces: **"why is this track in this
 * album?"** That cannot be answered from the resolved values alone, because the resolved values are
 * the output — so this carries the raw source, the normalized interpretation, and the grouping key
 * that actually decided, side by side.
 *
 * Read-only, Maintenance-only. Nothing here is consulted by playback, grouping or recommendation.
 */
data class MetadataDiagnostic(
    val libraryItemId: String,

    /** Exactly what the source said, including a sentinel like `<unknown>`. */
    val rawTitle: String?,
    val rawArtist: String?,
    val rawAlbum: String?,
    val rawAlbumArtist: String?,
    val rawGenre: String?,
    val rawTrack: String?,
    val rawYear: Int,

    /** What the app resolved those to. Null means "no information", not "empty string". */
    val resolvedTitle: String?,
    val trackArtist: String?,
    val albumArtist: String?,
    val albumTitle: String?,
    val genres: List<String>,
    val releaseYear: Int?,

    /** The keys that decided the grouping. */
    val albumGroupingKey: String,
    val artistGroupingKey: String,

    val disc: Int?,
    val track: Int?,

    /** Owned by the library item, not by the MediaStore projection. */
    val firstSeenAt: Long,
    /** What MediaStore currently reports. */
    val dateAdded: Long,

    val isCompilation: Boolean,

    /** Non-null when this track is in a probable-duplicate group. */
    val duplicateGroup: String?,
) {
    /** A labelled row. A null [value] means "section heading". */
    data class Row(val label: String, val value: String?)

    /**
     * Rows for display, with inferred values marked as such.
     *
     * A resolved value that differs from the raw one is annotated, because "the app decided this"
     * is a different fact from "the file says this" and a diagnostic that blurs them cannot explain
     * a grouping decision.
     */
    fun rows(): List<Row> = buildList {
        add(Row("Raw (as the source reports it)", null))
        add(Row("title", rawTitle ?: "— (none)"))
        add(Row("artist", rawArtist ?: "— (none)"))
        add(Row("album", rawAlbum ?: "— (none)"))
        add(Row("album artist", rawAlbumArtist ?: "— (none)"))
        add(Row("genre", rawGenre ?: "— (none)"))
        add(Row("track (raw)", rawTrack ?: "— (none)"))
        add(Row("year (raw)", rawYear.toString()))

        add(Row("Resolved", null))
        add(Row("title", resolvedTitle ?: "— (none)"))
        add(Row("track artist", trackArtist ?: "— (none)"))
        add(Row("album artist", withInference(albumArtist, rawAlbumArtist)))
        add(Row("album", albumTitle ?: "— (none)"))
        add(Row("genres", if (genres.isEmpty()) "— (none)" else genres.joinToString(", ")))
        add(Row("release year", withInference(releaseYear?.toString(), rawYear.takeIf { it > 0 }?.toString())))
        add(Row("compilation", if (isCompilation) "yes (explicit evidence)" else "no"))

        add(Row("Grouping", null))
        add(Row("album key", albumGroupingKey.ifBlank { "— (not computed yet)" }))
        add(Row("artist key", artistGroupingKey.ifBlank { "— (not computed yet)" }))
        add(Row("disc", disc?.toString() ?: "— (unset)"))
        add(Row("track", track?.toString() ?: "— (unset)"))
        add(Row("position source", positionSource()))

        add(Row("Dates", null))
        add(Row("first seen", TrackIntelligenceDiagnosticDates.format(firstSeenAt)))
        add(Row("MediaStore date added", TrackIntelligenceDiagnosticDates.format(dateAdded)))
        if (firstSeenAt > 0L && dateAdded > 0L && firstSeenAt != dateAdded) {
            add(Row("", "these differ — the library item's own arrival date is the one used"))
        }

        add(Row("Duplicates", null))
        add(Row("probable duplicate group", duplicateGroup ?: "none"))
    }

    /** Marks a resolved value that was inferred rather than read. */
    private fun withInference(resolved: String?, raw: String?): String = when {
        resolved == null -> "— (none)"
        raw == null -> "$resolved  (inferred: source had none)"
        MetadataNormalizer.normalizeForDisplay(raw) == null -> "$resolved  (inferred: source was a sentinel)"
        else -> resolved
    }

    private fun positionSource(): String = when {
        rawTrack == null -> "not reported"
        rawTrack == "0" -> "the source reported 0, which means unset — not track zero"
        else -> "decoded from the source's packed value"
    }
}

/** Date rendering shared by the metadata rows. Kept separate so the rows stay pure. */
internal object TrackIntelligenceDiagnosticDates {
    fun format(epochMs: Long): String =
        if (epochMs <= 0L) "— (never)" else java.time.Instant.ofEpochMilli(epochMs).toString()
}
