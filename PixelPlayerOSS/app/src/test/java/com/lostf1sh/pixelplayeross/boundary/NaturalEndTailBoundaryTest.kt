package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-128: with the natural end resolved by Media3's own transition, the ~80 ms still queued when
 * the engine reached EOF must play: the next engine on the same stream keeps it (gapless, no fade),
 * and a rate change lets it play out before releasing the stream.
 */
class NaturalEndTailBoundaryTest {
    private val engine = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni/native-audio-engine.cpp")
        .readText().replace("\r\n", "\n")

    @Test
    fun `natural EOS marks the queued tail to be played out`() {
        val eos = engine.substringAfter("if (engine->parser->isDecoderAtEndOfStream()) {").substringBefore("exit = EngineExit::END_OF_STREAM;")
        assertThat(eos).contains("engine->usbCtx->playOutBeforeDrain = true;")
    }

    @Test
    fun `the next engine keeps that tail and does not fade a natural transition`() {
        val seek = engine.substringAfter("Handle seek BEFORE pause").substringBefore("// Handle pause")
        // The decision moved to seekCutFor (AUD-140) so it can be host-tested; same rule.
        assertThat(seek).contains("(engine->entryFadeIn || engine->usbCtx->playOutBeforeDrain)")
        assertThat(seek).contains("if (cut != SEEK_CUT_KEEP_TAIL) discardQueuedPcm(engine->usbCtx);")
        // Fade-in only after a cut, never on a gapless natural transition.
        assertThat(seek).contains("if (engine->hasSubmittedAudio || engine->entryFadeIn) {")
    }
}
