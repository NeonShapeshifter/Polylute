package com.lostf1sh.pixelplayeross.presentation.lockscreen

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B29-fix1 F1: what the release reads from the finger's own positions, as a pure value (px and ms, negative = up).
 */
class FingerTraceTest {

    @Test
    fun `a lift where the last drag event was carried nothing the detector missed`() {
        val trace = FingerTrace()
        trace.onDown(800f, 0)
        trace.onDrag(500f)
        trace.onLift(500f, 100)
        assertThat(trace.unreportedDy()).isEqualTo(0f)
    }

    @Test
    fun `a lift far past the last drag event carries the rest of the travel`() {
        val trace = FingerTrace()
        trace.onDown(800f, 0)
        trace.onDrag(710f)
        trace.onLift(350f, 107)
        assertThat(trace.unreportedDy()).isEqualTo(-360f)
    }

    @Test
    fun `the speed is the finger's net travel over the whole gesture`() {
        val trace = FingerTrace()
        trace.onDown(800f, 0)
        trace.onDrag(710f)
        trace.onLift(350f, 100)
        assertThat(trace.averageVelocityY()).isWithin(0.01f).of(-4_500f)
    }

    @Test
    fun `nothing is reported before a lift, or for a gesture with no span`() {
        val trace = FingerTrace()
        trace.onDown(800f, 50)
        trace.onDrag(700f)
        assertThat(trace.unreportedDy()).isEqualTo(0f)
        assertThat(trace.averageVelocityY()).isEqualTo(0f)
        trace.onLift(600f, 50)
        assertThat(trace.averageVelocityY()).isEqualTo(0f)
    }

    @Test
    fun `a new touch-down forgets the last gesture`() {
        val trace = FingerTrace()
        trace.onDown(800f, 0)
        trace.onDrag(700f)
        trace.onLift(300f, 90)
        trace.onDown(900f, 1_000)
        assertThat(trace.unreportedDy()).isEqualTo(0f)
        assertThat(trace.averageVelocityY()).isEqualTo(0f)
    }
}
