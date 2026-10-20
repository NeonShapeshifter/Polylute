#include "usb-second-ring.h"

#include <pthread.h>
#include <stdio.h>
#include <string.h>

// C headers and pthread only: the host test is built without the C++ standard library.
//
// The recording thread owns `current` and touches nothing else but at a second boundary; `ring`, `head` and `count` are
// written there and read by usbSecondRingLast, both under the lock. So the per-reading path is lock-free, and the lock is taken
// once a second by the recorder.
static pthread_mutex_t gSecondRingLock = PTHREAD_MUTEX_INITIALIZER;

static void clearSample(UsbSecondSample *s, int64_t second, double nominal) {
    memset(s, 0, sizeof(*s));
    s->second = second;
    s->nominalFpmf = nominal;
}

/** Moves to the second [nowMs] is in, pushing the finished one into the ring. */
static void advance(UsbSecondRing *r, int64_t nowMs) {
    const int64_t second = nowMs / 1000;
    if (r->hasCurrent && r->current.second == second) return;
    if (r->hasCurrent && r->current.second < second) {
        pthread_mutex_lock(&gSecondRingLock);
        r->ring[r->head] = r->current;
        r->head = (r->head + 1) % USB_SECOND_RING_CAPACITY;
        if (r->count < USB_SECOND_RING_CAPACITY) r->count++;
        pthread_mutex_unlock(&gSecondRingLock);
    }
    clearSample(&r->current, second, r->hasCurrent ? r->current.nominalFpmf : 0.0);
    r->hasCurrent = true;
}

void usbSecondRingInit(UsbSecondRing *r) {
    memset(r, 0, sizeof(*r));
}

void usbSecondRingFeedback(UsbSecondRing *r, int64_t nowMs, double fpmf, double nominalFpmf, bool deviating) {
    advance(r, nowMs);
    UsbSecondSample *s = &r->current;
    s->nominalFpmf = nominalFpmf;
    if (s->readings == 0) {
        s->fpmfMin = s->fpmfMax = fpmf;
    } else {
        if (fpmf < s->fpmfMin) s->fpmfMin = fpmf;
        if (fpmf > s->fpmfMax) s->fpmfMax = fpmf;
    }
    s->fpmfSum += fpmf;
    s->readings++;
    if (deviating) s->deviating++;
}

void usbSecondRingRefused(UsbSecondRing *r, int64_t nowMs) {
    advance(r, nowMs);
    r->current.refused++;
}

void usbSecondRingIso(UsbSecondRing *r, int64_t nowMs, int packets, int packetErrors, int shortPackets) {
    advance(r, nowMs);
    UsbSecondSample *s = &r->current;
    s->isoUrbs++;
    s->isoPackets += packets;
    s->isoPacketErrors += packetErrors;
    s->isoShortPackets += shortPackets;
}

int usbSecondRingLast(UsbSecondRing *r, int64_t nowMs, int64_t windowMs, UsbSecondSample *out, int max) {
    int n = 0;
    pthread_mutex_lock(&gSecondRingLock);
    const int64_t nowSecond = nowMs / 1000;
    const int64_t firstSecond = nowSecond - windowMs / 1000;
    for (int i = 0; i < r->count && n < max; i++) {
        // oldest first: the oldest entry is `count` slots behind the head
        const UsbSecondSample *s = &r->ring[(r->head - r->count + i + USB_SECOND_RING_CAPACITY) % USB_SECOND_RING_CAPACITY];
        if (s->second < firstSecond || s->second >= nowSecond) continue;
        out[n++] = *s;
    }
    pthread_mutex_unlock(&gSecondRingLock);
    return n;
}

size_t usbSecondRingFormat(const UsbSecondSample *samples, int n, int64_t nowMs, char *out, size_t capacity) {
    if (capacity == 0) return 0;
    size_t used = 0;
    out[0] = '\0';
    const int64_t nowSecond = nowMs / 1000;
    for (int i = 0; i < n && used + 1 < capacity; i++) {
        const UsbSecondSample *s = &samples[i];
        const double mean = s->readings > 0 ? s->fpmfSum / s->readings : 0.0;
        const int wrote = snprintf(
            out + used, capacity - used,
            "t-%llds feedback n=%d fpmf min=%.5f max=%.5f mean=%.5f nominal=%.5f deviating=%d refused=%d | iso urbs=%d packets=%d errors=%d short=%d\n",
            (long long)(nowSecond - s->second), s->readings, s->fpmfMin, s->fpmfMax, mean, s->nominalFpmf, s->deviating, s->refused,
            s->isoUrbs, s->isoPackets, s->isoPacketErrors, s->isoShortPackets);
        if (wrote < 0) break;
        if ((size_t)wrote >= capacity - used) {  // truncated: say so rather than cut a line in half
            used = capacity - 1;
            break;
        }
        used += (size_t)wrote;
    }
    return used;
}
