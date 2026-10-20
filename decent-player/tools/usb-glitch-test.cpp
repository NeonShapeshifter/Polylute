// Host tests for AUD-124: USB_GLITCH counting/rate limiting and the async-feedback reaction.
#include "usb-glitch.h"
#include "usb-pcm-packetizer.h"

#include <assert.h>
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
        usbFeedbackWatchObserve(&w, raw, nominal, now, &ev);
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
    assert(USB_GLITCH_KIND_COUNT == 9);
    assert(USB_GLITCH_FEEDBACK_STUCK == 6 && USB_GLITCH_FEEDBACK_UNSTUCK == 7);
    assert(usbGlitchLogIntervalMs(USB_GLITCH_FEEDBACK_STUCK) == 1000);
    assert(usbGlitchLogIntervalMs(USB_GLITCH_FEEDBACK_UNSTUCK) == 1000);
    // B23-P2: OUTPUT_GAP is rate limited like the per-second kinds, not held to REAP_SLOW's 30 s summary.
    assert(USB_GLITCH_OUTPUT_GAP == 8);
    assert(usbGlitchLogIntervalMs(USB_GLITCH_OUTPUT_GAP) == 1000);
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
        if (usbOutputGapSubmit(&w, announcedIdle, now, kUrbUs, &g)) {
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

int main() {
    expectSchedstatIsParsedStrictly();
    expectTheBaselineIsRefreshedAtMostEveryTenMilliseconds();
    expectTheDeltaSaysSleptFromRunnable();
    expectRingStarvationIsCountedWhateverItsLength();
    expectRateLimit();
    expectReapSlowIsQuiet();
    expectDeviation();
    expectFeedbackKeepsUpAfterStart();
    expectNewKindsAreRegistered();
    expectTheOwnersWakeIsOneGapWithItsNumbers();
    expectShortHolesWithReplenishmentAreQuiet();
    expectTheLeadIsTheLimit();
    expectAnAnnouncedIdleIsNotAGap();
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
    printf("usb glitch tests: PASS\n");
    return 0;
}
