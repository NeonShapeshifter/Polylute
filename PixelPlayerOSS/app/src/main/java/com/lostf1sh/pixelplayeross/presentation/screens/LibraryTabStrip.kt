package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTabUnderline

/**
 * The Library's tab strip (B42-fix1, item 3): Songs, Albums, Artists... in a row that scrolls sideways, **following the selected tab**.
 *
 * It used to be a bare `Row(horizontalScroll(rememberScrollState()))` that nothing ever scrolled. With eight tabs it is wider than the
 * phone, so swiping the pages one after the other walked the selection off the end of the strip: the underline was on a tab nobody could
 * see, and the strip looked as if it had lost its tab ("the tab bar hides, because it doesn't follow the current page"). Now every change
 * of [selectedIndex], from a swipe of the pages or a tap, brings that tab fully into view, moving the strip as little as it takes.
 *
 * Where the strip is when the Library opens is not animated: it opens already showing the selected tab.
 */
@Composable
internal fun LibraryTabStrip(
    labels: List<String>,
    selectedIndex: Int,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollSpec: AnimationSpec<Float> = LibraryTabStripScroll.DefaultSpec,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val startPaddingPx = with(density) { (DapSpacing.Gutter - DapSpacing.Xs).roundToPx() }
    val marginPx = with(density) { DapSpacing.Gutter.roundToPx() }
    // each tab's width, padding included, as laid out; the strip's own width
    val widths = remember(labels.size) { mutableStateListOf<Int>().apply { repeat(labels.size) { add(0) } } }
    var viewport by remember { mutableIntStateOf(0) }
    var placed by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { viewport = it.width }
            .horizontalScroll(scroll)
            .padding(start = DapSpacing.Gutter - DapSpacing.Xs, end = DapSpacing.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            DapTabUnderline(
                label = label,
                selected = selectedIndex == index,
                onClick = { onTabClick(index) },
                modifier = Modifier
                    .onSizeChanged { if (index < widths.size) widths[index] = it.width }
                    .padding(horizontal = DapSpacing.Sm),
            )
        }
    }

    // Follows the selection, and also what is measured after it (the first layout, a rotation, a language that makes the labels longer).
    LaunchedEffect(selectedIndex, labels.size) {
        snapshotFlow { Triple(viewport, widths.toList(), selectedIndex) }.collect { (view, all, selected) ->
            val width = all.getOrNull(selected) ?: return@collect
            if (view <= 0 || width <= 0) return@collect
            val start = startPaddingPx + all.take(selected).sum()
            val target = LibraryTabStripScroll.target(
                current = scroll.value,
                itemStart = start,
                itemEnd = start + width,
                viewport = view,
                margin = marginPx,
            )
            if (target != scroll.value) {
                if (placed) scroll.animateScrollTo(target, scrollSpec) else scroll.scrollTo(target)
            }
            placed = true
        }
    }
}

/** Where the strip has to be so that a tab is fully in view: the arithmetic, apart from the composable, so it can be pinned. */
internal object LibraryTabStripScroll {
    val DefaultSpec: AnimationSpec<Float> = androidx.compose.animation.core.tween(
        durationMillis = 180,
        easing = androidx.compose.animation.core.FastOutSlowInEasing,
    )

    /**
     * The scroll position that shows the tab spanning [itemStart, itemEnd) (in the strip's own scrolling coordinates) completely, with
     * [margin] to spare on the side it was cut off, moving as little as possible from [current]: unchanged if it is already in view.
     * A tab wider than the strip shows its start.
     */
    fun target(current: Int, itemStart: Int, itemEnd: Int, viewport: Int, margin: Int): Int {
        if (viewport <= 0) return current.coerceAtLeast(0)
        val shownStart = current + margin
        val shownEnd = current + viewport - margin
        val wanted = when {
            itemEnd - itemStart >= shownEnd - shownStart -> itemStart - margin
            itemStart < shownStart -> itemStart - margin
            itemEnd > shownEnd -> itemEnd + margin - viewport
            else -> current
        }
        return wanted.coerceAtLeast(0)
    }
}
