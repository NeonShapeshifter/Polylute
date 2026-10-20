package com.lostf1sh.pixelplayeross.presentation.lockscreen

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B25-S2: when a release of the lock surface opens the app, as a pure function of how far it was carried and how fast the
 * finger was going (dp, dp/s, both positive for upward).
 */
class LockDismissPolicyTest {

    @Test
    fun `the reviewer's short flick, 70 dp in 60 ms, opens the app`() {
        // `input swipe 360 900 360 760 60` on the HONOR: 140 px at 2 px/dp over 60 ms.
        val travelDp = 70f
        val velocityDpPerS = travelDp / 0.060f
        assertThat(velocityDpPerS).isGreaterThan(1_000f)
        assertThat(LockDismissPolicy.shouldOpen(travelDp, velocityDpPerS)).isTrue()
    }

    @Test
    fun `a moderate flick of a thumb opens it, a short unhurried drag does not`() {
        // 45 dp in 100 ms is 450 dp/s: a flick. 45 dp in 600 ms is 75 dp/s: a drag that stopped.
        assertThat(LockDismissPolicy.shouldOpen(45f, 450f)).isTrue()
        assertThat(LockDismissPolicy.shouldOpen(45f, 75f)).isFalse()
    }

    @Test
    fun `a slow drag past the travel threshold still opens it, and a slow short one springs back`() {
        assertThat(LockDismissPolicy.shouldOpen(LockDismissPolicy.TRAVEL_DP, 0f)).isTrue()
        assertThat(LockDismissPolicy.shouldOpen(LockDismissPolicy.TRAVEL_DP + 40f, 10f)).isTrue()
        assertThat(LockDismissPolicy.shouldOpen(LockDismissPolicy.TRAVEL_DP - 1f, 10f)).isFalse()
        assertThat(LockDismissPolicy.shouldOpen(60f, 100f)).isFalse()
    }

    @Test
    fun `a pocket brush does not open it, however fast, because it is short`() {
        // a quick, tiny movement: under the fling's minimum travel
        assertThat(LockDismissPolicy.shouldOpen(8f, 2_000f)).isFalse()
        assertThat(LockDismissPolicy.shouldOpen(LockDismissPolicy.FLING_MIN_TRAVEL_DP - 1f, 5_000f)).isFalse()
        assertThat(LockDismissPolicy.shouldOpen(LockDismissPolicy.FLING_MIN_TRAVEL_DP, 5_000f)).isTrue()
    }

    @Test
    fun `a downward move, a fling that is too slow, or no movement never opens it`() {
        assertThat(LockDismissPolicy.shouldOpen(0f, -1_500f)).isFalse()
        assertThat(LockDismissPolicy.shouldOpen(50f, -300f)).isFalse()
        assertThat(LockDismissPolicy.shouldOpen(50f, LockDismissPolicy.FLING_DP_PER_S - 1f)).isFalse()
        assertThat(LockDismissPolicy.shouldOpen(0f, 0f)).isFalse()
    }

    @Test
    fun `the chosen values are platform-like, not the old ones`() {
        // ViewConfiguration's minimum fling velocity is 50 dp/s: the fling threshold is eight times it and a third of the old one.
        assertThat(LockDismissPolicy.FLING_DP_PER_S).isAtLeast(8 * 50f)
        assertThat(LockDismissPolicy.FLING_DP_PER_S).isLessThan(1_200f / 2)
        // and four touch slops (8 dp) of travel is what a fling must carry
        assertThat(LockDismissPolicy.FLING_MIN_TRAVEL_DP).isAtLeast(4 * 8f)
        assertThat(LockDismissPolicy.TRAVEL_DP).isEqualTo(96f)
    }

    @Test
    fun `WakeCycleCover holds the app from the arming to the end of the cycle, except a visible cycle with nothing to show`() {
        assertThat(WakeCycleCover.covers(WakeCycleState.IDLE, hasSong = true)).isFalse()
        assertThat(WakeCycleCover.covers(WakeCycleState.IDLE, hasSong = false)).isFalse()
        assertThat(WakeCycleCover.covers(WakeCycleState.ARMED, hasSong = true)).isTrue()
        assertThat(WakeCycleCover.covers(WakeCycleState.ARMED, hasSong = false)).isTrue()
        assertThat(WakeCycleCover.covers(WakeCycleState.WAITING_FOR_KEYGUARD, hasSong = true)).isTrue()
        assertThat(WakeCycleCover.covers(WakeCycleState.VISIBLE, hasSong = true)).isTrue()
        // nothing would be drawn over a held app
        assertThat(WakeCycleCover.covers(WakeCycleState.VISIBLE, hasSong = false)).isFalse()
    }
}
