package com.decent.usbaudio.media3

/**
 * The ExoPlayer-pipeline producer's clock, in the renderer's domain (AUD-110).
 *
 * Position = the timestamp of the first buffer this pipeline period actually plays, plus the frames
 * written *since that buffer*. Two defects lived here, both measured on the HONOR while moving the EQ:
 *
 * - `framesWritten` is cumulative on the stream (only a flush zeroes it, and the stream is cached
 *   across tracks and mode changes), so every PROCESSED period after the first started with the
 *   earlier periods' frames already counted — a forward jump of whole periods per toggle.
 * - After PRISTINE, the renderer's decoder sits where LoadControl stopped it (buffered 6.3 s with
 *   the DAC at 12.26 s). The realign seek to the player's "current" position is a no-op in Media3,
 *   so the first buffers carry that stale timestamp. Latching it put the clock seconds behind the
 *   audible position; anchoring the next PRISTINE period on that clock corrupted the item mapping.
 *
 * So a realign arms the clock with its anchor, buffers from before it are dropped (the renderer
 * decodes forward to it at CPU speed), and the first buffer at the anchor latches the start together
 * with the frame count at that moment.
 *
 * Not thread-safe: driven from the playback thread, like the rest of the sink's mapping.
 */
class PipelinePositionClock {
    var isLatched: Boolean = false
        private set

    private var startUs = 0L
    private var framesAtStart = 0L

    /** Renderer-domain position the next period must start at, or -1 for "whatever comes first". */
    private var anchorRendererUs = NO_ANCHOR

    /** Re-establish the start from the next accepted buffer, dropping buffers before [anchorRendererUs]. */
    fun arm(anchorRendererUs: Long) {
        isLatched = false
        this.anchorRendererUs = anchorRendererUs
    }

    /** The pipeline is no longer the producer (PRISTINE), or a flush repositioned the renderer. */
    fun disarm() {
        isLatched = false
        anchorRendererUs = NO_ANCHOR
    }

    /**
     * Offers the next buffer. False means it predates the realign anchor and must be dropped, not
     * played: it is audio the DAC already played through the native engine.
     *
     * [framesAheadUs] is the audio the streaming thread still has to write before this buffer's
     * first frame (AUD-143). Latched at `framesWritten`, a buffer that is really [framesAheadUs]
     * behind what is playing would put the clock that far ahead of the audible position — for the
     * whole item, since the offset is never revisited. The start is moved back by that much instead:
     * the clock continues the ending item's timeline until the buffer's own first frame is written.
     */
    fun accept(presentationTimeUs: Long, framesWritten: Long, framesAheadUs: Long = 0L): Boolean {
        if (isLatched) return true
        if (anchorRendererUs >= 0 && presentationTimeUs < anchorRendererUs - STALE_TOLERANCE_US) return false
        startUs = presentationTimeUs - framesAheadUs.coerceAtLeast(0L)
        framesAtStart = framesWritten
        anchorRendererUs = NO_ANCHOR
        isLatched = true
        return true
    }

    /** Renderer-domain position for [framesWritten] (the stream's cumulative count). */
    fun positionUs(framesWritten: Long, sampleRate: Int): Long =
        startUs + (framesWritten - framesAtStart).coerceAtLeast(0L) * 1_000_000L / sampleRate

    /** The latched start, for logs. */
    val startPositionUs: Long
        get() = startUs

    private companion object {
        const val NO_ANCHOR = -1L

        /** Half a typical 4096-frame buffer at 44.1 kHz: a buffer straddling the anchor is kept. */
        const val STALE_TOLERANCE_US = 50_000L
    }
}
