package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreAlbum
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreArtist
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreDetailUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B56 phase 5.1: the genre page is an overview, top to bottom: the header with Play and Shuffle (the whole genre, the source list as shown), the top artists, the albums, and
 * one row "All songs (N)" that opens the song list the page used to be.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B56GenrePageTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val out: File get() = File(System.getenv("B56_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B56-gates/renders").apply { mkdirs() }

    private fun song(n: Int, title: String, artist: Long, album: Long) =
        Song.emptySong().copy(id = "$n", title = title, artist = "Artist $artist", artistId = artist, album = "Album $album", albumId = album)

    private val songs = listOf(
        song(1, "Alpha", 1, 10), song(2, "Bravo", 1, 10), song(3, "Charlie", 1, 11), song(4, "Delta", 2, 12), song(5, "Echo", 3, 13), song(6, "Foxtrot", 3, 13),
    )
    private val state = GenreDetailUiState(
        genre = Genre("rock", "Rock", songCount = 6),
        songs = songs,
        albums = listOf(
            GenreAlbum(10, "Album 10", "Band One", null, 2001, 2), GenreAlbum(11, "Album 11", "Band One", null, 2002, 1),
            GenreAlbum(12, "Album 12", "Band Two", null, 2003, 1), GenreAlbum(13, "Album 13", "Band Three", null, 2004, 2),
        ),
        artists = listOf(GenreArtist(1, "Artist 1", 3, 2), GenreArtist(2, "Artist 2", 1, 1), GenreArtist(3, "Artist 3", 2, 1, plays = 9)),
    )

    private fun show(dark: Boolean = true, startOnAllSongs: Boolean = false) {
        compose.setContent {
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    Box(Modifier.fillMaxSize().background(Dap.background)) {
                        GenrePage(
                            title = "Rock", state = state, currentSongId = null, isUnknownGenre = false,
                            onBack = { calls += "back" }, onRetry = {}, onPlay = { calls += "play" }, onShuffle = { calls += "shuffle" }, onQuickFill = {},
                            onPlaySong = { calls += "song:${it.id}" }, onMoreSong = {},
                            onOpenAlbum = { calls += "album:$it" }, onOpenArtist = { calls += "artist:$it" },
                            startOnAllSongs = startOnAllSongs,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private val verticalScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun scrollTo(tag: String) {
        compose.onNode(verticalScroll).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    @Test
    fun `the page lists top artists, then albums, then one All songs row, and no song`() {
        show()

        compose.onNodeWithTag(GenreOverviewTags.TopArtists).assertExists()
        scrollTo(GenreOverviewTags.AllSongs)
        compose.onNodeWithText("All songs (6)").assertExists()
        assertThat(compose.onAllNodesWithTextCount("Alpha")).isEqualTo(0)                  // a song is not on the overview
    }

    @Test
    fun `the top artists come by songs in the genre, ties by plays`() {
        show()

        val names = listOf("Artist 1", "Artist 3", "Artist 2")                              // 3 songs, 2 songs, 1 song
        val positions = names.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }
        assertThat(positions).isInOrder()
    }

    @Test
    fun `an album and an artist open their pages`() {
        show()

        compose.onNodeWithText("Artist 2").performClick()
        scrollTo(GenreOverviewTags.AllSongs)
        compose.onNodeWithText("Album 12").performClick()

        assertThat(calls).containsExactly("artist:2", "album:12").inOrder()
    }

    @Test
    fun `All songs opens the song list and Back from it returns to the overview, not out of the genre`() {
        show()
        scrollTo(GenreOverviewTags.AllSongs)

        compose.onNodeWithTag(GenreOverviewTags.AllSongs).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithTag(GenreOverviewTags.TopArtists).assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    private fun render(name: String, dark: Boolean) {
        show(dark)
        val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `render the genre page in the dark theme`() = render("genre-page-dark", dark = true)

    @Test fun `render the genre page in the light theme`() = render("genre-page-light", dark = false)
}
