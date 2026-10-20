package com.lostf1sh.pixelplayeross.data.volume

import android.content.Context
import com.decent.usbaudio.UsbVolumeRange

/**
 * Maps a DAC's UAC2 volume range (1/256 dB) onto the integer steps the rest of the volume policy
 * speaks, and decides what a freshly opened DAC may play at.
 *
 * A raw step of one native unit would be 0.004 dB — a key press nobody could hear — so the backend
 * is exposed in steps of about [STEP_DB] dB, rounded up to a whole multiple of the DAC's resolution
 * so every step is a level the DAC can actually hold.
 */
object UsbHardwareVolumePolicy {
    const val STEP_DB = 2

    fun stepUnits(range: UsbVolumeRange): Int {
        val base = STEP_DB * UsbVolumeRange.UNITS_PER_DB
        val res = range.resolutionUnits.coerceAtLeast(1)
        return ((base + res - 1) / res) * res
    }

    fun maxStep(range: UsbVolumeRange): Int = (range.maxUnits - range.minUnits) / stepUnits(range)

    /** Floors, so a level between two steps is shown as the quieter one — never overstated. */
    fun stepFor(units: Int, range: UsbVolumeRange): Int =
        ((range.clamp(units) - range.minUnits) / stepUnits(range)).coerceIn(0, maxStep(range))

    /**
     * The step a level the DAC reported is taken for when it must not be understated (B27-A3): rounded **up**, so a level between two
     * steps counts as the louder one. [stepFor] floors, which is right for a readout and wrong for deciding that a write took.
     */
    // A final partial step may exceed the selectable maxStep by one. Capping that observation
    // would misreport a DAC above the highest selectable ceiling as exactly at that ceiling.
    fun stepCeilFor(units: Int, range: UsbVolumeRange): Int =
        (range.clamp(units) - range.minUnits + stepUnits(range) - 1) / stepUnits(range)

    fun unitsFor(step: Int, range: UsbVolumeRange): Int =
        range.clamp(range.minUnits + step.coerceIn(0, maxStep(range)) * stepUnits(range))

    fun decibels(units: Int): Float = units / UsbVolumeRange.UNITS_PER_DB.toFloat()

    /**
     * The level a DAC may keep when it is opened: the quietest of what it is at, what the listener
     * last chose on it, and the Safety Max ceiling. Automatic paths may lower, never raise — a
     * remembered level belongs to whatever was plugged in last time, and the app cannot know it is
     * the same pair of headphones now.
     *
     * @return the target in native units, or null when nothing asks for a change.
     */
    fun openTarget(currentUnits: Int, savedUnits: Int?, ceilingUnits: Int?): Int? {
        val target = listOfNotNull(savedUnits, ceilingUnits).minOrNull() ?: return null
        return target.takeIf { it < currentUnits }
    }
}

/** The last level the listener chose on each DAC, in native units. Read only to lower, never to raise. */
class UsbHardwareVolumeStore(context: Context) {
    private val prefs = context.getSharedPreferences("usb_hardware_volume", Context.MODE_PRIVATE)

    fun saved(contextId: String): Int? =
        if (prefs.contains(contextId)) prefs.getInt(contextId, 0) else null

    fun save(contextId: String, units: Int) {
        prefs.edit().putInt(contextId, units).apply()
    }
}
