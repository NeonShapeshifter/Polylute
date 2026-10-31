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

    private val output = read("usb-audio-output.cpp")

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
