package com.decent.usbaudio

import android.util.Log

/**
 * JNI handle for a direct USB audio output stream.
 *
 * This class is the Kotlin counterpart of the native [UsbAudioContext] defined
 * in [usb-audio-output.cpp]. It sends PCM data directly to a USB Audio Class 2.0
 * DAC via Linux usbdevfs isochronous transfers, bypassing the entire Android
 * audio stack (AudioFlinger, AudioTrack, AAudio).
 *
 * Pipeline: 80 URBs in flight, each carrying 8 ISO packets (1 ms of audio).
 * Total pipeline buffer: ~80 ms.
 *
 * Rate transition lifecycle (per USB Audio Class 2.0 spec, verified via xHCI ftrace):
 * ```
 * stream.stop()           // stop accepting writes
 * stream.drainUrbs()      // discard and reap ALL in-flight URBs
 * stream.release()        // free native context
 * // Kotlin layer does: setAlt(0) -> SET_CUR rate -> setAlt(N)
 * newStream = UsbAudioStream(fd, ...)
 * newStream.start()
 * newStream.write(pcm)    // pipeline fills naturally
 * ```
 *
 * @param fd               File descriptor from [android.hardware.usb.UsbDeviceConnection.getFileDescriptor]
 * @param interfaceId      Audio streaming interface number (typically 1)
 * @param endpointOut      Isochronous OUT endpoint address (e.g. 0x01)
 * @param endpointFeedback Feedback IN endpoint address (e.g. 0x81), or 0 if unused
 * @param sampleRate       Initial sample rate in Hz
 * @param channelCount     Number of channels (1=mono, 2=stereo)
 * @param bitDepth         Bits per sample (16, 24, or 32)
 * @param maxPacketSize    Max packet size from endpoint descriptor
 */
class UsbAudioStream(
        fd: Int,
        interfaceId: Int,
        endpointOut: Int,
        endpointFeedback: Int,
        sampleRate: Int,
        channelCount: Int,
        bitDepth: Int,
        maxPacketSize: Int
) {

    /** Native UsbAudioContext pointer. Exposed for NativeAudioEngine which
     *  shares the same USB context for direct submitPcmToUrbs calls.
     *  Volatile, and cleared by [release] under [handleLock], which the reads made from other
     *  threads (diagnostics, liveness) also hold (AUD-015). */
    @Volatile
    var nativeHandle: Long = 0L
        private set
    private val handleLock = Any()

    init {
        nativeHandle = nativeUsbAudioCreate(
                fd, interfaceId, endpointOut, endpointFeedback,
                sampleRate, channelCount, bitDepth, maxPacketSize
        )
        if (nativeHandle == 0L) {
            Log.e(TAG, "nativeUsbAudioCreate returned 0 — check logcat for native errors")
        }
    }

    /** True when the native context was created successfully. */
    val isReady: Boolean
        get() = nativeHandle != 0L

    /** True when the stream is actively running (after start, before stop/error). */
    val isAlive: Boolean
        get() = synchronized(handleLock) { nativeHandle != 0L && nativeIsRunning(nativeHandle) }

    /** Total frames written to USB since last start(). Used for position tracking. */
    val framesWritten: Long
        get() = synchronized(handleLock) { if (nativeHandle != 0L) nativeGetFramesWritten(nativeHandle) else 0L }

    /**
     * URB ownership reading for the diagnostics: total, reusable, inFlight, discards requested /
     * succeeded / failed, retired, feedback restores.
     *
     * `reusable` is `NUM_URBS - inFlight`, which only holds if every submission is eventually
     * retired by a reap. A cancellation is not a retirement — `USBDEVFS_DISCARDURB` only requests
     * it, and the URB stays kernel-owned until its completion is reaped — so a barrier that leaks
     * ownership shows up here as a reusable count that shrinks cycle after cycle.
     *
     * Cheap and synchronous: a few relaxed loads and one small string.
     */
    val urbDiag: String
        get() = synchronized(handleLock) { if (nativeHandle != 0L) nativeGetUrbDiag(nativeHandle) else "urb=none" }

    /**
     * The most recent seek barrier's own record: sequence number, population before, what it
     * actually requested and retired, and the population after.
     *
     * Self-contained by design — it answers "did *this* barrier do anything?" without depending on
     * process-lifetime totals, which stay unchanged across a barrier that had nothing to discard.
     */
    val lastBarrierDiag: String
        get() = synchronized(handleLock) {
            if (nativeHandle != 0L) nativeGetLastBarrierDiag(nativeHandle) else "barrier=none"
        }

    /**
     * Identity-based verdict for the most recent barrier: frozen target count, how many were
     * observed returning through *some* reap path, and an explicit line per swallowed or
     * unresolved target.
     *
     * Resolution here means an exact `(urbAddress, slot, generation)` was seen at a reap site —
     * never a DISCARD result.
     */
    val barrierIdentityDiag: String
        get() = synchronized(handleLock) {
            if (nativeHandle != 0L) nativeGetBarrierIdentityDiag(nativeHandle) else "identity=none"
        }

    /**
     * Select alternate setting on the USB streaming interface.
     * This determines the active format (bit depth) of the endpoint.
     *
     * @param altSetting Alternate setting number (1-4 typically)
     * @return True on success
     */
    fun setAltSetting(altSetting: Int): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioSetAltSetting(nativeHandle, altSetting)
    }

    /**
     * Configure the DAC's sample rate via UAC2 SET_CUR control request.
     *
     * @param sampleRateHz  Target sample rate (e.g. 44100, 96000)
     * @param clockSourceId UAC2 Clock Source entity ID. Pass 0 to skip
     *                      (some DACs auto-detect from the data stream).
     * @return True on success
     */
    fun setSampleRate(sampleRateHz: Int, clockSourceId: Int = 0): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioSetSampleRate(nativeHandle, sampleRateHz, clockSourceId)
    }

    /**
     * Start the USB audio stream. The pipeline fills naturally with
     * data from subsequent [write] calls.
     */
    fun start(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioStart(nativeHandle)
    }

    /**
     * Write interleaved float32 PCM to the USB DAC.
     *
     * The native layer converts to the target bit depth, splits into
     * 1ms URBs (8 ISO packets each), and manages the 64-URB pipeline.
     * This call blocks when the pipeline is full (natural backpressure
     * matching the DAC's clock rate).
     *
     * @param pcmBuffer Interleaved float PCM; length = frameCount * channelCount
     */
    fun write(pcmBuffer: FloatArray) {
        if (nativeHandle == 0L) return
        nativeUsbAudioWrite(nativeHandle, pcmBuffer)
    }

    /**
     * Write raw integer PCM bytes directly to the USB DAC (no float conversion).
     *
     * The native layer pads the input bit depth to the DAC's bit depth using
     * lossless integer operations (shift left, zero-fill LSBs). This is the
     * true bit-perfect path — zero float math in the entire pipeline.
     *
     * @param pcmBuffer Raw PCM bytes (interleaved, little-endian)
     * @param encoding  Media3 PCM encoding constant (C.ENCODING_PCM_16BIT, etc.)
     */
    fun writeRaw(pcmBuffer: ByteArray, encoding: Int) {
        if (nativeHandle == 0L) return
        val inputBitDepth = when (encoding) {
            2 -> 16   // C.ENCODING_PCM_16BIT
            0x15 -> 24 // C.ENCODING_PCM_24BIT
            0x16 -> 32 // C.ENCODING_PCM_32BIT
            else -> return
        }
        nativeUsbAudioWriteRaw(nativeHandle, pcmBuffer, inputBitDepth)
    }

    /**
     * Stop accepting new writes. Does NOT drain the pipeline.
     * Call [drainUrbs] after this to wait for all in-flight URBs to complete.
     */
    fun stop() {
        if (nativeHandle == 0L) return
        nativeUsbAudioStop(nativeHandle)
    }

    /**
     * Reset the frame accumulator and residual buffer. Called on seek/flush
     * to prevent packet size jitter and boundary pops after a discontinuity.
     */
    fun flush() {
        if (nativeHandle == 0L) return
        nativeFlush(nativeHandle)
    }

    /**
     * The writer is about to idle on purpose (a pause): the ring will run dry, and the first write
     * after the resume must not be counted as a starvation by `USB_GLITCH RING_EMPTY` (AUD-140).
     */
    fun markIdle() {
        if (nativeHandle == 0L) return
        nativeMarkIdle(nativeHandle)
    }

    // ── The pipeline's pause / seek / skip barrier (B9) ─────────────────────────────────────────
    //
    // What the native engine does at a gesture, for the ExoPlayer pipeline's writer: the ring is never
    // cut. What is in it plays out, the real continuation (the next buffer the writer has) is ramped to
    // zero over 8 ms behind it, the DAC is fed silence until the first buffer of the new position, and
    // that buffer fades in over 15 ms. The ramps are `pcmApplyGainRamp`, the lengths the engine's.
    //
    // All of these are for the thread that writes (they touch the ring's indices), under the same lock
    // as [write] / [writeRaw]. The engine does not use them.

    /**
     * B12a-6: tells the writer, from any thread, that a pause was asked for. If a block is being written it is
     * acted on between two URBs (the fade-out starts from there and the rest of the block is kept for the resume)
     * instead of after the block. Dropped by [cancelPauseRequest], by [beginPauseFade], by [beginSeek] and by
     * [resumeAfterPause].
     */
    fun requestPause() {
        if (nativeHandle == 0L) return
        nativePipelineRequestPause(nativeHandle)
    }

    /** The host resumed before the writer acted on a pause request: it is dropped. */
    fun cancelPauseRequest() {
        if (nativeHandle == 0L) return
        nativePipelineCancelPauseRequest(nativeHandle)
    }

    /**
     * True once (and cleared) when a pause was acted on inside a block ([requestPause]): the fade-out was queued
     * from where the request reached the writer. Writer thread only; the pause is then handled as one that was seen
     * at a block's end ([beginPauseFade] finds the ramp finished, or still to be completed by the next blocks).
     */
    fun consumePauseApplied(): Boolean {
        if (nativeHandle == 0L) return false
        return nativePipelineConsumePauseApplied(nativeHandle)
    }

    /**
     * A pause: arms the fade-out of the continuation unless the ring has nothing audible left or already
     * ends in a fade. True: the writer must now [write] / [writeRaw] the next queued buffers until
     * [isFadingOut] turns false (or it has none left: [endFadeOut]). The rest of the last of those
     * buffers is kept natively for [resumeAfterPause].
     */
    fun beginPauseFade(): Boolean {
        if (nativeHandle == 0L) return false
        return nativePipelineBeginPause(nativeHandle)
    }

    /**
     * A seek or a skip: decides from what is still unplayed, drops what a pause kept, and arms the fade-in
     * of the first audio at the new position. One of [SEEK_NONE], [SEEK_FADE] (write the old position's
     * continuation, then feed silence) or [SEEK_SILENCE] (the ring already ends in a fade: only silence).
     * Repeated seeks coalesce into one fade-out.
     */
    fun beginSeek(): Int {
        if (nativeHandle == 0L) return SEEK_NONE
        return nativePipelineBeginSeek(nativeHandle)
    }

    /** True while a continuation fade-out is armed and incomplete. */
    val isFadingOut: Boolean
        get() = nativeHandle != 0L && nativePipelineFadingOut(nativeHandle)

    /** The continuation ran out before the ramp finished: the ring ends in what was ramped. */
    fun endFadeOut() {
        if (nativeHandle == 0L) return
        nativePipelineEndFadeOut(nativeHandle)
    }

    /** Leaving a pause: replays what the pause kept, faded in, and arms the fade-in for what follows. */
    fun resumeAfterPause() {
        if (nativeHandle == 0L) return
        nativePipelineResume(nativeHandle)
    }

    /**
     * One step of the silence that keeps the DAC's ring from running dry until the first buffer of the
     * new position arrives: tops the ring up to ~40 ms with silence, or waits (≤ 2 ms) for the DAC to play
     * some. False when the stream failed.
     */
    fun feedSilenceStep(): Boolean {
        if (nativeHandle == 0L) return false
        return nativePipelineFeedSilence(nativeHandle)
    }

    /**
     * `[hasAudio, endsInFade, fadingOut, pendingFrames, fadeInRemaining, silenceFramesFed, pauseFades,
     * seekFades, fadeOutsCompleted]`, for the diagnostics.
     */
    fun gestureStats(): LongArray =
        synchronized(handleLock) {
            if (nativeHandle != 0L) nativePipelineStats(nativeHandle) else LongArray(9)
        }

    /**
     * Submit only valid, frame-aligned PCM retained at natural end-of-source.
     * Stop, seek, detach, and error paths deliberately use [flush] or [stop]
     * instead and never call this method.
     */
    fun drainEndOfStream(): Boolean =
        nativeHandle != 0L && nativeDrainEndOfStream(nativeHandle)

    /**
     * Drain all in-flight URBs. Blocks until every URB is reaped.
     *
     * This MUST be called after [stop] and BEFORE the Kotlin layer calls
     * setAlt(0) on the USB device. The xHCI Configure Endpoint Command
     * triggered by setAlt(0) frees the isochronous ring — if any URBs
     * are still in the ring, the host controller state becomes corrupted.
     *
     * @return Number of URBs successfully drained
     */
    fun drainUrbs(): Int {
        if (nativeHandle == 0L) return 0
        return nativeDrainUrbs(nativeHandle)
    }

    /**
     * Release all native resources. The instance must not be used after this.
     */
    fun release() {
        val handle = synchronized(handleLock) {
            val current = nativeHandle
            nativeHandle = 0L
            current
        }
        if (handle == 0L) return
        nativeUsbAudioDestroy(handle)
        Log.i(TAG, "UsbAudioStream released")
    }

    // JNI declarations

    private external fun nativeUsbAudioCreate(
            fd: Int, interfaceId: Int, endpointOut: Int, endpointFeedback: Int,
            sampleRate: Int, channelCount: Int, bitDepth: Int, maxPacketSize: Int
    ): Long

    private external fun nativeUsbAudioSetAltSetting(handle: Long, altSetting: Int): Boolean
    private external fun nativeUsbAudioSetSampleRate(handle: Long, sampleRateHz: Int, clockSourceId: Int): Boolean
    private external fun nativeUsbAudioStart(handle: Long): Boolean
    private external fun nativeUsbAudioWrite(handle: Long, pcmBuffer: FloatArray)
    private external fun nativeUsbAudioWriteRaw(handle: Long, pcmBuffer: ByteArray, inputBitDepth: Int)
    private external fun nativeUsbAudioStop(handle: Long)
    private external fun nativeFlush(handle: Long)
    private external fun nativeMarkIdle(handle: Long)
    private external fun nativeDrainEndOfStream(handle: Long): Boolean
    private external fun nativeDrainUrbs(handle: Long): Int
    private external fun nativeUsbAudioDestroy(handle: Long)
    private external fun nativeGetUrbDiag(handle: Long): String
    private external fun nativeGetLastBarrierDiag(handle: Long): String
    private external fun nativeGetBarrierIdentityDiag(handle: Long): String
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeGetFramesWritten(handle: Long): Long
    private external fun nativePipelineRequestPause(handle: Long)
    private external fun nativePipelineCancelPauseRequest(handle: Long)
    private external fun nativePipelineConsumePauseApplied(handle: Long): Boolean
    private external fun nativePipelineBeginPause(handle: Long): Boolean
    private external fun nativePipelineBeginSeek(handle: Long): Int
    private external fun nativePipelineFadingOut(handle: Long): Boolean
    private external fun nativePipelineEndFadeOut(handle: Long)
    private external fun nativePipelineResume(handle: Long)
    private external fun nativePipelineFeedSilence(handle: Long): Boolean
    private external fun nativePipelineStats(handle: Long): LongArray

    companion object {
        private const val TAG = "UsbAudioStream"

        /** [beginSeek]: nothing audible is on its way to the DAC. */
        const val SEEK_NONE = 0

        /** [beginSeek]: write the old position's continuation (the ramp is armed), then feed silence. */
        const val SEEK_FADE = 1

        /** [beginSeek]: the ring already ends in a fade; only silence is needed. */
        const val SEEK_SILENCE = 2

        init {
            System.loadLibrary("decent_usb_audio")
        }

        /**
         * AUD-145: how the writer waits for a URB completion, process totals:
         * `[mode (1 = poll(2) on the usbfs fd, 0 = the old 125 µs sleep), waits entered, µs spent in
         * them, waits that ended without a completion, polls that found nothing to reap]`.
         */
        @JvmStatic
        external fun nativeGetWaitStats(): LongArray

        /** AUD-145: false restores the 125 µs sleep (the measurement's kill switch); true is poll(2). */
        @JvmStatic
        external fun nativeSetPollWait(enabled: Boolean)

        /**
         * B10: the DAC's feedback as the output sees it, process totals: `[policy, stuck now (0/1), episodes,
         * readings seen while stuck, ms stuck in all, longest episode ms, the last episode's fpmf x 1e6, its ppm,
         * feedback completions in all, the last reading's fpmf x 1e6, the stream's nominal rate in Hz, the fpmf the
         * packetizer was last given x 1e6]`. See [FeedbackPolicy.fpmf].
         */
        @JvmStatic
        external fun nativeGetFeedbackStats(): LongArray

        /**
         * B27-A5 (review N-07): the stream's last [windowMs] in one-second summaries, as text: a line with the stream's facts, then
         * one line per second that streamed (feedback readings' min / max / mean, how many were off nominal or refused, the ISO
         * packets' statuses). Recorded all the time at the cost of a few compares on the thread that reaps; built only here.
         */
        @JvmStatic
        external fun nativeGetSecondSummaries(windowMs: Long): String

        /**
         * B10-2: what the output does with the feedback, a [FeedbackPolicy] id. Process-only, never persisted; an
         * unknown id changes nothing. Takes effect at the next write; the packet accumulator carries on, so there
         * is no jump.
         */
        @JvmStatic
        external fun nativeSetFeedbackPolicy(policy: Int)

        /**
         * Perform a USB port reset on the device. Resets the DAC's clock state.
         * The fd remains valid but all interface claims are released.
         * @return 0 on success, negative on error
         */
        @JvmStatic
        external fun nativeUsbReset(fd: Int): Int

        /**
         * Re-binds the kernel audio driver to interface [ifno] after it was released, only if the
         * interface has no driver (the kernel may already have rebound it). @return 1 when already
         * bound, 0 after a successful CONNECT, negative errno on failure.
         */
        @JvmStatic
        external fun nativeReattachKernelDriverIfUnbound(fd: Int, ifno: Int): Int

        /** The kernel driver bound to interface [ifno] (USBDEVFS_GETDRIVER), or null if none. */
        @JvmStatic
        external fun nativeKernelDriverName(fd: Int, ifno: Int): String?

        /** Debug-validation hook. PixelPlayer exposes this only from its
         * debug-only, shell-permission-protected receiver. */
        @JvmStatic
        external fun nativeStartPayloadValidation(
            manifestPath: String,
            label: String,
            expectedBytes: Long,
        ): Boolean

        @JvmStatic
        external fun nativeStopPayloadValidation(): String?

        @JvmStatic
        external fun nativeGetPayloadValidationStatus(): String
    }
}
