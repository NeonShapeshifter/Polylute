package com.lostf1sh.pixelplayeross.data.volume

import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneSafetyPolicy
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import kotlin.math.floor
import kotlin.math.roundToInt

data class VolumeBackendSnapshot(
    val kind: SafetyVolumeBackendKind,
    val outputContextId: String?,
    val outputLabel: String,
    val rawLevel: Int,
    val rawMin: Int,
    val rawMax: Int,
    val controllable: Boolean,
    /**
     * B27-A3 (review R-03): [rawLevel] is a level the backend reported. False after a write the backend refused, or whose result
     * could not be read: [rawLevel] is then only the last level that was known, and nothing may be concluded from it being low.
     */
    val levelVerified: Boolean = true,
)

object SafetyVolumePolicy {
    fun compatibleCalibration(
        calibration: SafetyMaxCalibration?,
        backend: VolumeBackendSnapshot,
    ): SafetyMaxCalibration? = calibration?.takeIf {
        it.isUsable &&
            it.outputContextId == backend.outputContextId &&
            it.backendKind == backend.kind &&
            it.backendMinRaw == backend.rawMin &&
            it.backendMaxRaw == backend.rawMax
    }

    /**
     * The calibration to enforce on [backend]: the stored one when its range still matches, else the
     * same ceiling rescaled onto the new range (AUD-074). A range change used to switch Safety Max
     * off silently; now it keeps protecting — rounded down, so never louder than the proportional
     * point — while the caller flags the calibration as needing a refresh.
     */
    fun effectiveCalibration(
        calibration: SafetyMaxCalibration?,
        backend: VolumeBackendSnapshot,
    ): SafetyMaxCalibration? {
        compatibleCalibration(calibration, backend)?.let { return it }
        val stored = calibration?.takeIf {
            it.isUsable && it.outputContextId == backend.outputContextId && it.backendKind == backend.kind
        } ?: return null
        val newSpan = backend.rawMax - backend.rawMin
        if (newSpan <= 0) return null
        val fraction = (stored.ceilingRaw - stored.backendMinRaw).toDouble() /
            (stored.backendMaxRaw - stored.backendMinRaw)
        val ceiling = (backend.rawMin + floor(fraction * newSpan).toInt())
            .coerceIn(backend.rawMin + 1, backend.rawMax)
        return stored.copy(ceilingRaw = ceiling, backendMinRaw = backend.rawMin, backendMaxRaw = backend.rawMax)
    }

    /**
     * B16-G2: the ceiling Safety Max holds [backend]'s level to right now, in the backend's own steps, or null when none
     * is in force: Safety Max off, no calibration for this output, or an override standing. Pure: the arbiter calls it
     * inside the compare-and-set of a temporary write, from the profile as the store holds it, so a ceiling that just
     * changed (a new Safety Max, a profile switch, an override that ended) is the one a restore step is judged against.
     */
    fun ceilingNow(
        safety: HeadphoneSafetyPolicy?,
        backend: VolumeBackendSnapshot,
        overrideActive: Boolean,
    ): Int? {
        if (overrideActive || safety == null || !safety.enabled) return null
        return effectiveCalibration(safety.calibrationFor(backend.outputContextId, backend.kind), backend)?.ceilingRaw
    }

    fun displayPercent(raw: Int, backend: VolumeBackendSnapshot, ceilingRaw: Int?): Int {
        val denominator = ceilingRaw?.minus(backend.rawMin) ?: (backend.rawMax - backend.rawMin)
        if (denominator <= 0) return 0
        val numerator = raw - backend.rawMin
        return (100.0 * numerator / denominator).roundToInt().coerceAtLeast(0)
    }

    fun requestedRaw(
        requested: Int,
        backend: VolumeBackendSnapshot,
        ceilingRaw: Int?,
        overrideActive: Boolean,
    ): Int {
        val absolute = requested.coerceIn(backend.rawMin, backend.rawMax)
        return if (ceilingRaw != null && !overrideActive) absolute.coerceAtMost(ceilingRaw) else absolute
    }

    fun mustClamp(raw: Int, ceilingRaw: Int?, overrideActive: Boolean): Int? =
        ceilingRaw?.takeIf { !overrideActive && raw > it }

    /**
     * F-07: a raise the ceiling refuses *outright*: the level is already at (or past) the ceiling,
     * the request asks for more, and no override is active.
     *
     * A raise that starts below the ceiling is not refused: it goes up to the ceiling and stops
     * there, which is the ceiling doing its job. Only the press that finds the level already at the
     * ceiling changes nothing, and that is the one the listener has to be told about, because on the
     * remote path (the media session, with the screen off) nothing else says anything: there is no
     * override gesture there and no readout, so a refused press used to look like a dead key.
     *
     * Never a way past the ceiling: this only *classifies* a request. The clamp that enforces the
     * ceiling is [requestedRaw], and it still runs on every request.
     */
    fun raiseRefusedByCeiling(
        requestedRaw: Int,
        currentRaw: Int,
        ceilingRaw: Int?,
        overrideActive: Boolean,
    ): Boolean = ceilingRaw != null &&
        !overrideActive &&
        requestedRaw > currentRaw &&
        currentRaw >= ceilingRaw

    /**
     * How many steps one remote request may raise.
     *
     * AUD-126: with a calibrated ceiling the ceiling is the bound and the historical allowance
     * stands. Without one on the DAC's own Feature Unit nothing is, and the remote channel is the
     * only path that can raise the level with nobody touching the device — every `MediaController`,
     * from the SystemUI slider to AVRCP, can ask. There it is one step at a time.
     */
    fun remoteRaiseSteps(backendKind: SafetyVolumeBackendKind, ceilingRaw: Int?): Int = when {
        ceilingRaw != null -> REMOTE_MAX_RAISE_STEPS
        backendKind == SafetyVolumeBackendKind.USB_HARDWARE_VOLUME -> 1
        else -> REMOTE_MAX_RAISE_STEPS
    }
}

/**
 * How often a held volume key steps. The first press always steps; repeats are paced, and raising is
 * paced slower than lowering because only raising can hurt.
 */
object VolumeKeyRepeatPolicy {
    const val RAISE_INTERVAL_MS = 150L
    const val LOWER_INTERVAL_MS = 80L

    fun shouldStep(repeatCount: Int, raising: Boolean, lastStepAtMs: Long, nowMs: Long): Boolean =
        repeatCount == 0 ||
            nowMs - lastStepAtMs >= if (raising) RAISE_INTERVAL_MS else LOWER_INTERVAL_MS

    /**
     * AUD-126: whether a remote raise may step now. The remote channel is paced exactly like a held
     * key, so nothing asking over it can walk the level up faster than a finger could.
     */
    fun remoteRaiseAllowed(nowMs: Long, lastRemoteRaiseAtMs: Long): Boolean =
        nowMs - lastRemoteRaiseAtMs >= RAISE_INTERVAL_MS
}

class SafetyOverrideGesture(
    private val requiredPresses: Int = 3,
    private val windowMs: Long = 3_000L,
    /**
     * Events closer together than this are one press, not several (FIX-5): a held key repeats every
     * ~50 ms, and on the paths that cannot see `repeatCount` (the media session's remote volume) that
     * is the only way to tell a held key from five taps. Every event moves the reference, counted or
     * not, so a stream of repeats never reaches the gap. 0 (the screen-on gesture) counts every call.
     */
    private val minGapMs: Long = 0L,
) {
    private var count = 0
    private var firstPressAt = 0L
    private var lastEventAt = 0L

    fun blockedVolumeUp(nowMs: Long): Boolean {
        val gap = nowMs - lastEventAt
        lastEventAt = nowMs
        if (count > 0 && gap < minGapMs) return false
        if (count == 0 || nowMs - firstPressAt > windowMs) {
            count = 1
            firstPressAt = nowMs
        } else {
            count++
        }
        if (count < requiredPresses) return false
        reset()
        return true
    }

    fun reset() {
        count = 0
        firstPressAt = 0L
        lastEventAt = 0L
    }
}
