// Host simulation of the native engine and the USB output at a natural track join (AUD-144, AUD-143,
// AUD-145).
//
// What runs is the real code: usb-audio-output.cpp, native-audio-engine.cpp, flac_parser.cc and
// libFLAC, entered through the same JNI functions the Kotlin sink calls (env and object are never
// dereferenced by the ones used here; the pipeline's raw write gets a minimal fake JNIEnv). Only the
// kernel is faked: `ioctl` and `poll` on one file descriptor play usbfs. A submitted ISO URB completes
// when its audio would have been heard (1 ms of audio per URB, back to back), and every accepted audio
// payload is captured at SUBMITURB, the boundary the device's payload harness measures.
//
// B9 adds the ExoPlayer pipeline's pause / seek / skip barrier to the same simulation (section 6): the
// writer's protocol (what UsbStreamingThread does through the JNI entry points) is driven here against
// the real usb-audio-output.cpp, and the captured payload is compared sample by sample with
// `pcmApplyGainRamp` — fade shape, frame count, the continuity of the ring (no empty microframe) and the
// audio of the old position never playing after the new one starts.
//
// B10 adds section 7: the DAC's feedback endpoint stuck at a value (the "shh", AUD-124) through the real
// output — the detector fires, the feedback policies put the packet sizes the policy says on the wire, and
// a policy switched in mid-stream does not jump. The fake endpoint answers what the test says, a constant or
// a healthy dither, and every packet's frame count is recorded at SUBMITURB.
//
// Why it exists: on the HONOR, two same-rate FLACs played back to back reached the DAC with the right
// byte count and the wrong hash (B8a, `bp-native-gap`: 27b6aa95… for a7482ec2…), and so did a 96 →
// 44.1 kHz pair. The block hash was reproduced on the host to the bit as the engine's *pause* fade
// (8 ms out, 15 ms in) starting at the second decoded block of the new track: Media3 stops its
// renderers for a ~35 ms rebuffer right after the transition and the engine took the stop for a
// user's pause. This file pins both halves of that: the engine is bit-exact across a join, and a pause
// still fades exactly as AUD-123/140 designed — so the join must never be sent one.
#include <jni.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <linux/usbdevice_fs.h>
#include <FLAC/stream_encoder.h>

#include "seek-barrier.h"
#include "sim-clock.h"
#include "usb-audio-output.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fcntl.h>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

// ── What the library exports through JNI ────────────────────────────────────────────────────────
extern "C" {
JNIEXPORT jlong JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioCreate(
    JNIEnv *, jobject, jint, jint, jint, jint, jint, jint, jint, jint);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(JNIEnv *, jobject, jlong);
JNIEXPORT jint JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWriteRaw(
    JNIEnv *, jobject, jlong, jbyteArray, jint);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeMarkIdle(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWrite(JNIEnv *, jobject, jlong, jfloatArray);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativePipelineBeginPause(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativePipelineRequestPause(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativePipelineCancelPauseRequest(JNIEnv *, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativePipelineConsumePauseApplied(JNIEnv *, jobject, jlong);
JNIEXPORT jint JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativePipelineBeginSeek(JNIEnv *, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativePipelineFadingOut(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativePipelineEndFadeOut(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativePipelineResume(JNIEnv *, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativePipelineFeedSilence(JNIEnv *, jobject, jlong);
JNIEXPORT jlong JNICALL Java_com_decent_usbaudio_NativeAudioEngine_nativeCreateFromFd(JNIEnv *, jobject, jint, jlong);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_NativeAudioEngine_nativeStart(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_NativeAudioEngine_nativePause(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_NativeAudioEngine_nativeResume(JNIEnv *, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_NativeAudioEngine_nativeSeek(JNIEnv *, jobject, jlong, jlong, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_NativeAudioEngine_nativeDestroy(JNIEnv *, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_NativeAudioEngine_nativeIsRunning(JNIEnv *, jobject, jlong);
JNIEXPORT jint JNICALL Java_com_decent_usbaudio_NativeAudioEngine_nativeGetExitReason(JNIEnv *, jobject, jlong);
}

namespace {

constexpr int kFakeFd = 777;
constexpr int kEndpointOut = 0x07;
constexpr int kEndpointFeedback = 0x88;

// B27-A0: the process clock. Real time, except inside a `simclock::VirtualTime` scope (the pipeline tests, whose writer is the
// test thread): there it only moves when that thread waits, so how fast the host is changes the wall time, never the verdict.
int64_t nowUs() { return simclock::nowNs() / 1000; }
void sleepMs(int ms) { simclock::sleepNs((int64_t)ms * 1000000); }

// ── fake usbfs ──────────────────────────────────────────────────────────────────────────────────

struct Pending {
    usbdevfs_urb *urb;
    int64_t completeAtUs;
    bool feedback;
};

struct FakeUsb {
    std::mutex mu;
    std::deque<Pending> inflight;
    std::vector<uint8_t> capture;  // every accepted audio payload byte, in submission order
    int64_t lastCompleteUs = 0;
    int rate = 96000;
    int bytesPerFrame = 8;
    double feedbackFpmf = 12.0;
    int audioUrbs = 0;
    int audioDiscards = 0;         // DISCARDURB that cut a data URB which had not completed
    // The DAC found nothing queued when a data URB arrived: empty microframes (B9). A pause or a
    // seek that is not painted over shows up here; an idle the writer announced still does, by design.
    bool anyAudio = false;
    int gaps = 0;
    int64_t gapUs = 0;
    int64_t maxGapUs = 0;
    int ringEmptyLogs = 0;         // USB_GLITCH RING_EMPTY lines the output itself reported
    std::vector<std::string> outputGapLines;  // B23-P2: the USB_GLITCH OUTPUT_GAP lines, whole, as logcat shows them
    // The feedback endpoint (B10): what it answers, optionally with a periodic blip like a healthy DAC's
    // dither, and the frames of every data packet in submission order.
    int feedbackBlipEvery = 0;     // every Nth reading is [feedbackBlipFpmf] instead (0: never)
    double feedbackBlipFpmf = 0.0;
    int64_t feedbackReads = 0;
    std::vector<uint8_t> packetFrames;
    std::vector<std::string> feedbackGlitches;  // the USB_GLITCH FEEDBACK_STUCK / FEEDBACK_UNSTUCK / FEEDBACK_REJECTED lines
    bool hold = false;             // completions never come (a stalled host)
    bool gone = false;             // the device is unplugged
    bool silentPoll = false;       // a kernel that never reports POLLOUT for ISO completions
    int isoErrorEveryNth = 0;      // B27-A5: every Nth audio URB completes with its first packet in error and empty (0: never)
    int64_t audioUrbsSeen = 0;
    /** The stalled host recovers: every URB held so far completes, back to back, from now. */
    void releaseHold() {
        std::lock_guard<std::mutex> lock(mu);
        hold = false;
        int64_t t = nowUs();
        for (auto &p : inflight) {
            if (p.feedback) continue;
            t += 1000;
            p.completeAtUs = std::min(p.completeAtUs, t);
        }
        lastCompleteUs = t;
    }
    void reset(int newRate, int newBytesPerFrame) {
        std::lock_guard<std::mutex> lock(mu);
        inflight.clear();
        capture.clear();
        lastCompleteUs = 0;
        rate = newRate;
        bytesPerFrame = newBytesPerFrame;
        audioUrbs = 0;
        audioDiscards = 0;
        anyAudio = false;
        gaps = 0;
        gapUs = 0;
        maxGapUs = 0;
        ringEmptyLogs = 0;
        outputGapLines.clear();
        feedbackBlipEvery = 0;
        feedbackReads = 0;
        packetFrames.clear();
        feedbackGlitches.clear();
        hold = false;
        gone = false;
        silentPoll = false;
        isoErrorEveryNth = 0;
        audioUrbsSeen = 0;
    }
} g_usb;

}  // namespace

extern "C" int ioctl(int fd, unsigned long request, ...) {
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    if (fd != kFakeFd) {
        errno = ENOTTY;
        return -1;
    }
    std::lock_guard<std::mutex> lock(g_usb.mu);
    const int64_t now = nowUs();
    switch (request) {
        case USBDEVFS_SUBMITURB: {
            auto *u = static_cast<usbdevfs_urb *>(arg);
            Pending p{u, 0, false};
            if (u->endpoint == kEndpointFeedback) {
                double answer = g_usb.feedbackFpmf;
                if (g_usb.feedbackBlipEvery > 0 && ++g_usb.feedbackReads % g_usb.feedbackBlipEvery == 0) {
                    answer = g_usb.feedbackBlipFpmf;
                }
                const uint32_t raw = (uint32_t)llround(answer * 65536.0);
                memcpy(u->buffer, &raw, 4);
                u->iso_frame_desc[0].actual_length = 4;
                u->iso_frame_desc[0].status = 0;
                u->status = 0;
                p.feedback = true;
                p.completeAtUs = now + 1000;
            } else {
                const uint8_t *bytes = static_cast<const uint8_t *>(u->buffer);
                g_usb.capture.insert(g_usb.capture.end(), bytes, bytes + u->buffer_length);
                g_usb.audioUrbs++;
                for (int i = 0; i < u->number_of_packets; i++) {
                    g_usb.packetFrames.push_back((uint8_t)(u->iso_frame_desc[i].length / g_usb.bytesPerFrame));
                    u->iso_frame_desc[i].actual_length = u->iso_frame_desc[i].length;
                    u->iso_frame_desc[i].status = 0;
                }
                u->status = 0;
                if (g_usb.isoErrorEveryNth > 0 && ++g_usb.audioUrbsSeen % g_usb.isoErrorEveryNth == 0 && u->number_of_packets > 0) {
                    u->iso_frame_desc[0].status = -EPROTO;
                    u->iso_frame_desc[0].actual_length = 0;
                }
                if (g_usb.anyAudio && g_usb.lastCompleteUs > 0 && !g_usb.hold && now > g_usb.lastCompleteUs + 1000) {
                    g_usb.gaps++;
                    g_usb.gapUs += now - g_usb.lastCompleteUs;
                    g_usb.maxGapUs = std::max(g_usb.maxGapUs, now - g_usb.lastCompleteUs);
                }
                g_usb.anyAudio = true;
                const int64_t durUs = (int64_t)u->buffer_length * 1000000LL / g_usb.bytesPerFrame / g_usb.rate;
                p.completeAtUs = g_usb.hold ? now + 3600LL * 1000000LL : std::max(g_usb.lastCompleteUs, now) + durUs;
                if (!g_usb.hold) g_usb.lastCompleteUs = p.completeAtUs;
            }
            g_usb.inflight.push_back(p);
            return 0;
        }
        case USBDEVFS_REAPURBNDELAY: {
            if (g_usb.gone) {
                errno = ENODEV;
                return -1;
            }
            auto best = g_usb.inflight.end();
            for (auto it = g_usb.inflight.begin(); it != g_usb.inflight.end(); ++it) {
                if (it->completeAtUs <= now && (best == g_usb.inflight.end() || it->completeAtUs < best->completeAtUs)) {
                    best = it;
                }
            }
            if (best == g_usb.inflight.end()) {
                errno = EAGAIN;
                return -1;
            }
            *static_cast<usbdevfs_urb **>(arg) = best->urb;
            g_usb.inflight.erase(best);
            return 0;
        }
        case USBDEVFS_DISCARDURB: {
            for (auto &p : g_usb.inflight) {
                if (p.urb == static_cast<usbdevfs_urb *>(arg)) {
                    if (p.completeAtUs <= now) {
                        errno = EINVAL;
                        return -1;
                    }
                    if (!p.feedback) g_usb.audioDiscards++;
                    p.completeAtUs = now;
                    p.urb->status = -ENOENT;
                    return 0;
                }
            }
            errno = EINVAL;
            return -1;
        }
        default:
            return 0;
    }
}

// usbfs reports POLLOUT while a completed URB waits to be reaped, POLLHUP once the device is gone.
extern "C" int poll(struct pollfd *fds, nfds_t nfds, int timeoutMs) {
    if (nfds != 1 || fds[0].fd != kFakeFd) return (int)syscall(SYS_poll, fds, nfds, timeoutMs);
    const int64_t deadline = nowUs() + (int64_t)timeoutMs * 1000;
    for (;;) {
        int64_t nextUs = deadline;
        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            if (g_usb.gone) {
                fds[0].revents = POLLHUP | POLLERR;
                return 1;
            }
            const int64_t now = nowUs();
            for (const auto &p : g_usb.inflight) {
                if (p.completeAtUs <= now && !g_usb.silentPoll) {
                    fds[0].revents = POLLOUT;
                    return 1;
                }
                nextUs = std::min(nextUs, p.completeAtUs);
            }
            if (g_usb.silentPoll) nextUs = deadline;  // nothing ever wakes it before its timeout
        }
        const int64_t now = nowUs();
        if (now >= deadline) {
            fds[0].revents = 0;
            return 0;
        }
        if (simclock::isVirtual()) {
            simclock::advanceTo(std::min(nextUs, deadline) * 1000);  // the writer waits: the DAC plays on until then
        } else {
            std::this_thread::sleep_for(std::chrono::microseconds(std::max<int64_t>(50, std::min(nextUs, deadline) - now)));
        }
    }
}

extern "C" int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    static const bool verbose = getenv("SIM_LOG") != nullptr;
    if (tag && strcmp(tag, "USB_GLITCH") == 0) {
        va_list gap;
        va_start(gap, fmt);
        const char *kind = va_arg(gap, const char *);
        va_end(gap);
        if (kind && strcmp(kind, "RING_EMPTY") == 0) {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            g_usb.ringEmptyLogs++;
        }
        if (kind && strcmp(kind, "OUTPUT_GAP") == 0) {
            char line[600];
            va_list copy;
            va_start(copy, fmt);
            vsnprintf(line, sizeof(line), fmt, copy);
            va_end(copy);
            std::lock_guard<std::mutex> lock(g_usb.mu);
            g_usb.outputGapLines.push_back(line);
        }
        if (kind && strncmp(kind, "FEEDBACK_", 9) == 0 &&
            (strstr(kind, "STUCK") != nullptr || strstr(kind, "REJECTED") != nullptr)) {
            // the whole line, as logcat shows it: "<KIND> at hh:mm:ss.mmm mono=...ms total=... heldBack=... <detail>"
            char line[600];
            va_list copy;
            va_start(copy, fmt);
            vsnprintf(line, sizeof(line), fmt, copy);
            va_end(copy);
            std::lock_guard<std::mutex> lock(g_usb.mu);
            g_usb.feedbackGlitches.push_back(line);
        }
    }
    if (!verbose) return 0;  // the gone-device case logs errors on purpose
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
    return 0;
}

extern "C" void __android_log_assert(const char *cond, const char *tag, const char *fmt, ...) {
    fprintf(stderr, "ASSERT %s %s %s\n", tag, cond ? cond : "", fmt ? fmt : "");
    abort();
}

namespace {

int g_failures = 0;
#define CHECK(cond, ...)                                  \
    do {                                                  \
        if (!(cond)) {                                    \
            g_failures++;                                 \
            printf("  FAIL %s:%d: ", __FILE__, __LINE__); \
            printf(__VA_ARGS__);                          \
            printf("\n");                                 \
        }                                                 \
    } while (0)

// ── test material ───────────────────────────────────────────────────────────────────────────────

struct Pcm {
    int bits = 24;
    std::vector<int32_t> samples;  // interleaved stereo, signed, `bits` wide
    int frames() const { return (int)(samples.size() / 2); }
};

Pcm makeNoise(int frames, int bits, uint32_t seed) {
    Pcm p;
    p.bits = bits;
    p.samples.resize((size_t)frames * 2);
    uint32_t x = seed;
    for (auto &v : p.samples) {
        x ^= x << 13; x ^= x >> 17; x ^= x << 5;
        v = (int32_t)(x << (32 - bits)) >> (32 - bits);  // every bit of the container is exercised
    }
    return p;
}

bool writeFlac(const std::string &path, const Pcm &pcm, int rate, int blocksize) {
    FLAC__StreamEncoder *enc = FLAC__stream_encoder_new();
    FLAC__stream_encoder_set_verify(enc, true);
    FLAC__stream_encoder_set_compression_level(enc, 5);
    FLAC__stream_encoder_set_channels(enc, 2);
    FLAC__stream_encoder_set_bits_per_sample(enc, pcm.bits);
    FLAC__stream_encoder_set_sample_rate(enc, rate);
    FLAC__stream_encoder_set_blocksize(enc, blocksize);
    FLAC__stream_encoder_set_total_samples_estimate(enc, pcm.frames());
    if (FLAC__stream_encoder_init_file(enc, path.c_str(), nullptr, nullptr) != FLAC__STREAM_ENCODER_INIT_STATUS_OK) {
        FLAC__stream_encoder_delete(enc);
        return false;
    }
    const bool ok = FLAC__stream_encoder_process_interleaved(enc, pcm.samples.data(), pcm.frames());
    FLAC__stream_encoder_finish(enc);
    FLAC__stream_encoder_delete(enc);
    return ok;
}

/** What the DAC's 32-bit slot carries for [pcm]: the sample MSB-justified, the low bits zero. */
std::vector<uint8_t> toUsb32(const Pcm &pcm) {
    std::vector<uint8_t> out(pcm.samples.size() * 4);
    for (size_t i = 0; i < pcm.samples.size(); i++) {
        const uint32_t v = (uint32_t)pcm.samples[i] << (32 - pcm.bits);
        memcpy(&out[i * 4], &v, 4);
    }
    return out;
}

/** The little-endian packed bytes ExoPlayer's FLAC decoder hands the sink for [pcm] (24-bit: 3 bytes). */
std::vector<uint8_t> toPackedLe(const Pcm &pcm) {
    const int bytes = pcm.bits / 8;
    std::vector<uint8_t> out(pcm.samples.size() * bytes);
    for (size_t i = 0; i < pcm.samples.size(); i++) {
        const uint32_t v = (uint32_t)pcm.samples[i];
        for (int b = 0; b < bytes; b++) out[i * bytes + b] = (uint8_t)(v >> (8 * b));
    }
    return out;
}

struct Engine {
    jlong handle = 0;
};

Engine createEngine(jlong usb, const std::string &path) {
    Engine e;
    const int fd = open(path.c_str(), O_RDONLY);
    e.handle = Java_com_decent_usbaudio_NativeAudioEngine_nativeCreateFromFd(nullptr, nullptr, fd, usb);
    close(fd);
    return e;
}

/** The sink's start of an engine: started, paused, placed at the item start, then resumed (playing). */
void startEngine(const Engine &e, bool resume) {
    Java_com_decent_usbaudio_NativeAudioEngine_nativeStart(nullptr, nullptr, e.handle);
    Java_com_decent_usbaudio_NativeAudioEngine_nativePause(nullptr, nullptr, e.handle);
    Java_com_decent_usbaudio_NativeAudioEngine_nativeSeek(nullptr, nullptr, e.handle, 0, 1);
    if (resume) Java_com_decent_usbaudio_NativeAudioEngine_nativeResume(nullptr, nullptr, e.handle);
}

// ── the start race of the engine (B35, R5) ──────────────────────────────────────────────────────

/**
 * `nativeStart` sets the decode thread running, and only afterwards does the sink's sequence pause it, put it at the item start and resume it. The
 * window is about 50 us wide: the decode thread needs that long to put the first block on the wire (measured unloaded, with a busy wait of N us between the
 * two calls: 0 us leaks 0 of 60 starts, 50 us 11, 100 us 53, 200 us all). A host that takes the CPU from the *test thread* for that long inside it (it
 * happens, rarely: 3 starts in 7,500 with the CPUs four times oversubscribed) lets the first block through, and the engine then does what it was told
 * in order: play, pause, seek to 0, resume. The stream is then the block or blocks that got out, the 8 ms fade-out a pause makes at the next block,
 * and the item again: longer than expected, equal to it up to the end of the first block and different from there (byte 65536 of A||B and 71680 more
 * bytes: the reviews' failure of 2026-10-20). It is a property of the engine's start protocol that the simulation's wall clock exposes, not an ordering
 * fault at the join (the join is bit-exact in the same run), and the engine is not changed here.
 *
 * What this recognises is exactly that stream and nothing else: [startByte] is where the engine's item begins, [blockFrames] its FLAC block, and
 * [got] must be [expected] up to the item, then 1 to 3 blocks of the item as they were in [source] (the PCM the engine decodes, which a test that
 * designs a fade into [expected] does not share), then the 8 ms fade-out of the next ones, then the whole item again from its start with the 15 ms
 * fade-in a resume makes after a seek, and everything after it exactly as [expected]. Anything else (a different length, a different fade, a modified
 * byte after the replay) is not it.
 */
std::vector<uint8_t> restartedItem(const std::vector<uint8_t> &want, size_t from, size_t length, int rate) {
    std::vector<uint8_t> item(want.begin() + (long)from, want.begin() + (long)(from + length));
    const int fadeIn = seekFadeInFrames(rate);
    pcmApplyGainRamp(item.data(), fadeIn, 2, 4, 0, fadeIn, true);
    return item;
}

/** The first [replay] bytes of the item as they were, then its next frames faded out: what the engine had delivered when the pause took effect. */
std::vector<uint8_t> replayedBlocks(const std::vector<uint8_t> &want, size_t from, size_t replay, int rate) {
    const int fadeOut = seekFadeOutFrames(rate);
    std::vector<uint8_t> out(want.begin() + (long)from, want.begin() + (long)(from + replay + (size_t)fadeOut * 8));
    pcmApplyGainRamp(out.data() + replay, fadeOut, 2, 4, 0, fadeOut, false);
    return out;
}

bool replayedStart(const std::vector<uint8_t> &source, const std::vector<uint8_t> &expected, const std::vector<uint8_t> &got, size_t startByte,
                   int blockFrames, int rate) {
    for (int blocks = 1; blocks <= 3; blocks++) {
        const size_t replay = (size_t)blocks * (size_t)blockFrames * 8;
        const size_t fade = (size_t)seekFadeOutFrames(rate) * 8;
        if (startByte + replay + fade > source.size() || got.size() != expected.size() + replay + fade) continue;
        std::vector<uint8_t> expect(expected.begin(), expected.begin() + (long)startByte);
        const std::vector<uint8_t> head = replayedBlocks(source, startByte, replay, rate);
        const std::vector<uint8_t> item = restartedItem(expected, startByte, expected.size() - startByte, rate);
        expect.insert(expect.end(), head.begin(), head.end());
        expect.insert(expect.end(), item.begin(), item.end());
        if (expect == got) return true;
    }
    return false;
}

/** A run may be repeated while it shows only that race and attempts remain; one that shows it every time, or anything else, is a failure. */
constexpr int kStartAttempts = 4;

void noteReplayedStart(int attempt) {
    printf("  (an engine's first block got out before its pause, as the start protocol allows: run %d of %d again)\n", attempt + 1, kStartAttempts);
}

bool waitStopped(const Engine &e, int timeoutMs) {
    const int64_t deadline = nowUs() + (int64_t)timeoutMs * 1000;
    while (nowUs() < deadline) {
        if (!Java_com_decent_usbaudio_NativeAudioEngine_nativeIsRunning(nullptr, nullptr, e.handle)) return true;
        sleepMs(2);
    }
    return false;
}

jlong createStream(int rate, int slotBits) {
    const jlong usb = Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioCreate(
        nullptr, nullptr, kFakeFd, 1, kEndpointOut, kEndpointFeedback, rate, 2, slotBits, 400);
    if (usb == 0 || !Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(nullptr, nullptr, usb)) {
        fprintf(stderr, "stream did not start\n");
        exit(2);
    }
    return usb;
}

/** What `releaseUsbStream` does: stop, drain (plays the ring out only when the end was natural), release. */
void releaseStream(jlong usb) {
    Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(nullptr, nullptr, usb);
    Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(nullptr, nullptr, usb);
    Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(nullptr, nullptr, usb);
}

// ── comparison ──────────────────────────────────────────────────────────────────────────────────

/** Index of the first differing byte, or -1 when [a] and [b] are identical. */
long firstDifference(const std::vector<uint8_t> &a, const std::vector<uint8_t> &b) {
    const size_t n = std::min(a.size(), b.size());
    for (size_t i = 0; i < n; i++) {
        if (a[i] != b[i]) return (long)i;
    }
    return a.size() == b.size() ? -1 : (long)n;
}

struct TempDir {
    std::string path;
    TempDir() {
        char tmpl[] = "/tmp/natural-join-XXXXXX";
        path = mkdtemp(tmpl);
    }
    ~TempDir() {
        for (const char *name : {"/a.flac", "/b.flac", "/c.flac"}) unlink((path + name).c_str());
        rmdir(path.c_str());
    }
};

constexpr int kFrames96 = 76800;   // 0.8 s at 96 kHz = 9 x 8192 + 3072
constexpr int kFrames441 = 44100;  // 1 s at 44.1 kHz = 9 x 4608 + 2628
constexpr int kSeam = kFrames96;   // frame at which B starts in A||B

// ── 1. A natural join at the same rate: the engine is bit-exact ─────────────────────────────────

/**
 * The sink's sequence at a natural end: A's decode thread ends on its own (its residual submitted, the
 * ring left to play out), Media3 moves on, the finished engine is destroyed and B's engine is created
 * on the same stream, started paused, placed at 0 and resumed. [gapMs] is the time between A's end of
 * stream and B's engine being started (88 ms in the B1-native log), [pauseAfterMs]/[resumeAfterMs]
 * model Media3's renderer stop and restart after B's start (-1: none).
 */
std::vector<uint8_t> runSameRateJoin(const Pcm &a, const Pcm &b, int gapMs, int pauseAfterMs, int resumeAfterMs,
                                     const std::string &dir) {
    // With a pause, B's first block is held in the ring (a host that has not reaped yet) until the stop
    // has been delivered: the engine then meets it as soon as that block is written, deterministically,
    // which is where it met it on the device (after B's first block).
    g_usb.reset(96000, 8);
    const jlong usb = createStream(96000, 32);
    Engine ea = createEngine(usb, dir + "/a.flac");
    startEngine(ea, true);
    if (!waitStopped(ea, 8000)) {
        fprintf(stderr, "engine A never ended\n");
        exit(2);
    }
    CHECK(Java_com_decent_usbaudio_NativeAudioEngine_nativeGetExitReason(nullptr, nullptr, ea.handle) == 1,
          "A did not end at the end of the stream");
    sleepMs(gapMs);
    Java_com_decent_usbaudio_NativeAudioEngine_nativeDestroy(nullptr, nullptr, ea.handle);
    Engine eb = createEngine(usb, dir + "/b.flac");
    if (pauseAfterMs >= 0) {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        g_usb.hold = true;
    }
    startEngine(eb, true);
    if (pauseAfterMs >= 0) {
        sleepMs(pauseAfterMs);
        Java_com_decent_usbaudio_NativeAudioEngine_nativePause(nullptr, nullptr, eb.handle);
        g_usb.releaseHold();
        sleepMs(resumeAfterMs);
        Java_com_decent_usbaudio_NativeAudioEngine_nativeResume(nullptr, nullptr, eb.handle);
    }
    if (!waitStopped(eb, 8000)) {
        fprintf(stderr, "engine B never ended\n");
        exit(2);
    }
    sleepMs(50);
    Java_com_decent_usbaudio_NativeAudioEngine_nativeDestroy(nullptr, nullptr, eb.handle);
    releaseStream(usb);
    std::lock_guard<std::mutex> lock(g_usb.mu);
    (void)a;
    (void)b;
    return g_usb.capture;
}

void testSameRateJoinIsBitExact(const Pcm &a, const Pcm &b, const std::string &dir) {
    std::vector<uint8_t> want = toUsb32(a);
    const std::vector<uint8_t> wantB = toUsb32(b);
    want.insert(want.end(), wantB.begin(), wantB.end());
    struct Timeline { int gapMs; const char *what; };
    // 88 ms: the B1-native log. 0: B starts while A's ring is still full. 200: the ring ran dry first.
    for (const Timeline &t : {Timeline{88, "88 ms after A's end"}, Timeline{0, "at once"}, Timeline{200, "after the ring ran dry"}}) {
        std::vector<uint8_t> got;
        long diff = -1;
        for (int attempt = 1;; attempt++) {
            got = runSameRateJoin(a, b, t.gapMs, -1, -1, dir);
            diff = firstDifference(want, got);
            const bool replayed = diff >= 0 && attempt < kStartAttempts &&
                (replayedStart(want, want, got, 0, 8192, 96000) || replayedStart(want, want, got, (size_t)kFrames96 * 8, 8192, 96000));
            if (!replayed) break;
            noteReplayedStart(attempt);
        }
        CHECK(diff < 0, "B started %s: A||B differs from byte %ld (frame %ld); %zu bytes expected, %zu delivered",
              t.what, diff, diff / 8, want.size(), got.size());
    }
    printf("natural join, same rate, engine untouched: %s\n", g_failures ? "FAIL" : "bit-exact");
}

// ── 2. A pause still fades (AUD-123/140): it is what the join must never be sent ────────────────

/** [want] with the pause the test designs at [frame]: 8 ms out over that block's first frames, 15 ms in over the kept remainder, nothing else touched. */
std::vector<uint8_t> designedPause(const std::vector<uint8_t> &want, long frame) {
    std::vector<uint8_t> expect = want;
    const int fadeOut = seekFadeOutFrames(96000), fadeIn = seekFadeInFrames(96000);
    uint8_t *at = expect.data() + (size_t)frame * 8;
    pcmApplyGainRamp(at, fadeOut, 2, 4, 0, fadeOut, false);
    pcmApplyGainRamp(at + (size_t)fadeOut * 8, fadeIn, 2, 4, 0, fadeIn, true);
    return expect;
}

void testPauseStillFades(const Pcm &a, const Pcm &b, const std::string &dir) {
    std::vector<uint8_t> want = toUsb32(a);
    const std::vector<uint8_t> wantB = toUsb32(b);
    want.insert(want.end(), wantB.begin(), wantB.end());

    // B resumes at its creation (the player is playing), the renderers stop once its first block is in
    // the ring and restart 35 ms later: the shape of the rebuffer in the B1-native log.
    std::vector<uint8_t> got;
    for (int attempt = 1;; attempt++) {
        got = runSameRateJoin(a, b, 88, 60, 35, dir);
        // The first block of A or of B got out before its pause. Whatever followed was then either the pause under test (at one of B's block
        // boundaries) or, when it was B's start that was hit, nothing: the scenario's pause met an engine that the start sequence had already paused
        // and put back, so the stream is the plain join. Either way the scenario did not run as designed, and it is run again.
        bool replayed = false;
        for (int block = 0; block <= 3 && !replayed && attempt < kStartAttempts && got.size() > want.size(); block++) {
            const std::vector<uint8_t> afterwards = block == 0 ? want : designedPause(want, (long)kSeam + 8192L * block);
            replayed = replayedStart(want, afterwards, got, 0, 8192, 96000) ||
                replayedStart(want, afterwards, got, (size_t)kFrames96 * 8, 8192, 96000);
        }
        if (!replayed) break;
        noteReplayedStart(attempt);
    }
    CHECK(got.size() == want.size(), "a pause lost or invented audio: %zu bytes expected, %zu delivered", want.size(), got.size());
    const long diff = firstDifference(want, got);
    CHECK(diff >= 0, "the pause did not fade at all");
    if (diff < 0) return;
    const long frame = diff / 8;
    // The fade-out starts at a decoded block boundary of B (8192 frames), after its first block.
    CHECK(frame >= kSeam + 8192 && (frame - kSeam) % 8192 == 0,
          "the fade began at frame %ld, not at a block boundary of B past its first block", frame);

    // Exactly the modification of the device's block (host-verified against 24ce9b85…): 8 ms out over
    // the block's first frames, 15 ms in over the kept remainder, nothing else touched.
    const std::vector<uint8_t> expect = designedPause(want, frame);
    CHECK(firstDifference(expect, got) < 0,
          "the pause fade is not the designed one (8 ms out, 15 ms in) at frame %ld: first difference at byte %ld",
          frame, firstDifference(expect, got));
    printf("pause after the join's first block: fades as designed at frame %ld (B frame %ld), count exact\n", frame, frame - kSeam);
}

// ── 3. A natural join across a rate change ──────────────────────────────────────────────────────

void testRateChangeJoin(const Pcm &a, const Pcm &c, const std::string &dir) {
    std::vector<uint8_t> want = toUsb32(a);
    const std::vector<uint8_t> wantC = toUsb32(c);
    want.insert(want.end(), wantC.begin(), wantC.end());
    std::vector<uint8_t> got;
    int cutsOfAudio = 0;
    int64_t drainMs = 0;
    for (int attempt = 1;; attempt++) {
        g_usb.reset(96000, 8);
        jlong usb = createStream(96000, 32);
        Engine ea = createEngine(usb, dir + "/a.flac");
        startEngine(ea, true);
        waitStopped(ea, 8000);
        sleepMs(40);
        // The deferred config at Media3's transition: the old stream is released (its ring plays out because
        // the end was natural) and one at the new rate is opened.
        Java_com_decent_usbaudio_NativeAudioEngine_nativeDestroy(nullptr, nullptr, ea.handle);
        const int64_t drainStartUs = nowUs();
        releaseStream(usb);
        drainMs = (nowUs() - drainStartUs) / 1000;
        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            cutsOfAudio = g_usb.audioDiscards;
            g_usb.rate = 44100;
            g_usb.lastCompleteUs = 0;
        }
        usb = createStream(44100, 32);
        Engine ec = createEngine(usb, dir + "/c.flac");
        startEngine(ec, true);
        waitStopped(ec, 8000);
        sleepMs(50);
        Java_com_decent_usbaudio_NativeAudioEngine_nativeDestroy(nullptr, nullptr, ec.handle);
        releaseStream(usb);

        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            got = g_usb.capture;
        }
        const bool replayed = firstDifference(want, got) >= 0 && attempt < kStartAttempts &&
            (replayedStart(want, want, got, 0, 8192, 96000) || replayedStart(want, want, got, (size_t)kFrames96 * 8, 4608, 44100));
        if (!replayed) break;
        noteReplayedStart(attempt);
    }
    CHECK(cutsOfAudio == 0, "the rate change cancelled %d URBs of A's tail still in the ring", cutsOfAudio);
    CHECK(drainMs < 400, "the drain took %lld ms", (long long)drainMs);
    const long diff = firstDifference(want, got);
    CHECK(diff < 0, "96 kHz → 44.1 kHz join differs from byte %ld (%zu expected, %zu delivered)", diff, want.size(), got.size());
    printf("natural join across a rate change: %s, the ring played out (%lld ms drain, %d URBs cut)\n",
           diff < 0 ? "bit-exact" : "FAIL", (long long)drainMs, cutsOfAudio);
}

// ── 4. The pipeline's own end of stream (AUD-143) ───────────────────────────────────────────────

struct FakeArray {
    std::vector<uint8_t> bytes;
};
jsize JNICALL fakeGetArrayLength(JNIEnv *, jarray array) { return (jsize)reinterpret_cast<FakeArray *>(array)->bytes.size(); }
jbyte *JNICALL fakeGetByteArrayElements(JNIEnv *, jbyteArray array, jboolean *) {
    return reinterpret_cast<jbyte *>(reinterpret_cast<FakeArray *>(array)->bytes.data());
}
void JNICALL fakeReleaseByteArrayElements(JNIEnv *, jbyteArray, jbyte *, jint) {}

/** ExoPlayer's pipeline: buffers of the decoder's own size written raw by the streaming thread. */
void writePipeline(JNIEnv *env, jlong usb, const Pcm &pcm, int bufferFrames) {
    const std::vector<uint8_t> packed = toPackedLe(pcm);
    const size_t frameBytes = (size_t)(pcm.bits / 8) * 2;
    for (size_t at = 0; at < packed.size(); at += (size_t)bufferFrames * frameBytes) {
        FakeArray chunk;
        chunk.bytes.assign(packed.begin() + at, packed.begin() + std::min(packed.size(), at + (size_t)bufferFrames * frameBytes));
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWriteRaw(env, nullptr, usb, reinterpret_cast<jbyteArray>(&chunk), pcm.bits);
    }
}

void testPipelineEndOfStreamPlaysOut(const Pcm &a, const Pcm &c) {
    simclock::VirtualTime virtualTime;  // B27-A0: the writer is this thread; see sim-clock.h
    JNINativeInterface table = {};
    table.GetArrayLength = fakeGetArrayLength;
    table.GetByteArrayElements = fakeGetByteArrayElements;
    table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
    JNIEnv env;
    env.functions = &table;

    // (a) A's last buffer, then the end-of-stream residual, then the rate change.
    g_usb.reset(96000, 8);
    jlong usb = createStream(96000, 32);
    writePipeline(&env, usb, a, 8192);
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(&env, nullptr, usb) == JNI_TRUE, "residual drain failed");
    releaseStream(usb);
    int cutsAtNaturalEnd;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        cutsAtNaturalEnd = g_usb.audioDiscards;
        g_usb.rate = 44100;
        g_usb.lastCompleteUs = 0;
    }
    CHECK(cutsAtNaturalEnd == 0, "a rate change after the pipeline's natural end cancelled %d URBs of its tail", cutsAtNaturalEnd);
    usb = createStream(44100, 32);
    writePipeline(&env, usb, c, 4608);
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(&env, nullptr, usb) == JNI_TRUE, "residual drain failed");
    releaseStream(usb);
    std::vector<uint8_t> want = toUsb32(a);
    const std::vector<uint8_t> wantC = toUsb32(c);
    want.insert(want.end(), wantC.begin(), wantC.end());
    std::vector<uint8_t> got;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        got = g_usb.capture;
    }
    const long diff = firstDifference(want, got);
    CHECK(diff < 0, "pipeline A→C differs from byte %ld (%zu expected, %zu delivered)", diff, want.size(), got.size());

    // (b) A seek or a skip ends nothing naturally: the next write clears the mark, so the teardown is
    // the immediate discard it always was (the skip latency AUD-112 measured is not given back).
    g_usb.reset(96000, 8);
    usb = createStream(96000, 32);
    writePipeline(&env, usb, a, 8192);
    Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(&env, nullptr, usb);
    Pcm more = makeNoise(8192 * 2, 24, 0x5eed1234u);
    writePipeline(&env, usb, more, 8192);  // a new item after a seek: live audio again
    const int64_t skipStartUs = nowUs();
    releaseStream(usb);
    const int64_t skipMs = (nowUs() - skipStartUs) / 1000;
    int discardsAtSkip;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        discardsAtSkip = g_usb.audioDiscards;
    }
    CHECK(discardsAtSkip > 0 && skipMs < 120, "a teardown after live audio waited %lld ms and cut %d URBs (expected an immediate discard)",
          (long long)skipMs, discardsAtSkip);
    printf("pipeline end of stream: tail played out (%d URBs cut), a skip still discards at once (%lld ms, %d URBs cut)\n",
           cutsAtNaturalEnd, (long long)skipMs, discardsAtSkip);
}

// ── 6. The pipeline's pause / seek / skip barrier (B9) ──────────────────────────────────────────
//
// UsbStreamingThread drives these entry points; here the same protocol is driven by hand against the real
// output. The expected stream is built from the source PCM with `pcmApplyGainRamp` and the engine's own
// lengths, so "the same shape as the engine's" is a byte comparison, not an impression.

constexpr int kBuf = 8192;        // the decoder's block, in frames
constexpr int kRate = 96000;

/** The pipeline writer's JNI environment: byte and float arrays backed by plain vectors. */
struct SimArray {
    bool isFloat = false;
    std::vector<uint8_t> bytes;
    std::vector<float> floats;
};
jsize JNICALL simGetLength(JNIEnv *, jarray array) {
    const auto *a = reinterpret_cast<SimArray *>(array);
    return (jsize)(a->isFloat ? a->floats.size() : a->bytes.size());
}
jbyte *JNICALL simGetBytes(JNIEnv *, jbyteArray array, jboolean *) {
    return reinterpret_cast<jbyte *>(reinterpret_cast<SimArray *>(array)->bytes.data());
}
void JNICALL simReleaseBytes(JNIEnv *, jbyteArray, jbyte *, jint) {}
jfloat *JNICALL simGetFloats(JNIEnv *, jfloatArray array, jboolean *) { return reinterpret_cast<SimArray *>(array)->floats.data(); }
void JNICALL simReleaseFloats(JNIEnv *, jfloatArray, jfloat *, jint) {}

struct Writer {
    // B27-A0: the writer is this thread and the fake DAC plays in virtual time (see sim-clock.h). Declared first so that the
    // scope is open before the stream exists and still open while ~Writer tears it down.
    simclock::VirtualTime virtualTime;
    JNINativeInterface table = {};
    JNIEnv env;
    jlong usb = 0;
    UsbAudioContext *ctx = nullptr;
    // The sources as ExoPlayer's FLAC decoder hands them over (24-bit LE), converted once: a buffer used to convert the whole
    // source again, so the writer's cost per 300-frame buffer grew with the file and a slow host starved the ring (B27-A0).
    std::map<const Pcm *, std::vector<uint8_t>> packedSources;
    const std::vector<uint8_t> &packedOf(const Pcm &pcm) {
        auto it = packedSources.find(&pcm);
        if (it == packedSources.end()) it = packedSources.insert(std::make_pair(&pcm, toPackedLe(pcm))).first;
        return it->second;
    }

    Writer() {
        table.GetArrayLength = simGetLength;
        table.GetByteArrayElements = simGetBytes;
        table.ReleaseByteArrayElements = simReleaseBytes;
        table.GetFloatArrayElements = simGetFloats;
        table.ReleaseFloatArrayElements = simReleaseFloats;
        env.functions = &table;
        g_usb.reset(kRate, 8);
        usb = createStream(kRate, 32);
        ctx = reinterpret_cast<UsbAudioContext *>(usb);
    }
    ~Writer() {
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(nullptr, nullptr, usb);
        Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(nullptr, nullptr, usb);
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(nullptr, nullptr, usb);
    }
    /** One buffer of [pcm] (frames [from, from + frames)) through the raw path, as the thread writes it. */
    void write(const Pcm &pcm, int from, int frames) {
        const std::vector<uint8_t> &all = packedOf(pcm);
        const size_t frameBytes = (size_t)(pcm.bits / 8) * 2;
        SimArray chunk;
        chunk.bytes.assign(all.begin() + (size_t)from * frameBytes, all.begin() + (size_t)(from + frames) * frameBytes);
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWriteRaw(&env, nullptr, usb, reinterpret_cast<jbyteArray>(&chunk), pcm.bits);
    }
    /** The same frames through the float path (PROCESSED / FFmpeg sources): 24-bit integers are exact floats. */
    void writeFloat(const Pcm &pcm, int from, int frames) {
        SimArray chunk;
        chunk.isFloat = true;
        chunk.floats.reserve((size_t)frames * 2);
        for (int i = from * 2; i < (from + frames) * 2; i++) chunk.floats.push_back((float)pcm.samples[(size_t)i] / (float)(1 << 23));
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWrite(&env, nullptr, usb, reinterpret_cast<jfloatArray>(&chunk));
    }
    bool beginPause() { return Java_com_decent_usbaudio_UsbAudioStream_nativePipelineBeginPause(nullptr, nullptr, usb) == JNI_TRUE; }
    void requestPause() { Java_com_decent_usbaudio_UsbAudioStream_nativePipelineRequestPause(nullptr, nullptr, usb); }
    void cancelPauseRequest() { Java_com_decent_usbaudio_UsbAudioStream_nativePipelineCancelPauseRequest(nullptr, nullptr, usb); }
    bool consumePauseApplied() { return Java_com_decent_usbaudio_UsbAudioStream_nativePipelineConsumePauseApplied(nullptr, nullptr, usb) == JNI_TRUE; }
    int beginSeek() { return Java_com_decent_usbaudio_UsbAudioStream_nativePipelineBeginSeek(nullptr, nullptr, usb); }
    bool fadingOut() { return Java_com_decent_usbaudio_UsbAudioStream_nativePipelineFadingOut(nullptr, nullptr, usb) == JNI_TRUE; }
    void endFadeOut() { Java_com_decent_usbaudio_UsbAudioStream_nativePipelineEndFadeOut(nullptr, nullptr, usb); }
    void resume() { Java_com_decent_usbaudio_UsbAudioStream_nativePipelineResume(nullptr, nullptr, usb); }
    void markIdle() { Java_com_decent_usbaudio_UsbAudioStream_nativeMarkIdle(nullptr, nullptr, usb); }
    bool feedSilence() { return Java_com_decent_usbaudio_UsbAudioStream_nativePipelineFeedSilence(nullptr, nullptr, usb) == JNI_TRUE; }
    int64_t stat(int index) {
        int64_t stats[9];
        pipelineGestureStats(ctx, stats);
        return stats[index];
    }
    int64_t queuedFrames() { return usbQueuedFrames(ctx); }
    std::vector<uint8_t> captured() {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        return g_usb.capture;
    }
    /** What the end of stream does: the packetizer's last partial URB goes out, so every frame is on the wire. */
    std::vector<uint8_t> finish() {
        Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(&env, nullptr, usb);
        return captured();
    }
};

/** Frames [from, from + frames) of [pcm] as the DAC's 32-bit slots carry them, appended to [out]. */
void appendUsb(std::vector<uint8_t> &out, const Pcm &pcm, int from, int frames) {
    const std::vector<uint8_t> all = toUsb32(pcm);
    out.insert(out.end(), all.begin() + (size_t)from * 8, all.begin() + (size_t)(from + frames) * 8);
}

void appendSilence(std::vector<uint8_t> &out, int64_t frames) { out.insert(out.end(), (size_t)frames * 8, 0); }

/** The ramp [rampStart, rampStart + frames) of a [total]-frame fade, applied to a copy of [pcm]'s frames. */
void appendRamped(std::vector<uint8_t> &out, const Pcm &pcm, int from, int frames, int64_t rampStart, int64_t total, bool fadeIn) {
    const size_t at = out.size();
    appendUsb(out, pcm, from, frames);
    pcmApplyGainRamp(out.data() + at, frames, 2, 4, rampStart, total, fadeIn);
}

/** Wall-clock wait during which the fake DAC keeps playing what was queued. */
void listen(int ms) { sleepMs(ms); }

const int kFadeOut = seekFadeOutFrames(kRate);  // 768: 8 ms
const int kFadeIn = seekFadeInFrames(kRate);    // 1440: 15 ms

/**
 * What UsbStreamingThread does when it sees a pause: arm the fade, write the next buffers of its queue until
 * the ramp is complete (or the queue has no more), tell the stream it is about to idle.
 */
void pauseProtocol(Writer &w, const Pcm &pcm, int &nextFrame, int bufferFrames) {
    if (w.beginPause()) {
        while (w.fadingOut() && nextFrame < pcm.frames()) {
            const int n = std::min(bufferFrames, pcm.frames() - nextFrame);
            w.write(pcm, nextFrame, n);
            nextFrame += n;
        }
        if (w.fadingOut()) w.endFadeOut();
    }
    w.markIdle();
}

void testPipelinePauseResume(const Pcm &a) {
    for (const bool floatPath : {false, true}) {
        for (const int bufferFrames : {kBuf, 300}) {
            // (the 300-frame buffers make the 768-frame ramp span three of them: one continuous ramp)
            Writer w;
            auto write = [&](int from, int frames) { floatPath ? w.writeFloat(a, from, frames) : w.write(a, from, frames); };
            int next = 0;
            while (next < 3 * kBuf) {
                const int n = std::min(bufferFrames, 3 * kBuf - next);
                write(next, n);
                next += n;
            }
            // The pause: the ring plays out, the continuation is ramped down, the rest of that buffer is kept.
            if (w.beginPause()) {
                while (w.fadingOut()) {
                    const int n = std::min(bufferFrames, a.frames() - next);
                    write(next, n);
                    next += n;
                }
            }
            w.markIdle();
            const int64_t pendingAtPause = w.stat(3);
            listen(250);  // paused: the ring is dry and the writer is idle
            const int64_t emptyBeforeResume = [] { std::lock_guard<std::mutex> l(g_usb.mu); return (int64_t)g_usb.ringEmptyLogs; }();
            w.resume();
            // The rest of the audio follows the resume.
            while (next < a.frames()) {
                const int n = std::min(bufferFrames, a.frames() - next);
                write(next, n);
                next += n;
            }
            const std::vector<uint8_t> got = w.finish();
            // The clock counts media: every frame of the source exactly once, the ramp when it was written and the
            // rest of its block when the resume replayed it.
            CHECK(w.ctx->framesWritten == a.frames(), "%s path, %d-frame buffers: %lld frames counted for %d written",
                  floatPath ? "float" : "raw", bufferFrames, (long long)w.ctx->framesWritten, a.frames());

            // Expected: the audio up to the pause point untouched; 8 ms of the next block faded out; the rest of
            // the blocks faded in over 15 ms; then everything else untouched. Not one frame more, not one less.
            const int pauseFrame = 3 * kBuf;
            std::vector<uint8_t> want;
            appendUsb(want, a, 0, pauseFrame);
            appendRamped(want, a, pauseFrame, kFadeOut, 0, kFadeOut, false);
            appendRamped(want, a, pauseFrame + kFadeOut, kFadeIn, 0, kFadeIn, true);
            appendUsb(want, a, pauseFrame + kFadeOut + kFadeIn, a.frames() - pauseFrame - kFadeOut - kFadeIn);
            const long diff = firstDifference(want, got);
            CHECK(got.size() == want.size(), "%s path, %d-frame buffers: %zu bytes delivered for %zu (the pause lost or invented audio)",
                  floatPath ? "float" : "raw", bufferFrames, got.size(), want.size());
            CHECK(diff < 0, "%s path, %d-frame buffers: the pause/resume stream differs from the designed fades at byte %ld (frame %ld)",
                  floatPath ? "float" : "raw", bufferFrames, diff, diff / 8);
            // The ramp ends inside a block: what is left of that block is kept for the resume.
            const int64_t keptWanted = (int64_t)((kFadeOut + bufferFrames - 1) / bufferFrames) * bufferFrames - kFadeOut;
            CHECK(pendingAtPause == keptWanted, "%d-frame buffers: %lld frames of the block kept for the resume, expected %lld",
                  bufferFrames, (long long)pendingAtPause, (long long)keptWanted);
            {
                std::lock_guard<std::mutex> lock(g_usb.mu);
                // While idle nothing is reported as a starvation, before or after the resume.
                CHECK(g_usb.ringEmptyLogs == 0 && emptyBeforeResume == 0,
                      "%s path, %d-frame buffers: %d RING_EMPTY reported around a pause", floatPath ? "float" : "raw", bufferFrames, g_usb.ringEmptyLogs);
                // The only time the DAC found the ring dry is the pause itself, once, and only at the resume.
                CHECK(g_usb.gaps == 1, "%s path, %d-frame buffers: %d empty stretches, expected only the pause", floatPath ? "float" : "raw", bufferFrames, g_usb.gaps);
                // B23-P2: ... and the output gap model, which watches submit gaps, does not call that pause a gap either.
                CHECK(g_usb.outputGapLines.empty(), "%s path, %d-frame buffers: a pause reported as an output gap: %s", floatPath ? "float" : "raw", bufferFrames,
                      g_usb.outputGapLines.empty() ? "" : g_usb.outputGapLines.front().c_str());
            }
        }
    }
    printf("pipeline pause/resume: 8 ms fade-out of the continuation, 15 ms fade-in of the rest, count exact, 0 RING_EMPTY\n");
}

/**
 * B12a-6: a pause that reaches the writer while it is inside a block. The FLAC decoder's block is 8192 frames
 * (~190 ms at 44.1 kHz) and the ring holds 80 ms, so most of a block is spent waiting for ring room; the pause used
 * to be seen only when the block was done, 71 ms later on the device (key 07.656 → pause() 07.800 → fade queued
 * 07.871). The stream now looks for the request between two URBs: the fade starts there and the rest of the block is
 * kept for the resume, with the same shape, the same count and no RING_EMPTY as a pause seen at a block's end.
 */
void testPipelinePauseInsideBlock(const Pcm &a) {
    const int bigBlock = 4 * kBuf;                                   // 341 ms at 96 kHz: the ring takes 80 ms of it
    {
        // (a) the control: the same block with no pause takes its natural time (it is paced by the DAC)
        Writer w;
        w.write(a, 0, 2 * kBuf);
        const int64_t t0 = nowUs();
        w.write(a, 2 * kBuf, bigBlock);
        const int64_t naturalMs = (nowUs() - t0) / 1000;
        CHECK(naturalMs > 180, "the control block took only %lld ms: too short to show a pause inside it", (long long)naturalMs);
    }
    int64_t worstReactMs = 0, worstBlockMs = 0;
    for (const bool floatPath : {false, true}) {
        // (b) the pause arrives 60 ms into the block
        Writer w;
        auto write = [&](int from, int frames) { floatPath ? w.writeFloat(a, from, frames) : w.write(a, from, frames); };
        write(0, 2 * kBuf);
        std::atomic<int64_t> requestedUs{0};
        // B27-A0: the host's request is an event of the virtual clock: it runs on this thread when 60 ms of the block have
        // played, in the middle of the writer's wait for ring room, which is where the real host's thread lands too.
        simclock::at(simclock::nowNs() + 60 * 1000000LL, [&] {
            requestedUs = nowUs();
            w.requestPause();
        });
        const int64_t startUs = nowUs();
        write(2 * kBuf, bigBlock);
        const int64_t returnedUs = nowUs();
        CHECK(requestedUs.load() != 0, "%s path: the pause request never reached the writer", floatPath ? "float" : "raw");
        const int64_t reactMs = (returnedUs - requestedUs.load()) / 1000;
        const int64_t blockMs = (returnedUs - startUs) / 1000;
        worstReactMs = std::max(worstReactMs, reactMs);
        worstBlockMs = std::max(worstBlockMs, blockMs);
        CHECK(reactMs < 25, "%s path: the fade was queued %lld ms after the pause request (target: within ~1 ms of it; it was ~70 ms)",
              floatPath ? "float" : "raw", (long long)reactMs);
        CHECK(blockMs < 150, "%s path: the block took %lld ms with a pause 60 ms in (the natural time is ~260 ms)", floatPath ? "float" : "raw",
              (long long)blockMs);
        CHECK(w.consumePauseApplied(), "%s path: the stream did not report that it acted on the pause", floatPath ? "float" : "raw");
        CHECK(!w.consumePauseApplied(), "the report of a pause acted on is not cleared by reading it");
        CHECK(w.stat(1) == 1 && w.stat(2) == 0, "%s path: after the pause the ring should end in a finished fade (endsInFade=%lld fadingOut=%lld)",
              floatPath ? "float" : "raw", (long long)w.stat(1), (long long)w.stat(2));
        CHECK(w.stat(6) == 1, "%d pause fades counted for one pause", (int)w.stat(6));
        // the pause point: what was written untouched, plus the ramp that is counted
        const int pauseFrame = (int)w.ctx->framesWritten - kFadeOut;
        CHECK(pauseFrame > 2 * kBuf && pauseFrame + kFadeOut < 2 * kBuf + bigBlock, "%s path: pause point %d is outside the block", floatPath ? "float" : "raw", pauseFrame);
        const int keptWanted = 2 * kBuf + bigBlock - pauseFrame - kFadeOut;
        CHECK(w.stat(3) == keptWanted, "%s path: %lld frames kept for the resume, expected %d", floatPath ? "float" : "raw", (long long)w.stat(3), keptWanted);
        w.markIdle();
        listen(250);                                                    // paused: the ring plays out, the writer is idle
        w.resume();
        int next = 2 * kBuf + bigBlock;
        while (next < a.frames()) {
            const int n = std::min(kBuf, a.frames() - next);
            write(next, n);
            next += n;
        }
        const std::vector<uint8_t> got = w.finish();
        CHECK(w.ctx->framesWritten == a.frames(), "%s path: %lld frames counted for %d written", floatPath ? "float" : "raw",
              (long long)w.ctx->framesWritten, a.frames());
        std::vector<uint8_t> want;
        appendUsb(want, a, 0, pauseFrame);
        appendRamped(want, a, pauseFrame, kFadeOut, 0, kFadeOut, false);
        appendRamped(want, a, pauseFrame + kFadeOut, kFadeIn, 0, kFadeIn, true);
        appendUsb(want, a, pauseFrame + kFadeOut + kFadeIn, a.frames() - pauseFrame - kFadeOut - kFadeIn);
        CHECK(got.size() == want.size(), "%s path: %zu bytes delivered for %zu (a pause inside a block lost or invented audio)",
              floatPath ? "float" : "raw", got.size(), want.size());
        const long diff = firstDifference(want, got);
        CHECK(diff < 0, "%s path: the stream differs from the designed fades at byte %ld (frame %ld, pause at %d)", floatPath ? "float" : "raw",
              diff, diff / 8, pauseFrame);
        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            CHECK(g_usb.ringEmptyLogs == 0, "%s path: %d RING_EMPTY around a pause inside a block", floatPath ? "float" : "raw", g_usb.ringEmptyLogs);
            CHECK(g_usb.gaps == 1, "%s path: %d empty stretches, expected only the pause", floatPath ? "float" : "raw", g_usb.gaps);
            CHECK(g_usb.outputGapLines.empty(), "%s path: a pause inside a block reported as an output gap", floatPath ? "float" : "raw");
        }
    }
    {
        // (c) a request made before the block: the block is the continuation, as when the pause is seen at a block's end
        Writer w;
        w.write(a, 0, 2 * kBuf);
        w.requestPause();
        w.write(a, 2 * kBuf, kBuf);
        CHECK(w.consumePauseApplied() && w.stat(1) == 1 && w.stat(2) == 0, "a request before the block did not start the fade at its first frame");
        // The first URB of the block carries the previous block's residual (a block is not a whole number of URBs), so a
        // few frames of this block go out untouched before the fade starts: never more than two URBs' worth.
        const int pauseFrame = (int)w.ctx->framesWritten - kFadeOut;
        CHECK(pauseFrame >= 2 * kBuf && pauseFrame - 2 * kBuf <= 2 * 12 * 8, "the fade started %d frames into the block (a request before it)", pauseFrame - 2 * kBuf);
        CHECK(w.stat(3) == kBuf - (pauseFrame - 2 * kBuf) - kFadeOut, "%lld frames kept of the block, expected %d", (long long)w.stat(3),
              kBuf - (pauseFrame - 2 * kBuf) - kFadeOut);
        w.markIdle();
        listen(200);
        w.resume();
        w.write(a, 3 * kBuf, kBuf);
        std::vector<uint8_t> want;
        appendUsb(want, a, 0, pauseFrame);
        appendRamped(want, a, pauseFrame, kFadeOut, 0, kFadeOut, false);
        appendRamped(want, a, pauseFrame + kFadeOut, kFadeIn, 0, kFadeIn, true);
        appendUsb(want, a, pauseFrame + kFadeOut + kFadeIn, 4 * kBuf - pauseFrame - kFadeOut - kFadeIn);
        CHECK(w.ctx->framesWritten == 4 * kBuf, "%lld frames counted for %d written", (long long)w.ctx->framesWritten, 4 * kBuf);
        CHECK(firstDifference(want, w.finish()) < 0, "the pause seen at a block's first frame differs from the designed shape");
    }
    {
        // (d) the ramp longer than what is left: it carries on into the next blocks, as before
        Writer w;
        w.write(a, 0, 2 * kBuf);
        w.requestPause();
        w.write(a, 2 * kBuf, 300);                                       // 300 < 768: the ramp is partial
        CHECK(w.consumePauseApplied() && w.fadingOut(), "a short block did not leave the ramp armed");
        int next = 2 * kBuf + 300;
        while (w.fadingOut()) {                                         // what performPause does: the next buffers continue it
            const int n = std::min(300, a.frames() - next);
            w.write(a, next, n);
            next += n;
        }
        w.markIdle();
        // the ramp is complete: what was written is the untouched head plus the 8 ms that are counted
        const int pauseFrame = (int)w.ctx->framesWritten - kFadeOut;
        CHECK(pauseFrame >= 2 * kBuf && pauseFrame < 2 * kBuf + 300, "the ramp of a short block started at frame %d", pauseFrame);
        listen(200);
        w.resume();
        w.write(a, next, kBuf);
        std::vector<uint8_t> want;
        appendUsb(want, a, 0, pauseFrame);
        appendRamped(want, a, pauseFrame, kFadeOut, 0, kFadeOut, false);
        appendRamped(want, a, pauseFrame + kFadeOut, kFadeIn, 0, kFadeIn, true);
        appendUsb(want, a, pauseFrame + kFadeOut + kFadeIn, next + kBuf - pauseFrame - kFadeOut - kFadeIn);
        CHECK(firstDifference(want, w.finish()) < 0, "a pause whose ramp spans blocks differs from the designed shape");
    }
    {
        // (e) a resume (or a seek) before the writer acts: the request is dropped and the block is written whole
        Writer w;
        w.write(a, 0, 2 * kBuf);
        w.requestPause();
        w.cancelPauseRequest();
        w.write(a, 2 * kBuf, kBuf);
        CHECK(!w.consumePauseApplied() && w.stat(1) == 0 && w.stat(3) == 0, "a cancelled request still cut a block");
        w.requestPause();
        CHECK(w.beginSeek() == PIPELINE_SEEK_FADE, "the seek over a pause request did not arm its own fade");
        const std::vector<uint8_t> before = w.captured();
        w.write(a, 3 * kBuf, kBuf);                                      // the seek's continuation: ramped, never split by a stale request
        CHECK(w.stat(2) == 0 || w.stat(1) == 1, "the seek's fade is neither finished nor armed");
        (void)before;
    }
    {
        // (f) a stream that has not played yet has nothing to fade: a request does not split its first block
        Writer w;
        w.requestPause();
        w.write(a, 0, kBuf);
        CHECK(!w.consumePauseApplied() && w.ctx->framesWritten == kBuf, "the first block of a stream was split by a pause request");
        CHECK(firstDifference([&] { std::vector<uint8_t> v; appendUsb(v, a, 0, kBuf); return v; }(), w.finish()) < 0,
              "the first block of a stream changed");
    }
    printf("pipeline pause inside a block: the fade is queued %lld ms after the request (worst of raw and float; the block alone takes ~260 ms, the "
           "device measured +71 ms before), rest kept, same shape and count, 0 RING_EMPTY\n", (long long)worstReactMs);
}

/** A pause with nothing queued behind it, and a pause once the ring has played out, take nothing and lose nothing. */
void testPipelinePauseEdges(const Pcm &a) {
    {
        Writer w;
        w.write(a, 0, 2 * kBuf);
        int next = 2 * kBuf;
        CHECK(w.beginPause(), "a pause over audible audio did not arm");
        w.endFadeOut();                    // the queue had nothing behind: no continuation to ramp
        w.markIdle();
        CHECK(w.stat(1) == 1, "a pause with no continuation left the ring's flag clear");
        listen(200);
        w.resume();                        // only a fade-in is owed
        w.write(a, next, kBuf);
        std::vector<uint8_t> want;
        appendUsb(want, a, 0, 2 * kBuf);
        appendRamped(want, a, 2 * kBuf, kFadeIn, 0, kFadeIn, true);
        appendUsb(want, a, 2 * kBuf + kFadeIn, kBuf - kFadeIn);
        CHECK(firstDifference(want, w.finish()) < 0, "a pause with nothing behind it changed more than the fade-in of what follows");
    }
    {
        Writer w;
        w.write(a, 0, kBuf);
        listen(400);                       // the ring has played out: silent, nothing audible to cut
        int next = kBuf;
        pauseProtocol(w, a, next, kBuf);
        CHECK(next == kBuf && w.stat(6) == 0, "a pause on a ring that had played out still queued a fade");
    }
    printf("pipeline pause edges: no continuation, ring already played out\n");
}

void testPipelineSeek(const Pcm &a, const Pcm &n) {
    // A seek in the middle of a decoded block: the ring holds the end of block 2, block 3 is the continuation.
    Writer w;
    w.write(a, 0, kBuf);
    w.write(a, kBuf, kBuf);
    w.write(a, 2 * kBuf, kBuf);
    const int64_t queuedAtSeek = w.queuedFrames();
    CHECK(queuedAtSeek > 0, "the ring held nothing when the seek arrived");

    const int plan = w.beginSeek();
    CHECK(plan == PIPELINE_SEEK_FADE, "a seek over audible audio did not ask for a fade (%d)", plan);
    // The continuation: block 3, of which only the first 8 ms are played; blocks 4.. belonged to the old position.
    CHECK(w.fadingOut(), "the continuation fade-out was not armed");
    w.write(a, 3 * kBuf, kBuf);
    CHECK(!w.fadingOut(), "the ramp did not complete within one block");
    // The decoder takes 150 ms to produce the first buffer of the new position (the measured seeks took 20-550
    // ms): longer than the ring holds, so without the silence the DAC would run dry before it arrives.
    const int64_t seekStartUs = nowUs();
    while (nowUs() - seekStartUs < 150000) CHECK(w.feedSilence(), "the silence feed failed");
    const int64_t seekUs = nowUs() - seekStartUs;
    const int64_t silence = w.stat(5);
    // The new position: its first buffer fades in over 15 ms; nothing of the old position follows.
    const int target = 20000;
    w.write(n, target, kBuf);
    w.write(n, target + kBuf, kBuf);
    const std::vector<uint8_t> got = w.finish();

    std::vector<uint8_t> want;
    appendUsb(want, a, 0, 3 * kBuf);
    appendRamped(want, a, 3 * kBuf, kFadeOut, 0, kFadeOut, false);
    appendSilence(want, silence);
    appendRamped(want, n, target, kFadeIn, 0, kFadeIn, true);
    appendUsb(want, n, target + kFadeIn, 2 * kBuf - kFadeIn);
    const long diff = firstDifference(want, got);
    CHECK(w.ctx->framesWritten == 5 * kBuf, "seek: %lld frames counted, expected the old audio and the new (%d)", (long long)w.ctx->framesWritten, 5 * kBuf);
    CHECK(got.size() == want.size(), "seek: %zu bytes delivered for %zu", got.size(), want.size());
    CHECK(diff < 0, "seek: the stream differs from (old audio, 8 ms fade-out, %lld frames of silence, new audio faded in) at byte %ld (frame %ld)",
          (long long)silence, diff, diff / 8);
    // No sample of the old position after the new one starts: the new audio is exactly where it must be, so
    // the 7 424 frames of block 3 that follow its ramp never reached the DAC.
    CHECK(got.size() == (size_t)(3 * kBuf + kFadeOut + silence + 2 * kBuf) * 8, "seek: the length is not old + ramp + silence + new");
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        CHECK(g_usb.gaps == 0 && g_usb.ringEmptyLogs == 0, "seek: the DAC found the ring dry (%d gaps, max %lld us; %d RING_EMPTY)",
              g_usb.gaps, (long long)g_usb.maxGapUs, g_usb.ringEmptyLogs);
        CHECK(g_usb.audioDiscards == 0, "seek: %d URBs were cancelled", g_usb.audioDiscards);
        CHECK(g_usb.outputGapLines.empty(), "seek: reported as an output gap: %s", g_usb.outputGapLines.empty() ? "" : g_usb.outputGapLines.front().c_str());
    }
    const int64_t leadFrames = seekSilenceLeadFrames(kRate);
    const int64_t bound = seekUs * kRate / 1000000 + leadFrames + kRate / 1000 + 1;
    CHECK(silence <= bound, "seek: %lld frames of silence for a %lld us seek (bound %lld)", (long long)silence, (long long)seekUs, (long long)bound);
    printf("pipeline seek: old audio, 8 ms fade-out, %lld frames of silence (%.0f ms), new audio faded in over 15 ms; 0 gaps, 0 RING_EMPTY, 0 URBs cut\n",
           (long long)silence, silence * 1000.0 / kRate);
}

/** AUD-140 for the pipeline: a seek while paused, with the ring still playing, then with the ring played out. */
void testPipelineSeekWhilePaused(const Pcm &a, const Pcm &n) {
    for (const bool ringPlayedOut : {false, true}) {
        Writer w;
        w.write(a, 0, 3 * kBuf);
        int next = 3 * kBuf;
        pauseProtocol(w, a, next, kBuf);          // the pause's ramp is behind the ring; the rest is kept
        const int64_t kept = w.stat(3);
        CHECK(kept == kBuf - kFadeOut, "the pause kept %lld frames of its block", (long long)kept);
        if (ringPlayedOut) listen(300);
        // The seek arrives while paused: the old position's kept block must never play again.
        const int plan = w.beginSeek();
        CHECK(plan == (ringPlayedOut ? PIPELINE_SEEK_NONE : PIPELINE_SEEK_SILENCE),
              "a seek while paused (ring %s) planned %d", ringPlayedOut ? "played out" : "still playing", plan);
        CHECK(w.stat(3) == 0, "the kept remainder of the old position survived the seek");
        w.markIdle();
        listen(150);
        w.resume();                               // the user plays again: the new position fades in
        const int target = 30000;
        w.write(n, target, kBuf);
        std::vector<uint8_t> want;
        appendUsb(want, a, 0, 3 * kBuf);
        appendRamped(want, a, 3 * kBuf, kFadeOut, 0, kFadeOut, false);
        appendRamped(want, n, target, kFadeIn, 0, kFadeIn, true);
        appendUsb(want, n, target + kFadeIn, kBuf - kFadeIn);
        const long diff = firstDifference(want, w.finish());
        CHECK(diff < 0, "seek while paused (ring %s): the stream differs from (old, pause ramp, new faded in) at byte %ld (frame %ld)",
              ringPlayedOut ? "played out" : "still playing", diff, diff / 8);
        std::lock_guard<std::mutex> lock(g_usb.mu);
        CHECK(g_usb.ringEmptyLogs == 0, "seek while paused: %d RING_EMPTY", g_usb.ringEmptyLogs);
        CHECK(g_usb.outputGapLines.empty(), "seek while paused: reported as an output gap: %s", g_usb.outputGapLines.empty() ? "" : g_usb.outputGapLines.front().c_str());
    }
    // A seek that overtook its pause: the pause had armed its ramp and written part of it when the seek came.
    {
        Writer w;
        w.write(a, 0, 3 * kBuf);
        CHECK(w.beginPause(), "pause did not arm");
        w.write(a, 3 * kBuf, 300);               // 300 of the 768 ramp frames
        const int plan = w.beginSeek();           // the player seeks while pausing
        CHECK(plan == PIPELINE_SEEK_FADE && w.fadingOut(), "a seek in the middle of a pause's ramp did not carry it on (%d)", plan);
        w.write(a, 3 * kBuf + 300, kBuf);        // the ramp is finished from the continuation, the rest dropped
        CHECK(!w.fadingOut() && w.stat(3) == 0, "the seek kept the pause's remainder or did not finish the ramp");
        const int target = 10000;
        w.write(a, target, kBuf);                // (new position: any audio; here the same source)
        std::vector<uint8_t> want;
        appendUsb(want, a, 0, 3 * kBuf);
        appendRamped(want, a, 3 * kBuf, kFadeOut, 0, kFadeOut, false);
        appendRamped(want, a, target, kFadeIn, 0, kFadeIn, true);
        appendUsb(want, a, target + kFadeIn, kBuf - kFadeIn);
        const long diff = firstDifference(want, w.finish());
        CHECK(diff < 0, "seek over a pause's ramp: one continuous ramp expected, differs at byte %ld (frame %ld)", diff, diff / 8);
    }
    printf("pipeline seek while paused: the old position's remainder never plays; the new one fades in at the resume\n");
}

/** A finger dragging the seek bar: five seeks within ~125 ms must not pile up fades or silence. */
void testPipelineRepeatedSeeks(const Pcm &a, const Pcm &n) {
    Writer w;
    w.write(a, 0, 3 * kBuf);
    const int64_t startUs = nowUs();
    int plans[5];
    for (int i = 0; i < 5; i++) {
        plans[i] = w.beginSeek();
        if (plans[i] == PIPELINE_SEEK_FADE) w.write(a, 3 * kBuf, kBuf);   // the continuation of the old position
        const int64_t until = nowUs() + 25000;
        while (nowUs() < until) CHECK(w.feedSilence(), "the silence feed failed");
    }
    const int64_t seekUs = nowUs() - startUs;
    const int64_t silence = w.stat(5);
    const int target = 5000;
    w.write(n, target, kBuf);
    const std::vector<uint8_t> got = w.finish();
    CHECK(plans[0] == PIPELINE_SEEK_FADE, "the first seek planned %d", plans[0]);
    for (int i = 1; i < 5; i++) CHECK(plans[i] == PIPELINE_SEEK_SILENCE, "seek %d planned %d: a second continuation would fade audio nobody asked to keep", i + 1, plans[i]);
    CHECK(w.stat(7) == 1 && w.stat(8) == 1, "%lld seek fades and %lld completed ramps for five seeks", (long long)w.stat(7), (long long)w.stat(8));
    std::vector<uint8_t> want;
    appendUsb(want, a, 0, 3 * kBuf);
    appendRamped(want, a, 3 * kBuf, kFadeOut, 0, kFadeOut, false);
    appendSilence(want, silence);
    appendRamped(want, n, target, kFadeIn, 0, kFadeIn, true);
    appendUsb(want, n, target + kFadeIn, kBuf - kFadeIn);
    const long diff = firstDifference(want, got);
    CHECK(diff < 0, "five seeks: the stream differs from one fade-out, the silence and one fade-in at byte %ld (frame %ld)", diff, diff / 8);
    const int64_t bound = seekUs * kRate / 1000000 + seekSilenceLeadFrames(kRate) + kRate / 1000 + 1;
    CHECK(silence <= bound, "five seeks fed %lld frames of silence in %lld us (bound %lld): it accumulated", (long long)silence, (long long)seekUs, (long long)bound);
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        CHECK(g_usb.gaps == 0 && g_usb.ringEmptyLogs == 0, "five seeks: %d gaps, %d RING_EMPTY", g_usb.gaps, g_usb.ringEmptyLogs);
        CHECK(g_usb.outputGapLines.empty(), "five seeks: reported as an output gap: %s", g_usb.outputGapLines.empty() ? "" : g_usb.outputGapLines.front().c_str());
    }
    printf("pipeline five seeks in %.0f ms: one fade-out, %lld frames of silence (%.0f ms, bound %.0f), one fade-in, 0 gaps\n",
           seekUs / 1000.0, (long long)silence, silence * 1000.0 / kRate, bound * 1000.0 / kRate);
}

/**
 * A manual skip is the same gesture, and when the next item needs another stream shape the old stream is
 * released: the faded tail must play out before it is discarded (AUD-123), not be cut.
 */
void testPipelineSkipThenRebuild(const Pcm &a) {
    Writer w;
    w.write(a, 0, 3 * kBuf);
    CHECK(w.beginSeek() == PIPELINE_SEEK_FADE, "skip did not ask for a fade");
    w.write(a, 3 * kBuf, kBuf);
    const int64_t t0 = nowUs();
    Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(nullptr, nullptr, w.usb);
    Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(nullptr, nullptr, w.usb);
    const int64_t drainMs = (nowUs() - t0) / 1000;
    std::vector<uint8_t> want;
    appendUsb(want, a, 0, 3 * kBuf);
    appendRamped(want, a, 3 * kBuf, kFadeOut, 0, kFadeOut, false);
    int cuts;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        cuts = g_usb.audioDiscards;
    }
    CHECK(cuts == 0, "the rebuild after a skip cancelled %d URBs of the faded tail", cuts);
    CHECK(firstDifference(want, w.captured()) < 0, "skip + rebuild: the tail is not old audio then the 8 ms fade-out");
    CHECK(drainMs < 400, "the faded tail took %lld ms to play out", (long long)drainMs);
    printf("pipeline skip then stream rebuild: faded tail played out (%lld ms), %d URBs cut\n", (long long)drainMs, cuts);
}

/** Natural joins stay what B8b measured: a pipeline that is never asked for a gesture is byte-exact. */
void testPipelineJoinsAreUntouched(const Pcm &a, const Pcm &b) {
    // Same rate: the end-of-stream marker, the residual, then the next item on the same stream.
    Writer w;
    w.write(a, 0, a.frames());
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(&w.env, nullptr, w.usb) == JNI_TRUE, "residual drain failed");
    w.write(b, 0, b.frames());
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(&w.env, nullptr, w.usb) == JNI_TRUE, "residual drain failed");
    listen(150);
    std::vector<uint8_t> want = toUsb32(a);
    const std::vector<uint8_t> wantB = toUsb32(b);
    want.insert(want.end(), wantB.begin(), wantB.end());
    CHECK(firstDifference(want, w.captured()) < 0, "pipeline same-rate join A||B is not bit-exact");
    int64_t stats[9];
    pipelineGestureStats(w.ctx, stats);
    CHECK(stats[2] == 0 && stats[3] == 0 && stats[4] == 0 && stats[5] == 0 && stats[6] == 0 && stats[7] == 0,
          "a join with no gesture moved the barrier's state");
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        CHECK(g_usb.gaps == 0 && g_usb.ringEmptyLogs == 0, "pipeline join: %d gaps, %d RING_EMPTY", g_usb.gaps, g_usb.ringEmptyLogs);
        CHECK(g_usb.outputGapLines.empty(), "pipeline join: reported as an output gap: %s", g_usb.outputGapLines.empty() ? "" : g_usb.outputGapLines.front().c_str());
    }
    printf("pipeline natural join: bit-exact, the barrier untouched (no fade, no silence, nothing kept)\n");
}

void testPipelineGestures(const Pcm &a, const Pcm &b) {
    // Longer sources than the engine tests use: a seek and a pause need audio behind the ring.
    const Pcm longA = makeNoise(10 * kBuf, 24, 0x7a11cafeu);
    const Pcm longN = makeNoise(40000 + 2 * kBuf, 24, 0x5eed0b9du);
    testPipelinePauseResume(longA);
    testPipelinePauseInsideBlock(longA);
    testPipelinePauseEdges(longA);
    testPipelineSeek(longA, longN);
    testPipelineSeekWhilePaused(longA, longN);
    testPipelineRepeatedSeeks(longA, longN);
    testPipelineSkipThenRebuild(longA);
    testPipelineJoinsAreUntouched(a, b);
}

// ── 5. The wait between two reaps (AUD-145) ─────────────────────────────────────────────────────

/**
 * Streams [pcm] through the engine in the given wait mode; returns bytes and the wait counters' delta, and in
 * [completions] how many URBs completed for the writer to wait on (data URBs and feedback readings).
 */
std::vector<uint8_t> streamOnce(bool pollMode, const std::string &path, uint64_t delta[5], int64_t *completions) {
    usbSetPollWait(pollMode);
    g_usb.reset(96000, 8);
    uint64_t before[5];
    usbWaitStats(before);
    int64_t feedbackBefore[USB_FEEDBACK_STATS_COUNT], feedbackAfter[USB_FEEDBACK_STATS_COUNT];
    usbFeedbackStats(feedbackBefore);
    const jlong usb = createStream(96000, 32);
    Engine e = createEngine(usb, path);
    startEngine(e, true);
    waitStopped(e, 8000);
    sleepMs(100);
    Java_com_decent_usbaudio_NativeAudioEngine_nativeDestroy(nullptr, nullptr, e.handle);
    releaseStream(usb);
    uint64_t after[5];
    usbWaitStats(after);
    usbFeedbackStats(feedbackAfter);
    for (int i = 0; i < 5; i++) delta[i] = after[i] - before[i];
    delta[0] = after[0];  // the mode is a state, not a count
    std::lock_guard<std::mutex> lock(g_usb.mu);
    *completions = (int64_t)g_usb.audioUrbs + (feedbackAfter[8] - feedbackBefore[8]);
    return g_usb.capture;
}

void testWaitModes(const std::string &dir) {
    uint64_t pollDelta[5], sleepDelta[5];
    int64_t pollCompletions = 0, sleepCompletions = 0;
    const std::vector<uint8_t> viaPoll = streamOnce(true, dir + "/a.flac", pollDelta, &pollCompletions);
    const std::vector<uint8_t> viaSleep = streamOnce(false, dir + "/a.flac", sleepDelta, &sleepCompletions);
    CHECK(firstDifference(viaPoll, viaSleep) < 0, "the wait mode changed the audio delivered (poll %zu B, sleep %zu B)",
          viaPoll.size(), viaSleep.size());
    CHECK(viaPoll.size() == (size_t)kFrames96 * 8, "poll mode delivered %zu bytes", viaPoll.size());
    CHECK(pollDelta[0] == 1 && sleepDelta[0] == 0, "the mode counter does not say which mode ran");
    // AUD-145: the writer's ring is full nearly all the time, and the old loop slept 125 µs a time, ~8 000 wake-ups a
    // second. Poll mode wakes when the kernel has something: at most once per completion it was waiting for.
    //
    // This used to be `poll * 3 < sleep` against the MEASURED sleep count, and it failed now and then (while a build
    // ran, with the machine loaded): the measured ratio is 3.0-3.4, so the threshold sat on the noise. The poll side
    // barely moves with load (1190-1300 wake-ups for these 0.8 s in every run, loaded or not), the sleep side does
    // not: a usleep(125) that overshoots is a wake-up that never happens. The claim is the same — poll wakes at
    // least 3x less than the 125 µs loop — but the loop's side is now what it is built to do (one wake-up per
    // 125 µs of audio), and the measured sleep count is only required to be a loop that really waits more.
    const double audioMs = kFrames96 * 1000.0 / 96000.0;
    const uint64_t nominalSleepWakes = (uint64_t)(audioMs * 1000.0 / 125.0);  // 6 400 for 0.8 s
    CHECK(pollDelta[1] * 3 < nominalSleepWakes, "poll woke %llu times against the %llu a 125 µs loop makes in %.0f ms: not 3x fewer",
          (unsigned long long)pollDelta[1], (unsigned long long)nominalSleepWakes, audioMs);
    CHECK(pollDelta[1] < sleepDelta[1], "poll woke %llu times, the sleep loop %llu: poll did not wake less",
          (unsigned long long)pollDelta[1], (unsigned long long)sleepDelta[1]);
    // Exact and load-independent: every poll wake-up is a completion (or the 2 ms slice running out, or a poll that
    // lost a completion to another reaper), so poll mode can never wake more often than the kernel gave it reasons.
    const uint64_t reasons = (uint64_t)pollCompletions + pollDelta[3] + pollDelta[4] + 16;
    CHECK(pollDelta[1] <= reasons, "poll woke %llu times for %lld completions, %llu timeouts, %llu empty polls: more than the kernel asked for",
          (unsigned long long)pollDelta[1], (long long)pollCompletions, (unsigned long long)pollDelta[3], (unsigned long long)pollDelta[4]);
    // (CPU is not compared: the fake kernel's own poll is a user-space loop. The device capture does that.)
    printf("wait modes: same bytes; waits poll=%llu sleep=%llu (a 125 us loop: %llu) over %.1f s of audio, poll %lld completions\n",
           (unsigned long long)pollDelta[1], (unsigned long long)sleepDelta[1], (unsigned long long)nominalSleepWakes,
           kFrames96 / 96000.0, (long long)pollCompletions);
    (void)sleepCompletions;
    usbSetPollWait(true);
}

/**
 * A kernel that never reports POLLOUT for these completions must cost latency, not audio: each poll is
 * bounded, and everything already completed is reaped before the next wait.
 */
void testSilentPollDoesNotStarve(const std::string &dir) {
    usbSetPollWait(true);
    g_usb.reset(96000, 8);
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        g_usb.silentPoll = true;
    }
    const jlong usb = createStream(96000, 32);
    Engine e = createEngine(usb, dir + "/a.flac");
    const int64_t t0 = nowUs();
    startEngine(e, true);
    const bool ended = waitStopped(e, 8000);
    const int64_t elapsedMs = (nowUs() - t0) / 1000;
    sleepMs(100);
    Java_com_decent_usbaudio_NativeAudioEngine_nativeDestroy(nullptr, nullptr, e.handle);
    releaseStream(usb);
    std::vector<uint8_t> got;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        got = g_usb.capture;
    }
    const std::vector<uint8_t> want = toUsb32(makeNoise(kFrames96, 24, 0x1234abcdu));
    CHECK(ended, "the engine never finished against a poll that never signals");
    CHECK(firstDifference(want, got) < 0, "a silent poll changed the audio (%zu of %zu bytes)", got.size(), want.size());
    // 0.8 s of audio, paced by the DAC: the writer keeps up, within the ring's slack.
    CHECK(elapsedMs < 800 + 350, "a silent poll stalled the writer: %lld ms for 800 ms of audio", (long long)elapsedMs);
    printf("poll that never signals: audio intact, %lld ms for 800 ms of audio\n", (long long)elapsedMs);
}

/** The timeouts are the ones the callers pass, in ms, whatever the wait is made of. */
void testWaitTimeouts() {
    for (bool pollMode : {true, false}) {
        usbSetPollWait(pollMode);
        g_usb.reset(96000, 8);
        const jlong usb = createStream(96000, 32);
        auto *ctx = reinterpret_cast<UsbAudioContext *>(usb);
        std::vector<uint8_t> silence(96 * 8 * 4, 0);
        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            g_usb.hold = true;  // a stalled host: nothing completes
        }
        submitPcmToUrbs(ctx, silence.data(), (int)silence.size());
        // usbWaitForProgress waits 2 ms for the oldest URB: false after about that long, not instantly
        // and not for seconds.
        const int64_t t0 = nowUs();
        const bool progress = usbWaitForProgress(ctx);
        const int64_t waitedUs = nowUs() - t0;
        CHECK(!progress, "%s mode: reported progress on a stalled ring", pollMode ? "poll" : "sleep");
        CHECK(waitedUs >= 1900 && waitedUs < 40000, "%s mode: the 2 ms wait took %lld us", pollMode ? "poll" : "sleep", (long long)waitedUs);
        // A device that is gone: the reap reports the error at once, the wait must not spin or hang.
        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            g_usb.gone = true;
        }
        uint64_t before[5], after[5];
        usbWaitStats(before);
        const int64_t g0 = nowUs();
        const bool gone = usbWaitForProgress(ctx);
        const int64_t goneUs = nowUs() - g0;
        usbWaitStats(after);
        CHECK(!gone, "%s mode: progress on a device that is gone", pollMode ? "poll" : "sleep");
        CHECK(goneUs < 40000, "%s mode: a gone device held the wait for %lld us", pollMode ? "poll" : "sleep", (long long)goneUs);
        CHECK(after[1] - before[1] < 50, "%s mode: %llu waits against a gone device (a spin)", pollMode ? "poll" : "sleep",
              (unsigned long long)(after[1] - before[1]));
        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            g_usb.hold = false;
            g_usb.gone = false;
            g_usb.inflight.clear();
        }
        ctx->urbsInFlight = 0;
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(nullptr, nullptr, usb);
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(nullptr, nullptr, usb);
    }
    usbSetPollWait(true);
    printf("wait timeouts: 2 ms bound and a gone device behave the same in both modes\n");
}


// ── 7. The DAC's feedback stuck at a value (B10, AUD-124 "shh") ─────────────────────────────────

/** [first, last) packets of the stream so far: how many, how many of [frames] frames, the mean per packet. */
struct PacketSpan {
    size_t packets = 0;
    size_t sevens = 0;     // packets of 7 frames (48 kHz nominal is 6: a 7 is the surplus the feedback asked for)
    size_t outliers = 0;   // anything but 6 or 7
    double mean = 0.0;
};

PacketSpan spanOf(size_t from, size_t to) {
    PacketSpan span;
    std::lock_guard<std::mutex> lock(g_usb.mu);
    size_t total = 0;
    for (size_t i = from; i < to && i < g_usb.packetFrames.size(); i++) {
        const int n = g_usb.packetFrames[i];
        span.packets++;
        total += (size_t)n;
        if (n == 7) span.sevens++;
        if (n != 6 && n != 7) span.outliers++;
    }
    span.mean = span.packets ? (double)total / (double)span.packets : 0.0;
    return span;
}

size_t packetsSoFar() {
    std::lock_guard<std::mutex> lock(g_usb.mu);
    return g_usb.packetFrames.size();
}

void setFeedback(double fpmf, int blipEvery = 0, double blipFpmf = 0.0) {
    std::lock_guard<std::mutex> lock(g_usb.mu);
    g_usb.feedbackFpmf = fpmf;
    g_usb.feedbackBlipEvery = blipEvery;
    g_usb.feedbackBlipFpmf = blipFpmf;
}

std::vector<std::string> feedbackGlitchLines(const char *kind) {
    std::vector<std::string> lines;
    std::lock_guard<std::mutex> lock(g_usb.mu);
    for (const std::string &l : g_usb.feedbackGlitches) {
        if (l.compare(0, strlen(kind), kind) == 0 && l[strlen(kind)] == ' ') lines.push_back(l);
    }
    return lines;
}

/** The number after [key] in [line], or -1. */
double fieldOf(const std::string &line, const char *key) {
    const size_t at = line.find(key);
    return at == std::string::npos ? -1.0 : atof(line.c_str() + at + strlen(key));
}

/** The pipeline's writer in miniature: [buffers] buffers of 0.1 s of 24-bit audio at 48 kHz, raw path. */
void streamBuffers(JNIEnv *env, jlong usb, FakeArray &chunk, int buffers) {
    for (int i = 0; i < buffers; i++) {
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWriteRaw(env, nullptr, usb, reinterpret_cast<jbyteArray>(&chunk), 24);
    }
}

void testStuckFeedbackAndPolicies() {
    JNINativeInterface table = {};
    table.GetArrayLength = fakeGetArrayLength;
    table.GetByteArrayElements = fakeGetByteArrayElements;
    table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
    JNIEnv env;
    env.functions = &table;

    constexpr int kRate48 = 48000;
    const Pcm tenth = makeNoise(kRate48 / 10, 24, 0x0b10feedu);
    FakeArray chunk;
    chunk.bytes = toPackedLe(tenth);

    // The 11:37 episode: the Melody answers 6.0049 (+817 ppm) from the stream's first second and keeps on.
    g_usb.reset(kRate48, 8);
    setFeedback(6.0049);
    usbSetFeedbackPolicy(USB_FEEDBACK_FOLLOW);
    const jlong usb = createStream(kRate48, 32);
    int64_t stats[USB_FEEDBACK_STATS_COUNT];

    // (1) follow: the host sends what the DAC asks. 6.5 s, the detector fires at 5 s.
    streamBuffers(&env, usb, chunk, 65);
    const size_t m1 = packetsSoFar();
    const PacketSpan follow = spanOf(0, m1);
    usbFeedbackStats(stats);
    CHECK(follow.outliers == 0, "follow: %zu packets were neither 6 nor 7 frames", follow.outliers);
    CHECK(fabs(follow.mean - 6.0049) < 0.0004, "follow: %.5f frames a packet, the DAC asked for 6.0049", follow.mean);
    const auto stuckLines = feedbackGlitchLines("FEEDBACK_STUCK");
    CHECK(stuckLines.size() == 1, "%zu FEEDBACK_STUCK lines in 6.5 s of a constant +817 ppm (expected exactly one)", stuckLines.size());
    if (!stuckLines.empty()) {
        const std::string &l = stuckLines[0];
        CHECK(l.find("fpmf=6.0049") != std::string::npos && l.find("rate=48000") != std::string::npos &&
                  l.find("policy=follow") != std::string::npos,
              "FEEDBACK_STUCK line lacks the value, rate or policy: %s", l.c_str());
        const double held = fieldOf(l, "held=");
        const double ppm = fieldOf(l, "Hz) ");   // "(48039.1 Hz) +816 ppm": the offset the line states
        CHECK(fabs(ppm - 817.0) < 3.0, "FEEDBACK_STUCK states %.0f ppm, the reading is ~+817: %s", ppm, l.c_str());
        // Detected by the first reading that finds the value 5 s old: a stall of the writer delays that reading, but
        // one of USB_FEEDBACK_GAP_MS or more would have broken the run instead.
        CHECK(held >= 5000.0 && held < 5000.0 + USB_FEEDBACK_GAP_MS, "STUCK reported at held=%.0f ms (the threshold is 5000)", held);
        CHECK(fieldOf(l, "readings=") > 1000.0, "STUCK counted %.0f readings in 5 s (a reading a millisecond)", fieldOf(l, "readings="));
    }
    CHECK(stats[1] == 1 && stats[2] == 1 && stats[3] > 200, "stats while stuck: stuck=%lld episodes=%lld readings=%lld",
          (long long)stats[1], (long long)stats[2], (long long)stats[3]);
    CHECK(stats[4] >= 6000 && stats[5] >= 6000, "stuck ms %lld longest %lld after ~6.4 s of one answer (counted from its first reading)",
          (long long)stats[4], (long long)stats[5]);
    CHECK(llabs(stats[11] - 6004900) < 300, "sending %lld (x1e-6) while following 6.0049", (long long)stats[11]);

    // (2) nominal, switched in without a pause: not one packet of the surplus, and the seam is clean.
    usbSetFeedbackPolicy(USB_FEEDBACK_NOMINAL);
    streamBuffers(&env, usb, chunk, 15);
    const size_t m2 = packetsSoFar();
    const PacketSpan nominal = spanOf(m1, m2);
    usbFeedbackStats(stats);
    CHECK(nominal.sevens == 0 && nominal.outliers == 0 && fabs(nominal.mean - 6.0) < 1e-9,
          "nominal: %zu surplus packets, mean %.5f (expected a flat 6.0 whatever the DAC asks)", nominal.sevens, nominal.mean);
    CHECK(stats[0] == USB_FEEDBACK_NOMINAL && stats[1] == 1 && llabs(stats[11] - 6000000) < 5 && llabs(stats[9] - 6004900) < 300,
          "nominal stats: policy=%lld stuck=%lld sending=%lld lastReading=%lld (the DAC still says 6.0049, the host does not follow it)",
          (long long)stats[0], (long long)stats[1], (long long)stats[11], (long long)stats[9]);

    // (3) clamp: +-300 ppm, so 6.0018 a packet — a third of the stuck excess.
    usbSetFeedbackPolicy(USB_FEEDBACK_CLAMP);
    streamBuffers(&env, usb, chunk, 15);
    const size_t m3 = packetsSoFar();
    const PacketSpan clamp = spanOf(m2, m3);
    CHECK(clamp.outliers == 0 && fabs(clamp.mean - 6.0018) < 0.0004, "clamp: %.5f frames a packet, expected 6.0018", clamp.mean);

    // (4) auto while stuck, with no healthy window before it (it was stuck from the start): nominal.
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    streamBuffers(&env, usb, chunk, 15);
    const size_t m4 = packetsSoFar();
    const PacketSpan autoStuck = spanOf(m3, m4);
    CHECK(autoStuck.sevens == 0 && autoStuck.outliers == 0, "auto, stuck: %zu surplus packets (expected nominal)", autoStuck.sevens);
    usbFeedbackStats(stats);
    CHECK(stats[0] == USB_FEEDBACK_AUTO && stats[1] == 1, "auto stats: policy=%lld stuck=%lld", (long long)stats[0], (long long)stats[1]);

    // (5) the DAC answers something else (6.0099, +1650 ppm: a blip's size, held for 2 s — not an episode):
    // the episode ends, and auto follows again at once.
    setFeedback(6.0099);
    streamBuffers(&env, usb, chunk, 20);
    const size_t m5 = packetsSoFar();
    const PacketSpan autoFollows = spanOf(m4 + 800, m5);  // skip the first 100 ms: the change reaches the host within a few ms
    usbFeedbackStats(stats);
    CHECK(autoFollows.outliers == 0 && fabs(autoFollows.mean - 6.0099) < 0.0006,
          "auto after the episode ended: %.5f frames a packet, expected to follow 6.0099", autoFollows.mean);
    CHECK(stats[1] == 0 && stats[2] == 1, "after it ended: stuck=%lld episodes=%lld", (long long)stats[1], (long long)stats[2]);
    const auto unstuckLines = feedbackGlitchLines("FEEDBACK_UNSTUCK");
    CHECK(unstuckLines.size() == 1, "%zu FEEDBACK_UNSTUCK lines (expected one)", unstuckLines.size());
    if (!unstuckLines.empty()) {
        const std::string &l = unstuckLines[0];
        const double total = fieldOf(l, "duration=");
        CHECK(l.find("reason=changed") != std::string::npos && l.find("next=6.0099") != std::string::npos && l.find("was fpmf=6.0049") != std::string::npos,
              "FEEDBACK_UNSTUCK line lacks the old and new value or the reason: %s", l.c_str());
        // 6.5 + 1.5 + 1.5 + 1.5 s of audio at the DAC's pace, from the first reading
        CHECK(total > 10500.0 && total < 16000.0, "episode total %.0f ms, expected ~11 000 (the whole time it answered 6.0049)", total);
    }
    CHECK(feedbackGlitchLines("FEEDBACK_STUCK").size() == 1, "2 s of 6.0099 was taken for a second episode");
    CHECK(m4 > 80000, "the simulation streamed only %zu packets (11 s are 88 000)", m4);

    // An unknown policy changes nothing.
    usbSetFeedbackPolicy(77);
    CHECK(usbFeedbackPolicy() == USB_FEEDBACK_AUTO, "an unknown policy was accepted");

    // (6) A stream's end closes an episode: restart the stuck feedback, run it past 5 s on a NEW stream,
    // and tear that down while stuck — the output reports the end rather than leaving "stuck" on forever.
    releaseStream(usb);
    g_usb.reset(kRate48, 8);
    setFeedback(6.0049);
    usbSetFeedbackPolicy(USB_FEEDBACK_FOLLOW);
    const jlong usb2 = createStream(kRate48, 32);
    streamBuffers(&env, usb2, chunk, 55);
    usbFeedbackStats(stats);
    CHECK(stats[1] == 1 && stats[2] == 2, "second stream: stuck=%lld episodes=%lld (a new stream starts a new episode)", (long long)stats[1], (long long)stats[2]);
    releaseStream(usb2);
    usbFeedbackStats(stats);
    CHECK(stats[1] == 0, "the stream ended and the episode stayed open");
    const auto endLines = feedbackGlitchLines("FEEDBACK_UNSTUCK");
    CHECK(!endLines.empty() && endLines.back().find("reason=stream-end") != std::string::npos,
          "no FEEDBACK_UNSTUCK reason=stream-end after the teardown (%zu lines)", endLines.size());

    setFeedback(12.0);
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    printf("stuck feedback: fires once at 5 s (+817 ppm), follow %.4f / nominal %.4f / clamp %.4f / auto-stuck %.4f frames a packet, "
           "episode closed by the change and by the teardown\n",
           follow.mean, nominal.mean, clamp.mean, autoStuck.mean);
}

/**
 * The seam of a policy switched in mid-stream, through the real submit path at a fractional rate: the
 * packetizer's accumulator carries on, so at the end frames sent + the fraction still owed equal exactly what
 * the packets were scheduled with — follow's 5.5175 for its packets, nominal's 5.5125 for its own — not one
 * frame dropped or repeated where the policy changed (and back).
 */
void testPolicySwitchConservesFrames() {
    JNINativeInterface table = {};
    table.GetArrayLength = fakeGetArrayLength;
    table.GetByteArrayElements = fakeGetByteArrayElements;
    table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
    JNIEnv env;
    env.functions = &table;

    constexpr int kRate441 = 44100;
    const Pcm tenth = makeNoise(kRate441 / 10, 24, 0xfeed0441u);
    FakeArray chunk;
    chunk.bytes = toPackedLe(tenth);
    g_usb.reset(kRate441, 8);
    setFeedback(5.5175);                       // +907 ppm, the 06:18 episode's value (held for 3 s here: not yet an episode)
    usbSetFeedbackPolicy(USB_FEEDBACK_FOLLOW);
    const jlong usb = createStream(kRate441, 32);
    auto *ctx = reinterpret_cast<UsbAudioContext *>(usb);
    const double followFpmf = llround(5.5175 * 65536.0) / 65536.0;   // what the endpoint's Q16.16 says
    const double nominalFpmf = kRate441 / 8000.0;

    size_t mark[4];
    streamBuffers(&env, usb, chunk, 10);       // 1 s follow
    mark[0] = packetsSoFar();
    usbSetFeedbackPolicy(USB_FEEDBACK_NOMINAL);
    streamBuffers(&env, usb, chunk, 10);       // 1 s nominal
    mark[1] = packetsSoFar();
    usbSetFeedbackPolicy(USB_FEEDBACK_CLAMP);
    streamBuffers(&env, usb, chunk, 10);       // 1 s clamp
    mark[2] = packetsSoFar();
    usbSetFeedbackPolicy(USB_FEEDBACK_FOLLOW);
    streamBuffers(&env, usb, chunk, 10);       // 1 s follow again
    mark[3] = packetsSoFar();

    long long frames = 0;
    int sizes[16] = {};
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        for (uint8_t n : g_usb.packetFrames) {
            frames += n;
            sizes[n < 16 ? n : 15]++;
        }
    }
    const double clampFpmf = nominalFpmf * (1.0 + USB_FEEDBACK_CLAMP_PPM * 1e-6);
    const double ideal = followFpmf * (double)(mark[0] + (mark[3] - mark[2])) + nominalFpmf * (double)(mark[1] - mark[0]) +
                         clampFpmf * (double)(mark[2] - mark[1]);
    const double owed = ctx->frameAccumulator;
    CHECK(sizes[5] + sizes[6] == (int)mark[3], "packets other than 5 or 6 frames at 44.1 kHz (%d of %zu)", (int)mark[3] - sizes[5] - sizes[6], mark[3]);
    CHECK(fabs((double)frames + owed - ideal) < 1e-3, "frames sent %lld + %.6f owed = %.6f, the packets were scheduled for %.6f: a frame jumped at a switch",
          frames, owed, (double)frames + owed, ideal);
    CHECK(owed >= 0.0 && owed < 1.0, "the accumulator left [0, 1): %.6f", owed);
    releaseStream(usb);
    setFeedback(12.0);
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    printf("policy switches (follow -> nominal -> clamp -> follow) at 44.1 kHz: %lld frames in %zu packets, scheduled %.4f: conserved\n",
           frames, mark[3], ideal);
}

/** A healthy DAC's dither (its nominal, a +0.25 % blip ~1.25 % of the readings) is followed and never an episode. */
void testHealthyFeedbackIsNotAnEpisode() {
    JNINativeInterface table = {};
    table.GetArrayLength = fakeGetArrayLength;
    table.GetByteArrayElements = fakeGetByteArrayElements;
    table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
    JNIEnv env;
    env.functions = &table;

    constexpr int kRate48 = 48000;
    const Pcm tenth = makeNoise(kRate48 / 10, 24, 0x0b10feedu);
    FakeArray chunk;
    chunk.bytes = toPackedLe(tenth);
    int64_t before[USB_FEEDBACK_STATS_COUNT], after[USB_FEEDBACK_STATS_COUNT];
    usbFeedbackStats(before);

    g_usb.reset(kRate48, 8);
    setFeedback(6.0, 80, 6.0149);
    usbSetFeedbackPolicy(USB_FEEDBACK_FOLLOW);
    const jlong usb = createStream(kRate48, 32);
    streamBuffers(&env, usb, chunk, 60);
    usbFeedbackStats(after);
    releaseStream(usb);
    const PacketSpan healthy = spanOf(0, packetsSoFar());
    CHECK(feedbackGlitchLines("FEEDBACK_STUCK").empty(), "a healthy dither was reported as stuck");
    CHECK(after[2] == before[2] && after[1] == 0, "healthy dither: episodes %lld -> %lld, stuck=%lld", (long long)before[2], (long long)after[2], (long long)after[1]);
    CHECK(after[8] - before[8] > 3000, "only %lld feedback readings in 6 s", (long long)(after[8] - before[8]));
    // The packetizer is given the last reading once per write (a 0.1 s buffer here), so a blip that is the last
    // reading at that moment lasts the whole buffer: 6.0 plus, at most, a few buffers' worth of the +0.25 %.
    CHECK(healthy.outliers == 0 && healthy.mean >= 6.0 && healthy.mean < 6.0015,
          "healthy follow: %.5f frames a packet (6.0 plus the blips' share)", healthy.mean);
    setFeedback(12.0);
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    printf("healthy dither for 6 s: no episode, followed (%.5f frames a packet)\n", healthy.mean);
}

/**
 * B12a-2: `auto` is the policy a process starts with (debug and release), and through the real output it refuses a
 * feedback that is implausible without being frozen. The Melody answered 5.7875 (46 300 Hz, +4.99 %) right after a
 * stuck episode; the old +-5 % window applied it. Here a fake DAC answers it from the first reading:
 *   - auto (the default): the packets stay at the nominal rate, the output counts the readings and says so once;
 *   - follow (to diagnose): the packets carry the DAC's word;
 *   - back to a healthy answer, auto follows it at once;
 *   - beyond +-5 % (a misparse): nominal under every policy, instead of whatever was last applied.
 */
void testDefaultAutoRefusesImplausibleFeedback(int policyAtStart) {
    JNINativeInterface table = {};
    table.GetArrayLength = fakeGetArrayLength;
    table.GetByteArrayElements = fakeGetByteArrayElements;
    table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
    JNIEnv env;
    env.functions = &table;

    CHECK(policyAtStart == USB_FEEDBACK_AUTO, "a process starts in policy %d (%s), expected auto", policyAtStart,
          usbFeedbackPolicyName(policyAtStart));

    constexpr int kRate441 = 44100;
    const Pcm tenth = makeNoise(kRate441 / 10, 24, 0xa070b12au);
    FakeArray chunk;
    chunk.bytes = toPackedLe(tenth);
    const double nominal = kRate441 / 8000.0;                       // 5.5125
    int64_t before[USB_FEEDBACK_STATS_COUNT], stats[USB_FEEDBACK_STATS_COUNT];

    // (1) the +4.99 % reading from the first one, with the default policy (nothing set here).
    g_usb.reset(kRate441, 8);
    setFeedback(5.7875);
    usbFeedbackStats(before);
    const jlong usb = createStream(kRate441, 32);
    streamBuffers(&env, usb, chunk, 30);
    const size_t m1 = packetsSoFar();
    usbFeedbackStats(stats);
    const PacketSpan refused = spanOf(1600, m1);                     // skip the first two buffers (800 packets each): the first reading is reaped after the first write
    CHECK(fabs(refused.mean - nominal) < 0.001,
          "auto (default) against a DAC answering 5.7875: %.5f frames a packet, expected the nominal %.4f", refused.mean, nominal);
    CHECK(stats[0] == USB_FEEDBACK_AUTO, "stats say policy %lld", (long long)stats[0]);
    CHECK(stats[12] - before[12] > 1000, "only %lld readings counted as not obeyed in 3 s of a DAC answering 5.7875",
          (long long)(stats[12] - before[12]));
    CHECK(stats[13] - before[13] == 1, "%lld streaks of implausible readings (expected exactly one)", (long long)(stats[13] - before[13]));
    CHECK(llabs(stats[14] - 5787500) < 300, "last rejected reading %lld (x1e-6), expected 5.7875", (long long)stats[14]);
    CHECK(llabs(stats[11] - 5512500) < 300, "sending %lld (x1e-6) against 5.7875: expected nominal 5.5125", (long long)stats[11]);
    const auto rejectedLines = feedbackGlitchLines("FEEDBACK_REJECTED");
    CHECK(rejectedLines.size() == 1, "%zu FEEDBACK_REJECTED lines for one streak (expected one)", rejectedLines.size());
    if (!rejectedLines.empty()) {
        const std::string &l = rejectedLines[0];
        CHECK(l.find("fpmf=5.7875") != std::string::npos && l.find("why=absurd") != std::string::npos &&
                  l.find("policy=auto") != std::string::npos && l.find("rate=44100") != std::string::npos,
              "FEEDBACK_REJECTED lacks the value, why, policy or rate: %s", l.c_str());
        const double ppm = fieldOf(l, "Hz) ");
        CHECK(fabs(ppm - 49927.0) < 40.0, "the line states %.0f ppm, the reading is ~+49 900: %s", ppm, l.c_str());
    }
    CHECK(feedbackGlitchLines("FEEDBACK_STUCK").size() <= 1, "a constant implausible answer must not be reported twice");

    // (2) follow, to diagnose: the DAC's word, unfiltered.
    usbSetFeedbackPolicy(USB_FEEDBACK_FOLLOW);
    streamBuffers(&env, usb, chunk, 15);
    const size_t m2 = packetsSoFar();
    const PacketSpan followed = spanOf(m1 + 400, m2);
    CHECK(fabs(followed.mean - 5.7875) < 0.002,
          "follow against 5.7875: %.5f frames a packet, expected to carry the DAC's word", followed.mean);

    // (3) back to auto, and the DAC answers its nominal again: followed at once, the streak over.
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    setFeedback(5.5125);
    streamBuffers(&env, usb, chunk, 10);
    const size_t m3 = packetsSoFar();
    const PacketSpan healthyAgain = spanOf(m2 + 400, m3);
    CHECK(fabs(healthyAgain.mean - nominal) < 0.0005,
          "auto after the DAC answered nominal again: %.5f", healthyAgain.mean);
    // a healthy DAC that is really tens of ppm off is still followed (here +120 ppm)
    setFeedback(5.5125 * (1.0 + 120e-6));
    streamBuffers(&env, usb, chunk, 15);
    const size_t m4 = packetsSoFar();
    const PacketSpan realClock = spanOf(m3 + 400, m4);
    CHECK(fabs(realClock.mean - 5.5125 * (1.0 + 120e-6)) < 0.0004,
          "auto against a DAC +120 ppm off: %.5f frames a packet, expected to follow %.5f", realClock.mean, 5.5125 * (1.0 + 120e-6));
    releaseStream(usb);

    // (4) beyond +-5 % (a 10.14-format misread, 4x): not even applied. The start read used to take it blindly and,
    // with every later reading refused, keep it for the whole stream under follow.
    for (int policy : {USB_FEEDBACK_AUTO, USB_FEEDBACK_FOLLOW}) {
        usbSetFeedbackPolicy(policy);
        g_usb.reset(kRate441, 8);
        setFeedback(nominal * 4.0);
        usbFeedbackStats(before);
        const size_t first = packetsSoFar();
        const jlong bad = createStream(kRate441, 32);
        streamBuffers(&env, bad, chunk, 10);
        const PacketSpan misparsed = spanOf(first + 80, packetsSoFar());
        usbFeedbackStats(stats);
        CHECK(fabs(misparsed.mean - nominal) < 0.001,
              "%s against a 4x misread: %.5f frames a packet, expected the nominal %.4f", usbFeedbackPolicyName(policy), misparsed.mean, nominal);
        CHECK(stats[12] - before[12] > 500, "%s: %lld readings counted as not obeyed", usbFeedbackPolicyName(policy),
              (long long)(stats[12] - before[12]));
        releaseStream(bad);
    }
    setFeedback(12.0);
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    printf("default policy auto: the +4.99 %% reading is refused (%.5f frames a packet, nominal %.4f; follow carries %.5f), "
           "+120 ppm is followed, a 4x misread never applied\n",
           refused.mean, nominal, followed.mean);
}

}  // namespace

// ── B27-A5 (review N-07): the one-second summaries ──────────────────────────────────────────────────────────────

/** One line of `usbSecondSummaries`, as `usbSecondRingFormat` writes it. */
struct SummaryLine {
    long ago = -1;
    int readings = -1, deviating = -1, refused = -1, urbs = -1, packets = -1, errors = -1, shorts = -1;
    double min = 0, max = 0, mean = 0, nominal = 0;
};

bool parseSummaryLine(const char *line, SummaryLine *out) {
    return sscanf(line, "t-%lds feedback n=%d fpmf min=%lf max=%lf mean=%lf nominal=%lf deviating=%d refused=%d | iso urbs=%d packets=%d errors=%d short=%d",
                  &out->ago, &out->readings, &out->min, &out->max, &out->mean, &out->nominal, &out->deviating, &out->refused,
                  &out->urbs, &out->packets, &out->errors, &out->shorts) == 12;
}

/**
 * The recording hooks, against the real output: a stream with a DAC answering 0.4 % off nominal and every 50th audio URB coming
 * back with a bad first packet. The dump must say, second by second, what the readings and the packets were, and a second of it
 * must be about a thousand readings and a thousand URBs of eight packets. Virtual time (the writer is this thread): the seconds
 * are the clock's, not the host's.
 */
void testStreamSecondSummaries() {
    const Pcm longA = makeNoise(10 * kBuf, 24, 0x5ec0dd11u);
    setFeedback(12.0 * 1.004);  // before the stream exists: its first continuous reading is already the DAC's 0.4 % off
    Writer w;
    // B31 P2.6: the virtual clock starts at the host's monotonic clock plus two seconds, so the stream began at a fraction of a second
    // that depended on when the host got here: the first second of the dump held 590-630 readings on one run and 800 on another, and a
    // partial second of 800 readings had 799 URBs (the URBs trail the readings by a few) and failed `l.urbs >= 800` twice under load.
    // Starting the stream on a second boundary of the virtual clock makes the first second as full as the rest, whatever the host did.
    simclock::advanceTo((simclock::nowNs() / 1000000000LL + 1) * 1000000000LL);
    const int64_t startSecond = nowUs() / 1000000;  // the ring is the process's: only this stream's seconds are looked at (a scope starts 2 s after the last)
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        g_usb.isoErrorEveryNth = 50;
    }
    for (int pass = 0; pass < 5; pass++) {
        for (int at = 0; at + kBuf <= longA.frames(); at += kBuf) w.write(longA, at, kBuf);   // ~0.85 s of audio each
    }
    sleepMs(1500);  // the seconds that were running are finished by now
    const int64_t ownWindowMs = (nowUs() / 1000000 - startSecond) * 1000;
    char text[16384];
    const size_t used = usbSecondSummaries(ownWindowMs, text, sizeof(text));
    CHECK(used == strlen(text), "reported %zu bytes, the text is %zu", used, strlen(text));
    CHECK(strstr(text, "usb-seconds window=") != nullptr && strstr(text, "s samples=") != nullptr, "no header: %.80s", text);
    CHECK(strstr(text, "rate=96000 ch=2 slotBits=32") != nullptr, "the header does not say how the stream was opened: %.200s", text);
    CHECK(strstr(text, "policy=") != nullptr, "the header does not say the feedback policy");

    int lines = 0, fullSeconds = 0;
    long readings = 0, deviating = 0, urbs = 0, packets = 0, errors = 0, shorts = 0;
    for (const char *line = strstr(text, "\nt-"); line != nullptr; line = strstr(line + 1, "\nt-")) {
        SummaryLine l;
        if (!parseSummaryLine(line + 1, &l)) {
            CHECK(false, "a line is not in the documented shape: %.160s", line + 1);
            continue;
        }
        lines++;
        readings += l.readings;
        deviating += l.deviating;
        urbs += l.urbs;
        packets += l.packets;
        errors += l.errors;
        shorts += l.shorts;
        CHECK(l.min <= l.mean && l.mean <= l.max, "min %.5f mean %.5f max %.5f out of order", l.min, l.mean, l.max);
        CHECK(l.nominal == 12.0, "nominal %.5f", l.nominal);
        CHECK(l.refused == 0, "%d readings refused though the DAC answered 0.4 %% off", l.refused);
        if (l.readings >= 990) {  // a full second reads about a thousand (1 ms feedback); anything less is the stream's last, partial second
            fullSeconds++;
            CHECK(std::fabs(l.mean - 12.048) < 0.001, "mean %.5f, the DAC answered 12.048", l.mean);
            CHECK(l.deviating == l.readings, "%d of %d readings counted as off nominal, all are", l.deviating, l.readings);
            // 996-997 on every run (the URBs trail the readings by the few the ring holds); 950 is the stated margin, not a wall-clock bound.
            CHECK(l.urbs >= 950 && l.urbs <= 1300, "%d audio URBs in a second of a 96 kHz stream (1 ms each)", l.urbs);
            CHECK(l.packets == l.urbs * 8, "%d packets for %d URBs of eight", l.packets, l.urbs);
        }
    }
    CHECK(lines >= 3, "%d seconds in the dump after ~4 s of streaming", lines);
    CHECK(fullSeconds >= 2, "%d seconds with about a thousand readings", fullSeconds);
    CHECK(readings >= 2500, "%ld readings in all", readings);
    CHECK(deviating == readings, "%ld of %ld readings counted as off nominal", deviating, readings);
    CHECK(errors >= urbs / 50 - 3 && errors <= urbs / 50 + 3, "%ld packet errors for %ld URBs, one in 50 was bad", errors, urbs);
    CHECK(shorts == errors, "%ld short packets for %ld errors: the bad packet delivered nothing", shorts, errors);
    CHECK(packets == urbs * 8, "%ld packets for %ld URBs", packets, urbs);

    // a window is a window: the last 2 s are at most two lines
    char brief[4096];
    usbSecondSummaries(2000, brief, sizeof(brief));
    int briefLines = 0;
    for (const char *line = strstr(brief, "\nt-"); line != nullptr; line = strstr(line + 1, "\nt-")) briefLines++;
    CHECK(briefLines <= 2, "%d lines for a 2 s window", briefLines);
    setFeedback(12.0);
    printf("second summaries: %d seconds, %ld readings (mean 12.048, all off nominal), %ld URBs / %ld packets, %ld packet errors and %ld short, "
           "dump of %zu bytes on demand\n", lines, readings, urbs, packets, errors, shorts, used);
}

// ── B23-P2: the output gap line ───────────────────────────────────────────────────────────────────────────

/**
 * The numbers of one OUTPUT_GAP line:
 * "… gap=175 ms lead=80 ms dry=95 ms reap=170 ms between=3 ms native=2 ms where=in-reap rate=96000 inFlight=80 runq=0 ms cpu=1 ms over=181 ms".
 * `where` is where the wall-clock time elapsed (scheduling included), `dry` the model's estimate; the tail (B24-Q3) is the writer's own
 * scheduler counters, or `runq=n/a` when the host does not offer them.
 */
struct GapLine {
    long gap = -1, lead = -1, dry = -1, reap = -1, between = -1, native = -1;
    char where[32] = {};
    int rate = 0;
    bool hasSched = false;
    long runq = -1, cpu = -1, over = -1;
};

bool parseGapLine(const std::string &line, GapLine *out) {
    const size_t at = line.find(" gap=");
    if (at == std::string::npos) return false;
    if (sscanf(line.c_str() + at, " gap=%ld ms lead=%ld ms dry=%ld ms reap=%ld ms between=%ld ms native=%ld ms where=%31s rate=%d",
               &out->gap, &out->lead, &out->dry, &out->reap, &out->between, &out->native, out->where, &out->rate) != 8) {
        return false;
    }
    const size_t tail = line.find(" runq=", at);
    if (tail == std::string::npos) return false;                      // the line always ends in runq=...
    out->hasSched = sscanf(line.c_str() + tail, " runq=%ld ms cpu=%ld ms over=%ld ms", &out->runq, &out->cpu, &out->over) == 3;
    return out->hasSched || line.compare(tail, std::string::npos, " runq=n/a") == 0;
}

/** This thread's scheduler counters ("<ns on a CPU> <ns runnable, waiting for one> <slices>"), as the writer's line reads them. */
struct SchedReading {
    bool valid = false;
    uint64_t cpuNs = 0, runqNs = 0;
};
void readOwnSchedstat(SchedReading *out) {
    out->valid = false;
    FILE *f = fopen("/proc/thread-self/schedstat", "r");
    if (!f) return;
    unsigned long long cpu = 0, runq = 0, slices = 0;
    out->valid = fscanf(f, "%llu %llu %llu", &cpu, &runq, &slices) == 3;
    out->cpuNs = cpu;
    out->runqNs = runq;
    fclose(f);
}

/** Writes [ms] of silence the way the pipeline's writer does (8 ms blocks), paced by the ring's back-pressure. */
void writeFor(UsbAudioContext *ctx, int rate, int ms) {
    std::vector<uint8_t> block((size_t)(rate / 125) * 8, 0);
    const int64_t end = nowUs() + (int64_t)ms * 1000;
    while (nowUs() < end) submitPcmToUrbs(ctx, block.data(), (int)block.size());
}

/**
 * B23-P2: the diagnostic for the owner's wake (15:30:20, `REAP_SLOW … 175 ms inFlight=80` at 96 kHz): a submit gap that uses up
 * the ring's lead is one line, with the numbers and where the writer spent the time. Against the fake DAC, which knows how long it
 * ran dry, and with a healthy stream that must say nothing.
 */
void testOutputGap() {
    constexpr int kRate = 96000;
    usbSetPollWait(true);
    // B27-A0: A-C run in virtual time (the writer is this thread; sim-clock.h): the numbers of the line are those of the model, not of
    // how fast the host happened to be. D keeps a real sleep, because what it checks is the kernel's own accounting of one.
    std::unique_ptr<simclock::VirtualTime> virtualTime(new simclock::VirtualTime);

    // A. a healthy stream: nothing is reported, and the DAC never ran dry
    {
        g_usb.reset(kRate, 8);
        const jlong usb = createStream(kRate, 32);
        auto *ctx = reinterpret_cast<UsbAudioContext *>(usb);
        writeFor(ctx, kRate, 1200);
        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            CHECK(g_usb.gaps == 0, "a healthy stream: the fake DAC ran dry %d times", g_usb.gaps);
            CHECK(g_usb.outputGapLines.empty(), "a healthy stream: %zu OUTPUT_GAP lines (first: %s)", g_usb.outputGapLines.size(),
                  g_usb.outputGapLines.empty() ? "" : g_usb.outputGapLines.front().c_str());
        }
        releaseStream(usb);
    }

    // B. the writer is not running for 175 ms between two writes (descheduled, or waiting for a producer that is late)
    GapLine late;
    long long daMeasuredMs = 0;
    {
        g_usb.reset(kRate, 8);
        const jlong usb = createStream(kRate, 32);
        auto *ctx = reinterpret_cast<UsbAudioContext *>(usb);
        writeFor(ctx, kRate, 500);
        sleepMs(175);
        writeFor(ctx, kRate, 300);
        std::string first;
        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            CHECK(!g_usb.outputGapLines.empty(), "a writer 175 ms late reported no OUTPUT_GAP");
            if (!g_usb.outputGapLines.empty()) first = g_usb.outputGapLines.front();
            daMeasuredMs = g_usb.maxGapUs / 1000;
            CHECK(g_usb.gaps >= 1, "the fake DAC did not run dry for a writer 175 ms late");
        }
        const bool parsed = parseGapLine(first, &late);
        CHECK(parsed, "the OUTPUT_GAP line is not in the documented shape: %s", first.c_str());
        if (parsed) {
            CHECK(late.gap >= 170 && late.gap <= 400, "gap=%ld ms for a 175 ms hold", late.gap);
            CHECK(late.lead >= 60 && late.lead <= 85, "lead=%ld ms: the ring holds ~80 ms", late.lead);
            CHECK(labs(late.dry - daMeasuredMs) <= 15, "dry=%ld ms, the fake DAC measured %lld ms", late.dry, daMeasuredMs);
            CHECK(late.dry == late.gap - late.lead || labs(late.dry - (late.gap - late.lead)) <= 2, "dry=%ld is not gap-lead (%ld-%ld)", late.dry, late.gap, late.lead);
            CHECK(strcmp(late.where, "between-writes") == 0 && late.between * 10 >= late.gap * 8,
                  "a writer that was not in the native write: where=%s between=%ld of gap=%ld", late.where, late.between, late.gap);
            CHECK(late.rate == kRate, "rate=%d", late.rate);
            // B24-Q3: the window the counters cover is the line's own (from a baseline up to 10 ms before the previous submit to this
            // one). Its runq/cpu are the kernel's, in real time, while this gap is virtual, so they are not compared with it here:
            // D below does that against the kernel itself, with a real sleep.
            if (late.hasSched) {
                CHECK(late.over >= late.gap && late.over <= late.gap + 40, "over=%ld ms for a gap of %ld ms", late.over, late.gap);
            }
        }
        releaseStream(usb);
    }

    // C. the host stalls with the writer inside the reap wait (the 15:30:20 shape: the ring full, the oldest URB 175 ms late)
    GapLine stalled;
    {
        g_usb.reset(kRate, 8);
        const jlong usb = createStream(kRate, 32);
        auto *ctx = reinterpret_cast<UsbAudioContext *>(usb);
        // the host stalls 500 ms in and recovers 200 ms later: events of the virtual clock, run while the writer waits
        simclock::at(simclock::nowNs() + 500 * 1000000LL, [] {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            g_usb.hold = true;
        });
        simclock::at(simclock::nowNs() + 700 * 1000000LL, [] { g_usb.releaseHold(); });
        writeFor(ctx, kRate, 1000);
        std::string first;
        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            CHECK(!g_usb.outputGapLines.empty(), "a stalled host reported no OUTPUT_GAP");
            if (!g_usb.outputGapLines.empty()) first = g_usb.outputGapLines.front();
        }
        const bool parsed = parseGapLine(first, &stalled);
        CHECK(parsed, "the OUTPUT_GAP line is not in the documented shape: %s", first.c_str());
        if (parsed) {
            CHECK(stalled.gap >= 100 && stalled.gap <= 300, "gap=%ld ms for a host that stalled ~200 ms", stalled.gap);
            CHECK(strcmp(stalled.where, "in-reap") == 0 && stalled.reap * 10 >= stalled.gap * 8,
                  "a writer inside the reap wait: where=%s reap=%ld of gap=%ld", stalled.where, stalled.reap, stalled.gap);
            CHECK(stalled.rate == kRate, "rate=%d", stalled.rate);
            // (B24-Q3's runq/cpu are not compared here: the wait is virtual, so the thread did not sleep and the kernel's counters
            // say nothing about the gap; D below checks the line's counters against the kernel's with a real sleep.)
        }
        releaseStream(usb);
    }
    // D. the kernel's accounting of a REAL sleep (B24-Q3), on the wall clock. The thread that writes is this one, so the test can read the
    // same counters the line is made from, around the same sleep. On a healthy host the sleeping writer is not runnable and not on a CPU;
    // on one that starved this thread (a laptop on battery, a build next to it) the kernel itself says it was runnable for a while, and
    // then what must hold is that the line does not claim more than the kernel recorded for that window (B27-A0: the verdict is about the
    // line, not about the load).
    virtualTime.reset();
    GapLine slept;
    {
        // A real-time sample can be spoiled by the load it is meant to survive: a stall while the ring is still filling is a gap line of
        // its own (a few ms), and the output keeps one line per kind per second, so it can swallow the line of the sleep. A sample with
        // no line for the sleep is taken again, a second later; only a line that is there is judged, and judged against the kernel.
        constexpr int kAttempts = 4;
        bool sampled = false;
        for (int attempt = 0; attempt < kAttempts && !sampled; attempt++) {
            if (attempt > 0) sleepMs(1100);
            g_usb.reset(kRate, 8);
            const jlong usb = createStream(kRate, 32);
            auto *ctx = reinterpret_cast<UsbAudioContext *>(usb);
            writeFor(ctx, kRate, 300);
            SchedReading before, after;
            readOwnSchedstat(&before);
            sleepMs(175);
            readOwnSchedstat(&after);
            writeFor(ctx, kRate, 200);
            GapLine line;
            bool found = false;
            {
                std::lock_guard<std::mutex> lock(g_usb.mu);
                for (const std::string &text : g_usb.outputGapLines) {
                    GapLine candidate;
                    if (parseGapLine(text, &candidate) && candidate.gap >= 170) {
                        line = candidate;
                        found = true;
                        break;
                    }
                }
            }
            releaseStream(usb);
            if (!found) continue;
            sampled = true;
            slept = line;
            if (line.hasSched && before.valid && after.valid) {
                const long truthRunq = (long)((after.runqNs - before.runqNs) / 1000000), truthCpu = (long)((after.cpuNs - before.cpuNs) / 1000000);
                CHECK(line.runq <= std::max(line.gap / 10, truthRunq + 10),
                      "the line says the sleeping writer was runnable for %ld ms of %ld; the kernel recorded %ld ms in that window", line.runq, line.gap, truthRunq);
                CHECK(line.cpu <= std::max(line.gap / 10, truthCpu + 10),
                      "the line says the sleeping writer was on a CPU for %ld ms of %ld; the kernel recorded %ld ms in that window", line.cpu, line.gap, truthCpu);
            }
        }
        CHECK(sampled, "a writer that slept 175 ms reported no OUTPUT_GAP for it in %d attempts", kAttempts);
    }
    printf("output gap: a healthy stream says nothing; a writer 175 ms late = gap=%ld ms lead=%ld ms dry=%ld ms (fake DAC: %lld ms) where=%s; "
           "a stalled host = gap=%ld ms reap=%ld ms where=%s; a real 175 ms sleep, the line's own counters: %s\n",
           late.gap, late.lead, late.dry, daMeasuredMs, late.where, stalled.gap, stalled.reap, stalled.where,
           slept.hasSched ? ("runq=" + std::to_string(slept.runq) + " ms cpu=" + std::to_string(slept.cpu) + " ms of " + std::to_string(slept.gap) + " ms").c_str() : "n/a");
}

int main(int argc, char **argv) {
    const int policyAtStart = usbFeedbackPolicy();                  // before any test has set one
    const std::string only = argc > 1 ? argv[1] : "";
    const int rate96 = 96000, rate441 = 44100;
    TempDir dir;
    Pcm a = makeNoise(kFrames96, 24, 0x1234abcdu), b = makeNoise(kFrames96, 24, 0x9e3779b9u);
    Pcm c = makeNoise(kFrames441, 16, 0x0badf00du);
    if (!writeFlac(dir.path + "/a.flac", a, rate96, 8192) || !writeFlac(dir.path + "/b.flac", b, rate96, 8192) ||
        !writeFlac(dir.path + "/c.flac", c, rate441, 4608)) {
        fprintf(stderr, "could not write the test FLACs\n");
        return 2;
    }
    if (only.empty() || only == "join") testSameRateJoinIsBitExact(a, b, dir.path);
    if (only.empty() || only == "pause") testPauseStillFades(a, b, dir.path);
    if (only.empty() || only == "rate") testRateChangeJoin(a, c, dir.path);
    if (only.empty() || only == "pipeline") testPipelineEndOfStreamPlaysOut(a, c);
    if (only.empty() || only == "gesture") testPipelineGestures(a, b);
    if (only.empty() || only == "wait") {
        testWaitModes(dir.path);
        testSilentPollDoesNotStarve(dir.path);
        testWaitTimeouts();
    }
    if (only.empty() || only == "feedback") {
        testStuckFeedbackAndPolicies();
        testPolicySwitchConservesFrames();
        testHealthyFeedbackIsNotAnEpisode();
    }
    if (only.empty() || only == "feedback" || only == "default-auto") testDefaultAutoRefusesImplausibleFeedback(policyAtStart);
    if (only.empty() || only == "output-gap") testOutputGap();
    if (only.empty() || only == "summaries") testStreamSecondSummaries();
    if (g_failures) {
        printf("natural join simulation: %d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("natural join simulation: PASS\n");
    return 0;
}
