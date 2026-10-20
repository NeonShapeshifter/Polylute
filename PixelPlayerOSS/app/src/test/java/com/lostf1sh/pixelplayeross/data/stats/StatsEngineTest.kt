package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit.MIN
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.Kit.event
import com.lostf1sh.pixelplayeross.data.stats.Kit.song
import java.time.DayOfWeek
import java.time.YearMonth
import org.junit.jupiter.api.Test

/** K-03, D3's pure half, D4, D5, D6 and D7 over synthetic histories. */
class StatsEngineTest {
    private val now = at(2026, 10, 15, 20)
    private val all = StatsPeriod.Preset(StatsTimeRange.ALL)

    private fun library(n: Int) = Kit.catalog((1..n).map { song(it.toLong(), durationMs = 4 * MIN) })

    private fun report(events: List<PlaybackStatsRepository.PlaybackEvent>, catalog: LibraryCatalog, period: StatsPeriod = all, detail: StatsEngine.Detail = StatsEngine.Detail.FULL) =
        StatsEngine.report(Kit.ledger(events, catalog), period, now, Kit.zone, detail)

    // --- K-03 ------------------------------------------------------------------------------------------------

    @Test
    fun `the concentration is the same in plays and in time and is over every song`() {
        // 101 songs. Song 101 has the most time and no play; song 1 has plays and little time.
        val events = buildList {
            for (i in 1..100) add(Kit.play(i.toLong(), now - i * 3 * MIN, occurrence = "p$i"))
            add(event(101, now - 400 * MIN, 3 * MIN, identity = "item-101", occurrence = "x1", reason = TrackEndReason.USER_NEXT, itemMs = 40 * MIN)) // 3 of 40 min: not a play
            add(event(101, now - 500 * MIN, 3 * MIN, identity = "item-101", occurrence = "x2", reason = TrackEndReason.USER_NEXT, itemMs = 40 * MIN))
            add(event(101, now - 600 * MIN, 3 * MIN, identity = "item-101", occurrence = "x3", reason = TrackEndReason.USER_NEXT, itemMs = 40 * MIN))
            add(event(101, now - 700 * MIN, 3 * MIN, identity = "item-101", occurrence = "x4", reason = TrackEndReason.USER_NEXT, itemMs = 40 * MIN))
        }
        val r = report(events, library(101))
        val c = r.concentration
        assertThat(c.population).isEqualTo(101)
        assertThat(c.denominatorMs).isEqualTo(100 * 3 * MIN + 4 * 3 * MIN)
        assertThat(c.top1Ms).isEqualTo(12 * MIN) // song 101, which Plays mode would never list in its top
        // The rankings differ with the mode; the concentration object is one thing.
        assertThat(r.rankings.songs(StatsRankMode.PLAYS).first().item.identity).isNotEqualTo("item-101")
        assertThat(r.rankings.songs(StatsRankMode.TIME).first().item.identity).isEqualTo("item-101")
        assertThat(r.rankings.songCount).isEqualTo(101)
    }

    @Test
    fun `a thousand songs and unattributed listening leave the shares over the songs of the library`() {
        val events = buildList {
            for (i in 1..1000) add(Kit.play(i.toLong(), now - i * 4 * MIN, occurrence = "p$i"))
            add(event(5000, now - 1002 * 4 * MIN, 30 * MIN)) // a locator no track holds, long before the rest
        }
        val r = report(events, library(1000))
        assertThat(r.concentration.population).isEqualTo(1000)
        assertThat(r.concentration.denominatorMs).isEqualTo(1000 * 3 * MIN)
        assertThat(r.concentration.top10).isWithin(1e-6f).of(0.01f)
        assertThat(r.totals.listenedMs).isEqualTo(1000 * 3 * MIN + 30 * MIN)
        assertThat(r.note.unattributedMs).isEqualTo(30 * MIN)
    }

    @Test
    fun `the average plays per song is plays over the songs that had one, not over a shown list`() {
        val events = listOf(
            Kit.play(1, now - 10 * MIN, occurrence = "a"), Kit.play(1, now - 20 * MIN, occurrence = "b"),
            Kit.play(2, now - 30 * MIN, occurrence = "c"),
            event(3, now - 40 * MIN, 20_000, identity = "item-3", occurrence = "d", reason = TrackEndReason.USER_NEXT, itemMs = 4 * MIN),
        )
        val t = report(events, library(3)).totals
        assertThat(t.plays).isEqualTo(3)
        assertThat(t.songsPlayed).isEqualTo(2)
        assertThat(t.songsHeard).isEqualTo(3)
        assertThat(t.avgPlaysPerSong).isWithin(1e-9).of(1.5)
    }

    // --- totals and timeline ---------------------------------------------------------------------------------

    @Test
    fun `an empty history reports zero and no history start`() {
        val r = report(emptyList(), library(3))
        assertThat(r.isEmpty).isTrue()
        assertThat(r.historyStartMs).isNull()
        assertThat(r.totals.avgPlaysPerSong).isNull()
    }

    @Test
    fun `the week timeline has seven days and the day that was heard carries the time`() {
        val monday = at(2026, 10, 12, 10)
        val r = report(listOf(Kit.play(1, monday, occurrence = "a")), library(1), StatsPeriod.Preset(StatsTimeRange.WEEK))
        assertThat(r.timeline.unit).isEqualTo(TimelineUnit.DAY)
        assertThat(r.timeline.buckets).hasSize(7)
        assertThat(r.timeline.buckets[0].timeMs).isEqualTo(3 * MIN)
        assertThat(r.timeline.buckets[0].plays).isEqualTo(1)
        assertThat(r.timeline.buckets.drop(1).sumOf { it.timeMs }).isEqualTo(0L)
    }

    @Test
    fun `the day timeline has twenty four hours and the year twelve months`() {
        val events = listOf(Kit.play(1, at(2026, 10, 15, 2, 30), occurrence = "a"))
        val day = report(events, library(1), StatsPeriod.Preset(StatsTimeRange.DAY))
        assertThat(day.timeline.unit).isEqualTo(TimelineUnit.HOUR)
        assertThat(day.timeline.buckets).hasSize(24)
        assertThat(day.timeline.buckets[2].timeMs).isEqualTo(3 * MIN)
        val year = report(events, library(1), StatsPeriod.Preset(StatsTimeRange.YEAR))
        assertThat(year.timeline.buckets).hasSize(12)
        assertThat(year.timeline.buckets[9].timeMs).isEqualTo(3 * MIN)
    }

    @Test
    fun `two events that overlap are not counted twice`() {
        val a = event(1, at(2026, 10, 15, 10, 4), 4 * MIN, identity = "item-1", occurrence = "a")
        val b = event(2, at(2026, 10, 15, 10, 6), 4 * MIN, identity = "item-2", occurrence = "b") // begins 2 min before a ends
        val r = report(listOf(a, b), library(2))
        assertThat(r.totals.listenedMs).isEqualTo(6 * MIN)
    }

    // --- D5: audio -------------------------------------------------------------------------------------------

    @Test
    fun `audio facts split the time and an event without them is not recorded and never guessed`() {
        val purity = ListenAudioFacts(
            route = ListenRoute.USB_DIRECT, signal = ListenSignal.PURITY, bitPerfect = ListenBitPerfect.UNKNOWN,
            sourceRateHz = 96_000, sourceBits = 24, sourceCodec = "audio/flac", deviceName = "DAC One",
        )
        val speaker = ListenAudioFacts(route = ListenRoute.SPEAKER, bitPerfect = ListenBitPerfect.NO, sourceRateHz = 44_100, sourceBits = 16, sourceCodec = "audio/flac")
        val events = listOf(
            event(1, now - 100 * MIN, 3 * MIN, identity = "item-1", occurrence = "a", audio = purity),
            event(2, now - 90 * MIN, 1 * MIN, identity = "item-2", occurrence = "b", audio = speaker),
            event(3, now - 80 * MIN, 2 * MIN, identity = "item-3", occurrence = "c", audio = purity.copy(share = 50)),
            event(1, now - 70 * MIN, 5 * MIN, identity = "item-1", occurrence = "d"), // from before the facts
        )
        val audio = report(events, library(3)).audio!!
        assertThat(audio.sinceMs).isEqualTo(now - 100 * MIN)
        assertThat(audio.notRecordedMs).isEqualTo(5 * MIN)
        assertThat(audio.mixedMs).isEqualTo(MIN) // the half of the third that its facts do not cover
        assertThat(audio.recordedMs).isEqualTo(5 * MIN)
        assertThat(audio.purityMs).isEqualTo(4 * MIN)
        assertThat(audio.noSignalMs).isEqualTo(1 * MIN)
        assertThat(audio.bitPerfectMs).isEqualTo(0L)
        assertThat(audio.notBitPerfectMs).isEqualTo(1 * MIN)
        assertThat(audio.unknownBitPerfectMs).isEqualTo(4 * MIN)
        assertThat(audio.routes.map { it.route }).containsExactly(ListenRoute.USB_DIRECT, ListenRoute.SPEAKER).inOrder()
        assertThat(audio.devices.single().name).isEqualTo("DAC One")
        assertThat(audio.formats.first().key).isEqualTo(AudioFormatKey(96_000, 24, "audio/flac"))
        assertThat(audio.formats.first().key.codecLabel).isEqualTo("FLAC")
        assertThat(audio.highest?.key).isEqualTo(AudioFormatKey(96_000, 24, "audio/flac"))
        assertThat(audio.highest?.item?.identity).isEqualTo("item-1")
    }

    @Test
    fun `a history with no audio facts says there are none`() {
        val audio = report(listOf(Kit.play(1, now - MIN, occurrence = "a")), library(1)).audio!!
        assertThat(audio.hasData).isFalse()
        assertThat(audio.sinceMs).isNull()
        assertThat(audio.notRecordedMs).isEqualTo(3 * MIN)
    }

    // --- D6: habits ------------------------------------------------------------------------------------------

    @Test
    fun `the clock puts time in the hour and weekday it was heard and plays where they ended`() {
        // 2026-10-15 is a Thursday.
        val habits = report(listOf(Kit.play(1, at(2026, 10, 15, 2, 30), occurrence = "a")), library(1)).habits!!
        assertThat(habits.clock.time(DayOfWeek.THURSDAY, 2)).isEqualTo(3 * MIN)
        assertThat(habits.clock.plays(DayOfWeek.THURSDAY, 2)).isEqualTo(1)
        assertThat(habits.clock.busiestHour()).isEqualTo(2)
        assertThat(habits.clock.busiestDay()).isEqualTo(DayOfWeek.THURSDAY)
    }

    @Test
    fun `a streak is a run of days with a play and the current one reaches today or yesterday`() {
        val events = listOf(13, 14, 15).map { Kit.play(1, at(2026, 10, it, 9), occurrence = "d$it") } +
            listOf(1, 2).map { Kit.play(1, at(2026, 9, it, 9), occurrence = "s$it") }
        val habits = report(events, library(1)).habits!!
        assertThat(habits.streaks.current).isEqualTo(3)
        assertThat(habits.streaks.longest).isEqualTo(3)
        val yesterdayOnly = report(listOf(Kit.play(1, at(2026, 10, 14, 9), occurrence = "y")), library(1)).habits!!
        assertThat(yesterdayOnly.streaks.current).isEqualTo(1)
        val stale = report(listOf(Kit.play(1, at(2026, 10, 1, 9), occurrence = "z")), library(1)).habits!!
        assertThat(stale.streaks.current).isEqualTo(0)
    }

    @Test
    fun `a period in the past has no current streak`() {
        val oct = StatsPeriod.Months(YearMonth.of(2026, 9), YearMonth.of(2026, 9))
        val habits = report(listOf(Kit.play(1, at(2026, 9, 3, 9), occurrence = "a")), library(1), oct).habits!!
        assertThat(habits.streaks.current).isNull()
        assertThat(habits.streaks.longest).isEqualTo(1)
    }

    @Test
    fun `sessions end after thirty minutes of silence`() {
        val base = at(2026, 10, 15, 9)
        val events = listOf(
            event(1, base, 3 * MIN, identity = "item-1", occurrence = "a"),
            event(2, base + 5 * MIN, 3 * MIN, identity = "item-2", occurrence = "b"),
            event(3, base + 90 * MIN, 3 * MIN, identity = "item-3", occurrence = "c"),
        )
        val s = report(events, library(3)).habits!!.sessions
        assertThat(s.count).isEqualTo(2)
        assertThat(s.longestMs).isEqualTo(8 * MIN)
        assertThat(s.averageMs).isEqualTo((8 * MIN + 3 * MIN) / 2)
    }

    @Test
    fun `discoveries are what was first played inside the period`() {
        val events = listOf(
            Kit.play(1, at(2026, 8, 1, 9), occurrence = "old"), // first play of 1 is in August
            Kit.play(1, at(2026, 10, 2, 9), occurrence = "again"),
            Kit.play(2, at(2026, 10, 3, 9), occurrence = "new"),
        )
        val period = StatsPeriod.Months(YearMonth.of(2026, 10), YearMonth.of(2026, 10))
        val d = report(events, library(2), period).habits!!.discoveries
        assertThat(d.songs.map { it.item.identity }).containsExactly("item-2")
        assertThat(d.songCount).isEqualTo(1)
    }

    @Test
    fun `an album is heard complete when every track has a play, and needs three tracks`() {
        val songs = (1..3).map { song(it.toLong(), album = "Full", albumId = 7, track = it) } +
            (4..6).map { song(it.toLong(), album = "Half", albumId = 8, track = it - 3) } +
            song(7, album = "Single", albumId = 9, track = 1)
        val events = (1..3).map { Kit.play(it.toLong(), now - it * 10 * MIN, occurrence = "f$it") } +
            listOf(Kit.play(4, now - 5 * MIN, occurrence = "h4"), Kit.play(5, now - 6 * MIN, occurrence = "h5")) +
            Kit.play(7, now - 7 * MIN, occurrence = "s7")
        val albums = report(events, Kit.catalog(songs)).habits!!.albums
        assertThat(albums.complete.map { it.title }).containsExactly("Full")
        assertThat(albums.partial.map { it.title to it.tracksPlayed }).containsExactly("Half" to 2)
    }

    @Test
    fun `a skip rate needs a sample and only counts listens whose way out was recorded`() {
        val recorded = (1..6).map { i ->
            event(1, now - i * 20 * MIN, 20_000, identity = "item-1", occurrence = "r$i", reason = TrackEndReason.USER_NEXT, itemMs = 4 * MIN)
        } + (7..9).map { i -> Kit.play(1, now - i * 20 * MIN, occurrence = "r$i") }
        val legacy = (1..20).map { i -> event(2, now - i * 25 * MIN, 10_000, identity = null) }
        val skips = report(recorded + legacy, library(2)).habits!!.skips
        assertThat(skips.songs.map { it.item?.identity }).containsExactly("item-1")
        assertThat(skips.songs.single().skips).isEqualTo(6)
        assertThat(skips.songs.single().starts).isEqualTo(9)
        assertThat(skips.sinceMs).isNotNull()
    }

    // --- D7 --------------------------------------------------------------------------------------------------

    @Test
    fun `a top list is the songs with a play of a period, and says when there are fewer than asked`() {
        val events = (1..7).flatMap { id -> (1..id).map { n -> Kit.play(id.toLong(), at(2026, 3, 1 + n, 12), occurrence = "o$id-$n") } } +
            listOf(Kit.play(1, at(2026, 11, 1), occurrence = "outside")) // another month
        val period = StatsPeriod.Months(YearMonth.of(2026, 3), YearMonth.of(2026, 8))
        val top = StatsEngine.topN(Kit.ledger(events, library(7)), period, 25, StatsRankMode.PLAYS, now, Kit.zone)
        assertThat(top.rows.map { it.item.identity }.take(3)).containsExactly("item-7", "item-6", "item-5").inOrder()
        assertThat(top.rows.first().plays).isEqualTo(7)
        assertThat(top.eligible).isEqualTo(7)
        assertThat(top.isShort).isTrue()
        assertThat(top.report.totals.plays).isEqualTo(28)
        assertThat(StatsEngine.topList(top.report, period, 3, StatsRankMode.PLAYS).rows).hasSize(3)
        assertThat(StatsEngine.topList(top.report, period, 3, StatsRankMode.PLAYS).isShort).isFalse()
    }

    @Test
    fun `the current month is in progress and ends at now`() {
        val b = StatsPeriod.Months(YearMonth.of(2026, 1), YearMonth.of(2026, 12)).bounds(now, Kit.zone, null)
        assertThat(b.inProgress).isTrue()
        assertThat(b.toMs).isEqualTo(now + 1)
        val past = StatsPeriod.Months(YearMonth.of(2026, 3), YearMonth.of(2026, 8)).bounds(now, Kit.zone, null)
        assertThat(past.inProgress).isFalse()
        assertThat(past.toMs).isEqualTo(at(2026, 9, 1, 0))
    }

    @Test
    fun `an overview skips the audio and the habits`() {
        val r = report(listOf(Kit.play(1, now - MIN, occurrence = "a")), library(1), detail = StatsEngine.Detail.OVERVIEW)
        assertThat(r.audio).isNull()
        assertThat(r.habits).isNull()
        assertThat(r.totals.plays).isEqualTo(1)
        assertThat(r.rankings.songCount).isEqualTo(1)
    }
}
