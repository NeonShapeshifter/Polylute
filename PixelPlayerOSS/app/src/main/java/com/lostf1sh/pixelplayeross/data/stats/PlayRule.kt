package com.lostf1sh.pixelplayeross.data.stats

/** What the statistics rank by: one choice for every ranking on the screen. */
enum class StatsRankMode {
    /** The most plays first; time listened breaks a tie. The default: a song played four times beats one played twice for longer. */
    PLAYS,

    /** The most time listened first; plays break a tie. */
    TIME,
}

/**
 * What counts as a play, judged **per occurrence** (B28), and the one definition Stats, the collections and the mixes share.
 *
 * An *occurrence* is one play of a track from its start to the listener leaving it (see [ListenOccurrence]). A listen that
 * reaches the history in several events (a pause that outlived the process, a stop and a resume) is still one occurrence, and
 * its events carry the same `occurrenceId`.
 *
 *  - **A play is an occurrence that was listened to for at least half the track or 4 minutes, whichever is less**
 *    ([PlaybackOutcomeClassifier.meaningfulThresholdMs], the number the player applied when a listen ended). The listening of the
 *    *fragments of one occurrence* adds up; the listening of different occurrences never does. Four 30-second skips of a
 *    four-minute song are four occurrences of 30 s, and none of them is a play.
 *  - *Listened*, not "was open": paused time is not in the events, and a seek adds none.
 *  - A short song needs half of itself (a 90 s song, 45 s); a long one needs 4 minutes. A track whose length is not known
 *    needs the 4-minute cap.
 *  - Repeat-one gives one occurrence per loop, and each loop that reaches the threshold is a play.
 *  - A play is a **completion** when its listening reached 90 % of the track, or when an event of it ended at the track's
 *    own end.
 *
 * ## Events written before occurrences were recorded
 *
 * They have no `occurrenceId`, so what was one listen cannot be read from them. The rule is conservative and the same everywhere:
 * **each such event is its own occurrence, except that an event of the same track which begins within [LEGACY_JOIN_GAP_MS] of
 * the end of the previous one, when that previous one had not yet reached the play threshold on its own, continues it.** That is
 * the only shape in which one listen is known to have been cut (the cut is the app's, not the listener's), and it cannot turn
 * separate skips into a play: separated by anything else, or by more than three seconds, they stay separate. The history file does
 * not keep a position, so "resumes where it left off" cannot be checked for these.
 */
internal object PlayRule {

    /** Two legacy events of one track this close together are one listen if the first was short of a play. */
    const val LEGACY_JOIN_GAP_MS = 3_000L

    /** An occurrence listened to this much of the track counts as heard to its end. */
    const val COMPLETION_FRACTION = PlaybackOutcomeClassifier.COMPLETION_FRACTION

    fun thresholdMs(trackLengthMs: Long): Long = PlaybackOutcomeClassifier.meaningfulThresholdMs(trackLengthMs)

    fun isPlay(listenedMs: Long, trackLengthMs: Long): Boolean = listenedMs >= thresholdMs(trackLengthMs)

    fun isCompletion(listenedMs: Long, trackLengthMs: Long, endedAtTrackEnd: Boolean): Boolean {
        if (!isPlay(listenedMs, trackLengthMs)) return false
        return endedAtTrackEnd || listenedMs >= PlaybackOutcomeClassifier.completionThresholdMs(trackLengthMs)
    }
}

/** Orders a ranking by [mode], the other measure breaking a tie, then [name] so the order is the same on every run. */
internal fun <T> rankingComparator(
    mode: StatsRankMode,
    plays: (T) -> Int,
    timeMs: (T) -> Long,
    name: (T) -> String,
): Comparator<T> {
    val byPlays = compareByDescending<T> { plays(it) }
    val byTime = compareByDescending<T> { timeMs(it) }
    val primary = if (mode == StatsRankMode.PLAYS) byPlays.then(byTime) else byTime.then(byPlays)
    return primary.thenBy { name(it).lowercase() }
}
