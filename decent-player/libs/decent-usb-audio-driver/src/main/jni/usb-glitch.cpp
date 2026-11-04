#include "usb-glitch.h"

#include <math.h>
#include <string.h>

bool glitchCount(GlitchCounter *counter, int64_t nowMs, int64_t intervalMs, int64_t *suppressedOut) {
    counter->total++;
    if (counter->total > 1 && nowMs - counter->lastLogMs < intervalMs) {
        counter->suppressed++;
        return false;
    }
    *suppressedOut = counter->suppressed;
    counter->suppressed = 0;
    counter->lastLogMs = nowMs;
    return true;
}

int64_t usbRingStarvedMs(int64_t lastSubmitMs, int64_t nowMs, bool playOutBeforeDrain) {
    if (lastSubmitMs <= 0 || playOutBeforeDrain) return -1;
    return nowMs - lastSubmitMs;
}

bool usbReapIsSlow(int64_t waitedMs) {
    return waitedMs > 20;
}

void usbOutputGapWatchReset(UsbOutputGapWatch *w) {
    memset(w, 0, sizeof(*w));
}

void usbOutputGapAddReap(UsbOutputGapWatch *w, int64_t reapUs) {
    if (reapUs > 0) w->reapUs += reapUs;
}

void usbOutputGapAddBetweenCalls(UsbOutputGapWatch *w, int64_t betweenUs) {
    if (betweenUs > 0) w->betweenCallsUs += betweenUs;
}

bool usbOutputGapSubmit(UsbOutputGapWatch *w, bool announcedIdle, int64_t nowUs, int64_t urbUs, int64_t queuedLeadUs, UsbOutputGap *gap) {
    bool ranDry = false;
    if (w->armed && !announcedIdle && nowUs >= w->playoutEndUs) {
        const int64_t gapUs = nowUs - w->lastSubmitUs;
        gap->gapUs = gapUs;
        gap->leadUs = w->playoutEndUs - w->lastSubmitUs;
        gap->dryUs = nowUs - w->playoutEndUs;
        gap->reapUs = w->reapUs < gapUs ? w->reapUs : gapUs;
        gap->betweenCallsUs = w->betweenCallsUs < gapUs - gap->reapUs ? w->betweenCallsUs : gapUs - gap->reapUs;
        gap->otherUs = gapUs - gap->reapUs - gap->betweenCallsUs;
        ranDry = gap->dryUs >= USB_OUTPUT_GAP_MIN_DRY_US;
    }
    // From the later of "now" and the end of what is queued: a submit into a ring that ran dry starts a new lead. After an announced
    // idle the queue is what the writer says the ring still holds (B46-B1), not nothing.
    const int64_t queued = queuedLeadUs > 0 ? queuedLeadUs : 0;
    const int64_t from = announcedIdle ? nowUs + queued
                       : (w->armed && nowUs < w->playoutEndUs) ? w->playoutEndUs : nowUs;
    w->playoutEndUs = from + urbUs;
    w->lastSubmitUs = nowUs;
    w->reapUs = 0;
    w->betweenCallsUs = 0;
    w->armed = true;
    return ranDry;
}

bool usbParseSchedstat(const char *text, UsbSchedSample *out) {
    out->valid = false;
    out->cpuNs = 0;
    out->runqNs = 0;
    if (text == nullptr) return false;
    // Three unsigned decimals separated by single spaces; the third (timeslices) only has to be there.
    const char *p = text;
    uint64_t v[3] = {0, 0, 0};
    for (int i = 0; i < 3; i++) {
        if (*p < '0' || *p > '9') return false;
        uint64_t n = 0;
        while (*p >= '0' && *p <= '9') {
            const uint64_t digit = (uint64_t)(*p - '0');
            if (n > (UINT64_MAX - digit) / 10) return false;  // would not fit
            n = n * 10 + digit;
            p++;
        }
        v[i] = n;
        if (i < 2) {
            if (*p != ' ') return false;
            p++;
        }
    }
    if (*p != '\n' && *p != '\0') return false;
    out->cpuNs = v[0];
    out->runqNs = v[1];
    out->valid = true;
    return true;
}

bool usbSchedBaselineDue(const UsbSchedBaseline *baseline, int64_t nowUs) {
    return !baseline->sample.valid || baseline->atUs <= 0 || nowUs - baseline->atUs >= USB_SCHED_BASELINE_EVERY_US;
}

void usbSchedBaselineSet(UsbSchedBaseline *baseline, const UsbSchedSample *sample, int64_t nowUs) {
    baseline->sample = *sample;
    baseline->atUs = nowUs;
}

UsbSchedDelta usbSchedDelta(const UsbSchedBaseline *baseline, const UsbSchedSample *now, int64_t nowUs) {
    UsbSchedDelta d = {false, 0, 0, 0};
    if (!baseline->sample.valid || !now->valid || baseline->atUs <= 0) return d;
    if (now->cpuNs < baseline->sample.cpuNs || now->runqNs < baseline->sample.runqNs || nowUs < baseline->atUs) return d;
    d.valid = true;
    d.cpuUs = (int64_t)((now->cpuNs - baseline->sample.cpuNs) / 1000);
    d.runqUs = (int64_t)((now->runqNs - baseline->sample.runqNs) / 1000);
    d.overUs = nowUs - baseline->atUs;
    return d;
}

bool usbGapWantsCpuLine(const UsbSchedDelta *delta) {
    return delta->valid && delta->runqUs >= USB_GAP_CPU_MIN_RUNQ_US;
}

enum UsbOutputGapWhere usbOutputGapWhere(const UsbOutputGap *gap) {
    if (gap->reapUs >= gap->betweenCallsUs && gap->reapUs >= gap->otherUs) return USB_OUTPUT_GAP_IN_REAP;
    if (gap->betweenCallsUs >= gap->otherUs) return USB_OUTPUT_GAP_BETWEEN_WRITES;
    return USB_OUTPUT_GAP_IN_WRITE;
}

const char *usbOutputGapWhereName(enum UsbOutputGapWhere where) {
    switch (where) {
        case USB_OUTPUT_GAP_IN_REAP: return "in-reap";
        case USB_OUTPUT_GAP_BETWEEN_WRITES: return "between-writes";
        case USB_OUTPUT_GAP_IN_WRITE: return "in-write";
        default: return "?";
    }
}

int64_t usbGlitchLogIntervalMs(enum UsbGlitchKind kind) {
    // B47-A5: a lock-up and each step of its recovery are rare and bounded by the recovery's cooldown: every one is logged, never held back.
    if (kind == USB_GLITCH_DAC_LOCKED || kind == USB_GLITCH_DAC_RECOVERY) return 0;
    return kind == USB_GLITCH_REAP_SLOW ? 30000 : 1000;
}

double usbFeedbackDeviation(double fpmf, double nominalFpmf) {
    return nominalFpmf > 0.0 ? fpmf / nominalFpmf - 1.0 : 0.0;
}

// ±5 %: a real async DAC reports its clock plus a correction for its own FIFO, which right after a
// stream start can exceed 1 % (device: +0.9 % logged 2 s after a 48 kHz start). The old ±1 % window
// silently dropped exactly those readings, so the host kept sending the stale rate while the DAC's
// FIFO drained — a "pss" some seconds after starting (AUD-124). A misparsed format is off by a power
// of two, far outside this window.
double usbFeedbackApply(double currentFpmf, double reportedFpmf, double nominalFpmf) {
    if (reportedFpmf > nominalFpmf * 0.95 && reportedFpmf < nominalFpmf * 1.05) return reportedFpmf;
    return currentFpmf;
}

enum UsbFeedbackPacket usbFeedbackPacketVerdict(int urbStatus, int packetStatus, unsigned int actualLength) {
    if (urbStatus != 0) return USB_FEEDBACK_PACKET_URB_STATUS;
    if (packetStatus != 0) return USB_FEEDBACK_PACKET_PACKET_STATUS;
    if (actualLength != USB_FEEDBACK_BYTES) return USB_FEEDBACK_PACKET_LENGTH;
    return USB_FEEDBACK_PACKET_OK;
}

const char *usbFeedbackPacketName(int verdict) {
    switch (verdict) {
        case USB_FEEDBACK_PACKET_OK: return "ok";
        case USB_FEEDBACK_PACKET_URB_STATUS: return "urb-status";
        case USB_FEEDBACK_PACKET_PACKET_STATUS: return "packet-status";
        case USB_FEEDBACK_PACKET_LENGTH: return "length";
        default: return "?";
    }
}

// ── Frozen feedback (B10) ───────────────────────────────────────────────────────────────────────

double usbFeedbackPpm(double fpmf, double nominalFpmf) {
    return nominalFpmf > 0.0 ? (fpmf / nominalFpmf - 1.0) * 1e6 : 0.0;
}

void usbFeedbackWatchReset(UsbFeedbackWatch *w) {
    memset(w, 0, sizeof(*w));
}

/**
 * Adds a run of [count] identical readings of [fpmf] to the healthy average: the exact exponentially
 * weighted mean of [count] readings with a time constant of USB_FEEDBACK_HEALTHY_TAU_READINGS. One exp()
 * per run that ends, and runs end a few dozen times a second at most.
 */
static void foldSettled(UsbFeedbackWatch *w, double fpmf, int64_t count) {
    if (count <= 0) return;
    const double decay = exp(-1.0 / USB_FEEDBACK_HEALTHY_TAU_READINGS);
    const double older = pow(decay, (double)count);               // weight the existing readings keep
    const double added = (1.0 - older) / (1.0 - decay);           // sum of the new readings' weights
    w->healthySum = w->healthySum * older + fpmf * added;
    w->healthyWeight = w->healthyWeight * older + added;
}

/** B47-A4: the oldest fresh fold joins the settled average. */
static void settleOldest(UsbFeedbackWatch *w) {
    if (w->freshCount <= 0) return;
    foldSettled(w, w->fresh[0].fpmf, w->fresh[0].count);
    for (int i = 1; i < w->freshCount; i++) w->fresh[i - 1] = w->fresh[i];
    w->freshCount--;
}

/** B47-A4: whatever ended at least USB_FEEDBACK_HEALTHY_SETTLE_MS before [nowMs] is now part of the healthy average. */
static void settleFolds(UsbFeedbackWatch *w, int64_t nowMs) {
    while (w->freshCount > 0 && nowMs - w->fresh[0].endMs >= USB_FEEDBACK_HEALTHY_SETTLE_MS) settleOldest(w);
}

/** A run of [count] identical healthy readings ended at [endMs]: it waits to settle (B47-A4). */
static void foldHealthy(UsbFeedbackWatch *w, double fpmf, int64_t count, int64_t endMs) {
    if (count <= 0) return;
    if (w->freshCount >= USB_FEEDBACK_FRESH_CAPACITY) settleOldest(w);   // a very busy stretch: the oldest settles early rather than being lost
    UsbFeedbackFold &fold = w->fresh[w->freshCount++];
    fold.endMs = endMs;
    fold.fpmf = fpmf;
    fold.count = count;
}

const char *usbFeedbackImplausibleName(int why) {
    switch (why) {
        case USB_FEEDBACK_PLAUSIBLE: return "plausible";
        case USB_FEEDBACK_IMPLAUSIBLE_OUT_OF_WINDOW: return "out-of-window";
        case USB_FEEDBACK_IMPLAUSIBLE_ABSURD: return "absurd";
        case USB_FEEDBACK_IMPLAUSIBLE_SUSTAINED: return "sustained";
        default: return "?";
    }
}

/** Marks the latest reading (im)plausible; [ev] says so when it begins a streak. */
static void setImplausible(UsbFeedbackWatch *w, enum UsbFeedbackImplausible why, double fpmf, double ppm,
                           UsbFeedbackEvent *ev) {
    const bool started = why != USB_FEEDBACK_PLAUSIBLE && !w->implausible;
    // B47-A4: a refusal begins: the readings that have not settled are the excursion's own first ones, not the DAC's healthy clock.
    if (started) w->freshCount = 0;
    w->implausible = why != USB_FEEDBACK_PLAUSIBLE;
    w->implausibleWhy = why;
    if (started) {
        ev->implausibleStarted = true;
        ev->implausibleWhy = why;
        ev->implausibleFpmf = fpmf;
        ev->implausiblePpm = ppm;
    }
}

/**
 * B12a-2: is this applied reading one `auto` must not obey? Absurd at any moment, or beyond the sustained
 * threshold in every reading for USB_FEEDBACK_SUSTAINED_MS (a pause longer than the gap starts the count over).
 */
static void judgePlausibility(UsbFeedbackWatch *w, double fpmf, double ppm, int64_t nowMs, UsbFeedbackEvent *ev) {
    const double mag = ppm < 0.0 ? -ppm : ppm;
    if (mag > USB_FEEDBACK_SUSTAINED_PPM) {
        if (!w->bigRun || nowMs - w->bigLastMs > USB_FEEDBACK_GAP_MS) {
            w->bigRun = true;
            w->bigStartMs = nowMs;
        }
        w->bigLastMs = nowMs;
    } else {
        w->bigRun = false;
    }
    enum UsbFeedbackImplausible why = USB_FEEDBACK_PLAUSIBLE;
    if (ppm <= -50000.0 || ppm >= 50000.0) {
        why = USB_FEEDBACK_IMPLAUSIBLE_OUT_OF_WINDOW;
    } else if (mag > USB_FEEDBACK_ABSURD_PPM) {
        why = USB_FEEDBACK_IMPLAUSIBLE_ABSURD;
    } else if (w->bigRun && nowMs - w->bigStartMs >= USB_FEEDBACK_SUSTAINED_MS) {
        why = USB_FEEDBACK_IMPLAUSIBLE_SUSTAINED;
    }
    setImplausible(w, why, fpmf, ppm, ev);
    if (ev->implausibleStarted) ev->implausibleRampMs = w->bigRun ? nowMs - w->bigStartMs : 0;
}

static void fillEvent(UsbFeedbackEvent *ev, enum UsbFeedbackEventKind kind, const UsbFeedbackWatch *w,
                      double nominalFpmf, int64_t heldMs) {
    ev->kind = kind;
    ev->raw = w->runRaw;
    ev->fpmf = w->runRaw / 65536.0;
    ev->ppm = usbFeedbackPpm(ev->fpmf, nominalFpmf);
    ev->heldMs = heldMs;
    ev->readings = w->runReadings;
}

static void startRun(UsbFeedbackWatch *w, uint32_t raw, int64_t nowMs) {
    w->haveRun = true;
    w->runRaw = raw;
    w->runStartMs = nowMs;
    w->runLastMs = nowMs;
    w->runReadings = 1;
}

/** The run is over: an episode reports its end, anything else joins the healthy average. */
static void closeRun(UsbFeedbackWatch *w, double nominalFpmf, enum UsbFeedbackEndReason reason, uint32_t nextRaw,
                     UsbFeedbackEvent *ev) {
    if (w->stuck) {
        fillEvent(ev, USB_FEEDBACK_EVENT_UNSTUCK, w, nominalFpmf, w->runLastMs - w->runStartMs);
        ev->reason = reason;
        ev->nextRaw = nextRaw;
        w->stuck = false;
    } else {
        // A run beyond the sustained threshold (a start transient, a bad stretch) has nothing to teach about what
        // a healthy DAC averages: it would drag `auto`'s fallback toward the very value it is there to replace.
        const double ppm = usbFeedbackPpm(w->runRaw / 65536.0, nominalFpmf);
        if (ppm <= USB_FEEDBACK_SUSTAINED_PPM && ppm >= -USB_FEEDBACK_SUSTAINED_PPM) {
            foldHealthy(w, w->runRaw / 65536.0, w->runReadings, w->runLastMs);
        }
    }
    w->haveRun = false;
}

int64_t usbFeedbackStuckHoldMs(int policy, double ppm) {
    const double offset = ppm < 0 ? -ppm : ppm;
    if (policy == USB_FEEDBACK_AUTO_EARLY && offset >= USB_FEEDBACK_EARLY_MIN_PPM) return USB_FEEDBACK_EARLY_STUCK_MS;
    return USB_FEEDBACK_STUCK_MIN_MS;
}

void usbFeedbackWatchObserve(UsbFeedbackWatch *w, uint32_t raw, double nominalFpmf, int64_t nowMs,
                             UsbFeedbackEvent *ev) {
    usbFeedbackWatchObservePolicy(w, raw, nominalFpmf, nowMs, USB_FEEDBACK_AUTO, ev);
}

void usbFeedbackWatchObservePolicy(UsbFeedbackWatch *w, uint32_t raw, double nominalFpmf, int64_t nowMs, int policy,
                                   UsbFeedbackEvent *ev) {
    ev->kind = USB_FEEDBACK_EVENT_NONE;
    ev->rateChangeHold = false;
    ev->implausibleStarted = false;
    ev->implausibleRampMs = 0;
    settleFolds(w, nowMs);
    if (w->haveRun && nowMs - w->runLastMs > USB_FEEDBACK_GAP_MS) {
        closeRun(w, nominalFpmf, USB_FEEDBACK_END_GAP, raw, ev);
    }
    judgePlausibility(w, raw / 65536.0, usbFeedbackPpm(raw / 65536.0, nominalFpmf), nowMs, ev);
    if (!w->haveRun) {
        startRun(w, raw, nowMs);
        return;
    }
    if (raw != w->runRaw) {
        closeRun(w, nominalFpmf, USB_FEEDBACK_END_CHANGED, raw, ev);
        startRun(w, raw, nowMs);
        return;
    }
    w->runReadings++;
    w->runLastMs = nowMs;
    if (!w->stuck) {
        const double ppm = usbFeedbackPpm(raw / 65536.0, nominalFpmf);
        const int64_t ageMs = nowMs - w->streamStartMs;
        const bool rateChangeHold = w->rateChangeStart && ageMs >= 0 && ageMs < USB_FEEDBACK_RATE_CHANGE_WINDOW_MS;
        const int64_t holdMs = rateChangeHold ? USB_FEEDBACK_RATE_CHANGE_STUCK_MS : usbFeedbackStuckHoldMs(policy, ppm);
        if (nowMs - w->runStartMs >= holdMs &&
            (ppm > USB_FEEDBACK_STUCK_MIN_PPM || ppm < -USB_FEEDBACK_STUCK_MIN_PPM)) {
            w->stuck = true;
            fillEvent(ev, USB_FEEDBACK_EVENT_STUCK, w, nominalFpmf, nowMs - w->runStartMs);
            ev->rateChangeHold = rateChangeHold;
        }
    }
}

void usbFeedbackWatchOutOfWindow(UsbFeedbackWatch *w, uint32_t raw, double nominalFpmf, UsbFeedbackEvent *ev) {
    ev->kind = USB_FEEDBACK_EVENT_NONE;
    ev->rateChangeHold = false;
    ev->implausibleStarted = false;
    ev->implausibleRampMs = 0;
    const double fpmf = raw / 65536.0;
    setImplausible(w, USB_FEEDBACK_IMPLAUSIBLE_OUT_OF_WINDOW, fpmf, usbFeedbackPpm(fpmf, nominalFpmf), ev);
}

void usbFeedbackWatchEnd(UsbFeedbackWatch *w, double nominalFpmf, UsbFeedbackEvent *ev) {
    ev->kind = USB_FEEDBACK_EVENT_NONE;
    ev->rateChangeHold = false;
    ev->implausibleStarted = false;
    ev->implausibleRampMs = 0;
    if (w->haveRun && w->stuck) closeRun(w, nominalFpmf, USB_FEEDBACK_END_STREAM, 0, ev);
    usbFeedbackWatchReset(w);
}

const char *usbFeedbackPolicyName(int policy) {
    switch (policy) {
        case USB_FEEDBACK_FOLLOW: return "follow";
        case USB_FEEDBACK_NOMINAL: return "nominal";
        case USB_FEEDBACK_CLAMP: return "clamp";
        case USB_FEEDBACK_AUTO: return "auto";
        case USB_FEEDBACK_AUTO_EARLY: return "auto-early";
        default: return "?";
    }
}

/**
 * The average of the readings before the episode, when there is enough of it and it is itself plausible
 * (within the stuck threshold of nominal: a "healthy" window that was not has nothing to teach).
 */
static double healthyOrNominal(const UsbFeedbackWatch *w, double nominalFpmf) {
    if (w->healthyWeight >= USB_FEEDBACK_HEALTHY_MIN_WEIGHT) {
        const double mean = w->healthySum / w->healthyWeight;
        const double ppm = usbFeedbackPpm(mean, nominalFpmf);
        if (ppm <= USB_FEEDBACK_STUCK_MIN_PPM && ppm >= -USB_FEEDBACK_STUCK_MIN_PPM) return mean;
    }
    return nominalFpmf;
}

double usbFeedbackPolicyFpmf(int policy, double followFpmf, double nominalFpmf, const UsbFeedbackWatch *w) {
    switch (policy) {
        case USB_FEEDBACK_NOMINAL:
            return nominalFpmf;
        case USB_FEEDBACK_CLAMP: {
            const double limit = nominalFpmf * USB_FEEDBACK_CLAMP_PPM * 1e-6;
            if (followFpmf > nominalFpmf + limit) return nominalFpmf + limit;
            if (followFpmf < nominalFpmf - limit) return nominalFpmf - limit;
            return followFpmf;
        }
        case USB_FEEDBACK_AUTO:
        case USB_FEEDBACK_AUTO_EARLY:
            // Stuck (frozen off nominal) or implausible (absurd, or wrong for seconds): not obeyed. Everything
            // else — a healthy DAC's blips, a crystal tens or hundreds of ppm off — is followed as before.
            return (w != nullptr && (w->stuck || w->implausible)) ? healthyOrNominal(w, nominalFpmf) : followFpmf;
        default:
            return followFpmf;
    }
}

// ── A locked-up DAC (B47-A5; see usb-glitch.h) ──────────────────────────────────────────────────

void usbDacLockReset(UsbDacLockWatch *w) {
    memset(w, 0, sizeof(*w));
}

bool usbDacLockObserve(UsbDacLockWatch *w, double ppm, int64_t nowMs, UsbDacLockEvent *ev) {
    const double magnitude = ppm < 0.0 ? -ppm : ppm;
    if (w->inRun && nowMs - w->lastMs > USB_DAC_LOCK_GAP_MS) usbDacLockReset(w);   // a pause between readings ends the run
    if (magnitude < USB_DAC_LOCK_RAIL_PPM) {
        usbDacLockReset(w);   // a reading that is a clock's again: the run is over, and so is a lock-up
        return false;
    }
    if (!w->inRun) {
        usbDacLockReset(w);
        w->inRun = true;
        w->startMs = nowMs;
    }
    w->lastMs = nowMs;
    w->readings++;
    w->ppm = ppm;
    if (w->locked || nowMs - w->startMs < USB_DAC_LOCK_RAIL_MS) return false;
    w->locked = true;
    ev->ppm = ppm;
    ev->railedMs = nowMs - w->startMs;
    ev->readings = w->readings;
    ev->lateReaps = w->lateReaps;
    return true;
}

void usbDacLockLateReap(UsbDacLockWatch *w) {
    if (w->inRun) w->lateReaps++;
}

int64_t usbDacLockRailedMs(const UsbDacLockWatch *w, int64_t nowMs) {
    return w->inRun && nowMs >= w->startMs ? nowMs - w->startMs : 0;
}

// ── The recovery's escalation (B47-A5; see usb-glitch.h) ────────────────────────────────────────

void usbDacRecoveryPolicyReset(UsbDacRecoveryPolicy *p) {
    memset(p, 0, sizeof(*p));
}

int usbDacRecoveryDecide(UsbDacRecoveryPolicy *p, bool locked, bool enabled, int64_t nowMs, int64_t *lastEpisodeStartMs, int *verdictStep, bool *verdictOk, bool pending) {
    *verdictStep = 0;
    *verdictOk = false;
    if (!enabled) return USB_DAC_STEP_NONE;
    if (p->awaitingStep != 0) {
        if (locked) {
            *verdictStep = p->awaitingStep;   // the rail is back: that step did not hold
            *verdictOk = false;
            p->awaitingStep = 0;
        } else if (!pending && nowMs - p->awaitingSinceMs >= USB_DAC_RECOVERY_VERIFY_MS) {
            *verdictStep = p->awaitingStep;
            *verdictOk = true;
            p->awaitingStep = 0;
        }
    }
    if (!locked) {
        if (pending) return USB_DAC_STEP_NONE;
        if (p->quietSinceMs == 0) p->quietSinceMs = nowMs;
        if (p->lastStep != 0 && p->awaitingStep == 0 && nowMs - p->quietSinceMs >= USB_DAC_RECOVERY_EPISODE_END_MS) {
            p->lastStep = 0;   // the recovery held for long enough: the next lock-up is a new episode
            p->gaveUp = false;
        }
        return USB_DAC_STEP_NONE;
    }
    p->quietSinceMs = 0;
    if (p->gaveUp || p->awaitingStep != 0) return USB_DAC_STEP_NONE;
    const int next = p->lastStep + 1;
    if (next > USB_DAC_STEP_BUS_RESET) {
        p->gaveUp = true;
        return USB_DAC_STEP_GIVE_UP;
    }
    if (next == USB_DAC_STEP_INTERFACE) {
        if (*lastEpisodeStartMs != 0 && nowMs - *lastEpisodeStartMs < USB_DAC_RECOVERY_COOLDOWN_MS) return USB_DAC_STEP_NONE;
        *lastEpisodeStartMs = nowMs;
    }
    p->lastStep = next;
    return next;
}

void usbDacRecoveryStepDone(UsbDacRecoveryPolicy *p, int step, int64_t nowMs) {
    p->awaitingStep = step;
    p->awaitingSinceMs = nowMs;
}
