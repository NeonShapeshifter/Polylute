package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-107 (owner: the crack on releasing the progress bar happens only in PRISTINE, never in
 * PROCESSED): the native engine's seek cut the queued audio at full level, fed the DAC 10 ms of
 * silence for a seek measured at 20-550 ms, entered the new position with a 5 ms ramp, and the sink
 * reset the packetizer from the playback thread while the decode thread was submitting.
 *
 * Now an audible seek plays the ring out with a fade-out of the real continuation queued behind it,
 * keeps the DAC fed with silence while the decoder seeks on a helper thread, and fades in over 15 ms.
 * The ramp shapes and "no empty microframe" are measured by tools/seek-barrier-test.cpp.
 */
class NativeSeekClickBoundaryTest {
    private val jni = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni")
    private val engine = File(jni, "native-audio-engine.cpp").readText().replace("\r\n", "\n")
    private val output = File(jni, "usb-audio-output.cpp").readText().replace("\r\n", "\n")
    private val sink = File(
        "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt"
    ).readText().replace("\r\n", "\n")

    private fun seekBlock() = engine.substringAfter("Handle seek BEFORE pause").substringBefore("// Handle pause")

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        return source.substring(start, source.indexOf("\n}\n", start))
    }

    @Test
    fun `an audible seek fades the continuation out and seeks while feeding silence`() {
        val block = seekBlock()
        val fadeOut = block.indexOf("queueSeekFadeOut(engine)")
        val fedSeek = block.indexOf("seekWhileFeedingSilence(engine,")
        assertThat(fadeOut).isAtLeast(0)
        assertThat(fedSeek).isGreaterThan(fadeOut)
        // The old fixed 10 ms of silence and 5 ms ramp are gone.
        assertThat(block).doesNotContain("sampleRate / 100")
        assertThat(block).doesNotContain("sampleRate / 200")
        assertThat(block).contains("seekFadeInFrames(engine->sampleRate)")
    }

    @Test
    fun `the fade-out ramps the decoded continuation down and queues it`() {
        // Since AUD-123 the seek, the track-change stop and the pause share one fade-out.
        assertThat(body(engine, "static void queueSeekFadeOut(")).contains("queueFadeOut(engine, nullptr, 0, false)")
        val fade = body(engine, "static int64_t queueFadeOut(")
        assertThat(fade).contains("seekFadeOutFrames(engine->sampleRate)")
        assertThat(fade).contains("parser->readBuffer(")
        assertThat(fade).contains("pcmApplyGainRamp(")
        assertThat(fade).contains("submitPcmToUrbs(")
    }

    @Test
    fun `the decoder seeks on a helper thread while the decode thread keeps the DAC fed`() {
        val fed = body(engine, "static bool seekWhileFeedingSilence(")
        val start = fed.indexOf("pthread_create(")
        val feed = fed.indexOf("seekFeedSilenceUntilDone(")
        val join = fed.indexOf("pthread_join(")
        assertThat(start).isAtLeast(0)
        assertThat(feed).isGreaterThan(start)
        assertThat(join).isGreaterThan(feed)
        assertThat(fed).contains("seekSilenceLeadFrames(")
    }

    @Test
    fun `the queued audio is measured after retiring what already played`() {
        assertThat(body(output, "int64_t usbQueuedFrames(")).contains("reapReadyUrbs(ctx)")
    }

    @Test
    fun `no packetizer reset from the playback thread while the decode thread submits`() {
        val flush = sink.substringAfter("override fun flush()").substringBefore("usbStartMediaTimeNeedsInit = true")
        assertThat(flush).contains("nativeEngine?.isRunning != true")
        assertThat(flush).doesNotContain("if (thread != null) thread.flush() else usbAudioStream?.flush()")
    }

    @Test
    fun `the seek barrier module is built`() {
        assertThat(File(jni, "CMakeLists.txt").readText()).contains("seek-barrier.cpp")
    }

    /**
     * BARRIER UNRESOLVED in the device logs: completions reaped while the feedback URB drained were
     * resolved by identity (BARRIER_IDENTITY resolved=80 residual=0) but not counted as retired, and
     * usbfs's EINVAL for an URB that already completed was counted as a failure.
     */
    @Test
    fun `barrier accounting counts early reaps and treats EINVAL as already complete`() {
        val drain = body(output, "static int drainAllUrbs(")
        val feedback = drain.substring(0, drain.indexOf("// Phase 1"))
        assertThat(feedback).contains("if (tally) tally->retired++;")
        assertThat(drain).contains("errno == EINVAL")
    }
}
