package com.decent.usbaudio.media3

/** Renderer clock derived from Media3's first PCM timestamp and the USB writer's frame count. */
class PipelinePositionClock {
    var isLatched: Boolean = false
        private set

    private var startUs = 0L
    private var framesAtStart = 0L

    /** A renderer reset re-establishes the timestamp and cumulative-frame baseline. */
    fun disarm() {
        isLatched = false
    }

    /**
     * Latches the first buffer and the amount of PCM queued ahead of it.
     *
     * [framesAheadUs] is the audio the streaming thread still has to write before this buffer's
     * first frame (AUD-143). Latched at `framesWritten`, a buffer that is really [framesAheadUs]
     * behind what is playing would put the clock that far ahead of the audible position — for the
     * whole item, since the offset is never revisited. The start is moved back by that much instead:
     * the clock continues the ending item's timeline until the buffer's own first frame is written.
     */
    fun accept(presentationTimeUs: Long, framesWritten: Long, framesAheadUs: Long = 0L): Boolean {
        if (isLatched) return true
        startUs = presentationTimeUs - framesAheadUs.coerceAtLeast(0L)
        framesAtStart = framesWritten
        isLatched = true
        return true
    }

    /** Renderer-domain position for [framesWritten] (the stream's cumulative count). */
    fun positionUs(framesWritten: Long, sampleRate: Int): Long =
        startUs + (framesWritten - framesAtStart).coerceAtLeast(0L) * 1_000_000L / sampleRate

    /** The latched start, for logs. */
    val startPositionUs: Long
        get() = startUs

}
