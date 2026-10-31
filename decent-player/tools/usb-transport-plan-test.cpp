// B46-A1: the transport plan against the numbers the output used before it existed.
//
// makeUsbTransportPlan() is a pure function that moves WHERE the transport numbers come from, not WHAT they are. This test is its
// contract: for the matrix of rates x containers x depths x channels, high speed, with and without a feedback endpoint, every output
// equals the value the code computed on its own before B46. Each "today" formula below is copied from the code it replaces; the
// comment names it as it stands in B45's tree (HEAD of B46).
//
// Built without the C++ standard library (C headers only), like the other packetizer tests. Run with `--dump` to print one line per
// cell of the matrix (the "after" half of the before -> after table; tools/usb-transport-numbers-before.cpp prints the "before" half
// from HEAD's own functions and the two outputs are diffed).

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "usb-pcm-convert.h"
#include "usb-pcm-packetizer.h"

static int g_failures = 0;
#define CHECK(cond, ...)                                  \
    do {                                                  \
        if (!(cond)) {                                    \
            g_failures++;                                 \
            printf("FAIL %s:%d: ", __FILE__, __LINE__);   \
            printf(__VA_ARGS__);                          \
            printf("\n");                                 \
        }                                                 \
    } while (0)

static const int kRates[] = {44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000};
static const int kRateCount = sizeof(kRates) / sizeof(kRates[0]);
static const int kPrerollMs[] = {0, 250, 500, 1000};  // RateSwitchPreroll.ALLOWED_MS

// ── What the code computed before the plan (B45's tree) ──────────────────────────────────────────

// usb-audio-output.cpp:1663 / :1754: `ctx->sampleRate / 8000.0`
static double todayNominalFpmf(int rate) { return rate / 8000.0; }
// usb-audio-output.cpp:1636: `(bits / 8) * ch`
static int todayBytesPerFrame(int containerBytes, int channels) { return containerBytes * channels; }
// UacStreamingDescriptors.kt:157: `ceil(sampleRateHz / 8000.0).toInt()` frames x channels x subslot
static int todayWidestPacketBytes(int rate, int channels, int containerBytes) {
    return (int)ceil(rate / 8000.0) * channels * containerBytes;
}
// seek-barrier.cpp:7, :8, :11 and usb-audio-output.cpp:2646
static int todayFadeOutFrames(int rate) { return rate * 8 / 1000; }
static int todayFadeInFrames(int rate) { return rate * 15 / 1000; }
// B46-B1 raised the lead from 40 ms (what B45's tree computed) to 60 ms; B47-A2 to 110 ms (with the ring at 128 URBs); B47-A3 adds a 50 ms
// resume lead. Every other number here is still B45's.
static int todaySilenceLeadFrames(int rate) { return rate * 110 / 1000; }
static int todayResumeLeadFrames(int rate) { return rate * 50 / 1000; }
static int todaySilenceChunkFrames(int rate) { return rate / 1000 + 1; }
// RateSwitchPreroll.kt:53 `rateHz.toLong() * ms / 1000L`, :56 chunks of at most 8192 (CHUNK_FRAMES)
static int64_t todayPrerollFrames(int rate, int ms) { return ms <= 0 || rate <= 0 ? 0 : (int64_t)rate * ms / 1000; }
static int todayPrerollBlocks(int64_t frames) {
    int blocks = 0;
    for (int64_t left = frames; left > 0; left -= 8192) blocks++;
    return blocks;
}

static UsbTransportEndpoint highSpeedEndpoint(int maxPacket, bool feedback) {
    UsbTransportEndpoint endpoint = {};
    endpoint.speed = USB_TRANSPORT_SPEED_HIGH;
    endpoint.maxPacketBytes = maxPacket;
    endpoint.bInterval = 1;
    endpoint.hasFeedback = feedback;
    endpoint.feedbackBytes = feedback ? 4 : 0;
    return endpoint;
}

static UsbTransportFormat formatOf(int rate, int channels, int containerBytes, int sourceBits) {
    UsbTransportFormat format = {};
    format.sampleRate = rate;
    format.channels = channels;
    format.containerBytes = containerBytes;
    format.sourceBits = sourceBits;
    return format;
}

static void checkCell(int rate, int channels, int containerBytes, int sourceBits, bool feedback, bool dump) {
    const int widest = todayWidestPacketBytes(rate, channels, containerBytes);
    const UsbTransportFormat format = formatOf(rate, channels, containerBytes, sourceBits);
    const UsbTransportPolicy policy = usbTransportDefaultPolicy();
    // The tightest endpoint that carries the format, and a generous one: slot sizes differ between them.
    for (int generous = 0; generous < 2; generous++) {
        const int maxPacket = generous ? 3 * 1024 : widest;
        const UsbTransportPlan plan = makeUsbTransportPlan(format, highSpeedEndpoint(maxPacket, feedback), policy);
        CHECK(plan.status == USB_PLAN_OK, "%d Hz %d ch %dB src %d fb %d maxPacket %d: status %d reason %s", rate, channels,
              containerBytes, sourceBits, feedback, maxPacket, plan.status, usbTransportReasonName(plan.reason));
        if (plan.status != USB_PLAN_OK) continue;

        CHECK(plan.assumptions == 0, "%d Hz: a high-speed bInterval-1 endpoint carries assumptions 0x%x", rate, plan.assumptions);
        CHECK(plan.serviceIntervalUs == 125 && plan.intervalsPerSecond == 8000, "time base %d us / %d per s", plan.serviceIntervalUs,
              plan.intervalsPerSecond);
        CHECK(plan.nominalFramesNum == rate && plan.nominalFramesDen == 8000, "nominal fraction %d/%d", plan.nominalFramesNum,
              plan.nominalFramesDen);
        CHECK(plan.nominalFramesPerInterval == todayNominalFpmf(rate), "nominal fpmf %.17g vs %.17g", plan.nominalFramesPerInterval,
              todayNominalFpmf(rate));
        CHECK(plan.bytesPerFrame == todayBytesPerFrame(containerBytes, channels), "bytes per frame %d", plan.bytesPerFrame);
        CHECK(plan.maxPacketBytes == widest, "widest packet %d vs %d", plan.maxPacketBytes, widest);
        CHECK(plan.maxFramesPerPacket == (int)ceil(rate / 8000.0), "widest packet in frames %d", plan.maxFramesPerPacket);
        CHECK(plan.packetsPerUrb == 8, "packets per URB %d", plan.packetsPerUrb);
        CHECK(plan.urbSlotBytes == (8 * maxPacket > 4096 ? 8 * maxPacket : 4096), "slot %d for maxPacket %d", plan.urbSlotBytes, maxPacket);
        CHECK(plan.urbMaxBytes == 8 * maxPacket, "URB max bytes %d", plan.urbMaxBytes);
        CHECK(plan.residualCapBytes == 8 * 3 * 1024, "residual cap %d", plan.residualCapBytes);
        CHECK(plan.ringUrbs == 128 && plan.backpressureUrbs == 128 && plan.lowRingUrbs == 64, "ring %d / back-pressure %d / low %d",
              plan.ringUrbs, plan.backpressureUrbs, plan.lowRingUrbs);
        // B46-B2: the limits are durations now (1440 ms and 11 900 ms), the same headroom at every rate
        CHECK(plan.queueSoftFrames == (int64_t)rate * 1440 / 1000 && plan.queueHardFrames == (int64_t)rate * 11900 / 1000, "queue %lld / %lld frames",
              (long long)plan.queueSoftFrames, (long long)plan.queueHardFrames);
        CHECK(plan.silenceLeadFrames == todaySilenceLeadFrames(rate), "silence lead %d", plan.silenceLeadFrames);
        CHECK(plan.silenceChunkFrames == todaySilenceChunkFrames(rate), "silence chunk %d", plan.silenceChunkFrames);
        CHECK(plan.resumeLeadFrames == todayResumeLeadFrames(rate), "resume lead %d", plan.resumeLeadFrames);
        CHECK(plan.fadeOutFrames == todayFadeOutFrames(rate), "fade-out %d", plan.fadeOutFrames);
        CHECK(plan.fadeInFrames == todayFadeInFrames(rate), "fade-in %d", plan.fadeInFrames);
        CHECK(plan.feedbackBytes == (feedback ? 4 : 0), "feedback bytes %d", plan.feedbackBytes);
        // The ceiling is reported; without a feedback endpoint it is the nominal one.
        if (!feedback) CHECK(plan.maxFramesPerPacketAtCeiling == plan.maxFramesPerPacket, "no feedback, no ceiling");
        CHECK(plan.maxFramesPerPacketAtCeiling >= plan.maxFramesPerPacket, "ceiling below nominal");

        for (int i = 0; i < 4; i++) {
            UsbTransportPolicy withPreroll = policy;
            withPreroll.prerollMs = kPrerollMs[i];
            const UsbTransportPlan pre = makeUsbTransportPlan(format, highSpeedEndpoint(maxPacket, feedback), withPreroll);
            CHECK(pre.prerollFrames == todayPrerollFrames(rate, kPrerollMs[i]), "pre-roll %d ms: %lld frames", kPrerollMs[i],
                  (long long)pre.prerollFrames);
            CHECK(pre.prerollBlocks == todayPrerollBlocks(todayPrerollFrames(rate, kPrerollMs[i])), "pre-roll %d ms: %d blocks",
                  kPrerollMs[i], pre.prerollBlocks);
            CHECK(pre.prerollBlockFrames == 8192, "pre-roll block %d", pre.prerollBlockFrames);
        }

        // One byte under the widest packet is the preflight's "does not fit".
        const UsbTransportPlan tooSmall = makeUsbTransportPlan(format, highSpeedEndpoint(widest - 1, feedback), policy);
        CHECK(tooSmall.status == USB_PLAN_UNSUPPORTED && tooSmall.reason == USB_PLAN_REASON_PACKET_OVER_MAX,
              "%d Hz: maxPacket %d accepted (%d)", rate, widest - 1, tooSmall.reason);

        if (dump && !generous) {
            long long preFrames[3];
            int preBlocks[3];
            for (int i = 0; i < 3; i++) {
                UsbTransportPolicy withPreroll = policy;
                withPreroll.prerollMs = kPrerollMs[i + 1];
                const UsbTransportPlan pre = makeUsbTransportPlan(format, highSpeedEndpoint(maxPacket, feedback), withPreroll);
                preFrames[i] = pre.prerollFrames;
                preBlocks[i] = pre.prerollBlocks;
            }
            printf("cell rate=%d ch=%d container=%dB src=%d fb=%d nominal=%.17g bpf=%d widest=%d pkts=%d slot=%d resid=%d ring=%d "
                   "back=%d low=%d soft=%d hard=%d lead=%d chunk=%d out=%d in=%d pre250=%lld/%d pre500=%lld/%d pre1000=%lld/%d\n",
                   rate, channels, containerBytes, sourceBits, feedback ? 1 : 0, plan.nominalFramesPerInterval, plan.bytesPerFrame,
                   plan.maxPacketBytes, plan.packetsPerUrb, plan.urbSlotBytes, plan.residualCapBytes, plan.ringUrbs,
                   plan.backpressureUrbs, plan.lowRingUrbs, (int)plan.queueSoftFrames, (int)plan.queueHardFrames, plan.silenceLeadFrames,
                   plan.silenceChunkFrames, plan.fadeOutFrames, plan.fadeInFrames, preFrames[0], preBlocks[0], preFrames[1],
                   preBlocks[1], preFrames[2], preBlocks[2]);
        }
    }
}

static void testMatrix(bool dump) {
    int cells = 0;
    for (int r = 0; r < kRateCount; r++) {
        for (int containerBytes = 2; containerBytes <= 4; containerBytes++) {
            // Every valid depth that does not narrow, and 0 = a float source that any container takes.
            for (int sourceBits = 0; sourceBits <= 32; sourceBits += 8) {
                if (sourceBits == 8 || (sourceBits != 0 && sourceBits > containerBytes * 8)) continue;
                for (int channels = 1; channels <= 2; channels++) {
                    for (int feedback = 0; feedback < 2; feedback++) {
                        checkCell(kRates[r], channels, containerBytes, sourceBits, feedback != 0, dump);
                        cells++;
                    }
                }
            }
        }
    }
    if (!dump) printf("plan matrix: %d cells x (tight, generous maxPacket) x 4 pre-rolls\n", cells);
}

// ── Refusals the output has always made, and the timings it does not drive yet ────────────────────

static void testRefusals() {
    const UsbTransportPolicy policy = usbTransportDefaultPolicy();
    const UsbTransportEndpoint endpoint = highSpeedEndpoint(3 * 1024, true);
    UsbTransportPlan plan;

    // A container narrower than the source (AUD-008): usbPcmCanConvert is the rule.
    static const int kSourceDepths[] = {16, 24, 32};
    for (int sourceBits : kSourceDepths) {
        for (int containerBytes = 2; containerBytes <= 4; containerBytes++) {
            plan = makeUsbTransportPlan(formatOf(48000, 2, containerBytes, sourceBits), endpoint, policy);
            const bool fits = usbPcmCanConvert(sourceBits, containerBytes * 8);
            CHECK((plan.status == USB_PLAN_OK) == fits, "source %d in %d bytes: plan %d, usbPcmCanConvert %d", sourceBits, containerBytes,
                  plan.status, fits);
            if (!fits) CHECK(plan.reason == USB_PLAN_REASON_CONTAINER_NARROWER, "reason %s", usbTransportReasonName(plan.reason));
        }
    }
    plan = makeUsbTransportPlan(formatOf(48000, 2, 4, 20), endpoint, policy);
    CHECK(plan.status == USB_PLAN_UNSUPPORTED && plan.reason == USB_PLAN_REASON_CONTAINER_NARROWER, "a 20-bit source is not a container depth");

    const struct { int rate, channels, container, maxPacket; } invalid[] = {
        {0, 2, 4, 400}, {48000, 0, 4, 400}, {48000, 2, 1, 400}, {48000, 2, 5, 400}, {48000, 2, 4, 0}, {-1, 2, 4, 400},
    };
    for (const auto &bad : invalid) {
        plan = makeUsbTransportPlan(formatOf(bad.rate, bad.channels, bad.container, 0), highSpeedEndpoint(bad.maxPacket, true), policy);
        CHECK(plan.status == USB_PLAN_UNSUPPORTED && plan.reason == USB_PLAN_REASON_INVALID, "invalid input %d/%d/%d/%d: %s", bad.rate,
              bad.channels, bad.container, bad.maxPacket, usbTransportReasonName(plan.reason));
    }

    UsbTransportPolicy big = policy;
    big.ringUrbs = USB_TRANSPORT_RING_URBS + 1;
    plan = makeUsbTransportPlan(formatOf(48000, 2, 4, 0), endpoint, big);
    CHECK(plan.status == USB_PLAN_UNSUPPORTED && plan.reason == USB_PLAN_REASON_RING_TOO_LARGE, "a ring larger than the context's arrays");
}

static void testTimingAssumptions() {
    UsbTransportPolicy lenient = usbTransportDefaultPolicy();
    UsbTransportPolicy strict = lenient;
    strict.refuseUnsupportedTiming = true;
    const UsbTransportFormat format = formatOf(44100, 2, 4, 24);
    const UsbTransportPlan reference = makeUsbTransportPlan(format, highSpeedEndpoint(400, true), lenient);

    struct Case {
        const char *name;
        int speed, bInterval, feedbackBytes, bit, reason;
    };
    const Case cases[] = {
        {"full speed", USB_TRANSPORT_SPEED_FULL, 1, 4, USB_PLAN_ASSUME_SERVICE_INTERVAL, USB_PLAN_REASON_FULL_SPEED},
        {"low speed", USB_TRANSPORT_SPEED_LOW, 1, 4, USB_PLAN_ASSUME_SERVICE_INTERVAL, USB_PLAN_REASON_FULL_SPEED},
        {"bInterval 4", USB_TRANSPORT_SPEED_HIGH, 4, 4, USB_PLAN_ASSUME_BINTERVAL, USB_PLAN_REASON_BINTERVAL},
        {"3-byte feedback", USB_TRANSPORT_SPEED_HIGH, 1, 3, USB_PLAN_ASSUME_FEEDBACK_WIDTH, USB_PLAN_REASON_FEEDBACK_WIDTH},
        {"speed unknown", USB_TRANSPORT_SPEED_UNKNOWN, 1, 4, USB_PLAN_ASSUME_SPEED_UNKNOWN, -1},
        {"super speed", USB_TRANSPORT_SPEED_SUPER, 1, 4, 0, -1},
    };
    for (const Case &c : cases) {
        UsbTransportEndpoint endpoint = highSpeedEndpoint(400, true);
        endpoint.speed = c.speed;
        endpoint.bInterval = c.bInterval;
        endpoint.feedbackBytes = c.feedbackBytes;

        // B46: planned with the numbers the output has always used, and the assumption said out loud.
        const UsbTransportPlan plan = makeUsbTransportPlan(format, endpoint, lenient);
        CHECK(plan.status == USB_PLAN_OK, "%s: the plan the output runs with must not refuse (%s)", c.name, usbTransportReasonName(plan.reason));
        CHECK(plan.assumptions == c.bit, "%s: assumptions 0x%x, wanted 0x%x", c.name, plan.assumptions, c.bit);
        CHECK(plan.packetsPerUrb == reference.packetsPerUrb && plan.urbSlotBytes == reference.urbSlotBytes &&
                  plan.ringUrbs == reference.ringUrbs && plan.maxPacketBytes == reference.maxPacketBytes &&
                  plan.nominalFramesPerInterval == reference.nominalFramesPerInterval,
              "%s: the numbers must be the high-speed ones", c.name);

        // The step that adds those timings turns the refusal on: precise reasons.
        const UsbTransportPlan refused = makeUsbTransportPlan(format, endpoint, strict);
        if (c.reason >= 0) {
            CHECK(refused.status == USB_PLAN_UNSUPPORTED && refused.reason == c.reason, "%s: strict policy gave %d (%s)", c.name,
                  refused.status, usbTransportReasonName(refused.reason));
        } else {
            CHECK(refused.status == USB_PLAN_OK, "%s: strict policy refused a timing it drives", c.name);
        }
    }

    // A feedback packet size only matters when there is a feedback endpoint; a data endpoint's bInterval 0 is "not reported".
    UsbTransportEndpoint noFeedback = highSpeedEndpoint(400, false);
    noFeedback.feedbackBytes = 3;
    CHECK(makeUsbTransportPlan(format, noFeedback, lenient).assumptions == 0, "feedback width without a feedback endpoint");
    UsbTransportEndpoint unreported = highSpeedEndpoint(400, true);
    unreported.bInterval = 0;
    unreported.feedbackBytes = 0;
    CHECK(makeUsbTransportPlan(format, unreported, lenient).assumptions == 0, "unreported bInterval / feedback width is not a contradiction");
}

// ── The fractional phase: ten minutes of packets, as the packetizer produces them today ─────────────

/** A source with room for [frames] of silence, planned URB by URB through planUsbPcmUrb. Counts every packet's frames. */
static void testLongRunSequence(int rate) {
    const UsbTransportFormat format = formatOf(rate, 2, 4, 24);
    const UsbTransportPlan plan = makeUsbTransportPlan(format, highSpeedEndpoint(3 * 1024, true), usbTransportDefaultPolicy());
    CHECK(plan.status == USB_PLAN_OK, "%d Hz", rate);

    const int64_t seconds = 600;
    const int64_t totalPackets = seconds * 8000;
    double accumulator = 0.0;
    double referenceAccumulator = 0.0;
    int64_t planned = 0;
    int64_t exactFrames = 0;
    int64_t differences = 0;
    int64_t maxDrift = 0;
    // available = enough for any one URB, so the packetizer never stops for want of bytes.
    for (int64_t packet = 0; packet < totalPackets; packet += USB_PCM_PACKETS_PER_URB) {
        const UsbPcmUrbPlan fromPlan = planUsbPcmUrb(plan.urbMaxBytes, plan.bytesPerFrame, 3 * 1024, plan.nominalFramesPerInterval,
                                                     accumulator, false);
        const UsbPcmUrbPlan today = planUsbPcmUrb(plan.urbMaxBytes, format.channels * format.containerBytes, 3 * 1024, rate / 8000.0,
                                                  referenceAccumulator, false);
        CHECK(fromPlan.packetCount == 8 && today.packetCount == 8, "URB at packet %lld", (long long)packet);
        if (fromPlan.packetCount != 8 || today.packetCount != 8) return;
        for (int i = 0; i < 8; i++) {
            if (fromPlan.packetSizes[i] != today.packetSizes[i]) differences++;
            planned += fromPlan.packetSizes[i] / plan.bytesPerFrame;
            // The exact sequence: floor(k x rate / 8000) - floor((k-1) x rate / 8000) frames in packet k.
            const int64_t k = packet + i + 1;
            const int64_t exact = k * rate / 8000 - (k - 1) * rate / 8000;
            exactFrames += exact;
            const int64_t drift = planned - exactFrames;
            const int64_t magnitude = drift < 0 ? -drift : drift;
            if (magnitude > maxDrift) maxDrift = magnitude;
        }
        accumulator = fromPlan.nextFrameAccumulator;
        referenceAccumulator = today.nextFrameAccumulator;
    }
    CHECK(differences == 0, "%d Hz: %lld packets differ from today's sequence", rate, (long long)differences);
    CHECK(planned == (int64_t)seconds * rate || planned == (int64_t)seconds * rate - 1 || planned == (int64_t)seconds * rate + 1,
          "%d Hz: %lld frames in %lld s (%lld expected)", rate, (long long)planned, (long long)seconds, (long long)seconds * rate);
    printf("long run %d Hz, %lld s: %lld packets, %lld frames, identical to today's sequence; largest running difference from the exact "
           "integer sequence: %lld frame(s)\n",
           rate, (long long)seconds, (long long)totalPackets, (long long)planned, (long long)maxDrift);
}

static void testStaticCapacities() {
    const UsbTransportPlan plan = makeUsbTransportPlan(formatOf(48000, 2, 4, 24), highSpeedEndpoint(400, true), usbTransportDefaultPolicy());
    CHECK(plan.ringUrbs + 1 <= USB_AUDIO_MAX_BARRIER_TARGETS, "ring %d vs barrier targets %d", plan.ringUrbs, USB_AUDIO_MAX_BARRIER_TARGETS);
    CHECK(plan.ringUrbs + 1 <= URB_DRAIN_MAX, "ring %d vs drain set %d", plan.ringUrbs, URB_DRAIN_MAX);
    CHECK(plan.ringUrbs + 1 <= URB_QUARANTINE_CAPACITY, "ring %d vs quarantine %d", plan.ringUrbs, URB_QUARANTINE_CAPACITY);
    CHECK(USB_TRANSPORT_RING_URBS == 128, "the ring is 128 URBs deep (B47-A2: 80 before)");
    // The residual buffer must hold the leftover of a widest high-speed URB: 8 packets of 3 x 1024.
    CHECK(plan.residualCapBytes >= USB_PCM_PACKETS_PER_URB * 3 * 1024, "residual cap %d", plan.residualCapBytes);
}

static void testArray() {
    UsbTransportPolicy policy = usbTransportDefaultPolicy();
    policy.prerollMs = 500;
    const UsbTransportPlan plan = makeUsbTransportPlan(formatOf(96000, 2, 4, 24), highSpeedEndpoint(400, true), policy);
    int64_t out[USB_PLAN_FIELD_COUNT];
    usbTransportPlanToArray(plan, out);
    CHECK(out[USB_PLAN_FIELD_STATUS] == USB_PLAN_OK && out[USB_PLAN_FIELD_RING_URBS] == 128 && out[USB_PLAN_FIELD_NOMINAL_NUM] == 96000 &&
              out[USB_PLAN_FIELD_RESUME_LEAD_FRAMES] == 96000 * 50 / 1000 && out[USB_PLAN_FIELD_FEEDBACK_START_GUARD_MS] == 500 &&
              out[USB_PLAN_FIELD_PREROLL_FRAMES] == 48000 && out[USB_PLAN_FIELD_PREROLL_BLOCKS] == 6 &&
              out[USB_PLAN_FIELD_QUEUE_HARD_FRAMES] == 96000 * 11900 / 1000 && out[USB_PLAN_FIELD_FEEDBACK_BYTES] == 4,
          "flat array");
    char text[512];
    usbTransportPlanDescribe(plan, text, sizeof(text));
    CHECK(strstr(text, "ring=128") && strstr(text, "interval=125 us"), "describe: %s", text);
    CHECK(USB_PLAN_FIELD_PAUSE_KEEPALIVE_MS == 31 && out[USB_PLAN_FIELD_PAUSE_KEEPALIVE_MS] == 2000, "pause keepalive appended");
    CHECK(plan.pauseKeepaliveMs == USB_TRANSPORT_PAUSE_KEEPALIVE_MS, "pause keepalive from policy");
    // B48-fix1: the start guard is the plan's named number, and an endpoint with no feedback has nothing to hold back
    CHECK(plan.feedbackStartGuardMs == USB_TRANSPORT_FEEDBACK_START_GUARD_MS && USB_TRANSPORT_FEEDBACK_START_GUARD_MS == 500, "start guard %d ms", plan.feedbackStartGuardMs);
    CHECK(strstr(text, "startGuard=500 ms"), "describe names the start guard: %s", text);
    const UsbTransportPlan implicitFeedback = makeUsbTransportPlan(formatOf(96000, 2, 4, 24), highSpeedEndpoint(400, false), policy);
    CHECK(implicitFeedback.feedbackStartGuardMs == 0, "no feedback endpoint: start guard %d ms", implicitFeedback.feedbackStartGuardMs);
    const UsbTransportPlan refused = makeUsbTransportPlan(formatOf(384000, 2, 4, 24), highSpeedEndpoint(64, true), policy);
    usbTransportPlanDescribe(refused, text, sizeof(text));
    CHECK(strstr(text, "packet-over-max-packet"), "describe of a refusal: %s", text);
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--dump") == 0) {
        testMatrix(true);
        return g_failures ? 1 : 0;
    }
    testMatrix(false);
    testRefusals();
    testTimingAssumptions();
    static const int kFractionalRates[] = {44100, 88200, 176400, 352800};
    for (int rate : kFractionalRates) testLongRunSequence(rate);
    testStaticCapacities();
    testArray();
    if (g_failures) {
        printf("usb transport plan: %d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("usb transport plan: PASS\n");
    return 0;
}
