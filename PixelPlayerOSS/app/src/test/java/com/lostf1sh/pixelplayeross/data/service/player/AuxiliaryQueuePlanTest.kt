package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import org.junit.jupiter.api.Test

/**
 * B17-H1: what the crossfade's auxiliary player is loaded with. The window rule itself is the engine's old one
 * (pinned first, so the refactor into a pure function cannot have changed it).
 */
class AuxiliaryQueuePlanTest {

    private val max = 200

    // --- the old rule, unchanged -----------------------------------------------------------------

    @Test
    fun `a short queue is held whole`() {
        val plan = AuxiliaryQueuePlan.build(count = 150, targetIndex = 42, playOrder = null, maxItems = max)
        assertThat(plan.windowed).isFalse()
        assertThat(plan.sourceIndices).isEqualTo(PlayOrder.identity(150))
        assertThat(plan.targetIndex).isEqualTo(42)
        assertThat(plan.auxOrder).isNull()
    }

    @Test
    fun `a long queue is windowed around the target and the window rule is the old one`() {
        val plan = AuxiliaryQueuePlan.build(count = 4870, targetIndex = 2000, playOrder = null, maxItems = max)
        assertThat(plan.windowed).isTrue()
        assertThat(plan.windowStart).isEqualTo(1900)
        assertThat(plan.sourceIndices.size).isEqualTo(200)
        assertThat(plan.sourceIndices.first()).isEqualTo(1900)
        assertThat(plan.targetIndex).isEqualTo(100)
        assertThat(plan.auxOrder).isNull()
        assertThat(plan.windowedPlayOrder).isNull()
    }

    @Test
    fun `the window is clamped at both ends of the queue`() {
        assertThat(AuxiliaryQueuePlan.windowBounds(target = 3, count = 4870, maxItems = max)).isEqualTo(0 to 200)
        assertThat(AuxiliaryQueuePlan.windowBounds(target = 4869, count = 4870, maxItems = max)).isEqualTo(4670 to 4870)
        assertThat(AuxiliaryQueuePlan.windowBounds(target = 10, count = 150, maxItems = max)).isEqualTo(0 to 150)
    }

    // --- shuffle on -------------------------------------------------------------------------------

    @Test
    fun `a short queue keeps the master's shuffle order so the next songs after the fade are the ones lined up`() {
        val order = intArrayOf(3, 0, 4, 1, 2)
        val plan = AuxiliaryQueuePlan.build(count = 5, targetIndex = 4, playOrder = order, maxItems = max)
        assertThat(plan.windowed).isFalse()
        assertThat(plan.sourceIndices).isEqualTo(PlayOrder.identity(5))
        assertThat(plan.auxOrder).isEqualTo(order)
    }

    @Test
    fun `a long shuffled queue is windowed in play order, not in the base order`() {
        val count = 4870
        val random = java.util.Random(11)
        val order = (0 until count).toMutableList().also { it.shuffle(random) }.toIntArray()
        val target = order[1500] // the song that plays at position 1500 of the shuffle
        val plan = AuxiliaryQueuePlan.build(count, target, order, max)

        assertThat(plan.windowed).isTrue()
        assertThat(plan.windowStart).isEqualTo(1400)
        // The window holds the songs the shuffle visits around the target, in the order it visits them.
        assertThat(plan.sourceIndices.toList()).isEqualTo(order.slice(1400 until 1600))
        assertThat(plan.sourceIndices[plan.targetIndex]).isEqualTo(target)
        // So the auxiliary player plays its own timeline front to back, which is the play order.
        assertThat(plan.auxOrder).isEqualTo(PlayOrder.identity(200))
        assertThat(plan.windowedPlayOrder).isEqualTo(order)
    }

    @Test
    fun `the songs that follow the target in a shuffled window are the ones the shuffle had next`() {
        val order = intArrayOf(7, 2, 9, 4, 0, 8, 1, 6, 3, 5) + IntArray(390) { 10 + it }
        val plan = AuxiliaryQueuePlan.build(count = 400, targetIndex = 9, playOrder = order, maxItems = 200)
        val following = plan.sourceIndices.drop(plan.targetIndex + 1).take(3)
        assertThat(following).containsExactly(4, 0, 8).inOrder()
    }

    @Test
    fun `a window already cut from the play order is cut again from it, as is`() {
        // The engine holds the play-ordered list as its snapshot while it is in this state; the next fade slices that list.
        val plan = AuxiliaryQueuePlan.build(
            count = 4870, targetIndex = 3000, playOrder = null, maxItems = max, sourceIsPlayOrder = true,
        )
        assertThat(plan.windowed).isTrue()
        assertThat(plan.windowStart).isEqualTo(2900)
        assertThat(plan.auxOrder).isEqualTo(PlayOrder.identity(200))
        assertThat(plan.windowedPlayOrder).isNull() // already known to the engine; nothing new to remember
    }

    @Test
    fun `a target outside a short queue is refused instead of building garbage`() {
        val failure = runCatching { AuxiliaryQueuePlan.build(count = 5, targetIndex = 9, playOrder = null, maxItems = max) }
        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}
