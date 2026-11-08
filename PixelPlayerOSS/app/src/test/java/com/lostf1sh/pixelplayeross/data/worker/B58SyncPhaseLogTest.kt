package com.lostf1sh.pixelplayeross.data.worker

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** B58 2 (#68): the per-phase timing lines the reviewer reads on the HONOR: their shape, their arithmetic, and that the closing line is written once. */
class B58SyncPhaseLogTest {
    private class Clock(var ms: Long = 0L) { fun ns(): Long = ms * 1_000_000L }

    @Test
    fun `each phase is the time since the previous line and the closing line adds them up`() {
        val clock = Clock()
        val lines = ArrayList<String>()
        val log = SyncPhaseLog("FULL", clock::ns, lines::add)
        clock.ms = 40; log.lap("setup")
        clock.ms = 140; log.lap("inventory", 4953)
        clock.ms = 1_140; log.lap("save", 4953)
        clock.ms = 1_200; log.finish(4953)
        assertThat(lines).containsExactly(
            "sync phase=setup ms=40 items=-",
            "sync phase=inventory ms=100 items=4953",
            "sync phase=save ms=1000 items=4953",
            "sync done mode=FULL songs=4953 ms=1200 phases=setup:40,inventory:100,save:1000",
        ).inOrder()
        assertThat(log.entries.sumOf { it.ms }).isAtMost(1_200L)
    }

    @Test
    fun `the closing line is written once however many exits reach it`() {
        val lines = ArrayList<String>()
        val log = SyncPhaseLog("INCREMENTAL", { 0L }, lines::add)
        log.finish(0)
        log.finish(0)
        assertThat(lines.count { it.startsWith("sync done") }).isEqualTo(1)
    }
}
