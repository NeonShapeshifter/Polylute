package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * What a "may duck" focus request (`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`: a navigation prompt, a notification
 * sound) asks of the app on USB Direct (B14-3, audit A-05). Pure; the clock and the DAC are the controller's.
 *
 * ## Why Direct needs this at all
 *
 * On the Android route the system ducks the app's AudioTracks itself, and the app is not even told. On Direct
 * the music is not in an AudioTrack: its bytes go over usbfs, where the system cannot reach them. The only
 * track the system can duck is the silent presence track, so a prompt would be laid over music at full level.
 * The app has to attenuate what it sends, and has to be told (the focus request says so, see
 * `DualPlayerEngine.requestAudioFocus`).
 *
 * ## What it does, by what the signal is allowed to be
 *
 * - **PROCESSED (Resonance):** the stage already owns the samples, so a digital gain, ramped. It composes
 *   after the EQ and the listener's volume and never raises anything above them.
 * - **PRISTINE (Purity):** not one sample may change, so the digital route is closed. The attenuation goes to
 *   the DAC's own volume (its UAC2 Feature Unit), ramped in steps, and comes back to the exact level it left,
 *   never above it and never above the Safety Max ceiling. A DAC with no verified hardware volume, or with too
 *   little room to go down to be of use, cannot be ducked: the app pauses and resumes on the gain.
 * - **Android route:** unchanged. The system ducks, or, when it hands the decision over, the app keeps its
 *   current behaviour.
 */
internal object DirectDuckPolicy {

    /** How far a duck takes the music down. */
    const val DUCK_DB = 12f

    /** The digital duck's linear gain: [DUCK_DB] down. */
    val DUCK_GAIN: Float = 10.0.pow(-DUCK_DB / 20.0).toFloat()

    /**
     * A hardware duck shallower than this is not a duck a spoken prompt can be heard over: the DAC is already
     * near the bottom of its range and the app pauses instead.
     */
    const val MIN_USEFUL_DUCK_DB = 6f

    enum class Action {
        /** Not Direct: the system's own ducking stands. */
        SYSTEM,
        DIGITAL_RAMP,
        HARDWARE_VOLUME,
        PAUSE,
    }

    fun actionFor(
        directActive: Boolean,
        signalMode: DirectSignalMode,
        hardware: PristineModePolicy.HardwareVolume?,
    ): Action = when {
        !directActive -> Action.SYSTEM
        signalMode == DirectSignalMode.PROCESSED -> Action.DIGITAL_RAMP
        usableHardwareDuckStep(hardware) != null -> Action.HARDWARE_VOLUME
        else -> Action.PAUSE
    }

    /**
     * The DAC step a hardware duck goes down to, or null when it cannot: no verified volume control, or less than
     * [MIN_USEFUL_DUCK_DB] of room. At most [DUCK_DB] down, never below the bottom of the DAC's range.
     */
    fun usableHardwareDuckStep(hardware: PristineModePolicy.HardwareVolume?): Int? {
        if (hardware == null || !hardware.verified || hardware.stepDb <= 0f) return null
        val steps = ceil(DUCK_DB / hardware.stepDb - 1e-4f).toInt().coerceAtLeast(1)
        val target = max(hardware.minStep, hardware.currentStep - steps)
        val loweredDb = (hardware.currentStep - target) * hardware.stepDb
        return target.takeIf { loweredDb >= MIN_USEFUL_DUCK_DB }
    }

    /**
     * The step a hardware duck comes back to, or null when it must not move the DAC.
     *
     * [held] is what the DAC reports now and [written] the last step the duck itself wrote: when they differ the
     * listener touched the volume during the duck (a key, the readout), and that choice stands, quieter or not.
     * Otherwise the way back is the level it left, capped by the Safety Max ceiling when one is in force, and by
     * the top of the range. Never above [preDuck].
     */
    fun restoreStep(preDuck: Int, held: Int, written: Int, ceilingStep: Int?, maxStep: Int): Int? {
        if (held != written) return null
        val target = min(min(preDuck, maxStep), ceilingStep ?: Int.MAX_VALUE)
        return target.takeIf { it > held }
    }
}
