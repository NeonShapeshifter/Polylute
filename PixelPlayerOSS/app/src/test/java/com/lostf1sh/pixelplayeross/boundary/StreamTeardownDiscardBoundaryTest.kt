package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-112 (measured on the HONOR): a track change with a rate change took ~2 s, because tearing the
 * stream down reaped the 80 in-flight URBs "naturally" (up to 500 ms each, 1.14 s + 0.67 s measured)
 * before discarding the rest. That audio belongs to the previous track and has no value. The
 * teardown now discards immediately, like the seek barrier (80 URBs in ~4 ms).
 *
 * Source-level: `drainAllUrbs` is usbfs ioctls, not runnable on the host.
 */
class StreamTeardownDiscardBoundaryTest {

    private val jni = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni/usb-audio-output.cpp").readText()

    @Test
    fun `tearing a stream down discards in-flight URBs instead of waiting for them to play`() {
        val drain = jni.substringAfter("Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(").substringBefore("\n}")
        assertThat(drain).contains("drainAllUrbs(ctx, true,")
    }
}
