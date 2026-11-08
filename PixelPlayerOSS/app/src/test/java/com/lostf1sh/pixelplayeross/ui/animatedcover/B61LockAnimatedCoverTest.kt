package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.lockscreen.LocalLockSurfaceShowing
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B61 item 5 (#70, B58 8 / B59 6 / B60 4): **the animated cover on Polylute's own lock screen.** One controller per activity, shared by the full player's cover page and the lock screen; a policy
 * input for the switch "Also on the lock screen" (off by default); the layer shows the engine's view for whichever surface holds it last and gives it back. Never two decoders; the screen going
 * off releases as it always did; a disc's own animated cover (`disc2.mp4`) follows the song on both surfaces.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class B61LockAnimatedCoverTest {
    @get:Rule val compose = createComposeRule()

    private class Dir : AnimatedCoverDirectory {
        val folders = HashMap<String, List<String>>()
        override fun names(dir: File): List<String>? = folders[dir.path]
        override fun stamp(dir: File) = 1L
        override fun sizeOf(file: File) = 4_000L
        override fun modifiedOf(file: File) = 9L
    }

    private class Engine(val cover: AnimatedCover, override val view: View) : AnimatedCoverEngine {
        override val firstFrame = MutableStateFlow(true)
        var runningNow = false
        var released = false
        override fun setRunning(running: Boolean) { runningNow = running }
        override fun release() { released = true }
    }

    private val io = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val dir = Dir()
    private val albums = object : AlbumFolders { override fun foldersOf(albumId: Long) = listOf(AlbumFolder("/m/Artist/Album", 12)) }
    private val finder = AnimatedCoverFinder(io, dir, AlbumArtUtils::shouldTrustDirectoryArtwork, { 0L }, albums, {})
    private val engines = mutableListOf<Engine>()

    private var lockOn by mutableStateOf(true)
    private var lockUp by mutableStateOf(false)
    private var playerOpen by mutableStateOf(false)
    private var playing by mutableStateOf(true)
    private var disc by mutableIntStateOf(0)
    private val song = "/m/Artist/Album/01.flac"

    @After fun close() { io.close() }

    @Composable
    private fun Surface(onLock: Boolean) {
        val host = rememberAnimatedCoverHost(song, { playing }, playerExpanded = !onLock && playerOpen, coverPageShowing = !onLock && playerOpen, albumId = 5L, discNumber = disc, onLock = onLock)
        Box(Modifier.size(200.dp).onSizeChanged { host?.onSize(it.width) }) { AnimatedCoverLayer(host) }
    }

    private fun show() {
        dir.folders["/m/Artist/Album"] = listOf("cover.mp4", "disc2.mp4")
        compose.setContent {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            CompositionLocalProvider(
                LocalAnimatedCoverEnv provides AnimatedCoverEnv(finder, true, 0L, lockCoverOn = lockOn),
                LocalAnimatedCoverEngines provides { _, _ -> AnimatedCoverEngineFactory { cover, _, _ -> Engine(cover, View(ctx)).also { engines += it } } },
            ) {
                val shared = rememberSharedAnimatedCoverController()
                CompositionLocalProvider(LocalAnimatedCoverController provides shared, LocalLockSurfaceShowing provides lockUp) {
                    Surface(onLock = false)
                    if (lockUp) Surface(onLock = true)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun settle() = repeat(20) { Thread.sleep(25); compose.waitForIdle() }

    private val alive get() = engines.count { !it.released }

    // ── the switch ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with the switch off the lock screen has no animated cover and nothing is decoded under it, as before`() {
        lockOn = false; lockUp = true; playerOpen = false
        show(); settle()
        assertThat(engines).isEmpty()
        // the player open under a lock screen that is up: held, not seen, no decoder made (the lock screen covers it): as it always was
        playerOpen = true
        compose.waitForIdle(); settle()
        assertThat(engines).isEmpty()
    }

    @Test
    fun `with the switch on the lock screen asks for the song's cover, makes one decoder, and draws its view`() {
        lockOn = true; lockUp = true; playerOpen = false
        show(); settle()
        assertThat(alive).isEqualTo(1)
        val engine = engines.single()
        assertThat(engine.cover.file.name).isEqualTo("cover.mp4")
        assertThat(engine.runningNow).isTrue()
        assertThat(engine.view!!.parent).isNotNull()                // it is on the lock screen's cover
        // a pause holds the frame, the decoder stays
        playing = false
        settle()
        assertThat(engine.runningNow).isFalse()
        assertThat(engine.released).isFalse()
    }

    // ── one decoder, two surfaces ────────────────────────────────────────────────────────────────────

    @Test
    fun `the player and the lock screen share one controller - never two decoders, the view goes to the lock screen and comes back, and the decoder is never remade`() {
        lockOn = true; playerOpen = true; lockUp = false
        show(); settle()
        assertThat(alive).isEqualTo(1)
        val engine = engines.single()
        assertThat(engine.view!!.parent).isNotNull()                // on the player's cover page
        val playerParent = engine.view!!.parent
        // the lock screen comes up over the open player: the lock screen's host decides, there is still one decoder, and the view is on the lock screen's cover
        lockUp = true
        compose.waitForIdle(); settle()
        assertThat(engines.size).isEqualTo(1)                       // the same engine: not remade
        assertThat(alive).isEqualTo(1)
        assertThat(engine.released).isFalse()
        assertThat(engine.view!!.parent).isNotNull()
        assertThat(engine.view!!.parent).isNotSameInstanceAs(playerParent)
        // the lock screen goes: the player's cover gets the view back, the same engine, never a second
        lockUp = false
        compose.waitForIdle(); settle()
        assertThat(engines.size).isEqualTo(1)
        assertThat(engine.released).isFalse()
        assertThat(engine.view!!.parent).isNotNull()
    }

    @Test
    fun `a disc's own animated cover follows the song on the lock screen`() {
        lockOn = true; lockUp = true; playerOpen = false; disc = 2
        show(); settle()
        assertThat(engines.last().cover.file.name).isEqualTo("disc2.mp4")
        assertThat(alive).isEqualTo(1)
        disc = 1                                                      // another disc of the album: its own file, else the album's
        settle()
        assertThat(engines.last().cover.file.name).isEqualTo("cover.mp4")
        assertThat(alive).isEqualTo(1)                                // the first was released before the second was made
        assertThat(engines.first().released).isTrue()
    }

    // ── the policy and the controller, without a screen ─────────────────────────────────────────────

    @Test
    fun `the policy holds and shows the cover on the lock screen only with the switch on, and the screen off still releases it`() {
        val up = AnimatedCoverPolicy.Inputs(enabled = true, lockSurfaceVisible = true, playing = true)
        // off: as before (held only in the open player, not seen under a lock screen)
        assertThat(AnimatedCoverPolicy.shouldHold(up)).isFalse()
        assertThat(AnimatedCoverPolicy.visible(up)).isFalse()
        assertThat(AnimatedCoverPolicy.shouldRun(up)).isFalse()
        val on = up.copy(lockCoverOn = true)
        assertThat(AnimatedCoverPolicy.shouldHold(on)).isTrue()
        assertThat(AnimatedCoverPolicy.visible(on)).isTrue()
        assertThat(AnimatedCoverPolicy.shouldRun(on)).isTrue()
        // not with Animated covers off, not without a song playing, not with animations off, not with the lock screen down
        assertThat(AnimatedCoverPolicy.shouldHold(on.copy(enabled = false))).isFalse()
        assertThat(AnimatedCoverPolicy.shouldRun(on.copy(playing = false))).isFalse()
        assertThat(AnimatedCoverPolicy.shouldRun(on.copy(systemAnimationsAllowed = false))).isFalse()
        assertThat(AnimatedCoverPolicy.shouldHold(on.copy(lockSurfaceVisible = false))).isFalse()
        // screen off: held, not seen, never running
        val off = on.copy(screenInteractive = false)
        assertThat(AnimatedCoverPolicy.shouldHold(off)).isTrue()
        assertThat(AnimatedCoverPolicy.visible(off)).isFalse()
        assertThat(AnimatedCoverPolicy.shouldRun(off)).isFalse()
        // the player's own rules are untouched
        val player = AnimatedCoverPolicy.Inputs(enabled = true, playerExpanded = true, coverPageShowing = true, playing = true)
        assertThat(AnimatedCoverPolicy.shouldRun(player)).isTrue()
        assertThat(AnimatedCoverPolicy.shouldRun(player.copy(lockSurfaceVisible = true))).isFalse()
        assertThat(player.describe()).contains("lockCoverOn=false")
    }

    @Test
    fun `screen off releases the lock screen's decoder after the idle time, and the screen on makes it again from its first frame`() {
        val scope = TestScope(StandardTestDispatcher())
        val made = mutableListOf<Engine>()
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val controller = AnimatedCoverController(
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)),
            factory = AnimatedCoverEngineFactory { cover, _, _ -> Engine(cover, View(ctx)).also { made += it } },
            idleReleaseMs = 15_000L, log = {}, events = {},
        )
        val cover = AnimatedCover(File("/m/Artist/Album/cover.mp4"), AnimatedCoverKind.VIDEO, 4_000L, 9L, null)
        fun want(i: AnimatedCoverPolicy.Inputs) = AnimatedCoverController.Want(cover, 840, AnimatedCoverPolicy.shouldHold(i), AnimatedCoverPolicy.visible(i), AnimatedCoverPolicy.shouldRun(i))
        val up = AnimatedCoverPolicy.Inputs(enabled = true, lockSurfaceVisible = true, lockCoverOn = true, playing = true)
        controller.update(want(up))
        assertThat(made).hasSize(1)
        assertThat(made.single().runningNow).isTrue()
        // the screen goes off: held, held still, not running; 15 s later the decoder is let go
        controller.update(want(up.copy(screenInteractive = false)))
        assertThat(made.single().runningNow).isFalse()
        scope.testScheduler.advanceTimeBy(14_000L); scope.testScheduler.runCurrent()
        assertThat(made.single().released).isFalse()
        scope.testScheduler.advanceTimeBy(1_500L); scope.testScheduler.runCurrent()
        assertThat(made.single().released).isTrue()
        // the screen comes on again: a new engine (from its first frame), never two alive
        controller.update(want(up))
        assertThat(made).hasSize(2)
        assertThat(made.count { !it.released }).isEqualTo(1)
        controller.release()
    }

    @Test
    fun `the view goes to the surface that claimed it last and back to the one before it`() {
        val controller = AnimatedCoverController(
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher()),
            factory = AnimatedCoverEngineFactory { _, _, _ -> null }, log = {}, events = {},
        )
        val player = Any()
        val lock = Any()
        assertThat(controller.viewOwner.value).isNull()
        controller.claimView(player)
        assertThat(controller.viewOwner.value).isSameInstanceAs(player)
        controller.claimView(lock)
        assertThat(controller.viewOwner.value).isSameInstanceAs(lock)
        controller.releaseView(lock)
        assertThat(controller.viewOwner.value).isSameInstanceAs(player)
        controller.releaseView(player)
        assertThat(controller.viewOwner.value).isNull()
        // a release out of order leaves the owner alone
        controller.claimView(player); controller.claimView(lock); controller.releaseView(player)
        assertThat(controller.viewOwner.value).isSameInstanceAs(lock)
    }
}
