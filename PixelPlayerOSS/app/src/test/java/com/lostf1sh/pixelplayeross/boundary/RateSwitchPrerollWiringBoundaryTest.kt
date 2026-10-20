package com.lostf1sh.pixelplayeross.boundary

import com.decent.usbaudio.media3.DirectPathTelemetry
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
 * B12a-4: where the pre-roll after a rate change is decided and queued, and that it can only come from the
 * debug bridge. The behaviour itself (only at a rate change, item complete, position unchanged) is in
 * `RateSwitchPrerollTest`.
 */
class RateSwitchPrerollWiringBoundaryTest {
    private val libs = "../../decent-player/libs"
    private val sink = File("$libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt")
        .readText().replace("\r\n", "\n")
    private val receiver = File("src/debug/java/com/lostf1sh/pixelplayeross/dap/admin/DapDebugPolicyReceiver.kt").readText()
    private val debugManifest = File("src/debug/AndroidManifest.xml").readText()
    private val mainManifest = File("src/main/AndroidManifest.xml").readText()
    private val engine = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt").readText()

    private val flat = GraphicEqSettings(true, GraphicEqBands.TEN_BAND_HZ, FloatArray(GraphicEqBands.TEN_BAND_COUNT))

    private fun sinkFunction(signature: String): String {
        val start = sink.indexOf(signature)
        assertThat(start).isAtLeast(0)
        return sink.substring(start, sink.indexOf("\n    }\n", start))
    }

    @Test
    fun `off by default at every layer`() {
        val source = object : DirectSignalSource {
            override fun mode() = DirectSignalMode.PRISTINE
            override fun stage() = null
        }
        assertThat(source.rateSwitchPrerollMs()).isEqualTo(0)
        val controller = DirectSignalController(requirements = { AudioProcessingRequirements() }, graphicEq = { flat }, masterVolume = { 1f })
        assertThat(controller.rateSwitchPrerollMs()).isEqualTo(0)
        var ms = 500
        val live = DirectSignalController(
            requirements = { AudioProcessingRequirements() }, graphicEq = { flat }, masterVolume = { 1f },
            rateSwitchPrerollMsProvider = { ms },
        )
        assertThat(live.rateSwitchPrerollMs()).isEqualTo(500)
        ms = 0
        assertThat(live.rateSwitchPrerollMs()).isEqualTo(0)
        assertThat(DirectPathTelemetry.snapshot().rateSwitchPrerolls).isEqualTo(0L)
    }

    @Test
    fun `the rebuild decides from the previous stream's rate, after the cache check and before the stream is replaced`() {
        val configure = sinkFunction("private fun configureUsbBitPerfect(")
        // the same-rate cached stream returns before the pre-roll is ever considered
        assertThat(configure.indexOf("pendingPreroll(previousRateHz = currentSampleRate, newRateHz = sampleRate)"))
            .isGreaterThan(configure.indexOf("USB stream cached for rate=\$sampleRate ch=\$channelCount — reusing"))
        // and before currentSampleRate is overwritten with the new rate
        assertThat(configure.indexOf("pendingPreroll(previousRateHz = currentSampleRate"))
            .isLessThan(configure.indexOf("currentSampleRate = sampleRate\n        currentChannelCount = channelCount\n        muteDelegateIfNeeded()"))
        // and what the host asked for is only read there, through the signal source
        assertThat(sinkFunction("private fun pendingPreroll(")).contains("config.signalSource?.rateSwitchPrerollMs() ?: 0")
        // whatever is not taken by the new thread is dropped when the configure ends
        assertThat(configure).contains("pendingPrerollMs = 0   // taken by the new pipeline thread above, or not owed to anyone (B12a-4)")
    }

    @Test
    fun `the silence is queued on a thread that was just created for the rebuilt stream, never on a kept one`() {
        val start = sinkFunction("private fun startNativeEngineIfFlac(")
        assertThat(start.indexOf("queuePrerollIfOwed(thread)")).isGreaterThan(start.indexOf("if (thread === runningThread) {"))
        assertThat(start.indexOf("queuePrerollIfOwed(thread)")).isGreaterThan(start.indexOf("return\n        }\n        DirectPathTelemetry.streamingThreadStarts"))
        // plan B: an engine that takes over drops it
        assertThat(start).contains("pendingPrerollMs = 0   // plan B: the engine does not get the pre-roll (B12a-4)")
    }

    @Test
    fun `the log line says how much silence goes before which item`() {
        val queue = sinkFunction("private fun queuePrerollIfOwed(")
        assertThat(queue).contains("Rate-switch preroll: \$ms ms of silence before \$item")
        assertThat(queue).contains("RateSwitchPreroll.enqueue(")
        assertThat(queue).contains("DirectPathTelemetry.rateSwitchPrerolls.incrementAndGet()")
    }

    @Test
    fun `the engine hands the sink the persisted setting through the signal source`() {
        assertThat(engine).contains("rateSwitchPrerollMsProvider = RateSwitchPrerollSetting.shared(context)::ms")
    }

    @Test
    fun `the debug bridge declares it, requires ms, accepts only the four values, and release builds have no such action`() {
        assertThat(debugManifest).contains("com.lostf1sh.pixelplayeross.debug.SET_RATE_SWITCH_PREROLL")
        assertThat(mainManifest).doesNotContain("RATE_SWITCH_PREROLL")
        assertThat(receiver).contains("ACTION_SET_RATE_SWITCH_PREROLL ->")
        assertThat(receiver).contains("SET_RATE_SWITCH_PREROLL needs --ei ms 0|250|500|1000; unchanged")
        assertThat(receiver).contains("RateSwitchPrerollSetting.shared(context).set(intent.getIntExtra(EXTRA_MS, -1))")
        assertThat(receiver).contains("RATE_SWITCH_PREROLL|ms=")
        // no production code writes it
        val writers = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("RateSwitchPrerollSetting.shared(") }.map { it.name }.toSet()
        assertThat(writers).containsExactly("DualPlayerEngine.kt")
        assertThat(engine).doesNotContain("RateSwitchPrerollSetting.shared(context).set(")
    }
}
