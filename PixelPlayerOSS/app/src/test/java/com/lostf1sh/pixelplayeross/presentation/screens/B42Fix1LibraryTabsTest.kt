package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42-fix1, item 3: "in Library, if you swipe inside, one by one, many times, the tab bar at the top hides, because it doesn't follow the
 * current page."
 *
 * The cause was not the page-to-tab mapping (it is exact; the first group of tests pins that) but the strip itself: a `Row(horizontalScroll)`
 * that nothing ever scrolled, eight tabs wide on a phone that shows five. Swiping the pages moved the selection onto tabs that were out of the
 * strip's view. These tests drive the real [HorizontalPager] with real swipes, in the strip's own harness, and ask after **every** swipe that the
 * selected tab is the page's tab and is wholly inside the strip.
 *
 * The same assertions, against the strip as it was (`B42Fix1LibraryStripBeforeProbe`, in `B42-fix1-evidence/before-probes/`), fail at the
 * fifth swipe.
 */
class B42Fix1LibraryTabPagesTest {
    @Test
    fun the_page_to_tab_mapping_is_exact_for_any_page_in_either_mode() {
        for (tabCount in 1..9) {
            // every page of the small range, and of both ends of the range a pager can reach
            val pages = (0..60) + (Int.MAX_VALUE - 60 until Int.MAX_VALUE) + (Int.MAX_VALUE / 2 - 30..Int.MAX_VALUE / 2 + 30)
            for (page in pages) {
                assertThat(resolveTabIndex(page, tabCount, compactMode = true)).isEqualTo((page.toLong() % tabCount).toInt())
            }
        }
        // negative pages (a wrapped index) fold to the same tab a mathematician would give
        assertThat(resolveTabIndex(-1, 8, compactMode = true)).isEqualTo(7)
        assertThat(resolveTabIndex(-8, 8, compactMode = true)).isEqualTo(0)
        assertThat(resolveTabIndex(Int.MIN_VALUE, 8, compactMode = true)).isEqualTo(0)
        assertThat(resolveTabIndex(Int.MIN_VALUE + 1, 3, compactMode = true)).isEqualTo(((Int.MIN_VALUE + 1L) % 3 + 3).toInt() % 3)
        // the finite pager: the page is the tab, held inside the strip
        assertThat(resolveTabIndex(5, 8, compactMode = false)).isEqualTo(5)
        assertThat(resolveTabIndex(99, 8, compactMode = false)).isEqualTo(7)
    }

    @Test
    fun the_infinite_pager_starts_on_the_tab_it_was_asked_for() {
        for (tabCount in 1..9) for (tab in 0 until tabCount) {
            val page = infinitePagerInitialPage(tabCount, tab)
            assertThat(resolveTabIndex(page, tabCount, compactMode = true)).isEqualTo(tab)
        }
    }

    @Test
    fun tapping_a_tab_goes_to_the_nearest_page_of_that_tab() {
        val n = 8
        val start = infinitePagerInitialPage(n, 3)
        // forward one, back one, and the wrap both ways
        assertThat(targetPageForTabIndex(start, 4, n, compactMode = true)).isEqualTo(start + 1)
        assertThat(targetPageForTabIndex(start, 2, n, compactMode = true)).isEqualTo(start - 1)
        assertThat(targetPageForTabIndex(start, 7, n, compactMode = true)).isEqualTo(start - 4) // 4 either way: a tie goes back, as it always did
        assertThat(targetPageForTabIndex(start, 0, n, compactMode = true)).isEqualTo(start - 3)
        // the finite pager: the tab is the page
        assertThat(targetPageForTabIndex(6, 2, n, compactMode = false)).isEqualTo(2)
        // always a page of that tab, whatever the start
        for (current in listOf(0, 1, 7, 8, 1_000_003, Int.MAX_VALUE / 2, Int.MAX_VALUE - 2, Int.MAX_VALUE - 1)) {
            for (tab in 0 until n) {
                val target = targetPageForTabIndex(current, tab, n, compactMode = true)
                assertThat(target).isAtLeast(0)
                assertThat(resolveTabIndex(target, n, compactMode = true)).isEqualTo(tab)
                // the nearest page of that tab, wherever there is a page on both sides of the current one
                if (current >= n && current <= Int.MAX_VALUE - 1 - n) {
                    assertThat(kotlin.math.abs(target.toLong() - current)).isAtMost(n / 2L)
                }
            }
        }
        // at either end of the range the pages that would be past it are not there: the tab's page on the side that exists
        assertThat(targetPageForTabIndex(0, 7, n, compactMode = true)).isEqualTo(7)
        assertThat(targetPageForTabIndex(Int.MAX_VALUE - 1, 0, n, compactMode = true)).isEqualTo(Int.MAX_VALUE - 1 - positiveMod(Int.MAX_VALUE - 1, n))
    }

    @Test
    fun the_scroll_target_changes_nothing_for_a_tab_already_in_view() {
        // a 360 px strip, a tab at 100..200, a margin of 24
        assertThat(LibraryTabStripScroll.target(current = 0, itemStart = 100, itemEnd = 200, viewport = 360, margin = 24)).isEqualTo(0)
        assertThat(LibraryTabStripScroll.target(current = 50, itemStart = 100, itemEnd = 200, viewport = 360, margin = 24)).isEqualTo(50)
    }

    @Test
    fun the_scroll_target_moves_just_enough_to_show_a_tab_that_is_cut_off() {
        // past the right edge: its end plus the margin lands on the strip's end
        assertThat(LibraryTabStripScroll.target(current = 0, itemStart = 400, itemEnd = 500, viewport = 360, margin = 24)).isEqualTo(500 + 24 - 360)
        // before the left edge: its start minus the margin lands on the strip's start
        assertThat(LibraryTabStripScroll.target(current = 300, itemStart = 100, itemEnd = 200, viewport = 360, margin = 24)).isEqualTo(100 - 24)
        // never before the beginning
        assertThat(LibraryTabStripScroll.target(current = 40, itemStart = 10, itemEnd = 90, viewport = 360, margin = 24)).isEqualTo(0)
        // a tab wider than the strip shows its start
        assertThat(LibraryTabStripScroll.target(current = 0, itemStart = 500, itemEnd = 1000, viewport = 360, margin = 24)).isEqualTo(500 - 24)
        // before the strip is measured, nothing moves
        assertThat(LibraryTabStripScroll.target(current = 77, itemStart = 500, itemEnd = 600, viewport = 0, margin = 24)).isEqualTo(77)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class B42Fix1LibraryStripTest {
    @get:Rule
    val compose = createComposeRule()

    private val labels = listOf("SONGS", "ALBUMS", "ARTISTS", "PLAYLISTS", "FOLDERS", "LIKED", "GENRES", "COLLECTIONS")

    /** What the harness gives the test: the pager it made (a plain field, read after composition). */
    private class Host {
        lateinit var pager: PagerState
    }

    private val host = Host()

    @Composable
    private fun Harness(compact: Boolean, startTab: Int) {
        val n = labels.size
        val pagerState = if (compact) {
            rememberPagerState(initialPage = infinitePagerInitialPage(n, startTab)) { Int.MAX_VALUE }
        } else {
            rememberPagerState(initialPage = startTab) { n }
        }
        host.pager = pagerState
        val selected by remember(pagerState) { derivedStateOf { resolveTabIndex(pagerState.currentPage, n, compact) } }
        val scope = rememberCoroutineScope()
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            Column(Modifier.width(360.dp)) {
                if (!compact) {
                    LibraryTabStrip(
                        labels = labels,
                        selectedIndex = selected,
                        onTabClick = { tab ->
                            scope.launch {
                                pagerState.animateScrollToPage(targetPageForTabIndex(pagerState.currentPage, tab, n, compact))
                            }
                        },
                        modifier = Modifier.testTag("strip"),
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("pager"),
                    beyondViewportPageCount = 1,
                    key = { it },
                ) { _ -> Box(Modifier.fillMaxSize()) }
            }
        }
    }

    private fun start(compact: Boolean = false, startTab: Int = 0) {
        // the pager's settle and the strip's scroll are animations: the clock is the test's
        compose.mainClock.autoAdvance = false
        compose.setContent { Harness(compact, startTab) }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_200)
    }

    private fun swipeLeftOnce() {
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        settle()
    }

    private fun swipeRightOnce() {
        compose.onNodeWithTag("pager").performTouchInput { swipeRight() }
        settle()
    }

    private fun tab(label: String) = compose.onNode(hasText(label))

    /**
     * Where the tab really is, **not clipped** by the strip: `boundsInRoot` is the visible part only, so a tab scrolled out of view would
     * report bounds inside the strip and every "is it in view" check would pass.
     */
    private fun boundsOf(label: String): Rect {
        val node = tab(label).fetchSemanticsNode()
        val p = node.positionInRoot
        return Rect(p.x, p.y, p.x + node.size.width, p.y + node.size.height)
    }

    private fun isSelected(label: String): Boolean =
        tab(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    private fun stripBounds(): Rect = compose.onNodeWithTag("strip").fetchSemanticsNode().boundsInRoot

    /** After a swipe: the selected tab is the page's tab, it is the only one selected, and it is wholly inside the strip. */
    private fun assertTheSelectedTabIsThePagesAndInView(step: String) {
        val expected = resolveTabIndex(host.pager.currentPage, labels.size, compactMode = false)
        val selected = labels.indices.filter { isSelected(labels[it]) }
        assertWithMessage("selected tab after $step").that(selected).containsExactly(expected)
        val strip = stripBounds()
        val shown = boundsOf(labels[expected])
        assertWithMessage("left edge of ${labels[expected]} after $step").that(shown.left).isAtLeast(strip.left - 0.5f)
        assertWithMessage("right edge of ${labels[expected]} after $step").that(shown.right).isAtMost(strip.right + 0.5f)
        // and the strip is still there, at its place, whole
        assertWithMessage("strip width after $step").that(strip.width).isWithin(1f).of(360f * 3f)
    }

    @Test
    fun fifty_swipes_forward_then_fifty_back_keep_the_selected_tab_in_view_after_every_one() {
        start()
        assertTheSelectedTabIsThePagesAndInView("start")
        repeat(50) { i ->
            swipeLeftOnce()
            assertTheSelectedTabIsThePagesAndInView("left ${i + 1}")
        }
        assertThat(host.pager.currentPage).isEqualTo(labels.size - 1)
        repeat(50) { i ->
            swipeRightOnce()
            assertTheSelectedTabIsThePagesAndInView("right ${i + 1}")
        }
        assertThat(host.pager.currentPage).isEqualTo(0)
    }

    @Test
    fun going_round_the_tabs_again_and_again_keeps_the_selected_tab_in_view() {
        start()
        repeat(4) { round ->
            repeat(labels.size - 1) { i ->
                swipeLeftOnce()
                assertTheSelectedTabIsThePagesAndInView("round $round forward ${i + 1}")
            }
            repeat(labels.size - 1) { i ->
                swipeRightOnce()
                assertTheSelectedTabIsThePagesAndInView("round $round back ${i + 1}")
            }
        }
    }

    @Test
    fun the_strip_opens_already_showing_the_tab_the_library_was_left_on() {
        start(startTab = 6)
        // no animation to wait for: it opened there
        assertTheSelectedTabIsThePagesAndInView("opened on genres")
        assertThat(isSelected("GENRES")).isTrue()
    }

    @Test
    fun a_tap_after_a_long_run_of_swipes_goes_to_that_tab_and_shows_it() {
        start()
        repeat(50) { swipeLeftOnce() }
        assertTheSelectedTabIsThePagesAndInView("end of the run")
        // a tab that is in view, three before the last one: tapped
        tab("LIKED").performClick()
        settle()
        assertThat(host.pager.currentPage).isEqualTo(5)
        assertTheSelectedTabIsThePagesAndInView("after the tap")
        assertThat(isSelected("LIKED")).isTrue()
        // and the same from the start of the strip after a run the other way
        repeat(50) { swipeRightOnce() }
        tab("ALBUMS").performClick()
        settle()
        assertThat(host.pager.currentPage).isEqualTo(1)
        assertTheSelectedTabIsThePagesAndInView("after the second tap")
    }

    @Test
    fun the_infinite_pager_follows_fifty_swipes_each_way_one_page_at_a_time() {
        start(compact = true, startTab = 2)
        val n = labels.size
        val first = host.pager.currentPage
        repeat(50) { i ->
            swipeLeftOnce()
            assertThat(host.pager.currentPage).isEqualTo(first + i + 1)
            assertThat(resolveTabIndex(host.pager.currentPage, n, compactMode = true)).isEqualTo((2 + i + 1) % n)
        }
        repeat(100) { i ->
            swipeRightOnce()
            assertThat(host.pager.currentPage).isEqualTo(first + 50 - (i + 1))
            assertThat(resolveTabIndex(host.pager.currentPage, n, compactMode = true)).isEqualTo(((2 + 50 - (i + 1)) % n + n) % n)
        }
    }
}
