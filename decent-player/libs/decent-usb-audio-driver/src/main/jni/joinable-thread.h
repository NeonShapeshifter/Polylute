// A thread that is joined exactly once, whether it was stopped or ended on its own (AUD-014).
//
// The decode thread can end by itself (EOS, error). nativeDestroy joined it only while `running`
// was still true, so a thread that had finished was never joined (a leaked thread per track) and
// `delete engine` could run while its epilogue still read the engine. Joining is now tied to "was
// started", and guarded so stop() followed by destroy() never joins twice (undefined behaviour).
#ifndef JOINABLE_THREAD_H
#define JOINABLE_THREAD_H

#include <pthread.h>
#include <stdbool.h>

typedef struct {
    pthread_t thread;
    bool started;
    bool joined;
} JoinableThread;

static inline void joinableThreadInit(JoinableThread *t) {
    t->started = false;
    t->joined = false;
}

static inline bool joinableThreadStart(JoinableThread *t, void *(*fn)(void *), void *arg) {
    if (t->started && !t->joined) return false;
    t->joined = false;
    t->started = pthread_create(&t->thread, NULL, fn, arg) == 0;
    return t->started;
}

/** Joins the thread if it was started and not yet joined; otherwise does nothing. */
static inline void joinableThreadJoin(JoinableThread *t) {
    if (!t->started || t->joined) return;
    pthread_join(t->thread, NULL);
    t->joined = true;
}

#endif
