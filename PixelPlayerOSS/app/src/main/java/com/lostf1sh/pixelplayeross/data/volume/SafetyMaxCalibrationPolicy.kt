package com.lostf1sh.pixelplayeross.data.volume

import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind

/**
 * B35 (RT-01): what a tap on "use the current volume as Safety Max" came to. Every answer but [SAVED] saved nothing, and each says
 * why, so a level that could not be read is never reported as a level of zero.
 */
enum class SafetyMaxCalibrationResult {
    /** The level was confirmed by the backend and is now the ceiling for this output. */
    SAVED,

    /** No selected profile, no known output, or a backend this app can neither read nor write. */
    NOT_AVAILABLE,

    /**
     * The level published is only the last one that was known (a write was refused or could not be read back), and a fresh reading
     * could not be taken either. A level nobody confirmed is not a ceiling.
     */
    LEVEL_UNCONFIRMED,

    /** The confirmed level is the backend's minimum: a ceiling there would leave nothing to listen at. */
    LEVEL_ZERO,

    /** The profile, the output or the connection to it changed while the level was being confirmed: what was read belongs to another. */
    OUTPUT_CHANGED,
}

/** What the calibration was asked on: the profile, the output, and the connection to it (the DAC's open generation). */
data class SafetyMaxCalibrationBasis(
    val profileId: String,
    val outputContextId: String,
    val kind: SafetyVolumeBackendKind,
    /** The DAC's open generation for a hardware backend, null for any other. A reopened or swapped DAC has another one. */
    val connection: Long?,
)

/**
 * The rule behind [SafetyMaxCalibrationResult], pure so a test can state it: a calibration saves only a level the backend
 * reported, read on the very output, connection and profile the listener asked on.
 */
object SafetyMaxCalibrationPolicy {
    /** The basis of a request made on [state], or null when there is nothing to calibrate ([SafetyMaxCalibrationResult.NOT_AVAILABLE]). */
    fun basisOf(state: SafetyVolumeState, connection: Long?): SafetyMaxCalibrationBasis? {
        val profileId = state.selectedProfileId ?: return null
        val contextId = state.backend.outputContextId ?: return null
        if (!state.backend.controllable) return null
        return SafetyMaxCalibrationBasis(profileId, contextId, state.backend.kind, connection)
    }

    /**
     * Why [state] (as it stands after any fresh reading) cannot be saved for [basis], or null when its level may be. [connection] is the
     * connection as it is now. Order matters: a level that was never confirmed says nothing about being low, so it is judged before zero.
     */
    fun refusal(basis: SafetyMaxCalibrationBasis, state: SafetyVolumeState, connection: Long?): SafetyMaxCalibrationResult? {
        val backend = state.backend
        if (state.selectedProfileId != basis.profileId ||
            backend.outputContextId != basis.outputContextId ||
            connection != basis.connection
        ) return SafetyMaxCalibrationResult.OUTPUT_CHANGED
        // The same output that can no longer be read or written (a DAC whose level cannot be read at open is a fixed output) is not
        // "another output": it is one this cannot be done on.
        if (!backend.controllable) return SafetyMaxCalibrationResult.NOT_AVAILABLE
        if (backend.kind != basis.kind) return SafetyMaxCalibrationResult.OUTPUT_CHANGED
        if (!backend.levelVerified) return SafetyMaxCalibrationResult.LEVEL_UNCONFIRMED
        if (backend.rawLevel <= backend.rawMin) return SafetyMaxCalibrationResult.LEVEL_ZERO
        return null
    }
}
