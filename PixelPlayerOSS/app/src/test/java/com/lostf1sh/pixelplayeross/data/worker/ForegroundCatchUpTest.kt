package com.lostf1sh.pixelplayeross.data.worker

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B23-P1: the catch-up sync and the day check wait out the burst of a wake.
 *
 * On the DAP every wake is an activity start, and the burst of a wake (SCREEN_ON, the lock surface, the session) is when the
 * USB writer and the decoder need the CPU. 2026-10-14 15:30:20: `ARMED -> VISIBLE` 20.108, `REAP_SLOW … 175 ms` 20.292,
 * `SCREEN_ON` 20.916 — and, in the B19 log, the foreground catch-up scheduled 9 ms after the process came forward. Time is
 * virtual: these tests run on a fake clock, not on the phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundCatchUpTest {

    private class Rig(scope: TestScope, setupDone: Boolean = true) {
        val scheduledAt = mutableListOf<Long>()
        val log = mutableListOf<String>()
        var setupDone = setupDone
        val catchUp = ForegroundCatchUp(
            scope = scope,
            cooldownMs = 60_000L,
            now = { scope.currentTime },
            initialSetupDone = { this.setupDone },
            enqueueIncrementalSync = { scheduledAt += scope.currentTime },
            log = { log += it },
        )
    }

    @Test
    fun `nothing is scheduled in the burst of a wake, only after the settle time`() = runTest {
        val rig = Rig(this)
        // 15:30:20.0 the process comes forward; the lock surface is VISIBLE 108 ms later, SCREEN_ON 916 ms later.
        rig.catchUp.onForeground()
        runCurrent()
        assertThat(rig.scheduledAt).isEmpty()
        advanceTimeBy(108)
        assertThat(rig.scheduledAt).isEmpty()      // lock surface shown
        advanceTimeBy(808)
        assertThat(rig.scheduledAt).isEmpty()      // SCREEN_ON
        advanceTimeBy(FOREGROUND_SETTLE_MS - 1_000)
        runCurrent()
        assertThat(rig.scheduledAt).isEmpty()      // 7.99 s in: still quiet
        advanceTimeBy(100)
        runCurrent()
        assertThat(rig.scheduledAt).containsExactly(FOREGROUND_SETTLE_MS)
        assertThat(FOREGROUND_SETTLE_MS).isAtLeast(5_000L)
        assertThat(FOREGROUND_SETTLE_MS).isAtMost(10_000L)
    }

    @Test
    fun `a burst of foreground events shares one wait, restarted by the last, and schedules once`() = runTest {
        val rig = Rig(this)
        // start, resume, a quick screen toggle: three events inside two seconds
        rig.catchUp.onForeground()
        advanceTimeBy(300)
        rig.catchUp.onForeground()
        advanceTimeBy(1_700)
        rig.catchUp.onForeground()                       // t = 2.0 s
        advanceTimeBy(FOREGROUND_SETTLE_MS - 1)          // 1 ms short of 8 s after the LAST event
        runCurrent()
        assertThat(rig.scheduledAt).isEmpty()
        advanceTimeBy(100)
        runCurrent()
        // once, 8 s after the last event of the burst, not 8 s after the first
        assertThat(rig.scheduledAt).containsExactly(2_000L + FOREGROUND_SETTLE_MS)
    }

    @Test
    fun `a second wake just before the deadline does not find the sync firing 100 ms after it (the review's case)`() = runTest {
        val rig = Rig(this)
        rig.catchUp.onForeground()                       // first wake, t = 0: it would fire at 8,000
        advanceTimeBy(7_900)
        rig.catchUp.onForeground()                       // second wake, 100 ms before that
        advanceTimeBy(100)                               // t = 8,000: the first wake's deadline
        runCurrent()
        assertThat(rig.scheduledAt).isEmpty()
        advanceTimeBy(FOREGROUND_SETTLE_MS - 101)        // t = 15,899: 1 ms short of 8 s after the second wake
        runCurrent()
        assertThat(rig.scheduledAt).isEmpty()
        advanceTimeBy(10)
        runCurrent()
        assertThat(rig.scheduledAt).containsExactly(7_900L + FOREGROUND_SETTLE_MS)
    }

    @Test
    fun `a screen that keeps waking cannot push the sync back for ever`() = runTest {
        val rig = Rig(this)
        // a wake every 5 s for a minute: each one restarts the wait, up to the cap from the first
        for (n in 0 until 12) {
            rig.catchUp.onForeground()
            advanceTimeBy(5_000)
        }
        runCurrent()
        // t = 60,000 now: the cap (60 s after the first wake) is where it ran, not at 55,000 + 8,000
        assertThat(rig.scheduledAt).containsExactly(FOREGROUND_SETTLE_MAX_MS)
    }

    @Test
    fun `the gate keeps one action pending through a storm of wakes and runs the last one, once, by the cap`() = runTest {
        val gate = ForegroundSettleGate(this, now = { currentTime })
        val ran = mutableListOf<Pair<String, Long>>()
        // twelve wakes, one every 5 s, each with its own action; nothing may run before the cap
        for (n in 0 until 12) {
            gate.request { ran += "wake-$n" to currentTime }
            assertThat(gate.isPending).isTrue()
            advanceTimeBy(5_000)
            if (n < 11) assertThat(ran).isEmpty()
        }
        runCurrent()
        // only the latest request's action, once, at the cap (60 s after the first wake of the storm), and nothing is left waiting
        assertThat(ran).containsExactly("wake-11" to FOREGROUND_SETTLE_MAX_MS)
        assertThat(gate.isPending).isFalse()
        advanceTimeBy(FOREGROUND_SETTLE_MS * 4)
        assertThat(ran).hasSize(1)
    }

    @Test
    fun `the 60 s cooldown still holds, measured when the sync would run`() = runTest {
        val rig = Rig(this)
        rig.catchUp.onForeground()
        advanceTimeBy(FOREGROUND_SETTLE_MS + 1)
        assertThat(rig.scheduledAt).hasSize(1)
        // a second wake 30 s later: its sync would run at ~38 s, 30 s after the last one: skipped
        advanceTimeBy(22_000)
        rig.catchUp.onForeground()
        advanceTimeBy(FOREGROUND_SETTLE_MS + 1)
        assertThat(rig.scheduledAt).hasSize(1)
        assertThat(rig.log).contains("Skipping foreground catch-up sync (cooldown active)")
        // a wake a minute after the first sync: runs
        advanceTimeBy(40_000)
        rig.catchUp.onForeground()
        advanceTimeBy(FOREGROUND_SETTLE_MS + 1)
        assertThat(rig.scheduledAt).hasSize(2)
    }

    @Test
    fun `nothing is scheduled before the initial setup is done, and it is asked when the wait is over`() = runTest {
        val rig = Rig(this, setupDone = false)
        rig.catchUp.onForeground()
        advanceTimeBy(FOREGROUND_SETTLE_MS + 1)
        assertThat(rig.scheduledAt).isEmpty()
        assertThat(rig.log).contains("Skipping foreground catch-up sync: initial setup not finished")
        // finished meanwhile: the next foreground schedules
        rig.setupDone = true
        rig.catchUp.onForeground()
        advanceTimeBy(FOREGROUND_SETTLE_MS + 1)
        assertThat(rig.scheduledAt).hasSize(1)
    }

    @Test
    fun `the gate runs its action once per wait, and a cancelled wait runs nothing`() = runTest {
        val gate = ForegroundSettleGate(this, now = { currentTime })
        var runs = 0
        gate.request { runs++ }
        gate.request { runs++ }
        assertThat(gate.isPending).isTrue()
        advanceTimeBy(FOREGROUND_SETTLE_MS + 1)
        assertThat(runs).isEqualTo(1)
        assertThat(gate.isPending).isFalse()
        gate.request { runs++ }
        gate.cancel()
        advanceTimeBy(FOREGROUND_SETTLE_MS * 2)
        assertThat(runs).isEqualTo(1)
        // the latest request's action is the one that runs
        val order = mutableListOf<String>()
        gate.request { order += "first" }
        gate.request { order += "second" }
        advanceTimeBy(FOREGROUND_SETTLE_MS + 1)
        assertThat(order).containsExactly("second")
        // a request that arrives while the action is running starts a new wait
        gate.request { gate.request { order += "again" } }
        advanceTimeBy(FOREGROUND_SETTLE_MS + 1)
        assertThat(order).containsExactly("second")
        advanceTimeBy(FOREGROUND_SETTLE_MS)
        assertThat(order).containsExactly("second", "again").inOrder()
    }
}
