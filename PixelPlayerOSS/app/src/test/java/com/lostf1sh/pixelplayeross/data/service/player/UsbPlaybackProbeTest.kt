package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-003 (R2-3 refuted on the device): after the app handed the DAC back, Android kept listing
 * `usb_headset` — the registration from the original attach is never withdrawn during Direct — while
 * `snd-usb-audio` had created the card *without* a playback stream, and PAL failed on
 * `/proc/asound/card1/stream0` every 50 ms: ~85 s of silent playback, and the guard never acted.
 *
 * The guard now reads the signal the HAL itself needs: a USB-Audio card whose `stream0` has a
 * Playback section. Unreadable `/proc/asound` is UNKNOWN and falls back to the old signal.
 */
class UsbPlaybackProbeTest {

    private val cards = """
         0 [lahainamtpsndcar]: lahaina-mtp-snd - lahaina-mtp-snd-card
                              lahaina-mtp-snd-card
         1 [Melody         ]: USB-Audio - SNOWSKY Melody
                              SNOWSKY Melody at usb-xhci-hcd.1.auto-1, high speed
    """.trimIndent()

    private val stream0 = """
        SNOWSKY Melody at usb-xhci-hcd.1.auto-1, high speed : USB Audio

        Playback:
          Status: Stop
          Interface 2
            Altset 1
            Format: S32_LE
    """.trimIndent()

    @Test
    fun `a USB card with a playback stream is a working output`() {
        assertThat(UsbPlaybackProbe.evaluate(cards) { if (it == 1) stream0 else null })
            .isEqualTo(UsbPlaybackProbe.Result.AVAILABLE)
    }

    @Test
    fun `the device's failure state - card without stream0 - is not an output`() {
        assertThat(UsbPlaybackProbe.evaluate(cards) { null })
            .isEqualTo(UsbPlaybackProbe.Result.UNAVAILABLE)
    }

    @Test
    fun `a stream with capture only is not a playback output`() {
        val captureOnly = "SNOWSKY Melody : USB Audio\n\nCapture:\n  Status: Stop\n"
        assertThat(UsbPlaybackProbe.evaluate(cards) { captureOnly })
            .isEqualTo(UsbPlaybackProbe.Result.UNAVAILABLE)
    }

    @Test
    fun `no USB card at all, while the app holds the DAC, is not an output`() {
        val onlyInternal = cards.lines().take(2).joinToString("\n")
        assertThat(UsbPlaybackProbe.evaluate(onlyInternal) { null })
            .isEqualTo(UsbPlaybackProbe.Result.UNAVAILABLE)
    }

    @Test
    fun `unreadable proc asound is unknown, not a verdict`() {
        assertThat(UsbPlaybackProbe.evaluate(null) { null }).isEqualTo(UsbPlaybackProbe.Result.UNKNOWN)
    }
}
