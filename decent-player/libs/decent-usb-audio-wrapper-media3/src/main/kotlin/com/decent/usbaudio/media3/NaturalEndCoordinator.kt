package com.decent.usbaudio.media3

/**
 * AUD-016 / AUD-128: the native engine's natural end, as Media3 is meant to see it.
 *
 * Media3 normally reads into the next item ~10 s before the end while the engine plays (that is
 * where a deferred configure for the next item's rate comes from), so the only thing standing
 * between a finished engine and Media3's own AUTO transition is the position: it froze at the last
 * sample. Here it keeps running from there, so Media3 crosses the item boundary itself — one AUTO,
 * REPEAT only with repeat-one, STATE_ENDED on the last item — and no seek is issued.
 *
 * B6 seeked Media3 to the end of the item instead (measured, AUD-128): that seek disabled and
 * re-enabled the renderer, dropped the read-ahead of the next item and produced a spurious REPEAT,
 * and the deferred 96 kHz config was applied while the 44.1 kHz track was still current.
 *
 * Until Media3 moves on: the ended track gets no new engine, buffers of the next item are dropped
 * (its own engine decodes it from the start), and a configure for it stays deferred until the
 * transition, which is where the sink applies it.
 *
 * B7 on the device (AUD-129/AUD-133): the next item's offset was latched from whichever buffer came
 * after the transition — X seconds into the track, since the read-ahead had been dropped (the UI ran
 * ahead, accumulating from one natural end to the next), or never after a rate change (window -1,
 * StuckPlayerException). The clock only overshoots the boundary by [BOUNDARY_OVERSHOOT_US], so the
 * renderer's monotonic position does not hold the next item's first second still, and buffers stay
 * dropped until the next item's engine exists.
 *
 * B7-fix handed the new engine "the ended item's offset plus its length", which carried any error in
 * the ended item's offset into the next one (AUD-136: a fresh track at +167 s). [onItemTransition]
 * now takes Media3's own renderer offset for the new period; the arithmetic is only the fallback
 * while that report is late.
 *
 * AUD-137 (B7-fix2 on the device): Media3's AUTO can arrive *before* the sink sees the engine's end
 * of stream (35 ms, measured). The natural end marked after it waited for a transition that had
 * already happened, and the watchdog then skipped the track that had just started. Such an AUTO is
 * recorded ([onEarlyTransition]) and resolved by the end of stream within
 * [EARLY_TRANSITION_WINDOW_MS]; the watchdog never advances once the item has changed.
 */
class NaturalEndCoordinator {
    enum class ConfigureAction { DEFER, RECONFIGURE }
    enum class BufferAction { DROP, CREATE_ENGINE_IF_NEEDED, NORMAL }

    /** The new item's window offset after a transition. */
    sealed interface NextOffset {
        /** Not the end of a natural end: the normal latch applies. */
        data object None : NextOffset
        data class FromMedia3(val offsetUs: Long) : NextOffset
        /** Media3 has not reported the new period yet; [fallbackUs] (-1 if none) after a grace. */
        data class AwaitMedia3(val fallbackUs: Long) : NextOffset
    }

    @Volatile
    private var naturalEndPending = false
    @Volatile
    private var finalPositionUs = 0L
    @Volatile
    private var detectedAtMs = 0L
    @Volatile
    private var nextWindowOffsetUs = -1L
    @Volatile
    private var earlyTransitionAtMs = -1L

    val pending: Boolean get() = naturalEndPending

    /**
     * [windowOffsetUs] is the ended item's latched renderer offset (-1 if none). Plus the ended
     * item's length it is only the fallback for the next item's; see [onItemTransition].
     */
    fun onEngineFinished(finalPositionUs: Long, windowOffsetUs: Long, nowMs: Long) {
        this.finalPositionUs = finalPositionUs
        detectedAtMs = nowMs
        nextWindowOffsetUs = if (windowOffsetUs >= 0L) windowOffsetUs + finalPositionUs else -1L
        naturalEndPending = true
    }

    /** Media-item µs: the engine's clock continued past its last sample, just past the boundary. */
    fun positionUs(nowMs: Long): Long =
        finalPositionUs + ((nowMs - detectedAtMs).coerceAtLeast(0L) * 1000L).coerceAtMost(BOUNDARY_OVERSHOOT_US)

    fun onConfigure(engineRunning: Boolean, trackChanged: Boolean): ConfigureAction = when {
        naturalEndPending -> ConfigureAction.DEFER
        engineRunning && !trackChanged -> ConfigureAction.DEFER
        else -> ConfigureAction.RECONFIGURE
    }

    fun onHandleBuffer(engineExists: Boolean, streamingThreadExists: Boolean): BufferAction = when {
        naturalEndPending -> BufferAction.DROP
        !engineExists && !streamingThreadExists -> BufferAction.CREATE_ENGINE_IF_NEEDED
        else -> BufferAction.NORMAL
    }

    /**
     * The next item's engine is being created after Media3's own transition. [media3OffsetUs] is
     * Media3's renderer offset for the new period (null while not reported).
     */
    fun onItemTransition(media3OffsetUs: Long?): NextOffset {
        val wasPending = naturalEndPending
        naturalEndPending = false
        val fallback = nextWindowOffsetUs
        nextWindowOffsetUs = -1L
        return when {
            !wasPending -> NextOffset.None
            media3OffsetUs != null -> NextOffset.FromMedia3(media3OffsetUs)
            else -> NextOffset.AwaitMedia3(fallback)
        }
    }

    /** A position reset the listener asked for replaces the natural end. */
    fun onUserSeek() {
        naturalEndPending = false
        nextWindowOffsetUs = -1L
        earlyTransitionAtMs = -1L
    }

    /** Media3's AUTO/REPEAT arrived while the ended item's engine was still playing its last samples. */
    fun onEarlyTransition(nowMs: Long) {
        earlyTransitionAtMs = nowMs
    }

    val earlyTransitionRecorded: Boolean get() = earlyTransitionAtMs >= 0L

    /**
     * At the end of stream: true (once) when Media3 had already moved on within the window, so the
     * sink opens the current item now instead of waiting for a transition that already happened.
     */
    fun consumeEarlyTransition(nowMs: Long): Boolean {
        val at = earlyTransitionAtMs
        if (at < 0L || nowMs - at > EARLY_TRANSITION_WINDOW_MS) return false
        earlyTransitionAtMs = -1L
        return true
    }

    /** The engine did not end within the window of an early transition: the sink switches normally. */
    fun earlyTransitionExpired(nowMs: Long): Boolean {
        val at = earlyTransitionAtMs
        return at >= 0L && nowMs - at >= EARLY_TRANSITION_WINDOW_MS
    }

    fun clearEarlyTransition() {
        earlyTransitionAtMs = -1L
    }

    /**
     * Media3 should have moved on well within this; if it has not (nothing read ahead and loading
     * never resumed), the sink falls back to advancing itself rather than leave playback stuck —
     * never when [itemChanged]: Media3 did move on, and advancing again skips a track (AUD-137).
     */
    fun overdue(nowMs: Long, itemChanged: Boolean): Boolean =
        !itemChanged && naturalEndPending && nowMs - detectedAtMs >= WATCHDOG_MS

    companion object {
        const val WATCHDOG_MS = 4_000L
        /** How far ahead of the end of stream Media3's own transition is still that end's. */
        const val EARLY_TRANSITION_WINDOW_MS = 500L

        /** AUTO and REPEAT continue the timeline; any other reason re-enabled the renderers. */
        fun isSequential(reason: Int): Boolean = reason == 0 || reason == 1

        /**
         * A configure deferred while the engine played was for the next item: it applies at that
         * transition only. Measured (AUD-137): applied one transition later, to a 48 kHz track.
         */
        fun deferredConfigApplies(deferredForSeq: Long, transitionSeq: Long): Boolean =
            deferredForSeq == transitionSeq
        /** Enough past the boundary for Media3 to cross it; little enough not to show. */
        const val BOUNDARY_OVERSHOOT_US = 250_000L
    }
}
