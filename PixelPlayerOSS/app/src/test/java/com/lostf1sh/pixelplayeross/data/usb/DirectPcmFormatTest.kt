package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.UsbPcmConversion
import com.decent.usbaudio.media3.MonoUpmix
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-008 / AUD-012 on the Kotlin side.
 *
 * - [UsbPcmConversion] mirrors the native table (`usb-pcm-convert.cpp`, tested by the native gate):
 *   the sink asks it before claiming, so an unsupported combination is refused up front instead of
 *   ending the decode thread mid-track.
 * - A mono source is duplicated exactly into both channels of the stereo alt; it used to be framed
 *   with one channel, so the DAC read half the data per microframe as L/R.
 */
class DirectPcmFormatTest {

    @Test
    fun `every lossless widening is supported, including 16 into 3-byte slots`() {
        for ((src, dst) in listOf(16 to 16, 16 to 24, 16 to 32, 24 to 24, 24 to 32, 32 to 32)) {
            assertThat(UsbPcmConversion.canConvert(src, dst)).isTrue()
        }
    }

    @Test
    fun `narrowing and unknown widths are refused`() {
        for ((src, dst) in listOf(24 to 16, 32 to 24, 32 to 16, 8 to 32, 16 to 20)) {
            assertThat(UsbPcmConversion.canConvert(src, dst)).isFalse()
        }
    }

    @Test
    fun `mono is duplicated sample by sample into both channels`() {
        val mono16 = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertThat(MonoUpmix.duplicate(mono16, bytesPerSample = 2))
            .isEqualTo(byteArrayOf(0x01, 0x02, 0x01, 0x02, 0x03, 0x04, 0x03, 0x04))

        val mono24 = byteArrayOf(0x0A, 0x0B, 0x0C)
        assertThat(MonoUpmix.duplicate(mono24, bytesPerSample = 3))
            .isEqualTo(byteArrayOf(0x0A, 0x0B, 0x0C, 0x0A, 0x0B, 0x0C))
    }

    @Test
    fun `a trailing partial sample is not invented`() {
        assertThat(MonoUpmix.duplicate(byteArrayOf(1, 2, 3), bytesPerSample = 2))
            .isEqualTo(byteArrayOf(1, 2, 1, 2))
    }
}
