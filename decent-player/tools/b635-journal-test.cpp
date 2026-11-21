#define main original_gate_main
#include "natural-join-test.cpp"
#undef main
#include "usb-journal.h"

int main() {
    char directory[] = "/tmp/b635-writer-journal-XXXXXX";
    CHECK(mkdtemp(directory) != nullptr, "private journal fixture directory");
    Writer w(96000, true);
    UsbJournalFacts facts = {1, 17, 96000, 32, 3, -2560, 1, 2};
    CHECK(usbJournalConfigure(directory), "real writer journal configure");
    usbJournalPlatform(w.ctx->streamSeq, &facts, 6);
    usbDacRecoveryForgetEpisodes(); usbSetDacRecoveryEnabled(true);
    const Pcm pcm = makeNoise(2048, 24, 0x635600);
    const int64_t start = nowUs();
    bool cured = false, reopen = false;
    int step1 = 0, step3 = 0;
    setFeedbackScript([&](int64_t at) {
        FeedbackAnswer a; a.fpmf = 12.0;
        if (!cured && at - start >= 500000) a.length = 0;
        return a;
    });
    while (nowUs() - start < 13000000) {
        w.write(pcm, 0, 2048);
        if (!reopen && nowUs() - start > 6500000) {
            usbRequestDacRecovery(3); reopen = true;
        }
        int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
        if (state[5] >= 1 && state[5] <= 3) {
            int ignored = 0;
            if (state[5] == 1) ++step1;
            if (state[5] == 3) { ++step3; facts.connection = 2; usbJournalPlatform(0, &facts, 6); }
            runOneStep(w, pcm, ignored, (int)state[5], 2048, [&] { cured = true; });
            usbJournalPlatform(w.ctx->streamSeq, &facts, 6);
        }
        // Logcat evidence is discarded. The journal must be driven by the output's events.
        g_usb.trainLines.clear(); g_usb.feedbackGlitches.clear();
    }
    w.finish(); CHECK(usbJournalFlush(5000), "actual writer journal drain");
    char *text = usbJournalExport();
    CHECK(step1 == 1 && step3 == 1, "unexpected fixture recovery ladder");
    CHECK(text && strstr(text, "initial-reading") && strstr(text, "no-readings"), "actual start and watchdog cause missing");
    CHECK(text && strstr(text, "frozen context"), "actual detector did not freeze the prior ring");
    CHECK(text && strstr(text, "step 1 begin") && strstr(text, "step 1 ran") && strstr(text, "step 1 ok"), "item 2 real health verdict not persisted");
    CHECK(text && strstr(text, "step 3 begin") && strstr(text, "step 3 ran") && strstr(text, "step 3 ok"), "full reopen verdict not persisted");
    CHECK(text && strstr(text, "music-resumed") && strstr(text, "dryUs") && strstr(text, "nextRetryMs"), "audio gap/backoff missing");
    CHECK(text && strstr(text, "feedback-state") && strstr(text, "\"length\":0"), "feedback state transition missing");
    printf("B635 real writer journal start/watchdog/snapshot/steps1+3/shared-health/gap/backoff/logcat-discarded %s bytes=%zu\n",
        g_failures ? "FAIL" : "PASS", text ? strlen(text) : 0);
    free(text); usbJournalShutdown(); setFeedbackScript(nullptr);
    char file[1024]; snprintf(file,sizeof(file),"%s/current.jsonl",directory); unlink(file);
    snprintf(file,sizeof(file),"%s/previous.jsonl",directory); unlink(file); rmdir(directory);
    return g_failures ? 1 : 0;
}
