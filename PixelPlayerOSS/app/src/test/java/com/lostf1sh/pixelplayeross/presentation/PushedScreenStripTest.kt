package com.lostf1sh.pixelplayeross.presentation

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.GenrePage
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreAlbum
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreBrowse
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreDetailUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SourceOrder
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B29-L9: the strip between the shell's mini player and the gesture bar, on every pushed screen.
 *
 * B28-fix2 stopped rows from scrolling through it on Stats and a collection page, one list at a time; Album, Artist, Playlist and Genre
 * (and every other screen on `DapBackScaffold`) had the same hole. The inset is now the scaffold's. A made-up shell (a 24 dp navigation
 * bar and an opaque 64 dp mini player flush above it, as in B28-fix2), not a screenshot of the phone; the strip is **read from the
 * pixels**: with the list scrolled to where rows are under the mini player, nothing but the page's own background may be drawn in it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
class PushedScreenStripTest {

    @get:Rule
    val compose = createComposeRule()

    private val navigationBar = 24.dp
    private val miniPlayer = 64.dp
    private var hostView: android.view.View? = null
    private var backgroundArgb = 0

    @Composable
    private fun Shell(body: @Composable () -> Unit) {
        val view = LocalView.current
        SideEffect { hostView = view }
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            // `Dap.background` is read inside the theme: it is the palette's, not a constant
            val background = Dap.background.toArgb()
            SideEffect { backgroundArgb = background }
            CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    CompositionLocalProvider(LocalDapPlayerShelfHeight provides miniPlayer) { body() }
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = navigationBar).fillMaxWidth().height(miniPlayer).background(Color(0xFF2B312E)),
                        contentAlignment = Alignment.Center,
                    ) { Text("Mini player", color = Color.White) }
                }
            }
        }
    }

    private fun show(body: @Composable () -> Unit) {
        compose.setContent { Shell(body) }
        compose.waitForIdle()
        // a navigation bar of 24 dp, handed over the way the system does, once the screen is listening for it
        val navPx = with(compose.density) { navigationBar.roundToPx() }
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                hostView!!,
                WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx)).build(),
            )
        }
        compose.waitForIdle()
    }

    /** Whether any pixel of the strip (the navigation bar's height at the bottom of the window) differs from the page's background. */
    private fun stripIsClear(): Boolean {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val navPx = with(compose.density) { navigationBar.roundToPx() }
        val background = backgroundArgb
        for (y in bitmap.height - navPx until bitmap.height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) != background) return false
        }
        return true
    }

    // ── a plain list on the scaffold stands for Album, Artist and Playlist: they are lists on the same scaffold ─────

    @Composable
    private fun SolidRows() {
        DapBackScaffold(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                // rows with no air between them, so wherever the list ends a row is under the strip if the viewport reaches it
                items((1..60).toList()) { n ->
                    Box(Modifier.fillMaxWidth().height(56.dp).background(if (n % 2 == 0) Color(0xFF3A4440) else Color(0xFF4A5450))) { Text("Row $n") }
                }
            }
        }
    }

    @Test
    fun `rows scrolled under the mini player never show in the strip above the gesture bar`() {
        show { SolidRows() }
        compose.onNode(hasScrollAction()).performScrollToIndex(30)
        compose.waitForIdle()
        B29Renders.save(compose, "L9-scaffold-rows-under-mini-player-es")
        assertThat(stripIsClear()).isTrue()
    }

    @Test
    fun `a list with air at its end rests where it did, the mini player's height above the viewport's end`() {
        show {
            DapBackScaffold(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = LocalDapPlayerShelfHeight.current + 24.dp),
                ) {
                    items((1..40).toList()) { n -> Box(Modifier.fillMaxWidth().height(56.dp)) { Text("Row $n") } }
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToIndex(39)
        compose.waitForIdle()
        // the last row ends 64 + 24 dp above the viewport's end (the strip is the viewport's own end now), clear of the mini player
        val last = compose.onNodeWithText("Row 40").fetchSemanticsNode().boundsInRoot
        val miniTop = with(compose.density) { (805.dp - navigationBar - miniPlayer).toPx() }
        assertThat(last.bottom).isAtMost(miniTop)
    }

    // ── the real pages ───────────────────────────────────────────────────────────────────────────────

    private fun song(id: Int, album: Long) = Song.emptySong().copy(
        id = "$id", libraryItemId = "item-$id", title = "Song $id", artist = "Artist ${id % 7}", artistId = (id % 7).toLong(),
        album = "Album $album", albumId = album, trackNumber = id, duration = 200_000L, genre = "Rock",
    )

    @Test
    fun `the genre page's songs scrolled under the mini player leave the strip clear`() {
        val songs = (1..80).map { song(it, (it % 9).toLong()) }
        val state = GenreDetailUiState(
            songs = SourceOrder.genrePage(songs), albums = GenreBrowse.albums(songs), artists = GenreBrowse.artists(songs),
        )
        show {
            GenrePage(
                title = "Rock", state = state, currentSongId = null, isUnknownGenre = false, onBack = {}, onRetry = {}, onPlay = {}, onShuffle = {},
                onQuickFill = {}, onPlaySong = {}, onMoreSong = {},
            )
        }
        // the page's list is asked for by what it is: the one that scrolls to an index
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(40)
        compose.waitForIdle()
        B29Renders.save(compose, "L9-genre-under-mini-player-es")
        assertThat(stripIsClear()).isTrue()
    }
}
