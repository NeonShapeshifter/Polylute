package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B47-A5: the debug bridge of the locked-up DAC's recovery. `SET_DAC_RECOVERY --ez enabled false|true` and `RECOVER_DAC_NOW --ei step 1|2`: declared in the debug manifest ONLY,
 * their extras required, process-only like SET_FEEDBACK_POLICY, and the recovery itself on by default in debug and release.
 */
class DacRecoveryBridgeBoundaryTest {
    private val appRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("Could not find the app module root from ${File(".").absolutePath}")
    private fun text(path: String) = File(appRoot, path).readText().replace("\r\n", "\n")
    private val receiver = text("src/debug/java/com/lostf1sh/pixelplayeross/dap/admin/DapDebugPolicyReceiver.kt")
    private val debugManifest = text("src/debug/AndroidManifest.xml")
    private val mainManifest = text("src/main/AndroidManifest.xml")
    private val jni = File(appRoot, "../../decent-player/libs/decent-usb-audio-driver/src/main/jni").canonicalFile
    private val stream = File(appRoot, "../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioStream.kt")
        .canonicalFile.readText().replace("\r\n", "\n")

    @Test
    fun `both actions are declared in the debug manifest and nowhere else`() {
        for (action in listOf("SET_DAC_RECOVERY", "RECOVER_DAC_NOW")) {
            assertThat(debugManifest).contains("com.lostf1sh.pixelplayeross.debug.$action")
            assertThat(mainManifest).doesNotContain(action)
            assertThat(receiver).contains("\"com.lostf1sh.pixelplayeross.debug.$action\"")
        }
        // the receiver itself is a debug source: neither action exists in a main source file
        val mainSources = File(appRoot, "src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
        for (file in mainSources) {
            val body = file.readText()
            assertThat(body).doesNotContain("SET_DAC_RECOVERY")
            assertThat(body).doesNotContain("RECOVER_DAC_NOW")
        }
    }

    @Test
    fun `the extras are required, nothing is persisted and the reply is one line`() {
        val set = receiver.substringAfter("ACTION_SET_DAC_RECOVERY ->").substringBefore("ACTION_RECOVER_DAC_NOW ->")
        assertThat(set).contains("if (!intent.hasExtra(EXTRA_ENABLED))")
        assertThat(set).contains("SET_DAC_RECOVERY needs --ez enabled true|false; unchanged")   // (logged with Timber: lint's baseline counts `Log` per file)
        assertThat(set).contains("UsbAudioStream.nativeSetDacRecoveryEnabled(")
        val now = receiver.substringAfter("ACTION_RECOVER_DAC_NOW ->").substringBefore("ACTION_SET_RATE_SWITCH_PREROLL ->")
        assertThat(now).contains("step != 1 && step != 2")
        assertThat(now).contains("RECOVER_DAC_NOW needs --ei step 1|2; nothing asked")
        assertThat(now).contains("UsbAudioStream.nativeRequestDacRecovery(step)")
        for (persisted in listOf("SharedPreferences", "DataStore", "putString", "putInt", ".edit")) {
            assertThat(set).doesNotContain(persisted)
            assertThat(now).doesNotContain(persisted)
        }
        val report = receiver.substringAfter("private fun reportDacRecovery").substringBefore("private fun reportRateSwitchPreroll")
        for (field in listOf("DAC_RECOVERY|enabled=", "|locks=", "|step1=", "|step2=", "|gaveUp=", "|lastMs=", "|tag=")) assertThat(report).contains(field)
    }

    @Test
    fun `recovery is on by default and the native exports match the Kotlin externals`() {
        val output = File(jni, "usb-audio-output.cpp").readText()
        assertThat(output).contains("static std::atomic<bool> g_dacRecoveryEnabled{true};")
        for (name in listOf("nativeDacLockState", "nativeDacRecoveryBegin", "nativeDacRecoveryEnd", "nativeNoteDacRecovery", "nativeSetDacRecoveryEnabled",
            "nativeRequestDacRecovery", "nativeGetDacRecoveryStats")) {
            assertThat(output).contains("Java_com_decent_usbaudio_UsbAudioStream_$name(")
            assertThat(stream).contains("external fun $name(")
        }
    }

    @Test
    fun `the writer asks the policy only when it is playing, and a step is the pause machinery plus the ordinary reconfigure`() {
        val thread = File(appRoot, "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbStreamingThread.kt")
            .canonicalFile.readText().replace("\r\n", "\n")
        val gate = thread.substringAfter("private fun dacRecoveryStepToTake()").substringBefore("private fun performDacRecovery")
        for (guard in listOf("dacRecovery == null", "paused", "seekOwed", "fadeInProgress", "!running")) assertThat(gate).contains(guard)
        val step = thread.substringAfter("private fun performDacRecovery").substringBefore("/** The next audio a pause can ramp down")
        for (call in listOf("usbStream.beginPauseFade()", "usbStream.markIdle()", "usbStream.dacRecoveryBegin()", "host.restartInterface(step)",
            "usbStream.dacRecoveryEnd()", "usbStream.noteDacRecovery(", "usbStream.resumeAfterPause()")) assertThat(step).contains(call)
        // the sink's step is its ordinary reconfigure, not a second implementation
        val sink = File(appRoot, "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt")
            .canonicalFile.readText().replace("\r\n", "\n")
        // B48-1: the one sequence lives in the driver (StreamingInterfaceSequence); the sink's start-up and the recovery host both call it
        val sequence = File(appRoot, "../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/StreamingInterfaceSequence.kt")
            .canonicalFile.readText().replace("\r\n", "\n")
        assertThat(sink).contains("streamingSequence.reconfigure(")
        assertThat(sequence.split("reconfigure(").size - 1).isAtLeast(2)   // its definition and the recovery's restart
        assertThat(sequence).contains("device.resetAndReclaimForStreaming()")
    }
}
