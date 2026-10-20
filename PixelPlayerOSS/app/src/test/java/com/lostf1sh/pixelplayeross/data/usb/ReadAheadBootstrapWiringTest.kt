package com.lostf1sh.pixelplayeross.data.usb

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-139 / AUD-138 wiring in UsbAudioSink; the decisions are host-tested in [ReadAheadBootstrapTest].
 *
 * Every engine seek and every anchor/offset change goes through one helper that records it, with its
 * origin and both positions, in the persistent position trace — so the next "it jumped" is readable
 * from the device after the fact, whatever the logcat buffer kept.
 */
class ReadAheadBootstrapWiringTest {
    private val sink = File(
        "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt"
    ).readText().replace("\r\n", "\n")

    private fun body(signature: String): String = sink.substringAfter(signature).substringBefore("\n    }\n")

    private fun occurrences(text: String, token: String) = text.windowed(token.length).count { it == token }

    @Test
    fun `every engine seek goes through the traced helper`() {
        assertThat(occurrences(sink, "engine.seek(")).isEqualTo(1)
        assertThat(occurrences(sink, ".seek(")).isEqualTo(1)
        val helper = body("private fun seekEngine(")
        assertThat(helper).contains("engine.seek(")
        assertThat(helper).contains("positionAudit.record(")
        assertThat(helper).contains("enginePlacement.onPlaced(")
    }

    @Test
    fun `every anchor and offset change goes through the traced helpers`() {
        assertThat(occurrences(sink, "mappingTracker.beginEpoch(")).isEqualTo(1)
        assertThat(occurrences(sink, "mappingTracker.latch(")).isEqualTo(1)
        assertThat(body("private fun openEpoch(")).contains("auditPosition(")
        assertThat(body("private fun latchEpoch(")).contains("auditPosition(")
        assertThat(body("private fun auditPosition(")).contains("positionAudit.record(")
    }

    @Test
    fun `the bootstrap asks whether a buffer may place the engine`() {
        // The one rule, shared with TenMinuteEngineMoveSimulationTest.
        assertThat(sink).contains("BootstrapSeekPolicy.decide(")
        assertThat(sink).contains("BootstrapSeekPolicy.bufferFromPlayingStream(")
        assertThat(sink).contains("Bootstrap seek refused")
        // A bootstrap seek is only ever the outcome of that decision, with the target it carries.
        assertThat(occurrences(sink, "\"bootstrap\")")).isEqualTo(1)
        val seek = sink.substringAfter("decision is BootstrapSeekPolicy.Decision.Seek").substringBefore("\n                    }")
        assertThat(seek).contains("seekEngine(engine, decision.targetUs, seekGeneration.incrementAndGet(), \"bootstrap\")")
        // The sink passes how far the engine has played: a late buffer must not place it.
        assertThat(sink).contains("enginePositionUs = it.getPositionUs()")
    }

    @Test
    fun `AUD-139 the engine a transition's configure created is placed at the item start`() {
        // createEngineIfNeeded used to return as soon as an engine ran, leaving one that configure
        // created unplaced for the first (read-ahead) buffer to place, minutes later.
        val create = sink.substringAfter("private fun createEngineIfNeeded(").substringBefore("if (nativeEngine?.isRunning")
        assertThat(create).contains("placeRunningEngineAtItemStart(running)")
        assertThat(create).contains("declareStartTarget")
        val place = body("private fun placeRunningEngineAtItemStart(")
        assertThat(place).contains("enginePlacement.itemStartFor(")
        assertThat(place).contains("enginePlacement.onPlaced(")
        assertThat(place).contains("issueExplicitSeek(engine, start.targetUs, \"item-start\")")
    }

    @Test
    fun `AUD-139 no path reads the host's position mirror as an anchor or an audible position`() {
        assertThat(sink).doesNotContain("mediaPositionUs()")
        assertThat(sink).doesNotContain("flush-host-position")
        assertThat(sink).doesNotContain("AnchorOrigin.PLAYER_MIRROR")
        assertThat(sink).doesNotContain("PLAYER_CURRENT_POSITION")
        // The API is gone, so a new caller cannot appear without putting it back.
        val signal = File(
            "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/DirectSignal.kt"
        ).readText()
        assertThat(signal).doesNotContain("fun mediaPositionUs")
        val controller = File(
            "src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DirectSignalController.kt"
        ).readText()
        assertThat(controller).doesNotContain("mediaPositionUs")
        assertThat(controller).doesNotContain("mediaPositionProvider")
        val engine = File(
            "src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt"
        ).readText()
        assertThat(engine).doesNotContain("mediaPositionProvider")
    }

    @Test
    fun `every engine seek names one of the origins that move a playing engine`() {
        // user (and the session: MappingPlayer forwards every controller seek), an item start, a
        // deferred one waiting for its engine, one carried to a replacement, the sink's own realign —
        // and the bootstrap, which the decision confines to an engine nobody placed and nobody heard.
        val origins = Regex("""seekEngine\(.*"([a-z-]+)"\)""").findAll(sink).map { it.groupValues[1] }.toSet()
        val issued = Regex("""issueExplicitSeek\(.*"([a-z-]+)"\)""").findAll(sink).map { it.groupValues[1] }.toSet()
        assertThat(origins + issued).containsAtLeast("bootstrap", "realign", "user", "item-start", "deferred")
        assertThat(origins + issued).containsNoneOf("flush", "host", "mirror", "buffer", "watchdog")
    }

    @Test
    fun `pause, play and the watchdog never seek the engine`() {
        assertThat(body("override fun play() {")).doesNotContain("seekEngine(")
        assertThat(body("override fun pause() {")).doesNotContain("seekEngine(")
        assertThat(body("private fun checkEngineCoherence(")).doesNotContain("seekEngine(")
    }

    @Test
    fun `AUD-138 a seek past the end is clamped and a foreign buffer does not latch the playing item`() {
        assertThat(body("fun requestMediaSeek(targetUs: Long) {")).contains("OffsetAuthority.seekTargetWithinItem(")
        assertThat(sink).contains("OffsetAuthority.latchPresentationForForeignBuffer(")
    }

    @Test
    fun `the position trace is persistent`() {
        assertThat(sink).contains("PositionTraceLog(")
        assertThat(sink).contains("position-trace.txt")
    }
}
