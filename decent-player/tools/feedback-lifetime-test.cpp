// Host fault injection for the one-shot feedback read at stream start (B14-1, audit A-03 / T-11).
//
// What runs is the real usb-audio-output.cpp, entered through the JNI start the sink calls. Only the kernel is
// faked, and it is faked strictly: `ioctl` and `poll` on one fd play usbfs, and the fake keeps every URB it
// holds, from SUBMITURB until a reap hands it back. Like usbfs it writes the completion (status, actual length,
// data) into the caller's memory when the URB is *reaped*, not when it completes, so a URB that was freed in
// between is written into freed memory. Three monitors turn that into a failure on any host, with no sanitizer:
//
//   - `free` is wrapped (-Wl,--wrap=free): freeing a URB, or its data buffer, that the kernel still holds is a
//     violation (and is not performed, so the test survives it);
//   - SUBMITURB checks that the data buffer is not on the calling thread's stack (the original read pointed it at
//     a local that was gone when the late completion was written);
//   - every URB the test plants or the code allocates is followed to its end: freed once, after its reap.
//
// The scenarios are the audit's probe and its neighbours: EAGAIN on the first reap, DISCARD accepted, EAGAIN on the
// second and a completion that arrives later (past the bounded wait, and inside it); a completion that is not the
// read's own URB on the success path (a parked one, a stale ring one); a device that is gone; and a quarantine
// that is full.
//
// B27-A2 (review R-02) runs the same five faults against the CONTINUOUS feedback URB (the one that is in flight for the whole
// stream and is cancelled at every teardown and barrier) and against the ring's own fallback when a URB cannot be parked.
// `free` is checked by range as well as by address: a context freed while the kernel holds a pointer into it (the
// continuous URB's data buffer used to be `ctx->feedbackBuffer`) is a violation too.
//
// AddressSanitizer was tried and cannot be used on this host: the NDK ships the asan runtime for Android targets
// only and the host's gcc has the dev stub without libasan.so.*, so nothing links. The monitors above are what
// this test relies on; it fails against the pre-B14 readFeedback (27 checks) and passes against the fix.
#include <jni.h>
#include <malloc.h>
#include <poll.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#include "urb-quarantine.h"
#include "usb-audio-output.h"

#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <unistd.h>

extern "C" {
JNIEXPORT jlong JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioCreate(
    JNIEnv *, jobject, jint, jint, jint, jint, jint, jint, jint, jint);
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(JNIEnv *, jobject, jlong);
JNIEXPORT jint JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(JNIEnv *, jobject, jlong);

void *__real_calloc(size_t, size_t);
void *__wrap_calloc(size_t, size_t);
void __real_free(void *);
void __wrap_free(void *);
}

namespace {

constexpr int kFakeFd = 778;
constexpr int kEndpointOut = 0x07;
constexpr int kEndpointFeedback = 0x88;
constexpr int kRate = 44100;
constexpr double kNominalFpmf = kRate / 8000.0;
constexpr int64_t kNever = INT64_MAX;
constexpr int kMaxHeld = 512;
constexpr int kMaxWatch = 16;
constexpr int kMaxViolations = 16;

using Clock = std::chrono::steady_clock;
int64_t nowUs() {
    return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now().time_since_epoch()).count();
}

// ── the strict fake usbfs ───────────────────────────────────────────────────────────────────────

/** A URB the kernel holds: accepted by SUBMITURB (or planted by the test) and not yet handed back by a reap. */
struct Held {
    bool used = false;
    usbdevfs_urb *urb = nullptr;
    uint8_t *buffer = nullptr;
    int length = 0;
    int64_t readyAtUs = kNever;  // when a reap can return it
    bool cancelled = false;
    bool oneShot = false;        // the read's URB (feedback endpoint, no usercontext)
    bool continuous = false;     // the stream's continuous feedback URB (feedback endpoint, with usercontext)
    bool ring = false;           // an audio URB of the ring
    bool planted = false;        // a completion of an earlier life, planted by the test: no data to write
};

/** An address the test follows: how often it was freed, and whether the kernel had handed it back first. */
struct Watch {
    const void *addr = nullptr;
    int frees = 0;
    int freesWhileHeld = 0;
};

struct Script {
    double fpmf = kNominalFpmf * 1.001;  // what the DAC answers, inside the +-5 % window
    int64_t answerAfterUs = 500;         // the one-shot's own completion; kNever: the DAC does not answer
    int64_t lateAfterDiscardUs = 0;      // after an accepted DISCARDURB its (-ENOENT) completion is reapable this
                                         // much later; kNever: held until the test releases it
    int64_t continuousAnswerUs = 1000;   // the continuous feedback URB's own completion; kNever: the DAC does not answer
    int64_t continuousLateUs = 0;        // like lateAfterDiscardUs, for the continuous feedback URB
    int64_t ringAnswerUs = 1000;         // an audio URB's own completion (1 ms of audio); kNever: the host never completes it
    int64_t ringLateUs = 0;              // like lateAfterDiscardUs, for the audio URBs
    bool gone = false;                   // every reap and discard fails with ENODEV
};

pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;  // never held across an allocation or a free
Held g_held[kMaxHeld];
Watch g_watch[kMaxWatch];
Script g_script;
char g_violations[kMaxViolations][160];
int g_violationCount = 0;
usbdevfs_urb *g_oneShotUrb = nullptr;
bool g_failNextCalloc = false;
int g_oneShotSubmits = 0;
int g_reaps = 0;

void violation(const char *fmt, ...) {  // g_mu held
    if (g_violationCount >= kMaxViolations) return;
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_violations[g_violationCount], sizeof(g_violations[0]), fmt, ap);
    va_end(ap);
    g_violationCount++;
}

Watch *watchOf(const void *addr) {  // g_mu held
    for (Watch &w : g_watch) {
        if (w.addr == addr) return &w;
    }
    return nullptr;
}

void watch(const void *addr) {
    pthread_mutex_lock(&g_mu);
    if (watchOf(addr) == nullptr) {
        for (Watch &w : g_watch) {
            if (w.addr == nullptr) {
                w = Watch();
                w.addr = addr;
                break;
            }
        }
    }
    pthread_mutex_unlock(&g_mu);
}

int freesOf(const void *addr) {
    pthread_mutex_lock(&g_mu);
    const Watch *w = watchOf(addr);
    const int n = w ? w->frees : -1;
    pthread_mutex_unlock(&g_mu);
    return n;
}

Held *heldOf(const void *urb) {  // g_mu held
    for (Held &h : g_held) {
        if (h.used && h.urb == urb) return &h;
    }
    return nullptr;
}

bool isHeld(const void *urb) {
    pthread_mutex_lock(&g_mu);
    const bool held = heldOf(urb) != nullptr;
    pthread_mutex_unlock(&g_mu);
    return held;
}

bool onCallerStack(const void *p) {
    pthread_attr_t attr;
    void *base = nullptr;
    size_t size = 0;
    if (pthread_getattr_np(pthread_self(), &attr) != 0) return false;
    pthread_attr_getstack(&attr, &base, &size);
    pthread_attr_destroy(&attr);
    return p >= base && p < static_cast<const uint8_t *>(base) + size;
}

void plant(usbdevfs_urb *urb, int64_t readyAtUs) {
    pthread_mutex_lock(&g_mu);
    for (Held &h : g_held) {
        if (!h.used) {
            h = Held();
            h.used = true;
            h.urb = urb;
            h.readyAtUs = readyAtUs;
            h.planted = true;
            break;
        }
    }
    pthread_mutex_unlock(&g_mu);
}

/** The late completion finally arrives (the DAC or the host controller woke up). */
void releaseCancelled() {
    pthread_mutex_lock(&g_mu);
    for (Held &h : g_held) {
        if (h.used && h.cancelled) h.readyAtUs = 0;
    }
    pthread_mutex_unlock(&g_mu);
}

void resetKernel() {
    pthread_mutex_lock(&g_mu);
    for (Held &h : g_held) h = Held();
    for (Watch &w : g_watch) w = Watch();
    g_script = Script();
    g_violationCount = 0;
    g_oneShotUrb = nullptr;
    g_oneShotSubmits = 0;
    g_reaps = 0;
    pthread_mutex_unlock(&g_mu);
}

}  // namespace

/** A free of an address the kernel still holds is the use after free this test exists to catch: record, do not free. */
extern "C" void __wrap_free(void *p) {
    if (p != nullptr) {
        pthread_mutex_lock(&g_mu);
        const size_t usable = malloc_usable_size(p);
        for (Held &h : g_held) {
            if (!h.used) continue;
            // Freeing the URB, its buffer, or the block that CONTAINS the buffer the kernel will write into (a context). An audio
            // URB is an OUT transfer: usbfs copies its data at submit and never touches the caller's buffer again, so only its
            // URB struct (where the completion is written) counts, as the quarantine's own design says.
            const uint8_t *buffer = h.ring ? nullptr : h.buffer;
            const bool inside = buffer != nullptr && buffer >= static_cast<const uint8_t *>(p) && buffer < static_cast<const uint8_t *>(p) + usable;
            if (h.urb == p || (buffer != nullptr && buffer == p) || inside) {
                violation("free(%p) of a URB the kernel still holds (%s)", p,
                          h.oneShot ? "one-shot read" : (h.continuous ? "continuous feedback" : (h.ring ? "ring" : "other")));
                if (Watch *w = watchOf(p)) w->freesWhileHeld++;
                if (Watch *w = watchOf(h.urb)) w->freesWhileHeld++;
                pthread_mutex_unlock(&g_mu);
                return;
            }
        }
        if (Watch *w = watchOf(p)) w->frees++;
        pthread_mutex_unlock(&g_mu);
    }
    __real_free(p);
}

extern "C" void *__wrap_calloc(size_t count, size_t size) {
    if (g_failNextCalloc) {
        g_failNextCalloc = false;
        return nullptr;
    }
    return __real_calloc(count, size);
}

extern "C" int ioctl(int fd, unsigned long request, ...) {
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    if (fd != kFakeFd) {
        errno = ENOTTY;
        return -1;
    }
    const int64_t now = nowUs();
    pthread_mutex_lock(&g_mu);
    switch (request) {
        case USBDEVFS_SUBMITURB: {
            auto *u = static_cast<usbdevfs_urb *>(arg);
            if (heldOf(u) != nullptr) violation("SUBMITURB reused a kernel-owned URB %p", (void *)u);
            for (const Held &held : g_held) {
                if (held.used && !held.ring && held.buffer == u->buffer) violation("SUBMITURB reused a kernel-owned buffer %p", u->buffer);
            }
            Held h;
            h.used = true;
            h.urb = u;
            h.buffer = static_cast<uint8_t *>(u->buffer);
            h.length = u->buffer_length;
            if (onCallerStack(u->buffer)) {
                violation("SUBMITURB ep=0x%02x: the data buffer %p is on the submitting thread's stack", u->endpoint, u->buffer);
            }
            if (u->endpoint == kEndpointFeedback && u->usercontext == nullptr) {
                h.oneShot = true;
                h.readyAtUs = g_script.answerAfterUs == kNever ? kNever : now + g_script.answerAfterUs;
                g_oneShotUrb = u;
                g_oneShotSubmits++;
            } else if (u->endpoint == kEndpointFeedback) {
                h.continuous = true;
                h.readyAtUs = g_script.continuousAnswerUs == kNever ? kNever : now + g_script.continuousAnswerUs;  // the continuous feedback URB
            } else {
                h.ring = true;
                h.readyAtUs = g_script.ringAnswerUs == kNever ? kNever : now + g_script.ringAnswerUs;  // 1 ms of audio
            }
            for (Held &slot : g_held) {
                if (!slot.used) {
                    slot = h;
                    pthread_mutex_unlock(&g_mu);
                    return 0;
                }
            }
            pthread_mutex_unlock(&g_mu);
            errno = ENOMEM;
            return -1;
        }
        case USBDEVFS_REAPURBNDELAY: {
            if (g_script.gone) {
                pthread_mutex_unlock(&g_mu);
                errno = ENODEV;
                return -1;
            }
            Held *best = nullptr;
            for (Held &h : g_held) {
                if (h.used && h.readyAtUs <= now && (best == nullptr || h.readyAtUs < best->readyAtUs)) best = &h;
            }
            if (best == nullptr) {
                pthread_mutex_unlock(&g_mu);
                errno = EAGAIN;
                return -1;
            }
            // The kernel writes the completion into the caller's memory now, at the reap.
            usbdevfs_urb *u = best->urb;
            if (best->planted) {
                u->status = 0;
            } else {
                u->status = best->cancelled ? -ENOENT : 0;
                for (int i = 0; i < u->number_of_packets; i++) {
                    u->iso_frame_desc[i].status = 0;
                    u->iso_frame_desc[i].actual_length = best->cancelled ? 0 : u->iso_frame_desc[i].length;
                }
                if ((best->oneShot || best->continuous) && !best->cancelled && best->length >= 4) {
                    const uint32_t raw = (uint32_t)llround(g_script.fpmf * 65536.0);
                    memcpy(best->buffer, &raw, 4);
                }
            }
            g_reaps++;
            *static_cast<usbdevfs_urb **>(arg) = u;
            *best = Held();
            pthread_mutex_unlock(&g_mu);
            return 0;
        }
        case USBDEVFS_DISCARDURB: {
            Held *h = heldOf(arg);
            if (g_script.gone) {
                pthread_mutex_unlock(&g_mu);
                errno = ENODEV;
                return -1;
            }
            if (h == nullptr || h->readyAtUs <= now) {  // not pending: it completed and waits to be reaped
                pthread_mutex_unlock(&g_mu);
                errno = EINVAL;
                return -1;
            }
            h->cancelled = true;
            // Each kind of URB has its own script for how late its cancelled completion comes; the default is straight back.
            const int64_t lateUs = h->oneShot ? g_script.lateAfterDiscardUs
                                 : (h->continuous ? g_script.continuousLateUs : (h->ring ? g_script.ringLateUs : 0));
            h->readyAtUs = lateUs == kNever ? kNever : now + lateUs;
            pthread_mutex_unlock(&g_mu);
            return 0;
        }
        default:
            pthread_mutex_unlock(&g_mu);
            return 0;
    }
}

extern "C" int poll(struct pollfd *fds, nfds_t nfds, int timeoutMs) {
    if (nfds != 1 || fds[0].fd != kFakeFd) return -1;
    const int64_t deadline = nowUs() + (int64_t)timeoutMs * 1000;
    for (;;) {
        pthread_mutex_lock(&g_mu);
        const int64_t now = nowUs();
        for (const Held &h : g_held) {
            if (h.used && h.readyAtUs <= now) {
                pthread_mutex_unlock(&g_mu);
                fds[0].revents = POLLOUT;
                return 1;
            }
        }
        pthread_mutex_unlock(&g_mu);
        if (nowUs() >= deadline) {
            fds[0].revents = 0;
            return 0;
        }
        usleep(100);
    }
}

extern "C" int __android_log_print(int, const char *tag, const char *fmt, ...) {
    static const bool verbose = getenv("SIM_LOG") != nullptr;
    if (!verbose) return 0;
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

/** What every scenario must satisfy: the kernel never held a URB the code freed. */
void checkNoViolations(const char *scenario) {
    pthread_mutex_lock(&g_mu);
    const int n = g_violationCount;
    for (int i = 0; i < n; i++) {
        g_failures++;
        printf("  FAIL %s: %s\n", scenario, g_violations[i]);
    }
    g_violationCount = 0;  // each one is reported once
    pthread_mutex_unlock(&g_mu);
}

UsbAudioContext *createStream() {
    const jlong h = Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioCreate(
        nullptr, nullptr, kFakeFd, 1, kEndpointOut, kEndpointFeedback, kRate, 2, 32, 400);
    if (h == 0) {
        fprintf(stderr, "stream was not created\n");
        exit(2);
    }
    return reinterpret_cast<UsbAudioContext *>(h);
}

bool startStream(UsbAudioContext *ctx) {
    return Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(nullptr, nullptr, reinterpret_cast<jlong>(ctx));
}

/** What `releaseUsbStream` does. The continuous feedback URB is the only thing the kernel may still hold for it. */
void releaseStream(UsbAudioContext *ctx) {
    const jlong h = reinterpret_cast<jlong>(ctx);
    Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(nullptr, nullptr, h);
    Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(nullptr, nullptr, h);
    Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(nullptr, nullptr, h);
}

void stopAndDrain(UsbAudioContext *ctx) {
    const jlong h = reinterpret_cast<jlong>(ctx);
    Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(nullptr, nullptr, h);
    Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(nullptr, nullptr, h);
}

void destroyStream(UsbAudioContext *ctx) {
    Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(nullptr, nullptr, reinterpret_cast<jlong>(ctx));
}

/** A reap site that runs after the stream started: 10 ms of silence queued, then everything already completed retired. */
void runAReapSite(UsbAudioContext *ctx) {
    static uint8_t silence[8 * 441];  // 10 ms of 44.1 kHz stereo in 32-bit slots
    memset(silence, 0, sizeof(silence));
    submitPcmToUrbs(ctx, silence, (int)sizeof(silence));
    usleep(20000);  // past the audio's own completion
    usbQueuedFrames(ctx);
}

void emptyQuarantine() {
    // Whatever a scenario parked and nobody reaped (a device that is gone): the test frees it, the code never may.
    for (int guard = 0; guard < 1000 && urbQuarantineCount() > 0; guard++) {
        void *any = nullptr;
        pthread_mutex_lock(&g_mu);
        for (const Watch &w : g_watch) {
            if (w.addr != nullptr && urbQuarantineTake(w.addr)) {
                any = const_cast<void *>(w.addr);
                break;
            }
        }
        pthread_mutex_unlock(&g_mu);
        if (any == nullptr) break;
        free(any);
    }
}

// ── scenarios ───────────────────────────────────────────────────────────────────────────────────

/** The DAC answers inside the first wait: the reading is used and the URB is freed once, after its reap. */
void testHealthyAnswer() {
    resetKernel();
    UsbAudioContext *ctx = createStream();
    CHECK(startStream(ctx), "the stream did not start");
    const double expected = g_script.fpmf;
    CHECK(std::fabs(ctx->calibratedFpmf - expected) < 1e-3, "calibrated %.5f, the DAC said %.5f", ctx->calibratedFpmf, expected);
    CHECK(g_oneShotSubmits == 1, "%d one-shot reads", g_oneShotSubmits);
    CHECK(!isHeld(g_oneShotUrb), "the kernel still holds the read's URB");
    CHECK(urbQuarantineCount() == 0, "%d URBs parked after a read that completed", urbQuarantineCount());
    releaseStream(ctx);
    checkNoViolations("healthy");
}

/**
 * The audit's probe (T-11): EAGAIN on the first reap, DISCARD accepted, EAGAIN on the second (and on every one
 * of the bounded wait), and the completion only comes later. Nothing may be freed while the kernel holds the
 * URB; it is parked, and the reap site that finally receives it frees it, once, after the kernel wrote into it.
 */
void testLateCompletionAfterCancel() {
    resetKernel();
    g_script.answerAfterUs = kNever;
    g_script.lateAfterDiscardUs = kNever;
    UsbAudioContext *ctx = createStream();
    CHECK(startStream(ctx), "the stream did not start");
    usbdevfs_urb *oneShot = g_oneShotUrb;
    CHECK(oneShot != nullptr, "no one-shot read was submitted");
    watch(oneShot);
    CHECK(ctx->calibratedFpmf == kNominalFpmf, "a read that never answered moved the rate to %.5f", ctx->calibratedFpmf);
    checkNoViolations("late completion, at the return of the read");
    CHECK(isHeld(oneShot), "the kernel does not hold the read's URB: the scenario did not happen");
    CHECK(urbQuarantineCount() == 1, "%d URBs parked, expected the read's URB", urbQuarantineCount());
    CHECK(freesOf(oneShot) == 0, "the read's URB was freed while the kernel still held it");

    releaseCancelled();  // the late completion arrives
    runAReapSite(ctx);
    CHECK(!isHeld(oneShot), "the late completion was never collected");
    CHECK(freesOf(oneShot) == 1, "the late URB was freed %d times, expected once (after its reap)", freesOf(oneShot));
    CHECK(urbQuarantineCount() == 0, "%d URBs still parked", urbQuarantineCount());
    releaseStream(ctx);
    checkNoViolations("late completion");
}

/** DISCARD accepted and the cancelled completion comes back inside the bounded wait: reaped, then freed, not parked. */
void testCancelledCompletionInsideTheWait() {
    resetKernel();
    g_script.answerAfterUs = kNever;
    g_script.lateAfterDiscardUs = 300;
    UsbAudioContext *ctx = createStream();
    CHECK(startStream(ctx), "the stream did not start");
    usbdevfs_urb *oneShot = g_oneShotUrb;
    CHECK(!isHeld(oneShot), "the cancelled URB is still held by the kernel");
    CHECK(urbQuarantineCount() == 0, "%d URBs parked for a completion that came back", urbQuarantineCount());
    CHECK(ctx->calibratedFpmf == kNominalFpmf, "a cancelled read moved the rate to %.5f", ctx->calibratedFpmf);
    releaseStream(ctx);
    checkNoViolations("cancelled completion inside the wait");
}

/** A parked URB of an earlier life comes back first: freed by the read (it is the caller's now), the read keeps waiting. */
void testParkedCompletionBeforeTheReadsOwn() {
    resetKernel();
    auto *parked = (usbdevfs_urb *)calloc(1, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc));
    watch(parked);
    CHECK(urbQuarantineAdd(parked), "the quarantine refused a URB");
    plant(parked, 0);
    UsbAudioContext *ctx = createStream();
    CHECK(startStream(ctx), "the stream did not start");
    CHECK(freesOf(parked) == 1, "the parked URB was freed %d times, expected once", freesOf(parked));
    CHECK(urbQuarantineCount() == 0, "%d URBs still parked", urbQuarantineCount());
    CHECK(g_oneShotSubmits == 1, "%d one-shot reads", g_oneShotSubmits);
    CHECK(!isHeld(g_oneShotUrb), "the read's own URB was left in the kernel");
    CHECK(std::fabs(ctx->calibratedFpmf - g_script.fpmf) < 1e-3,
          "calibrated %.5f, the DAC said %.5f: the foreign completion was taken for the answer", ctx->calibratedFpmf, g_script.fpmf);
    releaseStream(ctx);
    checkNoViolations("parked completion first");
}

/** A completion that is neither the read's nor parked (a ring URB of an earlier run): dropped, never freed, never the answer. */
void testStaleRingCompletionBeforeTheReadsOwn() {
    resetKernel();
    UsbAudioContext *ctx = createStream();
    usbdevfs_urb *ringUrb = ctx->ring[3].urb;
    watch(ringUrb);
    plant(ringUrb, 0);
    CHECK(startStream(ctx), "the stream did not start");
    CHECK(freesOf(ringUrb) == 0, "a ring URB was freed by the read");
    CHECK(!isHeld(ringUrb), "the stale ring completion was not collected");
    CHECK(!isHeld(g_oneShotUrb), "the read's own URB was left in the kernel");
    CHECK(std::fabs(ctx->calibratedFpmf - g_script.fpmf) < 1e-3,
          "calibrated %.5f, the DAC said %.5f: the foreign completion was taken for the answer", ctx->calibratedFpmf, g_script.fpmf);
    releaseStream(ctx);
    checkNoViolations("stale ring completion first");
}

/** The device is gone after the submit: nothing can be proven back, so nothing may be freed. */
void testDeviceGone() {
    resetKernel();
    g_script.answerAfterUs = kNever;
    g_script.gone = true;
    UsbAudioContext *ctx = createStream();
    CHECK(startStream(ctx), "the stream did not start");
    usbdevfs_urb *oneShot = g_oneShotUrb;
    watch(oneShot);
    checkNoViolations("device gone");
    CHECK(freesOf(oneShot) == 0, "the read's URB was freed with the device gone");
    CHECK(urbQuarantineCount() == 1, "%d URBs parked, expected the read's URB", urbQuarantineCount());
    pthread_mutex_lock(&g_mu);
    g_script.gone = false;
    pthread_mutex_unlock(&g_mu);
    releaseStream(ctx);
    // The process-level end of that URB: nobody will ever reap it. The test frees it; the code must not have.
    pthread_mutex_lock(&g_mu);
    if (Held *h = heldOf(oneShot)) *h = Held();
    pthread_mutex_unlock(&g_mu);
    emptyQuarantine();
    checkNoViolations("device gone, released");
}

/** The quarantine is full: the URB cannot be parked and is leaked on purpose rather than freed. */
void testQuarantineFull() {
    resetKernel();
    g_script.answerAfterUs = kNever;
    g_script.lateAfterDiscardUs = kNever;
    void *fillers[URB_QUARANTINE_CAPACITY];
    for (int i = 0; i < URB_QUARANTINE_CAPACITY; i++) {
        fillers[i] = malloc(16);
        CHECK(urbQuarantineAdd(fillers[i]), "filler %d was refused", i);
    }
    UsbAudioContext *ctx = createStream();
    CHECK(startStream(ctx), "the stream did not start");
    usbdevfs_urb *oneShot = g_oneShotUrb;
    watch(oneShot);
    checkNoViolations("quarantine full");
    CHECK(freesOf(oneShot) == 0, "the read's URB was freed although it could not be parked");
    CHECK(isHeld(oneShot), "the kernel does not hold the read's URB: the scenario did not happen");
    CHECK(urbQuarantineCount() == URB_QUARANTINE_CAPACITY, "%d URBs parked, expected the full %d", urbQuarantineCount(), URB_QUARANTINE_CAPACITY);
    releaseStream(ctx);
    for (void *p : fillers) {
        CHECK(urbQuarantineTake(p), "a filler was lost");
        free(p);
    }
    // The leaked URB: its late completion arrives and nobody reaps it; the test releases it.
    pthread_mutex_lock(&g_mu);
    if (Held *h = heldOf(oneShot)) *h = Held();
    pthread_mutex_unlock(&g_mu);
    free(oneShot);
    checkNoViolations("quarantine full, released");
}


// ── B27-A2: the continuous feedback URB (review R-02) ──────────────────────────────────────────

/** The stream with its continuous feedback URB submitted and pending in the fake kernel; [urb] is that block, watched. */
UsbAudioContext *startWithPendingContinuous(usbdevfs_urb **urb) {
    g_script.continuousAnswerUs = kNever;   // the DAC does not answer: the URB stays in the kernel until it is cancelled
    UsbAudioContext *ctx = createStream();
    CHECK(startStream(ctx), "the stream did not start");
    *urb = ctx->feedbackUrb;
    CHECK(*urb != nullptr && ctx->feedbackInFlight, "the continuous feedback URB is not in flight: the scenario did not happen");
    watch(*urb);
    CHECK(isHeld(*urb), "the kernel does not hold the continuous feedback URB");
    return ctx;
}

/** Healthy: the cancelled completion comes straight back. Reaped, then freed once, with the context. Nothing parked. */
void testContinuousHealthy() {
    resetKernel();
    usbdevfs_urb *urb = nullptr;
    UsbAudioContext *ctx = startWithPendingContinuous(&urb);
    g_script.continuousLateUs = 0;
    stopAndDrain(ctx);
    CHECK(!isHeld(urb), "the cancelled continuous URB was not collected");
    CHECK(urbQuarantineCount() == 0, "%d URBs parked for a completion that came back", urbQuarantineCount());
    CHECK(freesOf(urb) == 0, "the continuous URB was freed before the stream was destroyed");
    destroyStream(ctx);
    CHECK(freesOf(urb) == 1, "the continuous URB was freed %d times, expected once (after its reap)", freesOf(urb));
    checkNoViolations("continuous, healthy");
}

/**
 * The review's probe: DISCARD accepted and the completion only comes later, past the bounded wait of the teardown. The URB is
 * not idle because 50 reaps came back empty: it is parked, the context may go, and only the reap that finally receives it frees
 * it, once.
 */
void testContinuousLateCancellation() {
    resetKernel();
    usbdevfs_urb *urb = nullptr;
    UsbAudioContext *ctx = startWithPendingContinuous(&urb);
    g_script.continuousLateUs = kNever;
    stopAndDrain(ctx);
    checkNoViolations("continuous, late cancellation, after the drain");
    CHECK(isHeld(urb), "the kernel does not hold the continuous URB: the scenario did not happen");
    CHECK(urbQuarantineCount() == 1, "%d URBs parked, expected the continuous feedback URB", urbQuarantineCount());
    CHECK(!ctx->feedbackInFlight, "the replacement block is marked in flight");
    CHECK(ctx->feedbackUrb != urb, "the stream still points at the URB the kernel holds");
    CHECK(freesOf(urb) == 0, "the continuous URB was freed while the kernel still held it");

    destroyStream(ctx);                       // the context is gone; the URB is not
    checkNoViolations("continuous, late cancellation, after the destroy");
    CHECK(isHeld(urb), "the kernel lost the continuous URB when the stream was destroyed");
    CHECK(freesOf(urb) == 0, "the continuous URB was freed with the stream while the kernel still held it");

    releaseCancelled();                       // the late completion arrives
    pthread_mutex_lock(&g_mu);
    g_script = Script();                      // and the DAC is healthy again for the stream that follows
    pthread_mutex_unlock(&g_mu);
    UsbAudioContext *next = createStream();   // another stream on the same fd: its first reap collects it
    CHECK(startStream(next), "the next stream did not start");
    CHECK(!isHeld(urb), "the late completion was never collected");
    CHECK(freesOf(urb) == 1, "the late continuous URB was freed %d times, expected once (after its reap)", freesOf(urb));
    CHECK(urbQuarantineCount() == 0, "%d URBs still parked", urbQuarantineCount());
    releaseStream(next);
    checkNoViolations("continuous, late cancellation, collected");
}

/** A completion that is not the continuous URB's (a stale one) arrives first: it is not taken for it, and not freed. */
void testContinuousForeignCompletion() {
    resetKernel();
    usbdevfs_urb *urb = nullptr;
    UsbAudioContext *ctx = startWithPendingContinuous(&urb);
    g_script.continuousLateUs = kNever;
    auto *foreign = (usbdevfs_urb *)calloc(1, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc));
    watch(foreign);
    plant(foreign, 0);
    stopAndDrain(ctx);
    checkNoViolations("continuous, foreign completion, after the drain");
    CHECK(!isHeld(foreign), "the foreign completion was not collected");
    CHECK(freesOf(foreign) == 0, "a completion that was nobody's was freed by the code");
    CHECK(isHeld(urb), "the kernel does not hold the continuous URB: the foreign completion was taken for it");
    CHECK(urbQuarantineCount() == 1, "%d URBs parked, expected the continuous feedback URB", urbQuarantineCount());
    CHECK(freesOf(urb) == 0, "the continuous URB was freed while the kernel still held it");
    destroyStream(ctx);
    checkNoViolations("continuous, foreign completion, after the destroy");
    free(foreign);
    releaseCancelled();
    pthread_mutex_lock(&g_mu);
    g_script = Script();
    pthread_mutex_unlock(&g_mu);
    UsbAudioContext *next = createStream();
    CHECK(startStream(next), "the next stream did not start");
    CHECK(freesOf(urb) == 1, "the late continuous URB was freed %d times, expected once", freesOf(urb));
    releaseStream(next);
    checkNoViolations("continuous, foreign completion, collected");
}

/** The device is gone: nothing can be proven back, so the URB is parked and never freed; the test ends it. */
void testContinuousDeviceGone() {
    resetKernel();
    usbdevfs_urb *urb = nullptr;
    UsbAudioContext *ctx = startWithPendingContinuous(&urb);
    pthread_mutex_lock(&g_mu);
    g_script.gone = true;
    pthread_mutex_unlock(&g_mu);
    stopAndDrain(ctx);
    checkNoViolations("continuous, device gone, after the drain");
    CHECK(urbQuarantineCount() == 1, "%d URBs parked, expected the continuous feedback URB", urbQuarantineCount());
    CHECK(freesOf(urb) == 0, "the continuous URB was freed with the device gone");
    destroyStream(ctx);
    checkNoViolations("continuous, device gone, after the destroy");
    CHECK(freesOf(urb) == 0, "the continuous URB was freed with the stream, the device gone");
    pthread_mutex_lock(&g_mu);
    g_script.gone = false;
    if (Held *h = heldOf(urb)) *h = Held();   // the process-level end: nobody will reap it; the test frees it
    pthread_mutex_unlock(&g_mu);
    emptyQuarantine();
    checkNoViolations("continuous, device gone, released");
}

/** The quarantine is full: it cannot be parked; it is waited for alone and, if it still does not come, leaked, never freed. */
void testContinuousQuarantineFull() {
    resetKernel();
    usbdevfs_urb *urb = nullptr;
    UsbAudioContext *ctx = startWithPendingContinuous(&urb);
    g_script.continuousLateUs = kNever;
    void *fillers[URB_QUARANTINE_CAPACITY];
    for (int i = 0; i < URB_QUARANTINE_CAPACITY; i++) {
        fillers[i] = malloc(16);
        CHECK(urbQuarantineAdd(fillers[i]), "filler %d was refused", i);
    }
    stopAndDrain(ctx);
    checkNoViolations("continuous, quarantine full, after the drain");
    CHECK(freesOf(urb) == 0, "the continuous URB was freed although it could not be parked");
    CHECK(isHeld(urb), "the kernel does not hold the continuous URB: the scenario did not happen");
    destroyStream(ctx);
    checkNoViolations("continuous, quarantine full, after the destroy");
    CHECK(freesOf(urb) == 0, "the continuous URB was freed with the stream although it could not be parked");
    for (void *p : fillers) {
        CHECK(urbQuarantineTake(p), "a filler was lost");
        free(p);
    }
    pthread_mutex_lock(&g_mu);
    if (Held *h = heldOf(urb)) *h = Held();   // the leaked URB's end: the test frees it
    pthread_mutex_unlock(&g_mu);
    free(urb);
    checkNoViolations("continuous, quarantine full, released");
}

/**
 * The ring's own fallback (review R-02, second branch): audio URBs the kernel never gives back while the quarantine is full.
 * The barrier waits for each alone and gives up; the slot keeps the URB, and the ring's free used to free it. Now it is leaked
 * behind a spare, so the free of the ring touches only what the kernel handed back or never held.
 */
void testRingUrbNeverReturnedWithAFullQuarantine() {
    resetKernel();
    g_script.ringAnswerUs = kNever;
    g_script.ringLateUs = kNever;
    void *fillers[URB_QUARANTINE_CAPACITY];
    for (int i = 0; i < URB_QUARANTINE_CAPACITY; i++) {
        fillers[i] = malloc(16);
        CHECK(urbQuarantineAdd(fillers[i]), "filler %d was refused", i);
    }
    UsbAudioContext *ctx = createStream();
    CHECK(startStream(ctx), "the stream did not start");
    usbdevfs_urb *ringUrb[3] = {ctx->ring[0].urb, ctx->ring[1].urb, ctx->ring[2].urb};
    for (usbdevfs_urb *u : ringUrb) watch(u);
    static uint8_t silence[8 * 44];  // 1 ms of 44.1 kHz stereo in 32-bit slots: one URB each
    memset(silence, 0, sizeof(silence));
    for (int i = 0; i < 3; i++) CHECK(submitPcmToUrbs(ctx, silence, (int)sizeof(silence)), "the write %d failed", i);
    int held = 0;
    for (usbdevfs_urb *u : ringUrb) held += isHeld(u) ? 1 : 0;
    CHECK(held == 3, "the kernel holds %d of the 3 audio URBs: the scenario did not happen", held);
    stopAndDrain(ctx);
    checkNoViolations("ring, never returned, after the drain");
    destroyStream(ctx);
    checkNoViolations("ring, never returned, after the destroy");
    for (usbdevfs_urb *u : ringUrb) CHECK(freesOf(u) == 0, "a ring URB the kernel never returned was freed with the ring");
    for (void *p : fillers) {
        CHECK(urbQuarantineTake(p), "a filler was lost");
        free(p);
    }
    for (usbdevfs_urb *u : ringUrb) {         // the leaked URBs' end: the test frees them
        pthread_mutex_lock(&g_mu);
        if (Held *h = heldOf(u)) *h = Held();
        pthread_mutex_unlock(&g_mu);
        free(u);
    }
    checkNoViolations("ring, never returned, released");
}

void testFullQuarantineLateFeedbackNormalClose() {
    resetKernel();
    usbdevfs_urb *oldFeedback = nullptr;
    UsbAudioContext *ctx = startWithPendingContinuous(&oldFeedback);
    g_script.continuousLateUs = kNever;
    void *fillers[URB_QUARANTINE_CAPACITY];
    for (int i = 0; i < URB_QUARANTINE_CAPACITY; ++i) {
        fillers[i] = malloc(16);
        CHECK(urbQuarantineAdd(fillers[i]), "review filler not parked");
    }
    stopAndDrain(ctx); // cancelled feedback not returned; cannot park; safely leaked
    CHECK(isHeld(oldFeedback), "review old feedback not held");
    CHECK(ctx->feedbackUrb != oldFeedback, "review feedback block was reused");
    g_script.ringAnswerUs = kNever;
    CHECK(startStream(ctx), "review restart failed");
    static uint8_t silence[8 * 44] = {};
    CHECK(submitPcmToUrbs(ctx, silence, sizeof(silence)), "review submit failed");
    usbdevfs_urb *audio = ctx->ring[0].urb;
    void *audioBuffer = ctx->ring[0].buffer;
    watch(audio);
    watch(audioBuffer);
    CHECK(ctx->urbsInFlight == 1 && isHeld(audio), "review needs exactly one held audio URB");
    pthread_mutex_lock(&g_mu);
    if (Held *h = heldOf(oldFeedback)) h->readyAtUs = nowUs() - 1;
    pthread_mutex_unlock(&g_mu);
    usbQueuedFrames(ctx); // late leaked feedback arrives, current audio is NOT complete
    printf("REVIEW_LATE_FEEDBACK audioHeld=%d countedInFlight=%d\n", isHeld(audio), ctx->urbsInFlight);
    CHECK(ctx->urbsInFlight == 1, "late leaked feedback retired an audio URB still held by the kernel");
    CHECK(isHeld(audio) && ctx->ring[0].urb == audio && freesOf(audio) == 0, "held audio identity or ownership was lost");
    g_script.continuousLateUs = 0;
    releaseStream(ctx); // normal close while audio is still held; only its own cancellation can return it
    CHECK(freesOf(audio) == 1 && freesOf(audioBuffer) == 1, "normal close did not free audio exactly once after its own return");
    checkNoViolations("review full quarantine then late feedback then normal close");
    for (void *p : fillers) { CHECK(urbQuarantineTake(p), "review filler lost"); free(p); }
    free(oldFeedback); // now reaped and leaked by design
}

// A leaked completion is not audio by exclusion, even if its endpoint/cookie look like audio.
// Both nonblocking queue inspection and backpressure must preserve the currently held audio's ownership.
void testFullQuarantineLateFeedbackDoesNotRetireAudio(bool backpressure, bool allocationFails) {
    resetKernel();
    const int failuresBefore = g_failures;
    usbdevfs_urb *oldFeedback = nullptr;
    UsbAudioContext *ctx = startWithPendingContinuous(&oldFeedback);
    g_script.continuousLateUs = kNever;
    void *fillers[URB_QUARANTINE_CAPACITY];
    for (int i = 0; i < URB_QUARANTINE_CAPACITY; ++i) {
        fillers[i] = malloc(16);
        CHECK(urbQuarantineAdd(fillers[i]), "filler not parked");
    }
    g_failNextCalloc = allocationFails;
    stopAndDrain(ctx);
    CHECK(!g_failNextCalloc, "replacement allocation fault was not consumed");
    CHECK(isHeld(oldFeedback), "old feedback not held");
    CHECK(ctx->feedbackUrb != oldFeedback, "feedback block was reused");
    if (allocationFails) CHECK(ctx->feedbackUrb == nullptr, "replacement allocation did not fail");
    CHECK(freesOf(oldFeedback) == 0, "leaked feedback was freed before its return");
    g_script.ringAnswerUs = kNever;
    CHECK(startStream(ctx), "restart failed");
    static uint8_t silence[8 * 44] = {};
    CHECK(submitPcmToUrbs(ctx, silence, sizeof(silence)), "submit failed");
    usbdevfs_urb *audio = ctx->ring[0].urb;
    void *audioBuffer = ctx->ring[0].buffer;
    watch(audio);
    watch(audioBuffer);
    CHECK(ctx->urbsInFlight == 1 && isHeld(audio), "needs exactly one held audio URB");
    const int reapIdx = ctx->reapIdx, submitIdx = ctx->submitIdx;
    const auto reaps = ctx->urbReaps.load();
    auto *foreign = static_cast<usbdevfs_urb *>(calloc(1, sizeof(usbdevfs_urb)));
    foreign->endpoint = kEndpointOut;
    foreign->usercontext = audio->usercontext;  // borrowed cookie, not proof of ownership
    watch(foreign);
    plant(foreign, nowUs() - 1);
    pthread_mutex_lock(&g_mu);
    if (Held *h = heldOf(oldFeedback)) h->readyAtUs = nowUs() - 2;
    pthread_mutex_unlock(&g_mu);
    if (backpressure) CHECK(!usbWaitForProgress(ctx), "foreign completions reported audio progress");
    else CHECK(usbQueuedFrames(ctx) == sizeof(silence) / 8, "foreign completions changed queued frames");
    CHECK(!isHeld(oldFeedback) && !isHeld(foreign), "late and foreign completions were not consumed");
    CHECK(ctx->urbsInFlight == 1 && ctx->reapIdx == reapIdx && ctx->submitIdx == submitIdx && ctx->urbReaps.load() == reaps,
          "late feedback or foreign completion changed audio accounting");
    CHECK(ctx->ring[0].urb == audio && ctx->ring[0].buffer == audioBuffer && isHeld(audio), "audio ownership changed");
    CHECK(freesOf(audio) == 0 && freesOf(audioBuffer) == 0 && freesOf(foreign) == 0 && freesOf(oldFeedback) == 0,
          "held audio or unowned completion was freed");
    free(foreign);  // only the test owns this address
    // Continue writing past a whole ring wrap, with the first audio completion still held until now.
    pthread_mutex_lock(&g_mu);
    if (Held *h = heldOf(audio)) h->readyAtUs = nowUs() - 1;
    pthread_mutex_unlock(&g_mu);
    g_script.ringAnswerUs = 0;
    for (int i = 0; i < USB_AUDIO_NUM_URBS + 2; ++i) {
        CHECK(submitPcmToUrbs(ctx, silence, sizeof(silence)), "continuation %d failed", i);
    }
    CHECK(!isHeld(audio), "the audio's own completion was not reaped");
    checkNoViolations("late feedback then continued writes");
    g_script.continuousLateUs = 0;
    releaseStream(ctx); // normal Stop + Drain + Destroy
    checkNoViolations("full quarantine then late feedback then normal close");
    CHECK(freesOf(audio) == 1 && freesOf(audioBuffer) == 1, "normal close did not release owned audio exactly once");
    for (void *p : fillers) { CHECK(urbQuarantineTake(p), "filler lost"); free(p); }
    free(oldFeedback); // reaped and deliberately leaked; only the test reclaims it
    printf("late feedback ownership %s allocation-%s %s\n", backpressure ? "backpressure" : "ready",
           allocationFails ? "failed" : "ok", g_failures == failuresBefore ? "PASS" : "FAIL");
}

}  // namespace

int main() {
    testHealthyAnswer();
    testLateCompletionAfterCancel();
    testCancelledCompletionInsideTheWait();
    testParkedCompletionBeforeTheReadsOwn();
    testStaleRingCompletionBeforeTheReadsOwn();
    testDeviceGone();
    testQuarantineFull();
    testContinuousHealthy();
    testContinuousLateCancellation();
    testContinuousForeignCompletion();
    testContinuousDeviceGone();
    testContinuousQuarantineFull();
    testRingUrbNeverReturnedWithAFullQuarantine();
    testFullQuarantineLateFeedbackNormalClose();
    testFullQuarantineLateFeedbackDoesNotRetireAudio(false, false);
    testFullQuarantineLateFeedbackDoesNotRetireAudio(true, false);
    testFullQuarantineLateFeedbackDoesNotRetireAudio(false, true);
    testFullQuarantineLateFeedbackDoesNotRetireAudio(true, true);
    if (g_failures != 0) {
        printf("feedback lifetime: %d FAILED check(s)\n", g_failures);
        return 1;
    }
    printf("feedback lifetime: ok (one-shot: healthy, late completion, cancel inside the wait, parked and stale completions first, device gone, quarantine full; "
           "continuous: healthy, late cancellation, foreign completion, device gone, quarantine full; ring: never returned with a full quarantine)\n");
    return 0;
}
