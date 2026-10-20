package com.lostf1sh.pixelplayeross.presentation.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The edge-Back rule, exercised as arithmetic.
 *
 * The cases that matter are the ones that must *not* fire. A Back gesture that steals a scroll or a
 * carousel drag is worse than no Back gesture at all, so most of what is asserted here is refusal.
 */
class EdgeBackGesturePolicyTest {

    private val slop = 8f
    private val edge = 44f // 22 dp at density 2
    private val activation = 80f // 40 dp at density 2

    private fun policy() = EdgeBackGesturePolicy(
        edgeWidthPx = edge,
        activationDistancePx = activation,
        slopPx = slop,
    )

    private fun EdgeBackGesturePolicy.move(dx: Float, dy: Float, atMs: Long = 16L) =
        onMove(dx, dy, previousUptimeMillis = 0L, uptimeMillis = atMs)

    @Test
    fun `only a touch that begins in the strip is examined at all`() {
        val p = policy()
        assertTrue(p.startsAtEdge(0f))
        assertTrue(p.startsAtEdge(edge))
        assertFalse(p.startsAtEdge(edge + 1f))
    }

    @Test
    fun `a vertical scroll that starts at the edge is released, not claimed`() {
        val p = policy()
        p.startsAtEdge(0f)
        // A list whose rows begin at the left edge: the first pixels of a scroll happen here.
        assertEquals(EdgeBackDecision.IGNORED, p.move(dx = 2f, dy = 20f))
    }

    @Test
    fun `a horizontal drag from the edge is claimed and then fires past the threshold`() {
        val p = policy()
        p.startsAtEdge(4f)

        // Past the slop, mostly sideways: the gesture is ours now.
        assertEquals(EdgeBackDecision.CLAIMED, p.move(dx = 12f, dy = 1f))
        // Halfway there is still not Back.
        assertEquals(EdgeBackDecision.CLAIMED, p.move(dx = 30f, dy = 0f, atMs = 32L))
        // Past the activation distance it is.
        assertEquals(EdgeBackDecision.TRIGGERED, p.move(dx = 50f, dy = 0f, atMs = 48L))
    }

    @Test
    fun `a short, fast flick fires without travelling the full distance`() {
        val p = policy()
        p.startsAtEdge(2f)
        assertEquals(EdgeBackDecision.CLAIMED, p.move(dx = 10f, dy = 0f, atMs = 4L))
        // 30 px in 4 ms is 7500 px/s — far past the flick threshold, and well under 40 dp.
        assertEquals(EdgeBackDecision.TRIGGERED, p.move(dx = 30f, dy = 0f, atMs = 8L))
    }

    @Test
    fun `a slow short drag from the edge never fires`() {
        val p = policy()
        p.startsAtEdge(2f)
        // Half the activation distance, travelled slowly. It is claimed — the gesture is a
        // rightward drag from the edge and nothing else may act on it — but it is not Back.
        assertEquals(EdgeBackDecision.CLAIMED, p.move(dx = 12f, dy = 0f, atMs = 200L))
        assertEquals(EdgeBackDecision.CLAIMED, p.move(dx = 20f, dy = 0f, atMs = 400L))
        assertEquals(EdgeBackDecision.CLAIMED, p.move(dx = 20f, dy = 0f, atMs = 900L))
    }

    @Test
    fun `a claim is final, so a diagonal drag cannot be half a Back`() {
        val p = policy()
        p.startsAtEdge(0f)
        assertEquals(EdgeBackDecision.CLAIMED, p.move(dx = 14f, dy = 2f))
        // Once claimed, a later vertical component cannot turn it back into a scroll.
        assertEquals(EdgeBackDecision.CLAIMED, p.move(dx = 4f, dy = 40f, atMs = 32L))
    }

    @Test
    fun `a gesture beginning away from the edge is never owned`() {
        val p = policy()
        // This is the whole safety property: the policy is only ever consulted for a touch that
        // landed in the strip. A centre-screen horizontal drag — a carousel, a seek bar, a queue
        // row — never reaches the tracker, so Back cannot be what it does.
        assertFalse(p.startsAtEdge(edge + 1f))
        assertFalse(p.startsAtEdge(200f))
        assertFalse(p.startsAtEdge(719f))
    }
}
