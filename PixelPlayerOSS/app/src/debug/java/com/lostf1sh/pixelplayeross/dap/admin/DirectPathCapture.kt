package com.lostf1sh.pixelplayeross.dap.admin

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.decent.usbaudio.media3.DirectPathTelemetry
import com.lostf1sh.pixelplayeross.data.volume.PristineLockDiagnostic
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only recorder for the Direct transport's dual-path behaviour.
 *
 * ## Why a file
 *
 * This device's OEM logging stack does not surface application logs through `adb logcat`, so a
 * physical session has to be read back from storage. Everything here is written under the app's
 * external files directory, which is readable from a host with no `run-as` and no root.
 *
 * ## Why it is not written from the audio path
 *
 * The counters live in [DirectPathTelemetry] inside the wrapper library, where the playback and USB
 * threads can reach them. Those threads only ever increment atomics and append to a bounded
 * in-memory ring — **no file I/O happens on them**. This object owns the only writer: a plain
 * background thread that samples once a second and does all formatting and writing there.
 *
 * A diagnostic that perturbs the pipeline it is measuring is worse than no diagnostic, and on this
 * device the failure mode it would cause — a dropout — is indistinguishable from the one it is
 * meant to detect.
 *
 * ## What a capture contains
 *
 * - One `SAMPLE` line per second carrying the counters **as deltas since the previous sample**, so
 *   the file shows rates rather than totals and a reader can see precisely when something changed.
 * - A `SNAP` block on demand, and one at start and stop, carrying every value in full.
 * - `EVENT` lines for discrete transitions — claims, releases, producer changes, mode changes,
 *   realignments, rebuilds — recorded by the audio path into the ring and drained here.
 *
 * This class is a recorder. It is not read by any playback decision, and setting
 * [DirectPathTelemetry.captureEnabled] changes nothing except whether the optional timing and the
 * ring are populated.
 */
internal object DirectPathCapture {

    private const val TAG = "DirectPathCapture"
    private const val FILE_NAME = "direct-path-capture.txt"
    private const val SAMPLE_PERIOD_MS = 1_000L
    private const val MAX_LINES = 4_000

    private val running = AtomicBoolean(false)
    private var writer: Thread? = null

    /** Drain cursor into the telemetry ring, so each event is written exactly once. */
    private var eventsWritten = 0

    private var previous: DirectPathTelemetry.Snapshot? = null

    /**
     * Begins a capture session.
     *
     * Clears the counters first so the session starts from a known zero: a record whose totals
     * include an earlier run cannot be used to attribute a dropout to a transition.
     */
    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "Capture already running")
            return
        }
        DirectPathTelemetry.reset()
        eventsWritten = 0
        previous = null
        DirectPathTelemetry.captureEnabled = true

        write(context, "CAPTURE|START|t=${SystemClock.elapsedRealtime()}")
        writeSnapshot(context, "START")

        writer = Thread({
            while (running.get()) {
                try {
                    Thread.sleep(SAMPLE_PERIOD_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                // A diagnostic must not be able to take the app down. An exception thrown here
                // would kill this thread silently — no samples, no final block, and a session that
                // looks like it recorded nothing for reasons the file cannot explain. The failure
                // is written into the capture instead, where it will actually be read.
                try {
                    writeSample(context)
                } catch (t: Throwable) {
                    Log.w(TAG, "Capture sample failed", t)
                    runCatching { write(context, "CAPTURE|SAMPLE_ERROR|${t.javaClass.name}: ${t.message}") }
                }
            }
        }, "DirectPathCapture").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
        Log.i(TAG, "Direct path capture started")
    }

    /** Writes an immediate full reading without disturbing the running session. */
    fun snapshot(context: Context) {
        writeSnapshot(context, "ON_DEMAND")
    }

    /** Ends the session, writes a final reading, and leaves the file in place. */
    fun stop(context: Context) {
        if (!running.compareAndSet(true, false)) {
            Log.i(TAG, "Capture not running")
            return
        }
        DirectPathTelemetry.captureEnabled = false
        writer?.interrupt()
        writer = null
        writeSnapshot(context, "FINAL")
        write(context, "CAPTURE|STOP|t=${SystemClock.elapsedRealtime()}")
        Log.i(TAG, "Direct path capture stopped")
    }

    fun isRunning(): Boolean = running.get()

    // ── Sampling ────────────────────────────────────────────────────────

    private fun writeSample(context: Context) {
        val now = DirectPathTelemetry.snapshot()
        val before = previous
        previous = now
        drainEvents(context)

        fun delta(current: Long, previous: Long?) = current - (previous ?: 0L)

        val engine = DualPlayerEngineDebugAccess.route(context)
        val usb = DualPlayerEngineDebugAccess.usbDiagnostics(context)
        val positionMs = DualPlayerEngineDebugAccess.positionMs(context)
        val rebuilds = DualPlayerEngineDebugAccess.rebuildCount(context)

        write(
            context,
            listOf(
                "SAMPLE",
                "t=${now.elapsedRealtimeMs}",
                "mode=${now.signalMode}",
                "producer=${now.producer}",
                "inFramesΔ=${delta(now.inputFrames, before?.inputFrames)}",
                "outFramesΔ=${delta(now.outputFrames, before?.outputFrames)}",
                "blocksΔ=${delta(now.processedBlocks, before?.processedBlocks)}",
                "meanUs=${now.meanProcessNanos?.div(1_000) ?: "-"}",
                "maxUs=${now.processNanosMax / 1_000}",
                "p95Us=${now.percentileMicros(0.95) ?: "-"}",
                "qDepth=${now.lastQueueDepth}",
                "maxQ=${now.maxQueueDepth}",
                "underrunΔ=${delta(now.underruns, before?.underruns)}",
                "lateΔ=${delta(now.lateBuffers, before?.lateBuffers)}",
                "dropΔ=${delta(now.droppedBuffers, before?.droppedBuffers)}",
                "framesWritten=${now.usbFramesWritten}",
                // The three position domains side by side, so the ×10 series can answer whether
                // `windowOffsetUs` stays stable across same-period toggles — which is the main
                // question of that run and had no data source until now.
                "enginePosUs=${DirectPathTelemetry.liveEnginePositionUs}",
                "windowOffsetUs=${DirectPathTelemetry.liveWindowOffsetUs}",
                "sinkReturnedUs=${DirectPathTelemetry.liveSinkReturnedUs}",
                "posMs=$positionMs",
                "rebuilds=$rebuilds",
                "claimed=${now.usbClaimed}",
                "desired=${engine?.desiredBackend}",
                "actual=${engine?.actualBackend}",
                "reason=${engine?.reason}",
                "verdict=${now.bitPerfectVerdict}",
                "why=${now.bitPerfectReason ?: "-"}",
                "srcRate=${usb?.sourceSampleRate ?: "-"}",
                "srcDepth=${usb?.sourceBitDepth ?: "-"}",
                "codec=${usb?.sourceCodec ?: "-"}",
            ).joinToString("|")
        )
    }

    /**
     * Writes the events recorded since the last drain, as one batch.
     *
     * The cursor is an absolute count ([DirectPathTelemetry.eventsRecorded]), not a size of the
     * returned list. The ring only ever holds its most recent N events, so a size-based cursor
     * stops advancing the moment the ring wraps and the capture then records no further events for
     * the rest of the session — which is when they matter most.
     */
    private fun drainEvents(context: Context) {
        val total = DirectPathTelemetry.eventsRecorded
        if (total <= eventsWritten) return
        val events = DirectPathTelemetry.events()
        // The ring keeps only the newest, so the first entry returned is the (total - size)-th.
        val firstHeld = total - events.size
        val skip = (eventsWritten - firstHeld).coerceAtLeast(0)
        val lines = events.drop(skip).map {
            "EVENT|t=${it.elapsedRealtimeMs}|${it.kind}|${it.detail}"
        }
        eventsWritten = total
        writeLines(context, lines)
    }

    private fun writeSnapshot(context: Context, label: String) {
        val s = DirectPathTelemetry.snapshot()
        val engine = DualPlayerEngineDebugAccess.route(context)
        val usb = DualPlayerEngineDebugAccess.usbDiagnostics(context)
        val lines = buildList {
            add("SNAP|$label|t=${s.elapsedRealtimeMs}")
            add("SNAP|$label|transport.desired=${engine?.desiredBackend}")
            add("SNAP|$label|transport.actual=${engine?.actualBackend}")
            add("SNAP|$label|transport.transition=${engine?.transition}")
            add("SNAP|$label|transport.reason=${engine?.reason}")
            add("SNAP|$label|transport.directActive=${engine?.isDirectActive}")
            add("SNAP|$label|transport.usbClaimed=${s.usbClaimed}")
            add("SNAP|$label|transport.claims=${s.usbClaims}")
            add("SNAP|$label|transport.releases=${s.usbReleases}")
            add("SNAP|$label|transport.claimFailures=${s.usbClaimFailures}")
            add("SNAP|$label|transport.altSetting=${s.usbAltSetting}")
            add("SNAP|$label|transport.requestedRate=${s.usbRequestedRateHz}")
            add("SNAP|$label|transport.confirmedRate=${s.usbConfirmedRateHz}")
            add("SNAP|$label|transport.confirmedRateMatched=${s.usbConfirmedRateHz == s.usbRequestedRateHz}")
            add("SNAP|$label|signal.mode=${s.signalMode}")
            add("SNAP|$label|signal.bitPerfect=${s.bitPerfectVerdict}")
            add("SNAP|$label|signal.bitPerfectReason=${s.bitPerfectReason}")
            add("SNAP|$label|signal.bitPerfectAdditionalReasons=${s.bitPerfectAdditionalReasons}")
            add("SNAP|$label|signal.verified=false")
            add("SNAP|$label|source.codec=${usb?.sourceCodec}")
            add("SNAP|$label|source.rate=${usb?.sourceSampleRate}")
            add("SNAP|$label|source.depth=${usb?.sourceBitDepth}")
            add("SNAP|$label|source.channels=${s.transportChannelCount}")
            add("SNAP|$label|format.inputEncoding=${s.inputPcmEncoding}")
            add("SNAP|$label|format.transportEncoding=${s.transportPcmEncoding}")
            add("SNAP|$label|format.transportDepth=${s.transportBitDepth}")
            add("SNAP|$label|format.transportRate=${s.transportSampleRateHz}")
            add("SNAP|$label|format.transportChannels=${s.transportChannelCount}")
            add("SNAP|$label|format.dacBestDepth=${usb?.dacBestBitDepth}")
            add("SNAP|$label|pipeline.producer=${s.producer}")
            // Plan B (the native engine), as of the sink's last producer decision. false is the B12a default: the
            // ExoPlayer pipeline carries PRISTINE. A capture that says true was not measuring the default.
            add("SNAP|$label|pipeline.debugForcedNativeEngine=${DirectPathTelemetry.debugForcedNativeEngine}")
            // B12a-3 (F-10): the silent track that makes Android see Direct playing, and B12a-4: pre-rolls after a rate change.
            add("SNAP|$label|pipeline.presenceActive=${s.presenceActive}")
            add("SNAP|$label|pipeline.presenceStarts=${s.presenceStarts}")
            add("SNAP|$label|pipeline.presenceStops=${s.presenceStops}")
            add("SNAP|$label|pipeline.presenceFailures=${s.presenceFailures}")
            add("SNAP|$label|pipeline.rateSwitchPrerolls=${s.rateSwitchPrerolls}")
            add("SNAP|$label|pipeline.lastRateSwitchPrerollMs=${s.lastRateSwitchPrerollMs}")
            add("SNAP|$label|pipeline.modeTransitions=${s.modeTransitions}")
            add("SNAP|$label|pipeline.nativeEngineStarts=${s.nativeEngineStarts}")
            add("SNAP|$label|pipeline.nativeEngineStops=${s.nativeEngineStops}")
            add("SNAP|$label|pipeline.streamingThreadStarts=${s.streamingThreadStarts}")
            add("SNAP|$label|pipeline.streamingThreadStops=${s.streamingThreadStops}")
            // AUD-144: Media3 stops held back from the engine at a natural join (0 = the engine was never paused by one).
            add("SNAP|$label|pipeline.joinPausesHeld=${s.naturalJoinPausesHeld}")
            add("SNAP|$label|pipeline.pauseFades=${s.pipelinePauseFades}")
            add("SNAP|$label|pipeline.seekFades=${s.pipelineSeekFades}")
            add("SNAP|$label|pipeline.realignsKept=${s.pipelineRealignsKept}")
            add("SNAP|$label|pipeline.stageConfigures=${s.stageConfigures}")
            add("SNAP|$label|pipeline.stageRefusals=${s.stageRefusals}")
            add("SNAP|$label|pipeline.stageProcessCalls=${s.stageProcessCalls}")
            add("SNAP|$label|perf.inputFrames=${s.inputFrames}")
            add("SNAP|$label|perf.outputFrames=${s.outputFrames}")
            add("SNAP|$label|perf.framesWritten=${s.usbFramesWritten}")
            add("SNAP|$label|perf.framesOutstanding=${s.inputFrames - s.usbFramesWritten}")
            add("SNAP|$label|perf.meanProcessUs=${s.meanProcessNanos?.div(1_000)}")
            add("SNAP|$label|perf.maxProcessUs=${s.processNanosMax / 1_000}")
            add("SNAP|$label|perf.p95ProcessUs=${s.percentileMicros(0.95)}")
            add("SNAP|$label|perf.p99ProcessUs=${s.percentileMicros(0.99)}")
            add("SNAP|$label|perf.underruns=${s.underruns}")
            add("SNAP|$label|perf.lateBuffers=${s.lateBuffers}")
            add("SNAP|$label|perf.droppedBuffers=${s.droppedBuffers}")
            add("SNAP|$label|perf.maxQueueDepth=${s.maxQueueDepth}")
            add("SNAP|$label|perf.ringOverflowed=${s.ringOverflowed}")
            // AUD-145: how the USB output waits for a URB completion, and how often it woke to do so
            // (process totals: take the difference of two snapshots). waitMode 1 = poll(2) on the usbfs
            // fd, 0 = the old 125 µs sleep (SET_USB_WAIT_MODE), -1 = not read.
            add("SNAP|$label|perf.usbWaitMode=${s.usbWaitMode}")
            add("SNAP|$label|perf.usbWaitCalls=${s.usbWaitCalls}")
            add("SNAP|$label|perf.usbWaitedMs=${s.usbWaitedUs / 1_000}")
            add("SNAP|$label|perf.usbWaitTimeouts=${s.usbWaitTimeouts}")
            add("SNAP|$label|perf.usbWaitEmpty=${s.usbWaitEmpty}")
            // B10 (AUD-124 "shh"): the DAC's feedback as the USB output sees it. Process totals again. `stuck`
            // is true while the same off-nominal reading has been answered for 5 s or more; the episode's value
            // and offset stay after it ends. `policy` is SET_FEEDBACK_POLICY's (follow unless a test changed
            // it) and `sendingFpmf` what the packetizer was given: equal to lastFpmf only under follow.
            add("SNAP|$label|perf.feedbackPolicy=${s.feedbackPolicy}")
            add("SNAP|$label|perf.feedbackStuck=${s.feedbackStuck}")
            add("SNAP|$label|perf.feedbackStuckEpisodes=${s.feedbackStuckEpisodes}")
            add("SNAP|$label|perf.feedbackStuckMs=${s.feedbackStuckMs}")
            add("SNAP|$label|perf.feedbackStuckReadings=${s.feedbackStuckReadings}")
            add("SNAP|$label|perf.feedbackStuckLongestMs=${s.feedbackStuckLongestMs}")
            add("SNAP|$label|perf.feedbackStuckFpmf=${"%.4f".format(java.util.Locale.ROOT, s.feedbackStuckFpmf)}")
            add("SNAP|$label|perf.feedbackStuckPpm=${s.feedbackStuckPpm}")
            add("SNAP|$label|perf.feedbackReadings=${s.feedbackReadings}")
            add("SNAP|$label|perf.feedbackLastFpmf=${"%.4f".format(java.util.Locale.ROOT, s.feedbackLastFpmf)}")
            add("SNAP|$label|perf.feedbackSendingFpmf=${"%.4f".format(java.util.Locale.ROOT, s.feedbackSendingFpmf)}")
            // B12a-2: readings the output did not obey (beyond +-5 %, or implausible under auto), the streaks of
            // implausible readings (one FEEDBACK_REJECTED line each) and the last one's value.
            add("SNAP|$label|perf.feedbackRejectedReadings=${s.feedbackRejectedReadings}")
            add("SNAP|$label|perf.feedbackImplausibleStreaks=${s.feedbackImplausibleStreaks}")
            add("SNAP|$label|perf.feedbackRejectedFpmf=${"%.4f".format(java.util.Locale.ROOT, s.feedbackRejectedFpmf)}")
            // The live position domains — the same three fields the SAMPLE line carries. A snapshot
            // is taken at a labeled moment (baseline, or either side of a transition), so printing
            // them here is what makes one transition record self-contained: the sink's answer, the
            // offset it added, and the engine position that answer was built from, all read at one
            // instant rather than reconstructed from a periodic sample's neighbourhood.
            // URB ownership around the seek barrier. `reusable` must be identical across repeated
            // barriers; a shrinking count means cancelled URBs are never retired.
            add("URB_DIAG|$label|before=${DirectPathTelemetry.urbDiagBeforeSeek}")
            add("URB_DIAG|$label|after=${DirectPathTelemetry.urbDiagAfterSeek}")
            add("URB_DIAG|$label|seekOutcome=${DirectPathTelemetry.seekOutcome}")

            // Identity-based barrier verdict: did every frozen target come back, and through which
            // reap site. Resolution is an exact (urb, slot, generation) observation, not a DISCARD.
            DirectPathTelemetry.barrierIdentity.split("\n").forEach { line ->
                add("BARRIER_ID|$label|$line")
            }

            // Flush origin: which caller flushed, and whether it precedes or follows a stall.
            add("FLUSH|$label|origin=${DirectPathTelemetry.flushOrigin}")
            add("FLUSH|$label|atMs=${DirectPathTelemetry.flushAtMs}")
            // The full position chain, with the last advancing sample of each clock layer. A stall
            // detected here is already ~10 s old, so this must reach back further than the tail.
            DirectPathTelemetry.positionTrace?.split("\n")?.forEach { line ->
                add("POS_TRACE|$label|$line")
            }

            // ACK ledger. Read forward from startup: count transitions, and whether incoming
            // PlaybackInfo was adopted or held at the publication gate.
            DirectPathTelemetry.ackLedger?.split("\n")?.forEach { line ->
                add("ACK_LEDGER|$label|$line")
            }

            // Mapping-epoch log: the operand writes, the latch arms, and the latch itself with both
            // operands and their provenance. This is what says whether an abnormal offset came from
            // a cross-epoch pair or from two operands that genuinely belong together.
            DirectPathTelemetry.mappingLog?.split("\n")?.forEach { line ->
                add("MAPPING|$label|$line")
            }
            add("POSITION|$label|enginePosUs=${DirectPathTelemetry.liveEnginePositionUs}")
            add("POSITION|$label|windowOffsetUs=${DirectPathTelemetry.liveWindowOffsetUs}")
            add("POSITION|$label|sinkReturnedUs=${DirectPathTelemetry.liveSinkReturnedUs}")
            // Anchor operands, raw and uncombined. `derived` is what the transition actually used;
            // the two inputs beside it are what it was computed from. Printing all three is the
            // whole point — a derived value alone cannot say which operand is in the wrong
            // coordinate system.
            add("ANCHOR_DIAG|$label|windowOffsetUs=${DirectPathTelemetry.anchorWindowOffsetUs}")
            add("ANCHOR_DIAG|$label|enginePositionUs=${DirectPathTelemetry.anchorEnginePositionUs}")
            add("ANCHOR_DIAG|$label|derivedHandoffUs=${DirectPathTelemetry.anchorDerivedUs}")
            add("ANCHOR_DIAG|$label|engineRunning=${DirectPathTelemetry.anchorEngineRunning}")
            add("ANCHOR_DIAG|$label|engineCreated=${DirectPathTelemetry.anchorEngineCreated}")
            add("ANCHOR_DIAG|$label|anchorProducer=${DirectPathTelemetry.anchorProducer}")
            add("ANCHOR_DIAG|$label|sampleRate=${s.transportSampleRateHz}")
            add("ANCHOR_DIAG|$label|usbFramesWritten=${s.usbFramesWritten}")
            add("LATCH_DIAG|$label|presentationTimeUs=${DirectPathTelemetry.latchPresentationTimeUs}")
            add("LATCH_DIAG|$label|initialPlayerPositionUs=${DirectPathTelemetry.latchInitialPlayerPositionUs}")
            add("LATCH_DIAG|$label|computedWindowOffsetUs=${DirectPathTelemetry.latchWindowOffsetUs}")
            add("LATCH_DIAG|$label|configureCount=${s.configureCount}")
            add("LATCH_DIAG|$label|flushCount=${s.flushCount}")
            // The two contributors and their product, read from the model rather than from
            // player.volume — which holds only the product and cannot attribute either part.
            val gain = DualPlayerEngineDebugAccess.gainModel(context)
            add("GAIN|$label|userDigitalGain=${gain?.userDigitalGain}")
            add("GAIN|$label|replayGainFactor=${gain?.replayGainFactor}")
            // B9-4: the lock that freezes every DSP requirement, and whether this process is ignoring it.
            add("GAIN|$label|persistedPristineLock=${PristineLockDiagnostic.lastPersistedLock() ?: "unseen"}")
            add("GAIN|$label|pristineLockIgnoredByDiagnostic=${PristineLockDiagnostic.ignoringLock.value}")
            add("GAIN|$label|combinedGain=${gain?.combinedGain}")
            add("PCM_LEVEL|$label|inputRms=${DirectPathTelemetry.stageInputRms}")
            add("PCM_LEVEL|$label|outputRms=${DirectPathTelemetry.stageOutputRms}")
            add("PCM_LEVEL|$label|gainRatio=${DirectPathTelemetry.stageGainRatio}")
            add("PCM_LEVEL|$label|inputPeak=${DirectPathTelemetry.stageInputPeak}")
            add("PCM_LEVEL|$label|outputPeak=${DirectPathTelemetry.stageOutputPeak}")
            add("PCM_LEVEL|$label|samples=${DirectPathTelemetry.stageOutputSamples}")
            add("SNAP|$label|state.cachedPlayerPositionMs=${DualPlayerEngineDebugAccess.positionMs(context)}")
            add("SNAP|$label|state.durationMs=${DualPlayerEngineDebugAccess.durationMs(context)}")
            add("SNAP|$label|state.rebuilds=${DualPlayerEngineDebugAccess.rebuildCount(context)}")
        }
        writeLines(context, lines)
    }

    // ── File plumbing ───────────────────────────────────────────────────

    /** Guards the read-modify-write below; see [writeLines]. */
    private val fileLock = Any()

    /**
     * Appends [lines] to the capture file in a single read-modify-write, keeping the newest
     * [MAX_LINES].
     *
     * Two properties are load-bearing, and both were learned from a failure that cost a run.
     *
     * **One call per batch.** The whole file is rewritten rather than appended, so the bound is
     * exact — which makes each call cost one full read plus one full write. Calling this once per
     * *line* therefore turned a ~90-line snapshot into ~90 full-file operations, each allocating a
     * multi-megabyte joined string, and the app died of an uncaught `OutOfMemoryError` mid-run.
     * Callers with many lines hand them over together.
     *
     * **One writer at a time.** This is called from the sampler thread and from the broadcast
     * receiver, which are not otherwise coordinated. Two interleaved read-modify-writes lose one of
     * the two appends entirely, which is indistinguishable from the event never having happened —
     * an entire run's `EVENT` lines can vanish that way without anything reporting a problem.
     *
     * Still only ever called from those two places — never an audio or USB thread.
     */
    private fun writeLines(context: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        synchronized(fileLock) {
            runCatching {
                val base = context.getExternalFilesDir(null) ?: context.filesDir
                val dir = File(base, "diagnostics").apply { mkdirs() }
                val file = File(dir, FILE_NAME)
                val existing = if (file.isFile) file.readLines() else emptyList()
                file.writeText((existing + lines).takeLast(MAX_LINES).joinToString("\n"))
            }.onFailure { Log.w(TAG, "Could not write capture lines", it) }
        }
    }

    private fun write(context: Context, line: String) = writeLines(context, listOf(line))

    /** The capture file, for a host that wants to read it directly. */
    fun fileFor(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(base, "diagnostics"), FILE_NAME)
    }
}
