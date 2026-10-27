package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B39-A10: the local diagnostics built while debugging. The development-only group (the performance report, the time-boxed "advanced"
 * recorder with its frame-stall monitor and the metrics object nothing fed) is gone with its screen card, its preference and its strings;
 * the collectors the app needs, or that the noise trap and the detailed-logging toggle use, are still there; and none of them has a way out of
 * the phone other than the listener sharing a file.
 */
class DiagnosticsInventoryBoundaryTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")
    private val res = File("src/main/res")
    private val diagnostics = File(main, "data/diagnostics")

    private fun mainSources() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val removed = listOf(
        "AdvancedPerformanceDiagnostics", "AdvancedPerformanceDiagnosticsController", "DebugPerformanceReport",
        "DebugPerformanceReportCollector", "MainThreadStallMonitor", "PerformanceMetrics",
    )

    @Test
    fun `the development-only recorders are gone and nothing in the app refers to them`() {
        removed.forEach { name -> assertThat(File(diagnostics, "$name.kt").exists()).isFalse() }
        val offenders = mainSources().filter { file ->
            val text = file.readText()
            removed.any { Regex("\\b$it\\b").containsMatchIn(text) } || "advanced_performance_diagnostics" in text
        }
        assertThat(offenders.map { it.name }).isEmpty()
    }

    @Test
    fun `the device capabilities page reads the device and no longer builds or shares a performance report`() {
        val screen = File(main, "presentation/screens/DeviceCapabilitiesScreen.kt").readText()
        assertThat(screen).doesNotContain("ACTION_SEND")
        assertThat(screen).doesNotContain("PerformanceReport")
        val viewModel = File(main, "presentation/viewmodel/DeviceCapabilitiesViewModel.kt").readText()
        assertThat(viewModel).doesNotContain("generatePerformanceReport")
        assertThat(viewModel).doesNotContain("markLagNow")
    }

    @Test
    fun `no string of the removed report or the lag recorder is left in any built language`() {
        listOf("values", "values-es", "values-sv").forEach { dir ->
            File(res, dir).listFiles { f -> f.extension == "xml" }!!.forEach { file ->
                val text = file.readText()
                assertThat(text).doesNotContain("device_capabilities_report_")
                assertThat(text).doesNotContain("device_capabilities_advanced_diagnostics_")
            }
        }
    }

    @Test
    fun `what the app needs and what the noise trap and the logging toggle use is kept`() {
        listOf("HardwareEventLog", "RouteTraceLog", "NoiseMark", "DeviceDiagnosticsController", "MaintenanceOverrideStore", "HardwareDiagnostics")
            .forEach { name -> assertThat(File(diagnostics, "$name.kt").exists()).isTrue() }
        assertThat(File(main, "data/service/player/UsbDirectAudioDiagnostics.kt").exists()).isTrue()
        assertThat(File("../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/DirectPathTelemetry.kt").exists()).isTrue()
    }

    @Test
    fun `no diagnostics collector opens a connection, so nothing leaves the phone by itself`() {
        val network = Regex("HttpURLConnection|OkHttp|okhttp3|Retrofit|openConnection\\(|java\\.net\\.URL\\(|Socket\\(|FirebaseAnalytics|Crashlytics")
        val offenders = diagnostics.walkTopDown().filter { it.isFile && it.extension == "kt" && network.containsMatchIn(it.readText()) }
        assertThat(offenders.map { it.name }.toList()).isEmpty()
    }
}
