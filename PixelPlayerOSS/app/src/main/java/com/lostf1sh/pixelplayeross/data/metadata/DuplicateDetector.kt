package com.lostf1sh.pixelplayeross.data.metadata

import javax.inject.Inject
import javax.inject.Singleton

/** What the detector needs to know about a track: who it is, what it is called and how long it is. */
data class DuplicateCandidate(
    val libraryItemId: String,
    val title: String,
    val artist: String,
    val albumId: Long,
    val durationMs: Long,
)

/**
 * Two or more library items that may represent the same recording.
 *
 * The name says *probable* because that is all this can be: nothing here has heard the audio.
 */
data class DuplicateGroup(
    val key: String,
    val tracks: List<DuplicateCandidate>,
    /** Why these were grouped, for the diagnostic. */
    val evidence: String,
) {
    val size: Int get() = tracks.size

    /**
     * The duration spread across the group, in milliseconds.
     *
     * A wide spread is itself informative: two files a second apart are almost certainly the same
     * recording encoded twice, while two files a minute apart are an edit and a full version that
     * merely share a title.
     */
    val durationSpreadMs: Long
        get() = (tracks.maxOf { it.durationMs } - tracks.minOf { it.durationMs })
            .coerceAtLeast(0L)
}

/**
 * Groups probable duplicates, and stops there.
 *
 * ## What it must never do
 *
 * It does not merge, delete, hide, rename or re-point anything. Phase 12.1 guarantees that the
 * *same file* is one library item; this is about two items that may be one recording, and the answer
 * to that is a question for the user. A detector that acted on its own output would be a destructive
 * metadata feature wearing an informational name.
 *
 * ## Evidence, and why it is cheap
 *
 * Title and artist, normalized by [MetadataNormalizer.duplicateKey], with a duration tolerance
 * applied afterwards. No content hashing — the brief rules it out, and it would turn a suggestion
 * into a full-library read.
 *
 * Duration is a **filter, not a key**. Two copies of one recording commonly differ by a few
 * milliseconds (a different encoder's padding), and occasionally by a lot (a different master), so
 * folding duration into the key would split the pairs most worth reporting. Instead the group is
 * formed on title and artist and then split when the durations are too far apart to be the same
 * performance.
 *
 * ## Ambiguity stays ambiguous
 *
 * A group of two is reported as a group of two. Nothing ranks them, nothing nominates a "keeper",
 * and a track that cannot be identified — no title, no artist — is not reported at all, because
 * "unknown artist — unknown title" would group half a badly-tagged library as one "duplicate".
 */
@Singleton
class DuplicateDetector @Inject constructor() {

    fun findGroups(tracks: List<DuplicateCandidate>): List<DuplicateGroup> {
        val grouped = LinkedHashMap<String, MutableList<DuplicateCandidate>>()

        tracks.forEach { track ->
            val key = MetadataNormalizer.duplicateKey(track.title, track.artist) ?: return@forEach
            grouped.getOrPut(key) { mutableListOf() } += track
        }

        return grouped
            .filterValues { it.size > 1 }
            .flatMap { (key, candidates) -> splitByDuration(key, candidates) }
            .sortedWith(
                compareByDescending<DuplicateGroup> { it.size }.thenBy { it.tracks.first().title },
            )
    }

    /**
     * Splits one title/artist match into groups whose durations are close enough to be one recording.
     *
     * A fixed tolerance rather than a percentage: encoder padding is a fixed number of milliseconds,
     * so a proportional window would be far too wide on a long track and too narrow on a short one.
     */
    private fun splitByDuration(key: String, candidates: List<DuplicateCandidate>): List<DuplicateGroup> {
        val sorted = candidates.sortedBy { it.durationMs }
        val groups = mutableListOf<MutableList<DuplicateCandidate>>()
        sorted.forEach { track ->
            val current = groups.lastOrNull()
            if (current != null && track.durationMs - current.last().durationMs <= DURATION_TOLERANCE_MS) {
                current += track
            } else {
                groups += mutableListOf(track)
            }
        }
        return groups
            .filter { it.size > 1 }
            .map { members ->
                DuplicateGroup(
                    key = key,
                    tracks = members,
                    evidence = buildString {
                        append("same normalized title and artist")
                        val spread = members.maxOf { it.durationMs } - members.minOf { it.durationMs }
                        if (spread > 0) {
                            append(" · durations within ${spread}ms")
                        } else {
                            append(" · identical duration")
                        }
                        val distinctAlbums = members.map { it.albumId }.toSet().size
                        if (distinctAlbums > 1) append(" · across $distinctAlbums albums")
                    },
                )
            }
    }

    private companion object {
        /**
         * How far apart two durations may be and still be the same recording.
         *
         * Two seconds: comfortably wider than encoder padding and gapless-playback offsets, and far
         * narrower than the difference between a radio edit and an album version.
         */
        const val DURATION_TOLERANCE_MS = 2_000L
    }
}

