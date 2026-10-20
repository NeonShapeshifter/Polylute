package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.service.RestoreQueuePolicy.Presence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One row of an external file's folder as the MediaStore index lists it. Nothing was opened to
 * produce it: this is the index, read once for the whole folder.
 */
internal data class IndexedFolderRow(
    val id: Long,
    /** `content://media/external/audio/media/<id>`. */
    val uri: String,
    val displayName: String?,
    val title: String?,
    val artist: String?,
    val artistId: Long?,
    val album: String?,
    val albumId: Long?,
    val durationMs: Long?,
    val track: Int?,
    val year: Int?,
    val dateAddedSeconds: Long?,
    val dataPath: String?,
    val mimeType: String?,
)

/** Which rows of a folder listing queue up behind the file that was opened. Pure. */
internal object FolderSiblings {
    /**
     * The rows after the opened file in listing order; when the file is not in the listing, every
     * row but the file itself. The file is recognised by its URI or, failing that, its display name
     * (the same rule the queue has always used, so a renamed URI still finds its place).
     */
    fun after(rows: List<IndexedFolderRow>, openedUri: String, openedDisplayName: String?): List<IndexedFolderRow> {
        val name = openedDisplayName?.lowercase()?.trim()
        fun isOpened(row: IndexedFolderRow): Boolean =
            row.uri == openedUri || (name != null && row.displayName?.lowercase()?.trim() == name)

        val start = rows.indexOfFirst(::isOpened)
        return if (start != -1) rows.drop(start + 1) else rows.filterNot(::isOpened)
    }
}

/**
 * FIX-1 (B11-fix): the queue behind an external VIEW, built so the song that was opened never waits
 * for it.
 *
 * B11 probed every sibling of the opened file (an open per file) before queueing anything, and the
 * queue had always read every sibling's tags and artwork: on a 2855-file folder the VIEW took 10-50 s
 * to start while the previous queue kept playing. The opened song now starts alone; this builds the
 * tail from the index in the background and attaches it, and it is the only place that decides how
 * much of the tail is checked for dead entries.
 *
 * **Why a bounded look-ahead instead of checking the lot.** An entry whose file is gone only matters
 * when playback reaches it. The index outliving its files is rare and, when it happens, usually in
 * runs; the next few entries are what the listener reaches in the next minutes, so those are
 * checked (cheap: a handful of opens) and dropped, and everything further out is left as indexed.
 * If one of those turns out to be dead when it comes up, the player's own error handling skips it
 * (up to five in a row) and the listener is not shown an error. Checking all of them is what made the
 * start slow, and checking none leaves the first press of Next to land on a dead entry.
 */
internal object ExternalQueueContinuation {
    /** Stop checking once this many entries in a row at the head are known to exist. */
    const val PLAYABLE_LOOKAHEAD = 3

    /** Never open more than this many files to clean the head, however many are dead. */
    const val MAX_LOOKAHEAD_PROBES = 8

    suspend fun <T> pruneHead(siblings: List<T>, probe: suspend (T) -> Presence): List<T> {
        val kept = ArrayList<T>(siblings.size)
        var probes = 0
        var playable = 0
        for (item in siblings) {
            if (probes < MAX_LOOKAHEAD_PROBES && playable < PLAYABLE_LOOKAHEAD) {
                probes++
                if (probe(item) == Presence.MISSING) continue
                playable++
            }
            kept.add(item)
        }
        return kept
    }
}

/**
 * Starts the opened song, and only then builds and attaches the rest.
 *
 * [run] returns as soon as [start] has handed the song to the player; the tail is loaded, pruned and
 * attached by the job it returns. Neither the sibling listing nor any probe is on the path to
 * [start], however many siblings there are.
 */
internal class ExternalPlayback<T>(
    private val start: suspend () -> Unit,
    private val loadSiblings: suspend () -> List<T>,
    private val probe: suspend (T) -> Presence,
    private val attach: suspend (List<T>) -> Unit,
) {
    suspend fun run(scope: CoroutineScope): Job {
        start()
        return scope.launch {
            val tail = ExternalQueueContinuation.pruneHead(loadSiblings(), probe)
            if (tail.isNotEmpty()) attach(tail)
        }
    }
}
