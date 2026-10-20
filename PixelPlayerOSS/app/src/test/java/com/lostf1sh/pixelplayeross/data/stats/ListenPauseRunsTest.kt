package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit.HOUR
import com.lostf1sh.pixelplayeross.data.stats.Kit.MIN
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ListeningStatsTracker
import org.junit.jupiter.api.Test

/**
 * A listen that is paused for a long time (R28-02), from the real tracker to the file the real repository writes, to the ledger and
 * to Habits. The tracker's wall clock is under the test's hand; the song is the same occurrence across the pause, and the hours,
 * the sessions and the total are those of the time it was heard. Nothing here is a listener's data.
 */
class ListenPauseRunsTest {
    private val zone = Kit.zone
    private val shortRun = ListeningStatsTracker.MAX_PAUSE_IN_RUN_MS

    private fun catalog() = Kit.catalog(listOf(Kit.song(1, identity = "item-1")), firstSeen = mapOf("item-1" to at(2026, 1, 1)))

    private fun ledgerOf(h: ListenHarness) = ListeningLedger.build(h.events(), catalog())

    private fun reportAt(h: ListenHarness, nowMs: Long) =
        StatsEngine.report(ledgerOf(h), StatsPeriod.Preset(StatsTimeRange.ALL), nowMs, zone)

    private fun hoursOf(report: StatsReport) = (0 until 24).map { hour -> (0 until 7).sumOf { report.habits!!.clock.timeMs[it * 24 + hour] } }

    // --- the case of the review ---------------------------------------------------------------------------------------

    @Test
    fun `a listen heard in the morning and again at night keeps both hours, both sessions and one play`() {
        ListenHarness(identityOfLocator = { "item-1" }).use { h ->
            val clock = h.wallClockAt(at(2026, 10, 14, 8, 0))

            h.transition("1")
            h.awaitLookups(1)
            clock.pass(MIN) // 08:01
            h.sync("1", playing = false)
            clock.pass(14 * HOUR + 59 * MIN) // 23:00
            h.sync("1", playing = true)
            clock.pass(MIN) // 23:01
            h.finish(TrackEndReason.USER_NEXT, events = 2)

            val events = h.events()
            assertThat(events).hasSize(2)
            // One occurrence in two pieces, each where it was heard.
            assertThat(events.map { it.occurrenceId }.distinct()).containsExactly("o1")
            assertThat(events.map { it.startMillis() to it.endMillis() }).containsExactly(
                at(2026, 10, 14, 8, 0) to at(2026, 10, 14, 8, 1),
                at(2026, 10, 14, 23, 0) to at(2026, 10, 14, 23, 1),
            ).inOrder()
            assertThat(events.map { it.durationMs }).containsExactly(MIN, MIN)

            val report = reportAt(h, at(2026, 10, 15, 0))
            val hours = hoursOf(report)
            assertThat(hours[8]).isEqualTo(MIN)
            assertThat(hours[22]).isEqualTo(0L)
            assertThat(hours[23]).isEqualTo(MIN)
            assertThat(report.habits!!.sessions.count).isEqualTo(2)
            assertThat(report.totals.listenedMs).isEqualTo(2 * MIN)
            // Two minutes of a four-minute song is a play, and the pieces are one of them.
            assertThat(report.totals.plays).isEqualTo(1)
            val ledger = ledgerOf(h)
            assertThat(ledger.occurrences).hasSize(1)
            assertThat(ledger.occurrences.single().fragments).hasSize(2)
            assertThat(ledger.stats("item-1")!!.plays).isEqualTo(1)
            assertThat(ledger.stats("item-1")!!.starts).isEqualTo(1)
        }
    }

    @Test
    fun `the pieces of a listen are not several skips and several plays`() {
        ListenHarness(identityOfLocator = { "item-1" }).use { h ->
            val clock = h.wallClockAt(at(2026, 10, 14, 8, 0))

            // 60 s in the morning and 30 s at night, then Next: 90 s of a four-minute song is not a play, and the listener left once.
            h.transition("1")
            h.awaitLookups(1)
            clock.pass(MIN)
            h.sync("1", playing = false)
            clock.pass(14 * HOUR + 59 * MIN)
            h.sync("1", playing = true)
            clock.pass(30_000L)
            h.finish(TrackEndReason.USER_NEXT, events = 2)

            val events = h.events()
            assertThat(events.map { it.endReason }).containsExactly(TrackEndReason.INTERRUPTED, TrackEndReason.USER_NEXT).inOrder()
            val ledger = ledgerOf(h)
            assertThat(ledger.occurrences).hasSize(1)
            val stats = ledger.stats("item-1")!!
            assertThat(stats.plays).isEqualTo(0)
            assertThat(stats.skips).isEqualTo(1)
            assertThat(stats.starts).isEqualTo(1)
            assertThat(stats.listenedMs).isEqualTo(90_000L)
        }
    }

    @Test
    fun `pieces that add up to a play are one play, and only the last one says how the listen ended`() {
        ListenHarness(identityOfLocator = { "item-1" }).use { h ->
            val clock = h.wallClockAt(at(2026, 10, 14, 8, 0))

            h.transition("1")
            h.awaitLookups(1)
            clock.pass(70_000L)
            h.sync("1", playing = false)
            clock.pass(10 * HOUR)
            h.sync("1", playing = true)
            clock.pass(70_000L)
            h.finish(TrackEndReason.NATURAL_END, events = 2)

            val events = h.events()
            assertThat(events.map { it.endReason }).containsExactly(TrackEndReason.INTERRUPTED, TrackEndReason.NATURAL_END).inOrder()
            // Neither piece alone reaches half the song; the listen does.
            assertThat(events.map { it.durationMs }).containsExactly(70_000L, 70_000L)
            val stats = ledgerOf(h).stats("item-1")!!
            assertThat(stats.plays).isEqualTo(1)
            assertThat(stats.skips).isEqualTo(0)
            assertThat(stats.starts).isEqualTo(1)
        }
    }

    // --- across midnight -----------------------------------------------------------------------------------------------

    @Test
    fun `a pause after midnight keeps each piece in the day it was heard, and the play in the day the listen ended`() {
        ListenHarness(identityOfLocator = { "item-1" }).use { h ->
            val clock = h.wallClockAt(at(2026, 10, 14, 23, 58))

            h.transition("1")
            h.awaitLookups(1)
            clock.pass(2 * MIN + 30_000L) // 23:58 to 00:00:30, across midnight in one piece
            h.sync("1", playing = false)
            clock.pass(7 * HOUR + 59 * MIN + 30_000L) // 08:00 on the 15th
            h.sync("1", playing = true)
            clock.pass(MIN)
            h.finish(TrackEndReason.USER_NEXT, events = 2)

            val ledger = ledgerOf(h)
            val first = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.DAY), at(2026, 10, 14, 23, 59) + 59_999L, zone)
            val second = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.DAY), at(2026, 10, 15, 23, 59) + 59_999L, zone)
            val all = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), at(2026, 10, 15, 23, 59), zone)

            assertThat(first.totals.listenedMs).isEqualTo(2 * MIN)
            assertThat(second.totals.listenedMs).isEqualTo(MIN + 30_000L)
            assertThat(all.totals.listenedMs).isEqualTo(first.totals.listenedMs + second.totals.listenedMs)
            // 3.5 minutes of the track is a play, and it belongs to the day the listen ended.
            assertThat(first.totals.plays).isEqualTo(0)
            assertThat(second.totals.plays).isEqualTo(1)
            val hours = hoursOf(all)
            assertThat(hours[23]).isEqualTo(2 * MIN)
            assertThat(hours[0]).isEqualTo(30_000L)
            assertThat(hours[8]).isEqualTo(MIN)
            assertThat(all.habits!!.sessions.count).isEqualTo(2)
        }
    }

    // --- what is not split ---------------------------------------------------------------------------------------------

    @Test
    fun `a short pause stays inside one event and takes no time from it`() {
        ListenHarness(identityOfLocator = { "item-1" }).use { h ->
            val clock = h.wallClockAt(at(2026, 10, 14, 20, 0))

            h.transition("1")
            h.awaitLookups(1)
            clock.pass(60_000L)
            h.sync("1", playing = false)
            clock.pass(shortRun / 2)
            h.sync("1", playing = true)
            clock.pass(60_000L)
            h.finish(TrackEndReason.NATURAL_END)

            val events = h.events()
            assertThat(events).hasSize(1)
            assertThat(events.single().durationMs).isEqualTo(120_000L)
            assertThat(events.single().endMillis()).isEqualTo(at(2026, 10, 14, 20, 0) + 120_000L + shortRun / 2)
        }
    }

    @Test
    fun `pauses that add up to more than the limit end the event, however short each one is`() {
        ListenHarness(identityOfLocator = { "item-1" }).use { h ->
            val clock = h.wallClockAt(at(2026, 10, 14, 20, 0))

            h.transition("1")
            h.awaitLookups(1)
            clock.pass(30_000L)
            h.sync("1", playing = false)
            clock.pass(40_000L) // 40 s: inside the limit
            h.sync("1", playing = true)
            clock.pass(30_000L)
            h.sync("1", playing = false)
            clock.pass(40_000L) // 80 s in all: over it
            h.sync("1", playing = true)
            clock.pass(30_000L)
            h.finish(TrackEndReason.USER_NEXT, events = 2)

            val events = h.events()
            assertThat(events.map { it.durationMs }).containsExactly(60_000L, 30_000L).inOrder()
            assertThat(events.map { it.occurrenceId }.distinct()).hasSize(1)
        }
    }

    @Test
    fun `a listen that is paused when it ends ends when it stopped playing, not when the player last spoke`() {
        ListenHarness(identityOfLocator = { "item-1" }).use { h ->
            val clock = h.wallClockAt(at(2026, 10, 14, 8, 0))

            h.transition("1")
            h.awaitLookups(1)
            clock.pass(3 * MIN)
            h.sync("1", playing = false) // paused at 08:03
            clock.pass(4 * HOUR)
            h.sync("1", playing = false) // the player says it is still paused at 12:03
            h.finish(TrackEndReason.USER_NEXT)

            val event = h.events().single()
            assertThat(event.endMillis()).isEqualTo(at(2026, 10, 14, 8, 3))
            assertThat(event.startMillis()).isEqualTo(at(2026, 10, 14, 8, 0))
        }
    }

    @Test
    fun `a session that never played writes nothing, and one that plays after a long wait writes only what was heard`() {
        ListenHarness(identityOfLocator = { "item-1" }).use { h ->
            val clock = h.wallClockAt(at(2026, 10, 14, 8, 0))

            // Opened paused, left for a day, played for a minute.
            h.transition("1", playing = false)
            h.awaitLookups(1)
            clock.pass(24 * HOUR)
            h.sync("1", playing = true)
            clock.pass(MIN)
            h.finish(TrackEndReason.USER_NEXT)

            val event = h.events().single()
            assertThat(event.durationMs).isEqualTo(MIN)
            assertThat(event.startMillis()).isEqualTo(at(2026, 10, 15, 8, 0))
        }
    }

    // --- the audio of each piece ---------------------------------------------------------------------------------------

    @Test
    fun `each piece records the audio that carried it`() {
        ListenHarness(identityOfLocator = { "item-1" }).use { h ->
            val clock = h.wallClockAt(at(2026, 10, 14, 8, 0))
            val speaker = ListenAudioFacts(route = ListenRoute.SPEAKER)
            val dac = ListenAudioFacts(route = ListenRoute.USB_DIRECT, signal = ListenSignal.PURITY, deviceName = "Test DAC")

            h.tracker.onAudioFacts(speaker)
            h.transition("1")
            h.awaitLookups(1)
            clock.pass(MIN)
            h.sync("1", playing = false)
            clock.pass(12 * HOUR)
            h.tracker.onAudioFacts(dac)
            h.sync("1", playing = true)
            clock.pass(MIN)
            h.finish(TrackEndReason.USER_NEXT, events = 2)

            val events = h.events()
            assertThat(events[0].audio).isEqualTo(speaker)
            assertThat(events[1].audio).isEqualTo(dac)
        }
    }
}
