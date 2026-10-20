#pragma once

#include <stdint.h>

/**
 * Direct-path PCM conversions, shared by the native FLAC engine and the raw/float write paths.
 * Host-compilable on purpose (no JNI, no Android headers) so the gate script can test them.
 *
 * Containers are little-endian, 16/24/32 bits (2/3/4 bytes per sample). A 24-bit source is packed
 * in 3 bytes. Widening is lossless: the sample lands MSB-justified and the added LSBs are zero.
 */

/**
 * Whether [srcBits] can be carried in a [dstBits] container without loss (AUD-008). Narrowing is
 * refused rather than truncated, so the caller can choose another path before streaming instead
 * of the decode thread dying mid-track.
 */
bool usbPcmCanConvert(int srcBits, int dstBits);

/** Converts [samples] samples. Returns the bytes written to [dst], or -1 if unsupported. */
int usbPcmConvert(const uint8_t *src, int srcBits, uint8_t *dst, int dstBits, int samples);

/**
 * Float [-1, 1] to a [dstBits] container (Direct PROCESSED). 16 bits get TPDF dither (AUD-055):
 * truncation there is audible as correlated distortion on quiet material. 24/32 bits keep the
 * plain power-of-two scale, whose quantisation is far below any DAC's analogue floor.
 * [ditherState] is a per-stream xorshift32 seed, never zero. Returns false for other widths.
 */
bool usbPcmFromFloat(const float *src, uint8_t *dst, int samples, int dstBits, uint32_t *ditherState);

/** 16-bit → 32-bit: shift left 16. */
void padInt16ToInt32(const uint8_t *src, uint8_t *dst, int numSamples);

/** 24-bit packed (3 bytes/sample) → 32-bit: sign-extend + shift left 8. */
void padInt24ToInt32(const uint8_t *src, uint8_t *dst, int numSamples);

/** int32 (24-bit sign-extended from libFLAC) → 32-bit: shift left 8. */
void shiftInt32From24(const uint8_t *src, uint8_t *dst, int numSamples);
