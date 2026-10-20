package com.decent.usbaudio.media3

import android.os.Process
import android.util.Log
import com.decent.usbaudio.UsbAudioStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Dedicated thread for USB audio streaming, decoupled from ExoPlayer's
 * render thread.
 *
 * Supports two buffer types:
 * - [FloatBuffer]: float PCM from FFmpeg (MP3, AAC, FLAC via float path)
 * - [RawBuffer]: raw integer PCM from libFLAC (zero float, true bit-perfect)
 *
 * A pause, a seek and a skip never cut the ring dry (B9, the engine's AUD-107/123/140 for the pipeline):
 * what is in the ring plays out and the real continuation — the next buffer this thread holds — is ramped
 * to zero behind it, the DAC is fed silence until the first buffer of the new position, and that buffer
 * fades in. The ramps and the silence are the USB output's (native, `pcmApplyGainRamp`, `seekFeedSilenceStep`);
 * this thread only decides *which buffers* are the continuation, in what order, and when to stop feeding.
 *
 * @param usbStream The native USB audio stream to write to.
 *                  Must only be accessed from the USB thread.
 * @param channelCount Interleaved channels of what is queued (the stream's), which is what a
 *                  buffer's frames are counted in for [queuedFrames].
 */
class UsbStreamingThread(
    private val usbStream: PcmWriter,
    initiallyPaused: Boolean = false,
    private val channelCount: Int = 2,
    private val writerPriority: WriterPriority = ProcessAudioPriority,
) {
    constructor(usbStream: UsbAudioStream, initiallyPaused: Boolean = false, channelCount: Int = 2) :
        this(UsbAudioStreamWriter(usbStream), initiallyPaused, channelCount)

    /**
     * The scheduling the writer asks for, applied **by the writer thread to itself** before its loop (B23-P2).
     *
     * The ring holds ~80 ms; a writer that is runnable but not running for longer than that starves the DAC, and
     * `Thread.MAX_PRIORITY` is only nice -8. The owner's wake of 2026-10-14 had the writer 175 ms late.
     */
    interface WriterPriority {
        /** Called on the writer thread, once. Returns what was granted, for the one log line at the thread's start. */
        fun raiseCurrentThread(): String
    }

    /** What the thread writes to; [UsbAudioStream] in production, a fake in tests. */
    interface PcmWriter {
        fun write(pcmBuffer: FloatArray)
        fun writeRaw(pcmBuffer: ByteArray, encoding: Int)
        val framesWritten: Long
        fun flush()
        fun drainEndOfStream(): Boolean

        /** The thread is about to idle on purpose; the DAC running dry is then not a starvation (AUD-140). */
        fun markIdle() {}

        // ── The pause / seek / skip barrier (B9) ────────────────────────────────────────────────
        // A writer without one (an older stream, a fake) has no fades: every gesture is then a plain
        // stop or drop, as it was.

        /**
         * B12a-6: the host paused; any thread. A writer in the middle of a block acts on it between URBs ([consumePauseApplied]).
         * A writer without the barrier ignores it, and the pause is handled at the block's end as before.
         */
        fun requestPause() {}

        /** The host resumed before the pause was acted on. */
        fun cancelPauseRequest() {}

        /** True once when a pause was acted on inside a block (see [UsbAudioStream.consumePauseApplied]). Writer thread only. */
        fun consumePauseApplied(): Boolean = false

        /** A pause: true when the next buffers written are the continuation to ramp down (see [UsbAudioStream.beginPauseFade]). */
        fun beginPauseFade(): Boolean = false

        /** A seek or a skip: one of [UsbAudioStream.SEEK_NONE], [UsbAudioStream.SEEK_FADE], [UsbAudioStream.SEEK_SILENCE]. */
        fun beginSeek(): Int = UsbAudioStream.SEEK_NONE

        /** True while a continuation fade-out is armed and incomplete. */
        val isFadingOut: Boolean get() = false

        /** The continuation ran out before the ramp finished. */
        fun endFadeOut() {}

        /** Leaving a pause: what the pause kept plays, faded in. */
        fun resumeAfterPause() {}

        /** One step of the silence feed; false when the stream failed. */
        fun feedSilenceStep(): Boolean = true
    }

    private class UsbAudioStreamWriter(private val stream: UsbAudioStream) : PcmWriter {
        override fun write(pcmBuffer: FloatArray) = stream.write(pcmBuffer)
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) = stream.writeRaw(pcmBuffer, encoding)
        override val framesWritten: Long get() = stream.framesWritten
        override fun flush() = stream.flush()
        override fun drainEndOfStream(): Boolean = stream.drainEndOfStream()
        override fun markIdle() = stream.markIdle()
        override fun requestPause() = stream.requestPause()
        override fun cancelPauseRequest() = stream.cancelPauseRequest()
        override fun consumePauseApplied(): Boolean = stream.consumePauseApplied()
        override fun beginPauseFade(): Boolean = stream.beginPauseFade()
        override fun beginSeek(): Int = stream.beginSeek()
        override val isFadingOut: Boolean get() = stream.isFadingOut
        override fun endFadeOut() = stream.endFadeOut()
        override fun resumeAfterPause() = stream.resumeAfterPause()
        override fun feedSilenceStep(): Boolean = stream.feedSilenceStep()
    }

    /**
     * `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)`: nice -19, what Android's own audio callback threads
     * run at. It is a nice value in the normal scheduler (SCHED_OTHER), not a realtime class: **no SCHED_FIFO**, so a
     * bug here can make the writer greedy but cannot lock the CPU. Refused (no permission, an OEM that clamps it):
     * the thread keeps the Java `MAX_PRIORITY` it was started with and the line says so.
     */
    object ProcessAudioPriority : WriterPriority {
        override fun raiseCurrentThread(): String {
            val tid = Process.myTid()
            val before = Process.getThreadPriority(tid)
            return try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val after = Process.getThreadPriority(tid)
                "tid=$tid nice $before -> $after (asked ${Process.THREAD_PRIORITY_URGENT_AUDIO}, URGENT_AUDIO, SCHED_OTHER not FIFO)"
            } catch (e: RuntimeException) {
                "tid=$tid nice $before, URGENT_AUDIO refused (${e.javaClass.simpleName}: ${e.message}); keeping Java MAX_PRIORITY"
            }
        }
    }

    companion object {
        private const val TAG = "UsbStreamingThread"
        private const val QUEUE_CAPACITY = 128
        private const val POLL_TIMEOUT_MS = 100L

        /** A write is paced by the DAC (≤ ~100 ms for a buffer); a flush never waits longer than this for one. */
        private const val WRITE_WAIT_MS = 2_000L

        /**
         * How long a seek keeps feeding silence while the thread is paused. Media3 pauses the sink around a seek
         * (AUD-140: the player pauses while seeking; in the device log BUFFERING follows the flush), so the pause
         * is normally there while the decoder is still working on the new position, and the engine feeds for the
         * length of its own seek (20-550 ms measured). Without a bound a seek during a pause would be a wake-up
         * every millisecond for as long as the pause lasts, which is what AUD-036 removed.
         */
        private const val PAUSED_SILENCE_CAP_MS = 600L
    }

    /** Sealed class for type-safe audio buffer queueing. */
    private sealed class AudioBuffer {
        /**
         * [processAtWrite] is the host's DSP stage, run when the buffer is taken to be written rather
         * than when it was queued (AUD-111): the queue holds ~1.5 s, and a stage run at enqueue made
         * every EQ change wait behind audio already equalised with the old settings.
         *
         * [generation] is the thread's `streamGeneration` when the buffer was queued: a buffer of an
         * older generation belongs to a position a flush has left, and is never written as audio (B9).
         */
        class FloatBuffer(val data: FloatArray, val processAtWrite: ((FloatArray) -> Unit)?, val generation: Long) : AudioBuffer()
        class RawBuffer(val data: ByteArray, val encoding: Int, val generation: Long) : AudioBuffer()
        class EndOfStream(val generation: Long) : AudioBuffer()

        /** Wakes a writer waiting on an empty queue: a flush has a seek owed. It carries nothing. */
        object Wake : AudioBuffer()
    }

    /**
     * A flush's request to the writer: the old position's buffers, in order, which the ring continues
     * with. Only the first of them is ramped (8 ms); the rest is dropped with the position.
     */
    private class SeekRequest(val continuation: List<AudioBuffer>)

    private val audioQueue = ArrayBlockingQueue<AudioBuffer>(QUEUE_CAPACITY)

    /**
     * Counts the audio writes this thread starts and finishes, so a flush can wait for the one in progress
     * (its frames are counted before the pipeline clock latches the first buffer of the new position). A
     * lock held across the write would do the same, but a thread that re-takes a monitor within microseconds
     * of releasing it can keep a waiting one out for as long as it likes; a counter and a notify cannot.
     * Only this thread touches the stream's submit state, so nothing else needs excluding.
     */
    private val writeMonitor = Object()
    private var writesStarted = 0L
    private var writesDone = 0L
    /** Paused, the thread waits on this until resumed or stopped instead of polling (AUD-036). */
    private val pauseLock = Object()

    /** Guards [seekRequest], [streamGeneration] and the gesture flags: flush() on the playback thread, the writer. */
    private val gestureLock = Object()
    /** A seek the writer has not started yet (its continuation is what the ring really continues with). */
    private var seekRequest: SeekRequest? = null
    /** Lock-free mirror of `seekRequest != null`, for the writer's loops. */
    @Volatile
    private var seekOwed = false
    /** The writer is ramping a continuation (taken a seek, or armed a pause fade) and has not finished. */
    @Volatile
    private var fadeInProgress = false
    /** Writer thread only: audio of a new generation polled while a seek was owed, written after it. */
    private var carry: AudioBuffer? = null
    /** Writer thread only: old-position buffers polled before the seek was seen; they come first in its continuation. */
    private val staleInHand = ArrayList<AudioBuffer>()

    @Volatile
    private var running = false
    @Volatile
    private var paused = initiallyPaused
    private var thread: Thread? = null
    private var dropCount = 0
    @Volatile
    private var endOfStreamQueued = false
    @Volatile
    private var endOfStreamDrained = false
    /** The worker has taken the end-of-stream marker, whether or not the residual drain succeeded. */
    @Volatile
    private var endOfStreamMarkerTaken = false
    private val endOfStreamLock = Object()
    /** Frames of the buffer being written (taken from the queue, not yet counted in `framesWritten`). */
    @Volatile
    private var inFlightFrames = 0L
    @Volatile
    private var streamGeneration = 0L

    fun start() {
        running = true
        endOfStreamQueued = false
        endOfStreamDrained = false
        endOfStreamMarkerTaken = false
        Log.i(TAG, "USB streaming thread started (paused=$paused)")
        thread = Thread({
            // B23-P2: the writer raises itself, from inside, before it can submit anything. Logged once.
            Log.i(TAG, "USB streaming thread priority: ${writerPriority.raiseCurrentThread()}")
            while (running) {
                // A flush left a seek owed (a seek, a skip, an item start): the ring plays out, the
                // continuation is ramped down and the DAC is kept fed with silence (B9-2). Before the
                // pause, as in the engine: a seek delivered while paused still has to act on the ring.
                val seek = takeSeekRequest()
                if (seek != null) {
                    performSeek(seek)
                    continue
                }
                // B12a-6: `paused`, or a pause the stream acted on inside the block it was writing (its fade is
                // already queued; the user may even have resumed since, and the kept rest of the block must replay).
                if (paused || usbStream.consumePauseApplied()) {
                    performPause()
                    continue
                }
                val qBefore = audioQueue.size
                // Queue depth is the lead indicator for a dropout: this thread only ever starves
                // after the producer has stopped keeping up, so the high-water mark and the count
                // of near-empty writes say whether the pipeline had headroom long before an
                // audible gap appears.
                DirectPathTelemetry.maxQueueDepth.accumulateAndGet(qBefore, ::maxOf)
                DirectPathTelemetry.lastQueueDepth = qBefore
                val held = carry
                carry = null
                when (val buf = held ?: audioQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    is AudioBuffer.FloatBuffer, is AudioBuffer.RawBuffer -> {
                        if (isStale(buf)) {
                            // Polled just before a flush emptied the queue: it is the old position's next
                            // audio, the first of the continuation the ring is about to be ended with.
                            staleInHand.add(buf)
                            continue
                        }
                        if (seekOwed) {
                            // A flush landed after the check at the top: this buffer is the new position's
                            // and waits for the seek to be done first, so no old audio plays after it.
                            carry = buf
                            continue
                        }
                        writeBuffer(buf)
                        if (qBefore <= 1) {
                            DirectPathTelemetry.lateBuffers.incrementAndGet()
                            Log.w(TAG, "Queue nearly empty: $qBefore before write")
                        }
                    }
                    is AudioBuffer.EndOfStream -> {
                        endOfStreamDrained = buf.generation == streamGeneration && usbStream.drainEndOfStream()
                        Log.i(TAG, "Natural EOS residual drain success=$endOfStreamDrained")
                        synchronized(endOfStreamLock) {
                            endOfStreamMarkerTaken = true
                            endOfStreamLock.notifyAll()
                        }
                    }
                    AudioBuffer.Wake -> Unit
                    null -> {
                        // Nothing to send for a whole poll period while the thread is running: the
                        // producer is behind the DAC's consumption rate. This is the dropout.
                        DirectPathTelemetry.underruns.incrementAndGet()
                        Log.w(TAG, "Queue EMPTY — poll timeout")
                    }
                }
            }
            Log.i(TAG, "USB streaming thread exited")
        }, "UsbStreamingThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────

    /** Writes one buffer: the stage, then the stream (which ramps it when a gesture owes it a ramp). */
    private fun writeBuffer(buf: AudioBuffer) {
        when (buf) {
            is AudioBuffer.FloatBuffer -> {
                inFlightFrames = framesOf(buf)
                buf.processAtWrite?.invoke(buf.data)
                writing { usbStream.write(buf.data) }
                DirectPathTelemetry.usbFramesWritten = usbStream.framesWritten
                inFlightFrames = 0L
            }
            is AudioBuffer.RawBuffer -> {
                inFlightFrames = framesOf(buf)
                writing { usbStream.writeRaw(buf.data, buf.encoding) }
                DirectPathTelemetry.usbFramesWritten = usbStream.framesWritten
                inFlightFrames = 0L
            }
            else -> Unit
        }
    }

    private inline fun writing(write: () -> Unit) {
        synchronized(writeMonitor) { writesStarted++ }
        try {
            write()
        } finally {
            synchronized(writeMonitor) {
                writesDone = writesStarted
                writeMonitor.notifyAll()
            }
        }
    }

    /** Waits (bounded) for the audio write in progress, if any, to finish. Not for the writer's own thread. */
    private fun awaitWriteInProgress(timeoutMs: Long) {
        if (Thread.currentThread() === thread) return
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(writeMonitor) {
            val target = writesStarted
            while (writesDone < target && running) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remainingMs <= 0L) break
                writeMonitor.wait(remainingMs)
            }
        }
    }

    private fun isStale(buf: AudioBuffer): Boolean = synchronized(gestureLock) {
        when (buf) {
            is AudioBuffer.FloatBuffer -> buf.generation != streamGeneration
            is AudioBuffer.RawBuffer -> buf.generation != streamGeneration
            else -> false
        }
    }

    // ── Gestures (writer thread) ────────────────────────────────────────────────────────────────

    private fun takeSeekRequest(): SeekRequest? = synchronized(gestureLock) {
        val request = seekRequest ?: return@synchronized null
        seekRequest = null
        seekOwed = false
        // Until its ramp is written the gesture is not over (a stream rebuild waits for it).
        fadeInProgress = true
        request
    }

    private fun fadeFinished() {
        synchronized(gestureLock) {
            fadeInProgress = false
            gestureLock.notifyAll()
        }
    }

    private fun dropWakeMarkers() {
        audioQueue.removeIf { it === AudioBuffer.Wake }
    }

    /**
     * AUD-107 for the pipeline. The ring is never cancelled: it plays out, ends in an 8 ms fade-out of
     * the old position's real continuation, and silence follows until the first buffer of the new position
     * (which the stream fades in). A second seek while the ring already ends in a fade only keeps the silence
     * going — a finger dragging the bar piles up neither fades nor silence.
     */
    private fun performSeek(request: SeekRequest) {
        dropWakeMarkers()
        val continuation = ArrayList<AudioBuffer>(staleInHand.size + request.continuation.size)
        continuation.addAll(staleInHand)
        staleInHand.clear()
        continuation.addAll(request.continuation)
        val plan = usbStream.beginSeek()
        if (plan == UsbAudioStream.SEEK_FADE) {
            val remaining = continuation.iterator()
            while (running && remaining.hasNext() && usbStream.isFadingOut) {
                writeBuffer(remaining.next())
            }
            if (usbStream.isFadingOut) usbStream.endFadeOut()
            DirectPathTelemetry.pipelineSeekFades.incrementAndGet()
        }
        fadeFinished()
        if (plan != UsbAudioStream.SEEK_NONE) {
            val steps = feedSilenceUntilNewAudio()
            Log.i(TAG, "Seek: silence fed for $steps steps until the new position's first buffer (paused=$paused)")
        }
    }

    /**
     * Keeps the DAC's ring from running dry until the first buffer of the new position is queued, as the
     * engine does for the length of its seek. Not past a newer seek, which takes over; and while paused only
     * for [PAUSED_SILENCE_CAP_MS], after which the thread idles as a pause does (Media3 pauses the sink around a
     * seek, so the pause is normally there while the decoder works; either order of the two is handled).
     */
    private fun feedSilenceUntilNewAudio(): Int {
        val startedNs = System.nanoTime()
        val capNs = TimeUnit.MILLISECONDS.toNanos(PAUSED_SILENCE_CAP_MS)
        var steps = 0
        while (running && !seekOwed && !newAudioQueued() && (!paused || System.nanoTime() - startedNs < capNs)) {
            if (!usbStream.feedSilenceStep()) {
                Log.w(TAG, "Silence feed stopped: the stream failed")
                return steps
            }
            steps++
        }
        return steps
    }

    /** Audio (or the end of the stream) is waiting behind the flush; wake markers at the head are consumed. */
    private fun newAudioQueued(): Boolean {
        if (carry != null) return true
        while (true) {
            val head = audioQueue.peek() ?: return false
            if (head === AudioBuffer.Wake) {
                audioQueue.poll()
                continue
            }
            return true
        }
    }

    /**
     * AUD-123/140 for the pipeline. The ring plays out and ends in an 8 ms fade-out of the real continuation
     * (the next queued buffers); the rest of the buffer it ended inside is kept by the stream and plays,
     * faded in, at the resume — no audio is skipped. The thread then idles, announced, so the dry ring is not
     * reported as a starvation, until it is resumed, stopped, or a seek takes over.
     */
    private fun performPause() {
        // B12a-6: a pause the stream already acted on inside a block (cleared here, whichever way it goes from now).
        val appliedInBlock = usbStream.consumePauseApplied()
        if (usbStream.beginPauseFade()) {
            fadeInProgress = true
            while (running && !seekOwed && usbStream.isFadingOut) {
                val next = nextContinuation() ?: break
                writeBuffer(next)
            }
            // A seek that overtook the pause carries the same ramp on (with the old position's buffers).
            if (!seekOwed && usbStream.isFadingOut) usbStream.endFadeOut()
            DirectPathTelemetry.pipelinePauseFades.incrementAndGet()
            if (!seekOwed) fadeFinished()
        } else if (appliedInBlock) {
            // The fade was queued inside the block and is complete: nothing more to ramp, but it was a pause fade.
            DirectPathTelemetry.pipelinePauseFades.incrementAndGet()
        }
        // The ring is about to run dry by design: told to the stream from this thread, the one that
        // writes, so no later write can undo it (AUD-140).
        usbStream.markIdle()
        Log.i(TAG, "Paused: idle until resumed (seekOwed=$seekOwed)")
        // Untimed: resumeStreaming(), stop() and flush() notify. It polled every 50 ms for as long as the
        // pause lasted, hours with the screen off (AUD-036).
        synchronized(pauseLock) {
            while (paused && running && !seekOwed) pauseLock.wait()
        }
        if (running && !paused && !seekOwed) {
            Log.i(TAG, "Resumed from pause")
            usbStream.resumeAfterPause()
        }
    }

    /** The next audio a pause can ramp down, or null: nothing queued, or the track ends there. */
    private fun nextContinuation(): AudioBuffer? {
        val held = carry
        if (held != null) {
            carry = null
            return held
        }
        while (true) {
            val head = audioQueue.peek() ?: return null
            if (head === AudioBuffer.Wake) {
                audioQueue.poll()
                continue
            }
            // The marker stays where it is: the end of stream is drained in order, behind this.
            if (head is AudioBuffer.EndOfStream) return null
            return audioQueue.poll()
        }
    }

    /** Enqueue float PCM (FFmpeg path). Non-blocking, drop-oldest on full. */
    fun enqueue(floatBuf: FloatArray, processAtWrite: ((FloatArray) -> Unit)? = null) {
        if (endOfStreamQueued) return
        val buf = AudioBuffer.FloatBuffer(floatBuf, processAtWrite, streamGeneration)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
            dropCount++
            DirectPathTelemetry.droppedBuffers.incrementAndGet()
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, dropped buffer #$dropCount")
            }
        }
    }

    /** Enqueue raw integer PCM (libFLAC path). Non-blocking, drop-oldest on full. */
    fun enqueueRaw(rawBytes: ByteArray, encoding: Int) {
        if (endOfStreamQueued) return
        val buf = AudioBuffer.RawBuffer(rawBytes, encoding, streamGeneration)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
            dropCount++
            DirectPathTelemetry.droppedBuffers.incrementAndGet()
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, dropped raw buffer #$dropCount")
            }
        }
    }

    fun pauseStreaming() {
        paused = true
        // B12a-6: a block being written when this arrives has ~100 ms of its audio still to submit; the stream
        // looks for the request between URBs and starts the fade there instead of after the block.
        usbStream.requestPause()
    }

    fun resumeStreaming() {
        synchronized(pauseLock) {
            paused = false
            pauseLock.notifyAll()
        }
        usbStream.cancelPauseRequest()
    }

    fun hasPendingData(): Boolean =
        carry != null || audioQueue.any { it !== AudioBuffer.Wake } || (endOfStreamQueued && !endOfStreamDrained)

    fun isEndOfStreamDrained(): Boolean = endOfStreamQueued && endOfStreamDrained

    /** Buffers (and the end-of-stream marker) waiting to be written; the wake-up a flush leaves is not one. */
    fun queueSize(): Int = audioQueue.count { it !== AudioBuffer.Wake }

    private fun framesOf(buf: AudioBuffer): Long = when (buf) {
        is AudioBuffer.FloatBuffer -> buf.data.size.toLong() / channelCount
        is AudioBuffer.RawBuffer ->
            buf.data.size.toLong() / (PcmUtils.bytesPerSample(buf.encoding) * channelCount)
        is AudioBuffer.EndOfStream -> 0L
        AudioBuffer.Wake -> 0L
    }

    /**
     * Frames this thread still has to write: what is queued plus the buffer being written. The next
     * buffer offered to [enqueue]/[enqueueRaw] reaches the stream that many frames after
     * `framesWritten`, which is what the pipeline clock anchors a new period on (AUD-143).
     */
    fun queuedFrames(): Long = audioQueue.sumOf { framesOf(it) } + inFlightFrames

    /**
     * Blocks until the worker has taken the end-of-stream marker queued by [queueEndOfStream] (so
     * everything queued before it has been written and the residual drain attempted), the thread
     * stops, or [timeoutMs] passes. A paused thread never takes it: the caller decides whether
     * waiting makes sense.
     */
    fun awaitEndOfStream(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(endOfStreamLock) {
            while (!endOfStreamMarkerTaken && running) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remainingMs <= 0L) break
                endOfStreamLock.wait(remainingMs)
            }
            return endOfStreamMarkerTaken
        }
    }

    /**
     * True when a flush has left a fade-out to write that the thread has not finished: the ring still
     * ends in the old position's audio at full level until it has. A stream rebuild waits for this before
     * it releases the stream, or it would cut the fade it is about to hear (AUD-123).
     */
    fun fadeOutPending(): Boolean = seekOwed || fadeInProgress

    /**
     * Blocks until [fadeOutPending] is false, the thread stops, or [timeoutMs] passes. Returns whether the
     * ring now ends in its fade. Bounded: the ramp is 8 ms of audio, written when the ring has room.
     */
    fun awaitFadeOut(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(gestureLock) {
            while ((seekOwed || fadeInProgress) && running) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remainingMs <= 0L) break
                gestureLock.wait(remainingMs)
            }
            return !(seekOwed || fadeInProgress)
        }
    }

    /** Queue an ordered natural-EOS marker after every preceding source buffer. */
    fun queueEndOfStream(): Boolean {
        if (endOfStreamQueued) return true
        if (!audioQueue.offer(AudioBuffer.EndOfStream(streamGeneration))) {
            Log.e(TAG, "Unable to queue natural EOS marker")
            return false
        }
        endOfStreamQueued = true
        return true
    }

    /**
     * A seek, a skip or an item start repositioned the renderer: what is queued belongs to a position
     * nobody will hear (B9-2).
     *
     * The queue is emptied at once, as it always was, but its buffers are not simply dropped: they are the
     * old position's real continuation, which the ring (still holding up to ~80 ms of it) is ended with. They
     * are handed to the writer, which ramps the first 8 ms of them to zero, feeds silence, and drops the rest
     * — nothing is cancelled and nothing of the old position plays after the first buffer queued from here on.
     * A flush that finds a seek already owed keeps that seek's continuation (it is what the ring really
     * continues with) and drops what was queued since, which is the position that seek had moved to.
     */
    fun flush() {
        synchronized(gestureLock) {
            streamGeneration++
            val drained = ArrayList<AudioBuffer>()
            audioQueue.drainTo(drained)
            if (seekRequest == null) {
                seekRequest = SeekRequest(drained.filter { it is AudioBuffer.FloatBuffer || it is AudioBuffer.RawBuffer })
            }
            seekOwed = true
        }
        // A write in progress finishes before this returns, as it always did: its frames are counted
        // before the pipeline clock latches the first buffer of the new position.
        awaitWriteInProgress(WRITE_WAIT_MS)
        endOfStreamQueued = false
        endOfStreamDrained = false
        endOfStreamMarkerTaken = false
        // Wakes a writer waiting on an empty queue, and one idling in a pause (a seek while paused).
        audioQueue.offer(AudioBuffer.Wake)
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    fun stop() {
        synchronized(pauseLock) {
            running = false
            pauseLock.notifyAll()
        }
        synchronized(gestureLock) { gestureLock.notifyAll() }
        audioQueue.clear()
        endOfStreamQueued = false
        endOfStreamDrained = false
        synchronized(endOfStreamLock) {
            endOfStreamMarkerTaken = false
            endOfStreamLock.notifyAll()
        }
        thread?.join(2000)
        thread = null
    }
}
