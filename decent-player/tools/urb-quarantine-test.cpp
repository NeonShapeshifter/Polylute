// Host tests for the bounded barrier drain (AUD-112 follow-up).
#include "urb-quarantine.h"

#include <assert.h>
#include <stdio.h>

int main() {
    // A barrier waits for exactly the URBs it discarded; each resolves once, from any phase.
    int urbs[4];
    UrbDrainSet set;
    urbDrainInit(&set);
    for (int i = 0; i < 4; i++) urbDrainAdd(&set, &urbs[i]);
    assert(set.count == 4);
    assert(urbDrainResolve(&set, &urbs[2]));   // e.g. reaped while draining the feedback URB
    assert(!urbDrainResolve(&set, &urbs[2]));  // not twice
    int foreign;
    assert(!urbDrainResolve(&set, &foreign));  // feedback / stale completions are not ring URBs
    assert(set.count == 3);
    assert(urbDrainResolve(&set, &urbs[0]));
    assert(urbDrainResolve(&set, &urbs[1]));
    assert(set.count == 1);                    // urbs[3] never came back within the wait
    assert(set.pending[0] == &urbs[3]);

    // The one that never came back is parked, not reused, and is recognised when it does return.
    assert(urbQuarantineCount() == 0);
    assert(urbQuarantineAdd(&urbs[3]));
    assert(urbQuarantineCount() == 1);
    assert(!urbQuarantineTake(&urbs[1]));      // a live ring URB is never mistaken for it
    assert(urbQuarantineTake(&urbs[3]));
    assert(!urbQuarantineTake(&urbs[3]));
    assert(urbQuarantineCount() == 0);

    // Full quarantine refuses, so the caller keeps waiting rather than reusing a kernel-owned URB.
    static char many[URB_QUARANTINE_CAPACITY + 1];
    for (int i = 0; i < URB_QUARANTINE_CAPACITY; i++) assert(urbQuarantineAdd(&many[i]));
    assert(!urbQuarantineAdd(&many[URB_QUARANTINE_CAPACITY]));
    for (int i = 0; i < URB_QUARANTINE_CAPACITY; i++) assert(urbQuarantineTake(&many[i]));
    assert(urbQuarantineCount() == 0);

    printf("urb quarantine tests: PASS\n");
    return 0;
}
