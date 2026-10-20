package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit.DAY
import com.lostf1sh.pixelplayeross.data.stats.Kit.MIN
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository.PlaybackEvent
import java.time.YearMonth
import kotlin.random.Random
import org.junit.jupiter.api.Test

/**
 * Reading a range of the history when events lie inside other events (R28-03).
 *
 * The ledger keeps its events in the order they began, and an event that lies inside an earlier one ends before it, so the ends
 * are not in order. A range is found by searching those ends; this checks that the time of the event around is not lost, that no
 * instant is counted twice, and that the ranges of a history add up to the history. Nothing here is a listener's data.
 */
class LedgerRangeReadingTest {
    private val zone = Kit.zone

    private fun lib(n: Int) = Kit.catalog((1..n).map { Kit.song(it.toLong()) }, firstSeen = (1..n).associate { "item-$it" to at(2026, 1, 1) })

    /** An interval of listening of song [id], from [startMs] for [lengthMs]; every interval is its own occurrence. */
    private fun span(id: Long, startMs: Long, lengthMs: Long) =
        Kit.event(id, endMs = startMs + lengthMs, listenedMs = lengthMs, identity = "item-$id", occurrence = "o$id-$startMs")

    private fun report(ledger: ListeningLedger, period: StatsPeriod, nowMs: Long) =
        StatsEngine.report(ledger, period, nowMs, zone)

    /** The listening time of the local day [day] (the preset "today" read at the last instant of it). */
    private fun dayMs(ledger: ListeningLedger, year: Int, month: Int, day: Int): Long =
        report(ledger, StatsPeriod.Preset(StatsTimeRange.DAY), at(year, month, day, 23, 59) + 59_999L).totals.listenedMs

    /** The time covered by [events], each instant once, found without the ledger: sort, merge, add up. */
    private fun unionMs(events: List<PlaybackEvent>, fromMs: Long = Long.MIN_VALUE, toMs: Long = Long.MAX_VALUE): Long {
        var total = 0L
        var coveredTo = Long.MIN_VALUE
        for (e in events.sortedBy { it.startMillis() }) {
            val start = maxOf(e.startMillis(), fromMs, coveredTo)
            val end = minOf(e.endMillis(), toMs)
            if (end > start) total += end - start
            coveredTo = maxOf(coveredTo, e.endMillis())
        }
        return total
    }

    // --- the case that was lost ---------------------------------------------------------------------------------

    @Test
    fun `an interval inside another one does not hide the rest of it from the next day`() {
        // A 23:55 to 00:05, B 23:56 to 23:57 and C 23:58 to 23:59 inside it, three different tracks.
        val midnight = at(2026, 10, 15, 0, 0)
        val events = listOf(
            span(1, midnight - 5 * MIN, 10 * MIN),
            span(2, midnight - 4 * MIN, 1 * MIN),
            span(3, midnight - 2 * MIN, 1 * MIN),
        )
        val ledger = Kit.ledger(events, lib(3))

        assertThat(dayMs(ledger, 2026, 10, 15)).isEqualTo(5 * MIN)
        assertThat(dayMs(ledger, 2026, 10, 14)).isEqualTo(5 * MIN)
        // The whole history is the ten minutes of A: B and C add nothing to a time that A already had.
        val all = report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), at(2026, 10, 15, 12))
        assertThat(all.totals.listenedMs).isEqualTo(10 * MIN)
        assertThat(all.coverage.totalMs).isEqualTo(10 * MIN)
    }

    @Test
    fun `the events inside another are still listens of their own`() {
        val midnight = at(2026, 10, 15, 0, 0)
        val ledger = Kit.ledger(
            listOf(
                span(1, midnight - 5 * MIN, 10 * MIN),
                span(2, midnight - 4 * MIN, 1 * MIN),
                span(3, midnight - 2 * MIN, 1 * MIN),
            ),
            lib(3),
        )

        // Each is an occurrence with its own start, its own track and the time it was listened to, which the plays and the skips are judged from.
        assertThat(ledger.occurrences).hasSize(3)
        assertThat(ledger.occurrences.map { it.listenedMs }).containsExactly(10 * MIN, 1 * MIN, 1 * MIN)
        assertThat(ledger.stats("item-2")!!.starts).isEqualTo(1)
        assertThat(ledger.stats("item-3")!!.starts).isEqualTo(1)
        assertThat(ledger.fragments).hasSize(3)
    }

    @Test
    fun `a partial overlap is counted once and the later event keeps the part after the earlier one`() {
        val ten = at(2026, 10, 15, 10, 0)
        val events = listOf(span(1, ten, 20 * MIN), span(2, ten + 10 * MIN, 20 * MIN))
        val ledger = Kit.ledger(events, lib(2))

        val all = report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), at(2026, 10, 15, 12))
        assertThat(all.totals.listenedMs).isEqualTo(30 * MIN)
        val songs = all.rankings.songs(StatsRankMode.TIME).associate { it.item.identity to it.timeMs }
        assertThat(songs).containsExactly("item-1", 20 * MIN, "item-2", 10 * MIN)
    }

    @Test
    fun `events with the same limits count once and events that touch add up`() {
        val ten = at(2026, 10, 15, 10, 0)
        val same = Kit.ledger(listOf(span(1, ten, 10 * MIN), span(2, ten, 10 * MIN)), lib(2))
        assertThat(report(same, StatsPeriod.Preset(StatsTimeRange.ALL), at(2026, 10, 15, 12)).totals.listenedMs).isEqualTo(10 * MIN)

        val touching = Kit.ledger(listOf(span(1, ten, 10 * MIN), span(2, ten + 10 * MIN, 10 * MIN)), lib(2))
        assertThat(report(touching, StatsPeriod.Preset(StatsTimeRange.ALL), at(2026, 10, 15, 12)).totals.listenedMs).isEqualTo(20 * MIN)

        // One inside the other with the same end.
        val sameEnd = Kit.ledger(listOf(span(1, ten, 10 * MIN), span(2, ten + 6 * MIN, 4 * MIN)), lib(2))
        assertThat(report(sameEnd, StatsPeriod.Preset(StatsTimeRange.ALL), at(2026, 10, 15, 12)).totals.listenedMs).isEqualTo(10 * MIN)
    }

    // --- the ranges of a history add up to the history ---------------------------------------------------------------

    @Test
    fun `a contained interval across the end of a month keeps its time in the month it falls in`() {
        val first = at(2026, 11, 1, 0, 0)
        val events = listOf(
            span(1, first - 30 * MIN, 90 * MIN), // 23:30 on 31 October to 01:00
            span(2, first - 20 * MIN, 5 * MIN), // inside it, before midnight
            span(3, first + 10 * MIN, 5 * MIN), // inside it, after midnight
        )
        val ledger = Kit.ledger(events, lib(3))
        val now = at(2026, 12, 10)

        val october = report(ledger, StatsPeriod.Months(YearMonth.of(2026, 10), YearMonth.of(2026, 10)), now).totals.listenedMs
        val november = report(ledger, StatsPeriod.Months(YearMonth.of(2026, 11), YearMonth.of(2026, 11)), now).totals.listenedMs
        val whole = report(ledger, StatsPeriod.Months(YearMonth.of(2026, 10), YearMonth.of(2026, 11)), now).totals.listenedMs

        assertThat(october).isEqualTo(30 * MIN)
        assertThat(november).isEqualTo(60 * MIN)
        assertThat(october + november).isEqualTo(whole)
        assertThat(whole).isEqualTo(unionMs(events))
    }

    @Test
    fun `the days of a messy history add up to the history and to the time found without the ledger`() {
        val random = Random(28)
        val start = at(2026, 10, 12, 0, 0)
        val events = ArrayList<PlaybackEvent>()
        // Four days of listens that begin anywhere, last from seconds to hours, and are often inside, across or equal to another one.
        repeat(160) { index ->
            val begin = start + random.nextLong(0, 3 * DAY + 12 * 60 * MIN)
            val length = when (random.nextInt(4)) {
                0 -> random.nextLong(10_000, 90_000)
                1 -> random.nextLong(MIN, 12 * MIN)
                2 -> random.nextLong(10 * MIN, 3 * 60 * MIN)
                else -> 4 * MIN
            }
            events += span((index % 9 + 1).toLong(), begin, length)
        }
        val ledger = Kit.ledger(events, lib(9))

        val perDay = (12..15).map { day -> dayMs(ledger, 2026, 10, day) }
        val all = report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), at(2026, 10, 15, 23, 59) + 59_999L)

        assertThat(perDay.sum()).isEqualTo(all.totals.listenedMs)
        assertThat(all.totals.listenedMs).isEqualTo(unionMs(events))
        (12..15).forEachIndexed { index, day ->
            val from = at(2026, 10, day, 0, 0)
            assertThat(perDay[index]).isEqualTo(unionMs(events, from, from + DAY))
        }
        // The listening clock is the same time cut by the hour, and the track rows are the same time cut by the track.
        assertThat(all.habits!!.clock.timeMs.sum()).isEqualTo(all.totals.listenedMs)
        assertThat(all.rankings.songs(StatsRankMode.TIME).sumOf { it.timeMs }).isEqualTo(all.totals.listenedMs)
        assertThat(all.coverage.totalMs).isEqualTo(all.totals.listenedMs)
    }
}
