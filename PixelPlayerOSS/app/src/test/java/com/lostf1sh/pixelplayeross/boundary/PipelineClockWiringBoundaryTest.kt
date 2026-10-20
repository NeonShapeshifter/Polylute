package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-110 at the sink boundary: the pure [com.decent.usbaudio.media3.PipelinePositionClock] is proven
 * by `SignalModeRealignPositionTest`; this pins that the sink actually uses it everywhere the old
 * `usbStartMediaTimeUs + framesWritten` formula lived. Source-level: the sink needs Media3 and usbfs.
 */
class PipelineClockWiringBoundaryTest {

    private val sink = File(
        "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt"
    ).readText()

    @Test
    fun `no position is computed from the stream's cumulative frame count any more`() {
        assertThat(sink).doesNotContain("usbStartMediaTimeUs + frames * C.MICROS_PER_SECOND")
        assertThat(Regex("""pipelineClock\.positionUs\(""").findAll(sink).count()).isAtLeast(3)
    }

    @Test
    fun `stale buffers are dropped before the start is latched`() {
        val latch = sink.substringAfter("// After a PRISTINE → PROCESSED realign the renderer resumes")
        assertThat(latch).isNotEqualTo(sink)
        assertThat(latch.indexOf("pipelineClock.accept(")).isLessThan(latch.indexOf("usbStartMediaTimeUs = maxOf(0L, presentationTimeUs)"))
    }

    @Test
    fun `the PRISTINE to PROCESSED realign arms the clock with its anchor`() {
        assertThat(sink).contains("pipelineClock.arm(if (reanchorUs >= 0 && windowOffsetUs >= 0) windowOffsetUs + reanchorUs else -1L)")
    }

    @Test
    fun `every other re-arm of the start disarms the clock`() {
        val lines = sink.lines()
        val rearms = lines.indices.filter { lines[it].trim() == "usbStartMediaTimeNeedsInit = true" }
        assertThat(rearms).isNotEmpty()
        rearms.forEach { i ->
            val next = lines[i + 1].trim()
            assertThat(next == "pipelineClock.disarm()" || next.startsWith("// Armed with the anchor below")).isTrue()
        }
    }
}
