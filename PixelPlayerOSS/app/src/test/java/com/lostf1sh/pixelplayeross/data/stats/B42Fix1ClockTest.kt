package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit.HOUR
import com.lostf1sh.pixelplayeross.data.stats.Kit.MIN
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B42-fix1, item 4: everything that is "now" follows the device's clock and zone while the app stays open. The owner: "the tops, the stats and
 * the mixes go with the clock. I'm just stressing it; I think we already have it."
 *
 * This file is the layer under the screens: how a period is resolved for an instant and a zone (it is a function of both, so it rolls with them),
 * and the thing that tells an open screen the clock moved ([clockChangesFlow]). What the Stats view model and the mix do with that is in
 * `B42Fix1StatsClockTest` and `AutoCollectionRepositoryTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class B42Fix1ClockTest {
    private val utc = ZoneId.of("UTC")
    private val kiritimati = ZoneId.of("Pacific/Kiritimati") // UTC+14
    private val honolulu = ZoneId.of("Pacific/Honolulu") // UTC-10

    private fun bounds(period: StatsPeriod, nowMs: Long, zone: ZoneId = utc) = period.bounds(nowMs, zone, firstEventMs = at(2026, 1, 1))

    private fun preset(range: StatsTimeRange) = StatsPeriod.Preset(range)

    // ── periods are a function of the instant and the zone ──────────────────────────────────────────

    @Test
    fun today_starts_again_when_local_midnight_passes() {
        val before = bounds(preset(StatsTimeRange.DAY), at(2026, 10, 15, 23, 59))
        val after = bounds(preset(StatsTimeRange.DAY), at(2026, 10, 16, 0, 1))
        assertThat(before.fromMs).isEqualTo(at(2026, 10, 15, 0))
        assertThat(after.fromMs).isEqualTo(at(2026, 10, 16, 0))
    }

    @Test
    fun the_week_starts_again_on_monday() {
        val sunday = bounds(preset(StatsTimeRange.WEEK), at(2026, 10, 18, 23, 59))
        val monday = bounds(preset(StatsTimeRange.WEEK), at(2026, 10, 19, 0, 1))
        assertThat(sunday.fromMs).isEqualTo(at(2026, 10, 12, 0))
        assertThat(monday.fromMs).isEqualTo(at(2026, 10, 19, 0))
    }

    @Test
    fun the_month_and_the_year_start_again_when_the_month_and_the_year_change() {
        assertThat(bounds(preset(StatsTimeRange.MONTH), at(2026, 10, 31, 23, 59)).fromMs).isEqualTo(at(2026, 10, 1, 0))
        assertThat(bounds(preset(StatsTimeRange.MONTH), at(2026, 11, 1, 0, 1)).fromMs).isEqualTo(at(2026, 11, 1, 0))
        assertThat(bounds(preset(StatsTimeRange.YEAR), at(2026, 12, 31, 23, 59)).fromMs).isEqualTo(at(2026, 1, 1, 0))
        assertThat(bounds(preset(StatsTimeRange.YEAR), at(2027, 1, 1, 0, 1)).fromMs).isEqualTo(at(2027, 1, 1, 0))
    }

    @Test
    fun a_month_range_stops_being_in_progress_when_its_last_month_ends() {
        val october = StatsPeriod.Months(YearMonth.of(2026, 10), YearMonth.of(2026, 10))
        assertThat(bounds(october, at(2026, 10, 31, 23, 59)).inProgress).isTrue()
        assertThat(bounds(october, at(2026, 11, 1, 0, 1)).inProgress).isFalse()
        val thisYear = StatsPeriod.Months.year(2026)
        assertThat(bounds(thisYear, at(2026, 12, 31, 23, 59)).inProgress).isTrue()
        assertThat(bounds(thisYear, at(2027, 1, 1, 0, 1)).inProgress).isFalse()
    }

    @Test
    fun the_same_instant_is_another_day_in_another_zone() {
        val instant = at(2026, 10, 15, 20) // 20:00 UTC
        val inUtc = bounds(preset(StatsTimeRange.DAY), instant, utc)
        val inKiritimati = bounds(preset(StatsTimeRange.DAY), instant, kiritimati) // 10:00 on the 16th there
        val inHonolulu = bounds(preset(StatsTimeRange.DAY), instant, honolulu) // 10:00 on the 15th there
        assertThat(inUtc.fromMs).isEqualTo(at(2026, 10, 15, 0))
        assertThat(inKiritimati.fromMs).isEqualTo(at(2026, 10, 15, 10)) // 00:00 of the 16th in +14 is 10:00 UTC on the 15th
        assertThat(inHonolulu.fromMs).isEqualTo(at(2026, 10, 15, 10)) // 00:00 of the 15th in -10 is 10:00 UTC on the 15th
        // and the month can differ: 23:00 UTC on the 31st is already November in Kiritimati
        val lateOnTheLast = at(2026, 10, 31, 23)
        assertThat(bounds(preset(StatsTimeRange.MONTH), lateOnTheLast, utc).fromMs).isEqualTo(at(2026, 10, 1, 0))
        assertThat(bounds(preset(StatsTimeRange.MONTH), lateOnTheLast, kiritimati).fromMs).isEqualTo(at(2026, 10, 31, 10))
    }

    // ── what tells an open screen the clock moved ───────────────────────────────────────────────────

    /** The flow on virtual time: the clock is the start plus the test scheduler's time, the zone is whatever the test last set. */
    private class Rig(val startMs: Long, var zone: TimeZone) {
        val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    }

    @Test
    fun it_emits_at_each_local_midnight_and_not_before() = runTest {
        val rig = Rig(at(2026, 10, 15, 20), TimeZone.getTimeZone("UTC"))
        var emitted = 0
        backgroundScope.launch {
            clockChangesFlow({ rig.startMs + testScheduler.currentTime }, { rig.zone }, rig.signals).collect { emitted++ }
        }
        runCurrent()
        advanceTimeBy(4 * HOUR - MIN) // 23:59
        assertThat(emitted).isEqualTo(0)
        advanceTimeBy(MIN + 2_000L) // past the margin after 00:00
        assertThat(emitted).isEqualTo(1)
        advanceTimeBy(24 * HOUR)
        assertThat(emitted).isEqualTo(2)
    }

    @Test
    fun a_zone_change_emits_at_once_and_moves_the_next_midnight() = runTest {
        val rig = Rig(at(2026, 10, 15, 20), TimeZone.getTimeZone("UTC"))
        var emitted = 0
        backgroundScope.launch {
            clockChangesFlow({ rig.startMs + testScheduler.currentTime }, { rig.zone }, rig.signals).collect { emitted++ }
        }
        runCurrent()
        // the phone is put on Kiritimati time: it is 10:00 on the 16th there, so its midnight is 14 hours away, not 4
        rig.zone = TimeZone.getTimeZone("Pacific/Kiritimati")
        rig.signals.emit(Unit)
        runCurrent()
        assertThat(emitted).isEqualTo(1)
        advanceTimeBy(4 * HOUR + 5_000L) // the old zone's midnight: nothing
        assertThat(emitted).isEqualTo(1)
        advanceTimeBy(10 * HOUR) // the new one's
        assertThat(emitted).isEqualTo(2)
    }

    @Test
    fun the_clock_set_by_hand_emits_and_rearms_from_the_new_time() = runTest {
        // the date is changed in Settings from 20:00 to 23:30 the same day: the midnight is half an hour away now
        var offset = 0L
        val rig = Rig(at(2026, 10, 15, 20), TimeZone.getTimeZone("UTC"))
        var emitted = 0
        backgroundScope.launch {
            clockChangesFlow({ rig.startMs + testScheduler.currentTime + offset }, { rig.zone }, rig.signals).collect { emitted++ }
        }
        runCurrent()
        offset = 3 * HOUR + 30 * MIN
        rig.signals.emit(Unit)
        runCurrent()
        assertThat(emitted).isEqualTo(1)
        advanceTimeBy(30 * MIN + 2_000L)
        assertThat(emitted).isEqualTo(2)
    }

    @Test
    fun nothing_is_left_running_when_nobody_collects() = runTest {
        val rig = Rig(at(2026, 10, 15, 20), TimeZone.getTimeZone("UTC"))
        var reads = 0
        var emitted = 0
        val job = backgroundScope.launch {
            clockChangesFlow({ reads++; rig.startMs + testScheduler.currentTime }, { rig.zone }, rig.signals).collect { emitted++ }
        }
        runCurrent()
        job.cancel()
        runCurrent()
        val readsWhenStopped = reads
        // two days of midnights go by with nobody listening: the timer is gone (it would read the clock again at each one), and so is the
        // part that takes the signals (a signal now reaches nobody)
        advanceTimeBy(48 * HOUR)
        rig.signals.tryEmit(Unit)
        runCurrent()
        assertThat(reads).isEqualTo(readsWhenStopped)
        assertThat(emitted).isEqualTo(0)
    }
}
