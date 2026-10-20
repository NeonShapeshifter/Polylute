package com.lostf1sh.pixelplayeross.data.volume

/** What the Pristine controller needs from the rest of the app (F-02). */
interface PristineModeHost {
    /** userDigitalGain, replayGainFactor, profileCompensation. */
    fun gainContributors(): Triple<Float, Float?, Float>
    fun isDirectActive(): Boolean
    fun hardwareVolume(): PristineModePolicy.HardwareVolume?
    fun ceilingStep(): Int?
    /** Writes the DAC step and reads it back; true only if the DAC holds it. */
    fun setHardwareStep(step: Int): Boolean
    /** After the lock is released: true once the digital contributors are applied again. */
    suspend fun awaitContributorsApplied(): Boolean
    suspend fun lockState(): Pair<Boolean, Int>
    suspend fun persist(locked: Boolean, loweredSteps: Int)
}

sealed interface PristineToggleResult {
    data class Enabled(val loweredDb: Float) : PristineToggleResult
    data class NeedsConfirmation(val riseDb: Float, val reason: PristineModePolicy.RiseReason) : PristineToggleResult
    data class Disabled(val restoredDb: Float) : PristineToggleResult
    data class Failed(val reason: String) : PristineToggleResult
}

/**
 * Enters and leaves the Pristine lock without the effective level ever rising on its own (F-02).
 *
 * Entering: the DAC goes down by the frozen attenuation *first*, verified, and only then is the lock
 * set — so the moment the digital gain stops being applied, the DAC already carries it. Leaving: the
 * lock goes first, and the DAC comes back up only once the digital contributors are applied again;
 * if they never are, it stays down (quieter, never louder).
 */
@javax.inject.Singleton
open class PristineModeController(private val host: PristineModeHost) {

    @javax.inject.Inject
    constructor(host: DefaultPristineModeHost) : this(host as PristineModeHost)

    suspend fun enable(confirmRise: Boolean = false): PristineToggleResult {
        val (locked, _) = host.lockState()
        if (locked) return PristineToggleResult.Enabled(0f)
        val (digital, replayGain, compensation) = host.gainContributors()
        val attenuation = PristineModePolicy.frozenAttenuationDb(digital, replayGain, compensation)
        val hardware = host.hardwareVolume()
        return when (val plan = PristineModePolicy.activation(attenuation, host.isDirectActive(), hardware)) {
            PristineModePolicy.Activation.NothingToCompensate -> {
                host.persist(locked = true, loweredSteps = 0)
                PristineToggleResult.Enabled(0f)
            }
            is PristineModePolicy.Activation.LowerDac -> {
                if (!host.setHardwareStep(plan.targetStep)) {
                    return PristineToggleResult.Failed("DAC volume could not be lowered and verified")
                }
                host.persist(locked = true, loweredSteps = plan.loweredSteps)
                PristineToggleResult.Enabled(plan.loweredDb)
            }
            is PristineModePolicy.Activation.NeedsConfirmation -> {
                if (!confirmRise) return PristineToggleResult.NeedsConfirmation(plan.riseDb, plan.reason)
                // Confirmed: lower what can be lowered (the bottom of the DAC's range), the rest is
                // the rise the listener accepted.
                if (plan.reason == PristineModePolicy.RiseReason.DAC_RANGE && hardware != null &&
                    hardware.currentStep > hardware.minStep
                ) {
                    if (!host.setHardwareStep(hardware.minStep)) {
                        return PristineToggleResult.Failed("DAC volume could not be lowered and verified")
                    }
                    val steps = hardware.currentStep - hardware.minStep
                    host.persist(locked = true, loweredSteps = steps)
                    return PristineToggleResult.Enabled(steps * hardware.stepDb)
                }
                host.persist(locked = true, loweredSteps = 0)
                PristineToggleResult.Enabled(0f)
            }
        }
    }

    suspend fun disable(): PristineToggleResult {
        val (locked, lowered) = host.lockState()
        if (!locked) return PristineToggleResult.Disabled(0f)
        // Lock first: the contributors come back while the DAC is still down.
        host.persist(locked = false, loweredSteps = lowered)
        if (lowered <= 0) {
            host.persist(locked = false, loweredSteps = 0)
            return PristineToggleResult.Disabled(0f)
        }
        val hardware = host.hardwareVolume()
        if (!host.awaitContributorsApplied() || hardware == null) {
            // Never raise into a signal that is not attenuated again: stay quieter.
            host.persist(locked = false, loweredSteps = 0)
            return PristineToggleResult.Disabled(0f)
        }
        val current = host.hardwareVolume() ?: hardware
        val target = PristineModePolicy.restoreStep(current.currentStep, lowered, current.maxStep, host.ceilingStep())
        val restored = target > current.currentStep && host.setHardwareStep(target)
        host.persist(locked = false, loweredSteps = 0)
        return PristineToggleResult.Disabled(if (restored) (target - current.currentStep) * current.stepDb else 0f)
    }
}
