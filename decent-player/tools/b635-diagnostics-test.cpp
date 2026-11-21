#define main original_gate_main
#include "natural-join-test.cpp"
#undef main
extern "C" JNIEXPORT jstring JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeGetSecondSummaries(JNIEnv *, jclass, jlong);
static std::string captured;
static jstring capture(JNIEnv *, const char *s) { captured = s; return reinterpret_cast<jstring>(1); }
static std::string jniDump() {
    JNINativeInterface table = {};
    table.NewStringUTF = capture;
    JNIEnv env = {}; env.functions = &table;
    Java_com_decent_usbaudio_UsbAudioStream_nativeGetSecondSummaries(&env, nullptr, 128000);
    return captured;
}
static void rapidStarts() {
    Writer w(44100, true);
    const int64_t from = nowUs();
    const int64_t previous = w.ctx->streamSeq;
    releaseStream(w.usb);
    w.usb = 0; w.ctx = nullptr;
    g_usb.reset(96000, 8);
    w.usb = createStream(96000, 32, 44100);
    w.ctx = reinterpret_cast<UsbAudioContext *>(w.usb);
    CHECK(w.ctx != nullptr, "new stream missing");
    if (!w.ctx) return;
    const std::string dump = jniDump();
    char oldTag[100], newTag[100];
    snprintf(oldTag, sizeof(oldTag), "stream=%lld rate=44100 start ", (long long)previous);
    snprintf(newTag, sizeof(newTag), "stream=%lld rate=96000 start ", (long long)w.ctx->streamSeq);
    CHECK(nowUs() - from < 100000, "rapid fixture exceeded throttle window");
    CHECK(dump.find(oldTag) != std::string::npos && dump.find(newTag) != std::string::npos, "start throttle hid a different generation");
    printf("B635 rapid stream starts deltaMs=%lld bothRetained=%d\n", (long long)((nowUs() - from) / 1000), dump.find(newTag) != std::string::npos);
}
int main() {
    rapidStarts();
    int64_t oldSeq;
    {
        Writer old(44100, true);
        oldSeq = old.ctx->streamSeq;
        const Pcm pcm = makeNoise(2048, 16, 0x635);
        for (int n = 0; n < 30; n++) old.write(pcm, 0, 2048);
    }
    Writer w(96000, true, 44100);
    std::string dump = jniDump();
    const size_t start = dump.rfind(" start initial-reading");
    CHECK(start != std::string::npos, "no start");
    const std::string event = start == std::string::npos ? "" : dump.substr(start, dump.find('\n', start) - start);
    printf("B635 initial calibration=%.9f event=%s\n", w.ctx->calibratedFpmf, event.c_str());
    CHECK(event.find("raw=0x000c0000 length=4 status=0/0") != std::string::npos, "initial raw/length/status lost");
    CHECK(event.find("sentBefore=5.51250 sentAfter=12.00000") != std::string::npos, "stale start sending");
    char oldLabel[100], newLabel[100];
    snprintf(oldLabel, sizeof(oldLabel), "stream=%lld rate=44100", (long long)oldSeq);
    snprintf(newLabel, sizeof(newLabel), "stream=%lld rate=96000", (long long)w.ctx->streamSeq);
    CHECK(dump.find(std::string(oldLabel) + "\n") != std::string::npos, "old seconds not labelled");
    const Pcm pcm = makeNoise(2048, 24, 0x128);
    const int64_t from = nowUs();
    while (nowUs() - from < 130000000) {
        w.write(pcm, 0, 2048);
        // Audit's frequent gesture case: 10 per second, over 128 seconds.
        if ((nowUs() / 100000) != ((nowUs() - 22000) / 100000)) w.markIdle();
    }
    w.markIdle();
    dump = jniDump();
    size_t seconds = 0, events = 0;
    for (size_t at = 0; (at = dump.find("\nt-", at)) != std::string::npos; at++) seconds++;
    for (size_t at = 0; (at = dump.find("\nevent ", at)) != std::string::npos; at++) events++;
    printf("B635 JNI128 bytes=%zu seconds=%zu events=%zu\n", dump.size(), seconds, events);
    CHECK(seconds == 128, "128-second JNI dump incomplete");
    CHECK(events > 800, "128 seconds of 10Hz gestures lost");
    CHECK(dump.find("TRUNCATED") == std::string::npos, "JNI truncated");
    CHECK(dump.find(std::string(newLabel) + "\n") != std::string::npos, "new seconds not labelled");
    CHECK(dump.find("event ") < dump.find("\nt-"), "seconds displaced events");
    printf("B635 diagnostics %s failures=%d\n", g_failures ? "FAIL" : "PASS", g_failures);
    return g_failures ? 1 : 0;
}
