package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.PositionTraceLog
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-139 (a): the persistent ring of engine seeks and anchor/offset changes. Like the route trace
 * (AUD-035): an in-memory append on the caller's thread, the file written on its own executor, the
 * previous session's lines kept ahead of this one's.
 */
class PositionTraceLogTest {
    private class QueuedExecutor : java.util.concurrent.Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test
    fun `a record names the event, its origin and both positions, and costs no I O on the caller`() {
        val executor = QueuedExecutor()
        val writes = mutableListOf<List<String>>()
        val log = PositionTraceLog(maxLines = 4, executor = executor, clock = { "19:57:33.446" }) { writes += it }
        log.record("ENGINE_SEEK", "bootstrap", media3Ms = 30_612L, engineMs = 30_598L, "target=60650ms gen=21")
        assertThat(writes).isEmpty()
        executor.runAll()
        assertThat(writes.single()).containsExactly(
            "19:57:33.446 ENGINE_SEEK origin=bootstrap media3=30612ms engine=30598ms target=60650ms gen=21"
        )
    }

    @Test
    fun `the ring is bounded and a burst is one write`() {
        val executor = QueuedExecutor()
        val writes = mutableListOf<List<String>>()
        var t = 0
        val log = PositionTraceLog(maxLines = 2, executor = executor, clock = { "t${t++}" }) { writes += it }
        repeat(5) { log.record("OFFSET", "latch", -1L, -1L, "n=$it") }
        assertThat(executor.tasks).hasSize(1)
        executor.runAll()
        assertThat(writes.single()).containsExactly(
            "t3 OFFSET origin=latch media3=-1ms engine=-1ms n=3",
            "t4 OFFSET origin=latch media3=-1ms engine=-1ms n=4",
        ).inOrder()
    }
}
