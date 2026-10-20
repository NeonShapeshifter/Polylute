package com.lostf1sh.pixelplayeross.data.volume

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The readout's timing.
 *
 * The interesting property is that repeated presses *extend* one readout rather than starting a
 * second, and that the readout is timed from the press rather than from whenever the shell got
 * around to reacting to it.
 */
class VolumeHudPolicyTest {

    @Test
    fun `no press means no readout`() {
        assertFalse(VolumeHudPolicy.isVisible(lastInteractionAtMs = 0L, nowMs = 1_000L))
        assertEquals(0L, VolumeHudPolicy.remainingMillis(lastInteractionAtMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun `a press shows the readout for its whole window`() {
        val pressed = 10_000L
        assertTrue(VolumeHudPolicy.isVisible(pressed, nowMs = pressed))
        assertTrue(
            VolumeHudPolicy.isVisible(
                pressed,
                nowMs = pressed + VolumeHudPolicy.VISIBLE_MILLIS - 1,
            )
        )
        assertFalse(
            VolumeHudPolicy.isVisible(pressed, nowMs = pressed + VolumeHudPolicy.VISIBLE_MILLIS)
        )
    }

    @Test
    fun `a repeated press extends the readout rather than restarting the count`() {
        val firstPress = 10_000L
        // Half a window later the user presses again. From the second press the readout has a full
        // window again, so it is still up at a moment when the first press alone would have expired.
        val secondPress = firstPress + VolumeHudPolicy.VISIBLE_MILLIS / 2

        assertTrue(VolumeHudPolicy.isVisible(secondPress, nowMs = secondPress))
        assertTrue(
            VolumeHudPolicy.isVisible(
                secondPress,
                nowMs = firstPress + VolumeHudPolicy.VISIBLE_MILLIS + 1,
            )
        )
    }

    @Test
    fun `the remaining time is measured from the press, not from when it was observed`() {
        val pressed = 10_000L
        // A shell that reacted 400 ms late still only shows the readout for the 800 ms it has left,
        // rather than starting a fresh window for a press that is already partly spent.
        assertEquals(
            800L,
            VolumeHudPolicy.remainingMillis(pressed, nowMs = pressed + 400L),
        )
        assertEquals(
            0L,
            VolumeHudPolicy.remainingMillis(pressed, nowMs = pressed + 5_000L),
        )
    }
}
