// A virtual clock for the host simulations (B27, A0: the native gate must not depend on the host's speed).
//
// Why: the fake usbfs plays what the writer submits against the wall clock. The writer is real code on a real
// thread, so a host that is slow (a laptop on battery in `powersave`, 400-670 MHz; a build running next to it)
// delays the writer by milliseconds, and the fake DAC, which keeps playing, finds the ring dry. The verdict then said
// how fast the machine was, not whether the code was right: `natural-join-test.cpp:904` ("2 empty stretches") failed 9/9
// in the review, and the OUTPUT_GAP / seek checks failed on and off in B26 and B26-fix1.
//
// What: inside a `VirtualTime` scope the process has ONE thread that matters (the test thread, which is the writer),
// and time moves only when that thread waits: the fake `poll`, `usleep`, `sleepMs`, or `advanceTo`. CPU work takes no
// virtual time, so a slower host runs the same sequence in more wall time and gets the same verdict. Things that
// would be another thread in real time (the host that requests a pause 60 ms into a block) are scheduled events that
// run on this thread when the clock passes them, in order.
//
// The production objects read `clock_gettime(CLOCK_MONOTONIC)` and call `usleep`; this header defines both in the
// test executable (as the test already does for `ioctl` and `poll`), so the code under test is untouched. Outside a
// virtual scope they forward to the real functions; CLOCK_MONOTONIC then carries an offset so that it never goes
// backwards after a scope that ran ahead of the wall clock. CLOCK_REALTIME is never virtual: `pthread_cond_timedwait`
// takes absolute REALTIME deadlines and the engine's threads wait on them in the real-time tests.
//
// Include in exactly one translation unit of an executable.
#pragma once

#include <dlfcn.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

namespace simclock {

struct Event {
    int64_t atNs;
    int64_t seq;
    std::function<void()> fn;
};

struct State {
    std::atomic<bool> virt{false};
    std::atomic<int> depth{0};
    std::atomic<int64_t> vnowNs{0};
    std::atomic<int64_t> offsetNs{0};  // added to the real monotonic clock outside a scope: it never goes backwards
    std::mutex mu;                     // the event list and entering / leaving a scope
    std::vector<Event> events;
    int64_t seq = 0;
};

inline State &state() {
    static State s;
    return s;
}

inline int realClockGettime(clockid_t clk, struct timespec *ts) {
    typedef int (*Fn)(clockid_t, struct timespec *);
    static Fn fn = reinterpret_cast<Fn>(dlsym(RTLD_NEXT, "clock_gettime"));
    return fn(clk, ts);
}

inline int64_t realMonotonicNs() {
    struct timespec ts = {};
    realClockGettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

inline bool isVirtual() { return state().virt.load(std::memory_order_acquire); }

/** The monotonic clock of the process: virtual inside a scope, the wall clock plus the carried offset outside. */
inline int64_t nowNs() {
    State &s = state();
    if (s.virt.load(std::memory_order_acquire)) return s.vnowNs.load(std::memory_order_acquire);
    return realMonotonicNs() + s.offsetNs.load(std::memory_order_acquire);
}

/** Runs [fn] on the clock's thread when virtual time reaches [atNs]. Only inside a scope. */
inline void at(int64_t atNs, std::function<void()> fn) {
    State &s = state();
    std::lock_guard<std::mutex> lock(s.mu);
    s.events.push_back(Event{atNs, s.seq++, std::move(fn)});
}

/** Moves virtual time to [targetNs], running the events that fall on the way in the order of their time. */
inline void advanceTo(int64_t targetNs) {
    State &s = state();
    for (;;) {
        std::function<void()> fn;
        int64_t when = 0;
        {
            std::lock_guard<std::mutex> lock(s.mu);
            auto best = s.events.end();
            for (auto it = s.events.begin(); it != s.events.end(); ++it) {
                if (it->atNs > targetNs) continue;
                if (best == s.events.end() || it->atNs < best->atNs || (it->atNs == best->atNs && it->seq < best->seq)) best = it;
            }
            if (best == s.events.end()) break;
            when = best->atNs;
            fn = std::move(best->fn);
            s.events.erase(best);
        }
        if (when > s.vnowNs.load()) s.vnowNs.store(when, std::memory_order_release);
        fn();
    }
    if (targetNs > s.vnowNs.load()) s.vnowNs.store(targetNs, std::memory_order_release);
}

/** Waits [ns]: instantly, by moving the clock, inside a scope; for real outside it. */
inline void sleepNs(int64_t ns) {
    if (ns < 0) ns = 0;
    if (isVirtual()) {
        advanceTo(state().vnowNs.load() + ns);
        return;
    }
    std::this_thread::sleep_for(std::chrono::nanoseconds(ns));
}

/**
 * A virtual-time scope: while one is alive, time is the virtual clock. Scopes nest (the outermost one decides). The clock
 * starts where the process clock was, and leaving the outermost one carries any distance it ran ahead into the offset.
 */
class VirtualTime {
public:
    static constexpr int64_t kIsolationNs = 2000000000LL;
    VirtualTime() {
        State &s = state();
        std::lock_guard<std::mutex> lock(s.mu);
        if (s.depth.fetch_add(1) == 0) {
            // Two seconds of idle process before a scope: the output's glitch limiter keeps one line per kind per second in global
            // state, and tests that now run in no time must not have a line of the one before swallow the line of the next.
            s.vnowNs.store(realMonotonicNs() + s.offsetNs.load() + kIsolationNs);
            s.events.clear();
            s.virt.store(true, std::memory_order_release);
        }
    }
    ~VirtualTime() {
        State &s = state();
        std::lock_guard<std::mutex> lock(s.mu);
        if (s.depth.fetch_sub(1) == 1) {
            s.virt.store(false, std::memory_order_release);
            s.events.clear();
            const int64_t ahead = s.vnowNs.load() - realMonotonicNs() + kIsolationNs;
            if (ahead > s.offsetNs.load()) s.offsetNs.store(ahead);
        }
    }
    VirtualTime(const VirtualTime &) = delete;
    VirtualTime &operator=(const VirtualTime &) = delete;
};

}  // namespace simclock

extern "C" int clock_gettime(clockid_t clk, struct timespec *ts) noexcept {
    if (clk != CLOCK_MONOTONIC) return simclock::realClockGettime(clk, ts);
    const int64_t ns = simclock::nowNs();
    ts->tv_sec = (time_t)(ns / 1000000000LL);
    ts->tv_nsec = (long)(ns % 1000000000LL);
    return 0;
}

extern "C" int usleep(useconds_t us) {
    if (simclock::isVirtual()) {
        simclock::sleepNs((int64_t)us * 1000);
        return 0;
    }
    typedef int (*Fn)(useconds_t);
    static Fn fn = reinterpret_cast<Fn>(dlsym(RTLD_NEXT, "usleep"));
    return fn(us);
}
