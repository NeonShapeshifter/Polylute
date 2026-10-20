package com.lostf1sh.pixelplayeross.presentation.lockscreen

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The wake cycle, pinned against the behaviour the HONOR actually exhibited.
 *
 * Every test here is a pure state transition — no coroutines, no sleeps, no clock. The machine was
 * extracted precisely so that the handoff could be tested without a device, because the device
 * evidence is what the old implementation got wrong:
 *
 * ```
 * SCREEN_ON  -> keyguard up   -> the old code kept arming (correct, by luck)
 * ON_RESUME  -> keyguard up   -> the old code did not act (correct)
 * USER_PRESENT                -> NEVER ARRIVED (so the old code never showed anything)
 * FOCUS      -> keyguard down -> the signal that works, which the old code never listened for
 * ```
 */
class WakeCycleTest {

    private fun machine() = WakeCycleMachine()

    private fun WakeCycleMachine.wake(
        enabled: Boolean = true,
        dedicated: Boolean = true,
        keyguardLocked: Boolean = true,
        hasSong: Boolean = true,
        nowMs: Long = 0L,
    ) = apply {
        onScreenOff(enabled = enabled, dedicated = dedicated)
        onScreenOn(
            enabled = enabled,
            dedicated = dedicated,
            keyguardLocked = keyguardLocked,
            hasSong = hasSong,
            nowMs = nowMs,
        )
    }

    // --- Arming ----------------------------------------------------------------------------------

    @Test
    fun `screen off arms the cycle`() {
        val m = machine()
        assertThat(m.onScreenOff(enabled = true, dedicated = true)).isEqualTo(WakeCycleState.ARMED)
        assertThat(m.isLive).isTrue()
    }

    @Test
    fun `screen off without eligibility never arms`() {
        assertThat(machine().onScreenOff(enabled = false, dedicated = true)).isEqualTo(WakeCycleState.IDLE)
        assertThat(machine().onScreenOff(enabled = true, dedicated = false)).isEqualTo(WakeCycleState.IDLE)
    }

    // --- Screen on -------------------------------------------------------------------------------

    @Test
    fun `screen on with the keyguard already clear shows immediately`() {
        val m = machine()
        m.onScreenOff(enabled = true, dedicated = true)
        assertThat(
            m.onScreenOn(enabled = true, dedicated = true, keyguardLocked = false, hasSong = true, nowMs = 0L),
        ).isEqualTo(WakeCycleState.VISIBLE)
    }

    @Test
    fun `screen on with the keyguard up waits instead of showing`() {
        val m = machine().wake(keyguardLocked = true)
        assertThat(m.state).isEqualTo(WakeCycleState.WAITING_FOR_KEYGUARD)
        assertThat(m.shouldShowSurface).isFalse()
    }

    // --- The regression the device exposed -------------------------------------------------------

    @Test
    fun `a signal that arrives while still locked does not consume the cycle`() {
        // This is the old bug's shape: an event fires, the Keyguard is still up, and the cycle is
        // spent anyway. The trace showed ON_RESUME doing exactly this, and showed the cycle then
        // surviving to the focus gain 257 ms later purely because USER_PRESENT never fired.
        val m = machine().wake(keyguardLocked = true)

        repeat(7) {
            m.onUnlockEvidence(enabled = true, dedicated = true, keyguardLocked = true, hasSong = true, nowMs = 0L)
            assertThat(m.state).isEqualTo(WakeCycleState.WAITING_FOR_KEYGUARD)
            assertThat(m.isLive).isTrue()
        }
    }

    @Test
    fun `a later unlocked signal shows, exactly once`() {
        val m = machine().wake(keyguardLocked = true)

        m.onUnlockEvidence(enabled = true, dedicated = true, keyguardLocked = true, hasSong = true, nowMs = 0L)
        m.onUnlockEvidence(enabled = true, dedicated = true, keyguardLocked = false, hasSong = true, nowMs = 0L)

        assertThat(m.state).isEqualTo(WakeCycleState.VISIBLE)

        // Further signals must not re-raise or duplicate it: a shown surface is not a live cycle.
        m.onUnlockEvidence(enabled = true, dedicated = true, keyguardLocked = false, hasSong = true, nowMs = 0L)
        assertThat(m.state).isEqualTo(WakeCycleState.VISIBLE)
        assertThat(m.isLive).isFalse()
    }

    @Test
    fun `a shown cycle resolves to nothing once dismissed`() {
        val m = machine().wake(keyguardLocked = false)
        assertThat(m.state).isEqualTo(WakeCycleState.VISIBLE)

        assertThat(m.reset()).isEqualTo(WakeCycleState.IDLE)
        // And no later signal resurrects it.
        m.onUnlockEvidence(enabled = true, dedicated = true, keyguardLocked = false, hasSong = true, nowMs = 0L)
        assertThat(m.state).isEqualTo(WakeCycleState.IDLE)
    }

    // --- No show without a wake cycle ------------------------------------------------------------

    @Test
    fun `an ordinary resume with no armed cycle never shows`() {
        // Opening Settings, closing a dialog, returning home: focus and resume events fire with no
        // cycle behind them, and they must resolve to nothing at all.
        val m = machine()
        repeat(5) {
            m.onUnlockEvidence(enabled = true, dedicated = true, keyguardLocked = false, hasSong = true, nowMs = 0L)
            assertThat(m.state).isEqualTo(WakeCycleState.IDLE)
        }
    }

    @Test
    fun `a screen on with no preceding screen off never shows`() {
        val m = machine()
        assertThat(
            m.onScreenOn(enabled = true, dedicated = true, keyguardLocked = false, hasSong = true, nowMs = 0L),
        ).isEqualTo(WakeCycleState.IDLE)
    }

    @Test
    fun `a timed-out cycle does not fire later`() {
        val m = machine().wake(keyguardLocked = true)
        assertThat(m.isLive).isTrue()

        m.reset() // what the host does when the bounded cycle cap expires

        m.onUnlockEvidence(enabled = true, dedicated = true, keyguardLocked = false, hasSong = true, nowMs = 0L)
        assertThat(m.state).isEqualTo(WakeCycleState.IDLE)
    }

    // --- Fresh cycles ----------------------------------------------------------------------------

    @Test
    fun `a second screen off starts a fresh cycle rather than stacking`() {
        val m = machine().wake(keyguardLocked = true)
        m.onUnlockEvidence(enabled = true, dedicated = true, keyguardLocked = false, hasSong = true, nowMs = 0L)
        assertThat(m.state).isEqualTo(WakeCycleState.VISIBLE)

        // Screen off with the surface still up must not leave two.
        assertThat(m.onScreenOff(enabled = true, dedicated = true)).isEqualTo(WakeCycleState.ARMED)
        assertThat(m.shouldShowSurface).isFalse()
    }

    // --- Cancellation ----------------------------------------------------------------------------

    @Test
    fun `losing eligibility while waiting cancels the cycle`() {
        assertThat(waitingThen(keyguardLocked = true, enabled = false, dedicated = true, hasSong = true))
            .isEqualTo(WakeCycleState.IDLE)
        assertThat(waitingThen(keyguardLocked = true, enabled = true, dedicated = false, hasSong = true))
            .isEqualTo(WakeCycleState.IDLE)
        assertThat(waitingThen(keyguardLocked = true, enabled = true, dedicated = true, hasSong = false))
            .isEqualTo(WakeCycleState.IDLE)
    }

    @Test
    fun `a cycle that loses eligibility cannot show later`() {
        val m = machine().wake(keyguardLocked = true)
        m.onUnlockEvidence(enabled = false, dedicated = true, keyguardLocked = true, hasSong = true, nowMs = 0L)
        assertThat(m.state).isEqualTo(WakeCycleState.IDLE)

        // Even a perfectly good unlock signal must not revive a cancelled cycle.
        m.onUnlockEvidence(enabled = true, dedicated = true, keyguardLocked = false, hasSong = true, nowMs = 0L)
        assertThat(m.state).isEqualTo(WakeCycleState.IDLE)
    }

    private fun waitingThen(
        keyguardLocked: Boolean,
        enabled: Boolean,
        dedicated: Boolean,
        hasSong: Boolean,
    ): WakeCycleState {
        val m = machine().wake(keyguardLocked = true)
        return m.onUnlockEvidence(
            enabled = enabled,
            dedicated = dedicated,
            keyguardLocked = keyguardLocked,
            hasSong = hasSong,
            nowMs = 0L,
        )
    }

    // --- Bounds ----------------------------------------------------------------------------------

    @Test
    fun `the recheck ladder is finite and ordered`() {
        val ladder = WakeCycleMachine.KEYGUARD_RECHECK_MS.toList()
        assertThat(ladder).isNotEmpty()
        assertThat(ladder).isInOrder()
        // Bounded by construction: a handful of probes, not a poll.
        assertThat(ladder.size).isAtMost(10)
        // The measured lag was 257 ms, so the ladder must be dense below half a second.
        assertThat(ladder.count { it <= 500L }).isAtLeast(4)
    }

    @Test
    fun `the cycle cap is bounded`() {
        assertThat(WakeCycleMachine.WAKE_CYCLE_TIMEOUT_MS).isAtMost(120_000L)
        assertThat(WakeCycleMachine.WAKE_CYCLE_TIMEOUT_MS).isGreaterThan(0L)
    }

    // --- The single decision authority -----------------------------------------------------------

    @Test
    fun `every transition routes through the shared policy`() {
        // The machine must not grow a second opinion about when a surface may be shown.
        val source = java.io.File(
            "src/main/java/com/lostf1sh/pixelplayeross/presentation/lockscreen/WakeCycle.kt",
        ).let { if (it.isFile) it else java.io.File("app/${it.path}") }
        val text = source.readText()
        assertThat(text).contains("DapLockScreenPolicy.shouldShow(")
        assertThat(text).contains("DapLockScreenPolicy.shouldArm(")
    }

    // --- Security boundary -----------------------------------------------------------------------

    @Test
    fun `the host still never touches the keyguard`() {
        val source = java.io.File(
            "src/main/java/com/lostf1sh/pixelplayeross/presentation/lockscreen/DapLockScreenHost.kt",
        ).let { if (it.isFile) it else java.io.File("app/${it.path}") }
        val text = source.readText()

        listOf(
            "FLAG_SHOW_WHEN_LOCKED",
            "requestDismissKeyguard",
            "KeyguardManager.KeyguardLock",
            "disableKeyguard",
            "WindowManager",
            "TYPE_APPLICATION_OVERLAY",
            "DevicePolicyManager",
            "newKeyguardLock",
        ).forEach { forbidden ->
            assertThat(text).doesNotContain(forbidden)
        }
        // It reads the keyguard state and obeys it; that is the whole relationship.
        assertThat(text).contains("isKeyguardLocked")
    }
}
