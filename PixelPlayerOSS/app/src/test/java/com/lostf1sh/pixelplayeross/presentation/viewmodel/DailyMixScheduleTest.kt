package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.jupiter.api.Test

/**
 * AUD-132: the Daily Mix did not change at 00:00. The day check only ran when MainActivity started,
 * and in Dedicated Mode the app is the HOME app under Lock Task: it can stay open for days. The mix
 * now also renews at the next local midnight while the app runs, on a date/time/zone change, and on
 * the first use of the day — all decided by these rules.
 */
class DailyMixScheduleTest {
    private val madrid = TimeZone.getTimeZone("Europe/Madrid")
    private val bogota = TimeZone.getTimeZone("America/Bogota")

    private fun at(zone: TimeZone, y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, m - 1, d, h, min, 0) }.timeInMillis

    @Test
    fun `crossing local midnight is a new day, minutes before it is not`() {
        val lastUpdate = at(madrid, 2026, 9, 28, 23, 30)
        assertThat(DailyMixSchedule.isNewDay(lastUpdate, at(madrid, 2026, 9, 28, 23, 59), madrid)).isFalse()
        assertThat(DailyMixSchedule.isNewDay(lastUpdate, at(madrid, 2026, 9, 29, 0, 1), madrid)).isTrue()
    }

    @Test
    fun `the wake-up lands on the next local midnight, also across a DST change`() {
        val now = at(madrid, 2026, 9, 28, 22, 0)
        assertThat(DailyMixSchedule.msUntilNextLocalMidnight(now, madrid)).isEqualTo(2 * 3_600_000L)
        // 25 Oct 2026: clocks go back at 03:00 in Madrid — the day is 25 h long.
        val beforeFallBack = at(madrid, 2026, 10, 25, 0, 30)
        val next = beforeFallBack + DailyMixSchedule.msUntilNextLocalMidnight(beforeFallBack, madrid)
        assertThat(next).isEqualTo(at(madrid, 2026, 10, 26, 0, 0))
    }

    @Test
    fun `a time zone change is judged in the zone now in force`() {
        // Last mix made at 10:00 in Madrid (08:00 UTC). The phone moves to Bogota (UTC-5): at 23:00
        // there it is the same local day as 03:00 of that day; the next Bogota midnight is a new day.
        val lastUpdate = at(madrid, 2026, 9, 28, 10, 0)
        assertThat(DailyMixSchedule.isNewDay(lastUpdate, at(bogota, 2026, 9, 28, 23, 0), bogota)).isFalse()
        assertThat(DailyMixSchedule.isNewDay(lastUpdate, at(bogota, 2026, 9, 29, 0, 5), bogota)).isTrue()
        assertThat(DailyMixSchedule.localEpochDay(at(bogota, 2026, 9, 29, 0, 5), bogota))
            .isEqualTo(DailyMixSchedule.localEpochDay(at(madrid, 2026, 9, 29, 12, 0), madrid))
    }

    @Test
    fun `never updated counts as a new day`() {
        assertThat(DailyMixSchedule.isNewDay(0L, at(madrid, 2026, 9, 28, 12, 0), madrid)).isTrue()
    }
}
