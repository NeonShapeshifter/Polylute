package com.lostf1sh.pixelplayeross.data.service.player

/**
 * What happens to a shuffle request while the master holds a *window* of the queue and a crossfade is running (B18-R4).
 *
 * A shuffle flip is a flag, except on a master that holds a window cut for the old order (a queue longer than the
 * crossfade's 200 items, after a fade): that one is first widened back to the whole queue, and the widening cannot happen
 * while a fade is in flight. So a request made then is **deferred**, and what is deferred is **one intent**: the last request
 * wins and is applied once when the fade ends. B17 kept one retry listener per request, each carrying its own value, and
 * compared with the player's current flag *before* looking at what was pending: shuffle ON, fade running, OFF (deferred),
 * ON ("already on", ignored), fade ends, the old OFF runs, final state OFF though the last request was ON.
 *
 * The comparison with the current flag only means something when nothing is pending, so it happens here, at the moment a
 * request is applied. Pure: the player is reached through [Host], so the whole sequence runs on the JVM.
 */
internal class ShuffleRequestGate(private val host: Host) {

    interface Host {
        /** The master's own shuffle flag. */
        val shuffleOn: Boolean

        /** The master holds a window of the queue, not the whole queue. */
        val windowed: Boolean

        /** A crossfade is in flight. */
        val transitionRunning: Boolean

        /** Gives the master the whole queue again around the song that is playing. False when it could not. */
        fun widen(): Boolean

        /**
         * Writes the flag (a real change; the gate has already compared). [freshOrder]: the request was a listener switching
         * shuffle on, so classic mode deals the queue again; a queue load, which brings its own order, says false.
         */
        fun setFlag(enabled: Boolean, freshOrder: Boolean)

        /** Starts calling [settle] when a fade ends (and when one is cancelled). Called once per deferral, never twice in a row. */
        fun watchTransitionEnd()

        /** Stops it. Called exactly once per [watchTransitionEnd]. */
        fun unwatchTransitionEnd()

        /** Runs [action] after the current callback returns (the main handler). */
        fun later(action: () -> Unit)
    }

    private var wanted: Boolean? = null
    private var wantedFreshOrder = true
    private var widenOwed = false
    private var watching = false
    private var applying = false

    /** Something is waiting for a fade to end. */
    val hasPending: Boolean get() = wanted != null || widenOwed

    /** The intent that will be applied when the fade ends, null when none. For tests and logs. */
    val pendingTarget: Boolean? get() = wanted

    /**
     * What shuffle is *going to be*: the request that is waiting for the fade if there is one, else the master's own flag. This is
     * what a toggle has to flip. The flag alone is the answer to "what is it now", and while a request waits it is stale: ON, a
     * fade running, a tap (OFF waits), a second tap read the flag (still ON), asked for OFF again, and the last of two taps won
     * as OFF (B19-S2).
     */
    val intent: Boolean get() = wanted ?: host.shuffleOn

    /** A request to turn shuffle on or off ([freshOrder]: see [Host.setFlag]). */
    fun request(enabled: Boolean, freshOrder: Boolean = true) {
        if (host.windowed && host.transitionRunning) {
            wanted = enabled
            wantedFreshOrder = freshOrder
            watch()
            return
        }
        // Nothing needs to wait, and this request is newer than whatever did.
        clear()
        apply(enabled, freshOrder)
    }

    /**
     * The player's flag changed. A change the gate did not make (a head unit's standard `setShuffleModeEnabled`) on a windowed
     * master leaves a window cut for the other order: it is the newest intent and has already taken effect, so anything
     * deferred is superseded and the queue is widened once the fade, if any, is over.
     */
    fun onFlagChanged() {
        if (applying) return
        if (!host.windowed) return
        wanted = null
        widenOwed = true
        host.later { settle() }
    }

    /** A fade ended or was cancelled, or a flip needs the window widened: applies what is waiting, if a fade is no longer in the way. */
    fun settle() {
        if (!hasPending) return
        if (host.transitionRunning) {
            watch()
            return
        }
        val target = wanted
        val fresh = wantedFreshOrder
        val owed = widenOwed
        clear()
        when {
            target != null && target != host.shuffleOn -> apply(target, fresh)
            owed && host.windowed -> host.widen()
        }
    }

    private fun apply(enabled: Boolean, freshOrder: Boolean) {
        if (host.shuffleOn == enabled) return
        // A queue that could not be widened still changes its flag, as it did before; the host logs the failure.
        if (host.windowed) host.widen()
        applying = true
        try {
            host.setFlag(enabled, freshOrder)
        } finally {
            applying = false
        }
    }

    private fun watch() {
        if (watching) return
        watching = true
        host.watchTransitionEnd()
    }

    private fun clear() {
        wanted = null
        wantedFreshOrder = true
        widenOwed = false
        if (watching) {
            watching = false
            host.unwatchTransitionEnd()
        }
    }
}
