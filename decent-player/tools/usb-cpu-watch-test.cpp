// Host tests for B47-A1: the parsers behind the extra line of an OUTPUT_GAP whose writer sat in the run queue.
#include "usb-cpu-watch.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static void expectCpuLists() {
    uint64_t mask = 0;
    int count = 0;
    assert(usbParseCpuList("0-7\n", &mask, &count) && mask == 0xFF && count == 8);
    assert(usbParseCpuList("0,2-4,6", &mask, &count) && mask == ((1u << 0) | (1u << 2) | (1u << 3) | (1u << 4) | (1u << 6)) && count == 5);
    assert(usbParseCpuList("4-7", &mask, &count) && mask == 0xF0 && count == 4);
    assert(usbParseCpuList("3", &mask, &count) && mask == 8 && count == 1);
    const uint64_t keep = mask;
    assert(!usbParseCpuList("", &mask, &count) && mask == keep);
    assert(!usbParseCpuList("a-b", &mask, &count));
    assert(!usbParseCpuList("7-3", &mask, &count));
    assert(!usbParseCpuList("0,", &mask, &count));
    assert(!usbParseCpuList("0-99", &mask, &count));
    assert(!usbParseCpuList(nullptr, &mask, &count));
    char text[64];
    usbFormatCpuList(0xFF, text, sizeof(text));
    assert(strcmp(text, "0-7") == 0);
    usbFormatCpuList(((uint64_t)1 << 0) | ((uint64_t)1 << 2) | ((uint64_t)1 << 3) | ((uint64_t)1 << 4) | ((uint64_t)1 << 6), text, sizeof(text));
    assert(strcmp(text, "0,2-4,6") == 0);
    usbFormatCpuList(0, text, sizeof(text));
    assert(text[0] == '\0');
}

static void expectThreadStat() {
    // 39 fields, the last one the CPU: a name with a space and a parenthesis in it
    UsbThreadStat t;
    const char *line = "29746 (UsbStreaming (a)) R 1 1 0 0 -1 4194368 100 0 0 0 1234 567 0 0 20 0 80 0 100 1000000 5000 18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 0 17 5 0 0 0 0 0\n";
    assert(usbParseThreadStat(line, &t));
    assert(strcmp(t.comm, "UsbStreaming (a") == 0 || strncmp(t.comm, "UsbStreaming (a", 15) == 0);   // 15 characters: the kernel's own limit
    assert(t.state == 'R' && t.utime == 1234 && t.stime == 567 && t.processor == 5);
    assert(!usbParseThreadStat("29746 (x) R 1 1 0", &t));        // stops before the CPU field
    assert(!usbParseThreadStat("garbage", &t));
    assert(!usbParseThreadStat(nullptr, &t));
}

static void expectProcStat() {
    const char *text =
        "cpu  100 0 50 800 10 0 5 0 0 0\n"
        "cpu0 10 0 5 80 1 0 0 0 0 0\n"
        "cpu1 90 0 45 720 9 0 5 0 0 0\n"
        "intr 1 2 3\n";
    UsbCpuTimes now[8];
    assert(usbParseProcStat(text, now, 8) == 2);
    assert(now[0].cpu == 0 && now[0].total == 96 && now[0].busy == 15);   // everything but idle and iowait
    assert(now[1].cpu == 1 && now[1].busy == 140);
    const char *later =
        "cpu0 40 0 5 90 1 0 0 0 0 0\n"
        "cpu1 100 0 45 780 9 0 5 0 0 0\n";
    UsbCpuTimes after[8];
    assert(usbParseProcStat(later, after, 8) == 2);
    char line[128];
    usbFormatCpuBusy(now, 2, after, 2, line, sizeof(line));
    assert(strcmp(line, "0:75% 1:14%") == 0);                       // cpu0: 30 busy of 40; cpu1: 10 busy of 70
    usbFormatCpuBusy(after, 2, now, 2, line, sizeof(line));          // backwards: nothing comparable
    assert(line[0] == '\0');
    assert(usbParseProcStat("", now, 8) == 0 && usbParseProcStat(nullptr, now, 8) == 0);
}

static void expectTopThreads() {
    UsbThreadTicks before[3] = {{1, "main", 1000}, {2, "HeapTaskDaemon", 500}, {3, "pool-1", 10}};
    UsbThreadTicks now[5] = {{1, "main", 1009}, {2, "HeapTaskDaemon", 546}, {3, "pool-1", 10}, {4, "Default-3", 22}, {5, "idle", 0}};
    char line[160];
    usbFormatTopThreads(before, 3, now, 5, 3, line, sizeof(line));
    assert(strcmp(line, "HeapTaskDaemon:460ms,Default-3:220ms,main:90ms") == 0);   // a thread that appeared counts from zero; ones under a tick are left out
    usbFormatTopThreads(before, 3, now, 5, 1, line, sizeof(line));
    assert(strcmp(line, "HeapTaskDaemon:460ms") == 0);
    usbFormatTopThreads(now, 5, before, 3, 3, line, sizeof(line));   // backwards: counters never go below zero
    assert(line[0] == '\0' || strstr(line, "-") == nullptr);
}

int main() {
    expectCpuLists();
    expectThreadStat();
    expectProcStat();
    expectTopThreads();
    printf("usb cpu watch tests: PASS\n");
    return 0;
}
