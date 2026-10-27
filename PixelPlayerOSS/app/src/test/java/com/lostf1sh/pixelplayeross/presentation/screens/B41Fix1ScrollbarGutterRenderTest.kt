package com.lostf1sh.pixelplayeross.presentation.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.presentation.components.FileExplorerContent
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DirectoryEntry
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B41-fix1 (B9): the real folders page ("Carpetas en la biblioteca") and the real Songs list, drawn at the HONOR's width with enough rows to
 * scroll (so the scrollbar is there), and the margins of their cards measured from the picture: the cards of both must start and end the same
 * distance from the screen's edges. On the HONOR before this batch they started 18 dp from the left and ended 42 dp from the right.
 *
 * It compiles against the tree as it was before the batch (it only uses what B41 had), so the "before" pictures are made by running this same
 * test there: `B9_STAGE=before B41_RENDERS=.../B41-fix1-evidence`. The pictures are saved before the margins are asserted.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class B41Fix1ScrollbarGutterRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val stage = System.getenv("B9_STAGE") ?: "after"
    private val density = 3f

    private fun song(i: Int) = Song(
        id = "id-$i", title = "Cancion $i", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$i.flac",
        contentUriString = "content://$i", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val songs = (0 until 400).map(::song)
    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.stablePlayerState } returns MutableStateFlow(StablePlayerState(currentSong = songs[3], isPlaying = false))
        every { it.scrollToIndexEvent } returns MutableSharedFlow()
        every { it.libraryLocateRequest } returns MutableStateFlow(null)
        every { it.locatedSongId } returns MutableStateFlow(null)
    }

    /** The first and the last column (in dp from the left, and from the right edge) where a row of the picture differs from the page's background. */
    private fun margins(bitmap: Bitmap, y: Int): Pair<Float, Float> {
        val bg = bitmap.getPixel(1, y)
        fun differs(x: Int): Boolean {
            val p = bitmap.getPixel(x, y)
            return kotlin.math.abs(((p shr 16) and 0xFF) - ((bg shr 16) and 0xFF)) > 4 ||
                kotlin.math.abs(((p shr 8) and 0xFF) - ((bg shr 8) and 0xFF)) > 4 ||
                kotlin.math.abs((p and 0xFF) - (bg and 0xFF)) > 4
        }
        var first = 0
        while (first < bitmap.width && !differs(first)) first++
        var last = bitmap.width - 1
        while (last > 0 && !differs(last)) last--
        return first / density to (bitmap.width - 1 - last) / density
    }

    private fun picture(name: String): Bitmap {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        B41Renders.save(compose, "b9-$name-$stage")
        return bitmap
    }

    @Test
    fun the_folders_page_has_the_same_margin_on_both_sides() {
        val root = File("/storage/emulated/0")
        val entries = (0 until 30).map { DirectoryEntry(File(root, "Carpeta $it"), 10 + it, 10 + it, "/storage/emulated/0/Carpeta $it") }
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                FileExplorerContent(
                    currentPath = root,
                    directoryChildren = persistentListOf<DirectoryEntry>().addAll(entries),
                    availableStorages = persistentListOf(),
                    selectedStorageIndex = 0,
                    isLoading = false,
                    isPriming = false,
                    isReady = true,
                    isCurrentDirectoryResolved = true,
                    isAtRoot = true,
                    rootDirectory = root,
                    onNavigateTo = {},
                    onNavigateUp = {},
                    onNavigateHome = {},
                    onToggleAllowed = {},
                    onRefresh = {},
                    onStorageSelected = {},
                    onDone = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        val bitmap = picture("folders")
        // a line through the middle of the first card
        val y = compose.onNodeWithText("Carpeta 0").fetchSemanticsNode().boundsInRoot.center.y.toInt()
        val (left, right) = margins(bitmap, y)
        println("B41-fix1 B9 [$stage] folders page: cards start ${"%.1f".format(left)} dp from the left edge and end ${"%.1f".format(right)} dp from the right edge")
        assertThat(right).isWithin(1.5f).of(left)
    }

    @Test
    fun the_songs_list_has_the_same_margin_on_both_sides() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
              Box(Modifier.fillMaxSize().background(Dap.background)) {
                val items = flowOf(PagingData.from(songs)).collectAsLazyPagingItems()
                LibrarySongsTab(
                    songs = items,
                    isLoading = false,
                    playerViewModel = playerViewModel,
                    bottomBarHeight = 0.dp,
                    onMoreOptionsClick = {},
                    isRefreshing = false,
                    onRefresh = {},
                    sortOption = SortOption.SongTitleAZ,
                    onLocateCurrentSongVisibilityChanged = {},
                    onRegisterLocateCurrentSongAction = {},
                    hasCurrentSong = true,
                )
              }
            }
        }
        compose.waitForIdle()
        val bitmap = picture("songs")
        // a line through the row of the song that plays: the one row drawn as a card
        val y = compose.onNodeWithText("Cancion 3").fetchSemanticsNode().boundsInRoot.center.y.toInt()
        val (left, right) = margins(bitmap, y)
        println("B41-fix1 B9 [$stage] songs list: rows start ${"%.1f".format(left)} dp from the left edge and end ${"%.1f".format(right)} dp from the right edge")
        assertThat(right).isWithin(1.5f).of(left)
    }
}
