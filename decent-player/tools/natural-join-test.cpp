// Host simulation of the USB writer through the pipeline JNI protocol and a fake usbfs.
// All writer scenarios use tools/sim-clock.h virtual time; no hardware is opened.
#include <jni.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <linux/usbdevice_fs.h>

#include "seek-barrier.h"
#include "sim-clock.h"
#include "usb-audio-output.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <chrono>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <functional>
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
    JNIEnv *, jobject, jint, jint, jint, jint, jint, jint, jint, jint, jint, jint);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(JNIEnv *, jobject, jlong, jint previousRate = 0);
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
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryBegin(JNIEnv *, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryEnd(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeNoteDacRecovery(JNIEnv *, jobject, jlong, jint, jint, jlong);
JNIEXPORT jint JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbResetForRebind(JNIEnv *, jclass, jint);

}

namespace {

constexpr int kFakeFd = 777;
constexpr int kEndpointOut = 0x07;
constexpr int kEndpointFeedback = 0x88;
constexpr int kFakeBInterval = 1;       // the fake DAC's data endpoint: a packet every microframe
constexpr int kFakeFeedbackBytes = 4;   // and a Q16.16 feedback packet

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

/** B41-fix1: what the fake DAC's feedback endpoint answers at a moment, and how the transfer came back. */
struct FeedbackAnswer {
    double fpmf = 0.0;
    int urbStatus = 0;
    int packetStatus = 0;
    int length = 4;
};

/** B41-fix1: one audio URB as the kernel received it: when, how many frames in how many packets, and whether the output called the feedback stuck then. */
struct SubmittedUrb {
    int64_t atUs;
    int frames;
    int packets;
    bool stuck;
};

#ifdef B48FIX1_BEFORE
// HEAD has no start guard and no priming of a start: the same tests run there, and what they ask for is not there.
void setStartGuard(bool) {}
bool startGuardDefaultIsOn() { return false; }
int64_t silenceToReach(int64_t lead, int64_t chunk, int64_t queued) {
    return (lead <= 0 || chunk <= 0 || queued >= lead) ? 0 : ((lead - queued + chunk - 1) / chunk) * chunk;
}
#else
void setStartGuard(bool on) { usbSetFeedbackStartGuardEnabled(on); }
bool startGuardDefaultIsOn() { return false; }
int64_t silenceToReach(int64_t lead, int64_t chunk, int64_t queued) { return usbSilenceFramesToReach(lead, chunk, queued); }
#endif

struct FakeUsb {
    std::mutex mu;
    std::deque<Pending> inflight;
    std::vector<uint8_t> capture;  // every accepted audio payload byte, in submission order
    // B48-fix1: every start of audio into a ring that may be empty is primed with silence (the plan's resume lead), so the DAC plays that silence before the first sample of a stream.
    // `streamStarts` are the capture offsets at which a stream was created (`createStream`); `audible()` is the capture without those leading silences, which is what every test that
    // compares the wire with the source asks for. The silence itself is pinned by its own test (`testEveryStartPrimesTheRing`): exactly the plan's amount, then the source.
    // B48-fix1: what the simulation needs to see a start's first URBs. `urbsSinceStart` counts audio URBs from the last `markStreamStart` / `markRestart`; when
    // `stallAfterFirstUrbUs` is set, the writer is delayed that long right after the first URB that carries a sample (the thread preempted, or slow work between two URBs).
    // It is the first AUDIBLE URB and not the first URB of any kind: an isochronous URB plays as soon as it is submitted, so a start whose only URB in flight is one packet of its own
    // silence runs dry in any stall, primed or not. What priming promises is that the ring already holds the plan's lead when the first sample goes out; HEAD's first URB is audible.
    // `procOpenCostUs` is what the host simulation cannot see for itself: a read of a /proc file costs the real device milliseconds (the writer's scheduling line took 3-11 ms
    // on the HONOR), the host's cost is wall time that the virtual clock does not count. Off (0) except in the tests of the start.
    std::vector<std::pair<int64_t, std::string>> trainLines;
    int urbsSinceStart = 0;
    bool audibleSinceStart = false;
    int64_t stallAfterFirstUrbUs = 0;
    int64_t procOpenCostUs = 0;
    std::vector<int> writerLineAtUrb;           // how many audio URBs had gone out when each `Writer thread:` line was written
    std::vector<std::string> startLines;        // `Start: feedback start guard=...` and `Prime: ...` lines
    void markRestart() {
        std::lock_guard<std::mutex> lock(mu);
        urbsSinceStart = 0;
        audibleSinceStart = false;
    }
    struct StreamStart { size_t offset; int bytesPerFrame; };
    std::vector<StreamStart> streamStarts;
    void markStreamStart() {
        std::lock_guard<std::mutex> lock(mu);
        streamStarts.push_back(StreamStart{capture.size(), bytesPerFrame});
        urbsSinceStart = 0;
        audibleSinceStart = false;
    }
    /** The silence a stream started with, in bytes: the zero run from [start], rounded down to a whole frame of that stream (a source frame always has a non-zero byte; the noise sources do). */
    static size_t silenceRun(const std::vector<uint8_t> &bytes, const StreamStart &start) {
        size_t run = 0;
        while (start.offset + run < bytes.size() && bytes[start.offset + run] == 0) run++;
        return start.bytesPerFrame > 0 ? run / (size_t)start.bytesPerFrame * (size_t)start.bytesPerFrame : run;
    }
    std::vector<uint8_t> audible() {
        std::lock_guard<std::mutex> lock(mu);
        std::vector<uint8_t> out = capture;
        for (size_t i = streamStarts.size(); i-- > 0;) {
            const StreamStart &start = streamStarts[i];
            if (start.offset > out.size()) continue;
            const size_t run = silenceRun(out, start);
            out.erase(out.begin() + (long)start.offset, out.begin() + (long)(start.offset + run));
        }
        return out;
    }
    int64_t lastCompleteUs = 0;
    int rate = 96000;
    int bytesPerFrame = 8;
    int speed = 3;                 // B46-A4: what USBDEVFS_GET_SPEED answers (3 = high speed); a test sets 2 for a full-speed DAC
    double feedbackFpmf = 12.0;
    // B41-fix1: a script for the feedback endpoint (a function of the moment the transfer is submitted), which beats the constant
    // and the blips, and a log of the audio URBs with their moment, for tests that ask what went on the wire after a given reading.
    std::function<FeedbackAnswer(int64_t)> feedbackScript;
    std::vector<SubmittedUrb> submitted;
    int audioUrbs = 0;
    int audioDiscards = 0;         // DISCARDURB that cut a data URB which had not completed
    // The DAC found nothing queued when a data URB arrived: empty microframes (B9). A pause or a
    // seek that is not painted over shows up here; an idle the writer announced still does, by design.
    bool anyAudio = false;
    int gaps = 0;
    int64_t gapUs = 0;
    int64_t maxGapUs = 0;
    std::vector<int64_t> gapList;  // B47-A3: every empty stretch the fake DAC found, in order (a pause's idle is one of them)
    int ringEmptyLogs = 0;         // USB_GLITCH RING_EMPTY lines the output itself reported
    std::vector<std::string> outputGapLines;  // B23-P2: the USB_GLITCH OUTPUT_GAP lines, whole, as logcat shows them
    std::vector<std::string> transportLines;  // B46-A4: the stream-start plan line and every TRANSPORT_ASSUMPTION line
    std::vector<std::string> schedulingLines; // B47-A1: the "Writer thread:" line of a writer's first write, and every OUTPUT_GAP_CPU line
    // B46-B3: when each audio URB's frames are played (its completion moment, with its frame count), in submission order, and a one-shot
    // delay inside the writer's next poll: a wake-up that comes late (the thread was runnable, not running).
    std::vector<std::pair<int64_t, int>> playedAt;
    int64_t stallNextPollUs = 0;
    /** Frames whose URBs have finished playing at [t] (URB granularity: a URB in progress counts when it is done). */
    int64_t playedFrames(int64_t t) {
        std::lock_guard<std::mutex> lock(mu);
        int64_t frames = 0;
        for (const auto &entry : playedAt) {
            if (entry.first <= t) frames += entry.second;
        }
        return frames;
    }
    // The feedback endpoint (B10): what it answers, optionally with a periodic blip like a healthy DAC's
    // dither, and the frames of every data packet in submission order.
    int feedbackBlipEvery = 0;     // every Nth reading is [feedbackBlipFpmf] instead (0: never)
    double feedbackBlipFpmf = 0.0;
    int64_t feedbackReads = 0;
    std::vector<uint8_t> packetFrames;
    std::vector<std::string> feedbackGlitches;  // the USB_GLITCH FEEDBACK_STUCK / FEEDBACK_UNSTUCK / FEEDBACK_REJECTED lines
    // B47-A5: a DAC that copies 2026-10-29. After a real underrun (the bus found dry for 2 ms or more) its feedback rails at +4.99 % and its completions come late;
    // `lockMode` says what gets it out: 1 = SETINTERFACE (a streaming-interface restart), 2 = a bus reset, 3 = nothing; 0 = a healthy DAC.
    int lockMode = 0;
    bool lockArmed = false;        // the test's injected underrun is about to happen: only a gap found while armed locks the DAC (a pause or a restart leaves the bus dry by design)
    bool lockedUp = false;
    int64_t lockedAtUs = 0;
    int64_t lockedLateCount = 0;
    int setInterfaceCalls = 0;
    int resetCalls = 0;
    int64_t unlockedAtUs = 0;
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
        trainLines.clear();
        std::lock_guard<std::mutex> lock(mu);
        inflight.clear();
        capture.clear();
        streamStarts.clear();
        urbsSinceStart = 0;
        audibleSinceStart = false;
        stallAfterFirstUrbUs = 0;
        procOpenCostUs = 0;
        writerLineAtUrb.clear();
        startLines.clear();
        lastCompleteUs = 0;
        rate = newRate;
        bytesPerFrame = newBytesPerFrame;
        speed = 3;
        audioUrbs = 0;
        audioDiscards = 0;
        anyAudio = false;
        gaps = 0;
        gapUs = 0;
        maxGapUs = 0;
        gapList.clear();
        lockMode = 0;
        lockArmed = false;
        lockedUp = false;
        lockedAtUs = 0;
        lockedLateCount = 0;
        setInterfaceCalls = 0;
        resetCalls = 0;
        unlockedAtUs = 0;
        ringEmptyLogs = 0;
        outputGapLines.clear();
        transportLines.clear();
        schedulingLines.clear();
        playedAt.clear();
        stallNextPollUs = 0;
        feedbackBlipEvery = 0;
        feedbackReads = 0;
        feedbackScript = nullptr;
        submitted.clear();
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
    // B48-fix1: declared before the lock, so it runs after the lock is released: the writer is delayed after its first URB of a start (see FakeUsb::stallAfterFirstUrbUs)
    struct LateStall {
        int64_t us = 0;
        ~LateStall() {
            if (us > 0 && simclock::isVirtual()) simclock::sleepNs(us * 1000);
        }
    } lateStall;
    std::lock_guard<std::mutex> lock(g_usb.mu);
    const int64_t now = nowUs();
    switch (request) {
        case USBDEVFS_GET_SPEED:
            return g_usb.speed;
        case USBDEVFS_SUBMITURB: {
            auto *u = static_cast<usbdevfs_urb *>(arg);
            Pending p{u, 0, false};
            if (u->endpoint == kEndpointFeedback) {
                double answer = g_usb.lockedUp ? (g_usb.rate / 8000.0) * 1.0499 : g_usb.feedbackFpmf;
                if (g_usb.feedbackBlipEvery > 0 && ++g_usb.feedbackReads % g_usb.feedbackBlipEvery == 0) {
                    answer = g_usb.feedbackBlipFpmf;
                }
                FeedbackAnswer scripted;
                scripted.fpmf = answer;
                if (g_usb.feedbackScript) scripted = g_usb.feedbackScript(now);
                const uint32_t raw = (uint32_t)llround(scripted.fpmf * 65536.0);
                memcpy(u->buffer, &raw, 4);
                u->iso_frame_desc[0].actual_length = (unsigned int)scripted.length;
                u->iso_frame_desc[0].status = (unsigned int)scripted.packetStatus;
                u->status = scripted.urbStatus;
                p.feedback = true;
                p.completeAtUs = now + 1000;
            } else {
                const uint8_t *bytes = static_cast<const uint8_t *>(u->buffer);
                g_usb.capture.insert(g_usb.capture.end(), bytes, bytes + u->buffer_length);
                g_usb.audioUrbs++;
                g_usb.urbsSinceStart++;
                if (!g_usb.audibleSinceStart && std::any_of(bytes, bytes + u->buffer_length, [](uint8_t b) { return b != 0; })) {
                    g_usb.audibleSinceStart = true;
                    if (g_usb.stallAfterFirstUrbUs > 0) {
                        lateStall.us = g_usb.stallAfterFirstUrbUs;
                        g_usb.stallAfterFirstUrbUs = 0;
                    }
                }
                int64_t feedbackNow[USB_FEEDBACK_STATS_COUNT];
                usbFeedbackStats(feedbackNow);   // atomics only: safe under the fake kernel's lock, on the thread that is submitting
                g_usb.submitted.push_back(SubmittedUrb{now, u->buffer_length / g_usb.bytesPerFrame, u->number_of_packets, feedbackNow[1] != 0});
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
                    g_usb.gapList.push_back(now - g_usb.lastCompleteUs);
                }
                if (g_usb.lockMode != 0 && g_usb.lockArmed && !g_usb.lockedUp && g_usb.anyAudio && g_usb.lastCompleteUs > 0 && !g_usb.hold &&
                    now >= g_usb.lastCompleteUs + 2000) {
                    g_usb.lockedUp = true;   // a real underrun of 2 ms or more
                    g_usb.lockArmed = false;
                    g_usb.lockedAtUs = now;
                }
                g_usb.anyAudio = true;
                const int64_t durUs = (int64_t)u->buffer_length * 1000000LL / g_usb.bytesPerFrame / g_usb.rate;
                p.completeAtUs = g_usb.hold ? now + 3600LL * 1000000LL : std::max(g_usb.lastCompleteUs, now) + durUs;
                if (g_usb.lockedUp && ++g_usb.lockedLateCount % 100 == 0) p.completeAtUs += 30000;   // the oldest URB of a full ring takes ~30 ms
                if (!g_usb.hold) g_usb.lastCompleteUs = p.completeAtUs;
                g_usb.playedAt.push_back(std::make_pair(p.completeAtUs, (int)(u->buffer_length / g_usb.bytesPerFrame)));
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
        case USBDEVFS_SETINTERFACE:
            g_usb.setInterfaceCalls++;
            if (g_usb.lockMode == 1 && g_usb.lockedUp) {
                g_usb.lockedUp = false;
                g_usb.unlockedAtUs = now;
            }
            return 0;
        case USBDEVFS_RESET:
            g_usb.resetCalls++;
            if (g_usb.lockMode == 2 && g_usb.lockedUp) {
                g_usb.lockedUp = false;
                g_usb.unlockedAtUs = now;
            }
            return 0;
        default:
            return 0;
    }
}

// usbfs reports POLLOUT while a completed URB waits to be reaped, POLLHUP once the device is gone.
extern "C" int poll(struct pollfd *fds, nfds_t nfds, int timeoutMs) {
    if (nfds != 1 || fds[0].fd != kFakeFd) return (int)syscall(SYS_poll, fds, nfds, timeoutMs);
    int64_t lateWakeUs = 0;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        lateWakeUs = g_usb.stallNextPollUs;
        g_usb.stallNextPollUs = 0;
    }
    if (lateWakeUs > 0 && simclock::isVirtual()) simclock::sleepNs(lateWakeUs * 1000);   // B46-B3: the wake-up is late; the DAC plays on
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

// B48-fix1: a read of a /proc file costs the real device milliseconds (see FakeUsb::procOpenCostUs): in the tests of the start it costs virtual time, where the native code does it.
// B48-fix1: when the writer last read /proc/stat, which only the CPU baseline (usb-audio-output.cpp, takeCpuSnapshot) does: it is taken at most every 2 s, and a restart that finds it due
// is where HEAD paid its milliseconds between the first two URBs. `testEveryStartPrimesTheRing` waits for it so that the recovery's resume always meets a due baseline.
static std::atomic<int64_t> g_lastProcStatOpenUs{0};

extern "C" int open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    if (strcmp(path, "/proc/stat") == 0 && simclock::isVirtual()) g_lastProcStatOpenUs.store(nowUs());
    if (g_usb.procOpenCostUs > 0 && strncmp(path, "/proc/", 6) == 0 && simclock::isVirtual()) simclock::sleepNs(g_usb.procOpenCostUs * 1000);
    return (int)syscall(SYS_openat, AT_FDCWD, path, flags, mode);
}

extern "C" int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    static const bool verbose = getenv("SIM_LOG") != nullptr;
    if (fmt) {
        char line[1200];
        va_list copy;
        va_start(copy, fmt);
        vsnprintf(line, sizeof(line), fmt, copy);
        va_end(copy);
        if (strstr(line, "FEEDBACK_STUCK") || strstr(line, "DAC_LOCKED") || strstr(line, "DAC_RECOVERY") ||
            strstr(line, "FEEDBACK_OUT_OF_WINDOW") || strstr(line, "WriteRaw:") || strstr(line, "Feedback #")) {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            g_usb.trainLines.push_back(std::make_pair(nowUs(), std::string(line)));
        }
    }
    if (tag && strcmp(tag, "USB_GLITCH") == 0) {
        va_list gap;
        va_start(gap, fmt);
        const char *kind = va_arg(gap, const char *);
        va_end(gap);
        if (kind && strcmp(kind, "RING_EMPTY") == 0) {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            g_usb.ringEmptyLogs++;
        }
        if (kind && strcmp(kind, "OUTPUT_GAP_CPU") == 0) {
            char line[700];
            va_list copy;
            va_start(copy, fmt);
            vsnprintf(line, sizeof(line), fmt, copy);
            va_end(copy);
            std::lock_guard<std::mutex> lock(g_usb.mu);
            g_usb.schedulingLines.push_back(line);
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
            (strstr(kind, "STUCK") != nullptr || strstr(kind, "REJECTED") != nullptr || strstr(kind, "INVALID") != nullptr)) {
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
    if (fmt && strncmp(fmt, "Writer thread: %s", 17) == 0) {
        char line[700];
        va_list copy;
        va_start(copy, fmt);
        vsnprintf(line, sizeof(line), fmt, copy);
        va_end(copy);
        std::lock_guard<std::mutex> lock(g_usb.mu);
        g_usb.schedulingLines.push_back(line);
        g_usb.writerLineAtUrb.push_back(g_usb.audioUrbs);   // B48-fix1
    }
    if (fmt && (strncmp(fmt, "Start: rate change", 18) == 0 || strncmp(fmt, "Start: feedback start guard", 27) == 0 || strncmp(fmt, "Prime: ", 7) == 0)) {
        char line[400];
        va_list copy;
        va_start(copy, fmt);
        vsnprintf(line, sizeof(line), fmt, copy);
        va_end(copy);
        std::lock_guard<std::mutex> lock(g_usb.mu);
        g_usb.startLines.push_back(line);
    }
    if (fmt && (strstr(fmt, "TRANSPORT_ASSUMPTION") != nullptr || strncmp(fmt, "Start: %s speed", 15) == 0)) {
        char line[700];
        va_list copy;
        va_start(copy, fmt);
        vsnprintf(line, sizeof(line), fmt, copy);
        va_end(copy);
        std::lock_guard<std::mutex> lock(g_usb.mu);
        g_usb.transportLines.push_back(line);
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

jlong createStreamWith(int rate, int slotBits, int maxPacket, int bInterval, int feedbackBytes) {
    return Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioCreate(
        nullptr, nullptr, kFakeFd, 1, kEndpointOut, kEndpointFeedback, rate, 2, slotBits, maxPacket, bInterval, feedbackBytes);
}

jlong createStream(int rate, int slotBits, int previousRate = 0) {
#ifndef B48FIX2_BEFORE
    usbNoteDataAltSet(kFakeFd);
#endif

    const jlong usb = createStreamWith(rate, slotBits, 400, kFakeBInterval, kFakeFeedbackBytes);
    if (usb == 0 || !Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(nullptr, nullptr, usb, previousRate)) {
        fprintf(stderr, "stream did not start\n");
        exit(2);
    }
    g_usb.markStreamStart();
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

constexpr int kFrames96 = 76800;   // 0.8 s at 96 kHz = 9 x 8192 + 3072
constexpr int kFrames441 = 44100;  // 1 s at 44.1 kHz = 9 x 4608 + 2628
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

/** One PRISTINE case: two tracks with the end of the stream between them, bytes compared with the source one by one. */
void pristineCase(JNIEnv *env, int rate, int bits, int slot) {
    const Pcm a = makeNoise(rate / 5 + 17, bits, 0x45abc123u);
    const Pcm b = makeNoise(rate / 7 + 31, bits, 0x45def456u);
    g_usb.reset(rate, 2 * slot / 8);
    const jlong usb = createStream(rate, slot);
    writePipeline(env, usb, a, 4096);
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(env, nullptr, usb), "A residual drain failed");
    writePipeline(env, usb, b, 4608);
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(env, nullptr, usb), "B residual drain failed");
    releaseStream(usb);
    std::vector<uint8_t> want;
    for (const Pcm *pcm : {&a, &b}) {
        for (int32_t sample : pcm->samples) {
            const uint32_t packed = static_cast<uint32_t>(sample) << (slot - bits);
            for (int byte = 0; byte < slot / 8; byte++) want.push_back(static_cast<uint8_t>(packed >> (8 * byte)));
        }
    }
    // B48-fix1: the audible bytes: the wire without the start silence that primes the ring (the same hash as HEAD's, which had none)
    const std::vector<uint8_t> audible = g_usb.audible();
    const long diff = firstDifference(want, audible);
    CHECK(diff < 0, "PRISTINE %d/%d slot %d differs at %ld", rate, bits, slot, diff);
    CHECK(g_usb.audioDiscards == 0, "PRISTINE gapless join cancelled owned URBs");
    // FNV-1a makes before/after logs directly comparable; equality above is byte-for-byte.
    uint64_t hash = 14695981039346656037ull;
    for (uint8_t byte : audible) hash = (hash ^ byte) * 1099511628211ull;
    printf("HOST PRISTINE rate=%d bits=%d slot=%d gapless=A||B bytes=%zu fnv1a=%016llx %s\n",
           rate, bits, slot, audible.size(), static_cast<unsigned long long>(hash), diff < 0 ? "EXACT" : "FAIL");
}

/**
 * B46: the rates B45's test did not reach (88.2, 176.4, 352.8, 384 kHz; the fake endpoint's 400-byte maxPacket carries a widest
 * packet of 48 frames x 8 bytes = 384, so all four fit), then one rate change between tracks. Run after the original 18, so the
 * first 18 lines are the same lines B45 printed.
 */
void testPristineMoreRates(JNIEnv *env) {
    for (int rate : {88200, 176400, 352800, 384000}) {
        for (int bits : {16, 24, 32}) {
            for (int slot : {16, 24, 32}) {
                if (slot < bits) continue;
                pristineCase(env, rate, bits, slot);
            }
        }
    }
}

/**
 * A rate change between two tracks: A at 44.1 kHz, 16-bit source in a 24-bit slot; B at 352.8 kHz, 24-bit source in a 32-bit slot.
 * The sink rebuilds the stream at the change (a new context, a new plan: ring, slots and packet sizes of the new rate), and the
 * wire carries A then B, each exactly as its source, with no URB cut.
 */
void testPristineRateChange(JNIEnv *env) {
    const Pcm a = makeNoise(44100 / 5 + 17, 16, 0x45abc123u);
    const Pcm b = makeNoise(352800 / 7 + 31, 24, 0x45def456u);
    g_usb.reset(44100, 2 * 24 / 8);
    jlong usb = createStream(44100, 24);
    writePipeline(env, usb, a, 4096);
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(env, nullptr, usb), "A residual drain failed");
    releaseStream(usb);
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        g_usb.rate = 352800;
        g_usb.bytesPerFrame = 2 * 32 / 8;
        g_usb.lastCompleteUs = 0;
    }
    usb = createStream(352800, 32);
    writePipeline(env, usb, b, 4608);
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(env, nullptr, usb), "B residual drain failed");
    releaseStream(usb);
    std::vector<uint8_t> want;
    const struct { const Pcm *pcm; int bits, slot; } tracks[] = {{&a, 16, 24}, {&b, 24, 32}};
    for (const auto &track : tracks) {
        for (int32_t sample : track.pcm->samples) {
            const uint32_t packed = static_cast<uint32_t>(sample) << (track.slot - track.bits);
            for (int byte = 0; byte < track.slot / 8; byte++) want.push_back(static_cast<uint8_t>(packed >> (8 * byte)));
        }
    }
    const std::vector<uint8_t> audible = g_usb.audible();   // B48-fix1: without each stream's start silence
    const long diff = firstDifference(want, audible);
    CHECK(diff < 0, "PRISTINE rate change 44.1k->352.8k differs at %ld", diff);
    CHECK(g_usb.audioDiscards == 0, "PRISTINE rate change cancelled owned URBs");
    uint64_t hash = 14695981039346656037ull;
    for (uint8_t byte : audible) hash = (hash ^ byte) * 1099511628211ull;
    printf("HOST PRISTINE ratechange A=44100/16/slot24 B=352800/24/slot32 bytes=%zu fnv1a=%016llx %s\n",
           audible.size(), static_cast<unsigned long long>(hash), diff < 0 ? "EXACT" : "FAIL");
}

void testPristineFormats() {
    simclock::VirtualTime virtualTime;
    JNINativeInterface table = {};
    table.GetArrayLength = fakeGetArrayLength;
    table.GetByteArrayElements = fakeGetByteArrayElements;
    table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
    JNIEnv env;
    env.functions = &table;
    for (int rate : {44100, 96000, 192000}) {
        for (int bits : {16, 24, 32}) {
            for (int slot : {16, 24, 32}) {
                if (slot < bits) continue;
                pristineCase(&env, rate, bits, slot);
            }
        }
    }
    testPristineMoreRates(&env);
    testPristineRateChange(&env);
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
    const std::vector<uint8_t> got = g_usb.audible();   // B48-fix1: without each stream's start silence
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
// output. The expected stream is built from source PCM and the shared ramp helpers.

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

    explicit Writer(int streamRate = kRate, bool nominalFeedback = false, int previousRate = 0) {
        table.GetArrayLength = simGetLength;
        table.GetByteArrayElements = simGetBytes;
        table.ReleaseByteArrayElements = simReleaseBytes;
        table.GetFloatArrayElements = simGetFloats;
        table.ReleaseFloatArrayElements = simReleaseFloats;
        env.functions = &table;
        g_usb.reset(streamRate, 8);
        if (nominalFeedback) g_usb.feedbackFpmf = streamRate / 8000.0;
        usb = createStream(streamRate, 32, previousRate);
        ctx = reinterpret_cast<UsbAudioContext *>(usb);
    }
    ~Writer() {
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(nullptr, nullptr, usb);
        Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(nullptr, nullptr, usb);
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(nullptr, nullptr, usb);
    }
    /** B48-fix1: the silence the stream's first write primed the ring with (frames), the one place every stream differs from its designed bytes on purpose. */
    int64_t startSilenceFrames = 0;
    bool wroteAny = false;
    /** The first write of a stream is preceded by the priming; its amount is read from the stream's own silence count. */
    template <typename Fn> void firstWriteAccounting(Fn &&write) {
        const int64_t before = stat(5);
        write();
        if (!wroteAny) {
            wroteAny = true;
            startSilenceFrames = stat(5) - before;
        }
    }
    /** One buffer of [pcm] (frames [from, from + frames)) through the raw path, as the thread writes it. */
    void write(const Pcm &pcm, int from, int frames) {
        firstWriteAccounting([&] { writeRawBuffer(pcm, from, frames); });
    }
    void writeRawBuffer(const Pcm &pcm, int from, int frames) {
        const std::vector<uint8_t> &all = packedOf(pcm);
        const size_t frameBytes = (size_t)(pcm.bits / 8) * 2;
        SimArray chunk;
        chunk.bytes.assign(all.begin() + (size_t)from * frameBytes, all.begin() + (size_t)(from + frames) * frameBytes);
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWriteRaw(&env, nullptr, usb, reinterpret_cast<jbyteArray>(&chunk), pcm.bits);
    }
    /** The same frames through the float path (PROCESSED / FFmpeg sources): 24-bit integers are exact floats. */
    void writeFloat(const Pcm &pcm, int from, int frames) {
        firstWriteAccounting([&] { writeFloatBuffer(pcm, from, frames); });
    }
    void writeFloatBuffer(const Pcm &pcm, int from, int frames) {
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
    /** B47-A3: the silence the last resume() primed the ring with, in frames: the one place the stream differs from the designed fades on purpose. */
    int64_t resumeSilenceFrames = 0;
    void resume() {
        const int64_t before = stat(5);
        Java_com_decent_usbaudio_UsbAudioStream_nativePipelineResume(nullptr, nullptr, usb);
        resumeSilenceFrames = stat(5) - before;
    }
    void markIdle() { Java_com_decent_usbaudio_UsbAudioStream_nativeMarkIdle(nullptr, nullptr, usb); }
    bool feedSilence() { return Java_com_decent_usbaudio_UsbAudioStream_nativePipelineFeedSilence(nullptr, nullptr, usb) == JNI_TRUE; }
    int64_t stat(int index) {
        int64_t stats[9];
        pipelineGestureStats(ctx, stats);
        return stats[index];
    }
    int64_t queuedFrames() { return usbQueuedFrames(ctx); }
    std::vector<uint8_t> captured() { return g_usb.audible(); }   // B48-fix1: without the stream's start silence
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
            appendSilence(want, w.resumeSilenceFrames);   // B47-A3: the silence the resume primed the ring with
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
        appendSilence(want, w.resumeSilenceFrames);
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
        appendSilence(want, w.resumeSilenceFrames);
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
        appendSilence(want, w.resumeSilenceFrames);
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
        appendSilence(want, w.resumeSilenceFrames);
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
    const int64_t silence = w.stat(5) - w.startSilenceFrames;   // B48-fix1: the gesture's silence, not the start's
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
        const int leftoverFrames = w.ctx->residualBytes / 8;   // the sub-URB tail of the ramp the packetizer still holds
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
        // B48-fix1: a seek that finds nothing audible starts the packetisation clean (`residualBytes = 0`): the sub-URB leftover of the ramp (frames at a gain near zero) is not sent.
        // It was 0 frames before the stream's start priming (the 3 blocks and the ramp were a whole number of URBs); the priming's silence leaves the audio 50 frames out of step.
        const int droppedFrames = plan == PIPELINE_SEEK_NONE ? leftoverFrames : 0;
        appendRamped(want, a, 3 * kBuf, kFadeOut - droppedFrames, 0, kFadeOut, false);
        appendSilence(want, w.resumeSilenceFrames);
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
    const int64_t silence = w.stat(5) - w.startSilenceFrames;   // B48-fix1: the gesture's silence, not the start's
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
    const int leftoverFrames = w.ctx->residualBytes / 8;   // B48-fix1: the sub-URB tail of the ramp, which a release does not send (it is 0 frames when the audio and the packets line up; the start priming moves them)
    const int64_t t0 = nowUs();
    Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(nullptr, nullptr, w.usb);
    Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(nullptr, nullptr, w.usb);
    const int64_t drainMs = (nowUs() - t0) / 1000;
    std::vector<uint8_t> want;
    appendUsb(want, a, 0, 3 * kBuf);
    appendRamped(want, a, 3 * kBuf, kFadeOut - leftoverFrames, 0, kFadeOut, false);
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
    CHECK(stats[2] == 0 && stats[3] == 0 && stats[4] == 0 && stats[5] == w.startSilenceFrames && stats[6] == 0 && stats[7] == 0,   // B48-fix1: the start's priming is the only silence
          "a join with no gesture moved the barrier's state");
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        CHECK(g_usb.gaps == 0 && g_usb.ringEmptyLogs == 0, "pipeline join: %d gaps, %d RING_EMPTY", g_usb.gaps, g_usb.ringEmptyLogs);
        CHECK(g_usb.outputGapLines.empty(), "pipeline join: reported as an output gap: %s", g_usb.outputGapLines.empty() ? "" : g_usb.outputGapLines.front().c_str());
    }
    printf("pipeline natural join: bit-exact, the barrier untouched (no fade, no silence, nothing kept)\n");
}

void testPipelineGestures(const Pcm &a, const Pcm &b) {
    // Long sources: a seek and a pause need audio behind the ring.
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
 * Streams [pcm] through the pipeline raw writer in the given wait mode; returns bytes and the wait counters' delta, and in
 * [completions] how many URBs completed for the writer to wait on (data URBs and feedback readings).
 */
std::vector<uint8_t> streamOnce(bool pollMode, const Pcm &pcm, uint64_t delta[5], int64_t *completions) {
    simclock::VirtualTime virtualTime;
    usbSetPollWait(pollMode);
    g_usb.reset(96000, 8);
    uint64_t before[5];
    usbWaitStats(before);
    int64_t feedbackBefore[USB_FEEDBACK_STATS_COUNT], feedbackAfter[USB_FEEDBACK_STATS_COUNT];
    usbFeedbackStats(feedbackBefore);
    const jlong usb = createStream(96000, 32);
    JNINativeInterface table = {};
    table.GetArrayLength = fakeGetArrayLength;
    table.GetByteArrayElements = fakeGetByteArrayElements;
    table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
    JNIEnv env;
    env.functions = &table;
    writePipeline(&env, usb, pcm, 8192);
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(&env, nullptr, usb), "wait-mode residual drain failed");
    releaseStream(usb);
    uint64_t after[5];
    usbWaitStats(after);
    usbFeedbackStats(feedbackAfter);
    for (int i = 0; i < 5; i++) delta[i] = after[i] - before[i];
    delta[0] = after[0];  // the mode is a state, not a count
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        *completions = (int64_t)g_usb.audioUrbs + (feedbackAfter[8] - feedbackBefore[8]);
    }
    return g_usb.audible();
}

void testWaitModes(const Pcm &pcm) {
    uint64_t pollDelta[5], sleepDelta[5];
    int64_t pollCompletions = 0, sleepCompletions = 0;
    const std::vector<uint8_t> viaPoll = streamOnce(true, pcm, pollDelta, &pollCompletions);
    const std::vector<uint8_t> viaSleep = streamOnce(false, pcm, sleepDelta, &sleepCompletions);
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
void testSilentPollDoesNotStarve(const Pcm &pcm) {
    simclock::VirtualTime virtualTime;
    usbSetPollWait(true);
    g_usb.reset(96000, 8);
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        g_usb.silentPoll = true;
    }
    const jlong usb = createStream(96000, 32);
    JNINativeInterface table = {};
    table.GetArrayLength = fakeGetArrayLength;
    table.GetByteArrayElements = fakeGetByteArrayElements;
    table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
    JNIEnv env;
    env.functions = &table;
    const int64_t t0 = nowUs();
    writePipeline(&env, usb, pcm, 8192);
    const bool ended = Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(&env, nullptr, usb);
    const int64_t elapsedMs = (nowUs() - t0) / 1000;
    releaseStream(usb);
    const std::vector<uint8_t> got = g_usb.audible();
    const std::vector<uint8_t> want = toUsb32(makeNoise(kFrames96, 24, 0x1234abcdu));
    CHECK(ended, "the writer never drained against a poll that never signals");
    CHECK(firstDifference(want, got) < 0, "a silent poll changed the audio (%zu of %zu bytes)", got.size(), want.size());
    // 0.8 s of audio, paced by the DAC: the writer keeps up, within the ring's slack.
    CHECK(elapsedMs < 800 + 350, "a silent poll stalled the writer: %lld ms for 800 ms of audio", (long long)elapsedMs);
    printf("poll that never signals: audio intact, %lld ms for 800 ms of audio\n", (long long)elapsedMs);
}

/** The timeouts are the ones the callers pass, in ms, whatever the wait is made of. */
void testWaitTimeouts() {
    simclock::VirtualTime virtualTime;
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

// ── B41-fix1 (A1b): a reading the policy refuses reaches the rest of the block that is being written ───────────────────────
//
// 2026-10-25, the owner's HONOR, Melody, 44.1 kHz, after a seek: the feedback went +0.36 % → ... → `FEEDBACK_REJECTED absurd +20851 ppm`
// with `sending=5.5724` (+1.09 %), then +5.0 % for more than 24 s. The log's `sending` is what the packetizer was last given. The
// output hands it a rate when a block of PCM (about 100 ms of audio) begins and keeps it to the block's end, while the reap that
// paces the block handles the DAC's feedback: the absurd reading itself was judged in the very call that reaped it, but the block in
// progress went on at the rate of the reading BEFORE it, a plausible +1.09 %. The same holds for the moment the feedback is judged
// stuck. Here a fake DAC answers the Melody's ramp (timed to a block's middle, as the log has it); what the URBs carry after the
// reading is what is measured, at the kernel, URB by URB.

constexpr int kRate441 = 44100;
constexpr int kBlock441 = 4608;                  // the decoder's block in the owner's log (104.5 ms at 44.1 kHz)
constexpr double kNominal441 = kRate441 / 8000.0;

/** Frames per packet of the audio URBs submitted in [fromUs, toUs); 0 if none. */
double meanFramesPerPacket(int64_t fromUs, int64_t toUs, int *urbCount = nullptr) {
    std::lock_guard<std::mutex> lock(g_usb.mu);
    long frames = 0, packets = 0;
    int n = 0;
    for (const SubmittedUrb &u : g_usb.submitted) {
        if (u.atUs < fromUs || u.atUs >= toUs) continue;
        frames += u.frames;
        packets += u.packets;
        n++;
    }
    if (urbCount) *urbCount = n;
    return packets ? (double)frames / (double)packets : 0.0;
}

void setFeedbackScript(std::function<FeedbackAnswer(int64_t)> script) {
    std::lock_guard<std::mutex> lock(g_usb.mu);
    g_usb.feedbackScript = std::move(script);
}

/** The Melody when it is healthy: its nominal, and a +0.271 % blip every 80th reading. */
FeedbackAnswer healthyMelody() {
    static int64_t reads = 0;
    FeedbackAnswer a;
    a.fpmf = (++reads % 80 == 0) ? kNominal441 * 1.00271 : kNominal441;
    return a;
}

/**
 * A pause, a seek while paused, and a resume, the way UsbStreamingThread drives them: the old position fades out, the new one
 * fades in at the resume. [source] is any audio; [pos] the writer's place in it, moved on by what is written.
 */
void quickSeek(Writer &w, const Pcm &source, int &pos, int blocksBefore) {
    for (int i = 0; i < blocksBefore; i++) {
        w.write(source, pos, kBlock441);
        pos += kBlock441;
    }
    int next = pos;
    pauseProtocol(w, source, next, kBlock441);
    pos = next;
    w.beginSeek();
    w.markIdle();
    listen(100);
    w.resume();
}

/**
 * [sign] +1: +0.36 → +1.09 → +2.08 → +5.0 %; -1: the mirror of 17:00:08 (-0.36 → -1.09 → -2.08 → -5.0 %). The ramp is timed to the middle of
 * the block that begins [rampAtUs] after now: the reading in force when that block begins is the +-1.09 % one, and the absurd reading
 * arrives 40 ms into it.
 */
void testSeekRampDoesNotLeak(int sign) {
    const Pcm source = makeNoise(kRate441 * 14, 24, 0x5eed441u + (uint32_t)(sign > 0));
    Writer w(kRate441);
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    int64_t rampB = INT64_MAX;
    setFeedbackScript([&](int64_t t) -> FeedbackAnswer {
        FeedbackAnswer a = healthyMelody();
        if (rampB == INT64_MAX) return a;
        const int64_t d = t - rampB;
        double pct = 0.0;
        if (d < -40000) pct = 0.36;
        else if (d < 40000) pct = 1.09;
        else if (d < 100000) pct = 2.08;
        else pct = 5.0;
        a.fpmf = kNominal441 * (1.0 + sign * pct / 100.0);
        return a;
    });
    int pos = 0;
    for (int i = 0; i < 20; i++) { w.write(source, pos, kBlock441); pos += kBlock441; }   // ~2 s healthy
    // three quick seeks
    for (int s = 0; s < 3; s++) {
        quickSeek(w, source, pos, 2);
        if (s < 2) for (int i = 0; i < 3; i++) { w.write(source, pos, kBlock441); pos += kBlock441; }
    }
    // The next block but one is the one the ramp is timed to: arm it one block ahead, with the block's length as the writer paces it.
    const int64_t blockUs = (int64_t)kBlock441 * 1000000 / kRate441;
    w.write(source, pos, kBlock441); pos += kBlock441;                  // the first block after the resume (fades in)
    const int64_t armedAt = nowUs();
    rampB = armedAt + blockUs;                                          // the block that starts about now + one block
    for (int i = 0; i < 8; i++) { w.write(source, pos, kBlock441); pos += kBlock441; }
    // What went on the wire from the absurd reading (+40 ms, +1 ms to complete, + the reap) to the end of that block, and for the
    // second after it, where the latched +-5 % goes on being refused.
    int n1 = 0, n2 = 0;
    const double rest = meanFramesPerPacket(rampB + 50000, rampB + 95000, &n1);
    const double later = meanFramesPerPacket(rampB + 150000, rampB + 450000, &n2);
    const double band = kNominal441 * 0.005;                            // the healthy band: +-0.5 % (USB_FEEDBACK_SUSTAINED_PPM)
    CHECK(n1 >= 30, "only %d URBs between the absurd reading and the end of its block: the scenario did not happen", n1);
    CHECK(std::fabs(rest - kNominal441) < band,
          "%+d: the rest of the block carried %.5f frames a packet (%+.2f %% off nominal) after the DAC's reading was refused as absurd; healthy is within %.2f %%",
          sign, rest, (rest / kNominal441 - 1.0) * 100.0, 0.5);
    CHECK(n2 >= 100 && std::fabs(later - kNominal441) < band, "%+d: the next blocks carried %.5f frames a packet while the DAC latched at %+d %%", sign, later, sign * 5);
    int64_t stats[USB_FEEDBACK_STATS_COUNT];
    usbFeedbackStats(stats);
    CHECK(std::fabs(stats[11] / 1e6 - kNominal441) < band, "sending %.5f at the end, expected the healthy rate", stats[11] / 1e6);
    CHECK(stats[13] >= 1, "no streak of implausible readings was counted");
    printf("seek ramp %+d: the block in progress when the absurd reading came carried %.5f frames a packet after it (nominal %.4f, band +-%.4f), the next blocks %.5f\n",
           sign, rest, kNominal441, band, later);
    setFeedbackScript(nullptr);
}

/** An honest DAC that is really 300 ppm off is followed after three quick seeks as before: nothing here made `auto` timid. */
void testHonestDacIsStillFollowedAfterSeeks() {
    const Pcm source = makeNoise(kRate441 * 14, 24, 0x0e57a0d1u);
    Writer w(kRate441);
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    const double honest = kNominal441 * (1.0 + 300e-6);
    setFeedbackScript([&](int64_t) { FeedbackAnswer a; a.fpmf = honest; return a; });
    int pos = 0;
    for (int i = 0; i < 10; i++) { w.write(source, pos, kBlock441); pos += kBlock441; }
    for (int s = 0; s < 3; s++) quickSeek(w, source, pos, 2);
    for (int i = 0; i < 4; i++) { w.write(source, pos, kBlock441); pos += kBlock441; }   // let the fade-in pass
    const int64_t from = nowUs();
    for (int i = 0; i < 12; i++) { w.write(source, pos, kBlock441); pos += kBlock441; }
    int n = 0;
    const double mean = meanFramesPerPacket(from, nowUs(), &n);
    CHECK(n > 800, "%d URBs measured", n);
    CHECK(mean > kNominal441 + 0.0008 && mean < kNominal441 + 0.0026,
          "an honest DAC 300 ppm off: %.5f frames a packet after three seeks, expected %.5f (nominal %.5f)", mean, honest, kNominal441);
    printf("honest DAC +300 ppm after three seeks: followed (%.5f frames a packet, expected %.5f)\n", mean, honest);
    setFeedbackScript(nullptr);
}

/**
 * The feedback judged stuck while a block is half written: the DAC answers +0.8 % from the stream's start (the plateau of "Ciega,
 * Sordomuda"); `auto` follows it for 5 s and then, at the reading that makes it stuck, must stop at that reading, not at the end of
 * the block it is writing.
 */
void testStuckReachesTheBlockInProgress() {
    const Pcm source = makeNoise(kRate441 * 14, 24, 0x57acc0deu);
    const double plateau = 5.5575;   // 17:39:57 on the HONOR, +0.816 %
    Writer w(kRate441);              // (the Writer resets the fake DAC: the script comes after it, as in the other tests)
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    setFeedbackScript([&](int64_t) { FeedbackAnswer a; a.fpmf = plateau; return a; });
    int pos = 0;
    for (int i = 0; i < 80; i++) { w.write(source, pos % (source.frames() - kBlock441), kBlock441); pos += kBlock441; }   // ~8.4 s
    int64_t stuckAt = -1;
    std::vector<SubmittedUrb> urbs;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        urbs = g_usb.submitted;
    }
    for (const SubmittedUrb &u : urbs) {
        if (u.stuck) { stuckAt = u.atUs; break; }
    }
    CHECK(stuckAt > 0, "the feedback was never called stuck in 8 s of an identical +0.8 %% reading");
    int nBefore = 0, nAfter = 0;
    const double during = meanFramesPerPacket(urbs.front().atUs + 2000000, stuckAt - 2000, &nBefore);
    const double after = meanFramesPerPacket(stuckAt + 3000, stuckAt + 60000, &nAfter);
    CHECK(nBefore > 500 && std::fabs(during - plateau) < 0.003, "before it was stuck the plateau was followed: %.5f frames a packet (%d URBs), expected %.4f", during, nBefore, plateau);
    CHECK(nAfter >= 40 && std::fabs(after - kNominal441) < kNominal441 * 0.002,
          "within 3 ms of the feedback being called stuck the packets were still %.5f frames each (%d URBs), expected the healthy rate %.4f", after, nAfter, kNominal441);
    printf("stuck feedback reaches the block in progress: followed at %.5f until it was stuck, then %.5f frames a packet within 3 ms (nominal %.4f)\n", during, after, kNominal441);
    setFeedbackScript(nullptr);
}

// ── B41-fix1 (A1c): what the noise mark records about the feedback ─────────────────────────────────────────────────────────────

/** The fields of a second's line that B41-fix1 added, after the twelve B27-A5 parses. */
struct EvidenceLine {
    long ago = -1;
    int readings = -1, refused = -1, held = -1, dropUrb = -1, dropPacket = -1, dropLength = -1, lastUrb = 99, lastPacket = 99, lastLength = -1;
    long long ageMs = -2;
    double sentMin = 0, sentMax = 0, applied = 0, mean = 0;
    int sentCalls = -1;
    long long wireFrames = -1, wirePackets = -1;
};

bool parseEvidenceLine(const char *line, EvidenceLine *out) {
    return sscanf(line,
                  "t-%lds feedback n=%d fpmf min=%*f max=%*f mean=%lf nominal=%*f deviating=%*d refused=%d | iso urbs=%*d packets=%*d errors=%*d short=%*d"
                  " | fb held=%d dropped urb=%d packet=%d length=%d last=%d/%d/%d age=%lldms | sent min=%lf max=%lf calls=%d | wire frames=%lld packets=%lld applied=%lf",
                  &out->ago, &out->readings, &out->mean, &out->refused, &out->held, &out->dropUrb, &out->dropPacket, &out->dropLength, &out->lastUrb,
                  &out->lastPacket, &out->lastLength, &out->ageMs, &out->sentMin, &out->sentMax, &out->sentCalls, &out->wireFrames, &out->wirePackets,
                  &out->applied) == 18;
}

std::vector<EvidenceLine> evidenceLines(const char *text) {
    std::vector<EvidenceLine> lines;
    for (const char *line = strstr(text, "\nt-"); line != nullptr; line = strstr(line + 1, "\nt-")) {
        EvidenceLine l;
        if (parseEvidenceLine(line + 1, &l)) lines.push_back(l);
        else CHECK(false, "a line is not in the documented shape: %.200s", line + 1);
    }
    return lines;
}

/**
 * A stream that goes through the four things the 2026-10-25 marks could not tell apart, against a fake DAC: (1) healthy, (2) a
 * stretch in which every feedback transfer comes back with a packet error and four bytes, (3) a +0.8 % plateau `auto` answers with
 * the nominal rate, which used to read `refused=0`, (4) a different reading that ends it. The dump must say, second by second, what
 * was received, what was dropped and why, how old the last valid reading was, what the packetizer was given and what went out, and
 * list the changes of what the policy obeys with the stream's number.
 */
void testFeedbackEvidenceInTheDump() {
    const Pcm source = makeNoise(kRate441 * 30, 24, 0xe71d3ce5u);
    Writer w(kRate441);
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    simclock::advanceTo((simclock::nowNs() / 1000000000LL + 1) * 1000000000LL);
    const int64_t startSecond = nowUs() / 1000000;
    int64_t t0 = nowUs();
    int stage = 1;
    int64_t stageStart[6] = {0, 0, 0, 0, 0, 0};
    setFeedbackScript([&](int64_t t) -> FeedbackAnswer {
        FeedbackAnswer a = healthyMelody();
        const int64_t s = (t - t0) / 1000000;            // seconds into the run
        if (s >= 4 && s < 7) {                           // (2) three seconds of errors: four bytes and -EILSEQ on the packet
            a.fpmf = kNominal441 * 1.008;
            a.packetStatus = -EILSEQ;
        } else if (s >= 7 && s < 15) {                   // (3) the plateau, valid: +0.816 %
            a.fpmf = 5.5575;
        } else if (s >= 15) {                            // (4) back to the healthy DAC
            a = healthyMelody();
        }
        return a;
    });
    (void)stage; (void)stageStart;
    int pos = 0;
    bool seeked = false;
    while (nowUs() - t0 < 18 * 1000000LL) {
        if (!seeked && nowUs() - t0 > 2 * 1000000LL) {   // one gesture cycle in the healthy part: pause, seek while paused, resume
            seeked = true;
            quickSeek(w, source, pos, 1);
        }
        w.write(source, pos, kBlock441);
        pos += kBlock441;
        if (pos + kBlock441 > source.frames()) pos = 0;
    }
    sleepMs(1500);
    const int64_t windowMs = (nowUs() / 1000000 - startSecond) * 1000 + 3000;   // (the stream began a moment before the second it is counted from)
    static char text[65536];
    const size_t used = usbSecondSummaries(windowMs, text, sizeof(text));
    CHECK(used == strlen(text), "reported %zu bytes, the text is %zu", used, strlen(text));
    CHECK(strstr(text, "\nlegend:") != nullptr, "the dump does not explain its fields");
    const std::vector<EvidenceLine> lines = evidenceLines(text);
    CHECK(lines.size() >= 12, "%zu seconds in the dump after 18 s", lines.size());
    // seconds counted from the oldest line of this stream: t-N is N seconds before the dump
    int healthySecs = 0, errorSecs = 0, plateauSecs = 0, plateauHeld = 0;
    long dropped = 0;
    long long maxAge = 0;
    for (const EvidenceLine &l : lines) {
        if (l.dropPacket > 500) {          // (2) a second in which the transfers came back with the packet error
            errorSecs++;
            dropped += l.dropPacket;
            CHECK(l.dropUrb == 0 && l.dropLength == 0, "a packet error was filed as %d URB / %d length errors", l.dropUrb, l.dropLength);
            CHECK(l.lastUrb == 0 && l.lastPacket == -EILSEQ && l.lastLength == 4, "the codes of the last one are %d/%d/%d, expected 0/%d/4", l.lastUrb, l.lastPacket, l.lastLength, -EILSEQ);
            CHECK(l.readings <= 5, "%d readings in a second in which every transfer was an error: a transfer with an error was counted as a reading", l.readings);
            // (the follower keeps the last valid reading, a healthy blip of at most +0.27 %: what the errors carried, +0.8 %, must not be followed)
            CHECK(std::fabs(l.sentMax - kNominal441) < kNominal441 * 0.005 && std::fabs(l.sentMin - kNominal441) < kNominal441 * 0.005,
                  "sent %.4f..%.4f while the transfers were errors: the +0.8 %% they carried must not be followed", l.sentMin, l.sentMax);
            maxAge = std::max(maxAge, l.ageMs);
        } else if (l.held > 500) {         // (3) the plateau
            plateauSecs++;
            plateauHeld += l.held;
            CHECK(l.refused == 0, "refused=%d on a plateau inside +-5 %%: refused counts only the beyond-5 %% ones", l.refused);
            CHECK(std::fabs(l.mean - 5.5575) < 0.001, "received mean %.5f on the plateau", l.mean);
            CHECK(std::fabs(l.sentMax - kNominal441) < 0.01, "sent up to %.5f while held: the packetizer should have the healthy rate", l.sentMax);
            CHECK(std::fabs(l.applied - kNominal441) < 0.01, "applied %.5f while held", l.applied);
            CHECK(l.dropPacket == 0 && l.ageMs >= 0 && l.ageMs < 100, "plateau second: dropped %d age %lld ms", l.dropPacket, l.ageMs);
        } else if (l.readings >= 900 && l.held == 0) {
            healthySecs++;
            // (a reading submitted just before a boundary is reaped just after it: the second next to the errors may hold one)
            CHECK(l.dropPacket <= 2 && l.dropUrb == 0 && l.dropLength == 0, "a healthy second dropped %d/%d/%d", l.dropUrb, l.dropPacket, l.dropLength);
            CHECK(l.ageMs >= 0 && l.ageMs < 100, "a healthy second ended %lld ms after its last reading", l.ageMs);
        }
        // the three rates are three numbers: received (mean above), given (sent), gone (applied = frames / packets)
        CHECK((l.wirePackets == 0 && l.wireFrames == 0) ||
              (l.sentCalls >= 1 && l.wirePackets > 0 && std::fabs(l.applied - (double)l.wireFrames / (double)l.wirePackets) < 1e-4), "wire %lld frames in %lld packets, applied %.5f", l.wireFrames, l.wirePackets, l.applied);
    }
    CHECK(healthySecs >= 3, "%d healthy seconds", healthySecs);
    CHECK(errorSecs >= 2 && dropped >= 2000, "%d seconds of errors, %ld dropped transfers (about a thousand a second)", errorSecs, dropped);
    CHECK(maxAge >= 2000, "the age of the last valid reading only reached %lld ms in 3 s of errors", maxAge);
    CHECK(plateauSecs >= 3 && plateauHeld >= 2900, "%d plateau seconds, %d held (the 5 s window follows it first, then refuses)", plateauSecs, plateauHeld);
    // the changes of what the policy obeys, with the stream's number, in order
    std::string dump(text);
    const size_t seqAt = dump.find("seq=");
    const long long seq = seqAt == std::string::npos ? -1 : atoll(dump.c_str() + seqAt + 4);
    CHECK(seq >= 1, "no stream number in the header");
    const size_t stuckBegin = dump.find("stuck-begin identical");
    const size_t refuseBegin = dump.find("refuse-begin sustained");
    const size_t stuckEnd = dump.find("stuck-end changed");
    CHECK(stuckBegin != std::string::npos, "no stuck-begin event: %s", dump.substr(dump.find("\nevent") == std::string::npos ? 0 : dump.find("\nevent")).c_str());
    CHECK(refuseBegin != std::string::npos, "no refuse-begin event for the sustained plateau");
    CHECK(stuckEnd != std::string::npos, "no stuck-end event when the plateau ended");
    // the stream's gestures, in the same list and in order: start, then the pause, the idle, the seek and the resume of the cycle above
    {
        const size_t gStart = dump.find(" start initial-reading policy=auto");
        const size_t gPause = dump.find(" pause fade-out policy=auto");
        // (the pause protocol announces the idle at its end, so it comes before the seek, whose own announcement is within the 100 ms
        // that one gesture of a kind is kept for)
        const size_t gIdle = dump.find(" idle announced policy=auto", gPause == std::string::npos ? 0 : gPause);
        const size_t gSeek = dump.find(" seek ", gIdle == std::string::npos ? 0 : gIdle);
        const size_t gResume = dump.find(" resume ", gSeek == std::string::npos ? 0 : gSeek);
        CHECK(gStart != std::string::npos && gPause != std::string::npos && gSeek != std::string::npos && gIdle != std::string::npos && gResume != std::string::npos,
              "gestures missing from the dump: start %d pause %d seek %d idle %d resume %d", gStart != std::string::npos, gPause != std::string::npos,
              gSeek != std::string::npos, gIdle != std::string::npos, gResume != std::string::npos);
        if (gStart != std::string::npos && gPause != std::string::npos) {
            const size_t startLine = dump.rfind("event ", gStart);
            CHECK(dump.compare(startLine, 9, "event t-1") == 0 || dump.compare(startLine, 9, "event t-2") == 0, "the start is not the oldest event: %.60s", dump.c_str() + startLine);
            CHECK(dump.find("stream=" + std::to_string(seq), startLine) < gPause, "the gestures do not name the stream");
            CHECK(gStart < gPause && gPause < gIdle && gIdle < gSeek && gSeek < gResume, "the gestures are out of order");
        }
    }
    if (stuckBegin != std::string::npos) {
        const size_t lineStart = dump.rfind("event ", stuckBegin);
        const std::string line = dump.substr(lineStart, dump.find('\n', lineStart) - lineStart);
        CHECK(line.find("stream=" + std::to_string(seq)) != std::string::npos && line.find("rate=44100") != std::string::npos, "the event does not name its stream: %s", line.c_str());
        CHECK(line.find("reading=5.5575") != std::string::npos && line.find("policy=auto") != std::string::npos, "the event lacks the reading or the policy: %s", line.c_str());
        // what the packetizer was being given before, and from now on: the plateau, then the healthy rate
        const double before = fieldOf(line, "sending=");
        const size_t arrow = line.find("->");
        const double after = arrow == std::string::npos ? -1.0 : atof(line.c_str() + arrow + 2);
        CHECK(std::fabs(before - 5.5575) < 0.001 && std::fabs(after - kNominal441) < 0.01, "sending %.5f->%.5f in the event, expected the plateau then the healthy rate", before, after);
    }
    // and a feedback transfer that was not a reading is also one rate-limited line in the log, with the codes
    const auto invalid = feedbackGlitchLines("FEEDBACK_INVALID");
    CHECK(invalid.size() >= 2 && invalid.size() <= 4, "%zu FEEDBACK_INVALID lines for 3 s of errors (one a second at most)", invalid.size());
    if (!invalid.empty()) {
        CHECK(invalid[0].find("packet-status urb=0 packet=-84 length=4 rate=44100") != std::string::npos, "the line does not say what was wrong: %s", invalid[0].c_str());
    }
    printf("feedback evidence: %zu seconds; %d healthy / %d of errors (%ld dropped, last valid reading up to %lld ms old) / %d on the plateau (%d held, refused 0); "
           "events with stream=%lld: stuck-begin, refuse-begin, stuck-end\n",
           lines.size(), healthySecs, errorSecs, dropped, maxAge, plateauSecs, plateauHeld, seq);
    setFeedbackScript(nullptr);
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
            CHECK(late.lead >= ctx->plan.ringUrbs - 20 && late.lead <= ctx->plan.ringUrbs + 5, "lead=%ld ms: the ring holds ~%d ms", late.lead, ctx->plan.ringUrbs);
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
        // B47-A2: the hold must outlast the ring's lead (128 ms: the writer's last submit is that long before the DAC would run dry) for the model to
        // find the bus dry; at 80 ms a hold of 200 ms did.
        const int64_t holdReleaseMs = 500 + ctx->plan.ringUrbs + 150;
        simclock::at(simclock::nowNs() + holdReleaseMs * 1000000LL, [] { g_usb.releaseHold(); });
        writeFor(ctx, kRate, 1300);
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

// ── B46-A4: a device whose timing the output does not drive yet is played as it always was, and the log says so ──────────────────

/** Plays one short track through a freshly created stream and returns whether the wire carries exactly its bytes. */
bool wireCarriesExactly(JNIEnv *env, jlong usb, const Pcm &pcm, int slot) {
    writePipeline(env, usb, pcm, 4096);
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(env, nullptr, usb), "residual drain failed");
    std::vector<uint8_t> want;
    for (int32_t sample : pcm.samples) {
        const uint32_t packed = static_cast<uint32_t>(sample) << (slot - pcm.bits);
        for (int byte = 0; byte < slot / 8; byte++) want.push_back(static_cast<uint8_t>(packed >> (8 * byte)));
    }
    return firstDifference(want, g_usb.audible()) < 0;
}

int countLinesWith(const char *needle) {
    std::lock_guard<std::mutex> lock(g_usb.mu);
    int n = 0;
    for (const std::string &line : g_usb.transportLines) n += line.find(needle) != std::string::npos ? 1 : 0;
    return n;
}

void testTransportAssumptions() {
    simclock::VirtualTime virtualTime;
    JNINativeInterface table = {};
    table.GetArrayLength = fakeGetArrayLength;
    table.GetByteArrayElements = fakeGetByteArrayElements;
    table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
    JNIEnv env;
    env.functions = &table;
    const Pcm pcm = makeNoise(96000 / 5 + 17, 24, 0x46a4a4a4u);

    // (a) A high-speed DAC with bInterval 1 and a four-byte feedback: the plan line, and no assumption.
    g_usb.reset(96000, 8);
    jlong usb = createStream(96000, 32);
    CHECK(wireCarriesExactly(&env, usb, pcm, 32), "the healthy stream differs");
    releaseStream(usb);
    CHECK(countLinesWith("Start: plan: interval=125 us (8000/s) nominal=96000/8000") == 1, "no plan line at the start (%zu lines)", g_usb.transportLines.size());
    CHECK(countLinesWith("ring=128 low=64 queue=138240/1142400 frames") == 1, "the plan line does not carry the ring and the queue limits");
    CHECK(countLinesWith("TRANSPORT_ASSUMPTION") == 0, "a high-speed DAC was reported as an assumption");
    {
        char text[4096];
        usbSecondSummaries(30000, text, sizeof(text));
        CHECK(strstr(text, "plan[speed=3 bInterval=1 feedbackBytes=4 interval=125us ring=128 pkts=8 assumptions=0x0]") != nullptr,
              "the capture header does not carry the plan: %.320s", text);
    }

    // (b) A full-speed DAC with bInterval 4 and a three-byte 10.14 feedback: it plays today, so it still plays, byte for byte.
    g_usb.reset(96000, 8);
    g_usb.speed = 2;
    usb = createStreamWith(96000, 32, 400, 4, 3);
    CHECK(usb != 0, "a full-speed DAC must not be refused in B46");
    if (usb != 0 && Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(nullptr, nullptr, usb)) {
        g_usb.markStreamStart();
        CHECK(wireCarriesExactly(&env, usb, pcm, 32), "the full-speed stream differs from the source");
        releaseStream(usb);
    }
    CHECK(countLinesWith("TRANSPORT_ASSUMPTION speed=2") == 1, "no assumption for full speed");
    CHECK(countLinesWith("TRANSPORT_ASSUMPTION data endpoint bInterval=4") == 1, "no assumption for bInterval 4");
    CHECK(countLinesWith("TRANSPORT_ASSUMPTION feedback packet of 3 bytes") == 1, "no assumption for the 3-byte feedback");
    {
        char text[4096];
        usbSecondSummaries(30000, text, sizeof(text));
        CHECK(strstr(text, "plan[speed=2 bInterval=4 feedbackBytes=3 interval=125us ring=128 pkts=8 assumptions=0x7]") != nullptr,
              "the capture header does not carry the assumptions: %.320s", text);
    }

    // (c) A bus whose speed cannot be read.
    g_usb.reset(96000, 8);
    g_usb.speed = -1;
    usb = createStream(96000, 32);
    releaseStream(usb);
    CHECK(countLinesWith("TRANSPORT_ASSUMPTION bus speed unknown") == 1, "no assumption for an unreadable speed");

    // (d) The refusals the output has always made are now said at the creation, not discovered as silence at the first write.
    g_usb.reset(384000, 8);
    CHECK(createStreamWith(384000, 32, 100, 1, 4) == 0, "a maxPacket of 100 bytes cannot carry 48 frames x 8 bytes and must refuse");
    CHECK(createStreamWith(96000, 20, 400, 1, 4) == 0, "a 20-bit container is not a sample size");
    printf("transport assumptions: high speed = plan line only; full speed + bInterval 4 + 3-byte feedback = still plays (bit-exact), 3 TRANSPORT_ASSUMPTION lines, "
           "assumptions=0x7 in the capture header; unreadable speed = 1 line; impossible formats refused at creation\n");
}

// ── B46-B1: a skip while playing, and the silence fed until the new position's first buffer ─────────────────────────────────────
//
// The device (B45 session, 14:44:36.839 and six more): 50-140 ms after a user skip, while the streaming thread fed silence, an OUTPUT_GAP of
// 2-9 ms with lead 1-5 ms, where=between-writes, runq 2-12 ms. The thread's order for a skip while playing is the real one: the pause fade
// (the ring ends in an 8 ms fade of the old continuation), `markIdle` (performPause announces the idle, lastSubmitMs = 0), then the paused
// seek's silence feed (`pipelineBeginSeek` -> SILENCE, `pipelineFeedSilence` in a loop), then the new item's first buffer.

struct SkipOutcome {
    int realGaps = 0;           // the fake DAC found nothing queued when a URB arrived (more than 1 ms late)
    int64_t realMaxGapUs = 0;
    size_t modelLines = 0;      // the OUTPUT_GAP lines the writer's own model reported
    std::string firstModelLine;
    uint64_t audibleHash = 0;   // FNV-1a of the whole wire with its all-zero frames removed: the audible samples
    int64_t silenceFrames = 0;  // zero frames on the wire
    int64_t minLeadFrames = 0;  // the smallest unplayed lead the silence feed saw
    int64_t leadAtNewAudioFrames = 0;  // what the new position's first buffer waits behind: the extra latency of the silence
    int64_t leadTargetFrames = 0;      // the plan's silence lead
};

/** [stallMs] > 0: the writer is not running for that long, [stallAfterMs] after the seek began. */
SkipOutcome runSkip(int rate, int stallMs, int stallAfterMs) {
    const Pcm a = makeNoise(12 * kBuf, 24, 0x5eed0001u);
    const Pcm b = makeNoise(4 * kBuf, 24, 0x5eed0002u);
    Writer w(rate);
    SkipOutcome out;
    out.leadTargetFrames = w.ctx->plan.silenceLeadFrames;
    for (int i = 0; i < 5; i++) w.write(a, i * kBuf, kBuf);   // the ring is full, a block is in hand
    CHECK(w.beginPause(), "the pause of a skip found nothing to fade");
    w.write(a, 5 * kBuf, kBuf);                               // the continuation, ramped to zero in 8 ms
    if (w.fadingOut()) w.endFadeOut();
    w.markIdle();                                             // performPause: the ring is about to run dry by design
    const int64_t skipUs = nowUs();
    CHECK(w.beginSeek() == PIPELINE_SEEK_SILENCE, "the ring should already end in the pause's fade");
    out.minLeadFrames = INT64_MAX;
    bool stalled = stallMs <= 0;
    // the decoder takes ~170 ms to deliver the new position (the device log: 168 ms, 242 steps)
    while (nowUs() - skipUs < 170 * 1000) {
        if (!stalled && nowUs() - skipUs >= (int64_t)stallAfterMs * 1000) {
            sleepMs(stallMs);
            stalled = true;
        }
        const int64_t queued = w.queuedFrames();
        if (queued < out.minLeadFrames) out.minLeadFrames = queued;
        CHECK(w.feedSilence(), "the silence feed failed");
    }
    out.leadAtNewAudioFrames = w.queuedFrames();
    w.write(b, 0, kBuf);                                       // the first buffer of the new position (faded in)
    w.write(b, kBuf, kBuf);
    const std::vector<uint8_t> wire = w.finish();              // the end of the stream: the packetizer's last partial URB goes out too
    sleepMs(300);                                              // everything submitted has been played
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        out.realGaps = g_usb.gaps;
        out.realMaxGapUs = g_usb.maxGapUs;
        out.modelLines = g_usb.outputGapLines.size();
        if (!g_usb.outputGapLines.empty()) out.firstModelLine = g_usb.outputGapLines.front();
    }
    const size_t frameBytes = 8;   // 32-bit stereo slot
    uint64_t hash = 14695981039346656037ull;
    for (size_t at = 0; at + frameBytes <= wire.size(); at += frameBytes) {
        bool zero = true;
        for (size_t i = 0; i < frameBytes; i++) zero = zero && wire[at + i] == 0;
        if (zero) { out.silenceFrames++; continue; }
        for (size_t i = 0; i < frameBytes; i++) hash = (hash ^ wire[at + i]) * 1099511628211ull;
    }
    out.audibleHash = hash;
    return out;
}

void testSkipSilenceFeed() {
    static const int kRates[] = {44100, 96000, 192000};
    static const int kStallsBelowLead[] = {0, 8, 13, 30, 45, 82, 100};   // B47-A2: 82 is the worst stall seen, 100 is 82 + 22 %
    for (int rate : kRates) {
        uint64_t reference = 0;
        for (int stall : kStallsBelowLead) {
            const SkipOutcome o = runSkip(rate, stall, 85);
            printf("B1 skip %6d Hz, writer stalled %2d ms %d ms after the seek: fake DAC gaps=%d, model OUTPUT_GAP lines=%zu, lead min %.1f ms "
                   "(target %.1f), new audio waits behind %.1f ms, silence %lld frames, audible=%016llx\n",
                   rate, stall, 85, o.realGaps, o.modelLines, o.minLeadFrames * 1000.0 / rate, o.leadTargetFrames * 1000.0 / rate,
                   o.leadAtNewAudioFrames * 1000.0 / rate, (long long)o.silenceFrames, (unsigned long long)o.audibleHash);
            // zero OUTPUT_GAP, in the model and in the fake DAC (which counts anything over 1 ms)
            CHECK(o.realGaps == 0, "%d Hz stall %d ms: the fake DAC ran dry %d times", rate, stall, o.realGaps);
            CHECK(o.modelLines == 0, "%d Hz stall %d ms: %zu OUTPUT_GAP lines (first: %s)", rate, stall, o.modelLines, o.firstModelLine.c_str());
            // the lead the stall left is the target minus the stall (and a millisecond of the URB in flight)
            const int64_t leftMs = o.leadTargetFrames * 1000 / rate - stall;
            CHECK(o.minLeadFrames * 1000 / rate >= leftMs - 2, "%d Hz stall %d ms: the lead fell to %lld ms, expected about %lld", rate, stall,
                  (long long)(o.minLeadFrames * 1000 / rate), (long long)leftMs);
            // the audible samples are the same whatever the stall and the silence in front of them
            if (stall == 0) reference = o.audibleHash;
            CHECK(o.audibleHash == reference, "%d Hz stall %d ms: the audible samples differ", rate, stall);
            // the latency the lead adds to the first audio after the skip: the plan's lead, within one URB
            CHECK(std::llabs(o.leadAtNewAudioFrames - o.leadTargetFrames) <= rate / 1000 + 2 || stall > 0,
                  "%d Hz: the new audio waits behind %lld frames, the target is %lld", rate, (long long)o.leadAtNewAudioFrames,
                  (long long)o.leadTargetFrames);
        }
        // A stall LONGER than the lead is a real dry stretch, and then the model and the fake DAC agree on it: counted exactly when the
        // bus ran dry, with the same length (to a couple of ms).
        const SkipOutcome dry = runSkip(rate, 140, 85);
        printf("B1 skip %6d Hz, writer stalled 140 ms: fake DAC gaps=%d (max %.1f ms), model lines=%zu: %s\n", rate, dry.realGaps,
               dry.realMaxGapUs / 1000.0, dry.modelLines, dry.firstModelLine.c_str());
        CHECK(dry.realGaps >= 1 && dry.modelLines >= 1, "%d Hz: a stall of 140 ms (more than the lead) ran dry in the fake DAC %d times, the model said %zu",
              rate, dry.realGaps, dry.modelLines);
        GapLine line;
        if (parseGapLine(dry.firstModelLine, &line)) {
            CHECK(std::labs(line.dry - dry.realMaxGapUs / 1000) <= 3, "%d Hz: the model's dry=%ld ms, the fake DAC's gap %lld ms", rate, line.dry,
                  (long long)(dry.realMaxGapUs / 1000));
        }
    }
}

// ── B46-B3: a late writer ─────────────────────────────────────────────────────────────────────────────────────────────────────────
//
// The writer is not running for 5, 20, 50 or 100 ms, once or three times in a stream, at 44.1, 96, 192 and 384 kHz. To the native layer a
// producer that is late and a writer that is descheduled are the same thing (it is not asked to write for a while), so one stall between two
// writes stands for both; the second kind is the wake-up that comes late inside the reap wait (the completion was there, the thread was not).
// The fake DAC keeps playing through the stall and finds the bus dry only if it really was.

enum StallWhere { STALL_BETWEEN_WRITES, STALL_IN_WAIT };

struct StallRun {
    bool bytesExact = false;
    int realGaps = 0;
    int64_t realMaxGapUs = 0;
    size_t modelLines = 0;
    std::string firstModelLine;
    int64_t leadAfterStallMs = INT64_MAX;   // what the ring held when the writer came back (between writes)
    int64_t recoveryMs = 0;                 // the longest time from the end of a stall to the ring being back at 90 % of its depth
    int maxInFlight = 0;
    int ringUrbs = 0;                       // the plan's ring depth (B47-A2: it is no longer a constant of the test)
    int64_t maxPositionErrorFrames = 0;     // |Media3's position - the frames the fake DAC has played|, sampled after every write
    bool endsEqual = false;                 // frames written == frames played == frames of the source
};

StallRun runStalls(int rate, int stallMs, int count, StallWhere where) {
    constexpr int kBlock = 2048;
    const int spacingMs = 1200;   // above the log's one-line-per-second limit, so every dry stretch gets its own line
    const int totalMs = 600 + count * spacingMs + 600;
    const int frames = (int)((int64_t)rate * totalMs / 1000 / kBlock) * kBlock;
    const Pcm src = makeNoise(frames, 24, 0x46b30000u + (uint32_t)rate);
    Writer w(rate);
    StallRun out;
    out.ringUrbs = w.ctx->plan.ringUrbs;
    const int64_t ringTargetFrames = (int64_t)rate * (out.ringUrbs * 9 / 10) / 1000;   // 90 % of the ring (a URB is 1 ms of wire time)
    const int bytesPerFrame = w.ctx->plan.bytesPerFrame;
    const int64_t startUs = nowUs();
    int stalls = 0;
    int64_t stallEndUs = -1;
    for (int at = 0; at + kBlock <= frames; at += kBlock) {
        const int64_t sinceStartMs = (nowUs() - startUs) / 1000;
        if (stalls < count && sinceStartMs >= 600 + (int64_t)stalls * spacingMs) {
            stalls++;
            if (where == STALL_BETWEEN_WRITES) {
                sleepMs(stallMs);
                out.leadAfterStallMs = std::min<int64_t>(out.leadAfterStallMs, w.queuedFrames() * 1000 / rate);
                stallEndUs = nowUs();
            } else {
                std::lock_guard<std::mutex> lock(g_usb.mu);
                g_usb.stallNextPollUs = (int64_t)stallMs * 1000;
            }
        }
        w.write(src, at, kBlock);
        if (where == STALL_IN_WAIT && stalls > 0 && stallEndUs < 0 && g_usb.stallNextPollUs == 0) stallEndUs = nowUs();
        out.maxInFlight = std::max(out.maxInFlight, w.ctx->urbsInFlight);
        const int64_t queued = w.queuedFrames();
        if (stallEndUs >= 0 && queued >= ringTargetFrames) {
            out.recoveryMs = std::max<int64_t>(out.recoveryMs, (nowUs() - stallEndUs) / 1000);
            stallEndUs = -1;
        }
        // Media3's position: the frames written, less the ones still queued (and the partial URB the packetizer holds back)
        // B48-fix1: the start's priming silence is in the ring and in the played count, but not in the audio: leave it out of both
        const int64_t played = g_usb.playedFrames(nowUs());
        const int64_t silenceQueued = std::max<int64_t>(0, w.startSilenceFrames - played);
        const int64_t position = w.ctx->framesWritten - w.ctx->residualBytes / bytesPerFrame - (queued - silenceQueued);
        const int64_t playedAudio = played - std::min<int64_t>(played, w.startSilenceFrames);
        out.maxPositionErrorFrames = std::max<int64_t>(out.maxPositionErrorFrames, std::llabs(position - playedAudio));
    }
    const std::vector<uint8_t> wire = w.finish();
    const std::vector<uint8_t> want = toUsb32(src);
    out.bytesExact = wire.size() == want.size() && firstDifference(want, wire) < 0;
    sleepMs(400);
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        out.realGaps = g_usb.gaps;
        out.realMaxGapUs = g_usb.maxGapUs;
        out.modelLines = g_usb.outputGapLines.size();
        if (!g_usb.outputGapLines.empty()) out.firstModelLine = g_usb.outputGapLines.front();
    }
    out.endsEqual = w.ctx->framesWritten == frames && g_usb.playedFrames(nowUs()) == frames + w.startSilenceFrames;   // B48-fix1: the frames played include the start's priming
    return out;
}

void testWriterRecovery() {
    static const int kRates[] = {44100, 96000, 192000, 384000};
    static const int kStalls[] = {5, 20, 50, 100, 120, 150};   // B47-A2: 120 is under the 128 ms ring, 150 over it
    for (int rate : kRates) {
        for (int stall : kStalls) {
            for (int count : {1, 3}) {
                for (int where = 0; where < 2; where++) {
                    const StallRun r = runStalls(rate, stall, count, where == 0 ? STALL_BETWEEN_WRITES : STALL_IN_WAIT);
                    printf("B3 %6d Hz stall %3d ms x%d %-14s: bytes %s, fake DAC gaps=%d (max %.1f ms), OUTPUT_GAP lines=%zu, lead after the stall %s ms, "
                           "back to 90%% in %lld ms, max %d URBs in flight, position error %lld frames, ends %s\n",
                           rate, stall, count, where == 0 ? "between-writes" : "in-wait", r.bytesExact ? "EXACT" : "DIFFER", r.realGaps,
                           r.realMaxGapUs / 1000.0, r.modelLines,
                           r.leadAfterStallMs == INT64_MAX ? "n/a" : std::to_string((long long)r.leadAfterStallMs).c_str(),
                           (long long)r.recoveryMs, r.maxInFlight, (long long)r.maxPositionErrorFrames, r.endsEqual ? "equal" : "UNEQUAL");
                    CHECK(r.bytesExact, "%d Hz stall %d x%d %d: a frame was lost, repeated or moved", rate, stall, count, where);
                    CHECK(r.endsEqual, "%d Hz stall %d x%d %d: frames written / played / source differ", rate, stall, count, where);
                    CHECK(r.maxInFlight <= r.ringUrbs, "%d Hz stall %d: %d URBs in flight, more than the ring holds (%d)", rate, stall, r.maxInFlight, r.ringUrbs);
                    CHECK(r.maxPositionErrorFrames <= rate / 1000 + 2, "%d Hz stall %d x%d %d: the position is %lld frames from what was played", rate, stall,
                          count, where, (long long)r.maxPositionErrorFrames);
                    if (stall + 3 < r.ringUrbs) {
                        // under the ring's depth the bus never ran dry, and the model says nothing
                        CHECK(r.realGaps == 0 && r.modelLines == 0, "%d Hz stall %d x%d %d: fake DAC gaps=%d, OUTPUT_GAP lines=%zu (first: %s)", rate, stall, count,
                              where, r.realGaps, r.modelLines, r.firstModelLine.c_str());
                    } else {
                        // a stall longer than the ring is a real dry stretch, counted by both, once per stall
                        CHECK(r.realGaps == count && (int)r.modelLines == count, "%d Hz stall %d x%d %d: fake DAC gaps=%d, OUTPUT_GAP lines=%zu", rate, stall,
                              count, where, r.realGaps, r.modelLines);
                        GapLine line;
                        if (parseGapLine(r.firstModelLine, &line)) {
                            CHECK(std::labs(line.dry - r.realMaxGapUs / 1000) <= 3, "%d Hz: the model's dry=%ld ms, the fake DAC's longest gap %lld ms", rate, line.dry,
                                  (long long)(r.realMaxGapUs / 1000));
                        }
                    }
                    // back to its depth within the time a block takes to be written, plus the stall's own wake-up
                    CHECK(r.recoveryMs <= 2LL * 2048 * 1000 / rate + 5, "%d Hz stall %d: %lld ms to get back to depth", rate, stall, (long long)r.recoveryMs);
                    if (where == 0 && stall + 3 < r.ringUrbs) {
                        CHECK(r.leadAfterStallMs >= r.ringUrbs - stall - 3, "%d Hz stall %d: the stall left %lld ms of the %d", rate, stall, (long long)r.leadAfterStallMs, r.ringUrbs);
                    }
                }
            }
        }
    }
}


// ── B47-A2: a lead that survives the worst stall seen ───────────────────────────────────────────────────────────────────────────────
//
// 2026-10-29 17:32:33: the writer was runnable and not running for 82 ms (`OUTPUT_GAP gap=83 ms lead=80 ms runq=82 ms cpu=0 ms`) and the 80 ms ring
// ran out for ~3 ms. The target of the plan is a stall of 100 ms (the 82 + 22 %) that leaves the DAC with data, in steady playback and right after a
// skip, and one of 120 ms in steady playback. Every number printed here is the host simulation's (virtual clock, fake DAC that knows when it ran
// dry), not a device number.

void testLeadSurvivesTheWorstStall() {
    static const int kRates[] = {44100, 96000, 192000, 384000};
    static const int kStalls[] = {82, 100, 120};
    for (int rate : kRates) {
        const uint64_t reference = runSkip(rate, 0, 85).audibleHash;
        for (int stall : kStalls) {
            const StallRun mid = runStalls(rate, stall, 1, STALL_BETWEEN_WRITES);
            const SkipOutcome skip = runSkip(rate, stall, 85);
            const int leadMs = (int)(skip.leadTargetFrames * 1000 / rate);
            printf("A2 %6d Hz, writer stalled %3d ms (ring %d ms, skip lead %d ms): mid-stream: fake DAC dry %d x (max %.1f ms), bytes %s; "
                   "right after a skip: dry %d x (max %.1f ms), audible samples %s\n",
                   rate, stall, mid.ringUrbs, leadMs, mid.realGaps, mid.realMaxGapUs / 1000.0, mid.bytesExact ? "EXACT" : "DIFFER",
                   skip.realGaps, skip.realMaxGapUs / 1000.0, skip.audibleHash == reference ? "equal" : "DIFFER");
            CHECK(mid.bytesExact && mid.endsEqual, "%d Hz stall %d: a frame was lost, repeated or moved", rate, stall);
            CHECK(skip.audibleHash == reference, "%d Hz stall %d: the audible samples after a skip differ", rate, stall);
            // the target: 120 ms in steady playback, 100 ms right after a skip, both with the DAC still fed
            CHECK(mid.realGaps == 0, "%d Hz: a writer stalled %d ms mid-stream left the DAC dry %d times (max %.1f ms; the ring holds %d ms)", rate, stall,
                  mid.realGaps, mid.realMaxGapUs / 1000.0, mid.ringUrbs);
            if (stall <= 100) {
                CHECK(skip.realGaps == 0, "%d Hz: a writer stalled %d ms right after a skip left the DAC dry %d times (max %.1f ms; the lead is %d ms)", rate, stall,
                      skip.realGaps, skip.realMaxGapUs / 1000.0, leadMs);
            } else {
                // past the silence lead the dry stretch is the part of the stall the lead did not cover
                CHECK(skip.realMaxGapUs / 1000 <= stall - leadMs + 3, "%d Hz stall %d after a skip: dry %.1f ms, more than the %d ms beyond the lead", rate, stall,
                      skip.realMaxGapUs / 1000.0, stall - leadMs);
            }
        }
    }
}

/** What a pause, a seek and a skip cost in latency: the ring's lead when the request arrives, then the 8 ms fade behind it. */
// ── B47-A1: who had the CPU ─────────────────────────────────────────────────────────────────────────────────────────────────────────

void testWhoHadTheCpu() {
    constexpr int kRate = 48000;
    const Pcm src = makeNoise(4 * 2048, 24, 0xa1000001u);
    Writer w(kRate);
    for (int at = 0; at < 4 * 2048; at += 2048) w.write(src, at, 2048);
    std::vector<std::string> lines;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        lines = g_usb.schedulingLines;
    }
    // the first write of a writer thread says what its scheduling is, once per stream and thread
    size_t writerLines = 0;
    std::string writerLine;
    for (const std::string &l : lines) {
        if (l.find("Writer thread:") != std::string::npos) { writerLines++; writerLine = l; }
    }
    printf("A1 writer scheduling line: %s\n", writerLine.c_str());
    CHECK(writerLines == 1, "%zu Writer thread lines for one stream and one writer thread", writerLines);
    CHECK(writerLine.find("policy=OTHER") != std::string::npos && writerLine.find("allowed=") != std::string::npos && writerLine.find("lastCpu=") != std::string::npos &&
              writerLine.find("cpuset=") != std::string::npos && writerLine.find("uclamp=") != std::string::npos,
          "the scheduling line misses a field: %s", writerLine.c_str());
    // the extra gap line: where the writer ran and may run, each CPU's share where /proc/stat is readable, and this process's busiest threads
    const int64_t before = nowUs();
    (void)before;
    usbReportOutputGapCpu(w.ctx, 82000, 0);
    std::string gapLine;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        for (const std::string &l : g_usb.schedulingLines) {
            if (l.find("runq=82 ms cpu=0 ms") != std::string::npos) gapLine = l;
        }
    }
    printf("A1 OUTPUT_GAP_CPU line: %s\n", gapLine.c_str());
    CHECK(!gapLine.empty(), "no OUTPUT_GAP_CPU line for a writer that sat 82 ms in the run queue");
    CHECK(gapLine.find("lastCpu=") != std::string::npos && gapLine.find("allowed=") != std::string::npos && gapLine.find("cpuBusy[") != std::string::npos &&
              gapLine.find("ownThreads[") != std::string::npos && gapLine.find("rate=48000") != std::string::npos,
          "the extra line misses a field: %s", gapLine.c_str());
}

void testLeadLatencyOfGestures() {
    static const int kRates[] = {44100, 96000, 192000, 384000};
    for (int rate : kRates) {
        const Pcm src = makeNoise(rate, 24, 0xa2000000u + (uint32_t)rate);
        Writer w(rate);
        for (int at = 0; at + 2048 <= rate / 2; at += 2048) w.write(src, at, 2048);
        const int64_t queuedAtRequest = w.queuedFrames();
        const int64_t fadeFrames = w.ctx->plan.fadeOutFrames;
        printf("A2 latency %6d Hz: ring holds %.1f ms when a pause/seek/skip arrives, +%.1f ms fade = the cut is heard %.1f ms later; "
               "the first audio after a skip waits behind %.1f ms of silence\n",
               rate, queuedAtRequest * 1000.0 / rate, fadeFrames * 1000.0 / rate, (queuedAtRequest + fadeFrames) * 1000.0 / rate,
               w.ctx->plan.silenceLeadFrames * 1000.0 / rate);
        CHECK(queuedAtRequest * 1000 / rate >= w.ctx->plan.ringUrbs - 3 && queuedAtRequest * 1000 / rate <= w.ctx->plan.ringUrbs,
              "%d Hz: the ring holds %lld ms, the plan's ring is %d", rate, (long long)(queuedAtRequest * 1000 / rate), w.ctx->plan.ringUrbs);
    }
}

// ── B47-A3: a resume primes the ring ────────────────────────────────────────────────────────────────────────────────────────────────
//
// 2026-10-29 17:25:19 (and 14:46:49 in the B45 session): a resume after a long pause, the first URB went out alone and the next one 7 ms later
// (`RING_EMPTY` + `OUTPUT_GAP dry=6 ms inFlight=1 where=in-write`). The simulation does the same: the pause ends in a fade whose block leaves only a few frames
// for the resume to replay, the ring plays out, the resume replays them, and the thread needs 7 ms to bring the next buffer.

struct ResumeOutcome {
    int gapsAfterResume = 0;          // empty stretches the fake DAC found from the resume on (the pause's own idle is before it and is not counted)
    int64_t maxGapAfterResumeUs = 0;
    size_t modelLines = 0;
    int ringEmptyLogs = 0;
    int64_t silenceBeforeAudio = 0;   // zero frames on the wire right before the replayed block's first sample
    int64_t fedSilenceFrames = 0;     // what the resume fed (the stream's own count)
    bool audibleEqual = false;
};

ResumeOutcome runResume(int rate, int stallMs, int pausedMs) {
    constexpr int kBlock = 2048;
    const int keptFrames = 100;   // what the pause's block leaves for the resume: ~2 URBs
    const Pcm src = makeNoise(rate + 8 * kBlock, 24, 0xa3000000u + (uint32_t)rate);
    Writer w(rate);
    ResumeOutcome out;
    const int fade = w.ctx->plan.fadeOutFrames;
    int next = 0;
    for (; next < 6 * kBlock; next += kBlock) w.write(src, next, kBlock);
    CHECK(w.beginPause(), "the pause found nothing to fade");
    w.write(src, next, fade + keptFrames);                 // the continuation: 8 ms ramped, `keptFrames` kept for the resume
    next += fade + keptFrames;
    if (w.fadingOut()) w.endFadeOut();
    w.markIdle();
    listen(pausedMs);                                      // paused: the ring plays out and the DAC idles
    const int64_t silenceBefore = w.stat(5);
    size_t gapsBefore;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        gapsBefore = g_usb.gapList.size();
    }
    w.resume();                                            // primes the ring, then replays the kept frames, faded in
    out.fedSilenceFrames = w.stat(5) - silenceBefore;
    size_t gapsAtFirstUrbs;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        gapsAtFirstUrbs = g_usb.gapList.size();   // the first URB of the resume ends the pause's idle: that gap is the pause, by design
    }
    sleepMs(stallMs);                                      // the thread brings the next buffer
    w.write(src, next, kBlock);
    next += kBlock;
    const std::vector<uint8_t> wire = w.finish();
    sleepMs(300);
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        (void)gapsBefore;
        for (size_t i = gapsAtFirstUrbs; i < g_usb.gapList.size(); i++) {
            out.gapsAfterResume++;
            out.maxGapAfterResumeUs = std::max(out.maxGapAfterResumeUs, g_usb.gapList[i]);
        }
        out.modelLines = g_usb.outputGapLines.size();
        out.ringEmptyLogs = g_usb.ringEmptyLogs;
    }
    // The audio after the resume is the end of the wire: the kept block faded in over 15 ms, then the next buffer untouched.
    const int keptFrom = next - kBlock - keptFrames;
    const int fadeIn = w.ctx->plan.fadeInFrames;
    const int rampFrames = std::min(fadeIn, keptFrames + kBlock);
    std::vector<uint8_t> want;
    appendRamped(want, src, keptFrom, rampFrames, 0, fadeIn, true);
    appendUsb(want, src, keptFrom + rampFrames, keptFrames + kBlock - rampFrames);
    const size_t frameBytes = 8;
    if (wire.size() >= want.size()) {
        const size_t audioAt = wire.size() - want.size();
        const std::vector<uint8_t> got(wire.begin() + (long)audioAt, wire.end());
        out.audibleEqual = firstDifference(want, got) < 0;
        for (size_t at = audioAt; at >= frameBytes; at -= frameBytes) {
            bool zero = true;
            for (size_t i = 0; i < frameBytes; i++) zero = zero && wire[at - frameBytes + i] == 0;
            if (!zero) break;
            out.silenceBeforeAudio++;
        }
    }
    return out;
}

ResumeOutcome runPauseKeepalive(int rate, int stallMs, int pausedMs, bool seek) {
    constexpr int kBlock = 2048;
    const int keptFrames = 100;   // what the pause's block leaves for the resume: ~2 URBs
    const Pcm src = makeNoise(rate + 8 * kBlock, 24, 0xa3000000u + (uint32_t)rate);
    Writer w(rate);
    ResumeOutcome out;
    const int fade = w.ctx->plan.fadeOutFrames;
    int next = 0;
    for (; next < 6 * kBlock; next += kBlock) w.write(src, next, kBlock);
    CHECK(w.beginPause(), "the pause found nothing to fade");
    w.write(src, next, fade + keptFrames);                 // the continuation: 8 ms ramped, `keptFrames` kept for the resume
    next += fade + keptFrames;
    if (w.fadingOut()) w.endFadeOut();
    const int64_t pausedAtUs = nowUs();
    const int64_t countedAtPause = w.ctx->framesWritten;
#ifdef B48FIX2_BEFORE
    const int keepaliveMs = 0;   // HEAD's transport has no pause window; its writer marks idle immediately.
#else
    const int keepaliveMs = w.ctx->plan.pauseKeepaliveMs;
#endif
    while ((nowUs() - pausedAtUs) / 1000 < std::min(pausedMs, keepaliveMs)) {
        CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativePipelineFeedSilence(nullptr, nullptr, w.usb), "pause silence step failed");
    }
    CHECK(w.ctx->framesWritten == countedAtPause, "pause silence moved framesWritten");
    if (pausedMs >= keepaliveMs) w.markIdle();
    if (pausedMs > keepaliveMs) listen(pausedMs - keepaliveMs);
    if (seek) {
        CHECK(w.beginSeek() == PIPELINE_SEEK_SILENCE, "seek did not take over the pause's faded ring");
        CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativePipelineFeedSilence(nullptr, nullptr, w.usb), "seek silence failed");
    }
    // End the dry interval for measurement before the resume's priming (not starvation for an announced idle).
    const int64_t pauseDryUs = g_usb.gapUs + std::max<int64_t>(0, nowUs() - g_usb.lastCompleteUs);
    printf("B48-fix2 native pause=%dms seek=%d silenceWindow=%dms DAC dry=%.3fms counted=%lld\n",
           pausedMs, seek ? 1 : 0, keepaliveMs, pauseDryUs / 1000.0, (long long)(w.ctx->framesWritten - countedAtPause));
    if (pausedMs < 2000) CHECK(pauseDryUs == 0, "short pause/seek left DAC dry %.3fms", pauseDryUs / 1000.0);
    if (pausedMs == 3000) CHECK(pauseDryUs > 750000 && pauseDryUs < 1000000, "timeout dry time %.3fms", pauseDryUs / 1000.0);
    const int64_t silenceBefore = w.stat(5);
    size_t gapsBefore;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        gapsBefore = g_usb.gapList.size();
    }
    w.resume();                                            // primes the ring, then replays the kept frames, faded in
    out.fedSilenceFrames = w.stat(5) - silenceBefore;
    size_t gapsAtFirstUrbs;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        gapsAtFirstUrbs = g_usb.gapList.size();   // the first URB of the resume ends the pause's idle: that gap is the pause, by design
    }
    sleepMs(stallMs);                                      // the thread brings the next buffer
    w.write(src, next, kBlock);
    next += kBlock;
    const std::vector<uint8_t> wire = w.finish();
    sleepMs(300);
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        (void)gapsBefore;
        for (size_t i = gapsAtFirstUrbs; i < g_usb.gapList.size(); i++) {
            out.gapsAfterResume++;
            out.maxGapAfterResumeUs = std::max(out.maxGapAfterResumeUs, g_usb.gapList[i]);
        }
        out.modelLines = g_usb.outputGapLines.size();
        out.ringEmptyLogs = g_usb.ringEmptyLogs;
    }
    // The audio after the resume is the end of the wire: the kept block faded in over 15 ms, then the next buffer untouched.
    const int replayFrames = seek ? 0 : keptFrames;
    const int keptFrom = next - kBlock - replayFrames;
    const int fadeIn = w.ctx->plan.fadeInFrames;
    const int rampFrames = std::min(fadeIn, replayFrames + kBlock);
    std::vector<uint8_t> want;
    appendRamped(want, src, keptFrom, rampFrames, 0, fadeIn, true);
    appendUsb(want, src, keptFrom + rampFrames, replayFrames + kBlock - rampFrames);
    const size_t frameBytes = 8;
    if (wire.size() >= want.size()) {
        const size_t audioAt = wire.size() - want.size();
        const std::vector<uint8_t> got(wire.begin() + (long)audioAt, wire.end());
        out.audibleEqual = firstDifference(want, got) < 0;
        for (size_t at = audioAt; at >= frameBytes; at -= frameBytes) {
            bool zero = true;
            for (size_t i = 0; i < frameBytes; i++) zero = zero && wire[at - frameBytes + i] == 0;
            if (!zero) break;
            out.silenceBeforeAudio++;
        }
    }
    return out;
}

void testPauseKeepalive() {
    for (int pausedMs : {200, 1500, 3000}) {
        const ResumeOutcome o = runPauseKeepalive(44100, 0, pausedMs, false);
        CHECK(o.audibleEqual, "pause %dms changed resumed samples", pausedMs);
        if (pausedMs < 2000) CHECK(o.fedSilenceFrames == 0, "short pause owed priming %lld frames", (long long)o.fedSilenceFrames);
        CHECK(o.modelLines == 0 && o.ringEmptyLogs == 0, "pause/idle reported starvation");
    }
    const ResumeOutcome seek = runPauseKeepalive(44100, 0, 200, true);
    CHECK(seek.audibleEqual, "seek during pause changed resumed samples");
}

// The Kotlin writer owns the pause budget. Replay its before/after scheduling here through the real
// JNI silence/seek/resume paths; B48Fix3PauseSeekKeepaliveTest exercises the actual Kotlin decisions.
void testPauseSeekKeepalive() {
    constexpr int kBlock = 2048;
    const Pcm src = makeNoise(10 * kBlock, 24, 0xa3000042u);
    Writer w(44100);
    for (int next = 0; next < 6 * kBlock; next += kBlock) w.write(src, next, kBlock);
    CHECK(w.beginPause(), "skip pause found nothing to fade");
    w.write(src, 6 * kBlock, w.ctx->plan.fadeOutFrames + 100);
    if (w.fadingOut()) w.endFadeOut();
    const int64_t pausedAtUs = nowUs();
    const int64_t countedAtPause = w.ctx->framesWritten;
    const auto feedUntil = [&](int ms) {
        while (nowUs() - pausedAtUs < ms * 1000LL) {
            CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativePipelineFeedSilence(nullptr, nullptr, w.usb), "skip silence step failed");
        }
    };
    feedUntil(42);
    CHECK(w.beginSeek() == PIPELINE_SEEK_SILENCE, "skip seek did not take over the pause silence");
    feedUntil(192);   // the new position's first buffer is ready 150 ms after the seek, still paused
#ifdef B48FIX3_BEFORE
    w.markIdle();    // HEAD's Kotlin writer has already used this pauseSerial and idles here
    listen((int)((380000 - (nowUs() - pausedAtUs)) / 1000));
#else
    feedUntil(380);  // the same pause budget continues until resume, without marking idle
#endif
    const int64_t dryUs = g_usb.gapUs + std::max<int64_t>(0, nowUs() - g_usb.lastCompleteUs);
    CHECK(w.ctx->framesWritten == countedAtPause, "skip silence counted as audio");
    const int64_t silenceBeforeResume = w.stat(5);
    w.resume();
    const int64_t primingFrames = w.stat(5) - silenceBeforeResume;
    w.write(src, 8 * kBlock, kBlock);   // only the new position's source, never the discarded kept tail
    CHECK(w.ctx->framesWritten == countedAtPause + kBlock, "skip framesWritten includes silence or discarded audio");
    const std::vector<uint8_t> wire = w.finish();
    const int fadeIn = w.ctx->plan.fadeInFrames;
    std::vector<uint8_t> want;
    appendRamped(want, src, 8 * kBlock, fadeIn, 0, fadeIn, true);
    appendUsb(want, src, 8 * kBlock + fadeIn, kBlock - fadeIn);
    bool equal = false;
    if (wire.size() >= want.size()) {
        const std::vector<uint8_t> got(wire.end() - (long)want.size(), wire.end());
        equal = firstDifference(want, got) < 0;
    }
    printf("B48-fix3 native seek=42ms newBuffer=192ms resume=380ms DAC dry=%.3fms priming=%lld frames audible=%s\n",
           dryUs / 1000.0, (long long)primingFrames, equal ? "EQUAL" : "DIFFERENT");
    CHECK(dryUs == 0, "paused skip left DAC dry %.3fms", dryUs / 1000.0);
    CHECK(primingFrames == 0, "paused skip owed %lld frames of resume priming", (long long)primingFrames);
    CHECK(equal, "paused skip changed the new position's samples");
}


void testResumePrimesTheRing() {
    static const int kRates[] = {44100, 96000, 192000};
    for (int rate : kRates) {
        const ResumeOutcome o = runResume(rate, 7, 400);
        const int64_t leadFrames = (int64_t)rate * 50 / 1000;
        printf("A3 %6d Hz, resume after a pause, 7 ms between the first URBs and the next buffer: fake DAC dry after the resume %d x (max %.1f ms), "
               "OUTPUT_GAP lines=%zu, RING_EMPTY=%d, silence before the audio %lld frames (%.1f ms; fed %lld), audible stream %s\n",
               rate, o.gapsAfterResume, o.maxGapAfterResumeUs / 1000.0, o.modelLines, o.ringEmptyLogs, (long long)o.silenceBeforeAudio,
               o.silenceBeforeAudio * 1000.0 / rate, (long long)o.fedSilenceFrames, o.audibleEqual ? "EQUAL to the designed one" : "DIFFERENT");
        CHECK(o.gapsAfterResume == 0, "%d Hz: a resume with a 7 ms stall left the DAC dry %d times (max %.1f ms)", rate, o.gapsAfterResume,
              o.maxGapAfterResumeUs / 1000.0);
        CHECK(o.modelLines == 0, "%d Hz: the resume reported %zu OUTPUT_GAP lines", rate, o.modelLines);
        CHECK(o.ringEmptyLogs == 0, "%d Hz: the resume reported %d RING_EMPTY lines", rate, o.ringEmptyLogs);
        CHECK(o.audibleEqual, "%d Hz: the audio after the resume is not the designed one (fade-in of the kept block, then the next buffer)", rate);
        // the latency the priming adds to a resume: the plan's resume lead, to the silence chunk (the fade's own near-zero first frames may count too)
        CHECK(o.silenceBeforeAudio >= leadFrames - 1 && o.silenceBeforeAudio <= leadFrames + rate / 1000 + 4,
              "%d Hz: %lld frames of silence before the audio, the resume lead is %lld", rate, (long long)o.silenceBeforeAudio, (long long)leadFrames);
        CHECK(o.fedSilenceFrames >= leadFrames && o.fedSilenceFrames <= leadFrames + rate / 1000 + 2, "%d Hz: the resume fed %lld frames of silence, the lead is %lld",
              rate, (long long)o.fedSilenceFrames, (long long)leadFrames);
    }
}


// ── B47-A5: a locked-up DAC, detected and brought back ─────────────────────────────────────────────────────────────────────────────
//
// The fake DAC locks after a real underrun (feedback +4.99 %, completions ~30 ms late) and gets out of it only on a SETINTERFACE cycle (variant 1), only on a bus reset
// (variant 2), or never (variant 3). The writer here does what UsbStreamingThread does (a pause-style fade of the continuation, the native half before the interface
// restart, the restart, the native half after it, the resume), in the same order and through the same entry points; whether to run a step is the native policy's
// answer (`usbDacLockState`), the one the Kotlin writer asks. Times are the virtual clock's.

struct LockRun {
    bool locked = false;                 // the fake DAC locked up
    int64_t lockToDetectMs = -1;         // from the underrun to the writer's first look that says "locked"
    int64_t lockToStepEndMs = -1;        // ... to the end of the first step
    int64_t lockToUnlockedMs = -1;       // ... to the fake DAC being out of it
    int stepsRun = 0;
    int step1 = 0, step2 = 0, gaveUp = 0;
    int setInterfaceCalls = 0, resetCalls = 0;
    int64_t stepsMs = 0;                 // wall (virtual) time the steps took, writer side
    bool bytesExact = false;
    bool positionContinuous = false;
    int64_t silenceFrames = 0;
    size_t daclockedLines = 0, recoveryLines = 0;
    std::vector<std::string> recoveryText;
};

std::vector<std::string> g_logLinesSeen;

void runOneStep(Writer &w, const Pcm &src, int &next, int step, int block, const std::function<void()> &afterRestart = nullptr) {
    // performPause: the fade of the continuation behind the ring, then the writer idles (announced)
    if (w.beginPause()) {
        while (w.fadingOut() && next + block <= src.frames()) {
            w.write(src, next, block);
            next += block;
        }
        if (w.fadingOut()) w.endFadeOut();
    }
    w.markIdle();
    const int64_t startUs = nowUs();
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryBegin(nullptr, nullptr, w.usb) == JNI_TRUE, "the recovery's first half did not empty the ring");
    // UsbAudioDevice.setAltSetting(0), then the format's alt again (the same SETINTERFACE the sink makes)
    usbdevfs_setinterface si = {};
    si.interface = 1;
    si.altsetting = 0;
    ioctl(kFakeFd, USBDEVFS_SETINTERFACE, &si);
    if (step == 2) {
        Java_com_decent_usbaudio_UsbAudioStream_nativeUsbResetForRebind(nullptr, nullptr, kFakeFd);
        int interfaceNumber = 1;
        ioctl(kFakeFd, USBDEVFS_CLAIMINTERFACE, &interfaceNumber);
    }
    si.altsetting = 1;
    ioctl(kFakeFd, USBDEVFS_SETINTERFACE, &si);
    CHECK(Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryEnd(nullptr, nullptr, w.usb) == JNI_TRUE, "the recovery's second half did not re-arm the stream");
    Java_com_decent_usbaudio_UsbAudioStream_nativeNoteDacRecovery(nullptr, nullptr, w.usb, step, 0, (nowUs() - startUs) / 1000);
    if (afterRestart) afterRestart();   // B48-fix1: a test's moment "the interface has restarted" (the start of the guard and of the stall after the first URB)
    w.resume();   // the kept block of the fade, faded in, after the priming silence
}

LockRun runLockedDac(int rate, int lockMode, int playMs) {
    constexpr int kBlock = 2048;
    const int frames = (int)((int64_t)rate * (playMs + 3000) / 1000 / kBlock) * kBlock;
    const Pcm src = makeNoise(frames, 24, 0xa5000000u + (uint32_t)rate + (uint32_t)lockMode);
    Writer w(rate);
    g_usb.lockMode = lockMode;
    g_usb.feedbackFpmf = rate / 8000.0;   // a DAC that answers its nominal (the fake's default is a 96 kHz answer, outside +-5 % at every other rate)
    usbDacRecoveryForgetEpisodes();
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    LockRun out;
    int next = 0;
    int64_t silenceAtStart = w.stat(5);
    const int64_t startUs = nowUs();
    bool underrunDone = false;
    int64_t detectedAtUs = -1, firstStepEndUs = -1;
    int64_t stepMs = 0;
    for (; next + kBlock <= frames && (nowUs() - startUs) / 1000 < playMs;) {
        const int64_t sinceMs = (nowUs() - startUs) / 1000;
        if (!underrunDone && sinceMs >= 1500) {
            underrunDone = true;
            {
                std::lock_guard<std::mutex> lock(g_usb.mu);
                g_usb.lockArmed = true;
            }
            sleepMs(w.ctx->plan.ringUrbs + 25);   // the writer is not running for longer than the ring holds: a real underrun of ~25 ms
        }
        w.write(src, next, kBlock);
        next += kBlock;
        int64_t state[USB_DAC_STATE_COUNT];
        usbDacLockState(w.ctx, state);
        if (state[0] != 0 && detectedAtUs < 0) detectedAtUs = nowUs();
        const int step = (int)state[5];
        if (step == USB_DAC_STEP_INTERFACE || step == USB_DAC_STEP_BUS_RESET) {
            const int64_t t0 = nowUs();
            runOneStep(w, src, next, step, kBlock);
            stepMs += (nowUs() - t0) / 1000;
            out.stepsRun++;
            if (step == 1) out.step1++; else out.step2++;
            if (firstStepEndUs < 0) firstStepEndUs = nowUs();
        } else if (step == USB_DAC_STEP_GIVE_UP) {
            out.gaveUp++;
        }
    }
    const std::vector<uint8_t> wire = w.finish();
    sleepMs(300);
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        out.locked = g_usb.lockedAtUs > 0;
        if (g_usb.lockedAtUs > 0) {
            if (detectedAtUs >= 0) out.lockToDetectMs = (detectedAtUs - g_usb.lockedAtUs) / 1000;
            if (firstStepEndUs >= 0) out.lockToStepEndMs = (firstStepEndUs - g_usb.lockedAtUs) / 1000;
            if (g_usb.unlockedAtUs > 0) out.lockToUnlockedMs = (g_usb.unlockedAtUs - g_usb.lockedAtUs) / 1000;
        }
        out.setInterfaceCalls = g_usb.setInterfaceCalls;
        out.resetCalls = g_usb.resetCalls;
    }
    out.stepsMs = stepMs;
    out.silenceFrames = w.stat(5) - silenceAtStart;
    out.positionContinuous = w.ctx->framesWritten == next;   // every frame of the source counted once: Media3's position (a function of the count) never jumps or repeats
    // The audible stream is the source in order: with the zero frames (the pause-style fades' silence and the resume's priming) taken out, every source frame follows the
    // one before it. Compared as: the wire's non-zero frames == the source's non-zero frames, minus the ramps (checked as a subsequence by value).
    const std::vector<uint8_t> want = toUsb32(src);
    size_t wi = 0;
    bool inOrder = true;
    int64_t unmatched = 0;
    const size_t fb = 8;
    // walk the wire: each non-zero wire frame is either an unmodified source frame, or part of a ramp (modified); count the unmodified ones and require that they appear in source order
    size_t sourceAt = 0;
    int64_t unmodified = 0;
    for (size_t at = 0; at + fb <= wire.size(); at += fb) {
        bool zero = true;
        for (size_t i = 0; i < fb; i++) zero = zero && wire[at + i] == 0;
        if (zero) continue;
        // find this frame in the source at or after `sourceAt` (within a window: ramps and priming keep it close)
        bool found = false;
        for (size_t probe = sourceAt; probe < sourceAt + 20000 * fb && probe + fb <= want.size(); probe += fb) {
            if (memcmp(&want[probe], &wire[at], fb) == 0) {
                sourceAt = probe + fb;
                found = true;
                unmodified++;
                break;
            }
        }
        if (!found) unmatched++;   // a ramp frame (fade out or in): modified by design
    }
    (void)wi;
    (void)inOrder;
    out.bytesExact = unmatched <= 2 * (int64_t)(w.ctx->plan.fadeOutFrames + w.ctx->plan.fadeInFrames) * (out.stepsRun + 1) && unmodified > 0;
    return out;
}

void testLockedDacRecovery() {
    usbSetPollWait(true);
    static const int kRates[] = {44100, 96000};
    struct Variant { int mode; const char *name; };
    static const Variant kVariants[] = {{1, "recovers on SETINTERFACE"}, {2, "recovers only on a bus reset"}, {3, "never recovers"}};
    for (int rate : kRates) {
        for (const Variant &v : kVariants) {
            const LockRun r = runLockedDac(rate, v.mode, 22000);
            printf("A5 %6d Hz, DAC %-30s: locked=%d, detected %lld ms after the underrun, first step ended %lld ms after it, DAC out of it %lld ms after it; steps run %d (1:%d 2:%d), "
                   "gave up %d, SETINTERFACE x%d, bus reset x%d, writer spent %lld ms in steps, silence %lld frames, frames counted once %s, audible stream %s\n",
                   rate, v.name, r.locked ? 1 : 0, (long long)r.lockToDetectMs, (long long)r.lockToStepEndMs, (long long)r.lockToUnlockedMs, r.stepsRun, r.step1, r.step2,
                   r.gaveUp, r.setInterfaceCalls, r.resetCalls, (long long)r.stepsMs, (long long)r.silenceFrames, r.positionContinuous ? "yes" : "NO", r.bytesExact ? "in order" : "BROKEN");
            CHECK(r.locked, "%d Hz %s: the fake DAC did not lock up", rate, v.name);
            CHECK(r.positionContinuous, "%d Hz %s: the frame count (Media3's position) is not the frames of the source", rate, v.name);
            CHECK(r.bytesExact, "%d Hz %s: the audible stream is not the source in order", rate, v.name);
            // detected in a second and a half of rail plus a block, and the first step done a few hundred ms later: "a second or two", not minutes
            CHECK(r.lockToDetectMs >= USB_DAC_LOCK_RAIL_MS - 100 && r.lockToDetectMs <= USB_DAC_LOCK_RAIL_MS + 700, "%d Hz %s: detected %lld ms after the underrun", rate, v.name,
                  (long long)r.lockToDetectMs);
            CHECK(r.lockToStepEndMs >= 0 && r.lockToStepEndMs <= 2800, "%d Hz %s: the first step ended %lld ms after the underrun", rate, v.name, (long long)r.lockToStepEndMs);
            CHECK(r.step1 == 1, "%d Hz %s: step 1 ran %d times", rate, v.name, r.step1);
            if (v.mode == 1) {
                CHECK(r.stepsRun == 1 && r.step2 == 0 && r.gaveUp == 0 && r.resetCalls == 0, "%d Hz: a DAC that SETINTERFACE cures took %d steps (step 2 x%d, reset x%d, gave up %d)",
                      rate, r.stepsRun, r.step2, r.resetCalls, r.gaveUp);
                CHECK(r.lockToUnlockedMs >= 0 && r.lockToUnlockedMs <= 2800, "%d Hz: the DAC was out of it %lld ms after the underrun", rate, (long long)r.lockToUnlockedMs);
            } else if (v.mode == 2) {
                CHECK(r.step2 == 1 && r.stepsRun == 2 && r.gaveUp == 0 && r.resetCalls == 1, "%d Hz: a DAC that only a reset cures: step 2 x%d, steps %d, reset x%d, gave up %d", rate, r.step2,
                      r.stepsRun, r.resetCalls, r.gaveUp);
                CHECK(r.lockToUnlockedMs >= 0 && r.lockToUnlockedMs <= 5500, "%d Hz: out of it %lld ms after the underrun", rate, (long long)r.lockToUnlockedMs);
            } else {
                CHECK(r.step2 == 1 && r.stepsRun == 2 && r.gaveUp == 1 && r.resetCalls == 1, "%d Hz: a DAC that never recovers: step 2 x%d, steps %d, gave up %d, reset x%d", rate, r.step2,
                      r.stepsRun, r.gaveUp, r.resetCalls);
            }
        }
    }
    // a healthy DAC that has the same underrun does nothing at all
    {
        const LockRun r = runLockedDac(44100, 0, 12000);
        printf("A5 healthy DAC with the same underrun: locked=%d, steps run %d, SETINTERFACE x%d\n", r.locked ? 1 : 0, r.stepsRun, r.setInterfaceCalls);
        CHECK(!r.locked && r.stepsRun == 0 && r.setInterfaceCalls == 0, "a healthy DAC was recovered (steps %d)", r.stepsRun);
    }
    // B48-fix2: every clean rail is detected, even if a wrong format caused it; recovery remains bounded.
    {
        Writer w(44100);
        g_usb.lockMode = 1;
        g_usb.feedbackFpmf = 12.0;   // 96 kHz's answer on a 44.1 kHz stream: +117 %
        usbDacRecoveryForgetEpisodes();
        int steps = 0;
        for (int at = 0; at < 3 * 44100; at += 2048) {
            const Pcm src = makeNoise(2048, 24, 0xa5ff0000u + (uint32_t)at);
            w.write(src, 0, 2048);
            int64_t state[USB_DAC_STATE_COUNT];
            usbDacLockState(w.ctx, state);
            if (state[0] != 0 || state[5] != 0) steps++;
        }
        printf("A5 a misparsed feedback (+117 %% for the whole stream): detections or steps %d\n", steps);
        CHECK(steps > 0, "a clean feedback outside the window was invisible to the detector (%d)", steps);
    }
    // recovery off: the lock is detected and logged, nothing is done
    {
        usbSetDacRecoveryEnabled(false);
        const LockRun r = runLockedDac(44100, 1, 9000);
        usbSetDacRecoveryEnabled(true);
        printf("A5 recovery switched off: locked=%d, detected %lld ms after the underrun, steps run %d\n", r.locked ? 1 : 0, (long long)r.lockToDetectMs, r.stepsRun);
        CHECK(r.locked && r.stepsRun == 0 && r.setInterfaceCalls == 0, "recovery was off and %d steps ran", r.stepsRun);
    }
}

// ── B46-C2: `auto-early` against the real writer ─────────────────────────────────────────────────────────────────────────────────

struct EarlySim {
    double excessFrames = 0.0;           // frames the packetizer scheduled above the nominal rate over the whole run
    std::vector<uint8_t> packets;        // the frames of every packet, in order
    std::vector<std::string> stuckLines;
    std::vector<std::string> rejectedLines;
};

/** [seconds] of audio at [rate] with the DAC answering [plateau] from [fromMs] to [toMs] after the stream starts (nominal otherwise). */
EarlySim runEarlySim(int policy, int rate, double plateau, int64_t fromMs, int64_t toMs, int seconds) {
    simclock::VirtualTime virtualTime;
    JNINativeInterface table = {};
    table.GetArrayLength = fakeGetArrayLength;
    table.GetByteArrayElements = fakeGetByteArrayElements;
    table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
    JNIEnv env;
    env.functions = &table;
    const double nominal = rate / 8000.0;
    const Pcm tenth = makeNoise(rate / 10, 24, 0x46c20000u + (uint32_t)rate);
    FakeArray chunk;
    chunk.bytes = toPackedLe(tenth);
    g_usb.reset(rate, 8);
    usbSetFeedbackPolicy(policy);
    const int64_t t0 = nowUs();
    setFeedbackScript([=](int64_t atUs) {
        FeedbackAnswer answer;
        const int64_t ms = (atUs - t0) / 1000;
        answer.fpmf = (ms >= fromMs && ms < toMs) ? plateau : nominal;
        return answer;
    });
    const jlong usb = createStream(rate, 32);
    streamBuffers(&env, usb, chunk, seconds * 10);
    releaseStream(usb);
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);   // `auto` is the default of the process; a test leaves it as it found it
    EarlySim out;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        out.packets = g_usb.packetFrames;
    }
    double frames = 0.0;
    for (uint8_t n : out.packets) frames += n;
    out.excessFrames = frames - nominal * (double)out.packets.size();
    out.stuckLines = feedbackGlitchLines("FEEDBACK_STUCK");
    out.rejectedLines = feedbackGlitchLines("FEEDBACK_REJECTED");
    return out;
}

void testAutoEarly() {
    // the replayed plateaus (policy-window-results.jsonl): they begin 0.2 s after the stream starts and stay
    struct Plateau { const char *name; int rate; double fpmf; };
    const Plateau stuck[] = {{"Shakira, Ciega Sordomuda", 44100, 5.55749512}, {"Gustavo Cerati, Crimen", 48000, 6.04992676}};
    for (const Plateau &p : stuck) {
        const EarlySim autoRun = runEarlySim(USB_FEEDBACK_AUTO, p.rate, p.fpmf, 200, 100000000, 12);
        const EarlySim earlyRun = runEarlySim(USB_FEEDBACK_AUTO_EARLY, p.rate, p.fpmf, 200, 100000000, 12);
        double autoHeld = 0, earlyHeld = 0;
        CHECK(autoRun.stuckLines.size() == 1 && earlyRun.stuckLines.size() == 1, "%s: %zu / %zu FEEDBACK_STUCK lines", p.name, autoRun.stuckLines.size(),
              earlyRun.stuckLines.size());
        if (autoRun.stuckLines.size() == 1 && earlyRun.stuckLines.size() == 1) {
            autoHeld = fieldOf(autoRun.stuckLines[0], "held=");
            earlyHeld = fieldOf(earlyRun.stuckLines[0], "held=");
            CHECK(autoRun.stuckLines[0].find("policy=auto ") != std::string::npos, "auto's line lacks policy=auto: %s", autoRun.stuckLines[0].c_str());
            CHECK(earlyRun.stuckLines[0].find("policy=auto-early") != std::string::npos, "auto-early's line lacks its policy: %s", earlyRun.stuckLines[0].c_str());
            CHECK(autoHeld >= 5000 && autoHeld < 5000 + USB_FEEDBACK_GAP_MS, "auto: stuck at held=%.0f ms", autoHeld);
            CHECK(earlyHeld >= USB_FEEDBACK_EARLY_STUCK_MS && earlyHeld < USB_FEEDBACK_EARLY_STUCK_MS + USB_FEEDBACK_GAP_MS, "auto-early: stuck at held=%.0f ms", earlyHeld);
        }
        printf("auto-early, real writer, %s (%d Hz, plateau %.8f from 0.2 s): auto held %.0f ms -> %.0f frames above nominal; auto-early held %.0f ms -> %.0f frames "
               "(%.0f %%)\n", p.name, p.rate, p.fpmf, autoHeld, autoRun.excessFrames, earlyHeld, earlyRun.excessFrames,
               autoRun.excessFrames > 0 ? 100.0 * earlyRun.excessFrames / autoRun.excessFrames : 0.0);
        CHECK(earlyRun.excessFrames < autoRun.excessFrames * 0.85 && earlyRun.excessFrames > 0, "%s: auto-early scheduled %.0f frames above nominal, auto %.0f",
              p.name, earlyRun.excessFrames, autoRun.excessFrames);
        // a packet is never outside what nominal and the DAC's reading make of it (6 or 7 at 48 kHz, 5 or 6 at 44.1)
        const int lowest = (int)floor(p.rate / 8000.0);
        for (uint8_t n : earlyRun.packets) {
            if (n != lowest && n != lowest + 1) { CHECK(false, "%s: a packet of %d frames", p.name, n); break; }
        }
    }
    {   // the legitimate start transient, +1 % for 3 s (5.5674 at 44.1 kHz, 10:54:02-04): followed all the way by both, packet for packet
        const EarlySim autoRun = runEarlySim(USB_FEEDBACK_AUTO, 44100, 5.5674, 200, 3200, 8);
        const EarlySim earlyRun = runEarlySim(USB_FEEDBACK_AUTO_EARLY, 44100, 5.5674, 200, 3200, 8);
        printf("auto-early, real writer, start transient +1 %% for 3 s: %.0f frames above nominal in both; auto-early stuck lines: %zu\n", earlyRun.excessFrames,
               earlyRun.stuckLines.size());
        CHECK(autoRun.stuckLines.empty() && earlyRun.stuckLines.empty(), "the 3 s transient was called stuck");
        CHECK(autoRun.packets == earlyRun.packets, "the transient is followed differently by auto-early (%zu vs %zu packets)", autoRun.packets.size(), earlyRun.packets.size());
        CHECK(earlyRun.excessFrames > 1000, "the transient was not followed: %.0f frames above nominal", earlyRun.excessFrames);
    }
    {   // a healthy DAC: its nominal with a +0.27 % blip now and then. Never held, and identical to auto.
        simclock::VirtualTime virtualTime;
        JNINativeInterface table = {};
        table.GetArrayLength = fakeGetArrayLength;
        table.GetByteArrayElements = fakeGetByteArrayElements;
        table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
        JNIEnv env;
        env.functions = &table;
        std::vector<uint8_t> sequences[2];
        size_t stuckLines = 0;
        for (int i = 0; i < 2; i++) {
            const Pcm tenth = makeNoise(4800, 24, 0x46c2beefu);
            FakeArray chunk;
            chunk.bytes = toPackedLe(tenth);
            g_usb.reset(48000, 8);
            setFeedback(6.0, 80, 6.0149);
            usbSetFeedbackPolicy(i == 0 ? USB_FEEDBACK_AUTO : USB_FEEDBACK_AUTO_EARLY);
            const jlong usb = createStream(48000, 32);
            streamBuffers(&env, usb, chunk, 120);
            releaseStream(usb);
            usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
            {
                std::lock_guard<std::mutex> lock(g_usb.mu);
                sequences[i] = g_usb.packetFrames;
            }
            stuckLines += feedbackGlitchLines("FEEDBACK_STUCK").size();
        }
        CHECK(stuckLines == 0, "a healthy DAC was held (%zu FEEDBACK_STUCK lines)", stuckLines);
        CHECK(sequences[0] == sequences[1], "a healthy DAC is treated differently by auto-early");
        printf("auto-early, real writer, healthy DAC with blips for 12 s: never held, %zu packets identical to auto\n", sequences[0].size());
    }
    {   // American Money, +4.99 %: refused from its first reading as today, by both, packet for packet
        // B48-fix1: with the start guard on, even that first block goes out at nominal (testFeedbackStartGuard checks it); this is the policy's own behaviour, so the guard is off for it.
        setStartGuard(false);
        const EarlySim autoRun = runEarlySim(USB_FEEDBACK_AUTO, 44100, 5.7875, 0, 100000000, 6);
        const EarlySim earlyRun = runEarlySim(USB_FEEDBACK_AUTO_EARLY, 44100, 5.7875, 0, 100000000, 6);
        setStartGuard(true);
        CHECK(autoRun.packets == earlyRun.packets, "American Money is treated differently by auto-early");
        // Only the first block is above nominal (at most the ring's worth, 128 ms x 4.99 % = 281 frames; it was 100 ms and 220 frames when the ring was 80 ms):
        // the stream's start reading is in the window and is what the first block is planned with, the writer fills the ring with it before it reaps the
        // first continuous reading, and from that reading on the absurd value is refused. The same for auto, packet for packet.
        CHECK(earlyRun.excessFrames > 0 && earlyRun.excessFrames < 0.0499 * 44.1 * (USB_TRANSPORT_RING_URBS + 1) * 1.01,
              "American Money: %.0f frames above nominal, more than the first block's worth", earlyRun.excessFrames);
        printf("auto-early, real writer, American Money (+4.99 %%): refused by both from the first continuous reading, %.0f frames above nominal (the first block), "
               "packet for packet as auto\n", earlyRun.excessFrames);
    }
    {   // the policy is process-wide and opt-in: the process starts in auto, and an unknown id changes nothing
        usbSetFeedbackPolicy(77);
        CHECK(usbFeedbackPolicy() == USB_FEEDBACK_AUTO, "policy after an unknown id: %d", usbFeedbackPolicy());
        usbSetFeedbackPolicy(USB_FEEDBACK_AUTO_EARLY);
        CHECK(usbFeedbackPolicy() == USB_FEEDBACK_AUTO_EARLY, "auto-early did not take");
        usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    }
}


// ── B48-fix1: every start of audio primes the ring, and the first 500 ms after the DAC (re)starts go out at nominal ─────────────────────────────────────────────
//
// What the device log of 2026-10-30 00:36-00:43 showed: every new stream's first URB went out alone and the second 3-11 ms later (`OUTPUT_GAP dry=10 ms inFlight=1`, `RING_EMPTY`), because the
// writer's scheduling line (six /proc reads) and the CPU baseline were taken between the two. The simulation cannot see that cost by itself (the host's /proc reads are wall time, the clock
// is virtual), so these tests charge it where the native code does it (`procOpenCostUs`: 1.5 ms per /proc open, the device's order) and also stall the writer 10 ms right after the first URB.

bool g_guardWasOnAtProcessStart = false;   // read in main() before any test touches the switch

struct StartRun {
    int gaps = 0;
    int64_t maxGapUs = 0;
    int ringEmpty = 0;
    size_t modelLines = 0;
    int64_t primedFrames = 0;            // the stream's own count of the silence the first write fed
    int64_t expectedPrime = 0;           // what the plan says it is
    int64_t rawLeadingSilence = 0;       // zero frames on the wire before the first sample
    bool audibleEqual = false;
    int writerLines = 0;
    int writerLineAtUrb = -1;            // audio URBs sent when the `Writer thread:` line was written
    bool primeLogged = false;
    bool startGuardLogged = false;
};

/** A stream's first start, the writer stalled 10 ms right after its first URB and every /proc read costing the device's order of time. */
StartRun runFirstStart(int rate) {
    constexpr int kBlock = 2048;
    const Pcm src = makeNoise(8 * kBlock, 24, 0xb4810000u + (uint32_t)rate);
    Writer w(rate);
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        g_usb.procOpenCostUs = 1500;
        g_usb.stallAfterFirstUrbUs = 10000;
    }
    for (int at = 0; at + kBlock <= src.frames(); at += kBlock) w.write(src, at, kBlock);
    const std::vector<uint8_t> wire = w.finish();
    sleepMs(300);
    StartRun out;
    out.primedFrames = w.startSilenceFrames;
    out.expectedPrime = silenceToReach(w.ctx->plan.resumeLeadFrames, w.ctx->plan.silenceChunkFrames, 0);
    out.audibleEqual = wire == toUsb32(src);
    std::lock_guard<std::mutex> lock(g_usb.mu);
    out.gaps = g_usb.gaps;
    out.maxGapUs = g_usb.maxGapUs;
    out.ringEmpty = g_usb.ringEmptyLogs;
    out.modelLines = g_usb.outputGapLines.size();
    size_t zeroBytes = 0;
    while (zeroBytes < g_usb.capture.size() && g_usb.capture[zeroBytes] == 0) zeroBytes++;
    out.rawLeadingSilence = (int64_t)(zeroBytes / (size_t)g_usb.bytesPerFrame);
    for (const std::string &l : g_usb.schedulingLines) {
        if (l.find("Writer thread:") != std::string::npos) out.writerLines++;
    }
    if (!g_usb.writerLineAtUrb.empty()) out.writerLineAtUrb = g_usb.writerLineAtUrb.front();
    for (const std::string &l : g_usb.startLines) {
        if (l.find("Prime:") != std::string::npos && l.find("(stream)") != std::string::npos) out.primeLogged = true;
        if (l.find("Start: feedback start guard=") != std::string::npos) out.startGuardLogged = true;
    }
    return out;
}

/** A recovery's resume (step 1 or 2): the ring was drained, the interface restarted, and the writer stalls 10 ms right after the first URB of the restart. */
StartRun runRecoveryResume(int rate, int step) {
    constexpr int kBlock = 2048;
    const int frames = (int)((int64_t)rate * 3200 / 1000 / kBlock) * kBlock;   // long enough for the CPU baseline to be due again (2 s)
    const Pcm src = makeNoise(frames, 24, 0xb4820000u + (uint32_t)rate + (uint32_t)step);
    Writer w(rate);
    g_usb.feedbackFpmf = rate / 8000.0;
    const int64_t startUs = nowUs();
    int next = 0;
    while (nowUs() - startUs < 2400 * 1000 && next + kBlock <= frames - 8 * kBlock) {
        w.write(src, next, kBlock);
        next += kBlock;
    }
    size_t gapsBefore;
    {
        std::lock_guard<std::mutex> lock(g_usb.mu);
        gapsBefore = g_usb.gapList.size();
        g_usb.procOpenCostUs = 1500;
    }
    const int64_t silenceBefore = w.stat(5);
    runOneStep(w, src, next, step, kBlock, [&] {
        // the CPU baseline is due again 2 s after the last one: the restart's first URB meets it (a recovery that takes a moment longer, as the HONOR's did)
        const int64_t dueAtUs = g_lastProcStatOpenUs.load() + 2050 * 1000;
        if (g_lastProcStatOpenUs.load() > 0 && nowUs() < dueAtUs) sleepMs((dueAtUs - nowUs()) / 1000 + 1);
        std::lock_guard<std::mutex> lock(g_usb.mu);
        g_usb.stallAfterFirstUrbUs = 10000;
        g_usb.urbsSinceStart = 0;   // the restart's first audible URB is the one the stall follows
        g_usb.audibleSinceStart = false;
    });
    w.write(src, next, kBlock);
    sleepMs(400);
    StartRun out;
    out.primedFrames = w.resumeSilenceFrames;
    out.expectedPrime = silenceToReach(w.ctx->plan.resumeLeadFrames, w.ctx->plan.silenceChunkFrames, 0);
    (void)silenceBefore;
    std::lock_guard<std::mutex> lock(g_usb.mu);
    // the pause's own idle (the ring played out by design while the interface restarted) is the one gap that is the recovery's, at the first URB of the restart
    out.gaps = (int)g_usb.gapList.size() - (int)gapsBefore - 1;
    for (size_t i = gapsBefore + 1; i < g_usb.gapList.size(); i++) out.maxGapUs = std::max(out.maxGapUs, g_usb.gapList[i]);
    out.ringEmpty = g_usb.ringEmptyLogs;
    out.modelLines = g_usb.outputGapLines.size();
    for (const std::string &l : g_usb.startLines) {
        if (l.find("Prime:") != std::string::npos && l.find("(recovery)") != std::string::npos) out.primeLogged = true;
        if (l.find("Start: feedback start guard=") != std::string::npos && l.find("(recovery)") != std::string::npos) out.startGuardLogged = true;
    }
    return out;
}

void testEveryStartPrimesTheRing() {
    // a stream's first start, then a skip to a new stream at another rate, twice (44.1 -> 96 -> 192 kHz): each a new native stream, as the sink rebuilds one
    for (int rate : {44100, 96000, 192000}) {
        const StartRun r = runFirstStart(rate);
        printf("B48-1 %6d Hz, new stream, writer stalled 10 ms after its first URB, /proc reads 1.5 ms each: fake DAC dry %d x (max %.1f ms), OUTPUT_GAP=%zu, RING_EMPTY=%d, "
               "priming %lld frames (the plan's %lld; %.1f ms), %lld zero frames before the first sample, Writer thread line x%d after %d URBs, audible %s\n",
               rate, r.gaps, r.maxGapUs / 1000.0, r.modelLines, r.ringEmpty, (long long)r.primedFrames, (long long)r.expectedPrime, r.primedFrames * 1000.0 / rate,
               (long long)r.rawLeadingSilence, r.writerLines, r.writerLineAtUrb, r.audibleEqual ? "EQUAL to the source" : "DIFFERENT");
        CHECK(r.gaps == 0, "%d Hz: a new stream left the DAC dry %d times (max %.1f ms)", rate, r.gaps, r.maxGapUs / 1000.0);
        CHECK(r.ringEmpty == 0 && r.modelLines == 0, "%d Hz: a new stream reported %d RING_EMPTY and %zu OUTPUT_GAP lines", rate, r.ringEmpty, r.modelLines);
        CHECK(r.primedFrames == r.expectedPrime && r.expectedPrime > 0, "%d Hz: the start primed %lld frames of silence, the plan's amount is %lld", rate, (long long)r.primedFrames,
              (long long)r.expectedPrime);
        CHECK(r.rawLeadingSilence == r.primedFrames, "%d Hz: %lld zero frames on the wire before the first sample, the priming fed %lld", rate, (long long)r.rawLeadingSilence,
              (long long)r.primedFrames);
        CHECK(r.audibleEqual, "%d Hz: the audible samples after the priming are not the source", rate);
        CHECK(r.writerLines == 1, "%d Hz: %d `Writer thread:` lines for one writer thread", rate, r.writerLines);
        CHECK(r.writerLineAtUrb == 0, "%d Hz: the `Writer thread:` line was written after %d URBs had gone out (it must come before the first)", rate, r.writerLineAtUrb);
        CHECK(r.primeLogged && r.startGuardLogged, "%d Hz: the start did not log its priming and its guard", rate);
    }
    // a skip on the cached stream: the ring is held at the silence lead until the new audio arrives, the writer stalled 10 ms on it
    for (int rate : {44100, 96000, 192000}) {
        const SkipOutcome o = runSkip(rate, 10, 85);
        CHECK(o.realGaps == 0 && o.modelLines == 0, "%d Hz: a skip on the cached stream left the DAC dry %d x (%zu OUTPUT_GAP)", rate, o.realGaps, o.modelLines);
    }
    // a recovery's resume, step 1 (SETINTERFACE) and step 2 (bus reset first)
    for (int rate : {44100, 96000}) {
        for (int step : {1, 2}) {
            const StartRun r = runRecoveryResume(rate, step);
            printf("B48-1 %6d Hz, recovery step %d resume, writer stalled 10 ms after the restart's first URB: fake DAC dry after the restart %d x (max %.1f ms), OUTPUT_GAP=%zu, "
                   "RING_EMPTY=%d, priming %lld frames (the plan's %lld)\n",
                   rate, step, r.gaps, r.maxGapUs / 1000.0, r.modelLines, r.ringEmpty, (long long)r.primedFrames, (long long)r.expectedPrime);
            CHECK(r.gaps == 0, "%d Hz step %d: the recovery's resume left the DAC dry %d times (max %.1f ms)", rate, step, r.gaps, r.maxGapUs / 1000.0);
            CHECK(r.ringEmpty == 0 && r.modelLines == 0, "%d Hz step %d: the resume reported %d RING_EMPTY and %zu OUTPUT_GAP lines", rate, step, r.ringEmpty, r.modelLines);
            CHECK(r.primedFrames == r.expectedPrime && r.expectedPrime > 0, "%d Hz step %d: the resume primed %lld frames, the plan's amount is %lld", rate, step,
                  (long long)r.primedFrames, (long long)r.expectedPrime);
            CHECK(r.primeLogged && r.startGuardLogged, "%d Hz step %d: the recovery's restart did not log its priming and its guard", rate, step);
        }
    }
}

// ── the start guard ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

struct GuardRun {
    std::vector<SubmittedUrb> urbs;
    std::vector<uint8_t> packets;
    int64_t firstUrbUs = 0;
    int64_t t0 = 0;
    std::vector<std::string> rejected;
    std::vector<std::string> stuck;
    std::vector<std::string> startLines;
    int64_t restartUs = 0;
};

/** Frames above the nominal rate that the URBs submitted in [fromMs, toMs) after [originUs] carried: the packets' frames minus nominal x packets. */
double excessIn(const GuardRun &run, int64_t originUs, int64_t fromMs, int64_t toMs, double nominal) {
    double frames = 0.0;
    double packets = 0.0;
    for (const SubmittedUrb &u : run.urbs) {
        const int64_t ms = (u.atUs - originUs) / 1000;
        if (ms < fromMs || ms >= toMs) continue;
        frames += u.frames;
        packets += u.packets;
    }
    return frames - nominal * packets;
}

/** The Melody's start (the 2026-10-30 log, at 44.1 and 48 kHz): nominal, then the feedback climbs to +2.08 % by 170 ms, stays a moment and goes back. [startUs] is when the interface (re)started. */
double melodyRampAt(int64_t msSinceStart, double nominal) {
    if (msSinceStart < 30 || msSinceStart >= 230) return nominal;
    if (msSinceStart >= 170) return nominal * 1.0208;
    return nominal * (1.0 + 0.0208 * (double)(msSinceStart - 30) / 140.0);
}

double melodyStartRamp(int64_t atUs, int64_t startUs, double nominal) {
    return startUs > 0 ? melodyRampAt((atUs - startUs) / 1000, nominal) : nominal;
}

/** [seconds] of audio from a fresh stream, the DAC's feedback given by [script] (a function of the time in ms since the stream was created). */
GuardRun runGuard(int policy, int rate, bool guardOn, int seconds, const std::function<double(int64_t, double)> &script) {
    simclock::VirtualTime virtualTime;
    JNINativeInterface table = {};
    table.GetArrayLength = fakeGetArrayLength;
    table.GetByteArrayElements = fakeGetByteArrayElements;
    table.ReleaseByteArrayElements = fakeReleaseByteArrayElements;
    JNIEnv env;
    env.functions = &table;
    const double nominal = rate / 8000.0;
    const Pcm tenth = makeNoise(rate / 10, 24, 0xb4830000u + (uint32_t)rate);
    FakeArray chunk;
    chunk.bytes = toPackedLe(tenth);
    g_usb.reset(rate, 8);
    usbSetFeedbackPolicy(policy);
    setStartGuard(guardOn);
    GuardRun run;
    run.t0 = nowUs();
    const int64_t t0 = run.t0;
    setFeedbackScript([=](int64_t atUs) {
        FeedbackAnswer answer;
        answer.fpmf = script((atUs - t0) / 1000, nominal);
        return answer;
    });
    const jlong usb = createStream(rate, 32);
    streamBuffers(&env, usb, chunk, seconds * 10);
    releaseStream(usb);
    setFeedbackScript(nullptr);
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    setStartGuard(true);
    std::lock_guard<std::mutex> lock(g_usb.mu);
    run.urbs = g_usb.submitted;
    run.packets = g_usb.packetFrames;
    run.firstUrbUs = run.urbs.empty() ? t0 : run.urbs.front().atUs;
    run.startLines = g_usb.startLines;
    return run;
}

void testFeedbackStartGuard() {
    // (a) the Melody's start ramp, at 44.1 and 48 kHz: with the guard nothing goes out above nominal for 500 ms, with it off the ramp is followed until the refusal (HEAD's behaviour)
    for (int rate : {44100, 48000}) {
        const double nominal = rate / 8000.0;
        auto ramp = [](int64_t ms, double nom) { return melodyRampAt(ms, nom); };
        const GuardRun on = runGuard(USB_FEEDBACK_AUTO, rate, true, 3, ramp);
        const std::vector<std::string> rejectedOn = feedbackGlitchLines("FEEDBACK_REJECTED");
        const GuardRun off = runGuard(USB_FEEDBACK_AUTO, rate, false, 3, ramp);
        const double excessOn = excessIn(on, on.firstUrbUs, 0, 500, nominal);
        const double excessOff = excessIn(off, off.firstUrbUs, 0, 500, nominal);
        printf("B48-3 %5d Hz, the Melody's start ramp (+2.08 %% at 170 ms): frames above nominal in the first 500 ms: guard on %.1f, guard off %.1f; FEEDBACK_REJECTED lines with the guard: %zu\n", rate,
               excessOn, excessOff, rejectedOn.size());
        CHECK(excessOn <= 1.01 && excessOn >= -1.01, "%d Hz: %.1f frames above nominal in the first 500 ms with the start guard on", rate, excessOn);
        CHECK(excessOff > 5.0, "%d Hz: with the guard off the ramp was not followed (%.1f frames): the comparison is void", rate, excessOff);
        // the readings kept feeding the watch: the +2.08 % is refused, with the ramp's length, as it is without the guard
        CHECK(!rejectedOn.empty() && rejectedOn.front().find("ramp=") != std::string::npos, "%d Hz: the refusal of the ramp is gone from the log with the guard on", rate);
        // after the guard nothing is held back and the ramp is long over: both go out at nominal
        CHECK(std::fabs(excessIn(on, on.firstUrbUs, 500, 3000, nominal)) <= 1.5 && std::fabs(excessIn(off, off.firstUrbUs, 500, 3000, nominal)) <= 1.5,
              "%d Hz: frames above nominal after the guard", rate);
    }
    // (b) a healthy DAC that answers its nominal: the guard changes nothing. At 48 kHz nominal (6 frames per microframe) is exact in the feedback's 16.16 format, so the packets are
    // identical byte for byte. At 44.1 kHz the DAC's answer (5.512496) is not the nominal (5.5125): what the guard sends for 500 ms is a hair different from what `auto` sends, and the
    // packetizer's carry stays a fraction of a frame apart for the rest of the stream (some 5/6 positions shift). The two never differ by more than one frame in total.
    {
        auto nominalOnly = [](int64_t, double nom) { return nom; };
        const GuardRun on48 = runGuard(USB_FEEDBACK_AUTO, 48000, true, 3, nominalOnly);
        const GuardRun off48 = runGuard(USB_FEEDBACK_AUTO, 48000, false, 3, nominalOnly);
        CHECK(!on48.packets.empty() && on48.packets == off48.packets, "a healthy 48 kHz DAC is treated differently with the start guard (%zu / %zu packets)", on48.packets.size(), off48.packets.size());
        const GuardRun on = runGuard(USB_FEEDBACK_AUTO, 44100, true, 3, nominalOnly);
        const GuardRun off = runGuard(USB_FEEDBACK_AUTO, 44100, false, 3, nominalOnly);
        int64_t cumulativeOn = 0;
        int64_t cumulativeOff = 0;
        int64_t widest = 0;
        size_t differing = 0;
        const size_t common = std::min(on.packets.size(), off.packets.size());
        for (size_t i = 0; i < common; i++) {
            cumulativeOn += on.packets[i];
            cumulativeOff += off.packets[i];
            widest = std::max<int64_t>(widest, std::llabs(cumulativeOn - cumulativeOff));
            differing += on.packets[i] != off.packets[i];
        }
        CHECK(on.packets.size() == off.packets.size() && widest <= 1, "a healthy 44.1 kHz DAC: the totals of the two runs are %lld frames apart at the widest (%zu / %zu packets)", (long long)widest,
              on.packets.size(), off.packets.size());
        printf("B48-3 healthy DAC at nominal: 48 kHz %zu packets identical with the guard on and off; 44.1 kHz %zu packets, %zu positions shift, never more than %lld frame apart in total\n",
               on48.packets.size(), common, differing, (long long)widest);
    }
    // (c) a crystal that is really 0.2 % off from the start, and a real +0.8 % plateau (Crimen) from 0.2 s: the guard only delays them, to 500 ms
    {
        struct Case { const char *name; int rate; double ppm; int64_t from; };
        for (const Case &c : {Case{"crystal +0.2 %", 44100, 0.002, 0}, Case{"Gustavo Cerati, Crimen +0.832 %", 48000, 0.00832, 200}}) {
            const double nominal = c.rate / 8000.0;
            auto plateau = [&](int64_t ms, double nom) { return ms >= c.from ? nom * (1.0 + c.ppm) : nom; };
            const GuardRun on = runGuard(USB_FEEDBACK_AUTO, c.rate, true, 4, plateau);
            const size_t stuckOn = feedbackGlitchLines("FEEDBACK_STUCK").size();
            const GuardRun off = runGuard(USB_FEEDBACK_AUTO, c.rate, false, 4, plateau);
            const size_t stuckOff = feedbackGlitchLines("FEEDBACK_STUCK").size();
            const double inGuardOn = excessIn(on, on.firstUrbUs, 0, 500, nominal);
            const double inGuardOff = excessIn(off, off.firstUrbUs, 0, 500, nominal);
            const double afterOn = excessIn(on, on.firstUrbUs, 600, 3000, nominal);
            const double afterOff = excessIn(off, off.firstUrbUs, 600, 3000, nominal);
            printf("B48-3 %s: frames above nominal in 0-500 ms: guard on %.1f, off %.1f; in 600-3000 ms: on %.1f, off %.1f; FEEDBACK_STUCK on/off %zu/%zu\n", c.name, inGuardOn, inGuardOff,
                   afterOn, afterOff, stuckOn, stuckOff);
            CHECK(std::fabs(inGuardOn) <= 1.01, "%s: %.1f frames above nominal inside the guard", c.name, inGuardOn);
            CHECK(inGuardOff > 5.0, "%s: without the guard the plateau was not followed in the first 500 ms (%.1f): the comparison is void", c.name, inGuardOff);
            CHECK(std::fabs(afterOn - afterOff) <= std::max(2.5, afterOff * 0.01) && afterOn > 20.0, "%s: after the guard it is followed as auto does (%.1f vs %.1f frames above nominal)", c.name,
                  afterOn, afterOff);
            CHECK(stuckOn == stuckOff, "%s: the stuck watch saw different readings with the guard (%zu / %zu lines)", c.name, stuckOn, stuckOff);
        }
    }
    // (d) `follow` is the diagnostic policy and never obeys the guard
    {
        auto ramp = [](int64_t ms, double nom) { return melodyRampAt(ms, nom); };
        const GuardRun follow = runGuard(USB_FEEDBACK_FOLLOW, 44100, true, 2, ramp);
        const double excess = excessIn(follow, follow.firstUrbUs, 0, 500, 44100 / 8000.0);
        CHECK(excess > 5.0, "follow obeyed the start guard (%.1f frames above nominal in the first 500 ms)", excess);
    }
    // (e) the guard is on in a process that never touched the switch, the plan names it, and the start says so (and says `off` when it is off)
    {
        CHECK(g_guardWasOnAtProcessStart == startGuardDefaultIsOn(), "the start guard is not on by default (%d)", g_guardWasOnAtProcessStart);
        auto nominalOnly = [](int64_t, double nom) { return nom; };
        const GuardRun on = runGuard(USB_FEEDBACK_AUTO, 44100, true, 1, nominalOnly);
        const GuardRun off = runGuard(USB_FEEDBACK_AUTO, 44100, false, 1, nominalOnly);
        auto hasLine = [](const GuardRun &r, const char *text) {
            for (const std::string &l : r.startLines) if (l.find(text) != std::string::npos) return true;
            return false;
        };
#ifndef B48FIX1_BEFORE
        CHECK(hasLine(on, "Start: feedback start guard=500 ms"), "no `Start: feedback start guard=500 ms` line with the guard on");
        CHECK(hasLine(off, "Start: feedback start guard=off"), "no `Start: feedback start guard=off` line with the guard off");
        Writer w(44100);
        CHECK(w.ctx->plan.feedbackStartGuardMs == USB_TRANSPORT_FEEDBACK_START_GUARD_MS && USB_TRANSPORT_FEEDBACK_START_GUARD_MS == 500, "the plan's guard is %d ms", w.ctx->plan.feedbackStartGuardMs);
#else
        (void)hasLine; (void)on; (void)off;
        CHECK(false, "HEAD has no start guard to announce");
#endif
    }
    // (f) a rail that begins inside the guard still makes the B47 lock detector fire: the readings keep feeding it, only what is sent is held
    {
        simclock::VirtualTime virtualTime;
        Writer w(44100);
        g_usb.feedbackFpmf = 44100 / 8000.0;
        usbDacRecoveryForgetEpisodes();
        usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
        setStartGuard(true);
        const int64_t t0 = nowUs();
        setFeedbackScript([=](int64_t atUs) {
            FeedbackAnswer answer;
            answer.fpmf = (atUs - t0) / 1000 >= 100 ? 5.7875 : 5.5125;   // +4.99 % from 100 ms, inside the guard
            return answer;
        });
        const Pcm src = makeNoise(44100 * 3, 24, 0xb4840000u);
        bool locked = false;
        for (int at = 0; at + 2048 <= src.frames() && nowUs() - t0 < 2800 * 1000; at += 2048) {
            w.write(src, at, 2048);
            int64_t state[USB_DAC_STATE_COUNT];
            usbDacLockState(w.ctx, state);
            if (state[0] != 0) locked = true;
        }
        setFeedbackScript(nullptr);
        GuardRun run;
        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            run.urbs = g_usb.submitted;
        }
        const double excess = excessIn(run, run.urbs.front().atUs, 0, 500, 44100 / 8000.0);
        CHECK(locked, "the lock detector did not fire on a +4.99 %% rail that began inside the start guard");
        CHECK(std::fabs(excess) <= 1.01, "%.1f frames above nominal during the guard on a +4.99 %% rail", excess);
        printf("B48-3 a +4.99 %% rail from 100 ms (inside the guard): lock detected %s, %.1f frames above nominal in the first 500 ms\n", locked ? "yes" : "NO", excess);
    }
    // (g) a recovery's restart gets a fresh guard, and a skip on the cached stream gets none. Each is run with the guard on and with it off: the off run proves the scenario has something to hold.
    auto framesAboveNominalAfter = [](bool recovery, bool guardOn) {
        simclock::VirtualTime virtualTime;
        const int rate = 44100;
        const double nominal = rate / 8000.0;
        Writer w(rate);
        g_usb.feedbackFpmf = nominal;
        usbDacRecoveryForgetEpisodes();
        usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
        setStartGuard(guardOn);
        auto restartUs = std::make_shared<int64_t>(0);
        setFeedbackScript([=](int64_t atUs) {
            FeedbackAnswer answer;
            const int64_t sinceMs = (atUs - *restartUs) / 1000;
            if (*restartUs <= 0) answer.fpmf = nominal;
            else if (recovery) answer.fpmf = melodyRampAt(sinceMs, nominal);           // the Melody's servo ramp after the interface restarts
            else answer.fpmf = sinceMs >= 30 ? nominal * 1.01 : nominal;               // a DAC that really is 1 % off, after a skip
            return answer;
        });
        constexpr int kBlock = 2048;
        const Pcm src = makeNoise(rate * 5, 24, 0xb4850000u);
        int next = 0;
        const int64_t t0 = nowUs();
        while (nowUs() - t0 < 1500 * 1000) {   // the stream's own first guard is over
            w.write(src, next, kBlock);
            next += kBlock;
        }
        int64_t restartAt = 0;
        if (recovery) {
            runOneStep(w, src, next, 1, kBlock, [&] {
                restartAt = nowUs();
                *restartUs = restartAt;
            });
        } else {
            restartAt = nowUs();
            *restartUs = restartAt;
            w.beginSeek();   // a skip on the cached stream: the DAC is not restarted
        }
        for (int i = 0; i < 40 && next + kBlock <= src.frames(); i++) {
            w.write(src, next, kBlock);
            next += kBlock;
        }
        setFeedbackScript(nullptr);
        setStartGuard(true);
        GuardRun run;
        {
            std::lock_guard<std::mutex> lock(g_usb.mu);
            run.urbs = g_usb.submitted;
        }
        return excessIn(run, restartAt, 0, 500, nominal);
    };
    const double recoveryHeld = framesAboveNominalAfter(true, true);
    const double recoveryFollowed = framesAboveNominalAfter(true, false);
    const double skipGuardOn = framesAboveNominalAfter(false, true);
    const double skipGuardOff = framesAboveNominalAfter(false, false);
    printf("B48-3 a recovery's restart (step 1): frames above nominal in the 500 ms after it: guard on %.1f, off %.1f\n", recoveryHeld, recoveryFollowed);
    printf("B48-3 a skip on the cached stream (a DAC 1 %% off from 30 ms): frames above nominal in the 500 ms after it: guard on %.1f, off %.1f\n", skipGuardOn, skipGuardOff);
    CHECK(recoveryFollowed > 5.0, "a recovery's restart: the control run without the guard did not follow the ramp (%.1f frames): the comparison is void", recoveryFollowed);
    CHECK(std::fabs(recoveryHeld) <= 1.5, "a recovery's restart: %.1f frames above nominal in the 500 ms after it (the restart owes a fresh guard)", recoveryHeld);
    CHECK(skipGuardOn > 5.0 && std::fabs(skipGuardOn - skipGuardOff) <= 1.5,
          "a skip on the cached stream was held at nominal (%.1f frames above, %.1f without the guard): the DAC did not restart, it gets no guard", skipGuardOn, skipGuardOff);
}

// B48-fix2: only numeric train evidence; the live feedback beyond the window was not logged.
void testTrainFeedback() {
    for (int rate : {44100, 48000}) {
        for (int cure : {0, 1, 2, 3}) {
            simclock::VirtualTime virtualTime;
            Writer w(rate, true);
            const double nominal = rate / 8000.0;
            const Pcm src = makeNoise(rate * 52, 24, 0x48200000u + rate);
            const int64_t t0 = nowUs();
            const int railMs = rate == 44100 ? 300 : 2200;
            int restarts = 0;
            int64_t restartUs = 0;
            usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
            usbDacRecoveryForgetEpisodes();
            usbSetDacRecoveryEnabled(cure != 0);
            setFeedbackScript([&](int64_t atUs) {
                FeedbackAnswer a;
                const int64_t ms = (atUs - t0) / 1000;
                if (restarts > 0) {
                    const int64_t age = (atUs - restartUs) / 1000;
                    a.fpmf = age < 900 ? melodyRampAt(age, nominal) :
                        ((cure == 1 || (cure == 2 && restarts >= 2)) ? nominal : nominal * 0.94);
                } else if (ms >= railMs) {
                    a.fpmf = nominal * ((rate == 44100 && ms < railMs + 40000) ? 1.06 : 0.94);
                    if (rate == 44100 && ms >= railMs + 41000) a.fpmf = nominal;
                } else if (rate == 44100) {
                    a.fpmf = ms < 200 ? melodyRampAt(ms, nominal) : nominal * 1.028;
                } else {
                    a.fpmf = ms < 2000 ? nominal : nominal * 0.98;
                }
                return a;
            });
            g_usb.trainLines.clear();
            int next = 0;
            int steps[4] = {};
            int64_t detectedMs = -1;
            const int durationMs = cure == 0 ? 43000 : 13000;
            while (next + 128 <= src.frames() && nowUs() - t0 < durationMs * 1000LL) {
                w.write(src, next, 128);
                next += 128;
                int64_t state[USB_DAC_STATE_COUNT];
                usbDacLockState(w.ctx, state);
                if (state[0] && detectedMs < 0) detectedMs = (nowUs() - t0) / 1000 - railMs;
                const int step = (int)state[5];
                if (step >= 1 && step <= 3) steps[step]++;
                if (cure != 0 && (step == 1 || step == 2)) {
                    runOneStep(w, src, next, step, 128, [&] {
                        restarts++;
                        restartUs = nowUs();
                    });
                }
            }
            CHECK(detectedMs >= USB_DAC_LOCK_RAIL_MS && detectedMs <= USB_DAC_LOCK_RAIL_MS + 8,
                  "%d Hz replay cure=%d detected after %lld ms", rate, cure, (long long)detectedMs);
            CHECK(steps[1] == (cure != 0 ? 1 : 0), "%d Hz replay step 1 decision missing or recovery disabled", rate);
            if (cure > 0) {
                CHECK(steps[2] == (cure >= 2 ? 1 : 0) && steps[3] == (cure == 3 ? 1 : 0),
                      "replay escalation cure=%d step2=%d gaveUp=%d", cure, steps[2], steps[3]);
                bool ok = false;
                for (const auto &entry : g_usb.trainLines) {
                    if (entry.second.find("result=ok") != std::string::npos) ok = true;
                }
                CHECK(ok == (cure != 3), "replay verdict cure=%d ok=%d", cure, ok);
            }
            bool window = false, live = false, back = false, periodic = false;
            int64_t outsideUs = -1;
            int64_t lockUs = -1;
            bool staleAccepted = false;
            bool rawMatches = false;
            for (const auto &entry : g_usb.trainLines) {
                const std::string &line = entry.second;
                const size_t ageAt = line.find("acceptedAge=");
                if (ageAt != std::string::npos && atoll(line.c_str() + ageAt + 12) >= 1000) staleAccepted = true;
                if (line.find("FEEDBACK_OUT_OF_WINDOW fpmf=") != std::string::npos && outsideUs < 0) {
                    unsigned bytes[4] = {};
                    double logged = 0;
                    const size_t rawAt = line.find("raw=");
                    if (rawAt != std::string::npos && sscanf(line.c_str() + rawAt, "raw=%X %X %X %X", &bytes[0], &bytes[1], &bytes[2], &bytes[3]) == 4 &&
                        sscanf(line.c_str(), "FEEDBACK_OUT_OF_WINDOW fpmf=%lf", &logged) == 1) {
                        const uint32_t raw = bytes[0] | (bytes[1] << 8) | (bytes[2] << 16) | (bytes[3] << 24);
                        rawMatches = std::fabs(raw / 65536.0 - logged) <= 0.000051;
                    }
                    const size_t streamAt = line.find("streamAge=");
                    CHECK(streamAt != std::string::npos && atoll(line.c_str() + streamAt + 10) == entry.first / 1000 - (t0 / 1000 - 2),
                          "streamAge did not start at the data SETINTERFACE before the initial feedback read");
                }
                if (outsideUs < 0 && line.find("FEEDBACK_OUT_OF_WINDOW fpmf=") != std::string::npos) outsideUs = entry.first;
                if (line.find("DAC_LOCKED") != std::string::npos) {
                    if (lockUs < 0) lockUs = entry.first;
                    CHECK(line.find("why=rail") != std::string::npos, "rail announced without why=rail or announced again as stuck");
                }
                if (line.find("FEEDBACK_OUT_OF_WINDOW") != std::string::npos) {
                    window = line.find("raw=") != std::string::npos && line.find("streamAge=") != std::string::npos;
                    back = back || line.find("back after") != std::string::npos;
                }
                live = live || (line.find("live=") != std::string::npos && line.find("acceptedAge=") != std::string::npos);
                periodic = periodic || line.find("Feedback #") != std::string::npos;
            }
            CHECK(outsideUs >= 0 && lockUs / 1000 - outsideUs / 1000 == USB_DAC_LOCK_RAIL_MS, "DAC_LOCKED was not logged at exactly 1500ms after rail: outside=%lld lock=%lld delta=%lld", (long long)outsideUs, (long long)lockUs, (long long)(lockUs / 1000 - outsideUs / 1000));
            CHECK((cure != 0 || staleAccepted) && rawMatches, "missing a stale accepted age or received raw bytes");
            CHECK(window && live && periodic, "replay missing out-of-window/raw/streamAge/live/acceptedAge/periodic evidence");
            if (cure == 0 && rate == 44100) CHECK(back, "no back-after transition");
            printf("B48-fix2 train rate=%d cure=%d rail=+/-6%% DAC_LOCKED=%lldms steps=%d/%d/%d logs=%zu\n",
                   rate, cure, (long long)detectedMs, steps[1], steps[2], steps[3], g_usb.trainLines.size());
            setFeedbackScript(nullptr);
            usbSetDacRecoveryEnabled(true);
        }
    }
    for (double multiple : {0.0, 2.0, 10000.0}) {
        simclock::VirtualTime virtualTime;
        Writer w(48000);
        usbDacRecoveryForgetEpisodes();
        g_usb.feedbackFpmf = 6.0 * multiple;
        const Pcm src = makeNoise(48000 * 3, 24, 0x482ffff0u);
        for (int at = 0; at + 128 <= src.frames(); at += 128) w.write(src, at, 128);
        int64_t state[USB_DAC_STATE_COUNT];
        usbDacLockState(w.ctx, state);
        CHECK(state[0] == 1 && state[5] == 1, "extreme x%.0f did not detect and request recovery", multiple);
        CHECK(std::isfinite(w.ctx->calibratedFpmf) && std::fabs(w.ctx->calibratedFpmf - 6.0) < 0.0001,
              "extreme x%.0f reached follower", multiple);
        printf("B48-fix2 extreme x%.0f locked=%lld step=%lld follower=%.4f\n", multiple, (long long)state[0], (long long)state[5], w.ctx->calibratedFpmf);
    }
    // Exact Q16.16 boundaries at 40 kHz: the existing follower window is strict at both ends.
    for (double boundary : {4.75, 5.25}) {
        Writer w(40000, true);
        usbSetFeedbackPolicy(USB_FEEDBACK_FOLLOW);
        g_usb.feedbackFpmf = boundary;
        const Pcm src = makeNoise(40000, 24, 0x482eeee0u);
        for (int at = 0; at + 128 <= src.frames(); at += 128) w.write(src, at, 128);
        CHECK(w.ctx->readingFpmf == 5.0 && w.ctx->calibratedFpmf == 5.0, "exact window boundary %.2f entered the follower", boundary);
        usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    }
    const GuardRun ramp = runGuard(USB_FEEDBACK_AUTO, 44100, false, 4,
        [](int64_t ms, double nominal) { return melodyRampAt(ms - 1200, nominal); });
    (void)ramp;
    bool locked = false;
    for (const auto &entry : g_usb.trainLines) locked = locked || entry.second.find("DAC_LOCKED") != std::string::npos;
    CHECK(!locked, "settling mid-song +2.08%% ramp caused a lock-up");
    // A brief excursion must not permanently hide a long rail that starts inside the log limiter's second.
    runGuard(USB_FEEDBACK_AUTO, 48000, false, 4, [](int64_t ms, double nominal) {
        return ((ms >= 100 && ms < 150) || (ms >= 200 && ms < 2000)) ? nominal * 1.06 : nominal;
    });
    int leaves = 0;
    bool delayed = false;
    for (const auto &entry : g_usb.trainLines) {
        if (entry.second.find("FEEDBACK_OUT_OF_WINDOW fpmf=") == std::string::npos) continue;
        leaves++;
        const size_t sinceAt = entry.second.find("since=");
        delayed = delayed || (sinceAt != std::string::npos && atoll(entry.second.c_str() + sinceAt + 6) >= 800);
    }
    CHECK(leaves == 2 && delayed, "rate limiting hid a persistent rail after a brief excursion");

}

void testStartGuardDefaultOff() {
    CHECK(!g_guardWasOnAtProcessStart, "start guard must be off in a fresh process");
    setStartGuard(g_guardWasOnAtProcessStart);
    Writer w(44100, true);
    bool saysOff = false;
    for (const auto &line : g_usb.startLines) saysOff = saysOff || line.find("Start: feedback start guard=off") != std::string::npos;
    CHECK(saysOff, "default start did not log guard=off");
}


// B50: numeric rate-change starts through fake usbfs, the real writer and tools/sim-clock.h.
void testRateChangeStuckRecovery() {
    constexpr int kRate = 48000;
    constexpr int kPreviousRate = 44100;
    constexpr int kBlock = 128;
    constexpr int kShortHoldMs = 1500;
    constexpr int kWindowMs = 3000;
    constexpr int kWrongRateMs = 40;
    constexpr int kRampMs = 900;
    constexpr int kShortPlateauMs = 1400;
    constexpr int kLateStartMs = 4000;
    constexpr int kWindowEdgeStartMs = 2500;
    constexpr int kReplayMs = 16000;
    constexpr int kToleranceMs = 10;
    constexpr double kNominal = 6.0;
    constexpr double kPreviousFeedback = 5.51245;
    constexpr double kFrozenValues[] = {6.03491, 6.0199, 6.03992};
    struct Case { const char *name; int previous; int mode; double frozen; int hold; };
    // mode 0 freezes, 1 settles after the usual ramp, 2 is a short healthy plateau,
    // 3 is a longer healthy plateau (the accepted restart cost), 4 freezes after a cached skip.
    const Case cases[] = {
        {"changed rate +5819 ppm", kPreviousRate, 0, kFrozenValues[0], kShortHoldMs},
        {"same rate after recovery", kRate, 0, kFrozenValues[0], USB_FEEDBACK_STUCK_MIN_MS},
        {"changed rate +3316 ppm", kPreviousRate, 0, kFrozenValues[1], kShortHoldMs},
        {"changed rate +6653 ppm", kPreviousRate, 0, kFrozenValues[2], kShortHoldMs},
        {"first stream after attach", 0, 0, kFrozenValues[0], USB_FEEDBACK_STUCK_MIN_MS},
        {"usual ramp settles", kPreviousRate, 1, 0, 0},
        {"healthy plateau below hold", kPreviousRate, 2, 0, 0},
        {"healthy plateau above hold costs a restart", kPreviousRate, 3, 0, kShortHoldMs},
        {"cached skip in changed-rate stream", kPreviousRate, 4, kFrozenValues[0], USB_FEEDBACK_STUCK_MIN_MS},
        {"cached skip in first stream", 0, 4, kFrozenValues[0], USB_FEEDBACK_STUCK_MIN_MS},
        {"hold crossing start window", kPreviousRate, 5, kFrozenValues[0], USB_FEEDBACK_STUCK_MIN_MS},
    };
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    usbSetDacRecoveryEnabled(true);
    for (const Case &c : cases) {
        usbDacRecoveryForgetEpisodes();
        Writer w(kRate, true, c.previous);
        const Pcm src = makeNoise(kRate * (kReplayMs / 1000 + 3), 24, 0x50abcdefu);
        const int64_t t0 = nowUs();
        const int onsetMs = c.mode == 4 ? kLateStartMs : c.mode == 5 ? kWindowEdgeStartMs : kWrongRateMs;
        int restarts = 0, next = 0;
        bool skipped = false;
        setFeedbackScript([&](int64_t atUs) {
            FeedbackAnswer a;
            const int64_t age = (atUs - t0) / 1000;
            if (restarts > 0) a.fpmf = kNominal;
            else if (c.mode == 1) a.fpmf = age < kRampMs ? melodyRampAt(age, kNominal) : kNominal;
            else if (c.mode == 2 || c.mode == 3) a.fpmf = age < (c.mode == 2 ? kShortPlateauMs : kWindowMs) ? kNominal * 1.01 : kNominal;
            else a.fpmf = age < onsetMs ? (c.mode >= 4 ? kNominal : kPreviousFeedback) : c.frozen;
            return a;
        });
        while (next + kBlock <= src.frames() && nowUs() - t0 < kReplayMs * 1000LL) {
            if (c.mode == 4 && !skipped && (nowUs() - t0) / 1000 >= kLateStartMs - kRampMs) {
                CHECK(w.beginSeek() >= 0, "cached skip failed");
                while (w.fadingOut()) { w.write(src, next, kBlock); next += kBlock; }
                w.resume();
                skipped = true;
            }
            w.write(src, next, kBlock); next += kBlock;
            int64_t state[USB_DAC_STATE_COUNT];
            usbDacLockState(w.ctx, state);
            if (state[5] == USB_DAC_STEP_INTERFACE || state[5] == USB_DAC_STEP_BUS_RESET) {
                CHECK(state[5] == USB_DAC_STEP_INTERFACE && restarts == 0, "%s took another step", c.name);
                runOneStep(w, src, next, (int)state[5], kBlock, [&] { restarts++; });
            }
        }
        int stuck = 0, ok = 0, arms = 0;
        int64_t detectedMs = -1;
        for (const auto &line : g_usb.startLines) if (line.find("Start: rate change") != std::string::npos) {
            arms++;
            CHECK(line.find("44100 → 48000, stuck hold 1500 ms for the first 3000 ms") != std::string::npos,
                  "rate-change start line lacks rates, hold or window");
        }
        CHECK(arms == (c.previous == kPreviousRate ? 1 : 0), "%s armed %d times", c.name, arms);
        for (const auto &entry : g_usb.trainLines) {
            const std::string &line = entry.second;
            if (line.find("DAC_LOCKED") != std::string::npos) {
                stuck++;
                detectedMs = (entry.first - t0) / 1000;
                const bool shortened = c.hold == kShortHoldMs;
                CHECK(line.find("why=stuck") != std::string::npos, "%s was classified as a rail", c.name);
                CHECK((line.find("hold=rate-change") != std::string::npos) == shortened,
                      "%s wrong hold marker %s", c.name, line.c_str());
                CHECK(line.find("heldMs=" + std::to_string(c.hold)) != std::string::npos,
                      "%s wrong hold %s", c.name, line.c_str());
                printf("B50 %s at=%lldms %s\n", c.name, (long long)detectedMs, line.c_str());
            }
            if (line.find("result=ok") != std::string::npos) { ok++; printf("B50 %s %s\n", c.name, line.c_str()); }
        }
        CHECK(stuck == (c.hold ? 1 : 0) && restarts == (c.hold ? 1 : 0) && ok == (c.hold ? 1 : 0),
              "%s locks=%d restarts=%d ok=%d", c.name, stuck, restarts, ok);
        if (c.hold) {
            const int expectedMs = (c.mode == 3 ? 0 : onsetMs) + c.hold;
            CHECK(detectedMs >= expectedMs && detectedMs <= expectedMs + kToleranceMs,
                  "%s detected at %lld ms expected %d..%d", c.name, (long long)detectedMs, expectedMs, expectedMs + kToleranceMs);
        }
        if (c.mode == 1) {
            bool absurd = false;
            for (const auto &line : g_usb.feedbackGlitches) absurd = absurd || line.find("why=absurd") != std::string::npos;
            CHECK(absurd, "usual +2.08 percent ramp did not exercise absurd refusal");
        }
        printf("B50 %s arms=%d locks=%d restarts=%d ok=%d detected=%lldms\n",
               c.name, arms, stuck, restarts, ok, (long long)detectedMs);
        setFeedbackScript(nullptr);
    }
}

// B48-fix4: numeric feedback replay through the real JNI writer, usbfs and virtual clock.
void testStuckDacRecovery() {
    constexpr int kRate = 48000;
    constexpr int kBlock = 128;
    constexpr int kWrongRateMs = 40;
    constexpr int kRampMs = 900;
    constexpr int kHealthyPlateauMs = 3000;
    constexpr int kPauseAtMs = 4500;
    constexpr int kResumeAtMs = 6500;
    constexpr int kReplayMs = 38000;   // past cooldown, so give-up must remain latched
    constexpr int kNominalMs = 180000;
    constexpr double kNominal = 6.0;
    constexpr double kPreviousRate = 5.51245;
    constexpr double kFrozen = 6.03992;
    constexpr double kHealthyPlateau = kNominal * 1.01;
    struct Case { const char *name; int cure; bool pause; int policy; };
    const Case cases[] = {
        {"restart cures frozen feedback", 1, false, USB_FEEDBACK_AUTO},
        {"bus reset cures renewed freeze", 2, false, USB_FEEDBACK_AUTO},
        {"both restarts freeze again", 3, false, USB_FEEDBACK_AUTO},
        {"freeze during pause", 1, true, USB_FEEDBACK_AUTO},
        {"opt-in keeps its existing hold", 1, false, USB_FEEDBACK_AUTO_EARLY},
        {"recovery disabled", 0, false, USB_FEEDBACK_AUTO},
    };
    for (const Case &c : cases) {
        usbSetFeedbackPolicy(c.policy);
        usbSetDacRecoveryEnabled(c.cure != 0);
        usbDacRecoveryForgetEpisodes();
        Writer w(kRate, true);
        const Pcm src = makeNoise(kRate * (kReplayMs / 1000 + 3), 24, 0x48400001u);
        const int64_t t0 = nowUs();
        int restarts = 0;
        int64_t restartUs = 0;
        int64_t doneUs = 0;
        bool paused = false, resumed = false;
        int next = 0;
        int steps[4] = {};
        int64_t decisionUs = -1;
        setFeedbackScript([&](int64_t atUs) {
            FeedbackAnswer a;
            if (restarts > 0) {
                const int64_t age = (atUs - restartUs) / 1000;
                a.fpmf = age < kRampMs ? melodyRampAt(age, kNominal) :
                    ((c.cure == 1 || (c.cure == 2 && restarts >= 2)) ? kNominal : kFrozen);
            } else {
                a.fpmf = (atUs - t0) / 1000 < kWrongRateMs ? kPreviousRate : kFrozen;
            }
            return a;
        });
        g_usb.trainLines.clear();
        while (next + kBlock <= src.frames() && nowUs() - t0 < kReplayMs * 1000LL) {
            if (c.pause && !paused && (nowUs() - t0) / 1000 >= kPauseAtMs) {
                CHECK(w.beginPause(), "stuck pause did not start");
                while (w.fadingOut()) { w.write(src, next, kBlock); next += kBlock; }
                paused = true;
            }
            if (paused && !resumed) {
                // The real Kotlin writer never asks dacLockState here. The existing keep-alive reaps
                // readings, so stuck can begin while paused, without a device restart.
                if ((nowUs() - t0) / 1000 < kResumeAtMs) { w.feedSilence(); continue; }
                CHECK(w.ctx->feedbackWatch.stuck, "pause replay never reached stuck");
                CHECK(g_usb.setInterfaceCalls == 0 && g_usb.resetCalls == 0, "recovery ran during pause");
                w.resume();
                resumed = true;
            }
            w.write(src, next, kBlock);
            next += kBlock;
            int64_t state[USB_DAC_STATE_COUNT];
            usbDacLockState(w.ctx, state);
            const int step = (int)state[5];
            if (step >= USB_DAC_STEP_INTERFACE && step <= USB_DAC_STEP_GIVE_UP) steps[step]++;
            if (step == USB_DAC_STEP_INTERFACE || step == USB_DAC_STEP_BUS_RESET) {
                if (decisionUs < 0) decisionUs = nowUs();
                runOneStep(w, src, next, step, kBlock, [&] {
                    restarts++;
                    restartUs = nowUs();
                });
                doneUs = nowUs();
            }
            // The four-second verdict cannot announce success while a renewed five-second
            // frozen run is still awaiting classification (including the usual start ramp).
            if (c.cure >= 2 && restarts > 0 && (c.cure == 3 || restarts == 1)) {
                CHECK(w.ctx->dacPolicy.awaitingStep != 0 || w.ctx->feedbackWatch.stuck,
                      "%s reported ok before renewed freeze reached its hold", c.name);
            }
        }
        const int expectedFirst = c.cure == 0 ? 0 : 1;
        CHECK(steps[1] == expectedFirst && steps[2] == (c.cure >= 2 ? 1 : 0) && steps[3] == (c.cure == 3 ? 1 : 0),
              "%s steps=%d/%d/%d", c.name, steps[1], steps[2], steps[3]);
        const int64_t hold = usbFeedbackStuckHoldMs(c.policy, usbFeedbackPpm(kFrozen, kNominal));
        int stuckLocks = 0, railLocks = 0, okCount = 0;
        int64_t firstLockUs = -1;
        for (const auto &entry : g_usb.trainLines) {
            const std::string &line = entry.second;
            if (line.find("DAC_LOCKED") != std::string::npos) {
                if (firstLockUs < 0) firstLockUs = entry.first;
                stuckLocks += line.find("why=stuck") != std::string::npos;
                railLocks += line.find("why=rail") != std::string::npos;
                CHECK(line.find("heldMs=" + std::to_string(hold)) != std::string::npos,
                      "stuck recovery line lacks the existing hold: %s", line.c_str());
                CHECK(line.find("feedback=6.0399") != std::string::npos && line.find("+6653 ppm") != std::string::npos,
                      "stuck recovery line lacks the reading and ppm: %s", line.c_str());
            }
            if (line.find("result=ok") != std::string::npos) {
                okCount++;
                CHECK(doneUs > 0 && entry.first - doneUs >= USB_DAC_RECOVERY_VERIFY_MS * 1000LL,
                      "ok before the verify window");
            }
            if (line.find("DAC_LOCKED") != std::string::npos || line.find("DAC_RECOVERY") != std::string::npos ||
                line.find("FEEDBACK_OUT_OF_WINDOW") != std::string::npos) {
                printf("B48-fix4 %s t=%lldms %s\n", c.name, (long long)((entry.first - t0) / 1000), line.c_str());
            }
        }
        CHECK(stuckLocks == (c.cure >= 2 ? (c.cure == 3 ? 3 : 2) : 1) && railLocks == 0,
              "%s watches stuck=%d rail=%d", c.name, stuckLocks, railLocks);
        CHECK(firstLockUs / 1000 - t0 / 1000 >= hold, "lock before its hold");
        if (!c.pause && c.cure != 0) {
            CHECK(decisionUs - firstLockUs >= 0 && decisionUs - firstLockUs < kBlock * 1000000LL / kRate + 3000,
                  "step 1 did not follow stuck at its hold");
        }
        if (c.pause) CHECK(decisionUs - t0 >= kResumeAtMs * 1000LL, "paused recovery before resume");
        CHECK(okCount == (c.cure == 1 || c.cure == 2 ? 1 : 0), "%s ok count=%d", c.name, okCount);
        const auto stuckLines = feedbackGlitchLines("FEEDBACK_STUCK");
        CHECK(!stuckLines.empty(), "%s missing stuck line", c.name);
        for (const std::string &line : stuckLines) printf("B48-fix4 %s %s\n", c.name, line.c_str());
        char report[32768];
        usbSecondSummaries(kReplayMs, report, sizeof(report));
        if (c.cure != 0) {
            CHECK(strstr(report, "dac-locked stuck") != nullptr && strstr(report, "dac-recovery step 1 ran") != nullptr,
                  "%s Mark noise report lacks stuck recovery events", c.name);
        }
        const std::string streamTag = "stream=" + std::to_string(w.ctx->streamSeq) + " ";
        const std::string dump(report);
        for (size_t begin = 0, end; begin < dump.size(); begin = end + 1) {
            end = dump.find('\n', begin);
            if (end == std::string::npos) end = dump.size();
            const std::string line = dump.substr(begin, end - begin);
            if (line.find(streamTag) != std::string::npos && (line.find("stuck-begin") != std::string::npos ||
                line.find("dac-locked") != std::string::npos || line.find("dac-recovery") != std::string::npos)) {
                printf("B48-fix4 report %s\n", line.c_str());
            }
        }
        printf("B48-fix4 %s hold=%lldms firstLock=%lldms firstStep=%lldms steps=%d/%d/%d ok=%d\n", c.name,
               (long long)hold, (long long)((firstLockUs - t0) / 1000), (long long)(decisionUs < 0 ? -1 : (decisionUs - t0) / 1000), steps[1], steps[2], steps[3], okCount);
        setFeedbackScript(nullptr);
    }
    usbSetDacRecoveryEnabled(true);
    usbSetFeedbackPolicy(USB_FEEDBACK_AUTO);
    // A recovered, dithering clock can close its episode. Short blips must not keep it open
    // forever, or a later fault would incorrectly jump straight to step 2.
    {
        constexpr int kSecondFaultMs = USB_DAC_RECOVERY_COOLDOWN_MS + USB_FEEDBACK_STUCK_MIN_MS;
        constexpr int kDitherPeriodMs = 50;
        constexpr double kBlip = kNominal * 1.0025;
        Writer w(kRate, true);
        usbDacRecoveryForgetEpisodes();
        const int64_t t0 = nowUs();
        const Pcm src = makeNoise(kRate * (kSecondFaultMs / 1000 + 10), 24, 0x48400003u);
        int next = 0, restarts = 0;
        setFeedbackScript([&](int64_t atUs) {
            FeedbackAnswer a;
            const int64_t ms = (atUs - t0) / 1000;
            a.fpmf = restarts == 0 || ms >= kSecondFaultMs ? kFrozen :
                (ms % kDitherPeriodMs == 0 ? kBlip : kNominal);
            return a;
        });
        while (next + kBlock <= src.frames() && nowUs() - t0 < (kSecondFaultMs + USB_FEEDBACK_STUCK_MIN_MS + 1000) * 1000LL) {
            w.write(src, next, kBlock); next += kBlock;
            int64_t state[USB_DAC_STATE_COUNT];
            usbDacLockState(w.ctx, state);
            if (state[5] == USB_DAC_STEP_INTERFACE || state[5] == USB_DAC_STEP_BUS_RESET) {
                CHECK(state[5] == USB_DAC_STEP_INTERFACE, "healthy dither failed to close the prior episode");
                runOneStep(w, src, next, (int)state[5], kBlock, [&] { restarts++; });
            }
        }
        CHECK(restarts == 2, "later frozen episode did not get step 1 after cooldown");
        printf("B48-fix4 healthy dither closes the episode and later freeze gets step 1 again restarts=%d\n", restarts);
        setFeedbackScript(nullptr);
    }
    // Healthy controls keep polling the policy for minutes at nominal, or across a 3 s start plateau.
    for (bool plateau : {true, false}) {
        usbDacRecoveryForgetEpisodes();
        Writer w(kRate, true);
        const Pcm block = makeNoise(kBlock, 24, 0x48400002u);
        const int64_t t0 = nowUs();
        setFeedbackScript([=](int64_t atUs) {
            FeedbackAnswer a;
            a.fpmf = plateau && (atUs - t0) / 1000 < kHealthyPlateauMs ? kHealthyPlateau : kNominal;
            return a;
        });
        while (nowUs() - t0 < (plateau ? kReplayMs : kNominalMs) * 1000LL) {
            w.write(block, 0, kBlock);
            int64_t state[USB_DAC_STATE_COUNT];
            usbDacLockState(w.ctx, state);
            CHECK(state[0] == 0 && state[5] == USB_DAC_STEP_NONE && !w.ctx->feedbackWatch.stuck,
                  "healthy %s requested recovery", plateau ? "3 s plateau" : "nominal");
        }
        CHECK(feedbackGlitchLines("FEEDBACK_STUCK").empty(), "healthy feedback called stuck");
        CHECK(g_usb.setInterfaceCalls == 0 && g_usb.resetCalls == 0, "healthy feedback restarted");
        printf("B48-fix4 healthy %s duration=%lldms no stuck and no recovery\n", plateau ? "+1 percent plateau 3000ms" : "nominal", (long long)((nowUs() - t0) / 1000));
        setFeedbackScript(nullptr);
    }
}

int main(int argc, char **argv) {
    const int policyAtStart = usbFeedbackPolicy();                  // before any test has set one
#ifndef B48FIX1_BEFORE
    g_guardWasOnAtProcessStart = usbFeedbackStartGuardEnabled();    // B48-fix1: before any test has touched the switch
#endif
    const std::string only = argc > 1 ? argv[1] : "";
    Pcm a = makeNoise(kFrames96, 24, 0x1234abcdu), b = makeNoise(kFrames96, 24, 0x9e3779b9u);
    Pcm c = makeNoise(kFrames441, 16, 0x0badf00du);
    if (only.empty() || only == "pristine") testPristineFormats();
    if (only.empty() || only == "pipeline") testPipelineEndOfStreamPlaysOut(a, c);
    if (only.empty() || only == "gesture") testPipelineGestures(a, b);
    if (only.empty() || only == "wait") {
        testWaitModes(a);
        testSilentPollDoesNotStarve(a);
        testWaitTimeouts();
    }
    if (only.empty() || only == "feedback") {
        testStuckFeedbackAndPolicies();
        testPolicySwitchConservesFrames();
        testHealthyFeedbackIsNotAnEpisode();
    }
    if (only.empty() || only == "feedback" || only == "default-auto") testDefaultAutoRefusesImplausibleFeedback(policyAtStart);
    if (only.empty() || only == "feedback" || only == "seek-ramp") {
        testSeekRampDoesNotLeak(+1);
        testSeekRampDoesNotLeak(-1);
        testHonestDacIsStillFollowedAfterSeeks();
        testStuckReachesTheBlockInProgress();
    }
    if (only.empty() || only == "feedback" || only == "evidence") testFeedbackEvidenceInTheDump();
    if (only.empty() || only == "output-gap") testOutputGap();
    if (only.empty() || only == "summaries") testStreamSecondSummaries();
    if (only.empty() || only == "transport") testTransportAssumptions();
    if (only.empty() || only == "skip") testSkipSilenceFeed();
    if (only.empty() || only == "recovery") testWriterRecovery();
    if (only.empty() || only == "lead" || only == "cpu") testWhoHadTheCpu();
    if (only.empty() || only == "lead") {
        testLeadSurvivesTheWorstStall();
        testLeadLatencyOfGestures();
    }
    if (only.empty() || only == "resume") testResumePrimesTheRing();
    if (only.empty() || only == "dac-lock") testLockedDacRecovery();
    if (only.empty() || only == "auto-early") testAutoEarly();
    if (only.empty() || only == "startprime") testEveryStartPrimesTheRing();
    if (only.empty() || only == "startguard") testFeedbackStartGuard();
    if (only.empty() || only == "train") testTrainFeedback();
    if (only.empty() || only == "rate-change") testRateChangeStuckRecovery();
    if (only.empty() || only == "stuck-recovery") testStuckDacRecovery();
    if (only.empty() || only == "pause-keepalive") testPauseKeepalive();
    if (only.empty() || only == "pause-seek-keepalive") testPauseSeekKeepalive();
    if (only.empty() || only == "guard-default") testStartGuardDefaultOff();
    if (g_failures) {
        printf("natural join simulation: %d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("natural join simulation: PASS\n");
    return 0;
}
