package com.decent.usbaudio.media3

/**
 * Coherence watchdog between Media3's position and the native engine's (B7-fix, reworked for AUD-136).
 *
 * B7-fix on the device (`device-b7fix-selfskip`): its first correction came from a player sample of
 * the previous item (204 s, taken before the transition, while Media3 was buffering) and lowered the
 * offset by 204 s. Media3's audio renderer never lets its position go backwards unless the sink
 * reports a discontinuity (`max(current, sink)` in MediaCodecAudioRenderer), so Media3 froze; the
 * frozen position read as "behind", each correction raised the offset by the growing gap
 * (-1.5 → -9.1 s) and Media3 threw StuckPlayerException after 10 s.
 *
 * Now:
 * - evidence is a fresh [Sample] of a playing, READY Media3 taken after the epoch began;
 * - a drift over [DRIFT_LIMIT_US] must read the same twice [CONFIRM_MS] apart — a frozen player's
 *   "drift" grows with time and is not an offset error;
 * - a correction ([Action.Correct], applied by the sink with a discontinuity when it lowers the
 *   position) is verified: one that did not reduce the error, or after which Media3 stopped
 *   following, is undone ([Action.Revert]) and the watchdog switches itself off for the epoch;
 * - at most [MAX_CORRECTIONS] per epoch, each bounded by [MAX_CORRECTION_US];
 * - it never seeks: an offset never latched is only reported ([Action.Unlatched]).
 */
class PositionDriftMonitor {
    data class Sample(val positionMs: Long, val atMs: Long, val playing: Boolean, val ready: Boolean)

    sealed interface Action {
        data object None : Action
        data class Correct(val driftUs: Long) : Action
        data class Revert(val correctionUs: Long, val reason: String) : Action
        data class Disable(val reason: String) : Action
        data class Verified(val residualUs: Long) : Action
        data class Unlatched(val enginePositionUs: Long) : Action
    }

    private var evidenceAfterMs = 0L
    private var disabled = false
    private var corrections = 0
    private var lastCorrectionUs = 0L
    private var verifying = false
    private var firstAtMs = -1L
    private var firstSampleAtMs = -1L
    private var firstDriftUs = 0L
    private var inconsistent = 0
    private var unlatchedStartUs = -1L
    private var unlatchedReported = false

    /** A new item, seek or position reset: earlier evidence and corrections no longer apply. */
    fun onEpochStart(nowMs: Long) {
        evidenceAfterMs = nowMs + SETTLE_MS
        disabled = false
        corrections = 0
        lastCorrectionUs = 0L
        verifying = false
        firstAtMs = -1L
        inconsistent = 0
        unlatchedStartUs = -1L
        unlatchedReported = false
    }

    fun check(nowMs: Long, enginePositionUs: Long, sample: Sample?, offsetLatched: Boolean): Action {
        if (disabled) return Action.None
        if (!offsetLatched) {
            firstAtMs = -1L
            if (unlatchedStartUs < 0L) unlatchedStartUs = enginePositionUs
            if (!unlatchedReported && enginePositionUs - unlatchedStartUs >= UNLATCHED_REPORT_US) {
                unlatchedReported = true
                return Action.Unlatched(enginePositionUs)
            }
            return Action.None
        }
        unlatchedStartUs = -1L
        if (sample == null || !sample.playing || !sample.ready) return Action.None
        if (sample.atMs < evidenceAfterMs || nowMs - sample.atMs > FRESH_MS) return Action.None

        val driftUs = (sample.positionMs + (nowMs - sample.atMs)) * 1000L - enginePositionUs
        if (kotlin.math.abs(driftUs) <= DRIFT_LIMIT_US) {
            firstAtMs = -1L
            inconsistent = 0
            if (verifying) {
                verifying = false
                return Action.Verified(driftUs)
            }
            return Action.None
        }
        if (firstAtMs < 0L) {
            remember(nowMs, sample.atMs, driftUs)
            return Action.None
        }
        if (sample.atMs == firstSampleAtMs || nowMs - firstAtMs < CONFIRM_MS) return Action.None
        if (kotlin.math.abs(driftUs - firstDriftUs) > CONSISTENCY_US) {
            remember(nowMs, sample.atMs, driftUs)
            inconsistent++
            if (verifying && inconsistent >= 2) {
                disabled = true
                return Action.Revert(lastCorrectionUs, "Media3 stopped following after the correction")
            }
            return Action.None
        }
        firstAtMs = -1L
        inconsistent = 0
        if (kotlin.math.abs(driftUs) > MAX_CORRECTION_US) {
            disabled = true
            return Action.Disable("drift ${driftUs / 1000}ms beyond the bound")
        }
        if (verifying) {
            if (kotlin.math.abs(driftUs) >= kotlin.math.abs(lastCorrectionUs)) {
                disabled = true
                return Action.Revert(lastCorrectionUs, "correction did not reduce the error (now ${driftUs / 1000}ms)")
            }
            if (kotlin.math.abs(driftUs) > kotlin.math.abs(lastCorrectionUs) / 2) {
                disabled = true
                return Action.Disable("error not converging (${lastCorrectionUs / 1000} → ${driftUs / 1000}ms)")
            }
        }
        if (corrections >= MAX_CORRECTIONS) {
            disabled = true
            return Action.Disable("still ${driftUs / 1000}ms off after $corrections corrections")
        }
        corrections++
        lastCorrectionUs = driftUs
        verifying = true
        evidenceAfterMs = nowMs + SETTLE_MS
        return Action.Correct(driftUs)
    }

    private fun remember(nowMs: Long, sampleAtMs: Long, driftUs: Long) {
        firstAtMs = nowMs
        firstSampleAtMs = sampleAtMs
        firstDriftUs = driftUs
    }

    companion object {
        const val DRIFT_LIMIT_US = 1_000_000L
        const val CONFIRM_MS = 500L
        /** Two readings of a real offset error agree; a frozen player's differ by the time between. */
        const val CONSISTENCY_US = 300_000L
        /** The player mirror samples every 250 ms. */
        const val FRESH_MS = 400L
        /** Evidence must postdate the epoch (or the correction) by this: Media3 has to show it. */
        const val SETTLE_MS = 300L
        const val MAX_CORRECTIONS = 2
        const val MAX_CORRECTION_US = 600_000_000L
        const val UNLATCHED_REPORT_US = 2_000_000L
    }
}
