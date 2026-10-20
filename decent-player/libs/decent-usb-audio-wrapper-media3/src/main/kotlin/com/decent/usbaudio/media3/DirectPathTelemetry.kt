package com.decent.usbaudio.media3

import android.os.SystemClock
import com.decent.usbaudio.FeedbackPolicy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory evidence recorder for the Direct transport.
 *
 * ## Why this exists
 *
 * This device's OEM logging stack does not surface application logs through `adb logcat`, so a
 * physical session has to be read back from a file. But the numbers worth having — frames in,
 * frames out, how long the DSP took, how deep the queue ran — are produced on the playback and USB
 * threads, and **nothing here may touch the filesystem on those threads**. A synchronous write in
 * the audio path perturbs exactly the thing being measured, and at 96/192 kHz it can perturb it
 * into a dropout.
 *
 * So this object is the split point: the hot path only ever *increments counters* and appends to a
 * bounded in-memory ring. A separate host-side thread reads [snapshot] on its own schedule and does
 * all the formatting and I/O.
 *
 * ## Cost when idle
 *
 * Counters are [AtomicLong] rather than plain longs deliberately. A `++` on a plain field is
 * cheaper, but non-volatile 64-bit writes are not guaranteed atomic by the Java memory model and
 * this data is read from other threads; a diagnostic that occasionally reports a torn number would
 * cost more to distrust than the atomic costs to avoid. The exchange rate makes this easy: measured
 * from this device's own captures, `handleBuffer` is called roughly **ten times a second** (a
 * buffer is ~100 ms of audio at every rate observed), not a hundred thousand. Ten uncontended
 * atomic increments per second is not a cost worth designing around.
 *
 * [captureEnabled] gates only the work that is *not* a counter — the `nanoTime` pairs and the ring
 * appends — so a release build pays the counters and nothing else.
 *
 * ## Not a source of truth
 *
 * Nothing here feeds the audio path. The transport, the routing policy and the DSP never read it.
 * It observes; it does not decide.
 */
object DirectPathTelemetry {

    /** Enables timing and transition recording. Set by the host when a capture session starts. */
    @Volatile
    var captureEnabled: Boolean = false

    // ── Transport lifetime ──────────────────────────────────────────────

    /** Successful exclusive USB claims (`configureUsbBitPerfect` returning true). */
    val usbClaims = AtomicLong()

    /** Released USB streams (`releaseUsbStream`). */
    val usbReleases = AtomicLong()

    /** Claims that failed and left the route on the Android delegate. */
    val usbClaimFailures = AtomicLong()

    /** True while the sink is holding a live USB stream. */
    @Volatile
    var usbClaimed: Boolean = false

    /** The alternate setting the DAC is streaming on, or null before a claim. */
    @Volatile
    var usbAltSetting: Int? = null

    /** The rate the sink asked the DAC for. */
    @Volatile
    var usbRequestedRateHz: Int? = null

    /** The rate the DAC reported back via GET_CUR. Equal to requested means the clock locked. */
    @Volatile
    var usbConfirmedRateHz: Int? = null

    // ── Producer identity ───────────────────────────────────────────────

    /** Which component is producing PCM for the Direct transport. */
    enum class Producer { NONE, NATIVE_FLAC, EXOPLAYER }

    @Volatile
    var producer: Producer = Producer.NONE

    /**
     * DEBUG ONLY: plan B (`DirectSignalSource.debugForceNativeEngine`) was on the last time the sink
     * decided a producer, i.e. the native engine was allowed to exist. False is the B12a default and
     * means the ExoPlayer pipeline carries PRISTINE. A state, not a counter, so [reset] leaves it alone.
     */
    @Volatile
    var debugForcedNativeEngine: Boolean = false

    val nativeEngineStarts = AtomicLong()
    val nativeEngineStops = AtomicLong()
    val streamingThreadStarts = AtomicLong()
    val streamingThreadStops = AtomicLong()

    /**
     * AUD-144: stops from Media3 (the rebuffer right after a natural join, with the user still
     * playing) that were held back from the native engine instead of pausing it.
     */
    val naturalJoinPausesHeld = AtomicLong()

    /**
     * B9: gestures the ExoPlayer pipeline's writer handled without cutting the ring: pauses whose
     * continuation was faded out (8 ms) and seeks/skips whose old-position continuation was, and the
     * resumes that played the kept rest of a block back in. Process totals; a pause the writer found
     * nothing to fade for (an empty queue, a ring that had played out) is not counted.
     */
    val pipelinePauseFades = AtomicLong()
    val pipelineSeekFades = AtomicLong()

    /**
     * B9-3: a PRISTINE ↔ PROCESSED change with the pipeline as the producer on both sides, which kept
     * the streaming thread and its queue (and the audio already queued) instead of rebuilding them.
     */
    val pipelineRealignsKept = AtomicLong()

    /**
     * B12a-3 / F-10: the silent track that makes Android see music playing in USB Direct
     * ([DirectPlaybackPresence]): how many were started and stopped, how many starts failed (no speaker to pin it
     * to, a platform error), and whether one is running now. Process totals; a state, not a counter, for the last.
     */
    val presenceStarts = AtomicLong()

    /**
     * B12a-4: pre-rolls queued after a rate change ([RateSwitchPreroll]), and the last one's length in ms (0 = none
     * yet). Both stay 0 with the default setting.
     */
    val rateSwitchPrerolls = AtomicLong()

    @Volatile
    var lastRateSwitchPrerollMs: Int = 0
    val presenceStops = AtomicLong()
    val presenceFailures = AtomicLong()

    @Volatile
    var presenceActive: Boolean = false

    /**
     * AUD-145: the USB output's waits for a URB completion, read from the native layer when a snapshot is
     * taken: [mode (1 = poll on the usbfs fd, 0 = the 125 µs sleep), waits entered, µs spent in them,
     * waits that ended without a completion, polls that reported a completion and found nothing to reap].
     * Set by the sink, so this class never touches the native library (the unit tests run without it).
     */
    @Volatile
    var usbWaitStatsProvider: (() -> LongArray)? = null

    /**
     * B10: the USB output's view of the DAC's feedback (policy, whether it is stuck, the episodes), read from the
     * native layer when a snapshot is taken: [UsbAudioStream.nativeGetFeedbackStats]'s layout. Set by the sink,
     * for the same reason as [usbWaitStatsProvider].
     */
    @Volatile
    var usbFeedbackStatsProvider: (() -> LongArray)? = null

    // ── Signal path ─────────────────────────────────────────────────────

    /** Times the signal path actually moved between pristine and processed. */
    val modeTransitions = AtomicLong()

    /** Times a [DirectPcmStage] was configured for a stream shape. */
    val stageConfigures = AtomicLong()

    /** Times a [DirectPcmStage] was asked to process a block. */
    val stageProcessCalls = AtomicLong()

    /** Times a stage refused a format and the sink fell back to pass-through. */
    val stageRefusals = AtomicLong()

    @Volatile
    var signalMode: DirectSignalMode = DirectSignalMode.PRISTINE

    @Volatile
    var bitPerfectVerdict: BitPerfectVerdict = BitPerfectVerdict.UNKNOWN

    @Volatile
    var bitPerfectReason: BitPerfectReason? = null

    /**
     * The native engine's position, published on each position query.
     *
     * Compared against the player's reported position at the same instant, this is how the engine's
     * lead over the audible position is measured rather than assumed. `framesDecoded` counts frames
     * *submitted* to USB, so a lead is expected; what matters is its size against the block
     * deadline.
     */
    @Volatile
    var liveEnginePositionUs: Long = ANCHOR_UNSET

    /**
     * The offset the sink held when it last answered a position query.
     *
     * Recorded beside [liveSinkReturnedUs] so the two domains can be compared at one instant rather
     * than inferred from names: the sink's answer is what Media3 turns into the player's position,
     * and whether that answer is media-domain or renderer-domain is the question being settled.
     */
    @Volatile
    var liveWindowOffsetUs: Long = ANCHOR_UNSET

    /** The raw value the sink returned from `getCurrentPositionUs`, before Media3 transforms it. */
    @Volatile
    var liveSinkReturnedUs: Long = ANCHOR_UNSET

    // ── Seek protocol ───────────────────────────────────────────────────
    //
    // Requested and completed are separate on purpose. `getPositionUs` is set to the target the
    // moment a seek is requested so the reported position does not jump backwards, which means it
    // says nothing about whether the decoder has actually moved. Only a matching completed
    // generation does.

    /** Generation of the most recent seek pushed by the host. */
    @Volatile
    var seekRequestedGeneration: Long = 0

    /** Media target, microseconds, of the most recent seek pushed by the host. */
    @Volatile
    var seekTargetUs: Long = -1

    /** Generation the decoder has finished seeking to. Reaching [seekRequestedGeneration] means done. */
    @Volatile
    var seekCompletedGeneration: Long = 0

    /** Media target, microseconds, the decoder actually landed on. */
    @Volatile
    var seekCompletedTargetUs: Long = -1

    /** Whether the most recently completed seek succeeded; false means the position is not the target. */
    @Volatile
    var seekCompletedOk: Boolean = true

    /**
     * Outcome of the most recent seek: `PENDING`, `SUCCESS` or `FAILED`.
     *
     * Kept beside [seekCompletedGeneration] because the generation only says the attempt finished.
     * A FAILED generation means the target did not become the position.
     */
    @Volatile
    var seekOutcome: String = "PENDING"

    /**
     * URB ownership reading sampled around the seek barrier.
     *
     * `reusable` must be constant across repeated barriers; a count that shrinks cycle after cycle
     * means cancelled URBs are not being retired. See `UsbAudioStream.urbDiag`.
     */
    @Volatile
    var urbDiagBeforeSeek: String = "-"

    @Volatile
    var urbDiagAfterSeek: String = "-"


    /** Identity-based barrier verdict; see `UsbAudioStream.barrierIdentityDiag`. */
    @Volatile
    var barrierIdentity: String = "-"

    /**
     * Which caller initiated the most recent `AudioSink.flush()`, and when.
     *
     * Two stall incidents had a flush within milliseconds of the stuck error, so the origin is what
     * distinguishes "flush caused the stall" from "flush was error teardown after it".
     */
    @Volatile
    var flushOrigin: String = "-"

    @Volatile
    var flushAtMs: Long = 0

    /** Rendered position-chain trace; refreshed while a capture session is running. */
    @Volatile
    var positionTrace: String? = null

    /** ACK ledger: count transitions and adopt-vs-hold at the PlaybackInfo publication gate. */
    @Volatile
    var ackLedger: String? = null

    /**
     * The mapping-epoch log: every arm, reset, operand write, seek boundary and latch, in order.
     *
     * Separate from the position trace because a latch happens once per arm and the trace samples
     * every 250 ms — by the time a stall is noticed the latch that set the mapping is long past the
     * trace's useful window, and the trace records the offset without its operands.
     */
    var mappingLog: String? = null

    /**
     * DIAGNOSTIC ONLY. When true, the first mapping latch after a position reset subtracts the
     * *new* epoch's declared seek target instead of the possibly-stale `initialPlayerPositionUs`.
     *
     * This exists to answer one causal question — whether a stale start-position operand is
     * sufficient to produce the abnormal renderer offset and the stall that follows — and it is
     * deliberately a switch rather than a fix. Default false: production behavior is unchanged.
     */
    @Volatile
    var diagnosticRebaseOperandOnSeek: Boolean = false

    /** The most recently declared new-epoch start position, in microseconds; -1 when none. */
    @Volatile
    var diagnosticRebaseTargetUs: Long = -1L

    /** The target the armed latch will actually consume, frozen at arm time so the record is honest. */
    @Volatile
    var diagnosticRebasePendingTargetUs: Long = -1L

    /** How many latches have consumed a diagnostic substitution. Expected 0 or 1 per run. */
    val diagnosticRebaseApplies = java.util.concurrent.atomic.AtomicLong()

    /**
     * DIAGNOSTIC ONLY: the media position the current playback epoch started from, captured at the
     * production restart decision that established it.
     *
     * Sourced from the realign's engine-seek target — the exact value the new epoch begins at — and
     * never from a later `Player.currentPosition` sample or from the first presentation timestamp.
     */
    @Volatile
    var diagnosticEngineStartTargetUs: Long = -1L

    /** Where the armed diagnostic operand came from, so the latch record names its real provenance. */
    @Volatile
    var diagnosticRebasePendingSource: String = "UNSET"

    /** How many stages beyond the headline one are active, so a second cause is visible. */
    @Volatile
    var bitPerfectAdditionalReasons: Int = 0

    // ── Format ──────────────────────────────────────────────────────────

    /** The encoding the decoder handed the sink. */
    @Volatile
    var inputPcmEncoding: Int? = null

    /** The encoding the sink handed the USB writer. */
    @Volatile
    var transportPcmEncoding: Int? = null

    @Volatile
    var transportBitDepth: Int? = null

    @Volatile
    var transportSampleRateHz: Int? = null

    @Volatile
    var transportChannelCount: Int? = null

    // ── Counters the hot path increments ────────────────────────────────

    val inputFrames = AtomicLong()
    val outputFrames = AtomicLong()
    val processedBlocks = AtomicLong()

    /** Nanoseconds spent inside [DirectPcmStage.process], summed for an average. */
    val processNanosTotal = AtomicLong()

    /** Worst single block, for spotting a spike an average hides. */
    val processNanosMax = AtomicLong()

    /**
     * A histogram of processing time, in fixed microsecond buckets.
     *
     * A mean cannot answer "did any block come close to the deadline", which is the only question
     * that matters for whether the pipeline can keep up. Buckets are cheap to update on the hot
     * path — one array increment — and let the host compute a p95 without keeping a sample list.
     */
    val processNanosHistogram = Array(PROCESS_BUCKETS) { AtomicLong() }

    // ── Starvation and loss ─────────────────────────────────────────────

    /** Times the USB writer's queue ran dry (a poll found nothing to send). */
    val underruns = AtomicLong()

    /** Times the queue was down to its last buffer — the warning band before an underrun. */
    val lateBuffers = AtomicLong()

    /** Buffers dropped because the queue was full. */
    val droppedBuffers = AtomicLong()

    // ── Anchor diagnostics ──────────────────────────────────────────────
    //
    // Raw, uncombined values captured at the last signal-mode transition. Kept separate on
    // purpose: the bug this exists to explain was a *derived* number (~1e15 us) whose inputs were
    // never recorded, so it was impossible to tell which operand was wrong or which coordinate
    // system it was in. Combining them before logging is what made that unanswerable.

    const val ANCHOR_UNSET = Long.MIN_VALUE

    /** ExoPlayer-timeline position of the track's sample 0, as the sink held it. */
    @Volatile
    var anchorWindowOffsetUs: Long = ANCHOR_UNSET

    /** `NativeAudioEngine.getPositionUs()` — frames decoded from sample 0, as microseconds. */
    @Volatile
    var anchorEnginePositionUs: Long = ANCHOR_UNSET

    /** The sink's derived media position: `windowOffset + enginePosition`. */
    @Volatile
    var anchorDerivedUs: Long = ANCHOR_UNSET

    /** `engine.isRunning` at capture time; false after a destroy. */
    @Volatile
    var anchorEngineRunning: Boolean = false

    /** `engine.isCreated` at capture time. */
    @Volatile
    var anchorEngineCreated: Boolean = false

    /** Which producer the anchor was taken from. */
    @Volatile
    var anchorProducer: Producer = Producer.NONE

    // ── Latch operands ──────────────────────────────────────────────────
    //
    // The two inputs `windowOffsetUs` is computed from, recorded once per latch. The derived value
    // was measured at ~1e12 us and the inputs were never kept, so there was no way to tell whether
    // the presentation timestamp, the player position, or the arithmetic was at fault.

    /** `presentationTimeUs` of the buffer that established the offset. */
    @Volatile
    var latchPresentationTimeUs: Long = ANCHOR_UNSET

    /** `initialPlayerPositionUs`, captured from the player at media-item transition. */
    @Volatile
    var latchInitialPlayerPositionUs: Long = ANCHOR_UNSET

    /** The offset the latch produced. */
    @Volatile
    var latchWindowOffsetUs: Long = ANCHOR_UNSET

    /** How many times the sink has been configured. Distinguishes playback epochs. */
    val configureCount = AtomicLong()

    /** How many times the sink has been flushed (seeks). */
    val flushCount = AtomicLong()

    // ── PCM level (proves gain actually happened) ───────────────────────
    //
    // Measured across the stage, input and output of the *same* blocks, so the ratio is
    // self-contained: it does not depend on programme material, playback position, or what the
    // track happens to be doing. "It sounded quieter" is not evidence that a gain stage ran; a
    // requested 0.5 that produces an output/input RMS ratio of 0.5 is.
    //
    // Written only from the audio thread (read-modify-write, single writer), read racily by the
    // capture thread. Accumulating a sum of squares per sample is far too expensive to do
    // unconditionally, so it is gated on `captureEnabled`.

    @Volatile var stageInputSumSquares: Double = 0.0
    @Volatile var stageInputSamples: Long = 0
    @Volatile var stageOutputSumSquares: Double = 0.0
    @Volatile var stageOutputSamples: Long = 0
    @Volatile var stageOutputPeak: Float = 0f
    @Volatile var stageInputPeak: Float = 0f

    /** RMS of the blocks entering the stage, or null before any block has been measured. */
    val stageInputRms: Double?
        get() = if (stageInputSamples > 0) kotlin.math.sqrt(stageInputSumSquares / stageInputSamples) else null

    /** RMS of the blocks leaving the stage. */
    val stageOutputRms: Double?
        get() = if (stageOutputSamples > 0) kotlin.math.sqrt(stageOutputSumSquares / stageOutputSamples) else null

    /**
     * Output/input RMS ratio across the measured blocks.
     *
     * The direct answer to "did the requested gain actually reach the PCM". Cleared by [reset] so
     * each state under test is measured from a known zero rather than averaged with the last one.
     */
    val stageGainRatio: Double?
        get() {
            val inRms = stageInputRms ?: return null
            val outRms = stageOutputRms ?: return null
            return if (inRms > 0.0) outRms / inRms else null
        }

    /** Clears only the PCM level accumulators, leaving the transition counters intact. */
    fun resetPcmLevel() {
        stageInputSumSquares = 0.0; stageInputSamples = 0
        stageOutputSumSquares = 0.0; stageOutputSamples = 0
        stageOutputPeak = 0f; stageInputPeak = 0f
    }

    /** Highest queue depth seen, in buffers. */
    val maxQueueDepth = AtomicInteger()

    /**
     * Frames accepted by the USB context, as the native layer counts them.
     *
     * Published by the streaming thread after each write rather than read on demand, because the
     * only object that can reach it is inside the sink. At roughly ten writes a second this is a
     * volatile store per buffer — not a cost worth adding cross-module plumbing to avoid.
     */
    @Volatile
    var usbFramesWritten: Long = 0L

    /** Buffer depth at the most recent sample, in buffers. */
    @Volatile
    var lastQueueDepth: Int = 0

    // ── Bounded transition ring ─────────────────────────────────────────

    /**
     * One discrete event, recorded when something *changed* rather than continuously.
     *
     * Transitions are what a session has to be reconstructed from: which producer was running, what
     * the mode was, where the position was. Continuous values are covered by the counters above.
     */
    data class Event(
        val elapsedRealtimeMs: Long,
        val kind: String,
        val detail: String,
    )

    private val ring = arrayOfNulls<Event>(RING_CAPACITY)
    private val ringIndex = AtomicInteger()
    private val ringOverflowed = AtomicLong()

    /**
     * Appends one event to the bounded ring.
     *
     * Lock-free and allocation-light: a single slot store plus an index bump, so calling it from a
     * transition on the playback thread costs nothing worth measuring. Old events are overwritten
     * once the ring is full — the count of overwrites is reported so a session can tell it lost the
     * beginning rather than silently believing it saw everything.
     */
    fun record(kind: String, detail: String) {
        val slot = ringIndex.getAndIncrement()
        if (slot >= RING_CAPACITY) ringOverflowed.incrementAndGet()
        ring[slot % RING_CAPACITY] = Event(SystemClock.elapsedRealtime(), kind, detail)
    }

    /** Records only when a capture session is running; a no-op otherwise. */
    inline fun recordIfCapturing(kind: String, detail: () -> String) {
        if (captureEnabled) record(kind, detail())
    }

    /**
     * Times [block] and folds the result into the counters.
     *
     * Returns [block]'s own value so a caller can use it inline without a second function.
     */
    inline fun <T> timeProcess(block: () -> T): T {
        if (!captureEnabled) return block()
        val started = System.nanoTime()
        val result = block()
        val elapsed = System.nanoTime() - started
        processNanosTotal.addAndGet(elapsed)
        processNanosMax.accumulateAndGet(elapsed, ::maxOf)
        processNanosHistogram[bucketFor(elapsed)].incrementAndGet()
        return result
    }

    /**
     * How many events have ever been recorded, including ones the ring has since overwritten.
     *
     * A drain cursor has to be kept in these terms rather than in terms of `events().size`: that
     * size saturates at [RING_CAPACITY], so once the ring wraps a reader comparing the two sees
     * "nothing new" forever and stops writing events — silently, and precisely when a long session
     * has the most to say.
     */
    val eventsRecorded: Int
        get() = ringIndex.get()

    /** The events still held by the ring, oldest first. */
    fun events(): List<Event> {
        val written = ringIndex.get()
        val start = maxOf(0, written - RING_CAPACITY)
        return (start until written).mapNotNull { ring[it % RING_CAPACITY] }
    }

    // ── Snapshot and reset ──────────────────────────────────────────────

    /**
     * An immutable reading of every counter, taken from whatever thread calls it.
     *
     * Values are read independently, so a snapshot taken while audio is running is a *consistent
     * -enough* picture rather than one frozen instant: a frame can land between two reads. For a
     * session record that is the right trade — the alternative is stopping the audio thread.
     */
    data class Snapshot(
        val captureEnabled: Boolean,
        val elapsedRealtimeMs: Long,
        val usbClaims: Long,
        val usbReleases: Long,
        val usbClaimFailures: Long,
        val usbClaimed: Boolean,
        val usbAltSetting: Int?,
        val usbRequestedRateHz: Int?,
        val usbConfirmedRateHz: Int?,
        val producer: Producer,
        val nativeEngineStarts: Long,
        val nativeEngineStops: Long,
        val streamingThreadStarts: Long,
        val streamingThreadStops: Long,
        val modeTransitions: Long,
        val stageConfigures: Long,
        val stageProcessCalls: Long,
        val stageRefusals: Long,
        val signalMode: DirectSignalMode,
        val bitPerfectVerdict: BitPerfectVerdict,
        val bitPerfectReason: BitPerfectReason?,
        val bitPerfectAdditionalReasons: Int,
        val inputPcmEncoding: Int?,
        val transportPcmEncoding: Int?,
        val transportBitDepth: Int?,
        val transportSampleRateHz: Int?,
        val transportChannelCount: Int?,
        val inputFrames: Long,
        val outputFrames: Long,
        val processedBlocks: Long,
        val processNanosTotal: Long,
        val processNanosMax: Long,
        val histogram: List<Long>,
        val underruns: Long,
        val lateBuffers: Long,
        val droppedBuffers: Long,
        val maxQueueDepth: Int,
        val lastQueueDepth: Int,
        val usbFramesWritten: Long,
        val configureCount: Long,
        val flushCount: Long,
        val events: List<Event>,
        val ringOverflowed: Long,
        val naturalJoinPausesHeld: Long,
        val pipelinePauseFades: Long,
        val pipelineSeekFades: Long,
        val pipelineRealignsKept: Long,
        /** B12a-3: the silent playback that makes Android see Direct playing; see [presenceStarts]. */
        val presenceActive: Boolean,
        /** B12a-4: pre-rolls queued after a rate change and the last one's length in ms; see [rateSwitchPrerolls]. */
        val rateSwitchPrerolls: Long,
        val lastRateSwitchPrerollMs: Int,
        val presenceStarts: Long,
        val presenceStops: Long,
        val presenceFailures: Long,
        /** 1 = poll(2), 0 = the old sleep, -1 = not read; see [usbWaitStatsProvider]. */
        val usbWaitMode: Int,
        val usbWaitCalls: Long,
        val usbWaitedUs: Long,
        val usbWaitTimeouts: Long,
        val usbWaitEmpty: Long,
        /** B10: `follow`, `nominal`, `clamp`, `auto`, or `?` when not read; see [usbFeedbackStatsProvider]. */
        val feedbackPolicy: String,
        /** The feedback has answered the same off-nominal value for 5 s or more (`FEEDBACK_STUCK`) right now. */
        val feedbackStuck: Boolean,
        val feedbackStuckEpisodes: Long,
        val feedbackStuckReadings: Long,
        /** Ms spent stuck in all episodes of this process, the open one included. */
        val feedbackStuckMs: Long,
        val feedbackStuckLongestMs: Long,
        /** The last episode's value, in frames per microframe (0 when none), and its offset from nominal in ppm. */
        val feedbackStuckFpmf: Double,
        val feedbackStuckPpm: Long,
        /** Every feedback completion the output has handled, and the last applied reading's value. */
        val feedbackReadings: Long,
        val feedbackLastFpmf: Double,
        /** What the packetizer was last given: [feedbackLastFpmf] under `follow`, otherwise what the policy made of it. */
        val feedbackSendingFpmf: Double,
        /**
         * B12a-2: readings the output did not obey — outside +-5 % (any policy) or, under `auto`, implausible (absurd,
         * or beyond +-0.5 % for 5 s) — and how many streaks of implausible readings began (what `FEEDBACK_REJECTED`
         * logs), with the last one's value. 0 when the native library is older than the stats.
         */
        val feedbackRejectedReadings: Long,
        val feedbackImplausibleStreaks: Long,
        val feedbackRejectedFpmf: Double,
    ) {
        /** Mean nanoseconds inside the stage, or null when it never ran. */
        val meanProcessNanos: Long?
            get() = if (processedBlocks > 0) processNanosTotal / processedBlocks else null

        /**
         * The bucket upper bound, in microseconds, under which [percentile] of blocks fell.
         *
         * Computed from the histogram rather than a retained sample list, so a long session costs
         * no more memory than a short one.
         */
        fun percentileMicros(percentile: Double): Long? {
            val total = histogram.sum()
            if (total == 0L) return null
            val target = (total * percentile).toLong().coerceAtLeast(1L)
            var running = 0L
            for (bucket in histogram.indices) {
                running += histogram[bucket]
                // Reported as the bucket's upper bound, so the number is "no worse than this"
                // rather than implying a precision the bucketing does not have.
                if (running >= target) return (1L shl bucket) / 1_000
            }
            return (1L shl (histogram.size - 1)) / 1_000
        }
    }

    fun snapshot(): Snapshot {
        val wait = runCatching { usbWaitStatsProvider?.invoke() }.getOrNull()
            ?.takeIf { it.size >= 5 }
        val feedback = runCatching { usbFeedbackStatsProvider?.invoke() }.getOrNull()
            ?.takeIf { it.size >= 12 }
        return snapshotWith(wait, feedback)
    }

    private fun snapshotWith(wait: LongArray?, feedback: LongArray?): Snapshot = Snapshot(
        captureEnabled = captureEnabled,
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        usbClaims = usbClaims.get(),
        usbReleases = usbReleases.get(),
        usbClaimFailures = usbClaimFailures.get(),
        usbClaimed = usbClaimed,
        usbAltSetting = usbAltSetting,
        usbRequestedRateHz = usbRequestedRateHz,
        usbConfirmedRateHz = usbConfirmedRateHz,
        producer = producer,
        nativeEngineStarts = nativeEngineStarts.get(),
        nativeEngineStops = nativeEngineStops.get(),
        streamingThreadStarts = streamingThreadStarts.get(),
        streamingThreadStops = streamingThreadStops.get(),
        modeTransitions = modeTransitions.get(),
        stageConfigures = stageConfigures.get(),
        stageProcessCalls = stageProcessCalls.get(),
        stageRefusals = stageRefusals.get(),
        signalMode = signalMode,
        bitPerfectVerdict = bitPerfectVerdict,
        bitPerfectReason = bitPerfectReason,
        bitPerfectAdditionalReasons = bitPerfectAdditionalReasons,
        inputPcmEncoding = inputPcmEncoding,
        transportPcmEncoding = transportPcmEncoding,
        transportBitDepth = transportBitDepth,
        transportSampleRateHz = transportSampleRateHz,
        transportChannelCount = transportChannelCount,
        inputFrames = inputFrames.get(),
        outputFrames = outputFrames.get(),
        processedBlocks = processedBlocks.get(),
        processNanosTotal = processNanosTotal.get(),
        processNanosMax = processNanosMax.get(),
        histogram = processNanosHistogram.map { it.get() },
        underruns = underruns.get(),
        lateBuffers = lateBuffers.get(),
        droppedBuffers = droppedBuffers.get(),
        maxQueueDepth = maxQueueDepth.get(),
        lastQueueDepth = lastQueueDepth,
        usbFramesWritten = usbFramesWritten,
        configureCount = configureCount.get(),
        flushCount = flushCount.get(),
        events = events(),
        ringOverflowed = ringOverflowed.get(),
        naturalJoinPausesHeld = naturalJoinPausesHeld.get(),
        pipelinePauseFades = pipelinePauseFades.get(),
        pipelineSeekFades = pipelineSeekFades.get(),
        pipelineRealignsKept = pipelineRealignsKept.get(),
        presenceActive = presenceActive,
        rateSwitchPrerolls = rateSwitchPrerolls.get(),
        lastRateSwitchPrerollMs = lastRateSwitchPrerollMs,
        presenceStarts = presenceStarts.get(),
        presenceStops = presenceStops.get(),
        presenceFailures = presenceFailures.get(),
        usbWaitMode = wait?.get(0)?.toInt() ?: -1,
        usbWaitCalls = wait?.get(1) ?: 0L,
        usbWaitedUs = wait?.get(2) ?: 0L,
        usbWaitTimeouts = wait?.get(3) ?: 0L,
        usbWaitEmpty = wait?.get(4) ?: 0L,
        feedbackPolicy = FeedbackPolicy.name(feedback?.get(0)),
        feedbackStuck = feedback?.get(1) == 1L,
        feedbackStuckEpisodes = feedback?.get(2) ?: 0L,
        feedbackStuckReadings = feedback?.get(3) ?: 0L,
        feedbackStuckMs = feedback?.get(4) ?: 0L,
        feedbackStuckLongestMs = feedback?.get(5) ?: 0L,
        feedbackStuckFpmf = FeedbackPolicy.fpmf(feedback?.get(6)) ?: 0.0,
        feedbackStuckPpm = feedback?.get(7) ?: 0L,
        feedbackReadings = feedback?.get(8) ?: 0L,
        feedbackLastFpmf = FeedbackPolicy.fpmf(feedback?.get(9)) ?: 0.0,
        feedbackSendingFpmf = FeedbackPolicy.fpmf(feedback?.get(11)) ?: 0.0,
        feedbackRejectedReadings = feedback?.getOrNull(12) ?: 0L,
        feedbackImplausibleStreaks = feedback?.getOrNull(13) ?: 0L,
        feedbackRejectedFpmf = FeedbackPolicy.fpmf(feedback?.getOrNull(14)) ?: 0.0,
    )

    /** Clears every counter and the ring, so a session starts from a known zero. */
    fun reset() {
        usbClaims.set(0); usbReleases.set(0); usbClaimFailures.set(0)
        nativeEngineStarts.set(0); nativeEngineStops.set(0)
        streamingThreadStarts.set(0); streamingThreadStops.set(0)
        naturalJoinPausesHeld.set(0)
        pipelinePauseFades.set(0); pipelineSeekFades.set(0); pipelineRealignsKept.set(0)
        presenceStarts.set(0); presenceStops.set(0); presenceFailures.set(0)
        rateSwitchPrerolls.set(0)
        modeTransitions.set(0); stageConfigures.set(0)
        stageProcessCalls.set(0); stageRefusals.set(0)
        inputFrames.set(0); outputFrames.set(0); processedBlocks.set(0)
        processNanosTotal.set(0); processNanosMax.set(0)
        processNanosHistogram.forEach { it.set(0) }
        underruns.set(0); lateBuffers.set(0); droppedBuffers.set(0); maxQueueDepth.set(0)
        ringIndex.set(0); ringOverflowed.set(0)
        ring.fill(null)
    }

    /**
     * Buckets are logarithmic: bucket `b` holds blocks that took in `[2^b, 2^(b+1))` nanoseconds.
     *
     * Linear buckets were the obvious first choice and were wrong. Processing a ~100 ms block
     * through a ten-band filter bank takes milliseconds, so 64 one-microsecond buckets saturated on
     * the very first bucket boundary and every real measurement landed in the last one — a p95 that
     * reported `63` for a distribution centred on 6,700. A percentile is only worth recording if
     * the buckets span the range the values actually occupy, and a DSP block spans roughly three
     * orders of magnitude between a warm cache and a JIT-compiling first call.
     */
    const val PROCESS_BUCKETS = 64

    /** The log2 bucket holding [elapsedNanos]. `@PublishedApi` because [timeProcess] inlines. */
    @PublishedApi
    internal fun bucketFor(elapsedNanos: Long): Int =
        if (elapsedNanos <= 0L) 0 else (64 - java.lang.Long.numberOfLeadingZeros(elapsedNanos))
            .coerceIn(0, PROCESS_BUCKETS - 1)

    private const val RING_CAPACITY = 512
}
