package com.lostf1sh.pixelplayeross.data.diagnostics

import java.util.concurrent.Executor

/**
 * The route trace, off the main thread (AUD-035).
 *
 * Every route decision used to read the whole trace file and rewrite it, synchronously, on the main
 * thread — 18 call sites, up to [maxLines] lines each time. Recording is now an append to a bounded
 * in-memory buffer; the file is written on [executor], and records arriving while a write is pending
 * share it.
 */
class RouteTraceLog(
    private val maxLines: Int,
    private val executor: Executor,
    private val clock: () -> Long,
    private val write: (List<String>) -> Unit,
) {
    private val lines = ArrayDeque<String>()
    private var writePending = false

    fun record(tag: String, detail: String) {
        val schedule = synchronized(this) {
            lines.addLast("${clock()} $tag $detail")
            while (lines.size > maxLines) lines.removeFirst()
            if (writePending) false else {
                writePending = true
                true
            }
        }
        if (!schedule) return
        executor.execute {
            val snapshot = synchronized(this) {
                writePending = false
                lines.toList()
            }
            runCatching { write(snapshot) }
        }
    }
}
