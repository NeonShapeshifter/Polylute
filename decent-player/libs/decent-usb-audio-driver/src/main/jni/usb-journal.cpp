#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "usb-journal.h"

#include <errno.h>
#include <limits.h>
#include <locale.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>
#include <zlib.h>

namespace {
struct Meta { int64_t stream; UsbJournalFacts facts; };
struct Record {
    int kind; // 0 policy, 1 second, 2 platform, 3 control I/O
    int reason;
    int64_t mono, wall;
    UsbJournalFacts facts;
    union { UsbPolicyEvent event; UsbSecondSample second; } data;
};
struct Frozen {
    UsbSecondFrozen ring;
    Meta meta[USB_JOURNAL_METADATA_CAPACITY];
    int64_t wall, triggerStream;
};
struct Text {
    char *data;
    size_t size, capacity;
    bool failed;
    Text(): data(nullptr), size(0), capacity(0), failed(false) {}
    ~Text() { free(data); }
    void add(const char *format, ...) {
        if (failed) return;
        va_list a, b; va_start(a, format); va_copy(b, a);
        const int n = vsnprintf(nullptr, 0, format, a); va_end(a);
        if (n < 0 || size + (size_t)n > 8 * 1024 * 1024) { failed = true; va_end(b); return; }
        const size_t required = size + n + 1;
        if (required > capacity) {
            capacity = required * 2;
            void *p = realloc(data, capacity);
            if (!p) { failed = true; va_end(b); return; }
            data = (char *)p;
        }
        vsnprintf(data + size, capacity - size, format, b); va_end(b); size += n;
    }
};
static pthread_mutex_t lifecycle = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t queueLock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t snapshotLock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t fileLock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t ready = PTHREAD_COND_INITIALIZER;
static pthread_cond_t idle = PTHREAD_COND_INITIALIZER;
static Record queue[USB_JOURNAL_QUEUE_CAPACITY];
static Frozen frozen;
static Meta metadata[USB_JOURNAL_METADATA_CAPACITY];
static UsbJournalFacts currentFacts;
static int head, count, metaHead;
static int64_t newestStream;
static bool working, stopping, snapshotPending;
static int configured;
static uint64_t dropped, ioErrors;
static pthread_t thread;
static char directory[PATH_MAX];
static FILE *file;
static size_t fileBytes;
static uint64_t sequence, processSession;
#ifdef POLYLUTE_JOURNAL_TEST
static int testDelay;
#endif

static int64_t millis(clockid_t clock) {
    struct timespec t; clock_gettime(clock, &t); return t.tv_sec * 1000LL + t.tv_nsec / 1000000;
}
static void lose() { __atomic_fetch_add(&dropped, 1, __ATOMIC_RELAXED); }
static bool enabled() { return __atomic_load_n(&configured, __ATOMIC_ACQUIRE) != 0; }
static UsbJournalFacts factsFor(int64_t stream, const Meta *table) {
    for (int i = 0; i < USB_JOURNAL_METADATA_CAPACITY; ++i)
        if (stream != 0 && table[i].stream == stream) return table[i].facts;
    return UsbJournalFacts{}; // unknown stays unknown; never attribute an old snapshot to a new DAC
}
// Only reasons controlled by this output are persisted. No caller text/path/name is retained.
static const char *safeWhat(const char *value) {
    static const char *const words[] = {
        "absurd", "sustained", "out-of-window", "identical", "changed", "gap", "stream-end",
        "initial-reading", "no-answer", "no-feedback-endpoint", "urb-status", "packet-status", "length",
        "rail", "stuck", "no-readings", "plateau",
#ifdef POLYLUTE_DEBUG_FAULTS
        "debug-rail", "debug-stuck", "debug-no-readings", "debug-plateau",
#endif
        "poll-100ms", "bus-model", "announced", "fade-out", "fade", "silence", "none", "replays-the-rest", "fade-in",
        "music-resumed", "valid", "invalid", "reopen-backoff",
        "step 1 begin", "step 2 begin", "step 3 begin", "step 1 ran", "step 2 ran", "step 3 ran",
        "step 1 failed", "step 2 failed", "step 3 failed", "step 1 ok", "step 2 ok", "step 3 ok",
        "step 1 still-railed", "step 2 still-railed", "step 3 still-railed",
        "step 1 still-stuck", "step 2 still-stuck", "step 3 still-stuck",
        "step 1 no-readings", "step 2 no-readings", "step 3 no-readings",
        "behavior-budget", "reference-untrained", "reference-session", "reference-trusted", "reference-degraded", "behavior-accepted",
        "step 1 plateau", "step 2 plateau", "step 3 plateau",
        "step 1 operation-failed", "step 2 operation-failed", "step 3 operation-failed",
        "step 1 transport-unavailable", "step 2 transport-unavailable", "step 3 transport-unavailable",
        "step 1 superseded", "step 2 superseded", "step 3 superseded",
    };
    if (value) for (const char *word : words) if (strcmp(value, word) == 0) return word;
    return "unknown";
}
static const char *kindName(int kind) {
    static const char *const names[] = {"refuse-begin", "refuse-end", "stuck-begin", "stuck-end", "start",
        "pause", "idle", "seek", "resume", "dac-locked", "dac-recovery", "queue-timeout", "OUTPUT_GAP", "feedback-state", "backoff", "reference", "behavior"};
    return kind >= 0 && kind < (int)(sizeof(names) / sizeof(names[0])) ? names[kind] : "unknown";
}
static const char *platformName(int reason) {
    static const char *const names[] = {"unknown", "connection-open", "connection-close", "volume-verified",
        "volume-unverified", "no-hardware-volume", "stream-format"};
    return reason >= 0 && reason < 7 ? names[reason] : names[0];
}
static void body(Text &out, const Record &r) {
    const bool sample = r.kind == 1;
    const UsbPolicyEvent &e = r.data.event;
    const int64_t stream = sample ? r.data.second.stream : e.stream;
    const UsbJournalFacts &f = r.facts;
    out.add("\"monoMs\":%lld,\"wallMs\":%lld,\"connection\":%lld,\"generation\":%lld,\"stream\":%lld,\"rate\":%d,\"bits\":%d,\"alt\":%d,\"channels\":%d,\"levelUnits\":%d,\"volumeVerified\":%d,",
        (long long)r.mono, (long long)r.wall, (long long)f.connection, (long long)f.generation, (long long)stream,
        sample ? r.data.second.rate : e.rate, f.bits, f.alt, f.channels, f.level, f.verified);
    if (sample) {
        char line[1024]; usbSecondRingFormat(&r.data.second, 1, r.mono, line, sizeof(line));
        // The formatter uses only numeric fields/static labels. Escape its terminal newline.
        if (char *p = strchr(line, '\n')) *p = 0;
        out.add("\"kind\":\"second\",\"secondMonoMs\":%lld,\"summary\":\"%s\"", (long long)r.data.second.second * 1000, line);
    } else if (r.kind == 2) {
        out.add("\"kind\":\"platform\",\"what\":\"%s\"", platformName(r.reason));
    } else if (r.kind == 3) {
        static const char *const names[] = {"volume-read", "volume-write-begin", "volume-write",
            "volume-dropped", "hid-begin", "hid-end"};
        static const char *const origins[] = {"driver", "key", "remote", "ui", "clamp", "ramp",
            "duck", "pristine", "reconcile", "open-restore", "hid", "debug-hid"};
        out.add("\"kind\":\"control\",\"what\":\"%s\",\"origin\":\"%s\",\"elapsedMs\":%lld",
            names[r.reason - 7], origins[e.packetStatus], (long long)e.delta);
        if (r.reason <= 10) {
            if (e.total != INT32_MIN) out.add(",\"targetUnits\":%lld", (long long)e.total);
            if (e.burst != INT32_MIN) out.add(",\"readBackUnits\":%lld", (long long)e.burst);
            if (r.reason == 10) out.add(",\"result\":\"dropped\"");
            else if (r.reason == 7 || r.reason == 9) {
                const char *result = e.burst == INT32_MIN ? "unconfirmed" :
                    (r.reason == 9 && e.burst != e.total) ? "different" : "confirmed";
                out.add(",\"result\":\"%s\"", result);
            }
        } else if (r.reason == 12) out.add(",\"result\":\"%s\"", e.burst == 1 ? "ran" : "unavailable");
    } else {
        const char *what = safeWhat(e.what);
        out.add("\"kind\":\"%s\",\"what\":\"%s\"", kindName(e.kind), what);
        const bool resumed = strcmp(what, "music-resumed") == 0;
        if (((int)e.kind <= USB_POLICY_DAC_RECOVERY || e.kind == USB_POLICY_FEEDBACK_STATE || e.kind == USB_POLICY_REFERENCE || e.kind == USB_POLICY_BEHAVIOR) && !resumed) {
            out.add(",\"raw\":%u,\"length\":%d,\"urbStatus\":%d,\"packetStatus\":%d,\"policy\":%d", e.raw, e.length, e.urbStatus, e.packetStatus, e.policy);
        }
        if ((int)e.kind <= USB_POLICY_DAC_LOCKED) {
            out.add(",\"sentBefore\":%.8f,\"sentAfter\":%.8f", isfinite(e.sentBefore) ? e.sentBefore : 0, isfinite(e.sentAfter) ? e.sentAfter : 0);
        }
        if (e.kind == USB_POLICY_DAC_LOCKED && (strcmp(what, "rail") == 0 || strcmp(what, "stuck") == 0 || strcmp(what, "plateau") == 0)) {
            out.add(",\"detectorPpm\":%.3f", isfinite(e.ppm) ? e.ppm : 0);
        }
        if (e.kind == USB_POLICY_REFERENCE || e.kind == USB_POLICY_BEHAVIOR) out.add(",\"referenceRaw\":%lld,\"confidenceSessions\":%lld,\"faultKind\":%lld", (long long)e.total, (long long)e.delta, (long long)e.burst);
        if (e.kind == USB_POLICY_QUEUE_TIMEOUT) out.add(",\"delta\":%lld,\"total\":%lld,\"depth\":%lld,\"burst\":%lld,\"dryUs\":%lld", (long long)e.delta, (long long)e.total, (long long)e.queueDepth, (long long)e.burst, (long long)e.dryUs);
        if (e.kind == USB_POLICY_OUTPUT_GAP || resumed) out.add(",\"dryUs\":%lld", (long long)e.dryUs);
        if (e.kind == USB_POLICY_BACKOFF) out.add(",\"requiresFault\":true,\"delayMs\":%lld,\"nextRetryMs\":%lld,\"retryCount\":%lld", (long long)e.delta, (long long)e.queueDepth, (long long)e.total);
        if (e.kind == USB_POLICY_DAC_RECOVERY && e.delta >= 0 && (strstr(what, " ran") || strstr(what, " failed"))) out.add(",\"elapsedMs\":%lld", (long long)e.delta);
    }
}
static void path(char out[PATH_MAX], bool previous) {
    snprintf(out, PATH_MAX, "%s/%s.jsonl", directory, previous ? "previous" : "current");
}
static char *readFile(const char *name, size_t *size) {
    *size = 0; FILE *in = fopen(name, "rb");
    if (!in) return nullptr;
    char *data = (char *)calloc(USB_JOURNAL_FILE_BYTES + 1, 1);
    if (data) *size = fread(data, 1, USB_JOURNAL_FILE_BYTES, in);
    fclose(in); return data;
}
static void repair(bool previous) {
    char name[PATH_MAX]; path(name, previous); size_t n;
    char *data = readFile(name, &n);
    if (!data) return;
    size_t end = n;
    while (end && data[end - 1] != '\n') --end;
    if (end != n) truncate(name, (off_t)end);
    data[end] = 0;
    for (const char *p = data; (p = strstr(p, "\"seq\":")); ++p) {
        uint64_t value = strtoull(p + 6, nullptr, 10);
        if (value > sequence) sequence = value;
    }
    free(data);
}
// This mutex is taken only by the disk worker and the explicitly requested export, never producers.
static bool writeBody(const char *record) {
    Text line; line.add("{\"seq\":%llu,\"process\":%llu,%s}\n", (unsigned long long)++sequence, (unsigned long long)processSession, record);
    if (line.failed || line.size > USB_JOURNAL_FILE_BYTES) { lose(); return false; }
    pthread_mutex_lock(&fileLock);
#ifdef POLYLUTE_JOURNAL_TEST
    const int pause = __atomic_load_n(&testDelay, __ATOMIC_RELAXED);
    if (pause > 0) usleep(pause * 1000);
#endif
    char current[PATH_MAX], previous[PATH_MAX]; path(current, false); path(previous, true);
    if (fileBytes + line.size > USB_JOURNAL_FILE_BYTES) {
        if (file) fclose(file);
        file = nullptr;
        if (rename(current, previous) != 0 && errno != ENOENT) {
            __atomic_fetch_add(&ioErrors, 1, __ATOMIC_RELAXED); lose();
            pthread_mutex_unlock(&fileLock); return false; // never append past the cap after a failed rotation
        }
        fileBytes = 0;
    }
    if (!file) file = fopen(current, "ab");
    const bool ok = file && fwrite(line.data, 1, line.size, file) == line.size && fflush(file) == 0;
    if (ok) fileBytes += line.size;
    else {
        __atomic_fetch_add(&ioErrors, 1, __ATOMIC_RELAXED); lose();
        if (file) fclose(file);
        file = nullptr;
        truncate(current, (off_t)fileBytes); // discard a partial tail AFTER stdio has finished closing
    }
    pthread_mutex_unlock(&fileLock);
    return ok;
}
static void snapshot() {
    // One immutable slot, reserved by the producer. Slow compression/I/O cannot hold the queue.
    pthread_mutex_lock(&snapshotLock);
    UsbSecondFrozen &s = frozen.ring;
    int firstEvent = 0;
    for (;;) {
        Text text;
        text.add("SNAPSHOT monoMs=%lld wallMs=%lld windowMs=128000 samples=%d events=%d omittedEvents=%d ringEventLostAtMs=%lld ringSampleLostAtMs=%lld wallModel=anchor-plus-monotonic-delta\n",
            (long long)s.atMs, (long long)frozen.wall, s.samples, s.events - firstEvent, firstEvent,
            (long long)s.eventLostAtMs, (long long)s.sampleLostAtMs);
        for (int i = firstEvent; i < s.events; ++i) {
            Record r = {}; r.mono = s.event[i].atMs; r.wall = frozen.wall + r.mono - s.atMs;
            r.data.event = s.event[i]; r.facts = factsFor(s.event[i].stream, frozen.meta);
            text.add("{\"wallEstimated\":true,"); body(text, r); text.add("}\n");
        }
        for (int i = 0; i < s.samples; ++i) {
            Record r = {}; r.kind = 1; r.data.second = s.sample[i];
            r.mono = s.atMs; r.wall = frozen.wall;
            r.facts = factsFor(r.data.second.stream, frozen.meta);
            text.add("{"); body(text, r); text.add("}\n");
        }
        uLongf bytes = compressBound(text.size);
        unsigned char *packed = (unsigned char *)malloc(bytes);
        if (text.failed || !packed || compress2(packed, &bytes, (const Bytef *)text.data, text.size, Z_BEST_SPEED) != Z_OK) {
            free(packed); lose(); break;
        }
        // The complete snapshot is normally a few KiB. In an incompressible worst case the
        // two fixed files cannot hold the >1MiB source; preserve seconds/latest events and say so.
        if (bytes * 2 + 512 > USB_JOURNAL_FILE_BYTES && firstEvent < s.events) {
            free(packed); firstEvent += (s.events - firstEvent + 3) / 4; continue;
        }
        Text line;
        const UsbJournalFacts trigger = factsFor(frozen.triggerStream, frozen.meta);
        line.add("\"connection\":%lld,\"generation\":%lld,\"stream\":%lld,\"rate\":%d,\"bits\":%d,\"alt\":%d,", (long long)trigger.connection, (long long)trigger.generation, (long long)frozen.triggerStream, trigger.rate, trigger.bits, trigger.alt);
        line.add("\"kind\":\"snapshot\",\"monoMs\":%lld,\"wallMs\":%lld,\"encoding\":\"zlib-hex\",\"rawBytes\":%zu,\"omittedEvents\":%d,\"data\":\"",
            (long long)s.atMs, (long long)frozen.wall, text.size, firstEvent);
        for (uLongf i = 0; i < bytes; ++i) line.add("%02x", packed[i]);
        line.add("\""); free(packed);
        if (!line.failed) writeBody(line.data); else lose();
        break;
    }
    pthread_mutex_unlock(&snapshotLock);
}

static pthread_mutex_t referenceLock = PTHREAD_MUTEX_INITIALIZER;
static UsbFeedbackReference references[USB_FEEDBACK_REFERENCE_CAPACITY];
static int referenceCount, referenceHead;
static int referencesDirty;
static bool referenceKey(const char *key) {
    if (!key || strlen(key) != 64) return false;
    for (int i = 0; i < 64; ++i) if (!((key[i] >= '0' && key[i] <= '9') || (key[i] >= 'a' && key[i] <= 'f'))) return false;
    return true;
}
static void referencePath(char out[PATH_MAX], bool temporary) {
    snprintf(out, PATH_MAX, "%s/feedback-reference%s", directory, temporary ? ".tmp" : ".txt");
}
static void loadReferences() {
    memset(references, 0, sizeof(references)); referenceCount = referenceHead = 0;
    __atomic_store_n(&referencesDirty, 0, __ATOMIC_RELEASE);
    char name[PATH_MAX]; referencePath(name, false);
    FILE *in = fopen(name, "rb"); if (!in) return;
    char text[8193]; size_t size = fread(text, 1, sizeof(text), in); fclose(in);
    if (size > 8192) return;
    text[size] = 0; unsigned checksum = 0; int offset = 0;
    if (sscanf(text, "B635REF1 %x\n%n", &checksum, &offset) != 1 || offset <= 0 ||
        crc32(0, (const Bytef *)text + offset, size - offset) != checksum) return;
    char *line = text + offset;
    while (*line && referenceCount < USB_FEEDBACK_REFERENCE_CAPACITY) {
        UsbFeedbackReference r = {}; unsigned long long session = 0; int n = 0;
        if (sscanf(line, "%64s %u %u %llu\n%n", r.key, &r.raw, &r.sessions, &session, &n) != 4 ||
            n <= 0 || !referenceKey(r.key) || !r.raw || r.sessions > 2) { referenceCount = 0; return; }
        r.lastSession = session; references[referenceCount++] = r; line += n;
    }
    if (*line) referenceCount = 0;
    referenceHead = referenceCount % USB_FEEDBACK_REFERENCE_CAPACITY;
}
static void flushReferences() {
    if (!__atomic_load_n(&referencesDirty, __ATOMIC_ACQUIRE)) return;
    UsbFeedbackReference copy[USB_FEEDBACK_REFERENCE_CAPACITY]; int count;
    pthread_mutex_lock(&referenceLock);
    count = referenceCount; memcpy(copy, references, sizeof(copy));
    __atomic_store_n(&referencesDirty, 0, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&referenceLock);
    Text text;
    for (int i = 0; i < count; ++i) text.add("%s %u %u %llu\n", copy[i].key, copy[i].raw, copy[i].sessions, (unsigned long long)copy[i].lastSession);
    char current[PATH_MAX], temporary[PATH_MAX]; referencePath(current, false); referencePath(temporary, true);
    FILE *out = text.size <= 8192 - 32 ? fopen(temporary, "wb") : nullptr;
    bool ok = out && !text.failed;
    if (out) {
        ok = ok && fprintf(out, "B635REF1 %08x\n", (unsigned)crc32(0, (const Bytef *)text.data, text.size)) > 0 &&
            fwrite(text.data, 1, text.size, out) == text.size;
        if (fclose(out) != 0) ok = false;
    }
    if (ok) ok = rename(temporary, current) == 0;
    if (!ok) { unlink(temporary); __atomic_fetch_add(&ioErrors, 1, __ATOMIC_RELAXED); lose(); }
}

static void *drain(void *) {
    setpriority(PRIO_PROCESS, 0, 10); // background evidence must not inherit the audio thread's urgency
    locale_t numeric = newlocale(LC_NUMERIC_MASK, "C", nullptr);
    locale_t previousLocale = numeric ? uselocale(numeric) : (locale_t)0;
    pthread_mutex_lock(&fileLock);
    sequence = 0; repair(true); repair(false);
    char current[PATH_MAX]; path(current, false); struct stat st;
    fileBytes = stat(current, &st) == 0 ? (size_t)st.st_size : 0;
    pthread_mutex_unlock(&fileLock);
    uint64_t oldDrops = 0, oldErrors = 0;
    for (;;) {
        pthread_mutex_lock(&queueLock);
        while (!count && !snapshotPending && !__atomic_load_n(&referencesDirty, __ATOMIC_ACQUIRE) && !stopping) { working = false; pthread_cond_broadcast(&idle); pthread_cond_wait(&ready, &queueLock); }
        if (!count && !snapshotPending && !__atomic_load_n(&referencesDirty, __ATOMIC_ACQUIRE) && stopping) { working = false; pthread_cond_broadcast(&idle); pthread_mutex_unlock(&queueLock); break; }
        working = true;
        const bool snap = snapshotPending;
        Record record = {};
        const bool haveRecord = count != 0;
        if (!snap && haveRecord) { record = queue[(head - count + USB_JOURNAL_QUEUE_CAPACITY) % USB_JOURNAL_QUEUE_CAPACITY]; --count; }
        pthread_mutex_unlock(&queueLock);
        const uint64_t lost = __atomic_load_n(&dropped, __ATOMIC_RELAXED), errors = __atomic_load_n(&ioErrors, __ATOMIC_RELAXED);
        if (lost != oldDrops || errors != oldErrors) {
            Text line; line.add("\"kind\":\"journal-loss\",\"monoMs\":%lld,\"wallMs\":%lld,\"dropped\":%llu,\"ioErrors\":%llu",
                (long long)millis(CLOCK_MONOTONIC), (long long)millis(CLOCK_REALTIME), (unsigned long long)lost, (unsigned long long)errors);
            if (writeBody(line.data)) { oldDrops = lost; oldErrors = errors; }
        }
        if (snap) {
            snapshot(); pthread_mutex_lock(&queueLock); snapshotPending = false; pthread_mutex_unlock(&queueLock);
        } else if (haveRecord) { Text line; body(line, record); if (!line.failed) writeBody(line.data); else lose(); }
        flushReferences();
    }
    pthread_mutex_lock(&fileLock); if (file) fclose(file); file = nullptr; pthread_mutex_unlock(&fileLock);
    if (numeric) { uselocale(previousLocale); freelocale(numeric); }
    return nullptr;
}
static bool producer() {
    if (!enabled()) return false;
    if (pthread_mutex_trylock(&queueLock) != 0) { lose(); return false; }
    if (!enabled()) { pthread_mutex_unlock(&queueLock); return false; }
    return true;
}
static void offer(Record &r) { // queueLock held
    if (count == USB_JOURNAL_QUEUE_CAPACITY) { lose(); return; }
    r.wall = millis(CLOCK_REALTIME);
    const int64_t stream = r.kind == 1 ? r.data.second.stream : r.data.event.stream;
    if (stream > newestStream) {
        newestStream = stream;
        metadata[metaHead].stream = stream; metadata[metaHead].facts = currentFacts;
        metaHead = (metaHead + 1) % USB_JOURNAL_METADATA_CAPACITY;
    }
    if (r.kind != 3) r.facts = stream ? factsFor(stream, metadata) : currentFacts;
    queue[head] = r; head = (head + 1) % USB_JOURNAL_QUEUE_CAPACITY; ++count;
    pthread_cond_signal(&ready);
}
}

bool usbJournalConfigure(const char *location) {
    if (!location || strlen(location) + 32 >= sizeof(directory)) return false;
    pthread_mutex_lock(&lifecycle);
    if (enabled()) { const bool same = strcmp(directory, location) == 0; pthread_mutex_unlock(&lifecycle); return same; }
    if (mkdir(location, 0700) != 0 && errno != EEXIST) { pthread_mutex_unlock(&lifecycle); return false; }
    snprintf(directory, sizeof(directory), "%s", location);
    loadReferences();
    head = count = metaHead = 0; newestStream = 0;
    processSession = (uint64_t)millis(CLOCK_REALTIME) * 1000000 + (uint64_t)getpid(); working = true; stopping = snapshotPending = false;
    memset(metadata, 0, sizeof(metadata)); memset(&currentFacts, 0, sizeof(currentFacts));
    __atomic_store_n(&configured, 1, __ATOMIC_RELEASE);
    const bool ok = pthread_create(&thread, nullptr, drain, nullptr) == 0;
    if (!ok) __atomic_store_n(&configured, 0, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&lifecycle); return ok;
}

void usbJournalEvent(UsbSecondRing *ring, const UsbPolicyEvent *event) {
    if (event->kind == USB_POLICY_DAC_LOCKED) {
        if (!enabled()) return;
        // A fault must freeze its window even if a normal record is being copied. This lock
        // protects memory only; the worker ALWAYS releases it before compression or disk I/O.
        pthread_mutex_lock(&queueLock);
        if (!enabled()) { pthread_mutex_unlock(&queueLock); return; }
    } else if (!producer()) return;
    Record r = {}; r.mono = event->atMs; r.data.event = *event; r.data.event.what = safeWhat(event->what); offer(r);
    if (ring && event->kind == USB_POLICY_DAC_LOCKED) {
        if (snapshotPending || pthread_mutex_trylock(&snapshotLock) != 0) lose();
        else {
            if (usbSecondRingFreeze(ring, event->atMs, &frozen.ring)) {
                memcpy(frozen.meta, metadata, sizeof(metadata)); frozen.wall = millis(CLOCK_REALTIME); frozen.triggerStream = event->stream;
                snapshotPending = true; pthread_cond_signal(&ready);
            } else lose();
            pthread_mutex_unlock(&snapshotLock);
        }
    }
    pthread_mutex_unlock(&queueLock);
}

void usbJournalSecond(const UsbSecondSample *s, int64_t atMs) {
    if (!producer()) return;
    Record r = {}; r.kind = 1; r.mono = atMs; r.data.second = *s; offer(r); pthread_mutex_unlock(&queueLock);
}

void usbJournalPlatform(int64_t stream, const UsbJournalFacts *facts, int reason) {
    if (!producer()) return;
    // A new connection starts with unknown format; partial volume updates preserve known format.
    if (facts->connection != currentFacts.connection) currentFacts = *facts;
    else {
        currentFacts.generation = facts->generation;
        if (facts->rate) currentFacts.rate = facts->rate;
        if (facts->bits) currentFacts.bits = facts->bits;
        if (facts->alt) currentFacts.alt = facts->alt;
        if (facts->channels) currentFacts.channels = facts->channels;
        currentFacts.level = facts->level; currentFacts.verified = facts->verified;
    }
    if (stream) {
        int at = -1;
        for (int i = 0; i < USB_JOURNAL_METADATA_CAPACITY; ++i) if (metadata[i].stream == stream) { at = i; break; }
        if (at < 0) { at = metaHead; metaHead = (metaHead + 1) % USB_JOURNAL_METADATA_CAPACITY; }
        metadata[at].stream = stream; metadata[at].facts = currentFacts;
    }
    if (!stream && reason >= 3 && reason <= 5) {
        for (int i = 0; i < USB_JOURNAL_METADATA_CAPACITY; ++i) {
            if (metadata[i].facts.connection == currentFacts.connection && metadata[i].facts.generation == currentFacts.generation) {
                metadata[i].facts.level = currentFacts.level; metadata[i].facts.verified = currentFacts.verified;
            }
        }
    }
    if (stream > newestStream) newestStream = stream;
    Record r = {}; r.kind = 2; r.reason = reason; r.mono = millis(CLOCK_MONOTONIC);
    r.data.event.stream = stream; r.data.event.rate = currentFacts.rate; offer(r); pthread_mutex_unlock(&queueLock);
}

void usbJournalControl(int64_t connection, int64_t generation, int reason, int origin, int target, int result, int64_t elapsedMs) {
    if (reason < 7 || reason > 12 || origin < 0 || origin > 11 || !producer()) return;
    Record r = {}; r.kind = 3; r.reason = reason; r.mono = millis(CLOCK_MONOTONIC);
    if (currentFacts.connection == connection && currentFacts.generation == generation) {
        r.facts = currentFacts; r.data.event.stream = newestStream; r.data.event.rate = currentFacts.rate;
    } else r.facts = UsbJournalFacts{connection, generation, 0, 0, 0, INT32_MIN, 0, 0};
    r.data.event.packetStatus = origin;
    r.data.event.total = target;
    r.data.event.burst = result;
    r.data.event.delta = elapsedMs;
    offer(r); pthread_mutex_unlock(&queueLock);
}

void usbJournalBackoff(int64_t stream, int rate, int64_t atMs, int64_t nextMs, int retry) {
    UsbPolicyEvent e = {}; e.kind = (UsbPolicyEventKind)14; e.what = "reopen-backoff";
    e.stream = stream; e.rate = rate; e.atMs = atMs; e.delta = nextMs - atMs; e.total = retry; e.queueDepth = nextMs;
    usbJournalEvent(nullptr, &e);
}

bool usbJournalFlush(int timeoutMs) {
    if (!enabled()) return true;
    struct timespec deadline; clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_nsec += (timeoutMs % 1000) * 1000000L; deadline.tv_sec += timeoutMs / 1000 + deadline.tv_nsec / 1000000000L; deadline.tv_nsec %= 1000000000L;
    pthread_mutex_lock(&queueLock); pthread_cond_signal(&ready);
    int result = 0;
    while ((count || snapshotPending || working || __atomic_load_n(&referencesDirty, __ATOMIC_ACQUIRE)) && !result) result = pthread_cond_timedwait(&idle, &queueLock, &deadline);
    const bool ok = !count && !snapshotPending && !working && !__atomic_load_n(&referencesDirty, __ATOMIC_ACQUIRE);
    pthread_mutex_unlock(&queueLock); return ok;
}

char *usbJournalExport() {
    const bool flushed = usbJournalFlush(250);
    Text out; out.add("USB JOURNAL v1 flushed=%d dropped=%llu ioErrors=%llu\n", flushed ? 1 : 0,
        (unsigned long long)usbJournalDropped(), (unsigned long long)__atomic_load_n(&ioErrors, __ATOMIC_RELAXED));
    if (!enabled()) { out.add("journal-not-configured-in-this-process\n"); return out.data ? strdup(out.data) : nullptr; }
    if (pthread_mutex_trylock(&fileLock) != 0) { out.add("journal-export-busy\n"); return strdup(out.data); }
    for (int pass = 0; pass < 2; ++pass) {
        const bool previous = pass == 0;
        char name[PATH_MAX]; path(name, previous); size_t size; char *data = readFile(name, &size);
        out.add("-- %s.jsonl --\n", previous ? "previous" : "current");
        if (!data) continue;
        char *at = data;
        while (char *end = strchr(at, '\n')) {
            *end = 0;
            const char *packed = strstr(at, "\"data\":\""); const char *raw = strstr(at, "\"rawBytes\":");
            if (packed && raw && strstr(at, "\"encoding\":\"zlib-hex\"")) {
                out.add("%.*s\"data\":\"expanded below\"}\n", (int)(packed - at), at);
                packed += 8; const char *last = strchr(packed, '"');
                uLongf bytes = strtoul(raw + 11, nullptr, 10);
                const size_t length = last ? (size_t)(last - packed) / 2 : 0;
                Bytef *compressed = (Bytef *)malloc(length), *plain = bytes <= 8 * 1024 * 1024 ? (Bytef *)malloc(bytes + 1) : nullptr;
                if (compressed && plain && last && (last - packed) % 2 == 0) {
                    bool valid = true;
                    for (size_t i = 0; i < length; ++i) {
                        unsigned value = 0;
                        for (int j = 0; j < 2; ++j) {
                            const char c = packed[i * 2 + j];
                            const int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
                            if (digit < 0) valid = false;
                            value = value * 16 + (digit < 0 ? 0 : digit);
                        }
                        compressed[i] = (Bytef)value;
                    }
                    if (valid && uncompress(plain, &bytes, compressed, length) == Z_OK) { plain[bytes] = 0; out.add("-- frozen context --\n%s-- end frozen context --\n", plain); }
                    else out.add("snapshot-corrupt\n");
                } else out.add("snapshot-unreadable\n");
                free(compressed); free(plain);
            } else out.add("%s\n", at);
            at = end + 1;
        }
        if (*at) out.add("truncated-last-record-ignored\n");
        free(data);
    }
    pthread_mutex_unlock(&fileLock);
    if (out.failed) {
        if (out.size > 8 * 1024 * 1024 - 128) out.size = 8 * 1024 * 1024 - 128;
        out.failed = false; out.add("\nTRUNCATED journal export capacity\n");
    }
    return out.data ? strdup(out.data) : nullptr;
}

void usbJournalShutdown() {
    pthread_mutex_lock(&lifecycle);
    if (enabled()) {
        __atomic_store_n(&configured, 0, __ATOMIC_RELEASE);
        pthread_mutex_lock(&queueLock); stopping = true; pthread_cond_signal(&ready); pthread_mutex_unlock(&queueLock);
        pthread_join(thread, nullptr);
    }
    pthread_mutex_unlock(&lifecycle);
}
size_t usbJournalFixedBytes() { return sizeof(references) + sizeof(referenceLock) + sizeof(queue) + sizeof(frozen) + sizeof(metadata) + sizeof(currentFacts) + sizeof(directory) + sizeof(lifecycle) + sizeof(queueLock) + sizeof(snapshotLock) + sizeof(fileLock) + sizeof(ready) + sizeof(idle); }
uint64_t usbJournalDropped() { return __atomic_load_n(&dropped, __ATOMIC_RELAXED); }
#ifdef POLYLUTE_JOURNAL_TEST
void usbJournalTestDelay(int ms) { __atomic_store_n(&testDelay, ms, __ATOMIC_RELAXED); }
#endif

uint64_t usbJournalReferenceSession(int64_t stream) {
    return processSession ^ ((uint64_t)stream * 0x9e3779b97f4a7c15ULL);
}
bool usbJournalReferenceGet(const char *key, UsbFeedbackReference *out) {
    memset(out, 0, sizeof(*out));
    if (!referenceKey(key)) return false;
    snprintf(out->key, sizeof(out->key), "%s", key);
    pthread_mutex_lock(&referenceLock);
    bool found = false;
    for (int i = 0; i < referenceCount; ++i) if (!strcmp(references[i].key, key)) { *out = references[i]; found = true; break; }
    pthread_mutex_unlock(&referenceLock); return found;
}
bool usbJournalReferenceLearn(const char *key, uint32_t raw, double nominal, uint64_t session, UsbFeedbackReference *out) {
    if (!enabled() || !referenceKey(key) || !raw || nominal <= 0 || pthread_mutex_trylock(&referenceLock)) return false;
    int at = -1;
    for (int i = 0; i < referenceCount; ++i) if (!strcmp(references[i].key, key)) { at = i; break; }
    if (at < 0) {
        at = referenceHead; referenceHead = (referenceHead + 1) % USB_FEEDBACK_REFERENCE_CAPACITY;
        if (referenceCount < USB_FEEDBACK_REFERENCE_CAPACITY) ++referenceCount;
        memset(&references[at], 0, sizeof(references[at])); snprintf(references[at].key, sizeof(references[at].key), "%s", key);
    }
    UsbFeedbackReference &r = references[at];
    if (r.lastSession != session) {
        const bool agrees = r.sessions && fabs((raw - (double)r.raw) / 65536.0 / nominal * 1e6) <= USB_FEEDBACK_REFERENCE_STABLE_PPM;
        if (!agrees) { r.raw = raw; r.sessions = 1; }
        else { r.raw = (uint32_t)(((uint64_t)r.raw + raw) / 2); if (r.sessions < 2) ++r.sessions; }
        r.lastSession = session; __atomic_store_n(&referencesDirty, 1, __ATOMIC_RELEASE);
    }
    *out = r; pthread_mutex_unlock(&referenceLock); pthread_cond_signal(&ready); return true;
}
void usbJournalReferenceDegrade(const char *key) {
    if (!referenceKey(key)) return;
    pthread_mutex_lock(&referenceLock); // memory only; the consumer releases this BEFORE all file I/O
    for (int i = 0; i < referenceCount; ++i) if (!strcmp(references[i].key, key)) {
        references[i].sessions = 0; __atomic_store_n(&referencesDirty, 1, __ATOMIC_RELEASE); break;
    }
    pthread_mutex_unlock(&referenceLock); pthread_cond_signal(&ready);
}
