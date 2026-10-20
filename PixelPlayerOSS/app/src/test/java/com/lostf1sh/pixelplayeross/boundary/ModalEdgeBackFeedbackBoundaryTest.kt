package com.lostf1sh.pixelplayeross.boundary

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.components.LeftEdgeCentrePopupPositionProvider
import com.lostf1sh.pixelplayeross.presentation.navigation.EdgeBackGesturePolicy
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The edge-Back indicator inside app-owned modal windows.
 *
 * The feedback is drawn in the modal's own window by the composable every modal already mounts
 * ([com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge]), from progress the modal
 * window's touch path publishes out of the same policy the activity uses. These assertions pin the
 * four things that make that safe: the policy is shared rather than re-implemented, the feedback
 * decides nothing, Buttons mode cannot produce it, and no threshold arithmetic was copied.
 */
class ModalEdgeBackFeedbackBoundaryTest {

    private val bridge: String by lazy { source("presentation/components/ModalInputBridge.kt") }

    /**
     * Comments removed, so an assertion is about code and not about the prose that explains it.
     *
     * Both forms: the file's KDoc discusses the very things these assertions are about — it explains
     * why the modal path is not a `pointerInput`, for instance — and a test that tripped over its own
     * documentation would be testing the documentation.
     */
    private fun code(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private val bridgeCode: String by lazy { code(bridge) }

    // ---- The policy is shared, not re-implemented ------------------------------------------------

    @Test
    fun `the modal path runs the app's one edge gesture policy`() {
        assertThat(bridgeCode).contains("EdgeBackGesturePolicy(")
        // Still the single declaration, and still the single source of its rules.
        assertThat(source("presentation/navigation/DapEdgeBackGesture.kt"))
            .contains("class EdgeBackGesturePolicy(")
    }

    @Test
    fun `no second gesture recognizer was introduced`() {
        // The bridge is the same recognizer the activity uses, mounted in a second window. A new
        // recognizer would be a new policy class, or a Compose pointerInput in the modal path.
        val recognizers = allSources()
            .lines()
            .filter { it.contains("class ") && it.contains("GesturePolicy") }
        assertThat(recognizers).hasSize(1)
        assertThat(bridgeCode).doesNotContain("pointerInput")
        assertThat(bridgeCode).doesNotContain("detectHorizontalDragGestures")
    }

    // ---- No threshold arithmetic was copied ------------------------------------------------------

    @Test
    fun `the modal path repeats no threshold, slop or velocity rule`() {
        // Travel is summed from the same deltas the policy is handed, and normalised by the same
        // published constant. Nothing else about the rule is restated here.
        // B25-S6: both edges, so the x the policy reads is the inward one (leftward from the right edge).
        assertThat(bridgeCode).contains("travelledX += edge.inward(event.x) - lastX")
        assertThat(bridgeCode).contains("EdgeBackGesturePolicy.EdgeWidth")
        assertThat(bridgeCode).contains("activationDistancePx")
        // The velocity rule and the slop comparison live in the policy and nowhere else.
        assertThat(bridgeCode).doesNotContain("VELOCITY_PX_PER_SECOND")
        assertThat(bridgeCode).doesNotContain("1200f")
        assertThat(bridgeCode).doesNotContain("touchSlop >")
        assertThat(bridgeCode).doesNotContain("abs(travelledY)")
    }

    // ---- The feedback is visual only -------------------------------------------------------------

    @Test
    fun `progress is published, never consulted`() {
        // Every mention of the travel value is either a reset, the accumulation, or the division
        // that produces the visual fraction. None of them is a condition, so no decision — claim,
        // trigger, or pass through — can turn on it.
        val travelLines = bridgeCode.lines().filter { it.contains("travelledX") }
        assertThat(travelLines).isNotEmpty()
        travelLines.forEach { line ->
            assertThat(line).doesNotContain("if (")
            assertThat(line).doesNotContain("return")
        }
        assertThat(travelLines.any { it.contains("travelledX / activationDistancePx") }).isTrue()

        // It is written on the way out and read only to draw. The single place the drawn value is
        // consulted decides whether to compose the feedback at all — a rendering choice, which
        // cannot make Back happen or stop it happening.
        assertThat(bridgeCode).contains("onProgress(")
        assertThat(bridgeCode).contains("EdgeBackGestureIndicator(progress = gestureProgress, edge = gestureEdge)")

        // Back itself is still the policy's decision, performed from the branch the policy returned.
        assertThat(bridgeCode).contains("EdgeBackDecision.TRIGGERED -> {")
        assertThat(bridgeCode).contains("performWindowBack()")
    }

    @Test
    fun `the indicator is the same composable the activity draws`() {
        assertThat(bridgeCode).contains("EdgeBackGestureIndicator(")
        assertThat(source("MainActivity.kt")).contains("EdgeBackGestureIndicator(")
        // One implementation, so the two windows cannot drift apart.
        val declarations = allSources().lines().filter { it.contains("fun EdgeBackGestureIndicator(") }
        assertThat(declarations).hasSize(1)
    }

    @Test
    fun `it is drawn in the modal's own window, not by a window manager overlay`() {
        assertThat(bridgeCode).contains("Popup(")
        assertThat(bridgeCode).doesNotContain("WindowManager")
        assertThat(bridgeCode).doesNotContain("TYPE_APPLICATION_OVERLAY")
        assertThat(bridgeCode).doesNotContain("TYPE_APPLICATION")
        // Non-focusable, so the feedback cannot take focus from the sheet it is floating over.
        assertThat(bridgeCode).contains("PopupProperties(focusable = false)")
        // And it exists only while the gesture does: a permanent popup would sit under the very
        // pixels the edge gesture starts from.
        assertThat(bridgeCode).contains("if (gestureProgress > 0.001f)")
    }

    // ---- Buttons mode cannot publish feedback ----------------------------------------------------

    @Test
    fun `Buttons mode cannot produce feedback in a modal`() {
        // The touch path returns before any policy runs, so no progress is ever published and the
        // indicator is never composed. The gate is the same preference the activity's gesture reads.
        assertThat(bridgeCode).contains("if (!backGestureEnabled) return delegate.dispatchTouchEvent(event)")
        assertThat(bridgeCode).contains("backNavigationModeFlow")
    }

    // ---- Placement -------------------------------------------------------------------------------

    @Test
    fun `the indicator sits against the left edge, vertically centred`() {
        val position = LeftEdgeCentrePopupPositionProvider.calculatePosition(
            anchorBounds = IntRect(0, 0, 720, 400),
            windowSize = IntSize(720, 1610),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(40, 40),
        )

        // x = 0 is the physical left edge, which is where the gesture's strip is — measured from the
        // window's left rather than from the layout direction's start.
        assertThat(position).isEqualTo(IntOffset(x = 0, y = (1610 - 40) / 2))
    }

    @Test
    fun `placement does not depend on where the modal is anchored`() {
        val a = LeftEdgeCentrePopupPositionProvider.calculatePosition(
            anchorBounds = IntRect(0, 0, 720, 200),
            windowSize = IntSize(720, 1610),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(40, 40),
        )
        val b = LeftEdgeCentrePopupPositionProvider.calculatePosition(
            anchorBounds = IntRect(100, 900, 700, 1200),
            windowSize = IntSize(720, 1610),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(40, 40),
        )

        assertThat(a).isEqualTo(b)
    }

    private fun source(relative: String) =
        root.resolve("src/main/java/com/lostf1sh/pixelplayeross/$relative").readText()

    private fun allSources(): String =
        root.resolve("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }

    private companion object {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
