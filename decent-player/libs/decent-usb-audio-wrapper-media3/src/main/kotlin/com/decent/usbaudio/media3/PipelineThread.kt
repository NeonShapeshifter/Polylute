package com.decent.usbaudio.media3

/** Queue lifetime, tail draining and processing fades for the Media3 producer. */
object PipelineThread {
    /**
     * The pipeline is the producer: the running thread continues with its queue untouched, or one is
     * started. [create] returns a started thread.
     */
    fun keepOrStart(current: UsbStreamingThread?, create: () -> UsbStreamingThread): UsbStreamingThread =
        current ?: create()

    enum class TailOutcome {
        /** Everything queued was written and the end of stream marker taken. */
        PLAYED_OUT,
        /** Nothing queued: a flush (a seek, a skip) had already emptied it. */
        NOTHING_QUEUED,
        /** Paused: nothing is being heard, and a paused thread would not drain. */
        NOT_PLAYING,
        /** The marker could not be queued. */
        REFUSED,
        /** The budget ran out; the caller rebuilds anyway, as before. */
        TIMED_OUT,
    }

    /**
     * A new stream shape (rate, depth, channels) is about to replace the USB stream, which stops the
     * thread and discards what it holds. When the thread still holds the ending item's tail — a
     * natural track change; a seek or a skip flushed it first — that tail is played out before the
     * rebuild, through the end-of-stream path that already existed for the end of the queue: a marker
     * behind the last buffer, then the packetizer's frame-aligned residual.
     *
     * Blocks the caller for at most the queued audio's own duration plus [SLACK_MS] (bounded by
     * [MAX_WAIT_MS]): the thread is paced by the DAC, so that is how long it takes to be heard.
     */
    fun playOutTail(thread: UsbStreamingThread, playing: Boolean, sampleRate: Int): TailOutcome {
        if (!playing) return TailOutcome.NOT_PLAYING
        val queued = thread.queuedFrames()
        if (queued <= 0L) return TailOutcome.NOTHING_QUEUED
        if (!thread.queueEndOfStream()) return TailOutcome.REFUSED
        val tailMs = if (sampleRate > 0) queued * 1000L / sampleRate else 0L
        val budgetMs = (tailMs + SLACK_MS).coerceIn(SLACK_MS, MAX_WAIT_MS)
        return if (thread.awaitEndOfStream(budgetMs)) TailOutcome.PLAYED_OUT else TailOutcome.TIMED_OUT
    }

    /** What the DAC's ring and the residual drain need beyond the queued audio itself. */
    const val SLACK_MS = 500L
    const val MAX_WAIT_MS = 8_000L

    // ── B9: gestures and mode changes with the pipeline as the producer ─────────────────────────

    /**
     * How long a stream rebuild waits for the thread to write the fade-out of a flush that came right
     * before it (a manual skip to another rate, B9-3). The ramp is 8 ms of audio, written as soon as the
     * ring has room, so this is a ceiling for a stalled thread, not a delay the skip normally pays.
     */
    const val FADE_WAIT_MS = 300L

    /** Length of the crossfade that joins a PRISTINE ↔ PROCESSED change to the audio already queued. */
    const val PROCESSING_CROSSFADE_MS = 20

    fun crossfadeFrames(sampleRate: Int): Int = (sampleRate.toLong() * PROCESSING_CROSSFADE_MS / 1000L).toInt().coerceAtLeast(1)

}
