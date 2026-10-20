package com.lostf1sh.pixelplayeross.data.volume

import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind

/**
 * Decides when the listener is told, once, that a USB DAC in Direct has no calibrated Safety Max
 * ceiling (AUD-126).
 *
 * Without a calibration for the DAC's own hardware volume nothing bounds a rise of its Feature Unit
 * except the DAC's range, whose top is 0 dB. That was the state of the owner's DAC when its level
 * went from −6 dB to 0 dB unexplained, and nothing in the app or the log said so: this is the notice.
 * A DAC is told once per process — `reconcile` runs on every route, profile and settings event.
 */
internal class SafetyCalibrationNotice {
    private val told = HashSet<String>()

    @Synchronized
    fun shouldShow(backend: VolumeBackendSnapshot, calibration: SafetyMaxCalibration?): Boolean {
        if (backend.kind != SafetyVolumeBackendKind.USB_HARDWARE_VOLUME || !backend.controllable) return false
        if (calibration != null) return false
        val context = backend.outputContextId ?: return false
        return told.add(context)
    }
}
