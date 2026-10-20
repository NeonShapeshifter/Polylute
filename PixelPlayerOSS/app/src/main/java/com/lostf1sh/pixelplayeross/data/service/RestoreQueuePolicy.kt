package com.lostf1sh.pixelplayeross.data.service

/**
 * F-09: which items of a saved queue to bring back, when some of their files are gone.
 *
 * The snapshot used to be restored as saved, so a queue whose files had been deleted from storage came
 * back pointing at nothing: the player prepared the current item, failed, skipped a few more, and sat
 * in `ERROR` — after a restart too, because the same dead queue was restored again. Only choosing a
 * song from the Library, which replaces the queue, got out of it.
 *
 * What is wanted is the behaviour a listener would expect: the queue comes back without the songs
 * that no longer exist, and playback is parked on the first one that does. Two limits shape it:
 *
 * - **Probing is a call per item** (an open on the file's content URI), and a queue can be thousands
 *   of songs long. So the saved current item and the ones after it are probed until a playable one is
 *   found, and the others are not probed at all: they come back as saved. A dead song that surfaces
 *   later is skipped by the player's own error handling, one at a time. The probing is also capped
 *   ([MAX_PROBES]): a queue whose next 300 songs are all gone is not worth restoring, and the cap is
 *   what keeps a fully deleted queue of thousands from costing seconds of opens at start-up.
 * - **"Not found" has to be certain.** A file on an SD card that is not mounted, or one the app has no
 *   right to open, is not a deleted file, and its queue entry is not dropped for it (see [Presence]).
 *
 * Pure: the probe is an argument, so every case is testable without a device.
 */
object RestoreQueuePolicy {

    enum class Presence {
        /** The file opened. */
        PRESENT,

        /** The platform said, with certainty, that there is no such file. */
        MISSING,

        /** No certain answer: volume not mounted, no access, a scheme that cannot be probed. */
        UNKNOWN,
    }

    /**
     * @property keptPositions positions (indices into the queue handed in) to restore, in order.
     * @property startPosition index **within [keptPositions]** to start on; null when nothing is left.
     * @property droppedCount how many entries were left out because their file is gone.
     * @property startWasReplaced the saved current item is not the one started on, so its saved
     *           position is meaningless and playback must begin at 0.
     */
    data class Selection(
        val keptPositions: List<Int>,
        val startPosition: Int?,
        val droppedCount: Int,
        val startWasReplaced: Boolean,
        /** How many files were opened to decide this; for the log, and for the cap's test. */
        val probedCount: Int = 0,
    )

    /** The most files one restore opens. The common case is one: the saved current item. */
    const val MAX_PROBES = 300

    /**
     * FIX-1c: whether a restored queue may be put into the player. Only an empty one.
     *
     * The restore is what was playing *before*; it is read from disk and probed on a background
     * thread, so it can finish after something newer has already loaded (an external VIEW, a song
     * chosen in the app). Whatever got there first wins: the restore never replaces it, never
     * overwrites it later, and is not mistaken for a replacement by whoever loaded first. It is
     * asked on the main thread, right before the queue is set, after all the off-main work.
     */
    fun mayApply(playerItemCount: Int): Boolean = playerItemCount <= 0

    /**
     * @param count queue length.
     * @param savedIndex the saved current item (clamped into range).
     * @param probe called for at most the items that decide where to start, each at most once.
     */
    fun select(count: Int, savedIndex: Int, maxProbes: Int = MAX_PROBES, probe: (Int) -> Presence): Selection {
        if (count <= 0) return Selection(emptyList(), null, 0, startWasReplaced = false)
        val saved = savedIndex.coerceIn(0, count - 1)
        val dropped = BooleanArray(count)
        var probes = 0

        fun playable(index: Int): Boolean {
            probes++
            if (probe(index) == Presence.MISSING) {
                dropped[index] = true
                return false
            }
            return true
        }

        // The saved current item, then the ones after it; if nothing from there on is playable, the
        // ones before it, nearest first. The first playable one is where playback is parked.
        var start: Int? = null
        for (index in saved until count) {
            if (probes >= maxProbes) break
            if (playable(index)) { start = index; break }
        }
        if (start == null) {
            for (index in saved - 1 downTo 0) {
                if (probes >= maxProbes) break
                if (playable(index)) { start = index; break }
            }
        }

        val kept = (0 until count).filter { !dropped[it] }
        val startPosition = start?.let { kept.indexOf(it) }
        return Selection(
            keptPositions = if (start == null) emptyList() else kept,
            startPosition = startPosition,
            droppedCount = dropped.count { it },
            startWasReplaced = start != null && start != saved,
            probedCount = probes,
        )
    }
}
