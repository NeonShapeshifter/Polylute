package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreCategoriesGrid
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import com.lostf1sh.pixelplayeross.ui.ds.*
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B48-2: a genre's picture slot (grid tile, list row, the genre page's header) shows its own image when it has one and loads, else its custom icon, else its letter, and never the
 * music-note placeholder. 360 x 805 dp, xhdpi. The renders go to `B48-evidence/{before,after}` (before = this test run in the HEAD copy, whose copy of the tree is named B48-before).
 * Written against names that exist at HEAD (text, pixels, tag strings) so the same file runs there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B48GenreArtTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val image = File(context.filesDir, "b48-genre-art-fixture.png")

    @After fun cleanup() { image.delete() }

    private fun makeImage(): String {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xffe040a0.toInt())
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return image.absolutePath
    }

    private fun save(name: String): Bitmap {
        val bitmap = com.lostf1sh.pixelplayeross.ui.glass.B42Renders.screen(compose)
        val phase = if (File(".").canonicalPath.contains("B48-before")) "before" else "after"
        val folder = File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B48-evidence/$phase").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun pink(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) > 150 && android.graphics.Color.blue(pixel) > 90 && android.graphics.Color.green(pixel) < 100) count++
        }
        return count
    }

    /** Renders [name] until the image has loaded (Coil is asynchronous) or the wait is over; returns the last render. */
    private fun renderWaitingForImage(name: String, expectImage: Boolean): Bitmap {
        var bitmap = save(name)
        if (expectImage) repeat(25) {
            if (pink(bitmap) < 150) {
                Thread.sleep(100)
                compose.waitForIdle()
                bitmap = save(name)
            }
        }
        return bitmap
    }

    private fun player(grid: Boolean, icons: Map<String, Int> = emptyMap()): PlayerViewModel {
        val player = mockk<PlayerViewModel>(relaxed = true)
        every { player.isGenreGridView } returns MutableStateFlow(grid)
        every { player.navBarCompactMode } returns MutableStateFlow(false)
        every { player.customGenreIcons } returns MutableStateFlow(icons)
        return player
    }

    private fun showGenres(genres: List<Genre>, grid: Boolean, icons: Map<String, Int> = emptyMap()) {
        val player = player(grid, icons)
        compose.setContent {
            DapTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = Dap.background) {
                    Box(Modifier.fillMaxSize().padding(top = 37.dp, bottom = 22.dp)) {
                        GenreCategoriesGrid(persistentListOf(*genres.toTypedArray()), {}, player)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun genres(withImage: String?): List<Genre> = listOf(
        Genre("rock", "Rock", songCount = 15, imagePath = withImage),
        Genre("jazz", "Jazz", songCount = 7),
        Genre("pop", "Pop", songCount = 12),
    )

    // ── the grid ────────────────────────────────────────────────────────────────────────────────

    @Test fun `grid tiles show the chosen image cropped, and the letter where there is none`() {
        showGenres(genres(makeImage()), grid = true)
        val bitmap = renderWaitingForImage("genre-grid-with-image", expectImage = true)
        assertThat(pink(bitmap)).isGreaterThan(1_000)                 // the Rock tile is the image
        compose.onNodeWithText("J").assertIsDisplayed()                // no image: the letter
        compose.onNodeWithText("P").assertIsDisplayed()
    }

    @Test fun `grid tiles without any image are letters`() {
        showGenres(genres(null), grid = true)
        val bitmap = save("genre-grid-no-image")
        assertThat(pink(bitmap)).isEqualTo(0)
        for (letter in listOf("R", "J", "P")) compose.onNodeWithText(letter).assertIsDisplayed()
    }

    @Test fun `a grid tile with a custom icon shows the icon and not the letter`() {
        showGenres(genres(null), grid = true, icons = mapOf("jazz" to R.drawable.ic_music_placeholder))
        save("genre-grid-custom-icon")
        compose.onNodeWithTag("genre-art-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("J").assertCountEquals(0)
        compose.onNodeWithText("R").assertIsDisplayed()
    }

    // ── the list ────────────────────────────────────────────────────────────────────────────────

    @Test fun `list rows show the chosen image and the letter where there is none`() {
        showGenres(genres(makeImage()), grid = false)
        val bitmap = renderWaitingForImage("genre-list-with-image", expectImage = true)
        assertThat(pink(bitmap)).isGreaterThan(150)
        compose.onNodeWithText("J").assertIsDisplayed()
        compose.onNodeWithText("P").assertIsDisplayed()
    }

    @Test fun `list rows without any image are letters, not the music note`() {
        showGenres(genres(null), grid = false)
        val bitmap = save("genre-list-no-image")
        assertThat(pink(bitmap)).isEqualTo(0)
        for (letter in listOf("R", "J", "P")) compose.onNodeWithText(letter).assertIsDisplayed()
    }

    @Test fun `a list row with a custom icon shows the icon and not the letter`() {
        showGenres(genres(null), grid = false, icons = mapOf("jazz" to R.drawable.ic_music_placeholder))
        save("genre-list-custom-icon")
        compose.onNodeWithTag("genre-art-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("J").assertCountEquals(0)
    }

    // ── the genre page's header ─────────────────────────────────────────────────────────────────

    private fun showHeader(genre: Genre) {
        val song = Song.emptySong().copy(id = "1", title = "Example song", artist = "Example artist")
        compose.setContent {
            DapTheme(darkTheme = true) {
                CompositionLocalProvider(
                    com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation provides
                        com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority(androidx.activity.OnBackPressedDispatcher())
                ) {
                    GenrePage(genre.name, GenreDetailUiState(genre = genre, songs = listOf(song)), null, false, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `the header shows the chosen image`() {
        showHeader(Genre("rock", "Rock", songCount = 15, imagePath = makeImage()))
        val bitmap = renderWaitingForImage("genre-header-with-image", expectImage = true)
        assertThat(pink(bitmap)).isGreaterThan(1_000)
    }

    @Test fun `the header without an image is the title alone, as it was before the custom images`() {
        showHeader(Genre("rock", "Rock", songCount = 15))
        val bitmap = save("genre-header-no-image")
        assertThat(pink(bitmap)).isEqualTo(0)
        compose.onNodeWithText("Rock").assertIsDisplayed()
        compose.onAllNodesWithTag("genre-art-letter", useUnmergedTree = true).assertCountEquals(0)      // no cover slot: nothing to put a letter in
    }

    @Test fun `a header whose image file is gone shows the letter, never the music note`() {
        showHeader(Genre("rock", "Rock", songCount = 15, imagePath = File(context.filesDir, "deleted-genre-art.jpg").absolutePath))
        Thread.sleep(500)
        compose.waitForIdle()
        val bitmap = save("genre-header-broken-image")
        assertThat(pink(bitmap)).isEqualTo(0)
        compose.onNodeWithTag("genre-art-letter", useUnmergedTree = true).assertIsDisplayed()
    }
}
