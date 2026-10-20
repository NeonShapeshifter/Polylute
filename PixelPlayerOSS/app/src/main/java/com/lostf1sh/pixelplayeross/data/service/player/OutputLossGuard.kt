package com.lostf1sh.pixelplayeross.data.service.player

/** What went away under the music (B25-S1). Named so the pause says why it happened. */
enum class OutputLossReason {
    /** The USB DAC was unplugged while the exclusive Direct stream was playing to it. */
    DIRECT_DAC_DETACHED,

    /** The USB DAC was unplugged while Android was playing to it (the Direct claim is not the route). */
    ANDROID_USB_DAC_DETACHED,
}

/**
 * Pauses playback when the output the music is going to disappears, before anything can play to whatever is left.
 *
 * The owner likes that plugging in a DAC while music plays keeps it playing, and wants the other direction to stop:
 * unplugging must pause, never carry on out of the phone's speaker. Two things made it carry on in Direct. The Direct
 * stream's only AudioTrack is the silent delegate, so Android's own "becoming noisy" handling has nothing it can tie to
 * the DAC; and the sink's fall-back on a dead stream handed the very next buffer to that delegate, which the platform
 * routes to the speaker. This is the app's own answer: it watches the one fact it can read (a USB audio device is
 * there or it is not) and decides from it alone, so it does not depend on what the platform decides to report.
 *
 * What it does **not** know: a headphone jack on the DAC's own body (the USB link does not change when it is pulled out).
 *
 * Main thread only, like the route loop that feeds it. Pure: it holds one boolean and acts on nothing.
 */
class OutputLossGuard {
    private var dacPresent = false

    /** Whether the last observation saw a DAC. */
    val isDacPresent: Boolean get() = dacPresent

    /**
     * One observation of the USB bus.
     *
     * @param present a USB audio device is there now.
     * @param playWhenReady the master player has been asked to play (a buffering or a briefly stalled player counts).
     * @param directActive the route in effect was the Direct stream, which only changes what the reason says.
     * @return why playback must be paused now, or null (nothing was playing to a DAC, the DAC arrived, or no change).
     */
    fun onUsbPresence(present: Boolean, playWhenReady: Boolean, directActive: Boolean): OutputLossReason? {
        val wasPresent = dacPresent
        dacPresent = present
        if (!wasPresent || present || !playWhenReady) return null
        return if (directActive) OutputLossReason.DIRECT_DAC_DETACHED else OutputLossReason.ANDROID_USB_DAC_DETACHED
    }
}

/** Told by the engine, on the main thread and just before it pauses, that the output went away (B25-S1). */
fun interface OutputLossListener {
    fun onPlaybackPausedForOutputLoss(reason: OutputLossReason)
}
