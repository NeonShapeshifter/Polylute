package com.decent.usbaudio.media3

/**
 * Configuration for [UsbAudioSink].
 *
 * @param directEnabled When true, the sink may carry PCM to the USB DAC itself, bypassing the
 *        Android audio stack entirely. This is a statement about **transport**, not about signal
 *        purity: a Direct stream may be pristine or processed, and which one it is is answered at
 *        runtime by [signalSource], not here.
 * @param forceRouteToSpeaker When true, the delegate AudioTrack is routed to the built-in speaker
 *        to prevent AudioFlinger from opening the USB device.
 * @param signalSource The live authority for pristine-vs-processed. Absent means the host has no
 *        processing to offer, and the sink runs pristine whenever it can.
 * @param directPresence When true (the default), a silent speaker-pinned `AudioTrack` plays while the
 *        Direct stream is running, so Android sees music playing (F-10, [DirectPlaybackPresence]).
 */
data class UsbAudioSinkConfig(
    val directEnabled: Boolean = true,
    val forceRouteToSpeaker: Boolean = true,
    val signalSource: DirectSignalSource? = null,
    val directPresence: Boolean = true,
)
