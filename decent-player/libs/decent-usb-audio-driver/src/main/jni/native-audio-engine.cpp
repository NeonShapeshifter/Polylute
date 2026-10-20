/**
 * @file native-audio-engine.cpp
 * @brief Native FLAC decode → USB audio engine.
 *
 * Bypasses the entire ExoPlayer audio pipeline for FLAC files:
 * FLACParser decodes → bit-depth conversion → submitPcmToUrbs.
 * Single native thread, zero JNI in the hot path.
 *
 * The engine is controlled from Kotlin via JNI (start/pause/seek/stop)
 * and reports position via an atomic counter.
 */

#include "native-audio-engine.h"
#include "usb-audio-output.h"
#include "seek-barrier.h"
#include "joinable-thread.h"
#include "usb-glitch.h"

#include <flac_parser.h>
#include <data_source.h>

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <vector>
#include <cstring>
#include <cstdlib>

/**
 * Terminal state of a seek request.
 *
 * PENDING runs from publication until the decode thread has executed it. SUCCESS means the decoder
 * is at the requested target and that target may become the authoritative media position. FAILED
 * means the attempt is over but the decoder is *not* at the target — the caller must not treat it
 * as reached, and playback must not resume as though it had been.
 */
enum class SeekOutcome : int { PENDING = 0, SUCCESS = 1, FAILED = 2 };

/**
 * Why the decode thread stopped; mirrored by the Kotlin `NativeEngineExit` (same order). Only
 * END_OF_STREAM is the end of the track — the sink used to read every stop as one (AUD-013).
 */
enum class EngineExit : int { RUNNING = 0, END_OF_STREAM = 1, DECODE_ERROR = 2, USB_ERROR = 3, STOPPED = 4 };
#include <sys/stat.h>
#include <sys/mman.h>

#define TAG "NativeAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── MmapDataSource: memory-mapped file for zero-syscall reads ───────

/** DataSource with dedicated I/O thread. Decode thread NEVER does disk I/O.
 *  8MB buffer with compaction. I/O thread is the ONLY thread touching the fd.
 *  When buffer misses, decode waits for I/O thread to fill (no direct pread64). */
class AsyncBufferedDataSource : public DataSource {
    int fd_;
    bool ownsFd_;
    off64_t fileLength_;

    uint8_t *buf_;
    static const size_t BUF_CAP = 8 * 1024 * 1024;
    static const size_t READ_CHUNK = 128 * 1024;

    // Protected by mutex (shared between I/O thread and decode thread)
    pthread_mutex_t mu_;
    pthread_cond_t cond_;   // signal decode thread when data available
    pthread_cond_t ioCond_; // signal I/O thread: space freed, seek requested, or shutting down
    off64_t bufStart_;
    size_t bufFilled_;
    off64_t ioPos_;         // next read position for I/O thread
    bool seekPending_;
    off64_t seekTarget_;
    bool alive_;

    static void *ioLoop(void *arg) {
        auto *ds = static_cast<AsyncBufferedDataSource *>(arg);
        uint8_t *tempBuf = (uint8_t *)malloc(READ_CHUNK);

        while (true) {
            pthread_mutex_lock(&ds->mu_);
            if (!ds->alive_) { pthread_mutex_unlock(&ds->mu_); break; }

            if (ds->seekPending_) {
                ds->bufStart_ = ds->seekTarget_;
                ds->bufFilled_ = 0;
                ds->ioPos_ = ds->seekTarget_;
                ds->seekPending_ = false;
            }

            if (ds->bufFilled_ >= BUF_CAP) {
                // Full: wait for the decoder to consume, a seek, or shutdown — it polled every 5 ms
                // for as long as the engine existed (AUD-036). The timeout is only a safety net.
                waitForIoWork(ds, 1000);
                pthread_mutex_unlock(&ds->mu_);
                continue;
            }

            off64_t readPos = ds->ioPos_;
            size_t space = BUF_CAP - ds->bufFilled_;
            pthread_mutex_unlock(&ds->mu_);

            // Read into TEMP buffer (not main buffer — avoids race with compaction)
            size_t toRead = space < READ_CHUNK ? space : READ_CHUNK;
            ssize_t n = pread64(ds->fd_, tempBuf, toRead, readPos);

            if (n > 0) {
                pthread_mutex_lock(&ds->mu_);
                if (ds->ioPos_ == readPos && !ds->seekPending_) {
                    // Copy to CURRENT end of buffer (safe — mutex held)
                    size_t currentFilled = ds->bufFilled_;
                    if (currentFilled + n <= BUF_CAP) {
                        memcpy(ds->buf_ + currentFilled, tempBuf, n);
                        ds->bufFilled_ = currentFilled + n;
                        ds->ioPos_ += n;
                    }
                    pthread_cond_signal(&ds->cond_);
                }
                pthread_mutex_unlock(&ds->mu_);
            } else {
                // End of file: nothing to do until a seek moves ioPos_. A read error is retried soon.
                pthread_mutex_lock(&ds->mu_);
                if (ds->alive_ && !ds->seekPending_ && ds->ioPos_ == readPos) {
                    waitForIoWork(ds, n == 0 ? 1000 : 5);
                }
                pthread_mutex_unlock(&ds->mu_);
            }
        }
        free(tempBuf);
        return nullptr;
    }

    /** Called with mu_ held. */
    static void waitForIoWork(AsyncBufferedDataSource *ds, int timeoutMs) {
        struct timespec deadline;
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += timeoutMs / 1000;
        deadline.tv_nsec += (long)(timeoutMs % 1000) * 1000000L;
        if (deadline.tv_nsec >= 1000000000L) {
            deadline.tv_sec += 1;
            deadline.tv_nsec -= 1000000000L;
        }
        pthread_cond_timedwait(&ds->ioCond_, &ds->mu_, &deadline);
    }

    pthread_t ioThread_;
public:
    AsyncBufferedDataSource(int fd, bool ownsFd) : fd_(fd), ownsFd_(ownsFd),
            fileLength_(0), buf_(nullptr), bufStart_(0), bufFilled_(0),
            ioPos_(0), seekPending_(false), seekTarget_(0), alive_(true) {
        struct stat st;
        if (fstat(fd, &st) == 0) fileLength_ = st.st_size;
        buf_ = (uint8_t *)malloc(BUF_CAP);
        pthread_mutex_init(&mu_, nullptr);
        pthread_cond_init(&cond_, nullptr);
        pthread_cond_init(&ioCond_, nullptr);
        posix_fadvise(fd, 0, fileLength_, POSIX_FADV_SEQUENTIAL);
        // Only readahead first 2MB — enough for FLAC metadata + initial frames.
        // Reading the entire file monopolizes the FUSE daemon on SD cards,
        // blocking our pread64 calls (measured: >5s timeout for 128KB).
        off64_t raSize = fileLength_ < 2*1024*1024 ? fileLength_ : 2*1024*1024;
        readahead(fd, 0, raSize);
        pthread_create(&ioThread_, nullptr, ioLoop, this);
        LOGI("AsyncBufferedDataSource: fd=%d size=%lld (8MB buf, readahead issued)",
             fd, (long long)fileLength_);
    }

    ~AsyncBufferedDataSource() override {
        pthread_mutex_lock(&mu_);
        alive_ = false;
        pthread_cond_signal(&ioCond_);
        pthread_mutex_unlock(&mu_);
        pthread_join(ioThread_, nullptr);
        pthread_mutex_destroy(&mu_);
        pthread_cond_destroy(&cond_);
        pthread_cond_destroy(&ioCond_);
        free(buf_);
        if (ownsFd_ && fd_ >= 0) close(fd_);
    }

    ssize_t readAt(off64_t offset, void *const data, size_t size) override {
        if (offset >= fileLength_) return 0;

        pthread_mutex_lock(&mu_);

        // Fast path: data is already in buffer
        if (offset >= bufStart_ && (size_t)(offset - bufStart_) + size <= bufFilled_) {
            memcpy(data, buf_ + (offset - bufStart_), size);

            // Compact when consumed past half
            size_t consumed = (size_t)(offset + size - bufStart_);
            if (consumed > BUF_CAP / 2 && bufFilled_ > consumed) {
                size_t remaining = bufFilled_ - consumed;
                memmove(buf_, buf_ + consumed, remaining);
                bufStart_ = offset + size;
                bufFilled_ = remaining;
                pthread_cond_signal(&ioCond_);  // space freed for the I/O thread
            }
            pthread_mutex_unlock(&mu_);
            return (ssize_t)size;
        }

        // Partial hit: return what we have
        if (offset >= bufStart_ && offset < bufStart_ + (off64_t)bufFilled_) {
            size_t avail = (size_t)(bufStart_ + bufFilled_ - offset);
            memcpy(data, buf_ + (offset - bufStart_), avail);
            pthread_mutex_unlock(&mu_);
            return (ssize_t)avail;
        }

        // Miss: data not in buffer. For small reads (e.g., FLAC seek binary search),
        // do a direct pread64 — much faster than waiting for the I/O thread to
        // seek+fill, especially on SD card through FUSE.
        if (size <= 64 * 1024) {
            pthread_mutex_unlock(&mu_);
            const int64_t readStartMs = nowMs();
            ssize_t n = pread64(fd_, data, size, offset);
            const int64_t readMs = nowMs() - readStartMs;
            if (readMs > 20) {
                reportUsbGlitch(USB_GLITCH_READ_SLOW, "direct pread %zu B @ %lld took %lld ms",
                                size, (long long)offset, (long long)readMs);
            }
            // A failed read is an error, not the end of the file (AUD-056).
            return n >= 0 ? n : -1;
        }

        // Large miss: redirect I/O thread and wait
        seekTarget_ = offset;
        seekPending_ = true;
        pthread_cond_signal(&ioCond_);

        struct timespec deadline;
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += 15;
        const int64_t waitStartMs = nowMs();

        while (!(offset >= bufStart_ && (size_t)(offset - bufStart_) + size <= bufFilled_)) {
            if (offset >= bufStart_ && offset < bufStart_ + (off64_t)bufFilled_) {
                size_t avail = (size_t)(bufStart_ + bufFilled_ - offset);
                memcpy(data, buf_ + (offset - bufStart_), avail);
                pthread_mutex_unlock(&mu_);
                return (ssize_t)avail;
            }
            int rc = pthread_cond_timedwait(&cond_, &mu_, &deadline);
            if (rc != 0) {
                LOGW("readAt: timeout waiting for data @ %lld", (long long)offset);
                pthread_mutex_unlock(&mu_);
                // Not end of file: returning 0 made the parser report EOS and the sink skip to the
                // next track on a slow card (AUD-056). An error stops the engine as DECODE_ERROR.
                return -1;
            }
        }

        memcpy(data, buf_ + (offset - bufStart_), size);
        const int64_t waitedMs = nowMs() - waitStartMs;
        if (waitedMs > 20) {
            reportUsbGlitch(USB_GLITCH_READ_SLOW, "decoder waited %lld ms for %zu B @ %lld",
                            (long long)waitedMs, size, (long long)offset);
        }

        size_t consumed = (size_t)(offset + size - bufStart_);
        if (consumed > BUF_CAP / 2 && bufFilled_ > consumed) {
            size_t remaining = bufFilled_ - consumed;
            memmove(buf_, buf_ + consumed, remaining);
            bufStart_ = offset + size;
            bufFilled_ = remaining;
            pthread_cond_signal(&ioCond_);
        }

        pthread_mutex_unlock(&mu_);
        return (ssize_t)size;
    }

    off64_t getLength() override { return fileLength_; }

private:
    static int64_t nowMs() {
        struct timespec ts = {};
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    }
};

// ── NativeAudioEngine ───────────────────────────────────────────────

struct NativeAudioEngine {
    // Input
    AsyncBufferedDataSource *dataSource;
    FLACParser *parser;

    // USB output (owned by UsbAudioStream on the Java side)
    UsbAudioContext *usbCtx;

    // Stream info (from FLAC metadata)
    int sampleRate;
    int channels;
    int bitsPerSample;
    int dacBitDepth;  // from UsbAudioContext

    // Decode thread. Joined exactly once — by stop() or destroy() — also when it ended on its own
    // (AUD-014).
    JoinableThread decodeThread;
    std::atomic<bool> running;
    std::atomic<bool> paused;
    /** Paused, the decode thread blocks here until resume, seek or stop (AUD-036). */
    pthread_mutex_t idleMu;
    pthread_cond_t idleCond;
    /** EngineExit, published before `running` goes false. */
    std::atomic<int> exitReason;
    /** Set before stop(): queue the faded continuation instead of cutting dry (AUD-123). */
    std::atomic<bool> fadeOutOnStop;

    // Position tracking
    std::atomic<int64_t> framesDecoded;
    /** From the stream info, read once at creation: constant for the engine's life. */
    int64_t totalSamples = 0;

    // ── Seek protocol ───────────────────────────────────────────────
    // A seek is a request with a generation, not a flag. The requester publishes
    // (target, generation); the decode thread executes it and publishes completion of
    // that same generation. A generation is only ever acknowledged by the thread that
    // actually performed its seek, so a slow completion from an older request can never
    // be mistaken for the current position — with a bare flag, "pending" says nothing
    // about *which* seek finished.
    //
    // `seekTargetSampleIndex` is atomic because it is written by whichever thread calls
    // nativeSeek and read by the decode thread; it was a plain int64 before, which is a
    // data race even though an aligned 64-bit store happens to be atomic on arm64.
    std::atomic<int64_t> seekTargetSampleIndex;
    std::atomic<uint64_t> seekRequestedGeneration;
    /** Generation the decode thread has finished seeking to. 0 = none completed yet. */
    std::atomic<uint64_t> seekCompletedGeneration;
    /**
     * Outcome of the most recent seek: PENDING while the decode thread owes it, then SUCCESS or
     * FAILED once executed.
     *
     * Separate from the generation because "the attempt finished" and "the target became the
     * position" are different claims. A generation advancing to FAILED means the request is no
     * longer outstanding — not that the decoder is where it was asked to go. Conflating the two
     * would let a failed seek be treated as an authoritative position, and playback resume from
     * wherever the decoder happened to land.
     */
    std::atomic<int> seekOutcome;  // See SeekOutcome in the JNI section.
    /** Media target, in microseconds, of the most recently completed seek. */
    std::atomic<int64_t> seekCompletedTargetUs;
    std::atomic<bool> seekPending;

    // Buffers
    uint8_t *pcmBuffer;       // raw decoded PCM from FLACParser
    uint8_t *convertBuffer;   // bit-depth converted PCM for USB
    size_t pcmBufferSize;
    size_t convertBufferSize;

    // Seek click suppression; decode-thread only. Frames of fade-in still owed after a seek that
    // cut audible playback, and the ramp's full length. Zero outside that window.
    int64_t fadeInFramesRemaining = 0;
    int64_t fadeInTotalFrames = 0;
    // True once THIS engine has submitted audio. A fresh engine's item-start seek must not ramp,
    // even while the previous track's tail is still in flight (gapless).
    bool hasSubmittedAudio = false;
    // AUD-123. Set before start when the previous track was cut with a fade: the item-start seek
    // keeps that faded tail queued and this engine's first audio fades in.
    bool entryFadeIn = false;
    // Decode-thread only. A pause queued a fade-out; the rest of that block waits in pendingBuffer
    // and is played, faded in, on resume — no audio is skipped.
    bool pauseFadeQueued = false;
    bool stopFadeQueued = false;
    uint8_t *pendingBuffer = nullptr;
    int pendingFrames = 0;
};

/** Fade-in over the frames still owed after a seek, in the DAC's own sample format. */
static void applyFadeIn(NativeAudioEngine *engine, uint8_t *data, int frames) {
    const int64_t position = engine->fadeInTotalFrames - engine->fadeInFramesRemaining;
    pcmApplyGainRamp(data, frames, engine->channels, engine->dacBitDepth / 8,
                     position, engine->fadeInTotalFrames, true);
    engine->fadeInFramesRemaining -= frames;
    if (engine->fadeInFramesRemaining < 0) engine->fadeInFramesRemaining = 0;
}

/**
 * Converts [bytesRead] decoded bytes in pcmBuffer to the DAC's container. Returns the USB bytes, or
 * null for a combination the sink should have refused before claiming.
 */
static const uint8_t *toUsbFormat(NativeAudioEngine *engine, size_t bytesRead, int *usbBytes) {
    if (engine->bitsPerSample == engine->dacBitDepth) {
        *usbBytes = (int)bytesRead;
        return engine->pcmBuffer;
    }
    // Lossless padding to the DAC's container, including 16→24 for 3-byte slots (AUD-008).
    // FLACParser outputs 24-bit as packed 3-byte samples (little-endian).
    const int totalSamples = (int)(bytesRead / (engine->bitsPerSample / 8));
    *usbBytes = usbPcmConvert(engine->pcmBuffer, engine->bitsPerSample,
                              engine->convertBuffer, engine->dacBitDepth, totalSamples);
    return *usbBytes < 0 ? nullptr : engine->convertBuffer;
}

/**
 * Queues the real continuation of what is playing, ramped down to zero, behind what is in flight.
 * Queued audio cannot be faded — usbfs copied it at submit — and cancelling it leaves the endpoint
 * without packets until the first new URB, which is the crack. So the ring plays out and ends in a
 * fade instead of a cut: on a seek (AUD-107), a stop for a track change and a pause (AUD-123).
 *
 * [firstData]/[firstFrames] is a block already decoded and converted but not yet submitted: it IS
 * the continuation and is used first. With [keepRemainder] the block's frames after the fade are
 * kept in pendingBuffer for the resume instead of dropped. Decode thread only. Returns the frames
 * queued.
 */
static int64_t queueFadeOut(NativeAudioEngine *engine, const uint8_t *firstData, int firstFrames,
                            bool keepRemainder) {
    const int64_t fadeFrames = seekFadeOutFrames(engine->sampleRate);
    const int usbBytesPerFrame = engine->channels * (engine->dacBitDepth / 8);
    const uint8_t *data = firstData;
    int frames = firstFrames;
    int64_t done = 0;
    while (done < fadeFrames && engine->usbCtx->running.load()) {
        if (data == nullptr) {
            const size_t bytesRead = engine->parser->readBuffer(engine->pcmBuffer, engine->pcmBufferSize);
            if (bytesRead == (size_t)-1 || bytesRead == 0) break;  // end of stream: nothing to fade
            int usbBytes = 0;
            data = toUsbFormat(engine, bytesRead, &usbBytes);
            if (data == nullptr) break;
            frames = usbBytes / usbBytesPerFrame;
        }
        const int ramped = fadeOutFramesOfBlock(frames, done, fadeFrames);
        pcmApplyGainRamp(const_cast<uint8_t *>(data), ramped, engine->channels,
                         engine->dacBitDepth / 8, done, fadeFrames, false);
        if (!submitPcmToUrbs(engine->usbCtx, data, ramped * usbBytesPerFrame)) break;
        done += ramped;
        if (done >= fadeFrames && keepRemainder && ramped < frames && engine->pendingBuffer) {
            engine->pendingFrames = frames - ramped;
            memcpy(engine->pendingBuffer, data + (size_t)ramped * usbBytesPerFrame,
                   (size_t)engine->pendingFrames * usbBytesPerFrame);
        }
        data = nullptr;
    }
    return done;
}

static void queueSeekFadeOut(NativeAudioEngine *engine) {
    const int64_t done = queueFadeOut(engine, nullptr, 0, false);
    LOGI("Seek fade-out queued: %lld/%d frames", (long long)done, seekFadeOutFrames(engine->sampleRate));
}

/**
 * Stop for a track change with a fade (AUD-123): the faded continuation is queued and the stream
 * told to let it play out before any teardown discards it.
 */
static void queueStopFadeOut(NativeAudioEngine *engine, const uint8_t *block, int blockFrames) {
    const int64_t done = queueFadeOut(engine, block, blockFrames, false);
    engine->stopFadeQueued = true;
    engine->usbCtx->playOutBeforeDrain = true;
    LOGI("Stop fade-out queued: %lld/%d frames", (long long)done, seekFadeOutFrames(engine->sampleRate));
}

/** First iteration after a pause that faded out: the kept rest of that block, faded in. */
static void resumeAfterPauseFade(NativeAudioEngine *engine) {
    engine->pauseFadeQueued = false;
    engine->fadeInTotalFrames = seekFadeInFrames(engine->sampleRate);
    engine->fadeInFramesRemaining = engine->fadeInTotalFrames;
    if (engine->pendingFrames <= 0) return;
    const int frames = engine->pendingFrames;
    engine->pendingFrames = 0;
    applyFadeIn(engine, engine->pendingBuffer, frames);
    const int bytes = frames * engine->channels * (engine->dacBitDepth / 8);
    if (submitPcmToUrbs(engine->usbCtx, engine->pendingBuffer, bytes)) {
        engine->framesDecoded.fetch_add(frames);
    }
}

struct SeekJob {
    FLACParser *parser;
    FLAC__uint64 target;
    bool ok;
    std::atomic<bool> done;
};

static void *seekJobFunc(void *arg) {
    auto *job = static_cast<SeekJob *>(arg);
    job->ok = job->parser->seekAbsolute(job->target);
    job->done.store(true, std::memory_order_release);
    return nullptr;
}

struct SilenceFeed {
    NativeAudioEngine *engine;
    SeekJob *job;
    std::vector<uint8_t> zeros;
    int chunks;
    /** Lowest queued depth seen during the feed: > 0 means the DAC never ran dry (logged). */
    int64_t minQueuedFrames;
};

static int64_t feedQueuedFrames(void *self) {
    auto *feed = static_cast<SilenceFeed *>(self);
    const int64_t queued = usbQueuedFrames(feed->engine->usbCtx);
    if (feed->minQueuedFrames < 0 || queued < feed->minQueuedFrames) feed->minQueuedFrames = queued;
    return queued;
}

static bool feedSubmitSilence(void *self, int frames) {
    auto *feed = static_cast<SilenceFeed *>(self);
    const int bytes = frames * feed->engine->channels * (feed->engine->dacBitDepth / 8);
    if ((size_t)bytes > feed->zeros.size()) feed->zeros.resize((size_t)bytes, 0);
    feed->chunks++;
    return submitPcmToUrbs(feed->engine->usbCtx, feed->zeros.data(), bytes);
}

static void feedWaitForProgress(void *self) {
    if (!usbWaitForProgress(static_cast<SilenceFeed *>(self)->engine->usbCtx)) usleep(1000);
}

static bool feedSeekFinished(void *self) {
    return static_cast<SilenceFeed *>(self)->job->done.load(std::memory_order_acquire);
}

static bool feedStreamRunning(void *self) {
    auto *engine = static_cast<SilenceFeed *>(self)->engine;
    return engine->running.load() && engine->usbCtx->running.load();
}

/**
 * Seeks the decoder on a helper thread while this (decode) thread keeps the DAC fed with silence, so
 * no microframe goes empty however long the seek takes (20-550 ms measured; a fixed 10 ms block ran
 * dry). The helper touches only the parser; the USB context stays with this thread.
 */
static bool seekWhileFeedingSilence(NativeAudioEngine *engine, FLAC__uint64 target) {
    SeekJob job;
    job.parser = engine->parser;
    job.target = target;
    job.ok = false;
    job.done.store(false);
    pthread_t seekThread;
    if (pthread_create(&seekThread, nullptr, seekJobFunc, &job) != 0) {
        LOGW("Seek helper thread unavailable; seeking inline");
        return engine->parser->seekAbsolute(target);
    }
    SilenceFeed feed;
    feed.engine = engine;
    feed.job = &job;
    feed.chunks = 0;
    feed.minQueuedFrames = -1;
    SeekFeedOps ops = {&feed, feedQueuedFrames, feedSubmitSilence, feedWaitForProgress,
                       feedSeekFinished, feedStreamRunning};
    const bool fed = seekFeedSilenceUntilDone(&ops, seekSilenceLeadFrames(engine->sampleRate),
                                              engine->sampleRate / 1000 + 1);
    // The feed returns early only when the stream failed; the parser is still the helper's until it
    // is joined.
    pthread_join(seekThread, nullptr);
    LOGI("Seek fed silence: chunks=%d minQueued=%lld frames (%.1f ms) streamOk=%d", feed.chunks,
         (long long)feed.minQueuedFrames,
         engine->sampleRate > 0 ? feed.minQueuedFrames * 1000.0 / engine->sampleRate : 0.0,
         fed ? 1 : 0);
    return job.ok;
}

/**
 * Paused with no seek owed: block until resume, a seek or stop instead of waking at 50 Hz for as long
 * as the pause lasts (AUD-036). The flags are re-checked under the lock the wakers broadcast under,
 * so a wake-up cannot be lost; the timeout is only a safety net.
 */
static void waitWhilePaused(NativeAudioEngine *engine) {
    pthread_mutex_lock(&engine->idleMu);
    while (engine->paused.load() && engine->running.load() &&
           engine->seekRequestedGeneration.load(std::memory_order_acquire) ==
               engine->seekCompletedGeneration.load(std::memory_order_relaxed)) {
        struct timespec deadline;
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += 1;
        pthread_cond_timedwait(&engine->idleCond, &engine->idleMu, &deadline);
    }
    pthread_mutex_unlock(&engine->idleMu);
}

static void wakeDecodeThread(NativeAudioEngine *engine) {
    pthread_mutex_lock(&engine->idleMu);
    pthread_cond_broadcast(&engine->idleCond);
    pthread_mutex_unlock(&engine->idleMu);
}

static void *decodeThreadFunc(void *arg) {
    auto *engine = static_cast<NativeAudioEngine *>(arg);
    LOGI("Decode thread started: rate=%d ch=%d bits=%d dacBits=%d",
         engine->sampleRate, engine->channels,
         engine->bitsPerSample, engine->dacBitDepth);

    EngineExit exit = EngineExit::STOPPED;
    while (engine->running.load()) {
        // Handle seek BEFORE pause.
        //
        // A seek delivered while paused must still reposition the decoder — that is the
        // whole point of "pause, seek, resume". With the pause check first, a paused
        // engine `continue`d past this block and the seek sat unexecuted until resume,
        // so a paused seek silently did nothing. Running it here also gives the ordering
        // guarantee the seek contract needs: the seek and its barrier complete before
        // this iteration can reach readBuffer, so no pre-seek PCM can be submitted after
        // the target moves.
        //
        // Generation-compared rather than flag-checked, so the work is "this generation
        // has not been executed yet" instead of "some seek is pending".
        const uint64_t requestedGen =
            engine->seekRequestedGeneration.load(std::memory_order_acquire);
        if (requestedGen != engine->seekCompletedGeneration.load(std::memory_order_relaxed)) {
            // Read the target after the generation, then re-check the generation before
            // acknowledging: if a newer request landed in between, its target is the one
            // that must win, and this older completion must not be published as current.
            int64_t targetSample = engine->seekTargetSampleIndex.load(std::memory_order_relaxed);
            FLAC__uint64 totalSamples = engine->parser->getTotalSamples();
            LOGI("Seek: gen=%llu target=%lld total=%llu state=%s",
                 (unsigned long long)requestedGen, (long long)targetSample,
                 (unsigned long long)totalSamples,
                 engine->parser->getDecoderStateString());

            // Clamp to valid range
            if (targetSample < 0) targetSample = 0;
            if (totalSamples > 0 && (FLAC__uint64)targetSample >= totalSamples) {
                targetSample = (int64_t)(totalSamples - 1);
            }
            const bool pauseFaded = engine->pauseFadeQueued;
            // A seek replaces whatever a pause kept for its resume.
            engine->pendingFrames = 0;
            engine->pauseFadeQueued = false;

            // Whether audio is actually on its way to the DAC. An item start is also a seek (to 0)
            // on an idle ring, and must stay untouched: no silence, no ramp, bit-perfect from the
            // first sample, and no dip between gapless tracks.
            //
            // AUD-140: decided by what is still unplayed in the ring, not by the pause. usbQueuedFrames
            // retires what already played, so a ring that played out reads 0 (nothing to lose) and one
            // that still holds ~80 ms reads it, however far the pause got: its fade-out is queued
            // behind that audio, which is at full level.
            const int64_t unplayedFrames = usbQueuedFrames(engine->usbCtx);
            const SeekCut cut = seekCutFor(engine->hasSubmittedAudio, unplayedFrames,
                                           (engine->entryFadeIn || engine->usbCtx->playOutBeforeDrain));
            bool seekOk;
            if (cut == SEEK_CUT_FADE && engine->running.load()) {
                // AUD-107: the ring plays out and ends in a fade-out of the real continuation, the
                // DAC is kept fed with silence while the decoder seeks, and the new position fades
                // in. No URB is cancelled, so no microframe goes empty. The cost is the ring's depth
                // (~80 ms) of pre-seek audio still heard after the request.
                //
                // A pause that already queued its fade-out has ended the ring in it: queuing a second
                // continuation would fade audio nobody asked to keep.
                if (!pauseFaded) queueSeekFadeOut(engine);
                seekOk = seekWhileFeedingSilence(engine, (FLAC__uint64)targetSample);
                // AUD-140: a seek that overtook its pause. The ring now ends in the fade and silence,
                // which is the pause's fade-out; queuing another would play a few ms of the new
                // position first. The resume fades in, as after any pause.
                if (engine->paused.load()) engine->pauseFadeQueued = true;
            } else {
                // Nothing audible queued: drop whatever the ring still holds before the decoder
                // moves, so no pre-seek PCM can reach the DAC after the target moves. Except the
                // previous track's tail that was cut with a fade (AUD-123): it must play out.
                // The previous engine's tail must play: cut with a fade (AUD-123) or ended naturally
                // (AUD-128). Only a fade entry fades in; a natural transition stays bit-perfect.
                if (cut != SEEK_CUT_KEEP_TAIL) discardQueuedPcm(engine->usbCtx);
                seekOk = engine->parser->seekAbsolute((FLAC__uint64)targetSample);
            }
            if (seekOk) {
                if (engine->hasSubmittedAudio || engine->entryFadeIn) {
                    // Entering mid-waveform at full level is a click. 15 ms is still far below a
                    // fade anyone hears as one (the old 5 ms left an edge on these DACs).
                    engine->fadeInTotalFrames = seekFadeInFrames(engine->sampleRate);
                    engine->fadeInFramesRemaining = engine->fadeInTotalFrames;
                }
                engine->framesDecoded.store(targetSample);
                LOGI("Seek OK: gen=%llu sample %lld (%.1f sec), state=%s",
                     (unsigned long long)requestedGen, (long long)targetSample,
                     (double)targetSample / engine->sampleRate,
                     engine->parser->getDecoderStateString());
            } else {
                LOGE("Seek FAILED: gen=%llu target=%lld total=%llu state=%s — resetting",
                     (unsigned long long)requestedGen, (long long)targetSample,
                     (unsigned long long)totalSamples,
                     engine->parser->getDecoderStateString());
                engine->parser->reset(0);
                engine->parser->decodeMetadata();
                engine->framesDecoded.store(0);
            }

            engine->seekOutcome.store(
                (int)(seekOk ? SeekOutcome::SUCCESS : SeekOutcome::FAILED),
                std::memory_order_release);
            engine->seekCompletedTargetUs.store(
                engine->sampleRate > 0 ? targetSample * 1000000LL / engine->sampleRate : 0,
                std::memory_order_relaxed);

            // Publish completion only if no newer request has superseded this one.
            // A stale seek finishing late must never advertise itself as the current
            // position while a newer target is still owed.
            if (engine->seekRequestedGeneration.load(std::memory_order_relaxed) == requestedGen) {
                engine->seekCompletedGeneration.store(requestedGen, std::memory_order_release);
                engine->seekPending.store(false);
            } else {
                LOGI("Seek gen=%llu superseded before completion; latest target wins",
                     (unsigned long long)requestedGen);
            }
        }

        // Handle pause
        if (engine->paused.load()) {
            // AUD-123: the ring used to end at full level; fade the continuation out instead and keep
            // the rest of its block for the resume.
            if (engine->hasSubmittedAudio && !engine->pauseFadeQueued) {
                engine->framesDecoded.fetch_add(queueFadeOut(engine, nullptr, 0, true));
                engine->pauseFadeQueued = true;
            }
            // AUD-140: an intentional idle, not a starvation: the first URB after the resume finds the
            // ring dry by design, and RING_EMPTY must not read that as the DAC being starved.
            engine->usbCtx->lastSubmitMs = 0;
            waitWhilePaused(engine);
            continue;
        }
        if (engine->pauseFadeQueued) resumeAfterPauseFade(engine);

        // Decode one FLAC frame
        size_t bytesRead = engine->parser->readBuffer(
            engine->pcmBuffer, engine->pcmBufferSize);

        if (bytesRead == (size_t)-1 || bytesRead == 0) {
            if (engine->parser->isDecoderAtEndOfStream()) {
                LOGI("End of FLAC stream, %lld frames decoded",
                     (long long)engine->framesDecoded.load());
                // Natural EOS is the only path allowed to submit the valid
                // frame-aligned tail retained by the packetizer. Stop, seek,
                // detach, and decoder errors must discard rather than leak it.
                if (!submitFinalPcmResidual(engine->usbCtx)) {
                    LOGE("Failed to submit final FLAC PCM residual");
                }
                // AUD-128: the ~80 ms still queued is the end of the track. The next engine on this
                // stream keeps it (gapless) and a rate change lets it play out before releasing.
                engine->usbCtx->playOutBeforeDrain = true;
                exit = EngineExit::END_OF_STREAM;
            } else {
                LOGE("Decode error: %s",
                     engine->parser->getDecoderStateString());
                exit = EngineExit::DECODE_ERROR;
            }
            break;
        }

        // Calculate frame count from decoded bytes
        int srcBytesPerSample = engine->bitsPerSample / 8;
        int srcBytesPerFrame = srcBytesPerSample * engine->channels;
        int framesInBuffer = (int)(bytesRead / srcBytesPerFrame);

        // Convert bit depth: source → DAC. The sink refuses a narrowing combination before it
        // claims, so a failure here is not expected.
        int usbBytes = 0;
        const uint8_t *usbData = toUsbFormat(engine, bytesRead, &usbBytes);
        if (usbData == nullptr) {
            LOGE("Unsupported bit-depth conversion: %d → %d",
                 engine->bitsPerSample, engine->dacBitDepth);
            exit = EngineExit::DECODE_ERROR;
            break;
        }

        // Check running before USB submit (allows quick exit on stop). A stop asked to fade uses
        // this very block: it is the continuation (AUD-123).
        if (!engine->running.load()) {
            if (engine->fadeOutOnStop.load() && engine->hasSubmittedAudio && !engine->pauseFadeQueued) {
                queueStopFadeOut(engine, usbData, framesInBuffer);
            }
            break;
        }

        // Submit to USB (blocks naturally on URB pipeline = perfect backpressure)
        if (engine->fadeInFramesRemaining > 0) {
            applyFadeIn(engine, const_cast<uint8_t *>(usbData), framesInBuffer);
        }
        if (!submitPcmToUrbs(engine->usbCtx, usbData, usbBytes)) {
            // The stream stopped (submit/reap failure, detach, stop). Decoding on would run to EOS at
            // CPU speed with nothing reaching the DAC, and read as a natural end (AUD-013).
            if (engine->running.load()) {
                LOGE("USB submit failed at frame %lld; stopping as USB_ERROR",
                     (long long)engine->framesDecoded.load());
                exit = EngineExit::USB_ERROR;
            }
            break;
        }
        engine->hasSubmittedAudio = true;
        engine->entryFadeIn = false;
        engine->usbCtx->playOutBeforeDrain = false;  // the stream carries this engine's audio now

        int64_t newTotal = engine->framesDecoded.fetch_add(framesInBuffer) + framesInBuffer;
        // Log every ~1 second of audio
        if (newTotal % engine->sampleRate < framesInBuffer) {
            LOGI("Decode: %lld frames (~%.0f sec)",
                 (long long)newTotal, (double)newTotal / engine->sampleRate);
        }
    }

    // Stopped at the top of the loop, with nothing decoded yet: fade the next block out (AUD-123).
    if (exit == EngineExit::STOPPED && engine->fadeOutOnStop.load() && engine->hasSubmittedAudio &&
        !engine->stopFadeQueued && !engine->pauseFadeQueued) {
        queueStopFadeOut(engine, nullptr, 0);
    }
    engine->exitReason.store((int)exit, std::memory_order_release);
    LOGI("Decode thread exited (reason=%d), %lld total frames", (int)exit,
         (long long)engine->framesDecoded.load());
    // Last access to the engine: Kotlin may destroy it as soon as it sees !running. destroy() joins
    // this thread first anyway (AUD-014); publishing last keeps the epilogue out of that window.
    engine->running.store(false);
    return nullptr;
}

// ── JNI entry points ────────────────────────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeCreate(
        JNIEnv *, jobject, jstring jFilePath, jlong usbHandle) {
    // Will be implemented after Kotlin wrapper is ready
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeCreateFromFd(
        JNIEnv *, jobject, jint fd, jlong usbHandle) {
    auto *usbCtx = reinterpret_cast<UsbAudioContext *>(usbHandle);
    if (!usbCtx) {
        LOGE("nativeCreateFromFd: null USB context");
        return 0;
    }

    // Duplicate fd so we own it
    int ownedFd = dup(fd);
    if (ownedFd < 0) {
        LOGE("nativeCreateFromFd: dup() failed errno=%d", errno);
        return 0;
    }

    auto *ds = new AsyncBufferedDataSource(ownedFd, true);
    auto *parser = new FLACParser(ds);

    if (!parser->init()) {
        LOGE("nativeCreateFromFd: FLACParser init failed");
        delete parser;
        delete ds;
        return 0;
    }

    if (!parser->decodeMetadata()) {
        LOGE("nativeCreateFromFd: metadata decode failed");
        delete parser;
        delete ds;
        return 0;
    }

    auto *engine = new NativeAudioEngine();
    engine->dataSource = ds;
    engine->parser = parser;
    engine->usbCtx = usbCtx;
    engine->sampleRate = (int)parser->getSampleRate();
    engine->channels = (int)parser->getChannels();
    engine->bitsPerSample = (int)parser->getBitsPerSample();
    engine->totalSamples = (int64_t)parser->getTotalSamples();
    engine->dacBitDepth = usbCtx->bitDepth;
    engine->running.store(false);
    engine->paused.store(false);
    engine->exitReason.store((int)EngineExit::RUNNING);
    engine->fadeOutOnStop.store(false);
    joinableThreadInit(&engine->decodeThread);
    pthread_mutex_init(&engine->idleMu, nullptr);
    pthread_cond_init(&engine->idleCond, nullptr);
    engine->framesDecoded.store(0);
    engine->seekTargetSampleIndex.store(-1);
    engine->seekPending.store(false);
    // Generations start at 0 with 0 completed, so a freshly created engine is "in sync"
    // and does not execute a phantom seek before the first real one arrives.
    engine->seekRequestedGeneration.store(0);
    engine->seekCompletedGeneration.store(0);
    engine->seekOutcome.store((int)SeekOutcome::SUCCESS);
    engine->seekCompletedTargetUs.store(0);

    // Allocate decode buffers
    size_t maxBlock = parser->getMaxBlockSize();
    engine->pcmBufferSize = maxBlock * engine->channels * (engine->bitsPerSample / 8);
    engine->convertBufferSize = maxBlock * engine->channels * 4;  // max 32-bit output
    engine->pcmBuffer = (uint8_t *)malloc(engine->pcmBufferSize);
    engine->convertBuffer = (uint8_t *)malloc(engine->convertBufferSize);
    // Large enough for any converted block (the rest of a block kept across a pause).
    engine->pendingBuffer = (uint8_t *)malloc(engine->convertBufferSize > engine->pcmBufferSize
                                              ? engine->convertBufferSize : engine->pcmBufferSize);

    if (!engine->pcmBuffer || !engine->convertBuffer || !engine->pendingBuffer) {
        LOGE("nativeCreateFromFd: buffer allocation failed");
        free(engine->pcmBuffer);
        free(engine->convertBuffer);
        free(engine->pendingBuffer);
        delete parser;
        delete ds;
        delete engine;
        return 0;
    }

    LOGI("Engine created: rate=%d ch=%d bits=%d dacBits=%d maxBlock=%zu",
         engine->sampleRate, engine->channels, engine->bitsPerSample,
         engine->dacBitDepth, maxBlock);

    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeStart(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine || engine->running.load()) return JNI_FALSE;

    engine->running.store(true);
    engine->paused.store(false);
    engine->exitReason.store((int)EngineExit::RUNNING);

    // A previous run that ended on its own is joined before its handle is reused.
    joinableThreadJoin(&engine->decodeThread);
    if (!joinableThreadStart(&engine->decodeThread, decodeThreadFunc, engine)) {
        LOGE("nativeStart: pthread_create failed");
        engine->running.store(false);
        return JNI_FALSE;
    }

    // Set high priority for the decode thread
    struct sched_param param;
    param.sched_priority = sched_get_priority_max(SCHED_FIFO);
    pthread_setschedparam(engine->decodeThread.thread, SCHED_FIFO, &param);

    LOGI("Engine started");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativePause(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (engine) engine->paused.store(true);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeResume(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return;
    engine->paused.store(false);
    wakeDecodeThread(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeSeek(
        JNIEnv *, jobject, jlong handle, jlong positionUs, jlong generation) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return JNI_FALSE;
    // No decode thread left to execute it: accepting would publish a generation nobody acknowledges.
    if (!engine->running.load()) return JNI_FALSE;

    int64_t targetSample = positionUs * engine->sampleRate / 1000000LL;
    engine->seekTargetSampleIndex.store(targetSample, std::memory_order_relaxed);

    // Update framesDecoded immediately so getCurrentPositionUs returns the seek target
    // right away. Prevents ExoPlayer from seeing a stale backwards position jump while
    // the decode thread is still working.
    //
    // This value is deliberately forward-looking: it is *not* evidence that the seek has
    // completed. Completion is reported only through seekCompletedGeneration, which the
    // decode thread publishes after parser->seekAbsolute() — and after the barrier.
    engine->framesDecoded.store(targetSample, std::memory_order_relaxed);

    // Publishing the generation LAST is what makes the request visible: the decode thread
    // reads it first and only then reads the target, so a target can never be observed as
    // belonging to a newer generation than it does.
    engine->seekOutcome.store((int)SeekOutcome::PENDING, std::memory_order_relaxed);
    engine->seekRequestedGeneration.store((uint64_t)generation, std::memory_order_release);
    engine->seekPending.store(true, std::memory_order_relaxed);
    // A paused engine still executes the seek (pause, seek, resume).
    wakeDecodeThread(engine);

    LOGI("Seek requested: gen=%lld %lld us → sample %lld",
         (long long)generation, (long long)positionUs, (long long)targetSample);
    return JNI_TRUE;
}

/** Generation the decode thread has finished seeking to; 0 means none completed yet. */
JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetSeekCompletedGeneration(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return 0;
    return (jlong)engine->seekCompletedGeneration.load(std::memory_order_acquire);
}

/**
 * Outcome of the most recent seek: 0 = PENDING, 1 = SUCCESS, 2 = FAILED.
 *
 * Read alongside the generation: the pair is what says whether generation N *succeeded*, which the
 * generation alone never claims.
 */
JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetSeekOutcome(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return (jint)SeekOutcome::FAILED;
    return (jint)engine->seekOutcome.load(std::memory_order_acquire);
}

/** Media target, in microseconds, of the most recently completed seek. */
JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetSeekCompletedTargetUs(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return 0;
    return (jlong)engine->seekCompletedTargetUs.load(std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeStop(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return;

    engine->running.store(false);
    engine->paused.store(false);
    wakeDecodeThread(engine);
    joinableThreadJoin(&engine->decodeThread);
    LOGI("Engine stopped");
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeDestroy(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return;

    // Joined whether or not it is still running: a thread that ended on its own (EOS, error) was
    // never joined before, and its epilogue could still be reading the engine deleted below.
    engine->running.store(false);
    wakeDecodeThread(engine);
    joinableThreadJoin(&engine->decodeThread);
    pthread_cond_destroy(&engine->idleCond);
    pthread_mutex_destroy(&engine->idleMu);

    free(engine->pcmBuffer);
    free(engine->convertBuffer);
    free(engine->pendingBuffer);
    delete engine->parser;
    delete engine->dataSource;
    LOGI("Engine destroyed, %lld total frames",
         (long long)engine->framesDecoded.load());
    delete engine;
}

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetPositionUs(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine || engine->sampleRate <= 0) return 0;
    return engine->framesDecoded.load() * 1000000LL / engine->sampleRate;
}

/** Length from the stream info, µs; 0 when the file does not state it (AUD-130). */
JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetDurationUs(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine || engine->sampleRate <= 0 || engine->totalSamples <= 0) return 0;
    return engine->totalSamples * 1000000LL / engine->sampleRate;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetSampleRate(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return engine ? engine->sampleRate : 0;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetChannels(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return engine ? engine->channels : 0;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetBitsPerSample(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return engine ? engine->bitsPerSample : 0;
}

/** Before stop(): queue the faded continuation instead of cutting dry (AUD-123). */
JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeRequestFadeOutOnStop(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (engine) engine->fadeOutOnStop.store(true);
}

/**
 * Before start(): the previous track on this stream was cut with a fade (AUD-123). The item-start
 * seek keeps that tail queued and the first audio fades in. Set before the thread exists, so the
 * decode-thread fields need no synchronisation (pthread_create orders them).
 */
JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeEnterWithFadeIn(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine || engine->running.load()) return;
    engine->entryFadeIn = true;
    engine->fadeInTotalFrames = seekFadeInFrames(engine->sampleRate);
    engine->fadeInFramesRemaining = engine->fadeInTotalFrames;
}

/** EngineExit: why the decode thread stopped (RUNNING while it has not). */
JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetExitReason(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return engine ? (jint)engine->exitReason.load(std::memory_order_acquire) : (jint)EngineExit::STOPPED;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeIsRunning(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return (engine && engine->running.load()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
