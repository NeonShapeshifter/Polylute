package com.lostf1sh.pixelplayeross.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B25-S6: in dedicated mode the system's Back gesture is not there (Lock Task), so the app's own edge gesture is the only
 * one, and it only existed on the left edge ("works left to right, not right to left"). It now works from both sides, with
 * one set of thresholds and refusals, mirrored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class RightEdgeBackGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private var backs = 0
    private val edges = mutableListOf<BackEdge>()
    private val progress = mutableListOf<Float>()

    private fun setContent() = compose.setContent {
        Box(
            Modifier
                .fillMaxSize()
                .dapEdgeBackGesture(
                    enabled = true,
                    onBack = { backs++ },
                    onProgress = { progress += it },
                    onEdge = { edges += it },
                )
                .testTag("root")
        )
    }

    private fun swipe(from: (width: Float) -> Float, dx: Float, dy: Float = 0f, steps: Int = 8) {
        compose.onNodeWithTag("root").performTouchInput {
            val start = Offset(from(width.toFloat()), height / 2f)
            down(start)
            repeat(steps) { moveBy(Offset(dx / steps, dy / steps), delayMillis = 8) }
            up()
        }
        compose.waitForIdle()
    }

    // 22 dp at 3x (xxhdpi) is 66 px; 40 dp is 120 px.

    @Test
    fun `a swipe inward from the left edge goes back`() {
        setContent()
        swipe(from = { 6f }, dx = 200f)
        assertThat(backs).isEqualTo(1)
        assertThat(edges).containsExactly(BackEdge.LEFT)
    }

    @Test
    fun `a swipe inward from the right edge goes back, and is told to be on the right`() {
        setContent()
        swipe(from = { it - 6f }, dx = -200f)
        assertThat(backs).isEqualTo(1)
        assertThat(edges).containsExactly(BackEdge.RIGHT)
        assertThat(progress.max()).isGreaterThan(0f)
        assertThat(progress.last()).isEqualTo(0f)
    }

    @Test
    fun `a swipe outward from the right edge, or from the left, is not Back`() {
        setContent()
        swipe(from = { it - 6f }, dx = 200f)
        swipe(from = { 6f }, dx = -200f)
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `a short drag from the right edge that does not reach the distance is not Back`() {
        setContent()
        swipe(from = { it - 6f }, dx = -60f)
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `a vertical scroll that starts at the right edge is released, not claimed`() {
        setContent()
        swipe(from = { it - 6f }, dx = -4f, dy = 300f)
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `a leftward drag that starts away from the edge is never Back`() {
        setContent()
        swipe(from = { it / 2f }, dx = -250f)
        swipe(from = { it - 120f }, dx = -250f)
        assertThat(backs).isEqualTo(0)
        assertThat(edges).isEmpty()
    }

    @Test
    fun `the left and the right edge are two strips of the same width, and the middle is neither`() {
        val strip = 66f
        assertThat(EdgeBackGesturePolicy.edgeAt(0f, 1080f, strip)).isEqualTo(BackEdge.LEFT)
        assertThat(EdgeBackGesturePolicy.edgeAt(66f, 1080f, strip)).isEqualTo(BackEdge.LEFT)
        assertThat(EdgeBackGesturePolicy.edgeAt(67f, 1080f, strip)).isNull()
        assertThat(EdgeBackGesturePolicy.edgeAt(1013f, 1080f, strip)).isNull()
        assertThat(EdgeBackGesturePolicy.edgeAt(1014f, 1080f, strip)).isEqualTo(BackEdge.RIGHT)
        assertThat(EdgeBackGesturePolicy.edgeAt(1080f, 1080f, strip)).isEqualTo(BackEdge.RIGHT)
    }

    @Test
    fun `inward is rightward on the left edge and leftward on the right edge`() {
        assertThat(BackEdge.LEFT.inward(12f)).isEqualTo(12f)
        assertThat(BackEdge.RIGHT.inward(12f)).isEqualTo(-12f)
        assertThat(BackEdge.RIGHT.inward(-12f)).isEqualTo(12f)
    }

    @Test
    fun `a gesture mirrored from the right edge reaches the same decisions as the left one`() {
        fun run(edge: BackEdge, sign: Float): List<EdgeBackDecision> {
            val policy = EdgeBackGesturePolicy(edgeWidthPx = 44f, activationDistancePx = 80f, slopPx = 8f)
            return listOf(12f, 30f, 50f).mapIndexed { i, dx ->
                policy.onMove(edge.inward(sign * dx), 0f, previousUptimeMillis = i * 16L, uptimeMillis = (i + 1) * 16L)
            }
        }
        assertThat(run(BackEdge.RIGHT, -1f)).isEqualTo(run(BackEdge.LEFT, 1f))
        assertThat(run(BackEdge.RIGHT, -1f).last()).isEqualTo(EdgeBackDecision.TRIGGERED)
    }
}
