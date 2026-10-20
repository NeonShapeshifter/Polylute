package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.volume.ScreenOffOverridePolicy.Delivery
import org.junit.jupiter.api.Test

/**
 * FIX-5: asking for the temporary Safety Max override with the screen off. The counting matrix (screen
 * off, at the ceiling, the window, reset when lowering), the DAP-versus-app decision, and the fact that
 * without the confirmation nothing goes past the ceiling.
 */
class ScreenOffOverridePolicyTest {
    private fun gesture() = ScreenOffOverridePolicy.newGesture()

    /** Presses `count` times, `spacingMs` apart from `startMs`, and returns the times at which the gesture fired. */
    private fun SafetyOverrideGesture.press(count: Int, spacingMs: Long, startMs: Long = 0L): List<Long> =
        (0 until count).map { startMs + it * spacingMs }.filter { blockedVolumeUp(it) }

    // ---------------------------------------------------------------- counting

    @Test
    fun `the fifth press inside three seconds fires, the fourth does not`() {
        val g = gesture()
        assertThat(g.press(count = 4, spacingMs = 600L)).isEmpty()
        val fresh = gesture()
        assertThat(fresh.press(count = 5, spacingMs = 600L)).containsExactly(2_400L)
    }

    @Test
    fun `five presses spread over more than three seconds never add up`() {
        val g = gesture()
        // 0, 800, 1600, 2400, 3200: the fifth is 3.2 s after the first.
        assertThat(g.press(count = 5, spacingMs = 800L)).isEmpty()
        // The count then restarts from the late press, so four more are still not enough.
        assertThat(g.press(count = 3, spacingMs = 500L, startMs = 3_700L)).isEmpty()
    }

    @Test
    fun `the window is three seconds from the first press, inclusive`() {
        assertThat(gesture().press(count = 5, spacingMs = 750L)).containsExactly(3_000L)
        val late = gesture()
        val times = listOf(0L, 750L, 1_500L, 2_250L, 3_001L)
        assertThat(times.filter { late.blockedVolumeUp(it) }).isEmpty()
    }

    @Test
    fun `a held key counts as one press however long it is held`() {
        val g = gesture()
        // Down at 0, then the platform's repeats: 500 ms later and every 50 ms for ten seconds.
        val fired = mutableListOf<Long>()
        if (g.blockedVolumeUp(0L)) fired += 0L
        var t = 500L
        while (t <= 10_000L) {
            if (g.blockedVolumeUp(t)) fired += t
            t += 50L
        }
        assertThat(fired).isEmpty()
    }

    @Test
    fun `fast deliberate taps are not mistaken for a held key`() {
        // Five taps 150 ms apart, the quickest a person presses, still fire.
        assertThat(gesture().press(count = 5, spacingMs = 150L)).containsExactly(600L)
        assertThat(ScreenOffOverridePolicy.MIN_PRESS_GAP_MS).isLessThan(150L)
        assertThat(ScreenOffOverridePolicy.MIN_PRESS_GAP_MS).isGreaterThan(50L)
    }

    @Test
    fun `after it fires the count starts again, so a second ask needs five more`() {
        val g = gesture()
        assertThat(g.press(count = 5, spacingMs = 500L)).hasSize(1)
        assertThat(g.press(count = 4, spacingMs = 500L, startMs = 5_000L)).isEmpty()
        assertThat(g.blockedVolumeUp(7_000L)).isTrue()
    }

    @Test
    fun `lowering the volume breaks a sequence`() {
        val g = gesture()
        assertThat(g.press(count = 4, spacingMs = 400L)).isEmpty()
        g.reset() // the controller resets it when the level goes down
        assertThat(g.blockedVolumeUp(2_000L)).isFalse()
        assertThat(g.press(count = 3, spacingMs = 400L, startMs = 2_400L)).isEmpty()
    }

    @Test
    fun `the screen-on gesture is unchanged, three presses and no gap`() {
        val g = SafetyOverrideGesture()
        assertThat(g.blockedVolumeUp(0L)).isFalse()
        assertThat(g.blockedVolumeUp(10L)).isFalse()
        assertThat(g.blockedVolumeUp(20L)).isTrue()
    }

    @Test
    fun `five is more than the screen-on gesture asks and the window is the same three seconds`() {
        assertThat(ScreenOffOverridePolicy.REQUIRED_PRESSES).isEqualTo(5)
        assertThat(ScreenOffOverridePolicy.WINDOW_MS).isEqualTo(3_000L)
        assertThat(ScreenOffOverridePolicy.CONFIRM_TIMEOUT_MS).isEqualTo(10_000L)
        assertThat(ScreenOffOverridePolicy.WAKE_GRACE_MS).isEqualTo(10_000L)
        assertThat(ScreenOffOverridePolicy.UNLOCK_GRACE_MS).isEqualTo(30_000L)
    }

    // ---------------------------------------------------------------- what counts as a press

    @Test
    fun `only a single step up with the screen off or locked is a press`() {
        assertThat(ScreenOffOverridePolicy.countsAsPress(singleStepRaise = true, screenOffOrLocked = true)).isTrue()
        // With the screen on and unlocked the in-app gesture is the one.
        assertThat(ScreenOffOverridePolicy.countsAsPress(singleStepRaise = true, screenOffOrLocked = false)).isFalse()
        // The system panel's slider asks for whatever level the thumb is at.
        assertThat(ScreenOffOverridePolicy.countsAsPress(singleStepRaise = false, screenOffOrLocked = true)).isFalse()
    }

    @Test
    fun `presses below the ceiling are ordinary raises and never reach the gesture`() {
        // The controller only asks for raises the ceiling refused outright: below it they are applied.
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(requestedRaw = 17, currentRaw = 16, ceilingRaw = 17, overrideActive = false)).isFalse()
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(requestedRaw = 18, currentRaw = 17, ceilingRaw = 17, overrideActive = false)).isTrue()
        // Nor with an override standing, nor without a ceiling.
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(requestedRaw = 19, currentRaw = 18, ceilingRaw = 17, overrideActive = true)).isFalse()
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(requestedRaw = 18, currentRaw = 17, ceilingRaw = null, overrideActive = false)).isFalse()
    }

    // ---------------------------------------------------------------- where it goes

    @Test
    fun `on the appliance with its activity not resumed, which is the screen off, the screen is woken and the dialog follows`() {
        // The bug of 2026-10-07: with the screen off the activity is stopped even under Lock Task, so
        // "its activity is resumed" was never true and the appliance always fell to ON_UNLOCK.
        assertThat(delivery(dedicated = true, hostResumed = false)).isEqualTo(Delivery.WAKE_THEN_SHOW)
    }

    @Test
    fun `on the appliance with its activity already resumed the dialog is shown at once`() {
        assertThat(delivery(dedicated = true, hostResumed = true)).isEqualTo(Delivery.OVER_LOCK_SCREEN)
    }

    @Test
    fun `on a normal phone the screen is never woken, resumed activity or not`() {
        assertThat(delivery(dedicated = false, hostResumed = false)).isEqualTo(Delivery.ON_UNLOCK)
        assertThat(delivery(dedicated = false, hostResumed = true)).isEqualTo(Delivery.ON_UNLOCK)
    }

    @Test
    fun `nothing is asked when Safety Max is not protecting or an override already stands`() {
        for (dedicated in listOf(true, false)) for (resumed in listOf(true, false)) {
            assertThat(delivery(dedicated, resumed, protectionActive = false)).isEqualTo(Delivery.NONE)
            assertThat(delivery(dedicated, resumed, overrideActive = true)).isEqualTo(Delivery.NONE)
        }
    }

    @Test
    fun `the decision depends on the device and not on the activity, except to show at once`() {
        // Over every combination: dedicated decides whether the screen is woken at all; the activity only
        // decides whether it is already there to draw (now) or has to be waited for (then).
        for (resumed in listOf(true, false)) {
            assertThat(delivery(dedicated = true, hostResumed = resumed)).isIn(listOf(Delivery.OVER_LOCK_SCREEN, Delivery.WAKE_THEN_SHOW))
            assertThat(delivery(dedicated = false, hostResumed = resumed)).isEqualTo(Delivery.ON_UNLOCK)
        }
    }

    private fun delivery(
        dedicated: Boolean,
        hostResumed: Boolean,
        protectionActive: Boolean = true,
        overrideActive: Boolean = false,
    ) = ScreenOffOverridePolicy.delivery(protectionActive, overrideActive, dedicated, hostResumed)

    // ---------------------------------------------------------------- the grace

    private fun comesUp(
        delivery: Delivery,
        sinceMs: Long = 100_000L,
        nowMs: Long = 100_500L,
        protectionActive: Boolean = true,
        overrideActive: Boolean = false,
        level: Int = 17,
        ceiling: Int? = 17,
    ) = ScreenOffOverridePolicy.showWhenAppComesUp(delivery, sinceMs, nowMs, protectionActive, overrideActive, level, ceiling)

    @Test
    fun `after the wake the activity has ten seconds to come up, counted from the wake`() {
        assertThat(ScreenOffOverridePolicy.graceMs(Delivery.WAKE_THEN_SHOW)).isEqualTo(10_000L)
        assertThat(comesUp(Delivery.WAKE_THEN_SHOW, sinceMs = 100_000L, nowMs = 100_300L)).isTrue() // a normal resume
        assertThat(comesUp(Delivery.WAKE_THEN_SHOW, sinceMs = 100_000L, nowMs = 110_000L)).isTrue()
        assertThat(comesUp(Delivery.WAKE_THEN_SHOW, sinceMs = 100_000L, nowMs = 110_001L)).isFalse()
    }

    @Test
    fun `the grace of a wake does not start at the fifth press but at the wake`() {
        // The press was a minute ago, the screen was woken just now: the clock is the wake's.
        val pressAt = 40_000L
        val wakeAt = 100_000L
        assertThat(comesUp(Delivery.WAKE_THEN_SHOW, sinceMs = pressAt, nowMs = wakeAt + 300L)).isFalse()
        assertThat(comesUp(Delivery.WAKE_THEN_SHOW, sinceMs = wakeAt, nowMs = wakeAt + 300L)).isTrue()
    }

    @Test
    fun `on a phone an unlock within thirty seconds shows it, and the twelve seconds that used to be lost now count`() {
        assertThat(ScreenOffOverridePolicy.graceMs(Delivery.ON_UNLOCK)).isEqualTo(30_000L)
        assertThat(comesUp(Delivery.ON_UNLOCK, sinceMs = 100_000L, nowMs = 112_000L)).isTrue()
        assertThat(comesUp(Delivery.ON_UNLOCK, sinceMs = 100_000L, nowMs = 130_000L)).isTrue()
        assertThat(comesUp(Delivery.ON_UNLOCK, sinceMs = 100_000L, nowMs = 130_001L)).isFalse()
    }

    @Test
    fun `nothing pending means nothing to show, and the immediate kinds have no grace`() {
        assertThat(comesUp(Delivery.ON_UNLOCK, sinceMs = 0L)).isFalse()
        assertThat(comesUp(Delivery.WAKE_THEN_SHOW, sinceMs = 0L)).isFalse()
        assertThat(comesUp(Delivery.OVER_LOCK_SCREEN)).isFalse()
        assertThat(comesUp(Delivery.NONE)).isFalse()
    }

    @Test
    fun `either way it is shown only if the reason still holds`() {
        for (kind in listOf(Delivery.WAKE_THEN_SHOW, Delivery.ON_UNLOCK)) {
            assertThat(comesUp(kind, level = 16)).isFalse() // lowered meanwhile
            assertThat(comesUp(kind, overrideActive = true)).isFalse() // already overridden
            assertThat(comesUp(kind, protectionActive = false)).isFalse()
            assertThat(comesUp(kind, ceiling = null)).isFalse()
            assertThat(comesUp(kind)).isTrue()
        }
    }

    // ---------------------------------------------------------------- safety

    @Test
    fun `five refused presses leave the level at the ceiling, only the confirmation lifts it`() {
        val backend = VolumeBackendSnapshot(
            SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, "dac:x", "DAC", rawLevel = 17, rawMin = 0, rawMax = 30, controllable = true,
        )
        var level = 17
        val g = gesture()
        var asked = false
        repeat(5) { i ->
            val refused = SafetyVolumePolicy.raiseRefusedByCeiling(level + 1, level, 17, overrideActive = false)
            assertThat(refused).isTrue()
            // What the refused branch does: nothing is written. And were it written, the clamp holds.
            assertThat(SafetyVolumePolicy.requestedRaw(level + 1, backend, 17, overrideActive = false)).isEqualTo(17)
            if (ScreenOffOverridePolicy.countsAsPress(singleStepRaise = true, screenOffOrLocked = true) &&
                g.blockedVolumeUp(i * 500L)
            ) {
                asked = true
            }
        }
        assertThat(asked).isTrue()
        assertThat(level).isEqualTo(17)
        // Only with the override (the confirmation's button) does the same request go above it.
        assertThat(SafetyVolumePolicy.requestedRaw(18, backend, 17, overrideActive = true)).isEqualTo(18)
    }
}
