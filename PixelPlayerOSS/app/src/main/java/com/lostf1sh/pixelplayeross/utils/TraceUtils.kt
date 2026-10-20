package com.lostf1sh.pixelplayeross.utils

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

private val traceCookieGenerator = AtomicInteger()

/**
 * Runs [block] inside a synchronous trace section, guaranteeing the section is closed even
 * when [block] throws or returns non-locally. Only for blocks that cannot suspend — use
 * [traceAsyncSection] in suspend functions.
 */
inline fun <T> traceSection(label: String, block: () -> T): T {
    Trace.beginSection(label)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

/**
 * [traceSection] that also says so in the log when [block] kept the main thread longer than [thresholdMs] (B18-R3).
 *
 * A song change used to cost ~0.7 s of one frame on the HONOR, and the log could say *that* it did but not *what* did it. Each
 * handler that runs on the main thread when a song changes is wrapped in this, so the Perfetto trace carries the section name and
 * the log carries `SongChange: <label> took N ms`, with nothing printed for a section that is cheap. Not for suspend code.
 */
inline fun <T> timedSection(label: String, thresholdMs: Long = 8L, block: () -> T): T {
    val startedAt = android.os.SystemClock.elapsedRealtime()
    Trace.beginSection(label)
    try {
        return block()
    } finally {
        Trace.endSection()
        val tookMs = android.os.SystemClock.elapsedRealtime() - startedAt
        if (tookMs >= thresholdMs) timber.log.Timber.tag("SongChange").i("%s took %d ms on the main thread", label, tookMs)
    }
}

/**
 * Runs [block] inside an async trace section.
 *
 * `Trace.beginSection`/`endSection` are thread-local, so a section held open across a
 * suspension point can resume on a different thread and unbalance the caller's trace
 * stack. Async sections are matched by (label, cookie) instead and survive thread hops,
 * making them the only safe form for suspend functions.
 */
suspend fun <T> traceAsyncSection(label: String, block: suspend () -> T): T {
    val cookie = traceCookieGenerator.incrementAndGet()
    Trace.beginAsyncSection(label, cookie)
    try {
        return block()
    } finally {
        Trace.endAsyncSection(label, cookie)
    }
}
