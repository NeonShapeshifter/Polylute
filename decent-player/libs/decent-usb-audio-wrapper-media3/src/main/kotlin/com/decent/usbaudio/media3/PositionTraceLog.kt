package com.decent.usbaudio.media3

import java.util.concurrent.Executor

/**
 * Persistent ring of every native-engine seek and every anchor/offset change (AUD-139), each with
 * its origin and the positions of Media3 and the engine at that instant.
 *
 * The logcat buffer on the device is 256 KiB and vendor spam fills it in seconds: the forward jumps
 * the owner heard at 21:22 were gone before anyone could read them. This file outlives that, and the
 * app. Built like the route trace (AUD-035): recording is an append to a bounded in-memory buffer on
 * the caller's thread; the file is written on [executor], and records arriving while a write is
 * pending share it.
 */
class PositionTraceLog(
    private val maxLines: Int,
    private val executor: Executor,
    private val clock: () -> String,
    private val write: (List<String>) -> Unit,
) {
    private val lines = ArrayDeque<String>()
    private var writePending = false

    fun record(event: String, origin: String, media3Ms: Long, engineMs: Long, detail: String) {
        val schedule = synchronized(this) {
            lines.addLast("${clock()} $event origin=$origin media3=${media3Ms}ms engine=${engineMs}ms $detail")
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
