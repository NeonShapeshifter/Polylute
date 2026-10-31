package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.DirectPathTelemetry
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class SingleProducerCompatibilityTest {
    @Test
    fun `old producer names remain readable while new captures cannot force another producer`() {
        assertThat(DirectPathTelemetry.Producer.valueOf("NATIVE_FLAC").name).isEqualTo("NATIVE_FLAC")
        assertThat(DirectPathTelemetry.debugForcedNativeEngine).isFalse()
        assertThat(DirectPathTelemetry.snapshot().nativeEngineStarts).isEqualTo(0L)
        assertThat(DirectPathTelemetry.snapshot().nativeEngineStops).isEqualTo(0L)
    }

    @Test
    fun `retired preferences have no production reader or writer`() {
        val roots = listOf(File("src/main/java"), File("src/debug/java"), File("../../decent-player/libs"))
        val retired = listOf("debug_native_engine", "force_native_engine", "debug_pristine_pipeline", "force_exoplayer_pipeline")
        val hits = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.contains("/build/") }.flatMap { file ->
                retired.filter { file.readText().contains(it) }.map { "${file.path} reads or writes $it" }
            }.toList()
        }
        assertThat(hits).isEmpty()
    }
}
