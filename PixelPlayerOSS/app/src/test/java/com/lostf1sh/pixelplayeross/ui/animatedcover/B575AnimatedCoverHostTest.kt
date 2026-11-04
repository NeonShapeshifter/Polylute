package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import timber.log.Timber

/**
 * B57.5 2.2, the host (the glue between the player and the controller) in a real composition: a song change to another album never makes a decoder for the previous album's cover, skipping inside
 * an album never touches the decoder, the policy's inputs reach the event lines, and the engine's view moves between carousel pages without a crash.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class B575AnimatedCoverHostTest {
    @get:Rule val compose = createComposeRule()

    /** Folders by path; a folder in [gate] blocks its listing until the test opens the latch (a lookup that is still running while the player already holds). */
    private class Dir : AnimatedCoverDirectory {
        val folders = HashMap<String, List<String>>()
        val gate = HashMap<String, CountDownLatch>()
        override fun names(dir: File): List<String>? {
            gate[dir.path]?.await(10, TimeUnit.SECONDS)
            return folders[dir.path]
        }
        override fun stamp(dir: File) = 1L
        override fun sizeOf(file: File) = 4_000L
        override fun modifiedOf(file: File) = 9L
    }

    private class Albums : AlbumFolders {
        val byAlbum = HashMap<Long, List<AlbumFolder>>()
        override fun foldersOf(albumId: Long) = byAlbum[albumId].orEmpty()
    }

    private class Engine(val cover: AnimatedCover, override val view: View) : AnimatedCoverEngine {
        override val firstFrame = MutableStateFlow(false)
        var playingNow = false
        var released = false
        override fun setRunning(running: Boolean) { this.playingNow = running }
        override fun release() { released = true }
    }

    private val io = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val engines = mutableListOf<Engine>()
    private val dir = Dir()
    private val albums = Albums()
    private val finder = AnimatedCoverFinder(io, dir, AlbumArtUtils::shouldTrustDirectoryArtwork, { 0L }, albums, {})

    private var playing by mutableStateOf(true)
    private var expanded by mutableStateOf(true)
    private var onCoverPage by mutableStateOf(true)
    private var songPath by mutableStateOf("/m/Artist One/Album One/01.flac")
    private var albumId by mutableStateOf(7L)

    @After fun close() { io.close() }

    private fun show() {
        compose.setContent {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            CompositionLocalProvider(
                LocalAnimatedCoverEnv provides AnimatedCoverEnv(finder, true, 0L),
                LocalAnimatedCoverEngines provides { _, _ -> AnimatedCoverEngineFactory { cover, _, _ -> Engine(cover, View(ctx)).also { engines += it } } },
            ) {
                val host = rememberAnimatedCoverHost(songPath, { playing }, expanded, onCoverPage, albumId)
                Box(Modifier.size(200.dp).onSizeChanged { host?.onSize(it.width) }) { AnimatedCoverLayer(host) }
            }
        }
        compose.waitForIdle()
    }

    /** Lets the lookups running on the IO threads finish and the composition take their answer. */
    private fun settle() {
        repeat(20) {
            Thread.sleep(25)
            compose.waitForIdle()
        }
    }

    private fun library() {
        dir.folders["/m/Artist One/Album One"] = listOf("cover.mp4")
        dir.folders["/m/Artist Two/Album Two"] = listOf("cover.mp4")
        albums.byAlbum[7L] = listOf(AlbumFolder("/m/Artist One/Album One", 15))
        albums.byAlbum[8L] = listOf(AlbumFolder("/m/Artist Two/Album Two", 15))
    }

    // ── the cover belongs to its album ───────────────────────────────────────────────────────────────

    @Test
    fun `opening the player on a song of another album never makes a decoder for the album it came from`() {
        library()
        // the player is parked on an Album One song (its cover was found) ...
        expanded = false
        show(); settle()
        assertThat(engines).isEmpty()
        // ... and is opened on a Album Two song at the same moment (un-parked: the song and the sheet flow in together) while that album's lookup is still running
        val gate = CountDownLatch(1)
        dir.gate["/m/Artist Two/Album Two"] = gate
        songPath = "/m/Artist Two/Album Two/01.flac"; albumId = 8L; expanded = true
        compose.waitForIdle(); Thread.sleep(150); compose.waitForIdle()
        // nothing is made for Album One's cover while Album Two's is being looked for
        assertThat(engines).isEmpty()
        gate.countDown(); settle()
        assertThat(engines.map { it.cover.file.path }).containsExactly("/m/Artist Two/Album Two/cover.mp4")
        assertThat(engines.single().released).isFalse()
        assertThat(engines.single().playingNow).isTrue()
    }

    @Test
    fun `a song change to another album releases the old cover at once and plays the new one when it is found`() {
        library()
        show(); settle()
        val albumOne = engines.single()
        assertThat(albumOne.playingNow).isTrue()
        val gate = CountDownLatch(1)
        dir.gate["/m/Artist Two/Album Two"] = gate
        songPath = "/m/Artist Two/Album Two/01.flac"; albumId = 8L
        compose.waitForIdle(); Thread.sleep(150); compose.waitForIdle()
        // the old album's cover is not the new song's: it is let go now, not when the lookup returns
        assertThat(albumOne.released).isTrue()
        assertThat(engines).hasSize(1)
        gate.countDown(); settle()
        assertThat(engines).hasSize(2)
        assertThat(engines.last().cover.file.path).isEqualTo("/m/Artist Two/Album Two/cover.mp4")
        assertThat(engines.last().playingNow).isTrue()
    }

    @Test
    fun `skipping inside an album never touches the decoder`() {
        library()
        dir.folders["/m/Artist Two/Album Two"] = listOf("cover.mp4")
        songPath = "/m/Artist Two/Album Two/01.flac"; albumId = 8L
        show(); settle()
        val first = engines.single()
        for (n in 2..15) {
            songPath = "/m/Artist Two/Album Two/%02d.flac".format(n)
            compose.waitForIdle(); settle()
        }
        assertThat(engines).hasSize(1)
        assertThat(first.released).isFalse()
        assertThat(first.playingNow).isTrue()
    }

    @Test
    fun `a song of the same album in another folder keeps the album's decoder`() {
        dir.folders["/m/Artist One/Album One"] = listOf("cover.mp4")
        albums.byAlbum[7L] = listOf(AlbumFolder("/m/Artist One/Album One", 11), AlbumFolder("/m/Loose", 4))
        show(); settle()
        val first = engines.single()
        songPath = "/m/Loose/Loose 1.flac"
        compose.waitForIdle(); settle()
        assertThat(engines).hasSize(1)
        assertThat(first.released).isFalse()
        assertThat(engines.single().cover.file.path).isEqualTo("/m/Artist One/Album One/cover.mp4")
    }

    // ── a pager settle that blinks the cover page ────────────────────────────────────────────────────

    @Test
    fun `a cover page that blinks for a frame costs a decoder and the cover ends up playing`() {
        library()
        show(); settle()
        onCoverPage = false; compose.waitForIdle()
        onCoverPage = true; compose.waitForIdle(); settle()
        assertThat(engines.last().released).isFalse()
        assertThat(engines.last().playingNow).isTrue()
        assertThat(engines.count { !it.released }).isEqualTo(1)
    }

    // ── the playing flag ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the playing flag is followed when it is snapshot state, which is what the player hands in`() {
        library()
        show(); settle()
        val e = engines.single()
        playing = false; compose.waitForIdle(); assertThat(e.playingNow).isFalse()
        playing = true; compose.waitForIdle(); assertThat(e.playingNow).isTrue()
    }

    // ── the inputs reach the log ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the make and the release lines carry the inputs the policy had at that moment`() {
        library()
        val lines = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { if (tag == "AnimatedCover") synchronized(lines) { lines += message } }
        }
        Timber.plant(tree)
        try {
            show(); settle()
            onCoverPage = false; compose.waitForIdle()
            val seen = synchronized(lines) { lines.toList() }
            assertThat(seen.first { it.startsWith("make ") }).contains("coverPageShowing=true")
            assertThat(seen.first { it.startsWith("make ") }).contains("playing=true")
            val release = seen.first { it.startsWith("release ") }
            assertThat(release).contains("coverPageShowing=false")
            assertThat(release).contains("why=\"no longer held")
        } finally {
            Timber.uproot(tree)
        }
    }

    // ── the engine's view between carousel pages ─────────────────────────────────────────────────────

    @Test
    fun `the engine's view moves from one carousel page to another in one recomposition, forwards and backwards, without a crash`() {
        library()
        var page by mutableStateOf(0)
        compose.setContent {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            CompositionLocalProvider(
                LocalAnimatedCoverEnv provides AnimatedCoverEnv(finder, true, 0L),
                LocalAnimatedCoverEngines provides { _, _ -> AnimatedCoverEngineFactory { cover, _, _ -> Engine(cover, FrameLayout(ctx)).also { engines += it } } },
            ) {
                val host = rememberAnimatedCoverHost(songPath, { true }, true, true, albumId)
                // two pages of a carousel; only the current one draws the cover (the page the song is on)
                Box(Modifier.size(100.dp).onSizeChanged { if (page == 0) host?.onSize(it.width) }) { if (page == 0) AnimatedCoverLayer(host) }
                Box(Modifier.size(100.dp).onSizeChanged { if (page == 1) host?.onSize(it.width) }) { if (page == 1) AnimatedCoverLayer(host) }
            }
        }
        compose.waitForIdle(); settle()
        val view = engines.single().view
        repeat(3) {
            page = 1; compose.waitForIdle()
            assertThat(view.parent).isNotNull()
            page = 0; compose.waitForIdle()
            assertThat(view.parent).isNotNull()
        }
        assertThat(engines).hasSize(1)
    }
}
