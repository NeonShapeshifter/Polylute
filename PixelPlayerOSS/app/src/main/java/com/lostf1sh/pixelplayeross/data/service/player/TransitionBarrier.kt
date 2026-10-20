package com.lostf1sh.pixelplayeross.data.service.player

/**
 * B27-A1 (review R-01): what a pending crossfade must still be true of before it plays anything.
 *
 * A transition waits: for the incoming player to become READY (up to three seconds), and in the controller for the point
 * where it fires. Whatever happens in that wait (the DAC is unplugged, the user pauses, another app takes the audio focus,
 * the players are rebuilt) used to be invisible to it: when the incoming player reached READY the transition called
 * `play()` on both players again, turning a pause the app had just made into playback, on the speaker if the DAC was
 * gone.
 *
 * So a transition holds a **ticket** and shows it after every suspension. A ticket stops being valid when something
 * that invalidates pending work happens ([invalidate]: the output was lost, a pause arrived, the players were replaced,
 * the transition was cancelled) or when another transition starts ([begin]). The ticket is not the whole answer: the
 * play intent is read again too ([decide]), so a pause that raised no event still stops it.
 *
 * Main thread only, like the engine that owns it. Pure: it holds one counter.
 */
class TransitionBarrier {
    private var generation = 0L

    /** Starts a transition: every ticket handed out before is stale. */
    fun begin(): Long {
        generation += 1
        return generation
    }

    /** Something a pending transition was built on stopped being true: every ticket handed out so far is stale. */
    fun invalidate() {
        generation += 1
    }

    fun isCurrent(ticket: Long): Boolean = ticket == generation

    /** What a transition that wants to start playing may do now. */
    enum class Start {
        /** Everything it was built on is still true, and playback is asked for. */
        PLAY,

        /** The output was lost, a pause arrived or another transition began since the ticket was taken. */
        STALE,

        /** The players are not the ones the transition was built for (a rebuild, a swap). */
        PLAYERS_CHANGED,

        /** The incoming player has nothing queued (cleared by a cancel or a new queue). */
        NOTHING_TO_FADE_TO,

        /** The master player is not asked to play: a manual pause, a focus loss, a lost output, a handoff. */
        NOT_PLAYING,
    }

    companion object {
        /**
         * The permission to play, read **at the moment a transition is about to issue `play()`**. The order is the order of
         * the reasons: a stale ticket is reported as such even when the pause that made it stale also made the player not
         * play.
         *
         * @param ticketCurrent [isCurrent] for the ticket the transition holds
         * @param playersUnchanged the master and the auxiliary player are the ones the transition began with
         * @param incomingHasItems the auxiliary player still has a song queued
         * @param masterPlayWhenReady the master player is asked to play now (a buffering one counts, as for the output-loss
         *        guard); it is false after a manual pause, a focus loss and an output loss
         */
        fun decide(
            ticketCurrent: Boolean,
            playersUnchanged: Boolean,
            incomingHasItems: Boolean,
            masterPlayWhenReady: Boolean,
        ): Start = when {
            !ticketCurrent -> Start.STALE
            !playersUnchanged -> Start.PLAYERS_CHANGED
            !incomingHasItems -> Start.NOTHING_TO_FADE_TO
            !masterPlayWhenReady -> Start.NOT_PLAYING
            else -> Start.PLAY
        }
    }
}
