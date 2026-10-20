// One-second summaries of the USB stream, kept in memory for the next time something sounds wrong (B27-A5, review N-07).
//
// 2026-10-16 16:41, 96 kHz, Purity: ~2 s of static, `FEEDBACK_DEVIATION` 26 times, nothing else in the log. The log samples
// the feedback; it does not say what it did second by second, and the one place that sees every reading is the reap loop.
// This keeps, for each second that streamed, the feedback readings' min / max / mean (frames per microframe), how many of
// them were more than 0.2 % off nominal, how many were refused, and the ISO completions' packet statuses. Nothing is logged
// per second: the last [USB_SECOND_RING_CAPACITY] seconds sit in a fixed array until somebody asks for them.
//
// What it costs: a few compares and adds per feedback reading and per audio URB completion on the thread that already reaps
// them (one clock read each; no allocation, no lock, no I/O), and once a second one mutex and a copy of ~100 bytes. Nothing
// on the PCM path: nothing is read from, or written to, a sample, a packet plan or a URB.
#ifndef USB_SECOND_RING_H
#define USB_SECOND_RING_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define USB_SECOND_RING_CAPACITY 128

/** One wall-clock second of the stream. */
typedef struct {
    int64_t second;          /* monotonic ms / 1000 */
    int32_t readings;        /* feedback readings applied this second */
    int32_t refused;         /* readings beyond the plausible window, never applied */
    int32_t deviating;       /* applied readings more than 0.2 % away from nominal (the ones FEEDBACK_DEVIATION counts) */
    double fpmfMin, fpmfMax, fpmfSum;
    double nominalFpmf;
    int32_t isoUrbs;         /* audio URB completions */
    int32_t isoPackets;      /* ... and their packets */
    int32_t isoPacketErrors; /* packets whose status was not 0 */
    int32_t isoShortPackets; /* packets that delivered fewer bytes than they carried */
} UsbSecondSample;

typedef struct {
    UsbSecondSample current;
    bool hasCurrent;
    UsbSecondSample ring[USB_SECOND_RING_CAPACITY];
    int head;                /* next slot to write */
    int count;
} UsbSecondRing;

void usbSecondRingInit(UsbSecondRing *r);

/** One applied feedback reading at [nowMs]. */
void usbSecondRingFeedback(UsbSecondRing *r, int64_t nowMs, double fpmf, double nominalFpmf, bool deviating);

/** One reading refused as implausible at [nowMs]. */
void usbSecondRingRefused(UsbSecondRing *r, int64_t nowMs);

/** One audio URB completion at [nowMs]: its packets, how many had a status error, how many were short. */
void usbSecondRingIso(UsbSecondRing *r, int64_t nowMs, int packets, int packetErrors, int shortPackets);

/**
 * Copies, oldest first, the seconds that ended within [windowMs] before [nowMs] (the one still running is not included:
 * it is not finished). At most [max]; returns how many. Safe to call from another thread than the one that records.
 */
int usbSecondRingLast(UsbSecondRing *r, int64_t nowMs, int64_t windowMs, UsbSecondSample *out, int max);

/**
 * One line per sample, `t-<seconds before now>s feedback n=... fpmf min=... max=... mean=... nominal=... deviating=... refused=... |
 * iso urbs=... packets=... errors=... short=...`. Returns the bytes written (not counting the terminator); a line that does not
 * fit is left out whole.
 */
size_t usbSecondRingFormat(const UsbSecondSample *samples, int n, int64_t nowMs, char *out, size_t capacity);

#endif
