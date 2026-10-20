package com.lostf1sh.pixelplayeross.data.media

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the waveform summary.
 *
 * These pin the properties the UI relies on: a fixed bounded bucket count, an absolute (never
 * normalised) scale, deterministic channel reduction, and time-proportional buckets.
 */
class WaveformBuilderTest {

    private fun build(
        samples: FloatArray,
        channelCount: Int,
        totalFrames: Long = (samples.size / channelCount).toLong(),
        bucketCount: Int = WaveformBuilder.WAVEFORM_BUCKET_COUNT
    ): Waveform = WaveformBuilder(totalFrames, bucketCount).apply {
        accept(samples, channelCount)
    }.build()

    @Test
    fun `bucket count is bounded regardless of track length`() {
        val samples = FloatArray(64) { 0.5f }

        val shortTrack = build(samples, channelCount = 1, totalFrames = 32, bucketCount = 8)
        val longTrack = build(samples, channelCount = 1, totalFrames = 40_000_000, bucketCount = 8)

        assertEquals(8, shortTrack.bucketCount)
        assertEquals(8, longTrack.bucketCount)
    }

    @Test
    fun `silence renders as silence rather than a manufactured floor`() {
        val waveform = build(FloatArray(64), channelCount = 1, bucketCount = 8)

        assertTrue(waveform.peaks.all { it == 0f })
        assertTrue(waveform.rms.all { it == 0f })
    }

    @Test
    fun `peak is the largest absolute sample in each bucket`() {
        val samples = floatArrayOf(
            0.1f, 0.2f, -0.9f, 0.3f, // bucket 0
            0.05f, 0.05f, 0.05f, 0.05f // bucket 1
        )

        val waveform = build(samples, channelCount = 1, bucketCount = 2)

        assertEquals(0.9f, waveform.peaks[0], 1e-6f)
        assertEquals(0.05f, waveform.peaks[1], 1e-6f)
    }

    @Test
    fun `rms is the root mean square of each bucket`() {
        val samples = floatArrayOf(1f, -1f, 1f, -1f) // bucket 0, mean square = 1

        val waveform = build(samples, channelCount = 1, bucketCount = 1)

        assertEquals(1f, waveform.rms[0], 1e-6f)
    }

    @Test
    fun `rms of a half amplitude square wave is a half`() {
        val samples = FloatArray(4) { if (it % 2 == 0) 0.5f else -0.5f }

        val waveform = build(samples, channelCount = 1, bucketCount = 1)

        assertEquals(0.5f, waveform.rms[0], 1e-6f)
    }

    @Test
    fun `a signal present in only one channel is not cancelled`() {
        // Averaging the channels would give zero here; taking the per-frame maximum does not.
        val interleaved = floatArrayOf(
            0.8f, 0f,
            0.8f, 0f,
            -0.8f, 0f,
            0.8f, 0f
        )

        val waveform = build(interleaved, channelCount = 2, bucketCount = 1)

        assertTrue(waveform.peaks[0] > 0.7f, "expected the left-only signal to survive")
        assertTrue(waveform.rms[0] > 0.7f, "expected the left-only signal to survive in RMS too")
    }

    @Test
    fun `identical material always produces an identical waveform`() {
        val samples = FloatArray(256) { (it % 37) / 37f - 0.5f }

        val first = build(samples, channelCount = 1, bucketCount = 16)
        val second = build(samples, channelCount = 1, bucketCount = 16)

        assertEquals(first, second)
        assertContentEquals(first.peaks, second.peaks)
        assertContentEquals(first.rms, second.rms)
    }

    @Test
    fun `the scale is absolute so a quieter master stays quieter`() {
        val loud = build(FloatArray(32) { 1f }, channelCount = 1, bucketCount = 4)
        val quiet = build(FloatArray(32) { 0.25f }, channelCount = 1, bucketCount = 4)

        assertEquals(1f, loud.peaks[0], 1e-6f)
        assertEquals(0.25f, quiet.peaks[0], 1e-6f)
        assertTrue(quiet.peaks[0] < loud.peaks[0], "no per-render normalisation is allowed")
    }

    @Test
    fun `buckets are equal slices of time`() {
        // Exactly one frame per bucket, so each bucket holds a distinguishable value.
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)

        val waveform = build(samples, channelCount = 1, bucketCount = 4)

        assertEquals(0.1f, waveform.peaks[0], 1e-6f)
        assertEquals(0.2f, waveform.peaks[1], 1e-6f)
        assertEquals(0.3f, waveform.peaks[2], 1e-6f)
        assertEquals(0.4f, waveform.peaks[3], 1e-6f)
    }

    @Test
    fun `every decoded frame is accounted for even when it does not divide evenly`() {
        // 10 frames into 3 buckets: no frame may be dropped or double counted.
        val samples = FloatArray(10) { 0.5f }
        val builder = WaveformBuilder(totalFrames = 10, bucketCount = 3)

        builder.accept(samples, channelCount = 1)

        assertEquals(10L, builder.framesAccepted)
    }

    @Test
    fun `surplus frames fold into the final bucket instead of being lost`() {
        val builder = WaveformBuilder(totalFrames = 4, bucketCount = 2)
        val samples = FloatArray(8) { 0.4f }

        builder.accept(samples, channelCount = 1)
        val waveform = builder.build()

        assertEquals(8L, builder.framesAccepted)
        assertEquals(0.4f, waveform.peaks[1], 1e-6f, "the last bucket must take the surplus")
    }

    @Test
    fun `a chunked feed matches a single feed`() {
        val samples = FloatArray(300) { (it % 29) / 29f }

        val oneShot = build(samples, channelCount = 1, bucketCount = 12)

        val chunked = WaveformBuilder(totalFrames = 300, bucketCount = 12).apply {
            var offset = 0
            while (offset < samples.size) {
                val size = minOf(17, samples.size - offset)
                accept(samples.copyOfRange(offset, offset + size), channelCount = 1)
                offset += size
            }
        }.build()

        assertContentEquals(oneShot.peaks, chunked.peaks)
        assertContentEquals(oneShot.rms, chunked.rms)
    }

    @Test
    fun `build carries the stream properties it was given`() {
        val waveform = WaveformBuilder(totalFrames = 16, bucketCount = 4)
            .apply { accept(FloatArray(16) { 0.5f }, channelCount = 1) }
            .build(sampleRate = 96_000, durationMs = 4_200L)

        assertEquals(96_000, waveform.sampleRate)
        assertEquals(4_200L, waveform.durationMs)
    }

    @Test
    fun `equality compares bucket contents not array identity`() {
        val a = build(FloatArray(8) { 0.3f }, channelCount = 1, bucketCount = 2)
        val b = build(FloatArray(8) { 0.3f }, channelCount = 1, bucketCount = 2)
        val c = build(FloatArray(8) { 0.9f }, channelCount = 1, bucketCount = 2)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `the default bucket count stays in the hundreds to low thousands`() {
        // The UI draws one bar per bucket, so this is the guard against a point cloud.
        assertTrue(
            WaveformBuilder.WAVEFORM_BUCKET_COUNT in 256..4096,
            "unexpected bucket count ${WaveformBuilder.WAVEFORM_BUCKET_COUNT}"
        )
    }

    @Test
    fun `a stream of unknown length is rejected rather than summarised as empty`() {
        // A zero-frame stream means the decoder could not establish a duration. Refusing to build
        // keeps "no waveform" distinct from "a waveform that happens to be flat".
        assertFailsWith<IllegalArgumentException> {
            WaveformBuilder(totalFrames = 0, bucketCount = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            WaveformBuilder(totalFrames = 16, bucketCount = 0)
        }
    }
}
