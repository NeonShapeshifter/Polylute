package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-140 wiring in the native engine (the decisions are host-tested in `tools/seek-barrier-test.cpp`
 * and `tools/usb-glitch-test.cpp`, which the native gate runs).
 *
 * B7-fix4 sent a pause that had queued its fade to the discard branch on the premise that the ring
 * then ends in silence. It does not: the 8 ms fade goes behind ~80 ms of audio at full level. On the
 * device, 3 of 4 pause+seek races took that branch and two dropped 55 and 60 ms of music
 * (`BARRIER|… op[requested=80 ok=60 …]`). The cut now follows what is unplayed in the ring.
 */
class PauseSeekRaceBoundaryTest {
    private val jni = "../../decent-player/libs/decent-usb-audio-driver/src/main/jni"
    private fun read(name: String) = File("$jni/$name").readText().replace("\r\n", "\n")

    private val engine = read("native-audio-engine.cpp")
    private val output = read("usb-audio-output.cpp")

    private val seekBlock = engine.substringAfter("const bool pauseFaded = engine->pauseFadeQueued;")
        .substringBefore("if (seekOk) {")

    @Test
    fun `the seek cut is decided by what is unplayed in the ring, not by the pause`() {
        assertThat(seekBlock).contains("const int64_t unplayedFrames = usbQueuedFrames(engine->usbCtx);")
        assertThat(seekBlock).contains("seekCutFor(engine->hasSubmittedAudio, unplayedFrames,")
        // No pause flag reaches the decision.
        val call = seekBlock.substringAfter("seekCutFor(").substringBefore(");")
        assertThat(call).doesNotContain("paused")
        assertThat(call).doesNotContain("pauseFaded")
        assertThat(call).doesNotContain("urbsInFlight")
    }

    @Test
    fun `a pause that already faded the ring does not get a second continuation fade`() {
        assertThat(seekBlock).contains("if (!pauseFaded) queueSeekFadeOut(engine);")
        // The silence feed still runs, and the resume fades in as after any pause.
        assertThat(seekBlock).contains("seekWhileFeedingSilence(engine, (FLAC__uint64)targetSample)")
        assertThat(seekBlock).contains("if (engine->paused.load()) engine->pauseFadeQueued = true;")
    }

    @Test
    fun `the discard branch is only for a ring with nothing left to play`() {
        val discard = seekBlock.substringAfter("} else {")
        assertThat(discard).contains("if (cut != SEEK_CUT_KEEP_TAIL) discardQueuedPcm(engine->usbCtx);")
    }

    @Test
    fun `RING_EMPTY counts a dry ring whatever its duration`() {
        val submit = output.substringAfter("bool submitPcmToUrbs(").substringBefore("UrbSlot *slot = &ctx->ring[ctx->submitIdx];")
        assertThat(submit).contains("usbRingStarvedMs(ctx->lastSubmitMs, monotonicMs(), ctx->playOutBeforeDrain)")
        // The 200 ms window that hid the longest starvations.
        assertThat(submit).doesNotContain("sinceMs < 200")
        assertThat(submit).contains("usbQueuedFrames(ctx) == 0")
    }

    @Test
    fun `an intentional idle is announced so a resume is not counted as a starvation`() {
        // The native engine's pause branch zeroes the stamp before it waits.
        val pause = engine.substringAfter("// Handle pause").substringBefore("waitWhilePaused(engine);")
        assertThat(pause).contains("engine->usbCtx->lastSubmitMs = 0;")
        // The ExoPlayer pipeline's writer does the same from the thread that writes.
        val thread = File(
            "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbStreamingThread.kt"
        ).readText().replace("\r\n", "\n")
        // (B9: the pause moved into its own function; the writer still announces the idle itself.)
        val paused = thread.substringAfter("private fun performPause() {").substringBefore("/** The next audio a pause can ramp down")
        assertThat(paused).contains("usbStream.markIdle()")
        assertThat(output).contains("Java_com_decent_usbaudio_UsbAudioStream_nativeMarkIdle(")
        val stream = File(
            "../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioStream.kt"
        ).readText()
        assertThat(stream).contains("private external fun nativeMarkIdle(handle: Long)")
    }
}
