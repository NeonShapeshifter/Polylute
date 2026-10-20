package com.lostf1sh.pixelplayeross.data.stats

import kotlin.random.Random

/**
 * What one occurrence of a track is, and how a new one is told from the same one going on (B28a).
 *
 * An occurrence is one play of a track: from the moment it starts to the moment the listener leaves it. The tracker
 * gives each one an id and writes it on every event of that play, so a play that reaches the history in more than
 * one piece is still one play.
 *
 * | what happens                                       | occurrence | how it is seen                                                                  |
 * |----------------------------------------------------|------------|---------------------------------------------------------------------------------|
 * | a new item (next, previous, auto advance, a pick)  | new        | `onMediaItemTransition`, whatever its reason                                    |
 * | a repeat-one loop                                  | new        | `onMediaItemTransition` with the repeat reason, once per loop                   |
 * | a replay from the start of the same item           | new        | a seek that lands at the start after at least [MINIMUM_LISTENED_MS] were heard  |
 * | the track played to its end and is started again   | new        | the session was closed at the end; the next play opens another                  |
 * | a pause, resume, focus loss or output loss         | same       | the session is not closed by a state change                                     |
 * | a seek inside the item                             | same       | a seek that does not land at the start, or that nudges, or that is early        |
 * | a player rebuild or swap in the middle of the song | same       | the same song is still current, so the session goes on                          |
 * | a crossfade to another song                        | new        | the incoming song is another one                                                |
 *
 * A process that dies mid-song and resumes it starts a new occurrence: the tracker lives in memory and the id is not
 * persisted, so the restart cannot know it is the same play. The history then holds two occurrences a few minutes
 * apart, which is what `PlayRule`'s gap-join already reads as one playback.
 */
internal object ListenOccurrence {

    /** A seek counts as "back to the start" when it lands this close to the start of the item. */
    const val REPLAY_START_WINDOW_MS = 2_000L

    /** ...and when it comes from at least this far past it, so a nudge at the very start is not a replay. */
    const val REPLAY_MIN_JUMP_MS = 5_000L

    /** Less than this was heard in the occurrence: nothing has been recorded yet, so a restart of it is the same one. */
    const val MINIMUM_LISTENED_MS = 5_000L

    /** A fresh id: 64 random bits as at most 13 base-36 characters. Ids only need to differ; they are never parsed. */
    fun newId(): String = java.lang.Long.toUnsignedString(Random.nextLong(), 36)

    fun isReplayFromStart(listenedMs: Long, fromPositionMs: Long, toPositionMs: Long): Boolean =
        listenedMs >= MINIMUM_LISTENED_MS &&
            toPositionMs <= REPLAY_START_WINDOW_MS &&
            fromPositionMs - toPositionMs >= REPLAY_MIN_JUMP_MS
}
