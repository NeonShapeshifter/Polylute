package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.PipelinePositionClock
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * AUD-110 (confirmed on the device): moving the EQ toggles PRISTINE ↔ PROCESSED, and after a few
 * toggles the position jumped (+114 s, then -122 s), `windowOffsetUs` was re-latched to
 * `1e12 - 121 s`, Media3 saw no progress and a `StuckPlayerException` skipped the track.
 *
 * Two defects in the pipeline clock, reproduced here against the real [PipelinePositionClock]:
 * - `framesWritten` is cumulative on the cached stream (only a flush zeroes it), so every PROCESSED
 *   period after the first started with the previous periods' frames already counted;
 * - the renderer's decoder sat where LoadControl had stopped it (buffered 6.3 s while the DAC was at
 *   12.26 s), the realign seek to the "current" position was a no-op, and the first buffers carried
 *   that stale timestamp — which the clock latched as the start.
 *
 * The simulation drives N cycles at real rate: the native engine plays on a wall clock, each
 * PROCESSED period is fed by a renderer that resumes from a stale position, and the next PRISTINE
 * period is anchored from the pipeline clock. The position must follow the wall clock and the
 * renderer↔media offset must never change within the item.
 */
class SignalModeRealignPositionTest {

    private val offsetUs = 1_000_000_000_000L          // Media3's renderer offset for the first period
    private val rate = 44_100
    private val bufferFrames = 4096
    private val bufferUs = bufferFrames * 1_000_000L / rate

    @Test
    fun `twelve mode cycles at real rate keep the position on the wall clock`() {
        val clock = PipelinePositionClock()
        var wallUs = 0L                              // media position the DAC is really at
        var enginePosUs = 0L                         // PRISTINE producer (native engine)
        var framesWritten = 3_000_000L               // cached stream: a previous track's frames
        var staleRendererMediaUs = 0L                // where LoadControl left the renderer's decoder
        val windowOffset = offsetUs                  // latched once for the item, never re-latched

        repeat(12) { cycle ->
            // PRISTINE for ~9 s.
            repeat(100) { wallUs += 90_000; enginePosUs += 90_000 }
            assertThat(abs(enginePosUs - wallUs)).isAtMost(100_000L)

            // → PROCESSED at the engine's position.
            val anchorMediaUs = enginePosUs
            clock.arm(windowOffset + anchorMediaUs)
            // The renderer resumes from its stale position; buffers before the anchor must be dropped.
            var rendererMediaUs = staleRendererMediaUs
            while (true) {
                val pts = windowOffset + rendererMediaUs
                val accepted = clock.accept(pts, framesWritten)
                rendererMediaUs += bufferUs
                if (accepted) {
                    framesWritten += bufferFrames
                    break
                }
            }
            // PROCESSED for ~9 s of real playback.
            repeat(97) {
                framesWritten += bufferFrames
                rendererMediaUs += bufferUs
            }
            wallUs = anchorMediaUs + 98 * bufferUs
            val rendererPosition = clock.positionUs(framesWritten, rate)
            val mediaPositionUs = rendererPosition - windowOffset
            assertThat(abs(mediaPositionUs - wallUs)).isAtMost(100_000L)

            // → PRISTINE, anchored on the pipeline clock; LoadControl stops the decoder again.
            enginePosUs = mediaPositionUs
            staleRendererMediaUs = rendererMediaUs - 3_000_000L * (cycle % 2)
            clock.disarm()
        }
        assertThat(abs(enginePosUs - wallUs)).isAtMost(100_000L)
    }

    @Test
    fun `a buffer from before the realign anchor is dropped, the first one at it latches`() {
        val clock = PipelinePositionClock()
        clock.arm(offsetUs + 12_260_000L)
        assertThat(clock.accept(offsetUs + 6_408_707L, framesWritten = 0)).isFalse()
        assertThat(clock.isLatched).isFalse()
        assertThat(clock.accept(offsetUs + 12_260_000L, framesWritten = 0)).isTrue()
        assertThat(clock.isLatched).isTrue()
    }

    @Test
    fun `frames written before the latch are not counted`() {
        val clock = PipelinePositionClock()
        clock.arm(-1L)
        clock.accept(offsetUs + 20_000_000L, framesWritten = 5_000_000L)
        assertThat(clock.positionUs(framesWritten = 5_000_000L + rate, sampleRate = rate))
            .isEqualTo(offsetUs + 21_000_000L)
    }
}
