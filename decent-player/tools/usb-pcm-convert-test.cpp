// Host tests for the Direct PCM conversions (AUD-008, AUD-055).
#include "usb-pcm-convert.h"

#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

static int32_t readLe(const uint8_t *p, int bytes) {
    int32_t v = 0;
    for (int i = 0; i < bytes; i++) v |= (int32_t)p[i] << (8 * i);
    if (bytes < 4 && (v & (1 << (8 * bytes - 1)))) v |= -(1 << (8 * bytes));
    return v;
}

static void writeLe(uint8_t *p, int32_t v, int bytes) {
    for (int i = 0; i < bytes; i++) p[i] = (uint8_t)((v >> (8 * i)) & 0xFF);
}

/** Every lossless widening: the value lands MSB-justified, the added LSBs are zero. */
static void expectWidening(int srcBits, int dstBits) {
    const int32_t samples[] = {0, 1, -1, 12345, -12345, (1 << (srcBits - 1)) - 1, -(1 << (srcBits - 1))};
    const int n = sizeof(samples) / sizeof(samples[0]);
    uint8_t src[64], dst[64];
    for (int i = 0; i < n; i++) writeLe(src + i * (srcBits / 8), samples[i], srcBits / 8);
    assert(usbPcmCanConvert(srcBits, dstBits));
    int written = usbPcmConvert(src, srcBits, dst, dstBits, n);
    assert(written == n * (dstBits / 8));
    for (int i = 0; i < n; i++) {
        int32_t out = readLe(dst + i * (dstBits / 8), dstBits / 8);
        int32_t expected = (int32_t)((uint32_t)samples[i] << (dstBits - srcBits));
        if (out != expected) {
            fprintf(stderr, "%d->%d sample %d: got %d expected %d\n", srcBits, dstBits, i, out, expected);
            assert(false);
        }
    }
}

int main() {
    // AUD-008: the native engine and writeRaw handled only 16->32, 24->32 and same depth; every
    // other combination ended the decode thread, which the sink read as a natural end of track.
    expectWidening(16, 16);
    expectWidening(16, 24);   // DAC with 3-byte 24-bit slots (bSubslotSize = 3)
    expectWidening(16, 32);
    expectWidening(24, 24);
    expectWidening(24, 32);
    expectWidening(32, 32);

    // Narrowing is lossy: refused explicitly so the caller can pick another path up front,
    // instead of discovering it mid-track.
    assert(!usbPcmCanConvert(24, 16));
    assert(!usbPcmCanConvert(32, 24));
    assert(!usbPcmCanConvert(32, 16));
    assert(!usbPcmCanConvert(8, 32));
    uint8_t scratch[16] = {};
    assert(usbPcmConvert(scratch, 24, scratch, 16, 1) == -1);

    // AUD-055: float -> 16-bit uses TPDF dither. A constant a quarter of an LSB truncated to 0
    // forever; dithered, its mean survives.
    const int n = 200000;
    static float quarter[n];
    static int16_t out16[n];
    for (int i = 0; i < n; i++) quarter[i] = 0.25f / 32768.0f;
    uint32_t state = 12345u;
    assert(usbPcmFromFloat(quarter, reinterpret_cast<uint8_t *>(out16), n, 16, &state));
    double sum = 0;
    for (int i = 0; i < n; i++) {
        assert(out16[i] >= -1 && out16[i] <= 2);   // TPDF spans at most +-1 LSB around the value
        sum += out16[i];
    }
    double mean = sum / n;
    assert(fabs(mean - 0.25) < 0.02);

    // Full scale stays in range with dither on top.
    const float edges[] = {1.0f, -1.0f, 2.0f, -2.0f};
    int16_t edge16[4];
    assert(usbPcmFromFloat(edges, reinterpret_cast<uint8_t *>(edge16), 4, 16, &state));
    assert(edge16[0] >= 32766 && edge16[1] <= -32767 && edge16[2] >= 32766 && edge16[3] <= -32767);

    // 24 and 32 bits: no dither needed (quantisation far below the analogue floor), exact scale.
    const float half = 0.5f;
    uint8_t out24[3];
    int32_t out32;
    assert(usbPcmFromFloat(&half, out24, 1, 24, &state));
    assert(readLe(out24, 3) == 4194304);
    assert(usbPcmFromFloat(&half, reinterpret_cast<uint8_t *>(&out32), 1, 32, &state));
    assert(out32 == 1073741824);
    assert(!usbPcmFromFloat(&half, out24, 1, 20, &state));

    printf("usb pcm conversion tests: PASS\n");
    return 0;
}
