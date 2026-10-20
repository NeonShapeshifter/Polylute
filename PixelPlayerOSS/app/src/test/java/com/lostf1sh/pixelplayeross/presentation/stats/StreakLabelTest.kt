package com.lostf1sh.pixelplayeross.presentation.stats

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.StatsEngine
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import org.junit.jupiter.api.Test

/**
 * The streak pair on the Stats screen (B28-fix2 E3): "Current" runs back over the whole history to today and the longest is counted
 * inside the range, so in a short range the longest can be shorter than the current one. It must then say it is the range's.
 */
class StreakLabelTest {
    private val now = Kit.at(2026, 10, 15, 20)

    /** A play every day for 27 days, the last today. */
    private val ledger = ListeningLedger.build(
        (0 until 27).map { Kit.play(1, now - it * Kit.DAY, occurrence = "d$it") },
        Kit.catalog(listOf(Kit.song(1))),
    )

    @Test
    fun `every range but all time calls the longest streak the range's own`() {
        listOf(StatsTimeRange.DAY, StatsTimeRange.WEEK, StatsTimeRange.MONTH, StatsTimeRange.YEAR).forEach { range ->
            assertThat(streakLongestLabel(StatsPeriod.Preset(range))).isEqualTo(R.string.stats_streak_longest_in_period)
        }
        assertThat(streakLongestLabel(StatsPeriod.Preset(StatsTimeRange.ALL))).isEqualTo(R.string.stats_streak_longest)
    }

    @Test
    fun `whenever the current streak is longer than the longest of its range the label names the range`() {
        var contradictions = 0
        StatsTimeRange.entries.forEach { range ->
            val period = StatsPeriod.Preset(range)
            val streaks = StatsEngine.report(ledger, period, now, Kit.zone).habits!!.streaks
            val current = streaks.current ?: 0
            if (current > streaks.longest) {
                contradictions++
                assertThat(streakLongestLabel(period)).isEqualTo(R.string.stats_streak_longest_in_period)
            }
        }
        // The week is one of them (27 days against the 4 of Monday to Thursday), so the check above ran.
        assertThat(contradictions).isAtLeast(1)
        // All time never contradicts itself: the longest is never shorter than the current one.
        val all = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), now, Kit.zone).habits!!.streaks
        assertThat(all.longest).isAtLeast(all.current ?: 0)
        assertThat(all.longest).isEqualTo(27)
    }

    @Test
    fun `the numbers themselves are the ones the engine counts, the week's four days and the whole history's twenty seven`() {
        val week = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.WEEK), now, Kit.zone).habits!!.streaks

        assertThat(week.current).isEqualTo(27)
        assertThat(week.longest).isEqualTo(4)
    }
}
