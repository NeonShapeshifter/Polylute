// URB structs retired from the ring while the kernel may still hand them back (AUD-112).
//
// A barrier discards every in-flight URB and waits for their completions. When one does not come
// back within the bounded wait, its struct cannot be reused: the kernel keys the pending request by
// that user address and writes its status into it when it is eventually reaped. Such a struct is
// parked here and the ring slot gets a fresh one; whichever reap site later receives the parked
// address frees it instead of treating it as a ring completion.
//
// Process-wide, because the usbfs fd outlives a stream: a later stream on the same fd may reap it.
#ifndef URB_QUARANTINE_H
#define URB_QUARANTINE_H

#include <stdbool.h>

#define URB_QUARANTINE_CAPACITY 256

/**
 * The most URBs one barrier freezes as targets (usb-audio-output.h's BarrierSnapshot). Defined here with the other two ring
 * bookkeeping capacities, URB_QUARANTINE_CAPACITY and URB_DRAIN_MAX, because usb-pcm-packetizer.h checks all three against the
 * ring's size at compile time (B46-A1).
 */
#define USB_AUDIO_MAX_BARRIER_TARGETS 192   // B47-A2: 160 -> 192 with the ring at 128 URBs (ring + the feedback URB must fit)

/** Parks [urb]. False when full: the caller must then keep waiting instead of reusing it. */
bool urbQuarantineAdd(void *urb);

/** True when [urb] was parked; it is removed and now belongs to the caller, who frees it. */
bool urbQuarantineTake(const void *urb);

int urbQuarantineCount(void);

/**
 * The ring URBs one barrier is still waiting for. Every completion that names one resolves it,
 * whichever phase reaped it — a data completion reaped while the feedback URB was being drained
 * is as final as one reaped in phase 3.
 */
#define URB_DRAIN_MAX 192   // B47-A2: 128 -> 192, same reason

typedef struct {
    const void *pending[URB_DRAIN_MAX];
    int count;
} UrbDrainSet;

void urbDrainInit(UrbDrainSet *set);
void urbDrainAdd(UrbDrainSet *set, const void *urb);
/** True when [urb] was pending (and no longer is). */
bool urbDrainResolve(UrbDrainSet *set, const void *urb);

#endif
