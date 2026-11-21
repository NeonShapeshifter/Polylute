#include "usb-glitch.h"
#include <math.h>
#include <stdio.h>
#include <string.h>

static int failures;
#define CHECK(x, why) do { if (!(x)) { printf("FAIL %s\n", why); ++failures; } } while (0)
static uint32_t rawPpm(double ppm, double nominal = 12) { return (uint32_t)llround(nominal * (1 + ppm / 1e6) * 65536); }
static UsbFeedbackReference reference(double ppm, int sessions = 2) {
    UsbFeedbackReference r = {}; r.raw = rawPpm(ppm); r.sessions = sessions; return r;
}
static void fixture(const char *name, int kind, double baseline, int sessions, bool bad) {
    UsbFeedbackReference r = reference(baseline, sessions);
    UsbFeedbackPlateau p; usbFeedbackPlateauReset(&p, &r, 1);
    UsbFeedbackHealth h; usbFeedbackHealthReset(&h);
    int64_t first = -1;
    for (int64_t ms = 0; ms <= 7200000; ms += 10) {
        double ppm = 0;
        if (kind == 1) ppm = 300;
        if (kind == 2) ppm = 406.9010416667;
        if (kind == 3) ppm = 450;
        if (kind == 4) ppm = 300 + ((ms / 10) % 7 - 3) * 3;
        if (kind == 5) ppm = fmin(350, ms / 20000.0);
        if (kind == 6) ppm = ms >= 158000 ? 406.9010416667 : 0;
        usbFeedbackHealthProgress(&h, ms); usbFeedbackHealthValid(&h, ms);
        CHECK(!usbFeedbackHealthTick(&h, ms, true, false), "healthy fixture missing-reading fault");
        if (usbFeedbackPlateauObserve(&p, rawPpm(ppm), 12, ms, true) && first < 0) first = ms;
    }
    CHECK((first >= 0) == bad, name);
    if (bad) CHECK(first == (kind == 6 ? 163000 : 5000), "wrong exact 5s plateau bound");
    printf("PLATEAU %s durationMs=7200000 firstFaultMs=%lld expected=%d\n", name, (long long)first, bad);
}
int main() {
    fixture("exact-nominal", 0, 0, 2, false);
    fixture("constant+300ppm", 1, 300, 2, false);
    fixture("constant+407ppm-no-history", 2, 0, 0, false);
    fixture("constant+450ppm", 3, 450, 2, false);
    fixture("jitter", 4, 300, 2, false);
    fixture("slow-quantised-drift", 5, 0, 2, false);
    fixture("Melody407-known-baseline", 2, 0, 2, true);
    fixture("late-jump-at158s-AMBIGUOUS", 6, 0, 2, true);
    fixture("replug-with-matching-history", 1, 300, 2, false);
    fixture("different-rate-without-history", 2, 0, 0, false);
    {
        auto r = reference(0, 0); UsbFeedbackPlateau p; usbFeedbackPlateauReset(&p, &r, 42);
        uint32_t candidate = 0;
        for (int ms = 0; ms < 180000; ms += 10) {
            usbFeedbackPlateauObserve(&p, rawPpm(ms % 1000 == 0 ? 2500 : 0), 12, ms, true);
            CHECK(!usbFeedbackReferenceCandidate(&p, ms, &candidate), "reference adopted before three minutes");
        }
        for (int ms = 180000; ms <= 181000; ms += 10) usbFeedbackPlateauObserve(&p, rawPpm(0), 12, ms, true);
        CHECK(usbFeedbackReferenceCandidate(&p, 181000, &candidate) && candidate == rawPpm(0), "stable cluster must tolerate rare refill blips");
        usbFeedbackPlateauBreak(&p);
        CHECK(!usbFeedbackReferenceCandidate(&p, 360000, &candidate), "pause must break learning");
        for (int ms = 0; ms <= 200000; ms += 10) usbFeedbackPlateauObserve(&p, rawPpm(0), 12, ms, false);
        CHECK(!usbFeedbackReferenceCandidate(&p, 200000, &candidate), "recovery or fault learned a reference");
        puts("PLATEAU learning three minutes, stable cluster, pause/recovery exclusions PASS");
    }
    {
        for (int kind = 1; kind <= 4; ++kind) if (kind != 3) {
            UsbDacBehavior b = {}; const uint32_t raw = rawPpm(kind == 1 ? 49900 : 600);
            CHECK(!usbDacBehaviorObserve(&b, kind, raw, true), "first sight accepted");
            usbDacBehaviorStep(&b, 3, false);
            CHECK(!usbDacBehaviorObserve(&b, kind, raw, true), "failed reopen accepted");
            usbDacBehaviorStep(&b, 3, true);
            CHECK(usbDacBehaviorObserve(&b, kind, raw, true), "full escalation survival not bounded");
            CHECK(usbDacBehaviorAccepted(&b, kind, raw), "signature missing");
            CHECK(!usbDacBehaviorAccepted(&b, kind, raw + 1), "different raw suppressed");
            CHECK(!usbDacBehaviorAccepted(&b, 3, raw), "no-readings suppressed by a reading exception");
            usbDacBehaviorStep(&b, 1, true);  // manually recover an already accepted reading
            usbDacBehaviorVerified(&b);
            CHECK(!usbDacBehaviorAccepted(&b, kind, raw), "a verified cure must revoke the signature's acceptance");
        }
        UsbDacBehavior b = {};
        usbDacBehaviorObserve(&b, 4, rawPpm(407), true);
        usbDacBehaviorStep(&b, 3, true);
        usbDacBehaviorVerified(&b);
        CHECK(!usbDacBehaviorObserve(&b, 4, rawPpm(407), true), "a verified cure must clear re-open progress");
        UsbDacBehavior missing = {};
        for (int i = 0; i < 100; ++i) {
            CHECK(!usbDacBehaviorObserve(&missing, 3, rawPpm(600), false), "missing accepted");
            usbDacBehaviorStep(&missing, 3, true); usbDacBehaviorVerified(&missing);
        }
        CHECK(!usbDacBehaviorAccepted(&missing, 3, rawPpm(600)), "missing behavior exemption");
        puts("PLATEAU behavior rail/stuck/plateau bounded; missing never accepted PASS");
    }
    {
        UsbFeedbackPlateau p = {}; int sessions = 0;
        for (int64_t ms = 0; ms <= 7200000; ms += 10) {
            const double nominal = (ms / 60000) % 2 ? 44100 / 8000.0 : 12;
            const uint32_t raw = rawPpm((ms / 60000) % 2 ? 300 : 0, nominal);
            if (ms % 60000 == 0 || ms % 37000 == 0) {
                UsbFeedbackReference r = {}; r.raw = raw; r.sessions = 2;
                usbFeedbackPlateauReset(&p, &r, ++sessions);
            }
            CHECK(!usbFeedbackPlateauObserve(&p, raw, nominal, ms, true), "rate switch/replug used another rate's history");
        }
        printf("PLATEAU replug/rate-switch per-rate history durationMs=7200000 sessions=%d noFault PASS\n", sessions);
        UsbDacBehavior b = {};
        for (int i = 0; i < USB_DAC_BEHAVIOR_SIGNATURES + 20; ++i) usbDacBehaviorObserve(&b, 2, rawPpm(600) + i, true);
        CHECK(!b.budgetExhausted && b.count == USB_DAC_BEHAVIOR_SIGNATURES, "changing raw exhausted the signature budget prematurely");
        usbDacBehaviorStep(&b, 3, true);
        CHECK(usbDacBehaviorObserve(&b, 2, rawPpm(600) + USB_DAC_BEHAVIOR_SIGNATURES + 19, true), "latest constant signature not retained");
        UsbDacBehavior full = {};
        for (int i = 0; i < USB_DAC_BEHAVIOR_SIGNATURES; ++i) {
            const uint32_t raw = rawPpm(600) + i;
            usbDacBehaviorObserve(&full, 2, raw, true); usbDacBehaviorStep(&full, 3, true);
            CHECK(usbDacBehaviorObserve(&full, 2, raw, true), "bounded signature not accepted");
        }
        usbDacBehaviorObserve(&full, 1, rawPpm(49900), true);
        CHECK(full.budgetExhausted && usbDacBehaviorAccepted(&full,2,rawPpm(600)), "full cache reopened an old reset loop");
        CHECK(!usbDacBehaviorAccepted(&full,3,0), "exhaustion whitelisted missing readings");
        puts("PLATEAU signature memory budget retains accepted values; missing never admitted PASS");
    }
    printf("PLATEAU pure detector %s failures=%d stateBytes=%zu behaviorBytes=%zu\n", failures ? "FAIL" : "PASS", failures, sizeof(UsbFeedbackPlateau), sizeof(UsbDacBehavior));
    return failures ? 1 : 0;
}
