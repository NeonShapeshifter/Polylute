package com.lostf1sh.pixelplayeross.data.service.player

/**
 * Holds playback while an Android-path decision has no Android USB output to play to (AUD-003).
 *
 * With the DAC attached and the app having just returned it to Android, the platform takes a moment
 * to re-register the USB output; if `snd-usb-audio` does not rebind at all (measured on the HONOR
 * after a forced claim), it never does. In both cases Android would play to the speaker or a dead
 * route. This pauses instead, resumes if the route appears within [timeoutMs], and otherwise leaves
 * the listener paused. Shaped like [AutoDirectHandoff]: its own pause is consumed, and any other
 * play/pause is the user taking control.
 *
 * Not thread-safe: driven from the engine's main-thread route loop.
 */
class AndroidUsbRouteGuard(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    enum class Step { NONE, PAUSE, RESUME }

    var isHolding: Boolean = false
        private set

    private var resumeOwed = false
    private var ownPausePending = false
    private var startMs = 0L

    /** Applies one route decision. [blocked] is `AudioRouteDecision.androidOutputBlocked`. */
    fun step(blocked: Boolean, playing: Boolean, nowMs: Long): Step {
        if (blocked) {
            if (isHolding || !playing) return Step.NONE
            isHolding = true
            resumeOwed = true
            ownPausePending = true
            startMs = nowMs
            return Step.PAUSE
        }
        if (!isHolding) return Step.NONE
        val resume = resumeOwed
        clear()
        return if (resume) Step.RESUME else Step.NONE
    }

    /** True when the `playWhenReady == false` being reported is the pause this guard issued. */
    fun consumeOwnPause(): Boolean {
        if (!ownPausePending) return false
        ownPausePending = false
        return true
    }

    /** A play or pause that was not ours: the listener decides from here. */
    fun onUserTookControl() {
        if (isHolding) clear()
    }

    /**
     * The DAC itself is gone (B25-S1). What this hold waited for will never appear, and resuming now would play out of the
     * speaker: the pause it issued stays a pause. Like [AutoDirectHandoff.onDeviceLost].
     */
    fun onDeviceLost() {
        if (isHolding) clear()
    }

    /** Ends a hold that waited too long, leaving playback paused. True if one was ended. */
    fun expire(nowMs: Long): Boolean {
        if (!isHolding || nowMs - startMs < timeoutMs) return false
        clear()
        return true
    }

    private fun clear() {
        isHolding = false
        resumeOwed = false
        ownPausePending = false
    }

    companion object {
        /** Long enough for `snd-usb-audio` to probe and the audio service to add the device. */
        const val DEFAULT_TIMEOUT_MS = 3_000L
    }
}
