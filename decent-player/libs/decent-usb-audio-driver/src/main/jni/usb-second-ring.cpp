#include "usb-second-ring.h"

#include <pthread.h>
#include <stdio.h>
#include <string.h>

// C headers and pthread only: the host test is built without the C++ standard library.
//
// The recording thread owns `current` and touches nothing else but at a second boundary; `ring`, `head` and `count` are
// written there and read by usbSecondRingLast, both under the lock. So the per-reading path is lock-free, and the lock is taken
// once a second by the recorder. The policy events are rare; they are written and read under the same lock.
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
        // The age is that of the end of the second that is closing, from the last valid reading seen so far (a reading of a later
        // second has not happened yet: this is the first event after it).
        r->current.ageMs = r->lastValidMs > 0 ? (r->current.second + 1) * 1000 - r->lastValidMs : -1;
        if (r->current.ageMs < 0 && r->lastValidMs > 0) r->current.ageMs = 0;
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
    r->lastValidMs = nowMs;
}

void usbSecondRingRefused(UsbSecondRing *r, int64_t nowMs) {
    advance(r, nowMs);
    r->current.refused++;
}

void usbSecondRingHeld(UsbSecondRing *r, int64_t nowMs) {
    advance(r, nowMs);
    r->current.held++;
}

void usbSecondRingFeedbackDropped(UsbSecondRing *r, int64_t nowMs, int kind, int urbStatus, int packetStatus, unsigned int length) {
    advance(r, nowMs);
    UsbSecondSample *s = &r->current;
    if (kind == 1) s->dropUrb++;
    else if (kind == 2) s->dropPacket++;
    else s->dropLength++;
    s->lastUrbStatus = urbStatus;
    s->lastPacketStatus = packetStatus;
    s->lastLength = (int32_t)length;
}

void usbSecondRingSent(UsbSecondRing *r, int64_t nowMs, double fpmf) {
    advance(r, nowMs);
    UsbSecondSample *s = &r->current;
    if (s->sentCalls == 0) {
        s->sentMin = s->sentMax = fpmf;
    } else {
        if (fpmf < s->sentMin) s->sentMin = fpmf;
        if (fpmf > s->sentMax) s->sentMax = fpmf;
    }
    s->sentCalls++;
}

void usbSecondRingWire(UsbSecondRing *r, int64_t nowMs, int frames, int packets) {
    advance(r, nowMs);
    r->current.wireFrames += frames;
    r->current.wirePackets += packets;
}

void usbSecondRingIso(UsbSecondRing *r, int64_t nowMs, int packets, int packetErrors, int shortPackets) {
    advance(r, nowMs);
    UsbSecondSample *s = &r->current;
    s->isoUrbs++;
    s->isoPackets += packets;
    s->isoPacketErrors += packetErrors;
    s->isoShortPackets += shortPackets;
}

void usbSecondRingPolicyEvent(UsbSecondRing *r, const UsbPolicyEvent *event) {
    pthread_mutex_lock(&gSecondRingLock);
    r->events[r->eventHead] = *event;
    r->eventHead = (r->eventHead + 1) % USB_POLICY_EVENT_CAPACITY;
    if (r->eventCount < USB_POLICY_EVENT_CAPACITY) r->eventCount++;
    pthread_mutex_unlock(&gSecondRingLock);
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

int usbSecondRingEvents(UsbSecondRing *r, int64_t nowMs, int64_t windowMs, UsbPolicyEvent *out, int max) {
    int n = 0;
    pthread_mutex_lock(&gSecondRingLock);
    for (int i = 0; i < r->eventCount && n < max; i++) {
        const UsbPolicyEvent *e = &r->events[(r->eventHead - r->eventCount + i + USB_POLICY_EVENT_CAPACITY) % USB_POLICY_EVENT_CAPACITY];
        if (nowMs - e->atMs > windowMs || e->atMs > nowMs) continue;
        out[n++] = *e;
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
        const double applied = s->wirePackets > 0 ? (double)s->wireFrames / (double)s->wirePackets : 0.0;
        const int wrote = snprintf(
            out + used, capacity - used,
            "t-%llds feedback n=%d fpmf min=%.5f max=%.5f mean=%.5f nominal=%.5f deviating=%d refused=%d | iso urbs=%d packets=%d errors=%d short=%d"
            " | fb held=%d dropped urb=%d packet=%d length=%d last=%d/%d/%d age=%lldms | sent min=%.5f max=%.5f calls=%d | wire frames=%lld packets=%lld applied=%.5f\n",
            (long long)(nowSecond - s->second), s->readings, s->fpmfMin, s->fpmfMax, mean, s->nominalFpmf, s->deviating, s->refused,
            s->isoUrbs, s->isoPackets, s->isoPacketErrors, s->isoShortPackets,
            s->held, s->dropUrb, s->dropPacket, s->dropLength, s->lastUrbStatus, s->lastPacketStatus, s->lastLength, (long long)s->ageMs,
            s->sentMin, s->sentMax, s->sentCalls, (long long)s->wireFrames, (long long)s->wirePackets, applied);
        if (wrote < 0) break;
        if ((size_t)wrote >= capacity - used) {  // truncated: say so rather than cut a line in half
            used = capacity - 1;
            break;
        }
        used += (size_t)wrote;
    }
    return used;
}

static const char *eventKindName(int kind) {
    switch (kind) {
        case USB_POLICY_REFUSE_BEGIN: return "refuse-begin";
        case USB_POLICY_REFUSE_END: return "refuse-end";
        case USB_POLICY_STUCK_BEGIN: return "stuck-begin";
        case USB_POLICY_STUCK_END: return "stuck-end";
        case USB_POLICY_STREAM_START: return "start";
        case USB_POLICY_PAUSE: return "pause";
        case USB_POLICY_IDLE: return "idle";
        case USB_POLICY_SEEK: return "seek";
        case USB_POLICY_RESUME: return "resume";
        case USB_POLICY_DAC_LOCKED: return "dac-locked";
        case USB_POLICY_DAC_RECOVERY: return "dac-recovery";
        default: return "?";
    }
}

size_t usbSecondRingFormatEvents(const UsbPolicyEvent *events, int n, int64_t nowMs, const char *(*policyName)(int), char *out, size_t capacity) {
    if (capacity == 0) return 0;
    size_t used = 0;
    out[0] = '\0';
    for (int i = 0; i < n && used + 1 < capacity; i++) {
        const UsbPolicyEvent *e = &events[i];
        const int64_t agoMs = nowMs - e->atMs;
        const bool gesture = e->kind >= USB_POLICY_STREAM_START;
        const int wrote = gesture
            ? snprintf(out + used, capacity - used, "event t-%lld.%03llds stream=%lld rate=%d %s %s policy=%s sending=%.5f\n",
                       (long long)(agoMs / 1000), (long long)(agoMs % 1000), (long long)e->stream, e->rate, eventKindName(e->kind),
                       e->what != NULL ? e->what : "", policyName != NULL ? policyName(e->policy) : "?", e->sentBefore)
            : snprintf(out + used, capacity - used,
                       "event t-%lld.%03llds stream=%lld rate=%d %s %s reading=%.5f %+.0fppm policy=%s sending=%.5f->%.5f\n",
                       (long long)(agoMs / 1000), (long long)(agoMs % 1000), (long long)e->stream, e->rate, eventKindName(e->kind),
                       e->what != NULL ? e->what : "", e->fpmf, e->ppm, policyName != NULL ? policyName(e->policy) : "?", e->sentBefore,
                       e->sentAfter);
        if (wrote < 0) break;
        if ((size_t)wrote >= capacity - used) {
            used = capacity - 1;
            break;
        }
        used += (size_t)wrote;
    }
    return used;
}
