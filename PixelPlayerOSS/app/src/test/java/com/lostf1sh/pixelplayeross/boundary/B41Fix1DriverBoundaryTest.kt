package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B41-fix1, Part A (the USB feedback), pinned in the source. The behaviour is measured by the native gate (`tools/feedback-lifetime-test.cpp`,
 * `tools/natural-join-test.cpp`, `tools/usb-glitch-test.cpp`, `tools/usb-second-ring-test.cpp`, run by `test-direct-sequence-boundaries.sh`);
 * this keeps the shape of the changes from being edited back without those tests being looked at.
 */
class B41Fix1DriverBoundaryTest {
    private val jni = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni")
    private val output = File(jni, "usb-audio-output.cpp").readText().replace("\r\n", "\n")
    private val glitchHeader = File(jni, "usb-glitch.h").readText().replace("\r\n", "\n")

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        return source.substring(start, source.indexOf("\n}\n", start))
    }

    @Test
    fun `A1a both reads of the feedback endpoint decode only a clean transfer of the four bytes asked for`() {
        for (read in listOf("static void handleFeedbackCompletion(", "static double readFeedback(")) {
            val b = body(output, read)
            assertThat(b).contains("usbFeedbackPacketVerdict(")
            assertThat(b).contains("noteFeedbackDropped(")
            // the old test, which looked at the length alone, is gone from both
            assertThat(b).doesNotContain("actual_length >= 4")
        }
        // the one place the decision is made is pure and host-tested; the URB asks for exactly the size the verdict wants
        assertThat(glitchHeader).contains("#define USB_FEEDBACK_BYTES 4")
        assertThat(output).doesNotContain("actual_length >= 4")
        assertThat(body(output, "static bool submitFeedbackUrb(")).contains("u->buffer_length = USB_FEEDBACK_BYTES;")
        assertThat(body(output, "static bool submitFeedbackUrb(")).contains("u->iso_frame_desc[0].length = USB_FEEDBACK_BYTES;")
        // a transfer that is not a reading is re-queued like any other: the continuous read still resubmits at its end
        val handler = body(output, "static void handleFeedbackCompletion(")
        assertThat(handler.indexOf("submitFeedbackUrb(ctx);")).isGreaterThan(handler.indexOf("noteFeedbackDropped("))
        // ... and the start keeps the nominal rate when the read was not one
        assertThat(body(output, "Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(")).contains("startVerdict != USB_FEEDBACK_PACKET_OK")
    }

    @Test
    fun `A1b the rate is asked again between two URBs when the policy turns to refusing, and nowhere else`() {
        val submit = body(output, "static bool submitPcmToUrbsImpl(")
        assertThat(submit).contains("double fpmf = effectiveFpmf(ctx);")
        assertThat(submit).contains("uint32_t refusalSeen = ctx->refusalEpoch;")
        assertThat(submit).contains("if (ctx->refusalEpoch != refusalSeen) {")
        // after the reap that handles the feedback, before the URB is copied into its slot and submitted
        assertThat(submit.indexOf("if (ctx->refusalEpoch != refusalSeen) {")).isGreaterThan(submit.indexOf("reapOldestUrb(ctx, 200, ReapSite::BACKPRESSURE)"))
        assertThat(submit.indexOf("if (ctx->refusalEpoch != refusalSeen) {")).isLessThan(submit.indexOf("memcpy(slot->buffer, data + offset, plan.totalBytes);"))
        // the URB planned with the old rate is planned again; the plan is pure, the accumulator moves only at the submit
        val replan = submit.substringAfter("if (ctx->refusalEpoch != refusalSeen) {").substringBefore("// AUD-124: checked only when the ring is well below full")
        assertThat(replan).contains("fpmf = effectiveFpmf(ctx);")
        assertThat(replan).contains("plan = planUsbPcmUrb(")
        assertThat(replan).doesNotContain("frameAccumulator =")
        // the epoch turns in one place, when the policy goes from obeying to refusing
        assertThat(output.split("refusalEpoch++").size - 1).isEqualTo(1)
        assertThat(body(output, "static void updateRefusal(")).contains("if (refusing && !ctx->refusing) ctx->refusalEpoch++;")
        // the follower: a refused reading never enters it under `auto`; `follow` takes everything in the window, as it always did
        assertThat(body(output, "static void handleFeedbackCompletion(")).contains("if (!(ctx->refusing && usbFeedbackPolicy() == USB_FEEDBACK_AUTO)) ctx->calibratedFpmf = newFpmf;")
        // the logs still show the DAC's word, not the follower's
        assertThat(output).contains("ctx->urbsInFlight, inputBitDepth, ctx->readingFpmf,")
    }

    @Test
    fun `A1c the ring records where the output already looks, nothing is logged per reading and nothing is on the PCM path`() {
        // the new records are made at the places that already see the thing: the packetizer's rate in effectiveFpmf, the wire at the submit
        assertThat(body(output, "static double effectiveFpmf(")).contains("usbSecondRingSent(&g_secondRing")
        assertThat(body(output, "static int submitRingUrb(")).contains("usbSecondRingWire(&g_secondRing")
        assertThat(body(output, "static void noteFeedbackDropped(")).contains("usbSecondRingFeedbackDropped(&g_secondRing")
        // the per-reading path allocates and logs nothing (the policy events are rare and go through their own helper)
        val perReading = body(output, "static void watchFeedbackReading(")
        assertThat(perReading).contains("usbSecondRingHeld(&g_secondRing")
        for (forbidden in listOf("reportUsbGlitch", "LOGI", "LOGW", "malloc", "new ", "std::string", "snprintf")) {
            assertThat(perReading).doesNotContain(forbidden)
        }
        // none of it is in the packetizer's loop or its converters
        val submit = output.substringAfter("static bool submitPcmToUrbsImpl(").substringBefore("bool submitPcmToUrbs(UsbAudioContext")
        assertThat(submit).doesNotContain("usbSecondRing")
        for (pcm in listOf("usb-pcm-packetizer.cpp", "usb-pcm-convert.cpp")) {
            assertThat(File(jni, pcm).readText()).doesNotContain("usbSecondRing")
        }
        // the dump carries the legend, the seconds and the policy events, with the stream's number
        val dump = body(output, "size_t usbSecondSummaries(")
        assertThat(dump).contains("legend:")
        assertThat(dump).contains("usbSecondRingFormatEvents(")
        assertThat(output).contains("ctx->streamSeq = g_streamFacts.seq;")
        // the new log kind is registered with its name, after the ones the logs already use
        assertThat(output).contains("\"FEEDBACK_STUCK\", \"FEEDBACK_UNSTUCK\", \"OUTPUT_GAP\", \"FEEDBACK_INVALID\",")
    }

    @Test
    fun `A1 the gestures that precede a change are in the same ring`() {
        assertThat(body(output, "void pipelineResume(")).contains("noteGesture(ctx, USB_POLICY_RESUME")
        assertThat(body(output, "int pipelineBeginSeek(")).contains("noteGesture(ctx, USB_POLICY_SEEK")
        assertThat(body(output, "bool pipelineBeginPause(")).contains("noteGesture(ctx, USB_POLICY_PAUSE")
        assertThat(body(output, "Java_com_decent_usbaudio_UsbAudioStream_nativeMarkIdle(")).contains("noteGesture(ctx, USB_POLICY_IDLE")
        assertThat(body(output, "Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(")).contains("noteGesture(ctx, USB_POLICY_STREAM_START")
    }
}
