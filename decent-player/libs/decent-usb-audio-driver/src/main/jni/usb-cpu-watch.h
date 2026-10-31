// Who had the CPU when the writer did not (B47-A1). Pure parsers and formatters, host-tested; the file reads are the glue in usb-audio-output.cpp.
//
// 2026-10-29 17:32:33: `OUTPUT_GAP gap=83 ms lead=80 ms ... runq=82 ms cpu=0 ms`. The writer (nice -19, SCHED_OTHER) was runnable for 82 ms and ran for 0. The line says THAT
// it was not given a CPU, not by whom. What an app can read without root:
//  - its own scheduling: `sched_getscheduler`, `getpriority`, `sched_getaffinity`, `/proc/thread-self/stat` (field 39: the CPU it last ran on), and the cpuset / cgroup
//    files of its own process (`/proc/self/cpuset`, `/proc/thread-self/cgroup`), `/proc/thread-self/sched` where the kernel has it (uclamp);
//  - its own process's threads: `/proc/self/task/<tid>/stat` (name, state, utime/stime, CPU), so which of ITS threads ran in a window (the GC, the queue builder, the main
//    thread, a pool);
//  - `/proc/stat` (per-CPU busy time) where the platform allows it: apps lost it in Android 8, so it is tried and the line says whether it was readable;
//  - NOT other processes: system_server, SystemUI, the media services, the kernel's own work. For those a system trace is the tool (`perfetto` with `sched/sched_switch`,
//    started from the shell; A6 has the command).
#ifndef USB_CPU_WATCH_H
#define USB_CPU_WATCH_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define USB_CPU_WATCH_MAX_CPUS 32
#define USB_CPU_WATCH_MAX_THREADS 256
#define USB_CPU_WATCH_COMM 16

/** "0-7", "0,2-4,6" -> a bit mask (CPUs 0..63) and how many CPUs it names. False for anything else (the list is untouched). */
bool usbParseCpuList(const char *text, uint64_t *maskOut, int *countOut);
/** A mask as the kernel writes it ("0-3,6-7"); [out] is always terminated. */
void usbFormatCpuList(uint64_t mask, char *out, size_t capacity);

/** The fields of /proc/<pid>/task/<tid>/stat that matter. */
typedef struct {
    bool valid;
    char comm[USB_CPU_WATCH_COMM];
    char state;            // R running/runnable, S sleeping, D uninterruptible, ...
    uint64_t utime, stime; // clock ticks (USER_HZ, 100 a second)
    int processor;         // the CPU it last ran on
} UsbThreadStat;

/** Parses one stat line. The command name is in parentheses and may contain spaces and parentheses: the fields are counted after the LAST ')'. */
bool usbParseThreadStat(const char *line, UsbThreadStat *out);

/** One /proc/stat "cpuN" line: busy = everything but idle and iowait, total = all of it. */
typedef struct {
    bool valid;
    int cpu;
    uint64_t busy, total;
} UsbCpuTimes;

/** Parses the per-CPU lines of /proc/stat ("cpu0 ..." — not the summed "cpu " line). Returns how many CPUs it filled. */
int usbParseProcStat(const char *text, UsbCpuTimes *cpus, int capacity);

/** "0:97% 1:12% 2:3% ..." — the busy share of each CPU between two readings of /proc/stat, or "" when they do not compare. */
void usbFormatCpuBusy(const UsbCpuTimes *before, int beforeCount, const UsbCpuTimes *now, int nowCount, char *out, size_t capacity);

/** A thread of the process at one reading. */
typedef struct {
    int tid;
    char comm[USB_CPU_WATCH_COMM];
    uint64_t ticks;        // utime + stime
} UsbThreadTicks;

/**
 * The busiest threads between two readings, most ticks first, as "name:120ms,other:80ms" (a tick is 10 ms). Threads that did not exist at [before] count from zero.
 * At most [top] are named; threads under one tick are left out. [windowMs] is only for the caller's line.
 */
void usbFormatTopThreads(const UsbThreadTicks *before, int beforeCount, const UsbThreadTicks *now, int nowCount, int top, char *out, size_t capacity);

#endif
