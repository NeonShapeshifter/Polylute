package com.lostf1sh.pixelplayeross.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * B57 4.2: artwork is requested at the **real size of the cell it is drawn in**, in pixels (dp x density), not at a fixed 128 or 256 whatever the density or the grid. The
 * Albums grid asked for 256 px for cells of ~490 px on a tester's phone, so the covers were soft. One helper, one cap: [CAP_PX] keeps B13's budget (a bigger request costs decode time
 * and memory in a fling), and [MIN_PX] keeps a tiny cell from asking for a thumbnail that would be soft if the cell grows.
 */
object ArtworkRequestSize {
    const val CAP_PX = 512
    const val MIN_PX = 64

    /** The side, in pixels, to request for a square cell of [cellDp] at [density] (pixels per dp). */
    fun sidePx(cellDp: Float, density: Float): Int = (cellDp * density).let { if (it.isNaN()) MIN_PX else it.toInt() }.coerceIn(MIN_PX, CAP_PX)

    /** The side of one cell of a row of [columns] equal cells that share the window's width after [horizontalPadding] (both sides together) and [gap] between cells. */
    fun columnCellDp(screenWidthDp: Float, columns: Int, horizontalPadding: Float, gap: Float): Float =
        ((screenWidthDp - horizontalPadding - gap * (columns - 1)) / columns.coerceAtLeast(1)).coerceAtLeast(0f)

    /** The size for a cell of a [columns]-column grid across the window: what the cells measure, not a fixed number. */
    @Composable
    fun forColumns(columns: Int, horizontalPadding: Dp, gap: Dp): coil.size.Size {
        val density = LocalDensity.current.density
        val screenWidthDp = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width / density
        return remember(columns, horizontalPadding, gap, screenWidthDp, density) {
            sidePx(columnCellDp(screenWidthDp, columns, horizontalPadding.value, gap.value), density).let { coil.size.Size(it, it) }
        }
    }

    /** The size to hand to the image loader for a square cell of [cell]. */
    @Composable
    fun forCell(cell: Dp): coil.size.Size {
        val density = LocalDensity.current.density
        return remember(cell, density) { sidePx(cell.value, density).let { coil.size.Size(it, it) } }
    }
}
