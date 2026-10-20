package com.lostf1sh.pixelplayeross.data.service.player

import kotlinx.coroutines.flow.StateFlow

/**
 * An output level the app controls itself rather than through `STREAM_MUSIC`: the DAC's own Feature
 * Unit while USB Direct is active. Exposed to the media session as remote playback, so the platform
 * routes the hardware volume keys here even with the screen off, when no activity is there to
 * intercept them (AUD-027).
 */
interface RemoteDeviceVolume {
    /** Non-null exactly while such a level is in charge. */
    val state: StateFlow<RemoteVolumeState?>

    /** Requests [level]; the owner applies its own limits (the Safety Max ceiling). */
    fun setLevel(level: Int)
}

data class RemoteVolumeState(val min: Int, val max: Int, val level: Int)
