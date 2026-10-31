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
        assertThat(sink).doesNotContain("(previous offset + length)")
    }

    @Test
    fun `a latch is checked against Media3 and a flush keeps the explicit epoch it belongs to`() {
        assertThat(sink).contains("OffsetAuthority.media3Anchor(")
        assertThat(body("override fun flush() {")).contains("mappingTracker.preserve()")
    }

}
