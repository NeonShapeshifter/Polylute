// Click-free seek in the native engine (AUD-107): ramp shapes and the silence feed that keeps the
// DAC's isochronous stream full while the decoder seeks. Pure logic, host-tested; the engine
// supplies the USB side through SeekFeedOps.
#ifndef SEEK_BARRIER_H
#define SEEK_BARRIER_H

#include <stdbool.h>
#include <stdint.h>

/** Fade-out applied to the continuation of the pre-seek audio, queued behind what is in flight. */
int seekFadeOutFrames(int sampleRate);
/** Fade-in applied to the first audio at the new position. */
int seekFadeInFrames(int sampleRate);
/** Queued audio the silence feed never lets the DAC drop below while the seek runs. */
int seekSilenceLeadFrames(int sampleRate);

/**
 * Linear gain ramp over [frames] frames that sit at [rampPosition] of a [rampTotal]-frame ramp, in
 * the DAC's format (16-bit, packed 24-bit or 32-bit little-endian). Fade-in: frame k of the ramp is
 * scaled by (k+1)/total, frames past the ramp are untouched. Fade-out: (total-1-k)/total, so the
 * last ramp frame is exactly zero, and frames past the ramp are zeroed.
 */
void pcmApplyGainRamp(uint8_t *data, int frames, int channels, int bytesPerSample,
                      int64_t rampPosition, int64_t rampTotal, bool fadeIn);

/**
 * Frames of a decoded block of [blockFrames] that belong to a fade-out already [fadeDone] frames
 * into a [fadeTotal]-frame ramp; the rest of the block is the remainder that follows the fade
 * (AUD-123: kept for the resume after a pause, dropped on a stop or seek).
 */
int fadeOutFramesOfBlock(int blockFrames, int64_t fadeDone, int64_t fadeTotal);

typedef struct {
    void *self;
    /** Frames submitted to the DAC and not yet played. */
    int64_t (*queuedFrames)(void *self);
    /** Queues [frames] frames of silence. False when the stream failed. */
    bool (*submitSilence)(void *self, int frames);
    /** Blocks until the DAC has played some audio (about a URB), or briefly. */
    void (*waitForProgress)(void *self);
    /** True once the concurrent seek has finished. */
    bool (*seekFinished)(void *self);
    /** False once the stream is stopping; the feed then only waits for the seek. */
    bool (*streamRunning)(void *self);
} SeekFeedOps;

/**
 * One iteration of the feed: tops the queue up with [chunkFrames] of silence when it holds less than
 * [leadFrames] (and the stream is still running), otherwise waits for the DAC to make progress. False
 * if the stream failed while feeding. [seekFinished] is not consulted: the caller owns the loop (the
 * ExoPlayer pipeline's writer waits for the first buffer of the new position, B9-2).
 */
bool seekFeedSilenceStep(const SeekFeedOps *ops, int leadFrames, int chunkFrames);

/**
 * Keeps at least [leadFrames] queued, topping up with [chunkFrames] of silence at a time, until the
 * seek finishes. Returns false if the stream failed while feeding.
 */
bool seekFeedSilenceUntilDone(const SeekFeedOps *ops, int leadFrames, int chunkFrames);

/** How a seek treats the audio already queued to the DAC. */
typedef enum {
    /** The ring plays out, ends in a fade-out, and silence is fed while the decoder seeks. */
    SEEK_CUT_FADE = 0,
    /** Nothing audible is queued: the ring is discarded before the decoder moves. */
    SEEK_CUT_DISCARD = 1,
    /** A fresh engine's item start: the previous track's queued tail must play out. */
    SEEK_CUT_KEEP_TAIL = 2,
} SeekCut;

/**
 * [hasSubmittedAudio]: this engine has queued audio. [unplayedFrames]: what is submitted to the DAC
 * and not yet played (the ring's depth, from `usbQueuedFrames`, which retires what already played).
 * [tailToKeep]: the previous engine's tail was cut with a fade or ended naturally.
 *
 * The pause state is deliberately not an input (AUD-140). A pause queues its 8 ms fade-out *behind*
 * the ring, so a paused engine, faded or not, still has up to 80 ms of audio at full level in front
 * of that fade; only a ring that has played out is silent. Anything unplayed is faded out and the DAC
 * is fed silence while the decoder seeks; the discard is for a ring with nothing left to play.
 */
SeekCut seekCutFor(bool hasSubmittedAudio, int64_t unplayedFrames, bool tailToKeep);

#endif
