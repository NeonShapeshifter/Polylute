package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-060: the player mirror read ExoPlayerImpl's private fields by reflection every 250 ms while
 * the sink existed (plus a getMethod(...).invoke per wrapper level), for an ack ledger nothing
 * decides from. The reflection now runs only while a Direct-path capture is recording.
 */
class PlayerMirrorReflectionBoundaryTest {
    private val sink = File("../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun `the mirror tick and the operation probe only reflect while capturing`() {
        val tick = sink.substringAfter("private fun installPlayerMirror(player: Player) {").substringBefore("playerMirrorSeq++")
        assertThat(tick).contains("val ack = if (DirectPathTelemetry.captureEnabled) probeAckState(p) else NO_ACK_PROBE")
        val probe = sink.substringAfter("fun diagnosticPendingAcks(): Int {").substringBefore("\n    }\n")
        assertThat(probe).contains("if (!DirectPathTelemetry.captureEnabled) return -1")
    }
}
