package com.lostf1sh.pixelplayeross.presentation.viewmodel

import kotlin.math.roundToInt

/**
 * AUD-120: the Digital slider's rules. Any level other than exactly 100 % takes USB Direct to
 * PROCESSED, and a drag rarely lands on the very end of the track — measured on the device: 99 %,
 * "no quiere volver a Pristine", with nothing on screen saying why.
 */
object DigitalGainSlider {
    /** From here to the end of the track the level is 100 %. */
    const val SNAP_TO_FULL_FROM = 0.97f

    fun snapPercent(fraction: Float): Int =
        if (fraction >= SNAP_TO_FULL_FROM) 100 else (fraction.coerceIn(0f, 1f) * 100f).roundToInt()

    /** The digital level is what keeps Direct off PRISTINE, and nothing (the Pure Direct lock) freezes it. */
    fun pristineBlockedByDigital(usbDirectActive: Boolean, digitalPercent: Int, pristineLocked: Boolean): Boolean =
        usbDirectActive && digitalPercent < 100 && !pristineLocked
}
