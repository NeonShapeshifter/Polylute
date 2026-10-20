package com.lostf1sh.pixelplayeross.data.usb

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** AUD-137 wiring in UsbAudioSink; the decisions are host-tested in [EarlyAutoTransitionTest]. */
class EarlyAutoWiringTest {
    private val sink = File(
        "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt"
    ).readText().replace("\r\n", "\n")

    private fun body(signature: String): String = sink.substringAfter(signature).substringBefore("\n    }\n")

    @Test
    fun `an AUTO before the engine's end is recorded and resolved at the end of stream`() {
        assertThat(sink).contains("transitionSeq++")
        val create = body("private fun createPendingEngine(reason: Int) {")
        assertThat(create).contains("engineTransitionSeq != transitionSeq")
        assertThat(create).contains("naturalEnd.onEarlyTransition(")
        val stopped = body("private fun onEngineStopped(engine: NativeAudioEngine) {")
        assertThat(stopped).contains("naturalEnd.consumeEarlyTransition(")
    }

    @Test
    fun `the watchdog never advances once the item changed`() {
        val stopped = body("private fun onEngineStopped(engine: NativeAudioEngine) {")
        assertThat(stopped).contains("itemChanged = transitionSeq != endedSeq")
    }

    @Test
    fun `a deferred config is applied only to the transition it was deferred for`() {
        assertThat(sink).contains("deferredForTransitionSeq = transitionSeq + 1")
        assertThat(body("private fun cleanupFinishedEngine(): Boolean {"))
            .contains("NaturalEndCoordinator.deferredConfigApplies(deferredForTransitionSeq, transitionSeq)")
    }
}
