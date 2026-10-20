package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.worker.FOREGROUND_SETTLE_MS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B23-P1: the day check (AUD-132) asked from the activity's `onStart` and `onResume`.
 *
 * The process's first start asks at once, as before. Every later start and resume (a wake on the DAP) waits out the burst and asks
 * once; a new day's mixes are a few seconds late on the first wake of the day and nothing else changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityDayCheckTest {

    @Test
    fun `the first start of the process asks at once and its resume does not ask again`() = runTest {
        val asked = mutableListOf<Long>()
        val check = ActivityDayCheck(this, now = { currentTime }) { asked += currentTime }
        check.onActivityStart()
        assertThat(asked).containsExactly(0L)
        check.onActivityResume()
        advanceTimeBy(FOREGROUND_SETTLE_MS * 2)
        assertThat(asked).containsExactly(0L)
    }

    @Test
    fun `a wake asks once, after the settle time, however many starts and resumes it brings`() = runTest {
        val asked = mutableListOf<Long>()
        val check = ActivityDayCheck(this, now = { currentTime }) { asked += currentTime }
        check.onActivityStart()
        check.onActivityResume()
        advanceTimeBy(60_000)
        asked.clear()
        // the screen comes on: start, resume, then a toggle that adds a second start and resume
        check.onActivityStart()
        check.onActivityResume()
        advanceTimeBy(900)
        check.onActivityStart()
        check.onActivityResume()
        advanceTimeBy(FOREGROUND_SETTLE_MS - 1)
        assertThat(asked).isEmpty()                      // 7.99 s after the LAST event of the wake: not asked in the burst
        advanceTimeBy(20)
        assertThat(asked).containsExactly(60_000L + 900L + FOREGROUND_SETTLE_MS)
    }

    @Test
    fun `a second wake just before the deadline is not asked 100 ms after it either`() = runTest {
        val asked = mutableListOf<Long>()
        val check = ActivityDayCheck(this, now = { currentTime }) { asked += currentTime }
        check.onActivityStart()
        check.onActivityResume()
        advanceTimeBy(60_000)
        asked.clear()
        check.onActivityStart()                          // first wake, t = 60,000
        advanceTimeBy(7_900)
        check.onActivityStart()                          // second wake, 100 ms before its deadline
        advanceTimeBy(1_000)
        assertThat(asked).isEmpty()
        advanceTimeBy(FOREGROUND_SETTLE_MS)
        assertThat(asked).containsExactly(60_000L + 7_900L + FOREGROUND_SETTLE_MS)
    }

    @Test
    fun `a resume with nothing before it still waits`() = runTest {
        val asked = mutableListOf<Long>()
        val check = ActivityDayCheck(this, now = { currentTime }) { asked += currentTime }
        check.onActivityStart()
        check.onActivityResume()
        advanceTimeBy(10)
        check.onActivityResume()          // a Lock Task resume without a start (the HOME app resumes far more than it starts)
        advanceTimeBy(FOREGROUND_SETTLE_MS - 1)
        assertThat(asked).containsExactly(0L)
        advanceTimeBy(20)
        assertThat(asked).hasSize(2)
    }
}
