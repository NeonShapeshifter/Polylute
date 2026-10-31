package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** The writer keeps queued URBs at a natural end; a rate change drains them before release. */
class NaturalEndTailBoundaryTest {
    private val output = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni/usb-audio-output.cpp")
        .readText().replace("\r\n", "\n")

    @Test
    fun `pipeline end of stream preserves the tail for a natural join`() {
        val drain = output.substringAfter("Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(")
            .substringBefore("\n}")
        assertThat(drain).contains("playOutBeforeDrain")
    }
}
