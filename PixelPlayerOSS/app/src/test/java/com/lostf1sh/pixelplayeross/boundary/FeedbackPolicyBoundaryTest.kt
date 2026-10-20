package com.lostf1sh.pixelplayeross.boundary

import com.decent.usbaudio.FeedbackPolicy
import com.decent.usbaudio.media3.DirectPathTelemetry
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B10 (AUD-124 "shh"): the debug side of the feedback policy — the switch, the reply line and the capture's
 * counters — and the Kotlin half of the stats the native layer exports. The detector and the policies
 * themselves are measured against the real output in tools/usb-glitch-test.cpp and tools/natural-join-test.cpp.
 */
class FeedbackPolicyBoundaryTest {
    private val appRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("Could not find the app module root from ${File(".").absolutePath}")
    private val receiver = File(appRoot, "src/debug/java/com/lostf1sh/pixelplayeross/dap/admin/DapDebugPolicyReceiver.kt")
        .readText().replace("\r\n", "\n")
    private val capture = File(appRoot, "src/debug/java/com/lostf1sh/pixelplayeross/dap/admin/DirectPathCapture.kt")
        .readText().replace("\r\n", "\n")
    private val manifest = File(appRoot, "src/debug/AndroidManifest.xml").readText().replace("\r\n", "\n")

    @Test
    fun `the modes parse by name and anything else changes nothing`() {
        assertThat(FeedbackPolicy.parse("follow")).isEqualTo(FeedbackPolicy.FOLLOW)
        assertThat(FeedbackPolicy.parse("nominal")).isEqualTo(FeedbackPolicy.NOMINAL)
        assertThat(FeedbackPolicy.parse("clamp")).isEqualTo(FeedbackPolicy.CLAMP)
        assertThat(FeedbackPolicy.parse("auto")).isEqualTo(FeedbackPolicy.AUTO)
        // a typo, a different case, a missing extra: null, which the receiver reads as "leave it as it is"
        for (bad in listOf("Follow", "NOMINAL", "off", "", " clamp", null)) assertThat(FeedbackPolicy.parse(bad)).isNull()
    }

    @Test
    fun `the ids are the native enum`() {
        // usb-glitch.h: USB_FEEDBACK_FOLLOW = 0, NOMINAL = 1, CLAMP = 2, AUTO = 3
        assertThat(listOf(FeedbackPolicy.FOLLOW, FeedbackPolicy.NOMINAL, FeedbackPolicy.CLAMP, FeedbackPolicy.AUTO))
            .containsExactly(0, 1, 2, 3).inOrder()
        val header = File(appRoot, "../../decent-player/libs/decent-usb-audio-driver/src/main/jni/usb-glitch.h").readText()
        for ((name, id) in listOf("FOLLOW" to 0, "NOMINAL" to 1, "CLAMP" to 2, "AUTO" to 3)) {
            assertThat(header).contains("USB_FEEDBACK_$name = $id,")
        }
        for (id in 0L..3L) assertThat(FeedbackPolicy.parse(FeedbackPolicy.name(id))?.toLong()).isEqualTo(id)
        assertThat(FeedbackPolicy.name(9L)).isEqualTo("?")
        assertThat(FeedbackPolicy.name(null)).isEqualTo("?")
    }

    @Test
    fun `a snapshot carries the native stats and says nothing it did not read`() {
        val before = DirectPathTelemetry.usbFeedbackStatsProvider
        try {
            DirectPathTelemetry.usbFeedbackStatsProvider = null
            val unread = DirectPathTelemetry.snapshot()
            assertThat(unread.feedbackPolicy).isEqualTo("?")
            assertThat(unread.feedbackStuck).isFalse()
            assertThat(unread.feedbackStuckEpisodes).isEqualTo(0L)

            // [policy, stuck, episodes, readings stuck, ms stuck, longest, fpmf, ppm, readings, last, rate, sending]
            DirectPathTelemetry.usbFeedbackStatsProvider = {
                longArrayOf(1, 1, 2, 9_000, 61_500, 55_000, 6_004_900, 817, 123_456, 6_004_900, 48_000, 6_000_000)
            }
            val s = DirectPathTelemetry.snapshot()
            assertThat(s.feedbackPolicy).isEqualTo("nominal")
            assertThat(s.feedbackStuck).isTrue()
            assertThat(s.feedbackStuckEpisodes).isEqualTo(2L)
            assertThat(s.feedbackStuckReadings).isEqualTo(9_000L)
            assertThat(s.feedbackStuckMs).isEqualTo(61_500L)
            assertThat(s.feedbackStuckLongestMs).isEqualTo(55_000L)
            assertThat(s.feedbackStuckFpmf).isWithin(1e-9).of(6.0049)
            assertThat(s.feedbackStuckPpm).isEqualTo(817L)
            assertThat(s.feedbackReadings).isEqualTo(123_456L)
            assertThat(s.feedbackLastFpmf).isWithin(1e-9).of(6.0049)
            assertThat(s.feedbackSendingFpmf).isWithin(1e-9).of(6.0)

            // B12a-2: the three counters of the readings not obeyed ride after them; an older library without them reads 0
            assertThat(s.feedbackRejectedReadings).isEqualTo(0L)
            DirectPathTelemetry.usbFeedbackStatsProvider = {
                longArrayOf(3, 0, 0, 0, 0, 0, 0, 0, 5_000, 5_512_500, 44_100, 5_512_500, 4_321, 2, 5_787_500)
            }
            val b = DirectPathTelemetry.snapshot()
            assertThat(b.feedbackPolicy).isEqualTo("auto")
            assertThat(b.feedbackRejectedReadings).isEqualTo(4_321L)
            assertThat(b.feedbackImplausibleStreaks).isEqualTo(2L)
            assertThat(b.feedbackRejectedFpmf).isWithin(1e-9).of(5.7875)

            // a short array (an older native library) is not read as zeros that look like readings
            DirectPathTelemetry.usbFeedbackStatsProvider = { longArrayOf(1, 1) }
            assertThat(DirectPathTelemetry.snapshot().feedbackPolicy).isEqualTo("?")
        } finally {
            DirectPathTelemetry.usbFeedbackStatsProvider = before
        }
    }

    @Test
    fun `the switch is declared, requires its mode, is never persisted and replies with the line the reviewer reads`() {
        assertThat(manifest).contains("com.lostf1sh.pixelplayeross.debug.SET_FEEDBACK_POLICY")
        assertThat(receiver).contains("ACTION_SET_FEEDBACK_POLICY ->")
        assertThat(receiver).contains("\"com.lostf1sh.pixelplayeross.debug.SET_FEEDBACK_POLICY\"")

        val action = receiver.substringAfter("ACTION_SET_FEEDBACK_POLICY ->").substringBefore("private fun reportReplayGainFactor")
        // the mode is required: parse() is null for a missing or misspelled one, which logs and changes nothing
        assertThat(action).contains("FeedbackPolicy.parse(intent.getStringExtra(EXTRA_MODE))")
        assertThat(action).contains("if (mode == null)")
        assertThat(action).contains("SET_FEEDBACK_POLICY needs --es mode follow|nominal|clamp|auto; unchanged")
        assertThat(action).contains("UsbAudioStream.nativeSetFeedbackPolicy(mode)")
        // process-only, like SET_USB_WAIT_MODE: no preferences, no switch object that persists
        for (persisted in listOf("SharedPreferences", "DataStore", "NativeEngineDebugSwitch", "PristinePipelineDebugSwitch", "putString", "putInt", ".edit")) {
            assertThat(action).doesNotContain(persisted)
        }
        val report = receiver.substringAfter("private fun reportFeedbackPolicy").substringBefore("private fun fpmfText")
        for (field in listOf("FEEDBACK_POLICY|mode=", "|stuck=", "|lastFpmf=", "|nominal=", "|sending=", "|rejected=", "|streaks=", "|lastRejected=", "|tag=")) {
            assertThat(report).contains(field)
        }
        // the spec's order: mode, stuck, lastFpmf, nominal ... tag
        assertThat(report.indexOf("|stuck=")).isGreaterThan(report.indexOf("mode="))
        assertThat(report.indexOf("|lastFpmf=")).isGreaterThan(report.indexOf("|stuck="))
        assertThat(report.indexOf("|nominal=")).isGreaterThan(report.indexOf("|lastFpmf="))
        assertThat(report.indexOf("|tag=")).isGreaterThan(report.indexOf("|nominal="))
    }

    @Test
    fun `the capture prints the stuck counters`() {
        for (key in listOf(
            "perf.feedbackPolicy", "perf.feedbackStuck=", "perf.feedbackStuckEpisodes", "perf.feedbackStuckMs",
            "perf.feedbackStuckReadings", "perf.feedbackStuckLongestMs", "perf.feedbackStuckFpmf", "perf.feedbackStuckPpm",
            "perf.feedbackReadings", "perf.feedbackLastFpmf", "perf.feedbackSendingFpmf",
            "perf.feedbackRejectedReadings", "perf.feedbackImplausibleStreaks", "perf.feedbackRejectedFpmf",
        )) {
            assertThat(capture).contains("SNAP|\$label|$key")
        }
    }

    /**
     * B12a-2: `auto` is what a process starts with, in debug and in release, and the thresholds that make it refuse
     * an implausible reading are the ones the data justify (the comment next to them in usb-glitch.h says how).
     */
    @Test
    fun `auto is the default policy and the plausibility bounds sit between the healthy dither and the absurd`() {
        val jni = File(appRoot, "../../decent-player/libs/decent-usb-audio-driver/src/main/jni")
        val output = File(jni, "usb-audio-output.cpp").readText()
        val header = File(jni, "usb-glitch.h").readText()
        assertThat(output).contains("static std::atomic<int> g_feedbackPolicy{USB_FEEDBACK_AUTO};")
        // not persisted, not release-gated: nothing in the native layer reads a preference or a build flag for it
        val setter = output.substringAfter("void usbSetFeedbackPolicy(int policy) {").substringBefore("\n}")
        assertThat(setter).doesNotContain("BuildConfig")
        // ppm: > the routine healthy blip (+0.27 % = 2710) and the largest healthy sample (+0.54 % single), < the +4.99 % seen
        val absurd = Regex("""USB_FEEDBACK_ABSURD_PPM ([0-9.]+)""").find(header)!!.groupValues[1].toDouble()
        val sustained = Regex("""USB_FEEDBACK_SUSTAINED_PPM ([0-9.]+)""").find(header)!!.groupValues[1].toDouble()
        val sustainedMs = Regex("""USB_FEEDBACK_SUSTAINED_MS ([0-9]+)""").find(header)!!.groupValues[1].toLong()
        assertThat(sustained).isGreaterThan(2_710.0 * 1.5)       // the healthy blip with margin
        assertThat(absurd).isGreaterThan(9_956.0 * 1.5)          // the +1 % start transient and the +1 % stuck value
        assertThat(absurd).isLessThan(49_900.0)                  // the +4.99 % reading that prompted it
        assertThat(sustained).isLessThan(absurd)
        assertThat(sustainedMs).isGreaterThan(3_000L)            // longer than the longest healthy transient on record
        // the 1000 ppm of the device note would refuse every ordinary blip: it must not come back
        assertThat(sustained).isGreaterThan(1_000.0)
    }

    @Test
    fun `the sink hands the telemetry its provider without loading the native library`() {
        val sink = File(appRoot, "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt")
            .readText()
        // a lambda, so constructing a sink in a unit test does not load libdecent_usb_audio
        assertThat(sink).contains("DirectPathTelemetry.usbFeedbackStatsProvider = { UsbAudioStream.nativeGetFeedbackStats() }")
    }
}
