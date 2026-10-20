package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-115 (measured on the HONOR): two NEXTs during a slow drain crossed formats. The player
 * listener, on the main thread, created RAMONA's (96 kHz) engine while the playback thread was still
 * finishing IRIS OUT's configure and had just opened a 48 kHz stream — "Rate mismatch", a producer for
 * the wrong track at the wrong rate for ~0.3 s. The engine lifecycle is mutated from the playback
 * thread only (AUD-015): the listener marks the item pending and posts the creation there, behind any
 * configure in progress. Source-level: the sink needs Media3 and usbfs.
 */
class EngineCreationThreadBoundaryTest {

    private val sink = File(
        "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt"
    ).readText()

    private fun body(signature: String): String {
        val start = sink.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val open = sink.indexOf('{', start)
        var depth = 0
        for (i in open until sink.length) {
            when (sink[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return sink.substring(open, i + 1)
            }
        }
        error("unbalanced $signature")
    }

    @Test
    fun `the main-thread listener does not create the engine itself`() {
        val listener = body("override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int)")
        assertThat(listener).doesNotContain("createEngineIfNeeded(declareStartTarget = true)")
        assertThat(listener).contains("enginePendingForItem = true")
        assertThat(listener).contains("playbackHandler")
    }

    @Test
    fun `the pending engine is created on the playback thread`() {
        val consume = body("private fun createPendingEngine(")
        assertThat(consume).contains("createEngineIfNeeded(declareStartTarget = true)")
        assertThat(consume).contains("enginePendingForItem = false")
    }
}
