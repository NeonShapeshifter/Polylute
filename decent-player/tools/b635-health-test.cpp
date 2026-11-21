#define main original_gate_main
#include "natural-join-test.cpp"
#undef main

static void silenceFeedbackCompletions() {
    std::lock_guard<std::mutex> lock(g_usb.mu);
    for (auto &p : g_usb.inflight) if (p.feedback) p.completeAtUs = nowUs() + 3600000000LL;
}
static void replayMissing(bool noCompletions, bool cure, int rate) {
    Writer w(rate, true);
    usbSetDacRecoveryEnabled(true); usbDacRecoveryForgetEpisodes();
    const Pcm pcm = makeNoise(2048, 24, 0x6352);
    const int baseline = g_usb.setInterfaceCalls;
    const int64_t start = nowUs();
    const int64_t faultyAt = start + 1000000;
    setFeedbackScript([&](int64_t at) {
        FeedbackAnswer a; a.fpmf = rate == 44100 ? 361264.0 / 65536.0 : 12.;
        if (at >= faultyAt && !(cure && g_usb.setInterfaceCalls > baseline)) a.length = 0;
        return a;
    });
    int64_t detected = -1;
    bool ran = false;
    bool verified = false;
    bool escalated = false;
    while (nowUs() - start < 8000000) {
        if (noCompletions && nowUs() >= faultyAt && !ran) silenceFeedbackCompletions();
        w.write(pcm, 0, 2048);
        int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
        if (state[5] == 1 && !ran) {
            detected = (nowUs() - faultyAt) / 1000;
            CHECK(detected >= 450 && detected <= 650, "detection bound %lldms", (long long)detected);
            Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryBegin(nullptr, nullptr, w.usb);
            if (cure) {
                usbdevfs_setinterface si = {}; si.interface = 1;
                ioctl(kFakeFd, USBDEVFS_SETINTERFACE, &si);
                si.altsetting = 3; ioctl(kFakeFd, USBDEVFS_SETINTERFACE, &si);
            }
            Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryEnd(nullptr, nullptr, w.usb);
            usbNoteDacRecovery(w.ctx, 1, 0, 0);
            w.resume();
            ran = true;
        }
        if (state[5] == 2) escalated = true;
        char dump[65536]; usbSecondSummaries(128000, dump, sizeof(dump));
        char ok[160]; snprintf(ok, sizeof(ok), "stream=%lld rate=%d dac-recovery step 1 ok", (long long)w.ctx->streamSeq, rate);
        verified = verified || strstr(dump, ok) != nullptr;
    }
    CHECK(ran, "missing feedback never ran step 1");
    if (cure) CHECK(verified && !escalated, "healthy step 1 not verified");
    else CHECK(!verified && escalated, "recovery without readings called ok or never escalated");
    printf("B635 missing rate=%d mode=%s cure=%d firstStepMs=%lld ran=%d verified=%d escalated=%d\n",
        rate, noCompletions ? "no-completions" : "zero-length", cure, (long long)detected, ran, verified, escalated);
    setFeedbackScript(nullptr);
}
static void intentionalSilence() {
    for (int gesture = 0; gesture < 3; ++gesture) {
        Writer w(96000, true); usbDacRecoveryForgetEpisodes();
        const Pcm pcm = makeNoise(2048, 24, 0x42);
        for (int i = 0; i < 20; ++i) w.write(pcm, 0, 2048);
        setFeedbackScript([](int64_t) { FeedbackAnswer a; a.length = 0; return a; });
        if (gesture == 0) w.beginPause();
        if (gesture == 1) w.beginSeek();
        if (gesture == 2) w.ctx->endpointFeedback = 0;
        for (int i = 0; i < 2000; ++i) {
            if (gesture < 2) w.feedSilence(); else w.write(pcm, 0, 2048);
            int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
            CHECK(state[5] == 0 && state[0] == 0, "false alarm gesture/no-endpoint %d", gesture);
        }
        printf("B635 intentional-silence mode=%d PASS\n", gesture);
        setFeedbackScript(nullptr);
    }
}
static void slowValidAndRearm() {
    for (int rate : {44100, 96000}) {
        Writer w(rate, true, rate == 96000 ? 44100 : 0);
        usbDacRecoveryForgetEpisodes();
        setFeedbackScript([=](int64_t) { FeedbackAnswer a; a.fpmf = rate / 8000.0; a.completeAfterUs = 250000; return a; });
        const Pcm pcm = makeNoise(2048, 24, 0x250);
        const int64_t start = nowUs();
        while (nowUs() - start < 8000000) {
            w.write(pcm, 0, 2048);
            int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
            CHECK(state[0] == 0 && state[5] == 0, "slow valid readings triggered rate=%d", rate);
        }
        // Seek and pause abandon old ages. A fresh 400ms start without readings is allowed.
        for (int gesture = 0; gesture < 2; ++gesture) {
            if (gesture == 0) w.beginPause(); else w.beginSeek();
            w.endFadeOut();
            for (int n = 0; n < 1000; ++n) w.feedSilence();
            w.resume();
            const int64_t resumeAt = nowUs();
            setFeedbackScript([=](int64_t at) { FeedbackAnswer a; a.fpmf = rate / 8000.0; if (at - resumeAt < 400000) a.length = 0; return a; });
            while (nowUs() - resumeAt < 2000000) {
                w.write(pcm, 0, 2048);
                int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
                CHECK(state[0] == 0 && state[5] == 0, "re-arm inherited pre-gesture age");
            }
        }
        printf("B635 slow-valid 250ms / first-valid grace / re-arm rate=%d PASS\n", rate);
        setFeedbackScript(nullptr);
    }
}
static void verifyForcedRecoveryNeedsReadings() {
    Writer w(96000, true);
    usbDacRecoveryForgetEpisodes();
    const Pcm pcm = makeNoise(2048, 24, 0x4e0);
    for (int i = 0; i < 20; ++i) w.write(pcm, 0, 2048);
    setFeedbackScript([](int64_t) { FeedbackAnswer a; a.length = 0; return a; });
    Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryBegin(nullptr, nullptr, w.usb);
    Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryEnd(nullptr, nullptr, w.usb);
    usbNoteDacRecovery(w.ctx, 1, 0, 0);
    w.resume();
    const int64_t from = nowUs();
    while (nowUs() - from < 6000000) {
        w.write(pcm, 0, 2048);
        int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
    }
    char dump[65536]; usbSecondSummaries(128000, dump, sizeof(dump));
    char ok[160], failed[160];
    snprintf(ok, sizeof(ok), "stream=%lld rate=96000 dac-recovery step 1 ok", (long long)w.ctx->streamSeq);
    snprintf(failed, sizeof(failed), "stream=%lld rate=96000 dac-recovery step 1 no-readings", (long long)w.ctx->streamSeq);
    CHECK(!strstr(dump, ok) && strstr(dump, failed), "manual step was judged healthy without any readings");
    printf("B635 forced step without readings verified=%d rejected=%d\n", strstr(dump, ok) != nullptr, strstr(dump, failed) != nullptr);
    setFeedbackScript(nullptr);
}
static void verifyWindowDoesNotBridgeUnobservedIdle() {
    Writer w(96000, true);
    usbDacRecoveryForgetEpisodes();
    const Pcm pcm = makeNoise(2048, 24, 0x14e);
    for (int i = 0; i < 20; ++i) w.write(pcm, 0, 2048);
    Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryBegin(nullptr, nullptr, w.usb);
    Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryEnd(nullptr, nullptr, w.usb);
    usbNoteDacRecovery(w.ctx, 1, 0, 0);
    w.resume();
    const int64_t first = nowUs();
    while (nowUs() - first < 500000) {
        w.write(pcm, 0, 2048);
        int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
    }
    sleepMs(5000); // No completion/query callback while the producer is descheduled.
    w.write(pcm, 0, 2048);
    int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
    char ok[160]; snprintf(ok, sizeof(ok), "stream=%lld rate=96000 dac-recovery step 1 ok", (long long)w.ctx->streamSeq);
    char dump[65536]; usbSecondSummaries(128000, dump, sizeof(dump));
    const bool premature = strstr(dump, ok) != nullptr;
    CHECK(!premature, "verification bridged five seconds without audio/readings");
    const int64_t resumeAt = nowUs();
    while (nowUs() - resumeAt < 4300000) {
        w.write(pcm, 0, 2048);
        usbDacLockState(w.ctx, state);
    }
    usbSecondSummaries(128000, dump, sizeof(dump));
    const bool eventual = strstr(dump, ok) != nullptr;
    CHECK(eventual, "verification did not complete after a new healthy window");
    printf("B635 verify window after unobserved producer gap premature=%d eventual=%d\n", premature, eventual);
}
static void verifyWithoutFeedbackEndpoint() {
    Writer w(96000, true);
    usbDacRecoveryForgetEpisodes();
    const Pcm pcm = makeNoise(2048, 24, 0x4e1);
    for (int i = 0; i < 20; ++i) w.write(pcm, 0, 2048);
    w.ctx->endpointFeedback = 0;
    Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryBegin(nullptr, nullptr, w.usb);
    Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryEnd(nullptr, nullptr, w.usb);
    usbNoteDacRecovery(w.ctx, 1, 0, 0);
    w.resume();
    const int64_t from = nowUs();
    while (nowUs() - from < 5000000) {
        w.write(pcm, 0, 2048);
        int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
        CHECK(state[0] == 0 && state[5] == 0, "no-endpoint manual recovery triggered a fault");
    }
    char ok[160], dump[65536];
    snprintf(ok, sizeof(ok), "stream=%lld rate=96000 dac-recovery step 1 ok", (long long)w.ctx->streamSeq);
    usbSecondSummaries(128000, dump, sizeof(dump));
    CHECK(strstr(dump, ok), "no-endpoint recovery blocked on readings it does not expect");
    printf("B635 no-endpoint recovery verified=%d\n", strstr(dump, ok) != nullptr);
}
int main() {
    for (int rate : {44100, 96000}) {
        replayMissing(false, false, rate);
        replayMissing(false, true, rate);
        replayMissing(true, false, rate);
    }
    verifyForcedRecoveryNeedsReadings();
    verifyWindowDoesNotBridgeUnobservedIdle();
    verifyWithoutFeedbackEndpoint();
    intentionalSilence();
    slowValidAndRearm();
    printf("B635 feedback health %s failures=%d\n", g_failures ? "FAIL" : "PASS", g_failures);
    return g_failures ? 1 : 0;
}
