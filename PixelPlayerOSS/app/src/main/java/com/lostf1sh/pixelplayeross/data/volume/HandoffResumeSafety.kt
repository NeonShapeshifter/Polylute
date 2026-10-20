package com.lostf1sh.pixelplayeross.data.volume

import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.service.player.HandoffVolumeVerdict

/**
 * Whether the automatic Direct handoff may give playback back, read from the published Safety state.
 *
 * The Android-to-Direct deferral exists to prevent an uncontrolled volume increase: in Direct the
 * level is the DAC's own, not the Android media volume the listener was hearing (AUD-002). So the
 * handoff resumes only once this controller has reconciled the DAC's Feature Unit and the level is
 * bounded — by the armed Safety Max ceiling, or, without one, by [CONSERVATIVE_RESUME_DB]. Anything
 * it cannot bound is held paused for the listener to resume by hand.
 */
object HandoffResumeSafety {
    /**
     * Without a ceiling, the loudest DAC level an automatic resume may land on. Deliberately quiet:
     * the remembered per-DAC level may belong to other headphones, so it is not trusted either.
     */
    const val CONSERVATIVE_RESUME_DB = -30f

    fun verdict(state: SafetyVolumeState): HandoffVolumeVerdict {
        val backend = state.backend
        return when (backend.kind) {
            // No level the app can read or bound: never resume on the listener's behalf.
            SafetyVolumeBackendKind.FIXED_OUTPUT -> HandoffVolumeVerdict.HOLD
            // Still the Android (or no) backend: the DAC has not been reconciled yet.
            SafetyVolumeBackendKind.ANDROID_MEDIA, SafetyVolumeBackendKind.UNKNOWN -> HandoffVolumeVerdict.WAIT
            SafetyVolumeBackendKind.USB_HARDWARE_VOLUME -> {
                val ceiling = state.calibration?.ceilingRaw?.takeIf { state.safetyEnabled }
                when {
                    // B27-A3: a level the DAC has not confirmed (its last write was refused, or could not be read back) bounds nothing,
                    // whatever number is published: wait for a reading, never resume on one that was not taken.
                    !backend.levelVerified -> HandoffVolumeVerdict.WAIT
                    // Above an armed ceiling the reconcile clamps and republishes; wait for that.
                    ceiling != null && backend.rawLevel > ceiling -> HandoffVolumeVerdict.WAIT
                    ceiling != null -> HandoffVolumeVerdict.RESUME
                    (state.hardwareDecibels ?: Float.MAX_VALUE) <= CONSERVATIVE_RESUME_DB -> HandoffVolumeVerdict.RESUME
                    else -> HandoffVolumeVerdict.HOLD
                }
            }
        }
    }
}
