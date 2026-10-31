#pragma once

#include <stdint.h>

#include "urb-quarantine.h"

/**
 * Isochronous packets in one URB. The one definition: the packetizer, the URB struct size, the slot size and the plan
 * all read it (B46-A1; the writer had a second, separate 8).
 */
#define USB_PCM_PACKETS_PER_URB 8
/** The slot every ring buffer had before audit 2; still the size up to 384 kHz stereo 32-bit. */
#define USB_PCM_MIN_URB_SLOT_BYTES 4096
/** Largest URB a high-speed endpoint can take: 8 microframes x 3 x 1024 bytes. */
#define USB_PCM_MAX_URB_BYTES (USB_PCM_PACKETS_PER_URB * 3 * 1024)

/** Pure packet plan used by normal streaming and the natural-EOS drain. */
struct UsbPcmUrbPlan {
    int packetSizes[USB_PCM_PACKETS_PER_URB];
    int packetCount;
    int totalBytes;
    double nextFrameAccumulator;
    bool consumesAllAvailable;
};

/**
 * Plan one AudioStreaming URB without mutating stream state.
 *
 * Normal streaming returns a plan only when all eight microframe packets can
 * be filled. At natural EOS, a final short packet/URB may consume the remaining
 * complete source frames. No bytes are padded or invented.
 */
UsbPcmUrbPlan planUsbPcmUrb(int availableBytes,
                            int bytesPerFrame,
                            int maxPacketSize,
                            double framesPerMicroframe,
                            double frameAccumulator,
                            bool naturalEndOfStream);

/**
 * Bytes one URB slot of the ring must hold for an endpoint of [maxPacketSize]: a plan never exceeds
 * USB_PCM_PACKETS_PER_URB packets of maxPacketSize.
 */
int usbUrbSlotBytes(int maxPacketSize);

// ── The transport plan (B46 phase A) ────────────────────────────────────────────────────────────
//
// Every number the USB transport needs, derived in one place from the format, the endpoint and a policy. It is a pure function:
// no usbfs, no DAC, no allocation of the ring, no clock. The writer (usb-audio-output.cpp) allocates and paces from it, Kotlin
// asks the same function through JNI (UsbTransportPlanner) to preflight a format, and the host tests pin it to the numbers the
// code used before it existed (tools/usb-transport-plan-test.cpp). The feedback policy (usb-glitch.h) still decides the live
// rate; the plan gives it units and limits, it does not replace it, and the fractional phase stays in planUsbPcmUrb's accumulator.

/**
 * The ring the writer allocates: the plan's `ringUrbs` and the capacity of the context's fixed arrays. 128 URBs of 1 ms = 128 ms of wire time.
 *
 * B47-A2: it was 80. On 2026-10-29 17:32:33 the writer (nice -19, SCHED_OTHER) was runnable and not running for 82 ms (`OUTPUT_GAP gap=83 ms
 * lead=80 ms runq=82 ms cpu=0 ms`), 2 ms more than the ring held, and the DAC got nothing for ~3 ms. The lever is more URBs of the same
 * shape, not more packets per URB: a URB stays 8 microframes (1 ms), so the kernel, the packetizer, the slot size and every URB payload
 * byte are exactly what they were, and the extra depth is only more of them in flight. 128 ms survives a stall of 100 ms (the worst seen
 * plus 22 %) with 28 ms to spare and one of 120 ms with 8. The price is latency (a pause, seek or skip is heard after the ring's lead:
 * +48 ms) and 48 more slots of memory (~300 KB at the Melody's 6 208 B slot).
 */
#define USB_TRANSPORT_RING_URBS 128
/**
 * Producer queue limits, in time (B46-B2). Until B45 they counted Media3 buffers, 16 and 128, whose length is a FLAC block: with 4096-frame
 * buffers 16 of them are 1.5 s at 44.1 kHz and 0.17 s at 384 kHz (nine times less headroom exactly where the data rate is highest). A
 * duration is the same headroom at every rate. 44.1 kHz with 4096-frame buffers is the reference, so the Melody at 44.1 and 48 kHz queues the
 * same number of buffers as before to within one:
 *  - soft, 1440 ms: the sink tells Media3 to retry while the queue holds this much. 16 x 4096 frames = 1486 ms at 44.1 kHz; 1440 ms is
 *    15.5 such buffers at 44.1 kHz and 16.9 at 48 kHz, so the limit sits at 16 and 17 buffers there (it was 16).
 *  - hard, 11 900 ms: the queue's capacity, 128 x 4096 frames = 11 889 ms at 44.1 kHz. Above it the oldest buffers are dropped, as they were
 *    when the 128th buffer was full. Only producers that ignore the soft limit (the pre-roll's 1 s of silence, a seek's continuation) get near it.
 * Memory is bounded by it: 11.9 s x 384 000 frames/s x 8 bytes (stereo, 32 bit) = 36.5 MB at the highest rate the output carries, plus the
 * one buffer in hand and the one just queued.
 */
#define USB_TRANSPORT_QUEUE_SOFT_MS 1440
#define USB_TRANSPORT_QUEUE_HARD_MS 11900
/**
 * The lead the writer keeps while it feeds silence until the first buffer of a new position (a skip, a seek, a rebuild).
 * B46-B1: 60 ms, up from 40 (phase A kept 40). With 40 ms the host simulation shows the bus really running dry when the writer is
 * not running for 40 ms or more (a stall of 60 ms: 21 ms dry at 44.1, 96 and 192 kHz), and the lead dips to 9 ms at a stall of 30 ms;
 * the owner's session had stalls of 3-11 ms (runq up to 12 ms), so 60 ms is five times the worst. Not the whole ring: that is the
 * back-pressure limit, where the feed would wait on the ring instead of on its own lead.
 *
 * B47-A2: 110 ms, with the ring at 128. The same reason, with the worst stall now 82 ms (17:32:33, steady playback): 110 ms leaves 28 ms
 * after a stall of 82 and 10 ms after one of 100, right after a skip too. The cost is on the first audio after a skip: it waits behind
 * up to 110 ms of silence instead of 60 (+50 ms, at every rate).
 */
#define USB_TRANSPORT_SILENCE_LEAD_MS 110
/**
 * B47-A3: the lead a resume builds before real audio follows. After a long pause the ring is empty (the DAC played the pause's fade and
 * the silence behind it out), and on 2026-10-29 17:25:19 (and 2026-10-29 14:46:49 in B45) the first URB of a resume went out alone and the next
 * one 7 ms later: `RING_EMPTY` + `OUTPUT_GAP dry=6 ms inFlight=1`. Silence is submitted first up to this lead, so a late wake-up right
 * after the resume (7 ms seen) finds the ring holding 50 ms. The cost is +50 ms between the press of Play and the first sample, only
 * when the ring has played out; a resume that finds the pause's tail still queued adds only the missing part.
 */
#define USB_TRANSPORT_RESUME_LEAD_MS 50
/**
 * Optional 500 ms guard from the first URB after a stream/recovery start; `follow` bypasses it.
 * B48-fix2 leaves it off by default: the train starts under the guard reached +4.4 % and then beyond +5 %.
 * Its benefit is unproven; keep the debug switch for A/B. Pauses and cached skips never arm it.
 */
#define USB_TRANSPORT_FEEDBACK_START_GUARD_MS 500
/** Feed silence after the pause fade, then announce idle. Resumes and stream changes end it earlier. */
#define USB_TRANSPORT_PAUSE_KEEPALIVE_MS 2000
/** Seek / pause ramps: the continuation is ramped to zero in 8 ms, the first audio after a cut fades in over 15. */
#define USB_TRANSPORT_FADE_OUT_MS 8
#define USB_TRANSPORT_FADE_IN_MS 15
/** A rate-change pre-roll is queued in blocks of at most this many frames. */
#define USB_TRANSPORT_PREROLL_BLOCK_FRAMES 8192
/** Service intervals per second of the only timing the output drives: 125 us microframes (high speed and faster). */
#define USB_TRANSPORT_INTERVALS_PER_SECOND 8000
#define USB_TRANSPORT_SERVICE_INTERVAL_US 125
/** The feedback's plausibility window, in permille of the nominal rate (usb-glitch.h: +-5 %). */
#define USB_TRANSPORT_FEEDBACK_CEILING_PERMILLE 50
/** The feedback packet the driver reads: one Q16.16 value of four bytes. */
#define USB_TRANSPORT_FEEDBACK_BYTES 4

// The ring's bookkeeping has to hold a full ring and the feedback URB: a barrier freezes every URB in flight as a target, parks the
// ones that do not come back in the quarantine, and waits for them in a drain set.
static_assert(USB_TRANSPORT_RING_URBS + 1 <= USB_AUDIO_MAX_BARRIER_TARGETS, "the barrier cannot freeze a full ring");
static_assert(USB_TRANSPORT_RING_URBS + 1 <= URB_DRAIN_MAX, "the drain set cannot hold a full ring");
static_assert(USB_TRANSPORT_RING_URBS + 1 <= URB_QUARANTINE_CAPACITY, "the quarantine cannot park a full ring");

/** usbfs / `enum usb_device_speed` (what USBDEVFS_GET_SPEED returns). */
enum UsbTransportSpeed {
    USB_TRANSPORT_SPEED_UNKNOWN = 0,
    USB_TRANSPORT_SPEED_LOW = 1,
    USB_TRANSPORT_SPEED_FULL = 2,
    USB_TRANSPORT_SPEED_HIGH = 3,
    USB_TRANSPORT_SPEED_WIRELESS = 4,
    USB_TRANSPORT_SPEED_SUPER = 5,
    USB_TRANSPORT_SPEED_SUPER_PLUS = 6,
};

struct UsbTransportFormat {
    int sampleRate;       // Hz
    int channels;         // channels of the transport stream
    int containerBytes;   // bytes per sample on the wire (bSubslotSize): 2, 3 or 4
    int sourceBits;       // significant bits of the source; 0 = no constraint (a float / processed source fits any container)
};

struct UsbTransportEndpoint {
    int speed;            // UsbTransportSpeed
    int maxPacketBytes;   // wMaxPacketSize with its high-bandwidth multiplier decoded: bytes per service interval
    int bInterval;        // of the data endpoint; 1 = a packet every service interval; 0 = not reported
    bool hasFeedback;     // asynchronous: a feedback endpoint answers
    int feedbackBytes;    // size of the feedback packet: 4 (Q16.16), 3 (full speed, 10.14); 0 = not reported
};

struct UsbTransportPolicy {
    int ringUrbs;
    int queueSoftMs;
    int queueHardMs;
    int silenceLeadMs;
    int resumeLeadMs;            // B47-A3
    int feedbackStartGuardMs;    // B48-fix1: nominal-rate packets for this long after the interface (re)starts
    int pauseKeepaliveMs;        // B48-fix2
    int fadeOutMs;
    int fadeInMs;
    int prerollMs;               // requested rate-change pre-roll; 0 = none
    int prerollBlockFrames;
    int feedbackCeilingPermille;
    /**
     * What to do with a device whose timing the output does not drive yet (full speed, bInterval != 1, a 3-byte feedback).
     * false (the only value B46 uses): plan it with the high-speed numbers the code has always used, and say so in
     * `assumptions`. true: Unsupported with the precise reason; the step that adds those timings turns it on.
     */
    bool refuseUnsupportedTiming;
};

/** The policy the output runs with today. */
UsbTransportPolicy usbTransportDefaultPolicy();

enum UsbTransportStatus {
    USB_PLAN_OK = 0,
    USB_PLAN_UNSUPPORTED = 1,
};

enum UsbTransportReason {
    USB_PLAN_REASON_NONE = 0,
    USB_PLAN_REASON_INVALID = 1,             // a non-positive rate / channel count / packet size, a container that is not 2..4 bytes, a bad policy
    USB_PLAN_REASON_CONTAINER_NARROWER = 2,  // the container cannot hold the source without narrowing it
    USB_PLAN_REASON_PACKET_OVER_MAX = 3,     // the widest nominal packet does not fit the endpoint's maxPacket
    USB_PLAN_REASON_RING_TOO_LARGE = 4,      // the policy asks for more URBs than the context's arrays hold
    USB_PLAN_REASON_FULL_SPEED = 5,          // not supported yet: a service interval that is not 125 us
    USB_PLAN_REASON_BINTERVAL = 6,           // not supported yet: bInterval != 1
    USB_PLAN_REASON_FEEDBACK_WIDTH = 7,      // not supported yet: a feedback packet that is not four bytes
};

/** Bits of UsbTransportPlan::assumptions: the timing the plan was made with is not the device's. */
#define USB_PLAN_ASSUME_SERVICE_INTERVAL 1  // low / full / wireless speed planned as 125 us microframes
#define USB_PLAN_ASSUME_BINTERVAL 2         // bInterval != 1 planned as every microframe
#define USB_PLAN_ASSUME_FEEDBACK_WIDTH 4    // a feedback packet of another size read as four bytes (Q16.16)
#define USB_PLAN_ASSUME_SPEED_UNKNOWN 8     // the bus speed could not be read: planned as high speed

struct UsbTransportPlan {
    int status;                      // UsbTransportStatus; every other field is meaningful only when OK
    int reason;                      // UsbTransportReason when UNSUPPORTED
    int assumptions;                 // USB_PLAN_ASSUME_* bits

    // Time base.
    int serviceIntervalUs;           // 125
    int intervalsPerSecond;          // 8000
    int nominalFramesNum;            // nominal frames per service interval, exact: num / den (the sample rate over the interval rate)
    int nominalFramesDen;
    double nominalFramesPerInterval; // the same as a double: what the output starts its feedback calibration at

    // Packets.
    int bytesPerFrame;
    int maxFramesPerPacket;          // ceil(nominal): the widest packet the nominal rate makes
    int maxPacketBytes;              // that, in bytes: what the preflight compares with the endpoint's maxPacket
    int maxFramesPerPacketAtCeiling; // ceil(nominal x (1 + feedback ceiling)); reported, not enforced (see the deliverable)
    int maxPacketBytesAtCeiling;
    int packetsPerUrb;
    int urbMaxBytes;                 // packetsPerUrb x the endpoint's maxPacket
    int urbSlotBytes;                // one ring slot
    int residualCapBytes;            // the leftover the writer may hold between two calls

    // Ring.
    int ringUrbs;
    int backpressureUrbs;            // in flight when the writer waits for a completion
    int lowRingUrbs;                 // below it the writer checks for a starved ring

    // Producer queue, in frames of the stream's rate (B46-B2).
    int64_t queueSoftFrames;
    int64_t queueHardFrames;

    // Silence and transitions.
    int silenceLeadFrames;
    int silenceChunkFrames;
    int resumeLeadFrames;            // B47-A3: the silence a resume submits first when the ring has played out; B48-fix1: and every start of audio
    int fadeOutFrames;
    int fadeInFrames;

    // Rate-change pre-roll.
    int64_t prerollFrames;
    int prerollBlockFrames;
    int prerollBlocks;

    // Feedback.
    int feedbackBytes;               // 0 without a feedback endpoint
    int feedbackStartGuardMs;        // B48-fix1: the start guard's length (0 = none)
    int pauseKeepaliveMs;            // B48-fix2
};

/**
 * Plan [format] on [endpoint] under [policy]. Pure. Unsupported carries the reason; a refusal the output has always made
 * (invalid, container narrower, packet over maxPacket, ring too large) is always Unsupported. The timings the output does not
 * drive yet (full speed, bInterval != 1, a 3-byte feedback) are Unsupported only when the policy refuses them; otherwise they
 * plan as high speed, exactly as the output always did, and set `assumptions`.
 */
UsbTransportPlan makeUsbTransportPlan(const UsbTransportFormat &format,
                                      const UsbTransportEndpoint &endpoint,
                                      const UsbTransportPolicy &policy);

const char *usbTransportReasonName(int reason);

/**
 * B48-fix1: the silence a start submits to bring a ring that holds [queuedFrames] up to [leadFrames], in the whole [chunkFrames] steps the silence feed uses: 0 when the ring
 * already holds the lead (whatever it holds counts), else the smallest number of whole chunks that reaches it. For an empty ring that is the exact amount of priming
 * silence the tests pin.
 */
static inline int64_t usbSilenceFramesToReach(int64_t leadFrames, int64_t chunkFrames, int64_t queuedFrames) {
    if (leadFrames <= 0 || chunkFrames <= 0 || queuedFrames >= leadFrames) return 0;
    return ((leadFrames - queuedFrames + chunkFrames - 1) / chunkFrames) * chunkFrames;
}

/** `rate x ms / 1000` in whole frames, as the output has always computed its fade, lead and pre-roll lengths. */
static inline int64_t usbFramesForMs(int sampleRate, int ms) {
    if (sampleRate <= 0 || ms <= 0) return 0;
    return (int64_t)sampleRate * ms / 1000;
}

/** The plan as a flat array of int64 (JNI, logs): indexes below. */
enum UsbPlanField {
    USB_PLAN_FIELD_STATUS = 0,
    USB_PLAN_FIELD_REASON,
    USB_PLAN_FIELD_ASSUMPTIONS,
    USB_PLAN_FIELD_SERVICE_INTERVAL_US,
    USB_PLAN_FIELD_INTERVALS_PER_SECOND,
    USB_PLAN_FIELD_NOMINAL_NUM,
    USB_PLAN_FIELD_NOMINAL_DEN,
    USB_PLAN_FIELD_BYTES_PER_FRAME,
    USB_PLAN_FIELD_MAX_FRAMES_PER_PACKET,
    USB_PLAN_FIELD_MAX_PACKET_BYTES,
    USB_PLAN_FIELD_MAX_FRAMES_AT_CEILING,
    USB_PLAN_FIELD_MAX_PACKET_BYTES_AT_CEILING,
    USB_PLAN_FIELD_PACKETS_PER_URB,
    USB_PLAN_FIELD_URB_MAX_BYTES,
    USB_PLAN_FIELD_URB_SLOT_BYTES,
    USB_PLAN_FIELD_RESIDUAL_CAP_BYTES,
    USB_PLAN_FIELD_RING_URBS,
    USB_PLAN_FIELD_BACKPRESSURE_URBS,
    USB_PLAN_FIELD_LOW_RING_URBS,
    USB_PLAN_FIELD_QUEUE_SOFT_FRAMES,
    USB_PLAN_FIELD_QUEUE_HARD_FRAMES,
    USB_PLAN_FIELD_SILENCE_LEAD_FRAMES,
    USB_PLAN_FIELD_SILENCE_CHUNK_FRAMES,
    USB_PLAN_FIELD_FADE_OUT_FRAMES,
    USB_PLAN_FIELD_FADE_IN_FRAMES,
    USB_PLAN_FIELD_PREROLL_FRAMES,
    USB_PLAN_FIELD_PREROLL_BLOCK_FRAMES,
    USB_PLAN_FIELD_PREROLL_BLOCKS,
    USB_PLAN_FIELD_FEEDBACK_BYTES,
    USB_PLAN_FIELD_RESUME_LEAD_FRAMES,   // B47-A3, appended: the indexes before it did not move
    USB_PLAN_FIELD_FEEDBACK_START_GUARD_MS,   // B48-fix1, appended
    USB_PLAN_FIELD_PAUSE_KEEPALIVE_MS,   // B48-fix2, appended
    USB_PLAN_FIELD_COUNT,
};

void usbTransportPlanToArray(const UsbTransportPlan &plan, int64_t out[USB_PLAN_FIELD_COUNT]);

/** One-line text of the plan's numbers for the stream-start log (assumptions included). */
void usbTransportPlanDescribe(const UsbTransportPlan &plan, char *out, int capacity);
