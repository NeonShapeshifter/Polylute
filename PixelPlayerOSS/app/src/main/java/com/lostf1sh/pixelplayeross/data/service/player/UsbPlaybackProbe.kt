package com.lostf1sh.pixelplayeross.data.service.player

/**
 * Whether the platform can actually play to the USB DAC right now (AUD-003).
 *
 * Android's device list is not that signal: the `usb_headset` registered at the original attach is
 * never withdrawn while Direct holds the DAC, so after the app hands it back Android still lists it
 * even when `snd-usb-audio` came back without a playback stream — measured on the HONOR: card
 * `controlC1`, no `pcmC1D*p`, PAL failing on `/proc/asound/card1/stream0`, 85 s of silent playback.
 *
 * So this reads what the HAL itself needs: a USB-Audio card in `/proc/asound/cards` whose `stream0`
 * has a Playback section. Pure over the file contents; the caller reads them.
 */
object UsbPlaybackProbe {
    enum class Result { AVAILABLE, UNAVAILABLE, UNKNOWN }

    private val usbCard = Regex("""^\s*(\d+)\s+\[[^\]]*\]:\s*USB-Audio\b""")

    /**
     * [cards] is `/proc/asound/cards`, or null when unreadable (UNKNOWN: the caller falls back).
     * [streamOf] returns `/proc/asound/card<N>/stream0`, or null when it does not exist.
     */
    fun evaluate(cards: String?, streamOf: (Int) -> String?): Result {
        cards ?: return Result.UNKNOWN
        val usbCards = cards.lineSequence().mapNotNull { usbCard.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return if (usbCards.any { streamOf(it)?.contains("Playback:") == true }) {
            Result.AVAILABLE
        } else {
            Result.UNAVAILABLE
        }
    }
}
