package com.lostf1sh.pixelplayeross.data.intelligence

import com.lostf1sh.pixelplayeross.data.stats.PlaybackOutcome
import com.lostf1sh.pixelplayeross.data.stats.TrackEndReason

/**
 * Everything the app believes about one track, in one place, for Maintenance only.
 *
 * This exists because the identity model is otherwise invisible. A user cannot see that a track
 * kept its history across a renumber, and a developer cannot see whether the app agrees with them.
 * When something does go wrong — a favourite that seems to have moved, a play count that looks
 * wrong — the question is always the same: *which logical item does the app think this is, and what
 * does it think happened to it?* That is what this answers.
 *
 * It is **read-only**. Nothing here writes, and nothing here is used by playback or by any
 * recommendation path. It is scoped to Maintenance because it exposes internal identity, which is
 * engineering detail rather than something a listener should have to reason about.
 */
data class TrackIntelligenceDiagnostic(
    // --- identity -------------------------------------------------------------------------------
    val libraryItemId: String,
    /** MediaStore `_ID`. A locator: how the row is found, never what it is. */
    val currentLocator: Long?,
    val lifecycle: String,
    /** Last known matching evidence, retained even while the item is dormant. */
    val stableKey: String,
    /** Duration + size: the file-intrinsic evidence that survives a tag edit. */
    val fileSignature: String,
    val projectionExists: Boolean,
    val title: String,
    val artist: String,
    val album: String,

    // --- engagement -----------------------------------------------------------------------------
    val playCount: Int,
    val completionCount: Int,
    val skipCount: Int,
    val totalListenedMs: Long,
    val firstPlayedAt: Long,
    val lastPlayedAt: Long,
    val lastCompletedAt: Long,
    val lastSkippedAt: Long,

    // --- the counters that came before the history was read per occurrence ----------------------
    /** What `song_engagements` holds for the item, which is no longer written; null where it has no row. */
    val legacyPlayCount: Int? = null,
    val legacySkipCount: Int? = null,

    // --- the automatic collections --------------------------------------------------------------
    /** Which of them list this track now, in the order the Playlists screen shows them. */
    val collections: List<CollectionMembership> = emptyList(),

    // --- the session in flight ------------------------------------------------------------------
    val session: SessionView?,
) {
    /** Whether an automatic collection lists the track. [key] is the collection's stored key. */
    data class CollectionMembership(val key: String, val isMember: Boolean)

    /** The current listening session, as measured so far. */
    data class SessionView(
        val listenedMs: Long,
        val totalDurationMs: Long,
        val isPlaying: Boolean,
        val endReason: TrackEndReason,
        val projectedOutcome: PlaybackOutcome,
    )

    /**
     * The diagnostic as labelled rows.
     *
     * Formatting is separated from gathering so the labels and the honest handling of missing values
     * are testable without a database, a device or a clock.
     */
    fun rows(): List<Row> = buildList {
        add(Row("Identity", null))
        add(Row("library_item_id", libraryItemId.ifBlank { "— (not assigned)" }))
        add(Row("current locator", currentLocator?.toString() ?: "— (no projection)"))
        add(Row("lifecycle", lifecycle))
        add(Row("projection row", if (projectionExists) "present" else "none"))
        add(Row("matching fingerprint", stableKey.ifBlank { "— (not recorded yet)" }))
        add(Row("file signature", fileSignature.ifBlank { "— (not recorded yet)" }))
        add(Row("title", title))
        add(Row("artist", artist))
        add(Row("album", album))

        add(Row("Listening (from the history)", null))
        add(Row("plays", playCount.toString()))
        add(Row("completions", completionCount.toString()))
        add(Row("skips", skipCount.toString()))
        add(Row("total listened", formatDuration(totalListenedMs)))
        add(Row("first played", formatTimestamp(firstPlayedAt)))
        add(Row("last played", formatTimestamp(lastPlayedAt)))
        add(Row("last completed", formatTimestamp(lastCompletedAt)))
        add(Row("last skipped", formatTimestamp(lastSkippedAt)))

        add(Row("Legacy counters (before B28, no longer written)", null))
        add(Row("legacy plays", legacyPlayCount?.toString() ?: "— (no row)"))
        add(Row("legacy skips", legacySkipCount?.toString() ?: "— (no row)"))

        add(Row("Automatic collections", null))
        if (collections.isEmpty()) {
            add(Row("collections", "— (not built yet)"))
        } else {
            collections.forEach { add(Row(it.key, if (it.isMember) "in" else "not in")) }
        }

        add(Row("Current session", null))
        if (session == null) {
            add(Row("state", "no session in flight"))
        } else {
            add(Row("listened this session", formatDuration(session.listenedMs)))
            add(Row("track duration", formatDuration(session.totalDurationMs)))
            add(Row("playing", if (session.isPlaying) "yes" else "no"))
            add(Row("TrackEndReason", session.endReason.name))
            add(
                Row(
                    "projected PlaybackOutcome",
                    session.projectedOutcome.name.let { outcome ->
                        // Said plainly, because the projection is a measurement and not a promise:
                        // while the track is still playing the only honest reason is UNKNOWN.
                        if (session.isPlaying) "$outcome (measured so far, reason unknown while playing)"
                        else outcome
                    },
                )
            )
        }

        add(Row("Rules", null))
        add(Row("user state owner", "library_item_id"))
        add(Row("locator role", "how the file is found; disposable"))
    }

    /** A labelled line. A null [value] means "section heading". */
    data class Row(val label: String, val value: String?)

    companion object {
        /**
         * Renders a timestamp, or says so.
         *
         * Zero is not 1970 in this schema — it is the documented encoding of "never", which
         * `MIGRATION_6_7` chose deliberately rather than guessing a date that would silently misdate
         * a track the user has owned for years. Showing "1 Jan 1970" here would undo that decision.
         */
        fun formatTimestamp(epochMs: Long): String =
            if (epochMs <= 0L) "— (never)" else java.time.Instant.ofEpochMilli(epochMs).toString()

        fun formatDuration(ms: Long): String {
            if (ms <= 0L) return "0s"
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }
}
