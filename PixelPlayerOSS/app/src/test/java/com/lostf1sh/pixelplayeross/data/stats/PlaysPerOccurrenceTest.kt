package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit.MIN
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.Kit.event
import com.lostf1sh.pixelplayeross.data.stats.Kit.song
import org.junit.jupiter.api.Test

/**
 * K-01 and D2: one definition of a play, judged per occurrence. Four independent skips are no play; the fragments of one listen are;
 * a loop gives a play per qualifying lap; an event from before occurrences were recorded is judged alone unless it is the cut tail of
 * a short one.
 */
class PlaysPerOccurrenceTest {
    private val t0 = at(2026, 9, 1)
    private val catalog = Kit.catalog(listOf(song(1, durationMs = 4 * MIN), song(2, durationMs = 4 * MIN)))

    private fun plays(events: List<PlaybackStatsRepository.PlaybackEvent>) = Kit.ledger(events, catalog).stats("item-1")?.plays ?: 0

    @Test
    fun `four independent thirty second skips of a four minute song are no play`() {
        val events = (0 until 4).map { i ->
            event(1, t0 + i * 2 * MIN, 30_000, identity = "item-1", occurrence = "skip$i", reason = TrackEndReason.USER_NEXT, itemMs = 4 * MIN)
        }
        assertThat(plays(events)).isEqualTo(0)
        assertThat(Kit.ledger(events, catalog).stats("item-1")?.skips).isEqualTo(4)
    }

    @Test
    fun `two fragments of one occurrence add up to a play`() {
        val events = listOf(
            event(1, t0 + 2 * MIN, 70_000, identity = "item-1", occurrence = "o1", reason = TrackEndReason.INTERRUPTED, itemMs = 4 * MIN),
            event(1, t0 + 10 * MIN, 70_000, identity = "item-1", occurrence = "o1", reason = TrackEndReason.USER_NEXT, itemMs = 4 * MIN),
        )
        assertThat(plays(events)).isEqualTo(1)
        // One listen, so not a skip either, whatever its last fragment's reason says.
        assertThat(Kit.ledger(events, catalog).stats("item-1")?.skips).isEqualTo(0)
    }

    @Test
    fun `repeat one gives a play for every lap that qualifies`() {
        val events = (0 until 3).map { lap ->
            event(1, t0 + (lap + 1) * 4 * MIN, 4 * MIN, identity = "item-1", occurrence = "lap$lap", outcome = PlaybackOutcome.COMPLETED, reason = TrackEndReason.NATURAL_END, itemMs = 4 * MIN)
        }
        val stats = Kit.ledger(events, catalog).stats("item-1")!!
        assertThat(stats.plays).isEqualTo(3)
        assertThat(stats.completions).isEqualTo(3)
    }

    @Test
    fun `half of the track or four minutes is the bar, the shorter of the two`() {
        val short = Kit.catalog(listOf(song(1, durationMs = 90_000), song(2, durationMs = 12 * MIN)))
        fun playsOf(id: Long, listened: Long) = Kit.ledger(
            listOf(event(id, t0 + MIN, listened, identity = "item-$id", occurrence = "o$id", itemMs = if (id == 1L) 90_000 else 12 * MIN)),
            short,
        ).stats("item-$id")?.plays ?: 0
        assertThat(playsOf(1, 44_000)).isEqualTo(0)
        assertThat(playsOf(1, 45_000)).isEqualTo(1)
        assertThat(playsOf(2, 3 * MIN + 59_000)).isEqualTo(0)
        assertThat(playsOf(2, 4 * MIN)).isEqualTo(1)
    }

    @Test
    fun `an event from before occurrences stands alone`() {
        val events = (0 until 4).map { i -> event(1, t0 + i * 10 * MIN, 30_000, identity = null) }
        assertThat(plays(events)).isEqualTo(0)
    }

    @Test
    fun `a legacy event that continues a short one within three seconds is one listen`() {
        val first = event(1, t0 + 2 * MIN, 90_000, identity = null)
        val cut = event(1, t0 + 2 * MIN + 3_000 + 90_000, 90_000, identity = null) // begins 3 s after the first ended
        assertThat(plays(listOf(first, cut))).isEqualTo(1)
    }

    @Test
    fun `a legacy event after a gap of more than three seconds is a listen of its own`() {
        val first = event(1, t0 + 2 * MIN, 90_000, identity = null)
        val later = event(1, t0 + 2 * MIN + 4_000 + 90_000, 90_000, identity = null)
        assertThat(plays(listOf(first, later))).isEqualTo(0)
    }

    @Test
    fun `a legacy loop that already played in full does not swallow the next lap`() {
        val lap1 = event(1, t0 + 4 * MIN, 4 * MIN, identity = null)
        val lap2 = event(1, t0 + 8 * MIN, 4 * MIN, identity = null)
        assertThat(plays(listOf(lap1, lap2))).isEqualTo(2)
    }

    @Test
    fun `a short legacy listen is not a skip because nobody recorded how it ended`() {
        val ledger = Kit.ledger(listOf(event(1, t0 + MIN, 30_000, identity = null)), catalog)
        assertThat(ledger.occurrences.single().isSkip).isFalse()
        assertThat(ledger.occurrences.single().isRecorded).isFalse()
    }

    @Test
    fun `a replay is the listener's action but not a skip`() {
        val e = event(1, t0 + MIN, 20_000, identity = "item-1", occurrence = "o", reason = TrackEndReason.USER_REPLAY, itemMs = 4 * MIN)
        val occurrence = Kit.ledger(listOf(e), catalog).occurrences.single()
        assertThat(occurrence.isRecorded).isTrue()
        assertThat(occurrence.isSkip).isFalse()
    }

    @Test
    fun `a play belongs to the range its occurrence ended in and time is clipped to the range`() {
        val dayEnd = at(2026, 9, 1, 0, 1)
        // Listened from 23:58 to 00:01 (3 min) across midnight: one play, in the day that it ended in; time in both days.
        val ledger = Kit.ledger(
            listOf(event(1, dayEnd, 3 * MIN, identity = "item-1", occurrence = "o", reason = TrackEndReason.USER_NEXT, itemMs = 4 * MIN)),
            catalog,
        )
        val now = at(2026, 9, 2, 12)
        val aug31 = StatsPeriod.Months(java.time.YearMonth.of(2026, 8), java.time.YearMonth.of(2026, 8))
        val sep = StatsPeriod.Months(java.time.YearMonth.of(2026, 9), java.time.YearMonth.of(2026, 9))
        val a = StatsEngine.report(ledger, aug31, now, Kit.zone)
        val s = StatsEngine.report(ledger, sep, now, Kit.zone)
        assertThat(a.totals.plays).isEqualTo(0)
        assertThat(a.totals.listenedMs).isEqualTo(2 * MIN)
        assertThat(s.totals.plays).isEqualTo(1)
        assertThat(s.totals.listenedMs).isEqualTo(MIN)
    }
}
