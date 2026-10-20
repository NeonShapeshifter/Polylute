#include "seek-barrier.h"

#include <string.h>

// C headers only: the host test is built without the C++ standard library.

int seekFadeOutFrames(int sampleRate) { return sampleRate * 8 / 1000; }     // 8 ms
int seekFadeInFrames(int sampleRate) { return sampleRate * 15 / 1000; }     // 15 ms
// Half the 80-URB ring: the depth that keeps streaming glitch-free, halved because nothing but
// silence is being queued. It bounds how long new audio can wait behind silence after the seek.
int seekSilenceLeadFrames(int sampleRate) { return sampleRate * 40 / 1000; }

static int32_t readSample(const uint8_t *p, int bytesPerSample) {
    if (bytesPerSample == 4) {
        int32_t v;
        memcpy(&v, p, 4);
        return v;
    }
    if (bytesPerSample == 3) {
        int32_t v = (int32_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16));
        if (v & 0x800000) v |= ~0xFFFFFF;
        return v;
    }
    int16_t v;
    memcpy(&v, p, 2);
    return v;
}

static void writeSample(uint8_t *p, int32_t v, int bytesPerSample) {
    if (bytesPerSample == 4) {
        memcpy(p, &v, 4);
    } else if (bytesPerSample == 3) {
        p[0] = (uint8_t)(v & 0xFF);
        p[1] = (uint8_t)((v >> 8) & 0xFF);
        p[2] = (uint8_t)((v >> 16) & 0xFF);
    } else {
        const int16_t s = (int16_t)v;
        memcpy(p, &s, 2);
    }
}

void pcmApplyGainRamp(uint8_t *data, int frames, int channels, int bytesPerSample,
                      int64_t rampPosition, int64_t rampTotal, bool fadeIn) {
    if (bytesPerSample < 2 || bytesPerSample > 4 || channels <= 0) return;
    const int64_t total = rampTotal > 0 ? rampTotal : 1;
    for (int frame = 0; frame < frames; frame++) {
        const int64_t k = rampPosition + frame;
        double gain;
        if (fadeIn) {
            if (k >= total) return;  // past the ramp: the rest is untouched
            gain = (double)(k + 1) / (double)total;
        } else {
            gain = k >= total ? 0.0 : (double)(total - 1 - k) / (double)total;
        }
        for (int ch = 0; ch < channels; ch++) {
            uint8_t *p = data + ((size_t)frame * channels + ch) * bytesPerSample;
            writeSample(p, (int32_t)(readSample(p, bytesPerSample) * gain), bytesPerSample);
        }
    }
}

int fadeOutFramesOfBlock(int blockFrames, int64_t fadeDone, int64_t fadeTotal) {
    const int64_t owed = fadeTotal - fadeDone;
    if (blockFrames <= 0 || owed <= 0) return 0;
    return owed < blockFrames ? (int)owed : blockFrames;
}

bool seekFeedSilenceStep(const SeekFeedOps *ops, int leadFrames, int chunkFrames) {
    if (ops->streamRunning(ops->self) && ops->queuedFrames(ops->self) < leadFrames) {
        return ops->submitSilence(ops->self, chunkFrames);
    }
    ops->waitForProgress(ops->self);
    return true;
}

bool seekFeedSilenceUntilDone(const SeekFeedOps *ops, int leadFrames, int chunkFrames) {
    while (!ops->seekFinished(ops->self)) {
        if (!seekFeedSilenceStep(ops, leadFrames, chunkFrames)) return false;
    }
    return true;
}

SeekCut seekCutFor(bool hasSubmittedAudio, int64_t unplayedFrames, bool tailToKeep) {
    // What matters is whether audio is still on its way to the DAC. A seek can reach the decode thread
    // before the pause it came with (AUD-140: the player pauses while seeking), and a pause that did
    // queue its fade-out put it behind the ring: either way ~80 ms is still at full level, and
    // discarding it was a dry cut of 55-60 ms of music, then RING_EMPTY.
    if (hasSubmittedAudio && unplayedFrames > 0) return SEEK_CUT_FADE;
    return !hasSubmittedAudio && tailToKeep ? SEEK_CUT_KEEP_TAIL : SEEK_CUT_DISCARD;
}
