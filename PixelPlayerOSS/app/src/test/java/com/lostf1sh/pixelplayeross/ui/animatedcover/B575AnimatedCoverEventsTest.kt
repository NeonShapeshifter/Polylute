package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.view.View
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverController.Want
import com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverPolicy.Inputs
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B57.5 2.2: every engine made and every engine released is one line in the device log (tag `AnimatedCover`, info) with the reason and the policy's inputs at that moment, so the next test on
 * the phone can say why a cover came and went. (Before, `releaseCurrent(why)` threw the reason away: the log could not tell "the song changed" from "the page was left".)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class B575AnimatedCoverEventsTest {
    private fun cover(name: String = "cover.mp4", size: Long = 5_616_834L, folder: String = "Artist Two/[2023] Album Two") =
        AnimatedCover(File("/music/$folder/$name"), AnimatedCoverKind.entries.first { name.endsWith(it.fileName.substringAfter('.')) }, size, 7L)

    private class FakeEngine : AnimatedCoverEngine {
        override val view: View? = null
        override val firstFrame = MutableStateFlow(false)
        var onFailed: (String) -> Unit = {}
        override fun setRunning(running: Boolean) {}
        override fun release() {}
    }

    private class Rig(scope: kotlinx.coroutines.CoroutineScope) {
        val lines = mutableListOf<String>()
        val events = mutableListOf<String>()
        var clock = 1_000L
        val engines = mutableListOf<FakeEngine>()
        var unplayable = false
        val controller = AnimatedCoverController(
            scope,
            { _, _, onFailed -> if (unplayable) null else FakeEngine().also { it.onFailed = onFailed; engines += it } },
            idleReleaseMs = 15_000L, log = { lines += it }, events = { events += it }, nowMs = { clock },
        )
    }

    private val playing = Inputs(enabled = true, playerExpanded = true, coverPageShowing = true, playing = true)

    private fun want(cover: AnimatedCover?, i: Inputs, size: Int = 720) = Want(
        cover = cover, sizePx = size,
        hold = AnimatedCoverPolicy.shouldHold(i), visible = AnimatedCoverPolicy.visible(i), run = AnimatedCoverPolicy.shouldRun(i),
    )

    private fun Rig.update(cover: AnimatedCover?, i: Inputs, size: Int = 720) = controller.update(want(cover, i, size), i.describe())

    private fun Rig.kind(prefix: String) = events.filter { it.startsWith(prefix) }

    // ── what a line says ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the inputs are written out in a fixed order, every one of them`() {
        assertThat(playing.describe()).isEqualTo(
            "enabled=true playerExpanded=true coverPageShowing=true sheetOpen=false lockSurfaceVisible=false resumed=true screenInteractive=true systemAnimationsAllowed=true playing=true",
        )
        assertThat(Inputs().describe()).startsWith("enabled=false playerExpanded=false")
    }

    @Test
    fun `an engine made is one line with the kind, the file, its size, the page size and the inputs`() = runTest {
        val rig = Rig(backgroundScope)
        rig.update(cover(), playing)
        assertThat(rig.kind("make ")).hasSize(1)
        val line = rig.kind("make ").single()
        assertThat(line).startsWith("make ")
        assertThat(line).contains("kind=VIDEO")
        assertThat(line).contains("file=/music/Artist Two/[2023] Album Two/cover.mp4")
        assertThat(line).contains("bytes=5616834")
        assertThat(line).contains("px=720")
        assertThat(line).contains("inputs=[${playing.describe()}]")
    }

    @Test
    fun `an engine released is one line with how long it lived, why, and the inputs at that moment`() = runTest {
        val rig = Rig(backgroundScope)
        rig.update(cover(), playing)
        rig.clock += 25
        val gone = playing.copy(coverPageShowing = false)
        rig.update(cover(), gone)
        assertThat(rig.events.filter { it.startsWith("make ") || it.startsWith("release ") }).hasSize(2)
        val line = rig.kind("release ").single()
        assertThat(line).startsWith("release ")
        assertThat(line).contains("aliveMs=25")
        assertThat(line).contains("why=\"no longer held")
        assertThat(line).contains("inputs=[${gone.describe()}]")
        assertThat(line).contains("coverPageShowing=false")
    }

    @Test
    fun `every reason a release can have is said in its own words`() = runTest {
        val a = cover(folder = "Artist One/[2023] Album One")
        val b = cover()
        // the song's cover changed
        var rig = Rig(backgroundScope)
        rig.update(a, playing); rig.update(b, playing)
        assertThat(rig.kind("release ").single()).contains("why=\"the song's cover changed (/music/Artist One/[2023] Album One/cover.mp4 -> /music/Artist Two/[2023] Album Two/cover.mp4)")
        // the song has none
        rig = Rig(backgroundScope)
        rig.update(a, playing); rig.update(null, playing)
        assertThat(rig.kind("release ").single()).contains("why=\"the song has no animated cover")
        // setting off / player closed / another page
        for (leave in listOf(playing.copy(enabled = false), playing.copy(playerExpanded = false), playing.copy(coverPageShowing = false))) {
            rig = Rig(backgroundScope)
            rig.update(a, playing); rig.update(a, leave)
            assertThat(rig.kind("release ").single()).contains("why=\"no longer held")
            assertThat(rig.kind("release ").single()).contains(leave.describe())
        }
        // a failure
        rig = Rig(backgroundScope)
        rig.update(a, playing)
        rig.engines.single().onFailed("no frame within 4000 ms")
        assertThat(rig.kind("release ").single()).contains("why=\"failed: no frame within 4000 ms")
        // the owner left
        rig = Rig(backgroundScope)
        rig.update(a, playing); rig.controller.release()
        assertThat(rig.kind("release ").single()).contains("why=\"owner left")
    }

    @Test
    fun `not seen for fifteen seconds is a reason too`() = runTest {
        val rig = Rig(backgroundScope)
        val off = playing.copy(screenInteractive = false)
        rig.update(cover(), playing)
        rig.update(cover(), off)
        advanceTimeBy(16_000)
        assertThat(rig.kind("release ").single()).contains("why=\"not seen for 15000 ms")
        assertThat(rig.kind("release ").single()).contains("screenInteractive=false")
    }

    @Test
    fun `a failure keeps its own line apart from the events, and an event is written only when an engine really was made or released`() = runTest {
        val rig = Rig(backgroundScope)
        rig.unplayable = true
        repeat(3) { rig.update(cover(), playing) }
        // no engine was ever made: no make, no release, and the one fallback line the log always had
        assertThat(rig.events).isEmpty()
        assertThat(rig.lines).hasSize(1)
        assertThat(rig.lines.single()).contains("still cover")
    }

    @Test
    fun `the same inputs asked again are not new events`() = runTest {
        val rig = Rig(backgroundScope)
        repeat(5) { rig.update(cover(), playing) }
        assertThat(rig.events).hasSize(2)   // the make, and the one "run=true" that follows it
        assertThat(rig.kind("make ")).hasSize(1)
    }

    // ── a policy input that blinks ───────────────────────────────────────────────────────────────────

    @Test
    fun `an input that blinks for one frame releases the engine and makes it again at once, and the cover ends up playing`() = runTest {
        val rig = Rig(backgroundScope)
        val c = cover()
        rig.update(c, playing)
        // one frame with the cover page "not showing" (a pager settling on a song change), then back
        rig.update(c, playing.copy(coverPageShowing = false))
        rig.update(c, playing)
        assertThat(rig.engines).hasSize(2)
        assertThat(rig.controller.engine.value).isSameInstanceAs(rig.engines.last())
        assertThat(rig.controller.alive).isEqualTo(1)
        assertThat(rig.events.map { it.substringBefore(' ') }.filter { it == "make" || it == "release" }).containsExactly("make", "release", "make").inOrder()
        // and the log says it was the page that blinked
        assertThat(rig.kind("release ").single()).contains("coverPageShowing=false")
    }

    @Test
    fun `an input that only holds the frame (a sheet, a pause, the lock screen) never costs the engine`() = runTest {
        val rig = Rig(backgroundScope)
        val c = cover()
        rig.update(c, playing)
        for (hold in listOf(playing.copy(sheetOpen = true), playing.copy(playing = false), playing.copy(lockSurfaceVisible = true), playing.copy(systemAnimationsAllowed = false))) {
            rig.update(c, hold)
            rig.update(c, playing)
        }
        assertThat(rig.engines).hasSize(1)
        assertThat(rig.kind("make ")).hasSize(1)
        assertThat(rig.kind("release ")).isEmpty()
    }

    // ── found and held, but not made: the "wait" line ────────────────────────────────────────────────

    @Test
    fun `a cover that is found but cannot be made yet says why, once`() = runTest {
        val rig = Rig(backgroundScope)
        val c = cover()
        // the page has no size yet
        rig.update(c, playing, size = 0); rig.update(c, playing, size = 0)
        assertThat(rig.kind("wait ")).hasSize(1)
        assertThat(rig.kind("wait ").single()).contains("why=\"the page has no size yet")
        assertThat(rig.kind("make ")).isEmpty()
        // it has one: made, and no more waiting
        rig.update(c, playing)
        assertThat(rig.kind("make ")).hasSize(1)
        assertThat(rig.kind("wait ")).hasSize(1)
    }

    @Test
    fun `a cover held while the screen is not seen waits, naming the inputs`() = runTest {
        val rig = Rig(backgroundScope)
        val off = playing.copy(screenInteractive = false)
        repeat(3) { rig.update(cover(), off) }
        assertThat(rig.kind("wait ")).hasSize(1)
        assertThat(rig.kind("wait ").single()).contains("why=\"not visible")
        assertThat(rig.kind("wait ").single()).contains("screenInteractive=false")
        assertThat(rig.kind("make ")).isEmpty()
    }

    @Test
    fun `a song with no cover, or one that is not held, writes no wait line`() = runTest {
        val rig = Rig(backgroundScope)
        rig.update(null, playing)
        rig.update(cover(), playing.copy(coverPageShowing = false))
        assertThat(rig.kind("wait ")).isEmpty()
    }

    // ── run and hold: the engine's own state ─────────────────────────────────────────────────────────

    @Test
    fun `run is written when the engine starts or holds its frame, and not when nothing changed`() = runTest {
        val rig = Rig(backgroundScope)
        val c = cover()
        rig.update(c, playing)
        rig.update(c, playing)
        assertThat(rig.kind("run=")).hasSize(1)
        assertThat(rig.kind("run=").single()).startsWith("run=true ")
        rig.update(c, playing.copy(playing = false))
        rig.update(c, playing.copy(playing = false))
        assertThat(rig.kind("run=").last()).startsWith("run=false ")
        assertThat(rig.kind("run=").last()).contains("playing=false")
        rig.update(c, playing)
        assertThat(rig.kind("run=").map { it.substringBefore(' ') }).containsExactly("run=true", "run=false", "run=true").inOrder()
    }
}
