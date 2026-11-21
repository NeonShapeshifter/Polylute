#pragma once

#include "usb-second-ring.h"
#include "usb-glitch.h"

#define USB_JOURNAL_FILE_BYTES (128 * 1024)
#define USB_JOURNAL_QUEUE_CAPACITY 512
#define USB_JOURNAL_METADATA_CAPACITY 256

struct UsbJournalFacts {
    int64_t connection, generation;
    int32_t rate, bits, alt, level, verified, channels;
};

// Configuration/export are never called by the USB writer. Producers only try to copy memory.
bool usbJournalConfigure(const char *directory);
void usbJournalEvent(UsbSecondRing *ring, const UsbPolicyEvent *event);
void usbJournalSecond(const UsbSecondSample *sample, int64_t atMs);
void usbJournalPlatform(int64_t stream, const UsbJournalFacts *facts, int reason);
void usbJournalControl(int64_t connection, int64_t generation, int reason, int origin, int target, int result, int64_t elapsedMs);
void usbJournalBackoff(int64_t stream, int rate, int64_t atMs, int64_t nextMs, int retry);
bool usbJournalFlush(int timeoutMs);
char *usbJournalExport(); // caller frees; expands frozen zlib snapshots into readable text
void usbJournalShutdown(); // orderly close for host lifecycle/restart tests
size_t usbJournalFixedBytes();
uint64_t usbJournalDropped();

#ifdef POLYLUTE_JOURNAL_TEST
void usbJournalTestDelay(int milliseconds);
#endif

// Private reference storage shares only the background I/O thread, never a health decision.
bool usbJournalReferenceGet(const char *key, UsbFeedbackReference *out);
bool usbJournalReferenceLearn(const char *key, uint32_t raw, double nominal, uint64_t session, UsbFeedbackReference *out);
void usbJournalReferenceDegrade(const char *key);
uint64_t usbJournalReferenceSession(int64_t stream);
