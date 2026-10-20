package com.decent.usbaudio

import android.util.Log

/**
 * Native FLAC decode → USB audio engine.
 *
 * Bypasses the entire ExoPlayer audio pipeline for FLAC files.
 * A single native C++ thread handles: FLAC decode → bit-depth conversion → USB
 * isochronous transfers. Zero JNI in the hot path.
 *
 * Usage:
 * ```kotlin
 * val engine = NativeAudioEngine()
 * engine.createFromFd(flacFd, usbStreamHandle)
 * engine.start()
 * // ... engine runs autonomously, reports position via getPositionUs()
 * engine.stop()
 * engine.destroy()
 * ```
 *
 * @see UsbAudioStream for USB stream lifecycle management
 */
class NativeAudioEngine {

    /**
     * The native engine. Volatile, and every JNI call that dereferences it holds [handleLock], which
     * [destroy] also takes to clear it before freeing (AUD-015): a seek or position read from another
     * thread can never hand native code an engine that is being deleted.
     */
    @Volatile
    private var handle: Long = 0L
    private val handleLock = Any()

    /** True when the engine has been created and not yet destroyed. */
    val isCreated: Boolean get() = handle != 0L

    /** True when the decode thread is actively running. */
    val isRunning: Boolean get() = synchronized(handleLock) { handle != 0L && nativeIsRunning(handle) }

    /** Why the decode thread stopped; [NativeEngineExit.RUNNING] while it has not (AUD-013). */
    val exitReason: NativeEngineExit
        get() = synchronized(handleLock) {
            if (handle != 0L) NativeEngineExit.fromNative(nativeGetExitReason(handle)) else NativeEngineExit.STOPPED
        }

    /**
     * Create the engine from a file descriptor pointing to a FLAC file.
     *
     * @param flacFd      File descriptor for the FLAC file (will be dup'd internally).
     * @param usbHandle   Native handle from [UsbAudioStream] (the USB output context).
     * @return true if creation succeeded (FLAC metadata parsed, buffers allocated).
     */
    @Synchronized
    fun createFromFd(flacFd: Int, usbHandle: Long): Boolean {
        if (handle != 0L) {
            Log.w(TAG, "Engine already created, destroying first")
            destroy()
        }
        val created = nativeCreateFromFd(flacFd, usbHandle)
        synchronized(handleLock) { handle = created }
        if (handle == 0L) {
            Log.e(TAG, "Failed to create native engine")
            return false
        }
        Log.i(TAG, "Created: ${getSampleRate()}Hz ${getBitsPerSample()}-bit ${getChannels()}ch")
        return true
    }

    /** Start the decode thread. Audio flows immediately to USB. */
    fun start(): Boolean = synchronized(handleLock) {
        handle != 0L && nativeStart(handle)
    }

    /** Pause the decode loop (thread stays alive, USB pipeline drains). */
    fun pause() = synchronized(handleLock) {
        if (handle != 0L) nativePause(handle)
    }

    /** Resume the decode loop after pause. */
    fun resume() = synchronized(handleLock) {
        if (handle != 0L) nativeResume(handle)
    }

    /**
     * Seek to a position in the FLAC stream.
     * Uses the FLAC seek table for sample-accurate seeking.
     *
     * **Asynchronous, and accepting is not completing.** A true return means the request
     * was recorded, not that the decoder has moved. [getPositionUs] is not completion
     * evidence either — it is set to the target on request so the reported position does
     * not jump backwards while the decode thread catches up. The only completion signal
     * is [completedSeekGeneration] reaching the [generation] passed here.
     *
     * @param positionUs Target position in microseconds, in media-item time.
     * @param generation Monotonic request id. The decoder performs whichever request is
     *        newest and acknowledges that generation, so a slow older seek can neither
     *        resume playback nor publish itself as the current position.
     * @return true if the request was accepted.
     */
    fun seek(positionUs: Long, generation: Long): Boolean = synchronized(handleLock) {
        handle != 0L && nativeSeek(handle, positionUs, generation)
    }

    /** Generation the decoder has finished seeking to; 0 when none has completed. */
    val completedSeekGeneration: Long
        get() = synchronized(handleLock) { if (handle != 0L) nativeGetSeekCompletedGeneration(handle) else 0L }

    /**
     * Terminal state of a seek request, mirroring the native `SeekOutcome`.
     *
     * [PENDING] is not a position claim; only [SUCCESS] says the decoder is at the target.
     * [FAILED] says the attempt finished somewhere else — the caller must not treat the target as
     * reached, and playback must not resume as though it had been.
     */
    enum class SeekOutcome { PENDING, SUCCESS, FAILED }

    /** Outcome of the most recent seek, read together with [completedSeekGeneration]. */
    val seekOutcome: SeekOutcome
        get() = when (synchronized(handleLock) { if (handle != 0L) nativeGetSeekOutcome(handle) else 2 }) {
            0 -> SeekOutcome.PENDING
            1 -> SeekOutcome.SUCCESS
            else -> SeekOutcome.FAILED
        }

    /** True only when the decoder is actually at the most recently requested target. */
    val lastSeekSucceeded: Boolean
        get() = seekOutcome == SeekOutcome.SUCCESS

    /** Media target of the most recently completed seek, in microseconds. */
    val completedSeekTargetUs: Long
        get() = synchronized(handleLock) { if (handle != 0L) nativeGetSeekCompletedTargetUs(handle) else 0L }

    /** True once the decoder has completed the seek with this [generation]. */
    fun hasCompletedSeek(generation: Long): Boolean =
        handle != 0L && completedSeekGeneration >= generation

    /**
     * Stop the decode thread (blocks until thread exits). Thread-safe.
     *
     * Serialised with [destroy] by the instance monitor, not [handleLock]: the join can take as
     * long as an in-progress seek, and position reads must not wait on it. Only destroy frees.
     */
    @Synchronized
    fun stop() {
        val h = handle
        if (h != 0L) nativeStop(h)
    }

    /**
     * Stop, first queueing the real continuation faded out behind what is still playing, instead of
     * cutting it dry (AUD-123: track change). Blocks like [stop], ~8 ms longer.
     */
    @Synchronized
    fun stopWithFadeOut() {
        val h = handle
        if (h == 0L) return
        nativeRequestFadeOutOnStop(h)
        nativeStop(h)
    }

    /**
     * Before [start]: the previous track on this stream was cut with a fade, so the item-start seek
     * keeps that faded tail queued and this engine's first audio fades in (AUD-123).
     */
    fun enterWithFadeIn() = synchronized(handleLock) {
        if (handle != 0L) nativeEnterWithFadeIn(handle)
    }

    /** Destroy the engine and free all native resources. Thread-safe / idempotent. */
    @Synchronized
    fun destroy() {
        // Cleared under the lock every other JNI call holds, so none is using it when it is freed
        // and none can pick it up afterwards.
        val h = synchronized(handleLock) {
            val current = handle
            handle = 0L
            current
        }
        if (h != 0L) nativeDestroy(h)
    }

    /** Current playback position in microseconds (from decoded frames). */
    fun getPositionUs(): Long = synchronized(handleLock) {
        if (handle != 0L) nativeGetPositionUs(handle) else 0L
    }

    /** Length of the FLAC file in microseconds (from its stream info), or 0 if unknown. */
    fun getDurationUs(): Long = synchronized(handleLock) {
        if (handle != 0L) nativeGetDurationUs(handle) else 0L
    }

    /** FLAC file sample rate (e.g., 96000). */
    fun getSampleRate(): Int = synchronized(handleLock) {
        if (handle != 0L) nativeGetSampleRate(handle) else 0
    }

    /** FLAC file channel count (e.g., 2). */
    fun getChannels(): Int = synchronized(handleLock) {
        if (handle != 0L) nativeGetChannels(handle) else 0
    }

    /** FLAC file bits per sample (e.g., 24). */
    fun getBitsPerSample(): Int = synchronized(handleLock) {
        if (handle != 0L) nativeGetBitsPerSample(handle) else 0
    }

    // ── JNI declarations ───────────────────────────────────────────

    private external fun nativeCreateFromFd(fd: Int, usbHandle: Long): Long
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativePause(handle: Long)
    private external fun nativeResume(handle: Long)
    private external fun nativeSeek(handle: Long, positionUs: Long, generation: Long): Boolean
    private external fun nativeGetSeekCompletedGeneration(handle: Long): Long
    private external fun nativeGetSeekOutcome(handle: Long): Int
    private external fun nativeGetSeekCompletedTargetUs(handle: Long): Long
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeGetPositionUs(handle: Long): Long
    private external fun nativeGetSampleRate(handle: Long): Int
    private external fun nativeGetChannels(handle: Long): Int
    private external fun nativeGetBitsPerSample(handle: Long): Int
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeGetExitReason(handle: Long): Int
    private external fun nativeGetDurationUs(handle: Long): Long
    private external fun nativeRequestFadeOutOnStop(handle: Long)
    private external fun nativeEnterWithFadeIn(handle: Long)

    companion object {
        private const val TAG = "NativeAudioEngine"

        init {
            System.loadLibrary("decent_usb_audio")
        }
    }
}
