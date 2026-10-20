package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * A next or previous that arrives while a queue is still being loaded must not be lost (B18-R2).
 *
 * Loading a list starts the song that was asked for together with a few of its neighbours and attaches the rest afterwards.
 * Around that there are moments when the player has nowhere to go: before the new queue is in the player (the old one is
 * still there and about to be replaced), or after a burst of skips used up the neighbours while the rest is being built. A
 * `seekToNext` with no next item is a silent no-op, and one made against the queue that is about to be replaced is undone with
 * it: the tap on the screen, the headset button, the notification, the lock screen simply vanished. While a load is in flight
 * such a skip is **held** here instead, and applied, in the order it was asked, when the queue can take it.
 *
 * **A sequence, not a sum** (B19-S3). B18 kept `held += direction`, so a previous followed by a next at the head of the list
 * cancelled out and the listener stayed on the first song, where in order it is "restart, then move to the second". What waits
 * is the list of actions as they were asked, and [settle] plays them back in that order against the queue as it is, with
 * Media3's own rule at each step: a next with a next item moves, and so does a previous; a previous with nothing before it
 * restarts the song; a next at the real end with repeat off does nothing; repeat all wraps. An action that cannot be applied
 * *yet*, because songs are still to be attached on that side, stops the replay there: it and everything after it keep waiting,
 * so the order is kept. An action that can never move (the real end of the list) is consumed, never kept for ever.
 *
 * One holder for the whole process (it lives on the engine): skips reach the player from the app's controller, the
 * notification, a headset and the widget, and all of them pass through the session's player, which asks this first. Main thread
 * only, like the player. No player in here, only the waiting actions and a clock.
 */
class QueueLoadSkipHold(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var loading = false

    /** The new queue is not in the player yet: every skip is held, because the player's current queue is about to go. */
    private var queueNotInPlayer = false
    private var beganAtMs = 0L

    /** Songs that are still to be attached before / after what the player holds (so a skip that finds none that way waits). */
    private var songsBefore = false
    private var songsAfter = false

    /** The skips waiting, in the order they were asked: +1 is "next", -1 is "previous". At most [MAX_WAITING], the latest. */
    private val waiting = ArrayDeque<Int>()

    /** A queue load is in flight. */
    val isLoading: Boolean get() = loading

    /** How many skips are waiting. For tests and logs. */
    val heldCount: Int get() = waiting.size

    /** The skips waiting, in the order they were asked (+1 next, -1 previous). For tests and logs. */
    val held: List<Int> get() = waiting.toList()

    /**
     * A previous may still have somewhere to go once the load is done: the new queue is not in the player yet, or songs are still
     * to be attached before what it holds. The app's own "previous" asks this to tell a song that is there to go back to from a
     * restart (B18-R2).
     */
    val previousMayStillHaveSomewhereToGo: Boolean get() = loading && (queueNotInPlayer || songsBefore)

    /** Which load the hold is for: a load that was replaced by another must not end, or settle, the one that replaced it. */
    private var generation = 0L

    /**
     * A load starts. Whatever was held belonged to the queue it replaces; until [queueInPlayer], every skip is held. Returns the
     * token of this load, which [queueInPlayer], [isCurrent] and [end] check.
     */
    fun begin(): Long {
        generation++
        loading = true
        queueNotInPlayer = true
        songsBefore = false
        songsAfter = false
        beganAtMs = clockMs()
        waiting.clear()
        return generation
    }

    /** This load is still the one the hold is for. */
    fun isCurrent(token: Long): Boolean = loading && token == generation

    /**
     * The first songs of the new queue are in the player: from here only a skip that has nowhere to go is held. [songsBefore] and
     * [songsAfter] say whether more songs are still to be attached before / after them, which tells "nowhere to go yet" (wait for
     * the attach) from "nowhere to go ever" (the real end of the list, which Media3 deals with).
     */
    fun queueInPlayer(token: Long, songsBefore: Boolean = false, songsAfter: Boolean = false) {
        if (token != generation) return
        queueNotInPlayer = false
        this.songsBefore = songsBefore
        this.songsAfter = songsAfter
    }

    /**
     * Asks whether a skip must wait. [direction] is +1 for next and -1 for previous, [hasNeighbour] says the player holds an item
     * that way right now (repeat mode ignored: an item it would wrap to is not a neighbour), [repeatAll] is the player's repeat
     * mode. True: the skip is held and the caller must not move the player. False: carry on as usual.
     *
     * A skip that could move still waits behind skips that could not, so the order the listener asked in is the order they happen.
     */
    fun hold(direction: Int, hasNeighbour: Boolean, repeatAll: Boolean = false): Boolean {
        if (!loading) return false
        if (clockMs() - beganAtMs > MAX_LOAD_MS) {
            // A load that never reported back must not swallow skips for good.
            end()
            return false
        }
        val waits = queueNotInPlayer || waiting.isNotEmpty() || (!hasNeighbour && mustWaitForSongs(direction, repeatAll))
        if (!waits) return false
        waiting.addLast(direction)
        // A listener who mashes the button does not grow this without end: the latest actions are the ones that matter.
        while (waiting.size > MAX_WAITING) waiting.removeFirst()
        return true
    }

    private fun songsComing(direction: Int): Boolean = if (direction > 0) songsAfter else songsBefore

    /**
     * A skip with no neighbour waits when songs are still to come that way. With repeat all it also waits for songs that are to come
     * the *other* way: it wraps to the opposite end of the list, which is not in the player yet (the block's own other end is not it).
     */
    private fun mustWaitForSongs(direction: Int, repeatAll: Boolean): Boolean =
        songsComing(direction) || (repeatAll && (songsBefore || songsAfter))

    /** What [settle] needs to know about the queue and how to move on it. */
    interface Steps {
        /** The player holds an item that way right now; repeat mode is ignored (an item it would wrap to is not one). */
        fun hasNeighbour(direction: Int): Boolean

        /** Media3's own `hasNextMediaItem` / `hasPreviousMediaItem`: repeat all makes both true on a non-empty queue. */
        fun canMove(direction: Int): Boolean

        /** The player's repeat mode is repeat all. */
        val repeatsAll: Boolean

        /** `seekToNextMediaItem` / `seekToPreviousMediaItem`. */
        fun move(direction: Int)

        /** Restarts the song that is playing. */
        fun restart()
    }

    /**
     * Plays back what is waiting, in the order it was asked, against the queue as it is now. Returns how many actions were consumed.
     *
     * An action whose side still has songs to come and that has no neighbour yet stops the replay: it and what follows stay for the
     * next attach. Every other action is consumed here the way Media3 consumes it: it moves (also wrapping, with repeat all), or a
     * previous restarts the song (nothing before it), or a next does nothing (the real end, repeat off).
     */
    fun settle(steps: Steps): Int {
        var consumed = 0
        while (waiting.isNotEmpty()) {
            val direction = waiting.first()
            if (!steps.hasNeighbour(direction) && mustWaitForSongs(direction, steps.repeatsAll)) break
            waiting.removeFirst()
            consumed++
            when {
                steps.canMove(direction) -> steps.move(direction)
                direction < 0 -> steps.restart()
                else -> Unit
            }
        }
        return consumed
    }

    /** The user chose a destination (a row, a seek to an index): skips that were waiting are stale. */
    fun dropHeld() {
        waiting.clear()
    }

    /** The load [token] is over (whole queue attached, or abandoned): nothing more can be held. A load that was replaced is not. */
    fun end(token: Long) {
        if (token == generation) end()
    }

    private fun end() {
        loading = false
        queueNotInPlayer = false
        songsBefore = false
        songsAfter = false
        waiting.clear()
    }

    companion object {
        /** No queue takes this long to load; past it the hold gives up instead of holding skips forever. */
        const val MAX_LOAD_MS = 15_000L

        /** The most skips that wait: the latest ones. */
        const val MAX_WAITING = 32
    }
}

/**
 * Whether this player holds an item next to the one that is playing, in [direction] (+1 / -1), in the order it plays them now
 * (shuffle order when shuffle is on). Repeat mode is ignored on purpose: with repeat all `hasNextMediaItem()` is true on the last
 * song of a half-loaded queue because it would wrap to the first, and that is not the song the listener asked for.
 */
fun Player.hasQueueNeighbour(direction: Int): Boolean {
    val timeline = currentTimeline
    val index = currentMediaItemIndex
    if (timeline.isEmpty || index == C.INDEX_UNSET) return false
    val neighbour = if (direction > 0) {
        timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
    } else {
        timeline.getPreviousWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
    }
    return neighbour != C.INDEX_UNSET
}
