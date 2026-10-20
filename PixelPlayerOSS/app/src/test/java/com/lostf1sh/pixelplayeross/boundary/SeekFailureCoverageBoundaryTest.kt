package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-057 was reported as "a failed non-explicit (bootstrap) seek silently restarts at 0; only
 * explicit seeks pause". Verified in B5: not reproduced. Every native seek — user, item start,
 * bootstrap, realign — takes its generation from the same `seekGeneration`, and the outcome poll
 * reacts to FAILED for whichever generation is newest, so a failed bootstrap seek pauses too.
 * This pins that coverage so it cannot narrow to explicit seeks later. Green from the start.
 */
class SeekFailureCoverageBoundaryTest {
    private val sink = File(
        "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt"
    ).readText().replace("\r\n", "\n")

    @Test
    fun `bootstrap and realign seeks share the generation whose failure pauses`() {
        assertThat(sink).contains("seekEngine(engine, decision.targetUs, seekGeneration.incrementAndGet(), \"bootstrap\")")
        assertThat(sink).contains("val startGen = seekGeneration.incrementAndGet()")
        val poll = sink.substringAfter("if (seekGeneration.get() > DirectPathTelemetry.seekCompletedGeneration) {")
            .substringBefore("// RENDERER DOMAIN.")
        assertThat(poll).contains("NativeAudioEngine.SeekOutcome.FAILED ->")
        assertThat(poll).contains("onExplicitSeekFailed(completedGeneration)")
    }
}
