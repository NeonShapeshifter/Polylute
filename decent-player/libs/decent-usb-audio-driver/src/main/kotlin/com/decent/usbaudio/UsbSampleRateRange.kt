package com.decent.usbaudio

/** A UAC2 Clock Source Frequency Control sub-range, in hertz. */
data class UsbSampleRateRange(
    val minHz: Int,
    val maxHz: Int,
    val resolutionHz: Int,
) {
    fun contains(rateHz: Int): Boolean {
        if (rateHz !in minHz..maxHz) return false
        return resolutionHz == 0 || (rateHz - minHz) % resolutionHz == 0
    }
}
