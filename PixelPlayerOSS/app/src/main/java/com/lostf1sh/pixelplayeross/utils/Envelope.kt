package com.lostf1sh.pixelplayeross.utils

import com.lostf1sh.pixelplayeross.data.model.Curve
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Calculates the *amplitude* multiplier of the incoming track based on the progress of a transition
 * and a given curve.
 *
 * This function maps a linear progress value (0.0 to 1.0) to a non-linear value
 * according to the selected curve, which can be used to shape volume fades.
 *
 * [Curve.S_CURVE] is the equal-power fade-in, `sin(πp/2)`: paired with [fadeOutEnvelope]'s
 * `cos(πp/2)` the two amplitudes satisfy `in² + out² = 1`, so the power of the mix is constant. It
 * used to be `(1 − cos πp) / 2 = sin²(πp/2)`, a power shape applied as an amplitude (AUD-122).
 *
 * @param progress A Float from 0.0 (start) to 1.0 (end) representing the transition's progress.
 * @param curve The volume curve to apply.
 * @return A Float from 0.0 to 1.0 representing the calculated volume multiplier.
 */
fun envelope(progress: Float, curve: Curve): Float {
    val clampedProgress = progress.coerceIn(0f, 1f)

    return when (curve) {
        Curve.LINEAR -> clampedProgress
        Curve.S_CURVE -> sin(PI / 2 * clampedProgress).toFloat()
        Curve.LOG -> sqrt(clampedProgress)
        Curve.EXP -> clampedProgress * clampedProgress
    }
}

/**
 * The *amplitude* multiplier of the outgoing track: the true complement of [envelope] for the
 * equal-power S curve (`cos(πp/2)`, i.e. the incoming curve run backwards), and `1 − envelope` for
 * the curves whose shape was never the defect.
 */
fun fadeOutEnvelope(progress: Float, curve: Curve): Float =
    if (curve == Curve.S_CURVE) envelope(1f - progress.coerceIn(0f, 1f), Curve.S_CURVE)
    else 1f - envelope(progress, curve)

/** The two players' volumes at one instant of a crossfade. */
data class CrossfadeGains(val incoming: Float, val outgoing: Float)

/**
 * The volumes of the incoming and the outgoing player at [progress] of a crossfade, scaled by the
 * composed gain each carries (the user's level × ReplayGain × profile compensation): [incomingTarget]
 * is where the incoming track lands, [outgoingStartVolume] is where the outgoing one started. Neither
 * player ever goes above its own composed gain, so a crossfade cannot make anything louder.
 */
fun crossfadeGains(
    progress: Float,
    curveIn: Curve,
    curveOut: Curve,
    incomingTarget: Float,
    outgoingStartVolume: Float,
): CrossfadeGains = CrossfadeGains(
    incoming = (envelope(progress, curveIn) * incomingTarget).coerceIn(0f, 1f),
    outgoing = (fadeOutEnvelope(progress, curveOut) * outgoingStartVolume).coerceIn(0f, 1f),
)
