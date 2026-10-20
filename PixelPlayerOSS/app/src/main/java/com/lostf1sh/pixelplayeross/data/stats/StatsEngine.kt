package com.lostf1sh.pixelplayeross.data.stats

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * Local time arithmetic on epoch milliseconds, without an object per instant.
 *
 * Hours, days and weekdays are the listener's own (the zone the phone is in), and the day number is the local epoch day, so
 * it is also what [LocalDate.ofEpochDay] takes.
 */
internal class LocalClock(zone: ZoneId) {
    private val rules = zone.rules
    private val fixedOffsetMs: Long? = if (rules.isFixedOffset) rules.getOffset(Instant.EPOCH).totalSeconds * 1000L else null

    fun offsetMs(utcMs: Long): Long = fixedOffsetMs ?: (rules.getOffset(Instant.ofEpochMilli(utcMs)).totalSeconds * 1000L)

    fun dayIndex(utcMs: Long): Long = Math.floorDiv(utcMs + offsetMs(utcMs), DAY_MS)

    fun hour(utcMs: Long): Int = Math.floorMod(Math.floorDiv(utcMs + offsetMs(utcMs), HOUR_MS), 24L).toInt()

    /** 0 = Monday. 1970-01-01 was a Thursday. */
    fun weekday(dayIndex: Long): Int = Math.floorMod(dayIndex + 3, 7L).toInt()

    /** Calls [action] once per local hour that [startMs, endMs) touches, with the part of the interval inside it. */
    inline fun forEachHour(startMs: Long, endMs: Long, action: (sliceMs: Long, dayIndex: Long, hour: Int) -> Unit) {
        var t = startMs
        while (t < endMs) {
            val offset = offsetMs(t)
            val local = t + offset
            val hourIndex = Math.floorDiv(local, HOUR_MS)
            val sliceEnd = min(endMs, max(t + 1, (hourIndex + 1) * HOUR_MS - offset))
            action(sliceEnd - t, Math.floorDiv(hourIndex, 24L), Math.floorMod(hourIndex, 24L).toInt())
            t = sliceEnd
        }
    }

    companion object {
        const val HOUR_MS = 60L * 60 * 1000
        const val DAY_MS = 24 * HOUR_MS
    }
}

/**
 * Turns a [ListeningLedger] into what the screens show (B28).
 *
 * Pure: the ledger, a period, the time and the zone in; a [StatsReport] out. It reads nothing from a file or a database and
 * knows no language, so every number on the Stats screen, on Home's card and in a "Top N" is made by these functions and can be
 * checked against a synthetic history.
 *
 *  - Listening time is clipped to the period; plays belong to the period their occurrence **ended** in (D2). A listen that
 *    straddles midnight is time in two days and one play in one.
 *  - Everything is computed over the whole population before any list is shortened (K-03); [Rankings] holds the lists whole.
 *  - Time and plays that cannot be tied to a track stay in the totals and are reported in [StatsReport.coverage]; they are
 *    never credited to another track (R-04).
 */
object StatsEngine {

    /** How much of a report to build. Home's card needs only the numbers and the shape. */
    enum class Detail { OVERVIEW, FULL }

    /** Silence that ends a listening session. */
    const val SESSION_GAP_MS = 30L * 60 * 1000

    /** An album is "heard complete" only if it has this many tracks, so a single does not count after one play. */
    const val MIN_ALBUM_TRACKS = 3

    /** A song needs this many recorded listens before a skip rate is shown for it, and an artist this many. */
    const val MIN_SONG_SKIP_SAMPLE = 5
    const val MIN_ARTIST_SKIP_SAMPLE = 15

    const val SKIP_LIST_SIZE = 10
    const val DISCOVERY_LIST_SIZE = 25
    const val PARTIAL_ALBUMS = 5

    private class ItemAcc {
        var timeMs = 0L
        var plays = 0
        var skips = 0
        var recordedStarts = 0
    }

    fun report(
        ledger: ListeningLedger,
        period: StatsPeriod,
        nowMs: Long,
        zone: ZoneId,
        detail: Detail = Detail.FULL,
    ): StatsReport {
        val full = detail == Detail.FULL
        val bounds = period.bounds(nowMs, zone, ledger.firstMs)
        val from = bounds.fromMs ?: ledger.firstMs ?: nowMs
        val to = bounds.toMs
        val clock = LocalClock(zone)
        val days = bounds.days(zone, ledger.firstMs ?: nowMs)

        val items = HashMap<String, ItemAcc>()
        val dayMs = HashMap<Long, Long>()
        val dayPlays = HashMap<Long, Int>()
        val clockMs = LongArray(7 * 24)
        val clockPlays = IntArray(7 * 24)
        val hourMs = LongArray(24)
        val hourPlays = IntArray(24)
        val sessionsList = ArrayList<LongArray>()
        val audio = if (full) AudioAcc() else null

        var listenedMs = 0L
        var identityMs = 0L; var locatorMs = 0L; var goneMs = 0L; var unattributedMs = 0L
        var identityEvents = 0; var locatorEvents = 0; var goneEvents = 0; var unattributedEvents = 0

        // --- time: the events that overlap the period, clipped to it --------------------------------------
        // Searched in the fragments that own time: they are disjoint and in order, which the ends of all the events are not (R28-03).
        val fragments = ledger.timed
        var lo = 0
        var hi = fragments.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (fragments[mid].endMs > from) hi = mid else lo = mid + 1
        }
        for (index in lo until fragments.size) {
            val f = fragments[index]
            if (f.effStartMs >= to) break
            val start = max(f.effStartMs, from)
            val end = min(f.endMs, to)
            val ms = end - start
            if (ms <= 0L) continue
            listenedMs += ms
            when {
                f.basis == Basis.NONE -> { unattributedMs += ms; unattributedEvents++ }
                f.item == null -> { goneMs += ms; goneEvents++ }
                f.basis == Basis.IDENTITY -> { identityMs += ms; identityEvents++ }
                else -> { locatorMs += ms; locatorEvents++ }
            }
            f.item?.let { items.getOrPut(it.identity) { ItemAcc() }.timeMs += ms }
            clock.forEachHour(start, end) { slice, day, hour ->
                dayMs.merge(day, slice, Long::plus)
                val weekday = clock.weekday(day)
                clockMs[weekday * 24 + hour] += slice
                hourMs[hour] += slice
            }
            if (full) {
                val last = sessionsList.lastOrNull()
                if (last != null && start - last[1] <= SESSION_GAP_MS) {
                    last[1] = max(last[1], end)
                } else {
                    sessionsList += longArrayOf(start, end)
                }
                audio!!.add(f, ms)
            }
        }

        // --- plays and skips: the occurrences that ended in the period -------------------------------------
        val occurrences = ledger.occurrences
        lo = 0
        hi = occurrences.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (occurrences[mid].endMs >= from) hi = mid else lo = mid + 1
        }
        var plays = 0
        val playDays = java.util.TreeSet<Long>()
        for (index in lo until occurrences.size) {
            val o = occurrences[index]
            if (o.endMs >= to) break
            val acc = o.item?.let { items.getOrPut(it.identity) { ItemAcc() } }
            if (o.isRecorded) acc?.let { it.recordedStarts++ }
            if (o.isSkip) acc?.let { it.skips++ }
            if (!o.isPlay) continue
            plays++
            acc?.let { it.plays++ }
            val day = clock.dayIndex(o.endMs)
            val hour = clock.hour(o.endMs)
            dayPlays.merge(day, 1, Int::plus)
            clockPlays[clock.weekday(day) * 24 + hour]++
            hourPlays[hour]++
            playDays += day
        }

        // --- rankings --------------------------------------------------------------------------------------
        val catalog = ledger.catalog
        val songRows = ArrayList<SongRow>(items.size)
        for ((identity, acc) in items) {
            val item = catalog.item(identity) ?: continue
            if (acc.timeMs <= 0L && acc.plays == 0) continue
            songRows += SongRow(item, acc.plays, acc.timeMs, acc.skips)
        }

        val artistRows = if (full) artistRows(songRows) else emptyList()
        val albumRows = if (full) albumRows(songRows) else emptyList()
        val genreRows = if (full) genreRows(songRows) else emptyList()
        val rankings = Rankings(songRows, artistRows, albumRows, genreRows)

        // --- the headline numbers and the concentration (whole population, both before any list is cut) -----
        val songsPlayed = songRows.count { it.plays > 0 }
        val playsOfListedSongs = songRows.sumOf { it.plays }
        val totals = Totals(
            listenedMs = listenedMs,
            plays = plays,
            songsPlayed = songsPlayed,
            songsHeard = songRows.count { it.timeMs > 0L },
            activeDays = dayMs.size,
            days = days,
            avgPerDayMs = listenedMs / days,
            avgPlaysPerSong = if (songsPlayed > 0) playsOfListedSongs.toDouble() / songsPlayed else null,
        )
        val byTime = songRows.map { it.timeMs }.filter { it > 0L }.sortedDescending()
        val concentration = Concentration(
            population = byTime.size,
            denominatorMs = byTime.sum(),
            top1Ms = byTime.take(1).sum(),
            top3Ms = byTime.take(3).sum(),
            top10Ms = byTime.take(10).sum(),
        )

        val coverage = Coverage(
            identityMs, locatorMs, goneMs, unattributedMs,
            identityEvents, locatorEvents, goneEvents, unattributedEvents,
        )

        return StatsReport(
            period = period,
            fromMs = bounds.fromMs,
            toMs = to,
            inProgress = bounds.inProgress,
            generatedAtMs = nowMs,
            totals = totals,
            coverage = coverage,
            rankings = rankings,
            concentration = concentration,
            timeline = timeline(period, bounds, zone, clock, dayMs, dayPlays, hourMs, hourPlays, ledger.firstMs, nowMs),
            audio = audio?.finish(ledger.audioSinceMs),
            habits = if (full) {
                habits(
                    ledger = ledger, from = from, to = to, nowMs = nowMs, zone = zone, clock = clock,
                    clockMs = clockMs, clockPlays = clockPlays, sessions = sessionsList, playDays = playDays,
                    items = items, songRows = songRows, albumRows = albumRows, inProgress = bounds.inProgress,
                )
            } else null,
            historyStartMs = ledger.firstMs,
        )
    }

    /** The N songs of a period of months, ranked by [mode], and the summary of the same period from the same functions (D7). */
    fun topN(
        ledger: ListeningLedger,
        period: StatsPeriod.Months,
        size: Int,
        mode: StatsRankMode,
        nowMs: Long,
        zone: ZoneId,
    ): TopNReport = topList(report(ledger, period, nowMs, zone, Detail.FULL), period, size, mode)

    /**
     * The list of a "Top N" from the report of its period: the songs that had a play, by [mode], the first [size]. A song that was
     * only skipped is not one of the songs of a period, so a period with fewer than [size] played songs gives a shorter list and
     * [TopNReport.isShort] says so. Size and mode only cut and order what the report already holds.
     */
    fun topList(report: StatsReport, period: StatsPeriod.Months, size: Int, mode: StatsRankMode): TopNReport {
        val played = report.rankings.songs(mode).filter { it.plays > 0 }
        return TopNReport(period, size, mode, played.take(size), played.size, report)
    }

    // --- rankings by artist, album, genre, from the songs ---------------------------------------------------

    private class NameAcc {
        var plays = 0
        var timeMs = 0L
        var songs = 0
        val artists = HashSet<String>()
    }

    private fun artistRows(songs: List<SongRow>): List<ArtistRow> {
        val by = HashMap<String, NameAcc>()
        for (row in songs) {
            val names = row.item.artists.ifEmpty { listOf("") }
            for (name in names) {
                val acc = by.getOrPut(name) { NameAcc() }
                acc.plays += row.plays; acc.timeMs += row.timeMs; acc.songs++
            }
        }
        return by.map { (name, acc) -> ArtistRow(name, acc.plays, acc.timeMs, acc.songs) }
    }

    private fun albumRows(songs: List<SongRow>): List<AlbumRow> {
        class Album(val first: CatalogItem) {
            var plays = 0; var timeMs = 0L; var songs = 0
        }
        val by = HashMap<String, Album>()
        for (row in songs) {
            if (row.item.album.isBlank()) continue
            val acc = by.getOrPut(row.item.albumKey) { Album(row.item) }
            acc.plays += row.plays; acc.timeMs += row.timeMs; acc.songs++
        }
        return by.map { (key, acc) ->
            val song = acc.first.song
            AlbumRow(
                key = key,
                title = acc.first.album,
                artist = song.albumArtist?.takeIf { it.isNotBlank() } ?: acc.first.artists.firstOrNull().orEmpty(),
                artUri = song.albumArtUriString,
                plays = acc.plays, timeMs = acc.timeMs, songs = acc.songs,
            )
        }
    }

    private fun genreRows(songs: List<SongRow>): List<GenreRow> {
        val by = HashMap<String, NameAcc>()
        for (row in songs) {
            val acc = by.getOrPut(row.item.genre) { NameAcc() }
            acc.plays += row.plays; acc.timeMs += row.timeMs
            acc.artists += row.item.artists.map { it.lowercase() }.ifEmpty { listOf("") }
        }
        return by.map { (name, acc) -> GenreRow(name, acc.plays, acc.timeMs, acc.artists.size) }
    }

    // --- the timeline ----------------------------------------------------------------------------------------

    private fun timeline(
        period: StatsPeriod,
        bounds: StatsPeriod.Bounds,
        zone: ZoneId,
        clock: LocalClock,
        dayMs: Map<Long, Long>,
        dayPlays: Map<Long, Int>,
        hourMs: LongArray,
        hourPlays: IntArray,
        firstMs: Long?,
        nowMs: Long,
    ): Timeline {
        val startMs = bounds.fromMs ?: firstMs ?: nowMs
        val startDay = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val nowDay = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        fun atStartOfDay(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()

        val unit = when (period) {
            is StatsPeriod.Preset -> when (period.range) {
                StatsTimeRange.DAY -> TimelineUnit.HOUR
                StatsTimeRange.WEEK, StatsTimeRange.MONTH -> TimelineUnit.DAY
                StatsTimeRange.YEAR, StatsTimeRange.ALL -> TimelineUnit.MONTH
            }
            is StatsPeriod.Months -> if (period.from == period.to) TimelineUnit.DAY else TimelineUnit.MONTH
        }
        if (unit == TimelineUnit.HOUR) {
            val buckets = (0 until 24).map { hour ->
                val start = startDay.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()
                val end = startDay.atStartOfDay(zone).plusHours(hour + 1L).toInstant().toEpochMilli()
                TimelineBucket(start, end, hourMs[hour], hourPlays[hour])
            }
            return Timeline(unit, buckets)
        }
        if (unit == TimelineUnit.DAY) {
            val first: LocalDate
            val count: Int
            when {
                period is StatsPeriod.Preset && period.range == StatsTimeRange.WEEK -> { first = startDay; count = 7 }
                period is StatsPeriod.Preset -> {
                    val month = YearMonth.from(startDay)
                    first = month.atDay(1); count = month.lengthOfMonth()
                }
                else -> {
                    val month = (period as StatsPeriod.Months).from
                    first = month.atDay(1); count = month.lengthOfMonth()
                }
            }
            val buckets = (0 until count).map { offset ->
                val day = first.plusDays(offset.toLong())
                val index = day.toEpochDay()
                TimelineBucket(atStartOfDay(day), atStartOfDay(day.plusDays(1)), dayMs[index] ?: 0L, dayPlays[index] ?: 0)
            }
            return Timeline(unit, buckets)
        }
        // months
        val firstMonth: YearMonth
        val lastMonth: YearMonth
        when (period) {
            is StatsPeriod.Months -> { firstMonth = period.from; lastMonth = period.to }
            is StatsPeriod.Preset -> if (period.range == StatsTimeRange.YEAR) {
                firstMonth = YearMonth.of(nowDay.year, 1); lastMonth = YearMonth.of(nowDay.year, 12)
            } else {
                firstMonth = YearMonth.from(startDay); lastMonth = YearMonth.from(nowDay)
            }
        }
        val timeByMonth = HashMap<YearMonth, Long>()
        for ((day, ms) in dayMs) timeByMonth.merge(YearMonth.from(LocalDate.ofEpochDay(day)), ms, Long::plus)
        val playsByMonth = HashMap<YearMonth, Int>()
        for ((day, n) in dayPlays) playsByMonth.merge(YearMonth.from(LocalDate.ofEpochDay(day)), n, Int::plus)
        val buckets = ArrayList<TimelineBucket>()
        var month = firstMonth
        while (!month.isAfter(lastMonth)) {
            buckets += TimelineBucket(
                atStartOfDay(month.atDay(1)), atStartOfDay(month.plusMonths(1).atDay(1)),
                timeByMonth[month] ?: 0L, playsByMonth[month] ?: 0,
            )
            month = month.plusMonths(1)
        }
        return Timeline(unit, buckets)
    }

    // --- audio ----------------------------------------------------------------------------------------------

    private class AudioAcc {
        var recordedMs = 0L
        var mixedMs = 0L
        var notRecordedMs = 0L
        var purityMs = 0L
        var resonanceMs = 0L
        var noSignalMs = 0L
        var bitPerfectMs = 0L
        var notBitPerfectMs = 0L
        var unknownBitPerfectMs = 0L
        val routes = HashMap<ListenRoute, Long>()
        val devices = HashMap<String, Long>()
        val formats = HashMap<AudioFormatKey, Long>()
        var highest: HighestResolution? = null

        fun add(f: Fragment, ms: Long) {
            val a = f.audio
            if (a == null) {
                notRecordedMs += ms
                return
            }
            val share = a.share ?: 100
            val recorded = ms * share / 100
            recordedMs += recorded
            mixedMs += ms - recorded
            when (a.signal) {
                ListenSignal.PURITY -> purityMs += recorded
                ListenSignal.RESONANCE -> resonanceMs += recorded
                null -> noSignalMs += recorded
            }
            when (a.bitPerfect) {
                ListenBitPerfect.YES -> bitPerfectMs += recorded
                ListenBitPerfect.NO -> notBitPerfectMs += recorded
                ListenBitPerfect.UNKNOWN, null -> unknownBitPerfectMs += recorded
            }
            routes.merge(a.route ?: ListenRoute.OTHER, recorded, Long::plus)
            a.deviceName?.let { devices.merge(it, recorded, Long::plus) }
            val key = AudioFormatKey.recorded(a.sourceRateHz, a.sourceBits, a.sourceCodec)
            formats.merge(key, recorded, Long::plus)
            if (key.rateHz != null && key.resolution > (highest?.key?.resolution ?: -1L)) {
                highest = HighestResolution(f.item, key)
            }
        }

        fun finish(sinceMs: Long?) = AudioStats(
            sinceMs = sinceMs,
            recordedMs = recordedMs, mixedMs = mixedMs, notRecordedMs = notRecordedMs,
            purityMs = purityMs, resonanceMs = resonanceMs, noSignalMs = noSignalMs,
            bitPerfectMs = bitPerfectMs, notBitPerfectMs = notBitPerfectMs, unknownBitPerfectMs = unknownBitPerfectMs,
            routes = routes.map { RouteTime(it.key, it.value) }.sortedByDescending { it.timeMs },
            devices = devices.map { DeviceTime(it.key, it.value) }.sortedByDescending { it.timeMs },
            formats = formats.map { FormatTime(it.key, it.value) }.sortedByDescending { it.timeMs },
            highest = highest,
        )
    }

    // --- habits ---------------------------------------------------------------------------------------------

    private fun habits(
        ledger: ListeningLedger,
        from: Long,
        to: Long,
        nowMs: Long,
        zone: ZoneId,
        clock: LocalClock,
        clockMs: LongArray,
        clockPlays: IntArray,
        sessions: List<LongArray>,
        playDays: java.util.TreeSet<Long>,
        items: Map<String, ItemAcc>,
        songRows: List<SongRow>,
        albumRows: List<AlbumRow>,
        inProgress: Boolean,
    ): Habits {
        val catalog = ledger.catalog

        // sessions
        val lengths = sessions.map { it[1] - it[0] }
        val sessionStats = Sessions(
            count = lengths.size,
            averageMs = if (lengths.isEmpty()) 0L else lengths.sum() / lengths.size,
            longestMs = lengths.maxOrNull() ?: 0L,
        )

        // streaks: the longest inside the period; the current one reaches today, over the whole history
        var longest = 0; var longestStart: Long? = null; var longestEnd: Long? = null
        var run = 0; var runStart = 0L; var previous = Long.MIN_VALUE
        for (day in playDays) {
            if (previous != Long.MIN_VALUE && day == previous + 1) run++ else { run = 1; runStart = day }
            if (run > longest) { longest = run; longestStart = runStart; longestEnd = day }
            previous = day
        }
        val current = if (inProgress) currentStreak(ledger, clock.dayIndex(nowMs), clock) else null
        val streaks = Streaks(
            current = current,
            longest = longest,
            longestStart = longestStart?.let(LocalDate::ofEpochDay),
            longestEnd = longestEnd?.let(LocalDate::ofEpochDay),
        )

        // discoveries: tracks, artists and albums whose first play of all is inside the period
        val discovered = ArrayList<DiscoveredSong>()
        for (row in songRows) {
            val first = ledger.stats(row.item.identity)?.firstPlayMs ?: continue
            if (first in from until to) discovered += DiscoveredSong(row.item, first, row.plays)
        }
        discovered.sortByDescending { it.firstPlayMs }
        val newArtists = ledger.artistFirstPlayMs.filterValues { it in from until to }.keys
            .sortedBy { it.lowercase() }
        val albumsByKey = albumRows.associateBy { it.key }
        val newAlbums = ledger.albumFirstPlayMs.filterValues { it in from until to }.keys
            .mapNotNull { albumsByKey[it] }
            .sortedWith(compareByDescending<AlbumRow> { it.plays }.thenBy { it.title.lowercase() })
        val discoveries = Discoveries(
            songs = discovered.take(DISCOVERY_LIST_SIZE),
            songCount = discovered.size,
            artistCount = newArtists.size,
            albumCount = ledger.albumFirstPlayMs.count { it.value in from until to },
            artists = newArtists.take(DISCOVERY_LIST_SIZE),
            albums = newAlbums.take(DISCOVERY_LIST_SIZE),
        )

        // albums heard complete: every track of the album has a play in the period
        val playedIdentities = items.filterValues { it.plays > 0 }.keys
        val progress = ArrayList<AlbumProgress>()
        for (row in albumRows) {
            val tracks = catalog.tracksByAlbum[row.key] ?: continue
            if (tracks.size < MIN_ALBUM_TRACKS) continue
            val heard = tracks.count { it.identity in playedIdentities }
            if (heard == 0) continue
            progress += AlbumProgress(row.key, row.title, row.artist, row.artUri, heard, tracks.size, row.plays, row.timeMs)
        }
        val byPlays = compareByDescending<AlbumProgress> { it.plays }.thenByDescending { it.timeMs }.thenBy { it.title.lowercase() }
        val albums = AlbumsHeard(
            complete = progress.filter { it.isComplete }.sortedWith(byPlays),
            partial = progress.filter { !it.isComplete }.sortedWith(byPlays).take(PARTIAL_ALBUMS),
        )

        return Habits(
            clock = ListeningClock(clockMs, clockPlays),
            streaks = streaks,
            sessions = sessionStats,
            discoveries = discoveries,
            albums = albums,
            skips = skips(ledger, items, songRows),
        )
    }

    /** The run of days with a play that ends today, or yesterday (a day that has not had its first play yet is not a broken streak). */
    private fun currentStreak(ledger: ListeningLedger, todayIndex: Long, clock: LocalClock): Int {
        val days = java.util.TreeSet<Long>()
        for (o in ledger.occurrences) if (o.isPlay) days += clock.dayIndex(o.endMs)
        var day = when {
            todayIndex in days -> todayIndex
            todayIndex - 1 in days -> todayIndex - 1
            else -> return 0
        }
        var count = 0
        while (day in days) { count++; day-- }
        return count
    }

    private fun skips(ledger: ListeningLedger, items: Map<String, ItemAcc>, songRows: List<SongRow>): SkipStats {
        var recordedStarts = 0
        var skipsTotal = 0
        val songs = ArrayList<SkipRow>()
        val byArtist = HashMap<String, IntArray>()
        for (row in songRows) {
            val acc = items[row.item.identity] ?: continue
            recordedStarts += acc.recordedStarts
            skipsTotal += acc.skips
            if (acc.recordedStarts >= MIN_SONG_SKIP_SAMPLE && acc.skips > 0) {
                songs += SkipRow(row.item.title, row.item.artistLine.ifBlank { null }, row.item, acc.skips, acc.recordedStarts)
            }
            for (artist in row.item.artists) {
                val counts = byArtist.getOrPut(artist) { IntArray(2) }
                counts[0] += acc.skips; counts[1] += acc.recordedStarts
            }
        }
        val artists = byArtist
            .filter { it.value[1] >= MIN_ARTIST_SKIP_SAMPLE && it.value[0] > 0 }
            .map { SkipRow(it.key, null, null, it.value[0], it.value[1]) }
        val order = compareByDescending<SkipRow> { it.rate }.thenByDescending { it.skips }.thenBy { it.name.lowercase() }
        return SkipStats(
            sinceMs = ledger.recordedSinceMs,
            recordedStarts = recordedStarts,
            skips = skipsTotal,
            songs = songs.sortedWith(order).take(SKIP_LIST_SIZE),
            artists = artists.sortedWith(order).take(SKIP_LIST_SIZE),
        )
    }
}
