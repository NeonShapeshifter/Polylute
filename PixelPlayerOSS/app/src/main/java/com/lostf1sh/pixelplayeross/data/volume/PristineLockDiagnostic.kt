package com.lostf1sh.pixelplayeross.data.volume

import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * B9-4, measurement only: a process-only way to measure PROCESSED while the owner's Pure Direct lock (F-02) is on.
 *
 * Why it exists. `SET_REPLAY_GAIN_FACTOR` worked on 2026-09-29 (the capture shows `REALIGN →PROCESSED`) and
 * "stopped" working on 2026-10-06 (B8b: `GAIN|START|replayGainFactor=0.5` was in the capture, so the factor
 * reached the processor, and yet `Listening requirements changed: DIRECT_PRISTINE`, `mode=PRISTINE`). Nothing
 * broke: the owner turned the lock on between the two. `pristine_lock_enabled` is `false` in the backup taken
 * before B8a and `true` in the one taken before B8b, and [PristineModePolicy.requirements] — by design — drops
 * every DSP contributor's requirement (EQ, ReplayGain, digital volume, session effects) while it is on, so a
 * synthetic factor is accepted and then frozen with the rest.
 *
 * What it does not do. It never touches the persisted lock, the DAC's level or the lock's own bookkeeping
 * (`pristine_lock_lowered_steps`); it only makes the *requirement decision* read the lock as off while the
 * override is set. The override lives in this process, is not written anywhere, and a new process starts with
 * it off, so a forgotten `true` does not outlive the app. With it on the stream may be attenuated (that is the
 * point: a measured −6 dB), never boosted: the lock raises nothing on its own.
 *
 * `SET_HEADPHONE_EQ` is not the alternative: it overwrites the owner's EQ profile.
 */
object PristineLockDiagnostic {
    private val ignoring = MutableStateFlow(false)

    /** True while the debug bridge asks the requirements to read the Pure Direct lock as off. */
    val ignoringLock: StateFlow<Boolean> = ignoring.asStateFlow()

    @Volatile
    private var persisted: Boolean? = null

    fun setIgnoring(ignore: Boolean) {
        ignoring.value = ignore
    }

    /** What the requirement decision sees: the persisted lock, unless the diagnostic override says to ignore it. */
    fun effectiveLock(persistedLock: Boolean, ignoringOverride: Boolean): Boolean = persistedLock && !ignoringOverride

    /**
     * The flow `MusicService` feeds [PristineModePolicy.requirements]: [persistedLock] (the preference) as
     * the requirement decision should read it, re-evaluated when either the preference or the override
     * changes. Remembers the last preference value, which the bridge's reply lines report.
     */
    fun requirementsLock(persistedLock: Flow<Boolean>): Flow<Boolean> =
        persistedLock.combine(ignoring) { locked, ignoringOverride ->
            persisted = locked
            effectiveLock(locked, ignoringOverride)
        }

    /**
     * B16-G4: [requirementsLock] with a USB DAC required: the lock as the crossfade scheduler, the speed and the screens
     * read it, true only while the preference is on **and** the router found a DAC ([PristineModePolicy.lockInForce]).
     */
    fun inForce(persistedLock: Flow<Boolean>, usbDacPresent: Flow<Boolean?>): Flow<Boolean> =
        requirementsLock(persistedLock).combine(usbDacPresent) { locked, dac -> PristineModePolicy.lockInForce(locked, dac) }

    /** The preference as the requirements flow last saw it; null until it has. */
    fun lastPersistedLock(): Boolean? = persisted

    /** The unity tolerance the requirements use for ReplayGain (`MusicService`): a factor within it is no processing. */
    const val UNITY_TOLERANCE = 0.0001f

    /**
     * Whether a synthetic ReplayGain [factor] takes the stream to PROCESSED now: non-unity, and the lock not
     * freezing it. Null (no contribution) never does.
     */
    fun replayGainSwitchesToProcessed(factor: Float?, persistedLock: Boolean?, ignoringOverride: Boolean): Boolean {
        if (factor == null || abs(factor - 1f) <= UNITY_TOLERANCE) return false
        return !effectiveLock(persistedLock == true, ignoringOverride)
    }
}
