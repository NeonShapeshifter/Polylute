#define main original_gate_main
#include "natural-join-test.cpp"
#undef main
#include "usb-journal.h"

static const char *keyA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
static const char *keyB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
static const char *keyC = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
static void clearEvidence() {
    g_usb.capture.clear(); g_usb.submitted.clear(); g_usb.packetFrames.clear();
    g_usb.feedbackGlitches.clear(); g_usb.playedAt.clear();
    g_usb.outputGapLines.clear();
}
#ifndef B635_PLATEAU_BEFORE
static void learnSession(int ordinal, const char *key = keyA) {
    Writer w(96000, true); usbSetFeedbackIdentity(w.ctx, key);
    const Pcm pcm = makeNoise(4096, 24, 0x635ad);
    const int64_t start = nowUs();
    while (nowUs() - start < 181000000) {
        w.write(pcm, 0, 4096); int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
        CHECK(!state[0] && !state[5], "learning healthy playback faulted"); clearEvidence();
    }
    CHECK(usbJournalFlush(5000), "reference persist timeout");
    UsbFeedbackReference r; CHECK(usbJournalReferenceGet(key, &r), "healthy session was not learned");
    CHECK(r.sessions == (uint32_t)ordinal && r.raw == 0xc0000, "confidence/reference raw wrong after session %d", ordinal);
    UsbFeedbackReference other; CHECK(!usbJournalReferenceGet(keyB, &other), "another device/rate inherited a reference");
    printf("PLATEAU real writer learned session=%d durationMs=%lld confidence=%u\n", ordinal, (long long)((nowUs()-start)/1000), r.sessions);
}
static void plateauRecovery() {
    Writer w(96000, true); usbSetFeedbackIdentity(w.ctx, keyA); usbDacRecoveryForgetEpisodes();
    const int64_t start = nowUs();
    const int64_t hourUs = 3600000000LL;
    const int64_t laterRelapses[] = {720000000, 1440000000, 2160000000LL, 2880000000LL, 3580000000LL};
    int attempts = 0;
    int64_t lastStepUs = -1;
    int64_t faultAtUs = start + 1000000;
    int64_t before[USB_DAC_RECOVERY_STATS_COUNT];
    usbDacRecoveryStats(before);
    setFeedbackScript([&](int64_t t) {
        FeedbackAnswer a;
        a.fpmf = t >= faultAtUs ? 12.0048828125 : 12.0;
        return a;
    });
    const int block = 4096;
    const Pcm pcm = makeNoise(block, 24, 0x407);
    bool accepted = false;
    bool heldByCooldown = false;
    while (nowUs() - start < hourUs) {
        w.write(pcm, 0, block); int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
        if (attempts == 1 && state[0] && !state[5] && nowUs() - lastStepUs < 30000000)
            heldByCooldown = true;
        if (state[5]) {
            CHECK(state[5] == 1, "verified relapse inherited step %lld", (long long)state[5]);
            CHECK(lastStepUs < 0 || nowUs() - lastStepUs >= 30000000, "process cooldown bypassed");
            lastStepUs = nowUs();
            ++attempts;
            int unused = 0;
            runOneStep(w, pcm, unused, (int)state[5], block, [&] {
                faultAtUs = attempts == 1 ? nowUs() + 6500000 :
                    attempts <= 6 ? start + laterRelapses[attempts - 2] : start + hourUs;
            });
        }
        accepted = accepted || usbDacBehaviorAccepted(w.ctx->recoveryControl ? &w.ctx->recoveryControl->behavior : &w.ctx->behavior, 4, 0xc0140);
        clearEvidence();
        if (nowUs() - faultAtUs > 45000000) break;
    }
    int64_t after[USB_DAC_RECOVERY_STATS_COUNT];
    usbDacRecoveryStats(after);
    CHECK(attempts == 7 && after[3] - before[3] == 7 && heldByCooldown,
        "expected initial cure and six verified relapse cures under cooldown, got %d steps / %lld verdicts",
        attempts, (long long)(after[3] - before[3]));
    CHECK(!accepted, "verified relapse accepted as normal behavior");
    CHECK(usbJournalFlush(5000), "plateau journal drain"); char *text = usbJournalExport();
    CHECK(text && strstr(text, "step 1 ok") && !strstr(text, "behavior-accepted") && !strstr(text, "reference-degraded"),
        "relapse journal lacks verified recovery or reports acceptance/degradation"); free(text);
    UsbFeedbackReference r = {};
    CHECK(usbJournalReferenceGet(keyA, &r) && r.sessions == 2 && r.raw == 0xc0000,
        "verified relapse changed the trusted reference");
    setFeedbackScript(nullptr);
    printf("PLATEAU verified recurrence durationMs=%lld cuts=%d accepted=%d confidence=%u\n",
        (long long)((nowUs() - start) / 1000), attempts, accepted, r.sessions);
}

static void persistentPlateauIsStreamLocal() {
    Writer w(96000, true); usbSetFeedbackIdentity(w.ctx, keyC); usbDacRecoveryForgetEpisodes();
    const int64_t start = nowUs();
    const Pcm pcm = makeNoise(4096, 24, 0x140);
    setFeedbackScript([](int64_t) { FeedbackAnswer a; a.fpmf = 12.0048828125; return a; });
    int steps[4] = {};
    while (nowUs() - start < 70000000) {
        w.write(pcm, 0, 4096);
        int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
        if (state[5]) {
            const int step = (int)state[5];
            ++steps[step];
            int unused = 0; runOneStep(w, pcm, unused, step, 4096);
        }
        clearEvidence();
    }
    const auto *behavior = w.ctx->recoveryControl ? &w.ctx->recoveryControl->behavior : &w.ctx->behavior;
    CHECK(steps[1] == 1 && steps[2] == 1 && steps[3] == 1 && usbDacBehaviorAccepted(behavior, 4, 0xc0140),
        "persistent plateau should be accepted after one complete ladder");
    CHECK(usbJournalFlush(5000), "persistent plateau journal drain");
    UsbFeedbackReference reference = {};
    CHECK(usbJournalReferenceGet(keyC, &reference) && reference.sessions == 2 && reference.raw == 0xc0000,
        "acceptance for one stream replaced the persistent reference");
    printf("PLATEAU sustained through reopen cuts=%d/%d/%d confidence=%u\n",
        steps[1], steps[2], steps[3], reference.sessions);
    setFeedbackScript(nullptr);
}
#endif
static void healthy600() {
    Writer w(96000, true); usbDacRecoveryForgetEpisodes();
    const int64_t start = nowUs(); int steps[4] = {}; int64_t lastCutMs = -1;
    const Pcm pcm = makeNoise(4096, 24, 0x600); const auto expected = toUsb32(pcm);
    size_t cursor = 0, initial = 0; bool first = true;
    setFeedbackScript([](int64_t) { FeedbackAnswer a; a.fpmf = 12 * 1.0006; return a; });
    bool journalAccepted = false;
    auto verifyBytes = [&] {
        if (first && w.wroteAny) { initial = (size_t)w.startSilenceFrames * 8; first = false; }
        bool exact = true;
        for (uint8_t byte : g_usb.capture) {
            if (initial) { exact = exact && byte == 0; --initial; }
            else { exact = exact && byte == expected[cursor % expected.size()]; ++cursor; }
        }
        CHECK(exact, "600ppm music bytes changed or silence inserted"); clearEvidence();
    };
    while (nowUs() - start < 7200000000LL) {
        w.write(pcm, 0, 4096); verifyBytes();
        int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
        if (state[5] >= 1 && state[5] <= 3) {
            const int step = (int)state[5]; ++steps[step]; lastCutMs = (nowUs() - start) / 1000;
            int unused = 0; runOneStep(w, pcm, unused, step, 4096); verifyBytes();
        }
#ifndef B635_PLATEAU_BEFORE
        if (!journalAccepted && usbDacBehaviorAccepted(w.ctx->recoveryControl ? &w.ctx->recoveryControl->behavior : &w.ctx->behavior, 2, (uint32_t)llround(12 * 1.0006 * 65536))) {
            CHECK(usbJournalFlush(5000), "600ppm journal drain"); char *text = usbJournalExport();
            journalAccepted = text && strstr(text, "behavior-accepted") && strstr(text, "step 3 still-stuck"); free(text);
        }
#endif
    }
    w.finish(); verifyBytes();
    CHECK(steps[1] == 1 && steps[2] == 1 && steps[3] == 1, "healthy +600ppm was cut repeatedly: steps=%d/%d/%d", steps[1],steps[2],steps[3]);
    CHECK(lastCutMs >= 0 && lastCutMs < 20000, "healthy +600ppm reset after bounded initial ladder: lastMs=%lld", (long long)lastCutMs);
    CHECK(w.ctx->framesWritten * 8 == (int64_t)cursor, "600ppm frames lost/repeated on full reopen");
#ifndef B635_PLATEAU_BEFORE
    CHECK(journalAccepted, "600ppm acceptance was not journalled");
    CHECK(w.ctx->feedbackWatch.acceptedRun, "600ppm still refused by follower after acceptance");
    const UsbDacRecoveryPolicy &policy = w.ctx->recoveryControl ? w.ctx->recoveryControl->policy : w.ctx->dacPolicy;
    CHECK(policy.awaitingStep == 0 && w.ctx->feedbackHealthySinceMs < 0, "ignored raw fault falsely passed the shared health verifier");
#endif
    printf("PLATEAU healthy+600ppm durationMs=%lld cuts=%d/%d/%d lastCutMs=%lld sourceBytes=%zu EXACT journal=%d controlBytes=%zu\n",
        (long long)((nowUs()-start)/1000), steps[1],steps[2],steps[3], (long long)lastCutMs,cursor,journalAccepted,sizeof(UsbRecoveryControl));
    setFeedbackScript(nullptr);
}
int main() {
#ifndef B635_PLATEAU_BEFORE
    char directory[] = "/tmp/polylute-reference-XXXXXX"; CHECK(mkdtemp(directory), "reference directory");
    CHECK(usbJournalConfigure(directory), "reference configure");
    learnSession(1); learnSession(2);
    usbJournalShutdown(); CHECK(usbJournalConfigure(directory), "reference restart");
    UsbFeedbackReference r; CHECK(usbJournalReferenceGet(keyA,&r) && r.sessions == 2, "reference lost across restart");
    plateauRecovery();
    learnSession(1, keyC); learnSession(2, keyC);
    persistentPlateauIsStreamLocal();
#endif
    healthy600();
#ifndef B635_PLATEAU_BEFORE
#ifdef POLYLUTE_DEBUG_FAULTS
    {
        Writer w(96000,true); usbDacRecoveryForgetEpisodes(); const Pcm pcm = makeNoise(2048,24,0xdeb4);
        w.write(pcm,0,2048); const double before = w.ctx->calibratedFpmf;
        usbRequestDacRecovery(-104); int64_t state[USB_DAC_STATE_COUNT];usbDacLockState(w.ctx,state);
        CHECK(state[0] && state[5] == 1 && w.ctx->calibratedFpmf == before, "debug plateau missing/changes follower");
        int unused=0;runOneStep(w,pcm,unused,1,2048);
        for(int i=0;i<260;++i) {w.write(pcm,0,2048);usbDacLockState(w.ctx,state);clearEvidence();}
        CHECK(!state[0], "debug plateau did not recover"); puts("PLATEAU debug injection recovered with real feedback PASS");
    }
#endif
    usbJournalShutdown();
    char name[1024];for (const char *f : {"current.jsonl","previous.jsonl","feedback-reference.txt","feedback-reference.tmp"}) {snprintf(name,sizeof(name),"%s/%s",directory,f);unlink(name);}rmdir(directory);
#endif
    printf("PLATEAU real writer %s failures=%d\n",g_failures ? "FAIL":"PASS",g_failures); return g_failures ? 1:0;
}
