package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-123 (owner, build B5): the crack went from the seek but stayed, softer, on a track change and
 * on pause — both still cut the queued audio dry. They now use the B5 scheme: the ring plays out,
 * the real continuation is queued faded out, and the next audio fades in. Ramp shapes and continuity
 * are measured in tools/seek-barrier-test.cpp; this pins the wiring.
 */
class TrackChangeFadeBoundaryTest {
    private val libs = "../../decent-player/libs"
    private val engine = File("$libs/decent-usb-audio-driver/src/main/jni/native-audio-engine.cpp").readText().replace("\r\n", "\n")
    private val output = File("$libs/decent-usb-audio-driver/src/main/jni/usb-audio-output.cpp").readText().replace("\r\n", "\n")
    private val wrapper = File("$libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/NativeAudioEngine.kt").readText().replace("\r\n", "\n")
    private val sink = File("$libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt").readText().replace("\r\n", "\n")

    private fun loop(): String {
        val start = engine.indexOf("static void *decodeThreadFunc(")
        return engine.substring(start, engine.indexOf("\n}\n", start))
    }

    @Test
    fun `a stop requested with a fade queues the faded continuation before the thread exits`() {
        val loop = loop()
        assertThat(loop).contains("engine->fadeOutOnStop.load()")
        assertThat(loop).contains("queueStopFadeOut(engine, usbData, framesInBuffer)")
        val stopFade = engine.substringAfter("static void queueStopFadeOut(").substringBefore("\n}\n")
        assertThat(stopFade).contains("queueFadeOut(engine, block, blockFrames, false)")
        assertThat(stopFade).contains("engine->usbCtx->playOutBeforeDrain = true;")
        assertThat(engine).contains("nativeRequestFadeOutOnStop")
        assertThat(wrapper).contains("fun stopWithFadeOut()")
    }

    @Test
    fun `the next engine keeps the faded tail and enters with a fade-in`() {
        val seek = engine.substringAfter("Handle seek BEFORE pause").substringBefore("// Handle pause")
        // Since AUD-128 a natural end's tail is kept as well (see NaturalEndTailBoundaryTest).
        assertThat(seek).contains("if (cut != SEEK_CUT_KEEP_TAIL) discardQueuedPcm(engine->usbCtx);")
        assertThat(seek).contains("(engine->entryFadeIn || engine->usbCtx->playOutBeforeDrain)")
        assertThat(engine).contains("nativeEnterWithFadeIn")
        assertThat(wrapper).contains("fun enterWithFadeIn()")
    }

    @Test
    fun `pause fades out and the resume enters on the rest of the block, faded in`() {
        val pause = engine.substringAfter("// Handle pause").substringBefore("// Decode one FLAC frame")
        assertThat(pause).contains("queueFadeOut(engine, nullptr, 0, true)")
        assertThat(pause).contains("resumeAfterPauseFade(engine)")
    }

    @Test
    fun `the sink fades an audible track change and the teardown lets the ring play out`() {
        val configure = sink.substringAfter("// Track changed or engine finished — destroy old engine")
            .substringBefore("clearForcedRouting()")
        assertThat(configure).contains("oldEngine.stopWithFadeOut()")
        assertThat(configure).contains("pendingEntryFadeIn = true")
        assertThat(sink).contains("if (pendingEntryFadeIn) {")
        val drain = output.substringAfter("Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(").substringBefore("\n}\n")
        assertThat(drain).contains("playOutFadedTail(ctx)")
    }
}
