// Host test for AUD-014: a decode thread that ends on its own is still joined, exactly once.
#include "joinable-thread.h"

#include <assert.h>
#include <stdio.h>
#include <unistd.h>

static volatile int g_finished = 0;

static void *endsOnItsOwn(void *) {
    usleep(2000);  // the epilogue the old destroy could race
    g_finished = g_finished + 1;
    return NULL;
}

int main() {
    // Never started: joining is a no-op (a failed nativeStart, or destroy without start).
    JoinableThread idle;
    joinableThreadInit(&idle);
    joinableThreadJoin(&idle);
    assert(!idle.joined);

    for (int i = 0; i < 1000; i++) {
        JoinableThread t;
        joinableThreadInit(&t);
        const int before = g_finished;
        assert(joinableThreadStart(&t, endsOnItsOwn, NULL));
        // destroy() after the thread "finished" by itself: must wait for its epilogue.
        joinableThreadJoin(&t);
        assert(t.joined);
        assert(g_finished == before + 1);
        // stop() then destroy(): the second join must not touch the thread again.
        joinableThreadJoin(&t);
        assert(t.joined);
        if (i % 100 == 0) usleep(0);
    }
    printf("joinable thread tests: PASS\n");
    return 0;
}
