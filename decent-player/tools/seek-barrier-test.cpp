// Host tests for the click-free native seek (AUD-107): ramp lengths and shapes, the silence feed
// that keeps every microframe filled while the decoder seeks, and sample continuity across the
// whole fade-out -> silence -> fade-in sequence.
#include "seek-barrier.h"

#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

static int32_t readS(const uint8_t *p, int bps) {
    int32_t v = 0;
    for (int i = 0; i < bps; i++) v |= (int32_t)p[i] << (8 * i);
    if (bps < 4 && (v & (1 << (8 * bps - 1)))) v |= -(1 << (8 * bps));
    return v;
}

static void writeS(uint8_t *p, int32_t v, int bps) {
    for (int i = 0; i < bps; i++) p[i] = (uint8_t)((v >> (8 * i)) & 0xFF);
}

static void expectRampLengths() {
    const int rates[] = {44100, 48000, 96000, 192000, 384000};
    for (int rate : rates) {
        const double outMs = seekFadeOutFrames(rate) * 1000.0 / rate;
        const double inMs = seekFadeInFrames(rate) * 1000.0 / rate;
        const double leadMs = seekSilenceLeadFrames(rate) * 1000.0 / rate;
        if (outMs < 5.0 || outMs > 10.0 || inMs < 10.0 || inMs > 20.0 || leadMs < 20.0) {
            fprintf(stderr, "rate %d: fadeOut=%.2fms fadeIn=%.2fms lead=%.2fms\n", rate, outMs, inMs, leadMs);
            assert(false);
        }
    }
}

/** Fade-in starts near zero, rises monotonically, reaches the original level; fade-out ends at 0. */
static void expectRampShape(int bps) {
    const int channels = 2;
    const int total = 64;
    const int frames = total + 16;
    const int32_t level = (bps == 2) ? 20000 : (bps == 3 ? 4000000 : 1000000000);
    static uint8_t buf[80 * 2 * 4];
    static uint8_t split[80 * 2 * 4];

    for (int dir = 0; dir < 2; dir++) {
        const bool fadeIn = dir == 0;
        for (int f = 0; f < frames; f++) {
            writeS(buf + (f * channels + 0) * bps, level, bps);
            writeS(buf + (f * channels + 1) * bps, -level, bps);
        }
        memcpy(split, buf, sizeof(split));
        pcmApplyGainRamp(buf, frames, channels, bps, 0, total, fadeIn);
        // Applied in two pieces, continuing the ramp position, it must be identical.
        pcmApplyGainRamp(split, 30, channels, bps, 0, total, fadeIn);
        pcmApplyGainRamp(split + 30 * channels * bps, frames - 30, channels, bps, 30, total, fadeIn);
        assert(memcmp(buf, split, (size_t)frames * channels * bps) == 0);

        int32_t previous = fadeIn ? 0 : level + 1;
        for (int f = 0; f < frames; f++) {
            const int32_t l = readS(buf + (f * channels + 0) * bps, bps);
            const int32_t r = readS(buf + (f * channels + 1) * bps, bps);
            assert(l == -r || l == -r - 1 || l == -r + 1);
            if (fadeIn) {
                assert(l >= previous);
                if (f == 0) assert(l > 0 && l <= level / total + 1);
                if (f >= total - 1) assert(l == level);
            } else {
                assert(l <= previous);
                if (f >= total - 1) assert(l == 0 && r == 0);
            }
            previous = l;
        }
    }
}

// ── Silence feed against a model DAC ────────────────────────────────

struct ModelDac {
    int rate;
    double framesPerMicroframe;
    double accumulator;
    int64_t queued;
    int64_t microframes;
    int64_t seekEndsAtMicroframe;
    int64_t stopsAtMicroframe;
    int emptyMicroframes;
    int silenceSubmits;
    bool failSubmit;
};

static int64_t modelQueued(void *self) { return ((ModelDac *)self)->queued; }

static bool modelSubmit(void *self, int frames) {
    ModelDac *d = (ModelDac *)self;
    if (d->failSubmit) return false;
    d->queued += frames;
    d->silenceSubmits++;
    return true;
}

/** One URB's worth of time: eight microframes, each consuming its share of frames. */
static void modelWait(void *self) {
    ModelDac *d = (ModelDac *)self;
    for (int i = 0; i < 8; i++) {
        d->accumulator += d->framesPerMicroframe;
        const int64_t due = (int64_t)d->accumulator;
        d->accumulator -= (double)due;
        if (d->queued < due) {
            d->emptyMicroframes++;
            d->queued = 0;
        } else {
            d->queued -= due;
        }
        d->microframes++;
    }
}

static bool modelSeekDone(void *self) {
    ModelDac *d = (ModelDac *)self;
    return d->microframes >= d->seekEndsAtMicroframe;
}

static bool modelRunning(void *self) {
    ModelDac *d = (ModelDac *)self;
    return d->microframes < d->stopsAtMicroframe;
}

static ModelDac modelFor(int rate, double seekMs, int64_t initialQueued) {
    ModelDac d;
    memset(&d, 0, sizeof(d));
    d.rate = rate;
    d.framesPerMicroframe = rate / 8000.0;
    d.queued = initialQueued;
    d.seekEndsAtMicroframe = (int64_t)(seekMs * 8.0);
    d.stopsAtMicroframe = (int64_t)1 << 62;
    return d;
}

static void expectNoEmptyMicroframeWhileSeeking() {
    const int rates[] = {44100, 96000, 384000};
    const double seeks[] = {0.0, 1.0, 20.0, 80.0, 150.0, 600.0, 3000.0};
    for (int rate : rates) {
        const int lead = seekSilenceLeadFrames(rate);
        const int chunk = rate / 1000 + 1;
        // The ring as a seek finds it: ~80 ms of pre-seek audio plus the queued fade-out.
        const int64_t ring = (int64_t)rate * 80 / 1000 + seekFadeOutFrames(rate);
        for (double seekMs : seeks) {
            ModelDac d = modelFor(rate, seekMs, ring);
            SeekFeedOps ops = {&d, modelQueued, modelSubmit, modelWait, modelSeekDone, modelRunning};
            assert(seekFeedSilenceUntilDone(&ops, lead, chunk));
            if (d.emptyMicroframes != 0) {
                fprintf(stderr, "rate %d seek %.0fms: %d empty microframes\n", rate, seekMs, d.emptyMicroframes);
                assert(false);
            }
            // Still fed when the seek completes, and the new audio waits behind at most the lead.
            assert(d.queued > 0);
            const int64_t playedOut = ring - (int64_t)(d.microframes * d.framesPerMicroframe);
            const int64_t ceiling = (playedOut > lead ? playedOut : lead) + chunk + 1;
            assert(d.queued <= ceiling);
        }
    }
}

static void expectStopAndFailure() {
    // Stream stopping mid-seek: stop topping up, but still wait for the seek to finish.
    ModelDac stopping = modelFor(48000, 300.0, 48000 * 80 / 1000);
    stopping.stopsAtMicroframe = 8 * 100;
    SeekFeedOps ops = {&stopping, modelQueued, modelSubmit, modelWait, modelSeekDone, modelRunning};
    assert(seekFeedSilenceUntilDone(&ops, seekSilenceLeadFrames(48000), 49));
    assert(modelSeekDone(&stopping));

    // A failed submit is reported, not retried forever.
    ModelDac failing = modelFor(48000, 300.0, 0);
    failing.failSubmit = true;
    SeekFeedOps failOps = {&failing, modelQueued, modelSubmit, modelWait, modelSeekDone, modelRunning};
    assert(!seekFeedSilenceUntilDone(&failOps, seekSilenceLeadFrames(48000), 49));
}

// ── Sample continuity across the whole seek ─────────────────────────

static const int kRate = 44100;
static const int kMaxFrames = kRate / 2;
static int32_t g_out[kMaxFrames];

/** Largest sample-to-sample step: a hard cut at full level shows up as ~the amplitude. */
static int64_t maxStep(const int32_t *s, int n) {
    int64_t worst = 0;
    for (int i = 1; i < n; i++) {
        int64_t step = (int64_t)s[i] - s[i - 1];
        if (step < 0) step = -step;
        if (step > worst) worst = step;
    }
    return worst;
}

static int buildSequence(bool withRamps, int silenceFrames) {
    const double amplitude = 1.0e9;
    const double w = 2.0 * M_PI * 1000.0 / kRate;
    const int fadeOut = seekFadeOutFrames(kRate);
    const int fadeIn = seekFadeInFrames(kRate);
    int n = 0;
    // Pre-seek audio in flight, ending at a peak — the worst place to cut.
    const int pre = kRate * 80 / 1000;
    const double phase0 = M_PI / 2 - w * pre;
    for (int i = 0; i < pre; i++) g_out[n++] = (int32_t)(amplitude * sin(phase0 + w * i));
    if (withRamps) {
        // Its real continuation, faded out.
        uint8_t block[4096 * 4];
        for (int i = 0; i < fadeOut; i++) writeS(block + i * 4, (int32_t)(amplitude * sin(phase0 + w * (pre + i))), 4);
        pcmApplyGainRamp(block, fadeOut, 1, 4, 0, fadeOut, false);
        for (int i = 0; i < fadeOut; i++) g_out[n++] = readS(block + i * 4, 4);
    }
    for (int i = 0; i < silenceFrames; i++) g_out[n++] = 0;
    // The new position, entered mid-waveform at a peak of the opposite sign.
    const int post = kRate * 40 / 1000;
    uint8_t block[4096 * 4];
    for (int i = 0; i < post; i++) writeS(block + i * 4, (int32_t)(amplitude * sin(-M_PI / 2 + w * i)), 4);
    if (withRamps) pcmApplyGainRamp(block, post, 1, 4, 0, fadeIn, true);
    for (int i = 0; i < post; i++) g_out[n++] = readS(block + i * 4, 4);
    return n;
}

static void expectContinuousWaveform() {
    const double amplitude = 1.0e9;
    const double toneStep = amplitude * 2.0 * M_PI * 1000.0 / kRate;
    // Allowed: the tone's own slope plus one ramp step of the shorter ramp.
    const int64_t bound = (int64_t)(toneStep * 1.02 + amplitude / seekFadeOutFrames(kRate)) + 2;
    const int silences[] = {0, 441, 4410};
    for (int silence : silences) {
        const int n = buildSequence(true, silence);
        const int64_t step = maxStep(g_out, n);
        if (step > bound) {
            fprintf(stderr, "silence %d: max step %lld > bound %lld\n", silence, (long long)step, (long long)bound);
            assert(false);
        }
    }
    // The metric does catch the old barrier: a hard cut at a peak and an unramped entry.
    assert(maxStep(g_out, buildSequence(false, 441)) > bound * 4);
}

// ── AUD-123: track change and pause/resume use the same ramps ───────

static void expectFadeSplit() {
    // A block larger than the fade: only the fade's frames are ramped, the rest is remainder.
    assert(fadeOutFramesOfBlock(4096, 0, 352) == 352);
    // Fade spread over several small blocks (small FLAC blocks at 384 kHz).
    assert(fadeOutFramesOfBlock(1152, 0, 3072) == 1152);
    assert(fadeOutFramesOfBlock(1152, 2304, 3072) == 768);
    // Fade already complete: nothing of the block belongs to it.
    assert(fadeOutFramesOfBlock(1152, 3072, 3072) == 0);
    assert(fadeOutFramesOfBlock(0, 0, 352) == 0);
}

/**
 * Track change: the old track's ring, its continuation faded out, then (possibly) nothing while the
 * next engine starts, then the next track faded in. Nothing may step, and the stream may only go
 * empty after the signal has reached zero.
 */
static void expectContinuousTrackChangeAndPause() {
    const double amplitude = 1.0e9;
    const double w = 2.0 * M_PI * 1000.0 / kRate;
    const double toneStep = amplitude * w;
    const int64_t bound = (int64_t)(toneStep * 1.02 + amplitude / seekFadeOutFrames(kRate)) + 2;
    const int fadeOut = seekFadeOutFrames(kRate);
    const int fadeIn = seekFadeInFrames(kRate);

    // Track change.
    int n = 0;
    const int pre = kRate * 80 / 1000;
    const double phase0 = M_PI / 2 - w * pre;
    for (int i = 0; i < pre; i++) g_out[n++] = (int32_t)(amplitude * sin(phase0 + w * i));
    static uint8_t block[4096 * 4];
    const int blockFrames = 4096;
    for (int i = 0; i < blockFrames; i++) writeS(block + i * 4, (int32_t)(amplitude * sin(phase0 + w * (pre + i))), 4);
    const int ramped = fadeOutFramesOfBlock(blockFrames, 0, fadeOut);
    assert(ramped == fadeOut);
    pcmApplyGainRamp(block, ramped, 1, 4, 0, fadeOut, false);
    for (int i = 0; i < ramped; i++) g_out[n++] = readS(block + i * 4, 4);
    assert(g_out[n - 1] == 0);  // the stream may run empty only from here on
    const int underrun = kRate / 10;  // the next engine takes ~100 ms to start: silence, not a step
    for (int i = 0; i < underrun; i++) g_out[n++] = 0;
    for (int i = 0; i < 2000; i++) writeS(block + i * 4, (int32_t)(amplitude * sin(M_PI / 3 + w * i)), 4);
    pcmApplyGainRamp(block, 2000, 1, 4, 0, fadeIn, true);
    for (int i = 0; i < 2000; i++) g_out[n++] = readS(block + i * 4, 4);
    assert(maxStep(g_out, n) <= bound);

    // Pause then resume: the fade ramps the first frames of the next block; the resume enters on the
    // rest of that same block (no audio skipped), faded in.
    n = 0;
    for (int i = 0; i < pre; i++) g_out[n++] = (int32_t)(amplitude * sin(phase0 + w * i));
    for (int i = 0; i < blockFrames; i++) writeS(block + i * 4, (int32_t)(amplitude * sin(phase0 + w * (pre + i))), 4);
    const int paused = fadeOutFramesOfBlock(blockFrames, 0, fadeOut);
    pcmApplyGainRamp(block, paused, 1, 4, 0, fadeOut, false);
    for (int i = 0; i < paused; i++) g_out[n++] = readS(block + i * 4, 4);
    for (int i = 0; i < 4410; i++) g_out[n++] = 0;  // paused
    uint8_t *remainder = block + paused * 4;
    pcmApplyGainRamp(remainder, blockFrames - paused, 1, 4, 0, fadeIn, true);
    for (int i = 0; i < blockFrames - paused; i++) g_out[n++] = readS(remainder + i * 4, 4);
    assert(maxStep(g_out, n) <= bound);
}

/**
 * AUD-140 (device log 19:58:15, and B7-fix4 00:04:35 / 00:04:40): the player pauses while seeking, and
 * the seek reached the decode thread before the pause did. The ring held ~80 ms at full level and the
 * discard branch dropped it: `ok=60` and `ok=55` URBs that never reached the DAC, a dry cut, then
 * RING_EMPTY. B7-fix4 sent a pause that had queued its fade to the discard branch, and that is no
 * better: the 8 ms fade goes behind the ring, so ~72 ms of full-level audio is still in front of it.
 * Silence is what the ring holds only once it has played out, and that is what "nothing unplayed"
 * means — not a flag on the pause.
 */
static void expectSeekCutFollowsTheRing() {
    const int64_t ring = 3528;  // 80 ms at 44.1 kHz: the ring the device measured ok=60 / ok=55 in
    // Playing or paused, faded or not, anything unplayed is faded out: the pause state is not an
    // input, so the race (the seek before its pause, or after the pause's fade was queued behind the
    // ring) cannot discard audio that is still full level. B7-fix4 discarded the faded-pause case.
    assert(seekCutFor(true, ring, false) == SEEK_CUT_FADE);
    assert(seekCutFor(true, ring / 8, false) == SEEK_CUT_FADE);   // 10 ms left
    assert(seekCutFor(true, 1, false) == SEEK_CUT_FADE);
    // An empty ring: nothing to fade, nothing lost by discarding.
    assert(seekCutFor(true, 0, false) == SEEK_CUT_DISCARD);
    // A fresh engine's item start keeps the previous track's tail (AUD-123 / AUD-128) or drops an
    // idle ring; it never fades.
    assert(seekCutFor(false, ring, true) == SEEK_CUT_KEEP_TAIL);
    assert(seekCutFor(false, 0, false) == SEEK_CUT_DISCARD);
}

/**
 * The race as the listener hears it: whatever the decision discards is audio that never played. For
 * every state the engine can be in when the seek arrives, with a ring holding `queued` frames of
 * full-level audio, the discard branch must throw away nothing audible.
 */
static void expectNoAudibleAudioIsDiscarded() {
    const int64_t depths[] = {0, 8, 441, 1764, 3528};
    for (int64_t queued : depths) {
        for (int engineState = 0; engineState < 3; engineState++) {  // playing, paused (fade pending), paused (faded)
            const SeekCut cut = seekCutFor(true, queued, false);
            const int64_t audibleDiscarded = (cut == SEEK_CUT_DISCARD) ? queued : 0;
            if (audibleDiscarded != 0) {
                fprintf(stderr, "state %d, %lld frames queued: %lld discarded unplayed\n", engineState,
                        (long long)queued, (long long)audibleDiscarded);
                assert(false);
            }
        }
    }
}

int main() {
    expectRampLengths();
    expectRampShape(2);
    expectRampShape(3);
    expectRampShape(4);
    expectNoEmptyMicroframeWhileSeeking();
    expectStopAndFailure();
    expectContinuousWaveform();
    expectFadeSplit();
    expectContinuousTrackChangeAndPause();
    expectSeekCutFollowsTheRing();
    expectNoAudibleAudioIsDiscarded();
    printf("seek barrier tests: PASS\n");
    return 0;
}
