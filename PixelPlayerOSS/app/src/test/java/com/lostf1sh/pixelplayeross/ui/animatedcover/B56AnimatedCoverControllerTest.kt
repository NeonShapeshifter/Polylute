package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.view.View
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverController.Want
import com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverPolicy.Inputs
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** B56 phase 7.2/7.3 (#48): when the animated cover exists, runs, pauses and is released; what a failure does; one decoder at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class B56AnimatedCoverControllerTest {
    private fun cover(name: String = "cover.mp4", size: Long = 1000L) =
        AnimatedCover(File("/music/Gaia/$name"), AnimatedCoverKind.entries.first { name.endsWith(it.fileName.substringAfter('.')) }, size, 7L)

    private class FakeEngine(val cover: AnimatedCover, val log: MutableList<String>) : AnimatedCoverEngine {
        override val view: View? = null
        override val firstFrame: MutableStateFlow<Boolean> = MutableStateFlow(false)
        var playingNow = false
        var released = false
        var onFailed: (String) -> Unit = {}
        override fun setRunning(running: Boolean) { this.playingNow = running }
        override fun release() { check(!released) { "released twice" }; released = true; log += "release ${cover.file.name}#${cover.sizeBytes}" }
    }

    private class FakeFactory(val log: MutableList<String>) : AnimatedCoverEngineFactory {
        val engines = mutableListOf<FakeEngine>()
        var unplayable = false
        override fun create(cover: AnimatedCover, sizePx: Int, onFailed: (String) -> Unit): AnimatedCoverEngine? {
            log += "create ${cover.file.name}#${cover.sizeBytes} at $sizePx"
            if (unplayable) return null
            return FakeEngine(cover, log).also { it.onFailed = onFailed; engines += it }
        }
        val liveEngines: Int get() = engines.count { !it.released }
    }

    private class Rig(scope: kotlinx.coroutines.CoroutineScope) {
        val log = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val factory = FakeFactory(log)
        val controller = AnimatedCoverController(scope, factory, idleReleaseMs = 15_000L, log = { lines += it })
    }

    /** The screen's state the way the host turns it into a [Want]. */
    private fun want(cover: AnimatedCover?, i: Inputs, size: Int = 600) = Want(
        cover = cover, sizePx = size,
        hold = AnimatedCoverPolicy.shouldHold(i), visible = AnimatedCoverPolicy.visible(i), run = AnimatedCoverPolicy.shouldRun(i),
    )

    private val playing = Inputs(enabled = true, playerExpanded = true, coverPageShowing = true, playing = true)

    // ── the policy ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `it runs only with the setting on, the full player open on the cover page, a song playing and the screen on`() {
        assertThat(AnimatedCoverPolicy.shouldRun(playing)).isTrue()
        for ((why, off) in listOf(
            "setting off" to playing.copy(enabled = false),
            "player not open" to playing.copy(playerExpanded = false),
            "not the cover page (lyrics, menu, details)" to playing.copy(coverPageShowing = false),
            "paused" to playing.copy(playing = false),
            "a sheet over it" to playing.copy(sheetOpen = true),
            "DAP lock screen" to playing.copy(lockSurfaceVisible = true),
            "screen off" to playing.copy(screenInteractive = false),
            "app in the background" to playing.copy(resumed = false),
            "animations removed or Battery saver" to playing.copy(systemAnimationsAllowed = false),
        )) {
            assertWithMessage(why).that(AnimatedCoverPolicy.shouldRun(off)).isFalse()
        }
    }

    @Test
    fun `it is held for the cover page and not for anything else, and a sheet or a pause only hold the frame`() {
        assertThat(AnimatedCoverPolicy.shouldHold(playing.copy(sheetOpen = true, playing = false))).isTrue()
        assertThat(AnimatedCoverPolicy.shouldHold(playing.copy(enabled = false))).isFalse()
        assertThat(AnimatedCoverPolicy.shouldHold(playing.copy(playerExpanded = false))).isFalse()
        assertThat(AnimatedCoverPolicy.shouldHold(playing.copy(coverPageShowing = false))).isFalse()
        assertThat(AnimatedCoverPolicy.visible(playing.copy(lockSurfaceVisible = true))).isFalse()
        assertThat(AnimatedCoverPolicy.visible(playing.copy(screenInteractive = false))).isFalse()
        assertThat(AnimatedCoverPolicy.visible(playing.copy(resumed = false))).isFalse()
    }

    // ── the controller ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `it runs while allowed and pauses under a sheet, on the lock screen, with the screen off, in the background, and when the song pauses`() = runTest {
        val rig = Rig(backgroundScope)
        val c = cover()
        rig.controller.update(want(c, playing))
        val engine = rig.factory.engines.single()
        assertThat(engine.playingNow).isTrue()
        for (off in listOf(
            playing.copy(sheetOpen = true), playing.copy(lockSurfaceVisible = true), playing.copy(screenInteractive = false),
            playing.copy(resumed = false), playing.copy(playing = false),
        )) {
            rig.controller.update(want(c, off))
            assertThat(engine.playingNow).isFalse()
            // it resumes the same decoder: no new one, no jump to the start
            rig.controller.update(want(c, playing))
            assertThat(engine.playingNow).isTrue()
        }
        assertThat(rig.factory.engines).hasSize(1)
        assertThat(engine.released).isFalse()
    }

    @Test
    fun `it is released on leave, whichever way the player is left`() = runTest {
        for (leave in listOf(
            playing.copy(playerExpanded = false), playing.copy(coverPageShowing = false), playing.copy(enabled = false),
        )) {
            val rig = Rig(backgroundScope)
            rig.controller.update(want(cover(), playing))
            rig.controller.update(want(cover(), leave))
            assertThat(rig.factory.engines.single().released).isTrue()
            assertThat(rig.controller.engine.value).isNull()
            assertThat(rig.controller.alive).isEqualTo(0)
        }
        val rig = Rig(backgroundScope)
        rig.controller.update(want(cover(), playing))
        rig.controller.release()
        assertThat(rig.factory.engines.single().released).isTrue()
    }

    @Test
    fun `a song without an animated cover has no engine at all`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.update(want(null, playing))
        assertThat(rig.factory.engines).isEmpty()
        assertThat(rig.controller.engine.value).isNull()
    }

    @Test
    fun `at most one decoder at a time, and the old song's is released before the new one is made`() = runTest {
        val rig = Rig(backgroundScope)
        val a = cover("cover.mp4", 1000L)
        val b = cover("cover.webp", 2000L)
        val c = cover("cover.gif", 3000L)
        rig.controller.update(want(a, playing))
        rig.controller.update(want(b, playing))
        rig.controller.update(want(c, playing))
        rig.controller.update(want(null, playing))
        rig.controller.update(want(a, playing))
        assertThat(rig.log).containsExactly(
            "create cover.mp4#1000 at 600",
            "release cover.mp4#1000", "create cover.webp#2000 at 600",
            "release cover.webp#2000", "create cover.gif#3000 at 600",
            "release cover.gif#3000",
            "create cover.mp4#1000 at 600",
        ).inOrder()
        assertThat(rig.factory.liveEngines).isEqualTo(1)
        assertThat(rig.controller.alive).isEqualTo(1)
    }

    @Test
    fun `a song change to a song with no animated cover stops the old one`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.update(want(cover(), playing))
        rig.controller.update(want(null, playing))
        assertThat(rig.factory.engines.single().released).isTrue()
        assertThat(rig.controller.engine.value).isNull()
    }

    @Test
    fun `the same file asked for again keeps the decoder it has`() = runTest {
        val rig = Rig(backgroundScope)
        repeat(5) { rig.controller.update(want(cover(), playing)) }
        assertThat(rig.factory.engines).hasSize(1)
    }

    // ── fallback ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a file that cannot be played falls back to the still cover, silently, with one log line, and is not tried again`() = runTest {
        val rig = Rig(backgroundScope)
        rig.factory.unplayable = true
        repeat(4) { rig.controller.update(want(cover(), playing)) }
        assertThat(rig.controller.engine.value).isNull()
        assertThat(rig.factory.engines).isEmpty()
        assertThat(rig.log.count { it.startsWith("create") }).isEqualTo(1)
        assertThat(rig.lines).hasSize(1)
        assertThat(rig.lines.single()).contains("still cover")
    }

    @Test
    fun `an engine that fails later is released, the still cover stays, and the file is not tried again`() = runTest {
        val rig = Rig(backgroundScope)
        val c = cover()
        rig.controller.update(want(c, playing))
        val engine = rig.factory.engines.single()
        engine.onFailed("no hardware decoder for video/av01")
        assertThat(engine.released).isTrue()
        assertThat(rig.controller.engine.value).isNull()
        // a second report of the same failure and further updates add nothing
        engine.onFailed("again")
        repeat(3) { rig.controller.update(want(c, playing)) }
        assertThat(rig.factory.engines).hasSize(1)
        assertThat(rig.lines).hasSize(1)
        assertThat(rig.lines.single()).contains("no hardware decoder")
    }

    @Test
    fun `a replaced file is a new file and is tried`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.update(want(cover(size = 1000L), playing))
        rig.factory.engines.single().onFailed("broken")
        rig.controller.update(want(cover(size = 1000L), playing))
        assertThat(rig.factory.engines).hasSize(1)
        rig.controller.update(want(cover(size = 2000L), playing))
        assertThat(rig.factory.engines).hasSize(2)
    }

    @Test
    fun `a factory that throws is a fallback, not a crash`() = runTest {
        val log = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val c = AnimatedCoverController(backgroundScope, { _, _, _ -> error("boom") }, log = { lines += it })
        c.update(want(cover(), playing))
        assertThat(c.engine.value).isNull()
        assertThat(lines).hasSize(1)
        assertThat(log).isEmpty()
    }

    // ── not seen for long ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a cover that is not seen for fifteen seconds gives its decoder back, and comes back when it is seen`() = runTest {
        val rig = Rig(backgroundScope)
        val c = cover()
        rig.controller.update(want(c, playing))
        val screenOff = playing.copy(screenInteractive = false)
        rig.controller.update(want(c, screenOff))
        advanceTimeBy(14_000)
        assertThat(rig.factory.engines.single().released).isFalse()
        advanceTimeBy(2_000)
        assertThat(rig.factory.engines.single().released).isTrue()
        // still out of sight: nothing is made
        rig.controller.update(want(c, screenOff))
        assertThat(rig.factory.engines).hasSize(1)
        // seen again: a new one, from its first frame, running
        rig.controller.update(want(c, playing))
        assertThat(rig.factory.engines).hasSize(2)
        assertThat(rig.factory.engines.last().playingNow).isTrue()
    }

    @Test
    fun `a short blink of the screen does not cost the decoder`() = runTest {
        val rig = Rig(backgroundScope)
        val c = cover()
        rig.controller.update(want(c, playing))
        rig.controller.update(want(c, playing.copy(resumed = false)))
        advanceTimeBy(5_000)
        rig.controller.update(want(c, playing))
        advanceTimeBy(60_000)
        assertThat(rig.factory.engines).hasSize(1)
        assertThat(rig.factory.engines.single().released).isFalse()
    }

    @Test
    fun `under a sheet the decoder is kept, because the screen is on and it is only held`() = runTest {
        val rig = Rig(backgroundScope)
        val c = cover()
        rig.controller.update(want(c, playing.copy(sheetOpen = true)))
        advanceTimeBy(120_000)
        assertThat(rig.factory.engines).hasSize(1)
        assertThat(rig.factory.engines.single().released).isFalse()
        assertThat(rig.factory.engines.single().playingNow).isFalse()
    }

    @Test
    fun `no decoder is made before the page has a size, so it is never made at the file's own size`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.update(want(cover(), playing, size = 0))
        assertThat(rig.factory.engines).isEmpty()
        rig.controller.update(want(cover(), playing, size = 540))
        assertThat(rig.log.single()).isEqualTo("create cover.mp4#1000 at 540")
    }

    @Test
    fun `the engine is made at the size it is shown`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.update(want(cover(), playing, size = 512))
        assertThat(rig.log.single()).isEqualTo("create cover.mp4#1000 at 512")
    }
}
