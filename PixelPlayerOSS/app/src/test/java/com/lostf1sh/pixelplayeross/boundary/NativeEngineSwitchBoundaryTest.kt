package com.lostf1sh.pixelplayeross.boundary

import com.decent.usbaudio.media3.DirectPcmStage
import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.DirectSignalSource
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqSettings
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements
import com.lostf1sh.pixelplayeross.data.service.player.DirectSignalController
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B12a: the ExoPlayer pipeline (`enqueueRaw`) is the PRISTINE producer by default, in debug and in release;
 * the native engine is plan B behind the persisted debug switch `SET_FORCE_NATIVE_ENGINE`.
 *
 * What these pin down:
 * - plan B is off by default at every layer, and off means no engine anywhere: the sink has one place that
 *   can create an engine and it asks the switch;
 * - the switch moves only the *producer*. The signal mode stays whatever the signal source says, so a PRISTINE
 *   stream carried by the pipeline has no stage engaged;
 * - it is reachable only from the debug bridge (never from a release build), and persisted there;
 * - the pre-B12a switch (`SET_PRISTINE_EXOPLAYER_PIPELINE`) is retired: it reaches nothing.
 */
class NativeEngineSwitchBoundaryTest {
    private val libs = "../../decent-player/libs"
    private val sink = File("$libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt")
        .readText().replace("\r\n", "\n")
    private val engine = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt")
        .readText().replace("\r\n", "\n")
    private val receiver = File("src/debug/java/com/lostf1sh/pixelplayeross/dap/admin/DapDebugPolicyReceiver.kt")
        .readText().replace("\r\n", "\n")
    private val debugManifest = File("src/debug/AndroidManifest.xml").readText()
    private val mainManifest = File("src/main/AndroidManifest.xml").readText()

    private val flat = GraphicEqSettings(
        enabled = true,
        frequenciesHz = GraphicEqBands.TEN_BAND_HZ,
        gainsDb = FloatArray(GraphicEqBands.TEN_BAND_COUNT),
    )

    private fun controller(
        requirements: AudioProcessingRequirements = AudioProcessingRequirements(),
        planB: (() -> Boolean)? = null,
    ) = if (planB == null) {
        DirectSignalController(requirements = { requirements }, graphicEq = { flat }, masterVolume = { 1f })
    } else {
        DirectSignalController(
            requirements = { requirements },
            graphicEq = { flat },
            masterVolume = { 1f },
            debugForceNativeEngineProvider = planB,
        )
    }

    private fun sinkFunction(signature: String): String {
        val start = sink.indexOf(signature)
        assertThat(start).isAtLeast(0)
        return sink.substring(start, sink.indexOf("\n    }\n", start))
    }

    // ── Off by default, at every layer ──────────────────────────────────

    @Test
    fun `a signal source that says nothing about plan B has no engine`() {
        val source = object : DirectSignalSource {
            override fun mode() = DirectSignalMode.PRISTINE
            override fun stage(): DirectPcmStage? = null
        }
        assertThat(source.debugForceNativeEngine()).isFalse()
    }

    @Test
    fun `the controller is off unless it is given a provider`() {
        assertThat(controller().debugForceNativeEngine()).isFalse()
    }

    @Test
    fun `the controller reads the provider live`() {
        var forced = false
        val live = controller(planB = { forced })
        assertThat(live.debugForceNativeEngine()).isFalse()
        forced = true
        assertThat(live.debugForceNativeEngine()).isTrue()
    }

    // ── Still PRISTINE: the switch moves the producer, never the signal ──

    @Test
    fun `by default the pristine signal has nothing processing it`() {
        val default = controller()
        assertThat(default.mode()).isEqualTo(DirectSignalMode.PRISTINE)
        assertThat(default.stage().activeProcessing()).isEmpty()
    }

    @Test
    fun `plan B does not turn a processed request into a pristine one`() {
        val forced = controller(AudioProcessingRequirements(graphicEq = true), planB = { true })
        assertThat(forced.mode()).isEqualTo(DirectSignalMode.PROCESSED)
    }

    @Test
    fun `the switch is read only where the engine is decided - never by the signal mode`() {
        val allowed = sinkFunction("private fun nativeEngineAllowed(): Boolean {")
        assertThat(allowed).contains("NativeEnginePolicy.allowed(")
        assertThat(allowed).contains("requestedSignalMode()")
        assertThat(allowed).contains("debugForcesNativeEngine()")

        val requested = sink.substring(
            sink.indexOf("private fun requestedSignalMode()"),
            sink.indexOf("private fun nativeEngineAllowed()"),
        )
        assertThat(requested).doesNotContain("debugForce")
        assertThat(sinkFunction("private fun refreshSignalPath(): Boolean {")).doesNotContain("debugForce")
        assertThat(sinkFunction("private fun publishSignalTruth() {")).doesNotContain("debugForce")
        assertThat(sinkFunction("private fun reconcileSignalMode() {")).doesNotContain("debugForce")
    }

    @Test
    fun `the engine has exactly one creation site and it asks the switch`() {
        assertThat(Regex("""NativeAudioEngine\(\)""").findAll(sink).count()).isEqualTo(1)
        val start = sinkFunction("private fun startNativeEngineIfFlac(")
        assertThat(start).contains("val engineUsable = nativeEngineAllowed()")
        assertThat(start).contains("if (path != null && PipelineThread.wantsEngine(engineUsable, path)) {")
        assertThat(start.indexOf("NativeAudioEngine()")).isGreaterThan(start.indexOf("PipelineThread.wantsEngine(engineUsable, path)"))
        // and nothing else in the app or the library builds one
        val creators = File(".").walkTopDown()
            .plus(File(libs).walkTopDown())
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && "/test/" !in it.path }
            .filter { Regex("""[^.\w]NativeAudioEngine\(\)""").containsMatchIn(it.readText().lines().filterNot { l -> l.trim().startsWith("*") }.joinToString("\n")) }
            .map { it.name }
            .toSet()
        assertThat(creators).containsExactly("UsbAudioSink.kt")
    }

    @Test
    fun `the pristine pipeline start says which producer it chose and under which rules`() {
        val start = sinkFunction("private fun startNativeEngineIfFlac(")
        assertThat(start).contains("PRISTINE — ExoPlayer pipeline is the producer for")
        assertThat(start).contains("PROCESSED signal path — using ExoPlayer pipeline for")
        assertThat(start).contains("DirectPathTelemetry.debugForcedNativeEngine = debugForcesNativeEngine()")
        assertThat(sink).contains("nativeEngine=allowed(plan-B)")
    }

    // ── Wiring: the app feeds the sink from the persisted switch ────────

    @Test
    fun `the engine hands the sink the persisted switch through the signal source`() {
        assertThat(engine).contains("NativeEngineDebugSwitch.shared(context)")
        assertThat(engine).contains("debugForceNativeEngineProvider =")
    }

    // ── Reachable only from the debug bridge ────────────────────────────

    @Test
    fun `the debug bridge declares and dispatches plan B's actions`() {
        for (action in listOf("SET_FORCE_NATIVE_ENGINE", "REPORT_FORCE_NATIVE_ENGINE")) {
            assertThat(debugManifest).contains("com.lostf1sh.pixelplayeross.debug.$action")
            assertThat(receiver).contains("ACTION_$action ->")
        }
        assertThat(receiver).contains("NativeEngineDebugSwitch.shared(context).set(")
        assertThat(receiver).contains("SET_FORCE_NATIVE_ENGINE needs --ez enabled true|false; unchanged")
    }

    @Test
    fun `the report says whether plan B is on, what the sink decided and who produced`() {
        val report = receiver.substringAfter("private fun reportForceNativeEngine").substringBefore("private fun logAudioRoute")
        for (field in listOf("FORCE_NATIVE_ENGINE|", "|forced=", "|lastDecisionForced=", "|producer=", "|tag=")) {
            assertThat(report).contains(field)
        }
    }

    @Test
    fun `no plan B action is in the main manifest, so a release build cannot receive it`() {
        assertThat(mainManifest).doesNotContain("FORCE_NATIVE_ENGINE")
        assertThat(mainManifest).doesNotContain("PRISTINE_EXOPLAYER_PIPELINE")
    }

    @Test
    fun `no production code writes the switch - the engine only reads it`() {
        val users = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("NativeEngineDebugSwitch.shared(") }
            .map { it.name }
            .toSet()
        assertThat(users).containsExactly("DualPlayerEngine.kt")
        assertThat(engine).contains("NativeEngineDebugSwitch.shared(context)::isForced")
        assertThat(engine).doesNotContain("NativeEngineDebugSwitch.shared(context).set(")
    }

    @Test
    fun `the switch ignores a release build through BuildConfig, not through a runtime guess`() {
        val switch = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/NativeEngineDebugSwitch.kt").readText()
        assertThat(switch).contains("BuildConfig.DEBUG")
        assertThat(switch).contains("debugBuild && store.read()")
        assertThat(switch).contains("if (!debugBuild) return")
    }

    // ── The retired switch reaches nothing ──────────────────────────────

    @Test
    fun `the retired actions are a logged warning that writes nothing`() {
        val retired = receiver.substringAfter("ACTION_SET_PRISTINE_EXOPLAYER_PIPELINE, ACTION_REPORT_PRISTINE_EXOPLAYER_PIPELINE ->")
            .substringBefore("ACTION_SET_USB_WAIT_MODE")
        assertThat(retired).contains("is retired (B12a)")
        assertThat(retired).doesNotContain(".set(")
        assertThat(retired).doesNotContain("SharedPreferences")
    }

    @Test
    fun `nothing reads or writes the retired preference except the constants that name it`() {
        val users = File("src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/test/" !in it.path }
            .filter { f -> f.readText().let { "force_exoplayer_pipeline" in it || "debug_pristine_pipeline" in it } }
            .map { it.name }
            .toSet()
        assertThat(users).containsExactly("NativeEngineDebugSwitch.kt")
        assertThat(File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .any { "PristinePipelineDebugSwitch" in it.readText() }).isFalse()
    }
}
