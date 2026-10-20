package com.lostf1sh.pixelplayeross.data.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/*
 * What the Stats screen, Home's card and the "Top N" read (B28). Plain data: no strings, no formatting, no Android. Every label
 * the screen shows is made from these by the screen, in the language of the app.
 */

/** One song of a ranking. [plays] and [timeMs] are for the period; both are on every row. */
class SongRow(val item: CatalogItem, val plays: Int, val timeMs: Long, val skips: Int = 0)

/** One artist of a ranking. A play of a song credits every artist the song names. An empty [name] is "unknown artist". */
class ArtistRow(val name: String, val plays: Int, val timeMs: Long, val songs: Int)

/** One album of a ranking. An empty [title] is "unknown album". */
class AlbumRow(
    val key: String,
    val title: String,
    val artist: String,
    val artUri: String?,
    val plays: Int,
    val timeMs: Long,
    val songs: Int,
)

/** One genre of a ranking. An empty [name] is "unknown genre". */
class GenreRow(val name: String, val plays: Int, val timeMs: Long, val artists: Int)

/**
 * Every ranking of a period, whole. Ordering is applied here, by the mode, to the same rows, so switching Plays/Time re-orders
 * what is already computed: it cannot change a number, and it cannot race a load.
 */
class Rankings(
    private val songRows: List<SongRow>,
    private val artistRows: List<ArtistRow>,
    private val albumRows: List<AlbumRow>,
    private val genreRows: List<GenreRow>,
) {
    private val songsBy = HashMap<StatsRankMode, List<SongRow>>()
    private val artistsBy = HashMap<StatsRankMode, List<ArtistRow>>()
    private val albumsBy = HashMap<StatsRankMode, List<AlbumRow>>()
    private val genresBy = HashMap<StatsRankMode, List<GenreRow>>()

    val songCount: Int get() = songRows.size
    val artistCount: Int get() = artistRows.size
    val albumCount: Int get() = albumRows.size
    val genreCount: Int get() = genreRows.size

    @Synchronized
    fun songs(mode: StatsRankMode): List<SongRow> = songsBy.getOrPut(mode) {
        songRows.sortedWith(rankingComparator(mode, { it.plays }, { it.timeMs }, { it.item.title }))
    }

    @Synchronized
    fun artists(mode: StatsRankMode): List<ArtistRow> = artistsBy.getOrPut(mode) {
        artistRows.sortedWith(rankingComparator(mode, { it.plays }, { it.timeMs }, { it.name }))
    }

    @Synchronized
    fun albums(mode: StatsRankMode): List<AlbumRow> = albumsBy.getOrPut(mode) {
        albumRows.sortedWith(rankingComparator(mode, { it.plays }, { it.timeMs }, { it.title }))
    }

    @Synchronized
    fun genres(mode: StatsRankMode): List<GenreRow> = genresBy.getOrPut(mode) {
        genreRows.sortedWith(rankingComparator(mode, { it.plays }, { it.timeMs }, { it.name }))
    }

    companion object {
        val Empty = Rankings(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * The headline numbers of a period.
 *
 * [listenedMs] is all the listening, tied to a track or not. [plays] counts every play, and [songsPlayed] the tracks that had
 * at least one; [avgPlaysPerSong] is plays over exactly those (K-03: the old average divided all plays by the rows it happened
 * to show). [avgPerDayMs] is over the calendar days of the period so far, not only the days with listening.
 */
class Totals(
    val listenedMs: Long,
    val plays: Int,
    val songsPlayed: Int,
    val songsHeard: Int,
    val activeDays: Int,
    val days: Long,
    val avgPerDayMs: Long,
    val avgPlaysPerSong: Double?,
) {
    companion object {
        val Empty = Totals(0, 0, 0, 0, 0, 1, 0, null)
    }
}

/**
 * What share of the listening the biggest tracks take, over the **whole** population (K-03).
 *
 * The population is every library track heard in the period and [denominatorMs] is the time of exactly those, so the shares
 * do not depend on how many rows a screen shows or on the mode it ranks by. It is in time, in both modes.
 */
class Concentration(
    val population: Int,
    val denominatorMs: Long,
    val top1Ms: Long,
    val top3Ms: Long,
    val top10Ms: Long,
) {
    fun share(ms: Long): Float = if (denominatorMs <= 0L) 0f else (ms.toDouble() / denominatorMs.toDouble()).toFloat()

    val top1: Float get() = share(top1Ms)
    val top3: Float get() = share(top3Ms)
    val top10: Float get() = share(top10Ms)

    companion object {
        val Empty = Concentration(0, 0, 0, 0, 0)
    }
}

enum class TimelineUnit { HOUR, DAY, MONTH }

/** [startMs] inclusive to [endMs] exclusive. */
class TimelineBucket(val startMs: Long, val endMs: Long, val timeMs: Long, val plays: Int)

class Timeline(val unit: TimelineUnit, val buckets: List<TimelineBucket>) {
    fun peakByTime(): TimelineBucket? = buckets.filter { it.timeMs > 0L }.maxByOrNull { it.timeMs }

    companion object {
        val Empty = Timeline(TimelineUnit.DAY, emptyList())
    }
}

/** The kinds of unattributed history a screen can name. */
class AttributionNote(val coverage: Coverage) {
    /** Listening tied to no track. */
    val unattributedMs: Long get() = coverage.unattributedMs

    /** Listening of tracks the library knows but has no file for now. */
    val goneMs: Long get() = coverage.goneMs

    /** Listening tied to a track only through the locator the event recorded. */
    val locatorMs: Long get() = coverage.locatorMs
}

// --- audio -------------------------------------------------------------------------------------------------

/**
 * A format as heard: source rate and bit depth and the file's codec. Bits and codec are null where not recorded.
 *
 * Build it with [recorded]: raw PCM (`audio/raw`, which B28a wrote as the codec of events on Direct) is not a codec, so it reads as
 * a codec that was not recorded and its rows group with the other events of the same rate and depth that have none.
 */
data class AudioFormatKey(val rateHz: Int?, val bits: Int?, val codec: String?) {
    /** The codec as people name it. */
    val codecLabel: String? get() = codec?.let(::codecLabelOf)

    /** A rank for "highest resolution": the product of the two, where each is known. */
    val resolution: Long get() = (rateHz ?: 0).toLong() * (bits ?: 16).toLong()

    companion object {
        /** The key of what an event recorded: a raw PCM codec is read as none. */
        fun recorded(rateHz: Int?, bits: Int?, codec: String?): AudioFormatKey =
            AudioFormatKey(rateHz, bits, codec?.takeUnless(::isRawPcmMime))

        fun codecLabelOf(mime: String): String {
            val m = mime.lowercase(Locale.ROOT).removePrefix("audio/").removePrefix("x-")
            return when {
                m == "flac" -> "FLAC"
                m == "mpeg" || m == "mp3" -> "MP3"
                m == "mp4" || m.startsWith("mp4a") || m == "aac" || m == "m4a" -> "AAC / M4A"
                m == "alac" -> "ALAC"
                m == "wav" || m == "wave" -> "WAV"
                m == "ogg" || m == "vorbis" -> "Vorbis"
                m == "opus" -> "Opus"
                m == "aiff" -> "AIFF"
                m.isBlank() -> mime
                else -> m.uppercase(Locale.ROOT)
            }
        }
    }
}

class RouteTime(val route: ListenRoute, val timeMs: Long)

class DeviceTime(val name: String, val timeMs: Long)

class FormatTime(val key: AudioFormatKey, val timeMs: Long)

/** The highest-resolution track heard, by source rate × bit depth. [item] is null when the track has no file now. */
class HighestResolution(val item: CatalogItem?, val key: AudioFormatKey)

/**
 * Time by the audio that carried it (D5), from what each event recorded.
 *
 * An event from before B28a has no audio facts and is [notRecordedMs]: it is never guessed. An event whose facts changed while it
 * played says how much of it they cover (`share`); that part is [recordedMs] and the rest is [mixedMs]. The three add up to the
 * period's listening time. [sinceMs] is when the audio data begins in the history (ever), so the screen can say from which day it
 * exists. Signal and bit-perfect split [recordedMs] only.
 */
class AudioStats(
    val sinceMs: Long?,
    val recordedMs: Long,
    val mixedMs: Long,
    val notRecordedMs: Long,
    val purityMs: Long,
    val resonanceMs: Long,
    /** Recorded time on a route that has no signal mode (the Android route). */
    val noSignalMs: Long,
    val bitPerfectMs: Long,
    val notBitPerfectMs: Long,
    val unknownBitPerfectMs: Long,
    val routes: List<RouteTime>,
    val devices: List<DeviceTime>,
    val formats: List<FormatTime>,
    val highest: HighestResolution?,
) {
    val hasData: Boolean get() = recordedMs > 0L

    companion object {
        val Empty = AudioStats(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList(), null)
    }
}

// --- habits ------------------------------------------------------------------------------------------------

/** Hour of day × day of week. Index `weekday * 24 + hour`, Monday = 0. Time is listening; plays belong to the hour they ended in. */
class ListeningClock(val timeMs: LongArray, val plays: IntArray) {
    fun time(day: DayOfWeek, hour: Int): Long = timeMs[(day.value - 1) * 24 + hour]

    fun plays(day: DayOfWeek, hour: Int): Int = plays[(day.value - 1) * 24 + hour]

    val maxTimeMs: Long get() = timeMs.maxOrNull() ?: 0L
    val maxPlays: Int get() = plays.maxOrNull() ?: 0

    /** The hour with the most listening, over all days, or null with none. */
    fun busiestHour(): Int? = (0 until 24)
        .map { hour -> hour to (0 until 7).sumOf { timeMs[it * 24 + hour] } }
        .filter { it.second > 0L }
        .maxByOrNull { it.second }?.first

    /** The day of the week with the most listening, or null with none. */
    fun busiestDay(): DayOfWeek? = (0 until 7)
        .map { day -> day to (0 until 24).sumOf { timeMs[day * 24 + it] } }
        .filter { it.second > 0L }
        .maxByOrNull { it.second }?.let { DayOfWeek.of(it.first + 1) }

    companion object {
        val Empty = ListeningClock(LongArray(7 * 24), IntArray(7 * 24))
    }
}

/**
 * Runs of days with at least one play. [current] is the run that reaches today (or yesterday: today may not have started yet) and
 * is null for a period that ended in the past, where "current" is meaningless; [longest] is within the period.
 */
class Streaks(val current: Int?, val longest: Int, val longestStart: LocalDate?, val longestEnd: LocalDate?)

/** Listening sessions: a run of listening with no silence of more than 30 minutes. A length is first start to last end. */
class Sessions(val count: Int, val averageMs: Long, val longestMs: Long)

class DiscoveredSong(val item: CatalogItem, val firstPlayMs: Long, val plays: Int)

/** What was played for the first time in the period. */
class Discoveries(
    val songs: List<DiscoveredSong>,
    val songCount: Int,
    val artistCount: Int,
    val albumCount: Int,
    val artists: List<String>,
    val albums: List<AlbumRow>,
)

class AlbumProgress(
    val key: String,
    val title: String,
    val artist: String,
    val artUri: String?,
    val tracksPlayed: Int,
    val tracks: Int,
    val plays: Int,
    val timeMs: Long,
) {
    val isComplete: Boolean get() = tracks > 0 && tracksPlayed >= tracks
}

class AlbumsHeard(val complete: List<AlbumProgress>, val partial: List<AlbumProgress>) {
    val completeCount: Int get() = complete.size
}

class SkipRow(val name: String, val artist: String?, val item: CatalogItem?, val skips: Int, val starts: Int) {
    val rate: Float get() = if (starts <= 0) 0f else skips.toFloat() / starts.toFloat()
}

/**
 * Skip rates, from the listens whose way out was recorded (B28a and later). [sinceMs] is when that starts; before it nobody
 * knows whether a short listen was the listener's choice, so it is not counted as a skip and not in the denominator.
 */
class SkipStats(val sinceMs: Long?, val recordedStarts: Int, val skips: Int, val songs: List<SkipRow>, val artists: List<SkipRow>) {
    val hasData: Boolean get() = recordedStarts > 0

    companion object {
        val Empty = SkipStats(null, 0, 0, emptyList(), emptyList())
    }
}

class Habits(
    val clock: ListeningClock,
    val streaks: Streaks,
    val sessions: Sessions,
    val discoveries: Discoveries,
    val albums: AlbumsHeard,
    val skips: SkipStats,
) {
    companion object {
        val Empty = Habits(
            ListeningClock.Empty,
            Streaks(null, 0, null, null),
            Sessions(0, 0, 0),
            Discoveries(emptyList(), 0, 0, 0, emptyList(), emptyList()),
            AlbumsHeard(emptyList(), emptyList()),
            SkipStats.Empty,
        )
    }
}

/**
 * Everything the Stats screen shows for one period.
 *
 * [rankings] are whole; the screen shows the head of each and "see all" the rest. [audio] and [habits] are null in an overview
 * (Home's card), which asks only for the numbers it draws.
 */
class StatsReport(
    val period: StatsPeriod,
    val fromMs: Long?,
    val toMs: Long,
    val inProgress: Boolean,
    val generatedAtMs: Long,
    val totals: Totals,
    val coverage: Coverage,
    val rankings: Rankings,
    val concentration: Concentration,
    val timeline: Timeline,
    val audio: AudioStats?,
    val habits: Habits?,
    /** When the history begins, for "since" notes; null for none. */
    val historyStartMs: Long?,
) {
    val note: AttributionNote get() = AttributionNote(coverage)

    /** The period has no listening at all. */
    val isEmpty: Boolean get() = totals.listenedMs <= 0L && totals.plays == 0
}

/** One "Top N" (D7): the songs, and the summary of the same period from the same functions. */
class TopNReport(
    val period: StatsPeriod.Months,
    val size: Int,
    val mode: StatsRankMode,
    val rows: List<SongRow>,
    /** How many songs had a play in the period: fewer than [size] means the list is short because the history is. */
    val eligible: Int,
    val report: StatsReport,
) {
    val isShort: Boolean get() = eligible < size
}
