package com.lostf1sh.pixelplayeross.data.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-035: every route decision read the whole trace file and rewrote it, synchronously, on the
 * main thread (18 call sites, up to 1500 lines each time). Recording is now an in-memory append; the
 * file is written on a background executor, and a burst of records costs one write.
 */
class RouteTraceLogTest {
    private class QueuedExecutor : java.util.concurrent.Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test
    fun `recording does no I O on the caller's thread`() {
        val executor = QueuedExecutor()
        val writes = mutableListOf<List<String>>()
        val log = RouteTraceLog(maxLines = 3, executor = executor, clock = { 7L }) { writes += it }
        log.record("DECIDE", "a")
        assertThat(writes).isEmpty()
        executor.runAll()
        assertThat(writes).containsExactly(listOf("7 DECIDE a"))
    }

    @Test
    fun `a burst coalesces into one write of the last lines`() {
        val executor = QueuedExecutor()
        val writes = mutableListOf<List<String>>()
        var now = 0L
        val log = RouteTraceLog(maxLines = 3, executor = executor, clock = { now++ }) { writes += it }
        repeat(10) { log.record("T", "$it") }
        assertThat(executor.tasks).hasSize(1)
        executor.runAll()
        assertThat(writes).hasSize(1)
        assertThat(writes.single()).containsExactly("7 T 7", "8 T 8", "9 T 9").inOrder()
        log.record("T", "next")
        executor.runAll()
        assertThat(writes).hasSize(2)
    }
}
