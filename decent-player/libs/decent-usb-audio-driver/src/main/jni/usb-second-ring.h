// One-second summaries of the USB stream, kept in memory for the next time something sounds wrong (B27-A5, review N-07).
//
// 2026-10-16 16:41, 96 kHz, Purity: ~2 s of static, `FEEDBACK_DEVIATION` 26 times, nothing else in the log. The log samples
// the feedback; it does not say what it did second by second, and the one place that sees every reading is the reap loop.
// This keeps, for each second that streamed, the feedback readings' min / max / mean (frames per microframe), how many of
// them were more than 0.2 % off nominal, how many were refused, and the ISO completions' packet statuses. Nothing is logged
// per second: the last [USB_SECOND_RING_CAPACITY] seconds sit in a fixed array until somebody asks for them.
//
// B41-fix1 (A1c) adds what the 2026-10-25 episodes could not show. A reading the policy does not obey under `auto` was counted
// nowhere unless it was beyond +-5 %, so a +0.8 % plateau that `auto` answered with the nominal rate read `refused=0`; a feedback
// transfer that came back with an error was not counted at all (the audio completions are the only ones that went through
// `usbSecondRingIso`); and what the packetizer was given, and what actually went on the wire, was nowhere. So, per second:
//  - `held`: applied readings the policy did not obey (stuck or implausible, under `auto`);
//  - the feedback transfers that were not a reading, by what was wrong with them (the URB's status, its packet's, its length),
//    the codes of the last one, and how long ago the last valid reading was at the end of the second;
//  - the rate the packetizer was given (min / max over the blocks of PCM) and the rate that went out (frames / packets of the
//    audio URBs submitted) next to the received one;
// and, apart from the seconds, the start and end of each change of what the policy obeys, with the stream's identity.
//
// What it costs: a few compares and adds per feedback reading, per audio URB submitted and per audio URB completion on the thread
// that already does them (one clock read each; no allocation, no I/O), and once a second one mutex and a copy of ~200 bytes. The
// policy events (a few a minute at the most) take the mutex too. Nothing on the PCM path: nothing is read from, or written to, a
// sample, a packet plan or a URB.
#ifndef USB_SECOND_RING_H
#define USB_SECOND_RING_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define USB_SECOND_RING_CAPACITY 128
#define USB_POLICY_EVENT_CAPACITY 64

/** One wall-clock second of the stream. */
typedef struct {
    int64_t second;          /* monotonic ms / 1000 */
    int32_t readings;        /* feedback readings applied this second */
    int32_t refused;         /* readings beyond the plausible window, never applied */
    int32_t deviating;       /* applied readings more than 0.2 % away from nominal (the ones FEEDBACK_DEVIATION counts) */
    double fpmfMin, fpmfMax, fpmfSum;
    double nominalFpmf;
    int32_t isoUrbs;         /* audio URB completions */
    int32_t isoPackets;      /* ... and their packets */
    int32_t isoPacketErrors; /* packets whose status was not 0 */
    int32_t isoShortPackets; /* packets that delivered fewer bytes than they carried */
    /* B41-fix1 */
    int32_t held;            /* applied readings the policy did not obey this second (`auto`: stuck or implausible) */
    int32_t dropUrb;         /* feedback transfers that were not a reading: the URB's status was an error ... */
    int32_t dropPacket;      /* ... its packet's status was ... */
    int32_t dropLength;      /* ... or both were clean and the length was not the one asked for */
    int32_t lastUrbStatus, lastPacketStatus;  /* the codes of the last such transfer this second (0 when there was none) */
    int32_t lastLength;
    int64_t ageMs;           /* at the end of the second: ms since the last valid reading, -1 if the stream has had none */
    int32_t sentCalls;       /* times the packetizer was given a rate (once per block of PCM, more when the policy turned mid-block) */
    double sentMin, sentMax; /* ... and the rate it was given, frames per microframe */
    int64_t wireFrames;      /* frames of the audio URBs submitted this second ... */
    int64_t wirePackets;     /* ... and their packets: frames / packets is the rate that went out */
} UsbSecondSample;

/**
 * What changed in what the policy obeys, and the gestures of the stream around it. The gestures (start, pause, idle, seek, resume) are
 * here so that a mark carries, in one place, what the feedback did and what the stream was told just before (the 2026-10-25 ramps began
 * about 230 ms after a resume): the log has them, the mark did not.
 */
enum UsbPolicyEventKind {
    USB_POLICY_REFUSE_BEGIN = 0,  /* readings the policy will not obey began (absurd, sustained, out of window) */
    USB_POLICY_REFUSE_END = 1,    /* a reading it obeys again */
    USB_POLICY_STUCK_BEGIN = 2,   /* the same off-nominal reading for USB_FEEDBACK_STUCK_MIN_MS */
    USB_POLICY_STUCK_END = 3,
    USB_POLICY_STREAM_START = 4,  /* the stream was started (its first feedback read follows) */
    USB_POLICY_PAUSE = 5,         /* a pause began (the fade-out) */
    USB_POLICY_IDLE = 6,          /* the writer announced it would idle (a pause, a seek while paused) */
    USB_POLICY_SEEK = 7,          /* a seek began */
    USB_POLICY_RESUME = 8,        /* a resume */
    USB_POLICY_DAC_LOCKED = 9,    /* B47-A5 / B48-fix4: the rail or stuck watch fired; what says which */
    USB_POLICY_DAC_RECOVERY = 10, /* B47-A5: a recovery step ran (what = "step1 ok", "step2 failed", "gave-up", ...) */
    USB_POLICY_EVENT_KIND_COUNT = 11,
};

typedef struct {
    int64_t atMs;            /* monotonic ms */
    int64_t stream;          /* the stream's sequence number (usb-audio-output.cpp numbers them as they are created) */
    int32_t rate;            /* its nominal rate, Hz */
    enum UsbPolicyEventKind kind;
    const char *what;        /* a static string: the reason (absurd, sustained, out-of-window; changed, gap, stream-end), or what a gesture planned */
    double fpmf;             /* the reading that began or ended it */
    double ppm;              /* ... its offset from nominal */
    double sentBefore, sentAfter;  /* what the packetizer was being given, and what it is given from now on */
    int32_t policy;          /* the UsbFeedbackPolicy in force */
} UsbPolicyEvent;

typedef struct {
    UsbSecondSample current;
    bool hasCurrent;
    UsbSecondSample ring[USB_SECOND_RING_CAPACITY];
    int head;                /* next slot to write */
    int count;
    int64_t lastValidMs;     /* when the last valid feedback reading arrived; 0 = none yet */
    UsbPolicyEvent events[USB_POLICY_EVENT_CAPACITY];
    int eventHead;
    int eventCount;
} UsbSecondRing;

void usbSecondRingInit(UsbSecondRing *r);

/** One applied feedback reading at [nowMs]. */
void usbSecondRingFeedback(UsbSecondRing *r, int64_t nowMs, double fpmf, double nominalFpmf, bool deviating);

/** One reading refused as implausible at [nowMs]. */
void usbSecondRingRefused(UsbSecondRing *r, int64_t nowMs);

/** B41-fix1: the reading just recorded was one the policy does not obey. */
void usbSecondRingHeld(UsbSecondRing *r, int64_t nowMs);

/**
 * B41-fix1: a feedback transfer that was not a reading. [kind] is a UsbFeedbackPacket (usb-glitch.h: 1 URB status, 2 packet
 * status, 3 length); the three codes are what the kernel reported. The stream starts a new age for the last valid reading only
 * with a valid one.
 */
void usbSecondRingFeedbackDropped(UsbSecondRing *r, int64_t nowMs, int kind, int urbStatus, int packetStatus, unsigned int length);

/** B41-fix1: the packetizer was given [fpmf] frames per microframe for the next block of PCM. */
void usbSecondRingSent(UsbSecondRing *r, int64_t nowMs, double fpmf);

/** B41-fix1: an audio URB of [frames] frames in [packets] packets was submitted. */
void usbSecondRingWire(UsbSecondRing *r, int64_t nowMs, int frames, int packets);

/** One audio URB completion at [nowMs]: its packets, how many had a status error, how many were short. */
void usbSecondRingIso(UsbSecondRing *r, int64_t nowMs, int packets, int packetErrors, int shortPackets);

/** B41-fix1: records a change of what the policy obeys (takes the lock: these are rare). */
void usbSecondRingPolicyEvent(UsbSecondRing *r, const UsbPolicyEvent *event);

/**
 * Copies, oldest first, the seconds that ended within [windowMs] before [nowMs] (the one still running is not included:
 * it is not finished). At most [max]; returns how many. Safe to call from another thread than the one that records.
 */
int usbSecondRingLast(UsbSecondRing *r, int64_t nowMs, int64_t windowMs, UsbSecondSample *out, int max);

/** B41-fix1: the policy events within [windowMs] before [nowMs], oldest first. At most [max]; returns how many. */
int usbSecondRingEvents(UsbSecondRing *r, int64_t nowMs, int64_t windowMs, UsbPolicyEvent *out, int max);

/**
 * One line per sample, `t-<seconds before now>s feedback n=... fpmf min=... max=... mean=... nominal=... deviating=... refused=... |
 * iso urbs=... packets=... errors=... short=... | fb held=... dropped urb=... packet=... length=... last=<urb>/<packet>/<length>
 * age=...ms | sent min=... max=... calls=... | wire frames=... packets=... applied=...`. Returns the bytes written (not counting
 * the terminator); a line that does not fit is left out whole.
 */
size_t usbSecondRingFormat(const UsbSecondSample *samples, int n, int64_t nowMs, char *out, size_t capacity);

/**
 * One line per event, `event t-<seconds before now>s stream=... rate=... <kind> <what> reading=... <ppm> ppm policy=... sending=...-><...>`;
 * a gesture has no reading: `event t-<s>s stream=... rate=... <kind> <what> policy=... sending=...`.
 * [policyName] turns a UsbFeedbackPolicy into its name. Same rules as usbSecondRingFormat.
 */
size_t usbSecondRingFormatEvents(const UsbPolicyEvent *events, int n, int64_t nowMs, const char *(*policyName)(int), char *out, size_t capacity);

#endif
