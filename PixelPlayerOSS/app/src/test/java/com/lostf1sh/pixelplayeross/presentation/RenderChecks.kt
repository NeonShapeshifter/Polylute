package com.lostf1sh.pixelplayeross.presentation

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.text.TextLayoutResult

/**
 * B22: what "every frame fits 360 dp" means on the JVM, asserted the same way for every screen.
 *
 * Two things can go wrong when a longer translation meets a narrow window, and both are visible in the semantics tree
 * of a render:
 *
 *  - a node is laid out **past the window's edge** (a segmented control wider than its row, a status line pushed out
 *    by its label): its bounds leave `[0, width]`;
 *  - a text is laid out with **visual overflow** where it was not allowed to be cut: the label lost its tail.
 *
 * A text that is *meant* to end in an ellipsis (a long song title) opts out with [mayEllipsize].
 */
object RenderChecks {

    private fun walk(node: SemanticsNode, visit: (SemanticsNode) -> Unit) {
        visit(node)
        node.children.forEach { walk(it, visit) }
    }

    private fun textsOf(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    /** The screen's own tree and, when a sheet or a dialog is open, that window's tree as well. */
    private fun roots(compose: ComposeContentTestRule): List<SemanticsNode> =
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()

    /** Every node's bounds stay inside the window: nothing is placed past the left or right edge. */
    fun assertInsideWindow(compose: ComposeContentTestRule, label: String) {
        val offenders = mutableListOf<String>()
        roots(compose).forEach { root ->
            val width = root.size.width.toFloat()
            walk(root) { node ->
                val b = node.boundsInRoot
                // A node that has not been laid out (zero size) is not an offender.
                if (node.size.width > 0 && (b.left < -1f || b.right > width + 1f)) {
                    offenders += "${textsOf(node).firstOrNull() ?: node.id} [${b.left}, ${b.right}] of $width"
                }
            }
        }
        check(offenders.isEmpty()) { "$label: laid out past the window's edge: $offenders" }
    }

    /**
     * No text is cut short, except those [mayEllipsize] names. A text that fits all its lines has no ellipsized line;
     * one that lost its tail has. (`hasVisualOverflow` is not used: Robolectric's text metrics make it true for text
     * that fits.)
     */
    fun assertNoCutText(
        compose: ComposeContentTestRule,
        label: String,
        mayEllipsize: (String) -> Boolean = { false },
    ) {
        val offenders = mutableListOf<String>()
        roots(compose).forEach { root ->
            walk(root) { node ->
                val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@walk
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                val text = textsOf(node).firstOrNull().orEmpty()
                val cut = results.any { r -> (0 until r.lineCount).any { r.isLineEllipsized(it) } }
                if (cut && !mayEllipsize(text)) offenders += text
            }
        }
        check(offenders.isEmpty()) { "$label: text cut short: $offenders" }
    }
}
