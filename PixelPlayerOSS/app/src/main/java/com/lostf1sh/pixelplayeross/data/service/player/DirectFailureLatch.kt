package com.lostf1sh.pixelplayeross.data.service.player

/**
 * Remembers that a Direct claim failed, so the route policy stops re-deciding USB after it (AUD-011).
 *
 * Without it the policy had no fact for "Direct failed": with playback audible through the
 * delegate, every track change re-evaluated to SAFETY_TRANSITION_DEFERRED and re-armed the handoff —
 * pause, two rebuilds, another failed claim and ~2 s of silence on every track.
 *
 * Keyed by the attachment (the device path) and the source rate the claim failed on, because the
 * common persistent failures are per rate (a DAC that does not advertise 32 kHz) or per device. A
 * different rate retries once; a detach or a new permission clears it. An unknown rate on either side
 * blocks: retrying blind is exactly the storm this exists to stop.
 *
 * Not thread-safe: driven from the engine's main-thread route loop.
 */
class DirectFailureLatch {
    private var devicePath: String? = null
    private var sampleRateHz: Int? = null
    private var pcmEncoding: Int? = null
    private var channels: Int? = null
    private var latched = false

    /**
     * Records a failure for the source format that produced it. Encoding and channels are part of
     * the key (R4-2): a format rejection — a 24-bit source on a 16-bit DAC, say — must not block a
     * 16-bit track at the same rate.
     */
    fun onFailure(devicePath: String?, sampleRateHz: Int?, pcmEncoding: Int? = null, channels: Int? = null) {
        this.devicePath = devicePath
        this.sampleRateHz = sampleRateHz
        this.pcmEncoding = pcmEncoding
        this.channels = channels
        latched = true
    }

    fun blocks(devicePath: String?, sampleRateHz: Int?, pcmEncoding: Int? = null, channels: Int? = null): Boolean {
        if (!latched || devicePath != this.devicePath) return false
        // Each field blocks unless both sides know it and it differs: retrying blind is the storm.
        return matches(this.sampleRateHz, sampleRateHz) &&
            matches(this.pcmEncoding, pcmEncoding) &&
            matches(this.channels, channels)
    }

    private fun matches(failed: Int?, current: Int?): Boolean =
        failed == null || current == null || failed == current

    val isLatched: Boolean
        get() = latched

    fun clear() {
        latched = false
        devicePath = null
        sampleRateHz = null
        pcmEncoding = null
        channels = null
    }
}
