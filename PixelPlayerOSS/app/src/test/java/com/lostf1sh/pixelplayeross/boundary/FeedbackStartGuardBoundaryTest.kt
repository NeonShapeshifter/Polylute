package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B48-fix1 (item 3): the feedback start guard. For the first 500 ms after the streaming interface (re)starts the packetizer is given the nominal rate whatever the feedback reads.
 * The behaviour is pinned by the simulation (`natural-join-test.cpp`, `testFeedbackStartGuard`); this pins what a simulation cannot: the bridge that switches it, that the number is the
 * plan's and not a literal, that it is off by default in debug and release, and that it holds back only what is SENT.
 */
class FeedbackStartGuardBoundaryTest {
    private val appRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("Could not find the app module root from ${File(".").absolutePath}")
    private fun text(path: String) = File(appRoot, path).readText().replace("\r\n", "\n")
    private val receiver = text("src/debug/java/com/lostf1sh/pixelplayeross/dap/admin/DapDebugPolicyReceiver.kt")
    private val debugManifest = text("src/debug/AndroidManifest.xml")
    private val mainManifest = text("src/main/AndroidManifest.xml")
    private val jni = File(appRoot, "../../decent-player/libs/decent-usb-audio-driver/src/main/jni").canonicalFile
    private val output = File(jni, "usb-audio-output.cpp").readText().replace("\r\n", "\n")
    private val packetizerHeader = File(jni, "usb-pcm-packetizer.h").readText().replace("\r\n", "\n")
    private val stream = File(appRoot, "../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioStream.kt")
        .canonicalFile.readText().replace("\r\n", "\n")

    @Test
    fun `the action is declared in the debug manifest and nowhere else`() {
        assertThat(debugManifest).contains("com.lostf1sh.pixelplayeross.debug.SET_FEEDBACK_START_GUARD")
        assertThat(mainManifest).doesNotContain("SET_FEEDBACK_START_GUARD")
        assertThat(receiver).contains("\"com.lostf1sh.pixelplayeross.debug.SET_FEEDBACK_START_GUARD\"")
        val mainSources = File(appRoot, "src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
        for (file in mainSources) assertThat(file.readText()).doesNotContain("SET_FEEDBACK_START_GUARD")
    }

    @Test
    fun `the extra is required, nothing is persisted and the reply is one line`() {
        val set = receiver.substringAfter("ACTION_SET_FEEDBACK_START_GUARD ->").substringBefore("ACTION_RECOVER_DAC_NOW ->")
        assertThat(set).contains("if (!intent.hasExtra(EXTRA_ENABLED))")
        assertThat(set).contains("SET_FEEDBACK_START_GUARD needs --ez enabled true|false; unchanged")
        assertThat(set).contains("UsbAudioStream.nativeSetFeedbackStartGuardEnabled(")
        for (persisted in listOf("SharedPreferences", "DataStore", "putString", "putInt", ".edit")) assertThat(set).doesNotContain(persisted)
        val report = receiver.substringAfter("private fun reportFeedbackStartGuard").substringBefore("private fun reportRateSwitchPreroll")
        for (field in listOf("FEEDBACK_START_GUARD|enabled=", "|ms=", "|tag=")) assertThat(report).contains(field)
    }

    @Test
    fun `the guard is off by default and the native exports match the Kotlin externals`() {
        assertThat(output).contains("static std::atomic<bool> g_feedbackStartGuardEnabled{false};")
        for (name in listOf("nativeSetFeedbackStartGuardEnabled", "nativeFeedbackStartGuardEnabled")) {
            assertThat(output).contains("Java_com_decent_usbaudio_UsbAudioStream_$name(")
            assertThat(stream).contains("external fun $name(")
        }
    }

    @Test
    fun `the 500 ms is the plan's named number and every start says whether the guard is on`() {
        assertThat(packetizerHeader).contains("#define USB_TRANSPORT_FEEDBACK_START_GUARD_MS 500")
        // the plan carries it, and the output reads the plan: no literal 500 beside the guard
        assertThat(output).contains("ctx->plan.feedbackStartGuardMs")
        val armed = output.substringAfter("static void armStartOfAudio").substringBefore("\n}\n")
        assertThat(armed).contains("Start: feedback start guard=%d ms (%s)")
        assertThat(armed).contains("Start: feedback start guard=off (%s)")
        assertThat(armed).doesNotContain("500")
    }

    @Test
    fun `the guard holds back what is sent and never what is read`() {
        val holds = output.substringAfter("static bool startGuardHolds").substringBefore("\n}\n")
        assertThat(holds).contains("policy == USB_FEEDBACK_FOLLOW")     // the diagnostic policy never obeys it
        val sent = output.substringAfter("static double policyFpmfNow").substringBefore("\n}\n")
        assertThat(sent).contains("startGuardHolds(ctx, policy)")
        // the only caller is the one that builds the packetizer's rate: the watch, the healthy average and the lock detector are fed from the readings, upstream of it
        assertThat(Regex("startGuardHolds\\(").findAll(output).count()).isEqualTo(2)    // its definition and policyFpmfNow
    }

    @Test
    fun `a restart of the interface and a new stream each owe the guard and the priming`() {
        val arm = Regex("armStartOfAudio\\(").findAll(output).count()
        assertThat(arm).isEqualTo(3)    // the definition, nativeUsbAudioStart and usbDacRecoveryEnd
        assertThat(output.substringAfter("bool usbDacRecoveryEnd").substringBefore("\n}\n")).contains("armStartOfAudio(ctx, \"recovery\")")
        assertThat(output).contains("armStartOfAudio(ctx, \"stream\")")
        // the priming is the one path of B47-A3, reused: the first write of a start calls it, the pause resume calls it
        assertThat(Regex("primeRingForResume\\(ctx\\)").findAll(output).count()).isAtLeast(2)
    }
}
