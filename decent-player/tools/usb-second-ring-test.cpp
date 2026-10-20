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
    CHECK(sizeof(UsbSecondRing) < 24 * 1024, "the ring is %zu bytes", sizeof(UsbSecondRing));
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
    testItIsOneFixedBlock();
    testADumpFromAnotherThreadSeesWholeConsistentSeconds();
    if (g_failures != 0) {
        printf("usb second ring tests: %d FAILED check(s)\n", g_failures);
        return 1;
    }
    printf("usb second ring tests: PASS\n");
    return 0;
}
