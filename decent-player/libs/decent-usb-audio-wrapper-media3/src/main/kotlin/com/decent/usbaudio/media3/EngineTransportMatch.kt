package com.decent.usbaudio.media3

/**
 * Whether a native engine may feed the open USB stream (AUD-141).
 *
 * The engine writes its source frames as-is, and the packetizer frames them with the transport's
 * channel count. A mono FLAC on the stereo transport played at 2× (an octave up), a 5.1 one at 3×;
 * the guard used to compare only the rate, and an automatic advance creates the engine before Media3
 * configures the new item's format.
 */
object EngineTransportMatch {
    /** Null when the engine matches the transport, else the reason, as logged. */
    fun mismatch(engineRate: Int, engineChannels: Int, transportRate: Int, transportChannels: Int): String? = when {
        engineRate != transportRate -> "Rate mismatch: FLAC=$engineRate USB=$transportRate"
        engineChannels != transportChannels -> "Channel mismatch: FLAC=$engineChannels USB=$transportChannels"
        else -> null
    }
}
