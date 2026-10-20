package com.lostf1sh.pixelplayeross.data.usb

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-136 / AUD-134 wiring in UsbAudioSink: the decision classes are host-tested; this pins that the
 * sink actually feeds and obeys them.
 */
class SinkOffsetAuthorityWiringTest {
    private val sink = File(
        "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt"
    ).readText().replace("\r\n", "\n")

    private fun body(signature: String): String = sink.substringAfter(signature).substringBefore("\n    }\n")

    @Test
    fun `Media3's renderer offsets feed the ledger and the next item takes its offset from it`() {
        assertThat(body("override fun setOutputStreamOffsetUs(")).contains("offsetLedger.onStreamOffsetReported(")
        assertThat(sink).contains("offsetLedger.onItemTransition(reason)")
        assertThat(body("private fun createPendingEngine(reason: Int) {")).contains("naturalEnd.onItemTransition(offsetLedger.playingOffsetUs())")
        assertThat(sink).doesNotContain("(previous offset + length)")
    }

    @Test
    fun `a latch is checked against Media3 and a flush keeps the explicit epoch it belongs to`() {
        assertThat(sink).contains("OffsetAuthority.media3Anchor(")
        assertThat(body("override fun flush() {")).contains("OffsetAuthority.keepExplicitEpochAtFlush(")
    }

    @Test
    fun `the watchdog never seeks and tells Media3 when it moves the position back`() {
        val coherence = body("private fun checkEngineCoherence(")
        assertThat(coherence).doesNotContain("seekTo(")
        assertThat(coherence).doesNotContain("requestMediaSeek(")
        assertThat(sink).doesNotContain("RecoverBySeek")
        assertThat(body("override fun setListener(")).contains("sinkListener = listener")
        assertThat(sink).contains("sinkListener?.onPositionDiscontinuity()")
    }

    @Test
    fun `an end of stream seen by handleBuffer takes the natural-end path (AUD-134)`() {
        val finished = sink.substringAfter("// Native FLAC engine handles decode+USB directly — ignore ExoPlayer data.")
            .substringBefore("Log.i(TAG, \"Native engine finished — cleaning up for next track\")")
        assertThat(finished).contains("onEngineStopped(engine)")
        assertThat(finished).contains("if (naturalEnd.pending)")
    }
}
