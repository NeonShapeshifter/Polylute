// USB_GLITCH instrumentation (AUD-124) and the async-feedback decision it watches. The counting,
// rate limiting and feedback rules are pure and host-tested; logging is the Android glue in
// usb-audio-output.cpp.
#ifndef USB_GLITCH_H
#define USB_GLITCH_H

#include <stdbool.h>
#include <stdint.h>

enum UsbGlitchKind {
    USB_GLITCH_ISO_PACKET = 0,       // a data packet completed with an error or short
    USB_GLITCH_RING_EMPTY = 1,       // nothing queued while streaming: the DAC got empty microframes
    USB_GLITCH_REAP_SLOW = 2,        // the reap call took far longer than the ~1 ms a URB needs (wall time around the call: scheduling included)
    USB_GLITCH_FEEDBACK_DEVIATION = 3,  // DAC asks > 0.2 % away from nominal
    USB_GLITCH_FEEDBACK_REJECTED = 4,   // implausible feedback reading, not applied
    USB_GLITCH_READ_SLOW = 5,        // the file read held the decoder
    USB_GLITCH_FEEDBACK_STUCK = 6,   // B10: the DAC's feedback has not moved for seconds, away from nominal
    USB_GLITCH_FEEDBACK_UNSTUCK = 7, // B10: it moved again (or the stream ended): the episode's total
    USB_GLITCH_OUTPUT_GAP = 8,       // B23-P2: a submit gap longer than the lead the ring held: the model estimates the bus had nothing queued
    USB_GLITCH_KIND_COUNT = 9,
};

typedef struct {
    int64_t total;
    int64_t suppressed;
    int64_t lastLogMs;
} GlitchCounter;

/**
 * Counts one occurrence at [nowMs]. True when it should be logged now — at most once per
 * [intervalMs] per kind — with [*suppressedOut] set to the occurrences not logged since the last one.
 */
bool glitchCount(GlitchCounter *counter, int64_t nowMs, int64_t intervalMs, int64_t *suppressedOut);

/**
 * Whether a backpressure reap that waited [waitedMs] is worth reporting (AUD-135). [waitedMs] is the wall time around the reap
 * call, so it includes any time the writer was runnable but not scheduled: a long one is not by itself a USB completion stall.
 * With the ring full the oldest URB is due within ~1 ms, and waits of 4-10 ms are ordinary on the device.
 */
bool usbReapIsSlow(int64_t waitedMs);

/**
 * How long the DAC has gone without a URB when the next one is about to be submitted, or -1 when
 * that gap is not a starvation: no submit yet, or an idle the stream was told about (a pause, a
 * stop), or a faded cut awaiting the next engine (AUD-123). AUD-140: the duration is what counts, not
 * whether it was under 200 ms — the worst starvations (a stall of 255 ms leaves the ring dry for
 * ~175 ms) were invisible to the counter the owner judges the "pss" by.
 */
int64_t usbRingStarvedMs(int64_t lastSubmitMs, int64_t nowMs, bool playOutBeforeDrain);

// ── Output gaps (B23-P2) ────────────────────────────────────────────────────────────────────────
//
// 2026-10-14 15:30:20, 96 kHz FLAC, Direct: `REAP_SLOW waited 175 ms inFlight=80`, the owner's "mini-pause" at the
// wake. With 80 URBs of ~1 ms in the ring that is longer than the ring holds, but the log cannot say *why* the writer
// took 175 ms: blocked in the reap wait (the host controller did not complete the URBs, or the thread was not woken)
// or not in it at all (not scheduled, or waiting for its producer). RING_EMPTY cannot say either: it looks only when
// fewer than 40 URBs are counted in flight, and a writer that was descheduled with a full ring finds 80 completed but
// unreaped URBs still counted.
//
// This is a pure time model, no allocation and nothing but clock reads at the submit, so it can run on every URB:
//  - what the ring holds is *wire time*: a URB of N packets occupies N microframes of the isochronous schedule
//    (125 us each at high speed, the speed the output assumes everywhere: calibratedFpmf = rate / 8000), whatever the
//    audio rate. It does not drift with the DAC's clock, which is why it is not frames / rate.
//  - `playoutEnd` is when everything submitted so far has gone out: each submit moves it forward by the URB's wire
//    time, from `max(playoutEnd, now)`. Before a submit, `lead = playoutEnd - now`; at or below zero the bus had
//    nothing queued for `-lead` and the DAC was fed empty microframes.
//  - an *announced* idle (a pause, a seek's silence, a stream start, a natural end) restarts the model, the same way
//    it silences RING_EMPTY: it is not a starvation.
//  - the time since the previous submit is split by **wall clock** into what elapsed inside the reap call, between two
//    native write calls (the Java side) and the rest of the native call. These are elapsed times, not causes: a thread
//    that was preempted while it was inside the reap call is counted as being in it, and so is one that was blocked in
//    poll waiting for a completion. Telling those apart takes the scheduler's own record (Perfetto `thread_state`:
//    S/D = it slept, R/R+ = it was runnable and waiting for a CPU).
//  - `dry` is the model's estimate of how long the bus had nothing queued, not a measurement of what the DAC played.

/** Wire time of one isochronous packet at high speed, in microseconds (one microframe). */
#define USB_AUDIO_PACKET_US 125

/**
 * The shortest dry stretch that is reported. A model of a ring that is scheduled a microframe at a time cannot tell a stretch of a
 * millisecond from the jitter of the model itself: the first URBs after a start or an announced idle are 1 ms of lead each, and a
 * submit that comes 1.2 ms after the one before it is not an event. In the simulation against the fake DAC (which counts anything
 * over 1 ms) this was the only thing that fired in a seek and in a natural join, with `dry=0 ms`.
 */
#define USB_OUTPUT_GAP_MIN_DRY_US 2000

typedef struct {
    bool armed;               // a previous submit exists to measure against
    int64_t lastSubmitUs;
    int64_t playoutEndUs;     // when the bus has played out everything submitted so far
    int64_t reapUs;           // elapsed inside the reap call since the last submit (wall clock: blocked there or preempted there)
    int64_t betweenCallsUs;   // between two native write calls since the last submit
} UsbOutputGapWatch;

/** What `usbOutputGapSubmit` found when the lead had run out. All values in microseconds. */
typedef struct {
    int64_t gapUs;            // since the previous submit
    int64_t leadUs;           // what the previous submit left queued ahead of it
    int64_t dryUs;            // the model's estimate of how long the bus had nothing queued: gapUs - leadUs, always > 0 here
    int64_t reapUs;           // of the gap, elapsed inside the reap call (wall clock, so also any time the thread was preempted there)
    int64_t betweenCallsUs;   // of the gap, between two native write calls
    int64_t otherUs;          // of the gap, the rest (inside a native call, outside the reap wait)
} UsbOutputGap;

enum UsbOutputGapWhere {
    // Where most of the gap's wall-clock time elapsed. None of these says why, and none proves a USB completion stall: `in-reap` is
    // the elapsed time around the reap call, scheduling included (blocked in poll, or runnable without a CPU). The writer's own
    // `runq=`/`cpu=` (when the line has them) or the thread's scheduler state in a trace says which.
    USB_OUTPUT_GAP_IN_REAP = 0,        // elapsed around the reap call (a late completion, or the writer not running there)
    USB_OUTPUT_GAP_BETWEEN_WRITES = 1, // outside the native write: waiting for its producer, or not running
    USB_OUTPUT_GAP_IN_WRITE = 2,       // inside the native write but outside the reap call: the submit ioctl, a copy, a preemption
};

void usbOutputGapWatchReset(UsbOutputGapWatch *w);

/** Time spent inside one reap wait on the writer thread. */
void usbOutputGapAddReap(UsbOutputGapWatch *w, int64_t reapUs);

/** Time between the end of one native write call and the start of the next. */
void usbOutputGapAddBetweenCalls(UsbOutputGapWatch *w, int64_t betweenUs);

/**
 * One data URB of [urbUs] wire time was submitted at [nowUs]. [announcedIdle]: the stream was idle on purpose
 * (`lastSubmitMs == 0`, a faded cut, a start), so there is no lead to run out. True when the lead had run out
 * (`playoutEnd <= now` at the submit) for at least USB_OUTPUT_GAP_MIN_DRY_US, with [*gap] filled; the model then restarts from this submit.
 */
bool usbOutputGapSubmit(UsbOutputGapWatch *w, bool announcedIdle, int64_t nowUs, int64_t urbUs, UsbOutputGap *gap);

/** The bucket that holds most of the gap (ties go to the reap wait, then the Java side). */
enum UsbOutputGapWhere usbOutputGapWhere(const UsbOutputGap *gap);
const char *usbOutputGapWhereName(enum UsbOutputGapWhere where);

// ── The writer's own scheduler counters (B24-Q3) ────────────────────────────────────────────────
//
// OUTPUT_GAP says where the wall-clock time between two submits went, not why. The kernel keeps, per thread, how long it ran and
// how long it sat runnable waiting for a CPU (`/proc/thread-self/schedstat`: "<ns on a CPU> <ns waiting on a run queue> <slices>").
// Read at the start and at the end of a gap, those two numbers separate a writer that was *asleep* (a late completion, a producer
// that did not deliver) from one that was *runnable without a CPU* (a scheduling stall): gap - cpu - runq is the time it slept.
// The start is a baseline refreshed at most every USB_SCHED_BASELINE_EVERY_US while submits go by, so the steady state pays one
// comparison per URB and one small read per 10 ms; the end is read only when a gap is being reported. The baseline is up to
// 10 ms older than the previous submit, so the figures cover a window a few ms longer than the gap (`over`).

#define USB_SCHED_BASELINE_EVERY_US 10000

/** One reading of the writer thread's schedstat, in nanoseconds. */
typedef struct {
    bool valid;
    uint64_t cpuNs;    // time on a CPU
    uint64_t runqNs;   // time runnable and waiting for a CPU
} UsbSchedSample;

/** Parses the contents of a schedstat file ("<cpu ns> <run queue ns> <timeslices>\n"). False, and *out invalid, if it is not that. */
bool usbParseSchedstat(const char *text, UsbSchedSample *out);

typedef struct {
    UsbSchedSample sample;
    int64_t atUs;      // monotonic us of the reading; 0 = none yet
} UsbSchedBaseline;

/** True when no baseline exists or the one held is older than USB_SCHED_BASELINE_EVERY_US. */
bool usbSchedBaselineDue(const UsbSchedBaseline *baseline, int64_t nowUs);
void usbSchedBaselineSet(UsbSchedBaseline *baseline, const UsbSchedSample *sample, int64_t nowUs);

/** What the thread did between the baseline and [now], in microseconds; `valid` is false if either reading is missing or went backwards. */
typedef struct {
    bool valid;
    int64_t cpuUs;     // on a CPU
    int64_t runqUs;    // runnable, waiting for a CPU
    int64_t overUs;    // the window both cover: from the baseline to now
} UsbSchedDelta;

UsbSchedDelta usbSchedDelta(const UsbSchedBaseline *baseline, const UsbSchedSample *now, int64_t nowUs);

/** Log window per kind: REAP_SLOW is a 30 s summary (count held back), the rest once a second. */
int64_t usbGlitchLogIntervalMs(enum UsbGlitchKind kind);

/** Relative deviation of a feedback reading from nominal (0.009 = +0.9 %). */
double usbFeedbackDeviation(double fpmf, double nominalFpmf);

/**
 * The frames-per-microframe to send after a feedback reading. A plausible reading is applied as
 * is; an implausible one (a misparsed format is off by a power of two) keeps [currentFpmf].
 */
double usbFeedbackApply(double currentFpmf, double reportedFpmf, double nominalFpmf);

// ── Frozen feedback (B10, AUD-124 "shh") ────────────────────────────────────────────────────────
//
// Three device episodes (06:18 44.1 native, 11:37 48 and 11:44 88.2 on the ExoPlayer path) had the
// Melody's feedback endpoint answer one value for minutes — 5.5175, 6.0049, 11.0499 frames per
// microframe, +907 / +817 / +2259 ppm — while the listener heard a "shh". The same DAC healthy answers
// its nominal rate with a few brief blips (+0.25 %, 10-25 a second): never the same off-nominal value
// twice in a row at the 1 Hz the logs sample. A pause (the DAC starved for ~1.5 s) cleared it.
//
// The thresholds, from the 3838 one-second samples of the day (shh-2026-10-06/):
//  - the longest identical off-nominal run in healthy playback is 3 s (the +1 % transient right after a
//    stream start), the stuck ones lasted 55 s and 153 s: 5 s sits between with margin both ways;
//  - a stuck value was never closer to nominal than +817 ppm, a healthy DAC's clock is within
//    ~100 ppm of it (the healthy mean reading is ~+30 ppm: blip size x blip duty): 500 ppm is under the first and well over the
//    second. A constant AT nominal (12.0000 for 152 s at 96 kHz) is healthy and never counts.

/** Identical readings for this long, off nominal by more than USB_FEEDBACK_STUCK_MIN_PPM: stuck. */
#define USB_FEEDBACK_STUCK_MIN_MS 5000
#define USB_FEEDBACK_STUCK_MIN_PPM 500.0
/**
 * No reading for this long (a pause: the writer idles and nobody reaps the endpoint) breaks the run.
 * Streaming delivers ~900 a second and a seek's barrier leaves a gap of tens of ms, which does not.
 */
#define USB_FEEDBACK_GAP_MS 500
/**
 * `clamp`: the most a reading may sit from nominal. 3x the worst crystal (+-100 ppm), 2.7x under the
 * smallest stuck offset seen (817 ppm): a DAC that is really that fast still gets what it asks for,
 * a stuck one is held to a third of its excess.
 */
#define USB_FEEDBACK_CLAMP_PPM 300.0

// ── Implausible, not frozen (B12a-2, AUD-124) ───────────────────────────────────────────────────────
//
// After a stuck episode ended the Melody answered 5.7875 (46 300 Hz, +4.99 %): inside the old +-5 % window, so
// it was applied. `auto` now also refuses a reading that is implausible without being frozen. Two rules, both
// measured against the 1260 sampled FEEDBACK_DEVIATION lines of 2026-10-06 (shh-2026-10-06/) and the AUD-124
// start-transient test (`expectFeedbackKeepsUpAfterStart`):
//
//  - what a healthy DAC reports: its nominal, a blip of +0.21..+0.27 % (1.2-1.8 % of the readings, every
//    rate), rarely +0.36 % (6 samples) or +0.54 % (1), and right after a stream start a +1.0 % transient that
//    lasted 3 s (3 samples, 10:54:02-04) — a real async DAC asks for that while its FIFO fills, and not
//    following it was the "pss" some seconds after a start that widened the window from 1 % to 5 %;
//  - what a bad DAC reports: +0.08..+1.0 % frozen (the stuck episodes, caught by FEEDBACK_STUCK), +4.99 % once.
//
// So a magnitude alone cannot separate the +0.6..+2 % band: healthy for 3 s at a stream start, wrong when it stays.
// The rules are therefore: ABSURD — farther than 2 % at any moment (2x the largest healthy reading ever sampled,
// half of the +4.99 % seen) is never obeyed; SUSTAINED — farther than 0.5 % in every reading for 5 s (1.7x the
// longest healthy transient, and 1.9x the routine +0.27 % blip) is not obeyed from then on, frozen or not. A DAC
// whose real clock is tens or hundreds of ppm off is nowhere near either and is followed as before. The 1000 ppm
// of the note in device-test-2026-10-06.md would have refused every ordinary +0.25 % blip, i.e. most of a healthy
// DAC's dither: it is not a usable bound.
#define USB_FEEDBACK_ABSURD_PPM 20000.0
#define USB_FEEDBACK_SUSTAINED_PPM 5000.0
#define USB_FEEDBACK_SUSTAINED_MS 5000

/** `auto`'s healthy average: an exponentially weighted mean over ~5 s of readings (5000 at ~1000/s). */
#define USB_FEEDBACK_HEALTHY_TAU_READINGS 5000.0
/** ... usable once it holds the equivalent of 1 s of readings; otherwise `auto` falls back to nominal. */
#define USB_FEEDBACK_HEALTHY_MIN_WEIGHT 1000.0

enum UsbFeedbackEventKind {
    USB_FEEDBACK_EVENT_NONE = 0,
    USB_FEEDBACK_EVENT_STUCK = 1,
    USB_FEEDBACK_EVENT_UNSTUCK = 2,
};

/** Why a reading is not obeyed under `auto` although it is not (necessarily) frozen; see USB_FEEDBACK_ABSURD_PPM. */
enum UsbFeedbackImplausible {
    USB_FEEDBACK_PLAUSIBLE = 0,
    USB_FEEDBACK_IMPLAUSIBLE_OUT_OF_WINDOW = 1,  // beyond +-5 %: not even applied (usbFeedbackApply)
    USB_FEEDBACK_IMPLAUSIBLE_ABSURD = 2,         // beyond USB_FEEDBACK_ABSURD_PPM
    USB_FEEDBACK_IMPLAUSIBLE_SUSTAINED = 3,      // beyond USB_FEEDBACK_SUSTAINED_PPM for USB_FEEDBACK_SUSTAINED_MS
};

const char *usbFeedbackImplausibleName(int why);

enum UsbFeedbackEndReason {
    USB_FEEDBACK_END_CHANGED = 0,     // a different reading arrived
    USB_FEEDBACK_END_GAP = 1,         // no readings for USB_FEEDBACK_GAP_MS (a pause)
    USB_FEEDBACK_END_STREAM = 2,      // the stream ended or restarted
};

/** What the watcher found; filled by every call, `kind` NONE for almost all of them. */
typedef struct {
    enum UsbFeedbackEventKind kind;
    uint32_t raw;                      // the repeated reading, Q16.16
    double fpmf;
    double ppm;                        // its offset from nominal, signed
    int64_t heldMs;                    // STUCK: how long it had held when detected; UNSTUCK: the whole episode
    int64_t readings;                  // identical readings in the run
    enum UsbFeedbackEndReason reason;  // UNSTUCK only
    uint32_t nextRaw;                  // UNSTUCK by a change: the reading that broke it
    // Independent of `kind` (an UNSTUCK and the first implausible reading can be the same reading):
    bool implausibleStarted;           // this reading began a streak of implausible ones
    enum UsbFeedbackImplausible implausibleWhy;
    double implausibleFpmf;
    double implausiblePpm;
} UsbFeedbackEvent;

/**
 * State of one stream's feedback: the current run of identical readings, whether it is an episode, and
 * the healthy average `auto` falls back on. Plain data, ~100 bytes, no allocation; only the thread that
 * reaps the feedback endpoint touches it.
 */
typedef struct {
    bool haveRun;
    uint32_t runRaw;
    int64_t runStartMs;
    int64_t runLastMs;
    int64_t runReadings;
    bool stuck;
    // Exponentially weighted sums of the readings of runs that were NOT an episode.
    double healthySum;
    double healthyWeight;
    // B12a-2: the latest reading is not to be obeyed under `auto` (see USB_FEEDBACK_ABSURD_PPM) ...
    bool implausible;
    enum UsbFeedbackImplausible implausibleWhy;
    // ... and the run of consecutive readings beyond USB_FEEDBACK_SUSTAINED_PPM it is judged by.
    bool bigRun;
    int64_t bigStartMs;
    int64_t bigLastMs;
} UsbFeedbackWatch;

/** A fresh stream: no run, no episode, no healthy history (a new rate must not inherit the old one's). */
void usbFeedbackWatchReset(UsbFeedbackWatch *w);

/**
 * One applied reading (Q16.16 [raw]) at [nowMs]. Constant time, no allocation. [ev] says whether it
 * started or ended an episode. A reading equal to the run's value extends it; a different one (or one
 * after a gap) ends it — an episode ends with UNSTUCK, a short run is folded into the healthy average.
 */
void usbFeedbackWatchObserve(UsbFeedbackWatch *w, uint32_t raw, double nominalFpmf, int64_t nowMs,
                             UsbFeedbackEvent *ev);

/**
 * A reading outside +-5 % (usbFeedbackApply refused it): it never reaches [usbFeedbackWatchObserve], but under
 * `auto` it makes what is sent the healthy average or nominal until an applied reading replaces it. [ev] says
 * whether it began a streak of implausible readings.
 */
void usbFeedbackWatchOutOfWindow(UsbFeedbackWatch *w, uint32_t raw, double nominalFpmf, UsbFeedbackEvent *ev);

/** The stream ends or restarts: closes an open episode (UNSTUCK, reason STREAM) and resets. */
void usbFeedbackWatchEnd(UsbFeedbackWatch *w, double nominalFpmf, UsbFeedbackEvent *ev);

/** Signed offset of [fpmf] from nominal, in parts per million. */
double usbFeedbackPpm(double fpmf, double nominalFpmf);

/**
 * What the host does with the DAC's feedback (SET_FEEDBACK_POLICY, process-only; not persisted).
 * `auto` is the default since B12a (two decisive device tests, 2026-10-07: the "shh" went away within seconds of
 * not obeying a stuck feedback, and the DAC unstuck itself). `follow` is what the output did before; it stays
 * selectable to diagnose.
 */
enum UsbFeedbackPolicy {
    USB_FEEDBACK_FOLLOW = 0,   // send what the DAC asks
    USB_FEEDBACK_NOMINAL = 1,  // ignore the feedback: packets at the nominal rate
    USB_FEEDBACK_CLAMP = 2,    // what the DAC asks, within +-USB_FEEDBACK_CLAMP_PPM of nominal
    USB_FEEDBACK_AUTO = 3,     // follow, except while the feedback is stuck or implausible: then the healthy average (B12a: the default)
    USB_FEEDBACK_POLICY_COUNT = 4,
};

const char *usbFeedbackPolicyName(int policy);

/**
 * The frames-per-microframe the packetizer is given. [followFpmf] is the last applied reading (what
 * `follow` sends); [w] may be null (then `auto` follows). Only the increment per microframe changes with
 * the policy: the packetizer's fractional accumulator carries on, so a switch in mid-stream moves the
 * next packets' sizes by at most one frame and never drops or repeats one.
 */
double usbFeedbackPolicyFpmf(int policy, double followFpmf, double nominalFpmf, const UsbFeedbackWatch *w);

/** Reports one glitch (Android glue; rate-limited, tag USB_GLITCH). Not available to host tests. */
void reportUsbGlitch(enum UsbGlitchKind kind, const char *fmt, ...);

#endif
