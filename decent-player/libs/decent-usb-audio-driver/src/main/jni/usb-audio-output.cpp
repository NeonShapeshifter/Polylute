/**
 * @file usb-audio-output.cpp
 * @brief Direct USB audio output via Linux usbdevfs isochronous transfers.
 *
 * Pipeline: pre-allocated ring buffer of USB_AUDIO_NUM_URBS (= 80) URBs.
 * No malloc/free during streaming — completely avoids ARM MTE pointer tag
 * issues on Samsung devices.
 *
 * ISO URBs with ISO_ASAP complete in FIFO order, so we use a simple ring
 * with submit/reap indices. No need to identify which URB was reaped.
 */

#include "usb-audio-output.h"
#include "usb-pcm-packetizer.h"
#include "urb-quarantine.h"
#include "usb-glitch.h"
#include "usb-second-ring.h"
#include "seek-barrier.h"
#include "usb-cpu-watch.h"

#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <new>
#include <unistd.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <time.h>
#include <cstdarg>
#include <pthread.h>
#include <poll.h>
#include <dirent.h>
#include <sched.h>
#include <sys/resource.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#ifndef USBDEVFS_URB_ISO_ASAP
#define USBDEVFS_URB_ISO_ASAP 0x02
#endif

#define TAG "UsbAudioOutput"

/**
 * How long a barrier waits for the completions of the URBs it just discarded (AUD-112). A discarded
 * URB normally comes back within a few ms; the old 500-attempt loop turned any that did not into a
 * ~0.5 s stall on every track or rate change. Past this, the missing ones are quarantined.
 */
#define URB_DRAIN_COMPLETION_WAIT_MS 50
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Float → PCM conversion ──────────────────────────────────────────

// Float→integer conversion (with TPDF dither at 16 bits) lives in usb-pcm-convert.cpp.

// ── Ring buffer management ──────────────────────────────────────────

/**
 * Allocate all URB slots in the ring buffer.
 * Called once at stream creation. All memory stays alive until destroy.
 */
/** Slot index owning this URB pointer, or -1 if it is not one of ours. */
static int slotOfUrb(UsbAudioContext *ctx, const struct usbdevfs_urb *u) {
    if (u == nullptr) return -1;
    for (int i = 0; i < ctx->plan.ringUrbs; i++) {
        if (ctx->ring[i].urb == u) return i;
    }
    return -1;
}

/**
 * The single global reap observation point.
 *
 * Every successful REAPURB/REAPURBNDELAY on this fd must pass through here, immediately after the
 * ioctl succeeds and BEFORE the caller breaks, ignores, dispatches, resubmits or drops the
 * completion. It observes only — it never changes routing, ordering or timing, so the behaviour
 * under measurement (including the paths that currently swallow foreign completions) is preserved.
 *
 * Resolution is by exact identity: (urbAddress, slot, generation). Endpoint is checked too, and a
 * mismatch is recorded rather than accepted. A target resolves at whichever site returns it — it
 * does not matter which thread reaped it, because the question is whether the kernel gave the URB
 * back at all.
 */
static void observeReapedUrb(UsbAudioContext *ctx, ReapSite site, struct usbdevfs_urb *u) {
    if (ctx == nullptr || u == nullptr) return;
    std::lock_guard<std::mutex> lock(ctx->diagMutex);

    const uint64_t seq = ctx->reapSeq.fetch_add(1) + 1;
    const int slot = slotOfUrb(ctx, u);
    const bool isFeedback = (u == ctx->feedbackUrb);

    uint64_t generation = 0;
    int endpoint = 0;
    if (isFeedback) {
        generation = ctx->feedbackCookie.generation.load();
        endpoint = ctx->feedbackCookie.endpoint;
    } else if (slot >= 0) {
        generation = ctx->cookies[slot].generation.load();
        endpoint = ctx->cookies[slot].endpoint;
    }

    BarrierSnapshot &b = ctx->barrier;
    if (!b.valid) return;

    // Exact identity match against a frozen target.
    int match = -1;
    int sameSlotDifferentGen = -1;
    for (int i = 0; i < b.count; i++) {
        BarrierTarget &t = b.targets[i];
        if (t.urbAddress == (uint64_t)(uintptr_t)u && t.slot == slot &&
            t.generation == generation) {
            match = i;
            break;
        }
        if (t.slot == slot && t.urbAddress == (uint64_t)(uintptr_t)u &&
            t.generation != generation) {
            sameSlotDifferentGen = i;
        }
    }

    if (match >= 0) {
        BarrierTarget &t = b.targets[match];
        if (t.endpoint != endpoint) {
            b.wrongEndpoint++;
            LOGW("REAP_IDENTITY|seq=%llu site=%s slot=%d gen=%llu WRONG ENDPOINT "
                 "(expected %d got %d)", (unsigned long long)seq, reapSiteName(site), slot,
                 (unsigned long long)generation, t.endpoint, endpoint);
        }
        if (t.resolved) {
            b.duplicates++;
            LOGW("REAP_IDENTITY|seq=%llu site=%s slot=%d gen=%llu DUPLICATE target completion",
                 (unsigned long long)seq, reapSiteName(site), slot,
                 (unsigned long long)generation);
            return;
        }
        t.resolved = true;
        t.resolvedBy = (int)site;
        t.reapSequence = seq;
        t.completionStatus = u->status;
        b.resolvedCount++;
        b.perSiteUnique[(int)site]++;

        // The two paths that currently reap a foreign completion and then fail to account for it.
        if (site == ReapSite::BARRIER_FEEDBACK || site == ReapSite::READ_FEEDBACK) {
            LOGW("SWALLOWED_TARGET|seq=%llu site=%s slot=%d gen=%llu urb=%p status=%d "
                 "discardResult=%d discardErrno=%d",
                 (unsigned long long)seq, reapSiteName(site), slot,
                 (unsigned long long)generation, (void *)u, u->status,
                 t.discardResult, t.discardErrno);
            if (b.swallowedCount < 16) {
                b.swallowedSeq[b.swallowedCount] = seq;
                b.swallowedSite[b.swallowedCount] = (int)site;
                b.swallowedSlot[b.swallowedCount] = slot;
                b.swallowedGen[b.swallowedCount] = generation;
                b.swallowedCount++;
            }
        }
        return;
    }

    if (sameSlotDifferentGen >= 0) {
        b.staleGeneration++;
        LOGW("REAP_IDENTITY|seq=%llu site=%s slot=%d gen=%llu STALE GENERATION "
             "(target gen=%llu)", (unsigned long long)seq, reapSiteName(site), slot,
             (unsigned long long)generation,
             (unsigned long long)b.targets[sameSlotDifferentGen].generation);
        return;
    }
    b.nonTargetCompletions++;
}

/**
 * The bus speed usbfs reports for the device behind [fd] (`USBDEVFS_GET_SPEED`, an `enum usb_device_speed`), or unknown when it
 * cannot say. The plan needs it to tell a device whose timing the output drives (125 us microframes) from one it does not yet.
 */
static int readUsbSpeed(int fd) {
    const int speed = ioctl(fd, USBDEVFS_GET_SPEED, 0);
    return speed >= 0 ? speed : USB_TRANSPORT_SPEED_UNKNOWN;
}

static size_t ringUrbStructSize(const UsbAudioContext *ctx) {
    return sizeof(struct usbdevfs_urb) + (size_t)ctx->plan.packetsPerUrb * sizeof(struct usbdevfs_iso_packet_desc);
}

static int64_t monotonicMs() {
    struct timespec ts = {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static std::mutex g_dataAltMutex;
static int g_dataAltFd = -1;
static int64_t g_dataAltSetMs = -1;

void usbNoteDataAltSet(int fd) {
    std::lock_guard<std::mutex> lock(g_dataAltMutex);
    g_dataAltFd = fd;
    g_dataAltSetMs = monotonicMs();
}

static int64_t dataAltSetMs(int fd) {
    std::lock_guard<std::mutex> lock(g_dataAltMutex);
    return fd == g_dataAltFd ? g_dataAltSetMs : -1;
}

static int64_t monotonicUs() {
    struct timespec ts = {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

// ── Waiting for a completion (AUD-145) ──────────────────────────────

/**
 * Measured on the HONOR (120 s of steady ExoPlayer-path playback, screen off): the process spent 58 s of
 * CPU, 32.5 s of it in the USB writer thread. Its ring is full almost all of the time, so it spent its
 * life in reapOldestUrb's `REAPURBNDELAY` + `usleep(125)` loop: ~8 000 wake-ups a second.
 *
 * The wait is now a poll(2) on the usbfs fd: usbfs reports POLLOUT while a completed URB is waiting to
 * be reaped (usbdev_poll: `!list_empty(&ps->async_completed)`), which is how libusb waits. The reap
 * itself is unchanged, still `REAPURBNDELAY`, still one URB per call, and every observer, the feedback
 * handling and the quarantine stay in their callers. Only what the thread does between two reap
 * attempts changes: it sleeps until the kernel has something instead of until 125 µs have passed.
 *
 * `g_pollWait` is the kill switch (SET_USB_WAIT_MODE in the debug bridge): 0 restores the 125 µs sleep.
 * The counters are what the capture compares before and after.
 */
static std::atomic<int> g_pollWait{1};

/**
 * One poll never blocks longer than this, whatever the caller's timeout. It bounds the cost of a wake-up
 * that never comes (a kernel that does not signal ISO completions): the loop reaps everything already
 * completed before it waits again, so such a miss delays a refill by at most this much, against a ring
 * that holds ~80 ms. It never starves the ring.
 */
#define USB_WAIT_POLL_SLICE_MS 2

static std::atomic<uint64_t> g_waitCalls{0};
static std::atomic<uint64_t> g_waitedUs{0};
static std::atomic<uint64_t> g_waitTimeouts{0};
static std::atomic<uint64_t> g_waitEmpty{0};

void usbSetPollWait(bool enabled) { g_pollWait.store(enabled ? 1 : 0, std::memory_order_relaxed); }

void usbWaitStats(uint64_t out[5]) {
    out[0] = (uint64_t)g_pollWait.load(std::memory_order_relaxed);
    out[1] = g_waitCalls.load(std::memory_order_relaxed);
    out[2] = g_waitedUs.load(std::memory_order_relaxed);
    out[3] = g_waitTimeouts.load(std::memory_order_relaxed);
    out[4] = g_waitEmpty.load(std::memory_order_relaxed);
}

/**
 * One wait between two reap attempts, for at most [maxWaitMs] (>= 1). Returns when the kernel reports a
 * completed URB, when the time is up, or (device gone: POLLHUP/POLLERR, which the next reap reports as
 * an error) after a short sleep so a dead fd cannot turn the caller into a spin. True only when poll
 * reported a completion (so a following empty reap is counted as such).
 */
static bool waitForCompletion(UsbAudioContext *ctx, int maxWaitMs) {
    const int64_t startUs = monotonicUs();
    bool signalled = false;
    g_waitCalls.fetch_add(1, std::memory_order_relaxed);
    if (g_pollWait.load(std::memory_order_relaxed) != 0) {
        struct pollfd pfd = {};
        pfd.fd = ctx->fd;
        pfd.events = POLLOUT;
        const int sliceMs = maxWaitMs < 1 ? 1 : (maxWaitMs > USB_WAIT_POLL_SLICE_MS ? USB_WAIT_POLL_SLICE_MS : maxWaitMs);
        const int rc = poll(&pfd, 1, sliceMs);
        if (rc == 0) {
            g_waitTimeouts.fetch_add(1, std::memory_order_relaxed);
        } else if (rc > 0 && (pfd.revents & POLLOUT)) {
            signalled = true;
        } else if (rc > 0) {
            usleep(125);
        }
    } else {
        usleep(125);  // 125µs = 1 USB microframe
    }
    g_waitedUs.fetch_add((uint64_t)(monotonicUs() - startUs), std::memory_order_relaxed);
    return signalled;
}

// ── USB_GLITCH (AUD-124) ───────────────────────────────────────────

static const char *const kGlitchNames[USB_GLITCH_KIND_COUNT] = {
    "ISO_PACKET", "RING_EMPTY", "REAP_SLOW", "FEEDBACK_DEVIATION", "FEEDBACK_REJECTED", "READ_SLOW",
    "FEEDBACK_STUCK", "FEEDBACK_UNSTUCK", "OUTPUT_GAP", "FEEDBACK_INVALID", "DAC_LOCKED", "DAC_RECOVERY", "OUTPUT_GAP_CPU",
};
static GlitchCounter g_glitchCounters[USB_GLITCH_KIND_COUNT];
static pthread_mutex_t g_glitchLock = PTHREAD_MUTEX_INITIALIZER;

/**
 * One line per kind per second at most, tag USB_GLITCH, with the wall-clock time (to match what the
 * listener heard), the monotonic time, the running total and how many were held back. Counting is
 * a mutex and an increment; formatting only happens for the lines that are logged.
 */
void reportUsbGlitch(enum UsbGlitchKind kind, const char *fmt, ...) {
    if ((int)kind < 0 || kind >= USB_GLITCH_KIND_COUNT) return;
    const int64_t nowMs = monotonicMs();
    int64_t suppressed = 0;
    pthread_mutex_lock(&g_glitchLock);
    const bool log = glitchCount(&g_glitchCounters[kind], nowMs, usbGlitchLogIntervalMs(kind), &suppressed);
    const int64_t total = g_glitchCounters[kind].total;
    pthread_mutex_unlock(&g_glitchLock);
    if (!log) return;
    char detail[512];   // B47-A1: OUTPUT_GAP_CPU names threads and CPUs
    va_list args;
    va_start(args, fmt);
    vsnprintf(detail, sizeof(detail), fmt, args);
    va_end(args);
    struct timespec wall = {};
    clock_gettime(CLOCK_REALTIME, &wall);
    struct tm local = {};
    localtime_r(&wall.tv_sec, &local);
    __android_log_print(ANDROID_LOG_WARN, "USB_GLITCH",
                        "%s at %02d:%02d:%02d.%03ld mono=%lldms total=%lld heldBack=%lld %s",
                        kGlitchNames[kind], local.tm_hour, local.tm_min, local.tm_sec,
                        wall.tv_nsec / 1000000L, (long long)nowMs, (long long)total,
                        (long long)suppressed, detail);
}

/**
 * B23-P2: the one line for a submit gap that used up the ring's lead (the DAC was fed empty microframes), with where
 * the writer spent it. Called only when a gap was found (rare), never per URB. Values in whole ms:
 *  gap     since the previous submit
 *  lead    what the previous submit left queued ahead of it (about 80 with a full ring)
 *  dry     the model's estimate of how long nothing was queued: gap - lead (not a measurement of what the DAC played)
 *  reap    of the gap, wall-clock time inside the reap call (blocked in poll, or preempted there: the line cannot tell)
 *  between of the gap, between two native write calls: the writer waiting for its producer, or not running
 *  native  of the gap, the rest of the native call (the submit ioctl, a copy, a preemption)
 * `where` names the largest of the three; it says where the time elapsed, not why: `reap` is the wall time around the reap call,
 * scheduling included, so it does not by itself show a USB completion stall.
 * The tail says why, from the writer thread's own scheduler counters (B24-Q3; usb-glitch.h):
 *  runq    time the writer sat runnable waiting for a CPU (a scheduling stall if it is most of the gap)
 *  cpu     time it ran
 *  over    the window those two cover: from a baseline up to 10 ms before the previous submit to this submit
 *  gap - runq - cpu is how long it was asleep (in poll waiting for a completion, or waiting for its producer).
 * `runq=n/a` when the counters could not be read.
 */
static void reportOutputGap(const UsbAudioContext *ctx, const UsbOutputGap &gap, const UsbSchedDelta &sched) {
    const auto ms = [](int64_t us) { return (long long)((us + 500) / 1000); };
    char tail[96];
    if (sched.valid) {
        snprintf(tail, sizeof(tail), " runq=%lld ms cpu=%lld ms over=%lld ms", ms(sched.runqUs), ms(sched.cpuUs), ms(sched.overUs));
    } else {
        snprintf(tail, sizeof(tail), " runq=n/a");
    }
    reportUsbGlitch(USB_GLITCH_OUTPUT_GAP,
                    "gap=%lld ms lead=%lld ms dry=%lld ms reap=%lld ms between=%lld ms native=%lld ms where=%s rate=%d inFlight=%d%s",
                    ms(gap.gapUs), ms(gap.leadUs), ms(gap.dryUs), ms(gap.reapUs), ms(gap.betweenCallsUs),
                    ms(gap.otherUs), usbOutputGapWhereName(usbOutputGapWhere(&gap)), (int)ctx->sampleRate,
                    ctx->urbsInFlight, tail);
}

// ── Who had the CPU (B47-A1; usb-cpu-watch.h) ───────────────────────────────────────────────────────

/** Reads a small text file whole. False when it cannot be opened or read (a denial is an answer: the line says so). */
static bool readSmallFile(const char *path, char *buf, size_t capacity) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    const ssize_t n = read(fd, buf, capacity - 1);
    close(fd);
    if (n <= 0) return false;
    buf[n] = '\0';
    return true;
}

/** A reading of what is readable about the CPUs and the process's threads. Writer thread only. */
struct CpuSnapshot {
    int64_t atMs = 0;
    int cpuCount = 0;                                 // 0: /proc/stat is not readable here
    UsbCpuTimes cpus[USB_CPU_WATCH_MAX_CPUS];
    int threadCount = 0;
    UsbThreadTicks threads[USB_CPU_WATCH_MAX_THREADS];
};

static CpuSnapshot g_cpuBaseline;
#define USB_CPU_BASELINE_EVERY_MS 2000

static void takeCpuSnapshot(CpuSnapshot *snap, int64_t nowMs) {
    snap->atMs = nowMs;
    snap->cpuCount = 0;
    snap->threadCount = 0;
    static char statText[16384];
    if (readSmallFile("/proc/stat", statText, sizeof(statText))) snap->cpuCount = usbParseProcStat(statText, snap->cpus, USB_CPU_WATCH_MAX_CPUS);
    DIR *dir = opendir("/proc/self/task");
    if (dir == nullptr) return;
    for (struct dirent *entry = readdir(dir); entry != nullptr && snap->threadCount < USB_CPU_WATCH_MAX_THREADS; entry = readdir(dir)) {
        const int tid = atoi(entry->d_name);
        if (tid <= 0) continue;
        char path[64];
        char line[512];
        snprintf(path, sizeof(path), "/proc/self/task/%d/stat", tid);
        UsbThreadStat stat;
        if (!readSmallFile(path, line, sizeof(line)) || !usbParseThreadStat(line, &stat)) continue;
        UsbThreadTicks &t = snap->threads[snap->threadCount++];
        t.tid = tid;
        memcpy(t.comm, stat.comm, USB_CPU_WATCH_COMM);
        t.ticks = stat.utime + stat.stime;
    }
    closedir(dir);
}

/** About 2 ms of syscalls every USB_CPU_BASELINE_EVERY_MS, on the writer thread between two URBs; the ring holds 128 ms. */
static void refreshCpuBaselineIfDue(int64_t nowMs) {
    if (g_cpuBaseline.atMs == 0 || nowMs - g_cpuBaseline.atMs >= USB_CPU_BASELINE_EVERY_MS) takeCpuSnapshot(&g_cpuBaseline, nowMs);
}

static const char *schedulerName(int policy) {
    // The kernel's numbers (sched.h), only to NAME what `sched_getscheduler` answered: this reads the scheduling class, it never sets one.
    switch (policy) {
        case 0: return "OTHER";
        case 1: return "FIFO";
        case 2: return "RR";
        case 3: return "BATCH";
        case 5: return "IDLE";
        default: return "?";
    }
}

/** What the calling thread's scheduling is and what an app can read about it: one line, at the first write of a writer thread (and for the extra line below). */
static void describeThreadScheduling(char *out, size_t capacity) {
    const int tid = (int)syscall(SYS_gettid);
    cpu_set_t set;
    CPU_ZERO(&set);
    char allowed[48] = "?";
    int allowedCount = 0;
    if (sched_getaffinity(0, sizeof(set), &set) == 0) {
        uint64_t mask = 0;
        for (int i = 0; i < 64 && i < CPU_SETSIZE; i++) {
            if (CPU_ISSET(i, &set)) { mask |= (uint64_t)1 << i; allowedCount++; }
        }
        usbFormatCpuList(mask, allowed, sizeof(allowed));
    }
    char text[512];
    UsbThreadStat stat;
    stat.valid = false;
    if (readSmallFile("/proc/thread-self/stat", text, sizeof(text))) usbParseThreadStat(text, &stat);
    char cpuset[64] = "n/a";
    if (readSmallFile("/proc/self/cpuset", text, sizeof(text))) {
        text[strcspn(text, "\n")] = '\0';
        snprintf(cpuset, sizeof(cpuset), "%.60s", text);
    }
    char cgroup[200] = "n/a";   // every line of it (cgroup v1 lists cpuset, cpu, schedtune...): the cpuset one is the one that says which CPUs
    if (readSmallFile("/proc/thread-self/cgroup", text, sizeof(text))) {
        for (char *c = text; *c != '\0'; c++) {
            if (*c == '\n') *c = ';';
        }
        snprintf(cgroup, sizeof(cgroup), "%.190s", text);
    }
    char uclamp[48] = "n/a";   // /proc/thread-self/sched exists only on kernels with CONFIG_SCHED_DEBUG
    if (readSmallFile("/proc/thread-self/sched", text, sizeof(text))) {
        const char *min = strstr(text, "uclamp.min");
        const char *max = strstr(text, "uclamp.max");
        snprintf(uclamp, sizeof(uclamp), "%s%s", min ? "min " : "", max ? "max" : (min ? "" : "not-listed"));
    }
    snprintf(out, capacity, "tid=%d policy=%s nice=%d lastCpu=%d allowed=%s (%d CPUs) cpuset=%s cgroup=%s uclamp=%s", tid, schedulerName(sched_getscheduler(0)),
             getpriority(PRIO_PROCESS, (id_t)tid), stat.valid ? stat.processor : -1, allowed, allowedCount, cpuset, cgroup, uclamp);
}

/**
 * B47-A1: the extra line of an OUTPUT_GAP whose writer was in the run queue for [USB_CPU_WATCH_MIN_RUNQ_US] or more (2026-10-29 17:32:33: runq=82 ms, cpu=0 ms). It names
 * where the writer ran and where it may, which of this process's threads used CPU in the window since the last baseline (<= 2 s old, named in the line), and each CPU's busy
 * share where /proc/stat can be read. Threads of other processes cannot be seen from here: a system trace is the tool for those (usb-cpu-watch.h).
 */
void usbReportOutputGapCpu(const UsbAudioContext *ctx, int64_t runqUs, int64_t cpuUs) {
    const int64_t nowMs = monotonicMs();
    CpuSnapshot now;
    takeCpuSnapshot(&now, nowMs);
    char where[560];
    describeThreadScheduling(where, sizeof(where));
    char busy[200];
    busy[0] = '\0';
    if (now.cpuCount > 0 && g_cpuBaseline.cpuCount > 0) usbFormatCpuBusy(g_cpuBaseline.cpus, g_cpuBaseline.cpuCount, now.cpus, now.cpuCount, busy, sizeof(busy));
    char threads[200];
    threads[0] = '\0';
    if (g_cpuBaseline.threadCount > 0) usbFormatTopThreads(g_cpuBaseline.threads, g_cpuBaseline.threadCount, now.threads, now.threadCount, 5, threads, sizeof(threads));
    reportUsbGlitch(USB_GLITCH_OUTPUT_GAP_CPU, "runq=%lld ms cpu=%lld ms window=%lld ms %s | cpuBusy[%s] | ownThreads[%s] | rate=%d", (long long)(runqUs / 1000),
                    (long long)(cpuUs / 1000), (long long)(g_cpuBaseline.atMs > 0 ? nowMs - g_cpuBaseline.atMs : -1), where,
                    now.cpuCount > 0 ? (busy[0] ? busy : "same-reading") : "denied", threads[0] ? threads : "none", ctx->sampleRate);
}

/**
 * B24-Q3: the calling thread's schedstat counters, through a descriptor opened once by that thread (`/proc/thread-self/schedstat`).
 * One `pread` of ~30 bytes: it is called when a gap is being reported and at most once per USB_SCHED_BASELINE_EVERY_US otherwise,
 * never per URB. A thread that is not the one the descriptor was opened by (another stream, another writer) opens its own. If the
 * kernel does not offer the file the answer is "no reading" for that thread and nothing is retried.
 */
static bool readWriterSchedstat(UsbAudioContext *ctx, UsbSchedSample *out) {
    const int tid = (int)syscall(SYS_gettid);
    out->valid = false;
    // B48-fix1: the writer was described, and its descriptor opened, before its first submit (prepareWriterThread); a thread that was not is not read here, because describing it
    // is a handful of /proc reads that take milliseconds, and this runs between two URBs.
    if (ctx->schedstatTid != tid || ctx->schedstatFd < 0) return false;
    char buf[96];
    const ssize_t n = pread(ctx->schedstatFd, buf, sizeof(buf) - 1, 0);
    if (n <= 0) return false;
    buf[n] = '\0';
    return usbParseSchedstat(buf, out);
}

/**
 * B47-A1, B48-fix1: a writer thread touches this stream for the first time: what its scheduling is, once (`Writer thread: ...`), and its schedstat descriptor, opened by that
 * thread. Called at the top of every submit, where it costs one comparison, **before the first URB** of the call: the description reads six small files in /proc and takes
 * milliseconds, and B47 did it after the first URB, so every stream's second URB came 3-11 ms late (2026-10-30 00:37:13 `OUTPUT_GAP dry=10 ms inFlight=1`, the same right
 * after `Writer thread:` at 00:38:54, 00:39:45 and 00:40:33). The ring is empty at a stream's first write, so nothing can run dry here.
 */
static void prepareWriterThread(UsbAudioContext *ctx) {
    const int tid = (int)syscall(SYS_gettid);
    if (ctx->schedstatTid == tid) return;
    char scheduling[640];
    describeThreadScheduling(scheduling, sizeof(scheduling));
    LOGI("Writer thread: %s", scheduling);
    if (ctx->schedstatFd >= 0) close(ctx->schedstatFd);
    ctx->schedstatFd = open("/proc/thread-self/schedstat", O_RDONLY | O_CLOEXEC);
    ctx->schedstatTid = tid;
    ctx->schedBaseline = UsbSchedBaseline{};  // another thread's counters are not a baseline for this one
}

/**
 * B48-fix1: the ring holds at least the plan's low-ring depth (half of it: 64 ms at the default ring). Bookkeeping that reads files (the schedstat and CPU baselines) waits for it:
 * 128 ms of audio is what covers its few milliseconds, and between the first URBs of a start there is none. O(1): no URB is summed.
 */
static bool ringHoldsItsLead(const UsbAudioContext *ctx) {
    return ctx->urbsInFlight >= ctx->plan.lowRingUrbs;
}

/**
 * B23-P2: a data URB was accepted by the kernel at this moment. Clock reads and counters only: it reads
 * `ctx`, changes nothing that is sent, allocates nothing. B24-Q3 adds one `pread` of the writer's own schedstat (~30 bytes) at most
 * every USB_SCHED_BASELINE_EVERY_US (10 ms, so ~0.1 % of the URBs), and one more when a gap is reported; the rest of the URBs pay
 * one comparison.
 *
 * An announced idle restarts the model, as it silences RING_EMPTY (`lastSubmitMs == 0`: a start, a pause, a seek that
 * left nothing audible). A natural end or a fade-out (`playOutBeforeDrain`) leaves the ring dry on purpose until the
 * next write: only the first URB after it is exempt, not the whole block that follows.
 *
 * B46-B1: the restart is from what the ring STILL HOLDS (`ringUnplayedWireUs`), not from nothing. After a pause's fade-out the ring
 * holds the fade and the audio before it, and the silence feed that follows keeps it at the plan's lead; restarted from zero, the
 * model took the first stall of 2 ms or more for a dry bus. The simulation's fake DAC, which knows when it really ran dry, never did
 * for a stall under that lead (tools/natural-join-test.cpp, testSkipSilenceFeed).
 */
static int64_t ringUnplayedWireUs(UsbAudioContext *ctx);   // defined after reapReadyUrbs, which it needs

static void observeOutputGap(UsbAudioContext *ctx, int numPackets) {
    bool announcedIdle = ctx->lastSubmitMs <= 0;
    if (ctx->playOutBeforeDrain) {
        if (!ctx->outputGapPlayOutExempted) {
            announcedIdle = true;
            ctx->outputGapPlayOutExempted = true;
        }
    } else {
        ctx->outputGapPlayOutExempted = false;
    }
    const int64_t nowUs = monotonicUs();
    // An announced idle is rare (a start, a pause, the first URB after a fade-out): only then the ring is asked what it still holds.
    const int64_t queuedLeadUs = announcedIdle ? ringUnplayedWireUs(ctx) : 0;
    UsbOutputGap gap;
    if (usbOutputGapSubmit(&ctx->outputGap, announcedIdle, nowUs, (int64_t)numPackets * USB_AUDIO_PACKET_US, queuedLeadUs, &gap)) {
        // B24-Q3: the end of the window is read only here, against the baseline held from before the gap.
        UsbSchedSample sched = {false, 0, 0};
        const bool read = readWriterSchedstat(ctx, &sched);
        const UsbSchedDelta delta = read ? usbSchedDelta(&ctx->schedBaseline, &sched, nowUs) : UsbSchedDelta{false, 0, 0, 0};
        reportOutputGap(ctx, gap, delta);
        if (usbGapWantsCpuLine(&delta)) usbReportOutputGapCpu(ctx, delta.runqUs, delta.cpuUs);   // B47-A1
        if (read) usbSchedBaselineSet(&ctx->schedBaseline, &sched, nowUs);
    } else if (ringHoldsItsLead(ctx) && usbSchedBaselineDue(&ctx->schedBaseline, nowUs)) {
        // B48-fix1: only with a ring that holds its lead. After a start (or a recovery's restart) the baseline is "due" at the first URB, and taking it then (the CPU snapshot
        // reads /proc/stat and every thread's stat: 3-4 ms) was the 3 ms dry stretch between the first and the second URB of every start.
        refreshCpuBaselineIfDue(nowUs / 1000);   // B47-A1: every 2 s, not every 10 ms
        UsbSchedSample sched = {false, 0, 0};
        if (readWriterSchedstat(ctx, &sched)) usbSchedBaselineSet(&ctx->schedBaseline, &sched, nowUs);
    }
}

// B27-A5: the stream's one-second summaries (usb-second-ring.h). One ring for the process, like the feedback stats: the stream
// that is playing is the one that records, and a dump after it ended still has its last seconds.
static UsbSecondRing g_secondRing;

/** A data URB that completed with an error or short is a microframe the DAC did not get whole. */
static void checkIsoCompletion(UsbAudioContext *ctx, const struct usbdevfs_urb *c) {
    if (c == nullptr || c == ctx->feedbackUrb) return;
    if (c->status != 0) {
        reportUsbGlitch(USB_GLITCH_ISO_PACKET, "urb status=%d errors=%d", c->status, c->error_count);
        usbSecondRingIso(&g_secondRing, monotonicMs(), c->number_of_packets, c->number_of_packets, 0);
        return;
    }
    // Every packet is counted for the summaries (a few integer compares on the completion that is being looked at anyway); the
    // line in the log is still the first bad one, as it was.
    int firstBad = -1, errors = 0, shorts = 0;
    for (int i = 0; i < c->number_of_packets; i++) {
        const struct usbdevfs_iso_packet_desc &p = c->iso_frame_desc[i];
        if (p.status != 0) errors++;
        if (p.actual_length != p.length) shorts++;
        if (firstBad < 0 && (p.status != 0 || p.actual_length != p.length)) firstBad = i;
    }
    usbSecondRingIso(&g_secondRing, monotonicMs(), c->number_of_packets, errors, shorts);
    if (firstBad >= 0) {
        const struct usbdevfs_iso_packet_desc &p = c->iso_frame_desc[firstBad];
        reportUsbGlitch(USB_GLITCH_ISO_PACKET, "packet %d/%d status=%d actual=%u length=%u",
                        firstBad, c->number_of_packets, p.status, p.actual_length, p.length);
    }
}

static bool allocRing(UsbAudioContext *ctx) {
    size_t urbStructSize = ringUrbStructSize(ctx);
    for (int i = 0; i < ctx->plan.ringUrbs; i++) {
        ctx->ring[i].urb = (struct usbdevfs_urb *)calloc(1, urbStructSize);
        // Sized from the endpoint (audit 2): a plan can reach 8 x maxPacket, 6 KiB at 768 kHz stereo 32-bit,
        // and the fixed 4 KiB slot was overrun on every URB there.
        ctx->ring[i].buffer = (uint8_t *)malloc((size_t)ctx->plan.urbSlotBytes);
        ctx->ring[i].dataLength = 0;
        if (!ctx->ring[i].urb || !ctx->ring[i].buffer) {
            LOGE("allocRing: OOM at slot %d", i);
            // Free what we allocated
            for (int j = 0; j <= i; j++) {
                free(ctx->ring[j].urb);
                free(ctx->ring[j].buffer);
            }
            return false;
        }
    }
    ctx->ringAllocated = true;
    return true;
}

/**
 * Free all URB slots. Called once at stream destruction.
 */
static void freeRing(UsbAudioContext *ctx) {
    if (!ctx->ringAllocated) return;
    for (int i = 0; i < ctx->plan.ringUrbs; i++) {
        free(ctx->ring[i].urb);
        free(ctx->ring[i].buffer);
        ctx->ring[i].urb = nullptr;
        ctx->ring[i].buffer = nullptr;
    }
    ctx->ringAllocated = false;
}

// ── Continuous feedback ─────────────────────────────────────────────

/**
 * B27-A2 (review R-02): the continuous feedback URB and its four data bytes are one heap block, like the one-shot read's
 * (B14-1). usbfs writes a completion's status, length and data into the caller's memory when the URB is *reaped*, so the
 * data buffer has to be exactly as alive as the URB. It used to be `ctx->feedbackBuffer`, inside the context: a URB the
 * kernel still held after a teardown was freed with the context, and its late completion wrote into freed memory.
 */
static size_t feedbackUrbBytes() {
    return sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
}

static struct usbdevfs_urb *newFeedbackUrb() {
    return (struct usbdevfs_urb *)calloc(1, feedbackUrbBytes() + USB_FEEDBACK_BYTES);
}

/** The four data bytes behind [ctx]'s current continuous feedback URB. */
static uint8_t *feedbackData(const UsbAudioContext *ctx) {
    return reinterpret_cast<uint8_t *>(ctx->feedbackUrb) + feedbackUrbBytes();
}

/**
 * Allocate and initialize the feedback URB (called once at creation).
 */
static bool allocFeedbackUrb(UsbAudioContext *ctx) {
    ctx->feedbackUrb = newFeedbackUrb();
    if (!ctx->feedbackUrb) return false;
    ctx->feedbackInFlight = false;
    return true;
}

/**
 * Submit the feedback URB to read the DAC's async feedback endpoint.
 * The endpoint returns 4 bytes (Q16.16 format) reporting the DAC's
 * actual frames-per-microframe.
 */
static bool submitFeedbackUrb(UsbAudioContext *ctx) {
    if (ctx->endpointFeedback <= 0 || !ctx->feedbackUrb || ctx->feedbackInFlight)
        return false;

    struct usbdevfs_urb *u = ctx->feedbackUrb;
    memset(u, 0, feedbackUrbBytes() + USB_FEEDBACK_BYTES);
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = (unsigned char)ctx->endpointFeedback;
    u->buffer = feedbackData(ctx);
    u->buffer_length = USB_FEEDBACK_BYTES;
    u->number_of_packets = 1;
    u->iso_frame_desc[0].length = USB_FEEDBACK_BYTES;

    u->usercontext = &ctx->feedbackCookie;
    ctx->feedbackCookie.slot = -1;
    ctx->feedbackCookie.endpoint = ctx->endpointFeedback;
    ctx->feedbackCookie.generation.fetch_add(1);

    if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, u) < 0) {
        LOGW("submitFeedbackUrb: failed errno=%d (%s)", errno, strerror(errno));
        return false;
    }
    ctx->feedbackInFlight = true;
    return true;
}

// ── Feedback policy and the frozen-feedback watch (B10, AUD-124 "shh") ──

/**
 * What the output does with the DAC's feedback. `auto` is the default (B12a-2, in debug and in release): it
 * follows the DAC except while its feedback is stuck or implausible, when it sends the healthy average (nominal
 * if there is none). Two decisive device tests (2026-10-07) showed the "shh" goes away within seconds of not
 * obeying a stuck feedback. SET_FEEDBACK_POLICY in the debug bridge picks another for the life of the process
 * (`follow` to diagnose), never persisted: a new process starts in `auto`. Read once per write, so a change
 * lands within one buffer.
 */
static std::atomic<int> g_feedbackPolicy{USB_FEEDBACK_AUTO};

// What the capture and the debug bridge read from another thread. All relaxed: they are readings.
static std::atomic<int64_t> g_feedbackCount{0};     // every feedback completion, applied or not
static std::atomic<double> g_fbLastFpmf{0.0};       // the last applied reading
static std::atomic<double> g_fbSendingFpmf{0.0};    // what the packetizer was last given
static std::atomic<int32_t> g_fbRate{0};            // the stream's nominal rate
static std::atomic<int> g_fbStuckNow{0};
static std::atomic<int64_t> g_fbStuckSinceMs{0};    // monotonic ms the open episode's value first appeared
static std::atomic<uint64_t> g_fbEpisodes{0};
static std::atomic<uint64_t> g_fbStuckReadings{0};  // readings seen while stuck
static std::atomic<uint64_t> g_fbStuckDoneMs{0};    // total length of the episodes that ended
static std::atomic<uint64_t> g_fbLongestMs{0};
static std::atomic<double> g_fbStuckFpmf{0.0};      // the last episode's value
static std::atomic<double> g_fbStuckPpm{0.0};
// B12a-2: readings the output did not obey. Out-of-window ones (beyond +-5 %, under any policy) and, under
// `auto`, implausible ones (absurd, or beyond the sustained threshold for seconds).
static std::atomic<uint64_t> g_fbRejectedReadings{0};
static std::atomic<uint64_t> g_fbImplausibleStreaks{0};   // streaks of implausible readings (what is logged)
static std::atomic<double> g_fbRejectedFpmf{0.0};         // the last one's value

void usbSetFeedbackPolicy(int policy) {
    if (policy < 0 || policy >= USB_FEEDBACK_POLICY_COUNT) return;
    g_feedbackPolicy.store(policy, std::memory_order_relaxed);
}

int usbFeedbackPolicy() { return g_feedbackPolicy.load(std::memory_order_relaxed); }

static int64_t fpmfMicro(double fpmf) { return (int64_t)llround(fpmf * 1e6); }

void usbFeedbackStats(int64_t out[USB_FEEDBACK_STATS_COUNT]) {
    const bool stuck = g_fbStuckNow.load(std::memory_order_relaxed) != 0;
    const int64_t since = g_fbStuckSinceMs.load(std::memory_order_relaxed);
    const int64_t openMs = (stuck && since > 0) ? monotonicMs() - since : 0;
    const int64_t longest = (int64_t)g_fbLongestMs.load(std::memory_order_relaxed);
    out[0] = usbFeedbackPolicy();
    out[1] = stuck ? 1 : 0;
    out[2] = (int64_t)g_fbEpisodes.load(std::memory_order_relaxed);
    out[3] = (int64_t)g_fbStuckReadings.load(std::memory_order_relaxed);
    out[4] = (int64_t)g_fbStuckDoneMs.load(std::memory_order_relaxed) + openMs;
    out[5] = longest > openMs ? longest : openMs;
    out[6] = fpmfMicro(g_fbStuckFpmf.load(std::memory_order_relaxed));
    out[7] = (int64_t)llround(g_fbStuckPpm.load(std::memory_order_relaxed));
    out[8] = g_feedbackCount.load(std::memory_order_relaxed);
    out[9] = fpmfMicro(g_fbLastFpmf.load(std::memory_order_relaxed));
    out[10] = g_fbRate.load(std::memory_order_relaxed);
    out[11] = fpmfMicro(g_fbSendingFpmf.load(std::memory_order_relaxed));
    out[12] = (int64_t)g_fbRejectedReadings.load(std::memory_order_relaxed);
    out[13] = (int64_t)g_fbImplausibleStreaks.load(std::memory_order_relaxed);
    out[14] = fpmfMicro(g_fbRejectedFpmf.load(std::memory_order_relaxed));
}

// ── Stream facts and the dump of the one-second summaries (B27-A5) ──────────────────────────────

/** What the stream that is (or was last) playing was opened as: written at create, read when somebody asks for the summaries. */
struct StreamFacts {
    bool valid;
    int64_t seq;
    int64_t openedMs;
    int rate, channels, slotBits, endpointOut, endpointFeedback, maxPacket;
    // B46-A4: the transport plan the stream runs with and the endpoint facts it was made from.
    int busSpeed, bInterval, feedbackBytes, intervalUs, ringUrbs, packetsPerUrb, assumptions;
};
static StreamFacts g_streamFacts = {};
static pthread_mutex_t g_streamFactsLock = PTHREAD_MUTEX_INITIALIZER;

static void noteStreamFacts(UsbAudioContext *ctx) {
    pthread_mutex_lock(&g_streamFactsLock);
    g_streamFacts.seq++;
    g_streamFacts.valid = true;
    g_streamFacts.openedMs = monotonicMs();
    g_streamFacts.rate = ctx->sampleRate;
    g_streamFacts.channels = ctx->channelCount;
    g_streamFacts.slotBits = ctx->bitDepth;
    g_streamFacts.endpointOut = ctx->endpointOut;
    g_streamFacts.endpointFeedback = ctx->endpointFeedback;
    g_streamFacts.maxPacket = ctx->maxPacketSize;
    g_streamFacts.busSpeed = ctx->planEndpoint.speed;
    g_streamFacts.bInterval = ctx->planEndpoint.bInterval;
    g_streamFacts.feedbackBytes = ctx->planEndpoint.feedbackBytes;
    g_streamFacts.intervalUs = ctx->plan.serviceIntervalUs;
    g_streamFacts.ringUrbs = ctx->plan.ringUrbs;
    g_streamFacts.packetsPerUrb = ctx->plan.packetsPerUrb;
    g_streamFacts.assumptions = ctx->plan.assumptions;
    ctx->streamSeq = g_streamFacts.seq;  // B41-fix1: the policy events name the stream they happened in
    pthread_mutex_unlock(&g_streamFactsLock);
}

static const char *feedbackPolicyName(int policy) {
    switch (policy) {
        case USB_FEEDBACK_FOLLOW: return "follow";
        case USB_FEEDBACK_NOMINAL: return "nominal";
        case USB_FEEDBACK_CLAMP: return "clamp";
        case USB_FEEDBACK_AUTO: return "auto";
        case USB_FEEDBACK_AUTO_EARLY: return "auto-early";
        default: return "?";
    }
}

size_t usbSecondSummaries(int64_t windowMs, char *out, size_t capacity) {
    if (out == nullptr || capacity == 0) return 0;
    out[0] = '\0';
    if (windowMs <= 0) windowMs = 30000;
    if (windowMs > (int64_t)USB_SECOND_RING_CAPACITY * 1000) windowMs = (int64_t)USB_SECOND_RING_CAPACITY * 1000;
    const int64_t nowMs = monotonicMs();
    UsbSecondSample samples[USB_SECOND_RING_CAPACITY];  // ~25 KB of stack, on demand
    const int n = usbSecondRingLast(&g_secondRing, nowMs, windowMs, samples, USB_SECOND_RING_CAPACITY);
    UsbPolicyEvent events[USB_POLICY_EVENT_CAPACITY];
    const int eventCount = usbSecondRingEvents(&g_secondRing, nowMs, windowMs, events, USB_POLICY_EVENT_CAPACITY);
    StreamFacts f;
    pthread_mutex_lock(&g_streamFactsLock);
    f = g_streamFacts;
    pthread_mutex_unlock(&g_streamFactsLock);
    int used = snprintf(out, capacity,
                        "usb-seconds window=%llds samples=%d nowMonoMs=%lld policy=%s stream[%s seq=%lld rate=%d ch=%d slotBits=%d ep=0x%02x fbEp=0x%02x maxPkt=%d openedMsAgo=%lld] "
                        "plan[speed=%d bInterval=%d feedbackBytes=%d interval=%dus ring=%d pkts=%d assumptions=0x%x]\n",
                        (long long)(windowMs / 1000), n, (long long)nowMs, feedbackPolicyName(usbFeedbackPolicy()),
                        f.valid ? "known" : "none", (long long)f.seq, f.rate, f.channels, f.slotBits, f.endpointOut, f.endpointFeedback,
                        f.maxPacket, (long long)(f.valid ? nowMs - f.openedMs : 0),
                        f.busSpeed, f.bInterval, f.feedbackBytes, f.intervalUs, f.ringUrbs, f.packetsPerUrb, f.assumptions);
    if (used < 0 || (size_t)used >= capacity) return capacity - 1;
    // B41-fix1: what the extra fields of a second's line are (the first twelve are B27-A5's, unchanged).
    static const char kLegend[] =
        "legend: fb held=readings the policy did not obey (auto: stuck or implausible; refused counts only the beyond-5% ones); "
        "dropped urb/packet/length=feedback transfers that were not a reading (URB status, packet status, length not 4) and "
        "last=<urb>/<packet>/<length> of the last one; age=ms since the last valid reading at the end of the second (-1: none); "
        "sent=the rate the packetizer was given, frames per microframe, calls=how many times; "
        "wire applied=frames/packets of the audio URBs submitted, the rate that went out; fpmf min/max/mean is the rate received\n";
    int more = snprintf(out + used, capacity - (size_t)used, "%s", kLegend);
    if (more < 0 || (size_t)more >= capacity - (size_t)used) return capacity - 1;
    used += more;
    size_t total = (size_t)used + usbSecondRingFormat(samples, n, nowMs, out + used, capacity - (size_t)used);
    if (total + 1 >= capacity) return total;
    if (eventCount > 0) {
        total += usbSecondRingFormatEvents(events, eventCount, nowMs, feedbackPolicyName, out + total, capacity - total);
    }
    return total;
}

static std::atomic<bool> g_feedbackStartGuardEnabled{false};

void usbSetFeedbackStartGuardEnabled(bool enabled) { g_feedbackStartGuardEnabled.store(enabled, std::memory_order_relaxed); }
bool usbFeedbackStartGuardEnabled() { return g_feedbackStartGuardEnabled.load(std::memory_order_relaxed); }

/**
 * B48-fix1: the start guard holds at this moment: the switch is on, the policy is one that follows the feedback (`follow` is the diagnostic one and never obeys it; `nominal`
 * is nominal already), and either the interface just (re)started and no URB has gone out yet, or the first URB went out less than the plan's guard ago.
 */
static bool startGuardHolds(const UsbAudioContext *ctx, int policy) {
    if (!usbFeedbackStartGuardEnabled() || policy == USB_FEEDBACK_FOLLOW || ctx->plan.feedbackStartGuardMs <= 0) return false;
    return ctx->startGuardPending || monotonicMs() < ctx->startGuardUntilMs;
}

/** What the packetizer is given right now, without recording it: the policy's answer, or the nominal rate while the start guard holds. */
static double policyFpmfNow(const UsbAudioContext *ctx) {
    const int policy = usbFeedbackPolicy();
    const double nominal = ctx->plan.nominalFramesPerInterval;
    if (startGuardHolds(ctx, policy)) return nominal;
    return usbFeedbackPolicyFpmf(policy, ctx->calibratedFpmf, nominal, ctx->continuousFeedbackSeen ? &ctx->feedbackWatch : nullptr);
}

/**
 * The frames-per-microframe the packetizer is given for the next write: the last applied reading under
 * `follow`, otherwise what the policy makes of it. Only the increment per microframe changes; the
 * accumulator carries on, so a switch in mid-stream moves the next packet sizes by at most one frame.
 */
static double effectiveFpmf(UsbAudioContext *ctx) {
    const double fpmf = policyFpmfNow(ctx);
    g_fbSendingFpmf.store(fpmf, std::memory_order_relaxed);
    usbSecondRingSent(&g_secondRing, monotonicMs(), fpmf);  // B41-fix1: what the packetizer was given, a second at a time
    return fpmf;
}

/**
 * B41-fix1 (A1c): one change of what the policy obeys into the one-second ring (usb-second-ring.h), with the stream's identity
 * and what the packetizer was being given before it and is given after. Rare (a few a minute at the most): the ring takes its
 * lock for it, and nothing is logged here.
 */
static void notePolicyEvent(const UsbAudioContext *ctx, enum UsbPolicyEventKind kind, const char *what, double fpmf, double ppm) {
    UsbPolicyEvent e;
    memset(&e, 0, sizeof(e));
    e.atMs = monotonicMs();
    e.stream = ctx->streamSeq;
    e.rate = ctx->sampleRate;
    e.kind = kind;
    e.what = what;
    e.fpmf = fpmf;
    e.ppm = ppm;
    e.sentBefore = g_fbSendingFpmf.load(std::memory_order_relaxed);
    e.sentAfter = policyFpmfNow(ctx);
    e.policy = usbFeedbackPolicy();
    usbSecondRingPolicyEvent(&g_secondRing, &e);
}

/**
 * B41-fix1 (A1c): a gesture of the stream (start, pause, idle, seek, resume) into the same ring as the policy's changes. A drag of the
 * seek bar is many of them: one of a kind per 100 ms is kept, so that the rare policy events are not pushed out of the ring.
 */
static void noteGesture(const UsbAudioContext *ctx, enum UsbPolicyEventKind kind, const char *what) {
    static int64_t lastMs[USB_POLICY_EVENT_KIND_COUNT];
    const int64_t nowMs = monotonicMs();
    if (lastMs[kind] != 0 && nowMs - lastMs[kind] < 100) return;
    lastMs[kind] = nowMs;
    notePolicyEvent(ctx, kind, what, 0.0, 0.0);
}

/**
 * B41-fix1 (A1b): after a reading, is the policy refusing the feedback (stuck or implausible)? The turn from obeying to refusing
 * bumps [UsbAudioContext::refusalEpoch]: the block of PCM being packetized took its rate when it began, and the loop that writes it
 * looks at the epoch between two URBs.
 */
static void updateRefusal(UsbAudioContext *ctx) {
    const bool refusing = ctx->feedbackWatch.stuck || ctx->feedbackWatch.implausible;
    if (refusing && !ctx->refusing) ctx->refusalEpoch++;
    ctx->refusing = refusing;
}

/**
 * B41-fix1 (A1a): a feedback transfer that was not a reading. Counted in the one-second ring with the codes the kernel gave, and one
 * rate-limited USB_GLITCH line says it. It moves nothing: the rate stays what the last valid reading made it.
 */
static void noteFeedbackDropped(const UsbAudioContext *ctx, enum UsbFeedbackPacket verdict, int urbStatus, int packetStatus,
                                unsigned int length) {
    usbSecondRingFeedbackDropped(&g_secondRing, monotonicMs(), (int)verdict, urbStatus, packetStatus, length);
    reportUsbGlitch(USB_GLITCH_FEEDBACK_INVALID, "%s urb=%d packet=%d length=%u rate=%d", usbFeedbackPacketName(verdict), urbStatus,
                    packetStatus, length, ctx->sampleRate);
}

/** An episode began or ended: the counters, and one USB_GLITCH line with what a reviewer needs to see. */
static void announceFeedbackEvent(const UsbFeedbackEvent &ev, double nominal, int rate, double intervalsPerSecond) {
    const char *policy = usbFeedbackPolicyName(usbFeedbackPolicy());
    if (ev.kind == USB_FEEDBACK_EVENT_STUCK) {
        g_fbEpisodes.fetch_add(1, std::memory_order_relaxed);
        g_fbStuckFpmf.store(ev.fpmf, std::memory_order_relaxed);
        g_fbStuckPpm.store(ev.ppm, std::memory_order_relaxed);
        g_fbStuckSinceMs.store(monotonicMs() - ev.heldMs, std::memory_order_relaxed);
        g_fbStuckNow.store(1, std::memory_order_relaxed);
        reportUsbGlitch(USB_GLITCH_FEEDBACK_STUCK,
                        "fpmf=%.4f (%.1f Hz) %+.0f ppm nominal=%.1f Hz rate=%d held=%lldms readings=%lld "
                        "policy=%s sending=%.4f",
                        ev.fpmf, ev.fpmf * intervalsPerSecond, ev.ppm, nominal * intervalsPerSecond, rate, (long long)ev.heldMs,
                        (long long)ev.readings, policy, g_fbSendingFpmf.load(std::memory_order_relaxed));
    } else if (ev.kind == USB_FEEDBACK_EVENT_UNSTUCK) {
        g_fbStuckNow.store(0, std::memory_order_relaxed);
        g_fbStuckSinceMs.store(0, std::memory_order_relaxed);
        g_fbStuckDoneMs.fetch_add((uint64_t)ev.heldMs, std::memory_order_relaxed);
        if ((uint64_t)ev.heldMs > g_fbLongestMs.load(std::memory_order_relaxed)) {
            g_fbLongestMs.store((uint64_t)ev.heldMs, std::memory_order_relaxed);
        }
        const char *why = ev.reason == USB_FEEDBACK_END_CHANGED ? "changed"
                        : ev.reason == USB_FEEDBACK_END_GAP ? "gap" : "stream-end";
        char next[48] = "";
        if (ev.reason == USB_FEEDBACK_END_CHANGED) {
            snprintf(next, sizeof(next), " next=%.4f (%.1f Hz)", ev.nextRaw / 65536.0, ev.nextRaw / 65536.0 * intervalsPerSecond);
        }
        reportUsbGlitch(USB_GLITCH_FEEDBACK_UNSTUCK,
                        "was fpmf=%.4f (%.1f Hz) %+.0f ppm duration=%lldms readings=%lld reason=%s%s policy=%s",
                        ev.fpmf, ev.fpmf * intervalsPerSecond, ev.ppm, (long long)ev.heldMs, (long long)ev.readings,
                        why, next, policy);
    }
}

/**
 * A streak of implausible readings began (B12a-2): one FEEDBACK_REJECTED line and the streak counter. Under
 * `auto` what is sent meanwhile is the healthy average or nominal; under another policy the line only says
 * what the DAC reported. The per-reading count is [g_fbRejectedReadings], not this.
 */
static void announceImplausible(const UsbFeedbackEvent &ev, double nominal, int rate, double kept, double fallback, double intervalsPerSecond) {
    g_fbImplausibleStreaks.fetch_add(1, std::memory_order_relaxed);
    g_fbRejectedFpmf.store(ev.implausibleFpmf, std::memory_order_relaxed);
    // B47-A4: `kept` is the last reading the follower took and `sending` what the packetizer was given BEFORE this refusal (the ramp it had been following);
    // `fallback` is what it is given from now on under `auto` (the healthy average from before the excursion, or nominal), and `ramp` how long the readings had
    // been beyond +-0.5 % when the refusal began.
    reportUsbGlitch(USB_GLITCH_FEEDBACK_REJECTED,
                    "fpmf=%.4f (%.1f Hz) %+.0f ppm why=%s policy=%s kept=%.4f sending=%.4f fallback=%.4f ramp=%lldms nominal=%.1f Hz rate=%d",
                    ev.implausibleFpmf, ev.implausibleFpmf * intervalsPerSecond, ev.implausiblePpm,
                    usbFeedbackImplausibleName(ev.implausibleWhy), usbFeedbackPolicyName(usbFeedbackPolicy()), kept,
                    g_fbSendingFpmf.load(std::memory_order_relaxed), fallback, (long long)ev.implausibleRampMs, nominal * intervalsPerSecond, rate);
}

// ── The locked-up DAC (B47-A5; usb-glitch.h has the evidence and the thresholds) ────────────────────

static std::atomic<bool> g_dacRecoveryEnabled{true};
static std::atomic<int> g_manualRecoveryStep{0};
static std::atomic<int> g_dacLockedNow{0};
static std::atomic<int64_t> g_dacLocksSeen{0};
static std::atomic<int64_t> g_dacRecoveryCounts[USB_DAC_RECOVERY_STATS_COUNT] = {};   // [2..7] of the stats; [0] and [1] are derived

void usbSetDacRecoveryEnabled(bool enabled) { g_dacRecoveryEnabled.store(enabled, std::memory_order_relaxed); }
bool usbDacRecoveryEnabled() { return g_dacRecoveryEnabled.load(std::memory_order_relaxed); }
void usbRequestDacRecovery(int step) { g_manualRecoveryStep.store(step == 1 || step == 2 ? step : 0, std::memory_order_relaxed); }
int usbTakeManualDacRecovery() { return g_manualRecoveryStep.exchange(0, std::memory_order_relaxed); }

/** Either watch calls the DAC locked up: share the counters, log and Mark noise policy event. */
static void announceDacLock(UsbAudioContext *ctx, const char *why, double fpmf, double ppm, int64_t heldMs, int64_t readings, int64_t lateReaps, bool rateChangeHold = false) {
    g_dacLocksSeen.fetch_add(1, std::memory_order_relaxed);
    reportUsbGlitch(USB_GLITCH_DAC_LOCKED,
                    "why=%s%s feedback=%.4f (%.1f Hz) %+.0f ppm heldMs=%lld railedMs=%lld readings=%lld reapSlow=%lld policy=%s rate=%d stream=%lld",
                    why, rateChangeHold ? " hold=rate-change" : "", fpmf, fpmf * ctx->plan.intervalsPerSecond, ppm, (long long)heldMs,
                    (long long)usbDacLockRailedMs(&ctx->dacLock, monotonicMs()), (long long)readings, (long long)lateReaps,
                    usbFeedbackPolicyName(usbFeedbackPolicy()), ctx->sampleRate, (long long)ctx->streamSeq);
    notePolicyEvent(ctx, USB_POLICY_DAC_LOCKED, why, fpmf, ppm);
}

/** One feedback reading, applied or not, into the rail detector. The stuck watch has already seen it. */
static void observeDacLock(UsbAudioContext *ctx, double fpmf, const UsbFeedbackEvent &feedback) {
    UsbDacLockEvent lock;
    const double ppm = usbFeedbackPpm(fpmf, ctx->plan.nominalFramesPerInterval);
    const bool railBegan = usbDacLockObserve(&ctx->dacLock, ppm, monotonicMs(), &lock);
    if (railBegan) {
        announceDacLock(ctx, "rail", fpmf, lock.ppm, lock.railedMs, lock.readings, lock.lateReaps);
    } else if (feedback.kind == USB_FEEDBACK_EVENT_STUCK && !ctx->dacLock.locked) {
        // A rail has its own, shorter hold. Do not announce a second lock for that same frozen rail.
        announceDacLock(ctx, "stuck", feedback.fpmf, feedback.ppm, feedback.heldMs, feedback.readings, ctx->dacLock.lateReaps, feedback.rateChangeHold);
    }
    g_dacLockedNow.store(ctx->dacLock.locked || ctx->feedbackWatch.stuck ? 1 : 0, std::memory_order_relaxed);
}

static int64_t g_lastDacEpisodeMs = 0;   // the writer thread only (one stream writes at a time)

void usbDacRecoveryForgetEpisodes() { g_lastDacEpisodeMs = 0; }

/** The line and the counters for a verdict the policy reached on an earlier step. */
static void announceRecoveryVerdict(UsbAudioContext *ctx, int step, bool ok, int64_t nowMs) {
    if (step != 1 && step != 2) return;
    if (ok) g_dacRecoveryCounts[step == 1 ? 3 : 5].fetch_add(1, std::memory_order_relaxed);
    reportUsbGlitch(USB_GLITCH_DAC_RECOVERY, "step=%d result=%s verdictAt=%lldms rate=%d stream=%lld policy=%s", step, ok ? "ok" : (ctx->feedbackWatch.stuck ? "still-stuck" : "still-railed"),
                    (long long)nowMs, ctx->sampleRate, (long long)ctx->streamSeq, usbFeedbackPolicyName(usbFeedbackPolicy()));
    static const char *const kWhat[2][3] = {{"step 1 ok", "step 1 still-railed", "step 1 still-stuck"}, {"step 2 ok", "step 2 still-railed", "step 2 still-stuck"}};
    notePolicyEvent(ctx, USB_POLICY_DAC_RECOVERY, kWhat[step - 1][ok ? 0 : (ctx->feedbackWatch.stuck ? 2 : 1)], 0.0, 0.0);
}

void usbDacLockState(UsbAudioContext *ctx, int64_t out[USB_DAC_STATE_COUNT]) {
    const int64_t nowMs = monotonicMs();
    out[0] = g_dacLockedNow.load(std::memory_order_relaxed);
    out[1] = g_dacLocksSeen.load(std::memory_order_relaxed);
    out[2] = ctx ? usbDacLockRailedMs(&ctx->dacLock, nowMs) : 0;
    out[3] = ctx ? (int64_t)llround(ctx->dacLock.ppm) : 0;
    out[4] = ctx ? ctx->dacLock.lateReaps : 0;
    out[5] = 0;
    if (!ctx) return;
    // A manual request (RECOVER_DAC_NOW) goes first and bypasses the policy; the automatic decision is the policy's, from the detector.
    const int manual = usbTakeManualDacRecovery();
    int verdictStep = 0;
    bool verdictOk = false;
    const bool locked = ctx->dacLock.locked || ctx->feedbackWatch.stuck;
    const double runPpm = usbFeedbackPpm(ctx->feedbackWatch.runRaw / 65536.0, ctx->plan.nominalFramesPerInterval);
    // A run can still become stuck after the four-second verdict. Defer success while it is
    // unresolved, without shortening the watch's hold or calling a healthy start ramp a fault.
    const bool pending = ctx->dacLock.inRun || (ctx->feedbackWatch.haveRun &&
        (runPpm > USB_FEEDBACK_STUCK_MIN_PPM || runPpm < -USB_FEEDBACK_STUCK_MIN_PPM));
    int step = usbDacRecoveryDecide(&ctx->dacPolicy, locked, usbDacRecoveryEnabled(), nowMs, &g_lastDacEpisodeMs, &verdictStep, &verdictOk, pending);
    if (verdictStep != 0) announceRecoveryVerdict(ctx, verdictStep, verdictOk, nowMs);
    if (step == USB_DAC_STEP_GIVE_UP) {
        g_dacRecoveryCounts[6].fetch_add(1, std::memory_order_relaxed);
        reportUsbGlitch(USB_GLITCH_DAC_RECOVERY,
                        "step=gave-up result=%s railedMs=%lld reapSlow=%lld: both steps were taken and the feedback is locked again; the stream plays on, the feedback stays refused "
                        "(policy=%s rate=%d stream=%lld)",
                        ctx->feedbackWatch.stuck ? "still-stuck" : "still-railed", (long long)out[2], (long long)out[4], usbFeedbackPolicyName(usbFeedbackPolicy()), ctx->sampleRate, (long long)ctx->streamSeq);
        notePolicyEvent(ctx, USB_POLICY_DAC_RECOVERY, "gave-up", 0.0, 0.0);
    }
    out[5] = manual != 0 ? manual : step;   // 0, 1, 2, or 3 = the steps are used up (already logged; the writer has nothing to do)
}

void usbDacRecoveryStats(int64_t out[USB_DAC_RECOVERY_STATS_COUNT]) {
    out[0] = usbDacRecoveryEnabled() ? 1 : 0;
    out[1] = g_dacLocksSeen.load(std::memory_order_relaxed);
    for (int i = 2; i < USB_DAC_RECOVERY_STATS_COUNT; i++) out[i] = g_dacRecoveryCounts[i].load(std::memory_order_relaxed);
}

void usbNoteDacRecovery(UsbAudioContext *ctx, int step, int result, int64_t elapsedMs) {
    if (!ctx || (step != 1 && step != 2)) return;
    g_dacRecoveryCounts[step == 1 ? 2 : 4].fetch_add(1, std::memory_order_relaxed);
    g_dacRecoveryCounts[7].store(elapsedMs, std::memory_order_relaxed);
    reportUsbGlitch(USB_GLITCH_DAC_RECOVERY, "step=%d result=%s elapsed=%lldms rate=%d stream=%lld policy=%s", step, result == 0 ? "ran" : "failed",
                    (long long)elapsedMs, ctx->sampleRate, (long long)ctx->streamSeq, usbFeedbackPolicyName(usbFeedbackPolicy()));
    static const char *const kWhat[2][2] = {{"step 1 ran", "step 1 failed"}, {"step 2 ran", "step 2 failed"}};
    notePolicyEvent(ctx, USB_POLICY_DAC_RECOVERY, kWhat[step - 1][result == 0 ? 0 : 1], 0.0, 0.0);
    usbDacRecoveryStepDone(&ctx->dacPolicy, step, monotonicMs());
}

/**
 * Every clean feedback reading into the watch. About a thousand a second, so: one clock read, an integer
 * compare, a few relaxed stores; no allocation and no log unless an episode begins or ends.
 */
static void watchFeedbackReading(UsbAudioContext *ctx, uint32_t raw, double nominal) {
    g_fbLastFpmf.store(raw / 65536.0, std::memory_order_relaxed);
    g_fbRate.store(ctx->sampleRate, std::memory_order_relaxed);
    const bool wasImplausible = ctx->feedbackWatch.implausible;
    const char *wasWhy = usbFeedbackImplausibleName(ctx->feedbackWatch.implausibleWhy);
    UsbFeedbackEvent ev;
    usbFeedbackWatchObservePolicy(&ctx->feedbackWatch, raw, nominal, monotonicMs(), usbFeedbackPolicy(), &ev);
    updateRefusal(ctx);
    if (ev.kind != USB_FEEDBACK_EVENT_NONE) announceFeedbackEvent(ev, nominal, ctx->sampleRate, ctx->plan.intervalsPerSecond);
    if (ev.implausibleStarted) announceImplausible(ev, nominal, ctx->sampleRate, ctx->calibratedFpmf, policyFpmfNow(ctx), ctx->plan.intervalsPerSecond);
    // B41-fix1 (A1c): the start and the end of each change of what the policy obeys, with the stream's identity (the ring keeps them).
    if (ev.kind == USB_FEEDBACK_EVENT_STUCK) notePolicyEvent(ctx, USB_POLICY_STUCK_BEGIN, "identical", ev.fpmf, ev.ppm);
    observeDacLock(ctx, raw / 65536.0, ev);
    if (ev.kind == USB_FEEDBACK_EVENT_UNSTUCK) {
        notePolicyEvent(ctx, USB_POLICY_STUCK_END, ev.reason == USB_FEEDBACK_END_CHANGED ? "changed" : "gap", ev.fpmf, ev.ppm);
    }
    if (ev.implausibleStarted) {
        notePolicyEvent(ctx, USB_POLICY_REFUSE_BEGIN, usbFeedbackImplausibleName(ev.implausibleWhy), ev.implausibleFpmf, ev.implausiblePpm);
    }
    if (wasImplausible && !ctx->feedbackWatch.implausible) {
        notePolicyEvent(ctx, USB_POLICY_REFUSE_END, wasWhy, raw / 65536.0, usbFeedbackPpm(raw / 65536.0, nominal));
    }
    if (ctx->feedbackWatch.stuck) g_fbStuckReadings.fetch_add(1, std::memory_order_relaxed);
    if (ctx->feedbackWatch.implausible && usbFeedbackPolicyRefuses(usbFeedbackPolicy())) {
        g_fbRejectedReadings.fetch_add(1, std::memory_order_relaxed);
    }
    // A reading `auto` did not obey (stuck or implausible) is counted here, not in `refused`, which is only the beyond-5 % branch.
    if (ctx->refusing && usbFeedbackPolicyRefuses(usbFeedbackPolicy())) usbSecondRingHeld(&g_secondRing, monotonicMs());
}

/** The stream ends or restarts: an open episode is over (the DAC is about to be told something new). */
static void endFeedbackWatch(UsbAudioContext *ctx) {
    const bool wasImplausible = ctx->feedbackWatch.implausible;
    const char *wasWhy = usbFeedbackImplausibleName(ctx->feedbackWatch.implausibleWhy);
    UsbFeedbackEvent ev;
    usbFeedbackWatchEnd(&ctx->feedbackWatch, ctx->plan.nominalFramesPerInterval, &ev);
    ctx->refusing = false;
    usbDacLockReset(&ctx->dacLock);   // B47-A5: a new stream (or a restarted one) has no rail yet
    g_dacLockedNow.store(0, std::memory_order_relaxed);
    if (ev.kind != USB_FEEDBACK_EVENT_NONE) announceFeedbackEvent(ev, ctx->plan.nominalFramesPerInterval, ctx->sampleRate, ctx->plan.intervalsPerSecond);
    if (ev.kind == USB_FEEDBACK_EVENT_UNSTUCK) notePolicyEvent(ctx, USB_POLICY_STUCK_END, "stream-end", ev.fpmf, ev.ppm);
    if (wasImplausible) notePolicyEvent(ctx, USB_POLICY_REFUSE_END, wasWhy, 0.0, 0.0);
}

/** Transitions only, independently limited to one leave and one return per second. */
static void noteFeedbackWindow(UsbAudioContext *ctx, const uint8_t *fb, double fpmf, bool outside, int64_t nowMs) {
    if (outside != ctx->feedbackOutsideWindow) {
        ctx->feedbackOutsideWindow = outside;
        ctx->feedbackWindowLogPending = true;
        if (outside) ctx->outsideSinceMs = nowMs;
    }
    if (!ctx->feedbackWindowLogPending) return;
    const int bucket = outside ? 0 : 1;
    if (ctx->windowLogMs[bucket] >= 0 && nowMs - ctx->windowLogMs[bucket] < 1000) return;
    ctx->windowLogMs[bucket] = nowMs;
    ctx->feedbackWindowLogPending = false;
    const int64_t streamAge = ctx->dataAltSetMs >= 0 ? nowMs - ctx->dataAltSetMs : -1;
    const double ppm = usbFeedbackPpm(fpmf, ctx->plan.nominalFramesPerInterval);
    if (outside) {
        LOGW("FEEDBACK_OUT_OF_WINDOW fpmf=%.4f ppm=%+.0f raw=%02X %02X %02X %02X since=%lldms streamAge=%lldms",
             fpmf, ppm, fb[0], fb[1], fb[2], fb[3], (long long)(nowMs - ctx->outsideSinceMs), (long long)streamAge);
    } else {
        LOGW("FEEDBACK_OUT_OF_WINDOW back after %lldms fpmf=%.4f ppm=%+.0f raw=%02X %02X %02X %02X streamAge=%lldms",
             (long long)(nowMs - ctx->outsideSinceMs), fpmf, ppm, fb[0], fb[1], fb[2], fb[3], (long long)streamAge);
    }
}

/** Observers see every clean packet. Only the in-window, policy-approved rate enters the follower. */
static void observeCleanFeedback(UsbAudioContext *ctx, const uint8_t *fb, bool continuous) {
    if (continuous && !ctx->continuousFeedbackSeen) {
        // Preserve the startup calibration until the first continuous reading, as before.
        ctx->continuousFeedbackSeen = true;
        ctx->refusing = false;
    }
    const uint32_t raw = (uint32_t)fb[0] | ((uint32_t)fb[1] << 8) | ((uint32_t)fb[2] << 16) | ((uint32_t)fb[3] << 24);
    const double fpmf = raw / 65536.0;
    const double nominal = ctx->plan.nominalFramesPerInterval;
    const int64_t nowMs = monotonicMs();
    const bool outside = fpmf <= nominal * 0.95 || fpmf >= nominal * 1.05;
    ctx->liveFpmf = fpmf;
    noteFeedbackWindow(ctx, fb, fpmf, outside, nowMs);
    const double deviation = usbFeedbackDeviation(fpmf, nominal);
    const bool deviating = deviation > 0.002 || deviation < -0.002;
    usbSecondRingFeedback(&g_secondRing, nowMs, fpmf, nominal, deviating);
    if (deviating) {
        reportUsbGlitch(USB_GLITCH_FEEDBACK_DEVIATION, "fpmf=%.4f (%.1f Hz) %+.3f%% nominal=%.1f Hz",
                        fpmf, fpmf * ctx->plan.intervalsPerSecond, deviation * 100.0, nominal * ctx->plan.intervalsPerSecond);
    }
    watchFeedbackReading(ctx, raw, nominal);
    if (!outside) {
        ctx->readingFpmf = fpmf;
        ctx->acceptedAtMs = nowMs;
        const double applied = usbFeedbackApply(ctx->calibratedFpmf, fpmf, nominal);
        if (!(ctx->refusing && usbFeedbackPolicyRefuses(usbFeedbackPolicy()))) ctx->calibratedFpmf = applied;
    } else {
        // The watch already counts auto refusals; count the other policies here, once per reading.
        if (!usbFeedbackPolicyRefuses(usbFeedbackPolicy())) g_fbRejectedReadings.fetch_add(1, std::memory_order_relaxed);
        usbSecondRingRefused(&g_secondRing, nowMs);
    }
    if (g_feedbackCount.load(std::memory_order_relaxed) % 10000 == 0) {
        LOGI("Feedback #%lld: fpmf=%.4f (%.1f Hz)", (long long)g_feedbackCount.load(std::memory_order_relaxed),
             fpmf, fpmf * ctx->plan.intervalsPerSecond);
    }
}

/**
 * Process a completed feedback URB: parse the DAC's clock rate and
 * update calibratedFpmf for real-time clock tracking.
 *
 * B41-fix1 (A1a): a transfer is a reading only if the URB and its packet completed clean and it delivered the four bytes that were
 * asked for. The kernel can deliver bytes together with an error (-EILSEQ on the packet, -ECONNRESET on the URB, ...), and a positive
 * length is not a good completion: such a transfer is counted (the one-second ring, one USB_GLITCH line) and moves nothing.
 *
 * B41-fix1 (A1b): the reading is judged BEFORE the follower (`calibratedFpmf`) takes it. Under `auto` a reading the policy refuses
 * (absurd, sustained, or beyond +-5 %) never enters the follower, so nothing that reads the follower can pick it up; under the other
 * policies the follower takes every reading in the window, as `follow` always did.
 */
static void handleFeedbackCompletion(UsbAudioContext *ctx) {
    ctx->feedbackInFlight = false;
    g_feedbackCount.fetch_add(1, std::memory_order_relaxed);

    const struct usbdevfs_urb *done = ctx->feedbackUrb;
    const enum UsbFeedbackPacket verdict =
        usbFeedbackPacketVerdict(done->status, done->iso_frame_desc[0].status, done->iso_frame_desc[0].actual_length);
    if (verdict != USB_FEEDBACK_PACKET_OK) {
        noteFeedbackDropped(ctx, verdict, done->status, done->iso_frame_desc[0].status, done->iso_frame_desc[0].actual_length);
    } else {
        observeCleanFeedback(ctx, feedbackData(ctx), true);
    }

    // Resubmit for continuous tracking
    submitFeedbackUrb(ctx);
}

// ── One-shot feedback read at stream start ──────────────────────────

/** How a bounded wait for one URB's own completion ended. */
enum class OwnReap { REAPED, NOT_YET, FAILED };

/**
 * Reaps until [own] comes back, for at most [waitMs] (0: only what has already completed). Every completion that
 * is not [own] is observed like any other reap and routed as the other reap sites route it: a parked URB (AUD-112)
 * is finally ours to free, the continuous feedback URB is handled, and anything else is a completion of an earlier
 * run (this is stream start: the ring is empty and is reset right after) and is dropped.
 *
 * FAILED: the reap itself failed (the device is gone), so nothing is known about [own].
 */
static OwnReap reapUntilOwn(UsbAudioContext *ctx, const struct usbdevfs_urb *own, int waitMs) {
    const int64_t deadlineMs = monotonicMs() + waitMs;
    for (;;) {
        struct usbdevfs_urb *c = nullptr;
        const int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
        if (ret == 0 && c != nullptr) {
            observeReapedUrb(ctx, ReapSite::READ_FEEDBACK, c);
            if (c == own) return OwnReap::REAPED;
            if (urbQuarantineTake(c)) {
                free(c);
            } else if (c == ctx->feedbackUrb && ctx->feedbackInFlight) {
                handleFeedbackCompletion(ctx);
            }
            continue;
        }
        if (ret < 0 && errno != EAGAIN) return OwnReap::FAILED;
        if (monotonicMs() >= deadlineMs) return OwnReap::NOT_YET;
        usleep(1000);
    }
}

/**
 * One reading of the DAC's feedback endpoint, at stream start (B14-1, audit A-03). Returns frames per microframe,
 * or 0 when the DAC did not answer.
 *
 * The URB and its four data bytes are one heap block: when the kernel writes the completion (status, length and
 * data are written into the caller's memory when the URB is *reaped*, not when it completes) the buffer is
 * exactly as alive as the URB. The read used to point the URB at a local array and free the URB after a cancel
 * whose completion it had not collected.
 *
 * The one rule: a URB the kernel accepted is freed only after its own completion has been reaped (identity: the
 * address REAPURBNDELAY returned is `u`). An answer that is late is cancelled and waited for, bounded like a
 * barrier (AUD-112); if it still does not come back it is parked in the quarantine, where whichever reap site
 * receives it frees it. If it cannot be parked either, it is leaked on purpose: a leak is a few dozen bytes, a
 * free of a URB the kernel still holds is a write into freed memory.
 */
static double readFeedback(UsbAudioContext *ctx, int ep, enum UsbFeedbackPacket *verdictOut, bool *received) {
    *received = false;
    const int fd = ctx->fd;
    const size_t urbBytes = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    auto *u = (struct usbdevfs_urb *)calloc(1, urbBytes + USB_FEEDBACK_BYTES);
    if (!u) return 0;
    uint8_t *fb = (uint8_t *)u + urbBytes;
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = (unsigned char)ep;
    u->buffer = fb;
    u->buffer_length = USB_FEEDBACK_BYTES;
    u->number_of_packets = 1;
    u->iso_frame_desc[0].length = USB_FEEDBACK_BYTES;
    // The kernel refused it: it never held it, so this free is safe.
    if (ioctl(fd, USBDEVFS_SUBMITURB, u) < 0) { free(u); return 0; }

    usleep(2000);
    OwnReap state = reapUntilOwn(ctx, u, 0);
    bool cancelled = false;
    if (state == OwnReap::NOT_YET) {
        // No answer in time: cancel. EINVAL (it completed meanwhile and waits to be reaped) leaves its data valid.
        cancelled = ioctl(fd, USBDEVFS_DISCARDURB, u) == 0;
        state = reapUntilOwn(ctx, u, URB_DRAIN_COMPLETION_WAIT_MS);
    }
    if (state != OwnReap::REAPED) {
        if (urbQuarantineAdd(u)) {
            LOGW("readFeedback: no completion for the one-shot feedback URB %p; parked (total parked %d)",
                 (void *)u, urbQuarantineCount());
            return 0;  // `u` belongs to the quarantine now: not touched again
        }
        // Cannot park it: wait for this one alone, the barrier's last resort, and never free it unreaped.
        if (state == OwnReap::FAILED || reapUntilOwn(ctx, u, 500) != OwnReap::REAPED) {
            LOGE("readFeedback: the one-shot feedback URB %p never came back and the quarantine is full; leaked", (void *)u);
            return 0;
        }
        cancelled = true;  // late, and reaped only now: not a reading
    }

    // B41-fix1 (A1a): a transfer that came back with an error status, or with anything but the four bytes asked for, is not a
    // reading, even if it carries bytes: the start then keeps the nominal rate, as when no answer comes.
    double r = 0;
    if (!cancelled) {
        const enum UsbFeedbackPacket verdict =
            usbFeedbackPacketVerdict(u->status, u->iso_frame_desc[0].status, u->iso_frame_desc[0].actual_length);
        if (verdictOut != nullptr) *verdictOut = verdict;
        if (verdict == USB_FEEDBACK_PACKET_OK) {
            const uint32_t raw = (uint32_t)fb[0] | ((uint32_t)fb[1] << 8) | ((uint32_t)fb[2] << 16) | ((uint32_t)fb[3] << 24);
            g_feedbackCount.fetch_add(1, std::memory_order_relaxed);
            observeCleanFeedback(ctx, fb, false);
            r = raw / 65536.0;
            *received = true;
        } else {
            noteFeedbackDropped(ctx, verdict, u->status, u->iso_frame_desc[0].status, u->iso_frame_desc[0].actual_length);
        }
    }
    free(u);
    return r;
}

// ── ISO URB submission / reap ───────────────────────────────────────

/**
 * Submit the URB at ring[submitIdx] with the given packet layout.
 * @param numPackets  Actual number of packets (no zero-length padding)
 * Returns 0 on success, -1 on error.
 */
static int submitRingUrb(UsbAudioContext *ctx, const int *pktSizes, int numPackets, int totalBytes) {
    UrbSlot *slot = &ctx->ring[ctx->submitIdx];
    struct usbdevfs_urb *u = slot->urb;

    // Clear the full URB struct including iso_frame_desc (stale actual_length/status)
    size_t clearSize = sizeof(struct usbdevfs_urb) +
                       numPackets * sizeof(struct usbdevfs_iso_packet_desc);
    memset(u, 0, clearSize);

    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = (unsigned char)ctx->endpointOut;
    u->buffer = slot->buffer;
    u->buffer_length = totalBytes;
    u->number_of_packets = numPackets;
    for (int i = 0; i < numPackets; i++)
        u->iso_frame_desc[i].length = (unsigned)pktSizes[i];

    slot->dataLength = totalBytes;

    // Bind submission identity before the kernel can return it. Generation advances per successful
    // submission, so (slot, generation) names this exact request even though the slot is reused.
    const int mySlot = (int)(slot - &ctx->ring[0]);
    u->usercontext = &ctx->cookies[mySlot];
    ctx->cookies[mySlot].slot = mySlot;
    ctx->cookies[mySlot].endpoint = ctx->endpointOut;
    const uint64_t myGen = ctx->cookies[mySlot].generation.fetch_add(1) + 1;

    int ret = ioctl(ctx->fd, USBDEVFS_SUBMITURB, u);
    if (ret < 0) {
        // Not submitted, so this generation never existed in flight; roll it back so a later
        // successful submission of this slot does not carry a phantom generation.
        ctx->cookies[mySlot].generation.fetch_sub(1);
        return -1;
    }
    (void)myGen;
    if (ret < 0) {
        LOGE("SUBMITURB FAILED slot=%d ep=0x%02x bytes=%d pkts=%d errno=%d (%s)",
             ctx->submitIdx, ctx->endpointOut, totalBytes, numPackets, errno, strerror(errno));
        return -1;
    }

    ctx->submitIdx = (ctx->submitIdx + 1) % ctx->plan.ringUrbs;
    ctx->urbsInFlight++;
    ctx->urbSubmits.fetch_add(1, std::memory_order_relaxed);
    if (ctx->startGuardPending) {
        // B48-fix1: the first URB after the interface (re)started is when the DAC's servo starts reacting to data: the guard's clock starts here.
        ctx->startGuardPending = false;
        ctx->startGuardUntilMs = monotonicMs() + ctx->plan.feedbackStartGuardMs;
    }
    observeOutputGap(ctx, numPackets);
    // B41-fix1 (A1c): the rate that went out, a second at a time: this URB's frames and packets, which is all the plan says.
    usbSecondRingWire(&g_secondRing, monotonicMs(), ctx->bytesPerFrame > 0 ? totalBytes / ctx->bytesPerFrame : 0, numPackets);
    return 0;
}

// Only the submitted oldest audio URB can advance this ordered ring. In particular, a leaked
// feedback block (full quarantine) is neither the current feedback block nor audio by exclusion.
// Do not dereference or free an unrecognised address: its ownership is unknown.
static bool isOldestAudioCompletion(const UsbAudioContext *ctx, const struct usbdevfs_urb *u) {
    return ctx->ringAllocated && ctx->urbsInFlight > 0 && u == ctx->ring[ctx->reapIdx].urb;
}

/**
 * Reap the oldest submitted URB (at ring[reapIdx]).
 * ISO URBs with ISO_ASAP complete in FIFO order, so we always reap
 * the oldest one.
 *
 * IMPORTANT: REAPURBNDELAY returns ANY completed URB — audio or feedback.
 * If we get a feedback URB, we process it (update fpmf, resubmit) and
 * retry. This implements continuous real-time clock calibration on the
 * same thread as audio URB recycling — no second thread, no extra
 * synchronization.
 *
 * @param timeoutMs  Maximum wait in milliseconds
 * @return 0 = success (audio URB reaped), -1 = error, -2 = timeout
 */
static int reapOldestUrb(UsbAudioContext *ctx, int timeoutMs, ReapSite site) {
    struct usbdevfs_urb *c = nullptr;
    const int64_t startMs = monotonicMs();
    const int64_t startUs = monotonicUs();

    // Bounded by time, `timeoutMs` as before (it used to be 8 iterations of 125 µs per ms). Between two
    // reap attempts the thread waits in waitForCompletion (AUD-145).
    bool afterSignal = false;
    for (;;) {
        int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
        if (ret == 0 && c != nullptr) {
            afterSignal = false;
            // Check if this is a feedback URB (not an audio URB)
            // Observe before any routing decision: this is the only place a completion's exact
            // identity can be captured, and the caller may drop it.
            observeReapedUrb(ctx, site, c);
            if (urbQuarantineTake(c)) {
                // A URB a barrier gave up waiting for: not one of the ring's, and now safe to free.
                free(c);
                c = nullptr;
                continue;
            }
            if (c == ctx->feedbackUrb && ctx->feedbackInFlight) {
                handleFeedbackCompletion(ctx);
                c = nullptr;  // retry — we need an audio URB
                continue;
            }
            if (!isOldestAudioCompletion(ctx, c)) {
                c = nullptr;
                continue;
            }
            // This completion returns the current oldest audio URB to us.
            checkIsoCompletion(ctx, c);
            // With the ring full the oldest URB is due within ~1 ms. `waitedMs` is wall time around this call: far longer is a late
            // completion or a writer that was not running, and the number alone cannot say which (B24: OUTPUT_GAP's runq=/cpu= can).
            const int64_t waitedMs = monotonicMs() - startMs;
            if (site == ReapSite::BACKPRESSURE && usbReapIsSlow(waitedMs)) {
                usbDacLockLateReap(&ctx->dacLock);   // B47-A5: counted every time (the line below is rate limited), only while a rail is open
                reportUsbGlitch(USB_GLITCH_REAP_SLOW, "waited %lld ms inFlight=%d",
                                (long long)waitedMs, ctx->urbsInFlight);
            }
            ctx->reapIdx = (ctx->reapIdx + 1) % ctx->plan.ringUrbs;
            ctx->urbsInFlight--;
            ctx->urbReaps.fetch_add(1, std::memory_order_relaxed);
            return 0;
        }
        if (ret < 0 && errno != EAGAIN) {
            LOGE("reapOldestUrb: error errno=%d (%s)", errno, strerror(errno));
            return -1;
        }
        // The kernel said a URB was ready and there was none (another thread reaped it first).
        if (afterSignal) g_waitEmpty.fetch_add(1, std::memory_order_relaxed);
        const int64_t remainingUs = (int64_t)timeoutMs * 1000 - (monotonicUs() - startUs);
        if (remainingUs <= 0) return -2;  // timeout
        afterSignal = waitForCompletion(ctx, (int)((remainingUs + 999) / 1000));
    }
}

/**
 * Drain ALL in-flight URBs. Blocks until every URB is reaped or timeout.
 *
 * @param discardImmediately Skip the natural-reap phase and cancel every queued
 *        URB outright. False waits for URBs to complete on their own first, which
 *        is right when shutting a stream down; true is required for a seek, where
 *        waiting would let the stale audio actually play before it is discarded —
 *        the exact thing the barrier exists to prevent.
 * @return Number of URBs successfully drained
 */
/**
 * Per-invocation discard tally.
 *
 * Local to one barrier call on purpose. Cumulative counters can only say how many discards have
 * ever been requested; they cannot prove that *this* barrier requested any, which is exactly the
 * distinction that made a run of no-op barriers look like twenty successful ones.
 */
struct DiscardTally {
    int requested = 0;
    int ok = 0;
    /** URB had already completed: DISCARDURB returns ENODATA/ENOENT. Benign, not a failure. */
    int alreadyComplete = 0;
    int failed = 0;
    int lastErrno = 0;
    int retired = 0;
    /**
     * URBs still kernel-owned after the discard+reap sequence.
     *
     * Per the lifecycle every submitted URB must resolve to REAPED by the end of the barrier,
     * whether its cancellation was accepted (`DISCARD_ACCEPTED`) or refused with EINVAL because it
     * had already completed (`DISCARD_EINVAL` → still must be reaped). Anything left here is
     * unresolved, and unresolved is a real problem: the slot is still owned by the kernel while
     * the software is about to reuse it.
     */
    int unresolved = 0;
    /** First slot index still unresolved, for pinpointing which URBs are stuck. */
    int unresolvedFrom = -1;
};

/**
 * B27-A2 (review R-02): the continuous feedback URB was cancelled and its completion has not come back. That is not "idle".
 * The kernel may still hold the URB, and it writes the completion's status and the four data bytes into this memory when the
 * URB is reaped, which can be after the context (or the whole stream) is gone. A counter that ran out proves nothing about it.
 *
 * The same rule as the ring's and the one-shot read's (AUD-112, B14-1): a URB the kernel accepted is freed only after its own
 * completion was reaped, by identity. So it is parked in the quarantine, where whichever reap site receives it frees it, and
 * [ctx] gets a fresh idle block (or none, if there is no memory: then this stream has no continuous feedback). If it cannot be
 * parked, the caller's [reapStep] is run for this one alone, the barrier's last resort; if it still does not come back it is
 * leaked on purpose, because a leak is a few dozen bytes and a free of a URB the kernel holds is a write into freed memory.
 *
 * [reapStep] reaps once and returns true when [ctx]'s feedback URB itself came back. Returns true when it did (the block is the
 * caller's again, as it was), false when it was parked or leaked ([ctx]->feedbackUrb is then another block, or null).
 * [replace]: give [ctx] a fresh block (the stream goes on); false when the stream is being destroyed.
 */
template <typename ReapStep>
static bool retireUnreturnedFeedbackUrb(UsbAudioContext *ctx, ReapStep reapStep, bool replace) {
    struct usbdevfs_urb *held = ctx->feedbackUrb;
    if (urbQuarantineAdd(held)) {
        LOGW("feedback URB %p was cancelled and has not come back; parked (total parked %d)", (void *)held, urbQuarantineCount());
    } else {
        bool back = false;
        for (int attempt = 0; attempt < 500 && !back; attempt++) {
            if (reapStep()) back = true;
            else usleep(1000);
        }
        if (back) return true;
        LOGE("feedback URB %p never came back and the quarantine is full; leaked", (void *)held);
    }
    ctx->feedbackUrb = replace ? newFeedbackUrb() : nullptr;
    return false;
}

/**
 * Frees the continuous feedback URB with its context (B27-A2). A URB the kernel may still hold is cancelled and waited for
 * like the barrier does; an idle one, or one the kernel handed back, is freed. Never frees what was not reaped.
 */
static void releaseFeedbackUrb(UsbAudioContext *ctx) {
    if (ctx->feedbackUrb == nullptr) return;
    if (ctx->feedbackInFlight) {
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->feedbackUrb);
        const auto reapStep = [ctx]() -> bool {
            struct usbdevfs_urb *c = nullptr;
            if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c) != 0 || c == nullptr) return false;
            observeReapedUrb(ctx, ReapSite::BARRIER_FEEDBACK, c);
            if (c == ctx->feedbackUrb) return true;
            if (urbQuarantineTake(c)) free(c);  // anything else is a completion of an earlier run: dropped, never ours to free
            return false;
        };
        bool back = false;
        for (int i = 0; i < 50 && !back; i++) {
            if (reapStep()) back = true;
            else usleep(100);
        }
        if (!back) retireUnreturnedFeedbackUrb(ctx, reapStep, false);
        ctx->feedbackInFlight = false;
    }
    free(ctx->feedbackUrb);  // idle, or reaped; null after a parked or leaked one
    ctx->feedbackUrb = nullptr;
}

static int drainAllUrbs(UsbAudioContext *ctx, bool discardImmediately, DiscardTally *tally) {
    int drained = 0;
    int initialCount = ctx->urbsInFlight;
    LOGI("drainAllUrbs: draining %d URBs (feedback=%s)...",
         initialCount, ctx->feedbackInFlight ? "in-flight" : "idle");

    // Exactly the ring URBs in flight. Each resolves when the kernel returns it, from any phase.
    UrbDrainSet pending;
    urbDrainInit(&pending);
    for (int i = 0; i < ctx->urbsInFlight && i < ctx->plan.ringUrbs; i++) {
        urbDrainAdd(&pending, ctx->ring[(ctx->reapIdx + i) % ctx->plan.ringUrbs].urb);
    }

    int earlyReaped = 0;
    // Discard the feedback URB first so it doesn't interfere with draining
    if (ctx->feedbackInFlight && ctx->feedbackUrb) {
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->feedbackUrb);
        // One reap: true once the continuous feedback URB itself is back. Whatever else comes back is accounted as it always was.
        const auto reapFeedbackStep = [ctx, &pending, &earlyReaped, tally]() -> bool {
            struct usbdevfs_urb *c = nullptr;
            const int rr = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
            if (rr != 0 || c == nullptr) return false;
            // Observed BEFORE the c != feedbackUrb test below, which is where a foreign
            // completion is currently discarded.
            observeReapedUrb(ctx, ReapSite::BARRIER_FEEDBACK, c);
            if (c == ctx->feedbackUrb) return true;
            // A data URB reaped here is back for good. Before, it was dropped and phase 3
            // then waited ~0.5 s for a completion that had already been consumed (AUD-112).
            if (urbQuarantineTake(c)) free(c);
            else if (urbDrainResolve(&pending, c)) {
                earlyReaped++;
                // Retired like any other: counting it only by identity left the barrier record
                // reporting it UNRESOLVED (device logs: FEEDBACK=19 resolved, "UNRESOLVED 19/80").
                if (tally) tally->retired++;
            }
            return false;
        };
        bool feedbackBack = false;
        for (int i = 0; i < 50 && !feedbackBack; i++) {
            if (reapFeedbackStep()) feedbackBack = true;
            else usleep(100);
        }
        // B27-A2: 50 attempts without the URB are not the URB. See retireUnreturnedFeedbackUrb.
        if (!feedbackBack) retireUnreturnedFeedbackUrb(ctx, reapFeedbackStep, true);
        ctx->feedbackInFlight = false;
    }

    // Phase 1: try to reap naturally (URBs that completed normally). Skipped when the
    // caller needs the queued audio gone rather than played — a natural reap of an
    // in-flight AUDIO urb means its 1 ms of PCM just reached the DAC.
    while (!discardImmediately && ctx->urbsInFlight > 0) {
        const struct usbdevfs_urb *oldest = ctx->ring[ctx->reapIdx].urb;
        int result = reapOldestUrb(ctx, 500, ReapSite::BACKPRESSURE);
        if (result == 0) {
            urbDrainResolve(&pending, oldest);
            drained++;
            if (tally) tally->retired++;
        } else {
            break;
        }
    }

    if (ctx->urbsInFlight > 0) {
        LOGW("drainAllUrbs: %d/%d reaped naturally, discarding remaining %d",
             drained, initialCount, ctx->urbsInFlight);

        // Phase 2: DISCARD all remaining URBs first
        int toDiscard = ctx->urbsInFlight;
        for (int i = 0; i < toDiscard; i++) {
            int slotIdx = (ctx->reapIdx + i) % ctx->plan.ringUrbs;
            // Already returned while the feedback URB was drained: nothing left to cancel.
            bool stillPending = false;
            for (int p = 0; p < pending.count; p++) {
                if (pending.pending[p] == ctx->ring[slotIdx].urb) { stillPending = true; break; }
            }
            if (!stillPending) continue;
            ctx->discardsRequested.fetch_add(1, std::memory_order_relaxed);
            if (tally) tally->requested++;
            const int discardRc = ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->ring[slotIdx].urb);
            {
                // Secondary evidence only: attached to the target, never used to resolve it.
                std::lock_guard<std::mutex> lock(ctx->diagMutex);
                for (int ti = 0; ti < ctx->barrier.count; ti++) {
                    if (ctx->barrier.targets[ti].slot == slotIdx) {
                        ctx->barrier.targets[ti].discardResult = discardRc;
                        ctx->barrier.targets[ti].discardErrno = (discardRc == 0) ? 0 : errno;
                        break;
                    }
                }
            }
            if (discardRc == 0) {
                ctx->discardsSucceeded.fetch_add(1, std::memory_order_relaxed);
                if (tally) tally->ok++;
            } else if (errno == EINVAL || errno == ENODATA || errno == ENOENT) {
                // usbfs answers EINVAL when the URB is no longer pending — it completed and waits to
                // be reaped (proc_unlinkurb: async_getpending() found nothing).
                // The URB had already completed before the cancel reached it. That is a normal
                // race, not a failure: counting it as one is what made 80 benign outcomes look
                // like 80 unexplained discard failures.
                if (tally) tally->alreadyComplete++;
            } else {
                ctx->discardsFailed.fetch_add(1, std::memory_order_relaxed);
                if (tally) { tally->failed++; tally->lastErrno = errno; }
                LOGW("DISCARDURB slot=%d errno=%d (%s)", slotIdx, errno, strerror(errno));
            }
        }

        // Phase 3: reap the discarded URBs' completions, bounded in time (AUD-112).
        // REAPURBNDELAY returns completions from ANY endpoint in ANY order, so each one is matched by
        // identity against the pending set rather than counted 1:1 against the discards.
        int reaped = earlyReaped;
        int foreign = 0;
        const int64_t deadlineMs = monotonicMs() + URB_DRAIN_COMPLETION_WAIT_MS;
        while (pending.count > 0) {
            struct usbdevfs_urb *c = nullptr;
            int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
            if (ret == 0 && c != nullptr) {
                observeReapedUrb(ctx, ReapSite::BARRIER_PHASE3, c);
                if (urbQuarantineTake(c)) {
                    free(c);
                } else if (urbDrainResolve(&pending, c)) {
                    reaped++;
                    if (tally) tally->retired++;
                    ctx->discardedThenReaped.fetch_add(1, std::memory_order_relaxed);
                } else {
                    foreign++;
                }
            } else if (ret < 0 && errno != EAGAIN) {
                LOGE("drainAllUrbs: reap error errno=%d", errno);
                break;
            } else if (monotonicMs() >= deadlineMs) {
                break;
            } else {
                usleep(1000);  // 1ms
            }
        }

        // Still missing after the bounded wait: the kernel may complete them later and will write
        // their status into these exact structs when they are reaped, so the structs are parked in
        // the quarantine and each slot gets a fresh one. Nothing kernel-owned is ever resubmitted.
        // (The data buffer can be reused: usbfs copies OUT data into its own buffer at submit.)
        int quarantined = 0;
        for (int slot = 0; slot < ctx->plan.ringUrbs && pending.count > 0; slot++) {
            struct usbdevfs_urb *old = ctx->ring[slot].urb;
            bool missing = false;
            for (int p = 0; p < pending.count; p++) {
                if (pending.pending[p] == old) { missing = true; break; }
            }
            if (!missing) continue;
            auto *fresh = (struct usbdevfs_urb *)calloc(1, ringUrbStructSize(ctx));
            if (fresh == nullptr || !urbQuarantineAdd(old)) {
                // Cannot park it: fall back to the old unbounded-ish wait for this one alone.
                bool back = false;
                for (int attempt = 0; attempt < 500 && !back; attempt++) {
                    struct usbdevfs_urb *c = nullptr;
                    if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c) == 0 && c != nullptr) {
                        observeReapedUrb(ctx, ReapSite::BARRIER_PHASE3, c);
                        if (urbQuarantineTake(c)) free(c);
                        else if (urbDrainResolve(&pending, c)) reaped++;
                        if (c == old) back = true;
                    } else {
                        usleep(1000);
                    }
                }
                if (back) {
                    free(fresh);
                    continue;
                }
                // B27-A2 (review R-02): 500 attempts without the URB are not the URB. It was left in its slot, and the slot's URB is
                // freed with the ring, so a URB the kernel still held was freed. Leaked on purpose instead, behind a spare block
                // (the one allocated above, whose quarantine slot never came): a leak is a few hundred bytes, a free of a URB the
                // kernel holds is a write into freed memory.
                if (fresh != nullptr) {
                    LOGE("drainAllUrbs: ring URB %p (slot %d) never came back and could not be parked; leaked", (void *)old, slot);
                    ctx->ring[slot].urb = fresh;
                } else {
                    // No memory for a spare either: this ring cannot be reused or freed. Leak all of it and stop the stream.
                    LOGE("drainAllUrbs: ring URB %p (slot %d) never came back and there is no memory for a spare; ring leaked", (void *)old, slot);
                    ctx->running.store(false);
                    ctx->ringAllocated = false;
                }
                continue;
            }
            urbDrainResolve(&pending, old);
            ctx->ring[slot].urb = fresh;
            quarantined++;
        }
        LOGI("drainAllUrbs: discarded %d, reaped %d completions (%d early), foreign %d, "
             "quarantined %d (total parked %d)",
             toDiscard, reaped, earlyReaped, foreign, quarantined, urbQuarantineCount());
        drained += reaped;

        // Phase 4: Flush any remaining stale completions (from previous
        // sessions' leaked feedback URBs, etc.)
        for (int i = 0; i < 10; i++) {
            struct usbdevfs_urb *c = nullptr;
            if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c) == 0 && c != nullptr) {
                observeReapedUrb(ctx, ReapSite::BARRIER_PHASE4, c);
                if (urbQuarantineTake(c)) free(c);
                LOGW("drainAllUrbs: flushed stale completion %p", c);
                drained++;
            } else {
                break;
            }
        }
    }

    // NOTE: `urbsInFlight` is NOT a resolution count here. Phase 3 reaps with raw REAPURBNDELAY and
    // does not decrement it, so reading it at this point reports every URB as outstanding even
    // when all of them were reaped. Resolution is `queued - retired`, computed by the caller from
    // the tally; `reapIdx` is where the next reaper would look, which points at the leaked slots.
    if (tally) tally->unresolvedFrom = ctx->reapIdx;

    // Reset ring to clean state. This is a forced reset, so the post-barrier `reusable` derived
    // from it is a constant by construction — the resolution count is the measurement, not this.
    ctx->urbsInFlight = 0;
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;

    LOGI("drainAllUrbs: drained %d/%d, ring reset", drained, initialCount);
    return drained;
}

/**
 * One coherent record per barrier invocation.
 *
 * Every field is local to this call — `barrierSeq` identifies it, and the counts come from the
 * tally filled by `drainAllUrbs`, not from process-lifetime totals. Cumulative counters remain for
 * long-term diagnostics, but they cannot prove a single barrier did anything: a run of no-op
 * barriers leaves them unchanged, which is exactly how twenty non-events read as twenty successes.
 */
struct BarrierRecord {
    uint64_t seq = 0;
    bool valid = false;
    int beforeReusable = 0, beforeInFlight = 0, beforeDiscardPending = 0;
    int requested = 0, discardOk = 0, alreadyComplete = 0, discardFail = 0, lastErrno = 0;
    int retired = 0;
    int unresolved = 0, unresolvedFrom = -1;
    bool feedbackRestored = false;
    int afterReusable = 0, afterInFlight = 0, afterDiscardPending = 0;
};

static std::atomic<uint64_t> g_barrierSeq{0};
static BarrierRecord g_lastBarrier;

/** Cancelled-but-not-yet-retired, from the cumulative counters. 0 at steady state. */
static long long discardPendingOf(const UsbAudioContext *ctx) {
    const long long retired =
        (long long)(ctx->urbReaps.load() + ctx->discardedThenReaped.load());
    return (long long)ctx->discardsRequested.load() - retired;
}

void discardQueuedPcm(UsbAudioContext *ctx) {
    if (!ctx) return;

    BarrierRecord rec;
    rec.seq = g_barrierSeq.fetch_add(1) + 1;
    rec.valid = true;

    // Freeze the exact target set BEFORE anything is discarded. Identity is copied by value, so the
    // snapshot stays historically correct even after these slots are reused: slot 7 / generation 143
    // remains slot 7 / generation 143 even once slot 7 becomes generation 144.
    {
        std::lock_guard<std::mutex> lock(ctx->diagMutex);
        BarrierSnapshot &b = ctx->barrier;
        b = BarrierSnapshot();
        b.id = rec.seq;
        b.valid = true;
        int idx = 0;
        for (int i = 0; i < ctx->urbsInFlight && idx < USB_AUDIO_MAX_BARRIER_TARGETS; i++) {
            const int slotIdx = (ctx->reapIdx + i) % ctx->plan.ringUrbs;
            BarrierTarget &t = b.targets[idx++];
            t.urbAddress = (uint64_t)(uintptr_t)ctx->ring[slotIdx].urb;
            t.slot = slotIdx;
            t.generation = ctx->cookies[slotIdx].generation.load();
            t.endpoint = ctx->cookies[slotIdx].endpoint;
        }
        b.count = idx;
        LOGI("BARRIER_TARGETS|seq=%llu frozen=%d", (unsigned long long)rec.seq, b.count);
    }
    rec.beforeReusable = ctx->plan.ringUrbs - ctx->urbsInFlight;
    rec.beforeInFlight = ctx->urbsInFlight;
    rec.beforeDiscardPending = (int)discardPendingOf(ctx);

    const int queued = ctx->urbsInFlight;

    // Straight to discard: every queued URB holds pre-seek PCM, and reaping one naturally means
    // its millisecond of audio just reached the DAC.
    DiscardTally tally;
    const int drained = drainAllUrbs(ctx, true, &tally);

    // The packetisation phase and the partial frame belonged to the old position. Carrying either
    // across the seek would emit a fragment of pre-seek audio at the new position, which is exactly
    // the artefact this barrier exists to remove.
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;

    // drainAllUrbs discards the feedback URB too, and it is the only thing keeping calibratedFpmf
    // tracking the DAC's real clock. Without this the stream would run on the nominal rate and
    // drift into periodic pops after every seek.
    bool feedbackRestored = false;
    if (ctx->running.load() && ctx->endpointFeedback > 0) {
        feedbackRestored = submitFeedbackUrb(ctx);
        if (feedbackRestored) ctx->feedbackRestores.fetch_add(1, std::memory_order_relaxed);
    }

    rec.requested = tally.requested;
    rec.discardOk = tally.ok;
    rec.alreadyComplete = tally.alreadyComplete;
    rec.discardFail = tally.failed;
    rec.lastErrno = tally.lastErrno;
    rec.retired = tally.retired;
    // SUBMITTED -> (DISCARD_ACCEPTED | DISCARD_EINVAL) -> REAPED. Anything not retired is
    // unresolved, whichever way its cancellation went.
    rec.unresolved = queued - tally.retired;
    rec.unresolvedFrom = tally.unresolvedFrom;
    rec.feedbackRestored = feedbackRestored;
    rec.afterReusable = ctx->plan.ringUrbs - ctx->urbsInFlight;
    rec.afterInFlight = ctx->urbsInFlight;
    rec.afterDiscardPending = (int)discardPendingOf(ctx);
    g_lastBarrier = rec;

    LOGI("BARRIER|seq=%llu before[reusable=%d inFlight=%d pending=%d] "
         "op[requested=%d ok=%d alreadyComplete=%d fail=%d errno=%d] "
         "recovery[retired=%d feedback=%s] "
         "resolve[unresolved=%d from=%d] "
         "after[reusable=%d inFlight=%d pending=%d] queued=%d drained=%d",
         (unsigned long long)rec.seq,
         rec.beforeReusable, rec.beforeInFlight, rec.beforeDiscardPending,
         rec.requested, rec.discardOk, rec.alreadyComplete, rec.discardFail, rec.lastErrno,
         rec.retired, rec.feedbackRestored ? "restored" : "no",
         rec.unresolved, rec.unresolvedFrom,
         rec.afterReusable, rec.afterInFlight, rec.afterDiscardPending,
         queued, drained);

    // Final identity result for this barrier, dumped before the snapshot stops participating.
    {
        std::lock_guard<std::mutex> lock(ctx->diagMutex);
        BarrierSnapshot &b = ctx->barrier;
        LOGI("BARRIER_IDENTITY|seq=%llu targetCount=%d resolved=%d residual=%d "
             "perSite[BACKPRESSURE=%d FEEDBACK=%d PHASE3=%d PHASE4=%d READ_FEEDBACK=%d] "
             "nonTarget=%lld dup=%d staleGen=%d wrongEp=%d swallowed=%d",
             (unsigned long long)rec.seq, b.count, b.resolvedCount,
             b.count - b.resolvedCount,
             b.perSiteUnique[(int)ReapSite::BACKPRESSURE],
             b.perSiteUnique[(int)ReapSite::BARRIER_FEEDBACK],
             b.perSiteUnique[(int)ReapSite::BARRIER_PHASE3],
             b.perSiteUnique[(int)ReapSite::BARRIER_PHASE4],
             b.perSiteUnique[(int)ReapSite::READ_FEEDBACK],
             b.nonTargetCompletions, b.duplicates, b.staleGeneration, b.wrongEndpoint,
             b.swallowedCount);
        for (int i = 0; i < b.count; i++) {
            BarrierTarget &t = b.targets[i];
            if (t.resolved) continue;
            LOGE("BARRIER_RESIDUAL|seq=%llu slot=%d gen=%llu urb=%p ep=%d discardRc=%d errno=%d",
                 (unsigned long long)rec.seq, t.slot, (unsigned long long)t.generation,
                 (void *)(uintptr_t)t.urbAddress, t.endpoint, t.discardResult, t.discardErrno);
        }
        // Close the window. Post-barrier reaps belong to ordinary streaming, not to this barrier,
        // and comparing them against a finished snapshot produced a flood of false stale-generation
        // reports that would have masked a real one.
        b.valid = false;
    }

    if (rec.unresolved > 0) {
        LOGE("BARRIER|seq=%llu UNRESOLVED %d/%d — URBs did not reach REAPED; real problem",
             (unsigned long long)rec.seq, rec.unresolved, queued);
    } else if (queued != drained) {
        LOGW("BARRIER|seq=%llu queued != drained (%d vs %d) — check URB accounting",
             (unsigned long long)rec.seq, queued, drained);
    } else {
        LOGI("BARRIER|seq=%llu RESOLVED %d/%d all URBs reached REAPED",
             (unsigned long long)rec.seq, drained, queued);
    }
}

/** The most recent barrier record, for the host diagnostics. */
static const BarrierRecord &lastBarrierRecord() { return g_lastBarrier; }

// ── JNI entry points ────────────────────────────────────────────────

// Forward declaration (defined after integer padding functions, shared with host transport tests)
bool submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes);

/**
 * B46-A4: at stream start, the plan's numbers in one line, and, when the device's timing is not the one the output drives, one
 * TRANSPORT_ASSUMPTION line per assumption. Nothing is refused: the output has always planned every device as high speed with
 * eight 125 us microframes per URB and a four-byte Q16.16 feedback, and a DAC that plays today must still play. The line is the
 * evidence for the step that adds those timings (full speed, another bInterval, a 3-byte 10.14 feedback).
 */
static void logTransportPlan(const UsbAudioContext *ctx) {
    char text[480];
    usbTransportPlanDescribe(ctx->plan, text, sizeof(text));
    LOGI("Start: %s speed=%d bInterval=%d", text, ctx->planEndpoint.speed, ctx->planEndpoint.bInterval);
    const int a = ctx->plan.assumptions;
    if (a & USB_PLAN_ASSUME_SERVICE_INTERVAL) {
        LOGW("TRANSPORT_ASSUMPTION speed=%d is not high speed: planned as %d us microframes, %d per URB (a full-speed service "
             "interval is 1 ms); the packets of this stream may not match the device's schedule",
             ctx->planEndpoint.speed, ctx->plan.serviceIntervalUs, ctx->plan.packetsPerUrb);
    }
    if (a & USB_PLAN_ASSUME_BINTERVAL) {
        LOGW("TRANSPORT_ASSUMPTION data endpoint bInterval=%d: planned as a packet every microframe (bInterval 1)", ctx->planEndpoint.bInterval);
    }
    if (a & USB_PLAN_ASSUME_FEEDBACK_WIDTH) {
        LOGW("TRANSPORT_ASSUMPTION feedback packet of %d bytes: read as one four-byte Q16.16 value", ctx->planEndpoint.feedbackBytes);
    }
    if (a & USB_PLAN_ASSUME_SPEED_UNKNOWN) {
        LOGW("TRANSPORT_ASSUMPTION bus speed unknown (USBDEVFS_GET_SPEED failed): planned as high speed");
    }
}

void usbStreamTransportPlan(const UsbAudioContext *ctx, int prerollMs, int64_t out[USB_PLAN_FIELD_COUNT]) {
    if (prerollMs <= 0) {
        usbTransportPlanToArray(ctx->plan, out);
        return;
    }
    UsbTransportPolicy policy = usbTransportDefaultPolicy();
    policy.prerollMs = prerollMs;
    usbTransportPlanToArray(makeUsbTransportPlan(ctx->planFormat, ctx->planEndpoint, policy), out);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioCreate(
        JNIEnv *, jobject, jint fd, jint ifId, jint epOut, jint epFb,
        jint rate, jint ch, jint bits, jint maxPkt, jint bInterval, jint feedbackPacketBytes) {
    LOGI("Create: fd=%d ep=0x%02x rate=%d ch=%d bits=%d maxPkt=%d bInterval=%d feedbackPkt=%d",
         fd, epOut, rate, ch, bits, maxPkt, bInterval, feedbackPacketBytes);
    auto *ctx = new(std::nothrow) UsbAudioContext();
    if (!ctx) return 0;
    ctx->fd = fd;
    ctx->interfaceId = ifId;
    ctx->endpointOut = epOut;
    ctx->endpointFeedback = epFb;
    ctx->sampleRate = rate;
    ctx->channelCount = ch;
    ctx->bitDepth = bits;
    ctx->ditherState = 0x9E3779B9u ^ (uint32_t)rate;
    ctx->bytesPerSample = bits / 8;
    ctx->maxPacketSize = maxPkt;

    // B46-A2: every transport number of this stream comes from the plan. The refusals are the ones the output has always made
    // (a packet that does not fit maxPacket, a container that is not a sample size), now said up front instead of as silence at the
    // first write; a device whose timing the output does not drive yet is still planned as high speed (A4, at Start).
    UsbTransportFormat planFormat = {};
    planFormat.sampleRate = rate;
    planFormat.channels = ch;
    planFormat.containerBytes = bits % 8 == 0 ? bits / 8 : 0;   // a container that is not whole bytes is invalid, not rounded down
    UsbTransportEndpoint planEndpoint = {};
    planEndpoint.speed = readUsbSpeed(fd);
    planEndpoint.maxPacketBytes = maxPkt;
    planEndpoint.bInterval = bInterval;
    planEndpoint.hasFeedback = epFb > 0;
    planEndpoint.feedbackBytes = feedbackPacketBytes;
    ctx->planFormat = planFormat;
    ctx->planEndpoint = planEndpoint;
    ctx->plan = makeUsbTransportPlan(planFormat, planEndpoint, usbTransportDefaultPolicy());
    if (ctx->plan.status != USB_PLAN_OK) {
        LOGE("Create refused: the transport plan says %s (rate=%d ch=%d bits=%d maxPkt=%d)",
             usbTransportReasonName(ctx->plan.reason), rate, ch, bits, maxPkt);
        delete ctx;
        return 0;
    }
    ctx->bytesPerFrame = ctx->plan.bytesPerFrame;
    ctx->running.store(false);
    ctx->transferBuffer = nullptr;
    ctx->transferBufferCapacity = 0;
    ctx->framesWritten = 0;
    ctx->urbSubmits.store(0);
    ctx->urbReaps.store(0);
    ctx->discardsRequested.store(0);
    ctx->discardsSucceeded.store(0);
    ctx->discardsFailed.store(0);
    ctx->discardedThenReaped.store(0);
    ctx->feedbackRestores.store(0);
    ctx->interfaceClaimed = true;
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;
    ctx->urbsInFlight = 0;
    ctx->playOutBeforeDrain = false;
    ctx->lastSubmitMs = 0;
    ctx->primeOwed = false;
    ctx->primeWhy = "start";
    ctx->startGuardPending = false;
    ctx->startGuardUntilMs = 0;
    usbOutputGapWatchReset(&ctx->outputGap);
    ctx->outputGapReturnUs = 0;
    ctx->outputGapPlayOutExempted = false;
    ctx->schedstatFd = -1;
    ctx->schedstatTid = 0;
    ctx->schedBaseline = UsbSchedBaseline{};
    ctx->ringAllocated = false;
    ctx->frameAccumulator = 0.0;
    ctx->calibratedFpmf = ctx->plan.nominalFramesPerInterval;
    usbFeedbackWatchReset(&ctx->feedbackWatch);
    ctx->residualBytes = 0;
    memset(ctx->residualBuffer, 0, sizeof(ctx->residualBuffer));
    memset(ctx->ring, 0, sizeof(ctx->ring));
    ctx->feedbackUrb = nullptr;
    ctx->feedbackInFlight = false;

    if (!allocRing(ctx)) {
        delete ctx;
        return 0;
    }

    if (!allocFeedbackUrb(ctx)) {
        freeRing(ctx);
        delete ctx;
        return 0;
    }
    noteStreamFacts(ctx);  // B27-A5

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeNoteDataAltSet(JNIEnv *, jclass, jint fd) {
    usbNoteDataAltSet(fd);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioSetAltSetting(
        JNIEnv *, jobject, jlong h, jint alt) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    struct usbdevfs_setinterface si = {};
    si.interface = (unsigned)ctx->interfaceId;
    si.altsetting = (unsigned)alt;
    int r = ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &si);
    LOGI("setAlt(%d,%d): ret=%d errno=%d", ctx->interfaceId, alt, r, errno);
    if (r >= 0 && alt > 0) usbNoteDataAltSet(ctx->fd);
    return r >= 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioSetSampleRate(
        JNIEnv *, jobject, jlong h, jint rate, jint csId) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    // The plan is made for a rate: a stream whose rate changes gets the plan of the new one, or keeps both as they were.
    UsbTransportFormat format = ctx->planFormat;
    format.sampleRate = rate;
    const UsbTransportPlan replanned = makeUsbTransportPlan(format, ctx->planEndpoint, usbTransportDefaultPolicy());
    if (replanned.status != USB_PLAN_OK) {
        LOGE("setSampleRate(%d) refused: the transport plan says %s", rate, usbTransportReasonName(replanned.reason));
        return JNI_FALSE;
    }
    ctx->planFormat = format;
    ctx->plan = replanned;
    ctx->sampleRate = rate;
    return JNI_TRUE;
}

/** B46-A4: the plan this stream runs with, as the flat array of UsbPlanField (usb-pcm-packetizer.h). */
JNIEXPORT jlongArray JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetTransportPlan(
        JNIEnv *env, jobject, jlong h, jint prerollMs) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    int64_t fields[USB_PLAN_FIELD_COUNT] = {};
    if (ctx) usbStreamTransportPlan(ctx, prerollMs, fields);
    jlongArray out = env->NewLongArray(USB_PLAN_FIELD_COUNT);
    if (out) {
        jlong copy[USB_PLAN_FIELD_COUNT];
        for (int i = 0; i < USB_PLAN_FIELD_COUNT; i++) copy[i] = (jlong)fields[i];
        env->SetLongArrayRegion(out, 0, USB_PLAN_FIELD_COUNT, copy);
    }
    return out;
}

/**
 * The producer is about to idle on purpose (the ExoPlayer pipeline paused): the ring will run dry and
 * the first URB after the resume must not be counted as a starvation (AUD-140). Idle is announced by the pipeline writer.
 */
JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeMarkIdle(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->lastSubmitMs = 0;
    noteGesture(ctx, USB_POLICY_IDLE, "announced");
}

/**
 * The stream's feedback tracking from a quiet ring: the one-shot read of the DAC's rate, the continuous feedback URB. Shared by the start of a stream and by
 * the re-arm after a recovery's interface restart (B47-A5: the same code, not a second path). Returns what the read ended as, for the second ring.
 */
static const char *startFeedbackTracking(UsbAudioContext *ctx) {
    // Initial calibration from the DAC's async feedback endpoint.
    // Pipeline is empty here, so REAPURBNDELAY can only return the feedback URB.
    double nominalFpmf = ctx->plan.nominalFramesPerInterval;
    ctx->calibratedFpmf = nominalFpmf;
    ctx->readingFpmf = nominalFpmf;
    ctx->liveFpmf = nominalFpmf;
    ctx->continuousFeedbackSeen = false;
    ctx->acceptedAtMs = -1;
    ctx->dataAltSetMs = dataAltSetMs(ctx->fd);
    ctx->feedbackOutsideWindow = false;
    ctx->feedbackWindowLogPending = false;
    ctx->outsideSinceMs = 0;
    ctx->windowLogMs[0] = -1;
    ctx->windowLogMs[1] = -1;
    ctx->refusing = false;
    const char *startWhat = "no-feedback-endpoint";   // B41-fix1: how the start's feedback read went, for the ring's gesture line

    if (ctx->endpointFeedback > 0) {
        enum UsbFeedbackPacket startVerdict = USB_FEEDBACK_PACKET_OK;
        bool received = false;
        double fb = readFeedback(ctx, ctx->endpointFeedback, &startVerdict, &received);
        startWhat = received ? "initial-reading" : startVerdict != USB_FEEDBACK_PACKET_OK ? usbFeedbackPacketName(startVerdict) : "no-answer";
        if (received) {
            // The same window as every later reading (B12a-2: nothing is applied blindly): a reading outside
            // +-5 % leaves the nominal rate in charge until the first plausible one.
            // Observed already, but startup calibration keeps the existing follower rule.
            ctx->calibratedFpmf = usbFeedbackApply(nominalFpmf, fb, nominalFpmf);
            LOGI("Start: initial feedback=%.4f fpmf (%.1f Hz), nominal=%.4f (%.1f Hz)%s",
                 fb, fb * ctx->plan.intervalsPerSecond, nominalFpmf, nominalFpmf * ctx->plan.intervalsPerSecond,
                 ctx->calibratedFpmf == fb ? "" : " — outside +-5 %, not applied");
        } else {
            if (startVerdict != USB_FEEDBACK_PACKET_OK) {
                LOGW("Start: the feedback read was not a reading (%s), using nominal %.4f fpmf", usbFeedbackPacketName(startVerdict), nominalFpmf);
            } else {
                LOGW("Start: feedback not responding, using nominal %.4f fpmf", nominalFpmf);
            }
        }

        // Start continuous feedback: submit a feedback URB that will be
        // automatically recycled in the reap loop during streaming.
        // The host controller schedules the feedback endpoint once per
        // microframe (~1 ms effective interval).
        submitFeedbackUrb(ctx);
    }
    return startWhat;
}

/**
 * B48-fix1: the streaming interface (re)started: whatever the writer submits first must find the ring at the plan's lead (`primeOwed`), and the packetizer is held at the nominal
 * rate for the plan's guard from the first URB (`startGuardPending`). Logged as `Start: feedback start guard=500 ms` (or `off`: the switch, `follow`, or no feedback).
 */
static void armStartOfAudio(UsbAudioContext *ctx, const char *why) {
    ctx->primeOwed = true;
    ctx->primeWhy = why;
    ctx->startGuardPending = true;
    ctx->startGuardUntilMs = 0;
    const bool guarded = usbFeedbackStartGuardEnabled() && usbFeedbackPolicy() != USB_FEEDBACK_FOLLOW && ctx->plan.feedbackStartGuardMs > 0;
    if (guarded) {
        LOGI("Start: feedback start guard=%d ms (%s)", ctx->plan.feedbackStartGuardMs, why);
    } else {
        LOGI("Start: feedback start guard=off (%s)", why);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(
        JNIEnv *, jobject, jlong h, jint previousRate) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    ctx->running.store(true);
    // A started stream has seen no feedback yet: an episode of the one before is over (B10).
    endFeedbackWatch(ctx);
    ctx->feedbackWatch.rateChangeStart = previousRate > 0 && previousRate != ctx->sampleRate;
    ctx->feedbackWatch.streamStartMs = monotonicMs();
    if (ctx->feedbackWatch.rateChangeStart) {
        LOGI("Start: rate change %d → %d, stuck hold %d ms for the first %d ms",
             previousRate, ctx->sampleRate, USB_FEEDBACK_RATE_CHANGE_STUCK_MS, USB_FEEDBACK_RATE_CHANGE_WINDOW_MS);
    }
    usbDacRecoveryPolicyReset(&ctx->dacPolicy);   // B47-A5: a new stream starts with all its recovery steps unused
    ctx->framesWritten = 0;
    ctx->lastSubmitMs = 0;
    usbOutputGapWatchReset(&ctx->outputGap);
    ctx->outputGapReturnUs = 0;
    ctx->outputGapPlayOutExempted = false;
    ctx->schedBaseline = UsbSchedBaseline{};
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;
    ctx->urbsInFlight = 0;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;
    {
        // A started stream owes no gesture (the buffers it holds are kept: they are reused).
        PipelineGesture &g = ctx->gesture;
        ctx->pauseRequested.store(false, std::memory_order_relaxed);
        g.pauseAppliedInBlock = false;
        g.hasAudio = false;
        g.endsInFade = false;
        g.fadeOutArmed = false;
        g.fadeInRemaining = 0;
        g.pendingFrames = 0;
    }

    const char *startWhat = startFeedbackTracking(ctx);
    armStartOfAudio(ctx, "stream");

    noteGesture(ctx, USB_POLICY_STREAM_START, startWhat);
    LOGI("Start: rate=%d ch=%d bits=%d ring=%d slots×%dpkt fpmf=%.4f feedback=%s",
         ctx->sampleRate, ctx->channelCount, ctx->bitDepth,
         ctx->plan.ringUrbs, ctx->plan.packetsPerUrb,
         ctx->calibratedFpmf,
         ctx->feedbackInFlight ? "continuous" : "one-shot");
    logTransportPlan(ctx);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWrite(
        JNIEnv *env, jobject, jlong h, jfloatArray pcm) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !ctx->running.load()) return;

    jint totalSamples = env->GetArrayLength(pcm);
    if (totalSamples <= 0) return;
    int totalFrames = totalSamples / ctx->channelCount;
    int totalBytes = totalSamples * ctx->bytesPerSample;

    // Resize transfer buffer if needed
    if (!ctx->transferBuffer || ctx->transferBufferCapacity < totalBytes) {
        free(ctx->transferBuffer);
        ctx->transferBuffer = (uint8_t *)malloc(totalBytes);
        ctx->transferBufferCapacity = totalBytes;
    }

    struct timespec writeStart, writeEnd;
    clock_gettime(CLOCK_MONOTONIC, &writeStart);
    static long writeCallCount = 0;
    writeCallCount++;

    // Convert float PCM to target bit depth
    jfloat *f = env->GetFloatArrayElements(pcm, nullptr);
    if (!f) return;
    if (!usbPcmFromFloat(f, ctx->transferBuffer, totalSamples, ctx->bitDepth, &ctx->ditherState)) {
        env->ReleaseFloatArrayElements(pcm, f, JNI_ABORT);
        return;
    }
    env->ReleaseFloatArrayElements(pcm, f, JNI_ABORT);

    // Submit converted PCM to USB pipeline (shared with raw path). A pause or a seek in progress ramps
    // it on the way (B9); with none owed this is the plain submit, and it clears the "faded cut"
    // mark and counts the frames (AUD-143).
    submitPipelinePcm(ctx, ctx->transferBuffer, totalBytes, totalFrames);

    clock_gettime(CLOCK_MONOTONIC, &writeEnd);
    long writeUs = (writeEnd.tv_sec - writeStart.tv_sec) * 1000000L +
                   (writeEnd.tv_nsec - writeStart.tv_nsec) / 1000L;
    // Log every call that took > 10ms, or every 100th call
    if (writeUs > 10000 || writeCallCount % 100 == 0) {
        LOGI("nativeWrite #%ld: %d samples, %ldus (%.1fms), inflight=%d",
             writeCallCount, totalSamples, writeUs, writeUs / 1000.0, ctx->urbsInFlight);
    }

    // Periodic logging (~once per second)
    if (ctx->framesWritten % ctx->sampleRate < (int64_t)totalFrames) {
        LOGI("Write: %lld frames (~%.0f sec) inflight=%d fpmf=%.4f (%.1f Hz) sending=%.4f",
             (long long)ctx->framesWritten, (double)ctx->framesWritten/ctx->sampleRate,
             ctx->urbsInFlight, ctx->readingFpmf, ctx->readingFpmf * ctx->plan.intervalsPerSecond,
             g_fbSendingFpmf.load(std::memory_order_relaxed));
    }
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->running.store(false);
    LOGI("Stop: %lld frames, %d URBs in flight",
         (long long)ctx->framesWritten, ctx->urbsInFlight);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeFlush(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;
    ctx->framesWritten = 0;
    LOGI("Flush: frameAccumulator, residual, and framesWritten reset");
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    const bool submitted = submitFinalPcmResidual(ctx);
    // AUD-143: the pipeline's natural end: what is still queued in the
    // ring (~80 ms) is the end of the track, and a rate change that follows lets it play out before
    // the teardown discards it. Cleared by the next write (below).
    if (submitted) ctx->playOutBeforeDrain = true;
    return submitted ? JNI_TRUE : JNI_FALSE;
}

/**
 * Lets a faded-out tail finish playing before a teardown discards it (AUD-123): discarding it
 * immediately would cut the fade itself. Bounded to a little more than the ring's depth (70 ms more than the plan's ring).
 */
static void playOutFadedTail(UsbAudioContext *ctx) {
    const int64_t startMs = monotonicMs();
    // B47-A2: "a little more than the ring's depth": the depth is the plan's (it was a fixed 150 ms for an 80 ms ring).
    const int64_t ringMs = (int64_t)ctx->plan.ringUrbs * ctx->plan.packetsPerUrb * ctx->plan.serviceIntervalUs / 1000;
    const int64_t boundMs = ringMs + 70;
    while (usbQueuedFrames(ctx) > 0 && monotonicMs() - startMs < boundMs) {
        if (!usbWaitForProgress(ctx)) usleep(1000);
    }
    ctx->playOutBeforeDrain = false;
    LOGI("Drain: faded tail played out in %lld ms", (long long)(monotonicMs() - startMs));
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return 0;
    ctx->running.store(false);
    if (ctx->playOutBeforeDrain) playOutFadedTail(ctx);
    // Discard immediately (AUD-112): every caller is tearing the stream down — a track or rate
    // change, a dead stream, a player release — and the in-flight audio belongs to what is being
    // left. The natural reap waited up to 500 ms per URB (~1.8 s measured on a rate change).
    return drainAllUrbs(ctx, true, nullptr);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->running.store(false);

    if (ctx->urbsInFlight > 0) {
        drainAllUrbs(ctx, false, nullptr);
    }

    endFeedbackWatch(ctx);
    freeRing(ctx);
    releaseFeedbackUrb(ctx);
    free(ctx->transferBuffer);
    free(ctx->gesture.pending);
    free(ctx->gesture.silence);
    ctx->gesture.pending = nullptr;
    ctx->gesture.silence = nullptr;
    if (ctx->schedstatFd >= 0) close(ctx->schedstatFd);
    LOGI("Destroy: %lld frames total", (long long)ctx->framesWritten);
    delete ctx;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeIsRunning(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    return ctx->running.load() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Compact URB ownership reading: total, reusable, inFlight, discReq, discOk, discFail, retired,
 * feedbackRestores. Read by the host diagnostics; costs nothing when nobody asks.
 */
/**
 * Identity-based barrier verdict: frozen targets, resolution counts, per-site attribution, the
 * explicit swallowed list, and every unresolved exact target.
 *
 * Resolution here means "an exact (urbAddress, slot, generation) was observed at SOME reap site" —
 * not that this barrier's own loop reaped it, and never a DISCARD result.
 */
JNIEXPORT jstring JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetBarrierIdentityDiag(
        JNIEnv *env, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return env->NewStringUTF("identity=none");
    std::lock_guard<std::mutex> lock(ctx->diagMutex);
    BarrierSnapshot &b = ctx->barrier;
    if (!b.valid) return env->NewStringUTF("identity=no-barrier");

    std::string out = "barrierId=" + std::to_string(b.id) +
        " targetCount=" + std::to_string(b.count) +
        " resolved=" + std::to_string(b.resolvedCount) +
        " residual=" + std::to_string(b.count - b.resolvedCount);
    out += " perSite[";
    for (int i = 0; i < (int)ReapSite::COUNT; i++) {
        out += std::string(i ? " " : "") + reapSiteName((ReapSite)i) + "=" +
               std::to_string(b.perSiteUnique[i]);
    }
    out += "]";
    out += " nonTarget=" + std::to_string(b.nonTargetCompletions) +
           " duplicates=" + std::to_string(b.duplicates) +
           " staleGen=" + std::to_string(b.staleGeneration) +
           " unknown=" + std::to_string(b.unknown) +
           " wrongEndpoint=" + std::to_string(b.wrongEndpoint);

    for (int i = 0; i < b.count; i++) {
        BarrierTarget &t = b.targets[i];
        // The two suspected swallow sites, and every still-unresolved target, listed by identity.
        const bool swallowed = t.resolved &&
            (t.resolvedBy == (int)ReapSite::BARRIER_FEEDBACK ||
             t.resolvedBy == (int)ReapSite::READ_FEEDBACK);
        if (!swallowed && t.resolved) continue;
        out += "\nTARGET|" + std::string(swallowed ? "SWALLOWED" : "UNRESOLVED") +
               " slot=" + std::to_string(t.slot) +
               " gen=" + std::to_string(t.generation) +
               " urb=" + std::to_string((unsigned long long)t.urbAddress) +
               " ep=" + std::to_string(t.endpoint) +
               " discardRc=" + std::to_string(t.discardResult) +
               " errno=" + std::to_string(t.discardErrno) +
               " resolved=" + std::string(t.resolved ? "yes" : "no") +
               " by=" + (t.resolved ? reapSiteName((ReapSite)t.resolvedBy) : "none") +
               " seq=" + std::to_string((unsigned long long)t.reapSequence) +
               " status=" + std::to_string(t.completionStatus);
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetLastBarrierDiag(
        JNIEnv *env, jobject, jlong h) {
    const BarrierRecord &r = lastBarrierRecord();
    if (!r.valid) return env->NewStringUTF("barrier=none");
    char buf[320];
    snprintf(buf, sizeof(buf),
             "seq=%llu beforeReusable=%d beforeInFlight=%d requested=%d ok=%d alreadyComplete=%d "
             "fail=%d retired=%d feedback=%s afterReusable=%d afterInFlight=%d pending=%d",
             (unsigned long long)r.seq, r.beforeReusable, r.beforeInFlight,
             r.requested, r.discardOk, r.alreadyComplete, r.discardFail, r.retired,
             r.feedbackRestored ? "restored" : "no",
             r.afterReusable, r.afterInFlight, r.afterDiscardPending);
    return env->NewStringUTF(buf);
}

JNIEXPORT jstring JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetUrbDiag(
        JNIEnv *env, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return env->NewStringUTF("urb=none");
    char buf[256];
    snprintf(buf, sizeof(buf),
             "total=%d reusable=%d inFlight=%d discReq=%llu discOk=%llu discFail=%llu "
             "retired=%llu feedbackRestores=%llu",
             ctx->plan.ringUrbs, ctx->plan.ringUrbs - ctx->urbsInFlight, ctx->urbsInFlight,
             (unsigned long long)ctx->discardsRequested.load(),
             (unsigned long long)ctx->discardsSucceeded.load(),
             (unsigned long long)ctx->discardsFailed.load(),
             (unsigned long long)(ctx->urbReaps.load() + ctx->discardedThenReaped.load()),
             (unsigned long long)ctx->feedbackRestores.load());
    return env->NewStringUTF(buf);
}

/**
 * AUD-145: [mode (1 = poll, 0 = sleep), waits entered, µs spent waiting, waits that timed out, polls that
 * reported a completion and found nothing to reap]. Process totals.
 */
JNIEXPORT jlongArray JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetWaitStats(
        JNIEnv *env, jclass) {
    uint64_t stats[5];
    usbWaitStats(stats);
    jlongArray out = env->NewLongArray(5);
    if (out == nullptr) return nullptr;
    jlong values[5];
    for (int i = 0; i < 5; i++) values[i] = (jlong)stats[i];
    env->SetLongArrayRegion(out, 0, 5, values);
    return out;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeSetPollWait(
        JNIEnv *, jclass, jboolean enabled) {
    usbSetPollWait(enabled == JNI_TRUE);
}

/**
 * B10: [policy, stuck now, episodes, readings seen while stuck, ms stuck in all, longest episode ms, the
 * last episode's fpmf x 1e6, its ppm, feedback completions in all, last reading's fpmf x 1e6, nominal
 * rate Hz, fpmf the packetizer was last given x 1e6]. Process totals.
 */
JNIEXPORT jstring JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetSecondSummaries(JNIEnv *env, jclass, jlong windowMs) {
    // On demand only: the one place the summaries allocate.
    std::string text((size_t)USB_SECOND_RING_CAPACITY * 200 + 512, '\0');
    const size_t used = usbSecondSummaries((int64_t)windowMs, &text[0], text.size());
    text.resize(used);
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jlongArray JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetFeedbackStats(
        JNIEnv *env, jclass) {
    int64_t stats[USB_FEEDBACK_STATS_COUNT];
    usbFeedbackStats(stats);
    jlongArray out = env->NewLongArray(USB_FEEDBACK_STATS_COUNT);
    if (out == nullptr) return nullptr;
    jlong values[USB_FEEDBACK_STATS_COUNT];
    for (int i = 0; i < USB_FEEDBACK_STATS_COUNT; i++) values[i] = (jlong)stats[i];
    env->SetLongArrayRegion(out, 0, USB_FEEDBACK_STATS_COUNT, values);
    return out;
}

/** B10-2: 0 follow, 1 nominal, 2 clamp, 3 auto (a UsbFeedbackPolicy). Process-only. */
JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeSetFeedbackPolicy(
        JNIEnv *, jclass, jint policy) {
    usbSetFeedbackPolicy((int)policy);
}

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetFramesWritten(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return 0;
    return (jlong)ctx->framesWritten;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbReset(
        JNIEnv *, jclass, jint fd) {
    LOGI("USBDEVFS_RESET fd=%d", fd);
    int ret = ioctl(fd, USBDEVFS_RESET, 0);
    if (ret < 0) { LOGE("RESET FAILED errno=%d", errno); return ret; }
    LOGI("RESET OK, claiming interfaces...");
    struct usbdevfs_ioctl cmd = {}; cmd.ifno = 0; cmd.ioctl_code = USBDEVFS_DISCONNECT;
    ioctl(fd, USBDEVFS_IOCTL, &cmd);
    int i0 = 0; ioctl(fd, USBDEVFS_CLAIMINTERFACE, &i0);
    cmd.ifno = 1; ioctl(fd, USBDEVFS_IOCTL, &cmd);
    int i1 = 1; ioctl(fd, USBDEVFS_CLAIMINTERFACE, &i1);
    struct usbdevfs_setinterface si = {}; si.interface = 1; si.altsetting = 0;
    ioctl(fd, USBDEVFS_SETINTERFACE, &si);
    return 0;
}

// B39-A7: a bus reset with nothing claimed afterwards (unlike nativeUsbReset, which re-claims interfaces for Direct). With the interfaces
// released, usb_reset_device() unbinds and rebinds the drivers of the interfaces, so snd-usb-audio is probed again as at a plug-in. Used once,
// by the hand-back, and only when an interface still has no driver after the CONNECT.
JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbResetForRebind(
        JNIEnv *, jclass, jint fd) {
    LOGI("USBDEVFS_RESET (rebind) fd=%d", fd);
    int ret = ioctl(fd, USBDEVFS_RESET, 0);
    if (ret < 0) { LOGE("RESET (rebind) FAILED errno=%d", errno); return -errno; }
    return 0;
}

// Re-binds the kernel driver (snd-usb-audio) to one interface, the inverse of the forced claim's
// USBDEVFS_DISCONNECT — but only if the interface has no driver. On the HONOR the kernel rebinds
// snd-usb-audio by itself when an interface is released; a CONNECT on an already-bound interface
// fails with EBUSY and says nothing useful. Returns 1 when already bound, 0 after a successful
// CONNECT, negative errno on failure. Must follow USBDEVFS_RELEASEINTERFACE.
JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeReattachKernelDriverIfUnbound(
        JNIEnv *, jclass, jint fd, jint ifno) {
    struct usbdevfs_getdriver current = {};
    current.interface = ifno;
    if (ioctl(fd, USBDEVFS_GETDRIVER, &current) == 0) {
        LOGI("USBDEVFS_GETDRIVER ifno=%d already bound to %s; no CONNECT", ifno, current.driver);
        return 1;
    }
    if (errno != ENODATA) {
        LOGE("USBDEVFS_GETDRIVER ifno=%d failed errno=%d; trying CONNECT", ifno, errno);
    }
    struct usbdevfs_ioctl cmd = {};
    cmd.ifno = ifno;
    cmd.ioctl_code = USBDEVFS_CONNECT;
    cmd.data = nullptr;
    int ret = ioctl(fd, USBDEVFS_IOCTL, &cmd);
    if (ret < 0) {
        LOGE("USBDEVFS_CONNECT ifno=%d failed errno=%d", ifno, errno);
        return -errno;
    }
    LOGI("USBDEVFS_CONNECT ifno=%d ok", ifno);
    return 0;
}

// The kernel driver bound to one interface, or null when it has none (or the query fails). Used
// after a hand-back as the signal that Android's USB route exists: snd-usb-audio on both the
// control and the streaming interface means the card was created with its playback stream.
JNIEXPORT jstring JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeKernelDriverName(
        JNIEnv *env, jclass, jint fd, jint ifno) {
    struct usbdevfs_getdriver current = {};
    current.interface = ifno;
    if (ioctl(fd, USBDEVFS_GETDRIVER, &current) != 0) return nullptr;
    current.driver[sizeof(current.driver) - 1] = '\0';
    return env->NewStringUTF(current.driver);
}

} // extern "C" — pause for non-JNI writer helpers

// ── Recovery of a locked-up DAC: the two native halves around the interface restart (B47-A5) ──────

bool usbDacRecoveryBegin(UsbAudioContext *ctx) {
    if (!ctx || !ctx->running.load()) return false;
    // The pause-style fade the writer queued is at the end of the ring: whatever partial URB the packetizer still holds is the end of that fade, so it goes out first,
    // and the ring plays out (bounded) so the cut the listener hears is the fade itself. Only then is what is left discarded, with the seek barrier's own drain.
    submitFinalPcmResidual(ctx);
    if (ctx->playOutBeforeDrain) playOutFadedTail(ctx);
    const int queued = ctx->urbsInFlight;
    DiscardTally tally;
    const int drained = drainAllUrbs(ctx, true, &tally);
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;
    LOGI("DAC_RECOVERY|begin queued=%d drained=%d retired=%d inFlight=%d", queued, drained, tally.retired, ctx->urbsInFlight);
    return ctx->urbsInFlight == 0 && tally.retired >= queued;
}

bool usbDacRecoveryEnd(UsbAudioContext *ctx) {
    if (!ctx || !ctx->running.load()) return false;
    // As nativeUsbAudioStart leaves a stream, minus what a restart must keep: the frame count (Media3's position is a function of it) and the gesture state (what the
    // pause kept for the resume).
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;
    ctx->lastSubmitMs = 0;
    usbOutputGapWatchReset(&ctx->outputGap);
    ctx->outputGapReturnUs = 0;
    ctx->outputGapPlayOutExempted = false;
    ctx->schedBaseline = UsbSchedBaseline{};
    endFeedbackWatch(ctx);
    const char *startWhat = startFeedbackTracking(ctx);
    armStartOfAudio(ctx, "recovery");   // B48-fix1: the restarted interface gets the same priming and the same fresh guard as a new stream
    noteGesture(ctx, USB_POLICY_STREAM_START, startWhat);
    LOGI("DAC_RECOVERY|end feedback=%s framesWritten=%lld", ctx->feedbackInFlight ? "continuous" : "one-shot", (long long)ctx->framesWritten);
    return true;
}

// Integer padding lives in usb-pcm-convert.cpp, shared by raw and float writes.

// ── Shared URB submission logic ─────────────────────────────────────

/**
 * Submit PCM data (already in the target bit depth) to the USB pipeline.
 * Used by both nativeUsbAudioWrite (float path) and nativeUsbAudioWriteRaw.
 */
/**
 * [interruptible]: when the host has asked for a pause (`ctx->pauseRequested`), stop between two URBs, after
 * at least the first of this call, and report in [*consumedBytes] how much of [pcmData] was submitted (always a
 * whole number of frames). Nothing of the rest is saved as a residual: the caller owns it. Without it, or with
 * no request pending, this is the plain submit it always was, step for step.
 */
static bool submitPcmToUrbsImpl(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes,
                                bool interruptible, int *consumedBytes) {
    if (consumedBytes) *consumedBytes = totalBytes;
    prepareWriterThread(ctx);   // B48-fix1: before the first URB of this thread's first write, never between two URBs
    // B23-P2: where the time between two submits went (see observeOutputGap). Clock reads only; the stamp on the way
    // out covers every return below.
    if (ctx->outputGapReturnUs > 0) usbOutputGapAddBetweenCalls(&ctx->outputGap, monotonicUs() - ctx->outputGapReturnUs);
    struct ReturnStamp {
        UsbAudioContext *ctx;
        ~ReturnStamp() { ctx->outputGapReturnUs = monotonicUs(); }
    } returnStamp{ctx};
    // Prepend any residual bytes from the previous call to avoid
    // micro-discontinuities (pops) at buffer boundaries.
    const uint8_t *data = pcmData;
    int dataLen = totalBytes;
    uint8_t *mergedBuf = nullptr;

    if (ctx->residualBytes > 0 && totalBytes > 0) {
        dataLen = ctx->residualBytes + totalBytes;
        mergedBuf = (uint8_t *)malloc(dataLen);
        if (!mergedBuf) {
            LOGE("submitPcmToUrbs: cannot preserve residual; stopping instead of dropping PCM");
            ctx->running.store(false);
            return false;
        }
        memcpy(mergedBuf, ctx->residualBuffer, ctx->residualBytes);
        memcpy(mergedBuf + ctx->residualBytes, pcmData, totalBytes);
        data = mergedBuf;
        ctx->residualBytes = 0;
    }

    const int residualPrefix = dataLen - totalBytes;   // bytes of the previous call kept in front (0 if none)
    bool interrupted = false;
    int offset = 0;
    // Use calibrated fpmf from DAC's async feedback endpoint (read at start), as the feedback policy
    // allows (B10: `follow` is this exactly). This matches the DAC's actual hardware clock instead of
    // the nominal rate.
    double fpmf = effectiveFpmf(ctx);
    uint32_t refusalSeen = ctx->refusalEpoch;   // B41-fix1 (A1b): see the check after the reap below

    while (offset < dataLen && ctx->running.load()) {
        // B12a-6: a pause asked for while this block is being written starts its fade here, between two URBs,
        // instead of after the block. Only once the previous call's residual is behind us, so what is handed back
        // is a whole number of this block's own frames.
        if (interruptible && offset >= residualPrefix && ctx->pauseRequested.load(std::memory_order_relaxed)) {
            interrupted = true;
            break;
        }
        UsbPcmUrbPlan plan = planUsbPcmUrb(
            dataLen - offset,
            ctx->bytesPerFrame,
            ctx->maxPacketSize,
            fpmf,
            ctx->frameAccumulator,
            false);
        if (plan.packetCount == 0 || plan.totalBytes == 0) break;

        if (ctx->urbsInFlight >= ctx->plan.backpressureUrbs) {
            const int64_t reapStartUs = monotonicUs();
            int result = reapOldestUrb(ctx, 200, ReapSite::BACKPRESSURE);
            usbOutputGapAddReap(&ctx->outputGap, monotonicUs() - reapStartUs);
            if (result == -2) {
                LOGE("submitPcmToUrbs: reap timeout, inflight=%d", ctx->urbsInFlight);
                drainAllUrbs(ctx, false, nullptr);
                ctx->running.store(false);
                free(mergedBuf);
                return false;
            } else if (result < 0) {
                ctx->running.store(false);
                free(mergedBuf);
                return false;
            }
        }

        // B41-fix1 (A1b): the rate was taken when this block began, about 100 ms of audio ago, and the reap above handles the
        // DAC's feedback too. A reading that made the policy refuse (absurd, sustained, stuck, beyond +-5 %) while the block
        // was half written used to reach only the NEXT block: the rest of this one went out at the rate of the reading before
        // it, which on the Melody's ramp (+0.36 % ... +1.09 % ... +2.1 % ...) was still a plausible one. The policy is asked
        // again, between two URBs, only when it turned to refusing; the URB planned above is planned again at the answer
        // (the plan is pure: the accumulator is not touched until it is submitted). Following the DAC stays at block
        // granularity, as it was.
        if (ctx->refusalEpoch != refusalSeen) {
            refusalSeen = ctx->refusalEpoch;
            fpmf = effectiveFpmf(ctx);
            plan = planUsbPcmUrb(dataLen - offset, ctx->bytesPerFrame, ctx->maxPacketSize, fpmf, ctx->frameAccumulator, false);
            if (plan.packetCount == 0 || plan.totalBytes == 0) break;
        }

        // AUD-124: checked only when the ring is well below full (unusual while streaming), so the
        // steady state pays one comparison. Nothing queued since the last submit means the DAC just
        // received empty microframes. A faded cut awaiting the next item (AUD-123) is meant to run
        // empty, and so is an idle the producer announced (a pause zeroes `lastSubmitMs`).
        // AUD-140: whatever the duration — the old `< 200 ms` window hid the longest starvations.
        if (ctx->urbsInFlight < ctx->plan.lowRingUrbs) {
            const int64_t starvedMs = usbRingStarvedMs(ctx->lastSubmitMs, monotonicMs(), ctx->playOutBeforeDrain);
            if (starvedMs >= 0 && usbQueuedFrames(ctx) == 0) {
                reportUsbGlitch(USB_GLITCH_RING_EMPTY, "queue ran dry: last submit %lld ms ago, fpmf=%.4f",
                                (long long)starvedMs, ctx->readingFpmf);
            }
        }

        UrbSlot *slot = &ctx->ring[ctx->submitIdx];
        memcpy(slot->buffer, data + offset, plan.totalBytes);

        if (submitRingUrb(ctx, plan.packetSizes, plan.packetCount, plan.totalBytes) < 0) {
            LOGE("submitPcmToUrbs: submit failed, stopping stream");
            ctx->running.store(false);
            free(mergedBuf);
            return false;
        }
        // Validation-only passive observer: bytes are included only after the
        // kernel accepted this exact AudioStreaming URB. Disabled is one
        // predictable branch and performs no hashing, allocation, or I/O.
        observeSubmittedUsbPayload(ctx, slot->buffer, plan.totalBytes, plan.packetCount);
        ctx->frameAccumulator = plan.nextFrameAccumulator;
        ctx->lastSubmitMs = monotonicMs();
        offset += plan.totalBytes;
    }

    if (interrupted) {
        // The rest of the block is the caller's (it becomes the continuation of the pause's fade-out): not a residual.
        if (consumedBytes) *consumedBytes = offset - residualPrefix;
        free(mergedBuf);
        return ctx->running.load();
    }

    // Save any leftover bytes for the next call.
    // This catches sub-frame leftovers (the short-URB case is handled above).
    int leftover = dataLen - offset;
    if (leftover > 0 && leftover < ctx->plan.residualCapBytes && ctx->residualBytes == 0) {
        memcpy(ctx->residualBuffer, data + offset, leftover);
        ctx->residualBytes = leftover;
    }

    free(mergedBuf);
    // A stream stopped from outside (detach, stop) leaves the loop above early with PCM unqueued.
    return ctx->running.load();
}

bool submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes) {
    return submitPcmToUrbsImpl(ctx, pcmData, totalBytes, false, nullptr);
}

/** Retires every completion already available, without waiting. Submitting thread only. */
static void reapReadyUrbs(UsbAudioContext *ctx) {
    while (ctx->urbsInFlight > 0) {
        struct usbdevfs_urb *c = nullptr;
        if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c) != 0 || c == nullptr) return;
        observeReapedUrb(ctx, ReapSite::BACKPRESSURE, c);
        if (urbQuarantineTake(c)) {
            free(c);
            continue;
        }
        if (c == ctx->feedbackUrb && ctx->feedbackInFlight) {
            handleFeedbackCompletion(ctx);
            continue;
        }
        if (!isOldestAudioCompletion(ctx, c)) continue;
        // Iso URBs on one endpoint complete in submission order, as reapOldestUrb assumes.
        checkIsoCompletion(ctx, c);
        ctx->reapIdx = (ctx->reapIdx + 1) % ctx->plan.ringUrbs;
        ctx->urbsInFlight--;
        ctx->urbReaps.fetch_add(1, std::memory_order_relaxed);
    }
}

/**
 * B46-B1: the wire time of the URBs in the ring that the DAC has not played, not counting the newest one (the URB being observed).
 * Completed ones are retired first, so what is left is what is still to go out; the oldest of them may be partly played, so this
 * can overstate the lead by less than one URB (1 ms), which only makes the model slower to report, never quicker.
 */
static int64_t ringUnplayedWireUs(UsbAudioContext *ctx) {
    reapReadyUrbs(ctx);
    int64_t wireUs = 0;
    for (int i = 0; i + 1 < ctx->urbsInFlight; i++) {
        wireUs += (int64_t)ctx->ring[(ctx->reapIdx + i) % ctx->plan.ringUrbs].urb->number_of_packets * USB_AUDIO_PACKET_US;
    }
    return wireUs;
}

int64_t usbQueuedFrames(UsbAudioContext *ctx) {
    if (!ctx || ctx->bytesPerFrame <= 0) return 0;
    reapReadyUrbs(ctx);
    int64_t bytes = 0;
    for (int i = 0; i < ctx->urbsInFlight; i++) {
        bytes += ctx->ring[(ctx->reapIdx + i) % ctx->plan.ringUrbs].dataLength;
    }
    return bytes / ctx->bytesPerFrame;
}

bool usbWaitForProgress(UsbAudioContext *ctx) {
    if (!ctx || ctx->urbsInFlight <= 0) return false;
    return reapOldestUrb(ctx, 2, ReapSite::BACKPRESSURE) == 0;
}

bool submitFinalPcmResidual(UsbAudioContext *ctx) {
    if (!ctx || !ctx->running.load()) return false;
    if (ctx->residualBytes == 0) return true;
    if (ctx->bytesPerFrame <= 0 || ctx->residualBytes % ctx->bytesPerFrame != 0) {
        LOGE("EOS residual is not frame-aligned: bytes=%d frameBytes=%d",
             ctx->residualBytes, ctx->bytesPerFrame);
        return false;
    }

    UsbPcmUrbPlan plan = planUsbPcmUrb(
        ctx->residualBytes,
        ctx->bytesPerFrame,
        ctx->maxPacketSize,
        effectiveFpmf(ctx),
        ctx->frameAccumulator,
        true);
    if (!plan.consumesAllAvailable || plan.totalBytes != ctx->residualBytes ||
        plan.packetCount <= 0) {
        LOGE("EOS residual could not be packetized without padding: bytes=%d",
             ctx->residualBytes);
        return false;
    }

    if (ctx->urbsInFlight >= ctx->plan.backpressureUrbs) {
        int result = reapOldestUrb(ctx, 200, ReapSite::BACKPRESSURE);
        if (result < 0) {
            LOGE("EOS residual reap failed: result=%d", result);
            return false;
        }
    }

    UrbSlot *slot = &ctx->ring[ctx->submitIdx];
    memcpy(slot->buffer, ctx->residualBuffer, plan.totalBytes);
    if (submitRingUrb(ctx, plan.packetSizes, plan.packetCount, plan.totalBytes) < 0) {
        LOGE("EOS residual submit failed");
        return false;
    }

    observeSubmittedUsbPayload(ctx, slot->buffer, plan.totalBytes, plan.packetCount);
    ctx->frameAccumulator = plan.nextFrameAccumulator;
    ctx->residualBytes = 0;
    LOGI("Natural EOS submitted final residual: bytes=%d frames=%d packets=%d",
         plan.totalBytes, plan.totalBytes / ctx->bytesPerFrame, plan.packetCount);
    return true;
}

// ── ExoPlayer pipeline gestures (B9) ────────────────────────────────
//
// The queued continuation supplies the ramp at a pause, seek or skip. Ramp shapes and lengths
// come from pcmApplyGainRamp and the stream's plan (fadeOutFrames, fadeInFrames).

static void armFadeOut(UsbAudioContext *ctx, bool keepRemainder, bool countFrames) {
    PipelineGesture &g = ctx->gesture;
    g.fadeOutArmed = true;
    g.fadeOutKeep = keepRemainder;
    g.fadeOutCountFrames = countFrames;
    g.fadeOutDone = 0;
    g.fadeOutTotal = ctx->plan.fadeOutFrames;
    if (g.fadeOutTotal < 1) g.fadeOutTotal = 1;
}

/** The ramp reached zero (or the continuation ran out): the ring ends in a fade and may play out. */
static void finishFadeOut(UsbAudioContext *ctx) {
    PipelineGesture &g = ctx->gesture;
    LOGI("Pipeline fade-out queued: %lld/%lld frames (%s)", (long long)g.fadeOutDone, (long long)g.fadeOutTotal,
         g.fadeOutKeep ? "pause: the rest of the block is kept" : "seek or skip: the rest is dropped");
    g.fadeOutArmed = false;
    g.endsInFade = true;
    g.fadeOutsCompleted++;
    // A teardown from here must let the ring play out before it discards, or it cuts the fade itself
    // (AUD-123). The next audio written clears it.
    ctx->playOutBeforeDrain = true;
}

static void stashPending(PipelineGesture &g, const uint8_t *data, int frames, int frameBytes) {
    const size_t bytes = (size_t)frames * (size_t)frameBytes;
    if (g.pendingCapacity < bytes) {
        uint8_t *grown = (uint8_t *)realloc(g.pending, bytes);
        if (!grown) {
            // Never passed to the kernel: it is copied into a URB slot like any other PCM. Losing the
            // remainder costs a few ms of audio at the resume, and says so.
            LOGE("pipeline pause: cannot keep %d frames for the resume", frames);
            g.pendingFrames = 0;
            return;
        }
        g.pending = grown;
        g.pendingCapacity = bytes;
    }
    memcpy(g.pending, data, bytes);
    g.pendingFrames = frames;
}

static void primeRingForResume(UsbAudioContext *ctx);   // defined with the pipeline's resume, which also uses it

bool submitPipelinePcm(UsbAudioContext *ctx, uint8_t *data, int totalBytes, int totalFrames) {
    PipelineGesture &g = ctx->gesture;
    const int frameBytes = ctx->bytesPerFrame;
    if (g.fadeOutArmed && frameBytes > 0) {
        // The continuation of what is playing: its first frames are ramped to zero and submitted, and
        // the rest of the block is kept for the resume (a pause) or dropped (a seek, a skip).
        const int ramped = fadeOutFramesOfBlock(totalFrames, g.fadeOutDone, g.fadeOutTotal);
        bool ok = true;
        if (ramped > 0) {
            pcmApplyGainRamp(data, ramped, ctx->channelCount, ctx->bytesPerSample, g.fadeOutDone,
                             g.fadeOutTotal, false);
            ok = submitPcmToUrbs(ctx, data, ramped * frameBytes);
            g.fadeOutDone += ramped;
            if (g.fadeOutCountFrames) ctx->framesWritten += ramped;
        }
        if (g.fadeOutDone >= g.fadeOutTotal) {
            if (g.fadeOutKeep && ramped < totalFrames) {
                stashPending(g, data + (size_t)ramped * frameBytes, totalFrames - ramped, frameBytes);
            }
            finishFadeOut(ctx);
        }
        return ok;
    }
    // B48-fix1: audio is about to start into a ring that is empty or may be (a stream's first write, a recovery's restart, a seek that left nothing audible): the silence goes
    // in first, up to the plan's resume lead, by the same step the resume after a pause uses. Whatever the ring already holds counts.
    if (ctx->primeOwed) primeRingForResume(ctx);
    // B12a-6: a block is up to ~190 ms of audio and the ring holds 80 ms, so the writer spends most of a block
    // waiting for ring room; a pause that arrives meanwhile used to be seen only when the block was done. It is
    // looked for between URBs now — but only where a split is harmless: audio already playing (a stream that
    // never played has nothing to fade) and no fade-in under way (a ramp applied in place to the part kept for the
    // resume would be applied twice).
    const bool interruptible = frameBytes > 0 && g.hasAudio && g.fadeInRemaining <= 0;
    if (g.fadeInRemaining > 0) {
        // The first audio after a cut: entering mid-waveform at full level is a click.
        pcmApplyGainRamp(data, totalFrames, ctx->channelCount, ctx->bytesPerSample,
                         g.fadeInTotal - g.fadeInRemaining, g.fadeInTotal, true);
        g.fadeInRemaining -= totalFrames;
        if (g.fadeInRemaining < 0) g.fadeInRemaining = 0;
    }
    int consumedBytes = totalBytes;
    bool ok = submitPcmToUrbsImpl(ctx, data, totalBytes, interruptible, &consumedBytes);
    ctx->playOutBeforeDrain = false;  // the stream carries live audio again (AUD-143)
    const int consumedFrames = frameBytes > 0 ? consumedBytes / frameBytes : totalFrames;
    ctx->framesWritten += consumedFrames;
    g.hasAudio = true;
    g.endsInFade = false;
    if (ok && consumedFrames < totalFrames) {
        // The pause reached the writer inside this block: what was submitted is untouched audio, and the fade-out
        // of the continuation starts with the next frame — this block's own remainder. The 8 ms ramp is taken from
        // it and the rest is kept for the resume, exactly as when the pause is seen at a block's end.
        const int restFrames = totalFrames - consumedFrames;
        uint8_t *rest = data + (size_t)consumedFrames * frameBytes;
        ctx->pauseRequested.store(false, std::memory_order_relaxed);
        if (pipelineBeginPause(ctx)) {
            LOGI("Pipeline pause inside a block: %d of %d frames written, the fade-out starts at frame %d (%.1f ms of the block kept back)",
                 consumedFrames, totalFrames, consumedFrames,
                 ctx->sampleRate > 0 ? restFrames * 1000.0 / ctx->sampleRate : 0.0);
            g.pauseAppliedInBlock = true;
            return submitPipelinePcm(ctx, rest, restFrames * frameBytes, restFrames);
        }
        // Nothing audible to fade (the ring ended in a fade, or played out): the block is finished as it always was
        // and the writer's own pause handling does the rest.
        ok = submitPcmToUrbsImpl(ctx, rest, restFrames * frameBytes, false, nullptr);
        ctx->framesWritten += restFrames;
    }
    return ok;
}

void pipelineRequestPause(UsbAudioContext *ctx) { ctx->pauseRequested.store(true, std::memory_order_relaxed); }

void pipelineCancelPauseRequest(UsbAudioContext *ctx) { ctx->pauseRequested.store(false, std::memory_order_relaxed); }

bool pipelineConsumePauseApplied(UsbAudioContext *ctx) {
    const bool applied = ctx->gesture.pauseAppliedInBlock;
    ctx->gesture.pauseAppliedInBlock = false;
    return applied;
}

bool pipelineBeginPause(UsbAudioContext *ctx) {
    PipelineGesture &g = ctx->gesture;
    ctx->pauseRequested.store(false, std::memory_order_relaxed);   // handled, whichever way it goes from here
    if (g.fadeOutArmed) return true;  // a ramp is in progress: it carries on
    if (g.endsInFade) return false;   // the ring already ends in a fade: a second would fade nothing
    // Whether audio is still on its way to the DAC, for the seek barrier (AUD-140): a ring that
    // has played out is silent and has nothing to cut.
    if (seekCutFor(g.hasAudio, usbQueuedFrames(ctx), false) != SEEK_CUT_FADE) return false;
    armFadeOut(ctx, true, true);
    g.pauseFades++;
    noteGesture(ctx, USB_POLICY_PAUSE, "fade-out");
    return true;
}

int pipelineBeginSeek(UsbAudioContext *ctx) {
    PipelineGesture &g = ctx->gesture;
    ctx->pauseRequested.store(false, std::memory_order_relaxed);   // a seek takes over from a pause not yet acted on
    g.pauseAppliedInBlock = false;
    const int64_t unplayed = usbQueuedFrames(ctx);
    const SeekCut cut = seekCutFor(g.hasAudio, unplayed, false);
    // A seek replaces whatever a pause kept for its resume: that block belongs to the position left.
    g.pendingFrames = 0;
    // Whatever follows a cut that was audible fades in; a stream that never played starts bit-perfect.
    g.fadeInTotal = ctx->plan.fadeInFrames;
    g.fadeInRemaining = g.hasAudio ? g.fadeInTotal : 0;
    int plan;
    if (g.fadeOutArmed) {
        // A pause's ramp is in progress: it is finished as the same ramp, and the rest is dropped.
        g.fadeOutKeep = false;
        g.fadeOutCountFrames = false;
        plan = PIPELINE_SEEK_FADE;
    } else if (cut == SEEK_CUT_FADE) {
        // The ring ends in a pause's fade, or in an earlier seek's: queuing a second continuation would
        // fade audio nobody asked to keep (and a drag of the seek bar would pile them up).
        if (g.endsInFade) {
            plan = PIPELINE_SEEK_SILENCE;
        } else {
            armFadeOut(ctx, false, false);
            g.seekFades++;
            plan = PIPELINE_SEEK_FADE;
        }
    } else {
        // Nothing audible is on its way: the packetisation starts clean, and the gap before the new
        // audio is announced, not a starvation.
        ctx->frameAccumulator = 0.0;
        ctx->residualBytes = 0;
        ctx->lastSubmitMs = 0;
        plan = PIPELINE_SEEK_NONE;
    }
    noteGesture(ctx, USB_POLICY_SEEK, plan == PIPELINE_SEEK_FADE ? "fade" : plan == PIPELINE_SEEK_SILENCE ? "silence" : "none");
    LOGI("Pipeline seek: %s, %lld frames unplayed (%.1f ms), fade-in %lld frames",
         plan == PIPELINE_SEEK_FADE ? "ring ends in a fade of the old continuation"
         : plan == PIPELINE_SEEK_SILENCE ? "ring already ends in a fade, silence only"
         : "nothing audible queued, no fade",
         (long long)unplayed, ctx->sampleRate > 0 ? unplayed * 1000.0 / ctx->sampleRate : 0.0,
         (long long)g.fadeInRemaining);
    return plan;
}

bool pipelineFadingOut(const UsbAudioContext *ctx) { return ctx->gesture.fadeOutArmed; }

void pipelineEndFadeOut(UsbAudioContext *ctx) {
    if (ctx->gesture.fadeOutArmed) finishFadeOut(ctx);
}

namespace {
int64_t feedQueued(void *self);
bool feedSubmit(void *self, int frames);
void feedWait(void *self);
bool feedFinished(void *self);
bool feedRunning(void *self);
}  // namespace

/**
 * B47-A3: a resume after a pause finds the ring played out (the DAC took the pause's fade and its silence), and the audio that follows
 * is a replay of the block the pause kept, then the next buffer, which the thread has to fetch. On 2026-10-29 17:25:19 the first URB of such a
 * resume went out alone and the next 7 ms later (`RING_EMPTY` + `OUTPUT_GAP dry=6 ms inFlight=1 where=in-write`; the same at 14:46:49 in the B45 session),
 * a bus that ran dry for the 7 ms the ring did not cover. Silence goes in first, up to the plan's resume lead, with the same step the silence feed
 * uses after a skip: whatever the pause's tail still holds counts, so a quick resume adds only the missing part. The fade-in that the resume armed
 * is not touched (silence goes straight to the ring, not through the gesture), so the audio that follows still fades in from zero.
 */
static void primeRingForResume(UsbAudioContext *ctx) {
    ctx->primeOwed = false;   // B48-fix1: whoever primes (a resume, the first write, a recovery's restart) pays what is owed
    if (ctx->plan.resumeLeadFrames <= 0) return;
    SeekFeedOps ops = {ctx, feedQueued, feedSubmit, feedWait, feedFinished, feedRunning};
    // B48-fix1: the amount is decided once, from what the ring holds now (whatever it holds counts), in the whole silence chunks the feed uses, so it is exactly what the plan says
    // (usbSilenceFramesToReach) whatever the packetizer's residual does to the ring's own count; it is no longer chased through that count, which made it one chunk short or long.
    const int chunk = ctx->plan.silenceChunkFrames > 0 ? ctx->plan.silenceChunkFrames : 1;
    const int64_t owed = usbSilenceFramesToReach(ctx->plan.resumeLeadFrames, chunk, usbQueuedFrames(ctx));
    int64_t fed = 0;
    while (fed < owed && ctx->running.load()) {
        if (!ops.submitSilence(ctx, chunk)) break;
        fed += chunk;
    }
    if (fed > 0) {
        LOGI("Prime: %lld frames (%.1f ms) of silence before the audio (%s), the plan's %d-frame lead", (long long)fed,
             ctx->sampleRate > 0 ? fed * 1000.0 / ctx->sampleRate : 0.0, ctx->primeWhy ? ctx->primeWhy : "?", ctx->plan.resumeLeadFrames);
    }
}

void pipelineResume(UsbAudioContext *ctx) {
    PipelineGesture &g = ctx->gesture;
    ctx->pauseRequested.store(false, std::memory_order_relaxed);
    if (g.fadeOutArmed) finishFadeOut(ctx);
    if (!g.hasAudio) return;
    g.fadeInTotal = ctx->plan.fadeInFrames;
    g.fadeInRemaining = g.fadeInTotal;
    LOGI("Pipeline resume: %d frames of the faded block replayed, fade-in %lld frames", g.pendingFrames > 0 ? g.pendingFrames : 0,
         (long long)g.fadeInTotal);
    noteGesture(ctx, USB_POLICY_RESUME, g.pendingFrames > 0 ? "replays-the-rest" : "fade-in");
    if (!ctx->primeOwed) ctx->primeWhy = "resume";   // a recovery's restart owes the priming under its own name
    primeRingForResume(ctx);
    if (g.pendingFrames <= 0) return;
    const int frames = g.pendingFrames;
    g.pendingFrames = 0;
    // The rest of the block the pause faded out, faded in: no audio is skipped (AUD-123).
    submitPipelinePcm(ctx, g.pending, frames * ctx->bytesPerFrame, frames);
}

namespace {
int64_t feedQueued(void *self) { return usbQueuedFrames(static_cast<UsbAudioContext *>(self)); }

bool feedSubmit(void *self, int frames) {
    auto *ctx = static_cast<UsbAudioContext *>(self);
    PipelineGesture &g = ctx->gesture;
    const size_t bytes = (size_t)frames * (size_t)ctx->bytesPerFrame;
    if (g.silenceCapacity < bytes) {
        uint8_t *grown = (uint8_t *)realloc(g.silence, bytes);
        if (!grown) return false;
        g.silence = grown;
        g.silenceCapacity = bytes;
    }
    memset(g.silence, 0, bytes);
    g.silenceFramesFed += frames;
    return submitPcmToUrbs(ctx, g.silence, (int)bytes);
}

void feedWait(void *self) {
    auto *ctx = static_cast<UsbAudioContext *>(self);
    if (!usbWaitForProgress(ctx)) usleep(1000);
}

bool feedFinished(void *) { return false; }  // the writer owns the loop

bool feedRunning(void *self) { return static_cast<UsbAudioContext *>(self)->running.load(); }
}  // namespace

bool pipelineFeedSilence(UsbAudioContext *ctx) {
    SeekFeedOps ops = {ctx, feedQueued, feedSubmit, feedWait, feedFinished, feedRunning};
    return seekFeedSilenceStep(&ops, ctx->plan.silenceLeadFrames, ctx->plan.silenceChunkFrames);
}

void pipelineGestureStats(const UsbAudioContext *ctx, int64_t out[9]) {
    const PipelineGesture &g = ctx->gesture;
    out[0] = g.hasAudio ? 1 : 0;
    out[1] = g.endsInFade ? 1 : 0;
    out[2] = g.fadeOutArmed ? 1 : 0;
    out[3] = g.pendingFrames;
    out[4] = g.fadeInRemaining;
    out[5] = g.silenceFramesFed;
    out[6] = g.pauseFades;
    out[7] = g.seekFades;
    out[8] = g.fadeOutsCompleted;
}

// ── Raw bytes write (no float, for libFLAC integer path) ────────────

extern "C" {  // resume JNI functions

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWriteRaw(
        JNIEnv *env, jobject, jlong h, jbyteArray pcm, jint inputBitDepth) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !ctx->running.load()) return;

    jint inputBytes = env->GetArrayLength(pcm);
    if (inputBytes <= 0) return;

    int inputBps = inputBitDepth / 8;
    int totalSamples = inputBytes / inputBps;
    int totalFrames = totalSamples / ctx->channelCount;
    int outputBytes = totalSamples * ctx->bytesPerSample;

    // Resize transfer buffer if needed
    if (!ctx->transferBuffer || ctx->transferBufferCapacity < outputBytes) {
        free(ctx->transferBuffer);
        ctx->transferBuffer = (uint8_t *)malloc(outputBytes);
        ctx->transferBufferCapacity = outputBytes;
    }

    jbyte *rawData = env->GetByteArrayElements(pcm, nullptr);
    if (!rawData) return;

    // Bit-depth matching: pad input to the DAC's container (lossless integer ops, AUD-008).
    // Narrowing is refused; the sink checks the combination before it claims the stream.
    if (usbPcmConvert((uint8_t *)rawData, inputBitDepth, ctx->transferBuffer, ctx->bitDepth,
                      totalSamples) < 0) {
        LOGE("WriteRaw: unsupported bit-depth conversion %d → %d", inputBitDepth, ctx->bitDepth);
        env->ReleaseByteArrayElements(pcm, rawData, JNI_ABORT);
        return;
    }

    env->ReleaseByteArrayElements(pcm, rawData, JNI_ABORT);

    // Submit to USB pipeline (see nativeUsbAudioWrite: a gesture owed ramps it, otherwise it is plain)
    submitPipelinePcm(ctx, ctx->transferBuffer, outputBytes, totalFrames);

    if (ctx->framesWritten % ctx->sampleRate < (int64_t)totalFrames) {
        LOGI("WriteRaw: %lld frames (~%.0f sec) inflight=%d inputBits=%d fpmf=%.4f live=%.4f acceptedAge=%lldms fb#%lld sending=%.4f",
             (long long)ctx->framesWritten, (double)ctx->framesWritten/ctx->sampleRate,
             ctx->urbsInFlight, inputBitDepth, ctx->readingFpmf, ctx->liveFpmf,
             (long long)(ctx->acceptedAtMs >= 0 ? monotonicMs() - ctx->acceptedAtMs : -1),
             (long long)g_feedbackCount.load(std::memory_order_relaxed),
             g_fbSendingFpmf.load(std::memory_order_relaxed));
    }
}

// The pipeline's pause / seek / skip barrier (B9): thin entry points over the functions above. All of
// them run on the writer thread, inside the sink's stream lock, like the writes.

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePipelineBeginPause(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    return ctx && pipelineBeginPause(ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePipelineRequestPause(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (ctx) pipelineRequestPause(ctx);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePipelineCancelPauseRequest(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (ctx) pipelineCancelPauseRequest(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePipelineConsumePauseApplied(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    return ctx && pipelineConsumePauseApplied(ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePipelineBeginSeek(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    return ctx ? pipelineBeginSeek(ctx) : PIPELINE_SEEK_NONE;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePipelineFadingOut(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    return ctx && pipelineFadingOut(ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePipelineEndFadeOut(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (ctx) pipelineEndFadeOut(ctx);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePipelineResume(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (ctx) pipelineResume(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePipelineFeedSilence(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    return ctx && pipelineFeedSilence(ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePipelineStats(JNIEnv *env, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    int64_t stats[9] = {};
    if (ctx) pipelineGestureStats(ctx, stats);
    jlong out[9];
    for (int i = 0; i < 9; i++) out[i] = (jlong)stats[i];
    jlongArray array = env->NewLongArray(9);
    if (array) env->SetLongArrayRegion(array, 0, 9, out);
    return array;
}

// B47-A5: the lock-up detector's state and the two native halves of a recovery step (see usb-audio-output.h). The writer thread calls all three.
JNIEXPORT jlongArray JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeDacLockState(JNIEnv *env, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    int64_t state[USB_DAC_STATE_COUNT] = {};
    usbDacLockState(ctx, state);
    jlong out[USB_DAC_STATE_COUNT];
    for (int i = 0; i < USB_DAC_STATE_COUNT; i++) out[i] = (jlong)state[i];
    jlongArray array = env->NewLongArray(USB_DAC_STATE_COUNT);
    if (array) env->SetLongArrayRegion(array, 0, USB_DAC_STATE_COUNT, out);
    return array;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryBegin(JNIEnv *, jobject, jlong h) {
    return usbDacRecoveryBegin(reinterpret_cast<UsbAudioContext *>(h)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeDacRecoveryEnd(JNIEnv *, jobject, jlong h) {
    return usbDacRecoveryEnd(reinterpret_cast<UsbAudioContext *>(h)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeNoteDacRecovery(JNIEnv *, jobject, jlong h, jint step, jint result, jlong elapsedMs) {
    usbNoteDacRecovery(reinterpret_cast<UsbAudioContext *>(h), (int)step, (int)result, (int64_t)elapsedMs);
}

/** SET_DAC_RECOVERY in the debug bridge: process-only, on by default. */
JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeSetDacRecoveryEnabled(JNIEnv *, jclass, jboolean enabled) {
    usbSetDacRecoveryEnabled(enabled == JNI_TRUE);
}

/** B48-fix1: the feedback start guard (SET_FEEDBACK_START_GUARD in the debug bridge): process-only, on by default. */
JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeSetFeedbackStartGuardEnabled(JNIEnv *, jclass, jboolean enabled) {
    usbSetFeedbackStartGuardEnabled(enabled == JNI_TRUE);
}

/** B48-fix1: whether the feedback start guard is on in this process (the reply line of SET_FEEDBACK_START_GUARD). */
JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeFeedbackStartGuardEnabled(JNIEnv *, jclass) {
    return usbFeedbackStartGuardEnabled() ? JNI_TRUE : JNI_FALSE;
}

/** RECOVER_DAC_NOW in the debug bridge: step 1 or 2, taken by the writer thread at its next look. */
JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeRequestDacRecovery(JNIEnv *, jclass, jint step) {
    usbRequestDacRecovery((int)step);
}

JNIEXPORT jlongArray JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetDacRecoveryStats(JNIEnv *env, jclass) {
    int64_t stats[USB_DAC_RECOVERY_STATS_COUNT];
    usbDacRecoveryStats(stats);
    jlong out[USB_DAC_RECOVERY_STATS_COUNT];
    for (int i = 0; i < USB_DAC_RECOVERY_STATS_COUNT; i++) out[i] = (jlong)stats[i];
    jlongArray array = env->NewLongArray(USB_DAC_RECOVERY_STATS_COUNT);
    if (array) env->SetLongArrayRegion(array, 0, USB_DAC_RECOVERY_STATS_COUNT, out);
    return array;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeStartPayloadValidation(
        JNIEnv *env, jclass, jstring manifestPath, jstring label, jlong expectedBytes) {
    if (!manifestPath) return JNI_FALSE;
    const char *path = env->GetStringUTFChars(manifestPath, nullptr);
    const char *labelChars = label ? env->GetStringUTFChars(label, nullptr) : nullptr;
    bool result = startUsbPayloadValidation(
        path,
        labelChars,
        expectedBytes > 0 ? static_cast<uint64_t>(expectedBytes) : 0);
    if (labelChars) env->ReleaseStringUTFChars(label, labelChars);
    env->ReleaseStringUTFChars(manifestPath, path);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeStopPayloadValidation(
        JNIEnv *env, jclass) {
    const char *result = stopUsbPayloadValidation();
    return result ? env->NewStringUTF(result) : nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetPayloadValidationStatus(
        JNIEnv *env, jclass) {
    return env->NewStringUTF(getUsbPayloadValidationStatus());
}

} // extern "C"
