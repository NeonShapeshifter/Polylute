#include "urb-quarantine.h"

#include <pthread.h>

// C headers and pthread only: the host test is built without the C++ standard library.
static pthread_mutex_t gQuarantineLock = PTHREAD_MUTEX_INITIALIZER;
static void *gQuarantine[URB_QUARANTINE_CAPACITY];
static int gQuarantineCount = 0;

bool urbQuarantineAdd(void *urb) {
    pthread_mutex_lock(&gQuarantineLock);
    const bool added = gQuarantineCount < URB_QUARANTINE_CAPACITY;
    if (added) gQuarantine[gQuarantineCount++] = urb;
    pthread_mutex_unlock(&gQuarantineLock);
    return added;
}

bool urbQuarantineTake(const void *urb) {
    if (urb == nullptr) return false;
    pthread_mutex_lock(&gQuarantineLock);
    bool taken = false;
    for (int i = 0; i < gQuarantineCount; i++) {
        if (gQuarantine[i] == urb) {
            gQuarantine[i] = gQuarantine[--gQuarantineCount];
            taken = true;
            break;
        }
    }
    pthread_mutex_unlock(&gQuarantineLock);
    return taken;
}

int urbQuarantineCount(void) {
    pthread_mutex_lock(&gQuarantineLock);
    const int count = gQuarantineCount;
    pthread_mutex_unlock(&gQuarantineLock);
    return count;
}

void urbDrainInit(UrbDrainSet *set) { set->count = 0; }

void urbDrainAdd(UrbDrainSet *set, const void *urb) {
    if (set->count < URB_DRAIN_MAX) set->pending[set->count++] = urb;
}

bool urbDrainResolve(UrbDrainSet *set, const void *urb) {
    if (urb == nullptr) return false;
    for (int i = 0; i < set->count; i++) {
        if (set->pending[i] == urb) {
            set->pending[i] = set->pending[--set->count];
            return true;
        }
    }
    return false;
}
