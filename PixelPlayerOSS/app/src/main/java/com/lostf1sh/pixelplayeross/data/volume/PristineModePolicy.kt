package com.lostf1sh.pixelplayeross.data.volume

import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements
import kotlin.math.ceil
import kotlin.math.log10

/**
 * F-02 "Pure Direct" (owner request): one switch that freezes — without applying or losing — every
 * contributor that would take the Direct signal off PRISTINE: EQ (also while its editor is open),
 * digital volume, ReplayGain, the profile's boost compensation (AUD-025), the Android session
 * effects (bass boost, virtualizer, loudness), and (B15-F2) crossfade and playback speed.
 *
 * The B1 invariant still holds: nothing automatic raises the effective level. The attenuation those
 * contributors apply is moved onto the DAC's own volume before the mode switches, and handed back
 * when it is left, never above the Safety Max ceiling. Where it cannot be moved — a DAC without a
 * Feature Unit, one whose level cannot be read back, or not in Direct yet — the rise is shown and
 * must be confirmed.
 *
 * Crossfade and speed used to be left out ("explicit route choices that take the stream to the Android
 * path, visibly"). On 2026-10-12 the owner turned crossfade on under the lock, playback left Direct for
 * Android (the readout went from dB to %) and the screen still said Pure Direct: a lock that its own
 * settings can undo is not a lock. They are frozen like the EQ: the settings are kept, nothing applies
 * (songs join exactly, at 1.0×) and they apply again when the lock is left. [effectiveCrossfade] and
 * [effectiveSpeed] are what the transition scheduler and the player read.
 *
 * **Purity only means something with a DAC (owner, 2026-10-12; B16-G4).** The lock is a preference. It is *in force* only
 * while a USB DAC is connected ([lockInForce]): without one there is no Direct stream to keep untouched, so everything the
 * lock would freeze applies on the Android route as if it were off (EQ, ReplayGain, digital volume, effects, crossfade, speed),
 * the preference is kept, and it takes hold again by itself when a DAC is plugged in. Nothing is switched off, and the
 * level is never raised by the change in either direction: unplugging touches no DAC level (a quieter stream, never a louder
 * one: the frozen attenuations come back, and `player.volume` cannot exceed 1), and plugging in makes the DAC's own level,
 * bounded by Safety Max and the handoff verdict, the one that counts (the rise the lock could not move onto the DAC was
 * shown and confirmed when the lock was turned on outside Direct, [RiseReason.OUTSIDE_DIRECT]).
 */
object PristineModePolicy {
    /** A frozen gain within this of unity is an identity: nothing to compensate. */
    const val IDENTITY_DB = 0.05f

    data class HardwareVolume(
        val currentStep: Int,
        val minStep: Int,
        val maxStep: Int,
        val stepDb: Float,
        /** The DAC read back the level the app believes it holds. */
        val verified: Boolean,
        /** [DacVolumeArbiter]'s counter at the moment of the read: what a temporary writer (a duck) must present. */
        val ticket: Long = 0L,
    )

    enum class RiseReason { OUTSIDE_DIRECT, NO_HARDWARE_VOLUME, DAC_RANGE }

    sealed interface Activation {
        data object NothingToCompensate : Activation
        data class LowerDac(val targetStep: Int, val loweredSteps: Int, val loweredDb: Float) : Activation
        data class NeedsConfirmation(val riseDb: Float, val reason: RiseReason) : Activation
    }

    enum class Status { OFF, ACTIVE, ONLY_IN_DIRECT, DIRECT_NOT_PRISTINE, DAC_EQ_ON, DAC_EQ_UNKNOWN }

    /**
     * The level the frozen contributors take off now, in dB (≤ 0). A boost (product above unity) is
     * not a level the listener gets back when it is frozen, so it counts as 0 dB.
     */
    fun frozenAttenuationDb(userDigitalGain: Float, replayGainFactor: Float?, profileCompensation: Float): Float {
        val product = userDigitalGain * (replayGainFactor ?: 1f) * profileCompensation
        if (product >= 1f) return 0f
        return (20.0 * log10(product.coerceAtLeast(1e-5f).toDouble())).toFloat()
    }

    fun activation(attenuationDb: Float, directActive: Boolean, hardware: HardwareVolume?): Activation {
        val rise = -attenuationDb
        if (rise < IDENTITY_DB) return Activation.NothingToCompensate
        if (!directActive) return Activation.NeedsConfirmation(rise, RiseReason.OUTSIDE_DIRECT)
        if (hardware == null || !hardware.verified || hardware.stepDb <= 0f) {
            return Activation.NeedsConfirmation(rise, RiseReason.NO_HARDWARE_VOLUME)
        }
        // Whole steps, rounded towards quieter: the effective level may drop a fraction of a step,
        // never rise.
        val steps = ceil(rise / hardware.stepDb - 1e-4f).toInt()
        val target = hardware.currentStep - steps
        if (target < hardware.minStep) {
            val reachable = (hardware.currentStep - hardware.minStep) * hardware.stepDb
            return Activation.NeedsConfirmation(rise - reachable, RiseReason.DAC_RANGE)
        }
        return Activation.LowerDac(target, steps, steps * hardware.stepDb)
    }

    /** The DAC step to return to when leaving: the same steps back, capped by the ceiling and range. */
    fun restoreStep(currentStep: Int, loweredSteps: Int, maxStep: Int, ceilingStep: Int?): Int =
        minOf(currentStep + loweredSteps, maxStep, ceilingStep ?: maxStep)

    /**
     * Whether the lock is in force: the preference is on and a USB DAC is connected. [usbDacPresent] is what the router
     * last found (null before it has looked: the lock is then taken as in force, so a start-up with the DAC plugged in
     * never lets a frozen contributor through for a moment).
     */
    fun lockInForce(lockPreference: Boolean, usbDacPresent: Boolean?): Boolean = lockPreference && usbDacPresent != false

    /** The requirements the route is decided from: the listening state, with what the lock holds off while it is in force. */
    fun effectiveRequirements(
        base: AudioProcessingRequirements,
        lockPreference: Boolean,
        usbDacPresent: Boolean?,
    ): AudioProcessingRequirements = requirements(base, lockInForce(lockPreference, usbDacPresent))

    /**
     * While locked, no contributor produces a requirement: not the DSP ones, nor crossfade nor speed, which the
     * route policy would otherwise send to Android before anything else. The settings themselves are untouched.
     */
    fun requirements(base: AudioProcessingRequirements, locked: Boolean): AudioProcessingRequirements =
        if (!locked) base else base.copy(
            graphicEq = false,
            replayGain = false,
            digitalVolume = false,
            androidSessionEffect = false,
            crossfade = false,
            nonDefaultSpeed = false,
        )

    /** The crossfade switch as playback reads it: the listener's choice, held back while the lock is on. */
    fun effectiveCrossfade(enabled: Boolean, locked: Boolean): Boolean = enabled && !locked

    /** The playback speed as the player reads it: the listener's choice, 1.0× while the lock is on. */
    fun effectiveSpeed(speed: Float, locked: Boolean): Float = if (locked) 1f else speed

    /**
     * What the lock's label may say. ACTIVE only when the lock is on **and** the stream really is on USB Direct with an
     * unmodified (PRISTINE) signal; the preference alone says nothing about what is playing.
     *
     * - [Status.ONLY_IN_DIRECT]: the lock is on but the stream is not on Direct (no DAC, Android route for any reason).
     * - [Status.DIRECT_NOT_PRISTINE]: on Direct but something still modifies the signal (a debug override, a stage that
     *   has not gone back yet). Never reported as active.
     *
     * This is the label as it was before B30, which knew nothing of the DAC's own EQ: see [statusWithDacEq].
     */
    fun status(locked: Boolean, directActive: Boolean, signalMode: DirectSignalMode = DirectSignalMode.PRISTINE): Status = when {
        !locked -> Status.OFF
        !directActive -> Status.ONLY_IN_DIRECT
        signalMode == DirectSignalMode.PRISTINE -> Status.ACTIVE
        else -> Status.DIRECT_NOT_PRISTINE
    }

    /**
     * [status] with the DAC's own EQ (B30): "untouched all the way to the DAC" needs it known off or flat.
     *
     * - [Status.DAC_EQ_ON] / [Status.DAC_EQ_UNKNOWN]: the app leaves the PCM alone, but the DAC's EQ is on, or could not be read,
     *   so the app cannot claim an untouched signal. The USB stream is bit-perfect either way; it is the analogue output that is,
     *   or may be, shaped.
     * - a DAC the app cannot ask ([DacEqLabel.NOT_APPLICABLE]) is judged as before.
     */
    fun statusWithDacEq(locked: Boolean, directActive: Boolean, signalMode: DirectSignalMode, dacEq: DacEqLabel): Status =
        when (val base = status(locked, directActive, signalMode)) {
            Status.ACTIVE -> when (dacEq) {
                DacEqLabel.ON -> Status.DAC_EQ_ON
                DacEqLabel.UNKNOWN -> Status.DAC_EQ_UNKNOWN
                DacEqLabel.OFF_OR_FLAT, DacEqLabel.NOT_APPLICABLE -> Status.ACTIVE
            }
            else -> base
        }
}
