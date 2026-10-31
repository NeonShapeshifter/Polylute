package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import kotlin.math.abs
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B48-fix1 (item 2): the player's cover follows every change of the current item that did not come from its own swipe.
 *
 * On the HONOR (2026-10-30 00:41) a media-key skip left the cover short of the current song: the previous cover, shifted a few pixels, with the next one peeking in at the right
 * edge, for as long as the owner looked (8 s) and until a swipe. The cause is in `AlbumCarouselSection`: the effect that carries the cover to the current item is keyed on the target
 * AND on the queue, and when the queue is published again while the cover is on its way (a song's artwork or statistics change a moment after the skip) the effect restarts, finds the
 * pager already past the half way mark, concludes `currentPage == target` and stops: the animation that was cancelled is never finished. NO_PEEK hid it (its own settle correction
 * snaps the pager to the nearest page), ONE_PEEK and TWO_PEEK did not.
 *
 * These tests drive the section the way the player does (the current index changes, and the queue comes again a moment later) and read where the carousel is off its semantics, in
 * pages. A skip with nothing else happening was never broken: it is here so that it stays so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class B48Fix1CoverFollowsSkipTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(i: Int, art: String? = null) = Song(
        id = "id-$i", title = "Song $i", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$i.flac",
        contentUriString = "content://$i", albumArtUriString = art, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private var queue: ImmutableList<Song> by mutableStateOf((0 until 40).map { song(it) }.toImmutableList())

    private val selected = mutableListOf<Int>()
    private var index by mutableIntStateOf(3)
    private var pageStridePx = 1.0
    private lateinit var scrollRange: ScrollAxisRange
    private lateinit var hostView: View
    private var touchDownTime = 0L

    private fun setCarousel(style: String) {
        compose.setContent {
            hostView = LocalView.current
            Box(Modifier.width(360.dp).height(360.dp)) {
                AlbumCarouselSection(
                    currentSong = queue[index],
                    queue = queue,
                    expansionFraction = 1f,
                    currentMediaItemIndex = index,
                    onSongSelected = { _, i -> selected += i },
                    modifier = Modifier.width(360.dp).height(360.dp),
                    carouselStyle = style,
                    isOnScreen = { true },
                )
            }
        }
        compose.waitForIdle()
        // The scroll range's lambda reads the pager's live state: kept, it answers at any moment without asking the harness whether Compose is idle.
        scrollRange = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        pageStridePx = scrollRange.value().toDouble() / index
        compose.mainClock.autoAdvance = false
    }

    /** The player's side of a skip: the index changes. The recomposition it causes (and the start of whatever the carousel does about it) runs; the clock stays ours. */
    private fun changeIndexTo(page: Int) {
        index = page
        // `waitForIdle` does not come back once an animated scroll has run in this harness (the B22 tree too): the state change is handed to the recomposer by hand, and the frame it asks for is ours.
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    /**
     * A raw touch event into the host view, stamped with the main clock. `performTouchInput` waits for Compose to be idle before and after, and nothing is idle while the cover is
     * animating on a clock that is ours: a finger that lands on a cover on its way cannot be put there through it.
     */
    private fun touch(action: Int, x: Float, y: Float) {
        val now = compose.mainClock.currentTime
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now
        val event = MotionEvent.obtain(touchDownTime, now, action, x, y, 0)
        hostView.dispatchTouchEvent(event)
        event.recycle()
        compose.mainClock.advanceTimeByFrame()
    }

    private var republications = 0

    /**
     * The queue comes again a moment after a skip, as it does on the player: the song now playing is the same song with its artwork URI filled in (or its statistics moved), so the
     * list is no longer equal to the one the carousel was keyed on. It is handed to the recomposer by hand, like the index.
     */
    private fun republishTheQueue() {
        republications++
        val playing = index
        queue = queue.mapIndexed { i, s -> if (i == playing) song(i, "content://art/$republications") else s }.toImmutableList()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    /** The carousel's scroll position in pages (3.0 = at rest on the fourth). */
    private fun position(): Double = scrollRange.value().toDouble() / pageStridePx

    private fun assertAtRestOn(page: Int, why: String) {
        val at = position()
        assertThat(abs(at - page)).isLessThan(0.02)   // within a fiftieth of a page: neither the previous cover nor a half-visible next one
        assertThat(why).isNotEmpty()
    }

    /**
     * Watches the cover for [windowMs] of the main clock (a frame at a time) and says when it last moved, in virtual milliseconds from now: how long the cover took to arrive, or
     * 0 when it never moved. A fixed window and not "until it stops": a cover that has not started yet and one that has arrived both stand still.
     */
    private fun msUntilAtRest(windowMs: Long = 2_000): Long {
        var last = position()
        var lastMovedAt = 0L
        var elapsed = 0L
        val trace = StringBuilder()
        while (elapsed < windowMs) {
            compose.mainClock.advanceTimeByFrame()
            elapsed += 16
            val now = position()
            if (abs(now - last) >= 0.0005) lastMovedAt = elapsed
            last = now
            if (elapsed % 160L == 0L) trace.append(String.format("%.3f ", now))
        }
        println("trajectory (every 160 ms): $trace")
        return lastMovedAt
    }

    private fun skipFromOutsideShowsTheCurrentCover(style: String, republishAfterMs: Long?) {
        setCarousel(style)
        changeIndexTo(4)                            // a media-key skip: the index changes, nothing else is said to the carousel
        if (republishAfterMs != null) {
            compose.mainClock.advanceTimeBy(republishAfterMs)
            republishTheQueue()
        }
        val took = msUntilAtRest()
        println("a skip, $style, queue again after $republishAfterMs ms: at ${position()} after $took ms, selected=$selected")
        assertAtRestOn(4, "a media-key skip")
        assertThat(took).isAtMost(ANIMATION_BUDGET_MS)
        assertThat(selected).isEmpty()
    }

    @Test
    fun `a skip from outside brings the cover to the current item, one peek`() = skipFromOutsideShowsTheCurrentCover(CarouselStyle.ONE_PEEK, null)

    @Test
    fun `a skip from outside brings the cover to the current item, no peek`() = skipFromOutsideShowsTheCurrentCover(CarouselStyle.NO_PEEK, null)

    @Test
    fun `a skip whose queue comes again half way brings the cover to the current item, one peek`() = skipFromOutsideShowsTheCurrentCover(CarouselStyle.ONE_PEEK, 80)

    @Test
    fun `a skip whose queue comes again half way brings the cover to the current item, two peeks`() = skipFromOutsideShowsTheCurrentCover(CarouselStyle.TWO_PEEK, 80)

    @Test
    fun `a skip whose queue comes again half way brings the cover to the current item, no peek`() = skipFromOutsideShowsTheCurrentCover(CarouselStyle.NO_PEEK, 80)

    private fun burstEndsOnTheCurrentCover(style: String, gapMs: Long, republishAfterMs: Long?) {
        setCarousel(style)
        for (page in 4..13) {
            changeIndexTo(page)
            if (republishAfterMs != null) {
                compose.mainClock.advanceTimeBy(republishAfterMs)
                republishTheQueue()
            }
            compose.mainClock.advanceTimeBy((gapMs - (republishAfterMs ?: 0)).coerceAtLeast(0))   // the next key comes while the cover is still on its way
        }
        val took = msUntilAtRest()
        println("a burst, $style, $gapMs ms apart, queue again after $republishAfterMs ms: at ${position()} after $took ms, selected=$selected")
        assertAtRestOn(13, "ten media-key skips")
        assertThat(took).isAtMost(ANIMATION_BUDGET_MS)
        assertThat(selected).isEmpty()
    }

    @Test
    fun `ten fast skips end on the current cover, one peek, 100 ms apart`() = burstEndsOnTheCurrentCover(CarouselStyle.ONE_PEEK, 100, null)

    @Test
    fun `ten fast skips end on the current cover, no peek, 100 ms apart`() = burstEndsOnTheCurrentCover(CarouselStyle.NO_PEEK, 100, null)

    @Test
    fun `ten fast skips end on the current cover, one peek, 30 ms apart`() = burstEndsOnTheCurrentCover(CarouselStyle.ONE_PEEK, 30, null)

    @Test
    fun `ten fast skips each followed by the queue end on the current cover, one peek, 400 ms apart`() = burstEndsOnTheCurrentCover(CarouselStyle.ONE_PEEK, 400, 112)

    @Test
    fun `ten fast skips each followed by the queue end on the current cover, one peek, 100 ms apart`() = burstEndsOnTheCurrentCover(CarouselStyle.ONE_PEEK, 100, 48)

    @Test
    fun `ten fast skips each followed by the queue end on the current cover, two peeks, 200 ms apart`() = burstEndsOnTheCurrentCover(CarouselStyle.TWO_PEEK, 200, 80)

    @Test
    fun `ten fast skips each followed by the queue end on the current cover, no peek, 100 ms apart`() = burstEndsOnTheCurrentCover(CarouselStyle.NO_PEEK, 100, 48)

    @Test
    fun `Previous brings the cover back to the current item and a second Previous too`() {
        index = 10
        setCarousel(CarouselStyle.ONE_PEEK)
        changeIndexTo(9)
        compose.mainClock.advanceTimeBy(112)
        republishTheQueue()
        val first = msUntilAtRest()
        println("Previous: at ${position()} after $first ms")
        assertAtRestOn(9, "Previous")
        changeIndexTo(8)
        compose.mainClock.advanceTimeBy(144)
        republishTheQueue()
        val second = msUntilAtRest()
        println("Previous again: at ${position()} after $second ms, selected=$selected")
        assertAtRestOn(8, "Previous again")
        assertThat(first).isAtMost(ANIMATION_BUDGET_MS)
        assertThat(second).isAtMost(ANIMATION_BUDGET_MS)
        assertThat(selected).isEmpty()
    }

    private fun skipWhileTheFingerIsDown(republishTheQueueToo: Boolean) {
        setCarousel(CarouselStyle.ONE_PEEK)
        val cover = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        cover.performTouchInput {
            down(center)
            moveBy(Offset(-50f, 0f))      // a touch that has begun to drag, far from a page
        }
        compose.mainClock.advanceTimeByFrame()
        val fingerAt = position()
        println("finger down: at $fingerAt")
        assertThat(fingerAt).isGreaterThan(3.0)
        changeIndexTo(4)                            // the media key lands while the finger is down
        if (republishTheQueueToo) republishTheQueue()
        compose.mainClock.advanceTimeBy(600)
        println("600 ms later, finger still down: at ${position()}")
        assertThat(abs(position() - fingerAt)).isLessThan(0.3)     // still the finger's: nothing pulled the cover to page 4 under it
        cover.performTouchInput { up() }
        val took = msUntilAtRest()
        println("finger up: at ${position()} after $took ms, selected=$selected")
        assertAtRestOn(4, "after the finger lifted")
        assertThat(took).isAtMost(ANIMATION_BUDGET_MS)
        assertThat(selected).isEmpty()                             // the cover going back to page 3 under the finger was not the listener choosing song 3
    }

    @Test
    fun `a skip while the finger is on the cover does not pull against it, and what the finger leaves is not a swipe back`() = skipWhileTheFingerIsDown(republishTheQueueToo = false)

    @Test
    fun `a skip and the queue again while the finger is on the cover do not pull against it either`() = skipWhileTheFingerIsDown(republishTheQueueToo = true)

    /**
     * The finger lands on the cover while the skip's animation is on its way (the owner was holding the phone when the media key came). A touch takes hold of a scroll that is
     * running and stops it where it is; released without a page of drag, the cover settles on the page it was nearest to, which before the middle of the trip is the previous song's.
     * That settle is not the listener asking for the previous song, and the cover still has to arrive at the current one.
     */
    private fun fingerLandsOnTheCoverWhileItIsOnItsWay(style: String, afterMs: Long) {
        setCarousel(style)
        changeIndexTo(4)
        compose.mainClock.advanceTimeBy(afterMs)
        val onItsWay = position()
        touch(MotionEvent.ACTION_DOWN, 540f, 540f)
        touch(MotionEvent.ACTION_MOVE, 536f, 540f)         // taken hold of, hardly moved
        compose.mainClock.advanceTimeBy(120)
        touch(MotionEvent.ACTION_UP, 536f, 540f)
        val took = msUntilAtRest()
        println("finger after $afterMs ms ($style): cover was at ${String.format("%.3f", onItsWay)}, ends at ${position()} after $took ms, selected=$selected")
        assertThat(onItsWay).isGreaterThan(3.0)        // the animation had begun and was not over
        assertThat(onItsWay).isLessThan(4.0)
        assertAtRestOn(4, "after the finger let go")
        assertThat(took).isAtMost(ANIMATION_BUDGET_MS)
        assertThat(selected).isEmpty()                 // the previous song was not asked for
    }

    @Test
    fun `a finger on the cover 32 ms into a skip does not undo it, no peek`() = fingerLandsOnTheCoverWhileItIsOnItsWay(CarouselStyle.NO_PEEK, 32)

    @Test
    fun `a finger on the cover 64 ms into a skip does not undo it, no peek`() = fingerLandsOnTheCoverWhileItIsOnItsWay(CarouselStyle.NO_PEEK, 64)

    @Test
    fun `a finger on the cover 96 ms into a skip does not undo it, no peek`() = fingerLandsOnTheCoverWhileItIsOnItsWay(CarouselStyle.NO_PEEK, 96)

    @Test
    fun `a finger on the cover 64 ms into a skip does not undo it, one peek`() = fingerLandsOnTheCoverWhileItIsOnItsWay(CarouselStyle.ONE_PEEK, 64)

    @Test
    fun `a finger that really swipes the cover on its way is the listener choosing, and the cover is not pulled back`() {
        setCarousel(CarouselStyle.NO_PEEK)
        changeIndexTo(4)
        compose.mainClock.advanceTimeBy(64)
        touch(MotionEvent.ACTION_DOWN, 900f, 540f)
        var x = 900f
        repeat(10) {
            x -= 140f
            touch(MotionEvent.ACTION_MOVE, x, 540f)        // a page and a half forward, fast: past the song the skip was going to
        }
        touch(MotionEvent.ACTION_UP, x, 540f)
        compose.mainClock.advanceTimeBy(2_500)
        val end = Math.round(position()).toInt()
        println("a real swipe on the way: ends at ${position()}, selected=$selected")
        assertThat(end).isGreaterThan(4)
        assertThat(abs(position() - end)).isLessThan(0.02)
        assertThat(selected).containsExactly(end)       // the swipe is taken for what it is, once, and nothing pulls the cover back to page 4
    }

    private companion object {
        /** "Within the animation time": the pager's spring, with room for the frame it starts on. */
        const val ANIMATION_BUDGET_MS = 1_000L
    }
}
