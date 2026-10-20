package com.lostf1sh.pixelplayeross.data.volume

/**
 * B27-A3 (review R-03): what a write to a volume backend came to, as the backend itself reported it.
 *
 * Safety Max, the readout and the automatic resume are all derived from the level the backend **holds**, so a write publishes
 * the level that was read back, never the one that was asked for. A write the DAC refused (or whose result could not be read)
 * leaves its level unknown: [observedStep] is null, the request is not credited, and what was published before stays as the last
 * known level, marked unverified ([VolumeBackendSnapshot.levelVerified]).
 *
 * @param requestedStep the step that was written
 * @param observedStep the step the backend reported afterwards (a read-back, rounded up when it falls between two steps), or null
 */
data class VolumeWrite(val requestedStep: Int, val observedStep: Int?) {
    /** The backend told us what it holds. */
    val verified: Boolean get() = observedStep != null

    /** The backend holds exactly what was asked for. */
    val accepted: Boolean get() = observedStep == requestedStep

    /** The level to publish: the one the backend reported, else the last one that was known. Never the requested one by assumption. */
    fun publishedStep(lastKnown: Int): Int = observedStep ?: lastKnown

    /** A lower level was asked for and the backend is not known to hold anything at or below it. */
    fun reductionFailed(lastKnown: Int): Boolean = requestedStep < lastKnown && publishedStep(lastKnown) > requestedStep
}
