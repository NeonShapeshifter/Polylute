package com.lostf1sh.pixelplayeross.presentation.components.player

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.components.player.QueueSwipePolicy.Intent
import org.junit.jupiter.api.Test

/** B29-L7: where the queue swipe may start and what it must look like, as pure rules. */
class QueueSwipePolicyTest {

    private val slop = 24f // 8 dp at 3 px per dp

    @Test
    fun `nothing is decided until the finger is past the touch slop`() {
        assertThat(QueueSwipePolicy.intentAfterSlop(0f, -10f, slop)).isNull()
        assertThat(QueueSwipePolicy.intentAfterSlop(15f, -15f, slop)).isNull()
        assertThat(QueueSwipePolicy.intentAfterSlop(0f, -slop, slop)).isEqualTo(Intent.QUEUE)
    }

    @Test
    fun `an upward movement that is at least twice as long as it is wide is the queue's`() {
        assertThat(QueueSwipePolicy.intentAfterSlop(0f, -40f, slop)).isEqualTo(Intent.QUEUE)
        assertThat(QueueSwipePolicy.intentAfterSlop(18f, -40f, slop)).isEqualTo(Intent.QUEUE)
        assertThat(QueueSwipePolicy.intentAfterSlop(-18f, -40f, slop)).isEqualTo(Intent.QUEUE)
        // a batched event carrying a whole fast flick decides on its first look
        assertThat(QueueSwipePolicy.intentAfterSlop(30f, -450f, slop)).isEqualTo(Intent.QUEUE)
    }

    @Test
    fun `sideways, diagonal and downward movements are somebody else's`() {
        assertThat(QueueSwipePolicy.intentAfterSlop(60f, -10f, slop)).isEqualTo(Intent.OTHER)
        assertThat(QueueSwipePolicy.intentAfterSlop(40f, -50f, slop)).isEqualTo(Intent.OTHER)
        assertThat(QueueSwipePolicy.intentAfterSlop(0f, 40f, slop)).isEqualTo(Intent.OTHER)
        assertThat(QueueSwipePolicy.intentAfterSlop(-60f, -10f, slop)).isEqualTo(Intent.OTHER)
    }

    @Test
    fun `the swipe starts from the controls' top edge down to the system's strip, and nowhere else`() {
        assertThat(QueueSwipePolicy.startsInZone(downY = 1500f, controlsTop = 1400f, bottomBoundaryY = 2300f)).isTrue()
        assertThat(QueueSwipePolicy.startsInZone(downY = 1400f, controlsTop = 1400f, bottomBoundaryY = 2300f)).isTrue()
        assertThat(QueueSwipePolicy.startsInZone(downY = 1399f, controlsTop = 1400f, bottomBoundaryY = 2300f)).isFalse()
        assertThat(QueueSwipePolicy.startsInZone(downY = 2300f, controlsTop = 1400f, bottomBoundaryY = 2300f)).isFalse()
        // the controls never laid out: no zone
        assertThat(QueueSwipePolicy.startsInZone(downY = 1500f, controlsTop = Float.NaN, bottomBoundaryY = 2300f)).isFalse()
    }
}
