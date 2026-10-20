package com.lostf1sh.pixelplayeross.presentation.components.scoped

import kotlin.math.abs

/**
 * Decides when a seek bar may stop showing the position the user asked for and go back to
 * reporting the player's own position.
 *
 * While a seek is settling the bar shows the requested position. That is deliberate: the player
 * needs a moment to actually move, and showing the old position in the meantime is what makes a
 * seek look like it did not take.
 *
 * The failure this guards against is the opposite one — handing authority back *too* early, to a
 * player position that has not moved yet, which snaps the thumb back to where the user just
 * dragged it away from. So authority moves back when the player has demonstrably moved for the
 * seek, never merely because time passed:
 *
 *  - [hasSettled] returns true once the player is within [SETTLE_TOLERANCE] of the target;
 *  - or once the player is closer to the target than it was when the seek was issued, which
 *    covers a seek that lands near but not exactly on the requested position;
 *  - otherwise it holds until [SAFETY_TIMEOUT_MS]. That timeout is a backstop for a seek the
 *    player never honoured, not the normal path — the normal path resolves in a frame or two.
 */
object SeekReconciliation {

    /** How close to the requested position counts as arrived, as a fraction of the duration. */
    const val SETTLE_TOLERANCE = 0.01f

    /**
     * Backstop for a seek the player never acted on. Short on purpose: a display pinned to a
     * position the player is not going to reach should give up quickly and tell the truth.
     */
    const val SAFETY_TIMEOUT_MS = 1_000L

    fun hasSettled(
        authoritativeFraction: Float,
        targetFraction: Float,
        anchorFraction: Float,
        elapsedMs: Long
    ): Boolean {
        val distanceToTarget = abs(authoritativeFraction - targetFraction)
        if (distanceToTarget <= SETTLE_TOLERANCE) return true

        val distanceFromAnchor = abs(anchorFraction - targetFraction)
        if (distanceToTarget < distanceFromAnchor) return true

        return elapsedMs >= SAFETY_TIMEOUT_MS
    }
}
