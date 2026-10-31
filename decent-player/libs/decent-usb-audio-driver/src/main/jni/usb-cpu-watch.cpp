#include "usb-cpu-watch.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

bool usbParseCpuList(const char *text, uint64_t *maskOut, int *countOut) {
    if (text == nullptr) return false;
    uint64_t mask = 0;
    const char *p = text;
    bool any = false;
    while (*p != '\0' && *p != '\n') {
        if (*p < '0' || *p > '9') return false;
        char *end = nullptr;
        long lo = strtol(p, &end, 10);
        long hi = lo;
        p = end;
        if (*p == '-') {
            p++;
            if (*p < '0' || *p > '9') return false;
            hi = strtol(p, &end, 10);
            p = end;
        }
        if (lo < 0 || hi < lo || hi > 63) return false;
        for (long cpu = lo; cpu <= hi; cpu++) mask |= (uint64_t)1 << cpu;
        any = true;
        if (*p == ',') {
            p++;
            if (*p == '\0' || *p == '\n') return false;
        } else if (*p != '\0' && *p != '\n') {
            return false;
        }
    }
    if (!any) return false;
    *maskOut = mask;
    int count = 0;
    for (int i = 0; i < 64; i++) count += (int)((mask >> i) & 1);
    *countOut = count;
    return true;
}

void usbFormatCpuList(uint64_t mask, char *out, size_t capacity) {
    if (capacity == 0) return;
    out[0] = '\0';
    size_t used = 0;
    int i = 0;
    while (i < 64) {
        if (!((mask >> i) & 1)) { i++; continue; }
        int j = i;
        while (j + 1 < 64 && ((mask >> (j + 1)) & 1)) j++;
        const int wrote = i == j ? snprintf(out + used, capacity - used, "%s%d", used ? "," : "", i)
                                 : snprintf(out + used, capacity - used, "%s%d-%d", used ? "," : "", i, j);
        if (wrote < 0 || (size_t)wrote >= capacity - used) break;
        used += (size_t)wrote;
        i = j + 1;
    }
}

bool usbParseThreadStat(const char *line, UsbThreadStat *out) {
    memset(out, 0, sizeof(*out));
    if (line == nullptr) return false;
    const char *open = strchr(line, '(');
    const char *close = strrchr(line, ')');
    if (open == nullptr || close == nullptr || close < open) return false;
    size_t n = (size_t)(close - open - 1);
    if (n >= USB_CPU_WATCH_COMM) n = USB_CPU_WATCH_COMM - 1;
    memcpy(out->comm, open + 1, n);
    out->comm[n] = '\0';
    // after the ')': " S ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime ... processor" — field 3 is the state, 14 and 15 the times, 39 the CPU
    const char *p = close + 1;
    int field = 3;
    uint64_t utime = 0, stime = 0;
    long processor = -1;
    while (*p != '\0' && field <= 39) {
        while (*p == ' ') p++;
        if (*p == '\0' || *p == '\n') break;
        const char *start = p;
        while (*p != ' ' && *p != '\0' && *p != '\n') p++;
        if (field == 3) out->state = *start;
        if (field == 14) utime = strtoull(start, nullptr, 10);
        if (field == 15) stime = strtoull(start, nullptr, 10);
        if (field == 39) processor = strtol(start, nullptr, 10);
        field++;
    }
    if (field <= 39) return false;   // the line stopped before the CPU field
    out->utime = utime;
    out->stime = stime;
    out->processor = (int)processor;
    out->valid = true;
    return true;
}

int usbParseProcStat(const char *text, UsbCpuTimes *cpus, int capacity) {
    int count = 0;
    if (text == nullptr) return 0;
    const char *p = text;
    while (*p != '\0' && count < capacity) {
        if (p[0] == 'c' && p[1] == 'p' && p[2] == 'u' && p[3] >= '0' && p[3] <= '9') {
            char *end = nullptr;
            const long cpu = strtol(p + 3, &end, 10);
            uint64_t v[10] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};   // user nice system idle iowait irq softirq steal guest guest_nice
            int got = 0;
            const char *q = end;
            while (got < 10) {
                while (*q == ' ') q++;
                if (*q < '0' || *q > '9') break;
                v[got++] = strtoull(q, const_cast<char **>(&q), 10);
            }
            if (got >= 8) {   // user nice system idle iowait irq softirq steal: guest time is already inside user
                uint64_t total = 0;
                for (int i = 0; i < 8; i++) total += v[i];
                UsbCpuTimes &t = cpus[count++];
                t.valid = true;
                t.cpu = (int)cpu;
                t.total = total;
                t.busy = total - v[3] - v[4];
            }
        }
        const char *nl = strchr(p, '\n');
        if (nl == nullptr) break;
        p = nl + 1;
    }
    return count;
}

void usbFormatCpuBusy(const UsbCpuTimes *before, int beforeCount, const UsbCpuTimes *now, int nowCount, char *out, size_t capacity) {
    if (capacity == 0) return;
    out[0] = '\0';
    size_t used = 0;
    for (int i = 0; i < nowCount; i++) {
        const UsbCpuTimes *b = nullptr;
        for (int j = 0; j < beforeCount; j++) {
            if (before[j].cpu == now[i].cpu) { b = &before[j]; break; }
        }
        if (b == nullptr || now[i].total <= b->total || now[i].busy < b->busy) continue;
        const unsigned percent = (unsigned)((now[i].busy - b->busy) * 100 / (now[i].total - b->total));
        const int wrote = snprintf(out + used, capacity - used, "%s%d:%u%%", used ? " " : "", now[i].cpu, percent);
        if (wrote < 0 || (size_t)wrote >= capacity - used) break;
        used += (size_t)wrote;
    }
}

void usbFormatTopThreads(const UsbThreadTicks *before, int beforeCount, const UsbThreadTicks *now, int nowCount, int top, char *out, size_t capacity) {
    if (capacity == 0) return;
    out[0] = '\0';
    uint64_t delta[USB_CPU_WATCH_MAX_THREADS];
    bool taken[USB_CPU_WATCH_MAX_THREADS];
    const int n = nowCount < USB_CPU_WATCH_MAX_THREADS ? nowCount : USB_CPU_WATCH_MAX_THREADS;
    for (int i = 0; i < n; i++) {
        uint64_t base = 0;
        for (int j = 0; j < beforeCount; j++) {
            if (before[j].tid == now[i].tid) { base = before[j].ticks; break; }
        }
        delta[i] = now[i].ticks >= base ? now[i].ticks - base : 0;
        taken[i] = false;
    }
    size_t used = 0;
    for (int k = 0; k < top; k++) {
        int best = -1;
        for (int i = 0; i < n; i++) {
            if (!taken[i] && delta[i] >= 1 && (best < 0 || delta[i] > delta[best])) best = i;
        }
        if (best < 0) break;
        taken[best] = true;
        const int wrote = snprintf(out + used, capacity - used, "%s%s:%llums", used ? "," : "", now[best].comm, (unsigned long long)delta[best] * 10);
        if (wrote < 0 || (size_t)wrote >= capacity - used) break;
        used += (size_t)wrote;
    }
}
