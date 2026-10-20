package com.lostf1sh.pixelplayeross.data.stats

import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository.PlaybackEvent
import kotlin.math.abs
import kotlin.math.max

/** How an event was tied to a track. */
enum class Basis {
    /** The event recorded the library item it is about. The authoritative case. */
    IDENTITY,

    /** The event recorded only a locator, and the track that locator names now is plausibly the one. */
    LOCATOR,

    /** Tied to no track. Its time still counts; it is never credited to another one. */
    NONE,
}

/** Why an event could not be tied to a track. */
enum class UnattributedReason {
    /** The song was not a library row (a file opened from another app). */
    EXTERNAL,

    /** The locator the event recorded names no track now, and the event recorded no identity to find it by. */
    LOCATOR_ABSENT,

    /** The locator names a track now, but not one that can be the one that was heard (its length or its age says otherwise). */
    LOCATOR_IMPLAUSIBLE,
}

/**
 * One event, with its time put in order and its track decided (B28).
 *
 * [effStartMs] is [startMs] moved forward past anything the previous events already covered, so a time total, a listening
 * clock and the sessions never count the same instant twice (a crossfade overlaps two events by a moment). [item] is null for
 * an event whose identity is known but whose track has no file now, and for an event tied to no track ([basis] NONE).
 */
class Fragment(
    val event: PlaybackEvent,
    val startMs: Long,
    val endMs: Long,
    val effStartMs: Long,
    val identity: String?,
    val item: CatalogItem?,
    val basis: Basis,
    val reason: UnattributedReason?,
) {
    /** The listening time this event owns. */
    val effMs: Long get() = endMs - effStartMs

    val audio: ListenAudioFacts? get() = event.audio
}

/**
 * One play of a track, from its start to the listener leaving it: all the events that carry its occurrence id, or one
 * legacy event (see [PlayRule]).
 *
 * [isPlay], [isCompletion] and [isSkip] are the one definition of those words, and everything that counts them (Stats, the
 * collections, the mixes) reads them from here. [isSkip] needs a *recorded* reason: an occurrence from before B28a that
 * stopped short is [isPlay] false and nothing more, because nobody knows whether the listener left it or the app did.
 */
class Occurrence(
    val identity: String?,
    val item: CatalogItem?,
    val basis: Basis,
    val startMs: Long,
    val endMs: Long,
    val listenedMs: Long,
    val lengthMs: Long,
    val isPlay: Boolean,
    val isCompletion: Boolean,
    val isSkip: Boolean,
    /** The listener's way out was written down (an event of B28a or later). */
    val isRecorded: Boolean,
    val endReason: TrackEndReason?,
    val fragments: List<Fragment>,
)

/** Everything the whole history says about one track, by identity. */
class ItemStats(
    val identity: String,
    val plays: Int,
    val completions: Int,
    val skips: Int,
    /** Occurrences, plays or not. */
    val starts: Int,
    /** Occurrences whose way out was recorded, which is what a skip rate is over. */
    val recordedStarts: Int,
    val listenedMs: Long,
    val firstPlayMs: Long?,
    val lastPlayMs: Long?,
    val firstHeardMs: Long?,
    val lastHeardMs: Long?,
    /** When the last completion and the last (recorded) skip ended; null for none. */
    val lastCompletionMs: Long?,
    val lastSkipMs: Long?,
    /** The highest source rate and bit depth any recorded listen of it carried, or null where none was recorded. */
    val maxRateHz: Int?,
    val maxBits: Int?,
)

/**
 * How much of the listening could be tied to a track, in time and in events.
 *
 *  - [identityMs]: the event recorded its track and the track is in the library.
 *  - [locatorMs]: it recorded a locator only and the track there now plausibly is the one.
 *  - [goneMs]: it recorded its track, which has no file now. Counted, never ranked.
 *  - [unattributedMs]: tied to no track.
 */
class Coverage(
    val identityMs: Long,
    val locatorMs: Long,
    val goneMs: Long,
    val unattributedMs: Long,
    val identityEvents: Int,
    val locatorEvents: Int,
    val goneEvents: Int,
    val unattributedEvents: Int,
) {
    val totalMs: Long get() = identityMs + locatorMs + goneMs + unattributedMs
    val totalEvents: Int get() = identityEvents + locatorEvents + goneEvents + unattributedEvents

    /** The time that is ranked: tied to a track that is in the library. */
    val attributedMs: Long get() = identityMs + locatorMs

    companion object {
        val Empty = Coverage(0, 0, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * Decides which track an event is about (R-04).
 *
 * The identity the event recorded is the answer, and it is never second-guessed by the locator: a locator is the number
 * MediaStore gave the file when the event was written, and a renumber moves it to another file. Only an event that recorded no
 * identity falls back to its locator, and only to a track that can plausibly be the one:
 *
 *  - its length agrees with the length the event recorded (when it recorded one), and
 *  - the library did not first see it after the event happened.
 *
 * Nothing here can notice a silent reassignment of a locator to a different file of the same length that was in the library
 * before; that is why [Basis.LOCATOR] is reported separately wherever attribution is reported.
 */
internal object Attribution {
    private const val DURATION_TOLERANCE_MS = 2_000L
    private const val DURATION_TOLERANCE_FRACTION = 0.02
    private const val FIRST_SEEN_SLACK_MS = 24 * 60 * 60 * 1000L

    class Result(val identity: String?, val item: CatalogItem?, val basis: Basis, val reason: UnattributedReason?)

    private val External = Result(null, null, Basis.NONE, UnattributedReason.EXTERNAL)
    private val Absent = Result(null, null, Basis.NONE, UnattributedReason.LOCATOR_ABSENT)
    private val Implausible = Result(null, null, Basis.NONE, UnattributedReason.LOCATOR_IMPLAUSIBLE)

    fun attribute(event: PlaybackEvent, endMs: Long, catalog: LibraryCatalog): Result {
        val recorded = event.libraryItemId?.takeIf { it.isNotBlank() }
        if (recorded != null) {
            return Result(recorded, catalog.item(recorded), Basis.IDENTITY, null)
        }
        if (event.songId.toLongOrNull() == null) return External
        val item = catalog.itemAtLocator(event.songId) ?: return Absent
        if (!lengthsAgree(event.itemDurationMs, item.lengthMs)) return Implausible
        if (item.firstSeenAtMs > 0L && item.firstSeenAtMs > endMs + FIRST_SEEN_SLACK_MS) return Implausible
        return Result(item.identity, item, Basis.LOCATOR, null)
    }

    private fun lengthsAgree(recordedMs: Long?, libraryMs: Long): Boolean {
        if (recordedMs == null || recordedMs <= 0L || libraryMs <= 0L) return true
        val tolerance = max(DURATION_TOLERANCE_MS, (DURATION_TOLERANCE_FRACTION * max(recordedMs, libraryMs)).toLong())
        return abs(recordedMs - libraryMs) <= tolerance
    }
}

/**
 * The history, read against a library: every event tied to a track, every listen judged once.
 *
 * It is **derived**: nothing in it is stored, and it is rebuilt from the events and the catalog whenever either changes. That
 * is what lets Stats, the automatic collections and the mixes agree (D2): they all read plays, completions, skips and
 * last-played from here, and there is no second counter that can drift from the history.
 *
 * Build cost is one sort and one pass over the events (D9 measures it at two years of history).
 */
class ListeningLedger private constructor(
    val catalog: LibraryCatalog,
    /**
     * Every event, in the order it began. Its ends are not in order: an event that lies inside an earlier one ends before it. That is
     * why a range of time is not searched here but in [timed].
     */
    val fragments: List<Fragment>,
    /**
     * The fragments that own time, in time order: [fragments] without those a longer event already covered (R28-03). What each one
     * owns, [Fragment.effStartMs] to [Fragment.endMs], does not overlap another, so both ends increase along the list and a range
     * is found by binary search. The fragments left out still count as listens of their own: occurrences, plays and skips are judged
     * from [fragments].
     */
    val timed: List<Fragment>,
    /** By the time each ended. */
    val occurrences: List<Occurrence>,
    val itemStats: Map<String, ItemStats>,
    val coverage: Coverage,
    /** When the history begins and when its last listen ended; null for an empty one. */
    val firstMs: Long?,
    val lastMs: Long?,
    /** The first listen whose way out was recorded (B28a), and the first that carried audio facts. */
    val recordedSinceMs: Long?,
    val audioSinceMs: Long?,
) {
    val isEmpty: Boolean get() = fragments.isEmpty()

    /** Whole days from the first listen to the last, 0 for an empty history. This is the distance between events, not how long they were observed. */
    fun spanDays(): Long = if (firstMs == null || lastMs == null) 0L else (lastMs - firstMs) / DAY_MS

    /**
     * Whole days this history has been observed at [nowMs] (R28-04): from the start of the first listen to now, 0 for an empty history.
     *
     * A silence is observed too: the app has been running and recording since the first listen, so three plays on one afternoon are
     * a history of 28 days 28 days later, though [spanDays] is still 0. It starts where the first listen starts and no earlier, because
     * nothing in the history says what was recorded before it (an imported history may be a slice of a longer one). It is never shorter
     * than [spanDays]: a clock that is behind the history (a wrong date) cannot make the history younger than its own events.
     */
    fun observedDays(nowMs: Long): Long {
        val first = firstMs ?: return 0L
        val last = lastMs ?: return 0L
        return (max(nowMs, last) - first).coerceAtLeast(0L) / DAY_MS
    }

    fun stats(identity: String): ItemStats? = itemStats[identity]

    /** The first play of each artist, across every track of theirs the library has, over the whole history. */
    val artistFirstPlayMs: Map<String, Long> by lazy {
        val first = HashMap<String, Long>()
        for (item in catalog.items) {
            val firstPlay = itemStats[item.identity]?.firstPlayMs ?: continue
            for (artist in item.artists) first.merge(artist, firstPlay, ::minOf)
        }
        first
    }

    /** The first play of each named album, over the whole history. */
    val albumFirstPlayMs: Map<String, Long> by lazy {
        val first = HashMap<String, Long>()
        for (item in catalog.items) {
            if (item.album.isBlank()) continue
            val firstPlay = itemStats[item.identity]?.firstPlayMs ?: continue
            first.merge(item.albumKey, firstPlay, ::minOf)
        }
        first
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        val Empty = build(emptyList(), LibraryCatalog.Empty)

        fun build(events: List<PlaybackEvent>, catalog: LibraryCatalog): ListeningLedger {
            if (events.isEmpty()) {
                return ListeningLedger(catalog, emptyList(), emptyList(), emptyList(), emptyMap(), Coverage.Empty, null, null, null, null)
            }
            val fragments = timeOrdered(events, catalog)
            val builders = groupIntoOccurrences(fragments)
            val occurrences = builders.map { it.finish() }.sortedBy { it.endMs }
            return ListeningLedger(
                catalog = catalog,
                fragments = fragments,
                timed = fragments.filter { it.effMs > 0L },
                occurrences = occurrences,
                itemStats = itemStats(fragments, occurrences),
                coverage = coverage(fragments),
                firstMs = fragments.first().startMs,
                lastMs = fragments.maxOf { it.endMs },
                recordedSinceMs = fragments.filter { it.event.endReason != null }.minOfOrNull { it.endMs },
                audioSinceMs = fragments.filter { it.event.audio != null }.minOfOrNull { it.endMs },
            )
        }

        /** Every event as a [Fragment], by start, with the time that overlaps the one before taken off. */
        private fun timeOrdered(events: List<PlaybackEvent>, catalog: LibraryCatalog): List<Fragment> {
            val starts = LongArray(events.size) { events[it].startMillis() }
            val ends = LongArray(events.size) { events[it].endMillis() }
            val order = events.indices.sortedWith(compareBy<Int>({ starts[it] }, { ends[it] }))
            val fragments = ArrayList<Fragment>(events.size)
            var covered = Long.MIN_VALUE
            for (index in order) {
                val event = events[index]
                val start = starts[index]
                val end = ends[index]
                val effStart = if (covered > start) minOf(covered, end) else start
                covered = max(covered, end)
                val who = Attribution.attribute(event, end, catalog)
                fragments += Fragment(event, start, end, effStart, who.identity, who.item, who.basis, who.reason)
            }
            return fragments
        }

        private fun groupIntoOccurrences(fragments: List<Fragment>): List<OccurrenceBuilder> {
            val byId = HashMap<String, OccurrenceBuilder>()
            val lastLegacy = HashMap<String, OccurrenceBuilder>()
            val all = ArrayList<OccurrenceBuilder>()
            for (fragment in fragments) {
                val occurrenceId = fragment.event.occurrenceId
                val builder = if (occurrenceId != null) {
                    byId.getOrPut(occurrenceId) { OccurrenceBuilder().also(all::add) }
                } else {
                    val key = fragment.identity ?: "locator:${fragment.event.songId}"
                    val previous = lastLegacy[key]
                    val continues = previous != null &&
                        fragment.startMs - previous.endMs <= PlayRule.LEGACY_JOIN_GAP_MS &&
                        !previous.reachedPlay()
                    (if (continues) previous!! else OccurrenceBuilder().also(all::add)).also { lastLegacy[key] = it }
                }
                builder.add(fragment)
            }
            return all
        }

        private fun itemStats(fragments: List<Fragment>, occurrences: List<Occurrence>): Map<String, ItemStats> {
            class Acc(val identity: String) {
                var plays = 0
                var completions = 0
                var skips = 0
                var starts = 0
                var recordedStarts = 0
                var listenedMs = 0L
                var firstPlay: Long? = null
                var lastPlay: Long? = null
                var firstHeard: Long? = null
                var lastHeard: Long? = null
                var lastCompletion: Long? = null
                var lastSkip: Long? = null
                var maxRate: Int? = null
                var maxBits: Int? = null
            }

            val accs = HashMap<String, Acc>()
            for (fragment in fragments) {
                val identity = fragment.identity ?: continue
                val acc = accs.getOrPut(identity) { Acc(identity) }
                acc.listenedMs += fragment.effMs
                fragment.audio?.sourceRateHz?.let { acc.maxRate = max(acc.maxRate ?: 0, it) }
                fragment.audio?.sourceBits?.let { acc.maxBits = max(acc.maxBits ?: 0, it) }
            }
            for (occurrence in occurrences) {
                val identity = occurrence.identity ?: continue
                val acc = accs.getOrPut(identity) { Acc(identity) }
                acc.starts++
                if (occurrence.isRecorded) acc.recordedStarts++
                acc.firstHeard = minOf(acc.firstHeard ?: occurrence.startMs, occurrence.startMs)
                acc.lastHeard = max(acc.lastHeard ?: occurrence.endMs, occurrence.endMs)
                if (occurrence.isPlay) {
                    acc.plays++
                    acc.firstPlay = minOf(acc.firstPlay ?: occurrence.endMs, occurrence.endMs)
                    acc.lastPlay = max(acc.lastPlay ?: occurrence.endMs, occurrence.endMs)
                }
                if (occurrence.isCompletion) {
                    acc.completions++
                    acc.lastCompletion = max(acc.lastCompletion ?: occurrence.endMs, occurrence.endMs)
                }
                if (occurrence.isSkip) {
                    acc.skips++
                    acc.lastSkip = max(acc.lastSkip ?: occurrence.endMs, occurrence.endMs)
                }
            }
            return accs.mapValues { (_, a) ->
                ItemStats(
                    identity = a.identity, plays = a.plays, completions = a.completions, skips = a.skips,
                    starts = a.starts, recordedStarts = a.recordedStarts, listenedMs = a.listenedMs,
                    firstPlayMs = a.firstPlay, lastPlayMs = a.lastPlay, firstHeardMs = a.firstHeard, lastHeardMs = a.lastHeard,
                    lastCompletionMs = a.lastCompletion, lastSkipMs = a.lastSkip,
                    maxRateHz = a.maxRate, maxBits = a.maxBits,
                )
            }
        }

        private fun coverage(fragments: List<Fragment>): Coverage {
            var identityMs = 0L; var locatorMs = 0L; var goneMs = 0L; var unattributedMs = 0L
            var identityEvents = 0; var locatorEvents = 0; var goneEvents = 0; var unattributedEvents = 0
            for (fragment in fragments) {
                when {
                    fragment.basis == Basis.NONE -> { unattributedMs += fragment.effMs; unattributedEvents++ }
                    fragment.item == null -> { goneMs += fragment.effMs; goneEvents++ }
                    fragment.basis == Basis.IDENTITY -> { identityMs += fragment.effMs; identityEvents++ }
                    else -> { locatorMs += fragment.effMs; locatorEvents++ }
                }
            }
            return Coverage(identityMs, locatorMs, goneMs, unattributedMs, identityEvents, locatorEvents, goneEvents, unattributedEvents)
        }
    }

    /** Collects the fragments of one occurrence and judges it once they are all in. */
    private class OccurrenceBuilder {
        private val fragments = ArrayList<Fragment>(1)
        private var listenedMs = 0L
        private var knownLengthMs = 0L
        var endMs = Long.MIN_VALUE
            private set

        fun add(fragment: Fragment) {
            fragments += fragment
            listenedMs += fragment.event.durationMs.coerceAtLeast(0L)
            endMs = max(endMs, fragment.endMs)
            val length = fragment.event.itemDurationMs ?: fragment.item?.lengthMs ?: 0L
            knownLengthMs = max(knownLengthMs, length)
        }

        fun reachedPlay(): Boolean = PlayRule.isPlay(listenedMs, knownLengthMs)

        fun finish(): Occurrence {
            val first = fragments.first()
            val last = fragments.maxBy { it.endMs }
            val isPlay = PlayRule.isPlay(listenedMs, knownLengthMs)
            val reason = last.event.endReason
            val recorded = reason != null || last.event.outcome != null
            val endedAtEnd = fragments.any { it.event.outcome == PlaybackOutcome.COMPLETED }
            return Occurrence(
                identity = first.identity,
                item = first.item,
                basis = first.basis,
                startMs = first.startMs,
                endMs = endMs,
                listenedMs = listenedMs,
                lengthMs = knownLengthMs,
                isPlay = isPlay,
                isCompletion = PlayRule.isCompletion(listenedMs, knownLengthMs, endedAtEnd),
                isSkip = !isPlay && reason?.isDeliberateDeparture == true,
                isRecorded = recorded,
                endReason = reason,
                fragments = fragments,
            )
        }
    }
}
