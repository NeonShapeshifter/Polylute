package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.lockscreen.LocalLockSurfaceShowing
import com.lostf1sh.pixelplayeross.ui.glass.SheetPresence
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B56 phase 7.2 (#48): the glue between the screen and the controller, in a real composition: the finder is asked off the main thread for the song's folder, the policy's
 * inputs (a sheet, the lock screen, a pause, the page) reach the engine, and the cover exists only where the player draws it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class B56AnimatedCoverHostTest {
    @get:Rule val compose = createComposeRule()

    private class Dir : AnimatedCoverDirectory {
        var names: List<String>? = listOf("cover.mp4", "cover.gif")
        override fun names(dir: File) = names
        override fun stamp(dir: File) = 1L
        override fun sizeOf(file: File) = 4_000L
        override fun modifiedOf(file: File) = 9L
    }

    private class Engine(val cover: AnimatedCover, override val view: View, val sizePx: Int) : AnimatedCoverEngine {
        override val firstFrame = MutableStateFlow(false)
        var playingNow = false
        var released = false
        override fun setRunning(running: Boolean) { this.playingNow = running }
        override fun release() { released = true }
    }

    private val engines = mutableListOf<Engine>()
    private val dir = Dir()
    private val finder = AnimatedCoverFinder(Dispatchers.Unconfined, dir, AlbumArtUtils::shouldTrustDirectoryArtwork) { 0L }

    private var enabled by mutableStateOf(true)
    private var playing by mutableStateOf(true)
    private var expanded by mutableStateOf(true)
    private var onCoverPage by mutableStateOf(true)
    private var sheet by mutableStateOf(false)
    private var lock by mutableStateOf(false)
    private var songPath by mutableStateOf("/music/Gaia/01.flac")

    private fun show() {
        compose.setContent {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            CompositionLocalProvider(
                LocalAnimatedCoverEnv provides AnimatedCoverEnv(finder, enabled, 0L),
                LocalAnimatedCoverEngines provides { _, _ ->
                    AnimatedCoverEngineFactory { cover, sizePx, _ -> Engine(cover, View(ctx), sizePx).also { engines += it } }
                },
                LocalLockSurfaceShowing provides lock,
            ) {
                val host = rememberAnimatedCoverHost(songPath, { playing }, expanded, onCoverPage)
                SheetPresence.Hold(sheet)
                Box(Modifier.size(200.dp).onSizeChanged { host?.onSize(it.width) }) { AnimatedCoverLayer(host) }
            }
        }
        compose.waitForIdle()
    }

    private fun idle() = compose.waitForIdle()

    @Test
    fun `it plays the mp4 over the gif, at the size of the page, while the player is open on the cover page and a song plays`() {
        show()
        val e = engines.single()
        assertThat(e.cover.kind).isEqualTo(AnimatedCoverKind.VIDEO)
        assertThat(e.sizePx).isGreaterThan(0)
        assertThat(e.playingNow).isTrue()
        assertThat(e.released).isFalse()
    }

    @Test
    fun `a sheet, the lock screen and a pause hold the frame, and closing them runs it again on the same decoder`() {
        show()
        val e = engines.single()
        sheet = true; idle(); assertThat(e.playingNow).isFalse()
        sheet = false; idle(); assertThat(e.playingNow).isTrue()
        lock = true; idle(); assertThat(e.playingNow).isFalse()
        lock = false; idle(); assertThat(e.playingNow).isTrue()
        playing = false; idle(); assertThat(e.playingNow).isFalse()
        playing = true; idle(); assertThat(e.playingNow).isTrue()
        assertThat(engines).hasSize(1)
        assertThat(e.released).isFalse()
    }

    @Test
    fun `with the setting off, the player closed or another page showing there is no decoder, or the one there is is released`() {
        enabled = false
        show()
        assertThat(engines).isEmpty()
        enabled = true; idle()
        assertThat(engines).hasSize(1)
        val e = engines.single()
        onCoverPage = false; idle(); assertThat(e.released).isTrue()
        onCoverPage = true; idle(); assertThat(engines).hasSize(2)
        expanded = false; idle(); assertThat(engines.last().released).isTrue()
        enabled = false; expanded = true; idle()
        assertThat(engines).hasSize(2)
    }

    @Test
    fun `a song change stops and releases the old cover before the new one is made, and a song without one has none`() {
        show()
        val first = engines.single()
        dir.names = null
        songPath = "/music/Other/01.flac"; idle()
        assertThat(first.released).isTrue()
        assertThat(engines).hasSize(1)
        dir.names = listOf("cover.webp")
        songPath = "/music/Third/01.flac"; idle()
        assertThat(engines).hasSize(2)
        assertThat(engines.last().cover.kind).isEqualTo(AnimatedCoverKind.WEBP)
    }

    @Test
    fun `leaving the composition releases the decoder`() {
        var shown by mutableStateOf(true)
        compose.setContent {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            CompositionLocalProvider(
                LocalAnimatedCoverEnv provides AnimatedCoverEnv(finder, true, 0L),
                LocalAnimatedCoverEngines provides { _, _ -> AnimatedCoverEngineFactory { cover, sizePx, _ -> Engine(cover, View(ctx), sizePx).also { engines += it } } },
            ) {
                if (shown) {
                    val host = rememberAnimatedCoverHost("/music/Gaia/01.flac", { true }, true, true)
                    Box(Modifier.size(200.dp).onSizeChanged { host?.onSize(it.width) }) { AnimatedCoverLayer(host) }
                }
            }
        }
        compose.waitForIdle()
        val e = engines.single()
        shown = false
        compose.waitForIdle()
        assertThat(e.released).isTrue()
    }

    @Test
    fun `without the environment there is no animated cover at all`() {
        compose.setContent {
            val host = rememberAnimatedCoverHost("/music/Gaia/01.flac", { true }, true, true)
            assertThat(host).isNull()
        }
        compose.waitForIdle()
        assertThat(engines).isEmpty()
    }

    @Test
    fun `sheets hold the presence for as long as they are composed`() {
        var open by mutableStateOf(false)
        compose.setContent { SheetPresence.Hold(open); SheetPresence.Hold(open) }
        val base = SheetPresence.holds
        open = true; compose.waitForIdle()
        assertThat(SheetPresence.holds).isEqualTo(base + 2)
        assertThat(SheetPresence.anyOpen).isTrue()
        open = false; compose.waitForIdle()
        assertThat(SheetPresence.holds).isEqualTo(base)
    }
}
