#include "usb-pcm-convert.h"

#include <string.h>

// ── Integer padding (lossless, zero float) ──────────────────────────

// 16-bit → 32-bit: shift left 16
void padInt16ToInt32(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    auto *in16 = reinterpret_cast<const int16_t *>(src);
    for (int i = 0; i < numSamples; i++) out[i] = (int32_t)((uint32_t)(int32_t)in16[i] << 16);
}

// int32 (24-bit sign-extended from libFLAC) → 32-bit: shift left 8
void shiftInt32From24(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    auto *in32 = reinterpret_cast<const int32_t *>(src);
    for (int i = 0; i < numSamples; i++) out[i] = (int32_t)((uint32_t)in32[i] << 8);
}

// 24-bit packed (3 bytes/sample) → 32-bit: read 3 bytes, sign-extend, shift left 8
void padInt24ToInt32(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    for (int i = 0; i < numSamples; i++) {
        int32_t s = src[i*3] | (src[i*3+1] << 8) | (src[i*3+2] << 16);
        if (s & 0x800000) s |= (int32_t)0xFF000000;  // sign-extend from 24 to 32 bits
        out[i] = (int32_t)((uint32_t)s << 8);  // shift to fill 32-bit range
    }
}

// 16-bit → 24-bit packed: the 16 bits become the top two bytes, the low byte is zero.
static void padInt16ToInt24(const uint8_t *src, uint8_t *dst, int numSamples) {
    for (int i = 0; i < numSamples; i++) {
        dst[i*3] = 0;
        dst[i*3+1] = src[i*2];
        dst[i*3+2] = src[i*2+1];
    }
}

static bool isContainer(int bits) { return bits == 16 || bits == 24 || bits == 32; }

bool usbPcmCanConvert(int srcBits, int dstBits) {
    return isContainer(srcBits) && isContainer(dstBits) && dstBits >= srcBits;
}

int usbPcmConvert(const uint8_t *src, int srcBits, uint8_t *dst, int dstBits, int samples) {
    if (!usbPcmCanConvert(srcBits, dstBits) || samples < 0) return -1;
    const int outBytes = samples * (dstBits / 8);
    if (srcBits == dstBits) {
        memcpy(dst, src, (size_t)outBytes);
    } else if (srcBits == 16 && dstBits == 24) {
        padInt16ToInt24(src, dst, samples);
    } else if (srcBits == 16 && dstBits == 32) {
        padInt16ToInt32(src, dst, samples);
    } else {  // 24 → 32
        padInt24ToInt32(src, dst, samples);
    }
    return outBytes;
}

// ── Float → integer ──────────────────────────────────────────────────

static inline float clampUnit(float v) { return v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v); }

/** Uniform in [-0.5, 0.5) from a xorshift32 stream. */
static inline float uniformHalf(uint32_t *state) {
    uint32_t x = *state ? *state : 0x9E3779B9u;
    x ^= x << 13; x ^= x >> 17; x ^= x << 5;
    *state = x;
    return (float)(x >> 8) * (1.0f / 16777216.0f) - 0.5f;
}

bool usbPcmFromFloat(const float *src, uint8_t *dst, int n, int dstBits, uint32_t *ditherState) {
    if (dstBits == 16) {
        auto *out = reinterpret_cast<int16_t *>(dst);
        for (int i = 0; i < n; i++) {
            // TPDF: the sum of two independent uniform LSB-wide values, then round to nearest.
            float s = clampUnit(src[i]) * 32768.0f + uniformHalf(ditherState) + uniformHalf(ditherState);
            float r = s >= 0.0f ? (float)(int32_t)(s + 0.5f) : -(float)(int32_t)(-s + 0.5f);
            if (r > 32767.0f) r = 32767.0f;
            if (r < -32768.0f) r = -32768.0f;
            out[i] = (int16_t)r;
        }
        return true;
    }
    if (dstBits == 24) {
        for (int i = 0; i < n; i++) {
            float s = clampUnit(src[i]) * 8388608.0f;
            if (s > 8388607.0f) s = 8388607.0f;
            if (s < -8388608.0f) s = -8388608.0f;
            int32_t v = (int32_t)s;
            dst[i*3] = v & 0xFF; dst[i*3+1] = (v >> 8) & 0xFF; dst[i*3+2] = (v >> 16) & 0xFF;
        }
        return true;
    }
    if (dstBits == 32) {
        auto *out = reinterpret_cast<int32_t *>(dst);
        for (int i = 0; i < n; i++) {
            // Double: float32 cannot represent 2147483648.0 exactly.
            double s = (double)clampUnit(src[i]) * 2147483648.0;
            if (s > 2147483647.0) s = 2147483647.0;
            if (s < -2147483648.0) s = -2147483648.0;
            out[i] = (int32_t)s;
        }
        return true;
    }
    return false;
}
