package com.lostf1sh.pixelplayeross.data.worker

import timber.log.Timber

/**
 * B58 2 (#68): how long each phase of a library sync took, one log line per phase and one line at the end, under the tag `SyncWorker` (a technical tag of the Detailed log), so the
 * reviewer can read the HONOR's own timing instead of guessing where 14 minutes went.
 *
 * ```
 * sync phase=<name> ms=<milliseconds> items=<count or ->
 * sync done mode=<INCREMENTAL|FULL|REBUILD> songs=<songs saved> ms=<whole sync> phases=<name>:<ms>,<name>:<ms>,...
 * ```
 *
 * A phase is the time **since the previous line** ([lap] is called at the end of each one), so the code between two laps needs no wrapping and the phases add up to the whole sync.
 * The names, in the order they run: `walk-folders`, `inventory`, `identity-plan`, `dormant`, `fetch-metadata` (the MediaStore read and the tag reads of the files that changed),
 * `preprocess`, `save`, `reattach`, `overrides`, `seed-items`, `evidence`, `folder-artwork`, `composers`, `lrc-scan`, `clean-cache`. A phase that did not run has no line.
 */
internal class SyncPhaseLog(
    private val mode: String,
    private val nowNs: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit = { Timber.tag(SyncWorker.LOG_TAG).i(it) },
) {
    /** One phase as it ran: [items] is how many things it handled, or -1 when it has no count. */
    data class Entry(val name: String, val ms: Long, val items: Int)

    private val startedNs = nowNs()
    private var lastNs = startedNs
    private val _entries = ArrayList<Entry>()
    private var finished = false

    /** The phases that ran, in order. */
    val entries: List<Entry> get() = _entries

    /** Closes the phase [name]: the time since the last lap (or the start) is its own. */
    fun lap(name: String, items: Int = -1) {
        val now = nowNs()
        val entry = Entry(name, (now - lastNs) / 1_000_000L, items)
        lastNs = now
        _entries += entry
        sink("sync phase=${entry.name} ms=${entry.ms} items=${if (entry.items < 0) "-" else entry.items.toString()}")
    }

    /** The closing line: the whole sync and every phase's share of it. Written once, however many exits reach it. */
    fun finish(songs: Int) {
        if (finished) return
        finished = true
        val total = (nowNs() - startedNs) / 1_000_000L
        sink("sync done mode=$mode songs=$songs ms=$total phases=${_entries.joinToString(",") { "${it.name}:${it.ms}" }}")
    }
}
