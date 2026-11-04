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
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.decent.usbaudio.OpenLevelCheck
import com.decent.usbaudio.StreamingInterfaceSequence
import com.decent.usbaudio.UacStreamingDescriptors
import com.decent.usbaudio.UsbPcmConversion
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioStream
import com.decent.usbaudio.UsbTransportPlanner
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

    /** Source PCM bit depth, obtained from the Media3 input format. */
    private var trackBitDepth: Int = 0

    /** Current item's path, used only to label diagnostics. */
    @Volatile
    private var currentTrackPath: String? = null

    private var usbAudioStream: UsbAudioStream? = null
    private val usbAudioDevice = UsbAudioDevice.getInstance(context)
    private var usbStreamingThread: UsbStreamingThread? = null

    init {
        // AUD-145: the native layer's wait counters, read when a capture snapshot is taken. A lambda, so
        // nothing loads the native library here (the unit tests construct a sink without it).
        DirectPathTelemetry.usbWaitStatsProvider = { UsbAudioStream.nativeGetWaitStats() }
        // B10: what the output makes of the DAC's feedback (stuck episodes, the policy in force).
        DirectPathTelemetry.usbFeedbackStatsProvider = { UsbAudioStream.nativeGetFeedbackStats() }
        // B46-A4: the transport plan of the stream in use (ring, interval, queue limits, assumptions).
        DirectPathTelemetry.usbTransportPlanProvider = { usbAudioStream?.transportPlan()?.toArray() ?: LongArray(0) }
    }
    /** AUD-136: Media3's renderer offsets, reported through [setOutputStreamOffsetUs]. */
    private val offsetLedger = RendererOffsetLedger()

    /**
     * AUD-144: the user's intent (`Player.playWhenReady`), from the player's own listener. Unlike the
     * sampled mirror (250 ms) it is event-driven and set on the main thread *before* the playback
     * thread learns of a user's pause, so `pause()` can tell that pause from a rebuffer's stop.
     */
    @Volatile
    private var userWantsPlayback = true

    @Volatile
    private var naturalJoinAtMs = -1L

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

    @Volatile
    private var downmixSourceChannels = 0

    @Volatile
    private var upmixMonoSource = false

    // ── Signal-mode transitions (pushed, not discovered) ────────────────

    /**
     * Serializes transitions and collapses rapid requests; see [DirectSignalModeReconciler].
     *
     * Guarded by [playbackHandler]'s thread, never touched from the caller's.
     */
    private val signalModeReconciler = DirectSignalModeReconciler()

    @Volatile
    private var playbackHandler: Handler? = null

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

    /** Records the explicit seek target for the renderer-to-media mapping. Media3 performs the seek. */
    fun requestMediaSeek(targetUs: Long) {
        if (!config.directEnabled) return
        val seekUs = targetUs.coerceAtLeast(0L)
        openEpoch(seekUs, AnchorSource.EXPLICIT_SEEK_TARGET, "user-seek")
        DirectPathTelemetry.diagnosticRebaseTargetUs = seekUs
        naturalJoinAtMs = -1L
    }

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

    private fun auditPosition(event: String, origin: String, detail: String) {
        val engineMs = -1L // Legacy trace column; Media3 is the only producer.
        positionAudit.record(event, origin, playerMirror?.positionMs ?: -1L, engineMs,
            "$detail offsetUs=$windowOffsetUs item=${currentTrackPath?.let { File(it).name } ?: "-"}")
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

    fun provocationEngineAbsent(): Boolean = true

    fun provocationReady(): Boolean {
        val m = playerMirror ?: return false
        return m.playbackState == 4 /* Player.STATE_ENDED */
    }

    /**
     * Diagnostic only: the compact state the readiness probe is evaluating.
     *
     * Exists because a readiness probe that only ever answers true/false cannot explain a miss — the
     * previous version wrapped its call in `runCatching` and returned false on any failure, which is
     * indistinguishable from "the window never opened". This reports the inputs instead.
     */
    fun provocationStateBrief(livePlaybackState: Int): String {
        val m = playerMirror
        return "liveState=$livePlaybackState mirrorState=${m?.playbackState ?: -1}" +
            " enginePresent=false engineRunning=false" +
            " offsetUnlatched=${windowOffsetUs < 0} offsetUs=$windowOffsetUs" +
            " needsInit=$usbStartMediaTimeNeedsInit" +
            " operandUs=$initialPlayerPositionUs operandGen=$initialPlayerPositionGeneration" +
            " operandSetSeq=$initialPlayerPositionSetSeq" +
            " lastInitialPosSetSeq=${lastMappingSeqOf("INITIAL_POS_SET")}" +
            " signalMode=${DirectPathTelemetry.signalMode}" +
            " modeTransitions=${DirectPathTelemetry.modeTransitions.get()}"
    }

    fun provokeMappingUnlatch(livePlaybackState: Int): String {
        // before and after instead, and the provocation's validity check requires it unchanged.
        val stateBefore = livePlaybackState
        val offsetBefore = windowOffsetUs
        val needsInitBefore = usbStartMediaTimeNeedsInit
        val operandBefore = initialPlayerPositionUs
        val setSeqBefore = initialPlayerPositionSetSeq
        val itemSeqBefore = lastMappingSeqOf("INITIAL_POS_SET")

        if (stateBefore != 4 /* Player.STATE_ENDED */) {
            return "PROVOCATION_INVALID reason=not-ended" +
                " playbackStateBefore=$stateBefore enginePresentBefore=false" +
                " engineRunningBefore=false" +
                " offsetBeforeUs=$offsetBefore operandBeforeUs=$operandBefore"
        }

        // The diagnostic provocation used to write the unlatched sentinel here. It cannot any more,
        // and deliberately so: the mapping is owned by the epoch tracker, which has no generic
        // "clear the offset but leave the anchor" operation — that operation was the defect. The
        // probe is retained as a read-only precondition check.
        val operandAfter = initialPlayerPositionUs
        val setSeqAfter = initialPlayerPositionSetSeq
        val itemSeqAfter = lastMappingSeqOf("INITIAL_POS_SET")
        // operand untouched, item-transition history untouched, Player state untouched (the caller
        // re-reads it; `stateAfter` here is the sink's view of the same instant).
        val valid = windowOffsetUs < 0 && usbStartMediaTimeNeedsInit &&
            stateBefore == 4

        return "PROVOCATION_APPLIED valid=$valid" +
            " playbackStateBefore=$stateBefore" +
            " enginePresentBefore=false enginePresentAfter=false" +
            " engineRunningBefore=false engineRunningAfter=false" +
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
        return "windowOffsetUs=$windowOffsetUs needsInit=$usbStartMediaTimeNeedsInit " +
            "offsetUnlatched=${windowOffsetUs < 0} " +
            "initialPlayerPositionUs=$initialPlayerPositionUs " +
            "operandGen=$initialPlayerPositionGeneration " +
            "operandSetSeq=$initialPlayerPositionSetSeq pendingStartTargetUs=$pendingStartTargetUs " +
            "player=${m?.positionMs ?: -1L} playbackState=${m?.playbackState ?: -1} " +
            "playWhenReady=${m?.playWhenReady ?: "UNKNOWN"} isPlaying=${m?.isPlaying ?: false} " +
            "appInfo=${m?.appPlaybackInfoPositionUs ?: -1L} mirrorAgeMs=" +
            (if (m == null) -1L else SystemClock.elapsedRealtime() - m.atMs) + " " +
            "engineExists=false engineRunning=false " +
            "engineFinished=false " +
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
            .append(" engineRunning=").append(false)
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

    private fun captureAnchorDiagnostics() {
        DirectPathTelemetry.anchorWindowOffsetUs = windowOffsetUs
        DirectPathTelemetry.anchorDerivedUs = handoffPositionUs()
        DirectPathTelemetry.anchorProducer = DirectPathTelemetry.producer
    }

    private fun flushOriginDetail(): String =
        "flushOrigin=${DirectPathTelemetry.flushOrigin}"

    /** Cold start creates an epoch; configuring an existing period preserves it. */
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
     * @return true when the signal mode changed.
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
        val keepsQueue = previous != null && previous !== requestedStage && usbStreamingThread != null
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

    // Backpressure (returning false from handleBuffer paces ExoPlayer) is the streaming thread's `atSoftLimit()`: the transport plan's
    // soft limit, a duration (B46-B2). Pause responsiveness is handled by pauseStreaming(), not queue size.

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

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val enc = inputFormat.pcmEncoding
        trackBitDepth = when (enc) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            else -> 0
        }
        if (enc != Format.NO_VALUE) currentEncoding = enc
        // Cold start only: make sure an epoch exists before the first timestamp can arrive. A no-op
        // once any epoch is open, so a reconfigure never re-anchors a live mapping.
        ensureMappingEpoch()

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

    /** Rebase the pipeline period without replacing the writer or its queued tail. Playback thread only. */
    private fun armPipelinePeriod() {
        usbStartMediaTimeNeedsInit = true
        pipelineClock.disarm()
    }

    /**
     * Media3's renderer offset for the stream whose output starts now (AUD-136): the authority for
     * every window offset, kept in [offsetLedger]. Playback thread.
     */
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
        offsetLedger.onStreamOffsetReported(outputStreamOffsetUs)

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
            // B48-1: a DAC that is gone is the owner's to know at once (a recovery in the middle of its sequence stops; nothing is claimed after this).
            if (deviceGone) usbAudioDevice.owner.deviceDetached()
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

            if (usbStreamingThread == null) startPipeline(stream)
            if (signalModeReconciler.onRequest()) reconcileSignalMode()

            // Capture media timeline offset from first buffer (needed for position tracking)
            if (usbStartMediaTimeNeedsInit) {
                pipelineClock.accept(presentationTimeUs, usbAudioStream?.framesWritten ?: 0L, pipelineQueueLeadUs())
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
                    playingOffsetUs != null && offsetLedger.latestOffsetUs() == playingOffsetUs
                if (!mappingTracker.isLatched) {
                    // AUD-136: Media3's own offset for this buffer's stream says where the buffer is.
                    // An anchor it contradicts (a stale host position, a seek past the end) is wrong.
                    val media3AnchorUs = if (!fromPlayingStream) null else OffsetAuthority.media3Anchor(
                        presentationTimeUs = presentationTimeUs,
                        media3OffsetUs = offsetLedger.latestOffsetUs(),
                        epochAnchorUs = mappingTracker.anchorMediaPositionUs,
                        durationUs = 0L,
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
                            "seekTargetUs=-1 " +
                            "usbStartMediaTimeUs=$usbStartMediaTimeUs " +
                            "enginePosUs=-1 " +
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

            }

            val thread = usbStreamingThread ?: return true

            // Backpressure: if queue is nearly full, tell ExoPlayer to retry later.
            // This paces the renderer to the USB DAC's consumption rate without
            // depending on the delegate AudioTrack.
            if (thread.atSoftLimit()) {
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

    private fun realignPipelineForSignalMode() {
        if (usbAudioStream == null) return
        captureAnchorDiagnostics()
        realignWithinPipeline(handoffPositionUs(), System.nanoTime())
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
                "→$signalMode before=${beforeUs / 1000}ms after=${afterUs / 1000}ms " +
                "delta=${(afterUs - beforeUs) / 1000}ms duration=${elapsedMs}ms $detail " +
                "anchor=${anchorOrigin.wire} " +
                "anchorUs=$beforeUs anchorRenderDomain=${beforeUs >= RENDERER_DOMAIN_FLOOR_US} " +
                "enginePosUs=-1 " +
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
        PIPELINE_FRAMES("PIPELINE_FRAMES"),
        PIPELINE_RENDERER_DOMAIN("PIPELINE_FRAMES_RENDERER_DOMAIN"),
        UNKNOWN("UNKNOWN"),
    }

    private var lastAnchorOrigin = AnchorOrigin.UNKNOWN

    private fun handoffPositionUs(): Long {
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

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        // Single exit for the Direct path; see the write sites below.
        var directResult: Long? = null
        if (config.directEnabled) {
            val streamAlive = usbAudioStream?.isAlive == true
            if (streamAlive) {
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
            val thread = usbStreamingThread
            if (thread != null && handledEndOfStream) {
                return thread.isEndOfStreamDrained()
            }
        }
        return super.isEnded()
    }

    override fun hasPendingData(): Boolean {
        if (config.directEnabled) {
            if (usbStreamingThread?.hasPendingData() == true) return true
        }
        return super.hasPendingData()
    }

    override fun playToEndOfStream() {
        if (!handledEndOfStream) {
            // The queue marker preserves ordering: all source buffers are
            // written before the packetizer submits its final frame-aligned
            // residual.
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
        cancelHeldThreadPause("play()")
        usbStreamingThread?.resumeStreaming()
        updatePresence()
    }

    override fun pause() {
        isPlaying = false
        pauseStreamingThread()
        super.pause()
        updatePresence()
    }

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

    private fun markPipelineJoin() {
        if (usbStreamingThread != null) {
            naturalJoinAtMs = SystemClock.elapsedRealtime()
            logJoinMark("markPipelineJoin", "SET")
        } else {
            logJoinMark("markPipelineJoin", "not set (not the pipeline's join)")
        }
    }

    private fun logJoinMark(site: String, what: String) {
        Log.i(TAG, "Join mark @$site: $what naturalJoinAtMs=$naturalJoinAtMs thread=${usbStreamingThread != null}")
    }

    override fun setVolume(volume: Float) {
        pendingVolume = volume
        if (config.directEnabled && usbAudioStream?.isAlive == true) {
            muteDelegateIfNeeded()
        } else {
            unmuteDelegateIfNeeded()
        }
    }

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
        // Captured before the reset: what the mapping looked like on the way in.
        val offsetBeforeFlushUs = windowOffsetUs
        val operandBeforeFlushUs = initialPlayerPositionUs
        super.flush()
        val thread = usbStreamingThread
        if (thread != null) {
            // B9-2: the thread ends the ring in a fade of what it held, feeds silence and fades the new
            // position in; nothing is cancelled. See UsbStreamingThread.flush.
            thread.flush()
            // B9-3: the queue a crossfade and a retired stage were for is gone.
            pipelineCrossfade = null
            resetRetiredStage()
        } else {
            usbAudioStream?.flush()
        }
        usbStartMediaTimeNeedsInit = true
        pipelineClock.disarm()
        // `windowOffsetUs` is deliberately NOT cleared here; see the note in the realignment path.
        // A flush is a seek inside the current period, so the renderer↔media mapping is unchanged
        // and clearing it would only force a mismatched re-latch.
        DirectPathTelemetry.flushCount.incrementAndGet()
        handledEndOfStream = false
        // The same period keeps its renderer mapping across a reset.
        mappingTracker.preserve()
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

    /**
     * The device calls of a stream's start-up and of a locked-up DAC's recovery: one sequence (B47-A5), moved out of the sink in B48-1 so that the two run against one
     * device under its one owner ([UsbAudioDevice.owner]) and can be exercised without a phone.
     */
    private val streamingSequence = StreamingInterfaceSequence(usbAudioDevice, log = { Log.i(TAG, it) })

    private fun configureUsbBitPerfect(sampleRate: Int, channelCount: Int, encoding: Int): Boolean {

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

        // B48-1: the tail of the ending item plays out on the writer thread, which a recovery may be using: this waits for it, outside the device's owner. The rebuild itself
        // (release, claim, clock, alt, level, start) is one exclusive section: a recovery of the stream being replaced finishes first or never starts, and the new stream is
        // published as the next generation.
        if (usbAudioStream != null) playOutPipelineTailBeforeRebuild()
        return usbAudioDevice.owner.exclusive("configure rate=$sampleRate ch=$channelCount") {
            rebuildUsbStream(sampleRate, channelCount, encoding)
        }
    }

    private fun rebuildUsbStream(sampleRate: Int, channelCount: Int, encoding: Int): Boolean {
        if (usbAudioStream != null) releaseUsbStream()

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
            maxPacketSize = formatAlt.maxPacketBytes,
            endpointInterval = formatAlt.bInterval,
            feedbackPacketBytes = formatAlt.feedbackMaxPacketBytes,
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
                maxPacketSize = formatAlt.maxPacketBytes,
                endpointInterval = formatAlt.bInterval,
                feedbackPacketBytes = formatAlt.feedbackMaxPacketBytes,
            )
            if (!stream.isReady) {
                Log.e(TAG, "USB stream recreation failed after reopen")
                stream.release()
                return failUsbInitialization("USB stream recreation failed after reopen")
            }
            Log.i(TAG, "Device reopened with fresh fd=${deviceInfo.fd}")
        }
        Log.i(TAG, "Step 1: setAlt(0) — old ISO ring freed")

        // Steps 2-6½: the clock, the zero-bandwidth reset, the alternate setting, the PLL wait and the level (B47-A5: one helper, also what a recovery's
        // interface restart runs, so the two cannot drift apart).
        val reconfigured = streamingSequence.reconfigure(sampleRate, altSetting, "rate=$sampleRate alt=$altSetting")
        val failure = reconfigured.failure
        if (failure != null) {
            if (failure.altZeroFirst) usbAudioDevice.setAltSetting(0)
            stream.release()
            return failUsbInitialization(failure.message)
        }
        val confirmedSampleRate = reconfigured.confirmedSampleRate

        if (!stream.start(usbAudioDevice.owner.previousStreamRateHz)) {
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
        // B48-1: this stream's generation and its rate and alt, fixed here for the recovery of this stream (and no other).
        val facts = usbAudioDevice.owner.publishStream(sampleRate, altSetting)
        Log.i(TAG, "USB stream published: gen=${facts.generation} rate=${facts.sampleRateHz} alt=${facts.altSetting}")
        DirectPathTelemetry.usbRequestedRateHz = sampleRate
        // Read back from the DAC rather than assumed: GET_CUR is what makes the rate a measurement
        // instead of a request, and a mismatch here is the incorrect-speed-audio failure.
        DirectPathTelemetry.usbConfirmedRateHz = confirmedSampleRate
        DirectPathTelemetry.recordIfCapturing("USB_CLAIM") {
            "rate=$sampleRate confirmed=$confirmedSampleRate alt=$altSetting " +
                "dacDepth=$bitDepth sourceDepth=$trackBitDepth"
        }

        // is decided from it, and getting that order wrong would start a pristine producer for a
        // stream the host has asked to process.
        refreshSignalPath()

        startPipeline(stream)
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

    @Synchronized
    private fun startPipeline(stream: UsbAudioStream) {
        val runningThread = usbStreamingThread
        val thread = PipelineThread.keepOrStart(runningThread) {
            // The thread's queue limits are the stream's plan's: durations at the stream's rate (B46-B2).
            val plan = stream.transportPlan()
            val soft = plan?.queueSoftFrames ?: UsbTransportPlanner.defaultQueueFrames(currentSampleRate).first
            val hard = plan?.queueHardFrames ?: UsbTransportPlanner.defaultQueueFrames(currentSampleRate).second
            UsbStreamingThread(
                usbStream = stream,
                initiallyPaused = !isPlaying,
                channelCount = currentChannelCount.takeIf { it > 0 } ?: STEREO,
                queueSoftFrames = soft,
                queueHardFrames = hard,
                dacRecovery = recoveryHostFor(stream),
            ).also { it.start() }
        }
        usbStreamingThread = thread
        DirectPathTelemetry.producer = DirectPathTelemetry.Producer.EXOPLAYER
        if (thread === runningThread) return
        DirectPathTelemetry.streamingThreadStarts.incrementAndGet()
        queuePrerollIfOwed(thread, stream)
        DirectPathTelemetry.recordIfCapturing("PRODUCER") { "EXOPLAYER" }
    }

    /**
     * B48-1: the recovery host of the stream that was published last, bound to its generation. Null when none was published (the thread then never recovers): a thread made for
     * any other generation must not be able to restart the interface of this one.
     */
    private fun recoveryHostFor(stream: UsbAudioStream): UsbStreamingThread.DacRecoveryHost? {
        val facts = usbAudioDevice.owner.facts
        if (facts == null || stream !== usbAudioStream) {
            Log.w(TAG, "No published stream for this writer thread: its DAC recovery is off")
            return null
        }
        return OwnedRecoveryHost(usbAudioDevice.owner, streamingSequence, facts.generation) { Log.i(TAG, it) }
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
    private fun queuePrerollIfOwed(thread: UsbStreamingThread, stream: UsbAudioStream) {
        val ms = pendingPrerollMs
        if (ms <= 0) return
        pendingPrerollMs = 0
        val plan = stream.transportPlan(prerollMs = ms) ?: return
        val frames = RateSwitchPreroll.enqueue(thread, plan, currentChannelCount.takeIf { it > 0 } ?: STEREO)
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
        cancelHeldThreadPause("stream release")
        pipelineCrossfade = null
        resetRetiredStage()
        val stream = usbAudioStream ?: run {
            clearForcedRouting()
            unmuteDelegateIfNeeded()
            return
        }
        usbAudioDevice.owner.exclusive("release") { releaseOwnedStream(stream) }
    }

    /** The release proper, inside the device's exclusive section (B48-1): no recovery of this stream touches the interfaces while the stream is stopped and freed. */
    private fun releaseOwnedStream(stream: UsbAudioStream) {
        usbAudioStream = null
        usbAudioDevice.owner.clearStream()
        updatePresence()

        DirectPathTelemetry.usbReleases.incrementAndGet()
        DirectPathTelemetry.usbClaimed = false
        DirectPathTelemetry.recordIfCapturing("USB_RELEASE") {
            "rate=$currentSampleRate ch=$currentChannelCount"
        }

        // Stop the native writer first so a blocked pipeline write can exit before the join.
        stream.stop()

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
            } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) {
                logJoinMark("onMediaItemTransition(reason=$reason)", "seen, not yet marked")
                // Serialize the mark with any renderer pause that follows the transition.
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

            offsetLedger.onItemTransition(reason)
            currentTrackPath = resolveTrackPath(uri)
            // The item opens a new renderer/media mapping even when the same USB stream continues.
            playbackHandler?.post { armPipelinePeriod() }

        }
    }

    private fun resolveTrackPath(uri: Uri?): String? {
        if (uri == null) return null
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> resolveContentUri(uri)
            "http", "https" -> {
                Log.i(TAG, "resolveTrackPath: remote URI")
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

        private const val TAG = "UsbAudioSink"

        /** ~250 ms sampling: 160 entries cover ~40 s, well past the stuck detector's 10 s window. */
        private const val POSITION_TRACE_INTERVAL_MS = 250L
        private const val POSITION_TRACE_CAPACITY = 160

        /** Player mirror cadence; well under the trace cadence so age stays meaningful. */
        private const val PLAYER_MIRROR_INTERVAL_MS = 250L
        /** Seeks and offset changes are a handful per track: ~3000 lines cover days of listening. */
        private const val POSITION_AUDIT_MAX_LINES = 3000

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

    }
}
