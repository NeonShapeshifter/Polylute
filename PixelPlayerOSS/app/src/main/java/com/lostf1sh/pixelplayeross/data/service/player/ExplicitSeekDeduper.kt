package com.lostf1sh.pixelplayeross.data.service.player

/**
 * Collapses the same explicit seek arriving twice (AUD-117): the app's own UI notifies the engine
 * and then seeks through the MediaController, whose seek reaches the session player — which now also
 * notifies, so that seeks from other controllers are covered. Forwarding both would run the seek
 * barrier twice for one gesture.
 *
 * Main thread only, like `notifyExternalSeekInitiated`.
 */
class ExplicitSeekDeduper(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private var lastTargetMs = Long.MIN_VALUE
    private var lastAtMs = Long.MIN_VALUE

    fun shouldForward(targetMs: Long, nowMs: Long): Boolean {
        val duplicate = targetMs == lastTargetMs && nowMs - lastAtMs < windowMs
        if (!duplicate) {
            lastTargetMs = targetMs
            lastAtMs = nowMs
        }
        return !duplicate
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 500L
    }
}
