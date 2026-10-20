package com.lostf1sh.pixelplayeross.presentation.lockscreen

/**
 * When a release of the lock surface opens the app (B25-S2). A pure function of how far the surface was carried and how
 * fast the finger was going, both in dp and both positive for an upward move.
 *
 * It used to open only past 96 dp of travel or at 1,200 dp/s **of speed measured after the touch slop**, and a short flick
 * on the HONOR (70 dp in 60 ms, about 1,170 dp/s) was under both. Now:
 *
 *  - **A slow drag** still has to carry the surface [TRAVEL_DP] (96 dp) before a release opens the app, and a drag that stops
 *    short springs back.
 *  - **A fling** opens it from [FLING_MIN_TRAVEL_DP] (32 dp) at [FLING_DP_PER_S] (400 dp/s). 400 dp/s is eight times
 *    `ViewConfiguration`'s minimum fling velocity (50 dp/s, the platform's definition of "a fling at all"), a third of the
 *    old threshold, and under half of the owner's short flick (about 1,000 dp/s): a deliberate flick of a thumb passes it
 *    with room, an unhurried drag does not. The minimum travel is four times the 8 dp touch slop, and is what keeps a
 *    pocket brush out: a brush is short, or slow, or downward, and a downward drag never moves the surface at all.
 */
internal object LockDismissPolicy {
    const val TRAVEL_DP = 96f
    const val FLING_DP_PER_S = 400f
    const val FLING_MIN_TRAVEL_DP = 32f

    /**
     * @param travelDp how far the surface has been carried up (0 or more).
     * @param velocityDpPerS the finger's upward speed at release (negative for a downward one).
     */
    fun shouldOpen(travelDp: Float, velocityDpPerS: Float): Boolean =
        travelDp >= TRAVEL_DP || (velocityDpPerS >= FLING_DP_PER_S && travelDp >= FLING_MIN_TRAVEL_DP)
}
