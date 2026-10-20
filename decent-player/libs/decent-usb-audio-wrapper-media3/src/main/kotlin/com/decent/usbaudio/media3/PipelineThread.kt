package com.decent.usbaudio.media3

/**
 * AUD-143: what the sink does to the ExoPlayer pipeline's streaming thread when the producer is
 * chosen again, and when the USB stream under it is rebuilt.
 *
 * Measured on the HONOR with the USB payload harness: a 24/96 pair played back to back lost 125 440
 * frames (≈1.31 s) at the join, in PROCESSED and with the debug switch alike. Media3 reads the next
 * item while the thread's queue still holds ~16 buffers of the ending one, and at the item
 * transition `startNativeEngineIfFlac` stopped the thread — which clears its queue — *before*
 * deciding that no engine was going to take over, and then built a new thread.
 *
 * The thread owns audio that has been decoded and not yet heard. It is only ever stopped for a
 * reason that makes that audio obsolete: an engine replaces the producer, a seek flushed it, or the
 * stream is gone. Nothing that leaves the pipeline as the producer touches it.
 */
object PipelineThread {

    /** What a producer decision does to the pipeline thread. */
    enum class Action {
        /** The pipeline stays the producer and its thread is running: leave it, queue included. */
        KEEP,
        /** The pipeline is the producer and there is no thread yet. */
        START,
        /** An engine takes over: the thread goes, and the audio it still held with it. */
        REPLACE_BY_ENGINE,
    }

    /**
     * Whether the native engine is the producer for [trackPath]: the signal mode and the debug switch
     * allow it ([engineAllowed]) and the file is a local FLAC. Everything else is the pipeline.
     */
    fun wantsEngine(engineAllowed: Boolean, trackPath: String?): Boolean =
        engineAllowed && trackPath != null && trackPath.lowercase().endsWith(".flac")

    fun actionFor(wantsEngine: Boolean, threadRunning: Boolean): Action = when {
        wantsEngine -> Action.REPLACE_BY_ENGINE
        threadRunning -> Action.KEEP
        else -> Action.START
    }

    /** The engine replaces the pipeline: the thread stops (clearing its queue, as it always did). */
    fun replaceByEngine(current: UsbStreamingThread?): UsbStreamingThread? {
        current?.stop()
        return null
    }

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

    /** What a signal-mode change does to the producers. */
    enum class RealignAction {
        /**
         * The pipeline is the producer before and after (the debug switch on, or a file the engine does
         * not play): the thread and its queue stay, and only the processing of what is queued from now on
         * changes — no stop, no seek, no flush, no new clock.
         */
        KEEP_PIPELINE,

        /** The producers change (engine ↔ pipeline) or there is no pipeline running: the structural move it always was. */
        MOVE_PRODUCER,
    }

    fun realignFor(engineExists: Boolean, threadRunning: Boolean, wantsEngine: Boolean): RealignAction =
        if (!engineExists && threadRunning && !wantsEngine) RealignAction.KEEP_PIPELINE else RealignAction.MOVE_PRODUCER
}
