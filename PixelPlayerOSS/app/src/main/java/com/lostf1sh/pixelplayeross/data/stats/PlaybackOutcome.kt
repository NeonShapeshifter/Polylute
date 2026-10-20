package com.lostf1sh.pixelplayeross.data.stats

import androidx.media3.common.Player

/**
 * What actually happened in one listening session.
 *
 * This is the layer the rest of Phase 12 reasons from. A single integer `playCount` cannot answer
 * *"was this finished?"* or *"did they leave early?"*, and those are different questions with
 * different answers — so one session resolves to exactly one of these.
 */
enum class PlaybackOutcome {
    /** Heard all the way through: a natural end, or 90% of the track. */
    COMPLETED,

    /** Heard meaningfully, but not finished. A real listen, not a skip. */
    PLAYED,

    /** Left early, and the departure was deliberate. */
    SKIPPED,

    /**
     * Ambiguous — under the meaningful threshold without evidence of intent.
     *
     * This is the honest answer for a session we cannot characterise, and it is deliberately not
     * called a skip: most of what lands here is an interruption, not a decision.
     */
    NEUTRAL,
}

/**
 * How each outcome moves the persisted aggregates.
 *
 * Kept on the outcome itself so the mapping exists in exactly one place. The earlier risk was the
 * one the brief calls out — Home, the queue and any future mix each deciding privately what counts
 * as a play — and a single definition is what makes the counters comparable between them.
 *
 * Note what NEUTRAL does *not* do: it increments nothing. A session we could not characterise
 * contributes listening time and history, but it must not inflate a play count, because the whole
 * point of the neutral class is that we do not know.
 */
val PlaybackOutcome.incrementsPlays: Boolean
    get() = this == PlaybackOutcome.COMPLETED || this == PlaybackOutcome.PLAYED

val PlaybackOutcome.incrementsCompletions: Boolean
    get() = this == PlaybackOutcome.COMPLETED

val PlaybackOutcome.incrementsSkips: Boolean
    get() = this == PlaybackOutcome.SKIPPED

/**
 * Why a session stopped.
 *
 * Only some of these are the user's doing, and the distinction is the difference between an
 * intentional skip and the app being killed. Everything the user did not choose is
 * [UNKNOWN] or an explicit interruption, and can never produce a [PlaybackOutcome.SKIPPED].
 */
enum class TrackEndReason {
    /** The track ran to its end and playback advanced on its own. */
    NATURAL_END,

    /** The user pressed Next. */
    USER_NEXT,

    /** The user pressed Previous and went to the previous track. */
    USER_PREVIOUS,

    /**
     * The user started the track over: Previous pressed late in it, or the position dragged back to the start.
     *
     * It is the listener's own action, so the pass it closes is no longer "unknown how it ended" (B28), but it is not a
     * departure: they did not leave the track, they asked for it again, so it is not evidence of a skip
     * ([isDeliberateDeparture] stays false).
     */
    USER_REPLAY,

    /** The user picked a different track directly. */
    USER_SELECTED_OTHER,

    /** The queue was replaced from outside the transport controls. */
    QUEUE_REPLACED,

    /** Process death, service restart, error, output loss, reboot — not a decision. */
    INTERRUPTED,

    /** No evidence either way. */
    UNKNOWN,
    ;

    /**
     * Whether this reason is evidence of a deliberate departure.
     *
     * The narrowness is the point. A process death, a DAC disconnect, a playback error, a reboot or
     * an externally replaced queue all stop a track, and none of them mean the listener skipped it.
     * Counting them as skips would poison the skip signal with the app's own failures.
     */
    val isDeliberateDeparture: Boolean
        get() = this == USER_NEXT || this == USER_PREVIOUS || this == USER_SELECTED_OTHER
}

/**
 * Translates Media3's `onMediaItemTransition` reason into a [TrackEndReason].
 *
 * This exists as a pure function, and in one place, because the mapping is where skip data is
 * easiest to get wrong. `AUTO` means the previous track genuinely played out. `SEEK` means the
 * transport moved because someone touched it. Everything else — a replaced playlist, a repeat
 * boundary, a reason this build does not know — is deliberately [TrackEndReason.UNKNOWN] rather
 * than an optimistic guess, because an unknown that stays neutral is harmless and a wrong skip
 * count is not.
 *
 * Note the honest limitation: crossfade swaps the underlying player instead of emitting an
 * auto-transition, so a crossfaded track boundary can arrive here as something other than AUTO.
 * Those sessions fall to UNKNOWN and, if short, classify as NEUTRAL rather than being mislabelled
 * a skip. Losing a little completion signal is strictly better than inventing skip signal.
 *
 * Restated as the rule it is, because it is easy to erode: **a player swap is never evidence that a
 * track ended.** A crossfaded boundary therefore never reports NATURAL_END and never reports a
 * deliberate departure, so the outcome is decided solely by how much of the track was actually
 * heard — a completion that appears here was measured. The cost is bounded and understood: the
 * session is finalised at fade *start*, so up to one fade length of genuinely-heard audio goes
 * uncredited. On any ordinary track the completion threshold absorbs that; `CrossfadeOutcomeTest`
 * pins both halves of the contract so neither can drift silently.
 */
fun trackEndReasonFromTransition(reason: Int): TrackEndReason = when (reason) {
    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> TrackEndReason.NATURAL_END
    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> TrackEndReason.NATURAL_END
    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> TrackEndReason.USER_NEXT
    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> TrackEndReason.QUEUE_REPLACED
    else -> TrackEndReason.UNKNOWN
}

/**
 * Decides what one finished listening session was.
 *
 * Pure, so every threshold and every awkward case is testable without a device or a clock.
 *
 * ## Why the thresholds scale with duration
 *
 * A fixed bar is wrong at both ends of a real library. Four minutes is most of a 45-second
 * interlude, and eight minutes is a third of a 15-minute piece — so a fixed requirement would
 * either call every short track a skip or demand more than half of a long one. The meaningful
 * threshold is therefore `min(4 min, 50% of duration)`, which is generous to short tracks and
 * proportional for long ones.
 *
 * ## Why interruption is not a skip
 *
 * See [TrackEndReason.isDeliberateDeparture]. Only deliberate departures can skip.
 */
object PlaybackOutcomeClassifier {

    /** At or above this fraction of the track, it counts as finished. */
    const val COMPLETION_FRACTION = 0.90

    /** Half the track counts as a meaningful listen — unless the cap below is smaller. */
    const val MEANINGFUL_FRACTION = 0.50

    /** The meaningful threshold never exceeds this, however long the track is. */
    const val MEANINGFUL_CAP_MS = 4 * 60 * 1000L

    /** Below this, nothing is recorded at all — it is noise, not listening. */
    const val MINIMUM_LISTEN_MS = 5_000L

    /**
     * The listened time a track must reach to count as meaningfully heard.
     *
     * `min(4 min, 50% of duration)`: 22.5 s for a 45-second interlude, 2 min for a 4-minute song,
     * and still 4 min for a 15-minute piece rather than eight.
     */
    fun meaningfulThresholdMs(durationMs: Long): Long {
        if (durationMs <= 0L) return MEANINGFUL_CAP_MS
        return minOf((durationMs * MEANINGFUL_FRACTION).toLong(), MEANINGFUL_CAP_MS)
    }

    /** The listened time at which a track counts as finished. */
    fun completionThresholdMs(durationMs: Long): Long {
        if (durationMs <= 0L) return Long.MAX_VALUE
        return (durationMs * COMPLETION_FRACTION).toLong()
    }

    /**
     * How much of the track was heard, capped at 1.0.
     *
     * Bounded because repeated seeking and replaying can legitimately accumulate more listened time
     * than the track is long. That is real listening and it still counts toward total listening
     * time — but it is not 400% of a song, and down-stream "was this finished?" must not be able to
     * read it that way.
     */
    fun completionFraction(listenedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (listenedMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Classifies one finished session.
     *
     * [listenedMs] is accumulated *playing* time, so pauses and seeks have already been excluded
     * upstream and the answer here is about content, not about wall-clock.
     */
    fun classify(
        listenedMs: Long,
        durationMs: Long,
        endReason: TrackEndReason,
    ): PlaybackOutcome {
        if (listenedMs < MINIMUM_LISTEN_MS) {
            // Too short to characterise. Still not a skip: something was heard, we just cannot say
            // what it was, and inventing intent from five seconds of audio is how skip data rots.
            return PlaybackOutcome.NEUTRAL
        }

        val reachedEnd = endReason == TrackEndReason.NATURAL_END
        if (reachedEnd || listenedMs >= completionThresholdMs(durationMs)) {
            return PlaybackOutcome.COMPLETED
        }

        if (listenedMs >= meaningfulThresholdMs(durationMs)) {
            return PlaybackOutcome.PLAYED
        }

        // Under the meaningful threshold. Only a deliberate departure makes this a skip; an
        // interruption stays NEUTRAL because the listener did not choose to leave.
        return if (endReason.isDeliberateDeparture) {
            PlaybackOutcome.SKIPPED
        } else {
            PlaybackOutcome.NEUTRAL
        }
    }
}
