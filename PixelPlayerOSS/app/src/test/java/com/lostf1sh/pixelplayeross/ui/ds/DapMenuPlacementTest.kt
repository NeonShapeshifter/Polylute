package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** B56 phase 2 (#42): where the one overflow menu goes. The owner's complaint was a menu stuck to the right edge and covering what lies beside the button. */
class DapMenuPlacementTest {
    private val window = IntSize(720, 1610)
    private val menu = IntSize(560, 400)
    private val edge = 24

    @Test
    fun `a button in the right half opens the menu under it, aligned to the button's right edge`() {
        val button = IntRect(left = 560, top = 80, right = 656, bottom = 176)      // the Library header's overflow button, 16 px from the right edge

        val at = DapMenuPlacement.place(button, window, menu, edge)

        assertThat(at.y).isEqualTo(button.bottom)                                  // under the button
        assertThat(at.x + menu.width).isEqualTo(button.right)                      // right edges meet
    }

    @Test
    fun `a button so close to the edge that the menu would touch it leaves the margin`() {
        val button = IntRect(left = 640, top = 80, right = 712, bottom = 152)      // 8 px from the right edge: the old menu sat flush

        val at = DapMenuPlacement.place(button, window, menu, edge)

        assertThat(at.x + menu.width).isAtMost(window.width - edge)
        assertThat(at.x).isAtLeast(edge)
    }

    @Test
    fun `a button in the left half opens the menu from the button's left edge`() {
        val button = IntRect(left = 40, top = 80, right = 136, bottom = 176)

        val at = DapMenuPlacement.place(button, window, menu, edge)

        assertThat(at).isEqualTo(IntOffset(button.left, button.bottom))
    }

    @Test
    fun `a button at the very left still leaves the margin`() {
        val button = IntRect(left = 0, top = 80, right = 96, bottom = 176)

        assertThat(DapMenuPlacement.place(button, window, menu, edge).x).isEqualTo(edge)
    }

    @Test
    fun `with no room under the button the menu opens above it`() {
        val button = IntRect(left = 560, top = 1400, right = 656, bottom = 1496)

        val at = DapMenuPlacement.place(button, window, menu, edge)

        assertThat(at.y + menu.height).isEqualTo(button.top)
    }

    @Test
    fun `a menu taller than the window is held inside its margins`() {
        val tall = IntSize(560, 1700)

        val at = DapMenuPlacement.place(IntRect(560, 80, 656, 176), window, tall, edge)

        assertThat(at.y).isAtLeast(edge)
    }

    @Test
    fun `a menu wider than the window minus the margins starts at the margin`() {
        val wide = IntSize(700, 300)

        assertThat(DapMenuPlacement.place(IntRect(560, 80, 656, 176), window, wide, edge).x).isEqualTo(edge)
    }
}
