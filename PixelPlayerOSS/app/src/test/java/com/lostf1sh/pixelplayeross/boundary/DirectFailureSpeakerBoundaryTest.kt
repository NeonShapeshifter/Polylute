package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-004: any failed Direct claim — an unadvertised rate (22.05/32 kHz), a rejected SET_CUR, a
 * failed open — used to unmute the delegate and route it to the built-in speaker, so a listener
 * wearing headphones on the DAC heard their music come out of the phone.
 *
 * The failure now leaves the delegate muted and reports the error; the engine latches it (AUD-011)
 * and rebuilds on the Android path. Nothing chooses the speaker on the listener's behalf.
 *
 * Source-level because the sink cannot be constructed on the JVM (Media3 `DefaultAudioSink`,
 * `UsbManager`). The success path's muted, speaker-routed delegate is untouched and not asserted.
 */
class DirectFailureSpeakerBoundaryTest {

    private val sink = File(
        "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/" +
            "com/decent/usbaudio/media3/UsbAudioSink.kt"
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
    fun `a failed claim neither unmutes the delegate nor routes it to the speaker`() {
        val fail = body("private fun failUsbInitialization(")
        assertThat(fail).doesNotContain("forceMediaToSpeaker")
        assertThat(fail).doesNotContain("forceSpeakerForDirectFailure = true")
        assertThat(fail).doesNotContain("unmuteDelegateIfNeeded()")
    }

    @Test
    fun `configure never forces the speaker for a failure`() {
        val configure = body("override fun configure(")
        assertThat(configure).doesNotContain("forceSpeakerForDirectFailure")
        assertThat(configure).contains("directFailureHold")
    }
}
