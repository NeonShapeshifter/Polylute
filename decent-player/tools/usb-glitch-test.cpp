// Host tests for AUD-124: USB_GLITCH counting/rate limiting and the async-feedback reaction.
#include "usb-glitch.h"
#include "usb-pcm-packetizer.h"

#include <assert.h>
#include <errno.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void expectRateLimit() {
    GlitchCounter c;
    memset(&c, 0, sizeof(c));
    int64_t suppressed = -1;
    assert(glitchCount(&c, 1000, 1000, &suppressed));  // first one always logs
    assert(suppressed == 0);
    for (int i = 0; i < 50; i++) assert(!glitchCount(&c, 1000 + i, 1000, &suppressed));
    assert(glitchCount(&c, 2001, 1000, &suppressed));  // next window: logs, reporting the 50 held back
    assert(suppressed == 50);
    assert(c.total == 52);
}

static void expectDeviation() {
    const double nominal = 48000.0 / 8000.0;
    assert(fabs(usbFeedbackDeviation(48439.5 / 8000.0, nominal) - 0.009156) < 0.0001);
    assert(fabs(usbFeedbackDeviation(nominal, nominal)) < 1e-12);
}

/**
 * A DAC whose clock needs +1 % for the first 3 s after the stream starts, reporting its rate plus a
 * correction for its own FIFO being below target — which is what a real async DAC does, and what
 * the +0.9 % reading 2 s after a 48 kHz start in device-pss-2026-09-27.log looked like. The host
 * sends exactly what the packetizer plans from the applied fpmf. Its FIFO must never run dry.
 */
static int simulateStartUnderruns(int rate) {
    const double nominal = rate / 8000.0;
    double applied = nominal;
    double accumulator = 0.0;
    double fifo = 2.0 * nominal * 8;  // ~2 ms prefilled when the DAC starts playing
    const double target = fifo;
    int underruns = 0;
    for (int uf = 0; uf < 8000 * 5; uf++) {
        const bool fast = uf < 8000 * 3;
        const double dacFpmf = nominal * (fast ? 1.01 : 1.0);
        // Feedback every microframe: its rate plus half a frame per frame of FIFO deficit.
        const double reported = dacFpmf + 0.5 * (target - fifo) / target * nominal * 0.02;
        applied = usbFeedbackApply(applied, reported, nominal);
        UsbPcmUrbPlan plan = planUsbPcmUrb(1 << 20, 8, 4096, applied, accumulator, false);
        // One microframe's worth of the plan: its first packet.
        accumulator += applied;
        const int sent = (int)accumulator;
        accumulator -= sent;
        (void)plan;
        fifo += sent - dacFpmf;
        if (fifo < 0) {
            underruns++;
            fifo = 0;
        }
    }
    return underruns;
}

static void expectFeedbackKeepsUpAfterStart() {
    const int rates[] = {44100, 48000, 96000};
    for (int rate : rates) {
        const int underruns = simulateStartUnderruns(rate);
        if (underruns != 0) {
            fprintf(stderr, "rate %d: %d microframes with the DAC FIFO empty\n", rate, underruns);
            assert(false);
        }
    }
    // Still refuses nonsense: a 10.14-format misread is off by 4x.
    const double nominal = 6.0;
    assert(usbFeedbackApply(nominal, nominal * 4.0, nominal) == nominal);
    assert(usbFeedbackApply(nominal, nominal / 4.0, nominal) == nominal);
}

/**
 * AUD-135: waits of 4-10 ms with 80 URBs in flight were logged as REAP_SLOW almost every second and
 * pushed the rest of the log out of the buffer (a device test lost two of three cycles). A wait up to
 * 20 ms is ordinary reaping; beyond that it is a stall, and it is summarised every 30 s with the
 * count held back instead of once a second. The other kinds keep their 1 s window.
 */
static void expectReapSlowIsQuiet() {
    assert(!usbReapIsSlow(4));
    assert(!usbReapIsSlow(10));
    assert(!usbReapIsSlow(20));
    assert(usbReapIsSlow(21));
    assert(usbGlitchLogIntervalMs(USB_GLITCH_REAP_SLOW) == 30000);
    assert(usbGlitchLogIntervalMs(USB_GLITCH_RING_EMPTY) == 1000);
    assert(usbGlitchLogIntervalMs(USB_GLITCH_ISO_PACKET) == 1000);
}

/**
 * AUD-140 / AUD-124: RING_EMPTY only counted a dry ring whose last submit was under 200 ms ago. A stall
 * of 255 ms (a slow file read, a scheduling hiccup) leaves the 80 ms ring dry for ~175 ms and the
 * next submit reports sinceMs = 255 — which the window dropped. B7-fix4 counted 3 RING_EMPTY in a
 * session whose starvations were many more, exactly the ones the owner hears as "pss".
 */
static void expectRingStarvationIsCountedWhateverItsLength() {
    assert(usbRingStarvedMs(1000, 1010, false) == 10);
    assert(usbRingStarvedMs(1000, 1199, false) == 199);
    // The blind spot: 200 ms and more.
    assert(usbRingStarvedMs(1000, 1255, false) == 255);
    assert(usbRingStarvedMs(1000, 3000, false) == 2000);
    // Not a starvation: nothing submitted yet, or an idle the stream was told about (a pause, a
    // stop) — the engine and the streaming thread reset the stamp to 0 when they go idle.
    assert(usbRingStarvedMs(0, 5000, false) == -1);
    // A faded cut awaiting the next engine is meant to run empty (AUD-123).
    assert(usbRingStarvedMs(1000, 1255, true) == -1);
}

// ── B10: the frozen feedback of the "shh" (AUD-124) ─────────────────────────────────────────────────

static uint32_t rawOf(double fpmf) { return (uint32_t)llround(fpmf * 65536.0); }

/** A stream's readings at ~1 kHz on a simulated clock (what the output sees: ~900-1000 a second). */
struct Feed {
    UsbFeedbackWatch w;
    double nominal;
    int64_t now;
    int stuckEvents, unstuckEvents;
    int implausibleStarts;           // streaks of implausible readings (B12a-2)
    int policy = USB_FEEDBACK_AUTO;  // what the watch is asked under (B46-C2: `auto` unless a test says `auto-early`)
    UsbFeedbackEvent stuck, unstuck, implausible;
    explicit Feed(double nominalFpmf)
        : nominal(nominalFpmf), now(0), stuckEvents(0), unstuckEvents(0), implausibleStarts(0) {
        usbFeedbackWatchReset(&w);
        memset(&stuck, 0, sizeof(stuck));
        memset(&unstuck, 0, sizeof(unstuck));
        memset(&implausible, 0, sizeof(implausible));
    }
    void reading(uint32_t raw) {
        UsbFeedbackEvent ev;
        usbFeedbackWatchObservePolicy(&w, raw, nominal, now, policy, &ev);
        if (ev.kind == USB_FEEDBACK_EVENT_STUCK) { stuckEvents++; stuck = ev; }
        if (ev.kind == USB_FEEDBACK_EVENT_UNSTUCK) { unstuckEvents++; unstuck = ev; }
        if (ev.implausibleStarted) { implausibleStarts++; implausible = ev; }
    }
    /** A reading beyond +-5 %, which the output refuses before it reaches the watch. */
    void outOfWindow(uint32_t raw) {
        UsbFeedbackEvent ev;
        usbFeedbackWatchOutOfWindow(&w, raw, nominal, &ev);
        if (ev.implausibleStarted) { implausibleStarts++; implausible = ev; }
    }
    /** One reading a millisecond for [ms] milliseconds, all [raw]. */
    void run(uint32_t raw, int64_t ms) {
        for (int64_t i = 0; i < ms; i++) { reading(raw); now += 1; }
    }
    /** Nobody reaps the endpoint for [ms] (a pause, a stalled host). */
    void idle(int64_t ms) { now += ms; }
};

/**
 * The three episodes of 2026-10-06 as the output would have seen them. Offsets and durations are the
 * log's: 6.0049 at 48 kHz for 153 s, 11.0499 at 88.2 kHz for 55 s (then a pause and 11.0249), 5.5175 at
 * 44.1 kHz. Each fires once, 5 s in, with the value, ppm, hold time and reading count.
 */
static void expectStuckEpisodesFromTheDeviceFire() {
    {   // 11:36:57 Brillo, 24/48: stuck from the first second of the stream until the song ended
        Feed f(6.0);
        f.run(rawOf(6.0049), 153000);
        assert(f.stuckEvents == 1 && f.unstuckEvents == 0 && f.w.stuck);
        assert(f.stuck.raw == rawOf(6.0049));
        assert(fabs(f.stuck.fpmf - 6.0049) < 1e-4);
        assert(f.stuck.ppm > 800.0 && f.stuck.ppm < 830.0);                    // +817 ppm
        assert(f.stuck.heldMs == USB_FEEDBACK_STUCK_MIN_MS);                     // detected the moment it was 5 s old
        assert(f.stuck.readings == USB_FEEDBACK_STUCK_MIN_MS + 1);
        f.run(rawOf(6.0), 3);                                                    // Brillo #2: the DAC answers its rate
        assert(f.unstuckEvents == 1 && !f.w.stuck);
        assert(f.unstuck.reason == USB_FEEDBACK_END_CHANGED && f.unstuck.nextRaw == rawOf(6.0));
        assert(f.unstuck.heldMs == 152999 && f.unstuck.readings == 153000);      // the episode's total
        assert(f.stuckEvents == 1);
    }
    {   // 11:44:43 Imperio, 24/88.2: 11.0499 for 55 s, a 1.5 s pause, then 11.0249 (88 200 Hz) for good
        Feed f(11.025);
        f.run(rawOf(11.0499), 55000);
        assert(f.stuckEvents == 1);
        assert(f.stuck.ppm > 2250.0 && f.stuck.ppm < 2270.0);                    // +2259 ppm
        f.idle(1500);
        f.run(rawOf(11.0249), 60000);
        assert(f.unstuckEvents == 1 && f.unstuck.reason == USB_FEEDBACK_END_GAP);
        assert(f.unstuck.heldMs == 54999);                                       // up to its last reading, not the pause
        assert(f.stuckEvents == 1 && !f.w.stuck);                                // and 11.0249 is nominal: healthy
    }
    {   // 06:18 native engine, 16/44.1: 5.5175 (+907 ppm)
        Feed f(5.5125);
        f.run(rawOf(5.5175), 8000);
        assert(f.stuckEvents == 1 && f.stuck.ppm > 900.0 && f.stuck.ppm < 915.0);
    }
}

/** What a healthy DAC does must never fire, whatever its length: a constant AT nominal, and the dither. */
static void expectHealthyFeedbackNeverFires() {
    {   // 08:45 96 kHz: 12.0000 for 152 s straight
        Feed f(12.0);
        f.run(rawOf(12.0), 160000);
        assert(f.stuckEvents == 0 && !f.w.stuck);
    }
    // The dither of a healthy Melody: its nominal with a +0.25 % blip ~1.3 % of the time (10-25 a second
    // of ~940), 5.5275 at 44.1 and 6.0149 at 48 — for ten minutes. Pseudo-random, so blips come in runs too.
    const struct { double nominal, blip; } dithers[] = {{5.5125, 5.5275}, {6.0, 6.0149}, {11.025, 11.0499}};
    for (const auto &d : dithers) {
        Feed f(d.nominal);
        uint32_t x = 0x2545F491u;
        int blips = 0;
        for (int i = 0; i < 600000; i++) {
            x ^= x << 13; x ^= x >> 17; x ^= x << 5;
            const bool blip = (x % 1000) < 13;
            blips += blip;
            f.reading(rawOf(blip ? d.blip : d.nominal));
            f.now += 1;
        }
        assert(blips > 5000);
        assert(f.stuckEvents == 0 && !f.w.stuck);
    }
    {   // the +1 % transient right after a stream start lasted 3 s (10:54:02-04), then 5.5125
        Feed f(5.5125);
        f.run(rawOf(5.5674), 3000);
        f.run(rawOf(5.5125), 60000);
        assert(f.stuckEvents == 0);
    }
    {   // the 5 s boundary: 5000 identical readings (t = 0..4999) are not an episode, the one at t = 5000 is
        Feed f(5.5125);
        f.run(rawOf(5.5175), USB_FEEDBACK_STUCK_MIN_MS);
        assert(f.stuckEvents == 0);
        f.reading(rawOf(5.5175));
        assert(f.stuckEvents == 1 && f.stuck.heldMs == USB_FEEDBACK_STUCK_MIN_MS);
    }
}

/** The offset threshold (500 ppm), both signs: constant but within a crystal's tolerance is healthy. */
static void expectOffsetThreshold() {
    const double nominal = 12.0;
    struct { double ppm; bool fires; } cases[] = {
        {+408.0, false},  // 12.0049, seen on the device: a single sample
        {-408.0, false},
        {+36.0, false},   // the healthy mean
        {+510.0, true},
        {-510.0, true},
        {+817.0, true},   // the smallest stuck offset seen
        {-1000.0, true},  // a stuck DAC asking for LESS is as implausible
        {+2259.0, true},
    };
    for (const auto &c : cases) {
        Feed f(nominal);
        const uint32_t raw = rawOf(nominal * (1.0 + c.ppm * 1e-6));
        f.run(raw, 30000);
        if ((f.stuckEvents == 1) != c.fires) {
            fprintf(stderr, "%+.0f ppm: fires=%d expected %d\n", c.ppm, f.stuckEvents, (int)c.fires);
            assert(false);
        }
    }
}

/** A pause breaks the run (the DAC was starved ~1.5 s and came back healthy); a seek's barrier does not. */
static void expectGapBreaksTheRunOnlyWhenLong() {
    {
        Feed f(6.0);
        f.run(rawOf(6.0049), 3000);
        f.idle(USB_FEEDBACK_GAP_MS + 1);
        f.run(rawOf(6.0049), 4900);               // 3 s + 4.9 s with a pause between: neither side is 5 s
        assert(f.stuckEvents == 0);
        f.run(rawOf(6.0049), 200);                // the second half alone reaches 5 s
        assert(f.stuckEvents == 1);
    }
    {
        Feed f(6.0);
        f.run(rawOf(6.0049), 3000);
        f.idle(USB_FEEDBACK_GAP_MS - 1);          // a seek: the barrier restores the feedback URB in tens of ms
        f.run(rawOf(6.0049), 3000);
        assert(f.stuckEvents == 1);
    }
}

/** The stream ends or restarts: an episode is closed, and nothing carries over (not even the average). */
static void expectStreamEndClosesTheEpisode() {
    Feed f(6.0);
    f.run(rawOf(6.0), 4000);                      // some healthy history
    f.run(rawOf(6.0049), 9000);
    assert(f.w.stuck);
    UsbFeedbackEvent ev;
    usbFeedbackWatchEnd(&f.w, 6.0, &ev);
    assert(ev.kind == USB_FEEDBACK_EVENT_UNSTUCK && ev.reason == USB_FEEDBACK_END_STREAM);
    assert(ev.raw == rawOf(6.0049) && ev.heldMs == 8999);
    assert(!f.w.stuck && !f.w.haveRun && f.w.healthyWeight == 0.0);
    usbFeedbackWatchEnd(&f.w, 6.0, &ev);          // nothing open: nothing to report
    assert(ev.kind == USB_FEEDBACK_EVENT_NONE);
}

static void expectPoliciesDecide() {
    assert(strcmp(usbFeedbackPolicyName(USB_FEEDBACK_FOLLOW), "follow") == 0);
    assert(strcmp(usbFeedbackPolicyName(USB_FEEDBACK_NOMINAL), "nominal") == 0);
    assert(strcmp(usbFeedbackPolicyName(USB_FEEDBACK_CLAMP), "clamp") == 0);
    assert(strcmp(usbFeedbackPolicyName(USB_FEEDBACK_AUTO), "auto") == 0);
    assert(strcmp(usbFeedbackPolicyName(99), "?") == 0);

    const double nominal = 5.5125;
    const double stuck = 5.5175;      // +907 ppm, the 06:18 episode
    // follow is what the output always did, also for a policy nobody defined
    assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_FOLLOW, stuck, nominal, nullptr) == stuck);
    assert(usbFeedbackPolicyFpmf(99, stuck, nominal, nullptr) == stuck);
    assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_NOMINAL, stuck, nominal, nullptr) == nominal);
    // clamp: +-300 ppm of nominal; a healthy +36 ppm is untouched, the stuck +907 is held to +300
    const double limit = nominal * USB_FEEDBACK_CLAMP_PPM * 1e-6;
    assert(fabs(usbFeedbackPolicyFpmf(USB_FEEDBACK_CLAMP, stuck, nominal, nullptr) - (nominal + limit)) < 1e-12);
    assert(fabs(usbFeedbackPolicyFpmf(USB_FEEDBACK_CLAMP, 5.5075, nominal, nullptr) - (nominal - limit)) < 1e-12);
    assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_CLAMP, 5.5127, nominal, nullptr) == 5.5127);
    assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_CLAMP, nominal, nominal, nullptr) == nominal);

    // auto follows until the feedback is stuck, and with nothing to follow it follows
    assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, stuck, nominal, nullptr) == stuck);
    {
        Feed f(nominal);
        f.run(rawOf(5.5125), 2000);
        assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, 5.5275, nominal, &f.w) == 5.5275);   // healthy blip: followed
    }
    {   // stuck from the first second (all three episodes): no healthy window, so nominal
        Feed f(nominal);
        f.run(rawOf(stuck), 8000);
        assert(f.w.stuck);
        assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, stuck, nominal, &f.w) == nominal);
        // and follow / nominal / clamp are not affected by the watch
        assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_FOLLOW, stuck, nominal, &f.w) == stuck);
        // unstuck: back to following
        f.run(rawOf(5.5125), 5);
        assert(!f.w.stuck);
        assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, 5.5275, nominal, &f.w) == 5.5275);
    }
    {   // a healthy window before it: its average, exactly, when it is one value
        Feed f(nominal);
        const double healthy = nominal * (1.0 + 100e-6);
        f.run(rawOf(healthy), 3000);
        f.run(rawOf(stuck), 8000);
        assert(f.w.stuck);
        assert(fabs(usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, stuck, nominal, &f.w) - rawOf(healthy) / 65536.0) < 1e-9);
    }
    {   // the dither's average: nominal plus the blips' share, between nominal and a blip
        Feed f(nominal);
        for (int i = 0; i < 4000; i++) { f.reading(rawOf(i % 80 == 79 ? 5.5275 : 5.5125)); f.now += 1; }
        f.run(rawOf(stuck), 8000);
        assert(f.w.stuck);
        const double mean = usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, stuck, nominal, &f.w);
        const double ppm = usbFeedbackPpm(mean, nominal);
        assert(ppm > 5.0 && ppm < 80.0);     // 1.25 % duty x +2721 ppm = ~34 ppm
    }
    {   // too little history (0.4 s) is not a window: nominal
        Feed f(nominal);
        f.run(rawOf(nominal * (1.0 + 100e-6)), 400);
        f.run(rawOf(stuck), 8000);
        assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, stuck, nominal, &f.w) == nominal);
    }
    {   // a "healthy" window that was itself +1 % (a start transient) is not trusted either
        Feed f(nominal);
        f.run(rawOf(5.5674), 3000);
        f.run(rawOf(stuck), 8000);
        assert(f.w.stuck);
        assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, stuck, nominal, &f.w) == nominal);
    }
}

// ── B12a-2: implausible readings that are not frozen ─────────────────────────────────────────────────

/** What `auto` sends for [raw] being the latest applied reading of this feed. */
static double autoSends(const Feed &f, uint32_t raw) {
    return usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, raw / 65536.0, f.nominal, &f.w);
}

/**
 * The reading that prompted the batch: after a stuck episode the Melody answered 5.7875 (46 300 Hz, +4.99 %), inside
 * the +-5 % window, so it was applied. `auto` refuses it for what it is; `follow` still obeys it (to diagnose);
 * the next ordinary reading is followed at once.
 */
static void expectTheDeviceAbsurdReadingIsRefused() {
    const double nominal = 5.5125;
    Feed f(nominal);
    f.run(rawOf(nominal), 3000);                       // healthy start, one value
    f.run(rawOf(5.5674), 6000);                        // stuck at +1 % (06:39:46, "Collar Full")
    assert(f.w.stuck && f.unstuckEvents == 0);
    f.reading(rawOf(nominal));                         // it unsticks by itself, back to nominal (06:40:11)
    f.now += 1;
    assert(f.unstuckEvents == 1 && !f.w.stuck && !f.w.implausible);
    assert(autoSends(f, rawOf(nominal)) == rawOf(nominal) / 65536.0);
    // the stuck +1 % was itself a sustained deviation (both rules reach it at 5 s): one streak so far
    assert(f.implausibleStarts == 1 && f.implausible.implausibleWhy == USB_FEEDBACK_IMPLAUSIBLE_SUSTAINED);

    f.reading(rawOf(5.7875));                          // 46 300 Hz
    f.now += 1;
    const double ppm = usbFeedbackPpm(5.7875, nominal);
    assert(ppm > 49000.0 && ppm < 50000.0);            // +4.99 %: the +-5 % window applied it
    assert(f.w.implausible && f.w.implausibleWhy == USB_FEEDBACK_IMPLAUSIBLE_ABSURD);
    assert(f.implausibleStarts == 2 && f.implausible.implausibleWhy == USB_FEEDBACK_IMPLAUSIBLE_ABSURD);
    assert(fabs(f.implausible.implausiblePpm - ppm) < 5.0);   // Q16.16 rounding
    // auto never sends it; with the +1 % transient not taught to the average and 3 s of nominal, that is nominal's
    // own average, to the bit. follow still sends the DAC's word.
    assert(fabs(autoSends(f, rawOf(5.7875)) - nominal) < 1e-5);                 // Q16.16: 5.5125 is 361267.2 LSB
    assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_FOLLOW, 5.7875, nominal, &f.w) == 5.7875);
    assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_NOMINAL, 5.7875, nominal, &f.w) == nominal);
    // a second absurd reading in the same streak is not a second event; the first ordinary one ends it
    f.reading(rawOf(5.7875)); f.now += 1;
    assert(f.implausibleStarts == 2);
    f.reading(rawOf(5.5275)); f.now += 1;              // a healthy +0.27 % blip
    assert(!f.w.implausible);
    assert(autoSends(f, rawOf(5.5275)) == rawOf(5.5275) / 65536.0);
    f.reading(rawOf(5.7875)); f.now += 1;              // a new streak is a new event
    assert(f.implausibleStarts == 3);
}

/** The absurd bound is +-2 %, both signs, at any moment: no persistence needed. */
static void expectAbsurdBoundary() {
    const double nominal = 12.0;
    struct { double ppm; bool refused; } cases[] = {
        {+19900.0, false}, {-19900.0, false},
        {+20100.0, true},  {-20100.0, true},
        {+49900.0, true},  {-49000.0, true},
    };
    for (const auto &c : cases) {
        Feed f(nominal);
        f.run(rawOf(nominal), 100);
        f.reading(rawOf(nominal * (1.0 + c.ppm * 1e-6)));
        if (f.w.implausible != c.refused) {
            fprintf(stderr, "%+.0f ppm: implausible=%d expected %d\n", c.ppm, f.w.implausible, (int)c.refused);
            assert(false);
        }
    }
}

/**
 * Wrong for seconds without being frozen: every reading beyond +-0.5 % for 5 s, the values moving about. 5 s is
 * 1.7x the longest healthy transient on record (3 s). Anything at or under 0.5 % — a healthy reading — ends the
 * count, and so does a pause.
 */
static void expectSustainedDeviationIsRefused() {
    const double nominal = 5.5125;
    const double wobble[] = {5.5675, 5.5725, 5.5800, 5.5700, 5.5950};   // +0.99 % .. +1.5 %, never twice in a row
    {
        Feed f(nominal);
        for (int64_t t = 0; t < USB_FEEDBACK_SUSTAINED_MS; t++) {         // t = 0 .. 4999: 5 s minus a millisecond
            f.reading(rawOf(wobble[t % 5])); f.now += 1;
        }
        assert(!f.w.implausible && f.implausibleStarts == 0);
        assert(!f.w.stuck && f.stuckEvents == 0);                          // not identical: the stuck rule is blind to it
        f.reading(rawOf(wobble[0]));                                       // t = 5000
        assert(f.w.implausible && f.w.implausibleWhy == USB_FEEDBACK_IMPLAUSIBLE_SUSTAINED);
        assert(f.implausibleStarts == 1 && f.stuckEvents == 0);
        assert(fabs(autoSends(f, rawOf(wobble[0])) - nominal) < 1e-5);     // no healthy history: nominal
        f.now += 1;
        f.reading(rawOf(5.5275)); f.now += 1;                              // one healthy blip (+0.27 %) ends it
        assert(!f.w.implausible);
        assert(autoSends(f, rawOf(5.5275)) == rawOf(5.5275) / 65536.0);
    }
    {   // a healthy reading in the middle starts the 5 s over
        Feed f(nominal);
        for (int t = 0; t < 4000; t++) { f.reading(rawOf(wobble[t % 5])); f.now += 1; }
        f.reading(rawOf(nominal)); f.now += 1;
        for (int t = 0; t < 4000; t++) { f.reading(rawOf(wobble[t % 5])); f.now += 1; }
        assert(f.implausibleStarts == 0);                                  // 4 s + 4 s with a healthy reading between
        for (int t = 0; t < 1100; t++) { f.reading(rawOf(wobble[t % 5])); f.now += 1; }
        assert(f.implausibleStarts == 1);
    }
    {   // a pause longer than the gap starts it over; a seek's gap does not
        Feed f(nominal);
        for (int t = 0; t < 3000; t++) { f.reading(rawOf(wobble[t % 5])); f.now += 1; }
        f.idle(USB_FEEDBACK_GAP_MS + 1);
        for (int t = 0; t < 4900; t++) { f.reading(rawOf(wobble[t % 5])); f.now += 1; }
        assert(f.implausibleStarts == 0);
        for (int t = 0; t < 200; t++) { f.reading(rawOf(wobble[t % 5])); f.now += 1; }
        assert(f.implausibleStarts == 1);
        Feed g(nominal);
        for (int t = 0; t < 3000; t++) { g.reading(rawOf(wobble[t % 5])); g.now += 1; }
        g.idle(USB_FEEDBACK_GAP_MS - 1);
        for (int t = 0; t < 3000; t++) { g.reading(rawOf(wobble[t % 5])); g.now += 1; }
        assert(g.implausibleStarts == 1);
    }
}

/** Beyond +-5 %: the output never applies it, and under `auto` it must not leave the last applied value in charge. */
static void expectOutOfWindowReadingIsImplausibleToo() {
    const double nominal = 5.5125;
    Feed f(nominal);
    f.run(rawOf(nominal), 2000);
    f.outOfWindow(rawOf(nominal * 4.0));               // a 10.14-format misread
    assert(f.w.implausible && f.w.implausibleWhy == USB_FEEDBACK_IMPLAUSIBLE_OUT_OF_WINDOW);
    assert(f.implausibleStarts == 1);
    // what was last applied could be a blip; auto sends the healthy average instead
    assert(fabs(autoSends(f, rawOf(5.5275)) - nominal) < 1e-6);
    f.outOfWindow(rawOf(nominal * 4.0));
    assert(f.implausibleStarts == 1);
    f.reading(rawOf(nominal)); f.now += 1;             // the next applied reading ends it
    assert(!f.w.implausible);
    assert(strcmp(usbFeedbackImplausibleName(USB_FEEDBACK_IMPLAUSIBLE_OUT_OF_WINDOW), "out-of-window") == 0);
    assert(strcmp(usbFeedbackImplausibleName(USB_FEEDBACK_IMPLAUSIBLE_ABSURD), "absurd") == 0);
    assert(strcmp(usbFeedbackImplausibleName(USB_FEEDBACK_IMPLAUSIBLE_SUSTAINED), "sustained") == 0);
}

/**
 * What must keep being obeyed: the healthy dither (blips +0.21..+0.54 %, one at a time), the +1 % start transient
 * of 3 s, and a DAC whose real clock is tens or hundreds of ppm off — for minutes. None is implausible, and `auto`
 * sends exactly what `follow` does.
 */
static void expectPlausibleReadingsAreStillFollowed() {
    const double nominal = 5.5125;
    {   // every healthy value the day's logs show: 0.208 .. 0.271 routinely, 0.362 six times, 0.544 once
        Feed f(nominal);
        const double pct[] = {0.208, 0.226, 0.248, 0.271, 0.362, 0.544};
        for (int rep = 0; rep < 200; rep++) {
            for (double p : pct) {
                f.reading(rawOf(nominal * (1.0 + p / 100.0))); f.now += 1;
                assert(!f.w.implausible);
                f.reading(rawOf(nominal)); f.now += 1;
            }
        }
        assert(f.implausibleStarts == 0);
    }
    {   // the dither of a healthy Melody for ten minutes, as in expectHealthyFeedbackNeverFires
        Feed f(nominal);
        uint32_t x = 0x2545F491u;
        for (int i = 0; i < 600000; i++) {
            x ^= x << 13; x ^= x >> 17; x ^= x << 5;
            const uint32_t raw = rawOf((x % 1000) < 13 ? 5.5275 : nominal);
            f.reading(raw); f.now += 1;
            assert(autoSends(f, raw) == raw / 65536.0);                      // followed, reading by reading
        }
        assert(f.implausibleStarts == 0);
    }
    {   // the +1 % start transient (10:54:02-04): 3 s of it is followed; so is 4.9 s
        Feed f(nominal);
        for (int t = 0; t < 4900; t++) {
            const uint32_t raw = rawOf(5.5674);
            f.reading(raw); f.now += 1;
            assert(!f.w.implausible && autoSends(f, raw) == raw / 65536.0);
        }
        assert(f.implausibleStarts == 0);
    }
    const double clocks[] = {+36.0, +100.0, -200.0, +300.0, -450.0};         // a real crystal, off by tens or hundreds of ppm
    for (double ppm : clocks) {
        Feed f(nominal);
        const uint32_t raw = rawOf(nominal * (1.0 + ppm * 1e-6));
        f.run(raw, 600000);                                                  // ten minutes, one value
        assert(f.implausibleStarts == 0 && f.stuckEvents == 0 && !f.w.implausible);
        assert(autoSends(f, raw) == raw / 65536.0);
    }
}

/** The healthy average `auto` falls back on is never taught by a transient or a bad stretch. */
static void expectImplausibleStretchesAreNotTaughtToTheAverage() {
    const double nominal = 5.5125;
    Feed f(nominal);
    const double healthy = nominal * (1.0 + 100e-6);
    f.run(rawOf(healthy), 3000);                       // 3 s of a healthy DAC, +100 ppm
    f.run(rawOf(5.5674), 3000);                        // a 3 s +1 % transient, followed (not implausible)
    f.run(rawOf(healthy), 2);                          // ends the transient's run
    f.reading(rawOf(5.7875));                          // then an absurd reading
    assert(f.w.implausible);
    // the +1 % run was not folded into the average, so what auto sends is the healthy 100 ppm, exactly
    assert(fabs(autoSends(f, rawOf(5.7875)) - rawOf(healthy) / 65536.0) < 1e-9);
}

/** The AUD-124 start transient through the whole `auto` path: the DAC must never run dry (the same simulation as above). */
static int simulateStartUnderrunsAuto(int rate, int fastSeconds) {
    const double nominal = rate / 8000.0;
    UsbFeedbackWatch w;
    usbFeedbackWatchReset(&w);
    double applied = nominal;
    double fifo = 2.0 * nominal * 8;
    const double target = fifo;
    int underruns = 0;
    double accumulator = 0.0;
    int64_t nowMs = 0;
    for (int uf = 0; uf < 8000 * (fastSeconds + 3); uf++) {
        nowMs = uf / 8;
        const bool fast = uf < 8000 * fastSeconds;
        const double dacFpmf = nominal * (fast ? 1.01 : 1.0);
        const double reported = dacFpmf + 0.5 * (target - fifo) / target * nominal * 0.02;
        const double kept = usbFeedbackApply(applied, reported, nominal);
        applied = kept;
        UsbFeedbackEvent ev;
        usbFeedbackWatchObserve(&w, rawOf(reported), nominal, nowMs, &ev);
        const double sends = usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, applied, nominal, &w);
        accumulator += sends;
        const int sent = (int)accumulator;
        accumulator -= sent;
        fifo += sent - dacFpmf;
        if (fifo < 0) { underruns++; fifo = 0; }
    }
    return underruns;
}

static void expectAutoKeepsUpAfterStart() {
    const int rates[] = {44100, 48000, 96000};
    for (int rate : rates) {
        const int underruns = simulateStartUnderrunsAuto(rate, 3);
        if (underruns != 0) {
            fprintf(stderr, "auto, rate %d: %d microframes with the DAC FIFO empty after a 3 s +1 %% transient\n", rate, underruns);
            assert(false);
        }
    }
}

/** One plan of the packetizer per URB, as the output does, carrying the accumulator from the last. */
struct Wire {
    double acc;
    long long frames, packets;
    double ideal;     // sum of the fpmf every packet was scheduled with, plus the starting fraction
    int minSize, maxSize;
    Wire() : acc(0.3), frames(0), packets(0), ideal(0.3), minSize(1 << 30), maxSize(0) {}
    /** [urbs] URBs at [fpmf]; after each, frames sent + the fraction owed must equal what was asked for. */
    void send(double fpmf, int urbs) {
        for (int i = 0; i < urbs; i++) {
            UsbPcmUrbPlan plan = planUsbPcmUrb(1 << 20, 8, 4096, fpmf, acc, false);
            assert(plan.packetCount == USB_PCM_PACKETS_PER_URB);
            for (int p = 0; p < plan.packetCount; p++) {
                const int n = plan.packetSizes[p] / 8;
                frames += n;
                packets++;
                if (n < minSize) minSize = n;
                if (n > maxSize) maxSize = n;
            }
            acc = plan.nextFrameAccumulator;
            ideal += plan.packetCount * fpmf;
            assert(acc >= 0.0 && acc < 1.0);
            assert(fabs((double)frames + acc - ideal) < 1e-6);   // not one frame dropped or repeated, at any URB
        }
    }
};

/**
 * Each policy's packets, and a switch in mid-stream between all of them: the accumulator carries on, so
 * nothing is dropped or repeated at the seam and every packet is one of the two sizes around the rate.
 */
static void expectPolicySwitchDoesNotJump() {
    const double nominal = 5.5125, stuck = 5.5175;
    const int policies[] = {USB_FEEDBACK_FOLLOW, USB_FEEDBACK_NOMINAL, USB_FEEDBACK_CLAMP, USB_FEEDBACK_AUTO, USB_FEEDBACK_FOLLOW};
    Wire w;
    long long perPolicyFrames[5], perPolicyPackets[5];
    for (int i = 0; i < 5; i++) {
        const long long f0 = w.frames, p0 = w.packets;
        // auto here is mid-episode with no healthy window: it sends nominal
        UsbFeedbackWatch watch;
        usbFeedbackWatchReset(&watch);
        watch.stuck = (policies[i] == USB_FEEDBACK_AUTO);
        const double fpmf = usbFeedbackPolicyFpmf(policies[i], stuck, nominal, &watch);
        w.send(fpmf, 1000);
        perPolicyFrames[i] = w.frames - f0;
        perPolicyPackets[i] = w.packets - p0;
    }
    assert(w.minSize == 5 && w.maxSize == 6);
    // 8000 packets each: follow +907 ppm = 44 140 frames a second, nominal 44 100, clamp 44 114, auto = nominal
    const long long want[5] = {44140, 44100, 44114, 44100, 44140};
    for (int i = 0; i < 5; i++) {
        assert(perPolicyPackets[i] == 8000);
        assert(llabs(perPolicyFrames[i] - want[i]) <= 1);
    }
    // the seam itself: the first packets after follow -> nominal are 5s and 6s in nominal's pattern, and the
    // frame count across it is the sum of the two rates (checked at every URB by Wire::send).
    Wire seam;
    seam.send(stuck, 37);
    const long long beforeFrames = seam.frames;
    seam.send(nominal, 1);
    assert(seam.frames - beforeFrames >= 44 && seam.frames - beforeFrames <= 45);   // 8 x 5.5125 = 44.1
}

/** Every kind has a name and a log window; the two new ones log at the usual once a second. */
static void expectNewKindsAreRegistered() {
    assert(USB_GLITCH_KIND_COUNT == 13);
    assert(USB_GLITCH_FEEDBACK_STUCK == 6 && USB_GLITCH_FEEDBACK_UNSTUCK == 7);
    assert(usbGlitchLogIntervalMs(USB_GLITCH_FEEDBACK_STUCK) == 1000);
    assert(usbGlitchLogIntervalMs(USB_GLITCH_FEEDBACK_UNSTUCK) == 1000);
    // B23-P2: OUTPUT_GAP is rate limited like the per-second kinds, not held to REAP_SLOW's 30 s summary.
    assert(USB_GLITCH_OUTPUT_GAP == 8);
    assert(usbGlitchLogIntervalMs(USB_GLITCH_OUTPUT_GAP) == 1000);
    // B41-fix1: a feedback transfer that was not a reading has its own kind, once a second like the rest.
    assert(USB_GLITCH_FEEDBACK_INVALID == 9);
    assert(usbGlitchLogIntervalMs(USB_GLITCH_FEEDBACK_INVALID) == 1000);
}

// ── B41-fix1 (A1a): is a completed feedback transfer a reading at all? ──────────────────────────────────────
//
// 2026-10-25, an outside investigation of the driver: both reads of the feedback endpoint (the one at a stream start and the
// continuous one) decoded the four bytes whenever `actual_length >= 4`, and neither looked at `urb->status` or at the packet's
// status. A transfer that delivers four bytes together with an error (-EILSEQ on the packet, -ECONNRESET on the URB) moved the
// rate as if it were a real reading. The verdict is the pure half of the fix; usb-audio-output.cpp applies it at both reads, and
// feedback-lifetime-test.cpp drives both through the real output.

static void expectAFeedbackTransferIsAReadingOnlyIfItIsClean() {
    assert(USB_FEEDBACK_BYTES == 4);   // UAC2 at high speed: one Q16.16 value; the URB asks for exactly this
    // a clean four-byte transfer is a reading
    assert(usbFeedbackPacketVerdict(0, 0, 4) == USB_FEEDBACK_PACKET_OK);
    // the URB's own status, whatever the packet and the length say (the cancel, the reset, the stall)
    assert(usbFeedbackPacketVerdict(-ECONNRESET, 0, 4) == USB_FEEDBACK_PACKET_URB_STATUS);
    assert(usbFeedbackPacketVerdict(-ENOENT, 0, 4) == USB_FEEDBACK_PACKET_URB_STATUS);
    assert(usbFeedbackPacketVerdict(-EPROTO, -EILSEQ, 4) == USB_FEEDBACK_PACKET_URB_STATUS);   // the URB is judged first
    // the packet's status with a clean URB: the kernel delivered bytes with an error
    assert(usbFeedbackPacketVerdict(0, -EILSEQ, 4) == USB_FEEDBACK_PACKET_PACKET_STATUS);
    assert(usbFeedbackPacketVerdict(0, -EOVERFLOW, 4) == USB_FEEDBACK_PACKET_PACKET_STATUS);
    assert(usbFeedbackPacketVerdict(0, -EXDEV, 0) == USB_FEEDBACK_PACKET_PACKET_STATUS);       // before the length
    // both clean, but not the four bytes asked for: nothing, short, long
    assert(usbFeedbackPacketVerdict(0, 0, 0) == USB_FEEDBACK_PACKET_LENGTH);
    assert(usbFeedbackPacketVerdict(0, 0, 3) == USB_FEEDBACK_PACKET_LENGTH);   // UAC1's 10.14 value is three bytes: not this driver's
    assert(usbFeedbackPacketVerdict(0, 0, 5) == USB_FEEDBACK_PACKET_LENGTH);
    // names (the log line and the dump use them)
    assert(strcmp(usbFeedbackPacketName(USB_FEEDBACK_PACKET_OK), "ok") == 0);
    assert(strcmp(usbFeedbackPacketName(USB_FEEDBACK_PACKET_URB_STATUS), "urb-status") == 0);
    assert(strcmp(usbFeedbackPacketName(USB_FEEDBACK_PACKET_PACKET_STATUS), "packet-status") == 0);
    assert(strcmp(usbFeedbackPacketName(USB_FEEDBACK_PACKET_LENGTH), "length") == 0);
    assert(strcmp(usbFeedbackPacketName(99), "?") == 0);
    assert(USB_FEEDBACK_PACKET_KIND_COUNT == 4);
}

// ── B23-P2: the output-gap model ────────────────────────────────────────────────────────────────────────────
//
// A writer on a high-speed ring of 80 URBs of 8 packets (1 ms of wire time each): the owner's wake of 2026-10-14
// 15:30:20 had the writer wait 175 ms for the oldest URB with 80 in flight. The simulation is the writer's own
// pattern: the ring is filled at the start, then one URB is submitted each time the oldest has played (1 ms).

static const int64_t kUrbUs = 8 * USB_AUDIO_PACKET_US;  // 1000
static const int kRing = 80;

struct GapRun {
    UsbOutputGapWatch w;
    int64_t now = 0;       // us
    int found = 0;
    UsbOutputGap first;
    int64_t lastCost[3] = {0, 0, 0};
    GapRun() { usbOutputGapWatchReset(&w); memset(&first, 0, sizeof(first)); }
    // One URB is submitted at the current time; the model sees it.
    void submit(bool announcedIdle = false) {
        UsbOutputGap g;
        memset(&g, 0, sizeof(g));
        if (usbOutputGapSubmit(&w, announcedIdle, now, kUrbUs, 0, &g)) {
            if (found++ == 0) first = g;
        }
    }
    // The ring is filled at the start: 80 URBs in ~1 ms of CPU, 12 us apart.
    void fill() {
        for (int i = 0; i < kRing; i++) {
            submit(i == 0);
            now += 12;
        }
    }
    // The steady cadence: the next URB goes in 1 ms after the one before, `n` times.
    void steady(int n) {
        for (int i = 0; i < n; i++) {
            now += kUrbUs;
            submit();
        }
    }
};

static void expectTheOwnersWakeIsOneGapWithItsNumbers() {
    GapRun r;
    r.fill();
    r.steady(120);
    assert(r.found == 0);  // a healthy ring is silent
    // 15:30:20.292: the oldest URB took 175 ms. The writer was inside the reap wait for 170 ms of it, 3 ms went
    // between two native writes (the Java side), 2 ms in the rest of the call.
    const int64_t before = r.now;
    usbOutputGapAddBetweenCalls(&r.w, 3000);
    usbOutputGapAddReap(&r.w, 170000);
    r.now += 175000;
    r.submit();
    assert(r.found == 1);
    assert(r.first.gapUs == r.now - before);
    assert(r.first.gapUs == 175000);
    // What the previous submit left queued was the ring's 80 ms (less the 12 us fill offset): the bus ran dry for the rest.
    assert(r.first.leadUs >= 79000 && r.first.leadUs <= 80000);
    assert(r.first.dryUs == r.first.gapUs - r.first.leadUs);
    assert(r.first.dryUs >= 95000 && r.first.dryUs <= 96000);
    assert(r.first.reapUs == 170000 && r.first.betweenCallsUs == 3000 && r.first.otherUs == 2000);
    assert(usbOutputGapWhere(&r.first) == USB_OUTPUT_GAP_IN_REAP);
    // One gap is one line: the model restarts from the late submit and the next URBs are healthy again.
    for (int i = 0; i < 200; i++) {
        r.steady(1);
    }
    assert(r.found == 1);
}

static void expectShortHolesWithReplenishmentAreQuiet() {
    GapRun r;
    r.fill();
    r.steady(50);
    // Ten times: the writer is held up for 20 ms (descheduled, a long GC pause, a binder call), and then refills the
    // ring with the 20 URBs that played meanwhile, back to back. The lead dips to ~60 ms and is restored.
    for (int round = 0; round < 10; round++) {
        r.now += 20 * kUrbUs;
        for (int i = 0; i < 20; i++) {
            r.submit();
            r.now += 12;
        }
        r.steady(80);
    }
    if (r.found != 0) {
        fprintf(stderr, "20 ms holes: %d gaps reported (first gap %lld us lead %lld us)\n", r.found,
                (long long)r.first.gapUs, (long long)r.first.leadUs);
        assert(false);
    }
}

static void expectTheLeadIsTheLimit() {
    // A hole as long as the lead is not yet a gap; one that is longer by 3 ms is, and says by how much.
    {
        GapRun r;
        r.fill();
        r.steady(30);
        const int64_t lead = r.w.playoutEndUs - r.now;
        r.now += lead;  // exactly the lead
        r.submit();
        assert(r.found == 0);
    }
    {
        GapRun r;
        r.fill();
        r.steady(30);
        const int64_t lead = r.w.playoutEndUs - r.now;
        r.now += lead + 3000;
        r.submit();
        assert(r.found == 1);
        assert(r.first.dryUs == 3000);
    }
    // The model's own jitter is not a gap: under USB_OUTPUT_GAP_MIN_DRY_US of dry time nothing is reported (the first URBs after a
    // start are 1 ms of lead each, and the next may come 1.2 ms later), at it the line is.
    {
        GapRun r;
        r.submit(true);                                   // a stream start: 1 ms of lead
        r.now += kUrbUs + 200;                            // the next URB is 0.2 ms late
        r.submit();
        assert(r.found == 0);
        GapRun q;
        q.fill();
        q.steady(30);
        const int64_t lead = q.w.playoutEndUs - q.now;
        q.now += lead + USB_OUTPUT_GAP_MIN_DRY_US - 1;
        q.submit();
        assert(q.found == 0);
        GapRun t;
        t.fill();
        t.steady(30);
        const int64_t lead2 = t.w.playoutEndUs - t.now;
        t.now += lead2 + USB_OUTPUT_GAP_MIN_DRY_US;
        t.submit();
        assert(t.found == 1);
    }
}

static void expectAnAnnouncedIdleIsNotAGap() {
    GapRun r;
    r.fill();
    r.steady(30);
    // A pause (the writer announces it) and a 3 s wait: the first URB after it starts the model again.
    r.now += 3000000;
    r.submit(true);
    assert(r.found == 0);
    r.steady(100);
    assert(r.found == 0);
    // ... and the model is armed again afterwards: a real hole right after is found.
    r.now += 300000;
    r.submit();
    assert(r.found == 1);
}

/**
 * B46-B1: an announced idle restarts the model from what the ring still holds, not from nothing. The first silence URB after a
 * pause's fade-out is such an idle while the ring holds ~40-76 ms more; restarted from zero, the model took any stall of 2 ms or more
 * for a dry bus (`lead=1 ms dry=gap-1`), seven times in the owner's session of 2026-10-29.
 */
static void expectAnAnnouncedIdleKeepsTheRingsRealLead() {
    const int64_t kRingLeadUs = 40000;   // what the ring still holds when the silence begins
    // (a) restarted from what the ring holds: a writer stalled 30 ms is not a gap, one stalled 45 ms is a gap of 5 ms
    {
        GapRun r;
        r.fill();
        r.steady(30);
        UsbOutputGap g;
        memset(&g, 0, sizeof(g));
        assert(!usbOutputGapSubmit(&r.w, true, r.now, kUrbUs, kRingLeadUs, &g));   // the announced idle itself is never a gap
        r.now += 30000;
        assert(!usbOutputGapSubmit(&r.w, false, r.now, kUrbUs, 0, &g));             // 30 ms later: 11 ms of lead left
        // the bus plays out 40 ms (the ring) + 1 ms (the first URB) + 1 ms (the second) from the idle; 55 ms later it is 85 ms in
        r.now += 55000;
        assert(usbOutputGapSubmit(&r.w, false, r.now, kUrbUs, 0, &g));
        assert(g.leadUs == 12000 && g.dryUs == 43000 && g.gapUs == 55000);          // the lead it ran out of is the ring's, not 1 ms
    }
    // (b) the same stall against a model restarted from zero (what HEAD did) is reported as dry from the first millisecond
    {
        GapRun r;
        r.fill();
        r.steady(30);
        UsbOutputGap g;
        memset(&g, 0, sizeof(g));
        assert(!usbOutputGapSubmit(&r.w, true, r.now, kUrbUs, 0, &g));
        r.now += 30000;
        assert(usbOutputGapSubmit(&r.w, false, r.now, kUrbUs, 0, &g));
        assert(g.leadUs == kUrbUs && g.dryUs == 30000 - kUrbUs);                    // lead=1 ms dry=29 ms
    }
    // (c) a 30 ms stall against the ring's real 40 ms: nothing to report
    {
        GapRun r;
        r.fill();
        r.steady(30);
        UsbOutputGap g;
        memset(&g, 0, sizeof(g));
        assert(!usbOutputGapSubmit(&r.w, true, r.now, kUrbUs, kRingLeadUs, &g));
        r.now += 30000;
        assert(!usbOutputGapSubmit(&r.w, false, r.now, kUrbUs, 0, &g));
    }
    // (d) a start has nothing queued: queuedLeadUs 0 and a negative value restart exactly as before
    {
        GapRun r;
        UsbOutputGap g;
        memset(&g, 0, sizeof(g));
        assert(!usbOutputGapSubmit(&r.w, true, 1000, kUrbUs, -5, &g));
        assert(r.w.playoutEndUs == 1000 + kUrbUs);
    }
}

// ── B46-C: `auto-early` ────────────────────────────────────────────────────────────────────────────

static void expectAutoEarlyIsAnOptInPolicyNextToAuto() {
    assert(USB_FEEDBACK_AUTO == 3 && USB_FEEDBACK_AUTO_EARLY == 4 && USB_FEEDBACK_POLICY_COUNT == 5);
    assert(strcmp(usbFeedbackPolicyName(USB_FEEDBACK_AUTO), "auto") == 0);
    assert(strcmp(usbFeedbackPolicyName(USB_FEEDBACK_AUTO_EARLY), "auto-early") == 0);
    assert(usbFeedbackPolicyRefuses(USB_FEEDBACK_AUTO) && usbFeedbackPolicyRefuses(USB_FEEDBACK_AUTO_EARLY));
    assert(!usbFeedbackPolicyRefuses(USB_FEEDBACK_FOLLOW) && !usbFeedbackPolicyRefuses(USB_FEEDBACK_NOMINAL) && !usbFeedbackPolicyRefuses(USB_FEEDBACK_CLAMP));
    // `auto` is exactly what it was: 5 s whatever the offset. Only `auto-early` and only from 0.5 % up is quicker.
    static const double kOffsets[] = {-20000.0, -8000.0, -600.0, 600.0, 4999.0, 8000.0, 20000.0};
    for (double ppm : kOffsets) {
        assert(usbFeedbackStuckHoldMs(USB_FEEDBACK_AUTO, ppm) == USB_FEEDBACK_STUCK_MIN_MS);
        assert(usbFeedbackStuckHoldMs(USB_FEEDBACK_FOLLOW, ppm) == USB_FEEDBACK_STUCK_MIN_MS);
        const bool big = ppm >= USB_FEEDBACK_EARLY_MIN_PPM || ppm <= -USB_FEEDBACK_EARLY_MIN_PPM;
        assert(usbFeedbackStuckHoldMs(USB_FEEDBACK_AUTO_EARLY, ppm) == (big ? USB_FEEDBACK_EARLY_STUCK_MS : USB_FEEDBACK_STUCK_MIN_MS));
    }
    // 500 ms of margin over the longest legitimate transient on record (3.0 s), and under `auto`'s 5 s
    assert(USB_FEEDBACK_EARLY_STUCK_MS >= 3000 + 500 && USB_FEEDBACK_EARLY_STUCK_MS < USB_FEEDBACK_STUCK_MIN_MS);
}

/** One scenario run under both policies, in lockstep, one reading a millisecond; returns the frames above nominal each sent. */
struct EarlyRun {
    double autoExcess = 0.0, earlyExcess = 0.0;       // (sent - nominal) frames per microframe, summed over the milliseconds; x 8 microframes = frames above nominal
    int64_t autoStuckAtMs = -1, earlyStuckAtMs = -1;  // when each called the plateau stuck (-1: never)
    bool earlyNeverFartherFromNominal = true;         // at every millisecond, auto-early's rate is nominal-ward of auto's or equal
    bool earlyWithinWindow = true;                    // and always inside the +-5 % window
};

static EarlyRun runBoth(double nominal, double plateauFpmf, int64_t plateauFromMs, int64_t plateauToMs, int64_t totalMs) {
    Feed a(nominal), e(nominal);
    e.policy = USB_FEEDBACK_AUTO_EARLY;
    EarlyRun r;
    for (int64_t t = 0; t < totalMs; t++) {
        const uint32_t raw = rawOf(t >= plateauFromMs && t < plateauToMs ? plateauFpmf : nominal);
        a.reading(raw);
        e.reading(raw);
        a.now++;
        e.now++;
        if (r.autoStuckAtMs < 0 && a.w.stuck) r.autoStuckAtMs = t;
        if (r.earlyStuckAtMs < 0 && e.w.stuck) r.earlyStuckAtMs = t;
        // What each sends for the next block: the last reading (followFpmf) through the policy, as the writer asks it.
        const double followed = raw / 65536.0;
        const double sentAuto = usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, followed, nominal, &a.w);
        const double sentEarly = usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO_EARLY, followed, nominal, &e.w);
        r.autoExcess += sentAuto - nominal;
        r.earlyExcess += sentEarly - nominal;
        if (fabs(sentEarly - nominal) > fabs(sentAuto - nominal) + 1e-12) r.earlyNeverFartherFromNominal = false;
        if (fabs(sentEarly / nominal - 1.0) > 0.05 + 1e-12) r.earlyWithinWindow = false;
    }
    return r;
}

static void expectAutoEarlyLetsGoSoonerAndKeepsFollowingATransient() {
    // frames above nominal per millisecond = (fpmf - nominal) x 8 microframes; the sum over the run is the surplus the packetizer was handed
    const double kMicroframesPerMs = 8.0;
    struct Case { const char *name; double nominal; double plateau; };
    // The replayed plateaus of analysis/audio-investigation-2026-10-25/code-study/policy-window-results.jsonl
    const Case shakira = {"Shakira 44.1 kHz", 44100 / 8000.0, 5.55749512};
    const Case crimen = {"Crimen 48 kHz", 48000 / 8000.0, 6.04992676};
    const Case replayed[] = {shakira, crimen};
    for (const Case &c : replayed) {
        // the plateau begins 0.2 s after the stream starts (the four plateaus on record: 0.16-0.21 s), and stays
        const EarlyRun r = runBoth(c.nominal, c.plateau, 200, 1000000, 10000);
        const double autoFrames = r.autoExcess * kMicroframesPerMs, earlyFrames = r.earlyExcess * kMicroframesPerMs;
        printf("auto-early %-18s plateau %.8f: auto stuck at %lld ms (%.0f frames above nominal), auto-early at %lld ms (%.0f frames)\n", c.name, c.plateau,
               (long long)r.autoStuckAtMs, autoFrames, (long long)r.earlyStuckAtMs, earlyFrames);
        assert(r.autoStuckAtMs == 200 + USB_FEEDBACK_STUCK_MIN_MS);              // the run is 5 s old: the reading before the 5001st is the 5000th
        assert(r.earlyStuckAtMs == 200 + USB_FEEDBACK_EARLY_STUCK_MS);
        assert(r.earlyNeverFartherFromNominal && r.earlyWithinWindow);
        assert(earlyFrames < autoFrames * 0.75 && earlyFrames > autoFrames * 0.60);   // 3.5 s of the 5: a good deal less
    }
    {   // the legitimate +1 % start transient of 2026-10-06 (5.5674, +0.996 %): 3 s, then nominal. Followed all the way, by both.
        const double nominal = 44100 / 8000.0;
        const EarlyRun r = runBoth(nominal, 5.5674, 200, 3200, 10000);
        assert(r.autoStuckAtMs < 0 && r.earlyStuckAtMs < 0);
        assert(r.earlyExcess == r.autoExcess);                                  // the same surplus, to the bit: not one reading was refused
        assert(fabs(r.earlyExcess * kMicroframesPerMs - 3000 * (5.5674 - nominal) * kMicroframesPerMs) < 1.0);   // all of it followed: ~1 317 frames
    }
    {   // up to the margin: a transient of 3.4 s is still followed, one of 3.6 s is called stuck at 3.5 s
        const double nominal = 6.0;
        EarlyRun shortRun = runBoth(nominal, 6.06, 200, 3600, 10000);          // 3.4 s
        assert(shortRun.earlyStuckAtMs < 0 && shortRun.earlyExcess == shortRun.autoExcess);
        EarlyRun longRun = runBoth(nominal, 6.06, 200, 3800, 10000);           // 3.6 s
        assert(longRun.earlyStuckAtMs == 200 + USB_FEEDBACK_EARLY_STUCK_MS && longRun.autoStuckAtMs < 0);
        assert(longRun.earlyExcess < longRun.autoExcess);
    }
    {   // a small plateau (+0.165 %, Hollywood 48 kHz, 6.0099) is below the early threshold: judged exactly as auto judges it, at 5 s
        const EarlyRun r = runBoth(6.0, 6.0099, 200, 1000000, 10000);
        assert(r.autoStuckAtMs == 200 + USB_FEEDBACK_STUCK_MIN_MS && r.earlyStuckAtMs == r.autoStuckAtMs && r.earlyExcess == r.autoExcess);
    }
    {   // Miko's +407 ppm (96 kHz, 12.00488281) is below the stuck threshold for both: followed, never stuck
        const EarlyRun r = runBoth(12.0, 12.00488281, 200, 1000000, 10000);
        assert(r.autoStuckAtMs < 0 && r.earlyStuckAtMs < 0 && r.earlyExcess == r.autoExcess);
    }
    {   // American Money, +4.99 % (5.7875 at 44.1 kHz): inside the window but absurd; refused from the first reading by both, as today
        const double nominal = 44100 / 8000.0;
        Feed e(nominal), a(nominal);
        e.policy = USB_FEEDBACK_AUTO_EARLY;
        e.reading(rawOf(5.7875));
        a.reading(rawOf(5.7875));
        assert(e.implausibleStarts == 1 && a.implausibleStarts == 1 && e.w.implausible && a.w.implausible);
        assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO_EARLY, 5.7875, nominal, &e.w) == usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, 5.7875, nominal, &a.w));
        assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO_EARLY, 5.7875, nominal, &e.w) == nominal);
    }
}

static void expectAutoEarlyNeverHoldsAHealthyDac() {
    // A healthy DAC: its nominal, the +0.27 % blip (one reading, ~1 in 50), now and then the +0.54 % one, for a minute.
    const double nominal = 6.0;
    Feed a(nominal), e(nominal);
    e.policy = USB_FEEDBACK_AUTO_EARLY;
    for (int64_t t = 0; t < 60000; t++) {
        double fpmf = nominal;
        if (t % 50 == 7) fpmf = nominal * 1.0027;
        if (t % 997 == 3) fpmf = nominal * 1.0054;
        a.reading(rawOf(fpmf));
        e.reading(rawOf(fpmf));
        a.now++;
        e.now++;
        assert(!e.w.stuck && !e.w.implausible);
        assert(usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO_EARLY, fpmf, nominal, &e.w) == fpmf);   // followed, every one of them
    }
    assert(e.stuckEvents == 0 && e.implausibleStarts == 0 && a.stuckEvents == 0);
    // Several seconds of one value AT nominal is healthy too (12.0000 for 152 s at 96 kHz in the log)
    Feed n(12.0);
    n.policy = USB_FEEDBACK_AUTO_EARLY;
    n.run(rawOf(12.0), 20000);
    assert(n.stuckEvents == 0);
}

static void expectTheGapSaysWhereTheTimeWent() {
    UsbOutputGap g;
    memset(&g, 0, sizeof(g));
    g.gapUs = 175000; g.reapUs = 170000; g.betweenCallsUs = 3000; g.otherUs = 2000;
    assert(usbOutputGapWhere(&g) == USB_OUTPUT_GAP_IN_REAP);
    g.reapUs = 5000; g.betweenCallsUs = 160000; g.otherUs = 10000;
    assert(usbOutputGapWhere(&g) == USB_OUTPUT_GAP_BETWEEN_WRITES);
    g.reapUs = 5000; g.betweenCallsUs = 10000; g.otherUs = 160000;
    assert(usbOutputGapWhere(&g) == USB_OUTPUT_GAP_IN_WRITE);
    assert(strcmp(usbOutputGapWhereName(USB_OUTPUT_GAP_IN_REAP), "in-reap") == 0);
    assert(strcmp(usbOutputGapWhereName(USB_OUTPUT_GAP_BETWEEN_WRITES), "between-writes") == 0);
    assert(strcmp(usbOutputGapWhereName(USB_OUTPUT_GAP_IN_WRITE), "in-write") == 0);

    // The buckets are what the writer measured between two submits and are cleared by every submit.
    GapRun r;
    r.fill();
    r.steady(10);
    usbOutputGapAddReap(&r.w, 400000);  // measured, then a submit that is not late: cleared by it
    r.steady(1);
    r.now += 150000;
    usbOutputGapAddBetweenCalls(&r.w, 148000);
    r.submit();
    assert(r.found == 1);
    assert(r.first.reapUs == 0 && r.first.betweenCallsUs == 148000 && r.first.otherUs == 2000);
    assert(usbOutputGapWhere(&r.first) == USB_OUTPUT_GAP_BETWEEN_WRITES);
    // More measured than the gap itself (clock noise) never makes a negative remainder.
    GapRun q;
    q.fill();
    q.steady(10);
    usbOutputGapAddReap(&q.w, 900000);
    q.now += 100000;
    q.submit();
    assert(q.found == 1 && q.first.reapUs == q.first.gapUs && q.first.betweenCallsUs == 0 && q.first.otherUs == 0);
}

static void expectOutputGapsAreRateLimitedLikeTheOthers() {
    GlitchCounter c;
    memset(&c, 0, sizeof(c));
    int64_t held = -1;
    const int64_t interval = usbGlitchLogIntervalMs(USB_GLITCH_OUTPUT_GAP);
    assert(glitchCount(&c, 5000, interval, &held) && held == 0);
    assert(!glitchCount(&c, 5200, interval, &held));
    assert(!glitchCount(&c, 5700, interval, &held));
    assert(glitchCount(&c, 6100, interval, &held) && held == 2);
}

// ── B24-Q3: the writer's own scheduler counters ───────────────────────────────────────────────────────────

static void expectSchedstatIsParsedStrictly() {
    UsbSchedSample s;
    assert(usbParseSchedstat("123456789 987654321 42\n", &s) && s.valid && s.cpuNs == 123456789ULL && s.runqNs == 987654321ULL);
    // the kernel's own shape without the newline (a pread of a file the buffer cut) is accepted; the third number only has to exist
    assert(usbParseSchedstat("1 2 3", &s) && s.valid && s.cpuNs == 1 && s.runqNs == 2);
    assert(usbParseSchedstat("0 0 0\n", &s) && s.valid && s.cpuNs == 0 && s.runqNs == 0);
    // the largest values a 64-bit counter holds
    assert(usbParseSchedstat("18446744073709551615 18446744073709551615 7\n", &s) && s.cpuNs == UINT64_MAX);
    // anything else is no reading, and says so
    const char *bad[] = {"", "\n", "1", "1 2", "1 2 x", "x 2 3", "1  2 3", "1 2 3 4", "-1 2 3", "1.5 2 3", "18446744073709551616 2 3", nullptr};
    for (int i = 0; bad[i] != nullptr; i++) {
        s.valid = true;
        assert(!usbParseSchedstat(bad[i], &s) && !s.valid);
    }
    assert(!usbParseSchedstat(nullptr, &s) && !s.valid);
}

static void expectTheBaselineIsRefreshedAtMostEveryTenMilliseconds() {
    UsbSchedBaseline b = {};
    assert(usbSchedBaselineDue(&b, 1000000));                       // none yet
    UsbSchedSample s = {true, 100, 5};
    usbSchedBaselineSet(&b, &s, 1000000);
    assert(!usbSchedBaselineDue(&b, 1000000));
    assert(!usbSchedBaselineDue(&b, 1000000 + USB_SCHED_BASELINE_EVERY_US - 1));
    assert(usbSchedBaselineDue(&b, 1000000 + USB_SCHED_BASELINE_EVERY_US));
    // a baseline without a valid sample is never a baseline
    UsbSchedBaseline none = {};
    UsbSchedSample invalid = {false, 0, 0};
    usbSchedBaselineSet(&none, &invalid, 5000000);
    assert(usbSchedBaselineDue(&none, 5000001));
}

static void expectTheDeltaSaysSleptFromRunnable() {
    UsbSchedBaseline b = {};
    UsbSchedSample start = {true, 10000000ULL, 2000000ULL};
    usbSchedBaselineSet(&b, &start, 1000000);

    // A. the writer slept through a 175 ms gap: it ran 1 ms and waited for a CPU 0.2 ms; the rest (~174 ms) it was asleep
    UsbSchedSample asleep = {true, 11000000ULL, 2200000ULL};
    UsbSchedDelta d = usbSchedDelta(&b, &asleep, 1000000 + 180000);
    assert(d.valid && d.cpuUs == 1000 && d.runqUs == 200 && d.overUs == 180000);
    assert(d.overUs - d.cpuUs - d.runqUs == 178800);

    // B. the writer was runnable the whole time and never got a CPU: 170 ms in the run queue, 2 ms on a CPU
    UsbSchedSample starved = {true, 12000000ULL, 172000000ULL};
    d = usbSchedDelta(&b, &starved, 1000000 + 180000);
    assert(d.valid && d.runqUs == 170000 + 0 && d.cpuUs == 2000);
    assert(d.runqUs * 10 >= d.overUs * 9);

    // no reading, no baseline, or counters that went backwards (another thread's, a reset) are not a delta
    UsbSchedSample none = {false, 0, 0};
    assert(!usbSchedDelta(&b, &none, 2000000).valid);
    UsbSchedBaseline nobase = {};
    assert(!usbSchedDelta(&nobase, &asleep, 2000000).valid);
    UsbSchedSample backwards = {true, 5000000ULL, 3000000ULL};
    assert(!usbSchedDelta(&b, &backwards, 2000000).valid);
    assert(!usbSchedDelta(&b, &asleep, 500000).valid);              // a clock that went back
}

// ── B47-A4: a refusal falls back to what the DAC said BEFORE the excursion ───────────────────────────────────────────────────────────
//
// 2026-10-29 17:32:33.841 and 17:35:25.833: `FEEDBACK_REJECTED ... kept=5.6224 sending=5.5974` and `kept=5.4025 sending=5.4125`, read as a fallback taken from
// the excursion's own first readings (+1.5 %, -1.8 %). They are not: `kept` is the last reading the follower took and `sending` is what the packetizer was given
// BEFORE the refusal (the ramp it had been following); the fallback is what `healthyOrNominal` makes of the average, and it is within 500 ppm of nominal by its gate.
// What was real is that a run inside the healthy band (up to +-0.5 %) was folded into that average the moment it ended, the excursion's first readings among
// them: a slow onset (300 ms at +0.3 % before the ramp) moved the fallback from +68 ppm to ~+200 ppm. Folds now wait USB_FEEDBACK_HEALTHY_SETTLE_MS and a refusal
// drops those that have not settled.

/** A healthy DAC for [ms]: nominal, a +0.27 % blip of one reading every 40 (the Melody's dither). */
static void healthyFor(Feed &f, int64_t ms) {
    for (int64_t i = 0; i < ms; i++) {
        f.reading(rawOf(i % 40 == 39 ? f.nominal * 1.0027 : f.nominal));
        f.now += 1;
    }
}

/** What `auto` sends right now (the follower is the last reading, as it is when nothing is refused). */
static double sentNow(const Feed &f, double followFpmf) {
    return usbFeedbackPolicyFpmf(USB_FEEDBACK_AUTO, followFpmf, f.nominal, &f.w);
}

/** The settled history of the excursion run is up to ~0.5 s of healthy readings longer than the reference's: a few ppm apart, against ~150 ppm of contamination. */
static const double kSettledPpm = 8.0;

static void expectARefusalFallsBackToBeforeTheExcursion() {
    const double nominal = 44100.0 / 8000.0;
    // Reference: the same healthy stretch, then an absurd reading at once: the fallback from a clean history.
    Feed clean(nominal);
    healthyFor(clean, 12000);
    clean.reading(rawOf(nominal * 1.0208));
    assert(clean.w.implausible);
    const double reference = sentNow(clean, nominal * 1.0208);
    const double referencePpm = usbFeedbackPpm(reference, nominal);
    printf("A4 reference: the fallback of a clean 12 s history is %.4f fpmf (%+.0f ppm from nominal)\n", reference, referencePpm);

    // (1) a slow onset: 300 ms at +0.30 % (inside the healthy band, so each run is folded when it ends), then the ramp, then the rail.
    {
        Feed f(nominal);
        healthyFor(f, 12000);
        f.run(rawOf(nominal * 1.0030), 300);
        f.run(rawOf(nominal * 1.0100), 60);
        f.run(rawOf(nominal * 1.0190), 60);
        f.reading(rawOf(nominal * 1.0208));
        f.now += 1;
        assert(f.w.implausible && f.implausible.implausibleWhy == USB_FEEDBACK_IMPLAUSIBLE_ABSURD);
        const double after = sentNow(f, nominal * 1.0208);
        printf("A4 slow onset (300 ms at +0.30 %% then +1.0, +1.9, +2.08 %%): after the refusal auto sends %.6f (%+.0f ppm); the clean history gives %.6f (%+.0f ppm)\n",
               after, usbFeedbackPpm(after, nominal), reference, referencePpm);
        assert(fabs(usbFeedbackPpm(after, nominal) - referencePpm) < kSettledPpm);    // the pre-excursion value (to the settled window)
        assert(fabs(usbFeedbackPpm(after, nominal)) <= fabs(referencePpm) + kSettledPpm);   // never further from nominal than it
        f.run(rawOf(nominal * 1.0499), 3000);                                         // the rail: nothing changes
        assert(fabs(usbFeedbackPpm(sentNow(f, nominal * 1.0499), nominal) - referencePpm) < kSettledPpm);
    }
    // (2) the shape of the log: nominal, a ramp of five readings, the +4.99 % rail; and the mirror, -5 %.
    for (int sign = 1; sign >= -1; sign -= 2) {
        Feed f(nominal);
        healthyFor(f, 12000);
        static const double kRamp[] = {0.0027, 0.0090, 0.0145, 0.0190};   // %: the readings the line showed (+0.27, +1.45, +1.9) and between
        double lastSent = nominal;
        double framesAbove = 0.0;
        int64_t rampStartMs = f.now;
        for (double step : kRamp) {
            const double fpmf = nominal * (1.0 + sign * step);
            for (int i = 0; i < 70; i++) {
                f.reading(rawOf(fpmf));
                lastSent = sentNow(f, fpmf);
                framesAbove += 8.0 * (lastSent - nominal);   // 8 microframes a ms, each (sent - nominal) frames over (an upper bound: the writer reads the rate per block)
                f.now += 1;
            }
        }
        const int64_t beforeRefusalMs = f.now - rampStartMs;
        const double sentJustBefore = lastSent;
        f.reading(rawOf(nominal * (1.0 + sign * 0.0208)));
        f.now += 1;
        assert(f.w.implausible);
        const double after = sentNow(f, nominal * (1.0 + sign * 0.0208));
        printf("A4 %s ramp: followed for %lld ms (up to %+.2f %%), frames scheduled above nominal while following %+.0f; after the refusal auto sends %+.0f ppm "
               "(the clean history: %+.0f ppm); the line's ramp=%lldms\n",
               sign > 0 ? "+" : "-", (long long)beforeRefusalMs, usbFeedbackPpm(sentJustBefore, nominal) / 1e4, framesAbove,
               usbFeedbackPpm(after, nominal), referencePpm, (long long)f.implausible.implausibleRampMs);
        assert(fabs(usbFeedbackPpm(after, nominal) - referencePpm) < kSettledPpm);
        assert(fabs(usbFeedbackPpm(after, nominal)) <= fabs(referencePpm) + kSettledPpm);
        f.run(rawOf(nominal * (1.0 + sign * 0.0499)), 3000);
        assert(fabs(usbFeedbackPpm(sentNow(f, nominal * (1.0 + sign * 0.0499)), nominal) - referencePpm) < kSettledPpm);
        // B61: with the mid-stream line back at the ordinary 2 % (B60 review, owner OK), the +0.90 % and +1.45 % readings are followed again and the refusal comes at +2 % some 200 ms later, so
        // the line can say how long the feedback had been out of the band (B59 had made this 0 with its 7 500 line; B58's assertion is back).
        assert(f.implausible.implausibleRampMs > 0);
    }
    // (3) a healthy DAC never refuses, so the settle changes nothing for it: what `auto` sends is the follower, reading for reading
    {
        Feed f(nominal);
        for (int64_t i = 0; i < 30000; i++) {
            const double fpmf = i % 40 == 39 ? nominal * 1.0027 : nominal;
            f.reading(rawOf(fpmf));
            assert(!f.w.implausible && !f.w.stuck);
            assert(sentNow(f, fpmf) == fpmf);
            f.now += 1;
        }
    }
    // (4) the fresh folds are bounded and settle: 100 short runs inside one second never overflow the table
    {
        Feed f(nominal);
        for (int64_t i = 0; i < 4000; i++) {
            f.reading(rawOf(i % 2 ? nominal : nominal * 1.0010));   // a new run every reading
            f.now += 1;
            assert(f.w.freshCount <= USB_FEEDBACK_FRESH_CAPACITY);
        }
        f.idle(3000);
        f.reading(rawOf(nominal));
        assert(f.w.freshCount <= 1);   // everything older than the settle time is in the average
    }
}

// ── B47-A5: the lock-up detector ───────────────────────────────────────────────────────────────────────────────────────────────────────
//
// It must fire on 2026-10-29's rail (and on 2026-10-25's), after USB_DAC_LOCK_RAIL_FAST_MS (B61; 1 500 ms before), and on nothing a healthy Melody does.

struct LockFeed {
    UsbDacLockWatch w;
    double nominal;
    int64_t now = 0;
    int fires = 0;
    int64_t firedAtMs = -1;
    UsbDacLockEvent last;
    explicit LockFeed(double nominalFpmf) : nominal(nominalFpmf) {
        usbDacLockReset(&w);
        memset(&last, 0, sizeof(last));
    }
    void reading(double fpmf) {
        UsbDacLockEvent ev;
        if (usbDacLockObserve(&w, usbFeedbackPpm(fpmf, nominal), now, &ev)) {
            fires++;
            firedAtMs = now;
            last = ev;
        }
    }
    void run(double fpmf, int64_t ms) {
        for (int64_t i = 0; i < ms; i++) { reading(fpmf); now += 1; }
    }
};

static void expectTheLockDetectorFiresOnARailAndOnNothingElse() {
    const double n441 = 44100.0 / 8000.0;
    // the rail of 17:32:34: 5.7875 (+4.99 %), constant; fires once, USB_DAC_LOCK_RAIL_FAST_MS in (B61; 1 500 ms before), with the numbers the line needs
    {
        LockFeed f(n441);
        f.run(n441, 5000);                          // a healthy start
        const int64_t railFrom = f.now;
        f.run(5.7875, USB_DAC_LOCK_RAIL_FAST_MS);   // 200 readings, the last one 199 ms after the first: not yet
        assert(f.fires == 0);
        f.run(5.7875, 1);
        assert(f.fires == 1 && f.firedAtMs - railFrom == USB_DAC_LOCK_RAIL_FAST_MS);
        assert(f.last.railedMs == USB_DAC_LOCK_RAIL_FAST_MS && f.last.readings == USB_DAC_LOCK_RAIL_FAST_MS + 1 && f.last.ppm > 49000.0);
        usbDacLockLateReap(&f.w);
        usbDacLockLateReap(&f.w);
        f.run(5.7875, 180000);                      // three more minutes of the rail: still one fire
        assert(f.fires == 1 && f.w.locked && f.w.lateReaps == 2);
        // the flip to -5 % (5.2374) is the same run
        f.run(5.2374, 5000);
        assert(f.fires == 1 && f.w.locked);
        assert(usbDacLockRailedMs(&f.w, f.now) > 180000);
        // a reading that is a clock's again ends it, and a new rail fires again
        f.reading(n441);
        f.now += 1;
        assert(!f.w.locked && !f.w.inRun && usbDacLockRailedMs(&f.w, f.now) == 0);
        f.run(5.7875, 2000);
        assert(f.fires == 2);
    }
    {   // the mirror alone, and the 10-25 pair of values: 5.6274 / 5.7875 then 5.3975 / 5.2374 (the first two are not rails, the others are)
        LockFeed f(n441);
        f.run(5.6274, 5000);                        // +2.09 %: refused by `auto`, but not a rail
        assert(f.fires == 0);
        f.run(5.2374, 1600);
        assert(f.fires == 1 && f.last.ppm < -49000.0);
        assert(f.last.railedMs == USB_DAC_LOCK_RAIL_FAST_MS);                                     // 5 s into the stream: 200 ms of rail
    }
    // never on the healthy Melody's shapes
    {   LockFeed f(n441); f.run(n441, 2000); f.run(5.5674, 3000); f.run(n441, 2000); assert(f.fires == 0); }          // 10-06: +0.996 % for 3 s
    {   LockFeed f(48000.0 / 8000.0); f.run(6.0499, 9000); assert(f.fires == 0); }                                     // Crimen: +0.832 % for 9 s
    {   LockFeed f(n441); f.run(5.5575, 416000); assert(f.fires == 0); }                                               // Ciega, Sordomuda: +0.816 % for 416 s
    {   LockFeed f(96000.0 / 8000.0); f.run(12.0 * (1.0 + 407e-6), 600000); assert(f.fires == 0); }                    // Miko: +407 ppm
    {   // one-reading blips, the largest seen (+1.904 %, +1.45 %) among the usual +0.27 %, a hundred a second, for a minute
        LockFeed f(n441);
        for (int64_t i = 0; i < 60000; i++) {
            const double fpmf = i % 25 == 0 ? n441 * 1.0190 : i % 10 == 0 ? n441 * 1.0145 : i % 3 == 0 ? n441 * 1.0027 : n441;
            f.reading(fpmf);
            f.now += 1;
        }
        assert(f.fires == 0);
    }
    {   // even a rail of one reading in ten, or a run of 199 ms (B61: the time is 200 ms), is not a lock-up
        LockFeed f(n441);
        for (int64_t i = 0; i < 20000; i++) { f.reading(i % 10 == 0 ? 5.7875 : n441); f.now += 1; }
        assert(f.fires == 0);
        f.run(5.7875, USB_DAC_LOCK_RAIL_FAST_MS - 1);
        f.reading(n441);
        f.now += 1;
        f.run(5.7875, USB_DAC_LOCK_RAIL_FAST_MS - 1);
        assert(f.fires == 0);                       // each run is under the time, and a clock's reading between them ends the first
    }
    {   // a pause between two readings (no one reaps the endpoint) starts the count over, and the stream's first moment with it (B61)
        LockFeed f(n441);
        f.run(5.7875, USB_DAC_LOCK_START_GRACE_MS + USB_DAC_LOCK_RAIL_FAST_MS - 100);   // under the time from a stream's first reading
        f.now += 1000;                               // 1 s without readings
        f.run(5.7875, USB_DAC_LOCK_START_GRACE_MS + USB_DAC_LOCK_RAIL_FAST_MS);
        assert(f.fires == 0);                        // inside the first moment after the pause and 200 ms after it: not yet (the count started over)
        f.run(5.7875, 1);
        assert(f.fires == 1);
    }
    {   // late completions only count inside a run
        LockFeed f(n441);
        usbDacLockLateReap(&f.w);
        assert(f.w.lateReaps == 0);
        f.run(5.7875, 10);
        usbDacLockLateReap(&f.w);
        assert(f.w.lateReaps == 1);
    }
    printf("A5 detector: fires %d ms into a rail beyond +-%.1f %% (+4.99 %% and -5 %% alike), once per run; silent on the 10-06 +1 %% / 3 s plateau, Crimen +0.83 %%, "
           "Ciega +0.82 %% for 416 s, Miko +407 ppm, one-reading blips up to +1.9 %%, runs of 199 ms, and a pause in the readings\n",
           USB_DAC_LOCK_RAIL_FAST_MS, USB_DAC_LOCK_RAIL_PPM / 1e4);
}

/** B47-A1: only a writer that sat 20 ms or more in the run queue earns the extra line. */
static void expectTheCpuLineIsForARealStall() {
    UsbSchedDelta d = {true, 0, 82000, 94000};   // 2026-10-29 17:32:33: runq=82 ms cpu=0 ms over=94 ms
    assert(usbGapWantsCpuLine(&d));
    d.runqUs = USB_GAP_CPU_MIN_RUNQ_US;
    assert(usbGapWantsCpuLine(&d));
    d.runqUs = USB_GAP_CPU_MIN_RUNQ_US - 1;
    assert(!usbGapWantsCpuLine(&d));
    d.runqUs = 12000;                            // the session's worst ordinary stall
    assert(!usbGapWantsCpuLine(&d));
    d.valid = false;
    d.runqUs = 82000;
    assert(!usbGapWantsCpuLine(&d));             // no counters, no claim
    assert(usbGlitchLogIntervalMs(USB_GLITCH_OUTPUT_GAP_CPU) == 1000);   // rate limited like the others
}

// ── B59 5: the 48 kHz stick (#46) and the mid-song ramp (#39) ─────────────────────────────────────────

/**
 * #46, 2026-11-02 18:33:59: 44.1 -> 48 kHz, the feedback froze at +5 819 ppm. A value frozen that far from nominal, identical for 400 ms inside the rate-change window, is called stuck
 * (with `rateChangeHold`), not at 1 500 ms; a nearer one keeps the 1 500 ms; a slow reaper (too few readings) keeps it too; and a DAC that answers its new rate (readings that move) never fires.
 */
static void expectAFarFrozenValueAfterARateChangeIsCalledAtFourHundredMs() {
    {   // the device's numbers: nominal 6.0 (48 kHz), frozen at +5 819 ppm
        Feed f(6.0);
        f.w.rateChangeStart = true;
        f.w.streamStartMs = 0;
        f.run(rawOf(6.0 * (1.0 + 5819e-6)), 2000);
        assert(f.stuckEvents == 1);
        assert(f.stuck.rateChangeHold);
        assert(f.stuck.heldMs == USB_FEEDBACK_RATE_CHANGE_FAR_STUCK_MS);
        assert(f.stuck.readings >= USB_FEEDBACK_RATE_CHANGE_FAR_MIN_READINGS);
        assert(USB_FEEDBACK_RATE_CHANGE_FAR_STUCK_MS >= 300 && USB_FEEDBACK_RATE_CHANGE_FAR_STUCK_MS <= 500);     // inside what the log says is enough
    }
    {   // the healthy plateau of a rate change (+0.996 % = 9 960 ppm, 3 s and more) is not far enough to be called early: it keeps the 1 500 ms (and the 7 999 / 8 001 ppm edge)
        static const double kPlateaus[] = {9960.0, 10000.0, 8001.0};
        for (double ppm : kPlateaus) {
            Feed f(6.0);
            f.w.rateChangeStart = true;
            f.w.streamStartMs = 0;
            f.run(rawOf(6.0 * (1.0 + ppm * 1e-6)), 2000);
            assert(f.stuckEvents == 1 && f.stuck.heldMs == USB_FEEDBACK_RATE_CHANGE_STUCK_MS);
        }
        Feed edge(6.0);
        edge.w.rateChangeStart = true;
        edge.w.streamStartMs = 0;
        edge.run(rawOf(6.0 * (1.0 + 7990e-6)), 2000);
        assert(edge.stuckEvents == 1 && edge.stuck.heldMs == USB_FEEDBACK_RATE_CHANGE_FAR_STUCK_MS);
        // the three stuck values ever seen after a rate change: 3 316 (1 500 ms), 5 819 and 6 653 (400 ms)
        static const double kStuck[] = {5819.0, 6653.0};
        for (double ppm : kStuck) {
            Feed f(6.0);
            f.w.rateChangeStart = true;
            f.w.streamStartMs = 0;
            f.run(rawOf(6.0 * (1.0 + ppm * 1e-6)), 2000);
            assert(f.stuckEvents == 1 && f.stuck.heldMs == USB_FEEDBACK_RATE_CHANGE_FAR_STUCK_MS);
        }
    }
    {   // a frozen value nearer than 5 000 ppm: the 1 500 ms of B50
        Feed f(6.0);
        f.w.rateChangeStart = true;
        f.w.streamStartMs = 0;
        f.run(rawOf(6.0 * (1.0 + 3000e-6)), 2000);
        assert(f.stuckEvents == 1 && f.stuck.heldMs == USB_FEEDBACK_RATE_CHANGE_STUCK_MS);
    }
    {   // far, but a reaper that delivers a reading every 4 ms (100 in 400 ms): 400 ms is not enough readings, so it waits for 250 of them (1 000 ms here), not for the 1 500
        Feed f(6.0);
        f.w.rateChangeStart = true;
        f.w.streamStartMs = 0;
        const uint32_t raw = rawOf(6.0 * (1.0 + 5819e-6));
        for (int i = 0; i < 700; i++) { f.reading(raw); f.now += 4; }
        assert(f.stuckEvents == 1);
        assert(f.stuck.readings >= USB_FEEDBACK_RATE_CHANGE_FAR_MIN_READINGS && f.stuck.heldMs > USB_FEEDBACK_RATE_CHANGE_FAR_STUCK_MS && f.stuck.heldMs < USB_FEEDBACK_RATE_CHANGE_STUCK_MS);
    }
    {   // not a rate change, or past its 3 s window: the ordinary 5 s
        Feed f(6.0);
        f.run(rawOf(6.0 * (1.0 + 5819e-6)), 4000);
        assert(f.stuckEvents == 0);
        f.run(rawOf(6.0 * (1.0 + 5819e-6)), 1500);
        assert(f.stuckEvents == 1 && f.stuck.heldMs == USB_FEEDBACK_STUCK_MIN_MS && !f.stuck.rateChangeHold);
    }
    {   // a DAC that answers its new rate: the readings keep moving by a few tenths of a percent, however far out the average sits
        Feed f(6.0);
        f.w.rateChangeStart = true;
        f.w.streamStartMs = 0;
        for (int i = 0; i < 3000; i++) { f.reading(rawOf(6.0 * (1.0 + 6000e-6 + (i % 7) * 40e-6))); f.now += 1; }
        assert(f.stuckEvents == 0);
    }
}

/**
 * B61 (item 2.1), the revert of B59's 7 500 mid-stream line, gated against the device. 2026-11-07 00:52-01:06 (B60-review/logcat-live-0051.txt): at 48 kHz the Melody refills its FIFO in routine
 * bursts every ~3 s, 6.0000 -> 6.0149 -> 6.0449 -> 6.0499 (+8 321 ppm) and back within ~70 ms, and at 44.1 kHz up to 5.5575 (+8 162 ppm). The 7 500 line refused the top of every burst (~170 times);
 * with the app sending exactly nominal at that moment the DAC then climbed to the rail (eight locks, ~1.8 s of pause each). The line is the ordinary 2 % again: a burst is **followed**, reading
 * for reading, and the Melody's start ramp (+2.08 %) is still refused at 2 %. The cost (the owner's choice, 11-07: "a short shh now and then is better than a pause") is #39's small pop, which the
 * 7 500 line had cut: the ramp is followed up to +1.9 % again.
 */
static void expectTheMelodysRefillBurstIsFollowedAndTheRampIsStillRefusedAtTwoPercent() {
    struct Dac { const char *name; double nominal; double steps[4]; double topPpm; };
    static const Dac kDacs[] = {
        {"48 kHz", 6.0, {6.0149, 6.0199, 6.0449, 6.0499}, 8321.0},
        {"44.1 kHz", 44100.0 / 8000.0, {5.5275, 5.5425, 5.5525, 5.5575}, 8162.0},
    };
    for (const Dac &d : kDacs) {
        // 40 s of continuous readings (well past the 10 s that used to make a stream "mid-stream"), a burst every 3 s: nothing is ever refused, and `auto` sends what the DAC asks
        Feed f(d.nominal);
        double largestPpm = 0.0;
        int bursts = 0, refusedBy7500 = 0;                                                       // the line B59 had: 7 500 ppm
        for (int64_t t = 0; t < 40000; t++) {
            const int64_t phase = t % 3000;
            const double fpmf = phase < 20 ? d.steps[0] : phase < 40 ? d.steps[1] : phase < 55 ? d.steps[2] : phase < 125 ? d.steps[3] : d.nominal;
            f.reading(rawOf(fpmf));
            assert(!f.w.implausible);
            assert(fabs(sentNow(f, rawOf(fpmf) / 65536.0) - rawOf(fpmf) / 65536.0) < 1e-12);     // followed, reading for reading, not held at nominal
            const double ppm = usbFeedbackPpm(rawOf(fpmf) / 65536.0, d.nominal);
            if (ppm > largestPpm) largestPpm = ppm;
            if (phase == 60) { bursts++; if (ppm > 7500.0) refusedBy7500++; }                    // the top of each burst
            f.now += 1;
        }
        assert(f.implausibleStarts == 0 && f.stuckEvents == 0);
        assert(bursts == 14 && refusedBy7500 == bursts);                                         // every burst top was beyond B59's line
        assert(fabs(largestPpm - d.topPpm) < 25.0);                                              // the burst top really was in the replay
        printf("B61 burst %s: top +%.0f ppm followed on every one of %d bursts, never refused (B59's 7 500 line would have refused %d)\n", d.name, largestPpm, bursts, refusedBy7500);
    }
    assert(USB_FEEDBACK_MIDSTREAM_ABSURD_PPM > 2.0 * 8321.0);                                    // no future line within a factor of two of the largest burst seen
    assert(USB_FEEDBACK_MIDSTREAM_ABSURD_PPM == USB_FEEDBACK_ABSURD_PPM);
    const double nominal = 44100.0 / 8000.0;
    {   // the Melody's own start ramp mid-song, +0 -> +2.08 % over 170 ms: followed to +2 %, then refused (the 2 % line), as before B59
        Feed f(nominal);
        f.run(rawOf(nominal), 12000);
        double largestFollowedPpm = 0.0;
        for (int i = 0; i <= 170; i++) {
            const double ppm = 20800.0 * i / 170.0;
            f.reading(rawOf(nominal * (1.0 + ppm * 1e-6)));
            f.now += 1;
            if (!f.w.implausible && ppm > largestFollowedPpm) largestFollowedPpm = ppm;
        }
        assert(f.implausibleStarts == 1 && f.w.implausibleWhy == USB_FEEDBACK_IMPLAUSIBLE_ABSURD);
        assert(f.implausible.implausiblePpm > USB_FEEDBACK_ABSURD_PPM && f.implausible.implausiblePpm < USB_FEEDBACK_ABSURD_PPM + 400.0);
        assert(largestFollowedPpm > 19000.0 && largestFollowedPpm <= USB_FEEDBACK_ABSURD_PPM);   // #39 is back: followed up to +1.9 %
    }
    {   // a start transient (+1.0 % for 3 s) and a resume after a pause are followed, and past 2 % is refused whenever
        Feed f(nominal);
        f.run(rawOf(nominal * 1.0100), 3000);
        assert(f.implausibleStarts == 0 && !f.w.implausible);
        Feed g(nominal);
        g.run(rawOf(nominal), 12000);
        g.idle(1000);
        g.run(rawOf(nominal * 1.0100), 2000);
        assert(g.implausibleStarts == 0 && !g.w.implausible);
        Feed h(nominal);
        h.run(rawOf(nominal * 1.0250), 5);
        assert(h.w.implausible && h.w.implausibleWhy == USB_FEEDBACK_IMPLAUSIBLE_ABSURD);
    }
}

// ── B61 item 2.2: a rail is called in 200 ms, and a stream's first moment is not a rail ────────────────────────────────────────────

/** The 11-07 lock-ups as readings: the burst top, then the DAC's request climbing in a straight line (0.5 fpmf/s) from where the refusal left it. [fpmf at t ms]. */
static double runawayFpmf(double fromFpmf, int64_t tMs) { return fromFpmf + 0.5115 * (double)tMs / 1000.0; }

static void expectTheEightRailLocksOf1107AreCalledInTwoHundredMs() {
    struct Lock { const char *name; double nominal; double burstTop; const char *firstOutOfWindow; };
    static const Lock kLocks[] = {
        {"48 kHz 00:53:50 / 01:00:32 / 01:01:13 (first reading beyond the window 6.3049, +50 822 ppm)", 6.0, 6.0499, "10 4E 06 00"},
        {"44.1 kHz 01:04:05 / 01:04:27 / 01:04:58 / 01:05:40 / 01:06:02 (5.7925, +50 790 ppm)", 44100.0 / 8000.0, 5.5575, "E0 CA 05 00"},
    };
    for (const Lock &l : kLocks) {
        LockFeed f(l.nominal);
        f.run(l.nominal, 30000);                          // 20-100 s into the stream in the log: well past the first moment
        for (int64_t t = 0; t < 20; t++) { f.reading(l.burstTop); f.now += 1; }
        // the climb from the burst top; the log's samples: +5.08 % at 0.51 s, +8.67 % at 0.93 s, +17.1 % at 1.93 s
        const int64_t climbFrom = f.now;
        int64_t railFromMs = -1;
        for (int64_t t = 0; t < 2000 && f.fires == 0; t++) {
            const double fpmf = runawayFpmf(l.burstTop, t);
            if (railFromMs < 0 && usbFeedbackPpm(fpmf, l.nominal) >= USB_DAC_LOCK_RAIL_PPM) railFromMs = f.now;
            f.reading(fpmf);
            f.now += 1;
        }
        assert(f.fires == 1 && railFromMs >= 0);
        const int64_t detected = f.firedAtMs - railFromMs;
        assert(detected == USB_DAC_LOCK_RAIL_FAST_MS);                                           // 200 ms, from the first reading beyond +4.5 %
        assert(f.last.railedMs == USB_DAC_LOCK_RAIL_FAST_MS && f.last.ppm > USB_DAC_LOCK_RAIL_PPM && f.last.ppm < 70000.0);   // still near the line: a rail, not yet +17 %
        printf("B61 rail lock %s: climb began at +%lld ms, beyond +4.5 %% from +%lld ms, called at +%lld ms (200 ms of rail; the 1 500 ms rule called it at +%lld ms)\n", l.name,
               0LL, (long long)(railFromMs - climbFrom), (long long)(f.firedAtMs - climbFrom), (long long)(railFromMs - climbFrom + 1500));   // 1 500 ms: the hold before B61
        (void)l.firstOutOfWindow;
    }
}

static void expectAStreamsFirstMomentDoesNotCountAndNothingHealthyIsARail() {
    const double n48 = 6.0;
    {   // 10-31 00:12: 48 kHz after 44.1, the DAC answers the previous rate's value (5.51245, -81 258 ppm) for ~40 ms, then the new rate: not a lock, with or without the grace
        LockFeed f(n48);
        f.run(5.51245, 40);
        f.run(n48 * 1.006653, 3000);
        assert(f.fires == 0);
    }
    {   // the same stale answer lasting 450 ms (11 times what was seen), at the very start of a stream: nothing in the first moment counts, so it is still no lock
        LockFeed f(n48);
        f.run(5.51245, 450);
        f.run(n48, 3000);
        assert(f.fires == 0);
    }
    {   // a rail from the first reading of a stream is still called: 200 ms counted from the end of the first moment, i.e. at 700 ms (it was 1 500 before B61)
        LockFeed f(n48);
        f.run(n48 * 1.0499, USB_DAC_LOCK_START_GRACE_MS + USB_DAC_LOCK_RAIL_FAST_MS);
        assert(f.fires == 0);
        f.run(n48 * 1.0499, 1);
        assert(f.fires == 1 && f.firedAtMs == USB_DAC_LOCK_START_GRACE_MS + USB_DAC_LOCK_RAIL_FAST_MS && f.last.railedMs == f.firedAtMs);
        assert(USB_DAC_LOCK_START_GRACE_MS + USB_DAC_LOCK_RAIL_FAST_MS < 1500);
    }
    {   // the first moment starts again after a pause, like the continuous count: 12 s of readings, 1 s of silence, then a rail
        LockFeed f(n48);
        f.run(n48, 12000);
        f.now += 1000;
        f.run(n48 * 1.0499, USB_DAC_LOCK_START_GRACE_MS + USB_DAC_LOCK_RAIL_FAST_MS);            // inside the new first moment and 200 ms after it: not yet
        assert(f.fires == 0);
        f.run(n48 * 1.0499, 1);
        assert(f.fires == 1);
    }
    {   // never on what a healthy Melody does: the burst (+8 321 ppm) held for a second, the 3 s start plateau (+1.0 %), the start ramp (+2.08 %) for 170 ms, and 199 ms of rail
        LockFeed f(n48);
        f.run(n48, 5000);
        f.run(6.0499, 1000);
        f.run(n48, 100);
        f.run(n48 * 1.0100, 3000);
        f.run(n48 * 1.0208, 170);
        f.run(n48, 100);
        assert(f.fires == 0);
        f.run(n48 * 1.0499, USB_DAC_LOCK_RAIL_FAST_MS - 1);
        f.reading(n48);
        f.now += 1;
        f.run(n48 * 1.0499, USB_DAC_LOCK_RAIL_FAST_MS - 1);                                       // two runs of 199 ms with a clock's reading between them
        assert(f.fires == 0);
    }
    assert(USB_DAC_LOCK_RAIL_FAST_MS >= 150 && USB_DAC_LOCK_RAIL_FAST_MS <= 250);                // the prompt's window
    assert(USB_DAC_LOCK_START_GRACE_MS >= 10 * 40);                                              // ten times the 40 ms of stale readings seen
    assert(USB_DAC_LOCK_RAIL_PPM > 2.0 * 20800.0);                                               // over twice the Melody's start ramp
}

/**
 * B61 (item 2.2): the start lock of 2026-11-07 10:12:43 (96 -> 44.1 kHz, frozen at 5.5724 = +10 864 ppm, called at 1 500 ms) is called at 400 ms, and the healthy plateau of that
 * rate change (+9 960 ppm on 10-06, +9 070 the Melody's own request after that very restart, +10 000 in the replays) keeps the 1 500 ms.
 */
static void expectTheStartLockOf1107IsCalledAtFourHundredMsAndTheHealthyPlateauIsNot() {
    const double n441 = 44100.0 / 8000.0;
    {   // the device's numbers: 44.1 kHz, frozen at 5.5724 from the first reading of the rate-change stream
        Feed f(n441);
        f.w.rateChangeStart = true;
        f.w.streamStartMs = 0;
        f.run(rawOf(5.5724), 2000);
        assert(f.stuckEvents == 1 && f.stuck.rateChangeHold);
        assert(f.stuck.heldMs == USB_FEEDBACK_RATE_CHANGE_FAR_STUCK_MS && f.stuck.readings >= USB_FEEDBACK_RATE_CHANGE_FAR_MIN_READINGS);
        assert(f.stuck.ppm > 10800.0 && f.stuck.ppm < 10900.0);
        printf("B61 start lock 10:12:43 (96 -> 44.1 kHz, 5.5724 = %+.0f ppm): called after %lld ms (was 1500)\n", f.stuck.ppm, (long long)f.stuck.heldMs);
    }
    {   // the plateaus that end by themselves keep the slow hold: +9 070, +9 960, +10 000 and the edge 10 499; and the values just below and above the line
        static const double kHealthy[] = {9070.0, 9960.0, 10000.0, 10499.0, 8001.0};
        for (double ppm : kHealthy) {
            Feed f(n441);
            f.w.rateChangeStart = true;
            f.w.streamStartMs = 0;
            f.run(rawOf(n441 * (1.0 + ppm * 1e-6)), 1400);
            assert(f.stuckEvents == 0);                                                          // a plateau of 1.4 s is not called (the guard)
            f.run(rawOf(n441 * (1.0 + ppm * 1e-6)), 600);
            assert(f.stuckEvents == 1 && f.stuck.heldMs == USB_FEEDBACK_RATE_CHANGE_STUCK_MS);   // 3 s of it is (B50's accepted restart), at 1 500 ms as before
        }
        static const double kFar[] = {10500.0, 10864.0, 15000.0, 19999.0};
        for (double ppm : kFar) {
            Feed f(n441);
            f.w.rateChangeStart = true;
            f.w.streamStartMs = 0;
            f.run(rawOf(n441 * (1.0 + ppm * 1e-6)), 2000);
            assert(f.stuckEvents == 1 && f.stuck.heldMs == USB_FEEDBACK_RATE_CHANGE_FAR_STUCK_MS);
        }
        Feed past(n441);                                                                         // past the line `auto` refuses at any moment: nothing is followed, so the slow hold
        past.w.rateChangeStart = true;
        past.w.streamStartMs = 0;
        past.run(rawOf(n441 * 1.0210), 2000);
        assert(past.stuckEvents == 1 && past.stuck.heldMs == USB_FEEDBACK_RATE_CHANGE_STUCK_MS);
    }
    {   // not a rate change: the same value keeps the ordinary 5 s, and a DAC that answers its new rate (the readings move) never fires
        Feed f(n441);
        f.run(rawOf(5.5724), 4000);
        assert(f.stuckEvents == 0);
        Feed g(n441);
        g.w.rateChangeStart = true;
        g.w.streamStartMs = 0;
        for (int i = 0; i < 3000; i++) { g.reading(rawOf(n441 * (1.0 + 10900e-6 + (i % 7) * 40e-6))); g.now += 1; }
        assert(g.stuckEvents == 0);
    }
    assert(USB_FEEDBACK_RATE_CHANGE_FAR_ABOVE_PPM > 10000.0 * 1.04 && USB_FEEDBACK_RATE_CHANGE_FAR_ABOVE_PPM < 10864.0 * 0.97);
}

int main() {
    expectTheCpuLineIsForARealStall();
    expectSchedstatIsParsedStrictly();
    expectTheBaselineIsRefreshedAtMostEveryTenMilliseconds();
    expectTheDeltaSaysSleptFromRunnable();
    expectRingStarvationIsCountedWhateverItsLength();
    expectRateLimit();
    expectReapSlowIsQuiet();
    expectDeviation();
    expectFeedbackKeepsUpAfterStart();
    expectNewKindsAreRegistered();
    expectAFeedbackTransferIsAReadingOnlyIfItIsClean();
    expectTheOwnersWakeIsOneGapWithItsNumbers();
    expectShortHolesWithReplenishmentAreQuiet();
    expectTheLeadIsTheLimit();
    expectAnAnnouncedIdleIsNotAGap();
    expectAnAnnouncedIdleKeepsTheRingsRealLead();
    expectAutoEarlyIsAnOptInPolicyNextToAuto();
    expectAutoEarlyLetsGoSoonerAndKeepsFollowingATransient();
    expectAutoEarlyNeverHoldsAHealthyDac();
    expectTheGapSaysWhereTheTimeWent();
    expectOutputGapsAreRateLimitedLikeTheOthers();
    expectStuckEpisodesFromTheDeviceFire();
    expectHealthyFeedbackNeverFires();
    expectOffsetThreshold();
    expectGapBreaksTheRunOnlyWhenLong();
    expectStreamEndClosesTheEpisode();
    expectPoliciesDecide();
    expectPolicySwitchDoesNotJump();
    expectTheDeviceAbsurdReadingIsRefused();
    expectAbsurdBoundary();
    expectSustainedDeviationIsRefused();
    expectOutOfWindowReadingIsImplausibleToo();
    expectPlausibleReadingsAreStillFollowed();
    expectImplausibleStretchesAreNotTaughtToTheAverage();
    expectAutoKeepsUpAfterStart();
    expectARefusalFallsBackToBeforeTheExcursion();
    expectTheLockDetectorFiresOnARailAndOnNothingElse();
    expectAFarFrozenValueAfterARateChangeIsCalledAtFourHundredMs();
    expectTheMelodysRefillBurstIsFollowedAndTheRampIsStillRefusedAtTwoPercent();
    expectTheEightRailLocksOf1107AreCalledInTwoHundredMs();
    expectAStreamsFirstMomentDoesNotCountAndNothingHealthyIsARail();
    expectTheStartLockOf1107IsCalledAtFourHundredMsAndTheHealthyPlateauIsNot();
    printf("usb glitch tests: PASS\n");
    return 0;
}
