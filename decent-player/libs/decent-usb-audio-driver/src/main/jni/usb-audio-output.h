/**
 * @file usb-audio-output.h
 * @brief Direct USB audio output context for bit-perfect playback.
 *
 * Pipeline architecture:
 * - Pre-allocated ring buffer of 64 URB slots
 * - Each URB = 8 ISO packets (1ms of audio at high-speed)
 * - URBs complete in FIFO order (ISO_ASAP guarantees this)
 * - No malloc/free during streaming — avoids MTE pointer tag issues
 */

#pragma once

#include <atomic>
#include <mutex>
#include <cstdint>
#include <linux/usbdevice_fs.h>

#include "usb-glitch.h"
#include "usb-pcm-convert.h"
#include "usb-pcm-packetizer.h"

/**
 * Number of isochronous packets per URB submission.
 * At USB high-speed (125us microframes), 8 packets = 1ms of audio.
 */
#define USB_AUDIO_PACKETS_PER_URB 8

/**
 * Number of URBs in the ring buffer.
 * 80 URBs ≈ 80 ms of in-flight audio at 44.1 kHz, which empirically gives
 * the xHCI host controller enough scheduling headroom to maintain
 * continuous isochronous output without underruns on commodity Android
 * SoCs. Smaller pipelines (< ~64 URBs at 44.1 kHz) trigger glitches as
 * the ring drains faster than the URB submit/reap cycle can refill it.
 */
#define USB_AUDIO_NUM_URBS 80

// URB slot size: usbUrbSlotBytes(maxPacketSize) (usb-pcm-packetizer.h). The fixed 4096 assumed at most
// 384 kHz and was overrun above ~512 kHz (audit 2).

/**
 * Which reap path returned a completion.
 *
 * All five share one usbfs fd, and usbfs reaping is not endpoint-specific, so a completion can be
 * returned to a caller that did not submit it. Each site is named separately so attribution is
 * exact rather than merged into one "reaped" total.
 */
enum class ReapSite : int {
    BACKPRESSURE = 0,        // reapOldestUrb via submitPcmToUrbs ring-full backpressure
    BARRIER_FEEDBACK = 1,    // drainAllUrbs: the feedback-discard reap loop
    BARRIER_PHASE3 = 2,      // drainAllUrbs: discard-then-reap-all-completions
    BARRIER_PHASE4 = 3,      // drainAllUrbs: flush remaining stale completions
    READ_FEEDBACK = 4,       // readFeedback: one-shot calibration reap at stream start
    COUNT = 5,
};

inline const char *reapSiteName(ReapSite s) {
    switch (s) {
        case ReapSite::BACKPRESSURE: return "BACKPRESSURE";
        case ReapSite::BARRIER_FEEDBACK: return "BARRIER_FEEDBACK_PHASE";
        case ReapSite::BARRIER_PHASE3: return "BARRIER_PHASE3";
        case ReapSite::BARRIER_PHASE4: return "BARRIER_PHASE4";
        case ReapSite::READ_FEEDBACK: return "READ_FEEDBACK";
        default: return "?";
    }
}

/**
 * Submission identity for one slot.
 *
 * `generation` advances on every successful submission of that slot, so (slot, generation) names an
 * exact submission even though the slot and its URB address are reused. A snapshot copies the
 * generation by value, so later reuse cannot rewrite what the snapshot recorded.
 *
 * LIFETIME INVARIANT: the cookie pointer is stable for the lifetime of the stream (it lives in the
 * context, not the URB), and it is never freed while a submission is in flight. The generation is
 * what changes, and it changes only at submit. A reaper therefore reads either this submission's
 * generation or a later one — never a dangling pointer — and a mismatch is reported as a stale
 * generation rather than silently accepted.
 */
struct SubmissionCookie {
    std::atomic<uint64_t> generation{0};
    int slot{-1};
    int endpoint{0};
};

/** One frozen barrier target, copied by value so the snapshot stays historically correct. */
struct BarrierTarget {
    uint64_t urbAddress{0};
    int slot{-1};
    uint64_t generation{0};
    int endpoint{0};
    bool resolved{false};
    int resolvedBy{-1};
    uint64_t reapSequence{0};
    int completionStatus{0};
    int discardResult{0};
    int discardErrno{0};
};

#define USB_AUDIO_MAX_BARRIER_TARGETS 160

/** Frozen target set for one barrier, plus the identity-match outcome. */
struct BarrierSnapshot {
    uint64_t id{0};
    bool valid{false};
    int count{0};
    BarrierTarget targets[USB_AUDIO_MAX_BARRIER_TARGETS];

    int resolvedCount{0};
    int perSiteUnique[(int)ReapSite::COUNT]{};
    long long nonTargetCompletions{0};
    int duplicates{0};
    int staleGeneration{0};
    int unknown{0};
    int wrongEndpoint{0};

    /** Exact targets consumed by the two suspected swallow sites, listed explicitly. */
    int swallowedCount{0};
    uint64_t swallowedSeq[16]{};
    int swallowedSite[16]{};
    int swallowedSlot[16]{};
    uint64_t swallowedGen[16]{};
};

/**
 * One slot in the pre-allocated URB ring buffer.
 * The URB struct and data buffer are allocated once at stream creation
 * and reused for the lifetime of the stream. This avoids all MTE
 * pointer tag issues since we never pass malloc'd pointers through
 * the kernel and back.
 */
struct UrbSlot {
    /**
     * The URB struct, heap-allocated with enough trailing space for
     * USB_AUDIO_PACKETS_PER_URB iso_frame_desc entries.
     */
    struct usbdevfs_urb *urb;

    /** Data buffer for PCM audio. Fixed size, pre-allocated. */
    uint8_t *buffer;

    /** Actual number of bytes written to buffer for current submission. */
    int dataLength;
};

/**
 * The ExoPlayer pipeline's pause / seek / skip barrier (B9), the counterpart of what the native engine
 * does in its decode thread (AUD-107/123/140): the ring is never cut. What is in it plays out, the real
 * continuation of the audio (the next buffer the writer has) is ramped to zero in 8 ms behind it, the
 * DAC is fed silence until the first buffer of the new position, and that buffer fades in over 15 ms.
 *
 * Only the thread that submits PCM touches this (the pipeline's writer, under the sink's stream lock),
 * exactly like the ring indices. The native engine does not use it.
 */
struct PipelineGesture {
    /** Real audio has been submitted on this stream: only then does a gesture have anything to fade. */
    bool hasAudio{false};
    /**
     * B12a-6: a pause request landed inside a block being written and the fade-out was started from that
     * point (see [submitPipelinePcm]). The writer reads and clears it with [pipelineConsumePauseApplied]; it
     * is how it learns the pause has been acted on although it never saw the block end.
     */
    bool pauseAppliedInBlock{false};
    /** The ring's last audio is a finished fade-out (or the silence fed after one): nothing left to fade. */
    bool endsInFade{false};

    /** The next writes are the continuation being ramped to zero. */
    bool fadeOutArmed{false};
    /** A pause keeps the rest of the block for its resume; a seek drops it. */
    bool fadeOutKeep{false};
    /** A pause's ramp frames are the same position's audio and count; a seek's belong to the one left. */
    bool fadeOutCountFrames{false};
    int64_t fadeOutDone{0};
    int64_t fadeOutTotal{0};

    /** Frames of fade-in still owed to the next audio, and the ramp's full length. */
    int64_t fadeInRemaining{0};
    int64_t fadeInTotal{0};

    /** What a pause kept of the block it faded out, in the DAC's format; replayed faded in on the resume. */
    uint8_t *pending{nullptr};
    size_t pendingCapacity{0};
    int pendingFrames{0};

    /** Zeros for the silence feed. */
    uint8_t *silence{nullptr};
    size_t silenceCapacity{0};

    // Counters, read by the diagnostics and the host simulation.
    int64_t silenceFramesFed{0};
    int64_t pauseFades{0};
    int64_t seekFades{0};
    int64_t fadeOutsCompleted{0};
};

/**
 * Aggregate state for one USB audio output stream.
 */
struct UsbAudioContext {
    int fd;
    int interfaceId;
    int endpointOut;
    int endpointFeedback;
    int32_t sampleRate;
    int32_t channelCount;
    int32_t bitDepth;
    /** xorshift32 seed for the 16-bit TPDF dither of the float path (AUD-055). */
    uint32_t ditherState;
    int32_t bytesPerSample;
    int32_t bytesPerFrame;
    int32_t maxPacketSize;

    std::atomic<bool> running;

    /** Scratch buffer for PCM format conversion (float -> int16/24/32). */
    uint8_t *transferBuffer;
    int32_t transferBufferCapacity;

    int64_t framesWritten;
    bool interfaceClaimed;

    // ── Ring buffer ─────────────────────────────────────────────
    /** Pre-allocated URB slots. Never freed during streaming. */
    UrbSlot ring[USB_AUDIO_NUM_URBS];

    // ── Submission identity / reap observation (diagnostics only) ───────
    /** Per-slot submission identity; `urb->usercontext` points at these. */
    SubmissionCookie cookies[USB_AUDIO_NUM_URBS];
    SubmissionCookie feedbackCookie;
    /** Globally monotonic across every reap site, so cross-thread order is reconstructable. */
    std::atomic<uint64_t> reapSeq{0};
    BarrierSnapshot barrier;
    std::mutex diagMutex;

    /** Index of next slot to fill and submit (wraps modulo NUM_URBS). */
    int submitIdx;

    /** Index of next slot to reap (wraps modulo NUM_URBS). */
    int reapIdx;

    /** Number of URBs currently submitted and not yet reaped. */
    int urbsInFlight;

    /**
     * A stopped engine queued its faded-out continuation (AUD-123): a teardown must let the ring
     * play out before discarding, or it cuts the fade itself. Cleared by the next engine's audio.
     */
    bool playOutBeforeDrain;

    /** The ExoPlayer pipeline's gesture barrier (B9). */
    PipelineGesture gesture;

    /**
     * B12a-6: the host paused (`pipelineRequestPause`, any thread). The writer looks at it between the URBs of
     * a block, so a pause is acted on within ~1 ms instead of after the block (up to ~190 ms of audio at
     * 44.1 kHz for the 8192-frame blocks of the FLAC decoder). Cleared when the pause is handled, on a seek and
     * on the resume. A relaxed flag: it carries no data, only "start the fade now".
     */
    std::atomic<bool> pauseRequested{false};

    /** Monotonic ms of the last data URB submitted; 0 before the first (AUD-124 RING_EMPTY). */
    int64_t lastSubmitMs;

    /**
     * B23-P2: the time model of what the ring still holds (see usb-glitch.h, "Output gaps"), and when the last
     * native write call returned (monotonic us; 0 before the first). Only the submitting thread touches them: the
     * model costs a few clock reads per URB and allocates nothing. It reads and counts; it never changes what is sent.
     */
    UsbOutputGapWatch outputGap;
    int64_t outputGapReturnUs;
    /** `playOutBeforeDrain` exempts only the first URB after it from the gap check (observeOutputGap). */
    bool outputGapPlayOutExempted;

    /**
     * B24-Q3: the writer thread's own scheduler counters, so an OUTPUT_GAP line can say whether the writer was asleep or runnable
     * without a CPU (usb-glitch.h, "The writer's own scheduler counters"). `schedstatFd` is /proc/thread-self/schedstat, opened by
     * the writer thread itself the first time it is needed and again if another thread takes over (`schedstatTid`); -1 = not
     * readable, and then the line says `runq=n/a`. Only the submitting thread touches these.
     */
    int schedstatFd;
    int schedstatTid;
    UsbSchedBaseline schedBaseline;

    /**
     * URB ownership accounting.
     *
     * The ring is a fixed array of [USB_AUDIO_NUM_URBS] slots, so the reusable population is
     * always `USB_AUDIO_NUM_URBS - urbsInFlight` — provided every submission is eventually
     * accounted for by a reap or a discard-and-reap. `USBDEVFS_DISCARDURB` only *requests*
     * cancellation: the URB stays kernel-owned until its completion is reaped, so a discard that
     * is never retired would silently shrink the pool. These counters exist to make that visible
     * rather than assumed.
     */
    std::atomic<uint64_t> urbSubmits;
    std::atomic<uint64_t> urbReaps;
    std::atomic<uint64_t> discardsRequested;
    std::atomic<uint64_t> discardsSucceeded;
    std::atomic<uint64_t> discardsFailed;
    /** URBs whose cancellation was requested and whose completion was subsequently reaped. */
    std::atomic<uint64_t> discardedThenReaped;
    /** Times the feedback URB was re-armed after a barrier cancelled it. */
    std::atomic<uint64_t> feedbackRestores;

    /** Whether ring buffers have been allocated. */
    bool ringAllocated;

    /** Fractional accumulator for sample-rate-to-packet-size conversion. */
    double frameAccumulator;

    /**
     * Frames per microframe, calibrated from the DAC's async feedback endpoint.
     * More accurate than the nominal sampleRate/8000.0 calculation because it
     * reflects the DAC's actual hardware clock frequency.
     * E.g., nominal 44100Hz = 5.5125 fpmf, but DAC may report 5.5127 (44101.6Hz).
     * Without calibration, the drift causes periodic pops as the DAC's internal
     * buffer underflows or overflows.
     */
    double calibratedFpmf;

    /**
     * Residual buffer for bytes that didn't align to a complete frame
     * at the end of a write() call. Prepended to the next call's data
     * to avoid micro-discontinuities (pops) at buffer boundaries.
     * Holds up to one URB that could not be filled yet; sized for the widest (audit 2: a 768 kHz
     * leftover of up to 6 KiB did not fit the old 4 KiB and was dropped).
     */
    uint8_t residualBuffer[USB_PCM_MAX_URB_BYTES];
    int residualBytes;

    // ── Continuous feedback ─────────────────────────────────────
    /**
     * Dedicated URB for the async feedback endpoint. Submitted at start
     * and continuously recycled during streaming. When the reap loop
     * gets this URB instead of an audio URB, it updates calibratedFpmf
     * and resubmits — tracking the DAC's actual clock in real-time.
     * Effective polling interval is ~1 ms (the host controller schedules
     * the feedback endpoint once per microframe).
     */
    struct usbdevfs_urb *feedbackUrb;
    /**
     * B27-A2: the URB's four data bytes are behind it, in the same heap block (`feedbackData()`), not in this context: the
     * kernel writes them when the URB is reaped, which may be after the context is gone. A URB that is cancelled and does not
     * come back is parked in the quarantine (urb-quarantine.h) and replaced by a fresh block; `feedbackInFlight` says whether
     * the block now in `feedbackUrb` is submitted, never that an earlier one stopped mattering.
     */
    bool feedbackInFlight;

    /**
     * B10: whether the DAC's feedback is stuck (the same off-nominal reading for seconds), and the healthy
     * average before it. Fed by every applied reading, on the thread that reaps the feedback endpoint.
     */
    UsbFeedbackWatch feedbackWatch;
};

// ── Functions accessible from native-audio-engine ──────────────────

/**
 * Submit PCM data (already in the target bit depth) to the USB pipeline.
 * Blocks when the URB ring is full (natural backpressure from DAC clock).
 * @return false when the stream stopped (submit or reap failure, detach, stop): the PCM was not
 *         all queued and nothing more will be (AUD-013).
 */
bool submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes);

/**
 * Frames queued to the DAC and not yet played: every completion already available is retired
 * first, so a completed-but-unreaped URB (already heard) is not counted. Submitting thread only.
 */
int64_t usbQueuedFrames(UsbAudioContext *ctx);

/** Waits (briefly) for the oldest queued URB to be played. False when nothing is queued. */
bool usbWaitForProgress(UsbAudioContext *ctx);

/** Submit valid frame-aligned residual PCM exactly once at natural EOS. */
bool submitFinalPcmResidual(UsbAudioContext *ctx);

/**
 * AUD-145: how the writer waits for a URB completion between two reap attempts. true (the default):
 * poll(2) on the usbfs fd; false: the old 125 µs sleep. A kill switch for the measurement.
 */
void usbSetPollWait(bool enabled);

/** [mode (1 = poll), waits entered, µs spent waiting, waits that timed out, polls that found nothing to reap]. */
void usbWaitStats(uint64_t out[5]);

/**
 * B10-2: what the output does with the DAC's feedback, a UsbFeedbackPolicy (usb-glitch.h): auto (the default
 * since B12a), follow, nominal or clamp. Process-wide and never persisted; an unknown value changes nothing.
 * Takes effect at the next write; the packetizer's accumulator carries on, so there is no jump.
 */
void usbSetFeedbackPolicy(int policy);

/** The policy in force. */
int usbFeedbackPolicy();

#define USB_FEEDBACK_STATS_COUNT 15

/**
 * Process totals for the capture and the debug reply. Fpmf values are millionths of a frame per
 * microframe: [policy, stuck now (0/1), episodes, readings seen while stuck, ms stuck in all (the open
 * episode included), longest episode ms, the last episode's fpmf, its ppm, readings in all (every feedback
 * completion), the last applied reading's fpmf, the stream's nominal rate Hz, the fpmf the packetizer was
 * last given, B12a-2: readings not obeyed (out of window, or implausible under auto), streaks of implausible
 * readings, the last one's fpmf].
 */
void usbFeedbackStats(int64_t out[USB_FEEDBACK_STATS_COUNT]);

/**
 * B27-A5 (review N-07): the stream's last [windowMs] (default 30 s, at most USB_SECOND_RING_CAPACITY s) in one-second summaries,
 * as text, preceded by a line with the stream's facts (rate, channels, slot bits, endpoints, feedback policy). Min / max / mean
 * of the feedback readings, how many were off nominal or refused, and the ISO packets' statuses, a line per second that streamed.
 * Called on demand, from any thread; the recording costs nothing but a few compares on the thread that reaps (usb-second-ring.h).
 * Returns the bytes written.
 */
size_t usbSecondSummaries(int64_t windowMs, char *out, size_t capacity);

/** What a seek asks of the pipeline's writer. */
enum PipelineSeekPlan {
    /** Nothing audible is on its way to the DAC: nothing to fade, no silence to feed. */
    PIPELINE_SEEK_NONE = 0,
    /** Write the old position's continuation (the ramp is armed), then feed silence. */
    PIPELINE_SEEK_FADE = 1,
    /** The ring already ends in a fade (a pause, or an earlier seek): only feed silence. */
    PIPELINE_SEEK_SILENCE = 2,
};

/**
 * Submits one buffer of the pipeline, already in the DAC's format, applying what a gesture owes it: the
 * continuation's fade-out (only the ramp's frames are submitted; a pause keeps the rest, a seek drops
 * it) or the fade-in after a cut. With nothing owed it is the plain submit it always was, byte for byte.
 * [data] is modified in place. Writer thread only.
 */
bool submitPipelinePcm(UsbAudioContext *ctx, uint8_t *data, int totalBytes, int totalFrames);

/**
 * B12a-6: tells the writer, from any thread, that a pause has been asked for. Takes effect between two URBs of
 * the block being written, if any (never before the first audio of the stream, never inside a fade-in).
 */
void pipelineRequestPause(UsbAudioContext *ctx);

/** The host resumed (or the pause was handled some other way): a request not yet acted on is dropped. */
void pipelineCancelPauseRequest(UsbAudioContext *ctx);

/**
 * True once (and clears it) when a pause was acted on inside a block: the fade-out was started from the
 * point the request reached the writer, and the rest of that block is kept for the resume. Writer thread only.
 */
bool pipelineConsumePauseApplied(UsbAudioContext *ctx);

/**
 * A pause: arms the fade-out of the continuation, unless the ring has nothing audible left or already
 * ends in a fade. True when the writer must now write continuation buffers until [pipelineFadingOut]
 * is false (or none are left: [pipelineEndFadeOut]). Writer thread only.
 */
bool pipelineBeginPause(UsbAudioContext *ctx);

/**
 * A seek or a skip: decides from what is still unplayed (`seekCutFor`, as the engine does), drops what a
 * pause kept, and arms the fade-in of the first audio at the new position. Returns a [PipelineSeekPlan].
 * Repeated seeks coalesce: a ring that already ends in a fade asks for silence only. Writer thread only.
 */
int pipelineBeginSeek(UsbAudioContext *ctx);

/** True while a continuation fade-out is armed and incomplete. */
bool pipelineFadingOut(const UsbAudioContext *ctx);

/** No more continuation to ramp (the queue ran out): the ring ends in whatever was ramped. */
void pipelineEndFadeOut(UsbAudioContext *ctx);

/** Leaving a pause: replays what the pause kept, faded in, and arms the fade-in for what follows. */
void pipelineResume(UsbAudioContext *ctx);

/**
 * One step of the silence feed that keeps the DAC's ring from running dry until the first buffer of the
 * new position (`seekFeedSilenceStep`, the engine's own loop body). False when the stream failed.
 */
bool pipelineFeedSilence(UsbAudioContext *ctx);

/**
 * [hasAudio, endsInFade, fadingOut, pendingFrames, fadeInRemaining, silenceFramesFed, pauseFades,
 * seekFades, fadeOutsCompleted].
 */
void pipelineGestureStats(const UsbAudioContext *ctx, int64_t out[9]);

/**
 * Discard every queued PCM URB, so no pre-seek audio is still waiting to be played.
 *
 * The seek barrier. Cancels the in-flight transfers outright rather than waiting for
 * them to complete — a natural completion means that millisecond of audio reached the
 * DAC — then resets the packetisation phase and the partial frame, and restores the
 * feedback URB that the drain also cancelled.
 *
 * Keeps the stream alive: `running`, the interface claim and the device fd are all
 * untouched, so a seek never costs the USB_DIRECT route or a claim/release cycle.
 *
 * Must be called from the thread that submits PCM (the native decode thread in
 * PRISTINE), because it mutates the same submit/reap indices that `submitPcmToUrbs`
 * advances and there is no lock between them.
 */
void discardQueuedPcm(UsbAudioContext *ctx);

// padInt16ToInt32 / padInt24ToInt32 / shiftInt32From24 are declared in usb-pcm-convert.h.

// Debug-validation observer. The only production call-site is a disabled
// branch after a successful USBDEVFS_SUBMITURB. Activation is exposed solely
// through PixelPlayer's debug-only shell receiver.
bool startUsbPayloadValidation(const char *manifestPath, const char *label, uint64_t expectedBytes);
const char *stopUsbPayloadValidation();
const char *getUsbPayloadValidationStatus();
void observeSubmittedUsbPayload(const UsbAudioContext *ctx,
                                const uint8_t *payload,
                                int payloadBytes,
                                int packetCount);
