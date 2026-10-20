package com.lostf1sh.pixelplayeross.presentation.viewmodel

import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * When the daily mix belongs to a new local day (AUD-132). Days are counted in the zone in force
 * now, for both timestamps — the same idea of a day the mix repository caches by.
 */
object DailyMixSchedule {
    fun localEpochDay(nowMs: Long, zone: TimeZone): Long =
        TimeUnit.MILLISECONDS.toDays(nowMs + zone.getOffset(nowMs))

    fun isNewDay(lastUpdateMs: Long, nowMs: Long, zone: TimeZone): Boolean =
        lastUpdateMs <= 0L || localEpochDay(lastUpdateMs, zone) != localEpochDay(nowMs, zone)

    /** Wall time until the next local 00:00, DST-aware (a day can be 23 or 25 hours long). */
    fun msUntilNextLocalMidnight(nowMs: Long, zone: TimeZone): Long {
        val next = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (next.timeInMillis - nowMs).coerceAtLeast(1L)
    }
}
