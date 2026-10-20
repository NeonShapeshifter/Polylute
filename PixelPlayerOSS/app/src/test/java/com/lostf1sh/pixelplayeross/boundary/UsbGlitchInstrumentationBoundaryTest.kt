package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-124 ("pss" now and then, not tied to the track): every candidate cause reports a rate-limited
 * USB_GLITCH line with its counters, and the feedback reaction no longer drops readings above ±1 %.
 * The limiter and the feedback rule are measured in tools/usb-glitch-test.cpp.
 */
class UsbGlitchInstrumentationBoundaryTest {
    private val jni = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni")
    private val output = File(jni, "usb-audio-output.cpp").readText().replace("\r\n", "\n")
    private val engine = File(jni, "native-audio-engine.cpp").readText().replace("\r\n", "\n")

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        return source.substring(start, source.indexOf("\n}\n", start))
    }

    @Test
    fun `feedback is applied through the shared rule and deviations are reported`() {
        val feedback = body(output, "static void handleFeedbackCompletion(")
        assertThat(feedback).contains("usbFeedbackApply(")
        assertThat(feedback).contains("USB_GLITCH_FEEDBACK_DEVIATION")
        // a reading the window refuses is still reported — through the shared helper since B12a-2 (see below)
        assertThat(feedback).contains("usbFeedbackWatchOutOfWindow(")
        assertThat(feedback).contains("announceImplausible(")
        assertThat(body(output, "static void announceImplausible(")).contains("USB_GLITCH_FEEDBACK_REJECTED")
        assertThat(feedback).doesNotContain("nominal * 1.01")
    }

    @Test
    fun `packet errors, an empty ring and slow reaps are reported`() {
        assertThat(body(output, "static int reapOldestUrb(")).contains("checkIsoCompletion(ctx, c)")
        assertThat(body(output, "static int reapOldestUrb(")).contains("USB_GLITCH_REAP_SLOW")
        assertThat(body(output, "static void reapReadyUrbs(")).contains("checkIsoCompletion(ctx, c)")
        assertThat(body(output, "static bool submitPcmToUrbsImpl(")).contains("USB_GLITCH_RING_EMPTY")
    }

    /**
     * B10 (AUD-124 "shh"): the Melody's feedback endpoint answered one value for minutes (+817 to +2259 ppm) while
     * the listener heard a "shh". The detector rides the feedback handler of the common output, so both producers
     * are covered; the thresholds and the policies are measured in tools/usb-glitch-test.cpp and
     * tools/natural-join-test.cpp (the whole output against a fake usbfs).
     */
    @Test
    fun `every applied feedback reading feeds the stuck detector and the audio path logs nothing per reading`() {
        val handler = body(output, "static void handleFeedbackCompletion(")
        assertThat(handler).contains("watchFeedbackReading(ctx, raw, nominal)")
        // only an applied reading counts: the call sits inside the branch that applied it
        assertThat(handler.indexOf("watchFeedbackReading(")).isGreaterThan(handler.indexOf("ctx->calibratedFpmf = newFpmf;"))
        // ... and a refused reading (beyond +-5 %) goes to the watch's own entry point instead (B12a-2)
        assertThat(handler.indexOf("watchFeedbackReading(")).isLessThan(handler.indexOf("usbFeedbackWatchOutOfWindow("))
        assertThat(handler).contains("announceImplausible(ev, nominal, ctx->sampleRate, ctx->calibratedFpmf)")

        // ~1000 readings a second: the per-reading helper allocates and logs nothing; only the event helper logs
        val perReading = body(output, "static void watchFeedbackReading(")
        assertThat(perReading).contains("usbFeedbackWatchObserve(")
        for (forbidden in listOf("reportUsbGlitch", "LOGI", "LOGW", "malloc", "new ", "std::string", "snprintf")) {
            assertThat(perReading).doesNotContain(forbidden)
        }
        // the streak's one line lives in its own helper, never in the per-reading one
        assertThat(body(output, "static void announceImplausible(")).contains("USB_GLITCH_FEEDBACK_REJECTED")
        assertThat(perReading).contains("if (ev.implausibleStarted) announceImplausible(")
        val announce = body(output, "static void announceFeedbackEvent(")
        assertThat(announce).contains("USB_GLITCH_FEEDBACK_STUCK")
        assertThat(announce).contains("USB_GLITCH_FEEDBACK_UNSTUCK")
        assertThat(output).contains("\"FEEDBACK_STUCK\", \"FEEDBACK_UNSTUCK\"")
    }

    @Test
    fun `an episode ends with its stream`() {
        assertThat(body(output, "Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(")).contains("endFeedbackWatch(ctx)")
        assertThat(body(output, "Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(")).contains("endFeedbackWatch(ctx)")
        assertThat(body(output, "Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioCreate(")).contains("usbFeedbackWatchReset(&ctx->feedbackWatch)")
    }

    @Test
    fun `both packetizer call sites take their rate from the feedback policy and auto is the default`() {
        val submit = body(output, "static bool submitPcmToUrbsImpl(")
        assertThat(submit).contains("double fpmf = effectiveFpmf(ctx);")
        assertThat(submit).doesNotContain("double fpmf = ctx->calibratedFpmf")
        val residual = body(output, "bool submitFinalPcmResidual(UsbAudioContext *ctx) {")
        assertThat(residual).contains("effectiveFpmf(ctx),")
        assertThat(residual).doesNotContain("ctx->calibratedFpmf,")
        // the policy changes only the increment per microframe: nothing in it touches the accumulator
        val effective = body(output, "static double effectiveFpmf(")
        assertThat(effective).contains("usbFeedbackPolicyFpmf(")
        assertThat(effective).doesNotContain("frameAccumulator")
        // never persisted, and a new process starts in auto (B12a-2), in debug and in release alike
        assertThat(output).contains("static std::atomic<int> g_feedbackPolicy{USB_FEEDBACK_AUTO};")
        assertThat(output).doesNotContain("g_feedbackPolicy{USB_FEEDBACK_FOLLOW}")
        assertThat(output).contains("Java_com_decent_usbaudio_UsbAudioStream_nativeSetFeedbackPolicy(")
        assertThat(output).contains("Java_com_decent_usbaudio_UsbAudioStream_nativeGetFeedbackStats(")
    }

    @Test
    fun `slow file reads are reported and the log uses its own tag`() {
        val readAt = engine.substring(engine.indexOf("ssize_t readAt("), engine.indexOf("off64_t getLength()"))
        assertThat(readAt).contains("USB_GLITCH_READ_SLOW")
        assertThat(output).contains("\"USB_GLITCH\"")
        assertThat(File(jni, "CMakeLists.txt").readText()).contains("usb-glitch.cpp")
    }
}
