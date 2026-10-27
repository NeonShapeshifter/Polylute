// Host tests for the one-second summaries of the USB stream (B27-A5, review N-07): the ring keeps the last 30 s and nothing
// more, a second's numbers are the min / max / mean of what was recorded in it, the second still running is not reported, and the
// dump carries the fields the owner's "mark noise" file is read for.
#include "usb-second-ring.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_failures = 0;
#define CHECK(cond, ...)                                  \
    do {                                                  \
        if (!(cond)) {                                    \
            g_failures++;                                 \
            printf("  FAIL %s:%d: ", __FILE__, __LINE__); \
            printf(__VA_ARGS__);                          \
            printf("\n");                                 \
        }                                                 \
    } while (0)

static UsbSecondRing ring;
static UsbSecondSample out[USB_SECOND_RING_CAPACITY];

static void recordSeconds(int64_t fromSecond, int64_t toSecondExclusive) {
    for (int64_t sec = fromSecond; sec < toSecondExclusive; sec++) {
        usbSecondRingFeedback(&ring, sec * 1000 + 500, 12.0, 12.0, false);
    }
}

static void testKeepsTheLast30SecondsAndNothingMore() {
    usbSecondRingInit(&ring);
    recordSeconds(0, 100);
    const int64_t now = 100 * 1000 + 200;
    usbSecondRingFeedback(&ring, now, 12.0, 12.0, false);  // the second in progress

    int n = usbSecondRingLast(&ring, now, 30000, out, USB_SECOND_RING_CAPACITY);
    CHECK(n == 30, "%d samples for a 30 s window, expected 30", n);
    CHECK(out[0].second == 70 && out[n - 1].second == 99, "window is seconds %lld..%lld, expected 70..99",
          (long long)out[0].second, (long long)out[n - 1].second);
    for (int i = 1; i < n; i++) CHECK(out[i].second == out[i - 1].second + 1, "seconds out of order at %d", i);

    n = usbSecondRingLast(&ring, now, 5000, out, USB_SECOND_RING_CAPACITY);
    CHECK(n == 5 && out[0].second == 95, "%d samples for 5 s, first %lld", n, (long long)out[0].second);
    CHECK(usbSecondRingLast(&ring, now, 0, out, USB_SECOND_RING_CAPACITY) == 0, "an empty window returned samples");
    CHECK(usbSecondRingLast(&ring, now, 30000, out, 7) == 7, "max was not honoured");
}

static void testOlderThanTheCapacityIsGone() {
    usbSecondRingInit(&ring);
    recordSeconds(0, 300);
    const int64_t now = 300 * 1000 + 100;
    usbSecondRingFeedback(&ring, now, 12.0, 12.0, false);
    const int n = usbSecondRingLast(&ring, now, 1000 * 1000, out, USB_SECOND_RING_CAPACITY);
    CHECK(n == USB_SECOND_RING_CAPACITY, "%d samples kept, expected the capacity %d", n, USB_SECOND_RING_CAPACITY);
    CHECK(out[0].second == 300 - USB_SECOND_RING_CAPACITY && out[n - 1].second == 299, "kept seconds %lld..%lld",
          (long long)out[0].second, (long long)out[n - 1].second);
    CHECK(usbSecondRingLast(&ring, now, 30000, out, USB_SECOND_RING_CAPACITY) == 30, "a 30 s window after wrap-around is not 30");
}

static void testASecondIsItsMinMaxMeanAndCounts() {
    usbSecondRingInit(&ring);
    usbSecondRingFeedback(&ring, 5000, 12.0000, 12.0, false);
    usbSecondRingFeedback(&ring, 5100, 12.0100, 12.0, true);
    usbSecondRingFeedback(&ring, 5200, 11.9900, 12.0, true);
    usbSecondRingRefused(&ring, 5300);
    usbSecondRingRefused(&ring, 5400);
    usbSecondRingIso(&ring, 5500, 8, 0, 0);
    usbSecondRingIso(&ring, 5600, 8, 1, 2);
    usbSecondRingFeedback(&ring, 6100, 12.0, 12.0, false);  // the next second closes it
    const int n = usbSecondRingLast(&ring, 7000, 30000, out, USB_SECOND_RING_CAPACITY);
    CHECK(n == 1, "%d samples", n);
    const UsbSecondSample &s = out[0];
    CHECK(s.second == 5 && s.readings == 3 && s.deviating == 2 && s.refused == 2, "second %lld readings %d deviating %d refused %d",
          (long long)s.second, s.readings, s.deviating, s.refused);
    CHECK(s.fpmfMin == 11.99 && s.fpmfMax == 12.01, "min %.5f max %.5f", s.fpmfMin, s.fpmfMax);
    CHECK(s.fpmfSum / s.readings > 11.9999 && s.fpmfSum / s.readings < 12.0001, "mean %.5f", s.fpmfSum / s.readings);
    CHECK(s.isoUrbs == 2 && s.isoPackets == 16 && s.isoPacketErrors == 1 && s.isoShortPackets == 2, "iso %d/%d/%d/%d",
          s.isoUrbs, s.isoPackets, s.isoPacketErrors, s.isoShortPackets);
}

static void testSecondsWithoutDataAreAbsentAndTheRunningOneIsNotReported() {
    usbSecondRingInit(&ring);
    usbSecondRingFeedback(&ring, 1500, 12.0, 12.0, false);
    usbSecondRingFeedback(&ring, 10500, 12.0, 12.0, false);
    usbSecondRingFeedback(&ring, 12500, 12.0, 12.0, false);   // second 12 is running
    const int n = usbSecondRingLast(&ring, 12800, 30000, out, USB_SECOND_RING_CAPACITY);
    CHECK(n == 2 && out[0].second == 1 && out[1].second == 10, "%d samples, seconds %lld, %lld", n,
          (long long)out[0].second, n > 1 ? (long long)out[1].second : -1);
}

static void testTheDumpCarriesTheFieldsItIsReadFor() {
    usbSecondRingInit(&ring);
    usbSecondRingFeedback(&ring, 2100, 12.0049, 12.0, true);
    usbSecondRingFeedback(&ring, 2200, 12.0049, 12.0, true);
    usbSecondRingIso(&ring, 2300, 8, 0, 0);
    usbSecondRingFeedback(&ring, 3100, 12.0, 12.0, false);
    const int64_t now = 4000;
    const int n = usbSecondRingLast(&ring, now, 30000, out, USB_SECOND_RING_CAPACITY);
    char text[1024];
    const size_t used = usbSecondRingFormat(out, n, now, text, sizeof(text));
    CHECK(used == strlen(text), "reported %zu bytes, the text is %zu", used, strlen(text));
    static const char *const wanted[] = {"t-2s", "feedback n=2", "min=12.00490", "max=12.00490", "mean=12.00490", "nominal=12.00000",
                                         "deviating=2", "refused=0", "iso urbs=1", "packets=8", "errors=0", "short=0"};
    for (const char *want : wanted) {
        CHECK(strstr(text, want) != nullptr, "the dump has no '%s': %s", want, text);
    }
    // a buffer that cannot hold the line holds none of it
    char tiny[40];
    usbSecondRingFormat(out, n, now, tiny, sizeof(tiny));
    CHECK(strchr(tiny, '\n') == nullptr || tiny[strlen(tiny) - 1] == '\n', "a line was cut in half: %s", tiny);
}

static void testItIsOneFixedBlock() {
    // B41-fix1: ~200 bytes a second for the policy's numbers as well (it was ~120); still one block, no allocation.
    CHECK(sizeof(UsbSecondRing) < 40 * 1024, "the ring is %zu bytes", sizeof(UsbSecondRing));
}

// ── B41-fix1 (A1c): what the 2026-10-25 episodes could not show ──────────────────────────────────────────────

/** 17:39:57, Ciega, Sordomuda: the Melody stuck at +0.816 %. `auto` sent the nominal rate and `refused` stayed 0. */
static void testAPlateauTheMarkUsedToCallRefusedZeroIsHeld() {
    usbSecondRingInit(&ring);
    for (int i = 0; i < 1000; i++) {
        const int64_t t = 8000 + i;   // second 8, a reading a millisecond
        usbSecondRingFeedback(&ring, t, 5.5575, 5.5125, true);
        usbSecondRingHeld(&ring, t);
    }
    usbSecondRingFeedback(&ring, 9100, 5.5125, 5.5125, false);   // second 9: closes 8
    const int n = usbSecondRingLast(&ring, 10000, 30000, out, USB_SECOND_RING_CAPACITY);
    CHECK(n == 1 && out[0].readings == 1000 && out[0].held == 1000 && out[0].refused == 0,
          "held %d refused %d readings %d: the plateau must show as held, with refused still 0", out[0].held, out[0].refused, out[0].readings);
}

static void testFeedbackThatWasNotAReadingIsCountedByClassWithItsCodes() {
    usbSecondRingInit(&ring);
    usbSecondRingFeedbackDropped(&ring, 3000, 2, 0, -84, 4);       // the packet said -EILSEQ with four bytes
    usbSecondRingFeedbackDropped(&ring, 3100, 2, 0, -84, 4);
    usbSecondRingFeedbackDropped(&ring, 3200, 1, -104, 0, 4);      // the URB said -ECONNRESET
    usbSecondRingFeedbackDropped(&ring, 3300, 3, 0, 0, 3);         // clean, three bytes
    usbSecondRingFeedback(&ring, 4100, 5.5125, 5.5125, false);
    const int n = usbSecondRingLast(&ring, 6000, 30000, out, USB_SECOND_RING_CAPACITY);
    CHECK(n == 1, "%d samples", n);
    const UsbSecondSample &s = out[0];
    CHECK(s.dropUrb == 1 && s.dropPacket == 2 && s.dropLength == 1, "dropped %d/%d/%d, expected 1/2/1", s.dropUrb, s.dropPacket, s.dropLength);
    CHECK(s.lastUrbStatus == 0 && s.lastPacketStatus == 0 && s.lastLength == 3, "last codes %d/%d/%d, expected 0/0/3", s.lastUrbStatus, s.lastPacketStatus, s.lastLength);
    CHECK(s.readings == 0, "a transfer that was not a reading was counted as one");
    char text[2048];
    usbSecondRingFormat(out, n, 6000, text, sizeof(text));
    CHECK(strstr(text, "dropped urb=1 packet=2 length=1 last=0/0/3") != nullptr, "the dump does not say it: %s", text);
}

static void testTheAgeOfTheLastValidReadingIsMeasuredAtTheEndOfEachSecond() {
    usbSecondRingInit(&ring);
    usbSecondRingFeedback(&ring, 5100, 5.5125, 5.5125, false);       // the last valid one
    usbSecondRingFeedbackDropped(&ring, 6200, 2, 0, -71, 4);        // second 6: only errors
    usbSecondRingFeedbackDropped(&ring, 8300, 2, 0, -71, 4);        // second 8 (7 had nothing at all)
    usbSecondRingFeedbackDropped(&ring, 9300, 2, 0, -71, 4);
    const int n = usbSecondRingLast(&ring, 11000, 30000, out, USB_SECOND_RING_CAPACITY);
    CHECK(n == 3 && out[0].second == 5 && out[1].second == 6 && out[2].second == 8, "%d samples", n);
    CHECK(out[0].ageMs == 900, "second 5 ended 900 ms after its reading: %lld", (long long)out[0].ageMs);
    CHECK(out[1].ageMs == 1900, "second 6 ended 1900 ms after it: %lld", (long long)out[1].ageMs);
    CHECK(out[2].ageMs == 3900, "second 8 ended 3900 ms after it: %lld", (long long)out[2].ageMs);
    // a stream that has had none says so
    usbSecondRingInit(&ring);
    usbSecondRingFeedbackDropped(&ring, 1200, 2, 0, -71, 4);
    usbSecondRingFeedbackDropped(&ring, 2200, 2, 0, -71, 4);
    CHECK(usbSecondRingLast(&ring, 4000, 30000, out, USB_SECOND_RING_CAPACITY) == 1 && out[0].ageMs == -1, "age with no valid reading: %lld", (long long)out[0].ageMs);
    // and an error is not a valid reading: it does not freshen the age
    usbSecondRingInit(&ring);
    usbSecondRingFeedback(&ring, 1100, 5.5125, 5.5125, false);
    usbSecondRingFeedbackDropped(&ring, 1900, 2, 0, -71, 4);
    usbSecondRingFeedbackDropped(&ring, 2900, 2, 0, -71, 4);
    usbSecondRingLast(&ring, 4000, 30000, out, USB_SECOND_RING_CAPACITY);
    CHECK(out[0].ageMs == 900 && out[1].ageMs == 1900, "ages %lld, %lld: a dropped transfer must not count as a valid reading", (long long)out[0].ageMs, (long long)out[1].ageMs);
}

static void testTheRateReceivedTheRateGivenAndTheRateThatWentOutAreThreeNumbers() {
    usbSecondRingInit(&ring);
    usbSecondRingFeedback(&ring, 2100, 5.5575, 5.5125, true);
    usbSecondRingSent(&ring, 2150, 5.5575);
    usbSecondRingSent(&ring, 2250, 5.5125);
    usbSecondRingSent(&ring, 2350, 5.5350);
    // 8-packet URBs: 44 and 45 frames, the packetizer's own rounding
    usbSecondRingWire(&ring, 2200, 44, 8);
    usbSecondRingWire(&ring, 2300, 45, 8);
    usbSecondRingWire(&ring, 2400, 44, 8);
    usbSecondRingFeedback(&ring, 3100, 5.5125, 5.5125, false);
    const int n = usbSecondRingLast(&ring, 5000, 30000, out, USB_SECOND_RING_CAPACITY);
    const UsbSecondSample &s = out[0];
    CHECK(n == 1 && s.sentCalls == 3 && s.sentMin == 5.5125 && s.sentMax == 5.5575, "sent %d calls %.4f..%.4f", s.sentCalls, s.sentMin, s.sentMax);
    CHECK(s.wireFrames == 133 && s.wirePackets == 24, "wire %lld frames in %lld packets", (long long)s.wireFrames, (long long)s.wirePackets);
    char text[2048];
    usbSecondRingFormat(out, n, 5000, text, sizeof(text));
    CHECK(strstr(text, "sent min=5.51250 max=5.55750 calls=3") != nullptr, "the dump has no sent range: %s", text);
    CHECK(strstr(text, "wire frames=133 packets=24 applied=5.54167") != nullptr, "the dump has no applied rate: %s", text);
    CHECK(strstr(text, "mean=5.55750") != nullptr, "the received rate is no longer in the line: %s", text);
}

static void testPolicyChangesKeepTheirStreamAndWhatWasSent() {
    usbSecondRingInit(&ring);
    UsbPolicyEvent e;
    memset(&e, 0, sizeof(e));
    e.atMs = 100000;
    e.stream = 41;
    e.rate = 44100;
    e.kind = USB_POLICY_REFUSE_BEGIN;
    e.what = "absurd";
    e.fpmf = 5.6274;
    e.ppm = 20851.0;
    e.sentBefore = 5.5724;
    e.sentAfter = 5.5127;
    e.policy = 3;
    usbSecondRingPolicyEvent(&ring, &e);
    e.atMs = 100250;
    e.kind = USB_POLICY_REFUSE_END;
    e.what = "absurd";
    e.sentBefore = 5.5127;
    e.sentAfter = 5.5325;
    usbSecondRingPolicyEvent(&ring, &e);
    UsbPolicyEvent got[USB_POLICY_EVENT_CAPACITY];
    int n = usbSecondRingEvents(&ring, 101000, 30000, got, USB_POLICY_EVENT_CAPACITY);
    CHECK(n == 2 && got[0].kind == USB_POLICY_REFUSE_BEGIN && got[1].kind == USB_POLICY_REFUSE_END && got[0].stream == 41, "%d events", n);
    CHECK(usbSecondRingEvents(&ring, 101000, 800, got, USB_POLICY_EVENT_CAPACITY) == 1, "a 800 ms window must hold only the later event (750 ms ago)");
    char text[1024];
    usbSecondRingFormatEvents(got, usbSecondRingEvents(&ring, 101000, 30000, got, USB_POLICY_EVENT_CAPACITY), 101000, [](int p) { return p == 3 ? "auto" : "?"; }, text, sizeof(text));
    CHECK(strstr(text, "event t-1.000s stream=41 rate=44100 refuse-begin absurd reading=5.62740 +20851ppm policy=auto sending=5.57240->5.51270") != nullptr,
          "the event line is not what a reader greps for: %s", text);
    CHECK(strstr(text, "event t-0.750s stream=41 rate=44100 refuse-end absurd") != nullptr, "the end of the change is missing: %s", text);
    // the ring keeps the last USB_POLICY_EVENT_CAPACITY, oldest dropped
    for (int i = 0; i < USB_POLICY_EVENT_CAPACITY + 10; i++) {
        e.atMs = 200000 + i;
        e.stream = 100 + i;
        usbSecondRingPolicyEvent(&ring, &e);
    }
    n = usbSecondRingEvents(&ring, 201000, 30000, got, USB_POLICY_EVENT_CAPACITY);
    CHECK(n == USB_POLICY_EVENT_CAPACITY && got[0].stream == 110 && got[n - 1].stream == 100 + USB_POLICY_EVENT_CAPACITY + 9, "wrap: %d events from stream %lld", n, (long long)got[0].stream);
    // a gesture has no reading: its line is shorter, and names the stream and the policy
    UsbPolicyEvent g;
    memset(&g, 0, sizeof(g));
    g.atMs = 201500;
    g.stream = 41;
    g.rate = 44100;
    g.kind = USB_POLICY_RESUME;
    g.what = "fade-in";
    g.sentBefore = 5.5127;
    g.policy = 3;
    char gestureText[256];
    usbSecondRingFormatEvents(&g, 1, 201900, [](int p) { return p == 3 ? "auto" : "?"; }, gestureText, sizeof(gestureText));
    CHECK(strcmp(gestureText, "event t-0.400s stream=41 rate=44100 resume fade-in policy=auto sending=5.51270\n") == 0, "the gesture line: %s", gestureText);
    // a line that does not fit is left out whole
    char tiny[60];
    usbSecondRingFormatEvents(got, n, 201000, [](int) { return "auto"; }, tiny, sizeof(tiny));
    CHECK(strchr(tiny, '\n') == nullptr || tiny[strlen(tiny) - 1] == '\n', "an event line was cut in half: %s", tiny);
}

static volatile bool g_stop = false;
static volatile int g_reads = 0, g_bad = 0;
static void *reader(void *) {
    static UsbSecondSample mine[USB_SECOND_RING_CAPACITY];
    while (!g_stop) {
        const int n = usbSecondRingLast(&ring, 6000 * 1000LL, 3000 * 1000LL, mine, USB_SECOND_RING_CAPACITY);
        for (int i = 1; i < n; i++) {
            if (mine[i].second != mine[i - 1].second + 1) g_bad++;
        }
        g_reads++;
    }
    return nullptr;
}

static void testADumpFromAnotherThreadSeesWholeConsistentSeconds() {
    usbSecondRingInit(&ring);
    pthread_t t;
    pthread_create(&t, nullptr, reader, nullptr);
    for (int sec = 0; sec < 5000; sec++) usbSecondRingFeedback(&ring, (int64_t)sec * 1000 + 100, 12.0, 12.0, false);
    g_stop = true;
    pthread_join(t, nullptr);
    CHECK(g_bad == 0, "%d torn windows in %d reads", g_bad, g_reads);
}

int main() {
    testKeepsTheLast30SecondsAndNothingMore();
    testOlderThanTheCapacityIsGone();
    testASecondIsItsMinMaxMeanAndCounts();
    testSecondsWithoutDataAreAbsentAndTheRunningOneIsNotReported();
    testTheDumpCarriesTheFieldsItIsReadFor();
    testAPlateauTheMarkUsedToCallRefusedZeroIsHeld();
    testFeedbackThatWasNotAReadingIsCountedByClassWithItsCodes();
    testTheAgeOfTheLastValidReadingIsMeasuredAtTheEndOfEachSecond();
    testTheRateReceivedTheRateGivenAndTheRateThatWentOutAreThreeNumbers();
    testPolicyChangesKeepTheirStreamAndWhatWasSent();
    testItIsOneFixedBlock();
    testADumpFromAnotherThreadSeesWholeConsistentSeconds();
    if (g_failures != 0) {
        printf("usb second ring tests: %d FAILED check(s)\n", g_failures);
        return 1;
    }
    printf("usb second ring tests: PASS\n");
    return 0;
}
