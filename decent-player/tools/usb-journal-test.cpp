#ifdef B635_JOURNAL_BEFORE
#include <stdio.h>
extern bool usbJournalConfigure(const char *) __attribute__((weak));
int main() {
    if (!usbJournalConfigure) { puts("FAIL automatic bounded journal is absent from the before tree"); return 1; }
    return 0;
}
#else
#include "usb-journal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>



static int failures;
#define CHECK(c,m) do { if (!(c)) { printf("FAIL %s\n",m); ++failures; } } while(0)
static int64_t now() { timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000LL + t.tv_nsec / 1000000; }
static long bytes(const char *directory, const char *file) {
    char p[1024]; snprintf(p, sizeof(p), "%s/%s.jsonl", directory, file); struct stat st;
    return stat(p, &st) == 0 ? st.st_size : 0;
}
static void clean(const char *dir) {
    char p[1024]; const char *names[] = {"current", "previous"}; for (const char *f : names) { snprintf(p,sizeof(p),"%s/%s.jsonl",dir,f); unlink(p); } rmdir(dir);
}
static UsbPolicyEvent event(int64_t at, int64_t total = 0) {
    UsbPolicyEvent e = {}; e.atMs = at; e.stream = 42; e.rate = 96000;
    e.kind = USB_POLICY_QUEUE_TIMEOUT; e.what = "poll-100ms"; e.raw = 0xc0000;
    e.length = 4; e.delta = 1; e.total = total; e.burst = total % 10; return e;
}
static uint64_t maxSequence(const char *s) {
    uint64_t n = 0;
    for (const char *p = s; (p = strstr(p, "\"seq\":")); ++p) { uint64_t v = strtoull(p + 6, nullptr, 10); if (v > n) n = v; }
    return n;
}
int main() {

    char location[] = "/tmp/b635-journal-XXXXXX"; CHECK(mkdtemp(location), "temporary private directory");
    CHECK(usbJournalConfigure(location), "configure");
    UsbJournalFacts f = {7, 11, 96000, 32, 3, -2560, 1, 2}; usbJournalPlatform(42, &f, 6);
    UsbSecondRing *ring = (UsbSecondRing *)calloc(1, sizeof(UsbSecondRing)); usbSecondRingInit(ring);
    usbSecondRingStream(ring, now() - 127000, 42, 96000);
    const int64_t first = now() - 127000;
    for (int i = 0; i < 128; ++i) {
        usbSecondRingFeedback(ring, first + i * 1000, 12.0, 12.0, false);
        for (int k = 0; k < 10; ++k) {
            UsbPolicyEvent e = event(first + i * 1000 + k * 90, i * 10 + k);
            if (i == 0 && k == 0) { e.kind = USB_POLICY_STREAM_START; e.what = "initial-reading"; e.sentBefore = 5.5125; e.sentAfter = 12; }
            if (i == 64 && k == 0) { e.kind = USB_POLICY_OUTPUT_GAP; e.what = "bus-model"; e.dryUs = 24000; }
            usbSecondRingPolicyEvent(ring, &e);
        }
    }
    CHECK(usbJournalFlush(5000), "drain prior records");
    UsbPolicyEvent fault = event(first + 128000); fault.kind = USB_POLICY_DAC_LOCKED; fault.what = "no-readings"; fault.length = 0;
    usbSecondRingPolicyEvent(ring, &fault);
    CHECK(usbJournalFlush(5000), "automatic frozen snapshot finishes");
    char *text = usbJournalExport();
    CHECK(text && strstr(text, "events=1281 omittedEvents=0"), "full 128s event window preserved in automatic snapshot");
    CHECK(text && strstr(text, "samples=128"), "all 128 sample fragments preserved");
    CHECK(text && strstr(text, "initial-reading") && strstr(text, "OUTPUT_GAP") && strstr(text, "poll-100ms") && strstr(text, "no-readings"), "one export tells the complete incident");
    CHECK(text && strstr(text, "\"connection\":7,\"generation\":11,\"stream\":42"), "connection and owner metadata");
    printf("JOURNAL snapshot exportBytes=%zu samples=128 events=1281 compressed into two 128KiB files fixedBytes=%zu\n", text ? strlen(text) : 0, usbJournalFixedBytes()); free(text);
    usbJournalBackoff(42, 96000, now(), now() + 300000, 3);
    f.verified = 0; f.level = -1280; usbJournalPlatform(42, &f, 4);
    UsbPolicyEvent privateText = event(now()); privateText.what = "/storage/emulated/0/Music/PRIVATE_TITLE.flac user@example.com";
    usbSecondRingPolicyEvent(ring, &privateText);
    CHECK(usbJournalFlush(5000), "drain backoff and volume"); text = usbJournalExport();
    CHECK(strstr(text, "volume-unverified") && strstr(text, "nextRetryMs"), "volume hold and backoff have their own records");
    CHECK(!strstr(text, "PRIVATE_TITLE") && !strstr(text, "user@example.com") && !strstr(text, "/storage/"), "journal stores no arbitrary caller text or private paths"); free(text);
    for (int i = 0; i < 3000; ++i) {
        UsbPolicyEvent e = event(now(), i); usbJournalEvent(nullptr, &e);
        if (i % 16 == 0) CHECK(usbJournalFlush(5000), "rotation producer drain");
    }
    CHECK(usbJournalFlush(5000), "rotation completed"); text = usbJournalExport(); const uint64_t before = maxSequence(text); free(text);
    CHECK(bytes(location,"current") <= USB_JOURNAL_FILE_BYTES && bytes(location,"previous") <= USB_JOURNAL_FILE_BYTES && bytes(location,"previous") > 0, "rotates beyond 256KiB without growing");
    usbJournalShutdown();
    char current[1024]; snprintf(current,sizeof(current),"%s/current.jsonl",location); FILE *partial = fopen(current,"ab");
    fputs("{\"seq\":999999999,\"incomplete\":",partial); fclose(partial);
    CHECK(usbJournalConfigure(location), "restart with truncated tail");
    UsbPolicyEvent resumed = event(now(), 12345); usbJournalEvent(nullptr, &resumed); CHECK(usbJournalFlush(5000), "restart drain");
    text = usbJournalExport();
    CHECK(!strstr(text,"999999999") && maxSequence(text) > before, "truncated record ignored and sequence resumes"); free(text);
    usbJournalShutdown(); clean(location); free(ring);

    char killed[] = "/tmp/b635-journal-death-XXXXXX"; CHECK(mkdtemp(killed), "death directory");
    pid_t child = fork();
    if (child == 0) {
        if (!usbJournalConfigure(killed)) _exit(2);
        UsbJournalFacts childFacts = {88, 33, 44100, 24, 1, -500, 1, 2}; usbJournalPlatform(1, &childFacts, 6);
        _exit(usbJournalFlush(5000) ? 0 : 3); // no destructor, shutdown or logcat
    }
    int status = 0; waitpid(child, &status, 0); CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0, "child committed before process death");
    CHECK(usbJournalConfigure(killed), "new process reads private evidence"); text = usbJournalExport();
    CHECK(text && strstr(text,"\"connection\":88,\"generation\":33"), "survives process death with all logcat discarded"); free(text);
    usbJournalShutdown(); clean(killed);

    char slow[] = "/tmp/b635-journal-slow-XXXXXX"; CHECK(mkdtemp(slow), "slow I/O directory"); CHECK(usbJournalConfigure(slow),"slow I/O setup");
    usbJournalTestDelay(400); UsbPolicyEvent e = event(now()); usbJournalEvent(nullptr,&e); usleep(20000);
    const uint64_t oldDrops = usbJournalDropped(); const int64_t begin = now();
    for (int i = 0; i < 10000; ++i) { e.total = i; usbJournalEvent(nullptr,&e); }
    const int64_t elapsed = now() - begin;
    CHECK(elapsed < 250, "producer waited for the 400ms disk delay"); CHECK(usbJournalDropped() > oldDrops,"bounded queue counts overflow");
    usbJournalTestDelay(0); CHECK(usbJournalFlush(5000),"slow I/O catches up"); text = usbJournalExport(); CHECK(text && strstr(text,"journal-loss"),"loss is explicit in the exported journal"); free(text);
    printf("JOURNAL slowIO=400ms producer10000=%lldms dropped=%llu\n",(long long)elapsed,(unsigned long long)(usbJournalDropped()-oldDrops));
    usbJournalShutdown(); clean(slow);
    printf("USB journal %s failures=%d\n", failures ? "FAIL" : "PASS", failures); return failures ? 1 : 0;
}

#endif
