package com.decent.usbaudio.media3

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.decent.usbaudio.NativeAudioEngine
import com.decent.usbaudio.OpenLevelCheck
import com.decent.usbaudio.UacStreamingDescriptors
import com.decent.usbaudio.UsbPcmConversion
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * ExoPlayer [androidx.media3.exoplayer.audio.AudioSink] that sends PCM directly
 * to a USB Audio Class 2.0 DAC via isochronous transfers, bypassing the entire
 * Android audio stack (AudioFlinger, AudioTrack, AAudio).
 *
 * The delegate [DefaultAudioSink] is kept alive (muted) for ExoPlayer's clock
 * and position tracking. Audio data is routed to the USB DAC via a dedicated
 * streaming thread with a producer-consumer queue, decoupling USB timing from
 * the delegate's AudioTrack timing.
 *
 * @param delegate  The [DefaultAudioSink] owned by the ExoPlayer renderer.
 * @param context   Application context for USB device detection and audio routing.
 * @param config    Configuration options (default: bit-perfect enabled, route to speaker).
 */
@OptIn(UnstableApi::class)
class UsbAudioSink(
    private val delegate: DefaultAudioSink,
    private val context: Context,
    private val config: UsbAudioSinkConfig = UsbAudioSinkConfig()
) : ForwardingAudioSink(delegate) {

    @Volatile
    var diagnostics: UsbAudioSinkDiagnostics = UsbAudioSinkDiagnostics()
        private set

    @Volatile
    private var diagnosticsListener: ((UsbAudioSinkDiagnostics) -> Unit)? = null

    val isUsbDirectActive: Boolean
        get() = usbAudioStream?.isAlive == true

    fun setDiagnosticsListener(listener: ((UsbAudioSinkDiagnostics) -> Unit)?) {
        diagnosticsListener = listener
        listener?.invoke(diagnostics)
    }

    private fun publishDiagnostics(value: UsbAudioSinkDiagnostics) {
        diagnostics = value
        diagnosticsListener?.invoke(value)
    }

    /** Source file bit depth (16, 24, 32). Auto-detected from NativeAudioEngine. */
    private var trackBitDepth: Int = 0

    /** True when the native engine is running. Read by [NativeEngineAwareLoadControl]
     *  to stop ExoPlayer from loading data (prevents SD card I/O contention).
     *  Temporarily set to false during seek to allow one post-seek load. */
    @Volatile
    var isNativeEngineActive: Boolean = false
        private set

    /**
     * The native engine for the current item exists, even while [isNativeEngineActive] is briefly
     * false to let one buffer through (AUD-114). Media3 does not produce this audio, so its 2.5 s
     * buffering rule must not hold READY — and the UI's progress — behind an engine already playing.
     */
    /**
     * AUD-130: the engine's track is in its last [PRELOAD_WINDOW_US]. Media3 was kept from loading
     * anything while the engine played, so at a natural end it loaded the next item from scratch
     * (measured: BUFFERING 1.8 s, ~2.2 s of silence between two tracks of the same rate).
     */
    val isNativeEngineNearEnd: Boolean
        get() {
            val engine = nativeEngine ?: return false
            if (!engine.isRunning) return false
            val durationUs = engine.getDurationUs()
            return durationUs > 0L && durationUs - engine.getPositionUs() <= PRELOAD_WINDOW_US
        }

    val isNativeEngineReadyForCurrentItem: Boolean
        get() = config.directEnabled && nativeEngine?.isCreated == true &&
            activeEnginePath != null && activeEnginePath == currentTrackPath


    /** File path of the current track. Set internally by [PlayerIntegrationListener]
     *  from the MediaItem URI. When non-null and pointing to a FLAC file, the native
     *  audio engine is used. For HTTP URIs, this is null (ExoPlayer pipeline fallback). */
    private var currentTrackPath: String? = null

    /** Clean up a finished native engine and apply deferred USB config.
     *  @return true if an engine was cleaned up (caller should restart playback). */
    @Synchronized
    private fun cleanupFinishedEngine(): Boolean {
        val engine = nativeEngine
        if (engine != null && !engine.isRunning) {
            engine.destroy()
            nativeEngine = null
            isNativeEngineActive = false
            activeEnginePath = null
            recordMapping(
                "OFFSET_RESET",
                "oldOffsetUs=$windowOffsetUs newOffsetUs=-1 needsInit=true " +
                    "reason=ENGINE_CLEANUP gen=${mappingGeneration.get()} " +
                    mappingOperandState() + " " + mappingPlayerState(),
            )
            // SAME_POSITION_REBUILD: machinery was rebuilt, the media position did not change, so the
            // mapping is preserved. Clearing the offset here while leaving the anchor alive is
            // exactly how a cross-position pair used to become representable.
            mappingTracker.preserve()
            usbStartMediaTimeNeedsInit = true
            pipelineClock.disarm()
            Log.i(TAG, "cleanupFinishedEngine: old engine cleared")

            // Apply deferred USB reconfiguration (cross-rate transition) — only at the transition it
            // was deferred for (AUD-137): a seek re-enables the renderers and Media3 configures the
            // item it lands on itself; applied one transition late it opened a 48 kHz track at 192 kHz.
            if (hasDeferredConfig) {
                if (NaturalEndCoordinator.deferredConfigApplies(deferredForTransitionSeq, transitionSeq)) {
                    Log.i(TAG, "cleanupFinishedEngine: applying deferred config rate=$deferredRate")
                    configureUsbBitPerfect(deferredRate, deferredChannels, deferredEncoding)
                } else {
                    Log.i(TAG, "cleanupFinishedEngine: discarding deferred config rate=$deferredRate " +
                        "(for transition #$deferredForTransitionSeq, now #$transitionSeq)")
                }
                hasDeferredConfig = false
            }
            return true
        }
        return false
    }

    /**
     * Set on the main thread by the item transition, consumed on the playback thread (AUD-115): the
     * engine for a new item is only ever created where the stream it will feed is configured.
     */
    @Volatile
    private var enginePendingForItem = false

    /**
     * Playback thread only: clears the previous item's finished engine for a transition, and then
     * resets the position for the new track on the player's own thread, as the listener did inline.
     */
    private fun cleanUpFinishedEngineForItem() {
        // A natural end resolved by Media3's own transition: the new item already starts at 0, and a
        // rewind seek on top of it would be a spurious SEEK (AUD-128). Its resolution is left to
        // createPendingEngine, which applies the exact offset (AUD-129); with no engine coming (not a
        // local FLAC) it is resolved here.
        val natural = naturalEnd.pending
        if (natural) naturalJoinAtMs = SystemClock.elapsedRealtime()
        logJoinMark("cleanUpFinishedEngineForItem", if (natural) "SET" else "not set (no natural end pending)")
        if (natural && !enginePendingForItem) naturalEnd.onItemTransition(offsetLedger.playingOffsetUs())
        if (!cleanupFinishedEngine()) return
        if (natural) return
        val player = attachedPlayer ?: return
        Handler(player.applicationLooper).post { attachedPlayer?.seekTo(0) }
    }

    /** Playback thread only: creates the engine a transition asked for, if still wanted. */
    private fun createPendingEngine(reason: Int) {
        if (!enginePendingForItem) return
        val now = SystemClock.elapsedRealtime()
        // AUD-137: Media3 crossed into the next item while the ended item's engine still plays its
        // last samples (measured: the AUTO 35 ms before the sink saw the end of stream). Nothing is
        // created against a running engine; the end of stream resolves it, or the window expires.
        val running = nativeEngine
        if (running != null && running.isRunning && engineTransitionSeq != transitionSeq &&
            !naturalEnd.pending && NaturalEndCoordinator.isSequential(reason)
        ) {
            naturalEnd.onEarlyTransition(now)
            Log.i(TAG, "Natural end: Media3's transition (reason=$reason) came before the engine's end of stream; " +
                "resolving at it (≤${NaturalEndCoordinator.EARLY_TRANSITION_WINDOW_MS} ms)")
            playbackHandler?.postDelayed({ onEarlyTransitionExpired() }, NaturalEndCoordinator.EARLY_TRANSITION_WINDOW_MS)
            return
        }
        enginePendingForItem = false
        // What Media3's clock was last given: the ended item's clock running past its end.
        val reportedBeforeUs =
            if (naturalEnd.pending && windowOffsetUs >= 0) windowOffsetUs + naturalEnd.positionUs(now) else -1L
        val next = naturalEnd.onItemTransition(offsetLedger.playingOffsetUs())
        createEngineIfNeeded(declareStartTarget = true)
        // AUD-144: the engine of a natural join is not cut by the rebuffer Media3 does right after it.
        if (next !is NaturalEndCoordinator.NextOffset.None) naturalJoinAtMs = now
        logJoinMark("createPendingEngine", if (next !is NaturalEndCoordinator.NextOffset.None) "SET" else "not set (next=None)")
        // AUD-129 / AUD-136: after a natural end the new item's offset is Media3's own renderer offset
        // for the new period — never latched from a buffer that may come seconds into the track (or
        // not at all after a rate change), and never "previous offset + length", which carried any
        // error of the ended item into this one.
        when (next) {
            is NaturalEndCoordinator.NextOffset.FromMedia3 ->
                adoptItemOffset(next.offsetUs, reportedBeforeUs, "NATURAL_END_MEDIA3")
            is NaturalEndCoordinator.NextOffset.AwaitMedia3 -> {
                openEpoch(0L, AnchorSource.ITEM_TRANSITION_START, "natural-end-await-media3")
                naturalOffsetAwaitSinceMs = now
                naturalOffsetFallbackUs = next.fallbackUs
                naturalOffsetReportedBeforeUs = reportedBeforeUs
                Log.i(TAG, "Natural end: waiting for Media3's offset of the next item")
            }
            NaturalEndCoordinator.NextOffset.None -> Unit
        }
    }

    /**
     * Playback thread: an early transition whose engine did not end within the window (Media3's
     * duration shorter than the file's). The old engine is stopped and the current item opened, as
     * the transition would have done — never left playing under the next item's title.
     */
    private fun onEarlyTransitionExpired() {
        if (!naturalEnd.earlyTransitionExpired(SystemClock.elapsedRealtime())) return
        naturalEnd.clearEarlyTransition()
        if (!enginePendingForItem) return
        val old = nativeEngine
        if (old != null && old.isRunning) {
            Log.w(TAG, "Natural end: Media3 moved on and the engine did not end within the window; switching")
            old.stopWithFadeOut()
            pendingEntryFadeIn = true
        }
        cleanupFinishedEngine()
        createPendingEngine(RendererOffsetLedger.TRANSITION_REASON_AUTO)
        offsetLedger.playingOffsetUs()?.let { adoptItemOffset(it, -1L, "EARLY_TRANSITION_SWITCH") }
    }

    /** Playback thread: the new item's window offset after a natural end, and a clean slate for it. */
    private fun adoptItemOffset(offsetUs: Long, reportedBeforeUs: Long, reason: String) {
        naturalOffsetAwaitSinceMs = -1L
        openEpoch(0L, AnchorSource.ITEM_TRANSITION_START, reason)
        latchEpoch(offsetUs, reason)
        recordMapping("OFFSET_LATCH", "reason=$reason windowOffsetUs=$offsetUs reportedBeforeUs=$reportedBeforeUs")
        Log.i(TAG, "Natural end: next item offset set to $offsetUs ($reason)")
        // The renderer clock only moves back when told: without this the new item's position held
        // at the ended item's overshoot (or at a wrong offset's value) until the engine caught up.
        if (reportedBeforeUs >= 0L && offsetUs < reportedBeforeUs) sinkListener?.onPositionDiscontinuity()
        driftMonitor.onEpochStart(SystemClock.elapsedRealtime())
    }

    /** Playback thread: moves the window offset outside a latch, telling Media3 when it goes back. */
    private fun moveWindowOffset(newOffsetUs: Long, reason: String) {
        val oldOffsetUs = windowOffsetUs
        openEpoch(0L, AnchorSource.IMPLICIT_RESTART, reason)
        latchEpoch(newOffsetUs, reason)
        recordMapping("OFFSET_LATCH", "reason=$reason oldOffsetUs=$oldOffsetUs windowOffsetUs=$newOffsetUs")
        if (oldOffsetUs < 0L || newOffsetUs < oldOffsetUs) sinkListener?.onPositionDiscontinuity()
    }

    /** Creates a native engine if the USB stream is ready and no engine exists.
     *  Replaces the streaming thread fallback if one was set up due to rate mismatch. */
    @Synchronized
    private fun createEngineIfNeeded(declareStartTarget: Boolean = false) {
        val running = nativeEngine
        if (running?.isRunning == true) {
            // AUD-139: an engine the transition's own `configure` created (a rate change) is running
            // and unplaced when the item start is declared. Returning here left nobody to place it,
            // and the first renderer buffer — the read-ahead edge, minutes later — moved it. It is
            // the item's engine: place it at the declared start.
            if (declareStartTarget && activeEnginePath == currentTrackPath) placeRunningEngineAtItemStart(running)
            return
        }
        val stream = usbAudioStream
        if (stream != null && stream.isAlive) {
            // Clean up dead engine if exists
            val old = nativeEngine
            if (old != null && !old.isRunning) {
                old.destroy()
                nativeEngine = null
                activeEnginePath = null
            }
            if (nativeEngine == null) {
                recordMapping(
                    "OFFSET_RESET",
                    "oldOffsetUs=$windowOffsetUs newOffsetUs=-1 needsInit=true " +
                        "reason=ENGINE_CREATE gen=${mappingGeneration.get()} " +
                        "declareStartTarget=$declareStartTarget " +
                        mappingOperandState() + " " + mappingPlayerState(),
                )
                // SAME_POSITION_REBUILD: machinery was rebuilt, the media position did not change, so the
            // mapping is preserved. Clearing the offset here while leaving the anchor alive is
            // exactly how a cross-position pair used to become representable.
            mappingTracker.preserve()
                usbStartMediaTimeNeedsInit = true
                pipelineClock.disarm()
                startNativeEngineIfFlac(stream, declareStartTarget)
                // Engine starts paused with engineNeedsInitialSeek = true.
                // Temporarily unblock LoadControl so ExoPlayer sends at least one
                // handleBuffer — needed to capture presentationTimeUs and seek.
                // Without this, the LoadControl blocks immediately and the engine
                // stays paused forever (HTTP→local transition race).
                if (nativeEngine != null) {
                    isNativeEngineActive = false
                }
                Log.i(TAG, "createEngineIfNeeded: engine=${nativeEngine != null}")
            }
        }
    }

    /**
     * AUD-139: gives a running, unplaced engine its item's declared start. Already at it (created a
     * few ms ago at 0) it is only recorded as placed — no seek, so no barrier and no cut; elsewhere
     * (a restore's target) it is sent there as an explicit item-start seek.
     */
    private fun placeRunningEngineAtItemStart(engine: NativeAudioEngine) {
        when (val start = enginePlacement.itemStartFor(engine, engine.getPositionUs(), initialPlayerPositionUs)) {
            EnginePlacement.ItemStart.AlreadyPlaced -> Unit
            EnginePlacement.ItemStart.InPlace -> {
                enginePlacement.onPlaced(engine, positionResetCount, "item-start-in-place")
                auditPosition("ENGINE_PLACED", "item-start-in-place", "target=${initialPlayerPositionUs / 1000}ms")
            }
            is EnginePlacement.ItemStart.SeekTo -> issueExplicitSeek(engine, start.targetUs, "item-start")
        }
    }

    private var usbAudioStream: UsbAudioStream? = null
    private val usbAudioDevice = UsbAudioDevice.getInstance(context)
    private var usbStreamingThread: UsbStreamingThread? = null

    init {
        // AUD-145: the native layer's wait counters, read when a capture snapshot is taken. A lambda, so
        // nothing loads the native library here (the unit tests construct a sink without it).
        DirectPathTelemetry.usbWaitStatsProvider = { UsbAudioStream.nativeGetWaitStats() }
        // B10: what the output makes of the DAC's feedback (stuck episodes, the policy in force).
        DirectPathTelemetry.usbFeedbackStatsProvider = { UsbAudioStream.nativeGetFeedbackStats() }
    }
    // Written on the playback thread; read from the main thread too (seek, diagnostics) (AUD-015).
    @Volatile
    private var nativeEngine: NativeAudioEngine? = null

    /** AUD-016 / AUD-128: a finished engine whose track Media3 is about to leave by itself. */
    private val naturalEnd = NaturalEndCoordinator()

    // Coherence watchdogs (B7-fix); playback thread, from getCurrentPositionUs.
    private val driftMonitor = PositionDriftMonitor()
    private var lastCoherenceCheckMs = 0L
    private var lastCheckedEngine: NativeAudioEngine? = null
    private var engineItemMismatchSinceMs = -1L

    /** AUD-137: counts every onMediaItemTransition (main thread); read on the playback thread. */
    @Volatile
    private var transitionSeq = 0L
    /** [transitionSeq] when the current native engine was created: the item it belongs to. */
    @Volatile
    private var engineTransitionSeq = 0L

    /** AUD-136: Media3's renderer offsets, reported through [setOutputStreamOffsetUs]. */
    private val offsetLedger = RendererOffsetLedger()

    /** Media3's listener, kept to report a discontinuity when the sink moves its position back. */
    @Volatile
    private var sinkListener: AudioSink.Listener? = null

    // A natural end whose next-period offset Media3 had not reported yet; playback thread.
    private var naturalOffsetAwaitSinceMs = -1L
    private var naturalOffsetFallbackUs = -1L
    private var naturalOffsetReportedBeforeUs = -1L

    /**
     * Two invariants of native-engine mode, checked a few times a second:
     * - the engine decodes the item Media3 has as current (AUD-133 — measured: the track that had
     *   just ended playing again under the next one's title). A mismatch lasting over a second (a
     *   manual skip reconfigures well within that) is logged and the engine rebuilt for the item;
     * - Media3's position follows the engine's (POSITION_DRIFT): see [PositionDriftMonitor]. A
     *   correction moves only the offset, never the audio, and is undone if it did not help
     *   (AUD-136: the B7-fix version chased a frozen player into a StuckPlayerException).
     */
    private fun checkEngineCoherence(engine: NativeAudioEngine, enginePositionUs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCoherenceCheckMs < COHERENCE_CHECK_INTERVAL_MS) return
        lastCoherenceCheckMs = now
        if (engine !== lastCheckedEngine) {
            lastCheckedEngine = engine
            driftMonitor.onEpochStart(now)
        }
        if (naturalEnd.pending || enginePendingForItem || seekAwaitingEngineUs >= 0L || explicitSeekOwed()) {
            driftMonitor.onEpochStart(now)
            engineItemMismatchSinceMs = -1L
            return
        }
        val enginePath = activeEnginePath
        val itemPath = currentTrackPath
        if (enginePath != null && itemPath != null && enginePath != itemPath) {
            if (engineItemMismatchSinceMs < 0L) engineItemMismatchSinceMs = now
            if (now - engineItemMismatchSinceMs >= ENGINE_ITEM_MISMATCH_GRACE_MS) {
                engineItemMismatchSinceMs = -1L
                Log.w(TAG, "ENGINE_ITEM_MISMATCH engine=${File(enginePath).name} item=${File(itemPath).name} — rebuilding for the current item")
                playbackHandler?.post {
                    val stale = nativeEngine
                    if (stale != null && activeEnginePath != currentTrackPath) {
                        stale.stop()
                        stale.destroy()
                        nativeEngine = null
                        activeEnginePath = null
                        createEngineIfNeeded(declareStartTarget = true)
                    }
                }
            }
            return
        }
        engineItemMismatchSinceMs = -1L
        val sample = playerMirror?.let {
            PositionDriftMonitor.Sample(it.positionMs, it.atMs, it.isPlaying, it.playbackState == Player.STATE_READY)
        }
        when (val action = driftMonitor.check(now, enginePositionUs, sample, mappingTracker.isLatched)) {
            PositionDriftMonitor.Action.None -> Unit
            is PositionDriftMonitor.Action.Correct -> {
                val newOffsetUs = windowOffsetUs - action.driftUs
                Log.w(POSITION_DRIFT_TAG, "Media3 ${action.driftUs / 1000}ms off the engine (${enginePositionUs / 1000}ms); offset $windowOffsetUs → $newOffsetUs, verifying")
                moveWindowOffset(newOffsetUs, "POSITION_DRIFT driftUs=${action.driftUs}")
            }
            is PositionDriftMonitor.Action.Revert -> {
                val newOffsetUs = windowOffsetUs + action.correctionUs
                Log.w(POSITION_DRIFT_TAG, "Correction undone (${action.reason}); offset $windowOffsetUs → $newOffsetUs; watchdog off for this item")
                moveWindowOffset(newOffsetUs, "POSITION_DRIFT_REVERT correctionUs=${action.correctionUs}")
            }
            is PositionDriftMonitor.Action.Disable ->
                Log.w(POSITION_DRIFT_TAG, "Watchdog off for this item: ${action.reason}")
            is PositionDriftMonitor.Action.Verified ->
                Log.i(POSITION_DRIFT_TAG, "Correction verified: Media3 now ${action.residualUs / 1000}ms from the engine")
            is PositionDriftMonitor.Action.Unlatched -> {
                val media3OffsetUs = offsetLedger.playingOffsetUs()
                if (media3OffsetUs != null) {
                    Log.w(POSITION_DRIFT_TAG, "No offset while the engine plays (${action.enginePositionUs / 1000}ms); taking Media3's $media3OffsetUs")
                    moveWindowOffset(media3OffsetUs, "UNLATCHED_MEDIA3")
                } else {
                    Log.w(POSITION_DRIFT_TAG, "No offset while the engine plays (${action.enginePositionUs / 1000}ms) and none reported by Media3")
                }
            }
        }
    }

    /** Playback thread: the previous engine was cut with a fade; the next one enters with a fade-in. */
    private var pendingEntryFadeIn = false
    private val engineLock = Any()

    /**
     * AUD-144: the user's intent (`Player.playWhenReady`), from the player's own listener. Unlike the
     * sampled mirror (250 ms) it is event-driven and set on the main thread *before* the playback
     * thread learns of a user's pause, so `pause()` can tell that pause from a rebuffer's stop.
     */
    @Volatile
    private var userWantsPlayback = true

    /**
     * [SystemClock.elapsedRealtime] at which the last natural join was seen (Media3's own transition
     * after an engine's end of stream); -1 after a seek or a playlist change. It is taken when the
     * transition is first handled, before the new engine exists: in a rate change that engine is made
     * inside the cleanup task, and a stop from Media3 can land before the creation task has run.
     */
    @Volatile
    private var naturalJoinAtMs = -1L

    /** Playback thread: a stop from Media3 held back from the engine (AUD-144), until `play()` or the limit. */
    private var heldEnginePause: Runnable? = null

    /**
     * Playback thread: a stop from Media3 held back from the ExoPlayer pipeline's thread (B9-1), under the
     * same policy as the engine's: the rebuffer right after a natural join must not fade the audio, and a
     * pause that fades is exactly what a stop would now do to the thread.
     */
    private var heldThreadPause: Runnable? = null

    /**
     * Playback thread: the stage the last PROCESSED → PRISTINE change left behind while the pipeline kept its
     * queue (B9-3). Its delay lines are not wiped at the change — queued buffers still run through it and the
     * crossfade out of it needs it — but when that queue is gone ([resetRetiredStage]) or it is engaged again.
     */
    private var retiredStage: DirectPcmStage? = null

    /** Playback thread: the crossfade that joins the last PRISTINE ↔ PROCESSED change to the queued audio (B9-3). */
    private var pipelineCrossfade: ProcessingCrossfade? = null

    private var currentEncoding: Int = C.ENCODING_PCM_16BIT
    private var currentSampleRate: Int = 0

    /**
     * B12a-4: ms of silence still owed to the new stream's first buffer, set when a stream is rebuilt for a rate
     * change and taken when the pipeline's thread for it is created (see [RateSwitchPreroll]). Playback thread.
     */
    private var pendingPrerollMs = 0
    private var currentChannelCount: Int = 0
    private var pendingVolume: Float = 1f
    private var delegateMuted: Boolean = false
    /**
     * This configuration's Direct claim failed. The delegate then stays muted instead of being
     * routed to the built-in speaker (AUD-004): the host latches the failure and rebuilds on the
     * Android path, and nothing picks the speaker for a listener wearing headphones on the DAC.
     */
    private var directFailureHold: Boolean = false
    private var handleBufferCallCount: Long = 0

    // ── Signal path (pristine vs processed) ─────────────────────────────
    //
    // This is not the transport. `config.directEnabled` says the sink may carry PCM to the DAC;
    // these say what the sink is doing to that PCM on the way. Keeping them apart is what lets
    // Direct survive an equalizer being switched on, which the previous single flag made
    // impossible by construction.

    /** The mode the Direct transport is currently running in. Meaningful only while Direct is up. */
    @Volatile
    var signalMode: DirectSignalMode = DirectSignalMode.PRISTINE
        private set

    /**
     * The stage as configured for the current stream, or null when PCM must pass through untouched.
     *
     * Distinct from [pcmStage]: a stage exists as soon as the host offers one, but only becomes
     * *engaged* once it has accepted the stream's format. A stage that rejected the format must not
     * be handed samples, and the diagnostics must report the pass-through rather than claim
     * processing that is not happening.
     */
    @Volatile
    private var engagedStage: DirectPcmStage? = null

    /** Scratch conversion target. Reallocated only when a buffer outgrows it. */
    private var pcmScratch: FloatArray = FloatArray(0)

    /**
     * The mode the host is asking for right now.
     *
     * Read live on every buffer rather than captured at configure time, because the answer changes
     * while a track plays and a rebuild would release and re-claim the exclusive USB session for a
     * change that is purely arithmetic.
     */
    private fun requestedSignalMode(): DirectSignalMode =
        config.signalSource?.mode() ?: DirectSignalMode.PRISTINE

    /**
     * Whether the native FLAC engine may own decode for the current mode.
     *
     * B12a: **not unless the host asked for it** (plan B, a persisted debug switch; never in a release
     * build). The ExoPlayer pipeline is the PRISTINE producer for every format, so with the default
     * this is false for every input and no engine is ever created.
     *
     * The engine decodes straight into the USB URBs and never lets the application see a sample, so
     * it is by definition a pristine-only path. Letting it run in PROCESSED mode would produce a
     * stream whose diagnostics claim processing while the DAC receives untouched source — the exact
     * silent no-op this architecture exists to prevent.
     */
    private fun nativeEngineAllowed(): Boolean {
        // Neither a downmix nor a mono upmix exists in the engine, which writes source frames as-is.
        return NativeEnginePolicy.allowed(
            requestedSignalMode(), downmixSourceChannels, upmixMonoSource, debugForcesNativeEngine(),
        )
    }

    /**
     * DEBUG ONLY (plan B): the host asked for PRISTINE local FLACs to get the native engine instead of
     * the ExoPlayer pipeline. False unless the signal source says so. It is read here and nowhere
     * near the signal mode: it moves the producer, so the stream stays PRISTINE.
     */
    private fun debugForcesNativeEngine(): Boolean =
        config.signalSource?.debugForceNativeEngine() == true

    /**
     * The ExoPlayer pipeline is the producer and stays it for the mode the host now asks for (B9-3): its
     * thread, its queue and its clock carry on across the change. False when an engine exists or is about to
     * take over (a FLAC with plan B forced on in PRISTINE), where producers really do change.
     */
    private fun pipelineStaysProducer(): Boolean =
        PipelineThread.realignFor(
            engineExists = nativeEngine != null,
            threadRunning = usbStreamingThread != null,
            wantsEngine = PipelineThread.wantsEngine(nativeEngineAllowed(), currentTrackPath),
        ) == PipelineThread.RealignAction.KEEP_PIPELINE

    /**
     * Source channel count when the source has more channels than the stereo transport, else 0.
     *
     * A 5.1/7.1 source (EAC3, AC3, multichannel AAC/FLAC) written interleaved into a two-channel
     * USB stream is not a quieter or coarser version of the music, it is garbage: every frame is
     * split across three transport frames. Measured on the HONOR with an EAC3-JOC 5.1 file: the
     * stream was opened `ch=6` against a stereo DAC and the output was unlistenable. Such a source
     * cannot be bit-perfect on a stereo DAC in any case, so it is downmixed here and the native
     * engine (which cannot downmix) is not used for it.
     */
    @Volatile
    private var downmixSourceChannels = 0

    /**
     * A mono source on the stereo transport. It used to be framed with one channel over a two-channel
     * alt, so the DAC read half the data per microframe as L/R (AUD-012). The stream is opened with
     * two channels and every sample is duplicated exactly ([MonoUpmix]); the native engine, which
     * cannot upmix, is not used for it.
     */
    @Volatile
    private var upmixMonoSource = false

    // ── Signal-mode transitions (pushed, not discovered) ────────────────

    /**
     * Serializes transitions and collapses rapid requests; see [DirectSignalModeReconciler].
     *
     * Guarded by [playbackHandler]'s thread, never touched from the caller's.
     */
    private val signalModeReconciler = DirectSignalModeReconciler()

    /**
     * The looper the sink's lifecycle runs on, captured at [configure].
     *
     * Transitions create and destroy the native engine and the streaming thread, and every other
     * part of this sink does that from the playback thread — `configure`, `flush` and `release` all
     * run there. Marshalling onto the same thread is therefore what lets a transition reuse that
     * lifecycle without introducing a second lock to reason about.
     */
    @Volatile
    private var playbackHandler: Handler? = null

    /**
     * Tells the Direct pipeline that the host's desired signal mode may have changed.
     *
     * This exists because the PCM path cannot be the observer. While the native engine owns a local
     * FLAC it writes to USB from its own thread and `NativeEngineAwareLoadControl` stops ExoPlayer
     * from loading, so `handleBuffer` may never be called again for that track. A mode change
     * discovered only there is a mode change that never happens — measured on the HONOR as a
     * `DIRECT_PROCESSED` route with `modeTransitions=0`, `nativeEngineStops=0` and the native engine
     * still playing untouched source, followed by a `StuckPlayerException` once the renderer had
     * been silent for ten seconds.
     *
     * Safe to call from any thread. Returns immediately; the reconcile runs on the playback thread.
     * A call made before the sink has been configured is a no-op, because [configure] resolves the
     * mode itself and would reach the same state.
     */
    fun requestSignalMode() {
        val handler = playbackHandler ?: return
        if (!signalModeReconciler.onRequest()) return
        handler.post {
            reconcileSignalMode()
            // Republished even when no transition was needed. The active-processor set is derived
            // from the host's gain contributors, and one of those can change without the mode
            // changing at all — engaging the user's volume while ReplayGain already holds the
            // stream in PROCESSED. Refreshing only on a mode change left the truth naming one
            // cause when there were two.
            publishSignalTruth()
        }
    }

    /**
     * Pushes an explicit media-position seek to the native producer.
     *
     * The host supplies the target, in media-item time, at the moment the user asked for it. The
     * sink does not rediscover it: reconstructing a user seek later from `presentationTimeUs`,
     * `windowOffsetUs` or `player.currentPosition` is what the old path did, and it silently dropped
     * the seek whenever the renderer was starved — which in PRISTINE is the normal case, because
     * `NativeEngineAwareLoadControl` is deliberately not loading ExoPlayer any PCM. The engine kept
     * playing from its old position and the player's report converged back onto it, so the seek
     * looked accepted and was never performed.
     *
     * Latest wins. Each call takes a new generation; the decoder performs whichever request is
     * newest and acknowledges that generation, so the intermediate targets of a scrub coalesce and
     * a slow older seek can neither resume playback nor publish itself as the current position.
     *
     * Safe to call from any thread. A call with no live engine is a no-op — there is nothing to
     * move, and the engine that is created later is brought up at its own start target instead.
     */
    fun requestMediaSeek(targetUs: Long) {
        if (!config.directEnabled) return
        // AUD-138: past the end is the end — a natural end, the next item from 0. The overshoot was
        // carried: the next item started at 6.7 s and Media3 read 229 s against the engine's 7.7 s.
        val seekUs = OffsetAuthority.seekTargetWithinItem(
            targetUs, nativeEngine?.takeIf { it.isCreated }?.getDurationUs() ?: 0L,
        )
        naturalEnd.onUserSeek()
        // NEW_POSITION_EPOCH, opened here rather than in the issuer, and deliberately ahead of the
        // engine guard below. The epoch is a statement about the *position*, not about whether an
        // engine exists to receive the seek: a seek issued while no engine is live still moves the
        // player, and if its epoch were skipped the next buffer would latch a fresh presentation
        // timestamp against the previous position's anchor. Measured: a seek-bar seek to 350s with
        // no engine open left the anchor at 312ms and produced an offset delta of +350.136s.
        openEpoch(seekUs, AnchorSource.EXPLICIT_SEEK_TARGET, "user-seek")
        val engine = nativeEngine
        if (engine == null || !engine.isRunning) {
            // A finished engine still has a handle but no decode thread: a seek handed to it would
            // be accepted and never acknowledged, leaving the command owed forever. Hold the target
            // for the engine that the coming flush creates instead.
            seekAwaitingEngineUs = seekUs
            DirectPathTelemetry.recordIfCapturing("SEEK") {
                "deferred target=${seekUs / 1000}ms reason=${if (engine == null) "no-engine" else "engine-finished"}"
            }
            return
        }
        seekAwaitingEngineUs = -1L

        // The engine owns the pause state here: a seek issued while paused repositions the decoder
        // and then stops at the pause check, so playback stays paused and the first audible PCM
        // after resume comes from the target. No play() is required to make the seek take effect.
        DirectPathTelemetry.diagnosticRebaseTargetUs = seekUs
        recordMapping(
            "SEEK_EVENT",
            "phase=request targetUs=$seekUs gen=${mappingGeneration.get()} " +
                mappingOperandState() + " " + mappingPlayerState(),
        )
        val accepted = issueExplicitSeek(engine, seekUs, "user")
        if (!accepted) {
            DirectPathTelemetry.seekCompletedOk = false
            Log.w(TAG, "Media seek to ${seekUs / 1000}ms was refused")
        }
    }

    /**
     * A user seek that arrived with no live engine, in media-item µs, or -1.
     *
     * Consumed by the next [flush], which is the position reset that seek produces: if flush brings
     * up an engine, the target becomes its explicit command. Cleared there either way, because the
     * request describes exactly one reset.
     */
    @Volatile
    private var seekAwaitingEngineUs = -1L

    /** Next seek generation; monotonic for the life of the sink. */
    private val seekGeneration = AtomicLong(0)

    /**
     * Ownership state for the newest **explicit** command — a user seek or an item start.
     *
     * Media position has one owner at a time, and an explicit command outranks anything the sink
     * could infer, until it has actually completed. Generation ordering alone cannot express that,
     * because an inferred realign can mint a newer generation freely: on the HONOR, an explicit
     * item-start seek of `0` (gen=3) was superseded by a realign that inferred `356893ms` from a
     * contaminated player position and issued gen=4, seeked the engine to EOF and ended playback
     * 20 ms after it began. The explicit target was never wrong; it was outranked.
     *
     * So the newest explicit generation is tracked separately and compared against what the decoder
     * has acknowledged. While it is unresolved, an inferred realign may **carry** it across a
     * transition but may not replace it.
     */
    private val explicitSeekGeneration = AtomicLong(0)
    private val explicitSeekTargetUs = AtomicLong(-1L)

    /** Last completed generation seen from an engine, kept for when no engine is live. */
    @Volatile
    private var explicitSeekCompletedGeneration = 0L

    /**
     * True while an explicit command owns the position — outstanding *or* failed.
     *
     * A FAILED command keeps ownership deliberately. It is terminal for its generation, but it is
     * not successful ownership: the decoder is somewhere other than the requested target, so
     * letting an inferred realign take over would silently substitute a position nobody asked for,
     * and letting playback resume would play from wherever the decoder landed. Ownership is
     * released only by a new explicit command or a new item start — the product's normal
     * recovery flows.
     */
    private fun explicitSeekOwed(): Boolean {
        val pending = explicitSeekGeneration.get()
        if (pending == 0L) return false
        // A command belongs to the track it was given for. Generations are compared against the
        // *current* engine, and every item change builds a new one, so without this a seek made on
        // the previous track reads as owed to the next — measured: a new track started at the
        // previous track's 28 s seek target instead of 0.
        if (explicitSeekItemPath != currentTrackPath) return false
        if (explicitSeekFailed) return true
        val engine = nativeEngine
        val completed =
            if (engine != null && engine.isCreated) engine.completedSeekGeneration
            else explicitSeekCompletedGeneration
        return pending > completed
    }

    /** The track [explicitSeekGeneration] was issued for; see [explicitSeekOwed]. */
    @Volatile
    private var explicitSeekItemPath: String? = null

    /** Set when the newest explicit command reported FAILED; cleared by new intent. */
    @Volatile
    private var explicitSeekFailed: Boolean = false

    /**
     * Handles a failed explicit seek.
     *
     * The one thing that must not happen is playback continuing as though the target had been
     * reached. The queued pre-seek PCM was already discarded by the barrier, so there is no stale
     * audio to leak; what remains is to stop, and to keep the position owned so nothing infers a
     * replacement.
     *
     * This pauses rather than throwing into Media3's error path: an `AudioSink` has a `WriteException`
     * channel for write failures but none for a failed seek, and inventing a new fatal path for this
     * case was explicitly out of scope. Wired to the product's playback-error surface is a follow-up
     * decision, not something to guess at here.
     */
    private fun onExplicitSeekFailed(generation: Long) {
        explicitSeekFailed = true
        val targetMs = explicitSeekTargetUs.get() / 1000
        Log.e(TAG, "Explicit seek gen=$generation to ${targetMs}ms FAILED — target not reached")
        DirectPathTelemetry.recordIfCapturing("SEEK") {
            "FAILED gen=$generation target=${targetMs}ms — target NOT reached, ownership retained"
        }
        Handler(Looper.getMainLooper()).post { attachedPlayer?.pause() }
    }

    /**
     * Issues an explicit media-position command and takes ownership of the position.
     *
     * @param what `"user"` or `"item-start"` — recorded so a transition record names the authority.
     */
    private fun issueExplicitSeek(engine: NativeAudioEngine, targetUs: Long, what: String): Boolean {
        val generation = seekGeneration.incrementAndGet()
        explicitSeekItemPath = currentTrackPath
        explicitSeekGeneration.set(generation)
        explicitSeekTargetUs.set(targetUs)
        explicitSeekIssuedAtResetCount = positionResetCount
        // New intent clears a previous failure: the command that failed is superseded, so ownership
        // transfers to this one rather than staying stuck on the old failure.
        explicitSeekFailed = false
        // URB ownership on both sides of the barrier, so a leak across repeated seeks is visible
        // in the capture rather than only in logcat.
        DirectPathTelemetry.urbDiagBeforeSeek = usbAudioStream?.urbDiag ?: "urb=none"
        DirectPathTelemetry.seekRequestedGeneration = generation
        DirectPathTelemetry.seekTargetUs = targetUs

        val accepted = seekEngine(engine, targetUs, generation, what)
        DirectPathTelemetry.recordIfCapturing("SEEK") {
            "$what gen=$generation target=${targetUs / 1000}ms accepted=$accepted " +
                "playing=$isPlaying mode=$signalMode"
        }
        return accepted
    }

    /**
     * Re-issues the outstanding explicit command to a replacement engine.
     *
     * A PROCESSED → PRISTINE realign builds a new engine, which knows nothing of the command the
     * old one was given. Carrying the same target and generation across keeps one owner: the
     * alternative — inferring a fresh target from whatever the player happens to report — is
     * exactly how a correct `0` became an EOF seek.
     */
    private fun carryExplicitSeek(engine: NativeAudioEngine): Boolean {
        val generation = explicitSeekGeneration.get()
        val targetUs = explicitSeekTargetUs.get()
        if (generation == 0L || targetUs < 0) return false
        val accepted = seekEngine(engine, targetUs, generation, "carried")
        DirectPathTelemetry.recordIfCapturing("SEEK") {
            "carried gen=$generation target=${targetUs / 1000}ms accepted=$accepted"
        }
        return accepted
    }

    /** AUD-139: who placed the current engine; a renderer buffer places it only where nobody has. */
    private val enginePlacement = EnginePlacement()

    /**
     * AUD-139: the persistent ring of engine seeks and anchor/offset changes, written like the route
     * trace. `files/diagnostics/position-trace.txt` in the app's external files; the previous
     * session's lines are kept ahead of this one's.
     */
    private val positionAudit by lazy {
        PositionTraceLog(
            maxLines = POSITION_AUDIT_MAX_LINES,
            executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PositionAudit").apply { isDaemon = true }
            },
            clock = { java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()) },
        ) { lines ->
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(File(base, "diagnostics").apply { mkdirs() }, "position-trace.txt")
            val previous = previousPositionAudit ?: (if (file.isFile) file.readLines() else emptyList()).also {
                previousPositionAudit = it
            }
            file.writeText((previous + lines).takeLast(POSITION_AUDIT_MAX_LINES).joinToString("\n"))
        }
    }

    /** The position trace as the previous session left it; PositionAudit thread only. */
    private var previousPositionAudit: List<String>? = null

    /** One line of the position trace, with Media3's and the engine's positions at this instant. */
    private fun auditPosition(event: String, origin: String, detail: String) {
        val engineMs = nativeEngine?.takeIf { it.isCreated }?.getPositionUs()?.div(1000) ?: -1L
        positionAudit.record(event, origin, playerMirror?.positionMs ?: -1L, engineMs,
            "$detail offsetUs=$windowOffsetUs item=${currentTrackPath?.let { File(it).name } ?: "-"}")
    }

    /**
     * The only way the native engine is sent to a position (AUD-139): every seek is recorded, with its
     * origin, in the persistent trace, and marks the engine as placed for this position reset.
     */
    private fun seekEngine(engine: NativeAudioEngine, targetUs: Long, generation: Long, origin: String): Boolean {
        val accepted = engine.seek(targetUs, generation)
        enginePlacement.onPlaced(engine, positionResetCount, origin)
        positionAudit.record("ENGINE_SEEK", origin, playerMirror?.positionMs ?: -1L,
            engine.takeIf { it.isCreated }?.getPositionUs()?.div(1000) ?: -1L,
            "target=${targetUs / 1000}ms gen=$generation accepted=$accepted resets=$positionResetCount " +
                "offsetUs=$windowOffsetUs item=${currentTrackPath?.let { File(it).name } ?: "-"}")
        return accepted
    }

    /** Every new mapping epoch (anchor) goes through here and into the position trace (AUD-139). */
    private fun openEpoch(anchorUs: Long, source: AnchorSource, origin: String): MappingState.Unlatched {
        val opened = mappingTracker.beginEpoch(anchorUs, source)
        auditPosition("ANCHOR", origin, "anchor=${anchorUs / 1000}ms source=$source")
        return opened
    }

    /** Every latch (offset) goes through here and into the position trace (AUD-139). */
    private fun latchEpoch(presentationTimeUs: Long, origin: String): MappingState {
        val before = windowOffsetUs
        val state = mappingTracker.latch(presentationTimeUs)
        if (windowOffsetUs != before) {
            auditPosition("OFFSET", origin, "pts=$presentationTimeUs from=$before anchor=${mappingTracker.anchorMediaPositionUs / 1000}ms")
        }
        return state
    }

    /**
     * Bounded, time-throttled trace of every clock in the position chain.
     *
     * Sampled at most every [POSITION_TRACE_INTERVAL_MS] so ~[POSITION_TRACE_CAPACITY] entries cover
     * well over the stuck detector's 10 s window — the point is to still hold the seconds *before* a
     * stall when the stall is finally noticed, which a tail-only view cannot do.
     *
     * Every field is read at the same instant, from inside the position query itself, so the layers
     * can be compared at one timestamp rather than reconstructed from unrelated logs.
     */
    private class PositionTrace(
        val t: Long,
        val enginePositionUs: Long,
        val windowOffsetUs: Long,
        val sinkReturnedUs: Long,
        val playerPositionMs: Long,
        val playbackState: Int,
        val isPlaying: Boolean,
        val queryIndex: Long,
        val advance: Int,
        val mirrorAgeMs: Long,
        val mirrorSeq: Long,
        val pendingOperationAcks: Int,
        val appInfoPositionUs: Long,
    )

    /**
     * Snapshot of the Player's own getters, sampled on the Player's application looper.
     *
     * Player getters may only be called from that thread. Reading them from the playback thread
     * throws, and hiding that behind `runCatching` turned every sample into `-1` — a diagnostic that
     * silently reported a value no layer ever produced. The mirror is updated on the correct looper
     * and read from anywhere.
     *
     * `atMs` is not optional: a frozen position with a *fresh* mirror means the Player is reachable
     * and actively returning that value, while a frozen position with a *stale* mirror means the
     * sampling thread stopped running. Those are different failures and must not be conflated.
     */
    private class PlayerMirror(
        val positionMs: Long,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val isPlaying: Boolean,
        val seq: Long,
        val atMs: Long,
        /**
         * ExoPlayerImpl.pendingOperationAcks, or -1 when it could not be read.
         *
         * Verified present in the shipped 1.10.1 bytecode: handlePlaybackInfo does
         * `pendingOperationAcks -= update.operationAcks` and then `ifne` past the assignment of
         * `this.playbackInfo`. While it is non-zero, every incoming PlaybackInfo is discarded and
         * the app-side position stays pinned even though the internal player keeps running — which
         * is exactly the observed shape (frozen position, READY, isPlaying, fresh mirror).
         */
        val pendingOperationAcks: Int,
        /** ExoPlayerImpl.playbackInfo.positionUs, microseconds; -1 when unreadable. */
        val appPlaybackInfoPositionUs: Long,
        val probeOk: Boolean,
    )

    /**
     * Reads playback state that the Player API does not expose.
     *
     * Reflection only — nothing is written, and a failure is reported as `probeOk = false` rather
     * than being turned into a plausible number.
     */
    private fun probeAckState(player: Player): PlayerMirrorAck {
        var target: Any? = player
        var depth = 0
        while (target != null && depth < 4) {
            val cls = target.javaClass
            if (cls.name.endsWith("ExoPlayerImpl")) {
                return try {
                    // Handles are resolved once per class and cached: rediscovering them on every
                    // sample was the observer's most expensive steady-state action, and a
                    // diagnostic must not perturb the thing it measures.
                    val handles = ackHandlesFor(cls)
                    val acks = handles.acks.getInt(target)
                    val info = handles.playbackInfo.get(target)
                    val posUs = handles.positionUs.getLong(info)
                    PlayerMirrorAck(acks, posUs, true)
                } catch (t: Throwable) {
                    Log.w(TAG, "ack probe failed on ${cls.name}: ${t.message}")
                    PlayerMirrorAck(-1, -1L, false)
                }
            }
            target = try {
                cls.getMethod("getWrappedPlayer").invoke(target)
            } catch (t: Throwable) {
                null
            }
            depth++
        }
        return PlayerMirrorAck(-1, -1L, false)
    }

    private class PlayerMirrorAck(val pendingAcks: Int, val infoPositionUs: Long, val ok: Boolean)

    /** "Not probed": outside a capture the mirror carries no ack state (AUD-060). */
    private val NO_ACK_PROBE = PlayerMirrorAck(-1, -1L, false)

    private class AckHandles(
        val acks: java.lang.reflect.Field,
        val playbackInfo: java.lang.reflect.Field,
        val positionUs: java.lang.reflect.Field,
    )

    private val ackHandleCache = java.util.concurrent.ConcurrentHashMap<Class<*>, AckHandles>()

    private fun ackHandlesFor(cls: Class<*>): AckHandles {
        ackHandleCache[cls]?.let { return it }
        val acks = cls.getDeclaredField("pendingOperationAcks").apply { isAccessible = true }
        val info = cls.getDeclaredField("playbackInfo").apply { isAccessible = true }
        val posUs = info.type.getField("positionUs")
        return AckHandles(acks, info, posUs).also { ackHandleCache[cls] = it }
    }

    /**
     * One entry in the ACK ledger.
     *
     * The count is sampled on the Player's own looper, so an increment is caught within a tick of
     * the operation that caused it. `detail` carries the opId/operation when the change was
     * attributed at an operation boundary by [notePlayerOperation], and is empty when it was only
     * observed as a count change.
     */
    private class AckEvent(
        val seq: Long,
        val atMs: Long,
        val kind: String,
        val pending: Int,
        val appInfoUs: Long,
        val playerMs: Long,
        val detail: String,
    )

    private val ackLedger = ArrayDeque<AckEvent>()
    private var ackLedgerSeq = 0L
    private var lastAckSample = Int.MIN_VALUE
    private var lastLedgerHeldPending = -1
    private var lastLedgerWasHeld = false

    /**
     * Attributed at an operation boundary.
     *
     * Called by the host immediately before and after forwarding a Player operation, so an
     * increment is tied to the actual operation rather than merely observed as a count change.
     * Media3's protocol acknowledges counts, not individual operations, so this records what the
     * application issued — it does not claim which ACK resolves which operation.
     */
    /**
     * Reads `pendingOperationAcks` live, rather than from the 100 ms mirror.
     *
     * A synchronous read is what makes attribution exact: the count is sampled immediately before
     * and after one Player call, so a transition observed inside that window belongs to that call
     * rather than merely happening near it. Operations are rare, so the reflection cost is
     * irrelevant.
     */
    fun diagnosticPendingAcks(): Int {
        // Reflection only while a capture records it (AUD-060).
        if (!DirectPathTelemetry.captureEnabled) return -1
        val player = attachedPlayer ?: return Int.MIN_VALUE
        return probeAckState(player).pendingAcks
    }

    fun notePlayerOperation(phase: String, operation: String, pendingAcks: Int) {
        recordAckEvent(
            kind = "OP_$phase",
            pending = pendingAcks,
            appInfoUs = playerMirror?.appPlaybackInfoPositionUs ?: -1L,
            playerMs = playerMirror?.positionMs ?: -1L,
            detail = operation,
        )
    }

    private fun recordAckEvent(kind: String, pending: Int, appInfoUs: Long, playerMs: Long, detail: String) {
        if (!DirectPathTelemetry.captureEnabled) return
        synchronized(ackLedger) {
            ackLedgerSeq++
            if (ackLedger.size >= ACK_LEDGER_CAPACITY) ackLedger.removeFirst()
            ackLedger.addLast(
                AckEvent(
                    seq = ackLedgerSeq,
                    atMs = SystemClock.elapsedRealtime(),
                    kind = kind,
                    pending = pending,
                    appInfoUs = appInfoUs,
                    playerMs = playerMs,
                    detail = detail,
                ),
            )
        }
    }

    /** Ledger for the capture; oldest first, so the stall can be read forward from startup. */
    fun ackLedgerReport(): String {
        val snapshot: List<AckEvent>
        synchronized(ackLedger) { snapshot = ackLedger.toList() }
        if (snapshot.isEmpty()) return "ledger=empty"
        val sb = StringBuilder()
        sb.append("ledgerEntries=").append(snapshot.size)
            .append(" nonZeroEvents=").append(snapshot.count { it.kind.startsWith("ACK_INC") || it.kind == "APP_INFO_HELD" })
            .append(" held=").append(snapshot.count { it.kind == "APP_INFO_HELD" })
        snapshot.takeLast(60).forEach { e ->
            sb.append("\n  ").append(e.atMs).append(' ').append(e.kind)
                .append(" pending=").append(e.pending)
                .append(" appInfo=").append(e.appInfoUs)
                .append(" player=").append(e.playerMs)
                .append(if (e.detail.isNotEmpty()) " " + e.detail else "")
        }
        return sb.toString()
    }

    @Volatile
    private var playerMirror: PlayerMirror? = null
    private var playerMirrorSeq = 0L
    @Volatile
    private var playerMirrorHandler: Handler? = null

    /** Starts sampling the Player on its application looper; idempotent per attachment. */
    private fun installPlayerMirror(player: Player) {
        val handler = Handler(player.applicationLooper)
        playerMirrorHandler = handler
        val tick = object : Runnable {
            override fun run() {
                val p = attachedPlayer
                if (p != null) {
                    // No runCatching: this runs on the Player's own looper, so a throw here is a
                    // real defect and must not be silently converted into a plausible value.
                    // The ack ledger is diagnostic: reflecting into ExoPlayerImpl every 250 ms for it
                    // only runs while a Direct-path capture is recording (AUD-060).
                    val ack = if (DirectPathTelemetry.captureEnabled) probeAckState(p) else NO_ACK_PROBE
                    val playerMsNow = p.currentPosition
                    // Ledger: count transitions, and adopt-vs-hold at the publication gate.
                    if (ack.ok) {
                        if (lastAckSample == Int.MIN_VALUE) {
                            lastAckSample = ack.pendingAcks
                        } else if (ack.pendingAcks != lastAckSample) {
                            recordAckEvent(
                                kind = if (ack.pendingAcks > lastAckSample) "ACK_INC" else "ACK_DEC",
                                pending = ack.pendingAcks,
                                appInfoUs = ack.infoPositionUs,
                                playerMs = playerMsNow,
                                detail = "delta=${ack.pendingAcks - lastAckSample}",
                            )
                            lastAckSample = ack.pendingAcks
                        }
                        // Record TRANSITIONS, not the steady state. Adoption happens on every
                        // 100 ms tick, so logging each one flooded the bounded ledger and pushed
                        // out the rare PLAYER_OP events it exists to correlate with.
                        if (ack.pendingAcks > 0) {
                            // The gate is closed. Recorded once per distinct pending count, so a
                            // count that sticks is visible as one entry while a changing count
                            // still shows its progression.
                            if (lastLedgerHeldPending != ack.pendingAcks) {
                                recordAckEvent(
                                    kind = "APP_INFO_HELD",
                                    pending = ack.pendingAcks,
                                    appInfoUs = ack.infoPositionUs,
                                    playerMs = playerMsNow,
                                    detail = "incoming discarded while count non-zero",
                                )
                                lastLedgerHeldPending = ack.pendingAcks
                                lastLedgerWasHeld = true
                            }
                        } else if (lastLedgerWasHeld) {
                            // First tick back at zero: the gate opened again.
                            recordAckEvent(
                                kind = "APP_INFO_ADOPT_RESUMED",
                                pending = 0,
                                appInfoUs = ack.infoPositionUs,
                                playerMs = playerMsNow,
                                detail = "gate re-opened",
                            )
                            lastLedgerWasHeld = false
                            lastLedgerHeldPending = -1
                        }
                    }
                    playerMirrorSeq++
                    playerMirror = PlayerMirror(
                        positionMs = playerMsNow,
                        playbackState = p.playbackState,
                        playWhenReady = p.playWhenReady,
                        isPlaying = p.isPlaying,
                        seq = playerMirrorSeq,
                        atMs = SystemClock.elapsedRealtime(),
                        pendingOperationAcks = ack.pendingAcks,
                        appPlaybackInfoPositionUs = ack.infoPositionUs,
                        probeOk = ack.ok,
                    )
                }
                if (playerMirrorHandler === handler) handler.postDelayed(this, PLAYER_MIRROR_INTERVAL_MS)
            }
        }
        handler.post(tick)
    }

    private fun stopPlayerMirror() {
        playerMirrorHandler?.removeCallbacksAndMessages(null)
        playerMirrorHandler = null
        playerMirror = null
    }

    private val positionTrace = ArrayDeque<PositionTrace>()
    private var lastTraceAtMs = 0L
    private var positionQueryCount = 0L
    private var lastTracedSinkUs = Long.MIN_VALUE
    private var lastAdvanceAtMs = 0L
    private var lastAdvanceCount = 0

    // ── Mapping-epoch diagnostics ───────────────────────────────────────────
    //
    // `windowOffsetUs` is latched once per arm from two operands that live in different epochs:
    // `presentationTimeUs`, which is fresh off the buffer being handled, and `initialPlayerPositionUs`,
    // which is written only at a media-item transition and — decisively — is NOT rewritten when the
    // latch re-arms. The arm sites are the four that clear the offset to -1 and the two that only
    // raise `usbStartMediaTimeNeedsInit`; none of them touch the operand. So if the player's media
    // position has moved since the last item transition, the latch bakes the whole difference into
    // the mapping for the life of the arm, and every later position read is wrong by that constant.
    //
    // The generation counter makes "different epochs" a recorded fact rather than an inference from
    // timestamps: it advances at every boundary that can invalidate the Player-to-presentation-
    // timestamp relationship, and both operands are tagged with the generation current when they
    // were produced. Diagnostic only — nothing in the playback path reads any of this.
    private val mappingGeneration = AtomicLong(0)
    private val mappingLog = ArrayDeque<MappingEvent>()
    private var mappingSeq = 0L

    private class MappingEvent(val t: Long, val seq: Long, val kind: String, val detail: String)

    private fun recordMapping(kind: String, detail: String): Long {
        var seq = 0L
        val at = SystemClock.elapsedRealtime()
        synchronized(mappingLog) {
            if (mappingLog.size >= MAPPING_LOG_CAPACITY) mappingLog.removeFirst()
            seq = ++mappingSeq
            mappingLog.addLast(MappingEvent(at, seq, kind, detail))
        }
        // Also emitted to logcat. Mapping events are rare transitions, so this is a handful of
        // lines per session — and it is the channel that is *known* to deliver: the capture's
        // optional report blocks are published through a path that has been silently dropping
        // them, and a diagnostic must not depend on a channel that cannot be shown to work.
        if (DirectPathTelemetry.captureEnabled) Log.i(TAG, "MAPPING|$at|$seq|$kind|$detail")
        return seq
    }

    /**
     * Advances the mapping generation and records the boundary that did it.
     *
     * Boundaries are the sites where the player's media position can move without a new operand
     * being written: an item transition, a position reset (flush), and a sink configure. The latch
     * arm sites deliberately do NOT advance it — arming does not move the position, and advancing
     * there would label a normal fresh start as cross-epoch.
     */
    private fun advanceMappingGeneration(reason: String, detail: String): Long {
        val g = mappingGeneration.incrementAndGet()
        recordMapping("GEN_ADVANCE", "gen=$g reason=$reason $detail")
        return g
    }

    /** The Player's own view, for correlating a mapping event with what the user could see. */
    private fun mappingPlayerState(): String {
        val m = playerMirror ?: return "player=-1 state=-1 pwr=UNKNOWN isPlaying=false mirrorAgeMs=-1"
        return "player=${m.positionMs} state=${m.playbackState} pwr=${m.playWhenReady} " +
            "isPlaying=${m.isPlaying} appInfo=${m.appPlaybackInfoPositionUs} " +
            "acks=${m.pendingOperationAcks} mirrorAgeMs=${SystemClock.elapsedRealtime() - m.atMs}"
    }

    /** Raw relationship of the two latch operands, unrounded, for the capture to compare. */
    private fun mappingOperandState(): String =
        "operandUs=$initialPlayerPositionUs operandGen=$initialPlayerPositionGeneration " +
            "operandSetSeq=$initialPlayerPositionSetSeq pendingUs=$pendingStartTargetUs " +
            "windowOffsetUs=$windowOffsetUs " +
            "offsetDeltaFromBaseUs=${if (windowOffsetUs < 0) Long.MIN_VALUE else windowOffsetUs - RENDERER_DOMAIN_FLOOR_US}"

    /** Sequence number of the most recent mapping event of [kind], or -1 if none recorded yet. */
    private fun lastMappingSeqOf(kind: String): Long {
        synchronized(mappingLog) {
            for (e in mappingLog.reversed()) if (e.kind == kind) return e.seq
        }
        return -1L
    }

    /**
     * One-line precondition reading for the control reconstruction.
     *
     * Emitted on demand rather than sampled: the question is whether a specific combination holds at
     * one instant — offset unlatched, Player ended, operand still the EOF-era value, no item
     * transition in between — and a periodic sample cannot be compared against that instant.
     */
    /**
     * Diagnostic only: whether the pre-trigger state a controlled provocation requires is present.
     *
     * Cheap enough to poll — two field reads — because the natural STATE_ENDED window is short and
     * the provocation has to land inside it.
     */
    /** Diagnostic only: whether the mirror last saw the Player in STATE_ENDED. */
    fun provocationMirrorEnded(): Boolean = playerMirror?.playbackState == 4

    /** Diagnostic only: whether the native engine is absent, read live rather than from the mirror. */
    fun provocationEngineAbsent(): Boolean = nativeEngine == null

    fun provocationReady(): Boolean {
        val m = playerMirror ?: return false
        return m.playbackState == 4 /* Player.STATE_ENDED */ && nativeEngine == null
    }

    /**
     * Diagnostic only: the compact state the readiness probe is evaluating.
     *
     * Exists because a readiness probe that only ever answers true/false cannot explain a miss — the
     * previous version wrapped its call in `runCatching` and returned false on any failure, which is
     * indistinguishable from "the window never opened". This reports the inputs instead.
     */
    fun provocationStateBrief(livePlaybackState: Int): String {
        val engine = nativeEngine
        val m = playerMirror
        return "liveState=$livePlaybackState mirrorState=${m?.playbackState ?: -1}" +
            " enginePresent=${engine != null} engineRunning=${engine?.isRunning}" +
            " offsetUnlatched=${windowOffsetUs < 0} offsetUs=$windowOffsetUs" +
            " needsInit=$usbStartMediaTimeNeedsInit" +
            " operandUs=$initialPlayerPositionUs operandGen=$initialPlayerPositionGeneration" +
            " operandSetSeq=$initialPlayerPositionSetSeq" +
            " lastInitialPosSetSeq=${lastMappingSeqOf("INITIAL_POS_SET")}" +
            " signalMode=${DirectPathTelemetry.signalMode}" +
            " modeTransitions=${DirectPathTelemetry.modeTransitions.get()}"
    }

    /**
     * Diagnostic only: unlatch the mapping offset by invoking the production cleanup transition.
     *
     * This manipulates exactly one state variable — it calls the same `cleanupFinishedEngine()` the
     * production `flush()` and `onMediaItemTransition` paths call, and then deliberately does NOT
     * call `createEngineIfNeeded()`. No field is assigned a historical value, no timestamp is
     * injected, and the operand is not touched. The seek/configure/latch chain that follows is
     * entirely production code and unmodified.
     *
     * Refuses unless the preconditions genuinely hold, so an invalid state can never be reported as
     * a valid provocation.
     */
    fun provokeMappingUnlatch(livePlaybackState: Int): String {
        // Engine presence is deliberately NOT a precondition. The historical evidence does not prove
        // the engine's state at the trigger, and requiring absence would contradict `isEnded()`,
        // which reports ended only while the engine is present and stopped. Engine state is recorded
        // before and after instead, and the provocation's validity check requires it unchanged.
        val engineBefore = nativeEngine
        val stateBefore = livePlaybackState
        val offsetBefore = windowOffsetUs
        val needsInitBefore = usbStartMediaTimeNeedsInit
        val operandBefore = initialPlayerPositionUs
        val setSeqBefore = initialPlayerPositionSetSeq
        val itemSeqBefore = lastMappingSeqOf("INITIAL_POS_SET")

        if (stateBefore != 4 /* Player.STATE_ENDED */) {
            return "PROVOCATION_INVALID reason=not-ended" +
                " playbackStateBefore=$stateBefore enginePresentBefore=${engineBefore != null}" +
                " engineRunningBefore=${engineBefore?.isRunning}" +
                " offsetBeforeUs=$offsetBefore operandBeforeUs=$operandBefore"
        }

        // The diagnostic provocation used to write the unlatched sentinel here. It cannot any more,
        // and deliberately so: the mapping is owned by the epoch tracker, which has no generic
        // "clear the offset but leave the anchor" operation — that operation was the defect. The
        // probe is retained as a read-only precondition check.
        val engineAfter = nativeEngine
        val operandAfter = initialPlayerPositionUs
        val setSeqAfter = initialPlayerPositionSetSeq
        val itemSeqAfter = lastMappingSeqOf("INITIAL_POS_SET")
        // Valid only if the ONLY deliberate change is the mapping latch state: engine untouched,
        // operand untouched, item-transition history untouched, Player state untouched (the caller
        // re-reads it; `stateAfter` here is the sink's view of the same instant).
        val valid = windowOffsetUs < 0 && usbStartMediaTimeNeedsInit &&
            engineAfter === engineBefore && stateBefore == 4

        return "PROVOCATION_APPLIED valid=$valid" +
            " playbackStateBefore=$stateBefore" +
            " enginePresentBefore=${engineBefore != null} enginePresentAfter=${engineAfter != null}" +
            " engineRunningBefore=${engineBefore?.isRunning} engineRunningAfter=${engineAfter?.isRunning}" +
            " offsetBeforeUs=$offsetBefore offsetAfterUs=$windowOffsetUs" +
            " needsInitBefore=$needsInitBefore needsInitAfter=$usbStartMediaTimeNeedsInit" +
            " initialPlayerPositionBeforeUs=$operandBefore initialPlayerPositionAfterUs=$operandAfter" +
            " itemSeqBefore=$itemSeqBefore itemSeqAfter=$itemSeqAfter" +
            " rebaseSwitch=${DirectPathTelemetry.diagnosticRebaseOperandOnSeek}" +
            " provocationSeq=${recordMapping("PROVOCATION", "offsetBeforeUs=$offsetBefore offsetAfterUs=$windowOffsetUs operandUs=$operandAfter liveState=$stateBefore")}"
    }

    private fun needsInitAfter(): Boolean = usbStartMediaTimeNeedsInit

    fun controlPreconditionReport(): String {
        val m = playerMirror
        val engine = nativeEngine
        val running = engine?.isRunning
        return "windowOffsetUs=$windowOffsetUs needsInit=$usbStartMediaTimeNeedsInit " +
            "offsetUnlatched=${windowOffsetUs < 0} " +
            "initialPlayerPositionUs=$initialPlayerPositionUs " +
            "operandGen=$initialPlayerPositionGeneration " +
            "operandSetSeq=$initialPlayerPositionSetSeq pendingStartTargetUs=$pendingStartTargetUs " +
            "player=${m?.positionMs ?: -1L} playbackState=${m?.playbackState ?: -1} " +
            "playWhenReady=${m?.playWhenReady ?: "UNKNOWN"} isPlaying=${m?.isPlaying ?: false} " +
            "appInfo=${m?.appPlaybackInfoPositionUs ?: -1L} mirrorAgeMs=" +
            (if (m == null) -1L else SystemClock.elapsedRealtime() - m.atMs) + " " +
            "engineExists=${engine != null} engineRunning=$running " +
            "engineFinished=${engine != null && running == false} " +
            "signalMode=${DirectPathTelemetry.signalMode} " +
            "modeTransitions=${DirectPathTelemetry.modeTransitions.get()} " +
            "lastOffsetResetSeq=${lastMappingSeqOf("OFFSET_RESET")} " +
            "lastArmOnlySeq=${lastMappingSeqOf("OFFSET_ARM_ONLY")} " +
            "lastInitialPosSetSeq=${lastMappingSeqOf("INITIAL_POS_SET")} " +
            "lastItemTransitionSeq=${lastMappingSeqOf("GEN_ADVANCE")} " +
            "lastLatchSeq=${lastMappingSeqOf("OFFSET_LATCH")} " +
            "lastFlushSeq=${lastMappingSeqOf("SEEK_EVENT")}"
    }

    fun mappingReport(): String {
        val snapshot: List<MappingEvent>
        synchronized(mappingLog) { snapshot = mappingLog.toList() }
        val sb = StringBuilder()
        sb.append("mappingGeneration=").append(mappingGeneration.get())
            .append(" events=").append(snapshot.size)
            .append(" windowOffsetUs=").append(windowOffsetUs)
            .append(" offsetDeltaFromBaseUs=")
            .append(if (windowOffsetUs < 0) Long.MIN_VALUE else windowOffsetUs - RENDERER_DOMAIN_FLOOR_US)
            .append(' ').append(mappingOperandState())
            .append(" needsInit=").append(usbStartMediaTimeNeedsInit)
            .append(" usbStartMediaTimeUs=").append(usbStartMediaTimeUs)
            .append(" engineRunning=").append(nativeEngine?.isRunning ?: false)
        // Bounded render: the log is small, but a report must never be the observer's expensive act.
        snapshot.takeLast(MAPPING_REPORT_MAX_EVENTS).forEach { e ->
            sb.append("\n  M|").append(e.seq).append('|').append(e.t).append('|')
                .append(e.kind).append('|').append(e.detail)
        }
        return sb.toString()
    }

    private fun tracePosition(
        enginePositionUs: Long,
        windowOffsetUs: Long,
        sinkReturnedUs: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        positionQueryCount++
        if (now - lastTraceAtMs < POSITION_TRACE_INTERVAL_MS) return
        lastTraceAtMs = now

        // Progress of the sink-facing clock specifically: a query returning the same value is a
        // frozen clock, which is a different failure from the query not being called at all.
        // Progress is measured against the PREVIOUS SAMPLE, never a historical maximum: after a
        // backwards seek a maximum-based test latches and reports "not advancing" forever, which is
        // how the previous run reported advancingSamples=3 while the sink was plainly advancing.
        // A backwards seek now shows as one delta < 0 and normal detection resumes immediately.
        val previous = lastTracedSinkUs
        val advanced = if (previous != Long.MIN_VALUE && sinkReturnedUs > previous) 1 else 0
        lastTracedSinkUs = sinkReturnedUs
        if (advanced == 1) {
            lastAdvanceAtMs = now
            lastAdvanceCount++
        }

        val sample = PositionTrace(
            t = now,
            enginePositionUs = enginePositionUs,
            windowOffsetUs = windowOffsetUs,
            sinkReturnedUs = sinkReturnedUs,
            // Read from the mirror only — the Player's own getters belong to its looper.
            playerPositionMs = playerMirror?.positionMs ?: -1L,
            playbackState = playerMirror?.playbackState ?: -1,
            isPlaying = playerMirror?.isPlaying ?: false,
            mirrorAgeMs = playerMirror?.let { now - it.atMs } ?: -1L,
            mirrorSeq = playerMirror?.seq ?: -1L,
            pendingOperationAcks = playerMirror?.pendingOperationAcks ?: -1,
            appInfoPositionUs = playerMirror?.appPlaybackInfoPositionUs ?: -1L,
            queryIndex = positionQueryCount,
            advance = advanced,
        )
        synchronized(positionTrace) {
            if (positionTrace.size >= POSITION_TRACE_CAPACITY) positionTrace.removeFirst()
            positionTrace.addLast(sample)
        }
        // Publish on a slow heartbeat, or immediately once the player position has stopped moving
        // while supposedly playing — the state that matters. Formatting the full reports is the
        // observer's most expensive steady-state action, and doing it every second was pure
        // perturbation: the ring holds the history either way, and a stuck condition is what we
        // need dumped promptly.
        if (DirectPathTelemetry.captureEnabled) {
            if (advanced == 0 && playerMirror?.isPlaying == true) frozenTraceTicks++ else frozenTraceTicks = 0
            if (frozenTraceTicks >= FREEZE_PUBLISH_TICKS || now - lastTraceReportAtMs >= 5_000) {
                lastTraceReportAtMs = now
                frozenTraceTicks = 0
                DirectPathTelemetry.positionTrace = positionTraceReport()
                DirectPathTelemetry.ackLedger = ackLedgerReport()
                DirectPathTelemetry.mappingLog = mappingReport()
            }
        }
    }

    private var lastTraceReportAtMs = 0L
    private var frozenTraceTicks = 0

    /** Renders the trace for the capture, with the last advancing sample of each clock called out. */
    fun positionTraceReport(): String {
        val snapshot: List<PositionTrace>
        synchronized(positionTrace) { snapshot = positionTrace.toList() }
        if (snapshot.isEmpty()) return "trace=empty"
        val sb = StringBuilder()
        sb.append("traceSamples=").append(snapshot.size)
            .append(" spanMs=").append(snapshot.last().t - snapshot.first().t)
            .append(" queries=").append(positionQueryCount)
            .append(" lastAdvanceMsAgo=")
            .append(if (lastAdvanceAtMs == 0L) -1 else SystemClock.elapsedRealtime() - lastAdvanceAtMs)
            .append(" advancingSamples=").append(lastAdvanceCount)
        // Index of the last sample where the sink clock actually moved, and the last where the
        // engine did: whichever is later is the layer still alive.
        var lastSinkAdvance = -1
        var lastEngineAdvance = -1
        var prevSink = Long.MIN_VALUE
        var prevEngine = Long.MIN_VALUE
        snapshot.forEachIndexed { i, e ->
            if (prevSink != Long.MIN_VALUE && e.sinkReturnedUs > prevSink) lastSinkAdvance = i
            if (prevEngine != Long.MIN_VALUE && e.enginePositionUs > prevEngine) lastEngineAdvance = i
            prevSink = e.sinkReturnedUs
            prevEngine = e.enginePositionUs
        }
        // The Player layer, and whether a frozen reading is trustworthy: a fresh mirror means the
        // Player is actively returning that value; a stale one means the sampler stopped.
        var lastPlayerAdvance = -1
        var prevPlayer = Long.MIN_VALUE
        snapshot.forEachIndexed { i, e ->
            if (prevPlayer != Long.MIN_VALUE && e.playerPositionMs > prevPlayer) lastPlayerAdvance = i
            prevPlayer = e.playerPositionMs
        }
        val lastMirrorAge = snapshot.last().mirrorAgeMs
        sb.append(" lastPlayerAdvanceIdx=").append(lastPlayerAdvance)
            .append(" firstFrozenPlayerIdx=").append(lastPlayerAdvance + 1)
            .append(" lastAcks=").append(snapshot.last().pendingOperationAcks)
            .append(" lastAppInfoUs=").append(snapshot.last().appInfoPositionUs)
            .append(" playerVerdict=")
            .append(
                when {
                    lastMirrorAge < 0 -> "NO_MIRROR"
                    lastMirrorAge > 1_000 -> "MIRROR_STALE (sampler stopped, Player not proven frozen)"
                    else -> "MIRROR_FRESH (Player reachable and returning this value)"
                },
            )
        sb.append(" lastSinkAdvanceIdx=").append(lastSinkAdvance)
            .append(" lastEngineAdvanceIdx=").append(lastEngineAdvance)
        // First frozen sample per layer, so the FIRST divergence can be read directly.
        sb.append(" firstFrozenSinkIdx=").append(lastSinkAdvance + 1)
            .append(" firstFrozenEngineIdx=").append(lastEngineAdvance + 1)
        snapshot.takeLast(28).forEach { e ->
            sb.append("\n  ").append(e.t).append(" eng=").append(e.enginePositionUs)
                .append(" off=").append(e.windowOffsetUs)
                .append(" sink=").append(e.sinkReturnedUs)
                .append(" adv=").append(e.advance)
                .append(" player=").append(e.playerPositionMs)
                .append(" state=").append(e.playbackState)
                .append(" playing=").append(e.isPlaying)
                .append(" mirrorAgeMs=").append(e.mirrorAgeMs)
                .append(" acks=").append(e.pendingOperationAcks)
                .append(" appInfo=").append(e.appInfoPositionUs)
        }
        return sb.toString()
    }

    /** Target and generation of the most recent pushed seek, for the transition record. */
    @Volatile
    private var lastSeekTargetUs: Long = -1L
    @Volatile
    private var lastSeekGeneration: Long = 0L

    /**
     * Brings the applied signal path into line with the host's latest desired mode.
     *
     * Reads the desired mode *now* rather than taking it from the request, so a superseded request
     * reconciles to the newest state instead of executing an obsolete chain.
     */
    /**
     * Records the raw operands of the media-position anchor.
     *
     * Diagnostic only; reads nothing and changes nothing. It exists because the transition's
     * derived anchor was observed as ~1e15 us against an expected ~3e7, and the *inputs* to that
     * arithmetic were never recorded — so there was no way to tell whether the offset, the engine
     * position, or the combination was wrong, or which coordinate system each was in.
     */
    private fun captureAnchorDiagnostics() {
        val engine = nativeEngine
        DirectPathTelemetry.anchorWindowOffsetUs = windowOffsetUs
        DirectPathTelemetry.anchorEnginePositionUs = engine?.getPositionUs() ?: DirectPathTelemetry.ANCHOR_UNSET
        DirectPathTelemetry.anchorEngineRunning = engine?.isRunning ?: false
        DirectPathTelemetry.anchorEngineCreated = engine?.isCreated ?: false
        DirectPathTelemetry.anchorDerivedUs = handoffPositionUs()
        DirectPathTelemetry.anchorProducer = DirectPathTelemetry.producer
    }

    /**
     * Ensures an epoch exists before the first timestamp can arrive.
     *
     * Only ever creates one when none is open, which is the cold-start case: there is no previous
     * epoch for this anchor to disagree with, so it cannot manufacture a cross-position pair. If an
     * epoch already exists this is a no-op — a configure is not a position change.
     */
    private fun flushOriginDetail(): String =
        "flushOrigin=${DirectPathTelemetry.flushOrigin}"

    private fun ensureMappingEpoch() {
        if (mappingTracker.state != MappingState.None) return
        val declared = pendingStartTargetUs
        // AUD-139: the declared start or 0, never the host's mirror (see flush).
        openEpoch(
            anchorUs = if (declared > 0L) declared else 0L,
            source = if (declared > 0L) AnchorSource.DECLARED_ITEM_START
            else AnchorSource.IMPLICIT_RESTART,
            origin = "configure-first-epoch",
        )
    }

    private fun reconcileSignalMode() {
        // The reconciler decides *whether*; refreshSignalPath applies the mode and engages the
        // stage; realign moves the producers. Deciding from the reconciler rather than from
        // refreshSignalPath's return value keeps one authority for "a transition is owed", so a
        // superseded request cannot slip a second transition through.
        val desired = requestedSignalMode()
        if (!signalModeReconciler.reconcile(desired)) return
        refreshSignalPath()
        realignPipelineForSignalMode()
    }

    /**
     * Re-reads the host's mode and re-engages the stage when the stream shape has changed.
     *
     * Called on every buffer, so the common case — nothing changed — must be a few reference
     * comparisons and a return. Re-running [DirectPcmStage.configure] here would re-design the
     * filter bank and drop its delay lines on every buffer, which would silence the EQ's history
     * and make the filter audibly wrong rather than merely slow.
     *
     * @return true when the *producer* changed, which the caller must follow with a pipeline
     *         realignment. A stage being swapped for an equivalent one is not a producer change.
     */
    private fun refreshSignalPath(): Boolean {
        val wanted = requestedSignalMode()
        val requestedStage =
            if (wanted == DirectSignalMode.PROCESSED) config.signalSource?.stage() else null
        val sampleRate = currentSampleRate
        val channels = currentChannelCount

        // `signalTruthDirty` is what makes the *first* evaluation after a configure publish rather
        // than take the no-change path. Without it a pristine stream — which is already the initial
        // mode, with no stage — matches "nothing changed" on its very first call and never
        // publishes at all, leaving the transport-format fields unreported for the one mode that
        // most needs them to be legible.
        val unchanged = !signalTruthDirty &&
            wanted == signalMode &&
            requestedStage === configuredStage &&
            (requestedStage == null ||
                (sampleRate == configuredStageRate && channels == configuredStageChannels))
        if (unchanged) return false
        signalTruthDirty = false

        val moved = wanted != signalMode
        signalMode = wanted
        // Keep the reconciler's view of "what is running" identical to the field's, whichever path
        // resolved the mode.
        signalModeReconciler.markApplied(wanted)

        // Leaving PROCESSED drops the engaged stage. Its delay lines hold the tail of a stream that
        // is no longer playing, so they are cleared rather than kept for a re-engagement that may
        // happen at an unrelated position after a seek.
        val previous = configuredStage
        // B9-3: when the pipeline stays the producer its thread still holds buffers whose stage is `previous`,
        // and the first buffers after the change are crossfaded out of it. Wiping its delay lines now would
        // put a glitch in audio that has yet to play, so it is kept and reset with the queue (flush, release).
        val keepsQueue = previous != null && previous !== requestedStage && pipelineStaysProducer()
        retiredStage = if (keepsQueue) previous else null
        // Serialized with the stage running on the streaming thread (AUD-111): process() now runs
        // there, while configure/reset stay on the playback thread.
        val accepted = synchronized(stageLock) {
            if (previous != null && previous !== requestedStage && !keepsQueue) previous.reset()
            requestedStage != null &&
                sampleRate > 0 &&
                channels > 0 &&
                requestedStage.configure(sampleRate, channels)
        }

        if (requestedStage != null) {
            DirectPathTelemetry.stageConfigures.incrementAndGet()
            if (!accepted) DirectPathTelemetry.stageRefusals.incrementAndGet()
        }
        if (moved) {
            DirectPathTelemetry.modeTransitions.incrementAndGet()
            DirectPathTelemetry.recordIfCapturing("MODE_CHANGE") {
                "→ $signalMode stageAccepted=$accepted rate=$sampleRate ch=$channels"
            }
        }

        // A stage that refused the format leaves the signal untouched, so the sink must report the
        // untouched signal rather than a mode name the samples do not actually support.
        engagedStage = if (accepted) requestedStage else null
        configuredStage = if (accepted) requestedStage else null
        configuredStageRate = if (accepted) sampleRate else 0
        configuredStageChannels = if (accepted) channels else 0

        publishSignalTruth()
        return moved
    }

    // The stage as it was last configured, so the per-buffer check above can tell "already set up
    // for this exact stream" from "needs configuring" without re-running configure.
    private var configuredStage: DirectPcmStage? = null
    private var configuredStageRate: Int = 0
    private var configuredStageChannels: Int = 0

    /** Forces the next [refreshSignalPath] to publish even when nothing about the path changed. */
    @Volatile
    private var signalTruthDirty: Boolean = true

    private fun publishSignalTruth() {
        val stage = engagedStage
        val truth = when {
            !config.directEnabled -> BitPerfectTruth.NOT_DIRECT
            signalMode == DirectSignalMode.PRISTINE -> BitPerfectTruth.PRISTINE_UNVERIFIED
            stage == null -> BitPerfectTruth.PRISTINE_UNVERIFIED
            else -> BitPerfectTruth.processed(stage.activeProcessing())
        }
        DirectPathTelemetry.signalMode = signalMode
        DirectPathTelemetry.bitPerfectVerdict = truth.verdict
        DirectPathTelemetry.bitPerfectReason = truth.reason
        DirectPathTelemetry.bitPerfectAdditionalReasons = truth.additionalReasons
        DirectPathTelemetry.transportPcmEncoding = if (signalMode == DirectSignalMode.PROCESSED) {
            C.ENCODING_PCM_FLOAT
        } else {
            currentEncoding
        }
        DirectPathTelemetry.transportBitDepth = transportBitDepth()
        publishDiagnostics(
            diagnostics.copy(
                signalMode = signalMode,
                bitPerfect = truth,
                transportPcmEncoding = if (signalMode == DirectSignalMode.PROCESSED) {
                    C.ENCODING_PCM_FLOAT
                } else {
                    currentEncoding
                },
                transportBitDepth = transportBitDepth(),
            )
        )
    }

    /** The bit depth the USB writer is actually handed, in bits. */
    private fun transportBitDepth(): Int? = when (transportEncoding()) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT -> 32
        C.ENCODING_PCM_FLOAT -> dacBitDepth
        else -> null
    }

    private fun transportEncoding(): Int =
        if (signalMode == DirectSignalMode.PROCESSED) C.ENCODING_PCM_FLOAT else currentEncoding

    /** Bit depth of the alternate setting the DAC is streaming on, or null before a claim. */
    @Volatile
    private var dacBitDepth: Int? = null

    /**
     * Media timeline offset captured from the first buffer's presentationTimeUs
     * after each flush/init. Maps framesWritten=0 to the correct song position.
     * DefaultAudioSink calls this startMediaTimeUs internally.
     */
    private var usbStartMediaTimeUs: Long = 0L

    /**
     * The pipeline producer's clock (AUD-110): position = first played buffer + frames written since
     * it, and buffers from before a realign anchor are dropped. See [PipelinePositionClock].
     */
    private val pipelineClock = PipelinePositionClock()

    /** Guards the DSP stage: process() runs on the streaming thread, configure/reset do not. */
    private val stageLock = Any()
    private var usbStartMediaTimeNeedsInit: Boolean = true
    private var handledEndOfStream: Boolean = false

    /**
     * The renderer↔media mapping, epoch-scoped.
     *
     * One owner for both parts of the coordinate transform: the media-position anchor and the
     * renderer offset derived from it. They share a lifetime here, so a cross-position pair — an
     * offset established from one epoch's timestamp against another epoch's anchor — cannot be
     * represented, let alone latched.
     */
    private val mappingTracker = MappingEpochTracker()

    /**
     * ExoPlayer's window offset, derived from the latched mapping; -1 when not established.
     *
     * A derived value, never a second authoritative copy.
     */
    private val windowOffsetUs: Long
        get() = mappingTracker.windowOffsetUs

    /** True when the engine was just created and needs its first seek from handleBuffer.
     *  Prevents play() from resuming the engine before the correct position is known. */
    private var engineNeedsInitialSeek: Boolean = false

    /** Path of the file the current native engine is decoding. Used to detect track changes. */
    private var activeEnginePath: String? = null


    /** Max queue entries before returning false for backpressure (paces ExoPlayer).
     *  Pause responsiveness is handled by pauseStreaming(), not queue size. */
    private val QUEUE_BACKPRESSURE_THRESHOLD = 16

    /** Tracks ExoPlayer's play/pause state so seek-while-paused doesn't auto-resume. */
    private var isPlaying = false

    /**
     * F-10 (B12a-3): in Direct no `AudioTrack` of ours plays, so Android believes nothing is playing (the MagicOS
     * flashlight gesture took Volume Down with the screen off). This holds one silent, speaker-pinned track while
     * Direct is really playing; see [DirectPlaybackPresence]. Null when the host or the config does not want it.
     */
    private val presence: DirectPlaybackPresence? =
        if (config.directPresence && config.directEnabled) DirectPlaybackPresence.forContext(context) else null

    /**
     * Tells the presence what this sink knows: the user is playing through Direct, and the USB stream is up. Cheap
     * (a post to the presence's own thread), called after the DAC path has been dealt with and never before it.
     */
    private fun updatePresence() {
        presence?.update(
            playing = isPlaying,
            streamAlive = usbAudioStream?.isAlive == true && !directFailureHold,
        )
    }

    /** Deferred USB reconfiguration — applied after engine finishes playing. */
    private var deferredRate: Int = 0
    private var deferredChannels: Int = 0
    private var deferredEncoding: Int = 0
    private var hasDeferredConfig: Boolean = false
    /** The transition a deferred config is for: the next one after it was deferred (AUD-137). */
    private var deferredForTransitionSeq = -1L


    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val enc = inputFormat.pcmEncoding
        if (enc != Format.NO_VALUE) currentEncoding = enc
        // Cold start only: make sure an epoch exists before the first timestamp can arrive. A no-op
        // once any epoch is open, so a reconfigure never re-anchors a live mapping.
        ensureMappingEpoch()

        // If native engine is still playing the SAME track AND the rate didn't change,
        // don't touch it. This happens when ExoPlayer pre-buffers the next track ~10s
        // before EOF. But if the track or rate changed, destroy and reconfigure.
        // During a natural end (AUD-128) the finished engine's configure for the next item is deferred
        // too: it is applied at Media3's transition, never while the ended track is still current.
        if (nativeEngine?.isRunning == true || naturalEnd.pending) {
            val trackChanged = currentTrackPath != activeEnginePath
            if (!trackChanged) {
                // Same track, ExoPlayer pre-buffering — defer reconfiguration
                if (inputFormat.sampleRate != currentSampleRate || inputFormat.channelCount != currentChannelCount) {
                    deferredRate = inputFormat.sampleRate
                    deferredChannels = inputFormat.channelCount
                    deferredEncoding = enc
                    hasDeferredConfig = true
                    deferredForTransitionSeq = transitionSeq + 1
                    Log.i(TAG, "configure: engine running, pre-buffer — deferred rate=${inputFormat.sampleRate}")
                } else {
                    Log.i(TAG, "configure: engine running, same rate — keeping alive")
                }
                super.configure(inputFormat, specifiedBufferSize, outputChannels)
                muteDelegateIfNeeded()
                return
            }
            // Track changed (manual skip) — destroy engine and proceed
            Log.i(TAG, "configure: track changed, destroying engine")
        }
        // Track changed or engine finished — destroy old engine
        val oldEngine = nativeEngine
        if (oldEngine != null) {
            if (oldEngine.isRunning && isPlaying) {
                // AUD-123: an audible cut fades the continuation out behind the ring (which plays out)
                // and the next engine fades in, instead of discarding at full level.
                oldEngine.stopWithFadeOut()
                pendingEntryFadeIn = true
            } else {
                oldEngine.stop()
            }
            oldEngine.destroy()
            DirectPathTelemetry.nativeEngineStops.incrementAndGet()
            nativeEngine = null
            isNativeEngineActive = false
            activeEnginePath = null
            Log.i(TAG, "configure: destroyed old engine")
        }

        // A previous failed Direct transaction may have forced the Android
        // delegate away from a now-detached USB route. Re-evaluate routing for
        // every real configuration; normal Android routing remains untouched
        // unless this transaction itself fails after claiming USB Audio.
        clearForcedRouting()
        directFailureHold = false
        handleBufferCallCount = 0
        val sr = inputFormat.sampleRate.takeIf { it > 0 }
        val ch = inputFormat.channelCount.takeIf { it > 0 }

        Log.i(TAG, "configure: pcmEncoding=${when(enc) {
            C.ENCODING_PCM_FLOAT -> "FLOAT"; C.ENCODING_PCM_16BIT -> "16BIT"
            C.ENCODING_PCM_24BIT -> "24BIT"; C.ENCODING_PCM_32BIT -> "32BIT"
            else -> "UNKNOWN($enc)"
        }} rate=${inputFormat.sampleRate} ch=${inputFormat.channelCount}")

        // A new stream shape: the signal path must be re-derived and re-published even if the mode
        // itself is unchanged from the previous track.
        signalTruthDirty = true

        DirectPathTelemetry.configureCount.incrementAndGet()

        // Capture the thread this sink's lifecycle runs on, so a pushed mode change can run on it
        // rather than introducing a second thread that owns the engine.
        if (playbackHandler == null) {
            playbackHandler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
        }

        // Observation only. Nothing below feeds a decision this sink makes.
        DirectPathTelemetry.inputPcmEncoding = enc.takeIf { it != Format.NO_VALUE }
        DirectPathTelemetry.transportSampleRateHz = sr
        DirectPathTelemetry.transportChannelCount = ch
        DirectPathTelemetry.recordIfCapturing("CONFIGURE") {
            "encoding=$enc rate=${inputFormat.sampleRate} ch=${inputFormat.channelCount}"
        }

        if (config.directEnabled && sr != null && ch != null) {
            val device = usbAudioDevice.findUsbAudioDevice()
            if (device != null && usbAudioDevice.hasPermission(device)) {
                publishDiagnostics(
                    diagnostics.copy(
                        state = UsbDirectSinkState.USB_DIRECT_AVAILABLE,
                        deviceName = device.productName ?: device.deviceName,
                        permissionGranted = true,
                        sourceSampleRate = sr,
                        sourceChannelCount = ch,
                        sourcePcmEncoding = enc.takeIf { it != Format.NO_VALUE },
                        errorReason = null,
                    )
                )
                downmixSourceChannels = if (ch > STEREO) ch else 0
                upmixMonoSource = ch == 1
                if (downmixSourceChannels > 0) {
                    Log.i(TAG, "configure: $ch-channel source, downmixing to stereo for the DAC")
                    DirectPathTelemetry.recordIfCapturing("DOWNMIX") { "from=$ch to=$STEREO rate=$sr" }
                }
                if (configureUsbBitPerfect(sr, if (upmixMonoSource) STEREO else minOf(ch, STEREO), enc)) {
                    recordMapping(
                        "OFFSET_RESET",
                        "oldOffsetUs=$windowOffsetUs newOffsetUs=-1 needsInit=true " +
                            "reason=CONFIGURE gen=${mappingGeneration.get()} " +
                            mappingOperandState() + " " + mappingPlayerState(),
                    )
                    // SAME_POSITION_REBUILD: machinery was rebuilt, the media position did not change, so the
            // mapping is preserved. Clearing the offset here while leaving the anchor alive is
            // exactly how a cross-position pair used to become representable.
            mappingTracker.preserve()
                    usbStartMediaTimeNeedsInit = true
                    pipelineClock.disarm()
                    if (config.forceRouteToSpeaker) forceMediaToSpeaker()
                    super.configure(inputFormat, specifiedBufferSize, outputChannels)
                    muteDelegateIfNeeded()
                    Log.i(TAG, "Delegate configured (muted, routed to speaker)")
                    return
                }
                Log.w(TAG, "USB initialization failed; retaining Android audio delegate")
            } else if (device != null) {
                Log.w(TAG, "USB DAC found but no permission")
                publishDiagnostics(
                    UsbAudioSinkDiagnostics(
                        state = UsbDirectSinkState.USB_PERMISSION_REQUIRED,
                        deviceName = device.productName ?: device.deviceName,
                        permissionGranted = false,
                        sourceSampleRate = sr,
                        sourceChannelCount = ch,
                        sourcePcmEncoding = enc.takeIf { it != Format.NO_VALUE },
                    )
                )
            } else {
                publishDiagnostics(
                    UsbAudioSinkDiagnostics(
                        state = UsbDirectSinkState.ANDROID_AUDIO,
                        permissionGranted = null,
                        sourceSampleRate = sr,
                        sourceChannelCount = ch,
                        sourcePcmEncoding = enc.takeIf { it != Format.NO_VALUE },
                    )
                )
            }
        }

        if (directFailureHold) {
            muteDelegateIfNeeded()
            Log.w(TAG, "Direct setup failed; delegate held muted until the host re-routes")
        } else {
            unmuteDelegateIfNeeded()
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)

        if (usbAudioStream != null && !config.directEnabled) {
            releaseUsbStream()
        }
    }

    override fun setListener(listener: AudioSink.Listener) {
        sinkListener = listener
        super.setListener(listener)
    }

    /**
     * Media3's renderer offset for the stream whose output starts now (AUD-136): the authority for
     * every window offset, kept in [offsetLedger]. Playback thread.
     */
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
        offsetLedger.onStreamOffsetReported(outputStreamOffsetUs)
        if (naturalOffsetAwaitSinceMs >= 0L) {
            offsetLedger.playingOffsetUs()?.let {
                adoptItemOffset(it, naturalOffsetReportedBeforeUs, "NATURAL_END_MEDIA3_LATE")
            }
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        var stream = usbAudioStream
        if (stream != null && !stream.isAlive) {
            // B25-S1: a stream that dies must never hand the music to whatever Android falls back to. This used to release
            // the stream, un-mute the delegate (`releaseUsbStream` ends in `unmuteDelegateIfNeeded`), clear its routing and
            // fall through to `super.handleBuffer` below, so the very buffer that found the dead stream played on the
            // phone's speaker — for an unplugged DAC, before anything above this class could pause. The same hold a failed
            // claim takes (AUD-004) keeps the delegate silent until the host re-routes or pauses; `configure` ends it.
            val deviceGone = usbAudioDevice.findUsbAudioDevice() == null
            directFailureHold = true
            muteDelegateIfNeeded()
            releaseUsbStream()
            publishDiagnostics(
                diagnostics.copy(
                    state = UsbDirectSinkState.USB_DIRECT_ERROR,
                    errorReason = DirectStreamLoss.reasonFor(deviceGone),
                )
            )
            stream = null
        }
        if (config.directEnabled && stream?.isAlive == true) {
            muteDelegateIfNeeded()

            // AUD-128: the ended track's engine is finished and Media3 is reading ahead; nothing here
            // is played (the next item's own engine decodes it from its start).
            if (naturalEnd.onHandleBuffer(nativeEngine != null, usbStreamingThread != null) ==
                NaturalEndCoordinator.BufferAction.DROP
            ) {
                buffer.position(buffer.limit())
                return true
            }

            // Fallback engine creation: if no engine and no streaming thread,
            // try creating one now (path and USB rate should both be correct by this point)
            // Not while an item transition owns the creation (AUD-133): createPendingEngine opens the
            // current item and applies its offset.
            if (nativeEngine == null && usbStreamingThread == null && !enginePendingForItem) {
                startNativeEngineIfFlac(stream)
                // Engine starts paused. The usbStartMediaTimeNeedsInit block below
                // will capture presentationTimeUs and seek to the correct position.
            }

            // The host can change its mind while a track plays, and a mode change swaps which
            // component is producing PCM. That is a structural move, not a flag flip, so it is
            // performed once here — at a buffer boundary, on the playback thread that already owns
            // every other lifecycle decision in this sink.
            //
            // This must run *before* the native-engine branch below, not after it. The engine
            // returns early — it writes to USB from its own thread and the application never sees
            // the samples — so a check placed after that return is unreachable for exactly the
            // tracks it needs to act on. The failure is silent and one-directional: the routing
            // policy moves to processed, the sink never follows, and the DAC keeps receiving
            // untouched source while the diagnostic claims an equalizer is running. Measured on
            // the HONOR: `reason=DIRECT_PROCESSED` with `mode=PRISTINE`, `modeTransitions=0`,
            // `stageProcessCalls=0`, `producer=NATIVE_FLAC`.
            // A secondary observer only. The authoritative trigger is `requestSignalMode`, pushed
            // from the host, because this callback stops arriving entirely while the native engine
            // owns the track — which is precisely when a transition is most likely to be needed.
            if (signalModeReconciler.onRequest()) reconcileSignalMode()

            // Capture media timeline offset from first buffer (needed for position tracking)
            if (usbStartMediaTimeNeedsInit) {
                // After a PRISTINE → PROCESSED realign the renderer resumes from wherever LoadControl
                // stopped it, seconds behind the DAC. Those buffers were already played by the engine:
                // consume them unplayed until the realign anchor is reached (AUD-110).
                if (!pipelineClock.accept(presentationTimeUs, usbAudioStream?.framesWritten ?: 0L, pipelineQueueLeadUs())) {
                    buffer.position(buffer.limit())
                    return true
                }
                usbStartMediaTimeUs = maxOf(0L, presentationTimeUs)
                usbStartMediaTimeNeedsInit = false
                // Save window offset once per track (not reset by flush/seek).
                // windowOffset = ExoPlayer timeline position of track start (position 0).
                // On fresh start: initialPlayerPosition=0 → offset = pts (correct).
                // On restore at 158s: initialPlayerPosition=158s → offset = pts - 158s (correct).
                // AUD-138/139: a buffer of another stream than the playing item's — the next item, read
                // ahead or reached by a seek past the end — says nothing about this item's position.
                val playingOffsetUs = offsetLedger.playingOffsetUs()
                val fromPlayingStream =
                    BootstrapSeekPolicy.bufferFromPlayingStream(offsetLedger.latestOffsetUs(), playingOffsetUs)
                if (!mappingTracker.isLatched) {
                    // AUD-136: Media3's own offset for this buffer's stream says where the buffer is.
                    // An anchor it contradicts (a stale host position, a seek past the end) is wrong.
                    val media3AnchorUs = if (!fromPlayingStream) null else OffsetAuthority.media3Anchor(
                        presentationTimeUs = presentationTimeUs,
                        media3OffsetUs = offsetLedger.latestOffsetUs(),
                        epochAnchorUs = mappingTracker.anchorMediaPositionUs,
                        durationUs = nativeEngine?.getDurationUs() ?: 0L,
                    )
                    if (media3AnchorUs != null && mappingTracker.state is MappingState.Unlatched) {
                        recordMapping(
                            "OFFSET_FROM_MEDIA3",
                            "epochAnchorUs=${mappingTracker.anchorMediaPositionUs} media3AnchorUs=$media3AnchorUs " +
                                "media3OffsetUs=${offsetLedger.latestOffsetUs()} presentationTimeUs=$presentationTimeUs",
                        )
                        Log.w(TAG, "Latch: anchor ${mappingTracker.anchorMediaPositionUs / 1000}ms contradicts Media3 " +
                            "(${media3AnchorUs / 1000}ms at this buffer); using Media3's")
                        openEpoch(media3AnchorUs, mappingTracker.anchorSource, "media3-anchor")
                    }
                    // The epoch owns both operands: the anchor was fixed when the epoch opened, so the
                    // subtraction below cannot pair this timestamp with another position's anchor.
                    val originalOperandUs = mappingTracker.anchorMediaPositionUs
                    val computedOffsetOriginalUs = presentationTimeUs - originalOperandUs
                    val rebaseApplied = false
                    val effectiveOperandUs = originalOperandUs
                    val effectiveOperandSource = mappingTracker.anchorSource.name
                    // AUD-138: from a foreign buffer the playing item takes Media3's offset for itself;
                    // `pts - anchor` of the next item's buffer latched that item's offset instead.
                    val latchPtsUs = playingOffsetUs?.takeIf { !fromPlayingStream }?.let {
                        OffsetAuthority.latchPresentationForForeignBuffer(it, mappingTracker.anchorMediaPositionUs)
                    } ?: presentationTimeUs
                    val latchedState = latchEpoch(latchPtsUs, if (fromPlayingStream) "first-buffer" else "foreign-buffer")
                        as? MappingState.Latched
                    if (latchedState == null) {
                        // No epoch is open, so there is no anchor to subtract. Guessing one is the
                        // defect; refusing leaves the mapping unestablished instead.
                        recordMapping(
                            "OFFSET_LATCH_REFUSED",
                            "reason=no-epoch presentationTimeUs=$presentationTimeUs " +
                                "refusedLatches=${mappingTracker.refusedLatchCount} " +
                                mappingOperandState() + " " + mappingPlayerState(),
                        )
                    }
                    val computedOffsetUs = latchedState?.windowOffsetUs ?: Long.MIN_VALUE
                    val currentGen = mappingGeneration.get()
                    // Same-epoch is a recorded comparison of provenance, not an inference from the
                    // two numbers happening to look compatible. A mismatched pair is the whole
                    // hypothesis under test, so it is labelled here and not judged later.
                    val classification =
                        if (initialPlayerPositionGeneration == currentGen) "SAME_EPOCH" else "CROSS_EPOCH"
                    recordMapping(
                        "OFFSET_LATCH",
                        "presentationTimeUs=$presentationTimeUs " +
                            "presentationDeltaFromBaseUs=${presentationTimeUs - RENDERER_DOMAIN_FLOOR_US} " +
                            "originalInitialPlayerPositionUs=$originalOperandUs " +
                            "originalOperandGeneration=$initialPlayerPositionGeneration " +
                            "originalOperandSetSeq=$initialPlayerPositionSetSeq " +
                            "effectiveInitialPlayerPositionUs=$effectiveOperandUs " +
                            "effectiveOperandSource=$effectiveOperandSource " +
                            "rebaseApplied=$rebaseApplied " +
                            "diagnosticSeekTargetUs=${DirectPathTelemetry.diagnosticRebasePendingTargetUs} " +
                            "diagnosticEngineStartTargetUs=${DirectPathTelemetry.diagnosticEngineStartTargetUs} " +
                            "computedOffsetOriginalUs=$computedOffsetOriginalUs " +
                            "deltaOriginalFromBaseUs=${computedOffsetOriginalUs - RENDERER_DOMAIN_FLOOR_US} " +
                            "computedOffsetEffectiveUs=$computedOffsetUs " +
                            "deltaEffectiveFromBaseUs=${computedOffsetUs - RENDERER_DOMAIN_FLOOR_US} " +
                            "currentGen=$currentGen classification=$classification " +
                            "operandUs=$originalOperandUs " +
                            "operandGen=$initialPlayerPositionGeneration " +
                            "operandSetSeq=$initialPlayerPositionSetSeq " +
                            "computedOffsetUs=$computedOffsetUs " +
                            "computedDeltaFromBaseUs=${computedOffsetUs - RENDERER_DOMAIN_FLOOR_US} " +
                            "seekTargetUs=${explicitSeekTargetUs.get()} " +
                            "usbStartMediaTimeUs=$usbStartMediaTimeUs " +
                            "enginePosUs=${nativeEngine?.getPositionUs() ?: -1L} " +
                            mappingPlayerState(),
                    )
                    if (latchedState != null) {
                        // Record the operands, not just the result: a wrong derived value cannot say
                        // which input is in the wrong coordinate system.
                        DirectPathTelemetry.latchPresentationTimeUs = presentationTimeUs
                        DirectPathTelemetry.latchInitialPlayerPositionUs = originalOperandUs
                        DirectPathTelemetry.latchWindowOffsetUs = computedOffsetUs
                    }
                }
                Log.i(TAG, "startMediaTimeUs=$usbStartMediaTimeUs windowOffset=$windowOffsetUs initialPos=${initialPlayerPositionUs / 1000}ms")

                // Bootstrap/legacy position source: after an initial start, or a flush whose seek
                // was not pushed explicitly, place the engine from the renderer buffer timestamp.
                //
                // This is the *fallback*, never the authority for a user seek. It reconstructs the
                // target as `presentationTimeUs - windowOffsetUs`, which is only correct while the
                // renderer↔media mapping is intact, and — decisively — it is reached only from
                // handleBuffer, which in PRISTINE may never be called at all because
                // NativeEngineAwareLoadControl is deliberately not loading ExoPlayer any PCM.
                // A user seek that depends on it is a user seek that silently does not happen.
                //
                // It must also not overwrite a pushed seek the decoder has not executed yet: the
                // explicit target is newer than anything reconstructible here.
                val engine = nativeEngine
                val explicitOwed = explicitSeekOwed()
                val bufferUs = presentationTimeUs - windowOffsetUs
                // AUD-139: one rule for what a buffer may do to the engine, shared with the long-run
                // simulation. Only a seek moves a playing engine; a buffer places one that nothing has
                // placed and that has not been heard, and is otherwise Media3's read-ahead.
                val decision = engine?.let {
                    BootstrapSeekPolicy.decide(
                        placement = enginePlacement,
                        engine = it,
                        fromPlayingStream = fromPlayingStream,
                        enginePositionUs = it.getPositionUs(),
                        windowOffsetKnown = windowOffsetUs >= 0,
                        explicitOwed = explicitOwed,
                        bufferPositionUs = bufferUs,
                        explicitTargetUs = explicitSeekTargetUs.get(),
                        explicitIsFresh = explicitSeekIssuedAtResetCount >= positionResetCount - 1,
                    )
                }
                if (engine != null && decision == BootstrapSeekPolicy.Decision.Refused) {
                    // The engine was placed (item start, user seek, realign…), or it has been playing
                    // on its own for seconds: this buffer is Media3's read-ahead, up to 60 s past what
                    // is heard. Measured: "bootstrap seek to 60s (playing=true)" 30.6 s into a track,
                    // and 31 s at 4 min 16 s into one whose engine nothing had placed.
                    val placedBy = if (enginePlacement.isPlaced(engine)) "placed by ${enginePlacement.lastOrigin}"
                    else "unplaced, playing"
                    Log.i(TAG, "Bootstrap seek refused: engine $placedBy at " +
                        "${engine.getPositionUs() / 1000}ms; buffer at ${bufferUs / 1000}ms " +
                        "(${if (fromPlayingStream) "read ahead" else "another item's stream"})")
                    auditPosition("BOOTSTRAP_REFUSED", if (enginePlacement.isPlaced(engine)) enginePlacement.lastOrigin else "unplaced",
                        "bufferAt=${bufferUs / 1000}ms playingStream=$fromPlayingStream")
                    if (isPlaying) engine.resume()
                    engineNeedsInitialSeek = false
                } else if (engine != null && decision != BootstrapSeekPolicy.Decision.None &&
                    decision != BootstrapSeekPolicy.Decision.ExplicitOwed
                ) {
                    val flacPositionUs = bufferUs
                    // Already there: a user seek placed the engine at this position a moment ago,
                    // and re-seeking runs the barrier a second time — it discards the audio that
                    // had just started, so every seek was heard as two cuts ("pff"). Measured: user
                    // seek gen=7 to 168.7 s, then 65 ms later a bootstrap gen=8 to the same sample.
                    // Compared with the explicit target, not the engine's live position: the
                    // buffer that drives this bootstrap arrives ~300 ms after the seek, by which time
                    // the engine has played on, and re-seeking also stepped playback backwards.
                    // The explicit target counts only when it was issued for this flush (AUD-117):
                    // otherwise a target left at 0 by the item start matched a seek to 0 and the
                    // engine stayed at 5.4 s.
                    if (decision == BootstrapSeekPolicy.Decision.AlreadyThere) {
                        // Confirmed at this reset's position: later buffers of it are read-ahead.
                        enginePlacement.onPlaced(engine, positionResetCount, "bootstrap-already-there")
                        if (isPlaying) engine.resume()
                        engineNeedsInitialSeek = false
                        Log.i(TAG, "Bootstrap seek skipped: engine already at ${flacPositionUs / 1000}ms")
                    } else if (decision is BootstrapSeekPolicy.Decision.Seek) {
                        seekEngine(engine, decision.targetUs, seekGeneration.incrementAndGet(), "bootstrap")
                        if (isPlaying) engine.resume()
                        engineNeedsInitialSeek = false
                        Log.i(TAG, "Native engine bootstrap seek to ${decision.targetUs / 1_000_000}s (playing=$isPlaying)")
                    }
                } else if (explicitOwed) {
                    Log.i(TAG, "Bootstrap seek skipped: explicit seek gen=${seekGeneration.get()} still owed")
                }
                // Re-block LoadControl now that we have the position.
                // flush() temporarily unblocked it to allow this handleBuffer call.
                if (nativeEngine?.isRunning == true) {
                    isNativeEngineActive = true
                }
            }

            // Native FLAC engine handles decode+USB directly — ignore ExoPlayer data.
            val engine = nativeEngine
            if (engine != null) {
                if (engine.isRunning) {
                    buffer.position(buffer.limit())
                    return true
                }
                // AUD-134: an end of stream seen here first — a seek past the end reaches EOF at once —
                // is a natural end like any other: the engine stays until Media3's own transition,
                // which applies the next item's deferred config. Destroying it here skipped that
                // config: "Rate mismatch: FLAC=96000 USB=48000 — falling back to ExoPlayer pipeline".
                onEngineStopped(engine)
                if (naturalEnd.pending) {
                    buffer.position(buffer.limit())
                    return true
                }
                // Engine finished playing — clean up for next track.
                // Lazy creation at the top of handleBuffer will create a new engine
                // with the correct currentTrackPath on the next call.
                Log.i(TAG, "Native engine finished — cleaning up for next track")
                engine.destroy()
                DirectPathTelemetry.nativeEngineStops.incrementAndGet()
                nativeEngine = null
                isNativeEngineActive = false
                activeEnginePath = null
                recordMapping(
                    "OFFSET_RESET",
                    "oldOffsetUs=$windowOffsetUs newOffsetUs=-1 needsInit=true " +
                        "reason=ENGINE_FINISHED gen=${mappingGeneration.get()} " +
                        mappingOperandState() + " " + mappingPlayerState(),
                )
                // SAME_POSITION_REBUILD: machinery was rebuilt, the media position did not change, so the
            // mapping is preserved. Clearing the offset here while leaving the anchor alive is
            // exactly how a cross-position pair used to become representable.
            mappingTracker.preserve()
                usbStartMediaTimeNeedsInit = true
                pipelineClock.disarm()
                // Return true for this buffer — next handleBuffer will create new engine
                buffer.position(buffer.limit())
                return true
            }

            val thread = usbStreamingThread ?: return true

            // Backpressure: if queue is nearly full, tell ExoPlayer to retry later.
            // This paces the renderer to the USB DAC's consumption rate without
            // depending on the delegate AudioTrack.
            if (thread.queueSize() >= QUEUE_BACKPRESSURE_THRESHOLD) {
                return false
            }

            handleBufferCallCount++
            val source: ByteBuffer = buffer.slice().order(buffer.order())
            val snapshot: ByteBuffer = if (upmixMonoSource) {
                // Exact duplication into both channels of the stereo stream (AUD-012).
                val mono = ByteArray(source.remaining()).also { source.get(it) }
                ByteBuffer.wrap(MonoUpmix.duplicate(mono, PcmUtils.bytesPerSample(currentEncoding)))
                    .order(buffer.order())
            } else {
                source
            }

            val stage = engagedStage
            // B9-3: the first ~20 ms after a PRISTINE ↔ PROCESSED change that kept the queue are crossfaded.
            val crossfade = pipelineCrossfade?.takeIf { !it.isDone }
            if (downmixSourceChannels > 0) {
                enqueueDownmixed(snapshot, downmixSourceChannels, stage, thread)
            } else if (crossfade != null) {
                enqueueProcessed(snapshot, crossfade.stage, thread, crossfade)
            } else if (stage != null) {
                enqueueProcessed(snapshot, stage, thread)
            } else if (currentEncoding == C.ENCODING_PCM_FLOAT) {
                val totalSamples = snapshot.remaining() / 4
                if (totalSamples > 0) {
                    val floatBuf = FloatArray(totalSamples)
                    snapshot.asFloatBuffer().get(floatBuf)
                    if (handleBufferCallCount <= 3) {
                        Log.i(TAG, "handleBuffer #$handleBufferCallCount: FLOAT samples=$totalSamples")
                    }
                    thread.enqueue(floatBuf)
                }
            } else {
                val remaining = snapshot.remaining()
                if (remaining > 0) {
                    val rawBytes = ByteArray(remaining)
                    snapshot.get(rawBytes)
                    if (handleBufferCallCount <= 3) {
                        val bps = PcmUtils.bytesPerSample(currentEncoding)
                        Log.i(TAG, "handleBuffer #$handleBufferCallCount: RAW ${bps*8}bit bytes=$remaining")
                    }
                    thread.enqueueRaw(rawBytes, currentEncoding)
                }
            }

            // Advance buffer and return true — no delegate dependency.
            buffer.position(buffer.limit())
            return true
        }

        unmuteDelegateIfNeeded()
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    // ── Processed Direct ───────────────────────────────────────────────

    /**
     * Downmixes one multichannel buffer to stereo, runs the stage if one is engaged, and hands the
     * result to the USB writer. See [downmixSourceChannels].
     */
    private fun enqueueDownmixed(
        snapshot: ByteBuffer,
        sourceChannels: Int,
        stage: DirectPcmStage?,
        thread: UsbStreamingThread,
    ) {
        val bytesPerFrame = PcmUtils.bytesPerSample(currentEncoding) * sourceChannels
        if (bytesPerFrame <= 0) return
        val frames = snapshot.remaining() / bytesPerFrame
        if (frames <= 0) return
        val sourceSamples = frames * sourceChannels
        if (pcmScratch.size < sourceSamples) pcmScratch = FloatArray(sourceSamples)
        PcmUtils.readFloats(snapshot, currentEncoding, pcmScratch, sourceSamples)
        val stereo = FloatArray(frames * STEREO)
        StereoDownmix.apply(pcmScratch, sourceChannels, stereo, frames)
        if (stage == null) {
            thread.enqueue(stereo)
            return
        }
        // Stage at write time, like the processed path (AUD-111).
        thread.enqueue(stereo) { data ->
            synchronized(stageLock) {
                DirectPathTelemetry.stageProcessCalls.incrementAndGet()
                DirectPathTelemetry.timeProcess { stage.process(data, frames) }
            }
        }
    }

    /**
     * Converts one decoded buffer to float, runs the host's stage over it, and hands the result to
     * the USB writer.
     *
     * Float is the working format rather than the source's own integer encoding because it is the
     * only one that represents every supported input exactly: a float32 significand is 24 bits, so
     * 16- and 24-bit samples survive the round trip with no loss at all. The native layer converts
     * float back to the DAC's width with a saturating, power-of-two scale, so the quantisation
     * happens once, at the end, against the format the transport actually negotiated.
     *
     * A whole number of frames is required. A trailing partial frame is left in [snapshot] for the
     * caller rather than being padded or dropped, because inventing a sample would corrupt the
     * channel interleave for everything after it.
     */
    private fun enqueueProcessed(
        snapshot: ByteBuffer,
        stage: DirectPcmStage,
        thread: UsbStreamingThread,
        crossfade: ProcessingCrossfade? = null,
    ) {
        val bytesPerFrame = PcmUtils.bytesPerSample(currentEncoding) * currentChannelCount
        if (bytesPerFrame <= 0) return
        val frames = snapshot.remaining() / bytesPerFrame
        if (frames <= 0) return

        val sampleCount = frames * currentChannelCount
        // A fresh array: the streaming thread owns it until the DAC has consumed it.
        val samples = FloatArray(sampleCount)
        PcmUtils.readFloats(snapshot, currentEncoding, samples, sampleCount)
        // B9-3: a buffer inside the ramp of a mode change blends its own samples with the stage's; the ramp
        // position is claimed here, in queue order, and the blend itself runs at write time like the stage.
        val channels = currentChannelCount
        val rampStart = crossfade?.claim(frames) ?: 0
        val process: (FloatArray) -> Unit = if (crossfade != null) {
            { data -> crossfade.blend(data, frames, channels, rampStart) }
        } else {
            { data -> stage.process(data, frames) }
        }
        // The stage runs when the thread takes this buffer to write it, not now (AUD-111): the queue
        // holds ~1.5 s, and equalising at enqueue made every EQ change wait behind all of it.
        thread.enqueue(samples) { data ->
            synchronized(stageLock) {
                DirectPathTelemetry.stageProcessCalls.incrementAndGet()
                DirectPathTelemetry.inputFrames.addAndGet(frames.toLong())
                // Timed only while a capture session is running; `timeProcess` is a plain call otherwise.
                // Level is measured across the same blocks, so the ratio is independent of programme
                // material. Gated on capture: a per-sample sum of squares is not a hot-path cost worth
                // paying when nothing is recording.
                if (DirectPathTelemetry.captureEnabled) {
                    var inSum = 0.0
                    var inPeak = 0f
                    for (index in 0 until sampleCount) {
                        val value = data[index]
                        inSum += value.toDouble() * value
                        val magnitude = if (value < 0f) -value else value
                        if (magnitude > inPeak) inPeak = magnitude
                    }
                    DirectPathTelemetry.timeProcess { process(data) }
                    var outSum = 0.0
                    var outPeak = 0f
                    for (index in 0 until sampleCount) {
                        val value = data[index]
                        outSum += value.toDouble() * value
                        val magnitude = if (value < 0f) -value else value
                        if (magnitude > outPeak) outPeak = magnitude
                    }
                    DirectPathTelemetry.stageInputSumSquares += inSum
                    DirectPathTelemetry.stageInputSamples += sampleCount
                    DirectPathTelemetry.stageInputPeak = maxOf(DirectPathTelemetry.stageInputPeak, inPeak)
                    DirectPathTelemetry.stageOutputSumSquares += outSum
                    DirectPathTelemetry.stageOutputSamples += sampleCount
                    DirectPathTelemetry.stageOutputPeak = maxOf(DirectPathTelemetry.stageOutputPeak, outPeak)
                } else {
                    DirectPathTelemetry.timeProcess { process(data) }
                }
                DirectPathTelemetry.processedBlocks.incrementAndGet()
                DirectPathTelemetry.outputFrames.addAndGet(frames.toLong())
            }
        }

        if (handleBufferCallCount <= 3) {
            val active = stage.activeProcessing().joinToString(",") { it.name }
            Log.i(
                TAG,
                "handleBuffer #$handleBufferCallCount: PROCESSED " +
                    "in=${PcmUtils.bytesPerSample(currentEncoding) * 8}bit frames=$frames " +
                    "active=[$active]"
            )
        }
    }

    /**
     * Moves PCM production between the two structurally different producers after the signal mode
     * changed mid-track.
     *
     * Pristine and processed do not differ by a flag: with the native engine (plan B, B12a) pristine is
     * produced by the engine, which writes to the USB context from its own thread and never shows the
     * application a sample, while processed is produced by ExoPlayer's renderer so the stage can see
     * every sample. Moving between them therefore means tearing down one producer and starting the
     * other. By default (no engine) the pipeline produces both and only [realignWithinPipeline] runs.
     *
     * Both directions anchor the new producer at the position the DAC is about to play, so the
     * audible result is a short gap rather than a jump. Neither releases the USB stream: the claim,
     * the negotiated rate and the clock stay exactly as they were, which is the whole reason this
     * is done inside the sink instead of by rebuilding the player.
     *
     * Runs on the playback thread, which is where this sink already creates and destroys the engine
     * (`configure`, `flush`, `release` all do), so the engine lifecycle here needs no new locking.
     */
    private fun realignPipelineForSignalMode() {
        // Capture the operands separately, before anything combines or mutates them. This is the
        // only moment at which all of them are still true of the same instant.
        captureAnchorDiagnostics()

        // Read ownership before anything is torn down: `explicitSeekOwed` compares against the live
        // engine, and the PRISTINE direction is about to destroy one and build another.
        val explicitOwed = explicitSeekOwed()
        val explicitTarget = explicitSeekTargetUs.get()

        val positionUs = handoffPositionUs()
        val startedAtNs = System.nanoTime()
        Log.i(
            TAG,
            "Signal path → $signalMode; realigning producers at ${positionUs / 1000}ms" +
                " explicitOwed=$explicitOwed explicitTarget=${explicitTarget / 1000}ms"
        )

        // B9-3: the pipeline produces before and after (since B12a that is the default for every format; the
        // engine is plan B behind a debug switch). Nothing structural moves: the thread, its ~1.4 s of queued audio and the clock stay, and
        // only what is queued from now on is processed (or not). Before, this stopped the thread or sought
        // the player — a flush — and threw that queue away at every toggle.
        if (pipelineStaysProducer()) {
            realignWithinPipeline(positionUs, startedAtNs)
            return
        }

        if (nativeEngineAllowed()) {
            // PROCESSED → PRISTINE: stop feeding the DAC from ExoPlayer, hand the position to a
            // freshly created engine, and let LoadControl close so the renderer stops decoding.
            usbStreamingThread?.stop()
            usbStreamingThread = null
            val stream = usbAudioStream
            if (stream == null) {
                recordRealign(positionUs, startedAtNs, "no-stream")
                return
            }
            startNativeEngineIfFlac(stream)
            val engine = nativeEngine
            if (engine != null) {
                // Ownership is re-read here, not reused from the top of the method: creating the
                // engine issues an explicit item-start command, so a value captured before the
                // teardown can already be stale. Measured on the HONOR, the stale `false` let this
                // realign mint gen=2 from an inferred `1e12 + 384ms` and supersede the correct
                // gen=1 start target of `0` that the creation had just delivered.
                val owedNow = explicitSeekOwed()
                if (owedNow) {
                    // An explicit command still owns the position. Hand the SAME target and
                    // generation to the replacement engine rather than inferring a competing one.
                    //
                    // Note this holds even when the inferred anchor looks like perfectly good
                    // media-domain time — the question is who owns the position, not whether the
                    // candidate is well-formed. On the HONOR the inferred candidate was a
                    // plausible-looking `356893ms` and it superseded an explicit `0`.
                    val carried = carryExplicitSeek(engine)
                    Log.i(
                        TAG,
                        "Realign deferred to explicit gen=${explicitSeekGeneration.get()} " +
                            "target=${explicitSeekTargetUs.get() / 1000}ms (carried=$carried)"
                    )
                } else if (positionUs >= 0) {
                    // `positionUs` is already media-item-relative and the engine's seek takes
                    // stream-relative time, which for a whole-file item is the same domain.
                    // Subtracting `windowOffsetUs` here was how the renderer-domain offset reached
                    // the engine.
                    //
                    // Only reached with no explicit command outstanding: nothing here may outrank
                    // one that is still owed.
                    val startGen = seekGeneration.incrementAndGet()
                    // This is the exact media position the new playback epoch begins at — the value
                    // the restart decision itself established, not a later sample of it.
                    DirectPathTelemetry.diagnosticEngineStartTargetUs = positionUs
                    recordMapping(
                        "ENGINE_START_TARGET",
                        "targetUs=$positionUs targetSample=${positionUs * currentSampleRate / 1_000_000L} " +
                            "generation=$startGen path=REALIGN_ENGINE_SEEK " +
                            "playerPositionBeforeUs=${playerMirror?.positionMs?.times(1000L) ?: -1L} " +
                            "initialPlayerPositionUs=$initialPlayerPositionUs",
                    )
                    // DIAGNOSTIC ONLY. Arms the substitution from the engine-start decision when the
                    // switch is on; inert when it is off.
                    if (DirectPathTelemetry.diagnosticRebaseOperandOnSeek) {
                        diagnosticRebasePending = true
                        DirectPathTelemetry.diagnosticRebasePendingTargetUs = positionUs
                        DirectPathTelemetry.diagnosticRebasePendingSource = "ENGINE_START_TARGET"
                    }
                    seekEngine(engine, positionUs, startGen, "realign")
                } else {
                    // No explicit command and no valid media anchor — a renderer-domain player
                    // position is refused upstream and arrives here as -1. Deferring leaves the
                    // engine where it is; guessing a target from the other coordinate system is
                    // what produced the EOF seek this guards against.
                    Log.w(TAG, "Realign has no valid media anchor and no explicit command; deferring")
                }
                if (isPlaying) engine.resume()
                engineNeedsInitialSeek = false
                isNativeEngineActive = true
            }
            recordRealign(positionUs, startedAtNs, "engine=${engine != null}")
            return
        }

        // PRISTINE → PROCESSED: the engine cannot be the producer for a modified signal, so it is
        // destroyed and the player is asked to restart its decode at the position the DAC reached.
        // The seek is what re-anchors the renderer: while the engine ran, LoadControl kept
        // ExoPlayer from loading, so its decoder is arbitrarily far from the audible position.
        val engine = nativeEngine
        if (engine != null) {
            engine.stop()
            engine.destroy()
            DirectPathTelemetry.nativeEngineStops.incrementAndGet()
            nativeEngine = null
            isNativeEngineActive = false
            activeEnginePath = null
            engineNeedsInitialSeek = false
        }
        // Arm-only: the latch becomes possible again but the offset is preserved, so the guard
        // `if (windowOffsetUs < 0)` still refuses to re-latch. Recorded separately from a reset
        // because "armed but not latched" is a different state from "armed and latched".
        recordMapping(
            "OFFSET_ARM_ONLY",
            "offsetPreservedUs=$windowOffsetUs needsInit=true reason=REALIGN " +
                "gen=${mappingGeneration.get()} " + mappingOperandState() + " " + mappingPlayerState(),
        )
        usbStartMediaTimeNeedsInit = true
        // Armed with the anchor below, once it is known; see realign.
        // `windowOffsetUs` is deliberately NOT cleared here, and that is a correction to an earlier
        // "fix". The renderer↔media mapping belongs to the media *period*, and a realignment seeks
        // within the same period — the mapping does not change. Clearing it made the latch recompute
        // from a mismatched pair: the post-seek presentation timestamp against an
        // `initialPlayerPositionUs` captured when the item began. That mismatch is precisely how a
        // stale renderer-epoch error survives a transition.
        //
        // The flag still moves, because what genuinely must be re-established is the framesWritten
        // base used by the pipeline fallback.
        //
        // An explicit command still owns the position here too: the player is re-anchored on that
        // target rather than on an inferred one. When the explicit seek is still owed, `positionUs`
        // is either the native engine's own reading of that same target (the engine was running and
        // its framesDecoded is set to the target on request) or -1 because no valid anchor existed.
        val reanchorUs = if (explicitOwed) explicitTarget else positionUs
        // The pipeline period starts at this anchor, not at whatever stale buffer arrives first: the
        // seek below is a no-op when the player already believes it is there (AUD-110).
        pipelineClock.arm(if (reanchorUs >= 0 && windowOffsetUs >= 0) windowOffsetUs + reanchorUs else -1L)
        if (reanchorUs >= 0) {
            Handler(Looper.getMainLooper()).post {
                attachedPlayer?.seekTo(reanchorUs / 1000)
            }
        }
        recordRealign(
            positionUs,
            startedAtNs,
            "engine=destroyed seek-async=true explicitOwed=$explicitOwed " +
                "reanchor=${reanchorUs / 1000}ms",
        )
    }

    /**
     * B9-3: a signal-mode change with the pipeline as the producer on both sides. The queue is kept; the
     * processing of what is queued from now on changes, joined to what is already queued by a short crossfade
     * ([ProcessingCrossfade]) instead of a step. Crossfade, not a hard switch at the buffer boundary: the stage's
     * output differs from its input by the whole EQ and a stage engaged cold starts from zero state, either of
     * which is a click against the audio before it; 20 ms is long enough to hide both and short enough that
     * the change is heard as the change.
     *
     * The change reaches the audio that is queued *after* what the thread already holds (up to ~1.4 s), as it
     * has to when the buffers are already converted; the alternative, converting at write time, is not done here.
     */
    private fun realignWithinPipeline(positionUs: Long, startedAtNs: Long) {
        val engaged = engagedStage
        val retired = retiredStage
        val frames = PipelineThread.crossfadeFrames(currentSampleRate)
        pipelineCrossfade = when {
            // Towards PROCESSED: the buffer's own samples first, the stage's output at the end.
            engaged != null && retired == null -> ProcessingCrossfade(engaged, towardsProcessed = true, totalFrames = frames)
            // Towards PRISTINE: the stage's output first, the buffer's own samples at the end.
            engaged == null && retired != null -> ProcessingCrossfade(retired, towardsProcessed = false, totalFrames = frames)
            else -> null
        }
        DirectPathTelemetry.pipelineRealignsKept.incrementAndGet()
        val queuedFrames = usbStreamingThread?.queuedFrames() ?: 0L
        Log.i(
            TAG,
            "Realign within the pipeline: thread and queue kept ($queuedFrames frames queued), " +
                "crossfade=${pipelineCrossfade?.let { "${if (it.towardsProcessed) "to PROCESSED" else "to PRISTINE"} $frames frames" } ?: "none"}"
        )
        recordRealign(positionUs, startedAtNs, "kept=pipeline queuedFrames=$queuedFrames crossfadeFrames=${if (pipelineCrossfade != null) frames else 0}")
    }

    /** Playback thread: the queue the retired stage and the crossfade were for is gone. */
    private fun resetRetiredStage() {
        val retired = retiredStage ?: return
        retiredStage = null
        synchronized(stageLock) { retired.reset() }
    }

    /**
     * Records one mode transition: where the position was, where it landed, and how long the
     * realignment took.
     *
     * The position *after* is read on the far side of the switch, so the delta is a measurement of
     * what the listener actually experienced rather than a restatement of the intent. For
     * PRISTINE → PROCESSED the new producer has not started yet, so the delta reads as the
     * streaming thread's queue depth at the moment of the switch — which is itself the number worth
     * having, because it bounds the gap.
     */
    private fun recordRealign(beforeUs: Long, startedAtNs: Long, detail: String) {
        // Captured before anything below can call handoffPositionUs again and overwrite it: this is
        // the origin of the value the transition actually anchored on, not of a later read.
        val anchorOrigin = lastAnchorOrigin
        DirectPathTelemetry.recordIfCapturing("REALIGN") {
            val afterUs = handoffPositionUs()
            val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000
            val engine = nativeEngine
            "→$signalMode before=${beforeUs / 1000}ms after=${afterUs / 1000}ms " +
                "delta=${(afterUs - beforeUs) / 1000}ms duration=${elapsedMs}ms $detail " +
                "anchor=${anchorOrigin.wire} " +
                "anchorUs=$beforeUs anchorRenderDomain=${beforeUs >= RENDERER_DOMAIN_FLOOR_US} " +
                "enginePosUs=${engine?.takeIf { it.isCreated }?.getPositionUs() ?: -1L} " +
                "windowOffsetUs=$windowOffsetUs " +
                "sinkReturnedUs=${DirectPathTelemetry.liveSinkReturnedUs} " +
                "player=${config.signalSource?.handoffContext() ?: "-"}"
        }
    }

    /**
     * Which branch supplied the most recent [handoffPositionUs] value.
     *
     * Written and read only from the playback thread, which is the only thread that calls that
     * method — [realignPipelineForSignalMode] and [recordRealign] both run there.
     */
    private enum class AnchorOrigin(val wire: String) {
        NATIVE_ENGINE("NATIVE_ENGINE"),
        ENGINE_RENDERER_DOMAIN("NATIVE_ENGINE_RENDERER_DOMAIN"),
        PIPELINE_FRAMES("PIPELINE_FRAMES"),
        PIPELINE_RENDERER_DOMAIN("PIPELINE_FRAMES_RENDERER_DOMAIN"),
        UNKNOWN("UNKNOWN"),
    }

    private var lastAnchorOrigin = AnchorOrigin.UNKNOWN

    /**
     * The media-timeline position the DAC is about to play, in microseconds, or -1 when unknown.
     *
     * Read from whichever producer is currently running, because the two keep position in
     * different ways: the engine counts decoded frames from the FLAC's own start, while the
     * ExoPlayer pipeline counts frames written to USB from the last flush.
     */
    private fun handoffPositionUs(): Long {
        // The native engine's position IS the anchor while it runs: stream-relative from sample 0,
        // which for a whole-file item is media-item-relative too. `windowOffsetUs` is deliberately
        // not added — it is built from the renderer's buffer timestamp, which was measured at
        // 1_000_000_000_000 us on this path and is not a media position at all.
        val engine = nativeEngine
        if (engine != null && engine.isCreated && engine.isRunning) {
            // `nativeSeek` stores the requested target in `framesDecoded` verbatim on request, so a
            // target that was already in the wrong coordinate system poisons `getPositionUs()` until
            // the next real seek replaces it — measured on the HONOR, a realign anchored at
            // `1e12 + 384ms` and seeked the fresh engine to EOF. A renderer-domain value is refused.
            val enginePositionUs = engine.getPositionUs()
            if (enginePositionUs >= RENDERER_DOMAIN_FLOOR_US) {
                lastAnchorOrigin = AnchorOrigin.ENGINE_RENDERER_DOMAIN
                return -1L
            }
            lastAnchorOrigin = AnchorOrigin.NATIVE_ENGINE
            return enginePositionUs
        }
        // A running pipeline period knows the audible position exactly: its renderer-domain clock
        // minus the item's renderer↔media offset.
        if (!usbStartMediaTimeNeedsInit && windowOffsetUs >= 0 && currentSampleRate > 0) {
            val frames = usbAudioStream?.framesWritten
            if (frames != null) {
                val mediaUs = pipelineClock.positionUs(frames, currentSampleRate) - windowOffsetUs
                if (mediaUs in 0 until RENDERER_DOMAIN_FLOOR_US) {
                    lastAnchorOrigin = AnchorOrigin.PIPELINE_FRAMES
                    return mediaUs
                }
            }
        }
        // AUD-139: not the host's mirror. It was the third source here, and the cache behind it is
        // refreshed only when somebody reads it, so a read could return the position of another track
        // from minutes before (B7-fix4: `anchor=240020ms`, the host position of a track 16 minutes
        // gone). What the pipeline knows exactly is above; when it knows nothing there is no anchor,
        // and `-1` already means "no valid media anchor" to every caller, each of which declines to
        // seek on it — an explicit seek that is owed is carried instead.
        if (usbStartMediaTimeNeedsInit) {
            lastAnchorOrigin = AnchorOrigin.UNKNOWN
            return -1L
        }
        val frames = usbAudioStream?.framesWritten ?: run {
            lastAnchorOrigin = AnchorOrigin.UNKNOWN
            return -1L
        }
        if (currentSampleRate <= 0) {
            lastAnchorOrigin = AnchorOrigin.UNKNOWN
            return -1L
        }
        // The pipeline fallback is the third route a renderer-domain value can arrive by:
        // `usbStartMediaTimeUs` is latched from `presentationTimeUs`, which is a renderer buffer
        // timestamp, so this sum is in the renderer's coordinate system and not media time.
        // Measured on the HONOR it produced `1e12 + 336ms` here, which the ownership rule then had
        // to catch downstream — refusing it at the source keeps one rule in one place.
        val pipelinePositionUs = pipelineClock.positionUs(frames, currentSampleRate)
        if (pipelinePositionUs >= RENDERER_DOMAIN_FLOOR_US) {
            lastAnchorOrigin = AnchorOrigin.PIPELINE_RENDERER_DOMAIN
            return -1L
        }
        lastAnchorOrigin = AnchorOrigin.PIPELINE_FRAMES
        return pipelinePositionUs
    }

    // ── Position tracking via USB framesWritten ────────────────────────

    private var posLogCount = 0L

    private var engineEndNotified = false

    /** Incremented on every [flush]; lets a deferred end-of-track action detect it was overtaken. */
    @Volatile
    private var positionResetCount = 0L

    /** [positionResetCount] when the current explicit seek was issued; see [BootstrapSeekPolicy]. */
    @Volatile
    private var explicitSeekIssuedAtResetCount = Long.MIN_VALUE / 2

    /**
     * Resolves a natural end of the native engine on the player's looper.
     *
     * Media3 never reaches STATE_ENDED here, because LoadControl kept the renderer from reading to
     * end of stream, so the sink stands in for it. The item must not be left paused at its end with
     * a dead engine: play() would resume nothing while LoadControl stays closed, which is a silent
     * stall. Rewinding through [requestMediaSeek] hands the target to the engine [flush] recreates,
     * instead of letting flush infer it from a host mirror that may still read EOF.
     */
    /**
     * The engine stopped short of the end (USB failure, read or decode error): pause where it
     * stopped, and hand that position to the engine [flush] recreates, so play() retries from there
     * instead of resuming a dead engine (the silent stall [onNaturalEnd] describes) or skipping.
     */
    private fun onEngineFailed(p: Player, stoppedAtUs: Long) {
        p.pause()
        requestMediaSeek(stoppedAtUs)
        p.seekTo(p.currentMediaItemIndex, stoppedAtUs / 1000)
    }

    private fun onNaturalEnd(p: Player) {
        // Only the watchdog's fallback now (AUD-128): the natural end is Media3's own transition.
        naturalEnd.onUserSeek()
        when {
            p.repeatMode == Player.REPEAT_MODE_ONE -> {
                requestMediaSeek(0L)
                p.seekTo(p.currentMediaItemIndex, 0L)
            }
            p.hasNextMediaItem() -> p.seekToNextMediaItem()
            else -> {
                p.pause()
                requestMediaSeek(0L)
                p.seekTo(p.currentMediaItemIndex, 0L)
            }
        }
    }

    /**
     * A stopped engine, resolved once: a natural end (the clock runs on until Media3's own
     * transition), a failure, or nothing. Reached from the position query and, for an end of stream
     * handleBuffer sees first, from there (AUD-134).
     */
    private fun onEngineStopped(engine: NativeAudioEngine) {
        if (engineEndNotified) return
        engineEndNotified = true
        // Only a real end of stream ends the track (AUD-013/AUD-056): a USB failure or a
        // read error used to be resolved as a natural end and skip to the next track.
        val exit = engine.exitReason
        val action = NativeEngineEndPolicy.resolve(exit)
        Log.i(TAG, "Engine finished — exit=$exit action=$action")
        if (action == NativeEngineEndPolicy.Action.FAIL_DIRECT) {
            publishDiagnostics(
                diagnostics.copy(
                    state = UsbDirectSinkState.USB_DIRECT_ERROR,
                    errorReason = "Native engine stopped: USB stream failed",
                )
            )
        }
        val p = attachedPlayer
        if (action == NativeEngineEndPolicy.Action.NATURAL_END) {
            // AUD-016 / AUD-128: no seek. The clock keeps running past the last sample and
            // loading is unblocked, so Media3 crosses into the next item (or repeats, or
            // ends) by itself. The watchdog restores the old advance only if it never does.
            val now = SystemClock.elapsedRealtime()
            naturalEnd.onEngineFinished(engine.getPositionUs(), windowOffsetUs, now)
            isNativeEngineActive = false
            Log.i(TAG, "Natural end: position continues past ${engine.getPositionUs() / 1000}ms for Media3's own transition")
            // AUD-137: Media3's own transition already came, just before this end of stream.
            if (naturalEnd.consumeEarlyTransition(now)) {
                Log.i(TAG, "Natural end: Media3 had already moved on; opening the current item now")
                playbackHandler?.post {
                    cleanUpFinishedEngineForItem()
                    createPendingEngine(RendererOffsetLedger.TRANSITION_REASON_AUTO)
                }
            }
            // The item this engine played; the watchdog never advances past a different one.
            val endedSeq = engineTransitionSeq
            if (p != null) {
                Handler(p.applicationLooper).postDelayed({
                    if (naturalEnd.overdue(SystemClock.elapsedRealtime(), itemChanged = transitionSeq != endedSeq)) {
                        Log.w(TAG, "Natural end: no transition from Media3; falling back to advancing")
                        onNaturalEnd(p)
                    }
                }, NaturalEndCoordinator.WATCHDOG_MS)
            }
        } else if (p != null && action != NativeEngineEndPolicy.Action.NONE) {
            val resetsAtEnd = positionResetCount
            val stoppedAtUs = engine.getPositionUs()
            Handler(p.applicationLooper).post {
                // A seek or item change since the end was seen has already moved on.
                if (positionResetCount != resetsAtEnd) return@post
                onEngineFailed(p, stoppedAtUs)
            }
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        // Single exit for the Direct path; see the write sites below.
        var directResult: Long? = null
        if (config.directEnabled) {
            val streamAlive = usbAudioStream?.isAlive == true
            val engine = nativeEngine
            val engineCreated = engine?.isCreated == true

            if (++posLogCount % 500 == 1L) {
                Log.i(TAG, "getPositionUs: streamAlive=$streamAlive engine=$engineCreated " +
                        "running=${engine?.isRunning} window=$windowOffsetUs enginePos=${engine?.getPositionUs()}")
            }

            // Detect engine finished — advance to next track internally.
            // ExoPlayer's renderer never reaches outputStreamEnded because
            // LoadControl blocked loading, so we skip externally via the Player ref.
            if (engine != null && !engine.isRunning) onEngineStopped(engine)

            // Native engine: the engine's own stream position, which for a local whole-file item is
            // also its media-item position — the domain `AudioSink.getCurrentPositionUs` must answer
            // in.
            //
            // `windowOffsetUs` is deliberately NOT added. It is a difference between two *renderer*
            // timestamps, so it is meaningful only where both terms come from that domain — which is
            // the initial-seek path below, where it cancels. Adding it to a media-domain value mixed
            // two coordinate systems: measured on the HONOR, the reported position ran tens of
            // seconds ahead of the engine and the gap compounded by roughly one toggle cycle each
            // time, because every PRISTINE→PROCESSED handoff anchored on the engine while seeking
            // the player, which believed a different clock.
            if (naturalEnd.pending && windowOffsetUs >= 0) {
                // AUD-128: past the last sample, the clock runs on so Media3 reaches the boundary.
                directResult = windowOffsetUs + naturalEnd.positionUs(SystemClock.elapsedRealtime())
            } else if (streamAlive && engineCreated && engine!!.isRunning) {
                val enginePositionUs = engine.getPositionUs()
                // A natural end whose next-period offset Media3 never reported: the arithmetic after
                // a grace, rather than a clock held at NOT_SET into a StuckPlayerException.
                if (naturalOffsetAwaitSinceMs >= 0L &&
                    SystemClock.elapsedRealtime() - naturalOffsetAwaitSinceMs >= NATURAL_OFFSET_GRACE_MS
                ) {
                    naturalOffsetAwaitSinceMs = -1L
                    if (!mappingTracker.isLatched && naturalOffsetFallbackUs >= 0L) {
                        Log.w(TAG, "Natural end: no offset from Media3 for the next item; using previous offset + length")
                        adoptItemOffset(naturalOffsetFallbackUs, naturalOffsetReportedBeforeUs, "NATURAL_END_FALLBACK")
                    }
                }
                checkEngineCoherence(engine, enginePositionUs)
                // The only write site for this field. Reading it without this line in place is how
                // an earlier session mistook a never-written sentinel for "the branch never runs".
                DirectPathTelemetry.liveEnginePositionUs = enginePositionUs

                // Publish the decoder's seek acknowledgement. Guarded on a seek actually being
                // outstanding so the steady state costs one atomic read rather than three JNI
                // calls on the playback thread. The value itself is read from the engine, never
                // inferred from enginePositionUs — that field is set to the target on request and
                // cannot distinguish "asked to seek" from "has seeked".
                if (seekGeneration.get() > DirectPathTelemetry.seekCompletedGeneration) {
                    val completedGeneration = engine.completedSeekGeneration
                    val outcome = engine.seekOutcome
                    DirectPathTelemetry.seekCompletedGeneration = completedGeneration
                    DirectPathTelemetry.seekCompletedTargetUs = engine.completedSeekTargetUs
                    DirectPathTelemetry.seekCompletedOk =
                        outcome == NativeAudioEngine.SeekOutcome.SUCCESS
                    DirectPathTelemetry.seekOutcome =
                        outcome.name
                    // Post-quiescence ownership, so before/after can be compared per cycle.
                    DirectPathTelemetry.urbDiagAfterSeek = usbAudioStream?.urbDiag ?: "urb=none"
                    DirectPathTelemetry.barrierIdentity = usbAudioStream?.barrierIdentityDiag ?: "identity=none"

                    when (outcome) {
                        // Release ownership only on success: the decoder is at the target, so the
                        // handoff anchor may take over from a real position.
                        NativeAudioEngine.SeekOutcome.SUCCESS -> {
                            explicitSeekCompletedGeneration = completedGeneration
                            explicitSeekFailed = false
                        }
                        // Terminal for the generation, but the target is NOT the position.
                        NativeAudioEngine.SeekOutcome.FAILED ->
                            if (!explicitSeekFailed) onExplicitSeekFailed(completedGeneration)
                        NativeAudioEngine.SeekOutcome.PENDING -> Unit
                    }
                }

                // RENDERER DOMAIN. This API is the renderer clock's input, not a media-position
                // readout — proven from bytecode, not from the name:
                //
                //   MediaCodecAudioRenderer.updateCurrentPosition():
                //       currentPositionUs = audioSink.getCurrentPositionUs(isEnded())   // verbatim
                //   ExoPlayerImplInternal.resetRendererPosition():
                //       rendererPositionUs = period.toRendererTime(mediaPositionUs)
                //                          = mediaPositionUs + period.getRendererOffset()
                //
                // Media3 maps the renderer clock *back* to media time by subtracting that period
                // offset. Returning a bare media position here makes Media3 subtract the renderer
                // offset from it, which is how a ~40 s position became ~-1e12: the reported value
                // was the consequence of this line, not evidence of a defect upstream.
                //
                // `windowOffsetUs` is our estimate of that renderer↔media offset, so the sum is the
                // renderer position. It is deliberately NOT used for the handoff anchor, which needs
                // media time; see `handoffPositionUs`.
                directResult = if (windowOffsetUs >= 0) {
                    windowOffsetUs + enginePositionUs
                } else {
                    AudioSink.CURRENT_POSITION_NOT_SET
                }
            } else if (streamAlive) {
                // ExoPlayer pipeline fallback: relative framesWritten + startMediaTime
                directResult = if (usbStartMediaTimeNeedsInit) {
                    AudioSink.CURRENT_POSITION_NOT_SET
                } else {
                    val frames = usbAudioStream?.framesWritten ?: 0L
                    if (currentSampleRate > 0) {
                        // Frames since this period's first buffer, not since the stream's last flush.
                        pipelineClock.positionUs(frames, currentSampleRate)
                    } else {
                        AudioSink.CURRENT_POSITION_NOT_SET
                    }
                }
            }
        }
        if (directResult != null) {
            // Single exit for the Direct path, so these two have exactly one write site each and a
            // reading can be trusted without first proving the site exists.
            DirectPathTelemetry.liveWindowOffsetUs = windowOffsetUs
            DirectPathTelemetry.liveSinkReturnedUs = directResult
            // Trace here, inside the query, so every layer is read at one instant and a query that
            // stops being called is distinguishable from one that returns a frozen value.
            tracePosition(
                enginePositionUs = DirectPathTelemetry.liveEnginePositionUs,
                windowOffsetUs = windowOffsetUs,
                sinkReturnedUs = directResult,
            )
            return directResult
        }
        return super.getCurrentPositionUs(sourceEnded)
    }

    override fun isEnded(): Boolean {
        if (config.directEnabled) {
            val engine = nativeEngine
            // Engine still running → not ended
            if (engine != null && engine.isRunning) return false
            // Engine exists but stopped at the end of the file → signal ended directly.
            // Cannot delegate to super because LoadControl blocked ExoPlayer's loading,
            // so the delegate never reached end-of-stream on its own. A stop for any other reason
            // is not an end (AUD-013): the failure handler pauses instead.
            if (engine != null && !engine.isRunning) return NativeEngineEndPolicy.reportsEnded(engine.exitReason)
            val thread = usbStreamingThread
            if (thread != null && handledEndOfStream) {
                return thread.isEndOfStreamDrained()
            }
        }
        return super.isEnded()
    }

    override fun hasPendingData(): Boolean {
        if (config.directEnabled) {
            // Engine running → has pending data
            if (nativeEngine?.isRunning == true) return true
            if (usbStreamingThread?.hasPendingData() == true) return true
        }
        return super.hasPendingData()
    }

    override fun playToEndOfStream() {
        if (!handledEndOfStream) {
            // The queue marker preserves ordering: all source buffers are
            // written before the packetizer submits its final frame-aligned
            // residual. Native FLAC performs the same drain in its decode
            // thread when libFLAC reports natural EOS.
            val thread = usbStreamingThread
            handledEndOfStream = thread == null || thread.queueEndOfStream()
        }
        // Always propagate to delegate — ExoPlayer needs this signal to
        // detect end-of-stream and transition to the next track.
        super.playToEndOfStream()
    }

    override fun play() {
        super.play()
        isPlaying = true
        cancelHeldEnginePause("play()")
        cancelHeldThreadPause("play()")
        val resumed = if (!engineNeedsInitialSeek) { nativeEngine?.resume(); true } else false
        usbStreamingThread?.resumeStreaming()
        Log.i(TAG, "play() needsSeek=$engineNeedsInitialSeek resumed=$resumed")
        updatePresence()
    }

    override fun pause() {
        isPlaying = false
        if (!engineNeedsInitialSeek) pauseNativeEngine()
        pauseStreamingThread()
        super.pause()
        updatePresence()
    }

    /**
     * Media3 stopped its renderers. For the pipeline's thread that is a pause — the ring plays out and ends
     * in an 8 ms fade of the real continuation, the rest of that block waits for the resume (B9-1) — except right
     * after a natural join, where it is the rebuffer of the new item and the user is still playing: held back
     * under the same policy as the engine's ([NaturalJoinPause]), so the join stays bit-exact. A pause by the
     * user (`playWhenReady` false), a pause long after the join and a pause after a seek or a skip reach the
     * thread at once.
     */
    private fun pauseStreamingThread() {
        val thread = usbStreamingThread ?: return
        cancelHeldThreadPause("pause()")
        val handler = playbackHandler
        val joinedAtMs = naturalJoinAtMs
        val sinceJoinMs = if (joinedAtMs >= 0L) SystemClock.elapsedRealtime() - joinedAtMs else -1L
        if (handler == null ||
            NaturalJoinPause.onMedia3Stop(userWantsPlayback, sinceJoinMs) == NaturalJoinPause.Action.PAUSE_NOW
        ) {
            Log.i(
                TAG,
                "pause(): PAUSE_NOW (thread) sinceJoinMs=$sinceJoinMs userWantsPlayback=$userWantsPlayback " +
                    "reason=${if (handler == null) "no-handler" else NaturalJoinPause.reason(userWantsPlayback, sinceJoinMs)} " +
                    "naturalJoinAtMs=$joinedAtMs"
            )
            thread.pauseStreaming()
            return
        }
        val held = Runnable {
            heldThreadPause = null
            if (!isPlaying && usbStreamingThread === thread) {
                Log.i(TAG, "Held pause expired after ${NaturalJoinPause.MAX_HOLD_MS} ms without play(): pausing the streaming thread")
                thread.pauseStreaming()
            }
        }
        heldThreadPause = held
        handler.postDelayed(held, NaturalJoinPause.MAX_HOLD_MS)
        DirectPathTelemetry.naturalJoinPausesHeld.incrementAndGet()
        Log.i(
            TAG,
            "pause(): Media3 stopped ${sinceJoinMs} ms after a natural join, user still playing; " +
                "the streaming thread keeps playing (≤${NaturalJoinPause.MAX_HOLD_MS} ms) — B9-1"
        )
    }

    /** Playback thread: drops a held stop of the streaming thread (Media3 resumed, or the thread it was for is gone). */
    private fun cancelHeldThreadPause(why: String) {
        val held = heldThreadPause ?: return
        heldThreadPause = null
        playbackHandler?.removeCallbacks(held)
        Log.i(TAG, "Held thread pause cancelled by $why: the streaming thread was never paused")
    }

    /** Playback thread: the user paused while a stop was being held: it applies now. */
    private fun applyHeldThreadPauseNow() {
        val held = heldThreadPause ?: return
        playbackHandler?.removeCallbacks(held)
        held.run()
    }

    /** Playback thread: Media3 advanced by itself with the pipeline as the producer: a join, as for an engine (AUD-144). */
    private fun markPipelineJoin() {
        if (NaturalJoinPause.marksPipelineJoin(engineExists = nativeEngine != null, threadRunning = usbStreamingThread != null)) {
            naturalJoinAtMs = SystemClock.elapsedRealtime()
            logJoinMark("markPipelineJoin", "SET")
        } else {
            logJoinMark("markPipelineJoin", "not set (not the pipeline's join)")
        }
    }

    /**
     * B10-4 (AUD-144 diagnostics, logging only): where the join mark was set or left alone, and what the sink knew
     * at that moment. The B9 native `rate` run faded its join (`joinPausesHeld=0`): the engine's decode thread had
     * exited 14 ms before Media3's transition and no `Engine finished` line preceded it, so the mark's two setters
     * (a pending natural end, an engine created for one) had nothing to go on. These lines, with the `PAUSE_NOW`
     * line `pause()` now writes, settle it on the next measurement.
     */
    private fun logJoinMark(site: String, what: String) {
        val engine = nativeEngine
        Log.i(
            TAG,
            "Join mark @$site: $what — naturalJoinAtMs=$naturalJoinAtMs naturalEndPending=${naturalEnd.pending} " +
                "enginePendingForItem=$enginePendingForItem engine=${if (engine == null) "none" else if (engine.isRunning) "running" else "stopped"} " +
                "thread=${usbStreamingThread != null}"
        )
    }

    /**
     * Media3 stopped its renderers. For the engine that is a user's pause — a fade-out queued behind
     * the ring and a fade-in on the resume — except right after a natural join, where it is the
     * rebuffer of the new item and the user is still playing: held back (AUD-144, see [NaturalJoinPause]).
     */
    private fun pauseNativeEngine() {
        val engine = nativeEngine ?: return
        cancelHeldEnginePause("pause()")
        val handler = playbackHandler
        val joinedAtMs = naturalJoinAtMs
        val sinceJoinMs = if (joinedAtMs >= 0L) SystemClock.elapsedRealtime() - joinedAtMs else -1L
        if (handler == null ||
            NaturalJoinPause.onMedia3Stop(userWantsPlayback, sinceJoinMs) == NaturalJoinPause.Action.PAUSE_NOW
        ) {
            Log.i(
                TAG,
                "pause(): PAUSE_NOW (engine) sinceJoinMs=$sinceJoinMs userWantsPlayback=$userWantsPlayback " +
                    "reason=${if (handler == null) "no-handler" else NaturalJoinPause.reason(userWantsPlayback, sinceJoinMs)} " +
                    "naturalJoinAtMs=$joinedAtMs"
            )
            engine.pause()
            return
        }
        val held = Runnable {
            heldEnginePause = null
            if (!isPlaying && nativeEngine === engine) {
                Log.i(TAG, "Held pause expired after ${NaturalJoinPause.MAX_HOLD_MS} ms without play(): pausing the engine")
                engine.pause()
            }
        }
        heldEnginePause = held
        handler.postDelayed(held, NaturalJoinPause.MAX_HOLD_MS)
        DirectPathTelemetry.naturalJoinPausesHeld.incrementAndGet()
        Log.i(
            TAG,
            "pause(): Media3 stopped ${sinceJoinMs} ms after a natural join, user still playing; " +
                "the engine keeps playing (≤${NaturalJoinPause.MAX_HOLD_MS} ms) — AUD-144"
        )
    }

    /** Playback thread: drops a held stop (Media3 resumed, or the engine it was for is gone). */
    private fun cancelHeldEnginePause(why: String) {
        val held = heldEnginePause ?: return
        heldEnginePause = null
        playbackHandler?.removeCallbacks(held)
        Log.i(TAG, "Held engine pause cancelled by $why: the engine was never paused")
    }

    /** Playback thread: the user paused while a stop was being held: it applies now, as it always did. */
    private fun applyHeldEnginePauseNow() {
        val held = heldEnginePause ?: return
        playbackHandler?.removeCallbacks(held)
        held.run()
    }

    override fun setVolume(volume: Float) {
        pendingVolume = volume
        if (config.directEnabled && usbAudioStream?.isAlive == true) {
            muteDelegateIfNeeded()
        } else {
            unmuteDelegateIfNeeded()
        }
    }

    /**
     * Media3's seek hook — `MediaCodecAudioRenderer.onPositionReset` calls this, proven from bytecode.
     *
     * It deliberately does **not** seek the native engine, and it deliberately does not try to work
     * out where the seek was going. A user seek is pushed through [requestMediaSeek] with the
     * target the host was given, and an item start is pushed at engine creation; both carry a
     * generation, so this callback has nothing to reconstruct and must not pretend otherwise.
     *
     * It used to carry the comment "Native engine handles its own flush/seek internally", which was
     * simply untrue — nothing here seeked the engine. The seek was left to the next `handleBuffer`,
     * which in PRISTINE may never arrive because `NativeEngineAwareLoadControl` is holding ExoPlayer
     * off the PCM path on purpose. That is how a seek came to be accepted by the player and never
     * performed by the producer.
     */
    override fun flush() {
        // Flush origin. Two incidents had a flush within milliseconds of the stuck error, and
        // timestamps alone cannot say whether it caused the stall or was error teardown after it.
        // The caller identifies which path initiated it.
        runCatching {
            val stack = Throwable().stackTrace
                .drop(1)
                .take(4)
                .joinToString("<-") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            DirectPathTelemetry.flushOrigin = stack
        }
        DirectPathTelemetry.flushAtMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "flush() origin=${DirectPathTelemetry.flushOrigin} engineRunning=${nativeEngine?.isRunning} " +
                "awaitingSeekUs=$seekAwaitingEngineUs"
        )
        // Captured before the reset: what the mapping looked like on the way in.
        val offsetBeforeFlushUs = windowOffsetUs
        val operandBeforeFlushUs = initialPlayerPositionUs
        super.flush()
        // ExoPlayer-pipeline flush only; the native producer owns its own position.
        val thread = usbStreamingThread
        if (thread != null) {
            // B9-2: the thread ends the ring in a fade of what it held, feeds silence and fades the new
            // position in; nothing is cancelled. See UsbStreamingThread.flush.
            thread.flush()
            // B9-3: the queue a crossfade and a retired stage were for is gone.
            pipelineCrossfade = null
            resetRetiredStage()
        } else if (nativeEngine?.isRunning != true) {
            // A running native engine's decode thread is inside submitPcmToUrbs on this same
            // context; resetting its packetizer phase and residual from here raced it (AUD-107).
            // Its own seek barrier handles the stream on a seek.
            usbAudioStream?.flush()
        }
        usbStartMediaTimeNeedsInit = true
        pipelineClock.disarm()
        // `windowOffsetUs` is deliberately NOT cleared here; see the note in the realignment path.
        // A flush is a seek inside the current period, so the renderer↔media mapping is unchanged
        // and clearing it would only force a mismatched re-latch.
        DirectPathTelemetry.flushCount.incrementAndGet()
        handledEndOfStream = false
        // A natural-EOS engine cannot service a subsequent seek/replay because
        // its decode thread has already exited. Keeping the stale engine marked
        // active also makes NativeEngineAwareLoadControl suppress the one
        // ExoPlayer buffer needed to initialize the replacement engine.
        // Recreate it here while preserving the current track and USB stream.
        positionResetCount++
        val awaitingUs = seekAwaitingEngineUs
        seekAwaitingEngineUs = -1L
        // A Media3-internal reset during a natural end (AUD-128) must not clear the finished engine or
        // apply the next item's deferred config while the ended track is still current.
        if (nativeEngine?.isRunning == false && !naturalEnd.pending) {
            cleanupFinishedEngine()
            // Rebuild here only for a seek within this item. A flush for an item change runs before
            // the player listener has published the new item's path, so rebuilding now reopens the
            // file that just finished — measured: the next track played the previous one's audio.
            // Clearing the finished engine is enough to reopen LoadControl for the new item.
            if (awaitingUs >= 0L) createEngineIfNeeded(declareStartTarget = false)
        }
        // A seek that found no live engine is delivered to the one that exists now, as an explicit
        // command, so the epoch logic below defers to it rather than to the host mirror.
        val freshEngine = nativeEngine
        if (awaitingUs >= 0L && freshEngine != null && freshEngine.isRunning) {
            openEpoch(awaitingUs, AnchorSource.EXPLICIT_SEEK_TARGET, "deferred-seek")
            issueExplicitSeek(freshEngine, awaitingUs, "deferred")
        }
        // Mapping-epoch boundary. A flush is Media3's position-reset hook, so the player's media
        // position has moved and any operand written before this no longer describes this epoch.
        // Recorded after the engine-recreate block so the record shows the post-arm state, which is
        // the state the next latch will actually read.
        // The position-reset boundary is where a new playback position becomes authoritative, and it
        // is the last moment before the first buffer of that position can arrive. An explicit
        // command already opened its epoch at its exact target when it was issued; anything else
        // means Media3 moved the position itself — an implicit restart from ENDED, or a replay —
        // and the host's position at this boundary is the authority for the epoch that follows.
        //
        // Gated on the position actually differing: an equal host position is a same-position
        // reset, and re-anchoring one of those would be exactly the mistake this refactor removes.
        // An item transition opened this epoch at the new item's declared start and nothing has
        // latched it yet. The host mirror cannot be trusted at this boundary: it can still report
        // the outgoing item's position (see onMediaItemTransition). Measured: switching tracks
        // while the previous one was at 51 s re-anchored the new one at 51 s and it started there.
        val itemStartUnlatched = !mappingTracker.isLatched &&
            (mappingTracker.anchorSource == AnchorSource.ITEM_TRANSITION_START ||
                mappingTracker.anchorSource == AnchorSource.DECLARED_ITEM_START)
        if (itemStartUnlatched) {
            mappingTracker.preserve()
        } else if (OffsetAuthority.keepExplicitEpochAtFlush(
                mappingTracker.isLatched, mappingTracker.anchorSource,
                explicitSeekIssuedAtResetCount, positionResetCount,
            )
        ) {
            // AUD-136: the seek this reset belongs to opened this epoch at its exact target. It may
            // have completed already (measured: 44 ms, before this flush), which is not a reason to
            // replace it with the host mirror — that value was 167 s stale and moved the track there.
            mappingTracker.preserve()
        } else if (!explicitSeekOwed()) {
            // AUD-139: no host position here. The host's mirror is a cache that is refreshed only
            // when somebody reads it, and this read found the position of another track 16 minutes
            // old (an `ANCHOR` at 240020 ms in B7-fix4's position trace). An implicit reset
            // keeps the mapping; the first buffer's own stream offset from Media3 corrects an anchor
            // it contradicts (OffsetAuthority.media3Anchor), and nothing here can move the engine.
            mappingTracker.preserve()
        }
        driftMonitor.onEpochStart(SystemClock.elapsedRealtime())
        val flushGen = advanceMappingGeneration("FLUSH", "origin=${DirectPathTelemetry.flushOrigin}")
        // DIAGNOSTIC ONLY. Arm the substitution here — at the position-reset boundary, where the new
        // epoch actually begins — and freeze the target now, so the latch record names the value the
        // latch really used rather than whatever the field holds when it is finally read.
        if (DirectPathTelemetry.diagnosticRebaseOperandOnSeek &&
            DirectPathTelemetry.diagnosticRebaseTargetUs >= 0L
        ) {
            diagnosticRebasePending = true
            DirectPathTelemetry.diagnosticRebasePendingTargetUs =
                DirectPathTelemetry.diagnosticRebaseTargetUs
            DirectPathTelemetry.diagnosticRebasePendingSource = "DECLARED_SEEK_TARGET"
        }
        recordMapping(
            "SEEK_EVENT",
            "phase=position-reset gen=$flushGen offsetBeforeUs=$offsetBeforeFlushUs " +
                "offsetAfterUs=$windowOffsetUs operandBeforeUs=$operandBeforeFlushUs " +
                "operandNowUs=$initialPlayerPositionUs " +
                "operandGen=$initialPlayerPositionGeneration " + mappingPlayerState(),
        )
        // Temporarily unblock LoadControl so ExoPlayer loads at least one chunk
        // after seek. handleBuffer will re-block once it captures presentationTimeUs.
        // Without this, the LoadControl blocks ALL post-seek loading and the engine
        // never knows where to seek to.
        if (nativeEngine != null) {
            isNativeEngineActive = false
        }
    }

    override fun reset() {
        super.reset()
        // USB stream survives reset — configure() manages its lifecycle.
        // ExoPlayer calls reset() frequently (track changes, seeks).
        // Killing USB here causes audio to briefly route to the speaker.
    }

    override fun release() {
        releaseUsbStream()
        presence?.release()
        super.release()
    }

    // ── USB bit-perfect configuration ───────────────────────────────

    private fun configureUsbBitPerfect(sampleRate: Int, channelCount: Int, encoding: Int): Boolean {
        // NOTE: engine is NOT destroyed here. configure() returns early if engine
        // is still running. If we reach here, the engine is already dead or null.

        // Cache check — avoid needless USB stream recreation
        // The cached stream's container must also hold this source: after a track whose rate forced
        // a narrower alt, a deeper source at the same rate needs its own alt (or a rejection).
        val cachedContainerFits = encoding == C.ENCODING_PCM_FLOAT ||
            UsbPcmConversion.canConvert(PcmUtils.bytesPerSample(encoding) * 8, dacBitDepth ?: 0)
        if (sampleRate == currentSampleRate && channelCount == currentChannelCount
            && usbAudioStream?.isAlive == true && cachedContainerFits) {
            Log.d(TAG, "USB stream cached for rate=$sampleRate ch=$channelCount — reusing")
            currentSampleRate = sampleRate
            currentChannelCount = channelCount
            // Engine will be created lazily in handleBuffer when currentTrackPath is set
            publishDiagnostics(
                diagnostics.copy(
                    state = UsbDirectSinkState.USB_DIRECT_ACTIVE,
                    sourceSampleRate = sampleRate,
                    sourceChannelCount = channelCount,
                    sourcePcmEncoding = encoding.takeIf { it != Format.NO_VALUE },
                    errorReason = null,
                )
            )
            refreshSignalPath()
            return true
        }

        if (usbAudioStream != null) {
            playOutPipelineTailBeforeRebuild()
            releaseUsbStream()
        }

        val usbDevice = usbAudioDevice.findUsbAudioDevice()
            ?: return failUsbInitialization("USB audio device disappeared before initialization")

        // Decided from the raw descriptors, before the claim (R4-1): a format the DAC cannot carry
        // — no alt whose maxPacket fits the rate, or no container deep enough for the source — must
        // never detach snd-usb-audio, because the Android route may not come back until a replug
        // (R2-3). The track's own alt is chosen here too (R4-3), not one alt per device.
        // The advertised rate ranges below cannot move here: they are a class GET RANGE on the
        // AudioControl interface, which usbfs only allows once that interface is claimed.
        val sourceBits = if (encoding == C.ENCODING_PCM_FLOAT) null else PcmUtils.bytesPerSample(encoding) * 8
        val formatAlt = UacStreamingDescriptors.selectForFormat(
            usbAudioDevice.readPlaybackAlts(usbDevice),
            sampleRate,
            channelCount,
            sourceBits,
        ) ?: return failUsbInitialization(
            "No USB alt can carry $sampleRate Hz x $channelCount ch" +
                (sourceBits?.let { " at $it bit" } ?: "") + " (decided before claiming)"
        )

        var deviceInfo = usbAudioDevice.openDevice(usbDevice)
        if (deviceInfo == null) {
            Log.e(TAG, "Failed to open USB device")
            return failUsbInitialization("Failed to open USB audio device")
        }
        // The claim restores the DAC's saved Direct level before this point (AUD-118). If it could
        // not read or restore it, nothing plays through the DAC at an unknown — possibly full — level.
        if (usbAudioDevice.openLevelCheck == OpenLevelCheck.UNVERIFIED) {
            return failUsbInitialization(
                "DAC volume could not be read or restored after claiming; Direct refused (AUD-118)"
            )
        }

        // The track's alt: its container is the transport's sample size (sources narrower than it
        // are zero-padded in the LSBs), and its endpoint and maxPacket build the stream.
        val bitDepth = formatAlt.containerBits
        dacBitDepth = bitDepth
        val altSetting = formatAlt.altSetting
        Log.i(TAG, "USB Direct format: source=${trackBitDepth}bit → alt=$altSetting usb=${bitDepth}bit " +
                "clockSource=0x${deviceInfo.clockSourceId.toString(16)}")

        val advertisedRates = usbAudioDevice.readSampleRateRanges()
        if (advertisedRates.isNotEmpty() && advertisedRates.none { it.contains(sampleRate) }) {
            return failUsbInitialization(
                "USB DAC does not advertise support for $sampleRate Hz"
            )
        }

        var stream = UsbAudioStream(
            fd = deviceInfo.fd,
            interfaceId = deviceInfo.interfaceId,
            endpointOut = formatAlt.endpointOutAddress,
            endpointFeedback = formatAlt.feedbackEndpointAddress,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitDepth = bitDepth,
            maxPacketSize = formatAlt.maxPacketBytes
        )

        if (!stream.isReady) {
            Log.e(TAG, "USB stream creation failed")
            stream.release()
            return failUsbInitialization("USB stream creation failed")
        }

        // ─── xHCI-verified transition sequence (from USB protocol analysis) ───
        //
        // 1. setAlt(0)       → xHCI Configure Endpoint (FREE old rings)
        // 2. SET_CUR          → write new sample rate to Clock Source
        // 3. GET_CUR          → verify clock accepted (CLOCK_VALID_CONTROL)
        // 4. setAlt(0) AGAIN  → defensive reset after clock change
        // 5. setAlt(N)        → xHCI Configure Endpoint (ALLOC new rings)
        // 6. wait ~47ms       → DAC PLL lock time
        // 7. start            → submit URBs

        // Step 1: setAlt(0) — FREE old ISO rings
        if (!usbAudioDevice.setAltSetting(0)) {
            Log.w(TAG, "setAlt(0) failed — stale fd, reopening device...")
            usbAudioDevice.closeDevice()
            stream.release()
            deviceInfo = usbAudioDevice.openDevice(usbDevice)
            if (deviceInfo == null) {
                Log.e(TAG, "Failed to reopen USB device")
                return failUsbInitialization("Failed to reopen stale USB device")
            }
            // A reopen is a claim too: same level check as above (AUD-118).
            if (usbAudioDevice.openLevelCheck == OpenLevelCheck.UNVERIFIED) {
                // The stale stream was already released above, before reopening.
                return failUsbInitialization("DAC volume could not be restored after reopening; Direct refused (AUD-118)")
            }
            stream = UsbAudioStream(
                fd = deviceInfo.fd,
                interfaceId = deviceInfo.interfaceId,
                endpointOut = formatAlt.endpointOutAddress,
                endpointFeedback = formatAlt.feedbackEndpointAddress,
                sampleRate = sampleRate,
                channelCount = channelCount,
                bitDepth = bitDepth,
                maxPacketSize = formatAlt.maxPacketBytes
            )
            if (!stream.isReady) {
                Log.e(TAG, "USB stream recreation failed after reopen")
                stream.release()
                return failUsbInitialization("USB stream recreation failed after reopen")
            }
            Log.i(TAG, "Device reopened with fresh fd=${deviceInfo.fd}")
        }
        Log.i(TAG, "Step 1: setAlt(0) — old ISO ring freed")

        // Step 2: SET_CUR — write new sample rate
        val sampleRateSet = usbAudioDevice.setSampleRate(sampleRate)

        // Step 3: GET_CUR — verify the requested rate before enabling direct output.
        // Continuing after a failed SET_CUR can make a DAC run at its previous/default
        // clock while Media3 feeds a different source rate, producing incorrect-speed audio.
        val confirmedSampleRate = usbAudioDevice.readSampleRate()
        val clockValid = usbAudioDevice.readClockValid()
        Log.i(TAG, "Step 2-3: SET_CUR=$sampleRate success=$sampleRateSet, " +
                "GET_CUR=$confirmedSampleRate, CLOCK_VALID=$clockValid")
        if (!sampleRateSet || confirmedSampleRate != sampleRate || !clockValid) {
            usbAudioDevice.setAltSetting(0)
            stream.release()
            return failUsbInitialization(
                "USB DAC rejected or did not lock sample rate $sampleRate Hz " +
                    "(confirmed=$confirmedSampleRate, clockValid=$clockValid)"
            )
        }

        // Step 4: setAlt(0) AGAIN — defensive reset after clock change
        if (!usbAudioDevice.setAltSetting(0)) {
            stream.release()
            return failUsbInitialization("USB DAC failed to enter zero-bandwidth state")
        }
        Log.i(TAG, "Step 4: setAlt(0) again — defensive reset")

        // Step 5: setAlt(N) — ALLOC new ISO rings
        val altResult = usbAudioDevice.setAltSetting(altSetting)
        Log.i(TAG, "Step 5: setAlt($altSetting): $altResult — new ISO ring allocated")
        if (!altResult) {
            stream.release()
            return failUsbInitialization("USB DAC rejected streaming alternate setting $altSetting")
        }

        // Step 6: wait ~47ms — DAC PLL lock time
        Thread.sleep(50)

        // Step 6½ (AUD-126): the level, read after the alt and the clock settled and before the first
        // packet. The Melody resets its Feature Unit to 0 dB on these; the claim-time guard (AUD-118)
        // never saw a reconfigure of the device already open.
        val levelCheck = usbAudioDevice.restoreLevelAfterReconfigure("rate=$sampleRate alt=$altSetting")
        if (levelCheck == OpenLevelCheck.UNVERIFIED) {
            usbAudioDevice.setAltSetting(0)
            stream.release()
            return failUsbInitialization(
                "DAC volume could not be read or restored after the stream reconfigure; Direct refused (AUD-126)"
            )
        }

        if (!stream.start()) {
            Log.e(TAG, "USB stream start failed")
            stream.release()
            return failUsbInitialization("USB stream start failed")
        }

        // B12a-4: a stream rebuilt for another rate may owe its first buffer a pre-roll of silence (default 0).
        // `currentSampleRate` still holds the rate of the stream this one replaces (a release does not clear it);
        // a cached stream at the same rate returned above and never reaches here.
        pendingPreroll(previousRateHz = currentSampleRate, newRateHz = sampleRate)
        usbAudioStream = stream
        currentSampleRate = sampleRate
        currentChannelCount = channelCount
        muteDelegateIfNeeded()
        updatePresence()

        DirectPathTelemetry.usbClaims.incrementAndGet()
        DirectPathTelemetry.usbClaimed = true
        DirectPathTelemetry.usbAltSetting = altSetting
        DirectPathTelemetry.usbRequestedRateHz = sampleRate
        // Read back from the DAC rather than assumed: GET_CUR is what makes the rate a measurement
        // instead of a request, and a mismatch here is the incorrect-speed-audio failure.
        DirectPathTelemetry.usbConfirmedRateHz = confirmedSampleRate
        DirectPathTelemetry.recordIfCapturing("USB_CLAIM") {
            "rate=$sampleRate confirmed=$confirmedSampleRate alt=$altSetting " +
                "dacDepth=$bitDepth sourceDepth=$trackBitDepth"
        }

        // Resolve the signal path before the producer is chosen: whether the native engine may run
        // is decided from it, and getting that order wrong would start a pristine producer for a
        // stream the host has asked to process.
        refreshSignalPath()

        // Try to create engine now (works for first track where onMediaItemTransition
        // fired before configure). For subsequent tracks, createEngineIfNeeded() in
        // onMediaItemTransition handles it (path is correct by then).
        startNativeEngineIfFlac(stream)
        pendingPrerollMs = 0   // taken by the new pipeline thread above, or not owed to anyone (B12a-4)

        Log.i(TAG, "USB Direct stream ACTIVE: rate=$sampleRate ch=$channelCount " +
                "bits=$bitDepth device=${deviceInfo.deviceName}")
        publishDiagnostics(
            diagnostics.copy(
                state = UsbDirectSinkState.USB_DIRECT_ACTIVE,
                deviceName = usbDevice.productName ?: deviceInfo.deviceName,
                permissionGranted = true,
                sourceSampleRate = sampleRate,
                sourceChannelCount = channelCount,
                sourcePcmEncoding = encoding.takeIf { it != Format.NO_VALUE },
                sourceBitDepth = trackBitDepth.takeIf { it > 0 },
                dacBestBitDepth = bitDepth.takeIf { it > 0 },
                dacSupportedRates = advertisedRates.flatMap { range ->
                    if (range.minHz == range.maxHz) listOf(range.minHz) else emptyList()
                }.distinct().sorted(),
                errorReason = null,
            )
        )
        return true
    }

    private fun failUsbInitialization(reason: String): Boolean {
        Log.e(TAG, "USB Direct initialization failed: $reason")
        DirectPathTelemetry.usbClaimFailures.incrementAndGet()
        DirectPathTelemetry.usbClaimed = false
        DirectPathTelemetry.recordIfCapturing("USB_CLAIM_FAILED") { reason }
        clearForcedRouting()
        // Returns the interfaces (and snd-usb-audio) to Android rather than keeping them detached.
        usbAudioDevice.releaseToAndroid()
        // The delegate is not unmuted and not sent to the speaker (AUD-004): the error below makes
        // the host latch the failure and rebuild on the Android path, which is where the listener's
        // output is chosen — never the speaker by default.
        directFailureHold = true
        muteDelegateIfNeeded()
        publishDiagnostics(
            diagnostics.copy(
                state = UsbDirectSinkState.USB_DIRECT_ERROR,
                errorReason = reason,
            )
        )
        return false
    }

    /** Try to start a native FLAC engine. Falls back to ExoPlayer streaming thread. */
    @Synchronized
    /**
     * Creates the native engine for the current track if one is wanted and not already running.
     *
     * @param declareStartTarget true only when this creation **is** an item start, so the declared
     *        start position (0 for a fresh start) is pushed as an explicit command. Every other
     *        creation — a mid-track realign, a post-flush rebuild, the first configure — must pass
     *        false and let its caller supply the position.
     *
     *        This distinction is load-bearing and was learned the hard way: pushing the item-start
     *        target unconditionally meant a mid-track realign re-declared the track as starting at
     *        0. On the HONOR, resuming after a paused seek to 150s triggered a mode realign, and
     *        engine creation seeked the decoder back to 0 — so the first audible PCM after resume
     *        came from the start of the track instead of the seek target, while the player still
     *        reported 148.6s.
     */
    private fun startNativeEngineIfFlac(stream: UsbAudioStream, declareStartTarget: Boolean = false) {
        if (nativeEngine != null) return  // already created (synchronized method)

        // The streaming thread (mutually exclusive with the native engine) is NOT stopped here. It holds
        // audio that has been decoded and not yet heard; it only goes when an engine is really about to
        // take its place, below (AUD-143: stopping it first, before knowing there would be an engine,
        // threw away ~1.3 s of the ending track at every item transition of the pipeline).
        val path = currentTrackPath
        // The engine hands decoded samples straight to the USB URBs and never exposes them, so it
        // cannot be the producer for a processed signal. In PROCESSED mode the ExoPlayer pipeline
        // below carries the PCM instead, which is what lets the application see — and modify —
        // every sample. Skipping the engine here rather than muting its output is deliberate: a
        // running engine would still hold LoadControl closed and starve the pipeline that is now
        // supposed to be feeding the DAC.
        val engineUsable = nativeEngineAllowed()
        // Recorded on every start, on or off, so a capture says which rules its producer was chosen under.
        DirectPathTelemetry.debugForcedNativeEngine = debugForcesNativeEngine()
        if (!engineUsable && path != null) {
            if (requestedSignalMode() == DirectSignalMode.PRISTINE) {
                Log.i(TAG, "PRISTINE — ExoPlayer pipeline is the producer for ${File(path).name}" +
                    if (DirectPathTelemetry.debugForcedNativeEngine) " (plan B is on, but the stream shape rules the engine out)" else "")
            } else {
                Log.i(TAG, "PROCESSED signal path — using ExoPlayer pipeline for ${File(path).name}")
            }
        }
        if (path != null && PipelineThread.wantsEngine(engineUsable, path)) {
            val engine = NativeAudioEngine()
            try {
                val fd = android.os.ParcelFileDescriptor.open(
                    File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                val created = engine.createFromFd(fd.fd, stream.nativeHandle)
                fd.close()
                if (created) {
                    // Before start: its first audio fades in after a faded cut (AUD-123).
                    if (pendingEntryFadeIn) {
                        engine.enterWithFadeIn()
                        pendingEntryFadeIn = false
                    }
                }
                // AUD-143: whether this engine will be used is settled before the pipeline's thread is
                // touched. The same check is made again below, on the started engine.
                val rejected = created && EngineTransportMatch.mismatch(
                    engine.getSampleRate(), engine.getChannels(), currentSampleRate, currentChannelCount,
                ) != null
                if (created && !rejected) {
                    // The engine takes over the stream from its first block: the thread (and what it
                    // still holds) goes now, not before the engine could be created.
                    usbStreamingThread = PipelineThread.replaceByEngine(usbStreamingThread)
                }
                if (created && !rejected && engine.start()) {
                    // Verify FLAC sample rate matches USB stream — prevents distortion
                    // when ExoPlayer's queue and onMediaItemTransition disagree about
                    // which track is playing (e.g., cross-album Recently Played lists).
                    // AUD-141: the channels too. The engine writes its frames as-is into a transport
                    // framed with currentChannelCount: a mono FLAC at the same rate played an octave
                    // up, a 5.1 one at 3×, and `configure` then kept that engine for the whole track.
                    val mismatch = EngineTransportMatch.mismatch(
                        engine.getSampleRate(), engine.getChannels(), currentSampleRate, currentChannelCount,
                    )
                    if (mismatch != null) {
                        Log.w(TAG, "$mismatch — falling back to ExoPlayer pipeline")
                        engine.stop()
                        engine.destroy()
                    } else {
                        engine.pause()
                        nativeEngine = engine
                        isNativeEngineActive = true
                        pendingPrerollMs = 0   // plan B: the engine does not get the pre-roll (B12a-4)
                        engineEndNotified = false
                        engineTransitionSeq = transitionSeq
                        activeEnginePath = path
                        trackBitDepth = engine.getBitsPerSample()
                        DirectPathTelemetry.producer = DirectPathTelemetry.Producer.NATIVE_FLAC
                        DirectPathTelemetry.nativeEngineStarts.incrementAndGet()
                        DirectPathTelemetry.recordIfCapturing("PRODUCER") {
                            "NATIVE_FLAC ${File(path).name} " +
                                "${engine.getBitsPerSample()}bit/${engine.getSampleRate()}Hz"
                        }

                        // Place the engine at its start target now, as a pushed seek.
                        //
                        // This used to be deferred to handleBuffer ("paused, awaiting seek"), which
                        // is the same starvation trap as a user seek: in PRISTINE the engine holds
                        // LoadControl closed, so the callback that was supposed to do the seeking may
                        // never arrive and the engine sits paused forever. It also meant the start
                        // position was reconstructed from `presentationTimeUs - windowOffsetUs`,
                        // where a stale initialPlayerPositionUs put the engine at the previous
                        // item's EOF and ended playback in 20 ms.
                        //
                        // Resuming here is safe even though the seek has not completed: the decode
                        // thread executes the seek (and its barrier) before it can reach readBuffer,
                        // so no audio is submitted until the decoder is at the target.
                        if (declareStartTarget) {
                            issueExplicitSeek(engine, initialPlayerPositionUs, "item-start")
                        } else if (explicitSeekOwed()) {
                            // An explicit command given to an engine this one replaces — measured:
                            // an item-start seek sent to an engine built on the previous track's
                            // stream, destroyed by the reconfigure a millisecond later — would
                            // otherwise be owed forever, and the new engine would play from 0 while
                            // the player reported the target.
                            carryExplicitSeek(engine)
                        } else {
                            // Positioning belongs to the caller: a realign supplies the handoff
                            // anchor, and the flush/bootstrap paths supply theirs below. Declaring a
                            // start target here would override all of them with the item's start.
                            Log.i(
                                TAG,
                                "Native FLAC engine started for: ${File(path).name} " +
                                    "${trackBitDepth}-bit awaiting caller position"
                            )
                        }
                        engineNeedsInitialSeek = false
                        if (isPlaying) engine.resume()
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native engine failed: ${e.message}")
            }
            engine.destroy()
        }

        // Fallback: ExoPlayer pipeline via streaming thread
        // Media3 may configure a replacement sink while the Player is already paused
        // (for example, immediately after USB permission is granted). In that case it
        // does not necessarily call pause() again on the new sink, so the USB worker
        // must inherit the current playback state before it starts consuming buffers.
        //
        // AUD-143: a thread that is already running is the pipeline's producer for this stream and
        // stays, queue included. Replacing it here threw away the tail of the ending item.
        val runningThread = usbStreamingThread
        val thread = PipelineThread.keepOrStart(runningThread) {
            UsbStreamingThread(
                usbStream = stream,
                initiallyPaused = !isPlaying,
                channelCount = currentChannelCount.takeIf { it > 0 } ?: STEREO,
            ).also { it.start() }
        }
        usbStreamingThread = thread
        DirectPathTelemetry.producer = DirectPathTelemetry.Producer.EXOPLAYER
        if (thread === runningThread) {
            Log.i(TAG, "ExoPlayer pipeline keeps its streaming thread (${thread.queuedFrames()} frames still queued)")
            return
        }
        DirectPathTelemetry.streamingThreadStarts.incrementAndGet()
        queuePrerollIfOwed(thread)
        DirectPathTelemetry.recordIfCapturing("PRODUCER") {
            "EXOPLAYER path=${currentTrackPath?.let { p -> File(p).name } ?: "none"}" +
                if (DirectPathTelemetry.debugForcedNativeEngine) " nativeEngine=allowed(plan-B)" else ""
        }
        Log.i(TAG, "Using ExoPlayer pipeline (the default producer; the engine is plan B)")
    }

    /** B12a-4: a stream was rebuilt for [newRateHz] after [previousRateHz]: what the host asked for becomes owed silence. */
    private fun pendingPreroll(previousRateHz: Int, newRateHz: Int) {
        pendingPrerollMs = RateSwitchPreroll.msFor(
            requestedMs = config.signalSource?.rateSwitchPrerollMs() ?: 0,
            previousRateHz = previousRateHz,
            newRateHz = newRateHz,
        )
        if (pendingPrerollMs > 0) {
            Log.i(TAG, "Rate change ${previousRateHz.takeIf { it > 0 } ?: "none"} → $newRateHz Hz: " +
                "a $pendingPrerollMs ms pre-roll is owed to the first buffer")
        }
    }

    /**
     * B12a-4: queues the owed silence on the pipeline's new thread, ahead of the first real buffer (which is
     * enqueued by a later `handleBuffer`, so it follows the silence in the same queue). The item's frames are
     * never replaced: the silence is extra, and the position clock counts it as queue lead, so 0:00 is still
     * the item's first frame. Only a thread created for the rebuilt stream gets it.
     */
    private fun queuePrerollIfOwed(thread: UsbStreamingThread) {
        val ms = pendingPrerollMs
        if (ms <= 0) return
        pendingPrerollMs = 0
        val frames = RateSwitchPreroll.enqueue(
            thread, ms, currentSampleRate, currentChannelCount.takeIf { it > 0 } ?: STEREO,
        )
        DirectPathTelemetry.rateSwitchPrerolls.incrementAndGet()
        DirectPathTelemetry.lastRateSwitchPrerollMs = ms
        val item = currentTrackPath?.let { File(it).name } ?: "the first buffer"
        Log.i(TAG, "Rate-switch preroll: $ms ms of silence before $item ($frames frames at $currentSampleRate Hz)")
        DirectPathTelemetry.recordIfCapturing("PREROLL") { "rate=$currentSampleRate ms=$ms frames=$frames item=$item" }
    }

    // ── USB stream release ──────────────────────────────────────────

    /**
     * AUD-143: a new rate (or depth, or channel layout) replaces the USB stream, and releasing the
     * old one stops the pipeline's thread, which discards what it still holds. At a natural track
     * change that is the last ~1.4-3 s of the ending item (measured: 7 192 424 of 8 841 600 bytes
     * arrived across a 96 → 44.1 kHz change). The tail is played out first, through the end-of-stream
     * path the end of the queue already uses. A seek or a skip has flushed the thread before it gets
     * here, so there is nothing queued and no wait.
     */
    private fun playOutPipelineTailBeforeRebuild() {
        val thread = usbStreamingThread ?: return
        // A manual skip to another stream shape (B9-3): the flush before this left a fade-out for the thread to
        // write, and the stream must not be released under it — the ring plays out and ends in the fade, which
        // `nativeDrainUrbs` then lets finish (the faded cut AUD-123 asks for) instead of cutting the old track.
        if (thread.fadeOutPending()) {
            val fadeStartedMs = SystemClock.elapsedRealtime()
            val done = thread.awaitFadeOut(PipelineThread.FADE_WAIT_MS)
            Log.i(TAG, "Stream rebuild: fade-out of the old item written=$done after ${SystemClock.elapsedRealtime() - fadeStartedMs} ms")
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        val outcome = PipelineThread.playOutTail(thread, isPlaying, currentSampleRate)
        Log.i(
            TAG,
            "Stream rebuild: pipeline tail $outcome after ${SystemClock.elapsedRealtime() - startedAtMs} ms " +
                "(${thread.queuedFrames()} frames left)",
        )
    }

    /**
     * What the streaming thread still has to write, in µs (AUD-143): the distance, on the stream, from
     * what has been written to where the next buffer offered will land.
     */
    private fun pipelineQueueLeadUs(): Long {
        val thread = usbStreamingThread ?: return 0L
        val rate = currentSampleRate
        return if (rate > 0) thread.queuedFrames() * 1_000_000L / rate else 0L
    }

    private fun releaseUsbStream() {
        cancelHeldEnginePause("stream release")
        cancelHeldThreadPause("stream release")
        pipelineCrossfade = null
        resetRetiredStage()
        val stream = usbAudioStream ?: run {
            clearForcedRouting()
            unmuteDelegateIfNeeded()
            return
        }
        usbAudioStream = null
        updatePresence()

        DirectPathTelemetry.usbReleases.incrementAndGet()
        DirectPathTelemetry.usbClaimed = false
        DirectPathTelemetry.recordIfCapturing("USB_RELEASE") {
            "rate=$currentSampleRate ch=$currentChannelCount"
        }

        // Stop USB stream FIRST — sets ctx->running=false, which unblocks
        // submitPcmToUrbs inside the native engine's decode thread.
        // Without this, nativeEngine.stop() deadlocks on pthread_join.
        stream.stop()

        // Now safe to stop native engine (decode thread can exit)
        nativeEngine?.let {
            it.stop()
            it.destroy()
            DirectPathTelemetry.nativeEngineStops.incrementAndGet()
        }
        nativeEngine = null
        isNativeEngineActive = false

        // Stop the streaming thread (drains queue, joins thread)
        usbStreamingThread?.stop()
        usbStreamingThread = null

        // Drain ALL in-flight URBs — MUST complete before setAlt(0)
        val drained = stream.drainUrbs()
        Log.i(TAG, "USB stream drained $drained URBs")

        // Release native context
        stream.release()

        // No stream means no negotiated depth to describe, and `configure` re-reads it from the
        // alternate setting on the next claim.
        dacBitDepth = null
        engagedStage = null
        configuredStage = null
        configuredStageRate = 0
        configuredStageChannels = 0
        signalMode = DirectSignalMode.PRISTINE
        signalTruthDirty = true
        // The audio path is no longer running anything, so the next reconcile must not read a stale
        // "already applied" and skip a transition that is genuinely owed.
        signalModeReconciler.invalidate()

        // Keep device connection open between tracks (standard practice)
        clearForcedRouting()
        unmuteDelegateIfNeeded()
        Log.i(TAG, "USB audio stream released (device kept open)")
        val device = usbAudioDevice.findUsbAudioDevice()
        publishDiagnostics(
            UsbAudioSinkDiagnostics(
                state = when {
                    device == null -> UsbDirectSinkState.ANDROID_AUDIO
                    usbAudioDevice.hasPermission(device) -> UsbDirectSinkState.USB_DIRECT_AVAILABLE
                    else -> UsbDirectSinkState.USB_PERMISSION_REQUIRED
                },
                deviceName = device?.productName ?: device?.deviceName,
                permissionGranted = device?.let(usbAudioDevice::hasPermission),
                sourceSampleRate = currentSampleRate.takeIf { it > 0 },
                sourceChannelCount = currentChannelCount.takeIf { it > 0 },
                sourcePcmEncoding = currentEncoding,
                sourceBitDepth = trackBitDepth.takeIf { it > 0 },
            )
        )
    }

    // ── Delegate volume management ──────────────────────────────────

    private fun muteDelegateIfNeeded() {
        if (!delegateMuted) { super.setVolume(0f); delegateMuted = true }
    }

    /**
     * Leaves the delegate audible and holding [pendingVolume].
     *
     * AUD-142: ending a mute is not the only moment the delegate may be told a volume. Whenever the
     * delegate is the audible path it must receive **every** write — ReplayGain per track, the
     * play/pause ramps of `FadingPlayer`, the user's digital gain and the profile compensation of
     * AUD-025 all travel through `player.volume` — because nothing else forwards them to its
     * AudioTrack. Forwarding only on the un-mute discarded every write in the steady state.
     */
    private fun unmuteDelegateIfNeeded() {
        // AUD-004 / AUD-142: a failed Direct claim keeps the delegate silent until the host re-routes.
        // Un-muting here ended that for any caller that is not the hold's own exit (`configure` clears
        // the hold first): a ReplayGain volume at a track change, a FadingPlayer ramp, a buffer handed
        // to the delegate — audio out of whatever output Android has. The volume is already kept in
        // `pendingVolume` and is applied when the hold ends.
        if (directFailureHold) return
        delegateMuted = false
        super.setVolume(pendingVolume)
    }

    // ── Audio routing helpers ───────────────────────────────────────

    private fun forceMediaToSpeaker() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                delegate.setPreferredDevice(speaker)
                Log.i(TAG, "Delegate routed to speaker")
            }
        } catch (e: Exception) {
            Log.w(TAG, "forceMediaToSpeaker failed: ${e.message}")
        }
    }

    private fun clearForcedRouting() {
        try { delegate.setPreferredDevice(null) } catch (_: Exception) {}
    }

    // ── Player integration (attachToPlayer) ──────────────────────

    @Volatile private var attachedPlayer: Player? = null
    private var integrationListener: Player.Listener? = null

    /**
     * Attach this sink to an ExoPlayer instance. Registers an internal
     * [Player.Listener] that handles:
     * - Extracting the file path from each [MediaItem]'s URI
     * - Cleaning up finished native engines on track transitions
     * - Creating new native engines for local FLAC files
     * - Advancing to the next track when the native engine reaches EOF
     *
     * Must be called on the main thread, after [ExoPlayer.Builder.build].
     */
    fun attachToPlayer(player: Player) {
        val oldListener = integrationListener
        val oldPlayer = attachedPlayer
        if (oldListener != null && oldPlayer != null) {
            oldPlayer.removeListener(oldListener)
        }

        val listener = PlayerIntegrationListener()
        player.addListener(listener)
        userWantsPlayback = player.playWhenReady
        installPlayerMirror(player)
        attachedPlayer = player
        integrationListener = listener
        Log.i(TAG, "attachToPlayer: integration listener registered")
    }

    /** Detach from the current player. Call before player.release(). */
    fun detachFromPlayer() {
        val listener = integrationListener
        val player = attachedPlayer
        if (listener != null && player != null) {
            player.removeListener(listener)
        }
        stopPlayerMirror()
        attachedPlayer = null
        integrationListener = null
    }

    /**
     * Start position for the item now beginning, in microseconds.
     *
     * Set from an explicit command, never sampled from the player. It used to be read as
     * `attachedPlayer.currentPosition` during the transition, which is a read of the *previous*
     * item's position: measured on the HONOR, starting the Aerosmith track through the library
     * produced `initialPlayerPositionUs = 356904 ms` — the old item's EOF — so the freshly created
     * engine was seeked straight to the end of the file and playback finished in 20 ms. The same
     * stale value also latched `windowOffsetUs = 1e12 - 356904000`, which is how a bad start target
     * contaminates the renderer↔media mapping.
     *
     * Default is 0, because an explicit item start means START-FRESH. A resume is a different
     * intent and must be pushed as one ([requestStartTargetUs]) rather than inferred.
     */
    /**
     * The anchor of the current mapping epoch, in media-position microseconds.
     *
     * Derived from the tracker's single piece of state — there is deliberately no independent field
     * that could drift away from the offset computed from it.
     */
    private val initialPlayerPositionUs: Long
        get() = mappingTracker.anchorMediaPositionUs

    /**
     * Diagnostic provenance for [initialPlayerPositionUs]: the mapping generation current when it
     * was written, and the sequence number of the write itself. Never read by playback logic.
     *
     * These exist because this operand outlives every latch arm, so "which epoch does this number
     * belong to" cannot be recovered from the value alone — and a difference of epochs is exactly
     * what a cross-epoch latch is.
     */
    @Volatile
    private var initialPlayerPositionGeneration: Long = -1L
    @Volatile
    private var initialPlayerPositionSetSeq: Long = 0L

    /**
     * DIAGNOSTIC ONLY: a rebase has been armed and the next latch consumes it.
     *
     * Armed at a position-reset boundary, never at a declaration — a declared target that no seek
     * ever acted on must not be able to rewrite a later latch's operand.
     */
    private var diagnosticRebasePending: Boolean = false

    /**
     * The media position the **next** item start will begin at, in microseconds.
     *
     * Declared by whoever decides how the player starts, and consumed by exactly one item
     * transition — after that the default (0, START-FRESH) applies again, so an auto-advanced track
     * cannot inherit the previous item's resume position.
     */
    @Volatile
    private var pendingStartTargetUs: Long = 0L

    /**
     * Declares the media position at which the next item start will begin, in microseconds.
     *
     * There is one authority for an item's start position and this is how the native side is told
     * it. Media3 is given the same number by the same caller — `setMediaItems(items, index, pos)`
     * or `seekTo(index, 0)` — so the renderer mapping and the native producer are initialised from
     * one intent instead of two.
     *
     * Leaving it undeclared means START-FRESH at 0, which is what an explicit "play this track"
     * means. A **resume** — session restore, queue replacement, a player rebuild preserving state —
     * must declare its position, because otherwise Media3 is resumed at the saved position while
     * the native engine is started at 0, and the first mapping latch bakes the difference into
     * `windowOffsetUs`. Measured on the HONOR: Media3 resumed at 149s, the engine started at 0,
     * `initialPlayerPositionUs` was 0, the first `presentationTimeUs` was `1e12 + 149s`, and every
     * subsequent player position read 149s ahead of the native engine for the life of the item.
     */
    fun requestStartTargetUs(targetUs: Long) {
        val sanitised = targetUs.coerceAtLeast(0L)
        pendingStartTargetUs = sanitised
        // The declaration is the intent; only the item transition consumes it. Recording both is
        // what makes "declared but never consumed" distinguishable from "consumed".
        DirectPathTelemetry.diagnosticRebaseTargetUs = sanitised
        recordMapping(
            "START_TARGET_DECLARED",
            "targetUs=$sanitised pendingUs=$pendingStartTargetUs " +
                "gen=${mappingGeneration.get()} " + mappingPlayerState(),
        )
        Log.i(TAG, "Start target declared: ${sanitised / 1000}ms")
    }

    private inner class PlayerIntegrationListener : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Main thread, synchronously inside the user's `player.pause()`: before the playback thread
            // stops the renderers, so `pause()` there already sees it (AUD-144).
            userWantsPlayback = playWhenReady
            if (!playWhenReady) playbackHandler?.post {
                applyHeldEnginePauseNow()
                applyHeldThreadPauseNow()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return
            // AUD-144: only Media3's own advance is a join; a seek or a new playlist is a user's cut.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            ) {
                naturalJoinAtMs = -1L
                Log.i(TAG, "Join mark CLEARED by the transition (reason=$reason): a user's cut, not a join")
            } else if (NaturalEndCoordinator.isSequential(reason)) {
                // B10-4: what the sink knew when Media3 advanced by itself (an engine stopped but not yet resolved
                // as a natural end is the case AUD-144's native `rate` run hit)
                logJoinMark("onMediaItemTransition(reason=$reason)", "seen, not yet marked")
                // B9-1: the pipeline's own natural join. An engine marks its join where it hands over; the
                // pipeline has no engine, so the marker is taken here, on the playback thread, in order with
                // the `pause()` Media3 may send right after it.
                playbackHandler?.post { markPipelineJoin() }
            }

            // The start target was declared by whoever started the item; it is deliberately NOT
            // re-read from the player here, because at this instant the player can still be
            // reporting the outgoing item's position.
            //
            // Consumed, not merely read: the declaration describes one start, so an auto-advanced
            // or next-queued item falls back to START-FRESH instead of inheriting this one's
            // resume position.
            val previousInitialPosUs = initialPlayerPositionUs
            // NEW_POSITION_EPOCH: a new item begins at a declared position, so it gets its own epoch
            // and its own anchor. The previous item's anchor can never be carried into it.
            val declaredStartUs = pendingStartTargetUs
            openEpoch(
                anchorUs = declaredStartUs,
                source = if (declaredStartUs == 0L) AnchorSource.ITEM_TRANSITION_START
                else AnchorSource.DECLARED_ITEM_START,
                origin = "item-transition reason=$reason",
            )
            pendingStartTargetUs = 0L
            // Its own epoch boundary: this is the only site that writes the operand, so a generation
            // taken here is exactly "the epoch this operand belongs to".
            val itemGen = advanceMappingGeneration("ITEM_TRANSITION", "reason=$reason")
            initialPlayerPositionGeneration = itemGen
            val setSeq = recordMapping(
                "INITIAL_POS_SET",
                "oldUs=$previousInitialPosUs newUs=$initialPlayerPositionUs gen=$itemGen " +
                    "reason=$reason " + mappingPlayerState(),
            )
            initialPlayerPositionSetSeq = setSeq
            Log.i(
                TAG,
                "onMediaItemTransition: initialPlayerPos=${initialPlayerPositionUs / 1000}ms " +
                    "reason=$reason"
            )

            val uri = mediaItem.localConfiguration?.uri

            // A new occurrence of an item owns no seek yet. Commands given before this point were
            // for the previous occurrence, even when it is the same file: returning to a track after
            // seeking in it resumed at the old target, because the owed generation compared against a
            // fresh engine and the path matched.
            explicitSeekGeneration.set(0L)
            explicitSeekTargetUs.set(-1L)
            explicitSeekItemPath = null
            explicitSeekFailed = false
            seekAwaitingEngineUs = -1L

            // 1. Resolve the new item's path FIRST (AUD-133): the playback thread may handle a buffer
            // as soon as the cleanup below runs, and must never see the ended item's path then —
            // measured: "Native FLAC engine started for: <the track that had just ended>".
            // Counted before the creation is queued: it reads the new period's offset (AUD-136), and
            // tells an engine and a deferred config which item they belong to (AUD-137).
            transitionSeq++
            offsetLedger.onItemTransition(reason)
            val resolvedPath = resolveTrackPath(uri)
            currentTrackPath = resolvedPath
            if (resolvedPath != null) enginePendingForItem = true
            Log.i(TAG, "onMediaItemTransition: uri=$uri path=$resolvedPath")

            // 2. Clean up the previous track's finished engine — on the playback thread (AUD-015).
            // Destroying it here, on the main thread, raced a configure in progress, and the deferred
            // config it applies re-opens the USB stream. Queued ahead of the creation below. With no
            // handler yet no configure has run, so no engine exists to clean up.
            val handler = playbackHandler
            handler?.post { cleanUpFinishedEngineForItem() }

            // 3. Create engine if local FLAC — on the playback thread, not here (AUD-115/AUD-015).
            // This listener runs on the main thread; creating the engine here raced a configure in
            // progress on the playback thread and built an engine for this item against the stream
            // the previous item's configure had just opened ("Rate mismatch"). The item is marked
            // pending and the creation is queued behind that configure.
            if (resolvedPath != null) {
                if (handler != null) {
                    handler.post { createPendingEngine(reason) }
                } else {
                    // No configure has run yet: the first configure creates the engine itself.
                    Log.i(TAG, "onMediaItemTransition: engine pending until the first configure")
                }
            }
        }
    }

    /**
     * Resolve a [MediaItem]'s URI to a local file path for the native engine.
     *
     * - `file:///path/to/song.flac` → `/path/to/song.flac`
     * - `/storage/.../song.flac` (bare path) → as-is
     * - `content://media/external/audio/123` → resolved via ContentResolver
     * - `http://` or `https://` → null (ExoPlayer pipeline handles these)
     */
    private fun resolveTrackPath(uri: Uri?): String? {
        if (uri == null) return null
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> resolveContentUri(uri)
            "http", "https" -> {
                Log.i(TAG, "resolveTrackPath: HTTP URI → ExoPlayer pipeline (no native engine)")
                null
            }
            null -> {
                // Bare path string (no scheme) — common in local music players
                val pathStr = uri.toString()
                if (pathStr.startsWith("/")) pathStr else null
            }
            else -> null
        }
    }

    private fun resolveContentUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveContentUri failed: ${e.message}")
            null
        }
    }

    companion object {
        /** The transport is stereo: every supported DAC is two-channel. */
        private const val STEREO = 2
        /** A bootstrap target this close to where the engine already is is the same position. */
        private const val BOOTSTRAP_SAME_POSITION_US = 100_000L
        private const val TAG = "UsbAudioSink"

        /** ~250 ms sampling: 160 entries cover ~40 s, well past the stuck detector's 10 s window. */
        private const val POSITION_TRACE_INTERVAL_MS = 250L
        private const val POSITION_TRACE_CAPACITY = 160

        /** Player mirror cadence; well under the trace cadence so age stays meaningful. */
        private const val PLAYER_MIRROR_INTERVAL_MS = 250L
        private const val COHERENCE_CHECK_INTERVAL_MS = 250L
        private const val PRELOAD_WINDOW_US = 15_000_000L
        /** Seeks and offset changes are a handful per track: ~3000 lines cover days of listening. */
        private const val POSITION_AUDIT_MAX_LINES = 3000
        private const val ENGINE_ITEM_MISMATCH_GRACE_MS = 1_000L
        private const val POSITION_DRIFT_TAG = "POSITION_DRIFT"
        /** How long a natural end waits for Media3's offset of the next period before the fallback. */
        private const val NATURAL_OFFSET_GRACE_MS = 1_000L

        /** Ledger depth: hundreds of operations from startup through first stable playback. */
        private const val ACK_LEDGER_CAPACITY = 512

        /** ~2 s of a frozen player position while playing triggers an immediate report. */
        private const val FREEZE_PUBLISH_TICKS = 8

        /** Mapping events are rare transitions; the bound is only a guard against a pathological run. */
        private const val MAPPING_LOG_CAPACITY = 256
        private const val MAPPING_REPORT_MAX_EVENTS = 96

        /**
         * Above this, a position is in Media3's renderer domain rather than media time.
         *
         * Media3 seeds its renderer clock at `1_000_000_000_000` us and carries media time as an
         * offset from there, so a media position can never legitimately reach this floor — the
         * longest media item would have to run ~11.5 days. Used only to *label* a value in the
         * transition event; nothing here acts on it.
         */
        private const val RENDERER_DOMAIN_FLOOR_US = 1_000_000_000_000L

        /**
         * Wraps a [LoadControl] to suppress ExoPlayer loading when the native
         * FLAC engine is decoding directly to USB. Call BEFORE [ExoPlayer.Builder.build].
         *
         * @param delegate       Your app's LoadControl (e.g., DefaultLoadControl).
         * @param isEngineActive Lambda returning true when native engine is active.
         *                       Typical: `{ usbSink?.isNativeEngineActive == true }`
         */
        @JvmStatic
        @OptIn(UnstableApi::class)
        fun wrapLoadControl(
            delegate: LoadControl,
            isEngineReady: () -> Boolean = { false },
            isEnginePreloadWindow: () -> Boolean = { false },
            isEngineActive: () -> Boolean,
        ): LoadControl = NativeEngineAwareLoadControl(delegate, isEngineActive, isEngineReady, isEnginePreloadWindow)
    }
}
