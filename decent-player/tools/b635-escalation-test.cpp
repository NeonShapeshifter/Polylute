#define main original_gate_main
#include "natural-join-test.cpp"
#undef main

extern "C" jlong Java_com_decent_usbaudio_UsbAudioStream_nativeRecoveryControl(JNIEnv *, jobject, jlong, jlong, jint, jint, jlong);

static void firmware(int rate, int kind, int cure, int durationMs) {
    Writer w(rate, true);
    usbSetDacRecoveryEnabled(true); usbDacRecoveryForgetEpisodes();
    const Pcm pcm = makeNoise(2048, 24, 0x635500 + kind);
    const auto expected = toUsb32(pcm);
    size_t cursor = 0, initialSilence = 0;
    bool first = true, recovered = false;
    int steps[4] = {}, attempts = 0;
    int64_t firstStep = -1;
    const int64_t start = nowUs(), faultAt = start + 1000000;
    setFeedbackScript([&](int64_t at) {
        FeedbackAnswer a; a.fpmf = rate / 8000.0;
        if (!recovered && at >= faultAt) {
            if (kind == 3) a.length = 0;
            else a.fpmf *= kind == 1 ? 1.0499 : 1.0083;
        }
        return a;
    });
    auto checkBytes = [&] {
        if (first && w.wroteAny) { initialSilence = (size_t)w.startSilenceFrames * 8; first = false; }
        bool exact = true;
        for (uint8_t byte : g_usb.capture) {
            if (initialSilence) { exact = exact && byte == 0; initialSilence--; }
            else { exact = exact && byte == expected[cursor % expected.size()]; cursor++; }
        }
        CHECK(exact, "recovery changed music bytes or inserted silence (rate=%d kind=%d cure=%d)", rate, kind, cure);
        g_usb.capture.clear(); g_usb.submitted.clear(); g_usb.packetFrames.clear();
    };
    while (nowUs() - start < durationMs * 1000LL) {
        w.write(pcm, 0, 2048); checkBytes();
        int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
        if (state[5] >= 1 && state[5] <= 3) {
            const int step = (int)state[5]; steps[step]++; attempts++;
            if (firstStep < 0) firstStep = (nowUs() - faultAt) / 1000;
            int ignored = 0;
            const int64_t before = nowUs(), gaps = g_usb.gapUs;
            runOneStep(w, pcm, ignored, step, 2048, [&] { if (step == cure) recovered = true; });
            checkBytes();
            w.write(pcm, 0, 2048); checkBytes();
            printf("ESC rate=%d kind=%d cure=%d attempt=%d step=%d fromFaultMs=%lld operationMs=%lld busGapMs=%.3f\n",
                rate, kind, cure, attempts, step, (long long)((before - faultAt) / 1000),
                (long long)((nowUs() - before) / 1000), (g_usb.gapUs - gaps) / 1000.0);
        }
    }
    w.finish(); checkBytes();
    CHECK(steps[1] == 1, "step 1 count");
    if (cure < 4) CHECK(attempts == cure, "wrong curing step %d attempts=%d", cure, attempts);
    else CHECK(attempts >= 6 && attempts <= 8 && steps[2] == 1, "unbounded/permanent give-up retries attempts=%d", attempts);
    CHECK(w.ctx->framesWritten * 8 == (int64_t)cursor, "logical frame counter jumped on reopen");
    CHECK(w.stat(5) == (steps[3] ? 0 : w.startSilenceFrames), "recovery silence was injected");
    printf("ESC summary rate=%d kind=%d cure=%d firstStepMs=%lld attempts=%d sourceBytes=%zu EXACT controlBytes=%zu\n",
        rate, kind, cure, (long long)firstStep, attempts, cursor, sizeof(UsbRecoveryControl));
    setFeedbackScript(nullptr);
}
int main() {
    for (int rate : {44100, 96000}) for (int kind : {1, 2, 3}) for (int cure : {1, 2, 3})
        firmware(rate, kind, cure, 26000);
    firmware(96000, 3, 4, 700000);
    {
        Writer w(96000, true); usbDacRecoveryForgetEpisodes();
        const Pcm pcm = makeNoise(2048, 24, 0xdead635);
        for (int n = 0; n < 20; ++n) w.write(pcm, 0, 2048);
        CHECK(usbDacRecoveryBegin(w.ctx), "pre-stop drain");
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(nullptr, nullptr, w.usb);
        CHECK(usbDacRecoveryBegin(w.ctx), "stopped transport barred its own full reopen");
        const int64_t frames = w.ctx->framesWritten;
        int ignored = 0; runOneStep(w, pcm, ignored, 3, 2048);
        w.write(pcm, 0, 2048);
        w.finish(); // include the final partial URB in the actual submitted-frame counter
        CHECK(w.ctx->running.load() && w.ctx->framesWritten == frames + 2048, "stopped stream did not resume with continuous counter");
        printf("ESC stopped native transport full reopen PASS\n");
    }
    {
        Writer w(96000, true); usbDacRecoveryForgetEpisodes();
        usbNoteDacRecovery(w.ctx, 1, 1, 123); // operation failed, without a rail
        int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
        CHECK(state[5] == 2, "failed operation did not escalate");
        char report[32768]; usbSecondSummaries(128, report, sizeof(report));
        CHECK(strstr(report, "dac-recovery step 1 operation-failed") != nullptr, "failed operation falsely labelled as rail");
        printf("ESC failed operation distinct from a detector rail PASS\n");
    }
    {
        simclock::VirtualTime virtualTime;
        UsbRecoveryControl c = {};
        usbDacRecoveryPolicyReset(&c.policy); usbDacRecoveryForgetEpisodes();
        int step = usbOfflineRecoveryStep(&c);
        CHECK(step == 1, "offline first step");
        for (int attempt = 1; attempt <= 6; ++attempt) {
            CHECK(step == (attempt < 3 ? attempt : 3), "offline ladder");
            CHECK(usbOfflineRecoveryStep(&c) == 0, "offline concurrent step");
            Java_com_decent_usbaudio_UsbAudioStream_nativeRecoveryControl(nullptr, nullptr, 0,
                reinterpret_cast<jlong>(&c), 4, step, 123);
            if (step == 3) {
                const int64_t delay = c.policy.nextRetryMs - nowUs() / 1000;
                CHECK(delay == (attempt == 3 ? 30000 : attempt == 4 ? 90000 : 300000), "offline backoff");
                CHECK(usbOfflineRecoveryStep(&c) == 0, "offline tight retry");
                sleepMs(delay);
            }
            step = usbOfflineRecoveryStep(&c);
        }
        printf("ESC unavailable transport failures retry without false verification PASS\n");
    }
#ifdef POLYLUTE_DEBUG_FAULTS
    for (int kind : {1, 2, 3}) {
        Writer w(96000, true); usbDacRecoveryForgetEpisodes();
        const Pcm pcm = makeNoise(2048, 24, 0xdeb6);
        for (int n = 0; n < 20; ++n) w.write(pcm, 0, 2048);
        const double before = w.ctx->calibratedFpmf;
        usbRequestDacRecovery(-100 - kind);
        int64_t state[USB_DAC_STATE_COUNT]; usbDacLockState(w.ctx, state);
        CHECK(state[0] && state[5] == 1, "debug fault kind=%d not detected", kind);
        CHECK(w.ctx->calibratedFpmf == before, "debug injection modified feedback follower");
        int next = 0; runOneStep(w, pcm, next, 1, 2048);
        for (int n = 0; n < 250; ++n) { w.write(pcm, 0, 2048); usbDacLockState(w.ctx, state); }
        CHECK(!state[0], "debug kind did not clear on real recovery");
        printf("ESC debug kind=%d detected recovered follower=UNCHANGED\n", kind);
    }
#endif
    printf("B635 escalation %s failures=%d\n",g_failures?"FAIL":"PASS",g_failures);
    return g_failures?1:0;
}
